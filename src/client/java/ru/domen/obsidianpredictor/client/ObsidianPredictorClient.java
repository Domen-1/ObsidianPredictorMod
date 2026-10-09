package ru.domen.obsidianpredictor.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.BlockChangeHooks;

public class ObsidianPredictorClient implements ClientModInitializer {
    /** Chat commands close the chat screen right after running, so open screens a tick later. */
    static boolean openScreenNextTick;

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.obsidianpredictor.open",
                InputConstants.Type.KEYBOARD, InputConstants.KEY_O,
                new KeyMapping.Category(Identifier.fromNamespaceAndPath(ObsidianPredictorMod.MOD_ID, "main"))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> PredictorCommands.register(dispatcher));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                openScreenNextTick = true;
            }
            if (openScreenNextTick && mc.player != null && mc.gui.screen() == null) {
                openScreenNextTick = false;
                mc.setScreenAndShow(new PredictorScreen());
            }
            ClientSession.tick();
        });
        ClientChunkEvents.CHUNK_LOAD.register(ClientSession::onChunkLoad);
        BlockChangeHooks.clientListener = (level, pos, state) -> {
            // block updates arrive on the client thread; ignore anything else (e.g. integrated server)
            if (Minecraft.getInstance().isSameThread()) {
                ClientSession.onBlockChanged(level, pos, state);
            }
        };

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(ClientSession::reset));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Hello.TYPE, (payload, context) -> {
            ClientSession.serverModVersion = payload.modVersion();
            if (payload.protocolVersion() != Payloads.PROTOCOL_VERSION) {
                ClientSession.message("Server runs a different Obsidian Predictor version (" + payload.modVersion()
                        + " vs " + ObsidianPredictorMod.version() + "); shared predictions may not work.");
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.JobStatus.TYPE, (payload, context) -> ClientSession.onJobStatus(payload.info()));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.JobResult.TYPE, (payload, context) -> ClientSession.onJobResult(payload));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.JobClosed.TYPE, (payload, context) -> ClientSession.onJobClosed(payload));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.JobList.TYPE, (payload, context) -> ClientSession.onJobList(payload.jobs()));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ObsidianPredictorMod.MOD_ID, "status"), HudOverlay::render);
        LevelRenderEvents.END_MAIN.register(MarkerRenderer::render);
    }
}
