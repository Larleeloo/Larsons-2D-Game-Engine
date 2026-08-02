package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.TerrainCache;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

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
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam, b, 0.0, pass, null, null, cache);
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
        assertFalse(TerrainCache.faithfulIn(Perspective.ISOMETRIC));
        assertTrue(TerrainCache.faithfulIn(Perspective.SIDE_SCROLL));
        assertTrue(TerrainCache.faithfulIn(Perspective.TOP_DOWN));
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
}
