package com.larsons.engine.autobattler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-battler end to end: a full bot game driven headlessly through
 * {@link AutoGame} (rounds, PvE creeps, pairings, ghosts, eliminations, a
 * winner), and real online play over loopback — an {@link AutoServer} on an
 * ephemeral port with {@link AutoClient}s joining, lobbying, buying units,
 * and watching replicated combat, exactly as the scenes do.
 */
@Timeout(60)
class AutoBattlerNetTest {

    /** Poll until a condition holds, failing after ~10 seconds. */
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

    // --- headless full game -----------------------------------------------------------

    @Test
    void botsPlayAWholeGameToAWinner() {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 2024;
        cfg.planSeconds = 0.1;
        cfg.fightMaxSeconds = 25;
        cfg.postSeconds = 0.05;
        cfg.startHp = 40; // shorter game, same mechanics

        AtomicInteger overMessages = new AtomicInteger();
        String[] winner = new String[1];
        AutoGame.Sink sink = new AutoGame.Sink() {
            @Override
            public void toPlayer(int playerId, Map<String, Object> message) {}

            @Override
            public void toAll(Map<String, Object> message) {
                if ("over".equals(AutoProto.type(message))) {
                    overMessages.incrementAndGet();
                    winner[0] = (String) message.get("winner");
                }
            }
        };

        AutoGame game = new AutoGame(cfg, sink);
        AutoPlayer host = game.addPlayer("Idle Human", false); // never acts
        game.addPlayer("Bot A", true);
        game.addPlayer("Bot B", true);
        game.addPlayer("Bot C", true);
        assertTrue(game.start(host.id));
        assertEquals(AutoGame.Phase.PLAN, game.phase());
        assertEquals(1, game.round());

        double dt = 1.0 / 30;
        int guard = 30 * 60 * 60; // an hour of simulated time, tops
        while (game.phase() != AutoGame.Phase.OVER && guard-- > 0) {
            game.tick(dt);
        }

        assertEquals(AutoGame.Phase.OVER, game.phase(), "the game reached a winner");
        assertEquals(1, overMessages.get());
        assertNotNull(winner[0]);
        assertTrue(game.round() > 1, "multiple rounds were played");
        assertEquals(1, game.alivePlayers().size());
        assertEquals(winner[0], game.alivePlayers().get(0).name);
        assertEquals(1, game.alivePlayers().get(0).place);
        // Everyone eliminated got a distinct placement.
        boolean[] seen = new boolean[game.players().size() + 2];
        for (AutoPlayer p : game.players()) {
            assertTrue(p.place >= 1, p.name + " placed");
            assertFalse(seen[p.place], "unique placements");
            seen[p.place] = true;
        }
        // The idle human fed the bots kills and placed last.
        assertEquals(game.players().size(),
                game.byId(host.id).place, "an idle board dies first");
    }

    @Test
    void creepRoundsDropItemComponentsToWinners() {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 5;
        cfg.planSeconds = 0.1;
        cfg.fightMaxSeconds = 25;
        cfg.postSeconds = 0.05;
        AutoGame game = new AutoGame(cfg, new AutoGame.Sink() {
            @Override
            public void toPlayer(int playerId, Map<String, Object> message) {}

            @Override
            public void toAll(Map<String, Object> message) {}
        });
        AutoPlayer host = game.addPlayer("Human", false);
        game.addPlayer("Bot A", true);
        assertTrue(game.start(host.id));

        // Round 1 is PvE; bots field units and beat the slimes.
        double dt = 1.0 / 30;
        int guard = 30 * 300;
        while (game.round() == 1 && game.phase() != AutoGame.Phase.OVER && guard-- > 0) {
            game.tick(dt);
        }
        AutoPlayer bot = game.players().get(1);
        assertTrue(bot.bot);
        // The bot won round 1 vs creeps: it holds a component (possibly already
        // equipped onto a unit by its own planning logic).
        int held = bot.items.size();
        for (UnitInstance u : bot.units) held += u.items.size();
        assertTrue(held >= 1, "creep win dropped an item component");
    }

    // --- loopback online play ------------------------------------------------------------

    @Test
    void onlineLobbyGameplayAndCombatReplication() throws IOException {
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 77;
        cfg.planSeconds = 3.0;
        // Short cap: a one-unit board vs creeps may stall, and the test only
        // needs the timeout-draw result to come back promptly.
        cfg.fightMaxSeconds = 6;
        cfg.postSeconds = 0.5;
        AutoServer server = new AutoServer(cfg);
        server.start(0);
        try (AutoClient alice = AutoClient.connect("127.0.0.1", server.getPort(), "Alice", 3000);
             AutoClient bob = AutoClient.connect("127.0.0.1", server.getPort(), "Bob", 3000)) {

            assertTrue(alice.localId() > 0);
            await("both players in the lobby", () ->
                    alice.lobby().players().size() == 2 && bob.lobby().players().size() == 2);
            assertTrue(alice.isHost(), "first joiner hosts");
            assertFalse(bob.isHost());

            // Only the host can start / manage bots.
            bob.sendStart();
            bob.sendAddBot();
            alice.sendAddBot();
            await("host-added bot appears for everyone", () ->
                    alice.lobby().players().size() == 3 && bob.lobby().players().size() == 3);

            alice.sendStart();
            await("round 1 planning begins", () ->
                    alice.phase() != null && alice.phase().phase() == AutoGame.Phase.PLAN
                            && bob.phase() != null);
            assertEquals(1, alice.phase().round());
            assertTrue(alice.phase().pve(), "round 1 fights creeps");

            await("private state arrives", () -> alice.you() != null && bob.you() != null);
            await("standings arrive", () -> alice.standings().size() == 3);

            // Alice buys the first affordable shop card; the server answers
            // with fresh private state showing the unit on her bench.
            AutoClient.You you = alice.you();
            int slot = -1;
            for (int i = 0; i < you.shop().size(); i++) {
                String key = you.shop().get(i);
                if (key != null && AutoUnits.get(key).cost <= you.gold()) {
                    slot = i;
                    break;
                }
            }
            assertTrue(slot >= 0, "level-3 shops always offer affordable units");
            int goldBefore = you.gold();
            alice.sendBuy(slot);
            await("bought unit lands on the bench", () ->
                    alice.you() != null && !alice.you().bench().isEmpty());
            assertTrue(alice.you().gold() < goldBefore);

            // Field it, then watch the creep fight arrive as combat snapshots.
            int unitId = alice.you().bench().get(0).id;
            alice.sendMoveToBoard(unitId, 3, 4);
            await("unit fielded", () -> alice.you() != null
                    && alice.you().board().size() == 1);

            await("combat phase begins", () -> alice.phase() != null
                    && alice.phase().phase() == AutoGame.Phase.FIGHT);
            await("combat snapshots replicate", () -> alice.combatLatest() != null
                    && !alice.combatLatest().units().isEmpty());
            assertNotNull(alice.match());
            assertTrue(alice.match().pve());

            await("round result arrives", () -> !alice.pollResults().isEmpty());

            // Late joins are rejected once the game is running.
            IOException rejected = assertThrows(IOException.class, () ->
                    AutoClient.connect("127.0.0.1", server.getPort(), "Late", 3000));
            assertTrue(rejected.getMessage().contains("started"),
                    "got: " + rejected.getMessage());

            // A leaver's board plays on; the game itself continues.
            bob.close();
            await("server sees the disconnect", () -> server.playerCount() == 1);
            assertTrue(alice.isConnected());
        } finally {
            server.stop();
        }
    }

    @Test
    void serverRejectsWrongProtocolVersion() throws IOException {
        AutoServer server = new AutoServer(new AutoGame.Config());
        server.start(0);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            java.io.BufferedWriter out = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(),
                            java.nio.charset.StandardCharsets.UTF_8));
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8));
            out.write("{\"t\":\"join\",\"v\":999,\"name\":\"Old\"}\n");
            out.flush();
            String line = in.readLine();
            assertNotNull(line);
            Map<String, Object> msg = com.larsons.engine.net.Protocol.decode(line);
            assertEquals("error", AutoProto.type(msg));
            assertTrue(((String) msg.get("msg")).contains("version"));
        } finally {
            server.stop();
        }
    }
}
