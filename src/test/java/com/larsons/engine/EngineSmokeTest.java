package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.util.Json;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.PlayScene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless smoke tests. These exercise the core subsystems without opening a
 * window (no game loop, no BufferStrategy) so they run in CI / on a server:
 * JSON parsing, level loading, sprite-sheet slicing, and rendering the demo
 * scenes into an off-screen image.
 */
class EngineSmokeTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void jsonParsesObjectsArraysAndScalars() {
        Object root = Json.parse("{ \"a\": 1, \"b\": [true, null, \"x\"], \"c\": {\"d\": 2.5} }");
        Map<String, Object> obj = Json.asObject(root);
        assertEquals(1.0, (Double) obj.get("a"));
        List<Object> arr = Json.asArray(obj.get("b"));
        assertEquals(3, arr.size());
        assertEquals(Boolean.TRUE, arr.get(0));
        assertEquals("x", arr.get(2));
        assertEquals(2.5, (Double) Json.asObject(obj.get("c")).get("d"));
    }

    @Test
    void levelLoaderReadsBundledSampleLevel() {
        Level level = LevelLoader.load("levels/sample_level.json");
        assertEquals("Sample Level", level.name);
        assertEquals(24, level.width);
        assertEquals(14, level.height);
        assertEquals(32, level.tileSize);
        // Bottom row is solid dirt; top-left is empty sky.
        assertTrue(level.tileAt(0, level.height - 1) > 0);
        assertEquals(0, level.tileAt(0, 0));
    }

    @Test
    void spriteSheetSlicesIntoFrames() {
        BufferedImage sheet = new BufferedImage(32 * 4, 32, BufferedImage.TYPE_INT_ARGB);
        SpriteSheet sprites = SpriteSheet.fromImage(sheet, 32, 32);
        assertEquals(4, sprites.frameCount());
        assertNotNull(sprites.frame(0));
        assertNotNull(sprites.frame(10)); // wraps around
    }

    @Test
    void demoScenesUpdateAndRenderWithoutErrors() {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Test Type"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("menu", new MainMenuScene(ctx));
        scenes.register("play", new PlayScene(ctx, "levels/sample_level.json"));

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        scenes.setScene("menu");
        for (int i = 0; i < 5; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }

        scenes.setScene("play");
        for (int i = 0; i < 30; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        g.dispose();

        assertNotNull(scenes.current());
        assertEquals("PlayScene", scenes.current().name());
    }

    /**
     * Regression test for the input edge-detection bug: a press that arrives
     * between ticks (the normal case) must be reported as "just pressed" on the
     * next tick, exactly once, even while the key/button stays held.
     */
    @Test
    void justPressedFiresOnceForPressesBetweenTicks() {
        InputManager input = new InputManager();
        Component src = new Canvas();

        input.newFrame();
        assertFalse(input.isKeyJustPressed(KeyEvent.VK_ENTER));
        assertFalse(input.isMouseJustPressed());

        // Press occurs between ticks.
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ENTER, '\n'));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, 5, 5, 1, false));

        input.newFrame();
        assertTrue(input.isKeyJustPressed(KeyEvent.VK_ENTER));
        assertTrue(input.isMouseJustPressed());
        assertTrue(input.isKeyDown(KeyEvent.VK_ENTER));

        // Still held on the following tick: must NOT re-fire.
        input.newFrame();
        assertFalse(input.isKeyJustPressed(KeyEvent.VK_ENTER));
        assertFalse(input.isMouseJustPressed());
        assertTrue(input.isKeyDown(KeyEvent.VK_ENTER));
    }

    @Test
    void menuActivatesViaEnterKey() {
        InputManager input = new InputManager();
        Component src = new Canvas();
        boolean[] fired = {false, false};
        Menu menu = new Menu("Test")
                .add("First", () -> fired[0] = true)
                .add("Second", () -> fired[1] = true);

        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        menu.render(g, 640, 360); // computes item hit-boxes

        // Move selection down to the second item, then press Enter.
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
        input.newFrame();
        menu.update(1.0 / 120.0, input);
        assertEquals(1, menu.getSelectedIndex());

        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ENTER, '\n'));
        input.newFrame();
        menu.update(1.0 / 120.0, input);

        g.dispose();
        assertTrue(fired[1], "Enter should activate the selected item");
        assertFalse(fired[0]);
    }

    @Test
    void menuActivatesViaMouseClick() {
        InputManager input = new InputManager();
        Component src = new Canvas();
        boolean[] fired = {false};
        Menu menu = new Menu("Test").add("Click Me", () -> fired[0] = true);

        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        menu.render(g, 640, 360); // computes item hit-boxes

        MenuItem item = menu.items().get(0);
        int cx = item.x + item.width / 2;
        int cy = item.y + item.height / 2;

        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, cx, cy, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, cx, cy, 1, false));
        input.newFrame();
        menu.update(1.0 / 120.0, input);

        g.dispose();
        assertTrue(fired[0], "Clicking a menu item should activate it");
    }
}
