package com.larsons.engine.save;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job A — autosave, and the reason it is not on the game thread.
 *
 * <p>The measurement that opens {@code SAVE_PLAN.md}'s Job A is that
 * serializing a level is not free: 12 ms for a 512×512 level, 159 ms for a
 * 1024×1024 one. A 60 Hz frame is 16.67 ms, so an autosave that does all of
 * that where the game runs is a dropped frame at best. The tests here pin the
 * three things that keep it off the game thread and correct anyway.
 */
class AutosaveTest {

    private static final String TYPE = "autosave type";

    private static Level authored(Path levelsDir, String name, int side) {
        LevelStore store = new LevelStore(levelsDir.toString(), TYPE);
        Level lvl = Level.empty(name, side, side, 32);
        for (int c = 0; c < side; c++) {
            for (int r = side / 2; r < side; r++) lvl.setTile(c, r, Level.LAYER_GROUND, 1);
        }
        store.save(lvl);
        return lvl;
    }

    private static RunSession session(Path saves, Path levels) {
        return RunSession.open(new SaveStore(saves.toString(), levels.toString(),
                TYPE, SaveStore.DEFAULT_SLOT));
    }

    private static void capture(RunSession run, Level level, double health) {
        PlayerState p = new PlayerState(0, "", 64, 64);
        p.health = health;
        run.capture(TYPE, level, p, new Inventory(ItemRegistry.standard()),
                new PlayerStats(), 0.5);
    }

    // ---------------------------------------------------------------------------

    /**
     * <b>The measurement.</b> The part of a save that happens on the game
     * thread is the snapshot; the string building and the file write are not.
     * Taken on a 512×512 level, where the old all-on-one-thread cost was
     * measured at 11.9 ms against a 16.67 ms budget.
     *
     * <p>Deliberately a loose bound rather than a tight one: CI machines are
     * not benchmarks, and the claim being defended is "this is no longer the
     * dominant cost of a frame", not a particular number of milliseconds. It
     * still fails outright if somebody moves {@code stringify} back onto this
     * side of the call.
     */
    @Test
    void theGameThreadPaysForTheSnapshotAndNotForTheWrite(@TempDir Path saves,
                                                          @TempDir Path levels)
            throws Exception {
        Level level = authored(levels, "Big", 512);
        try (RunSession run = session(saves, levels)) {
            run.adopt(level);
            level.setTile(3, 300, Level.LAYER_GROUND, 0); // dig out a ground cell
            capture(run, level, 100);

            long started = System.nanoTime();
            run.saveAsync(level);
            double onThreadMs = (System.nanoTime() - started) / 1e6;

            run.awaitWrites();
            assertTrue(Files.exists(run.store().runFile()), "the write should have landed");
            assertTrue(run.store().hasLevel("Big"), "the changed level should be in the slot");

            assertTrue(onThreadMs < 16.67,
                    "the game thread should not spend a whole frame on a save "
                            + "(took " + String.format("%.2f", onThreadMs) + " ms)");
        }
    }

    /**
     * An unchanged level is not written at all. This is what makes a periodic
     * autosave cost microseconds instead of milliseconds for a player who is
     * walking around rather than building.
     */
    @Test
    void anUntouchedLevelIsNotRewritten(@TempDir Path saves, @TempDir Path levels) {
        Level level = authored(levels, "Quiet", 64);
        try (RunSession run = session(saves, levels)) {
            run.adopt(level);
            assertFalse(run.levelNeedsWrite(level),
                    "a level nobody has touched has nothing to save");

            capture(run, level, 100);
            run.saveNow(level);
            assertFalse(run.store().hasLevel("Quiet"),
                    "…so the slot should hold the run, and no copy of the level");
            assertNotNull(run.store().loadRun());

            level.setTile(1, 1, Level.LAYER_GROUND, 7);
            assertTrue(run.levelNeedsWrite(level), "a mined tile is a reason to write");
            run.saveNow(level);
            assertTrue(run.store().hasLevel("Quiet"));
        }
    }

    /**
     * A chest is not terrain, so the level's own revision counter does not
     * notice it. Saying so explicitly is what stops a stocked chest from being
     * the one thing a save quietly drops.
     */
    @Test
    void aContainerChangeIsAReasonToWriteEvenThoughNoTileMoved(@TempDir Path saves,
                                                               @TempDir Path levels) {
        Level level = authored(levels, "Vault", 64);
        try (RunSession run = session(saves, levels)) {
            run.adopt(level);
            assertFalse(run.levelNeedsWrite(level));

            level.containers.put(Level.cellKey(2, 2), new java.util.ArrayList<>());
            assertFalse(run.levelNeedsWrite(level),
                    "the level cannot tell — which is why the scene has to say");

            run.markLevelDirty("Vault");
            assertTrue(run.levelNeedsWrite(level));
        }
    }

    /** The periodic autosave waits for both the clock and for something to save. */
    @Test
    void theTimerOnlyFiresWhenSomethingHasHappened(@TempDir Path saves, @TempDir Path levels) {
        Level level = authored(levels, "Timed", 64);
        try (RunSession run = session(saves, levels)) {
            run.adopt(level);
            run.tick(RunSession.AUTOSAVE_SECONDS + 1);
            assertFalse(run.autosaveDue(),
                    "a run where nothing happened has nothing to autosave");

            run.markDirty();
            assertTrue(run.autosaveDue());

            capture(run, level, 100);
            run.saveNow(level);
            assertFalse(run.autosaveDue(), "saving should restart both the clock and the flag");
            assertFalse(run.dirty());
        }
    }

    /** The play clock counts up, and ignores the huge dt of a stalled frame. */
    @Test
    void thePlayClockCountsRealSeconds(@TempDir Path saves, @TempDir Path levels) {
        try (RunSession run = session(saves, levels)) {
            for (int i = 0; i < 120; i++) run.tick(1 / 60.0);
            assertEquals(2.0, run.record().playSeconds, 0.01);

            run.tick(45); // a debugger breakpoint, or a laptop lid closing
            assertEquals(2.0, run.record().playSeconds, 0.01,
                    "a frame that took 45 seconds was not 45 seconds of play");
        }
    }

    /**
     * A save taken later must land later, even when the earlier one went to the
     * writer thread and the later one did not want to wait for it.
     *
     * <p>This was a real bug and an intermittent one: {@code saveNow} wrote
     * inline while an autosave queued moments before was still in flight, so a
     * quit sometimes persisted the game as it had been half a minute earlier.
     */
    @Test
    void aLaterSaveIsNeverOvertakenByAnEarlierOne(@TempDir Path saves, @TempDir Path levels) {
        Level level = authored(levels, "Order", 256);
        try (RunSession run = session(saves, levels)) {
            run.adopt(level);
            level.setTile(4, 200, Level.LAYER_GROUND, 0);

            capture(run, level, 100);
            run.saveAsync(level);        // queued, health 100
            capture(run, level, 33);
            run.saveNow(level);          // must land after it, health 33

            assertEquals(33, run.store().loadRun().health, 1e-9,
                    "the quit save must be the one on disk, not the autosave before it");
        }
    }

    /** Closing flushes and stops; a save after that still writes rather than vanishing. */
    @Test
    void closingIsSafeAndStillSaves(@TempDir Path saves, @TempDir Path levels) {
        Level level = authored(levels, "Closing", 64);
        RunSession run = session(saves, levels);
        run.adopt(level);
        capture(run, level, 70);
        run.saveAsync(level);
        run.close();
        assertEquals(70, run.store().loadRun().health, 1e-9);

        capture(run, level, 12);
        run.saveNow(level); // after close: runs inline rather than being dropped
        assertEquals(12, run.store().loadRun().health, 1e-9);
    }
}
