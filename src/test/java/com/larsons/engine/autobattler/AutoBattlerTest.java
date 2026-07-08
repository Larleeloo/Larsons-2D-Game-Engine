package com.larsons.engine.autobattler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the auto-battler's data and rules: registry integrity,
 * the shared pool + shop odds, buying/combining/selling, item combining,
 * placement rules, the economy, and the deterministic battle simulation.
 */
@Timeout(30)
class AutoBattlerTest {

    /** A sink that counts messages and remembers per-player pushes for asserts. */
    private static final class CapturingSink implements AutoGame.Sink {
        final AtomicInteger total = new AtomicInteger();
        volatile Map<String, Object> lastBroadcast;

        @Override
        public void toPlayer(int playerId, Map<String, Object> message) {
            total.incrementAndGet();
        }

        @Override
        public void toAll(Map<String, Object> message) {
            total.incrementAndGet();
            lastBroadcast = message;
        }
    }

    private static AutoGame newStartedGame(AutoPlayer[] out) {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 42;
        cfg.planSeconds = 999; // stay in PLAN so tests can act freely
        AutoGame game = new AutoGame(cfg, new CapturingSink());
        AutoPlayer a = game.addPlayer("Alice", false);
        AutoPlayer b = game.addPlayer("Bob", false);
        assertTrue(game.start(a.id));
        out[0] = a;
        out[1] = b;
        return game;
    }

    // --- registries -----------------------------------------------------------------

    @Test
    void rosterUnitsAreWellFormed() {
        List<UnitDef> roster = AutoUnits.roster();
        assertTrue(roster.size() >= 20, "a real roster to build teams from");
        for (UnitDef d : roster) {
            assertTrue(d.cost >= 1 && d.cost <= 5, d.key + " cost in 1..5");
            assertNotNull(d.origin, d.key + " has an origin");
            assertNotNull(d.clazz, d.key + " has a class");
            assertEquals(Trait.Kind.ORIGIN, d.origin.kind, d.key);
            assertEquals(Trait.Kind.CLASS, d.clazz.kind, d.key);
            assertTrue(d.hp > 0 && d.ad > 0 && d.attackSpeed > 0, d.key + " has stats");
            assertTrue(d.range >= 1, d.key + " has range");
        }
        // Every cost tier is populated (shop odds reference all five).
        for (int cost = 1; cost <= 5; cost++) {
            assertFalse(AutoUnits.byCost(cost).isEmpty(), "cost " + cost + " units exist");
        }
        // Shop odds cover levels 1-9 and each row is a full distribution.
        assertEquals(9, AutoUnits.SHOP_ODDS.length);
        for (int lvl = 0; lvl < 9; lvl++) {
            int sum = 0;
            for (int pct : AutoUnits.SHOP_ODDS[lvl]) sum += pct;
            assertEquals(100, sum, "level " + (lvl + 1) + " odds sum to 100");
        }
    }

    @Test
    void traitsAreReachableAndOrdered() {
        for (Trait t : Trait.values()) {
            assertEquals(t.thresholds.length, t.values.length, t.name());
            for (int i = 1; i < t.thresholds.length; i++) {
                assertTrue(t.thresholds[i] > t.thresholds[i - 1], t.name() + " ascending");
            }
            // The top tier must be reachable with the units that exist.
            int carriers = 0;
            for (UnitDef d : AutoUnits.roster()) {
                if (d.origin == t || d.clazz == t) carriers++;
            }
            assertTrue(carriers >= t.thresholds[t.thresholds.length - 1],
                    t.name() + " top tier reachable (" + carriers + " carriers)");
        }
        assertEquals(0, Trait.FOREST.tier(1));
        assertEquals(1, Trait.FOREST.tier(2));
        assertEquals(2, Trait.FOREST.tier(3));
    }

    @Test
    void everyComponentPairCombines() {
        List<String> comps = AutoItems.componentKeys();
        assertEquals(5, comps.size());
        for (int i = 0; i < comps.size(); i++) {
            for (int j = i; j < comps.size(); j++) {
                String combined = AutoItems.combine(comps.get(i), comps.get(j));
                assertNotNull(combined, comps.get(i) + "+" + comps.get(j));
                AutoItem item = AutoItems.get(combined);
                assertNotNull(item);
                assertFalse(item.isComponent());
                assertFalse(item.statLine().isEmpty(), combined + " has stats");
            }
        }
        assertNull(AutoItems.combine("deathblade", "sword"),
                "combined items don't combine further");
    }

    // --- pool & shop -----------------------------------------------------------------

    @Test
    void shopRespectsLevelOddsAndPoolScarcity() {
        UnitPool pool = new UnitPool();
        Random rng = new Random(7);
        int before = pool.tierRemaining(1);
        List<String> shop = pool.rollShop(1, 5, rng);
        assertEquals(5, shop.size());
        for (String key : shop) {
            assertNotNull(key);
            assertEquals(1, AutoUnits.get(key).cost, "level 1 shops only roll cost 1");
        }
        assertEquals(before - 5, pool.tierRemaining(1), "rolls consume pool copies");

        // Drain a unit and confirm it stops appearing.
        String drained = shop.get(0);
        while (pool.remaining(drained) > 0) pool.take(drained);
        for (int i = 0; i < 200; i++) {
            for (String key : pool.rollShop(1, 5, rng)) {
                assertFalse(drained.equals(key), "drained units can't be rolled");
                if (key != null) pool.giveBack(key, 1);
            }
        }
    }

    // --- buying, combining, selling, placement -----------------------------------------

    @Test
    void buyingThreeCopiesCombinesToTwoStar() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 30;
        for (int i = 0; i < 3; i++) {
            alice.shop[0] = "squire";
            assertTrue(game.buy(alice, 0));
        }
        assertEquals(1, alice.units.size(), "three 1-stars merged");
        assertEquals(2, alice.units.get(0).star);
        assertEquals(27, alice.gold, "three 1-cost buys");

        // Selling a 2-star refunds its full copy value and returns 3 copies.
        int poolBefore = game.pool().remaining("squire");
        assertTrue(game.sell(alice, alice.units.get(0).id));
        assertEquals(30, alice.gold);
        assertEquals(poolBefore + 3, game.pool().remaining("squire"));
        assertTrue(alice.units.isEmpty());
    }

    @Test
    void componentsCombineOnAUnit() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 10;
        alice.shop[0] = "squire";
        assertTrue(game.buy(alice, 0));
        UnitInstance unit = alice.units.get(0);

        alice.items.add(AutoItems.SWORD);
        alice.items.add(AutoItems.SWORD);
        assertTrue(game.equip(alice, 0, unit.id));
        assertEquals(List.of("sword"), unit.items);
        assertTrue(game.equip(alice, 0, unit.id));
        assertEquals(List.of("deathblade"), unit.items, "two swords fuse");
        assertTrue(alice.items.isEmpty());
    }

    @Test
    void placementIsRestrictedToOwnHalfAndLevelCap() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0]; // starts level 3
        alice.gold = 30;
        for (int i = 0; i < 4; i++) {
            alice.shop[0] = i % 2 == 0 ? "sapling" : "frost_archer";
            assertTrue(game.buy(alice, 0));
        }
        List<UnitInstance> bench = alice.benchUnits();
        assertFalse(game.moveToBoard(alice, bench.get(0).id, 3, 2),
                "enemy half is off limits");
        assertTrue(game.moveToBoard(alice, bench.get(0).id, 3, 4));
        assertTrue(game.moveToBoard(alice, bench.get(1).id, 4, 4));
        assertTrue(game.moveToBoard(alice, bench.get(2).id, 5, 4));
        assertFalse(game.moveToBoard(alice, bench.get(3).id, 6, 4),
                "level 3 fields at most 3 units");
        assertEquals(3, alice.boardCount());

        // Swapping two fielded units is always allowed.
        UnitInstance first = alice.boardAt(3, 4);
        assertTrue(game.moveToBoard(alice, first.id, 4, 4));
        assertEquals(first, alice.boardAt(4, 4));
    }

    @Test
    void economyGrantsInterestStreaksAndXp() {
        AutoPlayer p = new AutoPlayer(1, "Eco", false);
        p.gold = 34;
        p.streak = -4; // loss streaks pay too
        assertEquals(5 + 3 + 2, p.income(), "base 5 + interest 3 + streak 2");
        p.wonLastRound = true;
        assertEquals(5 + 3 + 2 + 1, p.income());

        p.level = 3;
        p.xp = 0;
        p.gainXp(10);
        assertEquals(4, p.level, "10 xp climbs from 3 to 4");
        assertEquals(0, p.xp);
    }

    // --- battle simulation ---------------------------------------------------------------

    private static List<UnitInstance> board(int startId, int row, String... keys) {
        List<UnitInstance> out = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            UnitInstance u = new UnitInstance(startId + i, keys[i]);
            u.placeBoard(2 + i, row);
            out.add(u);
        }
        return out;
    }

    private static int runToCompletion(BattleSim sim) {
        int steps = 0;
        while (!sim.finished() && steps++ < 60 * 120) {
            sim.step(1.0 / 30);
        }
        return steps;
    }

    @Test
    void battleIsDeterministicForASeed() {
        for (long seed : new long[]{1, 99, 12345}) {
            BattleSim a = new BattleSim(board(1, 5, "squire", "ember_imp", "frost_archer"),
                    board(10, 5, "sapling", "shade_rat", "storm_pugilist"), seed);
            BattleSim b = new BattleSim(board(1, 5, "squire", "ember_imp", "frost_archer"),
                    board(10, 5, "sapling", "shade_rat", "storm_pugilist"), seed);
            runToCompletion(a);
            runToCompletion(b);
            assertTrue(a.finished() && b.finished());
            assertEquals(a.winner(), b.winner(), "same seed, same winner");
            assertEquals(a.survivorStars(a.winner() == BattleSim.HOME ? 0 : 1),
                    b.survivorStars(b.winner() == BattleSim.HOME ? 0 : 1));
            assertEquals(a.time(), b.time(), 1e-9, "same seed, same duration");
        }
    }

    @Test
    void strongerBoardWinsAndSurvivorsScaleDamage() {
        List<UnitInstance> strong = board(1, 5, "ember_knight", "dawn_paladin", "frost_colossus");
        for (UnitInstance u : strong) u.star = 2;
        BattleSim sim = new BattleSim(strong, board(10, 5, "squire"), 7);
        runToCompletion(sim);
        assertEquals(BattleSim.HOME, sim.winner());
        assertTrue(sim.survivorStars(BattleSim.HOME) >= 4,
                "2-star survivors count double toward damage");
        assertEquals(0, sim.livingCount(BattleSim.AWAY));
    }

    @Test
    void emptyBoardLosesImmediately() {
        BattleSim sim = new BattleSim(List.of(), board(1, 5, "creep_slime"), 3);
        sim.step(1.0 / 30);
        assertTrue(sim.finished());
        assertEquals(BattleSim.AWAY, sim.winner());
    }

    @Test
    void traitCountingIsPerDistinctSpecies() {
        List<UnitInstance> team = board(1, 5, "squire", "squire", "cleric", "dawn_paladin");
        Map<Trait, Integer> counts = BattleSim.countTraits(team);
        assertEquals(3, counts.get(Trait.HOLY), "duplicate squires count once");
        assertEquals(1, counts.get(Trait.WARRIOR).intValue());
    }

    @Test
    void combatSnapshotsCarryUnitsAndEvents() {
        BattleSim sim = new BattleSim(board(1, 4, "squire"), board(10, 4, "creep_slime"), 5);
        runToCompletion(sim);
        List<Map<String, Object>> units = sim.snapshotUnits();
        assertEquals(2, units.size());
        for (Map<String, Object> u : units) {
            assertTrue(u.containsKey("k") && u.containsKey("x") && u.containsKey("hp"));
        }
        assertFalse(sim.drainEvents().isEmpty(), "hits and a death were recorded");
        assertTrue(sim.drainEvents().isEmpty(), "drain empties the queue");
    }
}
