package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.ConfigForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pause screen: what it says about the run, and that it says it correctly.
 *
 * <p>A pause menu used to be five buttons. The thing worth testing about the
 * one that replaced it is not that it draws — the golden frames cover that —
 * but that the readings it draws are the run's actual readings: the goals
 * reflect what has already fired, the save chip reflects whether anything is
 * unsaved, and the pools are the body's rather than the character sheet's.
 */
class PauseMenuTest {

    private static final int W = 1280, H = 720;
    private static final String TYPE = "pause type";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final List<PlayScene> opened = new ArrayList<>();

    @AfterEach
    void closeEveryRun() {
        for (PlayScene scene : opened) scene.onExit();
        opened.clear();
    }

    /** A level with one one-shot goal and one repeating one. */
    private static void author(Path levelsDir) {
        LevelStore store = new LevelStore(levelsDir.toString(), TYPE);
        Level lvl = Level.empty("Hollow", 20, 16, 32);
        for (int c = 0; c < 20; c++) {
            for (int r = 10; r < 16; r++) lvl.setTile(c, r, Level.LAYER_GROUND, 1);
        }
        lvl.spawnX = 96;
        lvl.spawnY = 96;
        lvl.statRules.add(new StatRule("blocks_mined", 10, "diamond", 1, null, 0, false, false));
        lvl.statRules.add(new StatRule("jumps", 5, "bread", 2, null, 0, true, true));
        GameProfile settings = new GameProfile(TYPE);
        settings.gravityEnabled = false;
        settings.mobsEnabled = false;
        settings.audioEnabled = false;
        lvl.captureSettings(settings);
        store.save(lvl);
    }

    /** The same level, expanded into a generated world. */
    private static void authorWorld(Path levelsDir) {
        LevelStore store = new LevelStore(levelsDir.toString(), TYPE);
        Level lvl = store.load("Hollow");
        assertNotNull(lvl, "the level authored above");
        GameProfile settings = lvl.settings != null ? lvl.settings : new GameProfile(TYPE);
        settings.verticality = true;
        com.larsons.engine.world.gen.TerrainSettings terrain =
                new com.larsons.engine.world.gen.TerrainSettings();
        terrain.enabled = true;
        terrain.seed = 909;
        terrain.worldSize = com.larsons.engine.world.gen.TerrainSettings.MIN_WORLD_SIZE;
        terrain.normalize();
        settings.terrain = terrain;
        lvl.captureSettings(settings);
        store.save(lvl);
    }

    private PlayScene enterWorld(Path dir) {
        Path levelsDir = dir.resolve("levels");
        author(levelsDir);
        authorWorld(levelsDir);
        return open(dir, levelsDir);
    }

    private PlayScene enter(Path dir) {
        Path levelsDir = dir.resolve("levels");
        author(levelsDir);
        return open(dir, levelsDir);
    }

    private PlayScene open(Path dir, Path levelsDir) {

        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile profile = new GameProfile(TYPE);
        profile.audioEnabled = false;
        ctx.setProfile(profile);
        profile.lastLevelPath =
                new LevelStore(levelsDir.toString(), TYPE).fileFor("Hollow").toString();

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

    // ---------------------------------------------------------------------------

    /** The header and the run panel describe the run actually being played. */
    @Test
    void theScreenNamesTheRunItIsPausing(@TempDir Path dir) {
        PlayScene play = enter(dir);
        PauseScreen.Status s = play.pauseStatus();

        assertEquals(TYPE, s.gameType);
        assertEquals("Hollow", s.levelName);
        assertFalse(s.characterName.isBlank(), "the character being played should be named");
        assertFalse(s.world.isBlank(), "which physics this level runs is worth saying");
        assertFalse(s.online);
        assertEquals("slot1", s.slot);
        assertFalse(s.keyHints.isEmpty(), "a stuck player should get their binds back");
    }

    /** Vitals are the body's numbers, not the character sheet's maxima. */
    @Test
    void vitalsFollowTheBody(@TempDir Path dir) {
        PlayScene play = enter(dir);
        play.player().maxHealth = 120;
        play.player().health = 47;

        PauseScreen.Status s = play.pauseStatus();
        assertEquals(47, s.health, 1e-9);
        assertEquals(120, s.maxHealth, 1e-9);
    }

    /**
     * <b>The panel that earns the screen.</b> Goals show every rule the level
     * sets — not just the ones its author put on the HUD — and a one-shot that
     * has already paid out reads as settled rather than as stuck at 100%.
     */
    @Test
    void goalsShowEveryRuleAndWhichOnesAreDone(@TempDir Path dir) {
        PlayScene play = enter(dir);

        List<PauseScreen.Objective> before = play.pauseStatus().objectives;
        assertEquals(2, before.size(),
                "both rules should be listed, including the one with no HUD bar");
        assertFalse(before.get(0).done());
        assertEquals(0, before.get(0).progress(), 1e-9);

        // Earn the one-shot.
        play.runStats().add("blocks_mined", 10);
        play.update(1 / 60.0, new InputManager());

        List<PauseScreen.Objective> after = play.pauseStatus().objectives;
        assertTrue(after.get(0).done(), "a one-shot that has fired should read as done");
        assertEquals("done", after.get(0).detail());
        assertTrue(after.get(0).label().contains("diamond"),
                "a goal should say what it pays out: " + after.get(0).label());
    }

    /** A repeating goal counts toward its next firing, not its first. */
    @Test
    void aRepeatingGoalCountsTowardTheNextStep(@TempDir Path dir) {
        PlayScene play = enter(dir);
        play.runStats().add("jumps", 5);
        play.update(1 / 60.0, new InputManager());

        PauseScreen.Objective jumps = play.pauseStatus().objectives.get(1);
        assertFalse(jumps.done(), "a repeating goal is never finished");
        assertTrue(jumps.detail().contains("10"),
                "after one firing the target should be the next step: " + jumps.detail());
        assertTrue(jumps.detail().contains("×1"),
                "…and it should say how many times it has paid out: " + jumps.detail());
    }

    /** The save chip tracks the run, which is the point of having it. */
    @Test
    void theSaveStateIsLive(@TempDir Path dir) {
        PlayScene play = enter(dir);
        assertNotNull(play.runSession());

        play.runSession().markDirty();
        assertTrue(play.pauseStatus().unsaved);

        play.runSession().saveNow(play.currentLevel());
        assertFalse(play.pauseStatus().unsaved, "a save should clear the warning");
        assertTrue(play.pauseStatus().savedAt > 0);
    }

    /** Online there is no run to save, and the session's numbers take its place. */
    @Test
    void offlineRunsHaveASlotAndOnlineOnesDoNot(@TempDir Path dir) {
        PauseScreen.Status s = enter(dir).pauseStatus();
        assertEquals("slot1", s.slot);
        assertFalse(s.online);
        assertEquals(0, s.players, "player count is a thing only a session has");
    }

    /** The menu offers saving offline and does not pretend to online. */
    @Test
    void theActionsMatchWhoOwnsTheWorld(@TempDir Path dir) {
        ConfigForm form = enter(dir).pauseFormForTest();
        List<String> labels = new ArrayList<>();
        for (ConfigForm.Option o : form.options()) labels.add(o.label());

        assertTrue(labels.contains("Resume"));
        assertTrue(labels.contains("Save Run"));
        assertTrue(labels.contains("Save and Quit"));
        assertTrue(labels.contains("Quit to Menu"));
        assertEquals("Resume", labels.get(0), "the way out should be the first thing offered");
    }

    /**
     * <b>How far to draw is set while you are looking at it.</b> The render
     * distance and the horizon used to live in the editor's level-settings
     * form, which is the one place a player cannot reach: judging a view
     * distance means seeing the view, and answering "is this what is costing me
     * the frame rate" meant leaving the level, opening the editor, changing a
     * number and coming back. All three now sit in the pause menu, beside each
     * other, because they are one decision.
     */
    @Test
    void theViewDistancesAreOnThePauseMenu(@TempDir Path dir) {
        List<String> labels = labels(enterWorld(dir).pauseFormForTest());
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Render distance")),
                "the render distance is set here now: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Detail distance")),
                "and so is how much of it is drawn a block at a time: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Decorations")),
                "and how far the flowers go: " + labels);
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Distant generation")),
                "and the landscape behind it all: " + labels);
        assertEquals("Resume", labels.get(0),
                "the way out is still the first thing offered");
    }

    /** And they are gone from the level-settings form they came out of. */
    @Test
    void theLevelSettingsFormNoLongerSetsThem() {
        GameProfile p = new GameProfile(TYPE);
        p.terrain = new com.larsons.engine.world.gen.TerrainSettings();
        ConfigForm form = new ConfigForm("Settings");
        TerrainForms.addTerrainOptions(form, p, () -> {});
        for (String label : labels(form)) {
            assertFalse(label.startsWith("Render distance"),
                    "the render distance moved to the pause menu");
            assertFalse(label.startsWith("Distant generation"),
                    "so did the horizon");
        }
    }

    /** A level with no generated world has no distances worth offering. */
    @Test
    void anOrdinaryLevelIsNotOfferedThem(@TempDir Path dir) {
        List<String> labels = labels(enter(dir).pauseFormForTest());
        assertTrue(labels.stream().noneMatch(l -> l.startsWith("Render distance")),
                "a hand-built level is drawn to its own edges: " + labels);
    }

    private static List<String> labels(ConfigForm form) {
        List<String> labels = new ArrayList<>();
        for (ConfigForm.Option o : form.options()) labels.add(o.label());
        return labels;
    }

    // --- the screen itself -----------------------------------------------------

    /** It draws, at the sizes a window can actually be, without falling over. */
    @Test
    void itRendersAtEverySize(@TempDir Path dir) {
        PlayScene play = enter(dir);
        PauseScreen.Status status = play.pauseStatus();
        ConfigForm form = play.pauseFormForTest();

        for (int[] size : new int[][] {{1920, 1080}, {1280, 720}, {800, 480}, {640, 400},
                                       {320, 240}, {120, 90}}) {
            BufferedImage image = new BufferedImage(Math.max(1, size[0]), Math.max(1, size[1]),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            PauseScreen.render(Java2DTarget.unsized(g), size[0], size[1], form, status);
            g.dispose();
        }
    }

    /** Below the threshold the panels go and the actions get the whole width. */
    @Test
    void theNarrowLayoutDropsThePanels() {
        assertTrue(PauseScreen.TWO_COLUMN_MIN_WIDTH > 640,
                "a 640-wide window should get the one-column layout");
        assertTrue(PauseScreen.TWO_COLUMN_MIN_WIDTH <= 800,
                "an 800-wide window is wide enough for panels and should keep them");
    }

    // --- the formatters --------------------------------------------------------

    @Test
    void agoReadsAsADeltaAndNotAsAClock() {
        assertEquals("0s ago", PauseScreen.ago(0));
        assertEquals("42s ago", PauseScreen.ago(42_000));
        assertEquals("2m ago", PauseScreen.ago(150_000));
        assertEquals("3h ago", PauseScreen.ago(3 * 3600_000L + 60_000));
        assertEquals("0s ago", PauseScreen.ago(-5_000), "a clock that went backwards is not a fault");
    }

    @Test
    void bigCountersAreAbbreviated() {
        assertEquals("0", PauseScreen.number(0));
        assertEquals("940", PauseScreen.number(940));
        assertEquals("1.2k", PauseScreen.number(1234));
        assertEquals("3.4M", PauseScreen.number(3_400_000));
    }

    @Test
    void theClockIsNamedRatherThanRead() {
        assertEquals("Dawn", PauseScreen.timeOfDayName(0.02));
        assertEquals("Midday", PauseScreen.timeOfDayName(0.25));
        assertEquals("Night", PauseScreen.timeOfDayName(0.7));
        assertEquals("Dawn", PauseScreen.timeOfDayName(2.02), "the clock wraps");
    }
}
