package ru.domen.obsidianpredictor.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.PredictionJob;

import java.nio.file.Files;
import java.nio.file.Path;

/** Operator/console commands. Players normally use the client UI instead. */
public final class ServerCommands {
    private ServerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("obsidianpredictor")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("list").executes(ServerCommands::list))
                .then(Commands.literal("start")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(ServerCommands::start)))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    long id = LongArgumentType.getLong(ctx, "id");
                                    if (!ServerJobManager.stop(id, "stopped by " + ctx.getSource().getTextName())) {
                                        ctx.getSource().sendFailure(Component.literal("No job #" + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("Stopped job #" + id), true);
                                    return 1;
                                })))
                .then(Commands.literal("waypoints")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ServerCommands::waypoints)))
                .then(Commands.literal("export")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ServerCommands::export))))));
    }

    private static Path outputDir(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer().getWorldPath(LevelResource.ROOT).resolve("obsidianpredictor");
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var jobs = ServerJobManager.list();
        if (jobs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No obsidian predictions running"), false);
        }
        for (Payloads.JobInfo info : jobs) {
            PredictionJob.Phase phase = PredictionJob.Phase.byId(info.phase());
            String line = "#" + info.id() + " '" + info.name() + "' by " + info.owner() + " " + info.min().toShortString()
                    + " .. " + info.max().toShortString() + " " + phase + " chunks " + info.capturedChunks() + "/"
                    + info.totalChunks() + " rev " + info.revision() + " lava " + info.lavaCount() + " blast "
                    + info.blastCount() + " subs " + info.subscribers() + " mem ~" + memoryMb(info.id()) + " MB"
                    + (info.message().isEmpty() ? "" : " (" + info.message() + ")");
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return jobs.size();
    }

    private static long memoryMb(long id) {
        ServerJob job = ServerJobManager.get(id);
        return job == null ? 0 : job.job.memoryBytesEstimate() / (1024 * 1024);
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        String error = ServerJobManager.start(source.getLevel(), source.getPlayer(), StringArgumentType.getString(ctx, "name"), from, to);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started obsidian prediction"), true);
        return 1;
    }

    private static int waypoints(CommandContext<CommandSourceStack> ctx) {
        long id = LongArgumentType.getLong(ctx, "id");
        ServerJob job = ServerJobManager.get(id);
        if (job == null || job.job.result() == null) {
            ctx.getSource().sendFailure(Component.literal(job == null ? "No job #" + id : "Job #" + id + " has no result yet"));
            return 0;
        }
        Path file = outputDir(ctx).resolve(job.name.replaceAll("[^\\p{L}\\p{N} _.-]", "_") + " waypoints.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, job.waypoints());
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Cannot write " + file + ": " + e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Saved " + job.waypoints().size() + " waypoints to " + file), false);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BlockPos a = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos b = BlockPosArgument.getBlockPos(ctx, "to");
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > LitematicExporter.MAX_BLOCKS) {
            source.sendFailure(Component.literal("Area too large to export: " + volume + " blocks"));
            return 0;
        }
        String problem = LitematicExporter.checkLoaded(source.getLevel(), min, max);
        if (problem != null) {
            source.sendFailure(Component.literal(problem));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Path file = outputDir(ctx).resolve(name + ".litematic");
        try {
            LitematicExporter.export(source.getLevel(), min, max, name, file);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Export failed: " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Exported " + volume + " blocks to " + file), false);
        return 1;
    }
}
