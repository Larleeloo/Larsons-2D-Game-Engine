package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.level.Brush;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Building in three dimensions — {@code HEIGHT_PLAN.md} Job E.
 *
 * <p>Nothing above this job is reachable by a creator. A world can hold
 * eight-deep columns, a body can climb them, the renderer draws them and the
 * simulation respects them — and until the editor can aim at a face, none of
 * that is something anybody can make.
 */
@Timeout(60)
class HeightEditorTest {

    private static final int TILE = 32;
    private static final int CANVAS = 480;

    // --- placing against a face ---------------------------------------------------

    /**
     * Pointing at a column's top builds it higher; pointing at its side builds
     * outward from that face. The rule every block game uses, and the only one
     * that is not maddening.
     */
    @Test
    void aTopFaceBuildsUpwardAndASideFaceBuildsOutward() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = floored(format);
            int stone = lvl.blocks.get("stone").id();
            for (int layer = 1; layer <= 2; layer++) lvl.setTile(10, 10, layer, stone);
            Camera cam = camera(lvl);
            int lift = TerrainPainter.liftPixels(cam, TILE);
            int[] base = project(cam, 10.5 * TILE, 10.5 * TILE);

            TerrainPainter.Aim top = TerrainPainter.pick(cam, lvl,
                    base[0], base[1] - 2 * lift);
            assertNotNull(top, format + ": the top face is hit");
            assertTrue(top.top(), format + ": and is a top");
            assertEquals(10, top.placeCol(), format + ": building up stays in the column");
            assertEquals(10, top.placeRow(), format + ": building up stays in the column");
            assertEquals(3, top.placeLayer(lvl), format + ": on top of a three-deep column");

            TerrainPainter.Aim side = TerrainPainter.pick(cam, lvl, base[0], base[1] - lift / 2);
            assertNotNull(side, format + ": the side face is hit");
            assertEquals(10, side.col(), format + ": on the tower");
            assertFalse(side.top(), format + ": and it is a side");
            assertTrue(side.placeCol() != 10 || side.placeRow() != 10,
                    format + ": building against a side leaves the column");
        }
    }

    // --- what a column does when you build and mine it ----------------------------

    /** A column builds up to the level's ceiling, one block per placement. */
    @Test
    void placingRepeatedlyBuildsAColumnToTheCeiling() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        World world = new World(lvl);
        int stone = lvl.blocks.get("stone").id();

        for (int expected = 2; expected <= Level.DEFAULT_MAX_LAYERS; expected++) {
            assertEquals(expected - 1, lvl.placeLayer(10, 10),
                    "the next block goes on top of what is there");
            assertTrue(world.placeBlock(10, 10, stone), "and it goes");
            assertEquals(expected, lvl.stackHeight(10, 10));
        }
        assertEquals(-1, lvl.placeLayer(10, 10), "until the column reaches the ceiling");
        assertFalse(world.placeBlock(10, 10, stone));
    }

    /**
     * A column comes apart from the top down, and a hole cannot be mined into
     * the middle of one — a shape a heightfield has no way to hold.
     */
    @Test
    void miningTakesTheTopOfTheColumnAndNeverItsMiddle() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        World world = new World(lvl);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 1; layer <= 4; layer++) lvl.setTile(10, 10, layer, stone);
        assertEquals(5, lvl.stackHeight(10, 10));

        assertEquals(4, world.mineLayer(10, 10), "the tool bites the top");
        assertTrue(world.canMineLayer(10, 10, 4));
        assertFalse(world.canMineLayer(10, 10, 2),
                "and refuses the middle, which would leave blocks standing on nothing");

        assertNotNull(world.mineBlock(10, 10, false));
        assertEquals(4, lvl.stackHeight(10, 10), "one layer shorter");
        assertEquals(3, world.mineLayer(10, 10), "and the top has moved down with it");
    }

    // --- the tools that make a landscape ------------------------------------------

    /** Raise and lower move a whole footprint a layer at a time. */
    @Test
    void raiseAndLowerMoveTheGroundUnderTheBrush() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        List<int[]> stamp = Brush.cells(Brush.Shape.SQUARE, 3, 10, 10);

        assertTrue(Brush.applyHeight(lvl, Brush.Height.RAISE, stamp, 10, 10, stone));
        for (int[] cell : stamp) {
            assertEquals(2, lvl.stackHeight(cell[0], cell[1]),
                    "every cell under the brush came up a layer");
        }
        assertEquals(1, lvl.stackHeight(13, 10), "and nothing outside it moved");

        assertTrue(Brush.applyHeight(lvl, Brush.Height.LOWER, stamp, 10, 10, stone));
        for (int[] cell : stamp) assertEquals(1, lvl.stackHeight(cell[0], cell[1]));

        // Lowering stops at the floor rather than punching a hole: it is a
        // sculpting tool, and a hole is somewhere nobody can walk.
        Brush.applyHeight(lvl, Brush.Height.LOWER, stamp, 10, 10, stone);
        for (int[] cell : stamp) {
            assertEquals(1, lvl.stackHeight(cell[0], cell[1]),
                    "the floor is the bottom of the lower brush");
        }
    }

    /** Flatten levels a footprint to the height under the cursor. */
    @Test
    void flattenLevelsTheGroundToTheCellUnderTheCursor() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 1; layer <= 3; layer++) lvl.setTile(10, 10, layer, stone);
        lvl.setTile(11, 10, 1, stone);

        List<int[]> stamp = Brush.cells(Brush.Shape.SQUARE, 3, 10, 10);
        assertTrue(Brush.applyHeight(lvl, Brush.Height.FLATTEN, stamp, 10, 10, stone));
        for (int[] cell : stamp) {
            assertEquals(4, lvl.stackHeight(cell[0], cell[1]),
                    "the whole stamp is the centre's height now");
        }
    }

    /** Smooth turns a step into a slope. */
    @Test
    void smoothTurnsAStepIntoASlope() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        // A cliff: everything west of column 10 stands four deep.
        for (int c = 0; c < 10; c++) {
            for (int r = 0; r < lvl.height; r++) {
                for (int layer = 1; layer <= 3; layer++) lvl.setTile(c, r, layer, stone);
            }
        }
        assertEquals(4, lvl.stackHeight(9, 10));
        assertEquals(1, lvl.stackHeight(10, 10));

        List<int[]> stamp = Brush.cells(Brush.Shape.SQUARE, 5, 10, 10);
        for (int pass = 0; pass < 3; pass++) {
            Brush.applyHeight(lvl, Brush.Height.SMOOTH, stamp, 10, 10, stone);
        }
        assertTrue(lvl.stackHeight(10, 10) > 1,
                "the foot of the cliff came up, got " + lvl.stackHeight(10, 10));
        assertTrue(lvl.stackHeight(10, 10) < 4, "without becoming the cliff itself");
    }

    // --- generated landscape ------------------------------------------------------

    /**
     * A generated landscape has ground at more than one height, every cell has
     * something to stand on, and it survives a save.
     */
    @Test
    void aGeneratedLandscapeRollsAndIsAllStandable() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = LevelGenerator.generateLandscape("hills", 48, 48, TILE,
                    99L, format, 4);
            int min = Integer.MAX_VALUE, max = 0;
            for (int r = 0; r < lvl.height; r++) {
                for (int c = 0; c < lvl.width; c++) {
                    int h = lvl.stackHeight(c, r);
                    assertTrue(h >= 1, format + ": every cell has a floor at (" + c + "," + r + ")");
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            assertTrue(max > min, format + ": the ground rolls — " + min + " to " + max);
            assertTrue(max <= lvl.layerLimit(), format + ": and stays under the ceiling");

            // The same seed is the same landscape, which is what makes a
            // generated level something a creator can come back to.
            Level again = LevelGenerator.generateLandscape("hills", 48, 48, TILE,
                    99L, format, 4);
            assertEquals(lvl.toJson(), again.toJson(), format + ": deterministic in the seed");
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static Level floored(LevelFormat format) {
        Level lvl = Level.empty("edit", 20, 20, TILE);
        lvl.setFormat(format);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        GameProfile settings = new GameProfile("edit-test");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    private static Camera camera(Level lvl) {
        Camera cam = new Camera(lvl.perspective, CANVAS, CANVAS);
        cam.tileSize = lvl.tileSize;
        cam.centerOn(10.5 * TILE, 10.5 * TILE);
        return cam;
    }

    private static int[] project(Camera cam, double wx, double wy) {
        int[] out = new int[2];
        cam.worldToScreen(wx, wy, out);
        return out;
    }
}
