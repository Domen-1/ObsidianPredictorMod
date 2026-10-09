package ru.domen.obsidianpredictor.core;

import java.util.Arrays;

/**
 * Compact copy of an area's block flags, grouped by 16x16x16 chunk sections. A section is
 * either uniform (one code) or a nibble array of codes, see {@link FlagCodes}.
 * Not thread-safe: all access must happen on one thread (the prediction worker).
 */
public final class Snapshot implements Predictor.LayerSource {
    public final int minX, minY, minZ;
    public final int maxX, maxY, maxZ;
    public final int sizeX, sizeY, sizeZ;
    private final int chunkMinX, chunkMinZ, chunksX, chunksZ;
    private final int sectionMinY, sectionsY;
    /** Per section: nibble codes, or null when uniform. */
    private final byte[][] data;
    private final byte[] uniform;
    private final boolean[] chunkPresent;
    private int presentChunks;

    /** Inclusive block bounds. */
    public Snapshot(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        sizeX = maxX - minX + 1;
        sizeY = maxY - minY + 1;
        sizeZ = maxZ - minZ + 1;
        chunkMinX = minX >> 4;
        chunkMinZ = minZ >> 4;
        chunksX = (maxX >> 4) - chunkMinX + 1;
        chunksZ = (maxZ >> 4) - chunkMinZ + 1;
        sectionMinY = minY >> 4;
        sectionsY = (maxY >> 4) - sectionMinY + 1;
        int sections = Math.multiplyExact(Math.multiplyExact(chunksX, chunksZ), sectionsY);
        data = new byte[sections][];
        uniform = new byte[sections];
        chunkPresent = new boolean[chunksX * chunksZ];
    }

    public int chunkMinX() {
        return chunkMinX;
    }

    public int chunkMinZ() {
        return chunkMinZ;
    }

    public int chunksX() {
        return chunksX;
    }

    public int chunksZ() {
        return chunksZ;
    }

    public int totalChunks() {
        return chunksX * chunksZ;
    }

    public int presentChunks() {
        return presentChunks;
    }

    /** World section index (blockY >> 4) range covered by the snapshot. */
    public int sectionMinY() {
        return sectionMinY;
    }

    public int sectionMaxY() {
        return sectionMinY + sectionsY - 1;
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= chunkMinX && chunkX < chunkMinX + chunksX && chunkZ >= chunkMinZ && chunkZ < chunkMinZ + chunksZ;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean isChunkPresent(int chunkX, int chunkZ) {
        return containsChunk(chunkX, chunkZ) && chunkPresent[chunkIndex(chunkX, chunkZ)];
    }

    private int chunkIndex(int chunkX, int chunkZ) {
        return (chunkX - chunkMinX) * chunksZ + (chunkZ - chunkMinZ);
    }

    private int sectionIndex(int chunkX, int chunkZ, int sectionY) {
        return chunkIndex(chunkX, chunkZ) * sectionsY + (sectionY - sectionMinY);
    }

    /**
     * Stores one section of a chunk. {@code nibbles} holds 4096 codes, two per byte, indexed
     * {@code (y << 8) | (z << 4) | x}; pass null for a uniform section of {@code uniformCode}.
     *
     * @return whether the stored content changed
     */
    public boolean putSection(int chunkX, int chunkZ, int sectionY, byte[] nibbles, int uniformCode) {
        if (!containsChunk(chunkX, chunkZ) || sectionY < sectionMinY || sectionY >= sectionMinY + sectionsY) {
            return false;
        }
        int i = sectionIndex(chunkX, chunkZ, sectionY);
        boolean changed = nibbles == null
                ? data[i] != null || uniform[i] != uniformCode
                : !Arrays.equals(data[i], nibbles);
        data[i] = nibbles;
        uniform[i] = (byte) uniformCode;
        return changed;
    }

    /** Marks a chunk as fully stored; returns true if it was missing before. */
    public boolean markChunkPresent(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return false;
        }
        int i = chunkIndex(chunkX, chunkZ);
        if (chunkPresent[i]) {
            return false;
        }
        chunkPresent[i] = true;
        presentChunks++;
        return true;
    }

    /** Updates one block of an already stored chunk. */
    public void setBlock(int x, int y, int z, int code) {
        if (!contains(x, y, z) || !chunkPresent[chunkIndex(x >> 4, z >> 4)]) {
            return;
        }
        int i = sectionIndex(x >> 4, z >> 4, y >> 4);
        byte[] nibbles = data[i];
        if (nibbles == null) {
            if (uniform[i] == code) {
                return;
            }
            nibbles = new byte[2048];
            int u = uniform[i] & 15;
            Arrays.fill(nibbles, (byte) (u | (u << 4)));
            data[i] = nibbles;
        }
        setNibble(nibbles, ((y & 15) << 8) | ((z & 15) << 4) | (x & 15), code);
    }

    public static void setNibble(byte[] nibbles, int index, int code) {
        int b = nibbles[index >> 1];
        if ((index & 1) == 0) {
            b = (b & 0xF0) | code;
        } else {
            b = (b & 0x0F) | (code << 4);
        }
        nibbles[index >> 1] = (byte) b;
    }

    private static int getNibble(byte[] nibbles, int index) {
        int b = nibbles[index >> 1];
        return (index & 1) == 0 ? b & 15 : (b >> 4) & 15;
    }

    /** Code of one block; the chunk must be present. */
    public int getCode(int x, int y, int z) {
        int i = sectionIndex(x >> 4, z >> 4, y >> 4);
        byte[] nibbles = data[i];
        return nibbles == null ? uniform[i] : getNibble(nibbles, ((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    @Override
    public void fillLayer(int y, byte[] out) {
        int worldY = minY + y;
        int sectionY = worldY >> 4;
        int ly = worldY & 15;
        byte[] codeToFlags = FlagCodes.codeToFlags();
        for (int cx = 0; cx < chunksX; cx++) {
            int chunkBlockX = (chunkMinX + cx) << 4;
            int x0 = Math.max(minX, chunkBlockX), x1 = Math.min(maxX, chunkBlockX + 15);
            for (int cz = 0; cz < chunksZ; cz++) {
                int chunkBlockZ = (chunkMinZ + cz) << 4;
                int z0 = Math.max(minZ, chunkBlockZ), z1 = Math.min(maxZ, chunkBlockZ + 15);
                int s = (cx * chunksZ + cz) * sectionsY + (sectionY - sectionMinY);
                byte[] nibbles = data[s];
                if (nibbles == null) {
                    byte flags = codeToFlags[uniform[s]];
                    for (int x = x0; x <= x1; x++) {
                        int row = (x - minX) * sizeZ - minZ;
                        Arrays.fill(out, row + z0, row + z1 + 1, flags);
                    }
                } else {
                    for (int x = x0; x <= x1; x++) {
                        int row = (x - minX) * sizeZ - minZ;
                        for (int z = z0; z <= z1; z++) {
                            out[row + z] = codeToFlags[getNibble(nibbles, (ly << 8) | ((z & 15) << 4) | (x & 15))];
                        }
                    }
                }
            }
        }
    }

    /** Approximate heap used by section data, for status display. */
    public long memoryBytes() {
        long bytes = (long) data.length * 9 + chunkPresent.length;
        for (byte[] d : data) {
            if (d != null) bytes += d.length + 16;
        }
        return bytes;
    }
}
