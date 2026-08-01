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
        int built = cache.rebuilds();
        assertTrue(built > 0, "the first frame has to build something");

        cache.resetCounters();
        for (int i = 0; i < 10; i++) paint(lvl, cam, cache, bounds(lvl));

        assertEquals(0, cache.rebuilds(), "ten more frames of a still level rebuild nothing");
        assertTrue(cache.hits() >= built * 10, "every chunk should have been a hit");
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
        paint(lvl, cam, cache, bounds(lvl));
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
    void theBakedFloorMatchesTheLiveSweep() {
        // Chunk-aligned bounds, so the only difference left is chunk placement
        // rounding — the cache otherwise bakes cells the live sweep skipped.
        for (LevelFormat format : new LevelFormat[]{
                LevelFormat.SIDE_SCROLLER, LevelFormat.TOP_DOWN}) {
            Level lvl = level(format, TerrainCache.CHUNK * 3, TerrainCache.CHUNK * 3);
            Camera cam = camera(lvl);
            int[] b = bounds(lvl);

            BufferedImage live = paint(lvl, cam, null, b);
            BufferedImage cached = paint(lvl, cam, new TerrainCache(), b);

            double pct = 100.0 * differingPixels(live, cached) / (W * H);
            assertTrue(pct < 0.5,
                    format + " baked floor should match the live sweep, differed by " + pct + "%");
        }
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
        RecordingTarget target = new RecordingTarget(W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);

        TerrainPainter.draw(target, lvl, cam, bounds(lvl), 0.0, pass, null, null,
                new TerrainCache());
        pass.flush();

        assertEquals(0, target.count("fillPolygon"),
                "no cell is filled on the screen target — they went into the chunk images");
        assertEquals(9, target.count("drawImage"), "three by three chunks, one blit each");
    }
}
