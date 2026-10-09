package ru.domen.obsidianpredictor.server;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;
import ru.domen.obsidianpredictor.core.Predictor;
import ru.domen.obsidianpredictor.mixin.ChunkMapInvoker;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.PredictionJob;
import ru.domen.obsidianpredictor.world.Worker;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A shared prediction on the server. Loaded chunks are copied directly; the rest are read from
 * disk (with data fixing) and parsed on the worker, so the main thread never loads chunks.
 */
public final class ServerJob implements PredictionJob.Listener {
    public final long id;
    public final String name;
    public final UUID owner;
    public final String ownerName;
    public final ServerLevel level;
    public final PredictionJob job;
    private final MinecraftServer server;

    private final Set<UUID> subscribers = new LinkedHashSet<>();
    private final LongArrayFIFOQueue toCapture = new LongArrayFIFOQueue();
    /** Chunks whose disk read is in flight. */
    private final LongOpenHashSet readingFromDisk = new LongOpenHashSet();
    /** Chunks that changed while their disk read was in flight: read again afterwards. */
    private final LongOpenHashSet stale = new LongOpenHashSet();
    private int statusCooldown;
    private long lastSubscribedMs = System.currentTimeMillis();
    private long[] resultLava = new long[0];
    private long[] resultBlast = new long[0];

    ServerJob(long id, String name, UUID owner, String ownerName, ServerLevel level, BlockPos min, BlockPos max) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.level = level;
        this.server = level.getServer();
        this.job = new PredictionJob(min, max, server, this);
        job.setMaxStaleMs(ServerConfig.get().liveUpdateSeconds * 1000L);
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                toCapture.enqueue(ChunkPos.pack(cx, cz));
            }
        }
    }

    public Payloads.JobInfo info() {
        Predictor.Result result = job.result();
        return new Payloads.JobInfo(id, name, ownerName, level.dimension().identifier().toString(), job.min, job.max,
                job.phase().ordinal(), job.capturedChunks(), job.totalChunks(), job.predictionPermille(),
                job.revision(), result == null ? 0 : result.lavaCount(), result == null ? 0 : result.blastResistantCount(),
                subscribers.size(), job.message());
    }

    public Set<UUID> subscribers() {
        return subscribers;
    }

    void tick() {
        if (job.isActive()) {
            startCaptures();
            job.tick();
        }
        if (--statusCooldown <= 0) {
            statusCooldown = 20;
            if (job.phase() == PredictionJob.Phase.CAPTURING || job.isPredicting()) {
                broadcast(new Payloads.JobStatus(info()));
            }
        }
        if (!onlineSubscribers().isEmpty()) {
            lastSubscribedMs = System.currentTimeMillis();
        }
    }

    boolean idleTooLong() {
        int minutes = ServerConfig.get().idleStopMinutes;
        return minutes > 0 && System.currentTimeMillis() - lastSubscribedMs > minutes * 60_000L;
    }

    private void startCaptures() {
        ServerConfig config = ServerConfig.get();
        int started = 0;
        while (!toCapture.isEmpty() && started < config.chunkReadsPerTick && readingFromDisk.size() < config.maxPendingReads) {
            long key = toCapture.dequeueLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            started++;
            LevelChunk loaded = level.getChunkSource().getChunkNow(cx, cz);
            if (loaded != null) {
                job.captureLoadedChunk(loaded);
                continue;
            }
            readingFromDisk.add(key);
            ((ChunkMapInvoker) level.getChunkSource().chunkMap).obsidianpredictor$readChunk(new ChunkPos(cx, cz))
                    .whenCompleteAsync((tag, error) -> readOnWorker(cx, cz, tag, error), Worker.EXECUTOR_SAFE);
        }
    }

    private void readOnWorker(int cx, int cz, Optional<CompoundTag> tag, Throwable error) {
        if (!job.isActive()) return;
        String problem = null;
        if (error != null) {
            problem = "cannot read chunk " + cx + " " + cz + ": " + error;
        } else if (tag.isEmpty()) {
            problem = "chunk " + cx + " " + cz + " (blocks " + (cx << 4) + " " + (cz << 4) + ") is not generated";
        } else {
            ChunkStatus status = SerializableChunkData.getChunkStatusFromTag(tag.get());
            if (status == null || !status.isOrAfter(ChunkStatus.FULL)) {
                problem = "chunk " + cx + " " + cz + " (blocks " + (cx << 4) + " " + (cz << 4) + ") is not fully generated";
            }
        }
        if (problem != null) {
            String reason = problem;
            server.execute(() -> job.fail(reason));
            return;
        }
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag.get());
        if (data == null) {
            server.execute(() -> job.fail("cannot parse chunk " + cx + " " + cz));
            return;
        }
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sections = new PalettedContainer[job.sectionMaxY() - job.sectionMinY() + 1];
        for (SerializableChunkData.SectionData section : data.sectionData()) {
            int index = section.y() - job.sectionMinY();
            if (index >= 0 && index < sections.length && section.chunkSection() != null) {
                sections[index] = section.chunkSection().getStates();
            }
        }
        job.storeChunkOnWorker(cx, cz, sections);
    }

    @Override
    public void onChunkStored(PredictionJob job, int chunkX, int chunkZ, boolean changed) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        readingFromDisk.remove(key);
        if (stale.remove(key)) {
            toCapture.enqueue(key);
        }
    }

    void blockChanged(ServerLevel changedLevel, BlockPos pos, BlockState state) {
        if (changedLevel != level || !job.contains(pos)) return;
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (readingFromDisk.contains(key)) {
            stale.add(key);
        }
        job.blockChanged(pos, state);
    }

    @Override
    public void onResult(PredictionJob job) {
        Predictor.Result result = job.result();
        resultLava = toWorld(result.lava());
        resultBlast = toWorld(result.blastResistant());
        ObsidianPredictorMod.LOGGER.info("Obsidian prediction '{}' #{}: {} lava, {} blast resistant markers",
                name, job.revision(), result.lavaCount(), result.blastResistantCount());
        broadcast(new Payloads.JobStatus(info()));
        broadcast(resultPayload());
    }

    @Override
    public void onFailure(PredictionJob job, String message) {
        ObsidianPredictorMod.LOGGER.warn("Obsidian prediction '{}' failed: {}", name, message);
        broadcast(new Payloads.JobStatus(info()));
    }

    private long[] toWorld(int[] local) {
        long[] out = new long[local.length / 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = BlockPos.asLong(job.min.getX() + local[3 * i], job.min.getY() + local[3 * i + 1], job.min.getZ() + local[3 * i + 2]);
        }
        return out;
    }

    public Payloads.JobResult resultPayload() {
        return new Payloads.JobResult(id, job.revision(), resultLava, resultBlast);
    }

    /** Waypoint lines, same format and order as the Python version. */
    public List<String> waypoints() {
        Predictor.Result result = job.result();
        return result == null ? List.of() : Predictor.waypoints(result, job.min.getX(), job.min.getY(), job.min.getZ());
    }

    void subscribe(ServerPlayer player) {
        subscribers.add(player.getUUID());
        ServerPlayNetworking.send(player, new Payloads.JobStatus(info()));
        if (job.result() != null) {
            ServerPlayNetworking.send(player, resultPayload());
        }
    }

    void unsubscribe(UUID player) {
        subscribers.remove(player);
    }

    private List<ServerPlayer> onlineSubscribers() {
        return subscribers.stream().map(uuid -> server.getPlayerList().getPlayer(uuid)).filter(p -> p != null).toList();
    }

    void broadcast(CustomPacketPayload payload) {
        for (ServerPlayer player : onlineSubscribers()) {
            if (ServerPlayNetworking.canSend(player, payload.type())) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    void stop(String reason) {
        job.stop();
        broadcast(new Payloads.JobClosed(id, reason));
    }
}
