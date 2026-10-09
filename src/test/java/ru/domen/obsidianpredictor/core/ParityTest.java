package ru.domen.obsidianpredictor.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Same fixtures as the Rust port (rust/tests/expected): output of src/obi_predict.py with its
 * sets in insertion order, for Corner 1 = (100, -64, -200).
 */
class ParityTest {
    private static final Path DATA = Path.of(System.getProperty("obsidianpredictor.testData"));
    private static final int X0 = 100, Y0 = -64, Z0 = -200;

    private static byte[] paletteFlags(Litematic lit) {
        BlockClassifier classifier = new BlockClassifier();
        byte[] flags = new byte[lit.palette.size()];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = classifier.flags(lit.palette.get(i).id(), lit.palette.get(i).properties());
        }
        return flags;
    }

    private static List<String> expected(String name) throws Exception {
        return Files.readAllLines(DATA.resolve(name + ".txt"));
    }

    /** Predictor reading straight from the litematic. */
    @ParameterizedTest
    @CsvSource({"Terrain Scan Ocean, terrain_scan_ocean", "test, test"})
    void matchesPythonReference(String litematic, String expected) throws Exception {
        Litematic lit = Litematic.load(DATA.resolve(litematic + ".litematic"));
        byte[] flags = paletteFlags(lit);
        Predictor predictor = new Predictor(lit.sizeX, lit.sizeY, lit.sizeZ, (y, out) -> {
            for (int z = 0; z < lit.sizeZ; z++) {
                for (int x = 0; x < lit.sizeX; x++) {
                    out[x * lit.sizeZ + z] = flags[lit.get(x, y, z)];
                }
            }
        });
        Predictor.Result result = predictor.run(done -> {}, () -> false);
        long ox = Math.min(X0, X0 + lit.width + 1);
        long oy = Math.min(Y0, Y0 + lit.height + 1);
        long oz = Math.min(Z0, Z0 + lit.length + 1);
        assertEquals(expected(expected), Predictor.waypoints(result, ox, oy, oz));
    }

    /** Same data placed into world coordinates through a Snapshot, as the mod does. */
    @ParameterizedTest
    @CsvSource({"Terrain Scan Ocean, terrain_scan_ocean", "test, test"})
    void snapshotMatchesPythonReference(String litematic, String expected) throws Exception {
        Litematic lit = Litematic.load(DATA.resolve(litematic + ".litematic"));
        byte[] flags = paletteFlags(lit);
        int ox = Math.min(X0, X0 + lit.width + 1);
        int oy = Math.min(Y0, Y0 + lit.height + 1);
        int oz = Math.min(Z0, Z0 + lit.length + 1);
        Snapshot snapshot = new Snapshot(ox, oy, oz, ox + lit.sizeX - 1, oy + lit.sizeY - 1, oz + lit.sizeZ - 1);
        int air = FlagCodes.code(new BlockClassifier().flags("minecraft:air", java.util.Map.of()));
        for (int cx = snapshot.chunkMinX(); cx < snapshot.chunkMinX() + snapshot.chunksX(); cx++) {
            for (int cz = snapshot.chunkMinZ(); cz < snapshot.chunkMinZ() + snapshot.chunksZ(); cz++) {
                for (int sy = snapshot.sectionMinY(); sy <= snapshot.sectionMaxY(); sy++) {
                    byte[] nibbles = new byte[2048];
                    int first = -1;
                    boolean uniform = true;
                    for (int i = 0; i < 4096; i++) {
                        int x = (cx << 4) + (i & 15), y = (sy << 4) + (i >> 8), z = (cz << 4) + ((i >> 4) & 15);
                        int code = snapshot.contains(x, y, z)
                                ? FlagCodes.code(flags[lit.get(x - ox, y - oy, z - oz)])
                                : air;
                        Snapshot.setNibble(nibbles, i, code);
                        if (first < 0) first = code;
                        uniform &= code == first;
                    }
                    snapshot.putSection(cx, cz, sy, uniform ? null : nibbles, first);
                }
                snapshot.markChunkPresent(cx, cz);
            }
        }
        Predictor.Result result = new Predictor(snapshot.sizeX, snapshot.sizeY, snapshot.sizeZ, snapshot)
                .run(done -> {}, () -> false);
        assertEquals(expected(expected), Predictor.waypoints(result, ox, oy, oz));
    }
}
