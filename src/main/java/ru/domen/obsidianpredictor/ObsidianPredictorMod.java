package ru.domen.obsidianpredictor;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.server.ServerCommands;
import ru.domen.obsidianpredictor.server.ServerConfig;
import ru.domen.obsidianpredictor.server.ServerJobManager;

public class ObsidianPredictorMod implements ModInitializer {
    public static final String MOD_ID = "obsidianpredictor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override
    public void onInitialize() {
        Payloads.register();
        ServerConfig.load();
        ServerJobManager.registerNetworking();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ServerCommands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(ServerJobManager::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerJobManager.onPlayerLeft(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerJobManager.clear());
    }
}
