package ru.domen.obsidianpredictor.client;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import ru.domen.obsidianpredictor.core.Predictor;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.MemoryBudget;
import ru.domen.obsidianpredictor.world.PredictionJob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client state: the selected area and what is shown — either a prediction computed on this
 * client from loaded chunks, or a shared prediction followed from the server.
 */
public final class ClientSession {
    /** Share of the free heap a local prediction may use. */
    private static final double MEMORY_SHARE = 0.6;
    private static final int CAPTURES_PER_TICK = 64;

    /** Rendered markers, in waypoint order. */
    /**
     * {@code blastResistant} holds one waypoint per group (what Xaero export writes);
     * {@code blastGroups} holds every blast-resistant block, for highlighting whole groups.
     */
    public record Markers(ResourceKey<Level> dimension, BlockPos min, BlockPos max, List<BlockPos> lava,
                          List<BlockPos> blastResistant, BlockGroups blastGroups) {
        Markers withGroups(BlockGroups groups) {
            return new Markers(dimension, min, max, lava, blastResistant, groups);
        }
    }

    /** Grouping runs off the client thread; only the newest request may publish its result. */
    private static int groupingRequest;
    /** Parts of the blast-resistant block list of the followed job's latest revision. */
    private static long[][] pendingBlockParts;
    private static int pendingBlocksRevision = -1;

    public static BlockPos corner1;
    public static BlockPos corner2;

    private static LocalPrediction local;
    private static long followedJobId = -1;
    private static Payloads.JobInfo followedInfo;
    private static String pendingServerStart;
    private static volatile Markers markers;

    public static List<Payloads.JobInfo> serverJobs = List.of();
    public static String serverModVersion;
    /** Print the next job list to chat (requested by /obsidian jobs). */
    static boolean printNextJobList;

    private ClientSession() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void message(Component text) {
        if (mc().player != null) {
            mc().player.sendSystemMessage(Component.empty()
                    .append(Component.literal("[Obsidian Predictor] ").withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(text));
        }
    }

    static void message(String text) {
        message(Component.literal(text));
    }

    public static boolean serverAvailable() {
        return mc().getConnection() != null && ClientPlayNetworking.canSend(Payloads.StartJob.TYPE);
    }

    public static Markers markers() {
        return markers;
    }

    public static LocalPrediction local() {
        return local;
    }

    public static Payloads.JobInfo followed() {
        return followedJobId >= 0 ? followedInfo : null;
    }

    public static boolean isActive() {
        return local != null || followedJobId >= 0;
    }

    /** Selection as min/max corners, Y clamped to the world; null if incomplete. */
    public static BlockPos[] selection() {
        if (corner1 == null || corner2 == null) return null;
        BlockPos min = BlockPos.min(corner1, corner2);
        BlockPos max = BlockPos.max(corner1, corner2);
        ClientLevel level = mc().level;
        if (level != null) {
            min = new BlockPos(min.getX(), Math.max(min.getY(), level.getMinY()), min.getZ());
            max = new BlockPos(max.getX(), Math.min(max.getY(), level.getMaxY()), max.getZ());
        }
        return new BlockPos[]{min, max};
    }

    public static long selectionChunks() {
        BlockPos[] sel = selection();
        if (sel == null) return 0;
        return (long) ((sel[1].getX() >> 4) - (sel[0].getX() >> 4) + 1) * ((sel[1].getZ() >> 4) - (sel[0].getZ() >> 4) + 1);
    }

    // ---- starting and stopping ----

    public static void startLocal() {
        BlockPos[] sel = selection();
        ClientLevel level = mc().level;
        if (sel == null || level == null) {
            message("Set both corners first (/obsidian pos1, /obsidian pos2 or the screen).");
            return;
        }
        if (sel[0].getY() > sel[1].getY()) {
            message("The area is outside the world height.");
            return;
        }
        long chunks = selectionChunks();
        long budget = MemoryBudget.available(MEMORY_SHARE);
        String memoryProblem = MemoryBudget.check(sel[0], sel[1], budget);
        if (memoryProblem != null) {
            message(Component.literal(memoryProblem).withStyle(ChatFormatting.RED));
            return;
        }
        stopAll(false);
        local = new LocalPrediction(level, sel[0], sel[1]);
        local.job.setMemoryBudget(budget);
        markers = new Markers(level.dimension(), sel[0], sel[1], List.of(), List.of(), BlockGroups.EMPTY);
        message("Started: " + chunks + " chunks. Fly around until every yellow chunk is loaded.");
    }

    public static void startOnServer(String name) {
        BlockPos[] sel = selection();
        if (sel == null) {
            message("Set both corners first (/obsidian pos1, /obsidian pos2 or the screen).");
            return;
        }
        if (!serverAvailable()) {
            message("This server does not have Obsidian Predictor; use a local prediction instead.");
            return;
        }
        stopAll(false);
        pendingServerStart = name == null ? "" : name.strip();
        ClientPlayNetworking.send(new Payloads.StartJob(pendingServerStart, sel[0], sel[1]));
    }

    public static void follow(long jobId) {
        if (!serverAvailable()) return;
        stopAll(false);
        ClientPlayNetworking.send(new Payloads.JobAction(Payloads.Action.SUBSCRIBE, jobId));
        followedJobId = jobId;
        followedInfo = null;
    }

    public static void stopServerJob(long jobId) {
        if (serverAvailable()) {
            ClientPlayNetworking.send(new Payloads.JobAction(Payloads.Action.STOP, jobId));
        }
    }

    public static void requestJobList(boolean print) {
        if (!serverAvailable()) {
            if (print) message("This server does not have Obsidian Predictor.");
            return;
        }
        printNextJobList |= print;
        ClientPlayNetworking.send(new Payloads.ListJobs());
    }

    /** Stops the local prediction and stops following a server job (the server job keeps running). */
    public static void stopAll(boolean clearMarkers) {
        if (local != null) {
            local.job.stop();
            local = null;
        }
        if (followedJobId >= 0 && serverAvailable()) {
            ClientPlayNetworking.send(new Payloads.JobAction(Payloads.Action.UNSUBSCRIBE, followedJobId));
        }
        followedJobId = -1;
        followedInfo = null;
        pendingServerStart = null;
        pendingBlockParts = null;
        pendingBlocksRevision = -1;
        if (clearMarkers) {
            markers = null;
        }
    }

    public static void reset() {
        stopAll(true);
        serverJobs = List.of();
        serverModVersion = null;
    }

    // ---- events ----

    static void tick() {
        if (local != null) {
            if (mc().level != local.level) {
                message("Left the dimension; local prediction stopped.");
                stopAll(true);
            } else {
                local.tick();
            }
        }
    }

    static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (local != null && level == local.level) {
            local.job.captureLoadedChunk(chunk);
        }
    }

    static void onBlockChanged(Level level, BlockPos pos, BlockState state) {
        if (local != null && level == local.level) {
            local.job.blockChanged(pos, state);
        }
    }

    static void onJobStatus(Payloads.JobInfo info) {
        if (followedJobId < 0 && pendingServerStart != null) {
            followedJobId = info.id();
            pendingServerStart = null;
            message("Server is working on '" + info.name() + "' (#" + info.id() + "). Others can follow it from /obsidian jobs.");
        }
        if (info.id() != followedJobId) return;
        Payloads.JobInfo previous = followedInfo;
        followedInfo = info;
        ResourceKey<Level> dimension = mc().level == null ? null : mc().level.dimension();
        Markers current = markers;
        if (current == null || !current.min().equals(info.min()) || !current.max().equals(info.max())) {
            markers = new Markers(dimension, info.min(), info.max(), List.of(), List.of(), BlockGroups.EMPTY);
        }
        PredictionJob.Phase phase = PredictionJob.Phase.byId(info.phase());
        if (phase == PredictionJob.Phase.FAILED && (previous == null || previous.phase() != info.phase())) {
            message(Component.literal("Server prediction failed: " + info.message()).withStyle(ChatFormatting.RED));
        }
    }

    static void onJobResult(Payloads.JobResult result) {
        if (result.jobId() != followedJobId || mc().level == null) return;
        Payloads.JobInfo info = followedInfo;
        BlockPos min = info != null ? info.min() : markers != null ? markers.min() : BlockPos.ZERO;
        BlockPos max = info != null ? info.max() : markers != null ? markers.max() : BlockPos.ZERO;
        // keep the previous groups until this revision's blocks arrive, so they don't blink
        BlockGroups groups = markers != null ? markers.blastGroups() : BlockGroups.EMPTY;
        markers = new Markers(mc().level.dimension(), min, max, toPositions(result.lava()), toPositions(result.blastResistant()), groups);
    }

    static void onJobBlocks(Payloads.JobBlocks blocks) {
        if (blocks.jobId() != followedJobId || blocks.parts() <= 0 || blocks.part() >= blocks.parts()) return;
        if (blocks.revision() != pendingBlocksRevision || pendingBlockParts == null || pendingBlockParts.length != blocks.parts()) {
            pendingBlocksRevision = blocks.revision();
            pendingBlockParts = new long[blocks.parts()][];
        }
        pendingBlockParts[blocks.part()] = blocks.positions();
        for (long[] part : pendingBlockParts) {
            if (part == null) return;
        }
        int total = 0;
        for (long[] part : pendingBlockParts) total += part.length;
        long[] all = new long[total];
        int offset = 0;
        for (long[] part : pendingBlockParts) {
            System.arraycopy(part, 0, all, offset, part.length);
            offset += part.length;
        }
        pendingBlockParts = null;
        groupInBackground(all);
    }

    /** Groups blocks off-thread and attaches them to the current markers when done. */
    static void groupInBackground(long[] positions) {
        int request = ++groupingRequest;
        CompletableFuture.supplyAsync(() -> BlockGroups.of(positions))
                .thenAcceptAsync(groups -> {
                    if (request == groupingRequest && markers != null) {
                        markers = markers.withGroups(groups);
                    }
                }, mc());
    }

    static void onJobClosed(Payloads.JobClosed closed) {
        if (closed.jobId() == -1) {
            pendingServerStart = null;
            message(Component.literal(closed.reason()).withStyle(ChatFormatting.RED));
            return;
        }
        if (closed.jobId() == followedJobId) {
            followedJobId = -1;
            followedInfo = null;
            message("Server job closed: " + closed.reason());
        }
    }

    static void onJobList(List<Payloads.JobInfo> jobs) {
        serverJobs = jobs;
        if (printNextJobList) {
            printNextJobList = false;
            if (jobs.isEmpty()) {
                message("No predictions on this server. Start one with /obsidian start server <name>.");
            }
            for (Payloads.JobInfo info : jobs) {
                MutableComponent line = Component.literal("#" + info.id() + " '" + info.name() + "' by " + info.owner() + ", "
                        + info.min().toShortString() + " .. " + info.max().toShortString() + ", "
                        + PredictionJob.Phase.byId(info.phase()).name().toLowerCase() + " ");
                line.append(Component.literal("[follow]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/obsidian join " + info.id()))));
                message(line);
            }
        }
        if (mc().gui.screen() instanceof JobsScreen screen) {
            screen.refresh();
        }
    }

    private static List<BlockPos> toPositions(long[] packed) {
        List<BlockPos> list = new ArrayList<>(packed.length);
        for (long p : packed) list.add(BlockPos.of(p));
        return list;
    }

    // ---- export ----

    public static void exportWaypoints() {
        Markers current = markers;
        if (current == null || current.lava().isEmpty() && current.blastResistant().isEmpty()) {
            message("Nothing to export yet.");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (BlockPos p : current.lava()) {
            lines.add(Predictor.formatWaypoint(p.getX(), p.getY(), p.getZ(), Predictor.RED, "x"));
        }
        for (BlockPos p : current.blastResistant()) {
            lines.add(Predictor.formatWaypoint(p.getX(), p.getY(), p.getZ(), Predictor.BLACK, "x"));
        }
        Payloads.JobInfo info = followed();
        String name = info != null ? info.name() : "area " + current.min().getX() + " " + current.min().getY() + " " + current.min().getZ();
        Path file = FabricLoader.getInstance().getGameDir().resolve("obsidianpredictor")
                .resolve(name.replaceAll("[^\\p{L}\\p{N} _.-]", "_") + " waypoints.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            message(Component.literal("Cannot write " + file + ": " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        message(Component.literal("Saved " + lines.size() + " Xaero waypoints to ")
                .append(Component.literal(file.getFileName().toString()).withStyle(s -> s.withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenFile(file.getParent())))));
    }

    /** A prediction computed on this client from the chunks it has loaded. */
    public static final class LocalPrediction implements PredictionJob.Listener {
        final ClientLevel level;
        final PredictionJob job;
        private final LongArrayFIFOQueue initialChunks = new LongArrayFIFOQueue();

        LocalPrediction(ClientLevel level, BlockPos min, BlockPos max) {
            this.level = level;
            this.job = new PredictionJob(min, max, Minecraft.getInstance(), this);
            for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
                for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                    initialChunks.enqueue(ChunkPos.pack(cx, cz));
                }
            }
        }

        public PredictionJob job() {
            return job;
        }

        void tick() {
            // chunks that were already loaded when the prediction started; later ones arrive via CHUNK_LOAD
            for (int i = 0; i < CAPTURES_PER_TICK && !initialChunks.isEmpty(); i++) {
                long key = initialChunks.dequeueLong();
                if (level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false) instanceof LevelChunk chunk) {
                    job.captureLoadedChunk(chunk);
                }
            }
            job.tick();
        }

        @Override
        public void onResult(PredictionJob job) {
            Predictor.Result result = job.result();
            List<BlockPos> lava = new ArrayList<>(result.lavaCount());
            int[] l = result.lava();
            for (int i = 0; i < l.length; i += 3) lava.add(job.min.offset(l[i], l[i + 1], l[i + 2]));
            List<BlockPos> blast = new ArrayList<>(result.blastResistantCount());
            int[] b = result.blastResistant();
            for (int i = 0; i < b.length; i += 3) blast.add(job.min.offset(b[i], b[i + 1], b[i + 2]));
            markers = new Markers(level.dimension(), job.min, job.max, lava, blast, markers != null ? markers.blastGroups() : BlockGroups.EMPTY);
            int[] all = result.blastResistantBlocks();
            long[] positions = new long[all.length / 3];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = BlockPos.asLong(job.min.getX() + all[3 * i], job.min.getY() + all[3 * i + 1], job.min.getZ() + all[3 * i + 2]);
            }
            groupInBackground(positions);
            if (job.revision() == 1) {
                message("Done: " + lava.size() + " lava markers, " + blast.size() + " blast-resistant groups. They update as blocks change.");
            }
        }

        @Override
        public void onFailure(PredictionJob job, String message) {
            message(Component.literal("Prediction failed: " + message).withStyle(ChatFormatting.RED));
        }
    }
}
