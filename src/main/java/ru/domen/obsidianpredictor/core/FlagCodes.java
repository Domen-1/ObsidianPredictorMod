package ru.domen.obsidianpredictor.core;

import java.util.Arrays;

/**
 * Maps the few distinct block flag combinations that actually occur to 4-bit codes, so a
 * snapshot needs half a byte per block. Codes are handed out on first use and never change.
 */
public final class FlagCodes {
    public static final int MAX_CODES = 16;

    private static final byte[] flagsToCode = new byte[256];
    private static volatile byte[] codeToFlags = new byte[0];

    static {
        Arrays.fill(flagsToCode, (byte) -1);
    }

    private FlagCodes() {}

    public static synchronized int code(byte flags) {
        int code = flagsToCode[flags & 0xFF];
        if (code >= 0) {
            return code;
        }
        byte[] table = codeToFlags;
        if (table.length == MAX_CODES) {
            throw new IllegalStateException("more than " + MAX_CODES + " distinct block flag combinations");
        }
        code = table.length;
        byte[] grown = Arrays.copyOf(table, code + 1);
        grown[code] = flags;
        flagsToCode[flags & 0xFF] = (byte) code;
        codeToFlags = grown;
        return code;
    }

    /** Lookup table indexed by code; always has {@link #MAX_CODES} entries. */
    public static byte[] codeToFlags() {
        return Arrays.copyOf(codeToFlags, MAX_CODES);
    }

    public static int count() {
        return codeToFlags.length;
    }
}
