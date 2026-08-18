package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * <b>Load Level → Edit in Creative</b>: a saved level can be opened in the
 * editor from the screen that lists it, in the format it was built in, without
 * playing it and pausing first.
 */
class LevelSelectSceneTest {

    private static final int W = 800, H = 600;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A game type with one saved level, and a context pointed at it. */
    private record Fixture(GameContext ctx, LevelStore store, Level saved) {}

    private static Fixture fixture(Path dir, String typeName, LevelFormat format,
                                   boolean creativeEnabled) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile profile = new GameProfile(typeName);
        ctx.setProfile(profile);

        LevelStore store = new LevelStore(dir.resolve("levels").toString(), typeName);
        Level level = format.starterLevel("Old Town", 32, 24, 32);
        GameProfile settings = profile.copy();
        settings.creativeEnabled = creativeEnabled;
        settings.mobsEnabled = false; // a toggle only this level has
        level.captureSettings(settings);
        store.save(level);
        return new Fixture(ctx, store, level);
    }

    private static SceneManager scenes(Fixture f, Path dir, CreativeScene creative) {
        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        scenes.register("levelselect",
                new LevelSelectScene(f.ctx(), dir.resolve("levels").toString()));
        scenes.register("creative", creative);
        scenes.setScene("levelselect");
        return scenes;
    }

    private static void run(SceneManager scenes, InputManager input, int frames) {
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        for (int i = 0; i < frames; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        g.dispose();
    }

    private static void press(InputManager input, Component src, int keyCode) {
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, (char) 0));
        input.keyReleased(new KeyEvent(src, KeyEvent.KEY_RELEASED, 0L, 0, keyCode, (char) 0));
    }

    /**
     * Pick the level, choose <i>Edit in Creative</i>, and the editor opens on
     * that level — in its own format, with its own settings in force, and
     * pointed at by the game type so the editor returns to it.
     */
    @Test
    void aSavedLevelCanBeOpenedInTheEditorFromTheList(@TempDir Path dir) {
        Fixture f = fixture(dir, "Load Test", LevelFormat.THREE_D, true);
        CreativeScene creative = new CreativeScene(f.ctx());
        SceneManager scenes = scenes(f, dir, creative);

        InputManager input = new InputManager();
        Component src = new Canvas();
        run(scenes, input, 2);

        press(input, src, KeyEvent.VK_ENTER);  // the one level in the list
        run(scenes, input, 2);
        press(input, src, KeyEvent.VK_DOWN);   // Play Level → Edit in Creative
        run(scenes, input, 1);
        press(input, src, KeyEvent.VK_ENTER);
        run(scenes, input, 120);               // let the scene transition finish

        assertEquals("CreativeScene", scenes.current().name(),
                "Edit in Creative opens the editor");
        assertEquals("Old Town", creative.editing().name, "on the level that was picked");
        assertEquals(LevelFormat.THREE_D, creative.editing().format(),
                "in the format the level was built in");
        assertEquals(f.store().fileFor("Old Town").toString(), f.ctx().profile().lastLevelPath,
                "and the editor now returns to it");
        assertFalse(f.ctx().profile().mobsEnabled, "with the level's own settings in force");
    }

    /**
     * A level that says creative mode is off is not built in from here either —
     * the same answer the pause menu gives, rather than a second opinion.
     */
    @Test
    void aLevelWithCreativeTurnedOffIsNotOpenedInTheEditor(@TempDir Path dir) {
        Fixture f = fixture(dir, "No Build", LevelFormat.SIDE_SCROLLER, false);
        CreativeScene creative = new CreativeScene(f.ctx());
        SceneManager scenes = scenes(f, dir, creative);

        InputManager input = new InputManager();
        Component src = new Canvas();
        run(scenes, input, 2);

        press(input, src, KeyEvent.VK_ENTER);  // the one level in the list
        run(scenes, input, 2);
        // The same keys that opened the editor in the test above, on a menu
        // built the same way: the game type's own creative toggle is on, so the
        // row is offered. The level's is off, so choosing it says so instead.
        press(input, src, KeyEvent.VK_DOWN);
        run(scenes, input, 1);
        press(input, src, KeyEvent.VK_ENTER);
        run(scenes, input, 60);

        assertEquals("LevelSelectScene", scenes.current().name(), "nothing opened");
        assertNull(f.ctx().takePendingCreativeLevel(), "and no level was handed over");
    }

    /** What the hand-over does, on its own: current level, settings, object. */
    @Test
    void handingALevelToTheEditorPointsTheGameTypeAtIt(@TempDir Path dir) {
        Fixture f = fixture(dir, "Handover", LevelFormat.THREE_D, true);
        Level loaded = f.store().load("Old Town");
        Path file = f.store().fileFor("Old Town");

        LevelSelectScene.handToCreative(f.ctx(), file, loaded);

        assertEquals(file.toString(), f.ctx().profile().lastLevelPath);
        assertEquals(Perspective.THREE_D, f.ctx().profile().perspective,
                "the level's own format is what is in force");
        assertFalse(f.ctx().profile().mobsEnabled, "and its own toggles with it");
        assertSame(loaded, f.ctx().takePendingCreativeLevel(),
                "the editor is handed the object that was loaded, not a path to re-read");
    }
}
