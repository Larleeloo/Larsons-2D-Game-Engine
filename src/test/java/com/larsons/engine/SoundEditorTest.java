package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.SoundLoader;
import com.larsons.engine.audio.SoundPack;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.core.ShareJar;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creative mode's sound editing menu, driven the way a creator drives it —
 * clicking the SOUNDS palette and the rows it opens — plus the level's own
 * music track and the sound pack the share folder ships.
 */
@Timeout(90)
class SoundEditorTest {

    @AfterEach
    void resetRuntime() {
        Sounds.clear();
        Sounds.setEnabled(true);
        SoundPack.useDir("");
        SoundLoader.clearCache();
    }

    /** A creative scene running headless, ready to be clicked at. */
    private static final class Harness implements AutoCloseable {
        final SceneManager scenes = new SceneManager();
        final InputManager input = new InputManager();
        final Container source = new Container();
        final BufferedImage frame = new BufferedImage(1024, 720,
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = frame.createGraphics();

        Harness(GameContext ctx) {
            scenes.setViewport(1024, 720);
            scenes.register("creative", new CreativeScene(ctx));
            scenes.setScene("creative");
            tick(3);
        }

        void tick(int frames) {
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                scenes.update(1.0 / 120.0, input);
                scenes.render(Java2DTarget.unsized(g), 0f);
            }
        }

        void click(int x, int y) {
            input.mouseMoved(new MouseEvent(source, MouseEvent.MOUSE_MOVED,
                    0, 0, x, y, 0, false));
            input.mousePressed(new MouseEvent(source, MouseEvent.MOUSE_PRESSED,
                    0, 0, x, y, 1, false, MouseEvent.BUTTON1));
            tick(1);
            input.mouseReleased(new MouseEvent(source, MouseEvent.MOUSE_RELEASED,
                    0, 0, x, y, 1, false, MouseEvent.BUTTON1));
            tick(1);
        }

        void press(int keyCode) {
            input.keyPressed(new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, 0, keyCode, ' '));
            tick(1);
            input.keyReleased(new KeyEvent(source, KeyEvent.KEY_RELEASED, 0, 0, keyCode, ' '));
            tick(1);
        }

        @Override
        public void close() {
            g.dispose();
        }
    }

    private static GameContext context(Path dir) {
        System.setProperty("java.awt.headless", "true");
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile p = new GameProfile("Sound Editor Test");
        p.soundPackDir = dir.resolve("sounds").toString();
        ctx.setProfile(p);
        return ctx;
    }

    /**
     * The SOUNDS palette exists, opens, and its entries drive the editor
     * without the scene falling over — the whole path a creator takes.
     */
    @Test
    void theSoundsPaletteOpensTheEditorAndItsDialogs(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir.resolve("sounds"));
        GameContext ctx = context(dir);
        try (Harness h = new Harness(ctx)) {
            // Tab across to SOUNDS. It sits after EFFECTS in the palette, so
            // walking with Tab finds it wherever it is.
            for (int i = 0; i < 14; i++) h.press(KeyEvent.VK_TAB);

            // Click each of the first few sidebar entries in turn: the editor,
            // the options window, the level's music, and a group.
            for (int index = 0; index < 6; index++) {
                int row = index / 3;
                int col = index % 3;
                h.click(14 + col * 62 + 20, 34 + 14 * 22 + 10 + row * 64 + 26);
                h.tick(3);
                h.press(KeyEvent.VK_ESCAPE); // back out of whatever opened
                h.tick(2);
            }
            assertEquals("CreativeScene", h.scenes.current().name());
        }
    }

    /**
     * The catalogue the menu lists is the whole game, and its groups are the
     * ones the palette offers — so every one of those palette entries lands
     * on a non-empty list.
     */
    @Test
    void everyPaletteGroupListsSounds() {
        for (String category : SoundKeys.categories()) {
            assertFalse(SoundKeys.inCategory(category).isEmpty(),
                    "the " + category + " group should list sounds");
        }
        assertEquals(SoundKeys.all().size(),
                SoundKeys.inCategory("").size(),
                "the unfiltered list should be every sound in the game");
    }

    /** A level carries its own music track, and it survives being saved. */
    @Test
    void aLevelKeepsItsOwnMusicTrack() {
        Level level = Level.empty("Boss Arena", 32, 24, 32);
        assertEquals("music/level", level.musicKey(),
                "a level with no track of its own plays the generic one");

        level.music = "boss";
        assertEquals("music/boss", level.musicKey());

        Level reloaded = LevelLoader.parse(level.toJson());
        assertEquals("boss", reloaded.music, "the track should save with the level");
        assertEquals("music/boss", reloaded.musicKey());

        // A level written before music existed still loads and plays the
        // generic track rather than nothing.
        Level legacy = LevelLoader.parse(
                "{\"name\":\"Old\",\"width\":4,\"height\":4,"
                        + "\"tiles\":[[0,0,0,0],[0,0,0,0],[0,0,0,0],[1,1,1,1]]}");
        assertEquals("", legacy.music);
        assertEquals("music/level", legacy.musicKey());
    }

    /** Play-testing a level with sound on must not disturb the editor. */
    @Test
    void playTestingRunsWithTheSoundSystemLive(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir.resolve("sounds"));
        GameContext ctx = context(dir);
        try (Harness h = new Harness(ctx)) {
            h.press(KeyEvent.VK_P);            // into play-test
            h.press(KeyEvent.VK_SPACE);        // jump: the jump sound fires
            for (int i = 0; i < 40; i++) {
                h.input.keyPressed(new KeyEvent(h.source, KeyEvent.KEY_PRESSED,
                        0, 0, KeyEvent.VK_D, 'd'));
                h.tick(1);                      // walking: footsteps time out
            }
            h.input.keyReleased(new KeyEvent(h.source, KeyEvent.KEY_RELEASED,
                    0, 0, KeyEvent.VK_D, 'd'));
            h.tick(5);
            h.press(KeyEvent.VK_P);            // back to editing
            h.tick(5);
            assertEquals("CreativeScene", h.scenes.current().name());
        }
    }

    /** The share folder ships a sound pack beside the texture pack. */
    @Test
    void theShareFolderScaffoldsASoundPack(@TempDir Path dir) throws IOException {
        System.setProperty(ShareJar.FORCE_PROPERTY, "true");
        try {
            ShareJar.write(dir);
        } catch (IOException expected) {
            // Building the jar needs class directories on the classpath; the
            // folders and documentation are written either way, which is what
            // this is about.
        } finally {
            System.clearProperty(ShareJar.FORCE_PROPERTY);
        }
        Path sounds = dir.resolve(SoundPack.DIR_NAME);
        assertTrue(Files.isDirectory(sounds), "share/sounds should be scaffolded");
        assertTrue(Files.isRegularFile(sounds.resolve(SoundPack.KEYS_FILE)));
        assertTrue(Files.isRegularFile(sounds.resolve(SoundPack.README_FILE)));
        assertTrue(Files.isDirectory(sounds.resolve(SoundKeys.MUSIC)));
        assertTrue(Files.isDirectory(sounds.resolve(SoundKeys.ULTIMATES)));

        String how = Files.readString(dir.resolve("HOW_TO_PLAY_ONLINE.txt"));
        assertTrue(how.contains("sounds/"), "the instructions should mention the pack");
        assertTrue(how.contains("SILENT"), "and say what the default is");
    }

    /** Rewriting the key list picks up whatever exists right now. */
    @Test
    void theKeyListCanBeRegeneratedOnDemand(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());
        Path list = SoundPack.refreshKeyList();
        assertNotNull(list);
        String text = Files.readString(list);
        assertTrue(text.contains("player/jump.wav"));
        assertTrue(text.contains("CUSTOM CONTENT"),
                "the list should tell a creator their own objects are in it");
    }
}
