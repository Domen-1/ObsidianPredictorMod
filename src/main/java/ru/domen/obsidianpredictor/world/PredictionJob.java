package ru.domen.obsidianpredictor.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;
import ru.domen.obsidianpredictor.core.Predictor;
import ru.domen.obsidianpredictor.core.Snapshot;

import java.util.concurrent.Executor;

/**
 * Collects an area's blocks into a {@link Snapshot} and keeps a prediction for it up to date.
 * Shared by the client (blocks from loaded chunks) and the server (blocks from disk too).
 *
 * <p>All public methods are called on the main thread; snapshot work happens on the
 * {@link Worker}, which posts results back through {@code mainThread}.
 */
public final class PredictionJob {
    public enum Phase {
        CAPTURING, PREDICTING, READY, FAILED, STOPPED;

        public static Phase byId(int id) {
            Phase[] values = values();
            return id >= 0 && id < values.length ? values[id] : FAILED;
        }
    }

    public interface Listener {
        /** A new prediction is available. */
        void onResult(PredictionJob job);

        /** A chunk's blocks were stored; {@code changed} is false when they were the same as before. */
        default void onChunkStored(PredictionJob job, int chunkX, int chunkZ, boolean changed) {}

        void onFailure(PredictionJob job, String message);
    }

    /** Wait this long after the last block change before predicting again. */
    private static final long DEBOUNCE_MS = 1500;

    public final BlockPos min;
    public final BlockPos max;
    private final Snapshot snapshot;
    private final Executor mainThread;
    private final Listener listener;

    // main thread state
    private final LongOpenHashSet missingChunks = new LongOpenHashSet();
    private Phase phase = Phase.CAPTURING;
    private boolean predicting;
    private boolean dirty;
    private long lastChangeMs;
    private long dirtySinceMs;
    private long maxStaleMs = 10_000;
    /** Snapshot size at which the job fails instead of risking running out of memory. */
    private volatile long memoryBudgetBytes = Long.MAX_VALUE;
    private Predictor.Result result;
    private int revision;
    private String message = "";

    // shared with the worker
    private volatile int layersDone;
    private volatile boolean cancelled;

    public PredictionJob(BlockPos min, BlockPos max, Executor mainThread, Listener listener) {
        StateFlags.init();
        this.min = min;
        this.max = max;
        this.snapshot = new Snapshot(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
        this.mainThread = mainThread;
        this.listener = listener;
        for (int cx = snapshot.chunkMinX(); cx < snapshot.chunkMinX() + snapshot.chunksX(); cx++) {
            for (int cz = snapshot.chunkMinZ(); cz < snapshot.chunkMinZ() + snapshot.chunksZ(); cz++) {
                missingChunks.add(ChunkPos.pack(cx, cz));
            }
        }
    }

    public Phase phase() {
        return phase;
    }

    public String message() {
        return message;
    }

    public Predictor.Result result() {
        return result;
    }

    public int revision() {
        return revision;
    }

    public int totalChunks() {
        return snapshot.totalChunks();
    }

    public int capturedChunks() {
        return snapshot.totalChunks() - missingChunks.size();
    }

    public LongOpenHashSet missingChunks() {
        return missingChunks;
    }

    public boolean isPredicting() {
        return predicting;
    }

    /** Progress of the running prediction, 0..1000. */
    public int predictionPermille() {
        return predicting ? (int) (1000L * layersDone / Math.max(1, snapshot.sizeY)) : 0;
    }

    public int sectionMinY() {
        return snapshot.sectionMinY();
    }

    public int sectionMaxY() {
        return snapshot.sectionMaxY();
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return snapshot.containsChunk(chunkX, chunkZ);
    }

    public boolean contains(BlockPos pos) {
        return snapshot.contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean isActive() {
        return phase != Phase.FAILED && phase != Phase.STOPPED;
    }

    /** Copies the sections of a loaded chunk now and stores them in the background. */
    public void captureLoadedChunk(LevelChunk chunk) {
        if (!isActive()) return;
        ChunkPos pos = chunk.getPos();
        if (!snapshot.containsChunk(pos.x(), pos.z())) return;
        int sectionMin = snapshot.sectionMinY();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] sections = new PalettedContainer[snapshot.sectionMaxY() - sectionMin + 1];
        for (int sy = sectionMin; sy <= snapshot.sectionMaxY(); sy++) {
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index >= 0 && index < chunk.getSections().length) {
                LevelChunkSection section = chunk.getSections()[index];
                sections[sy - sectionMin] = section.getStates().copy();
            }
        }
        Worker.submit(() -> storeChunkOnWorker(pos.x(), pos.z(), sections));
    }

    /**
     * Stores sections that belong to the worker thread. {@code sections[i]} is section
     * {@code sectionMinY() + i}; null means air.
     */
    public void storeChunkOnWorker(int chunkX, int chunkZ, PalettedContainer<BlockState>[] sections) {
        if (cancelled) return;
        boolean changed = false;
        for (int i = 0; i < sections.length; i++) {
            SectionEncoder.Encoded encoded = sections[i] == null ? SectionEncoder.Encoded.air() : SectionEncoder.encode(sections[i]);
            changed |= snapshot.putSection(chunkX, chunkZ, snapshot.sectionMinY() + i, encoded.nibbles(), encoded.uniformCode());
        }
        boolean added = snapshot.markChunkPresent(chunkX, chunkZ);
        if (snapshot.memoryBytes() > memoryBudgetBytes) {
            cancelled = true;
            long used = MemoryBudget.mb(snapshot.memoryBytes());
            mainThread.execute(() -> fail("not enough memory: the area's blocks already take " + used
                    + " MB, more than this job may use. Give Minecraft more memory (-Xmx) or split the area."));
            return;
        }
        boolean anyChange = changed || added;
        mainThread.execute(() -> onChunkStored(chunkX, chunkZ, anyChange));
    }

    private void onChunkStored(int chunkX, int chunkZ, boolean changed) {
        if (!isActive()) return;
        boolean wasMissing = missingChunks.remove(ChunkPos.pack(chunkX, chunkZ));
        if (changed && !wasMissing) {
            markDirty();
        }
        listener.onChunkStored(this, chunkX, chunkZ, changed);
    }

    /** A block in the world changed; keeps the snapshot in sync. */
    public void blockChanged(BlockPos pos, BlockState state) {
        if (!isActive() || !snapshot.contains(pos.getX(), pos.getY(), pos.getZ())) return;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int code = StateFlags.code(state);
        // The worker ignores chunks it has not stored yet; capture order keeps this consistent.
        Worker.submit(() -> snapshot.setBlock(x, y, z, code));
        if (missingChunks.isEmpty()) {
            markDirty();
        }
    }

    private void markDirty() {
        long now = System.currentTimeMillis();
        if (!dirty) {
            dirtySinceMs = now;
        }
        dirty = true;
        lastChangeMs = now;
    }

    public void setMemoryBudget(long bytes) {
        this.memoryBudgetBytes = bytes;
    }

    /**
     * While blocks keep changing (a running world eater), recompute at least this often.
     * 0 waits until changes stop.
     */
    public void setMaxStaleMs(long maxStaleMs) {
        this.maxStaleMs = maxStaleMs;
    }

    /** Starts a prediction once every chunk is stored, and again after changes settle. */
    public void tick() {
        if (!isActive() || predicting || !missingChunks.isEmpty()) return;
        boolean first = phase == Phase.CAPTURING;
        long now = System.currentTimeMillis();
        boolean settled = now - lastChangeMs >= DEBOUNCE_MS;
        boolean tooStale = maxStaleMs > 0 && now - dirtySinceMs >= maxStaleMs;
        if (first || dirty && (settled || tooStale)) {
            startPrediction();
        }
    }

    private void startPrediction() {
        predicting = true;
        dirty = false;
        layersDone = 0;
        if (phase == Phase.CAPTURING) {
            phase = Phase.PREDICTING;
        }
        Worker.submit(() -> {
            try {
                long started = System.nanoTime();
                Predictor.Result r = new Predictor(snapshot.sizeX, snapshot.sizeY, snapshot.sizeZ, snapshot)
                        .run(done -> layersDone = done, () -> cancelled);
                long ms = (System.nanoTime() - started) / 1_000_000;
                ObsidianPredictorMod.LOGGER.debug("Prediction of {}..{} took {} ms", min, max, ms);
                mainThread.execute(() -> {
                    predicting = false;
                    if (!isActive()) return;
                    result = r;
                    revision++;
                    phase = Phase.READY;
                    listener.onResult(this);
                });
            } catch (Predictor.CancelledException e) {
                mainThread.execute(() -> predicting = false);
            } catch (Throwable t) {
                ObsidianPredictorMod.LOGGER.error("Prediction failed", t);
                mainThread.execute(() -> {
                    predicting = false;
                    fail("prediction failed: " + t);
                });
            }
        });
    }

    public void fail(String reason) {
        if (!isActive()) return;
        phase = Phase.FAILED;
        message = reason;
        cancelled = true;
        listener.onFailure(this, reason);
    }

    public void stop() {
        phase = Phase.STOPPED;
        cancelled = true;
    }

    public long memoryBytesEstimate() {
        // read without synchronization; only for display
        return snapshot.memoryBytes();
    }
}
