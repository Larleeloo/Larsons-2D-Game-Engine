package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.world.BlockRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback multiplayer tests for the world features merged in from the
 * Side-Scroller engine: creative block painting replicating to every client
 * (and to late joiners via the serialized live level), entity paint/erase,
 * and mined drops flowing into the server-side inventory.
 */
class NetWorldSyncTest {

    /** Registry-mode level: 10 wide, 4 tall, dirt floor. */
    private static final String LEVEL_JSON = """
            {
              "name": "Sync Test Level",
              "tileSize": 32,
              "tileset": "registry",
              "tiles": [
                [0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0],
                [1,1,1,1,1,1,1,1,1,1]
              ],
              "spawn": { "x": 64, "y": 64 }
            }
            """;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameServer startServer() throws IOException {
        GameProfile profile = new GameProfile("Sync Test Type");
        profile.creativeEnabled = true;
        profile.blockEditingEnabled = true;
        profile.mobsEnabled = true;
        profile.itemsEnabled = true;
        profile.audioEnabled = false;
        GameServer server = new GameServer(profile, LEVEL_JSON);
        server.start(0);
        return server;
    }

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
    void paintedBlocksReplicateToEveryClientAndLateJoiners() throws IOException {
        GameServer server = startServer();
        int stone = BlockRegistry.standard().get("stone").id();
        try (GameClient painter = GameClient.connect("127.0.0.1", server.getPort(), "Painter", 3000);
             GameClient viewer = GameClient.connect("127.0.0.1", server.getPort(), "Viewer", 3000)) {

            painter.sendBlockEdit(4, 1, stone, "paint");

            await("both clients see the painted block", () ->
                    painter.level().tileAt(4, 1) == stone
                            && viewer.level().tileAt(4, 1) == stone);
            assertEquals(stone, server.world().level.tileAt(4, 1),
                    "the server's world is the authority");
            assertTrue(painter.pollBlockEvents().stream().anyMatch(
                            e -> e[0] == 4 && e[1] == 1 && e[2] == stone),
                    "clients get block events for local feedback");

            // A player joining later receives the already-edited world.
            try (GameClient late = GameClient.connect("127.0.0.1", server.getPort(), "Late", 3000)) {
                assertEquals(stone, late.level().tileAt(4, 1),
                        "welcome carries the live level, not the original file");
            }

            // Erasing (paint mode, id 0) replicates too.
            painter.sendBlockEdit(4, 1, 0, "paint");
            await("erase replicated", () -> viewer.level().tileAt(4, 1) == 0);
        } finally {
            server.stop();
        }
    }

    @Test
    void paintedMobsAppearInSnapshotsAndCanBeErased() throws IOException {
        GameServer server = startServer();
        try (GameClient painter = GameClient.connect("127.0.0.1", server.getPort(), "Painter", 3000)) {
            painter.sendEntityPaint("mob", "slime", 200, 64);

            await("mob shows up in snapshots", () -> {
                Snapshot s = painter.latest();
                return s != null && !s.mobs().isEmpty();
            });
            EntityView mob = painter.latest().mobs().get(0);
            assertEquals("slime", mob.key);
            assertTrue(mob.health > 0);

            painter.sendEntityErase(mob.id);
            await("mob erased everywhere", () -> {
                Snapshot s = painter.latest();
                return s != null && s.mobs().isEmpty();
            });
        } finally {
            server.stop();
        }
    }

    @Test
    void pickupsLandInTheServerSideInventory() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Collector", 3000)) {
            await("first snapshot", () -> client.latest() != null);

            // Paint an apple onto the player; after the pickup delay the
            // server adds it to this player's inventory and pushes it down.
            Snapshot snap = client.latest();
            var me = snap.player(client.localId());
            client.sendEntityPaint("item", "apple", me.x + 4, me.y + 4);

            await("inventory message arrives", () -> client.inventoryData() != null);
            assertTrue(client.inventoryVersion() > 0);
            boolean hasApple = client.inventoryData().stream()
                    .filter(o -> o instanceof java.util.Map<?, ?>)
                    .map(o -> (java.util.Map<?, ?>) o)
                    .anyMatch(m -> "apple".equals(m.get("k")));
            assertTrue(hasApple, "the painted apple was picked up");
        } finally {
            server.stop();
        }
    }

    @Test
    void editsRespectTheGameTypesToggles() throws IOException {
        GameProfile profile = new GameProfile("Locked Down");
        profile.creativeEnabled = false;      // no painting
        profile.blockEditingEnabled = false;  // no mining either
        profile.audioEnabled = false;
        GameServer server = new GameServer(profile, LEVEL_JSON);
        server.start(0);
        int stone = BlockRegistry.standard().get("stone").id();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Nope", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            client.sendBlockEdit(4, 1, stone, "paint");
            client.sendBlockEdit(0, 3, 0, "play");
            client.sendEntityPaint("mob", "slime", 200, 64);

            // Give the server a moment to (not) apply them.
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            assertEquals(0, server.world().level.tileAt(4, 1), "paint refused");
            assertEquals(1, server.world().level.tileAt(0, 3), "mining refused");
            assertTrue(server.world().mobs().isEmpty(), "mob paint refused");
            assertNotNull(client.latest(), "the client stays connected regardless");
        } finally {
            server.stop();
        }
    }
}
