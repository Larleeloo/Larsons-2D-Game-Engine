package com.larsons.engine.deckbuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Council of Six online, over loopback: a {@link DeckServer} on an ephemeral
 * port with {@link DeckClient}s joining, picking leaders, playing cards,
 * revealing, and passing turns, exactly as the scenes do — plus the join
 * guards (late joins, protocol version).
 */
@Timeout(60)
class DeckNetTest {

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

    @Test
    void onlineLobbyTurnsAndReplication() throws IOException {
        DeckGame.Config cfg = new DeckGame.Config();
        cfg.seed = 77;
        cfg.turnSeconds = 30;      // humans drive the turns in this test
        cfg.postSeconds = 0.5;
        cfg.botActionSeconds = 0.05;
        DeckServer server = new DeckServer(cfg);
        server.start(0);
        try (DeckClient alice = DeckClient.connect("127.0.0.1", server.getPort(), "Alice", 3000);
             DeckClient bob = DeckClient.connect("127.0.0.1", server.getPort(), "Bob", 3000)) {

            assertTrue(alice.localId() > 0);
            await("both players in the lobby", () ->
                    alice.lobby().players().size() == 2 && bob.lobby().players().size() == 2);
            assertTrue(alice.isHost(), "first joiner hosts");
            assertFalse(bob.isHost());

            // Leader picks replicate and stay exclusive.
            alice.sendPickLeader(Leader.LARSON);
            await("Alice's pick replicates", () -> leaderOf(alice, alice.localId())
                    == Leader.LARSON && leaderOf(bob, alice.localId()) == Leader.LARSON);
            bob.sendPickLeader(Leader.LARSON);
            await("the duplicate pick is refused with a toast", () ->
                    bob.pollToasts().stream().anyMatch(t -> t.contains("taken")));
            assertTrue(leaderOf(bob, bob.localId()) == null);
            bob.sendPickLeader(Leader.BELLA);
            await("Bob's second pick lands", () ->
                    leaderOf(alice, bob.localId()) == Leader.BELLA);

            // Only the host manages the table.
            bob.sendStart();
            bob.sendAddBot();
            alice.sendAddBot();
            await("host-added bot appears for everyone", () ->
                    alice.lobby().players().size() == 3 && bob.lobby().players().size() == 3);

            alice.sendStart();
            await("round 1 begins", () -> alice.phase() != null
                    && alice.phase().phase() == DeckGame.Phase.PLAY
                    && bob.phase() != null);
            assertEquals(1, alice.phase().round());

            await("private state arrives", () -> alice.you() != null && bob.you() != null);
            await("the table arrives", () -> alice.table() != null && bob.table() != null);
            assertEquals(Locations.all().size(), alice.table().locations().size());
            assertEquals(DeckGame.MARKET_SIZE, alice.table().market().size());
            assertEquals(3, alice.table().standings().size());
            assertEquals(5, alice.you().hand().size(), "Larson still draws 5");
            assertEquals(DeckGame.AGENTS_PER_ROUND, alice.you().agentsLeft());

            // Round 1 starts on the first seat: Alice.
            await("it's Alice's turn", () -> alice.myTurn());

            // Alice plays her first card somewhere legal and free.
            DeckClient.You you = alice.you();
            int handIndex = -1;
            String locKey = null;
            for (int i = 0; i < you.hand().size() && locKey == null; i++) {
                CardDef card = Cards.get(you.hand().get(i));
                for (LocationDef loc : Locations.all()) {
                    if (card.icons.contains(loc.icon) && loc.costGold == 0
                            && loc.costLore == 0) {
                        handIndex = i;
                        locKey = loc.key;
                        break;
                    }
                }
            }
            assertNotNull(locKey, "a starter hand always has a free-entry target");
            alice.sendPlay(handIndex, locKey, Territory.HIGHLANDS);
            final String played = locKey;
            await("the agent lands on the table", () -> {
                DeckClient.Table t = alice.table();
                if (t == null) return false;
                for (DeckClient.LocationState ls : t.locations()) {
                    if (ls.key().equals(played) && !ls.occupants().isEmpty()) return true;
                }
                return false;
            });
            await("the play consumed an agent", () -> alice.you() != null
                    && alice.you().agentsLeft() == DeckGame.AGENTS_PER_ROUND - 1);

            // Playing passed the turn to Bob; he reveals and ends his turn.
            await("it's Bob's turn", () -> bob.myTurn());
            bob.sendReveal();
            await("Bob's reveal replicates", () -> bob.you() != null
                    && bob.you().revealed());
            bob.sendDone();
            await("the turn moves on past Bob", () -> !bob.myTurn());

            // Late joins are rejected once the game is running.
            IOException rejected = assertThrows(IOException.class, () ->
                    DeckClient.connect("127.0.0.1", server.getPort(), "Late", 3000));
            assertTrue(rejected.getMessage().contains("started"),
                    "got: " + rejected.getMessage());

            // A leaver's seat auto-passes; the game itself continues.
            bob.close();
            await("server sees the disconnect", () -> server.playerCount() == 1);
            assertTrue(alice.isConnected());
        } finally {
            server.stop();
        }
    }

    private static Leader leaderOf(DeckClient client, int playerId) {
        for (DeckClient.LobbyPlayer p : client.lobby().players()) {
            if (p.id() == playerId) return p.leader();
        }
        return null;
    }

    @Test
    void serverRejectsWrongProtocolVersion() throws IOException {
        DeckServer server = new DeckServer(new DeckGame.Config());
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
            assertEquals("error", DeckProto.type(msg));
            assertTrue(((String) msg.get("msg")).contains("version"));
        } finally {
            server.stop();
        }
    }

    @Test
    void fullBotGameOverTheWire() throws IOException {
        DeckGame.Config cfg = new DeckGame.Config();
        cfg.seed = 99;
        cfg.turnSeconds = 1.0; // the host idles; the clock passes their turns
        cfg.postSeconds = 0.05;
        cfg.botActionSeconds = 0.01;
        DeckServer server = new DeckServer(cfg);
        server.start(0);
        try (DeckClient host = DeckClient.connect("127.0.0.1", server.getPort(), "Host", 3000)) {
            host.sendAddBot();
            host.sendAddBot();
            await("bots seated", () -> host.lobby().players().size() == 3);
            host.sendStart();
            await("the game starts", () -> host.phase() != null
                    && host.phase().phase() != DeckGame.Phase.LOBBY);
            await("the game plays to a winner", () -> host.gameOver() != null);

            DeckClient.GameOver over = host.gameOver();
            assertNotNull(over.winner());
            assertEquals(3, over.standings().size());
            List<DeckClient.FxEvent> drained = host.pollEvents();
            assertFalse(drained.isEmpty(), "fx events streamed during play");
        } finally {
            server.stop();
        }
    }
}
