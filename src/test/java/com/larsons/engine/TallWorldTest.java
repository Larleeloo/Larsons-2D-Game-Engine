package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ceiling of {@value Level#MAX_LAYERS} — {@code HEIGHT_PLAN.md} Job T.
 *
 * <p><b>What this instrument is for.</b> Raising the ceiling is four
 * characters; every one of these tests guards something that was linear in the
 * old number and did not say so. Measured on the baseline with the ceiling
 * temporarily raised and nothing else changed, a single block placed at layer
 * 511 of a 240&times;140 level cost <b>67.2 MB</b>, one 512-block tower in a
 * 1024&times;1024 level cost <b>2.05 GB</b>, and one tower anywhere in a level
 * cost every visible cell 512 layers of walking — <b>2.59 ms a frame</b>, 15%
 * of a 60 Hz budget, to discover the sky is empty.
 *
 * <p>So these are not "does 512 work" tests. They pin the three costs that
 * would otherwise make 512 work only until somebody used it.
 */
@Timeout(60)
class TallWorldTest {

    private static final int TILE = 32;

    // --- the ceiling itself -------------------------------------------------------

    /** A plan view builds to {@value Level#MAX_LAYERS}; a side view still has one layer. */
    @Test
    void theCeilingIsFiveHundredAndTwelveOnAPlaneAndOneEdgeOn() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = tall(format);
            assertEquals(format.layered() ? Level.MAX_LAYERS : 1, lvl.layerLimit(),
                    format + ": the ceiling");
        }
    }

    /**
     * A level with nothing to say about its ceiling gets the format's, which is
     * what carries the new ceiling to every level ever saved: {@code maxLayers}
     * is written only when it differs from the default, so the default is what
     * "unspecified" means on disk.
     */
    @Test
    void aLevelThatNamesNoCeilingGetsTheFormats() {
        Level plain = tall(LevelFormat.TOP_DOWN);
        assertFalse(plain.toJson().contains("maxLayers"),
                "a level at the default does not write one");
        assertEquals(Level.MAX_LAYERS, LevelLoader.parse(plain.toJson()).layerLimit());

        plain.maxLayers = 8;
        assertTrue(plain.toJson().contains("maxLayers"), "and a level that means eight says so");
        assertEquals(8, LevelLoader.parse(plain.toJson()).layerLimit());
    }

    // --- (a) one block high up costs one layer, not five hundred ------------------

    /**
     * Placing one block at the ceiling allocates <em>that</em> layer and no
     * others.
     *
     * <p>This is the 67 MB. {@code ensureLayer} used to fill every layer it
     * grew past with a real {@code int[height][width]}, so a block at layer 511
     * came with 511 empty grids nobody asked for. Every reader already treats a
     * missing grid as empty, so the holes were pure cost.
     */
    @Test
    void aBlockAtTheCeilingAllocatesOneLayerAndNotTheFiveHundredUnderIt() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        int top = lvl.layerLimit() - 1;

        assertTrue(lvl.setTile(4, 4, top, stone), "the block goes in");

        assertNotNull(lvl.grid(top), "its own layer is there");
        assertNotNull(lvl.grid(Level.LAYER_GROUND), "and so is the floor");
        int allocated = 0;
        for (int layer = 0; layer < lvl.layerCount(); layer++) {
            if (lvl.grid(layer) != null) allocated++;
        }
        assertEquals(2, allocated,
                "the floor and the one layer written — not every layer in between");

        // And the holes read as empty rather than as anything at all.
        for (int layer = 1; layer < top; layer++) {
            assertEquals(0, lvl.tileAt(4, 4, layer), "layer " + layer + " is empty");
            assertFalse(lvl.solidAt(4, 4, layer), "layer " + layer + " stops nobody");
        }
        assertEquals(1, lvl.stackHeight(4, 4), "the run from the floor is the floor alone");
        assertEquals(top, lvl.topFilledLayer(4, 4), "and the block is where it was put");
    }

    /** A level saved with a hole in its layers loads back exactly as it was. */
    @Test
    void aSparseTowerSurvivesASaveAndALoad() {
        Level lvl = tall(LevelFormat.ISOMETRIC);
        int stone = lvl.blocks.get("stone").id();
        int[] at = {1, 5, 40, 300, lvl.layerLimit() - 1};
        for (int layer : at) lvl.setTile(6, 7, layer, stone);

        Level back = LevelLoader.parse(lvl.toJson());
        for (int layer : at) {
            assertEquals(stone, back.tileAt(6, 7, layer), "layer " + layer + " came back");
        }
        assertEquals(lvl.topFilledLayer(6, 7), back.topFilledLayer(6, 7));
        assertEquals(0, back.tileAt(6, 7, 200), "and the gaps are still gaps");
    }

    // --- (b) a dense level cannot promise more than it can pay for -----------------

    /**
     * A dense level's ceiling is what its footprint can afford; a chunked one
     * gets the whole thing.
     *
     * <p>The trade is real and is the reason this is stated rather than
     * discovered: a dense layer costs the whole footprint whether or not
     * anything stands in it, so <b>a bigger floor buys fewer floors</b>. At
     * 1024&times;1024 — the largest a level gets before it is chunked — 512
     * layers would have been 2 GB.
     */
    @Test
    void aDenseLevelsCeilingIsWhatItsFootprintCanPayFor() {
        Level small = Level.empty("small", 240, 140, TILE);
        small.setFormat(LevelFormat.TOP_DOWN);
        assertEquals(Level.MAX_LAYERS, small.layerLimit(),
                "an ordinary editor map builds the whole way up");

        Level big = Level.empty("big", 1024, 1024, TILE);
        big.setFormat(LevelFormat.TOP_DOWN);
        assertTrue(big.layerLimit() < Level.MAX_LAYERS,
                "a level at the dense limit does not promise 512 layers of itself");
        assertTrue(big.layerLimit() >= 2,
                "but never below the one stacked layer every plan view has had");
        assertTrue((long) big.width * big.height * big.layerLimit()
                        <= Level.DENSE_VOLUME_LIMIT,
                "and what it does promise fits the budget");

        Level giant = Level.emptyChunked("giant", 4096, 4096, TILE, null);
        giant.setFormat(LevelFormat.TOP_DOWN);
        assertEquals(Level.MAX_LAYERS, giant.layerLimit(),
                "chunked storage is sparse in exactly this dimension, so height is free");

        // The ceiling is a bound, not a suggestion: the storage refuses past it.
        int stone = big.blocks.get("stone").id();
        assertFalse(big.setTile(3, 3, big.layerLimit(), stone),
                "a block above what the storage can hold is refused");
    }

    // --- (c) the sweep is bounded by the column, not by the level ------------------

    /**
     * One tall tower does not make every other column expensive.
     *
     * <p>This is the 2.59 ms. {@code queueRaised} was bounded by
     * {@link Level#layerCount()} — how many layers <em>the level</em> has — so a
     * single 512-block tower made all 2000 cells on screen walk 512 layers.
     * {@link Level#columnDepth} is the same question asked per column.
     */
    @Test
    void oneTowerDoesNotMakeEveryOtherColumnDeep() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 0; layer < lvl.layerLimit(); layer++) lvl.setTile(2, 2, layer, stone);
        for (int layer = 0; layer < 8; layer++) lvl.setTile(9, 9, layer, stone);

        assertEquals(Level.MAX_LAYERS, lvl.layerCount(), "the level is as deep as its tower");
        assertEquals(Level.MAX_LAYERS, lvl.columnDepth(2, 2), "and so is the tower");
        assertEquals(8, lvl.columnDepth(9, 9), "the building next to it is eight");
        assertEquals(1, lvl.columnDepth(15, 15), "and bare floor is one");
        assertEquals(0, lvl.columnDepth(-1, 0), "a cell outside the level is nothing");
    }

    /**
     * The bound is never <em>too low</em>, which is the only direction that
     * matters: too high walks a few empty layers, too low drops geometry off
     * the screen. Put every way the terrain can change through it and compare
     * against an exact scan every time.
     */
    @Test
    void theColumnBoundNeverFallsBehindTheTerrain() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        int path = lvl.blocks.get("stone_path").id();
        Random rng = new Random(20260816L);

        for (int step = 0; step < 4000; step++) {
            int col = rng.nextInt(lvl.width), row = rng.nextInt(lvl.height);
            int layer = rng.nextInt(Math.min(24, lvl.layerLimit()));
            switch (rng.nextInt(6)) {
                // build up, so the bound has to rise
                case 0, 1 -> lvl.setTile(col, row, layer, stone);
                // clear, including clearing the top, so it has to fall
                case 2, 3 -> lvl.setTile(col, row, layer, 0);
                // repaint, which must not disturb it
                case 4 -> lvl.setTile(col, row, layer, path);
                // and mine a column apart from the top down, as a tool does
                default -> {
                    int top = lvl.topFilledLayer(col, row);
                    if (top > 0) lvl.setTile(col, row, top, 0);
                }
            }
            if (step % 37 == 0) assertBoundIsExact(lvl, col, row, "after step " + step);
        }
        for (int row = 0; row < lvl.height; row++) {
            for (int col = 0; col < lvl.width; col++) assertBoundIsExact(lvl, col, row, "at rest");
        }
    }

    /**
     * The terrain can also be replaced out from under the bound — a load, a
     * resize, a play-test being put back — and none of those go through
     * {@code setTile}. Each has to leave the bound telling the truth.
     */
    @Test
    void replacingTheTerrainWholesaleDoesNotStrandTheColumnBound() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 0; layer < 60; layer++) lvl.setTile(5, 5, layer, stone);
        assertEquals(60, lvl.columnDepth(5, 5), "read it, so there is something to strand");

        Object saved = lvl.snapshotTiles();
        for (int layer = 59; layer >= 20; layer--) lvl.setTile(5, 5, layer, 0);
        assertEquals(20, lvl.columnDepth(5, 5), "the tower came down");
        lvl.restoreTiles(saved);
        assertBoundIsExact(lvl, 5, 5, "after a play-test snapshot was put back");
        assertEquals(60, lvl.columnDepth(5, 5), "and the tower is back with it");

        lvl.resize(lvl.width + 7, lvl.height + 3);
        assertBoundIsExact(lvl, 5, 5, "after a resize");

        // A generator assigns straight into the floor grid, bypassing setTile
        // and every counter — which is safe only because this bounds the walk
        // *above* the floor. Pin that rather than trusting it.
        lvl.tiles()[6][6] = stone;
        assertBoundIsExact(lvl, 6, 6, "after a direct write to the floor");
    }

    /** {@link Level#columnDepth} against the definition, computed the slow way. */
    private static void assertBoundIsExact(Level lvl, int col, int row, String when) {
        int exact = 0;
        for (int layer = lvl.layerCount() - 1; layer >= 0; layer--) {
            if (lvl.tileAt(col, row, layer) > 0) { exact = layer + 1; break; }
        }
        assertEquals(exact, lvl.columnDepth(col, row),
                when + ": the bound at (" + col + "," + row + ")");
    }

    // --- T3: the sweep has to be offered the cells that rise into it ---------------

    /**
     * A tower whose own cell is below the viewport still paints into it, so the
     * bounds have to reach out along the height axis to find it.
     *
     * <p><b>This is the verification R2 wrote down and did not get.</b> That
     * step grew the painter's cull margin so a column off the bottom of the
     * screen would be kept — and the margin is only ever applied to cells the
     * scene put in the bounds, which were the four viewport corners projected
     * onto the floor and nothing else. The margin was never offered the cells
     * it existed to keep.
     */
    @Test
    void theBoundsReachOutAsFarAsTheLevelIsTall() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = tall(format, 60, 60);
            int stone = lvl.blocks.get("stone").id();
            Camera cam = new Camera(lvl.perspective, 480, 480);
            cam.tileSize = TILE;
            cam.centerOn(lvl.width * TILE / 2.0, lvl.height * TILE / 2.0);

            int[] flat = TerrainPainter.visibleBounds(cam, lvl, 480, 480);

            // A cell well below the bottom edge, and a tower on it exactly tall
            // enough to put its top face back on the camera's own focus. Both
            // come from the projection's lift vector rather than being written
            // down — a block at cell T drawn n layers up lands on the floor
            // point T − n·step, so T = focus + n·step is the cell whose n-tall
            // tower tops out at the middle of the screen. That is the same
            // arithmetic visibleBounds reaches out along, and it is different
            // in each format: straight down a row in top-down, and diagonally
            // across a row *and* a column in the diamond.
            int lift = TerrainPainter.liftPixels(cam, TILE);
            double[] step = cam.inversePlanar(0, Level.BLOCK_HEIGHT * TILE * cam.liftScale());
            // Far enough out that the floor-plane bounds genuinely exclude it
            // in *both* formats: the diamond's world-space bounding box reaches
            // about 11 tiles diagonally from the focus at this viewport, half
            // again what the square's reaches down a row, so a distance chosen
            // against top-down alone would still be inside it.
            int n = 20;
            int col = (int) Math.round(lvl.width / 2.0 + step[0] * n / TILE);
            int row = (int) Math.round(lvl.height / 2.0 + step[1] * n / TILE);
            int height = n + 1;
            int[] base = new int[2];
            cam.worldToScreen((col + 0.5) * TILE, (row + 0.5) * TILE, base);

            assertTrue(base[1] > 480, format + ": the tower's own cell is below the viewport");
            assertTrue(height > 1 && height < lvl.layerLimit(),
                    format + ": and a tower of " + height + " is one this level can hold");
            for (int layer = 0; layer < height; layer++) lvl.setTile(col, row, layer, stone);

            int topY = base[1] - lift * (height - 1);
            assertTrue(topY > 0 && topY < 480,
                    format + ": the tower's top face is inside the viewport");

            int[] tallBounds = TerrainPainter.visibleBounds(cam, lvl, 480, 480);
            assertTrue(area(tallBounds) > area(flat),
                    format + ": a tall level sweeps further than a flat one");
            assertTrue(col >= tallBounds[0] && col <= tallBounds[2]
                            && row >= tallBounds[1] && row <= tallBounds[3],
                    format + ": the cell whose column paints into the view is swept");

            // And the flat bounds — what both scenes computed before Job T —
            // are exactly the ones that would have dropped it.
            assertFalse(col >= flat[0] && col <= flat[2] && row >= flat[1] && row <= flat[3],
                    format + ": the floor-plane bounds alone would have missed it");
        }
    }

    private static int area(int[] b) {
        return Math.max(0, b[2] - b[0] + 1) * Math.max(0, b[3] - b[1] + 1);
    }

    // --- the rest of the engine at height -----------------------------------------

    /** A body climbs to the ceiling and stands on it, rather than at layer eight. */
    @Test
    void aColumnIsClimbableAllTheWayUp() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        int height = 400;
        for (int layer = 1; layer < height; layer++) lvl.setTile(6, 6, layer, stone);

        assertEquals(height, lvl.stackHeight(6, 6), "the column is as tall as it was built");
        assertEquals(height, lvl.supportHeight(6, 6), "and every block of it holds you up");
        assertEquals((height - 1) * lvl.blockHeight(), lvl.surfaceZ(height), 1e-9,
                "its top is where the arithmetic says");
        assertEquals(height - 1, TerrainPainter.standingLayer(lvl, lvl.surfaceZ(height)),
                "and a body at that height is standing on the top block");
    }

    /** The editor's cursor still resolves a column of hundreds, top face and side. */
    @Test
    void pickingStillFindsTheTopOfAVeryTallTower() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        int height = 200;
        for (int layer = 1; layer < height; layer++) lvl.setTile(10, 10, layer, stone);

        Camera cam = new Camera(lvl.perspective, 480, 480);
        cam.tileSize = TILE;
        cam.centerOn(10.5 * TILE, 10.5 * TILE);
        int[] base = new int[2];
        cam.worldToScreen(10.5 * TILE, 10.5 * TILE, base);
        int lift = TerrainPainter.liftPixels(cam, TILE);

        TerrainPainter.Aim top = TerrainPainter.pick(cam, lvl,
                base[0], base[1] - (height - 1) * lift);
        assertNotNull(top, "the top of a 200-block tower is pickable");
        assertTrue(top.top(), "and it is a top face");
        assertEquals(10, top.col());
        assertEquals(10, top.row());
        assertEquals(height - 1, top.layer(), "the layer under the cursor is the top one");
        assertEquals(height, top.placeLayer(lvl), "and the next block goes above it");
    }

    /** The server still validates a client's layer against the level's ceiling. */
    @Test
    void theCeilingIsStillTheRuleABlockEditIsCheckedAgainst() {
        Level lvl = tall(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        World world = new World(lvl);
        int stone = lvl.blocks.get("stone").id();

        assertTrue(lvl.setTile(4, 4, 400, stone), "400 is inside a 512 ceiling");
        assertFalse(lvl.setTile(4, 4, Level.MAX_LAYERS, stone), "512 is not");

        lvl.maxLayers = 12;
        assertEquals(12, lvl.layerLimit(), "a level may still choose a lower one");
        assertFalse(lvl.setTile(5, 5, 12, stone), "and it is enforced");
        assertNotNull(world, "the world reads the same level");
    }

    private static Level tall(LevelFormat format) {
        return tall(format, 24, 24);
    }

    private static Level tall(LevelFormat format, int w, int h) {
        Level lvl = Level.empty("tall", w, h, TILE);
        lvl.setFormat(format);
        GameProfile settings = new GameProfile("tall-test");
        settings.verticality = true;
        lvl.settings = settings;
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        return lvl;
    }
}
