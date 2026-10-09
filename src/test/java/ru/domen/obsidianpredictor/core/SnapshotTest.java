package ru.domen.obsidianpredictor.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotTest {
    @Test
    void setBlockAndFillLayerAgree() {
        byte[] flagValues = {0, BlockClassifier.WATER_SOURCE, BlockClassifier.LAVA_SOURCE, BlockClassifier.AIRLIKE};
        int[] codes = new int[flagValues.length];
        for (int i = 0; i < codes.length; i++) codes[i] = FlagCodes.code(flagValues[i]);

        // area deliberately not aligned to chunks, with negative coordinates
        Snapshot snapshot = new Snapshot(-21, -60, 7, 13, -20, 40);
        for (int cx = snapshot.chunkMinX(); cx < snapshot.chunkMinX() + snapshot.chunksX(); cx++) {
            for (int cz = snapshot.chunkMinZ(); cz < snapshot.chunkMinZ() + snapshot.chunksZ(); cz++) {
                for (int sy = snapshot.sectionMinY(); sy <= snapshot.sectionMaxY(); sy++) {
                    snapshot.putSection(cx, cz, sy, null, codes[0]);
                }
                snapshot.markChunkPresent(cx, cz);
            }
        }
        byte[][][] expected = new byte[snapshot.sizeY][snapshot.sizeX][snapshot.sizeZ];
        Random random = new Random(42);
        for (int i = 0; i < 5000; i++) {
            int x = snapshot.minX + random.nextInt(snapshot.sizeX);
            int y = snapshot.minY + random.nextInt(snapshot.sizeY);
            int z = snapshot.minZ + random.nextInt(snapshot.sizeZ);
            int v = random.nextInt(codes.length);
            snapshot.setBlock(x, y, z, codes[v]);
            expected[y - snapshot.minY][x - snapshot.minX][z - snapshot.minZ] = flagValues[v];
        }
        byte[] layer = new byte[snapshot.sizeX * snapshot.sizeZ];
        for (int y = 0; y < snapshot.sizeY; y++) {
            snapshot.fillLayer(y, layer);
            for (int x = 0; x < snapshot.sizeX; x++) {
                for (int z = 0; z < snapshot.sizeZ; z++) {
                    assertEquals(expected[y][x][z], layer[x * snapshot.sizeZ + z], "at " + x + "," + y + "," + z);
                }
            }
        }
    }
}
