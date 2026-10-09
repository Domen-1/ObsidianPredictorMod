package ru.domen.obsidianpredictor.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block classification. Mirrors the constants and predicates at the top of
 * {@code src/obi_predict.py} of Obsidian-Predictor (and {@code rust/src/blocks.rs}).
 * Blocks are identified by registry id and the string property values Litematica stores.
 */
public final class BlockClassifier {
    public static final String WATER = "minecraft:water";
    public static final String LAVA = "minecraft:lava";
    public static final String BUBBLE_COLUMN = "minecraft:bubble_column";

    public static final List<String> TOUGH_BLOCKS = List.of(
            "minecraft:obsidian",
            "minecraft:vault",
            "minecraft:trial_spawner",
            "minecraft:crying_obsidian",
            "minecraft:ender_chest",
            "minecraft:reinforced_deepslate",
            "minecraft:end_portal_frame",
            "minecraft:anvil",
            "minecraft:chipped_anvil",
            "minecraft:damaged_anvil",
            "minecraft:enchanting_table",
            "minecraft:ancient_debris",
            "minecraft:netherite_block",
            "minecraft:heavy_core",
            "minecraft:respawn_anchor",
            "minecraft:creaking_heart"
    );

    public static final List<String> LOW_BLAST_RES_TERRAIN = List.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:podzol",
            "minecraft:coarse_dirt",
            "minecraft:mycelium",
            "minecraft:rooted_dirt",
            "minecraft:moss_block",
            "minecraft:mud",
            "minecraft:muddy_mangrove_roots",
            "minecraft:crimson_nylium",
            "minecraft:warped_nylium",
            "minecraft:netherrack",
            "minecraft:sand",
            "minecraft:red_sand",
            "minecraft:gravel",
            "minecraft:soul_sand",
            "minecraft:soul_soil",
            "minecraft:calcite",
            "minecraft:clay",
            "minecraft:dripstone_block",
            "minecraft:red_sandstone",
            "minecraft:sandstone"
    );

    public static final List<String> FRAGILE_BLOCKS = List.of(
            "minecraft:glow_lichen",
            "minecraft:mangrove_leaves",
            "minecraft:small_dripleaf",
            "minecraft:big_dripleaf",
            "minecraft:big_dripleaf_stem",
            "minecraft:pointed_dripstone",
            "minecraft:sculk_vein",
            "minecraft:tube_coral",
            "minecraft:brain_coral",
            "minecraft:bubble_coral",
            "minecraft:fire_coral",
            "minecraft:horn_coral",
            "minecraft:tube_coral_fan",
            "minecraft:brain_coral_fan",
            "minecraft:bubble_coral_fan",
            "minecraft:fire_coral_fan",
            "minecraft:horn_coral_fan",
            "minecraft:tube_coral_wall_fan",
            "minecraft:brain_coral_wall_fan",
            "minecraft:bubble_coral_wall_fan",
            "minecraft:fire_coral_wall_fan",
            "minecraft:horn_coral_wall_fan"
    );

    // One bit per predicate of the Python version.
    public static final byte WATER_SOURCE = 1;
    public static final byte LAVA_SOURCE = 1 << 1;
    public static final byte BLAST_RESISTANT = 1 << 2;
    public static final byte ATTRACTS_VERTICAL_FLOW = 1 << 3;
    public static final byte AIRLIKE = 1 << 4;
    public static final byte LOW_BLAST_RES = 1 << 5;

    private final Set<String> airlike;
    private final Set<String> lowBlastRes;
    private final Set<String> tough = new HashSet<>(TOUGH_BLOCKS);
    private final Set<String> fragile = new HashSet<>(FRAGILE_BLOCKS);

    public BlockClassifier() {
        // Same list as src/airlike_blocks.json of the Python version.
        try (InputStream in = BlockClassifier.class.getResourceAsStream("/obsidianpredictor/airlike_blocks.json")) {
            if (in == null) {
                throw new IllegalStateException("airlike_blocks.json is missing from the mod jar");
            }
            List<String> list = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<String>>() {}.getType());
            airlike = new HashSet<>(list);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read airlike_blocks.json", e);
        }
        lowBlastRes = new HashSet<>(airlike);
        lowBlastRes.addAll(LOW_BLAST_RES_TERRAIN);
    }

    private static boolean waterlogged(Map<String, String> properties) {
        return "true".equals(properties.get("waterlogged"));
    }

    private static int level(String id, Map<String, String> properties) {
        String level = properties.get("level");
        if (level == null) {
            throw new IllegalArgumentException(id + " has no 'level' property");
        }
        return Integer.parseInt(level.trim());
    }

    private boolean isBlastResistant(String id, Map<String, String> properties) {
        if (waterlogged(properties)) {
            return !fragile.contains(id);
        }
        return tough.contains(id);
    }

    private static boolean isWaterSource(String id, Map<String, String> properties) {
        return id.equals(WATER) && level(id, properties) == 0
                || waterlogged(properties)
                || id.equals(BUBBLE_COLUMN);
    }

    private static boolean isLavaSource(String id, Map<String, String> properties) {
        return id.equals(LAVA) && level(id, properties) == 0;
    }

    private boolean attractsVerticalFlow(String id, Map<String, String> properties) {
        return id.equals(WATER) || id.equals(BUBBLE_COLUMN)
                || airlike.contains(id)
                // ideally I should check that it's not something like copper grate but I don't care
                || waterlogged(properties);
    }

    public byte flags(String id, Map<String, String> properties) {
        int flags = 0;
        if (isWaterSource(id, properties)) flags |= WATER_SOURCE;
        if (isLavaSource(id, properties)) flags |= LAVA_SOURCE;
        if (isBlastResistant(id, properties)) flags |= BLAST_RESISTANT;
        if (attractsVerticalFlow(id, properties)) flags |= ATTRACTS_VERTICAL_FLOW;
        if (airlike.contains(id)) flags |= AIRLIKE;
        if (lowBlastRes.contains(id)) flags |= LOW_BLAST_RES;
        return (byte) flags;
    }
}
