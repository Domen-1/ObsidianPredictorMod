package ru.domen.obsidianpredictor.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.xpple.clientarguments.arguments.CBlockPosArgument;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;

/** /obsidian ... — everything the screen does, from chat. */
public final class PredictorCommands {
    private PredictorCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("obsidian")
                .executes(ctx -> {
                    ObsidianPredictorClient.openScreenNextTick = true;
                    return 1;
                })
                .then(ClientCommands.literal("pos1")
                        .executes(ctx -> corner(ctx, true, null))
                        .then(ClientCommands.argument("pos", CBlockPosArgument.blockPos())
                                .executes(ctx -> corner(ctx, true, CBlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(ClientCommands.literal("pos2")
                        .executes(ctx -> corner(ctx, false, null))
                        .then(ClientCommands.argument("pos", CBlockPosArgument.blockPos())
                                .executes(ctx -> corner(ctx, false, CBlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(ClientCommands.literal("area")
                        .then(ClientCommands.argument("from", CBlockPosArgument.blockPos())
                                .then(ClientCommands.argument("to", CBlockPosArgument.blockPos())
                                        .executes(ctx -> {
                                            ClientSession.corner1 = CBlockPosArgument.getBlockPos(ctx, "from");
                                            ClientSession.corner2 = CBlockPosArgument.getBlockPos(ctx, "to");
                                            describeSelection();
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("start")
                        .executes(ctx -> {
                            ClientSession.startLocal();
                            return 1;
                        })
                        .then(ClientCommands.literal("server")
                                .executes(ctx -> {
                                    ClientSession.startOnServer("");
                                    return 1;
                                })
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ClientSession.startOnServer(StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        }))))
                .then(ClientCommands.literal("stop")
                        .executes(ctx -> {
                            ClientSession.stopAll(false);
                            ClientSession.message("Stopped. Markers stay until /obsidian clear.");
                            return 1;
                        }))
                .then(ClientCommands.literal("clear")
                        .executes(ctx -> {
                            ClientSession.stopAll(true);
                            ClientSession.corner1 = null;
                            ClientSession.corner2 = null;
                            return 1;
                        }))
                .then(ClientCommands.literal("export")
                        .executes(ctx -> {
                            ClientSession.exportWaypoints();
                            return 1;
                        }))
                .then(ClientCommands.literal("jobs")
                        .executes(ctx -> {
                            ClientSession.requestJobList(true);
                            return 1;
                        }))
                .then(ClientCommands.literal("join")
                        .then(ClientCommands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    ClientSession.follow(LongArgumentType.getLong(ctx, "id"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("kill")
                        .then(ClientCommands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    ClientSession.stopServerJob(LongArgumentType.getLong(ctx, "id"));
                                    return 1;
                                }))));
    }

    private static int corner(CommandContext<FabricClientCommandSource> ctx, boolean first, BlockPos pos) {
        BlockPos value = pos != null ? pos : ctx.getSource().getPlayer().blockPosition();
        if (first) ClientSession.corner1 = value;
        else ClientSession.corner2 = value;
        ClientSession.message((first ? "Corner 1: " : "Corner 2: ") + value.toShortString());
        describeSelection();
        return 1;
    }

    private static void describeSelection() {
        BlockPos[] sel = ClientSession.selection();
        if (sel != null) {
            ClientSession.message("Area " + sel[0].toShortString() + " .. " + sel[1].toShortString() + ", "
                    + ClientSession.selectionChunks() + " chunks. /obsidian start or /obsidian start server <name>");
        }
    }
}
