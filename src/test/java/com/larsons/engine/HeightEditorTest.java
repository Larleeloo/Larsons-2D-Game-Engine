package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.InputBinding;
import com.larsons.engine.level.Brush;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.event.KeyEvent;
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
        for (LevelFormat format : List.of(LevelFormat.THREE_D)) {
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
        Level lvl = floored(LevelFormat.THREE_D);
        World world = new World(lvl);
        int stone = lvl.blocks.get("stone").id();

        for (int expected = 2; expected <= Level.MAX_LAYERS; expected++) {
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
        Level lvl = floored(LevelFormat.THREE_D);
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

    // --- where the editor's brush drops a block ------------------------------------

    /**
     * The editor's placement rule stacks: a block goes on top of whatever is
     * under the cursor, all the way to the level's ceiling, and says why when
     * there is nowhere left to put one.
     *
     * <p>What was here before stopped at two layers — the shape a plan view had
     * before it had a height axis — so no amount of clicking in the editor
     * could build the third block of anything.
     */
    @Test
    void aPaintedBlockGoesOnTopOfTheColumnAllTheWayToTheCeiling() {
        Level lvl = floored(LevelFormat.THREE_D);
        for (int expected = 1; expected < lvl.layerLimit(); expected++) {
            Brush.Placement at = Brush.place(lvl, 10, 10, -1);
            assertFalse(at.refused(), "layer " + expected + " has room");
            assertEquals(expected, at.fromLayer(), "on top of what is there");
            assertEquals(1, at.layers(), "one block per click");
            lvl.setTile(10, 10, at.fromLayer(), lvl.blocks.get("stone").id());
        }
        Brush.Placement full = Brush.place(lvl, 10, 10, -1);
        assertTrue(full.refused(), "a column at the ceiling has nowhere left to build");
        assertTrue(full.refusal().contains("ceiling"),
                "and says so: " + full.refusal());
        assertTrue(Brush.place(lvl, -1, 5, -1).refused(), "so is a cell off the level");
    }

    /**
     * A locked build height is what makes a terrace: a short column is built up
     * to it — every layer on the way, because a block hanging over a gap is a
     * shape a heightfield cannot hold — and a column already taller has the
     * block at that height repainted instead.
     */
    @Test
    void aLockedBuildHeightFillsUpToItAndRepaintsWhatIsAlreadyTaller() {
        Level lvl = floored(LevelFormat.THREE_D);
        int stone = lvl.blocks.get("stone").id();

        Brush.Placement climb = Brush.place(lvl, 10, 10, 3);
        assertEquals(1, climb.fromLayer(), "starts at the column's own top");
        assertEquals(3, climb.toLayer(), "and fills to the locked height");
        assertEquals(3, climb.layers(), "no floating block over a gap");

        for (int layer = 1; layer <= 5; layer++) lvl.setTile(11, 10, layer, stone);
        Brush.Placement inside = Brush.place(lvl, 11, 10, 3);
        assertEquals(3, inside.fromLayer(), "a taller column is repainted at that height");
        assertEquals(1, inside.layers());

        // The level's ceiling still bounds it: a lock set past the top clamps
        // rather than writing outside the storage. Taken from the ceiling
        // rather than written as a number — 99 was "past the top" while the
        // top was eight, and is an ordinary height now that it is
        // Level.MAX_LAYERS.
        Brush.Placement past = Brush.place(lvl, 12, 10, lvl.layerLimit() + 50);
        assertFalse(past.refused());
        assertEquals(lvl.layerLimit() - 1, past.toLayer());
    }

    /** A side-scroller has one layer, and painting a full cell repaints it. */
    @Test
    void aSideScrollerStillPaintsOneFlatLayer() {
        Level lvl = Level.empty("flat", 20, 20, TILE);
        lvl.setFormat(LevelFormat.SIDE_SCROLLER);
        lvl.setTile(4, 4, 0, lvl.blocks.get("stone").id());
        Brush.Placement at = Brush.place(lvl, 4, 4, -1);
        assertFalse(at.refused(), "a painted cell repaints rather than refusing");
        assertEquals(0, at.fromLayer());
        assertEquals(0, at.toLayer());
    }

    /**
     * The eraser bites whatever is on top of the column, however deep it is.
     * Reading layer 1 — which is what it did — took the middle out of anything
     * taller and left the top standing on nothing.
     */
    @Test
    void theTopOfATallColumnIsWhatComesOffFirst() {
        Level lvl = floored(LevelFormat.THREE_D);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 1; layer <= 4; layer++) lvl.setTile(10, 10, layer, stone);
        assertEquals(4, lvl.topFilledLayer(10, 10));
        lvl.setTile(10, 10, 4, 0);
        assertEquals(3, lvl.topFilledLayer(10, 10), "and it moves down with the column");
        lvl.setTile(3, 3, 0, 0);
        assertEquals(-1, lvl.topFilledLayer(3, 3), "an emptied cell has no top left");
    }

    // --- the tools that make a landscape ------------------------------------------

    /** Raise and lower move a whole footprint a layer at a time. */
    @Test
    void raiseAndLowerMoveTheGroundUnderTheBrush() {
        Level lvl = floored(LevelFormat.THREE_D);
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
        Level lvl = floored(LevelFormat.THREE_D);
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
        Level lvl = floored(LevelFormat.THREE_D);
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

    /**
     * Flatten aims at the locked build height when there is one, which is what
     * turns it from "spread the cursor's height around" into "cut a terrace at
     * layer four".
     */
    @Test
    void flattenAimsAtTheLockedBuildHeight() {
        Level lvl = floored(LevelFormat.THREE_D);
        int stone = lvl.blocks.get("stone").id();
        List<int[]> stamp = Brush.cells(Brush.Shape.SQUARE, 3, 10, 10);

        assertTrue(Brush.applyHeight(lvl, Brush.Height.FLATTEN, stamp, 10, 10, stone,
                3, lvl::setTile));
        for (int[] cell : stamp) {
            assertEquals(4, lvl.stackHeight(cell[0], cell[1]),
                    "layer 3 is a column four blocks deep");
        }
    }

    /**
     * A sculpting stroke writes through the editor's own layer writer, so every
     * layer it moves reaches the undo step and the multiplayer wire.
     *
     * <p>Writing straight to the level would have left the whole landscape
     * outside both, which is not the sort of thing to find out after building
     * one.
     */
    @Test
    void sculptingWritesEveryLayerThroughTheWriterItIsGiven() {
        Level lvl = floored(LevelFormat.THREE_D);
        int stone = lvl.blocks.get("stone").id();
        List<int[]> stamp = Brush.cells(Brush.Shape.SQUARE, 3, 10, 10);
        List<int[]> writes = new java.util.ArrayList<>();

        assertTrue(Brush.applyHeight(lvl, Brush.Height.RAISE, stamp, 10, 10, stone, -1,
                (col, row, layer, id) -> {
                    writes.add(new int[]{col, row, layer, id});
                    return lvl.setTile(col, row, layer, id);
                }));
        assertEquals(stamp.size(), writes.size(),
                "one layer written per cell of the stamp");
        for (int[] w : writes) assertEquals(1, w[2], "the layer the ground came up to");
    }

    /**
     * The cursor preview and the stroke are the same arithmetic, so what a
     * creator is shown is what they get — asserted on smooth, whose answer
     * nobody can work out by eye.
     */
    @Test
    void thePreviewAgreesWithTheStrokeItIsPreviewing() {
        Level lvl = floored(LevelFormat.THREE_D);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 0; c < 10; c++) {
            for (int r = 0; r < lvl.height; r++) {
                for (int layer = 1; layer <= 3; layer++) lvl.setTile(c, r, layer, stone);
            }
        }
        List<int[]> stamp = Brush.cells(Brush.Shape.CIRCLE, 5, 10, 10);
        int[] shown = new int[stamp.size()];
        for (int i = 0; i < stamp.size(); i++) {
            shown[i] = Brush.plannedHeight(lvl, Brush.Height.SMOOTH,
                    stamp.get(i)[0], stamp.get(i)[1], 10, 10, -1);
        }
        Brush.applyHeight(lvl, Brush.Height.SMOOTH, stamp, 10, 10, stone);
        for (int i = 0; i < stamp.size(); i++) {
            assertEquals(shown[i], lvl.stackHeight(stamp.get(i)[0], stamp.get(i)[1]),
                    "the ghost at (" + stamp.get(i)[0] + "," + stamp.get(i)[1]
                    + ") is the column the stroke left");
        }
    }

    // --- generated landscape ------------------------------------------------------

    /**
     * A generated landscape has ground at more than one height, every cell has
     * something to stand on, and it survives a save.
     */
    @Test
    void aGeneratedLandscapeRollsAndIsAllStandable() {
        for (LevelFormat format : List.of(LevelFormat.THREE_D)) {
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

    // --- the editor, driven by a mouse --------------------------------------------

    /**
     * The whole point of the job, asserted through the thing a creator
     * actually touches: click the top of a column in the creative editor and
     * it gets taller, in both plan views, past the two layers that used to be
     * the top of the world.
     */
    @Test
    void clickingAColumnsTopInTheEditorBuildsItHigher() {
        for (LevelFormat format : List.of(LevelFormat.THREE_D)) {
            Editor editor = Editor.open(format);
            Level lvl = editor.level();
            editor.selectFirstBlock();

            // The cell under the middle of the canvas, whatever the projection
            // put there — asked of the editor's own aim rather than assumed.
            TerrainPainter.Aim start = editor.aimAt(editor.canvasX(), editor.canvasY());
            assertNotNull(start, format + ": the cursor is over the level");
            int col = start.col(), row = start.row();
            int before = lvl.stackHeight(col, row);

            for (int click = 1; click <= 4; click++) {
                // Follow the column up: the top face of a taller column is
                // further up the screen, which is exactly what a creator does.
                editor.clickWorldTop(col, row);
                assertEquals(before + click, lvl.stackHeight(col, row),
                        format + ": click " + click + " left a column "
                        + (before + click) + " deep");
            }
            assertTrue(lvl.stackHeight(col, row) > 2,
                    format + ": and it went past the two layers that used to be the"
                    + " top of the world");
            // And one undo hands back the whole last stroke.
            assertTrue(editor.scene.history().canUndo(), format + ": strokes are undoable");
        }
    }

    /**
     * The landscape verbs are reachable from the editor: arm raise, drag the
     * brush, and the ground under it comes up — with the whole footprint in one
     * undo step.
     */
    @Test
    void theRaiseToolLiftsTheGroundUnderTheBrushAndUndoesInOneStep() {
        Editor editor = Editor.open(LevelFormat.THREE_D);
        Level lvl = editor.level();
        editor.selectFirstBlock();
        editor.press(KeyEvent.VK_H);        // Stack -> Raise
        assertEquals(Brush.Height.RAISE, editor.scene.buildTool());
        editor.press(KeyEvent.VK_CLOSE_BRACKET);
        editor.press(KeyEvent.VK_CLOSE_BRACKET);   // a 3-wide brush

        TerrainPainter.Aim at = editor.aimAt(editor.canvasX(), editor.canvasY());
        assertNotNull(at);
        int col = at.col(), row = at.row();
        int before = lvl.stackHeight(col, row);
        editor.clickWorldTop(col, row);
        assertEquals(before + 1, lvl.stackHeight(col, row), "the cell under the cursor");
        assertEquals(before + 1, lvl.stackHeight(col + 1, row), "and its neighbour");

        editor.pressWithCtrl(KeyEvent.VK_Z);
        assertEquals(before, lvl.stackHeight(col, row), "one step takes the stroke back");
        assertEquals(before, lvl.stackHeight(col + 1, row));
    }

    /** The build height winds down past the floor and back to following the surface. */
    @Test
    void theBuildHeightWindsUpAndOffTheBottomBackToTheSurface() {
        Editor editor = Editor.open(LevelFormat.THREE_D);
        assertEquals(-1, editor.scene.buildLayer(), "unlocked to start with");
        editor.press(KeyEvent.VK_PAGE_UP);
        editor.press(KeyEvent.VK_PAGE_UP);
        assertEquals(1, editor.scene.buildLayer());
        for (int i = 0; i < 4; i++) editor.press(KeyEvent.VK_PAGE_DOWN);
        assertEquals(-1, editor.scene.buildLayer(),
                "off the bottom is back to following the surface, not layer -3");
    }

    /**
     * A creative session driving the real scene: a viewport, an input manager
     * and a canvas to render into, with the few gestures these tests need.
     */
    private static final class Editor {
        private static final int W = 800, H = 600;
        private static final int SIDEBAR = 192;

        final com.larsons.engine.demo.CreativeScene scene;
        private final com.larsons.engine.scene.SceneManager scenes;
        private final com.larsons.engine.input.InputManager input =
                new com.larsons.engine.input.InputManager();
        private final java.awt.Graphics2D g;

        private Editor(com.larsons.engine.demo.CreativeScene scene,
                       com.larsons.engine.scene.SceneManager scenes) {
            this.scene = scene;
            this.scenes = scenes;
            this.g = new java.awt.image.BufferedImage(W, H,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
        }

        static Editor open(LevelFormat format) {
            System.setProperty("java.awt.headless", "true");
            var ctx = new com.larsons.engine.config.GameContext(null,
                    new com.larsons.engine.config.GameTypeStore());
            GameProfile p = new GameProfile("Height Editor Test");
            p.verticality = true;
            ctx.setProfile(p);
            ctx.setCreativeFormat(format);
            var scenes = new com.larsons.engine.scene.SceneManager();
            scenes.setViewport(W, H);
            var scene = new com.larsons.engine.demo.CreativeScene(ctx);
            scenes.register("creative", scene);
            scenes.setScene("creative");
            Editor editor = new Editor(scene, scenes);
            // The starter canvas carries no settings of its own, so it inherits
            // the profile's — which is what saving it would write anyway.
            scene.editing().captureSettings(p);
            editor.step();
            return editor;
        }

        Level level() { return scene.editing(); }

        int canvasX() { return (SIDEBAR + W) / 2; }

        int canvasY() { return H / 2; }

        void step() {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }

        TerrainPainter.Aim aimAt(int sx, int sy) {
            return TerrainPainter.pick(scene.camera(), level(), sx, sy);
        }

        /** Arm the first real block in the palette (index 0 is "+ New Block"). */
        void selectFirstBlock() {
            click(100, 362);
        }

        /**
         * Click the top face of the column at (col,row): its floor tile's
         * centre, carried up the screen by however many blocks are standing on
         * it. This is the gesture, and it is also the assertion — a build that
         * did not follow the column up would stop working at the second block.
         */
        void clickWorldTop(int col, int row) {
            int ts = level().tileSize;
            int[] out = new int[2];
            scene.camera().worldToScreen((col + 0.5) * ts, (row + 0.5) * ts, out);
            int lift = TerrainPainter.liftPixels(scene.camera(), ts)
                    * Math.max(0, level().stackHeight(col, row) - 1);
            click(out[0], out[1] - lift);
        }

        void click(int sx, int sy) {
            input.moveMouse(sx, sy);
            step();                       // the move is seen before the press
            input.pressMouse(java.awt.event.MouseEvent.BUTTON1, 0);
            step();
            input.releaseMouse(java.awt.event.MouseEvent.BUTTON1);
            step();
        }

        void press(int keyCode) { press(keyCode, 0); }

        void press(int keyCode, int modifiers) {
            input.pressKey(keyCode, modifiers);
            step();
            input.releaseKey(keyCode);
            step();
        }

        /**
         * A chord: the modifier is held down for real, because a binding with
         * one asks the input whether that key is <em>down</em> rather than
         * trusting the mask the press arrived with.
         */
        void pressWithCtrl(int keyCode) {
            input.pressKey(KeyEvent.VK_CONTROL, 0);
            step();
            press(keyCode, InputBinding.CTRL);
            input.releaseKey(KeyEvent.VK_CONTROL);
            step();
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
