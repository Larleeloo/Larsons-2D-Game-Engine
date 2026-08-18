package com.larsons.engine.save;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.sim.StatRuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job P — the object the engine did not have.
 *
 * <p>Everything here is about one thing surviving a write and a read: the run.
 * The counter-test the engine already passed is that a {@code Level} survives
 * one; what it could not do was say who was playing it.
 */
class RunRecordTest {

    private static Inventory stocked() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("stone", 12);
        inv.add("bread", 3);
        inv.select(2);
        return inv;
    }

    @Test
    void aRunSurvivesTheJsonRoundTripFieldForField() {
        PlayerState p = new PlayerState(0, "", 320, 480);
        p.z = 64;
        p.facing = Facing.NORTH_WEST;
        p.facingLeft = true;
        p.characterKey = "scout";
        p.maxHealth = 120;
        p.health = 47.5;
        p.maxMana = 80;
        p.mana = 12;
        p.maxStamina = 90;
        p.stamina = 33;
        p.ultCharge = 0.42;

        PlayerStats stats = new PlayerStats();
        stats.add("blocks_mined", 57);
        stats.add("mobs_killed", 3);

        RunRecord run = RunRecord.capture("my type", "cave", p, stocked(), stats, 0.78, 1234.5);
        run.putFiredCounts("cave", new int[] {1, 0, 4});

        RunRecord back = RunRecord.fromJson(run.toJson());

        assertEquals("my type", back.gameType);
        assertEquals("cave", back.levelName);
        assertEquals("scout", back.characterKey);
        assertEquals(320, back.x, 1e-9);
        assertEquals(480, back.y, 1e-9);
        assertEquals(64, back.z, 1e-9);
        assertEquals(Facing.NORTH_WEST.name(), back.facing);
        assertTrue(back.facingLeft);
        assertEquals(47.5, back.health, 1e-9);
        assertEquals(120, back.maxHealth, 1e-9);
        assertEquals(12, back.mana, 1e-9);
        assertEquals(33, back.stamina, 1e-9);
        assertEquals(0.42, back.ultCharge, 1e-9);
        assertEquals(2, back.selectedSlot);
        assertEquals(57, back.stats.get("blocks_mined"), 1e-9);
        assertEquals(3, back.stats.get("mobs_killed"), 1e-9);
        assertArrayEquals(new int[] {1, 0, 4}, back.firedCountsFor("cave"));
        assertEquals(0.78, back.timeOfDay, 1e-9);
        assertEquals(1234.5, back.playSeconds, 1e-9);
        assertTrue(back.savedAt > 0);

        // The carried items come back as items, not as a blob.
        Inventory restored = new Inventory(ItemRegistry.standard());
        back.applyTo(restored);
        assertEquals(12, restored.totalOf("stone"));
        assertEquals(3, restored.totalOf("bread"));
        assertEquals(2, restored.selectedIndex());
    }

    /**
     * A save from another build is a partial answer, never an error. This is
     * the rule that stops a save file from being a reason the game will not
     * start.
     */
    @Test
    void unknownKeysAreIgnoredAndMissingOnesFallBack() {
        RunRecord r = RunRecord.fromJson("""
                {
                  "levelName": "town",
                  "somethingFromTheFuture": {"nested": [1, 2, 3]},
                  "health": 30
                }
                """);
        assertEquals("town", r.levelName);
        assertEquals(30, r.health, 1e-9);
        assertEquals("", r.characterKey);
        assertEquals(Facing.EAST.name(), r.facing);
        assertTrue(r.stats.isEmpty());
        assertTrue(r.inventory.isEmpty());
    }

    /**
     * Restoring puts the saved pools back — and clamps them, because a creator
     * may have edited the character since the save was written and a body with
     * 300 health out of 100 is a broken HUD.
     */
    @Test
    void restoringPoolsClampsToTheCharactersCurrentMaxima() {
        RunRecord r = new RunRecord();
        r.health = 300;
        r.mana = 500;
        r.stamina = 45;
        r.ultCharge = 7;

        PlayerState p = new PlayerState(0, "", 0, 0);
        p.maxHealth = 100;
        p.maxMana = 50;
        p.maxStamina = 90;
        r.applyTo(p);

        assertEquals(100, p.health, 1e-9);
        assertEquals(50, p.mana, 1e-9);
        assertEquals(45, p.stamina, 1e-9);
        assertEquals(1, p.ultCharge, 1e-9, "the ultimate meter is a fraction");
    }

    /** A record with no pools saved (health = -1) leaves the body as it is. */
    @Test
    void anEmptyRecordDoesNotZeroTheBody() {
        PlayerState p = new PlayerState(0, "", 0, 0);
        p.maxHealth = 100;
        p.health = 100;
        new RunRecord().applyTo(p);
        assertEquals(100, p.health, 1e-9,
                "a record that never saved a health should not invent one");
    }

    /** A facing name this build does not have is east, not a crash. */
    @Test
    void anUnknownFacingIsEast() {
        RunRecord r = new RunRecord();
        r.facing = "NORTH_BY_NORTHWEST";
        PlayerState p = new PlayerState(0, "", 0, 0);
        r.applyTo(p);
        assertEquals(Facing.EAST, p.facing);
    }

    /** Counters are open-ended, so a stat this build does not track survives. */
    @Test
    void statsThisBuildDoesNotKnowAreCarriedThrough() {
        RunRecord r = new RunRecord();
        r.stats.put("gizmos_polished", 9.0);
        PlayerStats stats = new PlayerStats();
        r.applyTo(stats);
        assertEquals(9, stats.get("gizmos_polished"), 1e-9);
    }

    // --- the fire counts, which are the run's and not the level's ---------------

    /**
     * <b>The exploit, in miniature.</b> A one-shot rule that has already fired
     * must not fire again when the level is re-entered with the counters that
     * earned it still standing.
     *
     * <p>The engine used to build a {@link StatRuleEngine} per level entry with
     * its counts zeroed, while {@link PlayerStats} carried on across door
     * travel — so walking out of a level and back in re-armed every one-shot
     * reward in it. {@code DoorContinuityTest} proves the fix through the play
     * scene; this proves the mechanism it rests on.
     */
    @Test
    void aFiredOneShotRuleStaysFiredAcrossAReEntry() {
        List<StatRule> rules = List.of(
                new StatRule("blocks_mined", 50, "diamond", 1, null, 0, false, false));
        PlayerStats stats = new PlayerStats();
        stats.add("blocks_mined", 50);

        Inventory inv = new Inventory(ItemRegistry.standard());
        StatRuleEngine first = new StatRuleEngine(rules);
        assertEquals(1, first.update(stats, inv).size(), "it should pay out the first time");
        assertEquals(1, inv.totalOf("diamond"));

        // Leave the level: the run remembers what fired.
        RunRecord run = new RunRecord();
        run.putFiredCounts("cave", first.firedCounts());

        // Come back to it: a new engine, restored from the run.
        StatRuleEngine second = new StatRuleEngine(rules);
        second.restore(run.firedCountsFor("cave"));
        assertTrue(second.update(stats, inv).isEmpty(),
                "walking through a door and back must not re-arm a one-shot reward");
        assertEquals(1, inv.totalOf("diamond"), "…and must not pay out twice");
    }

    /** Repeating rules still repeat — the fix must not freeze them. */
    @Test
    void repeatingRulesStillFireOnEveryStep() {
        List<StatRule> rules = List.of(
                new StatRule("distance_traveled", 1000, "bread", 1, null, 0, true, false));
        PlayerStats stats = new PlayerStats();
        Inventory inv = new Inventory(ItemRegistry.standard());

        StatRuleEngine engine = new StatRuleEngine(rules);
        stats.add("distance_traveled", 1000);
        engine.update(stats, inv);
        assertEquals(1, inv.totalOf("bread"));

        StatRuleEngine after = new StatRuleEngine(rules);
        after.restore(engine.firedCounts());
        stats.add("distance_traveled", 1000); // 2000 total: the next step is due
        assertEquals(1, after.update(stats, inv).size());
        assertEquals(2, inv.totalOf("bread"));
    }

    /**
     * A level whose author added or removed a rule since the save still
     * restores what lines up, rather than re-arming everything.
     */
    @Test
    void countsFromALevelWhoseRulesChangedAreRestoredAsFarAsTheyGo() {
        List<StatRule> now = List.of(
                new StatRule("blocks_mined", 10, "stone", 1, null, 0, false, false),
                new StatRule("jumps", 5, "bread", 1, null, 0, false, false));
        StatRuleEngine engine = new StatRuleEngine(now);
        engine.restore(new int[] {3, 1, 9, 9, 9}); // a save with more rules than the level
        assertEquals(3, engine.firedCount(now.get(0)));
        assertEquals(1, engine.firedCount(now.get(1)));

        StatRuleEngine shorter = new StatRuleEngine(now);
        shorter.restore(new int[] {2}); // …and one with fewer
        assertEquals(2, shorter.firedCount(now.get(0)));
        assertEquals(0, shorter.firedCount(now.get(1)));
    }

    /** No counts for a level the run has never entered. */
    @Test
    void aLevelNeverEnteredHasNoCounts() {
        RunRecord r = new RunRecord();
        assertNull(r.firedCountsFor("nowhere"));
        assertNull(r.firedCountsFor(null));
    }

    /** The record hands out copies, so a caller cannot edit it by accident. */
    @Test
    void firedCountsAreCopiedInAndOut() {
        RunRecord r = new RunRecord();
        int[] mine = {1, 2, 3};
        r.putFiredCounts("cave", mine);
        mine[0] = 99;
        assertArrayEquals(new int[] {1, 2, 3}, r.firedCountsFor("cave"));

        int[] out = r.firedCountsFor("cave");
        out[0] = 77;
        assertArrayEquals(new int[] {1, 2, 3}, r.firedCountsFor("cave"));
    }

    /** Capture tolerates a run with nothing in it yet. */
    @Test
    void captureHandlesMissingPieces() {
        RunRecord r = RunRecord.capture(null, null, null, null, null, 0.25, 0);
        assertEquals("", r.gameType);
        assertEquals("", r.levelName);
        assertNotNull(r.inventory);
        assertTrue(r.stats.isEmpty());
        assertFalse(r.toJson().isBlank());
        assertEquals(0.25, RunRecord.fromJson(r.toJson()).timeOfDay, 1e-9);
    }

    /** Written as plain readable JSON, like every other store in the engine. */
    @Test
    void theFileIsReadable() {
        RunRecord r = RunRecord.capture("t", "cave", null, null, null, 0.5, 0);
        Map<String, Object> raw = com.larsons.engine.util.Json.asObject(
                com.larsons.engine.util.Json.parse(r.toJson()));
        assertEquals("cave", raw.get("levelName"));
    }
}
