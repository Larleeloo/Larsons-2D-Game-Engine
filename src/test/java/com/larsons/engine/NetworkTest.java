package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.sim.PlayerInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end online play over loopback (requirement #3): a real
 * {@link GameServer} on an ephemeral port, real {@link GameClient}s dialing it
 * by host+port exactly as the Multiplayer menu does, input commands moving the
 * authoritative simulation, and snapshots flowing back.
 */
@Timeout(30)
class NetworkTest {

    private static final String LEVEL_JSON = """
            {
              "name": "Net Test Level",
              "tileSize": 32,
              "tiles": [
                [0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0],
                [1,1,1,1,1,1,1,1,1,1]
              ],
              "spawn": { "x": 64, "y": 64 }
            }
            """;

    private static GameServer startServer() throws IOException {
        GameProfile profile = new GameProfile("Net Test Type");
        profile.gravityEnabled = true;
        GameServer server = new GameServer(profile, LEVEL_JSON);
        server.start(0); // ephemeral port, like binding a real one
        return server;
    }

    /** Poll until a condition holds, failing after ~5 seconds. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
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
    void handshakeDeliversTheHostsGameTypeAndLevel() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Larson", 3000)) {
            assertTrue(client.localId() > 0);
            assertEquals(GameServer.TICK_RATE, client.tickRate());
            assertEquals("Net Test Type", client.profile().name,
                    "client should adopt the server's game type");

            Level level = LevelLoader.parse(client.levelJson());
            assertEquals("Net Test Level", level.name);
            assertEquals(10, level.width);

            await("first snapshot", () -> client.latest() != null);
            assertNotNull(client.latest().player(client.localId()));
        } finally {
            server.stop();
        }
    }

    @Test
    void inputCommandsMoveThePlayerInServerSnapshots() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Mover", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            double startX = client.latest().player(client.localId()).x;

            // One "hold right" command; the server applies the latest input
            // every tick until it changes, so the player keeps moving.
            client.sendInput(new PlayerInput(false, true, false, false, 1));
            await("player moved right", () -> {
                Snapshot s = client.latest();
                return s != null && s.player(client.localId()).x > startX + 20;
            });

            // Release: an idle command stops the movement.
            client.sendInput(new PlayerInput(false, false, false, false, 2));
            await("input acknowledged", () -> {
                Snapshot s = client.latest();
                return s != null && s.player(client.localId()).lastSeq == 2;
            });
            double stoppedX = client.latest().player(client.localId()).x;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            assertEquals(stoppedX, client.latest().player(client.localId()).x, 0.001,
                    "player should hold still on idle input");
        } finally {
            server.stop();
        }
    }

    @Test
    void gravityActsOnTheServerSimulation() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Faller", 3000)) {
            // Spawn is at y=64; the floor row starts at y=96, so a 32px player
            // standing on it rests at y=64. Give it a tick to settle and check
            // it doesn't fall through.
            await("settled on floor", () -> {
                Snapshot s = client.latest();
                return s != null && Math.abs(s.player(client.localId()).y - 64) < 0.01;
            });
        } finally {
            server.stop();
        }
    }

    @Test
    void twoPlayersSeeEachOtherAndLeavesAreBroadcast() throws IOException {
        GameServer server = startServer();
        try (GameClient alice = GameClient.connect("127.0.0.1", server.getPort(), "Alice", 3000)) {
            GameClient bob = GameClient.connect("127.0.0.1", server.getPort(), "Bob", 3000);
            try {
                await("both players in snapshots", () -> {
                    Snapshot s = alice.latest();
                    return s != null && s.players().size() == 2;
                });
                assertEquals(2, server.playerCount());
                assertNotNull(alice.latest().player(bob.localId()));
                await("join event announced", () ->
                        alice.recentEvents().stream().anyMatch(e -> e.contains("Bob joined")));
            } finally {
                bob.close();
            }
            await("Bob removed from snapshots", () -> {
                Snapshot s = alice.latest();
                return s != null && s.players().size() == 1;
            });
            await("leave event announced", () ->
                    alice.recentEvents().stream().anyMatch(e -> e.contains("Bob left")));
        } finally {
            server.stop();
        }
    }

    @Test
    void duplicateNamesAreMadeUnique() throws IOException {
        GameServer server = startServer();
        try (GameClient first = GameClient.connect("127.0.0.1", server.getPort(), "Twin", 3000);
             GameClient second = GameClient.connect("127.0.0.1", server.getPort(), "Twin", 3000)) {
            await("both players joined", () -> {
                Snapshot s = second.latest();
                return s != null && s.players().size() == 2;
            });
            Snapshot s = second.latest();
            String n1 = s.player(first.localId()).name;
            String n2 = s.player(second.localId()).name;
            assertEquals("Twin", n1);
            assertFalse(n2.equalsIgnoreCase(n1), "second Twin should be renamed, got " + n2);
        } finally {
            server.stop();
        }
    }

    @Test
    void incompatibleProtocolVersionIsRejected() throws IOException {
        GameServer server = startServer();
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.write("{\"t\":\"join\",\"v\":999,\"name\":\"TimeTraveler\"}\n");
            out.flush();
            String line = in.readLine();
            assertNotNull(line, "server should answer before closing");
            Map<String, Object> msg = Protocol.decode(line);
            assertEquals("error", Protocol.type(msg));
        } finally {
            server.stop();
        }
    }

    @Test
    void stoppingTheServerDisconnectsClients() throws IOException {
        GameServer server = startServer();
        GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Orphan", 3000);
        try {
            await("connected", client::isConnected);
            server.stop();
            await("client notices the shutdown", () -> !client.isConnected());
            assertNotNull(client.disconnectReason());
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    void connectingToNothingFailsFast() {
        assertThrows(IOException.class,
                () -> GameClient.connect("127.0.0.1", 1, "Nobody", 1500));
    }

    @Test
    void addressParsingHandlesOptionalPort() {
        assertEquals("example.com", Protocol.splitAddress("example.com")[0]);
        assertEquals(Integer.toString(Protocol.DEFAULT_PORT), Protocol.splitAddress("example.com")[1]);
        assertEquals("10.0.0.5", Protocol.splitAddress("10.0.0.5:25565")[0]);
        assertEquals("25565", Protocol.splitAddress("10.0.0.5:25565")[1]);
        assertEquals("localhost", Protocol.splitAddress("  ")[0]);
    }

    @Test
    void compactJsonRoundTripsProtocolMessages() {
        String line = Protocol.state(42, java.util.List.of());
        assertFalse(line.contains("\n"), "wire messages must be single-line");
        Map<String, Object> msg = Protocol.decode(line);
        assertEquals("state", Protocol.type(msg));
        assertEquals(42, ((Number) msg.get("tick")).intValue());
    }
}
