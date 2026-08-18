package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.save.SaveStore;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job D — a door is no longer a one-way trip for everything except you.
 *
 * <p>Driven through the real {@link PlayScene}, not through the save classes
 * underneath it, because the failure this fixes was never in those classes:
 * it was in the order of two lines in {@code tryDoorTravel}, which read the
 * destination from disk and never wrote the level it was leaving. A test of
 * the mechanism would have passed against the broken engine.
 *
 * <p>Three things must survive walking A → B → A:
 * <ol>
 *   <li>the hole dug in A,</li>
 *   <li>what was put in a chest in A,</li>
 *   <li>the fact that A's one-shot stat rule has already paid out.</li>
 * </ol>
 * The third is a regression test for a live exploit: every one-shot reward a
 * map-maker authored could be farmed by walking through a door and back.
 */
class DoorContinuityTest {

    private static final int W = 800, H = 600;
    private static final String TYPE = "door type";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * Every scene this test opened, closed after it.
     *
     * <p>Not tidiness: a live run owns a writer thread, and leaving one running
     * means it can still be writing into the temporary directory while JUnit is
     * deleting it. {@code onExit} flushes the run and waits for that thread to
     * stop, which is the only point at which the folder is nobody's. Calling it
     * twice is harmless — the second finds no run.
     */
    private final List<PlayScene> opened = new ArrayList<>();

    @AfterEach
    void closeEveryRun() {
        for (PlayScene scene : opened) scene.onExit();
        opened.clear();
    }

    /**
     * Two levels joined by a door in each, one of which pays out once for
     * mining. Deliberately tiny and gravity-free so the body stays where it is
     * put and the test is about doors rather than about physics.
     */
    private static void authorTwoLevels(Path levelsDir) {
        LevelStore store = new LevelStore(levelsDir.toString(), TYPE);

        Level cave = playable("Cave");
        cave.entities.add(new Level.EntitySpawn("door", "to_town", 96, 96));
        cave.statRules.add(
                new StatRule("blocks_mined", 1, "diamond", 1, null, 0, false, false));
        // A chest to stash things in, at the cell the test will fill.
        cave.setTile(6, 6, Level.LAYER_GROUND, 1);
        store.save(cave);

        Level town = playable("Town");
        town.entities.add(new Level.EntitySpawn("door", "to_cave", 96, 96));
        store.save(town);

        DoorDirectory doors = new DoorDirectory(store);
        doors.put(new DoorLink("to_town", "To Town", "Town", Color.ORANGE));
        doors.put(new DoorLink("to_cave", "To the Cave", "Cave", Color.CYAN));
    }

    private static Level playable(String name) {
        Level lvl = Level.empty(name, 20, 16, 32);
        // Solid ground under the whole map, so nothing falls out of the world.
        for (int c = 0; c < 20; c++) {
            for (int r = 10; r < 16; r++) lvl.setTile(c, r, Level.LAYER_GROUND, 1);
        }
        lvl.spawnX = 96;
        lvl.spawnY = 96;
        GameProfile settings = new GameProfile(TYPE);
        settings.gravityEnabled = false;
        settings.mobsEnabled = false;
        settings.audioEnabled = false;
        lvl.captureSettings(settings);
        return lvl;
    }

    /** A play scene rooted in temporary folders, already entered. */
    private record Fixture(PlayScene play, GameContext ctx, InputManager input,
                           Path levelsDir, Path savesDir) {

        void tick() {
            input.newFrame();
            play.update(1 / 60.0, input);
        }

        /** Press [E] on the door the player is standing on. */
        void walkThroughDoor() {
            int key = KeyBinds.active().binding(GameAction.INTERACT, 0).code();
            Canvas src = new Canvas();
            input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, key, (char) 0));
            input.keyReleased(new KeyEvent(src, KeyEvent.KEY_RELEASED, 0L, 0, key, (char) 0));
            tick();
        }
    }

    private Fixture enter(Path dir) {
        Path levelsDir = dir.resolve("levels");
        Path savesDir = dir.resolve("saves");
        authorTwoLevels(levelsDir);

        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        GameProfile profile = new GameProfile(TYPE);
        profile.audioEnabled = false;
        ctx.setProfile(profile);
        // "Load Level" pointing at the cave, which is how a first run starts.
        profile.lastLevelPath =
                new LevelStore(levelsDir.toString(), TYPE).fileFor("Cave").toString();

        PlayScene play = new PlayScene(ctx, "levels/sample_level.json",
                levelsDir.toString(), savesDir.toString());
        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        scenes.register("play", play);
        scenes.register("menu", new MainMenuScene(ctx));
        scenes.setScene("play");
        opened.add(play);
        return new Fixture(play, ctx, new InputManager(), levelsDir, savesDir);
    }

    /** A fingerprint of the authored files, to prove none of them moved. */
    private static Map<String, String> fingerprint(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                out.put(dir.relativize(p).toString(), Files.readString(p));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------

    /**
     * <b>The whole job, in one test.</b> Dig, stash, collect the reward, walk
     * out, walk back — and find the level as it was left rather than as it was
     * authored.
     */
    @Test
    void walkingOutOfALevelAndBackFindsItAsItWasLeft(@TempDir Path dir) throws Exception {
        Fixture f = enter(dir);
        f.tick();

        Level cave = f.play.currentLevel();
        assertEquals("Cave", cave.name);

        // Dig a hole, stash something in a chest cell, and earn the one-shot.
        cave.setTile(4, 10, Level.LAYER_GROUND, 0);
        cave.containers.put(Level.cellKey(6, 6),
                List.of(new ItemStack("stone", 17)));
        f.play.runStats().add("blocks_mined", 1);
        f.tick(); // the rule fires and pays out
        assertEquals(1, f.play.carried().totalOf("diamond"),
                "the one-shot rule should have paid out once");

        // A → B
        f.walkThroughDoor();
        assertEquals("Town", f.play.currentLevel().name, "[E] on the door should travel");

        // B → A
        f.walkThroughDoor();
        assertEquals("Cave", f.play.currentLevel().name);

        Level back = f.play.currentLevel();
        assertEquals(0, back.tileAt(4, 10, Level.LAYER_GROUND),
                "the hole dug in this level should still be dug");
        List<ItemStack> chest = back.containers.get(Level.cellKey(6, 6));
        assertNotNull(chest, "the chest stocked in this level should still be stocked");
        assertEquals(17, chest.get(0).count);

        // …and the one-shot must not be armed again.
        f.tick();
        assertEquals(1, f.play.carried().totalOf("diamond"),
                "walking through a door and back must not re-arm a one-shot reward");

        // I1: none of that touched the levels as their author built them.
        Map<String, String> authored = fingerprint(f.levelsDir());
        f.play.onExit();
        assertEquals(authored, fingerprint(f.levelsDir()),
                "playing must never write to an authored level");
    }

    /**
     * The counters themselves still carry across a door — that part was always
     * right, and the fix must not have traded one lifetime bug for another.
     */
    @Test
    void whatYouAreCarryingAndWhatYouHaveDoneStillCrossTheDoor(@TempDir Path dir) {
        Fixture f = enter(dir);
        f.tick();
        f.play.carried().add("stone", 9);
        f.play.runStats().add("blocks_mined", 5);

        f.walkThroughDoor();
        assertEquals("Town", f.play.currentLevel().name);
        assertEquals(9, f.play.carried().totalOf("stone"),
                "a door is not a place to lose your things");
        assertEquals(5, f.play.runStats().get("blocks_mined"), 1e-9);
    }

    /**
     * Quitting and coming back is the other half of continuity: a second run
     * over the same slot resumes in the level it stopped in, with the body and
     * the things it had.
     */
    @Test
    void aRunResumesWhereItStopped(@TempDir Path dir) throws Exception {
        Fixture first = enter(dir);
        first.tick();
        first.play.carried().add("bread", 4);
        first.play.runStats().add("mobs_killed", 2);
        first.play.currentLevel().setTile(4, 10, Level.LAYER_GROUND, 0);
        first.walkThroughDoor();                       // stop in Town
        first.play.player().health = 33;
        first.play.onExit();                           // quit: flushes the run

        // A second launch over the same folders and the same slot.
        Fixture second = enter(dir);
        second.tick();

        assertEquals("Town", second.play.currentLevel().name,
                "a continued run should resume in the level it stopped in");
        assertEquals(4, second.play.carried().totalOf("bread"));
        assertEquals(2, second.play.runStats().get("mobs_killed"), 1e-9);
        assertEquals(33, second.play.player().health, 0.5,
                "health should be what it was, not a full bar");

        // …and the hole dug in the level it is not standing in is still dug.
        SaveStore store = new SaveStore(dir.resolve("saves").toString(),
                dir.resolve("levels").toString(), TYPE, SaveStore.DEFAULT_SLOT);
        assertEquals(0, store.levelFor("Cave").tileAt(4, 10, Level.LAYER_GROUND));
        second.play.onExit();
    }

    /** Starting a slot over goes back to the authored levels. */
    @Test
    void aNewRunStartsFromTheAuthoredLevels(@TempDir Path dir) {
        Fixture first = enter(dir);
        first.tick();
        first.play.currentLevel().setTile(4, 10, Level.LAYER_GROUND, 0);
        first.play.carried().add("bread", 4);
        first.play.onExit();

        Fixture second = enter(dir);
        second.ctx().startNewRun(SaveStore.DEFAULT_SLOT);
        second.play.onEnter();
        second.tick();

        assertEquals(1, second.play.currentLevel().tileAt(4, 10, Level.LAYER_GROUND),
                "a new run should find the level as its author built it");
        assertEquals(0, second.play.carried().totalOf("bread"),
                "…and an empty inventory");
        assertTrue(second.play.currentLevel().name.equals("Cave"));
        second.play.onExit();
    }
}
