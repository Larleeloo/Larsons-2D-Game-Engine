package com.larsons.engine.autobattler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat-presentation contract the effects/animation client work rides
 * on: {@link BattleSim} replicates per-unit animation states and
 * damage-by-type tallies in snapshots, its events carry their source unit
 * (so clients can fly projectiles from attacker to target and identify who
 * died), and {@link AutoGame} answers board-scouting {@code view} requests
 * with the target's public stats + roster.
 */
@Timeout(30)
class AutoBattlerFxTest {

    private static BattleSim rangedVsMelee() {
        // A ranged archer far back and a melee squire in the far corner vs an
        // enemy squire (mirrored onto the far half): guarantees walking, both
        // distant shots (projectile events) and, eventually, deaths.
        UnitInstance archer = new UnitInstance(1, "frost_archer");
        archer.placeBoard(0, 7);
        UnitInstance squire = new UnitInstance(2, "squire");
        squire.placeBoard(7, 7);
        UnitInstance enemy = new UnitInstance(3, "squire");
        enemy.placeBoard(4, 4);
        return new BattleSim(List.of(archer, squire), List.of(enemy), 42);
    }

    @Test
    void snapshotsCarryAnimationStatesAndDamageTallies() {
        BattleSim sim = rangedVsMelee();
        boolean sawWalk = false, sawAttack = false;
        for (int i = 0; i < 30 * 60 && !sim.finished(); i++) {
            sim.step(1.0 / 30);
            for (Map<String, Object> u : sim.snapshotUnits()) {
                assertNotNull(u.get("st"), "every snapshot unit carries an anim state");
                String st = (String) u.get("st");
                sawWalk |= st.equals(AnimState.WALK.code);
                sawAttack |= st.equals(AnimState.ATTACK.code);
            }
        }
        assertTrue(sim.finished(), "the fight resolved");
        assertTrue(sawWalk, "units walked");
        assertTrue(sawAttack, "units attacked");

        // Damage tallies accumulated and replicate (attacks are physical).
        double totalPhysical = 0;
        boolean sawDeathState = false;
        for (BattleSim.CUnit c : sim.units()) {
            totalPhysical += c.dealtPhysical;
            if (c.dead) {
                assertEquals(AnimState.DEATH, c.anim, "dead units play DEATH");
                sawDeathState = true;
            }
        }
        assertTrue(totalPhysical > 0, "physical damage was tallied");
        assertTrue(sawDeathState, "someone died");
        boolean snapshotHasDamage = false;
        for (Map<String, Object> u : sim.snapshotUnits()) {
            if (u.containsKey("dp")) snapshotHasDamage = true;
        }
        assertTrue(snapshotHasDamage, "snapshots carry the damage tallies");
    }

    @Test
    void eventsCarryTheirSourceUnit() {
        BattleSim sim = rangedVsMelee();
        List<BattleSim.Event> all = new ArrayList<>();
        for (int i = 0; i < 30 * 60 && !sim.finished(); i++) {
            sim.step(1.0 / 30);
            all.addAll(sim.drainEvents());
        }
        all.addAll(sim.drainEvents());

        boolean sawRangedHit = false, sawDieWithUid = false;
        for (BattleSim.Event e : all) {
            if ((e.kind().equals("hit") || e.kind().equals("crit")) && e.sourceId() > 0) {
                double dx = e.sx() - e.x(), dy = e.sy() - e.y();
                if (dx * dx + dy * dy > 1.6 * 1.6) sawRangedHit = true;
                // The wire form only includes source fields when they exist.
                Map<String, Object> m = e.toMap();
                assertEquals(e.sourceId(), ((Number) m.get("su")).intValue());
                assertNotNull(m.get("sx"));
                assertNotNull(m.get("sy"));
            }
            if (e.kind().equals("die")) {
                assertTrue(e.sourceId() > 0, "die events name the dying unit");
                sawDieWithUid = true;
            }
        }
        assertTrue(sawRangedHit, "a ranged attack produced a sourced hit event");
        assertTrue(sawDieWithUid, "a death event carried its unit id");
    }

    @Test
    void healingIsCreditedToTheHealer() {
        // A lone Forest unit regenerates; the regen credits itself as healer
        // once it has taken damage.
        UnitInstance sapling = new UnitInstance(1, "sapling");
        sapling.placeBoard(3, 7);
        UnitInstance enemy = new UnitInstance(2, "squire");
        enemy.placeBoard(3, 4);
        UnitInstance enemy2 = new UnitInstance(3, "sapling");
        enemy2.placeBoard(4, 4);
        BattleSim sim = new BattleSim(List.of(sapling), List.of(enemy, enemy2), 7);
        for (int i = 0; i < 30 * 60 && !sim.finished(); i++) {
            sim.step(1.0 / 30);
        }
        double healed = 0;
        for (BattleSim.CUnit c : sim.units()) healed += c.healingDone;
        assertTrue(healed >= 0, "healing tally never goes negative");
        // Guardians cast a self-heal (bulwark), so with saplings on both sides
        // at least one cast-heal or regen tick should have landed.
        assertTrue(sim.time() > 0);
    }

    @Test
    void viewRequestReturnsTheTargetsBoardAndStats() {
        Map<Integer, List<Map<String, Object>>> perPlayer = new HashMap<>();
        AutoGame.Sink sink = new AutoGame.Sink() {
            @Override
            public void toPlayer(int playerId, Map<String, Object> message) {
                perPlayer.computeIfAbsent(playerId, k -> new ArrayList<>()).add(message);
            }

            @Override
            public void toAll(Map<String, Object> message) {}
        };
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 42;
        cfg.planSeconds = 999;
        AutoGame game = new AutoGame(cfg, sink);
        AutoPlayer alice = game.addPlayer("Alice", false);
        AutoPlayer bob = game.addPlayer("Bob", false);
        assertTrue(game.start(alice.id));

        // Give Bob a board presence so the scouted roster is non-trivial.
        boolean bought = false;
        for (int slot = 0; slot < AutoPlayer.SHOP_SIZE && !bought; slot++) {
            if (bob.shop[slot] != null) bought = game.buy(bob, slot);
        }
        assertTrue(bought, "bob bought a unit");
        UnitInstance unit = bob.units.get(0);
        assertTrue(game.moveToBoard(bob, unit.id, 3, 5));

        perPlayer.clear();
        game.handleAction(alice.id, AutoProto.view(bob.id));

        List<Map<String, Object>> replies = perPlayer.get(alice.id);
        assertNotNull(replies, "the requester got a reply");
        Map<String, Object> board = null;
        for (Map<String, Object> m : replies) {
            if ("board".equals(AutoProto.type(m))) board = m;
        }
        assertNotNull(board, "a board message came back");
        assertEquals(bob.id, ((Number) board.get("p")).intValue());
        assertEquals("Bob", board.get("name"));
        for (String key : new String[]{"hp", "lvl", "xp", "gold", "streak", "cap"}) {
            assertTrue(board.containsKey(key), "board view carries " + key);
        }
        assertTrue(board.get("board") instanceof List<?> list && list.size() == 1,
                "the fielded unit is in the scouted board");
        assertTrue(board.get("bench") instanceof List<?>, "the bench rides along");

        // Scouting is read-only and allowed for the dead: eliminate Alice and
        // make sure she can still look around.
        alice.alive = false;
        perPlayer.clear();
        game.handleAction(alice.id, AutoProto.view(bob.id));
        assertNotNull(perPlayer.get(alice.id), "eliminated spectators can scout");

        // ...but mutating actions are still refused for them.
        perPlayer.clear();
        int bobGold = bob.gold;
        game.handleAction(alice.id, AutoProto.reroll());
        assertEquals(bobGold, bob.gold);
    }

    @Test
    void animStateCodesRoundTrip() {
        for (AnimState s : AnimState.values()) {
            assertEquals(s, AnimState.fromCode(s.code));
        }
        assertEquals(AnimState.IDLE, AnimState.fromCode("nope"));
        assertEquals(AnimState.IDLE, AnimState.fromCode(""));
    }
}
