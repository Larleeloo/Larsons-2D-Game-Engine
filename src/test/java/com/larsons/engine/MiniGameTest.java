package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.minigame.Team;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mini-game system: configuration that saves inside levels, team
 * assignment, the four modes' objective logic (CTF flags, Stockpile deposits,
 * Battle kills, the Escort payload), PvP through the world simulation, round
 * resets, and the replicated wire state — plus a real online session whose
 * server referees a CTF level for two connected clients.
 */
@Timeout(30)
class MiniGameTest {

    private static final int TS = 32;

    /** A flat arena with CTF flag bases + team spawns painted like creative would. */
    private static Level ctfLevel() {
        Level lvl = Level.empty("CTF Arena", 60, 20, TS);
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_FLAG, "team1", 100, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_FLAG, "team2", 1000, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_SPAWN, "team1", 140, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_SPAWN, "team2", 960, 300));
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.CTF;
        cfg.scoreLimit = 2;
        lvl.minigame = cfg;
        return lvl;
    }

    /** Put a player's centre exactly on a world point. */
    private static void centerOn(PlayerState p, double x, double y) {
        p.x = x - TS / 2.0;
        p.y = y - TS / 2.0;
    }

    // --- configuration ---------------------------------------------------------

    @Test
    void configSavesInsideTheLevelJson() {
        Level lvl = ctfLevel();
        lvl.minigame.pvp = false;
        Level loaded = LevelLoader.parse(lvl.toJson());

        assertNotNull(loaded.minigame, "the mini game should ride along with the level");
        assertEquals(MiniGameConfig.Mode.CTF, loaded.minigame.mode);
        assertEquals(2, loaded.minigame.scoreLimit);
        assertFalse(loaded.minigame.pvp);
        // The painted markers travel as ordinary entities.
        assertEquals(2, loaded.entities.stream()
                .filter(e -> MiniGame.KIND_FLAG.equals(e.kind)).count());
    }

    @Test
    void stockpileResourceListRoundTrips() {
        Level lvl = Level.empty("Piles", 30, 20, TS);
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.STOCKPILE;
        cfg.teams = 3;
        cfg.resourceItems = new ArrayList<>(List.of("diamond", "ruby"));
        lvl.minigame = cfg;

        MiniGameConfig loaded = LevelLoader.parse(lvl.toJson()).minigame;
        assertEquals(List.of("diamond", "ruby"), loaded.resourceItems);
        assertEquals(3, loaded.teams);
    }

    @Test
    void normalizeEnforcesModeRules() {
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.CTF;
        cfg.teams = 4;
        cfg.normalize();
        assertEquals(2, cfg.teams, "CTF is always two teams");

        cfg.mode = MiniGameConfig.Mode.BATTLE;
        cfg.teams = 4;
        cfg.pvp = false;
        cfg.normalize();
        assertEquals(4, cfg.teams);
        assertTrue(cfg.pvp, "Battle always plays with PvP on");
    }

    @Test
    void levelsWithoutAMiniGameStayNormal() {
        Level lvl = Level.empty("Plain", 20, 20, TS);
        assertNull(MiniGame.createIfConfigured(lvl));
        Level loaded = LevelLoader.parse(lvl.toJson());
        assertNull(loaded.minigame);
    }

    // --- teams -----------------------------------------------------------------

    @Test
    void playersAreDealtOntoTheSmallestTeam() {
        MiniGame game = new MiniGame(ctfLevel(), ctfLevel().minigame);
        int t1 = game.assignTeam(1);
        int t2 = game.assignTeam(2);
        int t3 = game.assignTeam(3);
        int t4 = game.assignTeam(4);
        assertNotEquals(t1, t2, "the second joiner balances the teams");
        assertNotEquals(t3, t4);
        assertEquals(t1, game.assignTeam(1), "re-joining keeps the same team");
    }

    @Test
    void teamsRespawnAtTheirPaintedSpawns() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        int t1 = game.assignTeam(1);
        int t2 = game.assignTeam(2);
        double[] s1 = game.respawnPoint(1);
        double[] s2 = game.respawnPoint(2);
        assertEquals(t1 == 0 ? 140 : 960, s1[0]);
        assertEquals(t2 == 0 ? 140 : 960, s2[0]);
        assertNotEquals(s1[0], s2[0]);
    }

    @Test
    void validateReportsMissingMarkers() {
        Level lvl = Level.empty("Empty CTF", 20, 20, TS);
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.CTF;
        lvl.minigame = cfg;
        String missing = new MiniGame(lvl, cfg).validate();
        assertNotNull(missing);
        assertTrue(missing.contains("Red flag") && missing.contains("Blue flag"));

        assertNull(new MiniGame(ctfLevel(), ctfLevel().minigame).validate());
    }

    // --- capture the flag -------------------------------------------------------

    @Test
    void ctfStealCarryCaptureScoresAndWins() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "Red1", 0, 0);
        PlayerState blue = new PlayerState(2, "Blue1", 0, 0);
        assertEquals(0, game.assignTeam(1));
        assertEquals(1, game.assignTeam(2));
        List<PlayerState> players = List.of(red, blue);
        centerOn(blue, 960, 300); // out of the way

        // Red walks onto the Blue flag: stolen.
        centerOn(red, 1000, 300);
        game.step(1.0 / 60, players);
        assertEquals(MiniGame.Flag.CARRIED, game.flag(1).state);
        assertEquals(1, game.flag(1).carrier);

        // Carry it home while Red's own flag is at base: capture.
        centerOn(red, 100, 300);
        game.step(1.0 / 60, players);
        assertEquals(1, game.score(0));
        assertEquals(MiniGame.Flag.AT_BASE, game.flag(1).state);
        assertEquals(MiniGame.Phase.PLAYING, game.phase());

        // A second capture reaches the limit and ends the round.
        centerOn(red, 1000, 300);
        game.step(1.0 / 60, players);
        centerOn(red, 100, 300);
        game.step(1.0 / 60, players);
        assertEquals(2, game.score(0));
        assertEquals(MiniGame.Phase.ENDED, game.phase());
        assertEquals(0, game.winner());
        assertFalse(game.pollEvents().isEmpty(), "captures should be announced");
    }

    @Test
    void dyingDropsTheFlagAndTheOwnerCanReturnIt() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "Red1", 0, 0);
        PlayerState blue = new PlayerState(2, "Blue1", 0, 0);
        game.assignTeam(1);
        game.assignTeam(2);
        List<PlayerState> players = List.of(red, blue);
        centerOn(blue, 960, 300);

        centerOn(red, 1000, 300);
        game.step(1.0 / 60, players);
        assertEquals(MiniGame.Flag.CARRIED, game.flag(1).state);

        // The carrier dies mid-map: the flag drops where they fell.
        centerOn(red, 550, 300);
        game.step(1.0 / 60, players);
        game.onPlayerDeath(red, red.x, red.y);
        assertEquals(MiniGame.Flag.DROPPED, game.flag(1).state);

        // A Blue player touching their dropped flag sends it home.
        centerOn(blue, 550, 284);
        centerOn(red, 140, 300);
        game.step(1.0 / 60, players);
        assertEquals(MiniGame.Flag.AT_BASE, game.flag(1).state);
    }

    @Test
    void droppedFlagsFlyHomeOnTheirOwn() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "Red1", 0, 0);
        game.assignTeam(1);
        List<PlayerState> players = List.of(red);

        centerOn(red, 1000, 300);
        game.step(1.0 / 60, players);
        centerOn(red, 550, 100);
        game.step(1.0 / 60, players);
        game.onPlayerDeath(red, red.x, red.y);
        centerOn(red, 140, 300); // away from the drop

        game.step(30, players); // well past the return timer
        assertEquals(MiniGame.Flag.AT_BASE, game.flag(1).state);
    }

    @Test
    void endedRoundsResetAutomatically() {
        Level lvl = ctfLevel();
        lvl.minigame.scoreLimit = 1;
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "Red1", 0, 0);
        game.assignTeam(1);
        List<PlayerState> players = List.of(red);

        centerOn(red, 1000, 300);
        game.step(1.0 / 60, players);
        centerOn(red, 100, 300);
        game.step(1.0 / 60, players);
        assertEquals(MiniGame.Phase.ENDED, game.phase());

        red.health = 40;
        game.step(13, players); // the reset countdown elapses
        assertEquals(MiniGame.Phase.PLAYING, game.phase());
        assertEquals(0, game.score(0));
        assertEquals(-1, game.winner());
        assertEquals(PlayerState.MAX_HEALTH, red.health, "reset heals everyone");
        assertEquals(140 , red.x, "reset sends players to their team spawn");
    }

    // --- stockpile --------------------------------------------------------------

    @Test
    void standingAtYourStockpileDepositsConfiguredResources() {
        Level lvl = Level.empty("Piles", 40, 20, TS);
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_STOCKPILE, "team1", 200, 200));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_STOCKPILE, "team2", 900, 200));
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.STOCKPILE;
        cfg.scoreLimit = 5;
        cfg.resourceItems = new ArrayList<>(List.of("coal"));
        lvl.minigame = cfg;

        MiniGame game = new MiniGame(lvl, cfg);
        PlayerState miner = new PlayerState(1, "Miner", 0, 0);
        int team = game.assignTeam(1);
        Inventory inv = new Inventory(com.larsons.engine.entity.ItemRegistry.standard());
        inv.add("coal", 3);
        inv.add("diamond", 2); // not a configured resource: stays put
        game.setInventories(id -> inv);

        double pileX = team == 0 ? 200 : 900;
        centerOn(miner, pileX, 200);
        game.step(1.0 / 60, List.of(miner));

        assertEquals(3, game.score(team));
        assertEquals(0, inv.totalOf("coal"), "deposited resources leave the inventory");
        assertEquals(2, inv.totalOf("diamond"), "non-resources are kept");
        assertEquals(List.of(1), game.pollInventoryChanges());
        assertEquals(MiniGame.Phase.PLAYING, game.phase());

        inv.add("coal", 2); // the winning batch
        game.step(1.0 / 60, List.of(miner));
        assertEquals(5, game.score(team));
        assertEquals(MiniGame.Phase.ENDED, game.phase());
        assertEquals(team, game.winner());
    }

    // --- battle -----------------------------------------------------------------

    @Test
    void battleLoadoutAndKillScoring() {
        Level lvl = Level.empty("Pit", 40, 20, TS);
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.BATTLE;
        cfg.scoreLimit = 1;
        lvl.minigame = cfg;

        MiniGame game = new MiniGame(lvl, cfg);
        PlayerState a = new PlayerState(1, "A", 0, 0);
        PlayerState b = new PlayerState(2, "B", 0, 0);
        int teamA = game.assignTeam(1);
        game.assignTeam(2);
        Inventory invA = new Inventory(com.larsons.engine.entity.ItemRegistry.standard());
        game.setInventories(id -> id == 1 ? invA : null);

        game.grantLoadout(1);
        assertEquals(1, invA.totalOf("arcane_staff"), "Battle grants magic weapons");
        assertEquals(1, invA.totalOf("fire_staff"));
        assertEquals(1, invA.totalOf("iron_pickaxe"), "…and tools");

        // PvP rules: enemies yes, teammates and self no.
        assertTrue(game.canHurt(1, b));
        assertFalse(game.canHurt(1, a), "no hitting yourself");
        game.step(1.0 / 60, List.of(a, b));

        // A hurts B; B dies: A's team scores the kill and wins at the limit.
        game.damaged(1, b);
        b.health = 0;
        game.onPlayerDeath(b, b.x, b.y);
        assertEquals(1, game.score(teamA));
        assertEquals(MiniGame.Phase.ENDED, game.phase());
        assertEquals(teamA, game.winner());
    }

    @Test
    void pvpCanBeTurnedOffOutsideBattle() {
        Level lvl = ctfLevel();
        lvl.minigame.pvp = false;
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "R", 0, 0);
        PlayerState blue = new PlayerState(2, "B", 0, 0);
        game.assignTeam(1);
        game.assignTeam(2);
        assertFalse(game.canHurt(1, blue));
        assertNull(game.resolveMeleeHit(red, List.of(red, blue), blue.x, blue.y));
    }

    // --- escort -----------------------------------------------------------------

    private static Level escortLevel() {
        Level lvl = Level.empty("Route", 60, 20, TS);
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_PATH, "1", 100, 100));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_PATH, "2", 300, 100));
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.ESCORT;
        cfg.escortTimeSec = 300;
        lvl.minigame = cfg;
        return lvl;
    }

    @Test
    void escortedPayloadRollsAndDefendersContestIt() {
        Level lvl = escortLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState escort = new PlayerState(1, "E", 0, 0);
        PlayerState blocker = new PlayerState(2, "D", 0, 0);
        assertEquals(0, game.assignTeam(1), "first joiner escorts");
        assertEquals(1, game.assignTeam(2));

        // Escorted and uncontested: it rolls toward the next waypoint.
        centerOn(escort, 100, 100);
        centerOn(blocker, 900, 500);
        game.step(1.0, List.of(escort, blocker));
        double moved = game.payload()[0];
        assertTrue(moved > 100, "the payload should have moved");

        // A defender in range contests it: it stops.
        centerOn(blocker, moved + 20, 100);
        game.step(1.0, List.of(escort, blocker));
        assertEquals(moved, game.payload()[0], "contested payloads don't move");

        // Nobody escorting: it also stops.
        centerOn(blocker, 900, 500);
        centerOn(escort, 900, 100);
        game.step(1.0, List.of(escort, blocker));
        assertEquals(moved, game.payload()[0], "unescorted payloads don't move");

        // Escort it the rest of the way: the escorting team wins.
        centerOn(escort, moved, 100);
        game.step(8.0, List.of(escort, blocker));
        assertEquals(MiniGame.Phase.ENDED, game.phase());
        assertEquals(0, game.winner());
    }

    @Test
    void escortClockRunningOutIsADefenderWin() {
        Level lvl = escortLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        game.assignTeam(1);
        game.assignTeam(2);
        game.step(301, List.of());
        assertEquals(MiniGame.Phase.ENDED, game.phase());
        assertEquals(1, game.winner(), "the blocking team wins on time");
    }

    // --- PvP through the world simulation ----------------------------------------

    @Test
    void projectilesHitEnemyPlayersButNeverTeammates() {
        Level lvl = Level.empty("Duel", 60, 20, TS);
        lvl.perspective = com.larsons.engine.graphics.Perspective.TOP_DOWN;
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.BATTLE;
        lvl.minigame = cfg;
        MiniGame game = new MiniGame(lvl, cfg);
        GameProfile profile = new GameProfile("Duel");
        profile.gravityEnabled = false;

        World world = new World(lvl);
        world.setPvpRule(game);
        PlayerState shooter = new PlayerState(1, "S", 0, 0);
        PlayerState enemy = new PlayerState(2, "E", 0, 0);
        game.assignTeam(1);
        game.assignTeam(2);
        centerOn(shooter, 100, 100);
        centerOn(enemy, 260, 100);

        Inventory inv = new Inventory(world.itemTypes);
        inv.add("arcane_staff", 1);
        inv.select(0);
        Projectile shot = world.playerShoot(shooter, inv, 260, 100);
        assertNotNull(shot, "the staff should cast on mana");

        List<PlayerState> players = List.of(shooter, enemy);
        for (int i = 0; i < 240 && enemy.health >= PlayerState.MAX_HEALTH; i++) {
            world.step(1.0 / 60, players, profile);
        }
        assertTrue(enemy.health < PlayerState.MAX_HEALTH, "the bolt should hit the enemy");

        // Same shot at a teammate flies straight past.
        PlayerState buddy = new PlayerState(3, "T", 0, 0);
        // Force onto the shooter's team by filling the other team first.
        game.removePlayer(2);
        game.assignTeam(2);
        int t3 = game.assignTeam(3);
        assertEquals(game.teamOf(1), t3);
        centerOn(buddy, 260, 100);
        Projectile second = world.playerShoot(shooter, inv, 260, 100);
        assertNotNull(second);
        List<PlayerState> withBuddy = List.of(shooter, buddy);
        for (int i = 0; i < 240 && !world.projectiles().isEmpty(); i++) {
            world.step(1.0 / 60, withBuddy, profile);
        }
        assertEquals(PlayerState.MAX_HEALTH, buddy.health, "no friendly fire");
    }

    @Test
    void deadPlayersRespawnAtTheirTeamSpawnViaTheWorld() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        GameProfile profile = new GameProfile("CTF");
        World world = new World(lvl);
        world.setPvpRule(game);
        world.setDeathListener(game::onPlayerDeath);
        world.setRespawnProvider(game::respawnPoint);

        PlayerState red = new PlayerState(1, "R", 500, 100);
        int team = game.assignTeam(1);
        red.health = 0;
        world.step(1.0 / 60, List.of(red), profile);
        assertEquals(team == 0 ? 140 : 960, red.x, "respawn goes to the team spawn");
        assertEquals(PlayerState.MAX_HEALTH, red.health);
    }

    // --- wire state --------------------------------------------------------------

    @Test
    void wireStateRoundTripsThroughTheProtocol() {
        Level lvl = ctfLevel();
        MiniGame game = new MiniGame(lvl, lvl.minigame);
        PlayerState red = new PlayerState(1, "Red1", 0, 0);
        game.assignTeam(1);
        centerOn(red, 1000, 300);
        game.step(1.0 / 60, List.of(red));

        String line = Protocol.minigame(game.toWireMap());
        Map<String, Object> msg = Protocol.decode(line);
        assertEquals("mg", Protocol.type(msg));
        MiniGameView view = MiniGameView.fromMap(msg);

        assertEquals(MiniGameConfig.Mode.CTF, view.mode);
        assertFalse(view.ended);
        assertEquals(2, view.teams);
        assertEquals(0, view.teamOf(1));
        assertEquals(2, view.flags.size());
        MiniGameView.FlagView blueFlag = view.flags.stream()
                .filter(f -> f.team() == 1).findFirst().orElseThrow();
        assertEquals(MiniGame.Flag.CARRIED, blueFlag.state());
        assertEquals(1, blueFlag.carrier());
    }

    // --- online end-to-end --------------------------------------------------------

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    @Test
    void hostedCtfLevelRefereesForJoiningClients() throws IOException {
        Level lvl = ctfLevel();
        GameServer server = new GameServer(new GameProfile("CTF Night"), lvl.toJson());
        server.start(0);
        try (GameClient one = GameClient.connect("127.0.0.1", server.getPort(), "One", 3000);
             GameClient two = GameClient.connect("127.0.0.1", server.getPort(), "Two", 3000)) {

            assertNotNull(server.minigame(), "the server should pick up the level's game");

            await("both clients receive the mini-game state",
                    () -> one.minigame() != null && two.minigame() != null
                            && one.minigame().teamOf(one.localId()) >= 0
                            && one.minigame().teamOf(two.localId()) >= 0);

            MiniGameView view = one.minigame();
            assertEquals(MiniGameConfig.Mode.CTF, view.mode);
            assertNotEquals(view.teamOf(one.localId()), view.teamOf(two.localId()),
                    "two joiners land on opposite teams");

            // Each player spawned at their own team's painted spawn point.
            await("first snapshot", () -> one.latest() != null
                    && one.latest().player(two.localId()) != null);
            PlayerState p1 = one.latest().player(one.localId());
            PlayerState p2 = one.latest().player(two.localId());
            assertNotNull(p1);
            assertNotNull(p2);
            assertNotEquals(p1.x, p2.x, "teams spawn at their own bases");
        } finally {
            server.stop();
        }
    }

    @Test
    void onlineMeleePvpDamagesTheEnemyOverTheWire() throws IOException {
        // Adjacent team spawns put the two joiners inside melee reach.
        Level lvl = Level.empty("Duel Pit", 60, 20, TS);
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_FLAG, "team1", 100, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_FLAG, "team2", 1000, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_SPAWN, "team1", 300, 300));
        lvl.entities.add(new Level.EntitySpawn(MiniGame.KIND_SPAWN, "team2", 340, 300));
        MiniGameConfig cfg = new MiniGameConfig();
        cfg.mode = MiniGameConfig.Mode.CTF;
        lvl.minigame = cfg;

        GameProfile profile = new GameProfile("Duel");
        profile.gravityEnabled = false;
        GameServer server = new GameServer(profile, lvl.toJson());
        server.start(0);
        try (GameClient one = GameClient.connect("127.0.0.1", server.getPort(), "One", 3000);
             GameClient two = GameClient.connect("127.0.0.1", server.getPort(), "Two", 3000)) {

            await("first snapshot with both players", () -> one.latest() != null
                    && one.latest().player(two.localId()) != null);

            // Client one swings at client two's centre until damage shows up
            // in the snapshots (attacks are edge-triggered per input seq).
            int[] seq = {10};
            await("melee PvP damage lands", () -> {
                var snap = one.latest();
                PlayerState victim = snap == null ? null : snap.player(two.localId());
                if (victim == null) return false;
                if (victim.health < PlayerState.MAX_HEALTH) return true;
                one.sendInput(new com.larsons.engine.sim.PlayerInput(
                        false, false, false, false, seq[0]++)
                        .attackAt(victim.x + TS / 2.0, victim.y + TS / 2.0));
                return false;
            });
        } finally {
            server.stop();
        }
    }
}
