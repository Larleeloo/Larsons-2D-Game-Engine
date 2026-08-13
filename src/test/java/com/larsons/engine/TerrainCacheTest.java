package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainCache;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baked floor: that it is reused when nothing changed, rebuilt when
 * something did, and that it draws the same terrain the live sweep does.
 */
class TerrainCacheTest {

    private static final int TILE = 32;
    private static final int W = 480, H = 360;

    private static Level level(LevelFormat format, int w, int h) {
        Level lvl = Level.empty("cache", w, h, TILE);
        lvl.setFormat(format);
        java.util.List<Block> blocks = new java.util.ArrayList<>(lvl.blocks.all());
        java.util.Random rnd = new java.util.Random(3);
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                lvl.setTile(c, r, blocks.get(rnd.nextInt(blocks.size())).id());
            }
        }
        return lvl;
    }

    private static Camera camera(Level lvl) {
        Camera cam = new Camera(lvl.perspective, W, H);
        cam.tileSize = TILE;
        cam.centerOn(lvl.width * TILE / 2.0, lvl.height * TILE / 2.0);
        return cam;
    }

    private static int[] bounds(Level lvl) {
        return new int[]{0, 0, lvl.width - 1, lvl.height - 1};
    }

    /** One terrain pass into an image, optionally through the cache. */
    private static BufferedImage paint(Level lvl, Camera cam, TerrainCache cache, int[] b) {
        return paint(lvl, cam, cache, b, 0.0);
    }

    /**
     * The same, at a stated point on the animation clock.
     *
     * <p><b>Every test in this class before the ones at the bottom passes zero,
     * and that is exactly why the vibration lived here for so long.</b> The cache
     * put the animation frame number into every chunk's validity key, so a level
     * with nothing animated in it had its whole screen invalidated twelve times a
     * second — and twenty tests of the cache, all of them at {@code animClock = 0},
     * could not see it, because at a frozen clock the frame number never changes.
     */
    private static BufferedImage paint(Level lvl, Camera cam, TerrainCache cache, int[] b,
                                       double animClock) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam, b, animClock, pass, null, null,
                cache);
        pass.flush();
        g.dispose();
        return img;
    }

    /**
     * Paint until the cache has baked everything in view. Baking is capped per
     * frame so that walking into new ground does not stall one, so a test that
     * wants a settled cache has to let it settle.
     */
    private static void warmUp(Level lvl, Camera cam, TerrainCache cache) {
        for (int i = 0; i < 200; i++) {
            cache.resetCounters();
            paint(lvl, cam, cache, bounds(lvl));
            if (cache.rebuilds() == 0) return;
        }
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }

    @Test
    void aChunkIsBuiltOnceAndReusedAfterwards() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 24, 18);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();

        paint(lvl, cam, cache, bounds(lvl));
        assertTrue(cache.rebuilds() > 0, "the first frame has to build something");

        warmUp(lvl, cam, cache);
        cache.resetCounters();
        for (int i = 0; i < 10; i++) paint(lvl, cam, cache, bounds(lvl));

        assertEquals(0, cache.rebuilds(), "ten more frames of a still level rebuild nothing");
        assertTrue(cache.hits() > 0, "every chunk should have been a hit");
    }

    @Test
    void editingTheLevelRebuildsTheFloor() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 24, 18);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        paint(lvl, cam, cache, bounds(lvl));
        cache.resetCounters();

        lvl.setTile(3, 3, 0);            // mine a block
        paint(lvl, cam, cache, bounds(lvl));

        assertTrue(cache.rebuilds() > 0, "an edit must invalidate the baked floor");
    }

    @Test
    void aSetThatChangesNothingDoesNotInvalidate() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 24, 18);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);
        cache.resetCounters();

        int existing = lvl.tileAt(3, 3);
        lvl.setTile(3, 3, existing);     // same block, no change
        paint(lvl, cam, cache, bounds(lvl));

        assertEquals(0, cache.rebuilds(), "setting a tile to what it already was is not an edit");
    }

    @Test
    void editingOneBlockRebuildsOnlyItsOwnChunk() {
        // The flaw this replaced: one global counter meant a single changed
        // cell threw away every chunk on screen. Liquids run every tick and
        // mining, meteors and block placement all rewrite cells during play,
        // so in a level with water the cache spent its life rebuilding and
        // quietly did the per-cell sweep it exists to avoid.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 4,
                TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);
        cache.resetCounters();

        lvl.setTile(1, 1, 0);               // break one block, top-left chunk
        paint(lvl, cam, cache, bounds(lvl));

        assertEquals(1, cache.rebuilds(),
                "one changed cell should rebuild one chunk, not the whole view");
        assertTrue(cache.hits() > 1, "every other chunk should still be a hit");
    }

    @Test
    void aChangeAnywhereInAChunkInvalidatesThatChunk() {
        // The level's region size and the cache's chunk size have to agree.
        // If regions were finer than chunks, a change in the wrong corner
        // would leave a stale picture on screen.
        for (int cell = 0; cell < TerrainCache.CHUNK * TerrainCache.CHUNK; cell++) {
            Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 2,
                    TerrainCache.CHUNK * 2);
            Camera cam = camera(lvl);
            TerrainCache cache = new TerrainCache();
            warmUp(lvl, cam, cache);
            cache.resetCounters();

            int col = cell % TerrainCache.CHUNK;
            int row = cell / TerrainCache.CHUNK;
            lvl.setTile(col, row, 0);
            paint(lvl, cam, cache, bounds(lvl));

            assertTrue(cache.rebuilds() >= 1,
                    "a change at (" + col + "," + row + ") left its chunk stale");
        }
    }

    @Test
    void heavyChurnMakesTheCacheStandAside() {
        // A half-cached frame is worse than either extreme: alternating image
        // blits with per-cell fills measured 22 ms where all-live was 16 ms and
        // all-cached was 2 ms. So on ground being rewritten faster than it can
        // be baked, the cache does nothing at all rather than half a job.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 4,
                TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);

        java.util.Random rnd = new java.util.Random(2);
        java.util.List<Block> blocks = new java.util.ArrayList<>(lvl.blocks.all());
        for (int frame = 0; frame < 5; frame++) {
            for (int i = 0; i < 40; i++) {          // a torn-up world
                lvl.setTile(rnd.nextInt(lvl.width), rnd.nextInt(lvl.height),
                        blocks.get(rnd.nextInt(blocks.size())).id());
            }
            cache.resetCounters();
            paint(lvl, cam, cache, bounds(lvl));
        }

        assertEquals(0, cache.rebuilds(), "nothing should be baked while the world is torn up");
        assertEquals(0, cache.hits(), "and nothing served — the frame is swept whole");
    }

    @Test
    void lightChurnKeepsUsingTheCache() {
        // The common case during play: one block mined, a little water moving.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 4,
                TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);

        lvl.setTile(2, 2, 0);
        cache.resetCounters();
        paint(lvl, cam, cache, bounds(lvl));

        assertTrue(cache.hits() > 0, "one changed cell must not disable the cache");
    }

    @Test
    void theCacheRecoversOnceTheWorldSettles() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 3,
                TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();

        java.util.Random rnd = new java.util.Random(8);
        java.util.List<Block> blocks = new java.util.ArrayList<>(lvl.blocks.all());
        for (int frame = 0; frame < 5; frame++) {
            for (int i = 0; i < 40; i++) {
                lvl.setTile(rnd.nextInt(lvl.width), rnd.nextInt(lvl.height),
                        blocks.get(rnd.nextInt(blocks.size())).id());
            }
            paint(lvl, cam, cache, bounds(lvl));
        }

        warmUp(lvl, cam, cache);
        cache.resetCounters();
        paint(lvl, cam, cache, bounds(lvl));

        assertTrue(cache.hits() > 0, "a settled world should be cached again");
        assertEquals(0, cache.rebuilds(), "and need no further rebuilding");
    }

    @Test
    void replacingTheGridWholesaleInvalidatesEverything() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 3,
                TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);
        cache.resetCounters();

        lvl.bumpTerrainRevision();          // a load, a resize, a format change
        paint(lvl, cam, cache, bounds(lvl));

        assertEquals(0, cache.hits(), "nothing may survive a wholesale replacement");
    }

    @Test
    void aChangeInOneRegionDoesNotDisturbAnother() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 4, TerrainCache.CHUNK);
        long before = lvl.terrainRevisionAt(TerrainCache.CHUNK * 3, 0);

        lvl.setTile(0, 0, 0);

        assertEquals(before, lvl.terrainRevisionAt(TerrainCache.CHUNK * 3, 0),
                "a distant region's revision should not have moved");
        assertTrue(lvl.terrainRevisionAt(0, 0) > 0, "but its own should have");
    }

    @Test
    void zoomingRebuildsBecauseTheBakedPixelsAreTheWrongSize() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 24, 18);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        paint(lvl, cam, cache, bounds(lvl));
        cache.resetCounters();

        cam.zoom = 2.0;
        paint(lvl, cam, cache, bounds(lvl));

        assertTrue(cache.rebuilds() > 0, "a baked chunk is only valid at the zoom it was baked at");
    }

    @Test
    void theRevisionCounterMovesOnlyOnRealChanges() {
        Level lvl = level(LevelFormat.TOP_DOWN, 8, 8);
        long start = lvl.terrainRevision();

        lvl.setTile(1, 1, lvl.tileAt(1, 1));
        assertEquals(start, lvl.terrainRevision(), "a no-op set is not a revision");

        lvl.setTile(1, 1, 0);
        assertTrue(lvl.terrainRevision() > start, "clearing a tile is");

        long afterClear = lvl.terrainRevision();
        Block b = lvl.blocks.all().iterator().next();
        lvl.setUpper(2, 2, b.id());
        assertTrue(lvl.terrainRevision() > afterClear, "and so is stacking one");
    }

    @Test
    void theBakedFloorMatchesTheLiveSweepUpToAUniformShift() {
        // The baked floor sits on its own global pixel lattice, which is what
        // stops it shaking; that lattice can land a pixel away from where the
        // live sweep would have put the terrain. What matters is that the whole
        // sheet moves together, so the test allows a uniform offset and then
        // demands a close match at it — a per-chunk wobble would satisfy no
        // single offset.
        for (LevelFormat format : new LevelFormat[]{
                LevelFormat.SIDE_SCROLLER, LevelFormat.TOP_DOWN}) {
            Level lvl = level(format, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
            Camera cam = camera(lvl);
            TerrainCache cache = new TerrainCache();
            warmUp(lvl, cam, cache);

            BufferedImage live = paint(lvl, cam, null, bounds(lvl));
            BufferedImage cached = paint(lvl, cam, cache, bounds(lvl));

            double best = 100;
            int bestDx = 0, bestDy = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    double pct = 100.0 * shiftedDiff(live, cached, dx, dy) / (W * H);
                    if (pct < best) { best = pct; bestDx = dx; bestDy = dy; }
                }
            }
            assertTrue(best < 1.0, format + " baked floor differs from the live sweep by "
                    + best + "% even at its best offset (" + bestDx + "," + bestDy + ")");
        }
    }

    /**
     * And it still matches at every heading it claims to be faithful at.
     *
     * <p>This is the assertion the rule above is worth having for. Saying a
     * turned view is cacheable is a claim about pixels, and the cache had two
     * places where a heading could go missing: the single camera offset every
     * chunk is placed from, which was computed from the camera's <em>world</em>
     * position rather than its projected one, and the validity key, which did
     * not mention the heading at all and would have served a chunk baked
     * looking north to a camera looking east. Neither is visible in a diff and
     * both put the whole floor somewhere the live sweep is not.
     *
     * <p>Isometric is in the list, at the headings where the diamond is a
     * square again — the first time this cache has ever baked a diamond
     * projection, which is why the bake camera's derivation had to stop
     * assuming it never would.
     */
    @Test
    void theBakedFloorMatchesTheLiveSweepAtEveryHeadingItClaims() {
        for (LevelFormat format : new LevelFormat[]{
                LevelFormat.SIDE_SCROLLER, LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC}) {
            for (int eighth = 0; eighth < 8; eighth++) {
                double yaw = eighth * Camera.EIGHTH_TURN;
                Level lvl = level(format, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
                Camera cam = camera(lvl);
                cam.setYaw(yaw);
                if (!TerrainCache.faithfulIn(cam)) continue;

                TerrainCache cache = new TerrainCache();
                warmUp(lvl, cam, cache);
                assertTrue(cache.hits() > 0, format + " at " + eighth
                        + "/8: nothing was served from the cache, so this proves nothing");

                BufferedImage live = paint(lvl, cam, null, bounds(lvl));
                BufferedImage cached = paint(lvl, cam, cache, bounds(lvl));

                double best = 100;
                int bestDx = 0, bestDy = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        double pct = 100.0 * shiftedDiff(live, cached, dx, dy) / (W * H);
                        if (pct < best) { best = pct; bestDx = dx; bestDy = dy; }
                    }
                }
                assertTrue(best < 1.0, format + " at " + eighth + "/8 of a turn: the baked "
                        + "floor differs from the live sweep by " + best + "% even at its "
                        + "best offset (" + bestDx + "," + bestDy + ")");
            }
        }
    }

    /**
     * Turning the camera invalidates the floor, rather than serving the
     * heading it was baked at.
     *
     * <p>C3 offered two ways to handle a turn — key every chunk by
     * {@code (chunk, yaw)} at eight times the memory, or throw the cache away
     * when the view turns — and recommended the second. It is the second, and
     * it costs nothing to implement because it is what putting the heading in
     * the validity key already does: a chunk baked looking north simply is not
     * a valid chunk for a camera looking east, in the same way a chunk baked at
     * one zoom is not valid at another.
     *
     * <p><b>This test exists because the negative control found nothing to
     * fail.</b> Deleting the heading from the key left all twenty-eight of this
     * class's other tests green: every one of them builds a cache, uses it at
     * one heading, and throws it away. Nothing turned a camera that a cache was
     * already warm for, which is the only way the defect can show — and it is
     * what a player does every time they press the rotate key.
     */
    @Test
    void turningTheCameraRebuildsTheFloorRatherThanServingTheOldHeading() {
        Level lvl = level(LevelFormat.TOP_DOWN, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);

        // A quarter turn: still cacheable, and a completely different picture.
        cam.setYaw(2 * Camera.EIGHTH_TURN);
        cache.resetCounters();
        paint(lvl, cam, cache, bounds(lvl));
        assertEquals(0, cache.hits(), "a chunk baked at another heading was reused");

        warmUp(lvl, cam, cache);
        BufferedImage live = paint(lvl, cam, null, bounds(lvl));
        BufferedImage cached = paint(lvl, cam, cache, bounds(lvl));

        double best = 100;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                best = Math.min(best, 100.0 * shiftedDiff(live, cached, dx, dy) / (W * H));
            }
        }
        assertTrue(best < 1.0, "after a quarter turn the cached floor differs from the "
                + "live sweep by " + best + "% — the floor being drawn is the one baked "
                + "at the heading before the turn");
    }

    /**
     * A turn in flight draws live and leaves the cache alone, so the heading it
     * came from is still warm when it arrives back.
     *
     * <p>The plan's recommendation was to lean on the existing frame-level
     * stand-aside during a snap, and this is what that turned into: mid-turn
     * the projection is not cacheable at all ({@code faithfulIn}), so the
     * painter sweeps live and never reaches the cache. The chunks baked at the
     * previous heading are therefore not evicted — {@code endFrame} is not
     * called on a frame that did not use the cache — and turning back to a
     * heading recently left is instant rather than a rebuild.
     */
    @Test
    void aTurnInFlightSweepsLiveAndLeavesTheCacheWarmBehindIt() {
        Level lvl = level(LevelFormat.TOP_DOWN, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);

        for (double deg : new double[]{11, 23, 34, 45, 56, 67, 79}) {
            cam.setYaw(Math.toRadians(deg));
            cache.resetCounters();
            paint(lvl, cam, cache, bounds(lvl));
            assertEquals(0, cache.blits(), "mid-turn at " + deg + "° a chunk was blitted, "
                    + "and a chunk baked at a heading is a seam at any other");
            assertEquals(0, cache.rebuilds(), "mid-turn at " + deg + "° a chunk was baked, "
                    + "which would be thrown away by the next degree of the turn");
        }

        cam.setYaw(0);
        cache.resetCounters();
        paint(lvl, cam, cache, bounds(lvl));
        assertTrue(cache.hits() > 0, "coming back to the heading the turn started from "
                + "found nothing warm — the turn evicted what it did not use");
    }

    /** Pixels where {@code b} disagrees with {@code a} shifted by (dx, dy). */
    private static int shiftedDiff(BufferedImage a, BufferedImage b, int dx, int dy) {
        int n = 0;
        for (int y = 20; y < H - 20; y++) {
            for (int x = 20; x < W - 20; x++) {
                int ax = x - dx, ay = y - dy;
                if (ax < 0 || ay < 0 || ax >= W || ay >= H) continue;
                if (a.getRGB(ax, ay) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }

    @Test
    void isometricIsNotCachedBecauseItsEdgesAreAntialiased() {
        // Diamond tiles share diagonal edges; baked separately they blend
        // against transparency and every shared edge picks up a seam.
        assertFalse(TerrainCache.faithfulIn(unturned(Perspective.ISOMETRIC)));
        assertTrue(TerrainCache.faithfulIn(unturned(Perspective.SIDE_SCROLL)));
        assertTrue(TerrainCache.faithfulIn(unturned(Perspective.TOP_DOWN)));
    }

    /**
     * What decides it is where the tile's edges land, not which format asked —
     * so rotation moves both formats in and out of the answer, in opposite
     * phase.
     *
     * <p>C3's precondition measurement, as a rule. A tile edge on a screen axis
     * has no partial coverage to blend, so chunk images composite exactly; a
     * diagonal one blends against transparency in its own image and leaves the
     * background showing through along every shared edge. Top-down loses that
     * property the moment it turns off a cardinal heading and isometric
     * <em>gains</em> it at 45°, where an eighth of a turn puts the diamond's
     * edges back on the axes. The seam measured 0.5–0.7% of frame pixels at
     * every heading this says no to, and 0.001–0.055% at every heading it says
     * yes to — an order of magnitude, either side of one rule.
     */
    @Test
    void whatDecidesCacheabilityIsWhereTheTileEdgesLand() {
        for (int eighth = 0; eighth < 8; eighth++) {
            double yaw = eighth * Camera.EIGHTH_TURN;

            // A side view has no heading to be at; it is always cacheable.
            assertTrue(TerrainCache.faithfulIn(turned(Perspective.SIDE_SCROLL, yaw)),
                    "a side-scroller at " + eighth + "/8 of a turn");

            // Top-down keeps its axis-aligned edges only at the cardinals.
            assertEquals(eighth % 2 == 0,
                    TerrainCache.faithfulIn(turned(Perspective.TOP_DOWN, yaw)),
                    "top-down at " + eighth + "/8 of a turn: cacheable exactly when its "
                            + "tile edges are still on the screen axes");

            // Isometric is the same rule in the opposite phase: the diamond is
            // a square again at the diagonal headings.
            assertEquals(eighth % 2 == 1,
                    TerrainCache.faithfulIn(turned(Perspective.ISOMETRIC, yaw)),
                    "isometric at " + eighth + "/8 of a turn: an eighth of a turn puts "
                            + "the diamond's edges back on the screen axes");
        }
    }

    /** Mid-snap, between two headings, nothing is cacheable in a plan view. */
    @Test
    void aTurnInFlightIsNotCacheableInEitherPlanView() {
        for (double deg : new double[]{5, 22.5, 30, 67.5, 100}) {
            double yaw = Math.toRadians(deg);
            assertFalse(TerrainCache.faithfulIn(turned(Perspective.TOP_DOWN, yaw)),
                    "top-down mid-turn at " + deg + "°");
            assertFalse(TerrainCache.faithfulIn(turned(Perspective.ISOMETRIC, yaw)),
                    "isometric mid-turn at " + deg + "°");
        }
    }

    /**
     * The rule matches the artefact it is about, measured every run.
     *
     * <p>Everything above takes {@code faithfulIn} at its word. This is the
     * measurement the word is standing in for, and it is kept as a test rather
     * than written down as a number because a number in a comment is a claim
     * about a version of Java2D's rasteriser rather than about this one.
     *
     * <p>The floor is drawn twice: once whole, and once in chunk-sized pieces
     * composited afterwards, both through the same camera so placement cannot
     * enter into it and the only thing being measured is what compositing
     * separately-antialiased edges does. The two agree to a few hundredths of a
     * percent at every heading the rule allows and disagree by half a percent
     * or more at every heading it refuses — an order of magnitude either side of
     * one line, which is what makes it a rule rather than a threshold.
     */
    @Test
    void theCacheabilityRuleMatchesTheSeamItIsAbout() {
        for (LevelFormat format : new LevelFormat[]{LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC}) {
            for (int eighth = 0; eighth < 8; eighth++) {
                Level lvl = level(format, 24, 24);
                Camera cam = camera(lvl);
                cam.setYaw(eighth * Camera.EIGHTH_TURN);

                double seam = seamPercent(lvl, cam);
                boolean claimed = TerrainCache.faithfulIn(cam);

                if (claimed) {
                    assertTrue(seam < 0.2, format + " at " + eighth + "/8 of a turn is "
                            + "claimed cacheable, and baking it in chunks leaves " + seam
                            + "% of the frame different from the live sweep");
                } else {
                    assertTrue(seam > 0.2, format + " at " + eighth + "/8 of a turn is "
                            + "refused, but baking it in chunks costs only " + seam
                            + "% — the rule is turning down a view it could serve");
                }
            }
        }
    }

    /**
     * How much of the frame changes when the floor is drawn in chunk-sized
     * pieces and composited, rather than swept whole.
     *
     * <p>Both layers land on an opaque backdrop before they are compared,
     * because that is the whole mechanism: two half-covered edge pixels
     * composited leave a quarter of the <em>background</em> showing through,
     * and against a transparent base there is no background to show.
     */
    private static double seamPercent(Level lvl, Camera cam) {
        int[] whole = {0, 0, lvl.width - 1, lvl.height - 1};
        BufferedImage live = onBackdrop(layer(lvl, cam, whole));

        BufferedImage pieces = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = pieces.createGraphics();
        int across = (lvl.width + TerrainCache.CHUNK - 1) / TerrainCache.CHUNK;
        int down = (lvl.height + TerrainCache.CHUNK - 1) / TerrainCache.CHUNK;
        for (int cr = 0; cr < down; cr++) {
            for (int cc = 0; cc < across; cc++) {
                g.drawImage(layer(lvl, cam, new int[]{
                        cc * TerrainCache.CHUNK, cr * TerrainCache.CHUNK,
                        Math.min(lvl.width - 1, (cc + 1) * TerrainCache.CHUNK - 1),
                        Math.min(lvl.height - 1, (cr + 1) * TerrainCache.CHUNK - 1)}), 0, 0, null);
            }
        }
        g.dispose();
        BufferedImage composited = onBackdrop(pieces);

        return 100.0 * differingPixels(live, composited) / (W * H);
    }

    /** One terrain pass into a transparent layer of its own. */
    private static BufferedImage layer(Level lvl, Camera cam, int[] bounds) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam, bounds, 0.0, pass, null);
        pass.flush();
        g.dispose();
        return img;
    }

    private static BufferedImage onBackdrop(BufferedImage layer) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        g.drawImage(layer, 0, 0, null);
        g.dispose();
        return img;
    }

    private static Camera unturned(Perspective perspective) {
        return turned(perspective, 0);
    }

    private static Camera turned(Perspective perspective, double yaw) {
        Camera cam = new Camera(perspective, W, H);
        cam.tileSize = TILE;
        cam.setYaw(yaw);
        return cam;
    }

    @Test
    void anIsometricLevelDrawsIdenticallyWithOrWithoutACache() {
        Level lvl = level(LevelFormat.ISOMETRIC, 16, 16);
        Camera cam = camera(lvl);

        BufferedImage live = paint(lvl, cam, null, bounds(lvl));
        BufferedImage withCache = paint(lvl, cam, new TerrainCache(), bounds(lvl));

        assertEquals(0, differingPixels(live, withCache),
                "handing a cache to an isometric level must change nothing at all");
    }

    @Test
    void chunksNoLongerOnScreenAreDropped() {
        // Otherwise walking a large level ends up holding it all as images.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 8, TerrainCache.CHUNK * 8);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();

        paint(lvl, cam, cache, new int[]{0, 0, TerrainCache.CHUNK - 1, TerrainCache.CHUNK - 1});
        cache.resetCounters();
        // A window somewhere else entirely: the first chunk should be evicted,
        // so coming back to it is a rebuild rather than a hit.
        int far = TerrainCache.CHUNK * 6;
        paint(lvl, cam, cache, new int[]{far, far, far + TerrainCache.CHUNK - 1,
                far + TerrainCache.CHUNK - 1});
        cache.resetCounters();
        paint(lvl, cam, cache, new int[]{0, 0, TerrainCache.CHUNK - 1, TerrainCache.CHUNK - 1});

        assertTrue(cache.rebuilds() > 0, "a chunk left behind should not still be held");
    }

    /** One frame at a given camera x, through the cache. */
    private static BufferedImage frameAt(Level lvl, double camX, TerrainCache cache) {
        Camera cam = new Camera(lvl.perspective, W, H);
        cam.tileSize = TILE;
        cam.zoom = 1.0;
        cam.centerOn(camX, lvl.height * TILE / 2.0);
        return paint(lvl, cam, cache, bounds(lvl));
    }

    @Test
    void theBakedFloorDoesNotJitterAsTheCameraCreeps() {
        // The bug this cache shipped with, and the reason it is worth a test of
        // its own: each chunk used to round its own screen position, so as the
        // camera moved a fraction of a pixel the chunks crossed their rounding
        // boundaries at different moments and slid against each other. Standing
        // still it measured as 0.02% of pixels and looked harmless. Moving, it
        // was terrain visibly shaking.
        //
        // Slide the camera a quarter-pixel at a time and require each frame to
        // equal the previous one shifted by the whole pixels the camera moved:
        // a rigid sheet matches exactly. Before the fix this reported over
        // sixteen thousand differing pixels per step.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 40, 20);
        TerrainCache cache = new TerrainCache();

        double start = 500.0;
        for (int i = 0; i < 40; i++) frameAt(lvl, start, cache);   // warm every chunk

        BufferedImage prev = frameAt(lvl, start, cache);
        for (int step = 1; step <= 24; step++) {
            double camX = start + step * 0.25;
            BufferedImage cur = frameAt(lvl, camX, cache);
            int dx = (int) Math.round(-camX) - (int) Math.round(-(camX - 0.25));
            assertEquals(0, shiftedDiff(prev, cur, dx, 0),
                    "terrain shifted non-rigidly at camX=" + camX
                            + " — chunks are rounding independently again");
            prev = cur;
        }
    }

    @Test
    void bakingIsSpreadAcrossFramesRatherThanStallingOne() {
        // Walking into unseen ground asks for a screenful of chunks at once.
        // Baking them together turned a 0.8 ms median into a 13 ms spike.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 120, 80);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();

        paint(lvl, cam, cache, bounds(lvl));

        assertTrue(cache.rebuilds() <= 8,
                "one frame baked " + cache.rebuilds() + " chunks; the budget should cap it");
    }

    @Test
    void everythingIsCachedAfterEnoughFrames() {
        // The budget spreads the work; it must not prevent it finishing.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 40, 24);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();

        for (int i = 0; i < 60; i++) paint(lvl, cam, cache, bounds(lvl));
        cache.resetCounters();
        paint(lvl, cam, cache, bounds(lvl));

        assertEquals(0, cache.rebuilds(), "a settled view should rebuild nothing");
        assertTrue(cache.hits() > 0, "and should be serving hits");
    }

    @Test
    void aDecoratorTurnsTheCacheOff() {
        // An open container's lid animates over a finished top face; baking it
        // would freeze it mid-swing until someone edited the level.
        Level lvl = level(LevelFormat.TOP_DOWN, 16, 16);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        RecordingTarget target = new RecordingTarget(W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);

        TerrainPainter.draw(target, lvl, cam, bounds(lvl), 0.0, pass,
                (t, col, row, xs, ys, block, color) -> { }, null, cache);
        pass.flush();

        assertEquals(0, cache.rebuilds(), "a decorated pass must not bake anything");
        assertTrue(target.count("fillPolygon") > 0, "and must still draw the floor live");
    }

    @Test
    void theCacheDrawsChunksAsBlitsRatherThanCells() {
        // The point of the whole exercise: a few images instead of thousands
        // of per-cell fills.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        warmUp(lvl, cam, cache);
        RecordingTarget target = new RecordingTarget(W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);

        TerrainPainter.draw(target, lvl, cam, bounds(lvl), 0.0, pass, null, null, cache);
        pass.flush();

        assertEquals(0, target.count("fillPolygon"),
                "no cell is filled on the screen target — they went into the chunk images");
        assertEquals(9, target.count("drawImage"), "three by three chunks, one blit each");
    }

    // --- as time passes, which is what nothing above asked ------------------------

    /**
     * <b>The vibration, and the one assertion that sees it.</b> A static level,
     * the camera not moving, two seconds of frames: every frame must be
     * pixel-identical to the one before it.
     *
     * <p>Before the fix, 71 of 119 frames were not — in a level with no animated
     * textures anywhere, with nothing in the world moving and the player standing
     * still. The animation frame number was in every chunk's validity key, so all
     * twelve visible chunks went stale together twelve times a second; the
     * four-per-frame rebuild budget could not keep up; and the overflow was drawn
     * <em>live</em> while the rest was blitted from baked images. Baked and live
     * are not pixel-identical, so a shifting two thirds of the terrain flipped
     * between two renderings and back, on a 12 Hz beat, for as long as the game
     * ran. The frames it produced went, at 1280x720 and zoom 1.7:
     *
     * <pre>
     * frame | from cache | re-baked | drawn LIVE
     *     0 |          0 |        4 |  8
     *     1 |          4 |        4 |  4
     *     2 |          8 |        4 |  0
     *     3 |         12 |        0 |  0
     *     4 |         12 |        0 |  0
     *     5 |          0 |        4 |  8   <- and again, every 83 ms
     * </pre>
     *
     * <p>The first frames are allowed to differ: nothing is baked before the first
     * one, so the view is swept live until the budget has caught up, and the
     * hand-over from swept to baked is one visible transition of about 0.05% of
     * pixels. It happens once, on entering a level. What must not happen is a
     * second one.
     */
    @Test
    void aStillLevelIsPixelIdenticalFrameAfterFrameAsTimePasses() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 40, 30);
        Camera cam = camera(lvl);
        cam.zoom = 1.7;                       // a fractional zoom, as the game leaves it
        TerrainCache cache = new TerrainCache();
        int[] b = bounds(lvl);

        // Let the cold cache hand over from the live sweep to baked chunks.
        for (int i = 0; i < 30; i++) paint(lvl, cam, cache, b, i / 60.0);

        BufferedImage prev = paint(lvl, cam, cache, b, 30 / 60.0);
        for (int f = 31; f < 150; f++) {
            BufferedImage cur = paint(lvl, cam, cache, b, f / 60.0);
            assertEquals(0, differingPixels(prev, cur),
                    "frame " + f + " of a static level differs from the frame before it. "
                            + "Nothing in the world moved and the camera did not either, so "
                            + "the terrain is switching between two renderings of itself — "
                            + "which is what a player sees as the blocks vibrating");
            prev = cur;
        }
    }

    /**
     * And the mechanism, stated directly: time passing does not invalidate a
     * chunk of static blocks.
     */
    @Test
    void theAnimationClockDoesNotInvalidateAChunkWithNothingAnimatedInIt() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 32, 24);
        Camera cam = camera(lvl);
        TerrainCache cache = new TerrainCache();
        int[] b = bounds(lvl);

        for (int i = 0; i < 40; i++) paint(lvl, cam, cache, b, i / 60.0);
        cache.resetCounters();
        // Ten seconds of clock — a hundred and twenty animation frames' worth.
        for (int f = 0; f < 600; f++) paint(lvl, cam, cache, b, 100 + f / 60.0);

        assertEquals(0, cache.rebuilds(),
                "ten seconds of clock rebuilt " + cache.rebuilds() + " chunks in a level "
                        + "with no animated textures in it; the frame number belongs to the "
                        + "chunks that actually animate");
        assertTrue(cache.hits() > 0, "and every chunk should have been served from cache");
    }

    /**
     * A frame is never half baked and half swept.
     *
     * <p>This class's own measurement is that mixing costs more than either
     * extreme — 22 ms against 16 ms all-live and 2 ms all-cached — and the churn
     * threshold was written to prevent it. The rebuild budget then produced one
     * anyway whenever more chunks went stale than it could rebake, which is the
     * defect above. So the invariant is asserted rather than described: on every
     * frame of a walk across a level, either every visible chunk came from the
     * cache (with any stale ones rebaked first) or none of them did.
     */
    @Test
    void noFrameMixesBakedBlitsWithLiveFills() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 200, 30);
        TerrainCache cache = new TerrainCache();

        for (int f = 0; f < 400; f++) {
            Camera cam = new Camera(lvl.perspective, W, H);
            cam.tileSize = TILE;
            cam.zoom = 1.7;
            cam.centerOn(600 + f * (220.0 / 60.0), lvl.height * TILE / 2.0);
            int[] b = visible(lvl, cam);
            int chunks = chunkCount(b);

            cache.resetCounters();
            paint(lvl, cam, cache, b, f / 60.0);
            int blits = cache.blits();

            assertTrue(blits == 0 || blits == chunks,
                    "frame " + f + " blitted " + blits + " of " + chunks + " visible chunks "
                            + "from the cache and drew the rest live. A half-and-half frame is "
                            + "both the slowest rendering available and a picture that differs "
                            + "from its neighbours in a shifting patch of the screen");
        }
    }

    /**
     * Running water does not flip the whole view to a live sweep on the liquid's
     * own beat.
     *
     * <p>{@code LiquidSim} ticks about every 0.22 s, and each tick rewrites cells.
     * The old rule counted <em>cells</em> changed per frame and swept the entire
     * view when the count passed a threshold — so a pond pouring through a gap in
     * the floor produced one all-live frame every thirteenth frame with all-baked
     * frames either side of it, and the seams flickered across the whole screen at
     * about 4.6 Hz. Thirty cells inside three chunks is three cheap rebakes, and
     * that is what the frame now asks: do the chunks that need rebaking fit in the
     * budget?
     */
    @Test
    void aFlowingLiquidRebakesItsOwnChunksRatherThanSweepingTheView() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 60, 40);
        Block water = null;
        for (Block block : lvl.blocks.all()) {
            if (block.liquid()) { water = block; break; }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(water != null,
                "the block registry has no liquid to flow");

        Camera cam = camera(lvl);
        cam.zoom = 1.7;
        // Bounds as the play scene computes them — the viewport, not the level.
        // Handing the cache a region far bigger than the screen asks it to keep
        // forty chunks fresh on a budget sized for six, which is not a thing the
        // game ever does.
        int[] b = visible(lvl, cam);
        int chunks = chunkCount(b);
        // A small pool in view, sitting on a floor with a gap for it to pour
        // through, so it keeps flowing rather than settling in a tick or two.
        int midCol = (b[0] + b[2]) / 2, midRow = (b[1] + b[3]) / 2;
        for (int c = b[0]; c <= b[2]; c++) lvl.setTile(c, midRow + 2, lvl.tileAt(c, midRow + 2));
        for (int r = midRow - 2; r <= midRow; r++) {
            for (int c = midCol - 2; c <= midCol + 2; c++) lvl.setTile(c, r, water.id());
        }
        lvl.setTile(midCol, midRow + 1, 0);
        lvl.setTile(midCol, midRow + 2, 0);

        TerrainCache cache = new TerrainCache();
        for (int i = 0; i < 40; i++) paint(lvl, cam, cache, b, i / 60.0);

        com.larsons.engine.world.LiquidSim liquids = new com.larsons.engine.world.LiquidSim();
        int sweptFrames = 0;
        for (int f = 0; f < 300; f++) {
            liquids.step(lvl, true, 1.0 / 60.0);
            cache.resetCounters();
            paint(lvl, cam, cache, b, f / 60.0);
            if (cache.blits() == 0) sweptFrames++;
        }

        // The pool's first spread genuinely rewrites a lot at once and may cost a
        // frame or two; what must not happen is a sweep on the liquid's own beat.
        // The old cell-counting rule produced one every thirteenth frame — about
        // twenty-three of these three hundred.
        assertTrue(sweptFrames <= 3,
                sweptFrames + " frames of a level with a pool in it threw the whole "
                        + "cache away and swept " + chunks + " chunks live. A liquid that "
                        + "rewrites a handful of cells should rebake the chunks holding them");
    }

    /**
     * The other direction, which the fix must not break: a chunk that really does
     * hold an animated texture still refreshes as the clock advances.
     *
     * <p>Taking the frame number out of the validity key for every chunk would be
     * a fix that froze tile animation, and freezing it would be invisible in every
     * other test here — they all run at a stopped clock. So the animated case is
     * pinned beside the static one.
     */
    @Test
    void aChunkHoldingAnAnimatedTextureStillRefreshes(@TempDir Path dir) throws IOException {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 32, 24);
        // Two visibly different frames at 8 fps on whatever block sits at (0,0).
        Block block = lvl.blockAt(0, 0);
        org.junit.jupiter.api.Assumptions.assumeTrue(block != null, "no block at (0,0)");
        BufferedImage sheet = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setColor(Color.RED);
        sg.fillRect(0, 0, 32, 32);
        sg.setColor(Color.BLUE);
        sg.fillRect(32, 0, 32, 32);
        sg.dispose();
        Path file = dir.resolve("flow.png");
        javax.imageio.ImageIO.write(sheet, "png", file.toFile());
        String key = block.textureKey();
        try {
            Skins.put(new SkinDef(key, file.toString(), 32, 32, 2, 8));
            assertTrue(Skins.animated(key), "the sheet just installed should read as animated");

            Camera cam = camera(lvl);
            TerrainCache cache = new TerrainCache();
            int[] b = bounds(lvl);
            for (int i = 0; i < 60; i++) paint(lvl, cam, cache, b, i / 60.0);

            cache.resetCounters();
            // One second of clock is twelve of the cache's animation frames.
            for (int f = 0; f < 60; f++) paint(lvl, cam, cache, b, 10 + f / 60.0);

            assertTrue(cache.rebuilds() > 0,
                    "a chunk holding a two-frame sheet at 8 fps was never rebuilt over a "
                            + "second of clock, so its animation is frozen on screen");
        } finally {
            Skins.remove(key);
        }
    }

    /** The tile bounds a camera can actually see, as the play scene computes them. */
    private static int[] visible(Level lvl, Camera cam) {
        double[] tl = cam.screenToWorld(0, 0);
        double[] br = cam.screenToWorld(W, H);
        return new int[]{
                Math.max(0, (int) Math.floor(tl[0] / TILE) - 1),
                Math.max(0, (int) Math.floor(tl[1] / TILE) - 1),
                Math.min(lvl.width - 1, (int) Math.floor(br[0] / TILE) + 1),
                Math.min(lvl.height - 1, (int) Math.floor(br[1] / TILE) + 1)};
    }

    /** How many chunks those bounds cover — what a whole frame's worth of blits is. */
    private static int chunkCount(int[] b) {
        int c0 = Math.max(0, b[0] / TerrainCache.CHUNK);
        int r0 = Math.max(0, b[1] / TerrainCache.CHUNK);
        return (b[2] / TerrainCache.CHUNK - c0 + 1) * (b[3] / TerrainCache.CHUNK - r0 + 1);
    }
}
