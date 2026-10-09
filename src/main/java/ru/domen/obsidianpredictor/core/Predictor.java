package ru.domen.obsidianpredictor.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import static ru.domen.obsidianpredictor.core.BlockClassifier.AIRLIKE;
import static ru.domen.obsidianpredictor.core.BlockClassifier.ATTRACTS_VERTICAL_FLOW;
import static ru.domen.obsidianpredictor.core.BlockClassifier.BLAST_RESISTANT;
import static ru.domen.obsidianpredictor.core.BlockClassifier.LAVA_SOURCE;
import static ru.domen.obsidianpredictor.core.BlockClassifier.LOW_BLAST_RES;
import static ru.domen.obsidianpredictor.core.BlockClassifier.WATER_SOURCE;

/**
 * Port of {@code ObsidianPredictor} from {@code src/obi_predict.py}, line for line, via the
 * Rust port ({@code rust/src/predictor.rs}). Where Python iterates or pops a {@code set}, this
 * uses insertion order, exactly like the Rust port, so all three agree on the same input.
 * Coordinates are region-local: (0, 0, 0) is the minimum corner of the area.
 */
public final class Predictor {
    /** Block flags of one layer, stored at {@code x * sizeZ + z}. */
    public interface LayerSource {
        void fillLayer(int y, byte[] out);
    }

    /** Marker positions as flat (x, y, z) triples, region-local, in output order. */
    public record Result(int[] lava, int[] blastResistant) {
        public int lavaCount() {
            return lava.length / 3;
        }

        public int blastResistantCount() {
            return blastResistant.length / 3;
        }
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("prediction cancelled", null, false, false);
        }
    }

    private static final int NONE = -1;
    private static final int TOMB = -1;

    /** Set of layer cells with insertion order; pop takes the oldest, re-adding appends. */
    private static final class OrderedCellSet {
        final int[] slot;
        final IntArrayList items = new IntArrayList();
        int head;
        int len;

        OrderedCellSet(int cells) {
            slot = new int[cells];
            Arrays.fill(slot, NONE);
        }

        void add(int cell) {
            if (slot[cell] == NONE) {
                slot[cell] = items.size();
                items.add(cell);
                len++;
            }
        }

        boolean contains(int cell) {
            return slot[cell] != NONE;
        }

        void remove(int cell) {
            items.set(slot[cell], TOMB);
            slot[cell] = NONE;
            len--;
        }

        int pop() {
            while (items.getInt(head) == TOMB) {
                head++;
            }
            int cell = items.getInt(head++);
            slot[cell] = NONE;
            len--;
            return cell;
        }

        boolean isEmpty() {
            return len == 0;
        }

        /** Only valid when empty: drops tombstones so a reused set starts fresh. */
        void reset() {
            items.clear();
            head = 0;
        }
    }

    private static final class CellFlags {
        final boolean[] flag;
        final IntArrayList touched = new IntArrayList();

        CellFlags(int cells) {
            flag = new boolean[cells];
        }

        void set(int cell) {
            if (!flag[cell]) {
                flag[cell] = true;
                touched.add(cell);
            }
        }

        void clear() {
            for (int i = 0; i < touched.size(); i++) {
                flag[touched.getInt(i)] = false;
            }
            touched.clear();
        }
    }

    private final LayerSource source;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;

    /** {@code layer_matrix[x, z]}, stored at {@code x * sizeZ + z}. */
    private final byte[] layerMatrix;
    /** Block flags of the layer being processed (y) and of the one below (y - 1). */
    private byte[] layer;
    private byte[] layerBelow;
    /** {@code flooded_lava[y]} and {@code flooded_lava[y - 1]}; deeper layers are never touched. */
    private OrderedCellSet floodedLava;
    private OrderedCellSet floodedLavaBelow;
    private CellFlags focusedFloodedLava;
    private CellFlags focusedFloodedLavaBelow;

    /** {@code blast_resistant_markers} as an insertion-ordered set of packed positions. */
    private final LongArrayList blastResistantOrder = new LongArrayList();
    private final LongOpenHashSet blastResistantMarkers = new LongOpenHashSet();
    private final IntArrayList lavaMarkers = new IntArrayList();

    // scratch buffers reused between layers
    private final boolean[] isSource;
    private final IntArrayList sources = new IntArrayList();
    private final IntArrayList flows = new IntArrayList();
    /** Queue entries pack (x + 1, z + 1, level) into a long; see {@link #queued}. */
    private final LongArrayFIFOQueue waterQueue = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue lavaQueue = new LongArrayFIFOQueue();

    public Predictor(int sizeX, int sizeY, int sizeZ, LayerSource source) {
        this.source = source;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int cells = Math.multiplyExact(sizeX, sizeZ);
        layerMatrix = new byte[cells];
        Arrays.fill(layerMatrix, (byte) 9);
        layer = new byte[cells];
        layerBelow = new byte[cells];
        floodedLava = new OrderedCellSet(cells);
        floodedLavaBelow = new OrderedCellSet(cells);
        focusedFloodedLava = new CellFlags(cells);
        focusedFloodedLavaBelow = new CellFlags(cells);
        isSource = new boolean[cells];
    }

    /** x and z may be -1 or size (out of bounds), hence the +1; level fits in 4 bits. */
    private static long queued(int x, int z, int level) {
        return ((long) (x + 1) << 32) | ((long) (z + 1) << 4) | level;
    }

    private int cell(int x, int z) {
        return x * sizeZ + z;
    }

    private long pack(int x, int y, int z) {
        return ((long) y * sizeX + x) * sizeZ + z;
    }

    private static boolean isIsolated(int px, int pz, IntArrayList markers) {
        for (int i = 0; i < markers.size(); i += 2) {
            if (Math.abs(markers.getInt(i) - px) + Math.abs(markers.getInt(i + 1) - pz) < 8) {
                return false;
            }
        }
        return true;
    }

    /**
     * Simulates liquid propagation from a given water block, updating {@code layer_matrix}
     * with new water values.
     */
    private void simulateWater(int startX, int y, int startZ, int startLevel, boolean checkCollisions) {
        waterQueue.clear();
        waterQueue.enqueue(queued(startX, startZ, startLevel));
        int terminalWaterLevel = 7;
        boolean foundLava = false;

        while (!waterQueue.isEmpty()) {
            long node = waterQueue.dequeueLong();
            int x = (int) (node >>> 32) - 1, z = (int) ((node & 0xFFFFFFFFL) >>> 4) - 1, waterLevel = (int) (node & 15);
            if (!(0 <= x && x < sizeX) || !(0 <= z && z < sizeZ)) {
                continue;
            }
            int cell = cell(x, z);
            if (waterLevel >= layerMatrix[cell]) {
                continue;
            }
            byte block = layer[cell];
            if ((block & LAVA_SOURCE) != 0) {
                floodedLava.add(cell);
            }

            if ((block & AIRLIKE) == 0 && checkCollisions) {
                continue;
            }

            layerMatrix[cell] = (byte) waterLevel;
            // this is needed to stop propagation
            if (waterLevel == terminalWaterLevel || y <= 0) {
                continue;
            }
            int nextLevel = (waterLevel + 1) % 8;
            byte blockBelow = layerBelow[cell];
            // check that water can flow sideways
            if (waterLevel == 0 || (blockBelow & ATTRACTS_VERTICAL_FLOW) == 0) {
                waterQueue.enqueue(queued(x + 1, z, nextLevel));
                waterQueue.enqueue(queued(x - 1, z, nextLevel));
                waterQueue.enqueue(queued(x, z + 1, nextLevel));
                waterQueue.enqueue(queued(x, z - 1, nextLevel));
            } else if (checkCollisions) {
                // It is sufficient to stop processing for water of higher level than this.
                // This is the level at which the given stream encountered a height drop.
                terminalWaterLevel = waterLevel;
            }
            // update lava marker data
            // (Python also checks `(x, z) not in self.flooded_lava`, which tests the dict's
            // y keys and is therefore always true.)
            if ((blockBelow & LAVA_SOURCE) != 0) {
                floodedLavaBelow.add(cell);
                if (!foundLava) {
                    foundLava = true;
                    focusedFloodedLavaBelow.set(cell);
                }
            }
        }
    }

    /**
     * Unites {@code flooded_lava[y]} into path-connected components and distributes markers on
     * them. Flushes {@code flooded_lava[y]} after being invoked.
     */
    private void resolveLavaMarkers(int y) {
        IntArrayList markers = new IntArrayList();
        while (!floodedLava.isEmpty()) {
            markers.clear();
            int initial = floodedLava.pop();
            int initialX = initial / sizeZ, initialZ = initial % sizeZ;
            lavaQueue.clear();
            lavaQueue.enqueue(queued(initialX, initialZ, 0));
            // exhaust the connected component
            while (!lavaQueue.isEmpty()) {
                long p = lavaQueue.dequeueLong();
                int x = (int) (p >>> 32) - 1, z = (int) ((p & 0xFFFFFFFFL) >>> 4) - 1;
                for (int dir = 0; dir < 4; dir++) {
                    int px = dir == 0 ? x + 1 : dir == 1 ? x - 1 : x;
                    int pz = dir == 2 ? z + 1 : dir == 3 ? z - 1 : z;
                    if (!(0 <= px && px < sizeX && 0 <= pz && pz < sizeZ)) {
                        continue;
                    }
                    int cell = cell(px, pz);
                    if (floodedLava.contains(cell)) {
                        lavaQueue.enqueue(queued(px, pz, 0));
                        floodedLava.remove(cell);
                    }
                    if (focusedFloodedLava.flag[cell] && isIsolated(px, pz, markers)) {
                        markers.add(px);
                        markers.add(pz);
                    }
                }
            }
            // decide which markers to use
            if (markers.isEmpty()) {
                markers.add(initialX);
                markers.add(initialZ);
            }
            for (int i = 0; i < markers.size(); i += 2) {
                lavaMarkers.add(markers.getInt(i));
                lavaMarkers.add(y);
                lavaMarkers.add(markers.getInt(i + 1));
            }
        }
    }

    /**
     * Inflates all water sources at the given y level, processes downwards flow from previous
     * layers, marks flooded lava sources, scans for blast resistant blocks.
     */
    private void processLayer(int y) {
        sources.clear();
        flows.clear();

        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int cell = cell(x, z);
                byte block = layer[cell];
                if ((block & WATER_SOURCE) != 0) {
                    // we will inflate this as if the eater destroyed all surroundings
                    sources.add(cell);
                    isSource[cell] = true;
                } else if ((block & LOW_BLAST_RES) != 0 && layerMatrix[cell] < 9) {
                    // we will inherit the flow from directly above
                    flows.add(cell);
                }
                // bonus check
                if ((block & BLAST_RESISTANT) != 0) {
                    long key = pack(x, y, z);
                    blastResistantOrder.add(key);
                    blastResistantMarkers.add(key);
                }
                // we should flush all values before simulating this layer
                layerMatrix[cell] = 9;
            }
        }
        // layer_matrix is flushed; simulate water
        for (int i = 0; i < sources.size(); i++) {
            int cell = sources.getInt(i);
            int x = cell / sizeZ, z = cell % sizeZ;
            // small optimization to process oceans faster
            boolean inner = x + 1 < sizeX && isSource[cell + sizeZ]
                    && x >= 1 && isSource[cell - sizeZ]
                    && z + 1 < sizeZ && isSource[cell + 1]
                    && z >= 1 && isSource[cell - 1];
            if (inner) {
                layerMatrix[cell] = 0;
                continue;
            }
            // if we're here, it's not an inner water source
            simulateWater(x, y, z, 0, false);
        }

        for (int i = 0; i < flows.size(); i++) {
            int cell = flows.getInt(i);
            simulateWater(cell / sizeZ, y, cell % sizeZ, 8, true);
        }

        for (int i = 0; i < sources.size(); i++) {
            isSource[sources.getInt(i)] = false;
        }

        // this also resets lava layer data
        resolveLavaMarkers(y);
    }

    /** Groups blast-resistant blocks into 6-connected components, one marker per group. */
    private int[] blastResistantMarkerList() {
        IntArrayList markers = new IntArrayList();
        LongArrayList stack = new LongArrayList();
        for (int i = 0; i < blastResistantOrder.size(); i++) {
            long key = blastResistantOrder.getLong(i);
            if (!blastResistantMarkers.remove(key)) {
                continue;
            }
            markers.add(unpackX(key));
            markers.add(unpackY(key));
            markers.add(unpackZ(key));
            stack.add(key);
            while (!stack.isEmpty()) {
                long k = stack.removeLong(stack.size() - 1);
                int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
                if (x + 1 < sizeX) visit(x + 1, y, z, stack);
                if (x >= 1) visit(x - 1, y, z, stack);
                if (y + 1 < sizeY) visit(x, y + 1, z, stack);
                if (y >= 1) visit(x, y - 1, z, stack);
                if (z + 1 < sizeZ) visit(x, y, z + 1, stack);
                if (z >= 1) visit(x, y, z - 1, stack);
            }
        }
        return markers.toIntArray();
    }

    private void visit(int x, int y, int z, LongArrayList stack) {
        long key = pack(x, y, z);
        if (blastResistantMarkers.remove(key)) {
            stack.add(key);
        }
    }

    private int unpackZ(long key) {
        return (int) (key % sizeZ);
    }

    private int unpackX(long key) {
        return (int) ((key / sizeZ) % sizeX);
    }

    private int unpackY(long key) {
        return (int) ((key / sizeZ) / sizeX);
    }

    /**
     * Processes all layers top to bottom and returns the markers. Can only be called once.
     *
     * @param progress  receives the number of finished layers
     * @param cancelled checked between layers; throws {@link CancelledException} when true
     */
    public Result run(IntConsumer progress, BooleanSupplier cancelled) {
        if (sizeY > 0) {
            source.fillLayer(sizeY - 1, layer);
            if (sizeY >= 2) {
                source.fillLayer(sizeY - 2, layerBelow);
            }
        }
        for (int y = sizeY - 1; y >= 0; y--) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            processLayer(y);
            progress.accept(sizeY - y);

            // shift the window one layer down: y - 1 becomes current, y - 2 becomes below
            byte[] swap = layer;
            layer = layerBelow;
            layerBelow = swap;
            if (y >= 2) {
                source.fillLayer(y - 2, layerBelow);
            }
            floodedLava.reset();
            OrderedCellSet floodedSwap = floodedLava;
            floodedLava = floodedLavaBelow;
            floodedLavaBelow = floodedSwap;
            focusedFloodedLava.clear();
            CellFlags focusedSwap = focusedFloodedLava;
            focusedFloodedLava = focusedFloodedLavaBelow;
            focusedFloodedLavaBelow = focusedSwap;
        }
        return new Result(lavaMarkers.toIntArray(), blastResistantMarkerList());
    }

    /** {@code waypoint:...} line in the format of {@code format_waypoint} in Python. */
    public static String formatWaypoint(long x, long y, long z, int color, String name) {
        String shortName = name.length() > 2 ? name.substring(0, 2) : name;
        return "waypoint:" + name + ":" + shortName + ":" + x + ":" + y + ":" + z + ":" + color
                + ":false:0:gui.xaero_default:false:0:0:true";
    }

    public static final int RED = 12;
    public static final int BLACK = 0;

    /** Waypoint lines in the same order as {@code save_waypoints} in Python. */
    public static java.util.List<String> waypoints(Result result, long originX, long originY, long originZ) {
        java.util.List<String> lines = new java.util.ArrayList<>(result.lavaCount() + result.blastResistantCount());
        int[] lava = result.lava();
        for (int i = 0; i < lava.length; i += 3) {
            lines.add(formatWaypoint(originX + lava[i], originY + lava[i + 1], originZ + lava[i + 2], RED, "x"));
        }
        int[] blast = result.blastResistant();
        for (int i = 0; i < blast.length; i += 3) {
            lines.add(formatWaypoint(originX + blast[i], originY + blast[i + 1], originZ + blast[i + 2], BLACK, "x"));
        }
        return lines;
    }
}
