package com.larsons.engine.save;

import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job C — a save slot is a second levels root.
 *
 * <p>Two things are under test and the second one matters more than it looks.
 * The first is that a run keeps its changes to <em>every</em> level it has
 * touched, not just the one it stopped in. The second is invariant I1: an
 * authored level is a template, and playing must never write to it. The engine
 * used to fail I1 outright — the pause menu's <em>Save Level</em> wrote back
 * over the authored file, so playing a level edited it and there was no
 * pristine copy left to start a second run from.
 */
class SaveStoreTest {

    /** An authored game type of two levels, in a levels root of its own. */
    private static LevelStore authored(Path levelsDir) {
        LevelStore store = new LevelStore(levelsDir.toString(), "test type");
        Level cave = Level.empty("Cave", 16, 12, 32);
        cave.setTile(3, 3, Level.LAYER_GROUND, 1);
        store.save(cave);
        Level town = Level.empty("Town", 16, 12, 32);
        town.setTile(5, 5, Level.LAYER_GROUND, 1);
        store.save(town);
        return store;
    }

    private static SaveStore slot(Path saves, Path levels, String name) {
        return new SaveStore(saves.toString(), levels.toString(), "test type", name);
    }

    /** A fingerprint of every authored file, to prove none of them moved. */
    private static Map<String, String> fingerprint(Path dir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                out.put(dir.relativize(p).toString(), Files.readString(p));
            }
        }
        return out;
    }

    @Test
    void anEmptySlotHasNoRunAndReadsTheAuthoredLevels(@TempDir Path saves, @TempDir Path levels) {
        authored(levels);
        SaveStore store = slot(saves, levels, "slot1");

        assertFalse(store.exists());
        assertNull(store.loadRun());
        assertTrue(store.savedLevels().isEmpty());

        Level cave = store.levelFor("Cave");
        assertNotNull(cave, "with no run copy it should fall back to the authored level");
        assertEquals(1, cave.tileAt(3, 3, Level.LAYER_GROUND));
        assertNull(store.levelFor("Atlantis"), "a level nobody authored is simply absent");
        assertNull(store.levelFor(""));
    }

    /**
     * <b>The point of the job.</b> A run modifies two levels, is saved, and
     * comes back with both — while the authored files are byte-for-byte what
     * they were.
     */
    @Test
    void aRunKeepsEveryLevelItTouchedAndTheAuthoredOnesAreUntouched(
            @TempDir Path saves, @TempDir Path levels) throws IOException {
        authored(levels);
        Map<String, String> before = fingerprint(levels);
        SaveStore store = slot(saves, levels, "slot1");

        // Dig a hole in the cave and stock a chest in the town.
        Level cave = store.levelFor("Cave");
        cave.setTile(3, 3, Level.LAYER_GROUND, 0);
        cave.setTile(7, 2, Level.LAYER_GROUND, 4);
        store.saveLevel(cave);

        Level town = store.levelFor("Town");
        List<ItemStack> chest = new ArrayList<>();
        chest.add(new ItemStack("stone", 17));
        town.containers.put(Level.cellKey(5, 5), chest);
        store.saveLevel(town);

        RunRecord run = new RunRecord();
        run.gameType = "test type";
        run.levelName = "Town";
        store.saveRun(run);

        // A fresh store over the same slot — the next launch.
        SaveStore reopened = slot(saves, levels, "slot1");
        assertTrue(reopened.exists());
        assertEquals("Town", reopened.loadRun().levelName);

        Level caveBack = reopened.levelFor("Cave");
        assertEquals(0, caveBack.tileAt(3, 3, Level.LAYER_GROUND), "the hole should still be dug");
        assertEquals(4, caveBack.tileAt(7, 2, Level.LAYER_GROUND));

        Level townBack = reopened.levelFor("Town");
        List<ItemStack> back = townBack.containers.get(Level.cellKey(5, 5));
        assertNotNull(back, "the chest should still be there");
        assertEquals(17, back.get(0).count, "…and still hold what was put in it");

        assertEquals(List.of("cave", "town"), reopened.savedLevels());

        // I1: nothing on the play path may write to an authored level.
        assertEquals(before, fingerprint(levels),
                "playing a level must not edit the level as its author built it");
    }

    /** Two slots over one game type are two independent runs. */
    @Test
    void slotsDoNotSeeEachOther(@TempDir Path saves, @TempDir Path levels) {
        authored(levels);

        SaveStore one = slot(saves, levels, "slot1");
        Level cave = one.levelFor("Cave");
        cave.setTile(3, 3, Level.LAYER_GROUND, 0);
        one.saveLevel(cave);
        RunRecord runOne = new RunRecord();
        runOne.levelName = "Cave";
        runOne.stats.put("blocks_mined", 1.0);
        one.saveRun(runOne);

        SaveStore two = slot(saves, levels, "slot2");
        assertFalse(two.exists());
        assertEquals(1, two.levelFor("Cave").tileAt(3, 3, Level.LAYER_GROUND),
                "a new run starts from the authored level, not from the other slot");
        assertFalse(two.hasLevel("Cave"));

        assertEquals(List.of("slot1"), SaveStore.slots(saves.toString(), "test type"));
        two.saveRun(new RunRecord());
        assertEquals(List.of("slot1", "slot2"), SaveStore.slots(saves.toString(), "test type"));
    }

    /** Deleting a slot takes its levels with it and leaves the others alone. */
    @Test
    void deletingASlotRemovesItEntirely(@TempDir Path saves, @TempDir Path levels)
            throws IOException {
        authored(levels);
        Map<String, String> before = fingerprint(levels);

        SaveStore one = slot(saves, levels, "slot1");
        one.saveLevel(one.levelFor("Cave"));
        one.saveRun(new RunRecord());
        SaveStore two = slot(saves, levels, "slot2");
        two.saveRun(new RunRecord());

        assertTrue(one.delete());
        assertFalse(one.exists());
        assertFalse(Files.exists(one.directory()));
        assertTrue(two.exists(), "deleting one run should not touch another");
        assertEquals(List.of("slot2"), SaveStore.slots(saves.toString(), "test type"));
        assertEquals(before, fingerprint(levels), "…nor the authored levels");

        assertFalse(one.delete(), "deleting a slot that is already gone is not an error");
    }

    /**
     * A corrupt run file reports "no save" rather than throwing. A file the
     * player cannot fix from inside the game must never be the reason the game
     * will not start.
     */
    @Test
    void aCorruptRunFileReadsAsNoSave(@TempDir Path saves, @TempDir Path levels)
            throws IOException {
        authored(levels);
        SaveStore store = slot(saves, levels, "slot1");
        store.saveRun(new RunRecord());
        Files.writeString(store.runFile(), "{ this is not json");

        assertTrue(store.exists(), "the file is still there to be repaired by hand");
        assertNull(store.loadRun(), "…but it reads as no save");
    }

    /**
     * A damaged copy of one level costs that level's changes, not the run. The
     * authored level is the fallback, which is the whole reason it is kept
     * pristine.
     */
    @Test
    void aDamagedRunCopyFallsBackToTheAuthoredLevel(@TempDir Path saves, @TempDir Path levels)
            throws IOException {
        authored(levels);
        SaveStore store = slot(saves, levels, "slot1");
        Level cave = store.levelFor("Cave");
        cave.setTile(3, 3, Level.LAYER_GROUND, 0);
        Path copy = store.saveLevel(cave);
        Files.writeString(copy, "{ truncated");

        Level back = store.levelFor("Cave");
        assertNotNull(back);
        assertEquals(1, back.tileAt(3, 3, Level.LAYER_GROUND),
                "losing one level's changes beats losing the run");
    }

    /** The run file is plain readable JSON in a predictable place. */
    @Test
    void theSlotLayoutIsPlainFiles(@TempDir Path saves, @TempDir Path levels) {
        authored(levels);
        SaveStore store = slot(saves, levels, "slot1");
        store.saveLevel(store.levelFor("Cave"));
        store.saveRun(new RunRecord());

        Path dir = saves.resolve("test_type").resolve("slot1");
        assertTrue(Files.exists(dir.resolve(SaveStore.RUN_FILE)), dir.toString());
        assertTrue(Files.exists(dir.resolve(SaveStore.LEVELS_DIR).resolve("cave.json")),
                "a run's level copies live beside its run file, one folder down");
    }
}
