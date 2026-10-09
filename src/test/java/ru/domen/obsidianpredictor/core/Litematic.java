package ru.domen.obsidianpredictor.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Minimal Litematica reader for tests: first region only, like the Python version. */
final class Litematic {
    record Block(String id, Map<String, String> properties) {}

    final int width, height, length;
    final int sizeX, sizeY, sizeZ;
    final List<Block> palette;
    private final long[] data;
    private final int nbits;
    private final long mask;

    private Litematic(int width, int height, int length, List<Block> palette, long[] data) {
        this.width = width;
        this.height = height;
        this.length = length;
        sizeX = Math.abs(width);
        sizeY = Math.abs(height);
        sizeZ = Math.abs(length);
        this.palette = palette;
        this.data = data;
        nbits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        mask = (1L << nbits) - 1;
    }

    /** Palette index at region-local (x, y, z); same packing as LitematicaBitArrayProxy. */
    int get(int x, int y, int z) {
        long index = ((long) y * sizeX * sizeZ) + (long) z * sizeX + x;
        long startOffset = index * nbits;
        int start = (int) (startOffset >> 6);
        int end = (int) (((index + 1) * nbits - 1) >> 6);
        int bitOffset = (int) (startOffset & 0x3F);
        if (start == end) {
            return (int) ((data[start] >>> bitOffset) & mask);
        }
        return (int) (((data[start] >>> bitOffset) | (data[end] << (64 - bitOffset))) & mask);
    }

    @SuppressWarnings("unchecked")
    static Litematic load(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)), 1 << 16)) {
            DataInputStream din = new DataInputStream(in);
            if (din.readByte() != 10) throw new IOException("not NBT");
            din.readUTF();
            Map<String, Object> root = (Map<String, Object>) payload(din, 10);
            Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
            Map<String, Object> region = (Map<String, Object>) regions.values().iterator().next();
            Map<String, Object> size = (Map<String, Object>) region.get("Size");
            List<Block> palette = new ArrayList<>();
            for (Object entry : (List<Object>) region.get("BlockStatePalette")) {
                Map<String, Object> e = (Map<String, Object>) entry;
                Map<String, String> props = new LinkedHashMap<>();
                Object p = e.get("Properties");
                if (p != null) {
                    ((Map<String, Object>) p).forEach((k, v) -> props.put(k, (String) v));
                }
                palette.add(new Block((String) e.get("Name"), props));
            }
            return new Litematic((Integer) size.get("x"), (Integer) size.get("y"), (Integer) size.get("z"),
                    palette, (long[]) region.get("BlockStates"));
        }
    }

    private static Object payload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int elem = in.readByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(len, 0));
                for (int i = 0; i < len; i++) list.add(payload(in, elem));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return map;
                    String name = in.readUTF();
                    map.put(name, payload(in, t));
                }
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("unknown tag " + type);
        }
    }
}
