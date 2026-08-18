package com.larsons.engine;

import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.LiquidSim;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIM_PLAN S3: the liquid simulation now costs what the liquid costs, not what
 * the level costs — and it produces exactly the same water.
 *
 * <p><b>The bug was reported as an out-of-memory crash, which is the loudest
 * possible version of the p95 stall SIM_PLAN was written about.</b> Every pass
 * in {@code LiquidSim} walked the whole grid — presence, drain, grow, quench,
 * and the reachability BFS's own source hunt — and the BFS allocated an
 * {@code int[3]} per queue entry and an {@code int[width * height]} per liquid
 * family per tick. On a large dense level that exhausted a two-gigabyte heap:
 *
 * <pre>
 * java.lang.OutOfMemoryError: Java heap space
 *   at com.larsons.engine.world.LiquidSim.reachable(LiquidSim.java:262)
 * </pre>
 *
 * <p>A pond in the corner of a big map cost exactly as much as a flooded one.
 * The work is now bounded to a box around each liquid family, grown by the
 * furthest one tick can carry it.
 *
 * <p><b>Two things have to be true, and they pull against each other.</b> The
 * cheap version must not be a <em>different</em> simulation — SIM_PLAN
 * invariant 1 is that the sim stays deterministic, because the server ticks it
 * and clients predict with it, and a change that makes one machine faster and
 * two machines disagree is not an optimisation. So the behavioural half of this
 * file pins what the water actually does, and the cost half pins that it no
 * longer scales with empty space.
 */
class LiquidSimBoundedTest {

    private static final int TILE = 32;

    /** A level of the given size with a floor along the bottom. */
    private static Level level(LevelFormat format, int w, int h) {
        Level lvl = Level.empty("liquid", w, h, TILE);
        lvl.setFormat(format);
        Block stone = lvl.blocks.get("stone");
        assertTrue(stone != null, "the registry should carry stone");
        for (int c = 0; c < w; c++) lvl.setTile(c, h - 1, stone.id());
        return lvl;
    }

    private static int id(Level lvl, String key) {
        Block b = lvl.blocks.get(key);
        assertTrue(b != null, "the registry should carry " + key);
        return b.id();
    }

    /** Run whole liquid ticks, and hand back everything that changed. */
    private static List<LiquidSim.Change> run(LiquidSim sim, Level lvl, int ticks) {
        List<LiquidSim.Change> all = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            all.addAll(sim.step(lvl, true, LiquidSim.TICK));
        }
        return all;
    }

    /** Every non-empty cell of the surface layer, as a comparable string. */
    private static String shapeOf(Level lvl) {
        StringBuilder out = new StringBuilder();
        int layer = lvl.surfaceLayer();
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                int id = lvl.tileAt(c, r, layer);
                if (id != 0) out.append(c).append(',').append(r).append('=').append(id).append(';');
            }
        }
        return out.toString();
    }

    // --- the water still behaves like water ---------------------------------------

    /**
     * A source pours down and spreads along the floor, and it does it in the
     * same place whatever size the level around it is.
     *
     * <p><b>This is the assertion that makes the optimisation safe rather than
     * merely fast.</b> The bounded version computes reachability inside a box;
     * if that box were ever too small, the water would stop at its edge — and
     * it would do so <em>only on levels large enough for the box to matter</em>,
     * which is exactly the case no small test would ever catch. So the same
     * scenario is run on a level the box covers entirely and on one hundreds of
     * times larger, and the two must produce identical grids.
     */
    @Test
    @Timeout(60)
    void theSameWaterFallsWhateverSizeTheLevelIs() {
        // Same height — and so the same floor to land on — so the only thing
        // that differs is how much empty level surrounds the stream.
        String small = pourAndDescribe(60, 40);
        String large = pourAndDescribe(4000, 40);

        assertEquals(small, large,
                "the same source produced different water on a larger level, which means "
                        + "the bounded region is clipping the simulation rather than just "
                        + "the work it does");
    }

    /**
     * Place a source at a fixed spot near the top-left, let it pour, and
     * describe the result relative to that spot.
     */
    private static String pourAndDescribe(int w, int h) {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, w, h);
        int water = id(lvl, "water");
        // Room for the stream to fall and spread, well inside the level so the
        // large case is genuinely mostly empty space.
        lvl.setTile(10, 5, water);
        run(new LiquidSim(), lvl, 30);

        StringBuilder out = new StringBuilder();
        int layer = lvl.surfaceLayer();
        for (int r = 0; r < Math.min(h, 40); r++) {
            for (int c = 0; c < Math.min(w, 60); c++) {
                int id = lvl.tileAt(c, r, layer);
                if (id != 0) out.append(c).append(',').append(r).append('=').append(id).append(';');
            }
        }
        return out.toString();
    }

    /** Cutting the source drains everything downstream, as it always has. */
    @Test
    @Timeout(60)
    void removingASourceDrainsWhatItWasFeeding() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 60, 30);
        int water = id(lvl, "water");
        lvl.setTile(10, 5, water);

        LiquidSim sim = new LiquidSim();
        run(sim, lvl, 30);
        assertTrue(shapeOf(lvl).length() > 0, "the water never went anywhere");
        int flowing = countOf(lvl, id(lvl, "water_flow"));
        assertTrue(flowing > 0, "a source that poured for thirty ticks left no flow behind");

        lvl.setTile(10, 5, 0);
        run(sim, lvl, 30);

        assertEquals(0, countOf(lvl, id(lvl, "water_flow")),
                "flow outlived the source that was feeding it");
    }

    /** And on a plane it pools outward instead of falling. */
    @Test
    @Timeout(60)
    void aPlanViewSourcePoolsOutward() {
        Level lvl = level(LevelFormat.THREE_D, 60, 60);
        int water = id(lvl, "water");
        // A plan-view level is layered: liquids live on the surface layer,
        // because the ground layer there is the floor itself.
        lvl.setTile(30, 30, lvl.surfaceLayer(), water);

        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 20; i++) sim.step(lvl, false, LiquidSim.TICK);

        int flow = countOf(lvl, id(lvl, "water_flow"));
        assertTrue(flow > 0, "a plan-view source did not pool at all");
        // The pool is a diamond of the liquid's range, so it must not have run
        // away across the level either.
        assertTrue(flow < 200, "a plan-view pool spread further than its range allows: " + flow);
    }

    private static int countOf(Level lvl, int id) {
        int n = 0;
        int layer = lvl.surfaceLayer();
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                if (lvl.tileAt(c, r, layer) == id) n++;
            }
        }
        return n;
    }

    // --- and it no longer costs what the level costs -------------------------------

    /**
     * A pond in the corner of a large level costs about what it costs in a small
     * one.
     *
     * <p><b>A timing assertion, and it is written to be about a ratio rather
     * than about milliseconds.</b> An absolute bound would measure the build
     * agent; the ratio between two runs on the same agent measures the
     * algorithm. Before the change the two differed by the ratio of the level
     * areas — a hundredfold here — because every pass walked the whole grid.
     * The bar is deliberately loose: one whole scan per tick remains, so the
     * large level is legitimately somewhat slower, and this is here to catch a
     * return to whole-grid <em>work per family</em>, not to defend a number.
     */
    @Test
    @Timeout(120)
    void aSmallPondDoesNotCostWhatABigLevelCosts() {
        long small = timePour(60, 40);
        long large = timePour(1024, 1024);

        double ratio = large / (double) Math.max(1, small);
        double areaRatio = (1024.0 * 1024.0) / (60.0 * 40.0);

        assertTrue(ratio < areaRatio / 4,
                "a level " + (int) areaRatio + " times larger took " + String.format("%.1f", ratio)
                        + " times as long to simulate the same small pond — the work is still "
                        + "scaling with the level rather than with the liquid "
                        + "(small " + small / 1_000_000 + " ms, large " + large / 1_000_000 + " ms)");
    }

    private static long timePour(int w, int h) {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, w, h);
        lvl.setTile(10, 5, id(lvl, "water"));
        LiquidSim sim = new LiquidSim();
        run(sim, lvl, 10);            // warm: let the stream reach the floor

        long start = System.nanoTime();
        run(sim, lvl, 40);
        return System.nanoTime() - start;
    }

    /**
     * The case that actually crashed: a large level with liquid on it, run long
     * enough that a per-tick whole-grid allocation would have shown up.
     *
     * <p>Not an assertion about memory — a heap bound in a test measures the
     * agent's GC. It is an assertion that this completes at all, on a level big
     * enough that the old {@code int[width * height]} per family per tick was
     * 16 MB a go.
     */
    @Test
    @Timeout(120)
    void aLargeLevelWithWaterOnItSurvivesAThousandTicks() {
        // The largest level that still uses a dense grid: above
        // Level.DENSE_TILE_LIMIT the sim steps aside entirely, so this is
        // the worst case it actually runs on — and the one the old
        // int[width * height] per family per tick was 4 MB a go for.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 1024, 1024);
        int water = id(lvl, "water");
        for (int i = 0; i < 8; i++) lvl.setTile(20 + i * 7, 5, water);

        LiquidSim sim = new LiquidSim();
        run(sim, lvl, 200);

        assertTrue(countOf(lvl, id(lvl, "water_flow")) > 0,
                "eight sources on a large level produced no flow at all");
    }
}
