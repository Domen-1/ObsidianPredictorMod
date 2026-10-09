//! Block classification. Mirrors the constants and predicates at the top of `src/obi_predict.py`
//! in https://github.com/EQUENOS-2/Obsidian-Predictor.

use std::collections::{HashMap, HashSet};

pub const WATER: &str = "minecraft:water";
pub const LAVA: &str = "minecraft:lava";
pub const BUBBLE_COLUMN: &str = "minecraft:bubble_column";

pub const TOUGH_BLOCKS: &[&str] = &[
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
    "minecraft:creaking_heart",
];

pub const LOW_BLAST_RES_TERRAIN: &[&str] = &[
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
    "minecraft:sandstone",
];

pub const FRAGILE_BLOCKS: &[&str] = &[
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
    "minecraft:horn_coral_wall_fan",
];

/// Same list as `src/airlike_blocks.json` of the Python version; shared with the mod.
const AIRLIKE_JSON: &str = include_str!("../../src/main/resources/obsidianpredictor/airlike_blocks.json");

// Per-palette-entry flags, one bit per predicate of the Python version.
pub const WATER_SOURCE: u8 = 1 << 0;
pub const LAVA_SOURCE: u8 = 1 << 1;
pub const BLAST_RESISTANT: u8 = 1 << 2;
pub const ATTRACTS_VERTICAL_FLOW: u8 = 1 << 3;
pub const AIRLIKE: u8 = 1 << 4;
pub const LOW_BLAST_RES: u8 = 1 << 5;
/// Palette index outside the palette (corrupted file).
pub const INVALID: u8 = 1 << 7;

pub struct BlockState {
    pub id: String,
    pub properties: HashMap<String, String>,
}

impl BlockState {
    fn waterlogged(&self) -> bool {
        self.properties.get("waterlogged").is_some_and(|v| v == "true")
    }

    fn level(&self) -> Result<i64, String> {
        let level = self
            .properties
            .get("level")
            .ok_or_else(|| format!("{} has no 'level' property", self.id))?;
        level
            .trim()
            .parse()
            .map_err(|_| format!("{} has invalid level '{level}'", self.id))
    }
}

pub struct Classifier {
    airlike: HashSet<String>,
    low_blast_res: HashSet<String>,
    tough: HashSet<String>,
    fragile: HashSet<String>,
}

impl Classifier {
    pub fn new() -> Self {
        let airlike: HashSet<String> =
            serde_json::from_str(AIRLIKE_JSON).expect("airlike_blocks.json is a list of strings");
        let to_set = |list: &[&str]| list.iter().map(|s| s.to_string()).collect::<HashSet<_>>();
        let mut low_blast_res = airlike.clone();
        low_blast_res.extend(to_set(LOW_BLAST_RES_TERRAIN));
        Self {
            airlike,
            low_blast_res,
            tough: to_set(TOUGH_BLOCKS),
            fragile: to_set(FRAGILE_BLOCKS),
        }
    }

    fn is_blast_resistant(&self, block: &BlockState) -> bool {
        if block.waterlogged() {
            return !self.fragile.contains(&block.id);
        }
        self.tough.contains(&block.id)
    }

    fn is_water_source(&self, block: &BlockState) -> Result<bool, String> {
        Ok(block.id == WATER && block.level()? == 0
            || block.waterlogged()
            || block.id == BUBBLE_COLUMN)
    }

    fn is_lava_source(&self, block: &BlockState) -> Result<bool, String> {
        Ok(block.id == LAVA && block.level()? == 0)
    }

    fn attracts_vertical_flow(&self, block: &BlockState) -> bool {
        block.id == WATER
            || block.id == BUBBLE_COLUMN
            || self.airlike.contains(&block.id)
            // ideally I should check that it's not something like copper grate but I don't care
            || block.waterlogged()
    }

    pub fn flags(&self, block: &BlockState) -> Result<u8, String> {
        let mut flags = 0;
        if self.is_water_source(block)? {
            flags |= WATER_SOURCE;
        }
        if self.is_lava_source(block)? {
            flags |= LAVA_SOURCE;
        }
        if self.is_blast_resistant(block) {
            flags |= BLAST_RESISTANT;
        }
        if self.attracts_vertical_flow(block) {
            flags |= ATTRACTS_VERTICAL_FLOW;
        }
        if self.airlike.contains(&block.id) {
            flags |= AIRLIKE;
        }
        if self.low_blast_res.contains(&block.id) {
            flags |= LOW_BLAST_RES;
        }
        Ok(flags)
    }
}
