package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-step creative-mode entry: the main menu's format picker leads to the
 * <b>New Level</b> settings screen, and the level is created from there — named,
 * sized and carrying the settings that were asked for — rather than the instant
 * a format is picked.
 */
class NewLevelSceneTest {

    private static final int W = 800, H = 600;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameContext context(Path dir, String typeName) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(new GameProfile(typeName));
        return ctx;
    }

    /** Run the scene manager for a few frames, so transitions actually land. */
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

    // --- the picker leads to the settings screen ------------------------------------

    /**
     * Choosing a level format no longer drops the creator into the editor: it
     * opens the settings screen for the level about to be built.
     */
    @Test
    void pickingALevelFormatOpensTheSettingsScreen(@TempDir Path dir) {
        GameContext ctx = context(dir, "Picker Test");

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        scenes.register("menu", new MainMenuScene(ctx));
        scenes.register(NewLevelScene.NAME, new NewLevelScene(ctx));
        scenes.register("creative", new CreativeScene(ctx));
        scenes.setScene("menu");

        InputManager input = new InputManager();
        Component src = new Canvas();
        run(scenes, input, 2);

        // Main menu: Play Level, Load Level, Creative Mode … — down twice, select.
        press(input, src, KeyEvent.VK_DOWN);
        run(scenes, input, 1);
        press(input, src, KeyEvent.VK_DOWN);
        run(scenes, input, 1);
        press(input, src, KeyEvent.VK_ENTER);
        run(scenes, input, 2);

        // The picker: its first entry is the first LevelFormat.
        press(input, src, KeyEvent.VK_ENTER);
        run(scenes, input, 120); // let the scene transition finish

        assertEquals("NewLevelScene", scenes.current().name(),
                "picking a format asks about the level before building it");
        assertNull(ctx.takePendingCreativeLevel(), "nothing is created yet");
        assertEquals("", ctx.profile().lastLevelPath,
                "no level is created by opening the settings screen");
    }

    /** The settings screen builds and renders with a format asked for. */
    @Test
    void theSettingsScreenRendersForTheChosenFormat(@TempDir Path dir) {
        GameContext ctx = context(dir, "Settings Test");
        ctx.setCreativeFormat(LevelFormat.ISOMETRIC);

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        scenes.register(NewLevelScene.NAME, new NewLevelScene(ctx));
        scenes.setScene(NewLevelScene.NAME);

        run(scenes, new InputManager(), 10);

        assertEquals("NewLevelScene", scenes.current().name());
        assertNull(ctx.takeCreativeFormat(),
                "the settings screen consumes the format the picker asked for");
    }

    // --- creating the level ---------------------------------------------------------

    /**
     * What "Create Level" does: the chosen format's starter canvas, at the
     * chosen size, under the chosen name, carrying its own copy of the settings
     * the screen was showing — saved into the game type so the level exists.
     */
    @Test
    void creatingALevelUsesEverythingTheScreenAsked(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "My Game");
        GameProfile settings = new GameProfile("My Game");
        settings.perspective = Perspective.SIDE_SCROLL; // the game type's default
        settings.mobsEnabled = false;                   // a choice made on the screen
        settings.tileSize = 48;

        Level level = NewLevelScene.create(store, "Market Square", LevelFormat.ISOMETRIC,
                40, 30, settings, List.of("knight"));

        assertEquals("Market Square", level.name);
        assertEquals(LevelFormat.ISOMETRIC, level.format());
        assertEquals(40, level.width);
        assertEquals(30, level.height);
        assertEquals(48, level.tileSize);
        assertEquals(List.of("knight"), level.characters, "the roster it was given");
        assertFalse(level.settings.mobsEnabled, "the level carries the toggles it was created with");
        assertEquals(Perspective.ISOMETRIC, level.settings.perspective,
                "the saved settings can never contradict the level's own format");

        // Created means created: the level is on disk, listed, and reloads.
        assertTrue(Files.exists(store.fileFor("Market Square")));
        assertEquals(List.of("market_square"), store.list(),
                "listed under the file name the store gave it");
        assertEquals(LevelFormat.ISOMETRIC, store.formatOf("Market Square"));
        Level reloaded = LevelLoader.load(store.fileFor("Market Square").toString());
        assertEquals("Market Square", reloaded.name);
        assertFalse(reloaded.settings.mobsEnabled);
    }

    /**
     * The name the screen offers is always free, so creating a level never
     * lands on top of one that is already there.
     */
    @Test
    void theSuggestedNameStepsAsideForLevelsThatAlreadyExist(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "Busy Game");
        GameProfile settings = new GameProfile("Busy Game");

        String first = NewLevelScene.suggestedName(store, LevelFormat.TOP_DOWN);
        assertEquals("New Top-Down Level", first);

        NewLevelScene.create(store, first, LevelFormat.TOP_DOWN, 20, 20, settings, List.of());
        String second = NewLevelScene.suggestedName(store, LevelFormat.TOP_DOWN);
        assertNotEquals(first, second, "the taken name is not offered again");
        assertEquals("New Top-Down Level 2", second);

        NewLevelScene.create(store, second, LevelFormat.TOP_DOWN, 20, 20, settings, List.of());
        assertEquals(2, store.list().size(), "neither level overwrote the other");
    }

    // --- the editor opens what was created -------------------------------------------

    /**
     * The editor opens the level the settings screen created — the object
     * itself, not a fresh canvas and not the game type's last level.
     */
    @Test
    void theEditorOpensTheLevelTheScreenCreated(@TempDir Path dir) {
        GameContext ctx = context(dir, "Handover Test");
        LevelStore store = new LevelStore(dir.toString(), "Handover Test");
        Level created = NewLevelScene.create(store, "Sky Bridge", LevelFormat.TOP_DOWN,
                32, 24, ctx.profile().copy(), List.of());
        ctx.setPendingCreativeLevel(created);

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        CreativeScene creative = new CreativeScene(ctx);
        scenes.register("creative", creative);
        scenes.setScene("creative");
        run(scenes, new InputManager(), 5);

        assertSame(created, creative.editing(), "the editor edits the level that was created");
        assertEquals(LevelFormat.TOP_DOWN, creative.editing().format());
        assertEquals(Perspective.TOP_DOWN, ctx.profile().perspective,
                "the new level's settings are the ones in force");
        assertNull(ctx.takePendingCreativeLevel(), "the handover is spent");
    }

    /** The handover slot is read once, like the format request beside it. */
    @Test
    void thePendingLevelIsHandedOverOnlyOnce(@TempDir Path dir) {
        GameContext ctx = context(dir, "Slot Test");
        assertNull(ctx.takePendingCreativeLevel(), "nothing pending by default");

        Level level = LevelFormat.SIDE_SCROLLER.starterLevel("One", 20, 12, 32);
        ctx.setPendingCreativeLevel(level);
        assertSame(level, ctx.takePendingCreativeLevel());
        assertNull(ctx.takePendingCreativeLevel(), "the handover is spent");
    }
}
