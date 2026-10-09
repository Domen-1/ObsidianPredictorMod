package ru.domen.obsidianpredictor.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/obsidianpredictor-server.json; missing keys keep their defaults. */
public final class ServerConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("obsidianpredictor-server.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Optional fixed cap on the area per job, in chunks; 0 = limited only by memory. */
    public int maxChunksPerJob = 0;
    /** Share of the free heap (percent) one job may use for its copy of the area. */
    public int memoryPercent = 50;
    /** Jobs that may exist at the same time. */
    public int maxJobs = 4;
    /** New chunk reads started per tick, per job. */
    public int chunkReadsPerTick = 8;
    /** Chunk reads in flight per job. */
    public int maxPendingReads = 32;
    /** Only operators may start jobs. Stopping someone else's job always needs operator. */
    public boolean opsOnly = false;
    /** While blocks keep changing, recompute at most this often (seconds). 0 = only after changes stop. */
    public int liveUpdateSeconds = 10;
    /** Stop a job after nobody has been subscribed to it for this many minutes. 0 = never. */
    public int idleStopMinutes = 60;

    private static ServerConfig instance = new ServerConfig();

    public static ServerConfig get() {
        return instance;
    }

    public static void load() {
        ServerConfig config = new ServerConfig();
        try {
            if (Files.exists(PATH)) {
                JsonObject raw = JsonParser.parseString(Files.readString(PATH)).getAsJsonObject();
                ServerConfig loaded = GSON.fromJson(raw, ServerConfig.class);
                if (loaded != null) {
                    config = loaded;
                    // 0.1.0 wrote a fixed 6400-chunk cap as the default; areas are now limited by memory
                    if (!raw.has("memoryPercent") && config.maxChunksPerJob == 6400) {
                        config.maxChunksPerJob = 0;
                    }
                }
            }
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(config));
        } catch (IOException | RuntimeException e) {
            ObsidianPredictorMod.LOGGER.error("Cannot load {}, using defaults", PATH, e);
        }
        config.maxChunksPerJob = Math.max(0, config.maxChunksPerJob);
        config.memoryPercent = Math.clamp(config.memoryPercent, 5, 90);
        config.maxJobs = Math.max(1, config.maxJobs);
        config.chunkReadsPerTick = Math.max(1, config.chunkReadsPerTick);
        config.maxPendingReads = Math.max(1, config.maxPendingReads);
        config.liveUpdateSeconds = Math.max(0, config.liveUpdateSeconds);
        config.idleStopMinutes = Math.max(0, config.idleStopMinutes);
        instance = config;
    }
}
