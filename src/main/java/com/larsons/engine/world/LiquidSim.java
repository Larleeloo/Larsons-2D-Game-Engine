package com.larsons.engine.world;

import com.larsons.engine.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private record Cells(Level level, int layer) {
        static Cells of(Level level) {
            return new Cells(level, level.surfaceLayer());
        }

        int get(int col, int row) {
            return level.tileAt(col, row, layer);
        }

        boolean set(int col, int row, int id) {
            return level.setTile(col, row, layer, id);
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
        if (!level.registryTiles || level.tiles == null) return List.of();
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
        Set<Integer> present = presentTileIds(level, cells);
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
            quenchLava(level, cells, changes);
        }
        // One pass per distinct liquid family that has a flow twin and is
        // actually on the map.
        for (Block b : blocks.all()) {
            if (b.liquid() && !b.isFlow() && blocks.flowFor(b) != null
                    && (present.contains(b.id())
                    || present.contains(blocks.flowFor(b).id()))) {
                flowFamily(level, cells, b, gravityOn, changes);
            }
        }
    }

    /** The distinct non-empty tile ids in the liquid layer (one fast scan). */
    private static Set<Integer> presentTileIds(Level level, Cells cells) {
        Set<Integer> present = new HashSet<>();
        int last = 0;
        for (int r = 0; r < level.height; r++) {
            for (int c = 0; c < level.width; c++) {
                int id = cells.get(c, r);
                if (id != 0 && id != last) {
                    present.add(id);
                    last = id;
                }
            }
        }
        return present;
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
                            List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        Block flow = blocks.flowFor(source);
        int w = level.width, h = level.height;
        int srcId = source.id(), flowId = flow.id();
        int range = spreadRange(source);

        int[] best = reachable(level, cells, srcId, flowId, range, gravityOn);

        // Drain: flow cells no longer fed by a source disappear at once.
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (cells.get(c, r) == flowId && best[r * w + c] < 0) {
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
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    if (cells.get(c, r) != 0 || best[r * w + c] < 0) continue;
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
    private static int[] reachable(Level level, Cells cells, int srcId, int flowId,
                                   int range, boolean gravityOn) {
        int w = level.width, h = level.height;
        int[] best = new int[w * h];
        java.util.Arrays.fill(best, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (cells.get(c, r) == srcId) {
                    best[r * w + c] = range;
                    queue.add(new int[]{c, r, range});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int c = cur[0], r = cur[1], b = cur[2];
            if (gravityOn) {
                boolean belowOpen = r + 1 < h && passable(cells, c, r + 1, srcId, flowId);
                if (belowOpen && best[(r + 1) * w + c] < range) {
                    best[(r + 1) * w + c] = range;
                    queue.add(new int[]{c, r + 1, range});
                }
                if (belowOpen || b <= 0) continue;
                for (int dc = -1; dc <= 1; dc += 2) {
                    int nc = c + dc;
                    if (nc < 0 || nc >= w) continue;
                    if (passable(cells, nc, r, srcId, flowId) && best[r * w + nc] < b - 1) {
                        best[r * w + nc] = b - 1;
                        queue.add(new int[]{nc, r, b - 1});
                    }
                }
            } else {
                if (b <= 0) continue;
                for (int[] d : PLANE_NEIGHBOURS) {
                    int nc = c + d[0], nr = r + d[1];
                    if (nc < 0 || nc >= w || nr < 0 || nr >= h) continue;
                    if (passable(cells, nc, nr, srcId, flowId) && best[nr * w + nc] < b - 1) {
                        best[nr * w + nc] = b - 1;
                        queue.add(new int[]{nc, nr, b - 1});
                    }
                }
            }
        }
        return best;
    }

    /** The four plan-view spread directions (no "down" to pour along). */
    private static final int[][] PLANE_NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static boolean passable(Cells cells, int c, int r, int srcId, int flowId) {
        int id = cells.get(c, r);
        return id == 0 || id == srcId || id == flowId;
    }

    private static boolean isFamily(Cells cells, int c, int r, int srcId, int flowId) {
        int id = cells.get(c, r);
        return id == srcId || id == flowId;
    }

    /** Water beside/above lava: flowing lava → stone, source lava → obsidian. */
    private void quenchLava(Level level, Cells cells, List<Change> changes) {
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

        List<int[]> hits = new ArrayList<>();
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
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
        if (cells.set(c, r, id)) changes.add(new Change(c, r, id, cells.layer()));
    }
}
