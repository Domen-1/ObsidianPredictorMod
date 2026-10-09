package ru.domen.obsidianpredictor.world;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import ru.domen.obsidianpredictor.core.Snapshot;

/** Turns a chunk section into snapshot codes. Safe off-thread on a container nobody else uses. */
public final class SectionEncoder {
    /** {@code nibbles == null} means every block has {@code uniformCode}. */
    public record Encoded(byte[] nibbles, int uniformCode) {
        public static Encoded air() {
            return new Encoded(null, StateFlags.airCode());
        }
    }

    private SectionEncoder() {}

    public static Encoded encode(PalettedContainer<BlockState> states) {
        int[] first = {-1};
        boolean[] uniform = {true};
        states.forEachInPalette(state -> {
            int code = StateFlags.code(state);
            if (first[0] < 0) {
                first[0] = code;
            } else if (code != first[0]) {
                uniform[0] = false;
            }
        });
        if (uniform[0]) {
            return new Encoded(null, first[0] < 0 ? StateFlags.airCode() : first[0]);
        }
        // The palette may still list states that no longer occur, so check the real blocks.
        byte[] nibbles = new byte[2048];
        int firstCode = StateFlags.code(states.get(0, 0, 0));
        boolean allSame = true;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int code = StateFlags.code(states.get(x, y, z));
                    allSame &= code == firstCode;
                    Snapshot.setNibble(nibbles, (y << 8) | (z << 4) | x, code);
                }
            }
        }
        return allSame ? new Encoded(null, firstCode) : new Encoded(nibbles, firstCode);
    }
}
