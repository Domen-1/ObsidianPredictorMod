package ru.domen.obsidianpredictor.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import ru.domen.obsidianpredictor.core.BlockClassifier;
import ru.domen.obsidianpredictor.core.FlagCodes;

import java.util.HashMap;
import java.util.Map;

/** Snapshot code of every block state, computed once from the block lists. */
public final class StateFlags {
    private static volatile byte[] codeById;
    private static int airCode;

    private StateFlags() {}

    /** Must run after all mods registered their blocks, i.e. not in onInitialize. */
    public static synchronized void init() {
        if (codeById != null) {
            return;
        }
        BlockClassifier classifier = new BlockClassifier();
        byte[] codes = new byte[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            codes[Block.getId(state)] = (byte) FlagCodes.code(classifier.flags(id, properties(state)));
        }
        airCode = codes[Block.getId(Blocks.AIR.defaultBlockState())];
        codeById = codes;
    }

    /** Property values as Litematica stores them, e.g. level=0, waterlogged=true. */
    public static Map<String, String> properties(BlockState state) {
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), valueName(state, property));
        }
        return properties;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static int code(BlockState state) {
        return codeById[Block.getId(state)];
    }

    public static int airCode() {
        return airCode;
    }
}
