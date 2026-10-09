package ru.domen.obsidianpredictor.world;

import net.minecraft.core.BlockPos;
import ru.domen.obsidianpredictor.core.Snapshot;

/**
 * Areas are limited by memory, not by a fixed size. A job gets a share of the heap that is free
 * when it starts; it fails cleanly if its snapshot grows past that instead of running out of memory.
 */
public final class MemoryBudget {
    private MemoryBudget() {}

    /** {@code share} of the heap that is free right now. */
    public static long available(double share) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (long) ((rt.maxMemory() - used) * share);
    }

    public static long sections(BlockPos min, BlockPos max) {
        long chunks = (long) ((max.getX() >> 4) - (min.getX() >> 4) + 1) * ((max.getZ() >> 4) - (min.getZ() >> 4) + 1);
        return chunks * ((max.getY() >> 4) - (min.getY() >> 4) + 1);
    }

    /** Memory needed no matter what the terrain is. */
    public static long fixedBytes(BlockPos min, BlockPos max) {
        return Snapshot.fixedBytes(max.getX() - min.getX() + 1, max.getZ() - min.getZ() + 1, sections(min, max));
    }

    /** Null if the area can start within {@code budget}, otherwise why not. */
    public static String check(BlockPos min, BlockPos max, long budget) {
        long fixed = fixedBytes(min, max);
        if (fixed > budget) {
            return "area too large for the free memory: it needs at least " + mb(fixed) + " MB, " + mb(budget)
                    + " MB is available. Give Minecraft more memory (-Xmx) or split the area.";
        }
        return null;
    }

    /** Rough upper bound for display: every section with mixed blocks. */
    public static long worstCaseBytes(BlockPos min, BlockPos max) {
        return fixedBytes(min, max) + Snapshot.worstCaseSectionBytes(sections(min, max));
    }

    public static long mb(long bytes) {
        return (bytes + (1 << 20) - 1) >> 20;
    }
}
