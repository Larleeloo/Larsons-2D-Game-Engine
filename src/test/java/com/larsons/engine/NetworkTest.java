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
            // Spawn is at y=64; the floor row starts at y=96, so a player
            // standing on it rests with its feet on y=96 (the hitbox is just
            // under one tile tall). Give it a tick to settle and check it
            // doesn't fall through.
            double restY = 96 - new GameProfile("Net Test Type").playerSize;
            await("settled on floor", () -> {
                Snapshot s = client.latest();
                return s != null && Math.abs(s.player(client.localId()).y - restY) < 0.01;
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

    /** A registry-mode level with a varied grid, as the creative editor saves. */
    private static Level customLevel(int width, int height) {
        Level lvl = Level.empty("Custom", width, height, 32);
        int dirt = lvl.blocks.get("dirt").id();
        int stone = lvl.blocks.get("stone").id();
        for (int c = 0; c < width; c++) {
            lvl.setTile(c, height - 1, (c % 2 == 0) ? dirt : stone);
            lvl.setTile(c, height - 2, (c % 3 == 0) ? stone : 0);
        }
        return lvl;
    }

    @Test
    void bigCustomLevelsJoinWithoutTimingOut() throws IOException {
        // A 400x300 grid in the old pretty row-of-arrays serialization was
        // several times the protocol line cap: the client silently dropped
        // the welcome and every join timed out. RLE + compact must fit.
        Level big = customLevel(400, 300);
        GameProfile profile = new GameProfile("Big Level Type");
        GameServer server = new GameServer(profile, big.toJson());
        server.start(0);
        try (GameClient client = GameClient.connect(
                "127.0.0.1", server.getPort(), "Joiner", 5000)) {
            Level received = client.level();
            assertNotNull(received, "client should hold the served level");
            assertEquals(400, received.width);
            assertEquals(300, received.height);
            for (int c = 0; c < 400; c += 37) {
                assertEquals(big.tileAt(c, 299), received.tileAt(c, 299),
                        "tile mismatch at col " + c);
            }
            await("first snapshot", () -> client.latest() != null);
        } finally {
            server.stop();
        }
    }

    @Test
    void levelsRoundTripThroughRleSerialization() {
        Level lvl = customLevel(50, 20);
        Level back = LevelLoader.parse(lvl.toJson());
        assertEquals(lvl.width, back.width);
        assertEquals(lvl.height, back.height);
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                assertEquals(lvl.tileAt(c, r), back.tileAt(c, r),
                        "tile mismatch at " + c + "," + r);
            }
        }
    }

    @Test
    void onlineBlocksHaveDurabilityInsteadOfBreakingInstantly() throws IOException {
        Level lvl = Level.empty("Mine Test", 20, 15, 32);
        int dirt = lvl.blocks.get("dirt").id(); // 0.6 s of bare-handed mining
        lvl.setTile(3, 4, dirt);
        GameProfile profile = new GameProfile("Mine Test Type");
        profile.gravityEnabled = false; // hold the player at the spawn
        GameServer server = new GameServer(profile, lvl.toJson());
        server.start(0);
        try (GameClient client = GameClient.connect(
                "127.0.0.1", server.getPort(), "Miner", 3000)) {
            await("first snapshot", () -> client.latest() != null);

            // A bare "break this block" request must not work any more —
            // that was the instant-breaking bug.
            client.sendBlockEdit(3, 4, 0, "play");
            sleep(400);
            assertEquals(dirt, client.level().tileAt(3, 4),
                    "a bare edit request must not break the block");

            // Hold-to-mine intent rides the input command; the server chips
            // away at the block's hardness and only then breaks it.
            PlayerInput in = new PlayerInput(false, false, false, false, 1);
            in.mine = true;
            in.mineCol = 3;
            in.mineRow = 4;
            client.sendInput(in);
            sleep(200); // well under dirt's 0.6 s hardness
            assertEquals(dirt, client.level().tileAt(3, 4),
                    "the block should survive the start of the stroke");
            await("block mined through its durability",
                    () -> client.level().tileAt(3, 4) == 0);
        } finally {
            server.stop();
        }
    }

    @Test
    void releasedMiningStrokeResetsProgress() throws IOException {
        Level lvl = Level.empty("Mine Reset Test", 20, 15, 32);
        int stone = lvl.blocks.get("stone").id(); // 1.6 s bare-handed
        lvl.setTile(3, 4, stone);
        GameProfile profile = new GameProfile("Mine Reset Type");
        profile.gravityEnabled = false;
        GameServer server = new GameServer(profile, lvl.toJson());
        server.start(0);
        try (GameClient client = GameClient.connect(
                "127.0.0.1", server.getPort(), "Waffler", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            PlayerInput mining = new PlayerInput(false, false, false, false, 1);
            mining.mine = true;
            mining.mineCol = 3;
            mining.mineRow = 4;
            client.sendInput(mining);
            sleep(700); // roughly half of stone's hardness
            client.sendInput(new PlayerInput(false, false, false, false, 2)); // release
            sleep(300);
            PlayerInput again = new PlayerInput(false, false, false, false, 3);
            again.mine = true;
            again.mineCol = 3;
            again.mineRow = 4;
            client.sendInput(again);
            sleep(700); // a fresh stroke: not enough on its own
            assertEquals(stone, client.level().tileAt(3, 4),
                    "releasing the mouse must reset mining progress");
        } finally {
            server.stop();
        }
    }

    /**
     * Regression: quitting an online session nulls the scene's net reference,
     * but the menu fade keeps <em>rendering</em> the play scene for a few more
     * frames — and online there is no local World, so the offline render
     * paths crashed the game loop with an NPE ({@code world.darkness}).
     */
    @Test
    void quittingAnOnlineSessionSurvivesTheMenuFade() throws IOException {
        System.setProperty("java.awt.headless", "true");
        GameServer server = startServer();
        GameClient client = GameClient.connect(
                "127.0.0.1", server.getPort(), "Quitter", 3000);
        com.larsons.engine.config.GameContext ctx =
                new com.larsons.engine.config.GameContext(
                        null, new com.larsons.engine.config.GameTypeStore());
        ctx.setProfile(client.profile());
        ctx.setSession(new com.larsons.engine.net.NetSession(client, server));

        com.larsons.engine.scene.SceneManager scenes =
                new com.larsons.engine.scene.SceneManager();
        scenes.setViewport(640, 360);
        scenes.register("menu", new com.larsons.engine.demo.MainMenuScene(ctx));
        scenes.register("play", new com.larsons.engine.demo.PlayScene(
                ctx, "levels/sample_level.json"));

        com.larsons.engine.input.InputManager input =
                new com.larsons.engine.input.InputManager();
        java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(
                640, 360, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = frame.createGraphics();
        try {
            scenes.setScene("play");
            for (int i = 0; i < 10; i++) {
                input.newFrame();
                scenes.update(1.0 / 120.0, input);
                scenes.render(g, 0f);
            }

            // The server goes away (the host quit); the client notices...
            server.stop();
            await("client sees the disconnect", () -> !client.isConnected());

            // ...and the player presses Enter on the disconnect overlay,
            // which tears the session down and starts the menu fade. Every
            // frame of that fade still renders this scene.
            java.awt.Component src = new java.awt.Canvas();
            input.keyPressed(new java.awt.event.KeyEvent(src,
                    java.awt.event.KeyEvent.KEY_PRESSED, 0L, 0,
                    java.awt.event.KeyEvent.VK_ENTER, '\n'));
            for (int i = 0; i < 120; i++) { // enough frames to finish the fade
                input.newFrame();
                scenes.update(1.0 / 120.0, input);
                scenes.render(g, 0f);
            }
            assertEquals("MainMenuScene", scenes.current().name(),
                    "the quit should land on the menu without crashing");
        } finally {
            g.dispose();
            ctx.closeSession();
            server.stop();
        }
    }

    @Test
    void blockBatchesRoundTrip() {
        String line = Protocol.blockBatch(java.util.List.of(
                new int[]{1, 2, 7}, new int[]{3, 4, 0}));
        Map<String, Object> msg = Protocol.decode(line);
        assertEquals("blocks", Protocol.type(msg));
        java.util.List<Object> flat = com.larsons.engine.util.Json.asArray(msg.get("l"));
        assertEquals(6, flat.size());
        assertEquals(7, ((Number) flat.get(2)).intValue());
        assertEquals(0, ((Number) flat.get(5)).intValue());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
