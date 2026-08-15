package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.EditHistory;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.world.SurfaceDecor;
import org.junit.jupiter.api.Test;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creative mode's undo: the history itself ({@link EditHistory}), the snapshots
 * a level hands it, and Ctrl+Z / Ctrl+Y driven through the real editor from
 * real clicks and keystrokes.
 */
class CreativeUndoTest {

    // --- the history ----------------------------------------------------------------

    /** A tiny mutable box, standing in for whatever an edit is saving. */
    private static final class Box {
        int value;
    }

    private static EditHistory.Edit edit(Box box) {
        return EditHistory.field(() -> box.value, v -> box.value = v);
    }

    @Test
    void oneStepTakesBackAWholeActionInReverseOrder() {
        EditHistory history = new EditHistory();
        Box a = new Box(), b = new Box();
        history.begin("paint");
        history.add(edit(a));
        history.add(edit(b));
        a.value = 1;
        b.value = 2;
        assertTrue(history.commit(), "an action that changed something is remembered");
        assertEquals(1, history.undoDepth());
        assertEquals("paint", history.undoLabel());

        assertTrue(history.undo());
        assertEquals(0, a.value, "both halves of the action came back");
        assertEquals(0, b.value);
        assertFalse(history.canUndo());

        assertTrue(history.redo());
        assertEquals(1, a.value, "and both went forward again");
        assertEquals(2, b.value);
    }

    @Test
    void aStepThatChangedNothingIsNotRemembered() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("cancelled window");
        history.add(edit(box));
        assertFalse(history.commit(), "nothing changed, so there is nothing to undo");
        assertFalse(history.canUndo());
        assertEquals(0, history.undoDepth());
    }

    @Test
    void overlappingSavesOfOneCellUndoToTheStateBeforeTheStroke() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("stroke");
        history.add(edit(box));   // saved 0
        box.value = 1;
        history.add(edit(box));   // saved 1, halfway through the stroke
        box.value = 2;
        history.commit();
        history.undo();
        assertEquals(0, box.value, "the oldest save in the step is the one that wins");
    }

    @Test
    void nestedStepsAreOneAction() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("outer");
        history.begin("inner");   // e.g. an eraser stroke inside a paint stroke
        history.add(edit(box));
        box.value = 7;
        assertFalse(history.commit(), "the inner close does not push on its own");
        assertTrue(history.recording());
        assertTrue(history.commit());
        assertEquals(1, history.undoDepth());
        assertEquals("outer", history.undoLabel(), "the outermost name is the action's");
    }

    @Test
    void flushClosesAStepHoweverDeeplyItIsNested() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("stroke");
        history.begin("stroke");
        history.add(edit(box));
        box.value = 3;
        assertTrue(history.flush());
        assertFalse(history.recording());
        history.undo();
        assertEquals(0, box.value);
    }

    @Test
    void aNewActionThrowsAwayWhatRedoWouldHavePutBack() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("first");
        history.add(edit(box));
        box.value = 1;
        history.commit();
        history.undo();
        assertTrue(history.canRedo());

        history.begin("second");
        history.add(edit(box));
        box.value = 5;
        history.commit();
        assertFalse(history.canRedo(), "editing after an undo is a new branch");
        history.undo();
        assertEquals(0, box.value);
    }

    @Test
    void theHistoryIsBoundedAndForgetsTheOldestFirst() {
        EditHistory history = new EditHistory(3);
        Box box = new Box();
        for (int i = 1; i <= 5; i++) {
            history.begin("edit " + i);
            history.add(edit(box));
            box.value = i;
            history.commit();
        }
        assertEquals(3, history.undoDepth());
        for (int i = 0; i < 3; i++) history.undo();
        assertEquals(2, box.value, "only the last three actions were still remembered");
        assertFalse(history.canUndo());
    }

    @Test
    void applyingAnUndoCannotRecordOneOfItsOwn() {
        EditHistory history = new EditHistory();
        Box box = new Box();
        history.begin("edit");
        // An edit whose undo tries to record: restoring state must never look
        // like a fresh action, or Ctrl+Z would never reach the one before it.
        history.add(EditHistory.of(() -> {
            history.begin("recursive");
            history.add(edit(box));
            history.commit();
            box.value = 0;
        }, () -> box.value = 1));
        box.value = 1;
        history.commit();
        history.undo();
        assertEquals(0, box.value);
        assertFalse(history.canUndo(), "the undo did not push a step of its own");
        assertEquals(1, history.redoDepth());
    }

    // --- what a level hands the history ---------------------------------------------

    @Test
    void aCellSnapshotBringsBackTheStackItsDetailsAndItsContainer() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Cells", 16, 16, 32);
        int stone = lvl.blocks.get("stone").id();
        int dirt = lvl.blocks.get("dirt").id();
        lvl.setTile(4, 4, Level.LAYER_GROUND, dirt);
        lvl.setTile(4, 4, Level.LAYER_UPPER, stone);
        lvl.surfaceDecor.add(new SurfaceDecor.Placement(4, 4, SurfaceDecor.Face.UP,
                "moss", false, SurfaceDecor.Visibility.OPEN_FACE));
        lvl.openContainer(4, 4).add(new ItemStack("stone", 4));

        Level.CellState saved = lvl.captureCell(4, 4);
        // Clearing the floor takes the stack, the details and the container with
        // it — the whole reason cells are saved whole rather than as tile ids.
        lvl.setTile(4, 4, Level.LAYER_GROUND, 0);
        assertEquals(0, lvl.tileAt(4, 4, Level.LAYER_UPPER));
        assertTrue(lvl.surfaceDecor.isEmpty());
        assertNull(lvl.containerAt(4, 4));

        lvl.restoreCell(saved);
        assertEquals(dirt, lvl.tileAt(4, 4));
        assertEquals(stone, lvl.tileAt(4, 4, Level.LAYER_UPPER));
        assertEquals(1, lvl.surfaceDecor.size());
        assertEquals(SurfaceDecor.Face.UP, lvl.surfaceDecor.get(0).face());
        assertNotNull(lvl.containerAt(4, 4));
        assertEquals(1, lvl.containerAt(4, 4).size());

        // The snapshot stays reusable, so undo → redo → undo lands in one place.
        lvl.setTile(4, 4, Level.LAYER_GROUND, 0);
        lvl.restoreCell(saved);
        assertEquals(stone, lvl.tileAt(4, 4, Level.LAYER_UPPER));
        assertEquals(1, lvl.surfaceDecor.size());
    }

    @Test
    void aDocSnapshotBringsBackMarkersRulesCutscenesAndTheMiniGame() {
        Level lvl = Level.empty("Doc", 20, 20, 32);
        lvl.entities.add(new Level.EntitySpawn("mob", "slime", 64, 64));
        lvl.statRules.add(new StatRule("blocks_mined", 50, "diamond", 1, null, 0, false, true));
        Cutscene cs = new Cutscene("cs1", "Intro");
        cs.actors.add(new Cutscene.Actor("a1", "Hero", 48));
        cs.steps.add(new Cutscene.Step(Cutscene.Op.SAY, "a1", "hello", 0, 0, 1.5));
        cs.x = 100;
        lvl.cutscenes.add(cs);
        lvl.minigame = new MiniGameConfig();
        lvl.minigame.mode = MiniGameConfig.Mode.CTF;
        lvl.music = "boss";
        lvl.lightAngle = 90;
        lvl.characters.add("ranger");
        double spawnWas = lvl.spawnX;

        Level.Doc saved = lvl.snapshotDoc();

        // Change every part of it, the way the editor's windows do — including
        // reaching inside a cutscene, which is edited in place.
        lvl.entities.clear();
        lvl.statRules.clear();
        lvl.cutscenes.get(0).name = "Renamed";
        lvl.cutscenes.get(0).steps.clear();
        lvl.cutscenes.add(new Cutscene("cs2", "Second"));
        lvl.minigame.mode = MiniGameConfig.Mode.BATTLE;
        lvl.music = "";
        lvl.lightAngle = 0;
        lvl.characters.clear();
        lvl.spawnX = 999;

        lvl.restoreDoc(saved);
        assertEquals(1, lvl.entities.size());
        assertEquals("slime", lvl.entities.get(0).type);
        assertEquals(1, lvl.statRules.size());
        assertEquals(1, lvl.cutscenes.size(), "the cutscene added afterwards is gone");
        assertEquals("Intro", lvl.cutscenes.get(0).name);
        assertEquals(1, lvl.cutscenes.get(0).steps.size(), "its script came back too");
        assertEquals(1, lvl.cutscenes.get(0).actors.size());
        assertEquals(100, lvl.cutscenes.get(0).x, 1e-9);
        assertEquals(MiniGameConfig.Mode.CTF, lvl.minigame.mode);
        assertEquals("boss", lvl.music);
        assertEquals(90, lvl.lightAngle, 1e-9);
        assertEquals(List.of("ranger"), lvl.characters);
        assertEquals(spawnWas, lvl.spawnX, 1e-9, "and the spawn it was moved off");
    }

    @Test
    void twoDocSnapshotsOfAnUntouchedLevelAreEqualSoAWindowVisitCostsNothing() {
        Level lvl = Level.empty("Doc", 20, 20, 32);
        lvl.cutscenes.add(new Cutscene("cs1", "Intro"));
        lvl.statRules.add(new StatRule("blocks_mined", 10, null, 0, null, 0, false, true));
        lvl.minigame = new MiniGameConfig();
        assertEquals(lvl.snapshotDoc(), lvl.snapshotDoc(),
                "two reads of an unchanged level must look the same to the history");

        EditHistory history = new EditHistory();
        history.begin("window session");
        history.add(EditHistory.field(lvl::snapshotDoc, lvl::restoreDoc));
        assertFalse(history.commit(), "a window opened and closed is not an edit");

        history.begin("window session");
        history.add(EditHistory.field(lvl::snapshotDoc, lvl::restoreDoc));
        lvl.cutscenes.get(0).name = "Changed";
        assertTrue(history.commit(), "and one that changed something is");
        history.undo();
        assertEquals("Intro", lvl.cutscenes.get(0).name);
    }

    @Test
    void aBoundsSnapshotUndoesAResizeThatDroppedContent() {
        Level lvl = Level.empty("Resize", 40, 20, 32);
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(35, 15, stone);
        lvl.entities.add(new Level.EntitySpawn("mob", "slime", 35 * 32, 15 * 32));
        lvl.surfaceDecor.add(new SurfaceDecor.Placement(35, 15, SurfaceDecor.Face.UP,
                "moss", false, SurfaceDecor.Visibility.ALWAYS));

        Level.Bounds saved = lvl.snapshotBounds();
        lvl.resize(20, 10);
        assertEquals(0, lvl.tileAt(35, 15), "shrinking cut the far corner away");
        assertTrue(lvl.entities.isEmpty(), "and dropped what stood in it");
        assertTrue(lvl.surfaceDecor.isEmpty());

        lvl.restoreBounds(saved);
        assertEquals(40, lvl.width);
        assertEquals(20, lvl.height);
        assertEquals(stone, lvl.tileAt(35, 15));
        assertEquals(1, lvl.entities.size());
        assertEquals(1, lvl.surfaceDecor.size());
    }

    @Test
    void aBoundsSnapshotBringsTheDenseGridBackAfterAGiantResize() {
        Level lvl = Level.empty("Grow", 64, 64, 32);
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(10, 10, stone);
        assertFalse(lvl.isChunked());

        Level.Bounds saved = lvl.snapshotBounds();
        // Growing past the dense limit converts the level to chunked storage,
        // and resizing back down does not convert it back on its own.
        lvl.resize(2048, 2048);
        assertTrue(lvl.isChunked());

        lvl.restoreBounds(saved);
        assertFalse(lvl.isChunked(), "undo put the dense grid back, not just the bounds");
        assertEquals(64, lvl.width);
        assertEquals(stone, lvl.tileAt(10, 10));
    }

    @Test
    void undoingALevelSwapHandsBackTheLevelThatWasOpen() {
        // New/Load/Generate are undoable without copying anything: the step
        // holds the level object that was open, and hands that back.
        Level first = Level.empty("First", 20, 20, 32);
        Level second = Level.empty("Second", 30, 30, 32);
        EditHistory history = new EditHistory();
        Level[] open = {first};
        history.begin("load level");
        history.add(EditHistory.field(() -> open[0], lvl -> open[0] = lvl));
        open[0] = second;
        assertTrue(history.commit());
        history.undo();
        assertSame(first, open[0]);
        history.redo();
        assertSame(second, open[0]);
    }

    // --- the editor, driven by real clicks and keystrokes ---------------------------

    /** The palette's second swatch (index 1) — its first real object. */
    private static final int SWATCH_X = 70, SWATCH_Y = 378;
    /** A point on the canvas, right of the sidebar. */
    private static final int CANVAS_X = 420, CANVAS_Y = 300;

    private static CreativeScene openEditor(SceneManager scenes, String gameType) {
        System.setProperty("java.awt.headless", "true");
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile(gameType));
        CreativeScene scene = new CreativeScene(ctx);
        scenes.setViewport(800, 600);
        scenes.register("creative", scene);
        scenes.setScene("creative");
        return scene;
    }

    private static void tick(SceneManager scenes, InputManager input, Graphics2D g, int frames) {
        for (int i = 0; i < frames; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
    }

    private static void key(InputManager input, Container src, int code) {
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0, 0, code, '\0'));
    }

    private static void keyUp(InputManager input, Container src, int code) {
        input.keyReleased(new KeyEvent(src, KeyEvent.KEY_RELEASED, 0, 0, code, '\0'));
    }

    private static void click(InputManager input, Container src, int x, int y, int button) {
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1,
                false, button));
    }

    private static void unclick(InputManager input, Container src, int x, int y, int button) {
        input.mouseReleased(new MouseEvent(src, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1,
                false, button));
    }

    /** Press Ctrl+Z (or Ctrl+Y) for exactly one tick, the way a creator does. */
    private static void chord(SceneManager scenes, InputManager input, Container src,
                              Graphics2D g, int code) {
        key(input, src, KeyEvent.VK_CONTROL);
        key(input, src, code);
        tick(scenes, input, g, 1);
        keyUp(input, src, code);
        keyUp(input, src, KeyEvent.VK_CONTROL);
    }

    /** The whole ground layer, for before/after comparisons. */
    private static List<Integer> groundOf(Level lvl) {
        List<Integer> cells = new ArrayList<>(lvl.width * lvl.height);
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) cells.add(lvl.tileAt(c, r));
        }
        return cells;
    }

    private static int solidCells(Level lvl) {
        int n = 0;
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                if (lvl.tileAt(c, r) != 0) n++;
            }
        }
        return n;
    }

    @Test
    void ctrlZTakesBackAWholeBrushStrokeAndCtrlYPutsItBack() {
        SceneManager scenes = new SceneManager();
        CreativeScene scene = openEditor(scenes, "Undo Test");
        InputManager input = new InputManager();
        Container src = new Container();
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).createGraphics();
        tick(scenes, input, g, 2);

        Level lvl = scene.editing();
        List<Integer> before = groundOf(lvl);

        // Arm a real block (the palette's first entry is "+ New Block").
        click(input, src, SWATCH_X, SWATCH_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        unclick(input, src, SWATCH_X, SWATCH_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        assertFalse(scene.history().canUndo(), "arming a brush is not an edit");

        // One drag across several cells.
        click(input, src, CANVAS_X, CANVAS_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        for (int x = CANVAS_X + 16; x <= CANVAS_X + 96; x += 16) {
            input.mouseDragged(new MouseEvent(src, MouseEvent.MOUSE_DRAGGED, 0, 0, x, CANVAS_Y,
                    1, false, MouseEvent.NOBUTTON));
            tick(scenes, input, g, 1);
        }
        unclick(input, src, CANVAS_X + 96, CANVAS_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);

        List<Integer> painted = groundOf(lvl);
        assertNotEquals(before, painted, "the stroke painted something");
        assertEquals(1, scene.history().undoDepth(), "the whole drag is a single step");

        chord(scenes, input, src, g, KeyEvent.VK_Z);
        assertEquals(before, groundOf(lvl), "Ctrl+Z took the whole stroke back at once");
        assertFalse(scene.history().canUndo());
        assertTrue(scene.history().canRedo());

        chord(scenes, input, src, g, KeyEvent.VK_Y);
        assertEquals(painted, groundOf(lvl), "Ctrl+Y put it back");
        g.dispose();
    }

    @Test
    void ctrlZTakesBackAnErasedBlockAndTheDetailThatHungOffIt() {
        SceneManager scenes = new SceneManager();
        CreativeScene scene = openEditor(scenes, "Undo Test");
        InputManager input = new InputManager();
        Container src = new Container();
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).createGraphics();
        tick(scenes, input, g, 2);

        Level lvl = scene.editing();
        // Ground everywhere with a detail on every cell, so a right-click lands
        // on something wherever the camera happens to be looking.
        lvl.fillFloor(lvl.blocks.get("stone").id());
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                lvl.surfaceDecor.add(new SurfaceDecor.Placement(c, r, SurfaceDecor.Face.UP,
                        "moss", false, SurfaceDecor.Visibility.ALWAYS));
            }
        }
        int cells = solidCells(lvl), details = lvl.surfaceDecor.size();

        // The eraser takes one layer off per click: the detail, then the block.
        for (int i = 0; i < 2; i++) {
            click(input, src, CANVAS_X, CANVAS_Y, MouseEvent.BUTTON3);
            tick(scenes, input, g, 1);
            unclick(input, src, CANVAS_X, CANVAS_Y, MouseEvent.BUTTON3);
            tick(scenes, input, g, 1);
        }
        assertEquals(details - 1, lvl.surfaceDecor.size(), "one detail erased");
        assertEquals(cells - 1, solidCells(lvl), "then the block under it");
        assertEquals(2, scene.history().undoDepth(), "two strokes, two steps");

        chord(scenes, input, src, g, KeyEvent.VK_Z);
        assertEquals(cells, solidCells(lvl), "the block came back");

        chord(scenes, input, src, g, KeyEvent.VK_Z);
        assertEquals(details, lvl.surfaceDecor.size(), "and so did the detail on it");
        assertFalse(scene.history().canUndo());
        g.dispose();
    }

    @Test
    void ctrlZUnplacesAPaintedMarker() {
        SceneManager scenes = new SceneManager();
        CreativeScene scene = openEditor(scenes, "Undo Test");
        InputManager input = new InputManager();
        Container src = new Container();
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).createGraphics();
        tick(scenes, input, g, 2);

        Level lvl = scene.editing();
        int markers = lvl.entities.size();

        // Tab walks the palette categories: BLOCKS → LIQUIDS → LIGHTS → MOBS.
        for (int i = 0; i < 3; i++) {
            key(input, src, KeyEvent.VK_TAB);
            tick(scenes, input, g, 1);
            keyUp(input, src, KeyEvent.VK_TAB);
        }
        click(input, src, SWATCH_X, SWATCH_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        unclick(input, src, SWATCH_X, SWATCH_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);

        click(input, src, CANVAS_X, CANVAS_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        unclick(input, src, CANVAS_X, CANVAS_Y, MouseEvent.BUTTON1);
        tick(scenes, input, g, 1);
        assertEquals(markers + 1, lvl.entities.size(), "a mob was painted in");
        assertEquals("mob", lvl.entities.get(lvl.entities.size() - 1).kind);

        chord(scenes, input, src, g, KeyEvent.VK_Z);
        assertEquals(markers, lvl.entities.size(), "Ctrl+Z unplaced it");

        chord(scenes, input, src, g, KeyEvent.VK_Y);
        assertEquals(markers + 1, lvl.entities.size(), "Ctrl+Y placed it again");
        g.dispose();
    }

    @Test
    void ctrlZWithNothingToUndoLeavesTheLevelAlone() {
        SceneManager scenes = new SceneManager();
        CreativeScene scene = openEditor(scenes, "Undo Test");
        InputManager input = new InputManager();
        Container src = new Container();
        Graphics2D g = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).createGraphics();
        tick(scenes, input, g, 2);

        Level lvl = scene.editing();
        List<Integer> before = groundOf(lvl);
        assertFalse(scene.history().canUndo(), "a fresh session has nothing to take back");
        for (int i = 0; i < 3; i++) chord(scenes, input, src, g, KeyEvent.VK_Z);
        chord(scenes, input, src, g, KeyEvent.VK_Y);
        assertEquals(before, groundOf(lvl));
        assertEquals("CreativeScene", scenes.current().name());
        g.dispose();
    }
}
