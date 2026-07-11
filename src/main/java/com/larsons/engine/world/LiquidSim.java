package com.larsons.engine.world;

import com.larsons.engine.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Cellular liquid simulation for {@link Block#liquid()} blocks (water, lava,
 * acid). Painted <em>source</em> blocks stay put; the space they can reach is
 * kept filled with the liquid's hidden {@code <key>_flow} twin:
 *
 * <ul>
 *   <li>Liquid falls freely down open cells, and spreads sideways along a
 *       floor up to a per-liquid range from the nearest source (water flows
 *       farther than lava).</li>
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

    /** One changed cell, for feedback/broadcast. */
    public record Change(int col, int row, int id) {}

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
     * Advance with an explicit gravity flag: falling blocks (sand, gravel)
     * only drop in side-scroll gravity worlds, while liquids always flow.
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
        if (gravityOn) fallBlocks(level, changes);
        quenchLava(level, changes);
        // One pass per distinct liquid family that has a flow twin.
        for (Block b : blocks.all()) {
            if (b.liquid() && !b.isFlow() && blocks.flowFor(b) != null) {
                flowFamily(level, b, changes);
            }
        }
    }

    /**
     * Gravity for {@link Block#falling()} blocks (sand, gravel, opted-in
     * customs): unsupported ones drop a cell per tick, displacing liquids —
     * the same bottom-up sweep sand towers collapse with in the original.
     */
    private void fallBlocks(Level level, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        int w = level.width, h = level.height;
        for (int r = h - 2; r >= 0; r--) {
            for (int c = 0; c < w; c++) {
                Block b = blocks.get(level.tiles[r][c]);
                if (b == null || !b.falling()) continue;
                Block below = blocks.get(level.tiles[r + 1][c]);
                if (level.tiles[r + 1][c] == 0 || (below != null && below.liquid())) {
                    setCell(level, c, r + 1, b.id(), changes);
                    setCell(level, c, r, 0, changes);
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

    private void flowFamily(Level level, Block source, List<Change> changes) {
        BlockRegistry blocks = level.blocks;
        Block flow = blocks.flowFor(source);
        int w = level.width, h = level.height;
        int srcId = source.id(), flowId = flow.id();
        int range = spreadRange(source);

        // BFS from every source: down resets the budget, sideways costs 1.
        // "Passable" means air or this family's own liquid.
        int[] best = new int[w * h];
        java.util.Arrays.fill(best, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (level.tiles[r][c] == srcId) {
                    best[r * w + c] = range;
                    queue.add(new int[]{c, r, range});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int c = cur[0], r = cur[1], b = cur[2];
            boolean belowOpen = r + 1 < h && passable(level, c, r + 1, srcId, flowId);
            if (belowOpen && best[(r + 1) * w + c] < range) {
                best[(r + 1) * w + c] = range;
                queue.add(new int[]{c, r + 1, range});
            }
            if (!belowOpen && b > 0) {
                for (int dc = -1; dc <= 1; dc += 2) {
                    int nc = c + dc;
                    if (nc < 0 || nc >= w) continue;
                    if (passable(level, nc, r, srcId, flowId) && best[r * w + nc] < b - 1) {
                        best[r * w + nc] = b - 1;
                        queue.add(new int[]{nc, r, b - 1});
                    }
                }
            }
        }

        // Drain: flow cells no longer fed by a source disappear at once.
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                if (level.tiles[r][c] == flowId && best[r * w + c] < 0) {
                    setCell(level, c, r, 0, changes);
                }
            }
        }

        // Grow: falls advance a few cells per tick, spread one. Each round
        // only fills air cells directly under a liquid cell; the first round
        // also fills cells beside one.
        for (int round = 0; round < FALL_ROUNDS; round++) {
            List<int[]> adds = new ArrayList<>();
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    if (level.tiles[r][c] != 0 || best[r * w + c] < 0) continue;
                    boolean above = r > 0 && isFamily(level, c, r - 1, srcId, flowId);
                    boolean beside = round == 0
                            && ((c > 0 && isFamily(level, c - 1, r, srcId, flowId))
                            || (c + 1 < w && isFamily(level, c + 1, r, srcId, flowId)));
                    if (above || beside) adds.add(new int[]{c, r});
                }
            }
            for (int[] a : adds) setCell(level, a[0], a[1], flowId, changes);
            if (adds.isEmpty()) break;
        }
    }

    private static boolean passable(Level level, int c, int r, int srcId, int flowId) {
        int id = level.tiles[r][c];
        return id == 0 || id == srcId || id == flowId;
    }

    private static boolean isFamily(Level level, int c, int r, int srcId, int flowId) {
        int id = level.tiles[r][c];
        return id == srcId || id == flowId;
    }

    /** Water beside/above lava: flowing lava → stone, source lava → obsidian. */
    private void quenchLava(Level level, List<Change> changes) {
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
                int id = level.tiles[r][c];
                if (id != lavaId && id != lavaFlowId) continue;
                boolean touched = touches(level, c, r, waterId)
                        || (waterFlowId > 0 && touches(level, c, r, waterFlowId));
                if (touched) hits.add(new int[]{c, r, id});
            }
        }
        for (int[] hit : hits) {
            int replacement = hit[2] == lavaId && obsidian != null
                    ? obsidian.id() : stone.id();
            setCell(level, hit[0], hit[1], replacement, changes);
        }
    }

    private static boolean touches(Level level, int c, int r, int id) {
        return level.tileAt(c - 1, r) == id || level.tileAt(c + 1, r) == id
                || level.tileAt(c, r - 1) == id || level.tileAt(c, r + 1) == id;
    }

    private static void setCell(Level level, int c, int r, int id, List<Change> changes) {
        if (level.setTile(c, r, id)) changes.add(new Change(c, r, id));
    }
}
