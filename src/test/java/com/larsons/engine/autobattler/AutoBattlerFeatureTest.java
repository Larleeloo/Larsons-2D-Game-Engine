package com.larsons.engine.autobattler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The newer auto-battler features: the Dota Auto Chess opening (level 1,
 * 1 gold), the shop lock, once-per-round item removal, one-click board
 * arrangement, bench-full combining, the Wisp's Io-style fusing, the player
 * relic system and each relic's effect, the expanded creep bestiary, and the
 * per-element damage tallies the damage panel's breakdown bars ride on.
 */
@Timeout(60)
class AutoBattlerFeatureTest {

    private static final class NullSink implements AutoGame.Sink {
        @Override
        public void toPlayer(int playerId, Map<String, Object> message) {}

        @Override
        public void toAll(Map<String, Object> message) {}
    }

    private static AutoGame newStartedGame(AutoPlayer[] out) {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 42;
        cfg.planSeconds = 999; // stay in PLAN so tests can act freely
        AutoGame game = new AutoGame(cfg, new NullSink());
        AutoPlayer a = game.addPlayer("Alice", false);
        AutoPlayer b = game.addPlayer("Bob", false);
        assertTrue(game.start(a.id));
        // Round 1 deals random relics; neutralize them so each test grants
        // exactly the relic it is about.
        for (AutoPlayer pl : game.players()) {
            pl.relic = null;
            pl.relicFired = false;
            pl.traitBoosts.clear();
            Arrays.fill(pl.shopDeals, 0);
        }
        out[0] = a;
        out[1] = b;
        return game;
    }

    private static UnitInstance buyKey(AutoGame game, AutoPlayer p, String key) {
        p.shop[0] = key;
        p.shopDeals[0] = 0;
        int before = p.units.size();
        assertTrue(game.buy(p, 0), "bought " + key);
        for (UnitInstance u : p.units) {
            if (u.key.equals(key)) return u;
        }
        // The buy merged instantly (e.g. Apprentice pairs); return the upgrade.
        assertTrue(p.units.size() <= before, "a combine consumed the copy");
        return null;
    }

    // --- the Dota Auto Chess opening -------------------------------------------------

    @Test
    void gamesOpenAtLevelOneWithOneGold() {
        AutoPlayer[] p = new AutoPlayer[2];
        newStartedGame(p);
        assertEquals(1, p[0].level, "start at level 1");
        assertEquals(1, p[0].gold, "start with 1 gold");
        assertEquals(1, p[0].boardCap(), "one unit fieldable at level 1");
        // The round-1 shop is affordable: level 1 rolls only cost-1 units.
        for (String key : p[0].shop) {
            assertNotNull(key);
            assertEquals(1, AutoUnits.get(key).cost);
        }
    }

    // --- shop lock & round flow -------------------------------------------------------

    @Test
    void lockedShopsSurviveTheRoundAndUnequipResets() {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 9;
        cfg.planSeconds = 0.05;
        cfg.fightMaxSeconds = 5;
        cfg.postSeconds = 0.05;
        AutoGame game = new AutoGame(cfg, new NullSink());
        AutoPlayer alice = game.addPlayer("Alice", false);
        AutoPlayer bob = game.addPlayer("Bob", false);
        assertTrue(game.start(alice.id));

        assertTrue(game.toggleLock(alice));
        assertTrue(alice.shopLocked);
        String[] kept = alice.shop.clone();
        alice.unequipUsed = true; // pretend the removal was spent this round

        double dt = 1.0 / 30;
        int guard = 30 * 60;
        while (!(game.round() == 2 && game.phase() == AutoGame.Phase.PLAN)
                && guard-- > 0) {
            game.tick(dt);
        }
        assertEquals(2, game.round(), "reached round 2");
        assertArrayEqualsish(kept, alice.shop);
        assertTrue(alice.shopLocked, "the lock persists until toggled off");
        assertFalse(alice.unequipUsed, "item removal refreshes each round");

        assertTrue(game.toggleLock(alice));
        assertFalse(alice.shopLocked);
        assertNotNull(bob); // bob just plays along
    }

    private static void assertArrayEqualsish(String[] expected, String[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "locked shop slot " + i);
        }
    }

    // --- once-per-round item removal ---------------------------------------------------

    @Test
    void itemRemovalWorksOncePerRound() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 20;
        UnitInstance armed = buyKey(game, alice, "squire");
        UnitInstance other = buyKey(game, alice, "cleric");
        armed.items.add(AutoItems.SWORD);
        armed.items.add(AutoItems.BOW);
        other.items.add(AutoItems.ROD);

        assertTrue(game.unequip(alice, armed.id));
        assertTrue(armed.items.isEmpty(), "all items came off");
        assertTrue(alice.items.containsAll(List.of(AutoItems.SWORD, AutoItems.BOW)));
        assertTrue(alice.unequipUsed);

        assertFalse(game.unequip(alice, other.id), "only once per round");
        assertEquals(List.of(AutoItems.ROD), other.items);
    }

    // --- bench-full combining ----------------------------------------------------------

    @Test
    void aFullBenchStillAcceptsTheCombiningCopy() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 99;
        buyKey(game, alice, "squire");
        buyKey(game, alice, "squire");
        String[] fillers = {"sapling", "ember_imp", "frost_archer", "storm_pugilist",
                "shade_rat", "cog_pup", "moss_shellback"};
        for (String filler : fillers) buyKey(game, alice, filler);
        assertEquals(-1, alice.freeBenchSlot(), "bench is full");

        // A non-combining unit is still refused...
        alice.shop[0] = "coin_page";
        assertFalse(game.buy(alice, 0));

        // ...but the third squire merges straight into a 2-star.
        alice.shop[0] = "squire";
        assertTrue(game.buy(alice, 0), "combining copy accepted");
        UnitInstance squire = null;
        for (UnitInstance u : alice.units) {
            if (u.key.equals("squire")) squire = u;
            assertTrue(u.bench < AutoPlayer.BENCH_SIZE || u.onBoard(),
                    "no unit lingers in the overflow slot");
        }
        assertNotNull(squire);
        assertEquals(2, squire.star);
    }

    // --- the Wisp (Io) -----------------------------------------------------------------

    @Test
    void wispFusesIntoAPairAsTheMissingCopy() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 99;
        UnitInstance a = buyKey(game, alice, "squire");
        buyKey(game, alice, "squire");
        UnitInstance lone = buyKey(game, alice, "cleric");
        UnitInstance wisp = buyKey(game, alice, AutoUnits.WISP);
        assertNotNull(wisp);

        int wispPool = game.pool().remaining(AutoUnits.WISP);
        assertFalse(game.fuse(alice, wisp.id, lone.id), "a lone unit has no pair");
        assertTrue(game.fuse(alice, wisp.id, a.id), "a pair + wisp upgrades");

        UnitInstance squire = null;
        for (UnitInstance u : alice.units) {
            assertFalse(AutoUnits.WISP.equals(u.key), "the wisp was consumed");
            if (u.key.equals("squire")) squire = u;
        }
        assertNotNull(squire);
        assertEquals(2, squire.star, "the pair reached 2 stars");
        assertEquals(wispPool + 1, game.pool().remaining(AutoUnits.WISP),
                "the wisp copy went back to the pool");
    }

    // --- relics --------------------------------------------------------------------------

    @Test
    void relicsAreDealtOnRoundOneAndRedealtEveryTenRounds() {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 31;
        cfg.planSeconds = 0.05;
        cfg.fightMaxSeconds = 5;
        cfg.postSeconds = 0.05;
        AutoGame game = new AutoGame(cfg, new NullSink());
        AutoPlayer alice = game.addPlayer("Alice", false);
        game.addPlayer("Bob", false);
        assertTrue(game.start(alice.id));

        Relic first = alice.relic;
        assertNotNull(first, "a relic is dealt on round 1");

        double dt = 1.0 / 30;
        int guard = 30 * 60 * 5;
        while (!(game.round() == AutoGame.RELIC_INTERVAL + 1
                && game.phase() == AutoGame.Phase.PLAN) && guard-- > 0) {
            game.tick(dt);
            if (game.phase() == AutoGame.Phase.OVER) break;
        }
        assertEquals(AutoGame.RELIC_INTERVAL + 1, game.round(), "reached round 11");
        assertNotNull(alice.relic);
        assertNotEquals(first, alice.relic, "the re-deal always changes the relic");
    }

    @Test
    void menderHealsAndZealotBoostsASynergyPermanently() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];

        alice.hp = 50;
        game.grantRelic(alice, Relic.MENDER);
        assertTrue(alice.hp >= 65 && alice.hp <= 70, "healed 15-20, got " + alice.hp);

        game.grantRelic(alice, Relic.ZEALOT);
        assertEquals(1, alice.traitBoosts.size());
        for (double boost : alice.traitBoosts.values()) {
            assertEquals(Relic.ZEALOT_BOOST, boost, 1e-9);
        }
        // The boost is permanent: swapping the relic away keeps it.
        game.grantRelic(alice, Relic.MENDER);
        assertEquals(1, alice.traitBoosts.size());
    }

    @Test
    void harmonizerGrantsABonusTraitThatCounts() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 20;
        alice.level = 2;
        UnitInstance squire = buyKey(game, alice, "squire");
        UnitInstance imp = buyKey(game, alice, "ember_imp");
        assertTrue(game.moveToBoard(alice, squire.id, 3, 4));
        assertTrue(game.moveToBoard(alice, imp.id, 4, 4));

        game.grantRelic(alice, Relic.HARMONIZER);
        UnitInstance attuned = squire.bonusTrait != null ? squire
                : imp.bonusTrait != null ? imp : null;
        assertNotNull(attuned, "one fielded unit was attuned");

        Map<Trait, Integer> counts = BattleSim.countTraits(alice.boardUnits());
        assertTrue(counts.getOrDefault(attuned.bonusTrait, 0) >= 1,
                "the bonus trait joins the synergy count");
    }

    @Test
    void apprenticePairsTwoStarUpgrades() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 20;
        game.grantRelic(alice, Relic.APPRENTICE);
        buyKey(game, alice, "squire");
        buyKey(game, alice, "squire");
        assertEquals(1, alice.units.size(), "two copies merged");
        assertEquals(2, alice.units.get(0).star);
    }

    @Test
    void fenceTriplesUpgradedSellPricesAndDiscountsRefundWhatWasPaid() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 99;

        // A discounted 3-cost unit refunds what was actually paid.
        alice.shop[0] = "dusk_stalker";
        alice.shopDeals[0] = 3;
        int before = alice.gold;
        assertTrue(game.buy(alice, 0));
        assertEquals(before, alice.gold, "3 - 3 = free");
        UnitInstance stalker = alice.units.get(alice.units.size() - 1);
        assertEquals(0, stalker.paid);
        assertEquals(0, game.sellValue(alice, stalker), "no discount-flip profit");

        // Fence: upgraded units sell for triple.
        buyKey(game, alice, "squire");
        buyKey(game, alice, "squire");
        buyKey(game, alice, "squire");
        UnitInstance twoStar = null;
        for (UnitInstance u : alice.units) {
            if (u.key.equals("squire")) twoStar = u;
        }
        assertNotNull(twoStar);
        assertEquals(2, twoStar.star);
        int plain = game.sellValue(alice, twoStar);
        game.grantRelic(alice, Relic.FENCE);
        assertEquals(plain * 3, game.sellValue(alice, twoStar));
    }

    @Test
    void bargainerDiscountsShopSlots() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        game.grantRelic(alice, Relic.BARGAINER);
        alice.gold = 500;
        boolean discounted = false;
        for (int i = 0; i < 40 && !discounted; i++) {
            assertTrue(game.reroll(alice));
            for (int deal : alice.shopDeals) discounted |= deal > 0;
        }
        assertTrue(discounted, "some slots get the 3-gold discount");
    }

    @Test
    void plundererStealsGoldEqualToDamage() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        AutoPlayer bob = p[1];
        alice.gold = 10;
        bob.gold = 10;

        game.plunder(alice, bob, 6);
        assertEquals(10, alice.gold, "no relic, no steal");

        game.grantRelic(alice, Relic.PLUNDERER);
        game.plunder(alice, bob, 6);
        assertEquals(16, alice.gold);
        assertEquals(4, bob.gold);

        game.plunder(alice, bob, 99);
        assertEquals(20, alice.gold, "capped at what the loser has");
        assertEquals(0, bob.gold);
    }

    @Test
    void collectorBiasFavoursOwnedUnits() {
        Random rng = new Random(3);
        int plain = 0, biased = 0;
        UnitPool a = new UnitPool();
        UnitPool b = new UnitPool();
        for (int i = 0; i < 300; i++) {
            for (String key : a.rollShop(1, 5, rng)) {
                if ("squire".equals(key)) plain++;
                if (key != null) a.giveBack(key, 1);
            }
            for (String key : b.rollShop(1, 5, rng, Set.of("squire"))) {
                if ("squire".equals(key)) biased++;
                if (key != null) b.giveBack(key, 1);
            }
        }
        assertTrue(biased > plain * 2,
                "owned units roll far more often (" + biased + " vs " + plain + ")");
    }

    @Test
    void voidBrandDamageAmpSwingsAMirrorMatch() {
        List<UnitInstance> home = List.of(fielded(1, "squire", 3, 4));
        List<UnitInstance> away = List.of(fielded(2, "squire", 4, 4));
        BattleSim.TeamMods amped =
                new BattleSim.TeamMods(Relic.VOID_BRAND_AMP, Map.of());
        BattleSim sim = new BattleSim(home, away, 12345, 5, amped,
                BattleSim.TeamMods.NONE);
        int steps = 0;
        while (!sim.finished() && steps++ < 30 * 120) sim.step(1.0 / 30);
        assertEquals(BattleSim.HOME, sim.winner(),
                "+15% damage decides an otherwise mirrored fight");
    }

    private static UnitInstance fielded(int id, String key, int col, int row) {
        UnitInstance u = new UnitInstance(id, key);
        u.placeBoard(col, row);
        return u;
    }

    // --- board arrangement ----------------------------------------------------------------

    @Test
    void arrangeGathersSpreadsAndFlips() {
        AutoPlayer[] p = new AutoPlayer[2];
        AutoGame game = newStartedGame(p);
        AutoPlayer alice = p[0];
        alice.gold = 99;
        alice.level = 6;
        String[] keys = {"squire", "moss_shellback", "frost_archer", "guild_broker"};
        List<UnitInstance> units = new ArrayList<>();
        int col = 0;
        for (String key : keys) {
            UnitInstance u = buyKey(game, alice, key);
            assertTrue(game.moveToBoard(alice, u.id, col++, 7));
            units.add(u);
        }

        assertTrue(game.arrange(alice, "front"));
        Set<String> cells = new HashSet<>();
        for (UnitInstance u : units) {
            assertTrue(u.onBoard());
            assertTrue(u.row >= BattleSim.ROWS / 2, "stays on the own half");
            assertTrue(cells.add(u.col + "," + u.row), "no stacking");
        }
        for (UnitInstance u : units) {
            if (u.def().melee()) {
                assertEquals(BattleSim.ROWS / 2, u.row, "melee lead from the front row");
            }
        }

        assertTrue(game.arrange(alice, "spread"));
        cells.clear();
        for (UnitInstance u : units) {
            assertTrue(u.row >= BattleSim.ROWS / 2);
            assertEquals(0, (u.col + u.row) % 2, "spread uses checkerboard cells");
            assertTrue(cells.add(u.col + "," + u.row), "no stacking");
        }

        int[] beforeCols = units.stream().mapToInt(u -> u.col).toArray();
        assertTrue(game.arrange(alice, "flip"));
        for (int i = 0; i < units.size(); i++) {
            assertEquals(BattleSim.COLS - 1 - beforeCols[i], units.get(i).col,
                    "columns mirror");
        }
        assertFalse(game.arrange(alice, "nonsense"));
    }

    // --- creeps ---------------------------------------------------------------------------

    @Test
    void creepWavesAreVariedAndRandomWavesStayStageAppropriate() {
        Set<String> allSpecies = new HashSet<>();
        for (int round : new int[]{1, 5, 10, 15, 20, 25}) {
            List<String> wave = AutoUnits.creepWave(round);
            assertFalse(wave.isEmpty());
            for (String key : wave) {
                UnitDef def = AutoUnits.get(key);
                assertNotNull(def, key + " is registered");
                assertTrue(def.isCreep(), key + " is a creep");
                allSpecies.add(key);
            }
            if (round >= 5) {
                assertTrue(new HashSet<>(wave).size() >= 2,
                        "round " + round + " mixes species");
            }
        }
        assertTrue(allSpecies.size() >= 8, "a real bestiary: " + allSpecies);

        Random rng = new Random(7);
        for (int round : new int[]{1, 5, 10, 15, 20}) {
            List<String> wave = AutoUnits.randomCreepWave(round, rng);
            assertEquals(AutoUnits.creepWave(round).size() + 1, wave.size(),
                    "wild hunts add one extra creep");
            List<String> pool = AutoUnits.creepPool(round);
            for (String key : wave) {
                assertTrue(pool.contains(key), key + " fits round " + round);
            }
        }
    }

    // --- elemental damage breakdown ----------------------------------------------------------

    @Test
    void snapshotsCarryPerElementDamageTallies() {
        UnitInstance imp = fielded(1, "ember_imp", 3, 5); // fire attacker
        UnitInstance slime = fielded(2, "creep_slime", 3, 4);
        BattleSim sim = new BattleSim(List.of(imp), List.of(slime), 5, 3);
        int steps = 0;
        while (!sim.finished() && steps++ < 30 * 120) sim.step(1.0 / 30);
        assertEquals(BattleSim.HOME, sim.winner());

        boolean sawFireTally = false;
        for (Map<String, Object> u : sim.snapshotUnits()) {
            if (u.get("de") instanceof Map<?, ?> byElement) {
                sawFireTally |= byElement.containsKey(Element.FIRE.code);
            }
        }
        assertTrue(sawFireTally, "the fire attacker's tally replicates per element");
    }

    @Test
    void relicKeysRoundTripOnTheWire() {
        for (Relic r : Relic.values()) {
            assertEquals(r, Relic.fromKey(r.key));
            assertFalse(r.description.isEmpty());
        }
        assertEquals(null, Relic.fromKey("nope"));
        // roll() never returns the excluded relic.
        Random rng = new Random(1);
        for (int i = 0; i < 50; i++) {
            assertNotEquals(Relic.MENDER, Relic.roll(rng, Relic.MENDER));
        }
    }
}
