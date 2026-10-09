package ru.domen.obsidianpredictor.server;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ru.domen.obsidianpredictor.world.StateFlags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saves loaded blocks of an area as a Litematica schematic, e.g. to run the standalone
 * Python/Rust predictor on exactly what the mod sees.
 */
public final class LitematicExporter {
    public static final long MAX_BLOCKS = 256L * 1024 * 1024;

    private LitematicExporter() {}

    /** Returns null on success, otherwise why the export is impossible. */
    public static String checkLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return "chunk " + cx + " " + cz + " is not loaded (use /forceload)";
                }
            }
        }
        return null;
    }

    public static void export(ServerLevel level, BlockPos min, BlockPos max, String name, Path file) throws IOException {
        int sx = max.getX() - min.getX() + 1, sy = max.getY() - min.getY() + 1, sz = max.getZ() - min.getZ() + 1;
        long volume = (long) sx * sy * sz;
        Object2IntLinkedOpenHashMap<BlockState> palette = new Object2IntLinkedOpenHashMap<>();
        palette.put(Blocks.AIR.defaultBlockState(), 0);
        int[] indices = new int[Math.toIntExact(volume)];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState state = level.getBlockState(pos.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    int index = palette.getOrDefault(state, -1);
                    if (index < 0) {
                        index = palette.size();
                        palette.put(state, index);
                    }
                    indices[i++] = index;
                }
            }
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(int) ((volume * bits + 63) / 64)];
        for (int j = 0; j < indices.length; j++) {
            long value = indices[j];
            long bit = (long) j * bits;
            int start = (int) (bit >> 6);
            int offset = (int) (bit & 63);
            packed[start] |= value << offset;
            if (offset + bits > 64) {
                packed[start + 1] |= value >>> (64 - offset);
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette.keySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            Map<String, String> properties = new TreeMap<>(StateFlags.properties(state));
            if (!properties.isEmpty()) {
                CompoundTag props = new CompoundTag();
                properties.forEach(props::putString);
                entry.put("Properties", props);
            }
            paletteTag.add(entry);
        }

        CompoundTag region = new CompoundTag();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(sx, sy, sz));
        region.put("BlockStatePalette", paletteTag);
        region.putLongArray("BlockStates", packed);
        region.put("TileEntities", new ListTag());
        region.put("Entities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put(name, region);

        long now = System.currentTimeMillis();
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", name);
        metadata.putString("Author", "Obsidian Predictor");
        metadata.putString("Description", "Blocks " + min.toShortString() + " .. " + max.toShortString());
        metadata.put("EnclosingSize", vec(sx, sy, sz));
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TotalBlocks", volume);
        metadata.putInt("TotalVolume", (int) Math.min(Integer.MAX_VALUE, volume));
        metadata.putLong("TimeCreated", now);
        metadata.putLong("TimeModified", now);

        CompoundTag root = new CompoundTag();
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("Version", 7);
        root.put("Metadata", metadata);
        root.put("Regions", regions);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    private static CompoundTag vec(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }
}
