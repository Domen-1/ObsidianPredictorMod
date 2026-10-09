package ru.domen.obsidianpredictor.client;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.PredictionJob;

import java.util.ArrayList;
import java.util.List;

/** A few status lines in the top left corner. */
public final class HudOverlay {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF6040;
    private static final int PURPLE = 0xFFC080FF;
    private static final int YELLOW = 0xFFFFDD40;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private record Line(String text, int color) {}

    private HudOverlay() {}

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || !ClientSession.isActive() && ClientSession.markers() == null) return;
        List<Line> lines = new ArrayList<>();

        ClientSession.LocalPrediction local = ClientSession.local();
        Payloads.JobInfo remote = ClientSession.followed();
        if (local != null) {
            PredictionJob job = local.job();
            lines.add(new Line("Obsidian Predictor (local)", WHITE));
            status(lines, job.phase(), job.capturedChunks(), job.totalChunks(), job.predictionPermille(), job.isPredicting(), job.message());
            if (job.phase() == PredictionJob.Phase.CAPTURING) {
                nearestMissingChunk(lines, mc, job.missingChunks());
            }
        } else if (remote != null) {
            lines.add(new Line("Obsidian Predictor: '" + remote.name() + "' on server", WHITE));
            status(lines, PredictionJob.Phase.byId(remote.phase()), remote.capturedChunks(), remote.totalChunks(),
                    remote.predictionPermille(), remote.predictionPermille() > 0, remote.message());
        } else {
            lines.add(new Line("Obsidian Predictor: stopped (markers kept)", WHITE));
        }

        ClientSession.Markers markers = ClientSession.markers();
        if (markers != null && (!markers.lava().isEmpty() || !markers.blastResistant().isEmpty())) {
            int groups = markers.blastResistant().size();
            int blocks = markers.blastGroups().blockCount();
            lines.add(new Line("Lava to cover: " + markers.lava().size() + "   Blast resistant: " + groups
                    + (groups == 1 ? " group" : " groups") + (blocks > 0 ? " (" + blocks + (blocks == 1 ? " block)" : " blocks)") : ""), WHITE));
            if (markers.dimension() == mc.level.dimension()) {
                nearest(lines, mc, markers.lava(), "Nearest lava", RED);
                nearest(lines, mc, markers.blastResistant(), "Nearest blast resistant", PURPLE);
            }
        }

        int y = 4;
        for (Line line : lines) {
            graphics.fill(2, y - 1, 6 + mc.font.width(line.text), y + mc.font.lineHeight, 0x80000000);
            graphics.text(mc.font, line.text, 4, y, line.color, false);
            y += mc.font.lineHeight + 2;
        }
    }

    private static void status(List<Line> lines, PredictionJob.Phase phase, int captured, int total, int permille,
                               boolean predicting, String message) {
        switch (phase) {
            case CAPTURING -> lines.add(new Line("Collecting chunks " + captured + "/" + total
                    + (captured < total ? " - load the yellow ones" : ""), YELLOW));
            case PREDICTING -> lines.add(new Line("Simulating " + permille / 10 + "%", YELLOW));
            case READY -> {
                if (predicting) lines.add(new Line("Updating " + permille / 10 + "%", YELLOW));
            }
            case FAILED -> lines.add(new Line("Failed: " + message, RED));
            case STOPPED -> lines.add(new Line("Stopped", WHITE));
        }
    }

    private static void nearestMissingChunk(List<Line> lines, Minecraft mc, LongOpenHashSet missing) {
        if (missing.isEmpty()) return;
        Vec3 eye = mc.player.getEyePosition();
        long best = 0;
        double bestDist = Double.MAX_VALUE;
        LongIterator it = missing.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            double dx = (ChunkPos.getX(key) << 4) + 8 - eye.x, dz = (ChunkPos.getZ(key) << 4) + 8 - eye.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        double dx = (ChunkPos.getX(best) << 4) + 8 - eye.x, dz = (ChunkPos.getZ(best) << 4) + 8 - eye.z;
        lines.add(new Line("Nearest unloaded chunk: " + Math.round(Math.sqrt(bestDist)) + " m " + arrow(mc, dx, dz)
                + "  " + ((ChunkPos.getX(best) << 4) + 8) + " " + ((ChunkPos.getZ(best) << 4) + 8), YELLOW));
    }

    /** Arrow towards a horizontal offset, relative to where the player looks. */
    private static String arrow(Minecraft mc, double dx, double dz) {
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        return ARROWS[Math.floorMod((int) Math.round(relative / 45.0), 8)];
    }

    private static void nearest(List<Line> lines, Minecraft mc, List<BlockPos> positions, String label, int color) {
        if (positions.isEmpty()) return;
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : positions) {
            double d = p.distToCenterSqr(eye);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        String arrow = arrow(mc, best.getX() + 0.5 - eye.x, best.getZ() + 0.5 - eye.z);
        int dy = best.getY() - Mth.floor(eye.y);
        lines.add(new Line(label + ": " + Math.round(Math.sqrt(bestDist)) + " m " + arrow
                + (dy > 2 ? " (" + dy + " up)" : dy < -2 ? " (" + -dy + " down)" : "")
                + "  " + best.getX() + " " + best.getY() + " " + best.getZ(), color));
    }
}
