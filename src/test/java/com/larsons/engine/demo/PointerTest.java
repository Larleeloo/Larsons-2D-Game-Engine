package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.Pointer;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mouse pointer over a first-person view: hidden while it is steering, and
 * back the moment it is a pointer again.
 *
 * <p><b>Why this is a test and not an eyeball.</b> The engine draws through two
 * windows — an AWT canvas and a GLFW one — and the pointer is a property of a
 * window, so the view can only ask ({@link Pointer}) and the window has to
 * answer. One of the two never registered an answer at all, which is not a
 * thing a screenshot of the other shows: a player on the GL backend steered a
 * first-person view with the desktop's arrow sliding across the world, and
 * every test passed. So what is pinned here is the <em>asking</em>, through a
 * handler that records it, which is the half the engine owns.
 */
class PointerTest {

    private static final int W = 960, H = 600;
    private static final String TYPE = "pointer type";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A window, as far as the engine can tell. */
    private static final class Fake implements Pointer.Handler {
        boolean visible = true;
        boolean warpable;
        boolean holds;
        int warps;

        @Override
        public void setPointerVisible(boolean show) { visible = show; }

        @Override
        public boolean canWarpPointer() { return warpable; }

        @Override
        public boolean warpPointer(int x, int y) {
            warps++;
            return warpable;
        }

        @Override
        public boolean holdsPointer() { return holds; }
    }

    private final List<PlayScene> opened = new ArrayList<>();

    @AfterEach
    void tidy() {
        for (PlayScene scene : opened) scene.onExit();
        opened.clear();
        Pointer.install(null);
    }

    /**
     * The pointer goes when the view starts steering with it and comes back
     * when the game is paused — which is the whole of what a player asked for,
     * and both halves have to hold or the other is a bug.
     */
    @Test
    void aFirstPersonViewTakesThePointerAndPausingGivesItBack(@TempDir Path dir) {
        Fake window = new Fake();
        Pointer.install(window);
        PlayScene play = enter(dir);
        InputManager input = new InputManager();

        tick(play, input);
        assertTrue(window.visible, "a plan view aims with the pointer, so it has one");

        press(play, input, KeyEvent.VK_F5);     // …into first person
        tick(play, input);
        assertFalse(window.visible, "a view that steers with the mouse hides the arrow");

        press(play, input, KeyEvent.VK_ESCAPE); // …paused
        tick(play, input);
        assertTrue(window.visible, "a paused game is a menu, and a menu is clicked");

        press(play, input, KeyEvent.VK_ESCAPE); // …and back into the game
        tick(play, input);
        assertFalse(window.visible, "still first person, so the pointer goes again");
    }

    /** Cycling back to the plan view is the other way the pointer comes back. */
    @Test
    void thePlanViewGetsThePointerBack(@TempDir Path dir) {
        Fake window = new Fake();
        Pointer.install(window);
        PlayScene play = enter(dir);
        InputManager input = new InputManager();

        // [F5] cycles: plan, first person, third behind, third in front, plan.
        for (int i = 0; i < 3; i++) {
            press(play, input, KeyEvent.VK_F5);
            assertFalse(window.visible, "still a solid view after " + (i + 1) + " presses");
        }
        press(play, input, KeyEvent.VK_F5);
        assertTrue(window.visible, "round to the plan view, which aims with the pointer");
    }

    /** Whatever a scene did to the pointer, the next screen gets a working one. */
    @Test
    void leavingTheSceneAlwaysGivesThePointerBack(@TempDir Path dir) {
        Fake window = new Fake();
        Pointer.install(window);
        PlayScene play = enter(dir);
        InputManager input = new InputManager();
        press(play, input, KeyEvent.VK_F5);
        assertFalse(window.visible);

        play.onExit();
        opened.remove(play);
        assertTrue(window.visible, "a hidden cursor left behind is a menu nobody can click");
    }

    /**
     * A window that can put the pointter back in the middle is asked to; one
     * that cannot is not, and the view still works. That difference is the
     * whole reason {@link Pointer#canWarp()} exists — AWT has no pointer lock
     * and needs a {@code Robot} it may not get, where GLFW has one.
     */
    @Test
    void onlyAWindowThatCanWarpIsAskedTo(@TempDir Path dir) {
        Fake window = new Fake();
        window.warpable = false;
        Pointer.install(window);
        PlayScene play = enter(dir);
        InputManager input = new InputManager();
        press(play, input, KeyEvent.VK_F5);

        // Hard against the right edge, which is where a warping window would
        // carry it home from.
        input.moveMouse(W - 1, H / 2);
        tick(play, input);
        assertFalse(window.visible, "hidden either way");
        int askedWhenItCannot = window.warps;

        window.warpable = true;
        input.moveMouse(W - 2, H / 2);
        tick(play, input);
        assertTrue(window.warps > askedWhenItCannot,
                "a window that can warp is asked to when the pointer nears the edge");
    }

    /**
     * A window that <em>holds</em> the pointer is left to do it: nothing
     * recentres the cursor and nothing steers from the edges.
     *
     * <p>Both of those exist to work around a window that can only hide the
     * arrow, and both cost something on one that can lock it — a recentre
     * throws away the frame of motion it happens on, and edge-steering would
     * read an unbounded virtual position resting far outside the window as a
     * player leaning on the edge and spin the world. Under a lock the reading
     * is simply what the hand did, every frame.
     */
    @Test
    void aWindowThatHoldsThePointerIsLeftToHoldIt(@TempDir Path dir) {
        Fake window = new Fake();
        window.warpable = true;
        window.holds = true;
        Pointer.install(window);
        PlayScene play = enter(dir);
        InputManager input = new InputManager();
        press(play, input, KeyEvent.VK_F5);
        assertFalse(window.visible, "hidden, as on any window");

        // A locked pointer reports positions that wander out of the window
        // entirely, and getting there is a real turn: the hand moved. Resting
        // there is not, and on an unlocked window it would be — that reading is
        // exactly what "leaning on the edge" looks like.
        input.moveMouse(W + 400, H / 2);
        tick(play, input);
        double settled = play.lookHeading();
        for (int i = 0; i < 30; i++) tick(play, input);

        assertEquals(0, window.warps, "there is no edge to be carried back from");
        assertEquals(settled, play.lookHeading(), 1e-9,
                "and a reading that does not move is not a turn, however far out it is");
    }

    /** With no window attached at all, everything here is a no-op. */
    @Test
    void withNoWindowNothingBlowsUp() {
        Pointer.install(null);
        Pointer.setVisible(false);
        assertFalse(Pointer.canWarp());
        assertFalse(Pointer.warpTo(10, 10));
        Pointer.restore();
    }

    // --- fixtures ------------------------------------------------------------------

    /**
     * One key, one tick. {@link InputManager#newFrame()} is what promotes a
     * press to "just pressed", which is what a key bind reads — a test that
     * skips it presses nothing.
     */
    private static void press(PlayScene play, InputManager input, int keyCode) {
        input.pressKey(keyCode, 0);
        input.newFrame();
        play.update(1 / 60.0, input);
        input.releaseKey(keyCode);
        input.newFrame();
    }

    /** One tick with nothing pressed. */
    private static void tick(PlayScene play, InputManager input) {
        input.newFrame();
        play.update(1 / 60.0, input);
    }

    /** A level with a height axis, since only those have a solid view at all. */
    private static void author(Path levelsDir) {
        LevelStore store = new LevelStore(levelsDir.toString(), TYPE);
        Level lvl = Level.empty("Rise", 24, 24, 32);
        lvl.setFormat(LevelFormat.THREE_D);
        lvl.fillFloor(1);
        lvl.spawnX = 12 * 32;
        lvl.spawnY = 12 * 32;
        GameProfile settings = new GameProfile(TYPE);
        settings.verticality = true;
        settings.mobsEnabled = false;
        settings.audioEnabled = false;
        lvl.captureSettings(settings);
        store.save(lvl);
    }

    private PlayScene enter(Path dir) {
        Path levelsDir = dir.resolve("levels");
        author(levelsDir);
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile profile = new GameProfile(TYPE);
        profile.verticality = true;
        profile.audioEnabled = false;
        ctx.setProfile(profile);
        profile.lastLevelPath =
                new LevelStore(levelsDir.toString(), TYPE).fileFor("Rise").toString();

        PlayScene play = new PlayScene(ctx, "levels/sample_level.json",
                levelsDir.toString(), dir.resolve("saves").toString());
        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        scenes.register("play", play);
        scenes.register("menu", new MainMenuScene(ctx));
        scenes.setScene("play");
        opened.add(play);
        play.update(1 / 60.0, new InputManager());
        return play;
    }
}
