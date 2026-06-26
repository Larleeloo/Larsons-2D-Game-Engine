package com.larsons.engine;

import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.util.Json;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.PlayScene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        SceneManager scenes = new SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("menu", new MainMenuScene());
        scenes.register("play", new PlayScene("levels/sample_level.json"));

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        scenes.setScene("menu");
        for (int i = 0; i < 5; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(g, 0f);
        }

        scenes.setScene("play");
        for (int i = 0; i < 30; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(g, 0f);
        }
        g.dispose();

        assertNotNull(scenes.current());
        assertEquals("PlayScene", scenes.current().name());
    }
}
