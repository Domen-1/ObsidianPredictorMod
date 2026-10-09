//! Port of `ObsidianPredictor` from `src/obi_predict.py`. The simulation logic is kept
//! line-for-line; only the data structures differ (flat grids instead of dicts/sets).
//!
//! Where Python iterates or pops a `set`, the result depends on CPython's hash ordering.
//! Here those containers use insertion order instead, so outputs are deterministic. This only
//! changes which point of a lava patch / blast-resistant group gets the waypoint, not which
//! patches and groups are found.

use std::collections::{HashSet, VecDeque};

use crate::blocks::{
    AIRLIKE, ATTRACTS_VERTICAL_FLOW, BLAST_RESISTANT, Classifier, INVALID, LAVA_SOURCE,
    LOW_BLAST_RES, WATER_SOURCE,
};
use crate::litematic::Region;

const NONE: u32 = u32::MAX;
const TOMB: u32 = u32::MAX;

/// Set of layer cells with insertion order: `pop` takes the oldest element and re-adding a
/// removed element appends it, like a dict-backed set.
struct OrderedCellSet {
    slot: Vec<u32>,
    items: Vec<u32>,
    head: usize,
    len: usize,
}

impl OrderedCellSet {
    fn new(cells: usize) -> Self {
        Self {
            slot: vec![NONE; cells],
            items: Vec::new(),
            head: 0,
            len: 0,
        }
    }

    fn add(&mut self, cell: usize) {
        if self.slot[cell] == NONE {
            self.slot[cell] = self.items.len() as u32;
            self.items.push(cell as u32);
            self.len += 1;
        }
    }

    fn contains(&self, cell: usize) -> bool {
        self.slot[cell] != NONE
    }

    fn remove(&mut self, cell: usize) {
        let slot = self.slot[cell] as usize;
        self.items[slot] = TOMB;
        self.slot[cell] = NONE;
        self.len -= 1;
    }

    fn pop(&mut self) -> usize {
        while self.items[self.head] == TOMB {
            self.head += 1;
        }
        let cell = self.items[self.head] as usize;
        self.head += 1;
        self.slot[cell] = NONE;
        self.len -= 1;
        cell
    }

    fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Only valid when empty: drops tombstones so a reused set starts fresh.
    fn reset(&mut self) {
        debug_assert!(self.is_empty());
        self.items.clear();
        self.head = 0;
    }
}

struct CellFlags {
    flag: Vec<bool>,
    touched: Vec<u32>,
}

impl CellFlags {
    fn new(cells: usize) -> Self {
        Self {
            flag: vec![false; cells],
            touched: Vec::new(),
        }
    }

    fn set(&mut self, cell: usize) {
        if !self.flag[cell] {
            self.flag[cell] = true;
            self.touched.push(cell as u32);
        }
    }

    fn clear(&mut self) {
        for &cell in &self.touched {
            self.flag[cell as usize] = false;
        }
        self.touched.clear();
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Color {
    Black = 0,
    Red = 12,
}

pub fn format_waypoint(x: i64, y: i64, z: i64, color: Color, name: &str) -> String {
    let short_name: String = name.chars().take(2).collect();
    format!(
        "waypoint:{name}:{short_name}:{x}:{y}:{z}:{}:false:0:gui.xaero_default:false:0:0:true",
        color as i32
    )
}

fn is_isolated(point: (i64, i64), points: &[(i64, i64)]) -> bool {
    let (x0, z0) = point;
    for &(x, z) in points {
        if (x - x0).abs() + (z - z0).abs() < 8 {
            return false;
        }
    }
    true
}

pub struct ObsidianPredictor<'a> {
    region: &'a Region,
    /// Flags per palette index; indices past the palette map to `INVALID`.
    palette_flags: Vec<u8>,

    pub origin_x: i64,
    pub origin_y: i64,
    pub origin_z: i64,

    pub size_x: usize,
    pub size_y: usize,
    pub size_z: usize,

    /// `layer_matrix[x, z]`, stored at `x * size_z + z`.
    layer_matrix: Vec<u8>,
    /// Block flags of the layer being processed (y) and of the one below (y - 1).
    layer: Vec<u8>,
    layer_below: Vec<u8>,
    /// `flooded_lava[y]` and `flooded_lava[y - 1]`; deeper layers are never touched.
    flooded_lava: OrderedCellSet,
    flooded_lava_below: OrderedCellSet,
    focused_flooded_lava: CellFlags,
    focused_flooded_lava_below: CellFlags,

    /// `blast_resistant_markers` as an insertion-ordered set of packed positions.
    blast_resistant_order: Vec<u64>,
    blast_resistant_markers: HashSet<u64>,
    lava_markers: Vec<(usize, usize, usize)>,

    // scratch buffers reused between layers
    is_source: Vec<bool>,
    sources: Vec<u32>,
    flows: Vec<u32>,
    water_queue: VecDeque<(i64, i64, u8)>,
    lava_queue: VecDeque<(i64, i64)>,
}

impl<'a> ObsidianPredictor<'a> {
    pub fn new(
        x0: i64,
        y0: i64,
        z0: i64,
        region: &'a Region,
        classifier: &Classifier,
    ) -> Result<Self, String> {
        let mut palette_flags = vec![INVALID; 1 << region.bits_per_block()];
        for (i, block) in region.palette.iter().enumerate() {
            palette_flags[i] = classifier.flags(block)?;
        }
        let cells = region.size_x * region.size_z;
        Ok(Self {
            region,
            palette_flags,
            origin_x: x0.min(x0 + region.width as i64 + 1),
            origin_y: y0.min(y0 + region.height as i64 + 1),
            origin_z: z0.min(z0 + region.length as i64 + 1),
            size_x: region.size_x,
            size_y: region.size_y,
            size_z: region.size_z,
            layer_matrix: vec![9; cells],
            layer: vec![0; cells],
            layer_below: vec![0; cells],
            flooded_lava: OrderedCellSet::new(cells),
            flooded_lava_below: OrderedCellSet::new(cells),
            focused_flooded_lava: CellFlags::new(cells),
            focused_flooded_lava_below: CellFlags::new(cells),
            blast_resistant_order: Vec::new(),
            blast_resistant_markers: HashSet::new(),
            lava_markers: Vec::new(),
            is_source: vec![false; cells],
            sources: Vec::new(),
            flows: Vec::new(),
            water_queue: VecDeque::new(),
            lava_queue: VecDeque::new(),
        })
    }

    pub fn to_world_coordinates(&self, x: usize, y: usize, z: usize) -> (i64, i64, i64) {
        (
            self.origin_x + x as i64,
            self.origin_y + y as i64,
            self.origin_z + z as i64,
        )
    }

    #[inline]
    fn cell(&self, x: usize, z: usize) -> usize {
        x * self.size_z + z
    }

    fn pack(&self, x: usize, y: usize, z: usize) -> u64 {
        ((y * self.size_x + x) * self.size_z + z) as u64
    }

    fn unpack(&self, key: u64) -> (usize, usize, usize) {
        let key = key as usize;
        let z = key % self.size_z;
        let rest = key / self.size_z;
        (rest % self.size_x, rest / self.size_x, z)
    }

    fn load_layer(&self, y: usize, out: &mut [u8]) -> Result<(), String> {
        let mut seen = 0u8;
        for z in 0..self.size_z {
            for x in 0..self.size_x {
                let flags = self.palette_flags[self.region.get(x, y, z)];
                seen |= flags;
                out[x * self.size_z + z] = flags;
            }
        }
        if seen & INVALID != 0 {
            return Err(format!("layer {y} references a block outside the palette"));
        }
        Ok(())
    }

    /// Simulates liquid propagation from a given water block, updating `layer_matrix`
    /// with new water values.
    fn simulate_water(&mut self, x: usize, y: usize, z: usize, water_level: u8, check_collisions: bool) {
        let (size_x, size_z) = (self.size_x as i64, self.size_z as i64);
        self.water_queue.clear();
        self.water_queue.push_back((x as i64, z as i64, water_level));
        let mut terminal_water_level = 7;
        let mut found_lava = false;

        while let Some((x, z, water_level)) = self.water_queue.pop_front() {
            if !(0 <= x && x < size_x) || !(0 <= z && z < size_z) {
                continue;
            }
            let cell = self.cell(x as usize, z as usize);
            if water_level >= self.layer_matrix[cell] {
                continue;
            }
            let block = self.layer[cell];
            if block & LAVA_SOURCE != 0 {
                self.flooded_lava.add(cell);
            }

            if block & AIRLIKE == 0 && check_collisions {
                continue;
            }

            self.layer_matrix[cell] = water_level;
            // this is needed to stop propagation
            if water_level == terminal_water_level || y == 0 {
                continue;
            }
            let next_level = (water_level + 1) % 8;
            let block_below = self.layer_below[cell];
            // check that water can flow sideways
            if water_level == 0 || block_below & ATTRACTS_VERTICAL_FLOW == 0 {
                self.water_queue.push_back((x + 1, z, next_level));
                self.water_queue.push_back((x - 1, z, next_level));
                self.water_queue.push_back((x, z + 1, next_level));
                self.water_queue.push_back((x, z - 1, next_level));
            } else if check_collisions {
                // It is sufficient to stop processing for water of higher level than this.
                // This is the level at which the given stream encountered a height drop.
                terminal_water_level = water_level;
            }
            // update lava marker data
            // (Python also checks `(x, z) not in self.flooded_lava`, which tests the dict's
            // y keys and is therefore always true.)
            if block_below & LAVA_SOURCE != 0 {
                self.flooded_lava_below.add(cell);
                if !found_lava {
                    found_lava = true;
                    self.focused_flooded_lava_below.set(cell);
                }
            }
        }
    }

    /// Unites `flooded_lava[y]` into path-connected components and distributes markers on
    /// them. Flushes `flooded_lava[y]` after being invoked.
    fn resolve_lava_markers(&mut self, y: usize) {
        let (size_x, size_z) = (self.size_x as i64, self.size_z as i64);
        let in_bounds = |x: i64, z: i64| 0 <= x && x < size_x && 0 <= z && z < size_z;
        while !self.flooded_lava.is_empty() {
            let mut markers: Vec<(i64, i64)> = Vec::new();
            let initial = self.flooded_lava.pop();
            let initial_lava = ((initial / self.size_z) as i64, (initial % self.size_z) as i64);
            self.lava_queue.clear();
            self.lava_queue.push_back(initial_lava);
            // exhaust the connected component
            while let Some((x, z)) = self.lava_queue.pop_front() {
                for point in [(x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)] {
                    if !in_bounds(point.0, point.1) {
                        continue;
                    }
                    let cell = self.cell(point.0 as usize, point.1 as usize);
                    if self.flooded_lava.contains(cell) {
                        self.lava_queue.push_back(point);
                        self.flooded_lava.remove(cell);
                    }
                    if self.focused_flooded_lava.flag[cell] && is_isolated(point, &markers) {
                        markers.push(point);
                    }
                }
            }
            // decide which markers to use
            if markers.is_empty() {
                markers.push(initial_lava);
            }
            for (x, z) in markers {
                self.lava_markers.push((x as usize, y, z as usize));
            }
        }
    }

    /// Inflates all water sources at the given y level, processes downwards flow from
    /// previous layers, marks flooded lava sources, scans for blast resistant blocks.
    fn process_layer(&mut self, y: usize) {
        self.sources.clear();
        self.flows.clear();

        for x in 0..self.size_x {
            for z in 0..self.size_z {
                let cell = self.cell(x, z);
                let block = self.layer[cell];
                if block & WATER_SOURCE != 0 {
                    // we will inflate this as if the eater destroyed all surroundings
                    self.sources.push(cell as u32);
                    self.is_source[cell] = true;
                } else if block & LOW_BLAST_RES != 0 && self.layer_matrix[cell] < 9 {
                    // we will inherit the flow from directly above
                    self.flows.push(cell as u32);
                }
                // bonus check
                if block & BLAST_RESISTANT != 0 {
                    let key = self.pack(x, y, z);
                    self.blast_resistant_order.push(key);
                    self.blast_resistant_markers.insert(key);
                }
                // we should flush all values before simulating this layer
                self.layer_matrix[cell] = 9;
            }
        }
        // layer_matrix is flushed; simulate water
        for i in 0..self.sources.len() {
            let cell = self.sources[i] as usize;
            let (x, z) = (cell / self.size_z, cell % self.size_z);
            // small optimization to process oceans faster
            let inner = x + 1 < self.size_x
                && self.is_source[cell + self.size_z]
                && x >= 1
                && self.is_source[cell - self.size_z]
                && z + 1 < self.size_z
                && self.is_source[cell + 1]
                && z >= 1
                && self.is_source[cell - 1];
            if inner {
                self.layer_matrix[cell] = 0;
                continue;
            }
            // if we're here, it's not an inner water source
            self.simulate_water(x, y, z, 0, false);
        }

        for i in 0..self.flows.len() {
            let cell = self.flows[i] as usize;
            self.simulate_water(cell / self.size_z, y, cell % self.size_z, 8, true);
        }

        for i in 0..self.sources.len() {
            self.is_source[self.sources[i] as usize] = false;
        }

        // this also resets lava layer data
        self.resolve_lava_markers(y);
    }

    /// Processes all layers top to bottom. `progress` receives the fraction done.
    pub fn run(&mut self, mut progress: impl FnMut(f64)) -> Result<(), String> {
        if self.size_y == 0 {
            return Ok(());
        }
        let mut layer = std::mem::take(&mut self.layer);
        let mut layer_below = std::mem::take(&mut self.layer_below);
        self.load_layer(self.size_y - 1, &mut layer)?;
        if self.size_y >= 2 {
            self.load_layer(self.size_y - 2, &mut layer_below)?;
        }
        self.layer = layer;
        self.layer_below = layer_below;

        for y in (0..self.size_y).rev() {
            self.process_layer(y);
            progress((self.size_y - y) as f64 / self.size_y as f64);

            // shift the window one layer down: y - 1 becomes current, y - 2 becomes below
            std::mem::swap(&mut self.layer, &mut self.layer_below);
            if y >= 2 {
                let mut below = std::mem::take(&mut self.layer_below);
                self.load_layer(y - 2, &mut below)?;
                self.layer_below = below;
            }
            self.flooded_lava.reset();
            std::mem::swap(&mut self.flooded_lava, &mut self.flooded_lava_below);
            self.focused_flooded_lava.clear();
            std::mem::swap(&mut self.focused_flooded_lava, &mut self.focused_flooded_lava_below);
        }
        Ok(())
    }

    /// Groups blast-resistant blocks into 6-connected components, one marker per group.
    fn get_blast_resistant_markers(&mut self) -> Vec<(usize, usize, usize)> {
        let mut markers = Vec::new();
        let mut stack = Vec::new();
        for i in 0..self.blast_resistant_order.len() {
            let key = self.blast_resistant_order[i];
            if !self.blast_resistant_markers.remove(&key) {
                continue;
            }
            let point = self.unpack(key);
            markers.push(point);
            stack.push(point);
            while let Some((x, y, z)) = stack.pop() {
                let neighbours = [
                    (x + 1 < self.size_x).then(|| (x + 1, y, z)),
                    x.checked_sub(1).map(|x| (x, y, z)),
                    (y + 1 < self.size_y).then(|| (x, y + 1, z)),
                    y.checked_sub(1).map(|y| (x, y, z)),
                    (z + 1 < self.size_z).then(|| (x, y, z + 1)),
                    z.checked_sub(1).map(|z| (x, y, z)),
                ];
                for (nx, ny, nz) in neighbours.into_iter().flatten() {
                    if self.blast_resistant_markers.remove(&self.pack(nx, ny, nz)) {
                        stack.push((nx, ny, nz));
                    }
                }
            }
        }
        markers
    }

    /// Waypoint lines in the same order and format as `save_waypoints` in Python.
    pub fn waypoints(&mut self) -> Vec<String> {
        let blastres_markers = self.get_blast_resistant_markers();
        let mut lines = Vec::with_capacity(self.lava_markers.len() + blastres_markers.len());
        for &(x, y, z) in &self.lava_markers {
            let (x, y, z) = self.to_world_coordinates(x, y, z);
            lines.push(format_waypoint(x, y, z, Color::Red, "x"));
        }
        for (x, y, z) in blastres_markers {
            let (x, y, z) = self.to_world_coordinates(x, y, z);
            lines.push(format_waypoint(x, y, z, Color::Black, "x"));
        }
        lines
    }

    pub fn lava_marker_count(&self) -> usize {
        self.lava_markers.len()
    }
}

#[cfg(test)]
mod tests {
    use super::{Color, format_waypoint, is_isolated};

    #[test]
    fn waypoint_format_matches_python() {
        assert_eq!(
            format_waypoint(-12, 63, 450, Color::Red, "x"),
            "waypoint:x:x:-12:63:450:12:false:0:gui.xaero_default:false:0:0:true"
        );
        assert_eq!(
            format_waypoint(0, -64, 0, Color::Black, "x"),
            "waypoint:x:x:0:-64:0:0:false:0:gui.xaero_default:false:0:0:true"
        );
    }

    #[test]
    fn isolation_uses_manhattan_distance_below_8() {
        assert!(is_isolated((0, 0), &[(4, 4)]));
        assert!(!is_isolated((0, 0), &[(4, 3)]));
        assert!(is_isolated((0, 0), &[]));
    }
}
