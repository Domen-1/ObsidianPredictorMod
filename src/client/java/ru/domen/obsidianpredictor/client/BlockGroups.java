package ru.domen.obsidianpredictor.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Blast-resistant blocks split into 6-connected groups, for drawing each group as one shape. */
public final class BlockGroups {
    public static final BlockGroups EMPTY = new BlockGroups(new LongOpenHashSet(), List.of());

    public record Group(long[] blocks, double centerX, double centerY, double centerZ,
                        int minX, int minZ, int maxX, int maxZ) {}

    /** Every block, for checking neighbours. */
    public final LongOpenHashSet all;
    public final List<Group> groups;

    private BlockGroups(LongOpenHashSet all, List<Group> groups) {
        this.all = all;
        this.groups = groups;
    }

    public int blockCount() {
        return all.size();
    }

    public static BlockGroups of(long[] positions) {
        if (positions.length == 0) return EMPTY;
        LongOpenHashSet all = new LongOpenHashSet(positions);
        LongOpenHashSet left = new LongOpenHashSet(all);
        List<Group> groups = new ArrayList<>();
        LongArrayList stack = new LongArrayList();
        for (long start : positions) {
            if (!left.remove(start)) continue;
            LongArrayList members = new LongArrayList();
            stack.add(start);
            while (!stack.isEmpty()) {
                long p = stack.removeLong(stack.size() - 1);
                members.add(p);
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                visit(left, stack, BlockPos.asLong(x + 1, y, z));
                visit(left, stack, BlockPos.asLong(x - 1, y, z));
                visit(left, stack, BlockPos.asLong(x, y + 1, z));
                visit(left, stack, BlockPos.asLong(x, y - 1, z));
                visit(left, stack, BlockPos.asLong(x, y, z + 1));
                visit(left, stack, BlockPos.asLong(x, y, z - 1));
            }
            double sx = 0, sy = 0, sz = 0;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < members.size(); i++) {
                long p = members.getLong(i);
                int x = BlockPos.getX(p), z = BlockPos.getZ(p);
                sx += x;
                sy += BlockPos.getY(p);
                sz += z;
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
            }
            int n = members.size();
            groups.add(new Group(members.toLongArray(), sx / n + 0.5, sy / n + 0.5, sz / n + 0.5, minX, minZ, maxX, maxZ));
        }
        return new BlockGroups(all, groups);
    }

    private static void visit(LongOpenHashSet left, LongArrayList stack, long p) {
        if (left.remove(p)) {
            stack.add(p);
        }
    }
}
