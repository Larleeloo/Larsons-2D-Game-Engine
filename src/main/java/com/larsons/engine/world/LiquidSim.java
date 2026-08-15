package com.larsons.engine.world;

import com.larsons.engine.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cellular liquid simulation for {@link Block#liquid()} blocks (water, lava,
 * acid). Painted <em>source</em> blocks stay put; the space they can reach is
 * kept filled with the liquid's hidden {@code <key>_flow} twin:
 *
 * <ul>
 *   <li>Liquid falls freely down open cells, and spreads sideways along a
 *       floor up to a per-liquid range from the nearest source (water flows
 *       farther than lava). In the plan-view level formats (top-down,
 *       isometric) there is no "down" to pour along, so the same range
 *       spreads outward in all four directions and a source pools into a
 *       diamond instead.</li>
 *   <li>Reachability is recomputed every tick, so removing a source (or
 *       cutting the stream with a block) drains everything downstream, and
 *       mining a hole under a pool sends it pouring through.</li>
 *   <li>Growth is frontier-limited — a few cells of fall and one cell of
 *       spread per tick — so streams visibly pour instead of teleporting.</li>
 *   <li>Water touching lava quenches it: flowing lava turns to stone, source
 *       lava to obsidian (the Minecraft rule, because it's a good rule).</li>
 * </ul>
 *
 * <p>The sim is a pure function of the tile grid, so it is deterministic and
 * runs identically in single-player, creative play-tests, and on the
 * authoritative multiplayer server (which broadcasts the resulting block
 * changes; see {@link World#pollBlockChanges()}).
 */
public final class LiquidSim {

    /** Seconds between liquid ticks. */
    public static final double TICK = 0.22;

    /** Cells of downward fall applied per tick (fast, like pouring). */
    private static final int FALL_ROUNDS = 3;

    /**
     * One changed cell, for feedback/broadcast. {@code layer} is which of the
     * level's layers it changed ({@link Level#LAYER_GROUND} /
     * {@link Level#LAYER_UPPER}), because a plan-view pool sits on the floor
     * rather than replacing it and a listener applying the change elsewhere
     * has to put it in the same place.
     */
    public record Change(int col, int row, int id, int layer) {
        /** A change to the only layer a side-scrolling level has. */
        public Change(int col, int row, int id) {
            this(col, row, id, Level.LAYER_GROUND);
        }
    }

    /**
     * The layer liquids and falling blocks live in, and the reads and writes
     * that reach it. In a side-scroller that is the level's one grid; in a
     * plan view it is the stacked layer, because there the ground layer is the
     * floor itself — a pool spreading across a room lies <em>on</em> the floor,
     * and a stream that replaced it would eat the room.
     */
    private record Cells(Level level, boolean perColumn, int flatLayer) {
        static Cells of(Level level) {
            return new Cells(level, level.verticality(), level.surfaceLayer());
        }

        /**
         * Which layer of a cell the liquid lives in.
         *
         * <p>One layer for the whole level while the height axis is off — the
         * stacked layer on a plane, the only layer in a side view — which is
         * what every level saved before this behaved like, and changing it
         * would let their pools climb walls they have always stopped at.
         *
         * <p>With the axis on it is per column: the first layer above whatever
         * holds a body up, so a pool lies <em>on</em> the ground it is on and
         * a puddle on a plateau stays on the plateau. Stable because a liquid
         * is not solid, so pouring one into a cell does not raise the surface
         * the next tick measures from.
         *
         * <p>A hole keeps the old answer. Its support height is zero, and
         * writing liquid into layer 0 would make the hole a floor — water you
         * could walk on, where today it is a gap with water hanging over it.
         */
        int layerAt(int col, int row) {
            if (!perColumn) return flatLayer;
            return Math.max(Level.LAYER_UPPER, level.supportHeight(col, row));
        }

        // The flat layer is resolved once and held, not asked per cell.
        // Asking per cell put a Level.surfaceLayer() call — and the format
        // lookup behind it — inside the whole-grid survey scan, which is the
        // hottest loop in this class and the one SIM_PLAN measured the p95
        // stall in. It cost 200 ms on a level that had taken 1 ms, and
        // LiquidSimBoundedTest caught it on the first run after the change:
        // the work had gone back to scaling with the level rather than with
        // the liquid, which is the exact defect that test exists for.

        /** How high the ground under a cell is, in layers. */
        int surface(int col, int row) {
            return perColumn ? level.supportHeight(col, row) : 0;
        }

        int get(int col, int row) {
            return level.tileAt(col, row, layerAt(col, row));
        }

        boolean set(int col, int row, int id) {
            return level.setTile(col, row, layerAt(col, row), id);
        }
    }

    private double accumulator;

    /**
     * Advance the simulation by {@code dt} seconds; mutates the level's tiles
     * and returns every cell that changed (empty list on non-tick frames).
     *
     * <p>Giant chunked levels are skipped: a whole-grid cellular pass over up
     * to 65536&sup2; cells is unbounded work, so painted liquids stay as
     * still pools there (an active-region sim is future work).
     */
    public List<Change> step(Level level, double dt) {
        return step(level, true, dt);
    }

    /**
     * Advance with an explicit gravity flag. Gravity worlds (side-scroll) drop
     * falling blocks and pour liquids downward; without it — the plan-view
     * formats, where the screen shows a floor rather than a wall — sand and
     * gravel stay where they are placed and liquids spread outward across the
     * plane instead, pooling around their source like water on a table.
     */
    public List<Change> step(Level level, boolean gravityOn, double dt) {
        if (!level.registryTiles || level.tiles() == null) return List.of();
        accumulator += dt;
        if (accumulator < TICK) return List.of();
        List<Change> changes = new ArrayList<>();
        while (accumulator >= TICK) {
            accumulator -= TICK;
            tick(level, gravityOn, changes);
        }
        return changes;
    }

    private void tick(Level level, boolean gravityOn, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        Cells cells = Cells.of(level);
        // One cheap presence scan gates the expensive passes. Most levels
        // carry no liquids or falling blocks at all, but the cellular passes
        // below allocate whole-grid BFS buffers and rescan the map several
        // times each — on a big custom level that regularly blew the 60 Hz
        // multiplayer server's tick budget and stuttered everyone's game.
        Survey survey = survey(level, cells);
        Set<Integer> present = survey.ids();
        boolean anyFalling = false;
        for (int id : present) {
            Block b = blocks.get(id);
            if (b != null && b.falling()) {
                anyFalling = true;
                break;
            }
        }
        if (gravityOn && anyFalling) fallBlocks(level, cells, changes);
        if (containsAny(blocks, present, "lava", "lava_flow")
                && containsAny(blocks, present, "water", "water_flow")) {
            quenchLava(level, cells, survey, changes);
        }
        // One pass per distinct liquid family that has a flow twin and is
        // actually on the map.
        for (Block b : blocks.all()) {
            if (b.liquid() && !b.isFlow() && blocks.flowFor(b) != null
                    && (present.contains(b.id())
                    || present.contains(blocks.flowFor(b).id()))) {
                flowFamily(level, cells, b, gravityOn, survey, changes);
            }
        }
    }

    /**
     * What is in the liquid layer and where — one scan, used by everything
     * after it.
     *
     * <p><b>This used to be four scans and a fifth per liquid family, and that
     * is what put a heap dump in a bug report.</b> Every pass below walked the
     * whole grid: the presence check, the drain, the grow, the quench, and the
     * reachability BFS's own source hunt. On a large dense level that is five
     * or more passes over millions of cells every 0.22 s, and the BFS on top
     * allocated an {@code int[3]} per queue entry and an {@code int[width *
     * height]} per family per tick. It ran out of a two-gigabyte heap:
     *
     * <pre>
     * Exception in thread "game-loop" java.lang.OutOfMemoryError: Java heap space
     *   at com.larsons.engine.world.LiquidSim.reachable(LiquidSim.java:262)
     *   at com.larsons.engine.world.LiquidSim.flowFamily(LiquidSim.java:209)
     * </pre>
     *
     * <p>The work was never proportional to the liquid — it was proportional to
     * the <em>level</em>, and a pond in the corner of a big map cost exactly as
     * much as a flooded one. So this scan now also records where each tile id
     * actually is, and every pass after it works inside that box. One scan
     * stays; the rest become proportional to the water.
     */
    private record Survey(Set<Integer> ids, Map<Integer, int[]> boxes) {

        /** The bounding box of {@code id}, or {@code null} if it is not present. */
        int[] box(int id) { return boxes.get(id); }

        /**
         * The two ids' boxes together, grown by the margin anything can move in
         * one tick and clipped to the level — or {@code null} when neither is
         * present.
         *
         * <p>Conservative by construction: a cell can only change this tick if
         * it already holds the family or sits within one step of a cell that
         * does, and the margins below are larger than one step in every
         * direction the simulation can travel. Cells outside cannot change, so
         * a reachability figure that is wrong out there cannot be read by
         * anything.
         */
        int[] region(int a, int b, int level_w, int level_h, int marginX, int marginY) {
            int[] one = box(a), two = box(b);
            if (one == null && two == null) return null;
            int[] u = one == null ? two.clone() : one.clone();
            if (one != null && two != null) {
                u[0] = Math.min(u[0], two[0]);
                u[1] = Math.min(u[1], two[1]);
                u[2] = Math.max(u[2], two[2]);
                u[3] = Math.max(u[3], two[3]);
            }
            u[0] = Math.max(0, u[0] - marginX);
            u[1] = Math.max(0, u[1] - marginY);
            u[2] = Math.min(level_w - 1, u[2] + marginX);
            u[3] = Math.min(level_h - 1, u[3] + marginY);
            return u;
        }
    }

    /** The distinct non-empty tile ids in the liquid layer, and where each one is. */
    private static Survey survey(Level level, Cells cells) {
        Set<Integer> present = new HashSet<>();
        Map<Integer, int[]> boxes = new HashMap<>();
        for (int r = 0; r < level.height; r++) {
            for (int c = 0; c < level.width; c++) {
                int id = cells.get(c, r);
                if (id == 0) continue;
                if (present.add(id)) {
                    boxes.put(id, new int[]{c, r, c, r});
                } else {
                    int[] box = boxes.get(id);
                    if (c < box[0]) box[0] = c;
                    if (r < box[1]) box[1] = r;
                    if (c > box[2]) box[2] = c;
                    if (r > box[3]) box[3] = r;
                }
            }
        }
        return new Survey(present, boxes);
    }

    private static boolean containsAny(BlockRegistry blocks, Set<Integer> present,
                                       String... keys) {
        for (String key : keys) {
            Block b = blocks.get(key);
            if (b != null && present.contains(b.id())) return true;
        }
        return false;
    }

    /**
     * Gravity for {@link Block#falling()} blocks (sand, gravel, opted-in
     * customs): unsupported ones drop a cell per tick, displacing liquids —
     * the same bottom-up sweep sand towers collapse with in the original.
     */
    private void fallBlocks(Level level, Cells cells, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        int w = level.width, h = level.height;
        for (int r = h - 2; r >= 0; r--) {
            for (int c = 0; c < w; c++) {
                Block b = blocks.get(cells.get(c, r));
                if (b == null || !b.falling()) continue;
                Block below = blocks.get(cells.get(c, r + 1));
                if (cells.get(c, r + 1) == 0 || (below != null && below.liquid())) {
                    setCell(cells, c, r + 1, b.id(), changes);
                    setCell(cells, c, r, 0, changes);
                }
            }
        }
    }

    /** Horizontal spread range, in tiles from the supporting source/fall. */
    private static int spreadRange(Block source) {
        return switch (source.key()) {
            case "lava" -> 3;
            case "acid" -> 4;
            default -> 5;
        };
    }

    private void flowFamily(Level level, Cells cells, Block source, boolean gravityOn,
                            Survey survey, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        Block flow = blocks.flowFor(source);
        int w = level.width, h = level.height;
        int srcId = source.id(), flowId = flow.id();
        int range = spreadRange(source);

        // The region anything in this family can change this tick: where it
        // already is, plus the furthest one tick can carry it. See Survey.
        int[] region = survey.region(srcId, flowId, w, h,
                range + 2, Math.max(FALL_ROUNDS, 1) + 2);
        if (region == null) return;
        int c0 = region[0], r0 = region[1], c1 = region[2], r1 = region[3];
        int bw = c1 - c0 + 1;

        int[] best = reachable(cells, srcId, flowId, range, gravityOn, c0, r0, c1, r1);

        // Drain: flow cells no longer fed by a source disappear at once.
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                if (cells.get(c, r) == flowId && best[(r - r0) * bw + (c - c0)] < 0) {
                    setCell(cells, c, r, 0, changes);
                }
            }
        }

        // Grow: in a gravity world falls advance a few cells per tick and the
        // sideways spread advances one; on a plane the pool grows by one ring
        // in every direction, so a source visibly spreads outward.
        int rounds = gravityOn ? FALL_ROUNDS : 1;
        for (int round = 0; round < rounds; round++) {
            List<int[]> adds = new ArrayList<>();
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (cells.get(c, r) != 0 || best[(r - r0) * bw + (c - c0)] < 0) continue;
                    boolean fed;
                    if (gravityOn) {
                        // Each round fills air under a liquid cell; the first
                        // round also fills cells beside one.
                        fed = (r > 0 && isFamily(cells, c, r - 1, srcId, flowId))
                                || (round == 0
                                && ((c > 0 && isFamily(cells, c - 1, r, srcId, flowId))
                                || (c + 1 < w && isFamily(cells, c + 1, r, srcId, flowId))));
                    } else {
                        fed = (r > 0 && isFamily(cells, c, r - 1, srcId, flowId))
                                || (r + 1 < h && isFamily(cells, c, r + 1, srcId, flowId))
                                || (c > 0 && isFamily(cells, c - 1, r, srcId, flowId))
                                || (c + 1 < w && isFamily(cells, c + 1, r, srcId, flowId));
                    }
                    if (fed) adds.add(new int[]{c, r});
                }
            }
            for (int[] a : adds) setCell(cells, a[0], a[1], flowId, changes);
            if (adds.isEmpty()) break;
        }
    }

    /**
     * Spread budget left at every cell a source can reach, or {@code -1} where
     * none can. In a gravity world falling down resets the budget and moving
     * sideways along a floor costs one; on a plane there is no "down", so
     * every step across the floor costs one and the reachable set is a
     * diamond around each source. Cells are "passable" when they are air or
     * already hold this liquid family.
     */
    private static int[] reachable(Cells cells, int srcId, int flowId,
                                   int range, boolean gravityOn,
                                   int c0, int r0, int c1, int r1) {
        int bw = c1 - c0 + 1, bh = r1 - r0 + 1;
        int[] best = new int[bw * bh];
        java.util.Arrays.fill(best, -1);

        // A primitive queue holding cell indices, not an ArrayDeque of int[3].
        // The budget is read from `best` at poll time rather than carried:
        // relaxation only ever improves a cell, so a value that has improved
        // since the cell was queued is the value the original would have
        // reached on a later pass. The fixpoint is the same one — which is what
        // determinism requires here, and it is the whole reason this is safe to
        // change at all (SIM_PLAN invariant 1).
        int[] queue = new int[Math.max(64, Math.min(bw * bh, 1 << 12))];
        int head = 0, tail = 0;

        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                if (cells.get(c, r) == srcId) {
                    int i = (r - r0) * bw + (c - c0);
                    best[i] = range;
                    if (tail == queue.length) queue = grow(queue);
                    queue[tail++] = i;
                }
            }
        }
        while (head < tail) {
            int cur = queue[head++];
            int c = c0 + cur % bw, r = r0 + cur / bw;
            int b = best[cur];
            if (gravityOn) {
                boolean belowOpen = r + 1 <= r1 && passable(cells, c, r + 1, srcId, flowId);
                if (belowOpen && best[cur + bw] < range) {
                    best[cur + bw] = range;
                    if (tail == queue.length) queue = grow(queue);
                    queue[tail++] = cur + bw;
                }
                if (belowOpen || b <= 0) continue;
                for (int dc = -1; dc <= 1; dc += 2) {
                    int nc = c + dc;
                    if (nc < c0 || nc > c1) continue;
                    int ni = cur + dc;
                    if (spreadable(cells, c, r, nc, r, srcId, flowId) && best[ni] < b - 1) {
                        best[ni] = b - 1;
                        if (tail == queue.length) queue = grow(queue);
                        queue[tail++] = ni;
                    }
                }
            } else {
                if (b <= 0) continue;
                for (int[] d : PLANE_NEIGHBOURS) {
                    int nc = c + d[0], nr = r + d[1];
                    if (nc < c0 || nc > c1 || nr < r0 || nr > r1) continue;
                    int ni = (nr - r0) * bw + (nc - c0);
                    if (spreadable(cells, c, r, nc, nr, srcId, flowId) && best[ni] < b - 1) {
                        best[ni] = b - 1;
                        if (tail == queue.length) queue = grow(queue);
                        queue[tail++] = ni;
                    }
                }
            }
        }
        return best;
    }

    /**
     * A bigger queue with the same contents at the same indices.
     *
     * <p><b>Deliberately not compacting</b>, and the first version of this did.
     * Compaction moves the live entries to the front, which invalidates the
     * caller's {@code head} and {@code tail} — and the caller holds those in
     * locals, so it carried on indexing past the end. It threw
     * {@code ArrayIndexOutOfBoundsException: Index 4096 out of bounds} on the
     * first level big enough to need a second growth, which is the only kind of
     * level that would ever have reached it in the field.
     *
     * <p>Plain doubling is bounded by the search, not by the level: a cell is
     * queued once per improvement to its budget and budgets run 0..range, so
     * total enqueues cannot exceed {@code (range + 1)} per cell <em>of the
     * box</em>. That is the whole point of having a box.
     */
    private static int[] grow(int[] queue) {
        int[] bigger = new int[queue.length * 2];
        System.arraycopy(queue, 0, bigger, 0, queue.length);
        return bigger;
    }

    /** The four plan-view spread directions (no "down" to pour along). */
    private static final int[][] PLANE_NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static boolean passable(Cells cells, int c, int r, int srcId, int flowId) {
        int id = cells.get(c, r);
        return id == 0 || id == srcId || id == flowId;
    }

    /**
     * {@link #passable} for a spread from ({@code fromC}, {@code fromR}) —
     * the same test, plus the one thing a height axis adds: <b>water does not
     * climb</b>.
     *
     * <p>A pool spreads onto ground level with it or below it and stops at
     * anything higher, which is what makes a plateau hold a puddle and a
     * hillside shed one. It does not <em>pour</em> down the drop it is allowed
     * to spread over — that is a waterfall, and rule (b) of this plan's S1,
     * refused there because it multiplies the worst case of the one system
     * this project has already measured an out-of-memory error in.
     */
    private static boolean spreadable(Cells cells, int fromC, int fromR,
                                      int c, int r, int srcId, int flowId) {
        return passable(cells, c, r, srcId, flowId)
                && cells.surface(c, r) <= cells.surface(fromC, fromR);
    }

    private static boolean isFamily(Cells cells, int c, int r, int srcId, int flowId) {
        int id = cells.get(c, r);
        return id == srcId || id == flowId;
    }

    /** Water beside/above lava: flowing lava → stone, source lava → obsidian. */
    private void quenchLava(Level level, Cells cells, Survey survey, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        Block water = blocks.get("water");
        Block waterFlow = blocks.get("water_flow");
        Block lava = blocks.get("lava");
        Block lavaFlow = blocks.get("lava_flow");
        Block stone = blocks.get("stone");
        Block obsidian = blocks.get("obsidian");
        if (water == null || lava == null || stone == null) return;
        int w = level.width, h = level.height;
        int waterId = water.id();
        int waterFlowId = waterFlow != null ? waterFlow.id() : -1;
        int lavaId = lava.id();
        int lavaFlowId = lavaFlow != null ? lavaFlow.id() : -1;

        // Only where the lava is. Quenching needs water beside a lava cell, so
        // a cell outside the lava's own box plus one step cannot be a hit.
        int[] region = survey.region(lavaId, lavaFlowId, w, h, 1, 1);
        if (region == null) return;
        int c0 = region[0], r0 = region[1], c1 = region[2], r1 = region[3];

        List<int[]> hits = new ArrayList<>();
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int id = cells.get(c, r);
                if (id != lavaId && id != lavaFlowId) continue;
                boolean touched = touches(cells, c, r, waterId)
                        || (waterFlowId > 0 && touches(cells, c, r, waterFlowId));
                if (touched) hits.add(new int[]{c, r, id});
            }
        }
        for (int[] hit : hits) {
            int replacement = hit[2] == lavaId && obsidian != null
                    ? obsidian.id() : stone.id();
            setCell(cells, hit[0], hit[1], replacement, changes);
        }
    }

    private static boolean touches(Cells cells, int c, int r, int id) {
        return cells.get(c - 1, r) == id || cells.get(c + 1, r) == id
                || cells.get(c, r - 1) == id || cells.get(c, r + 1) == id;
    }

    private static void setCell(Cells cells, int c, int r, int id, List<Change> changes) {
        if (cells.set(c, r, id)) changes.add(new Change(c, r, id, cells.layerAt(c, r)));
    }
}
