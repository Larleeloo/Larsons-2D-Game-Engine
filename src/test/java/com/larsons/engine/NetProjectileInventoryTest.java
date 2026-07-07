package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback multiplayer tests for the projectile + inventory features: shots
 * fired through the input command (server-side ammo, snapshot replication,
 * impact fx broadcasts), inventory move/drop/use requests applied to the
 * server's authoritative copy, and play-mode placement consuming the matching
 * block item.
 */
class NetProjectileInventoryTest {

    /** Registry-mode level: 20 wide, 5 tall, dirt floor. */
    private static final String LEVEL_JSON = """
            {
              "name": "Projectile Test Level",
              "tileSize": 32,
              "tileset": "registry",
              "tiles": [
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]
              ],
              "spawn": { "x": 64, "y": 96 }
            }
            """;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameServer startServer() throws IOException {
        GameProfile profile = new GameProfile("Projectile Net Test");
        profile.creativeEnabled = true;
        profile.blockEditingEnabled = true;
        profile.mobsEnabled = true;
        profile.itemsEnabled = true;
        profile.projectilesEnabled = true;
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

    /** The count of {@code key} in the latest {@code inv} push, or -1 before one. */
    private static int invCount(GameClient client, String key) {
        List<Object> data = client.inventoryData();
        if (data == null) return -1;
        int n = 0;
        for (Object o : data) {
            if (o instanceof Map<?, ?> m && key.equals(m.get("k"))
                    && m.get("n") instanceof Number num) {
                n += num.intValue();
            }
        }
        return n;
    }

    /** The slot index holding {@code key} in the latest {@code inv} push, or -1. */
    private static int invSlot(GameClient client, String key) {
        List<Object> data = client.inventoryData();
        if (data == null) return -1;
        for (Object o : data) {
            if (o instanceof Map<?, ?> m && key.equals(m.get("k"))
                    && m.get("i") instanceof Number i) {
                return i.intValue();
            }
        }
        return -1;
    }

    @Test
    void shotsConsumeServerSideAmmoReplicateAndBroadcastImpactFx() throws IOException {
        GameServer server = startServer();
        try (GameClient archer = GameClient.connect("127.0.0.1", server.getPort(), "Archer", 3000)) {
            await("first snapshot", () -> archer.latest() != null);
            var me = archer.latest().player(archer.localId());

            // Give the archer a bow and arrows via creative paints + pickup.
            archer.sendEntityPaint("item", "wooden_bow", me.x + 4, me.y + 4);
            archer.sendEntityPaint("item", "arrow", me.x + 4, me.y + 4);
            archer.sendEntityPaint("item", "arrow", me.x + 4, me.y + 4);
            await("bow and arrows picked up", () ->
                    invCount(archer, "wooden_bow") == 1 && invCount(archer, "arrow") == 2);
            int bowSlot = invSlot(archer, "wooden_bow");
            assertTrue(bowSlot >= 0);

            // A second player joins (after the pickups, so it can't race the
            // archer for them) to prove shots replicate to everyone.
            try (GameClient viewer = GameClient.connect("127.0.0.1", server.getPort(), "Viewer", 3000)) {
                // Fire once: an attack input with the bow selected.
                PlayerInput in = new PlayerInput(false, false, false, false, 100);
                in.selected = bowSlot;
                in.attackAt(me.x + 400, me.y - 20);
                archer.sendInput(in);

                await("both clients see the arrow in flight", () -> {
                    Snapshot a = archer.latest();
                    Snapshot v = viewer.latest();
                    return a != null && !a.shots().isEmpty()
                            && v != null && !v.shots().isEmpty();
                });
                EntityView shot = archer.latest().shots().get(0);
                assertEquals("arrow", shot.key);
                assertTrue(shot.vx > 0, "snapshots carry velocity for rotation");

                await("one arrow was spent server-side", () -> invCount(archer, "arrow") == 1);

                // The impact broadcast reaches everyone (the arrow lands somewhere).
                List<World.Impact> fx = new ArrayList<>();
                await("impact fx broadcast", () -> {
                    fx.addAll(viewer.pollFxEvents());
                    return !fx.isEmpty();
                });
                assertEquals("arrow", fx.get(0).key());
                assertFalse(fx.get(0).explosion());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void inventoryMoveAndDropAreServerAuthoritative() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Sorter", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            var me = client.latest().player(client.localId());
            client.sendEntityPaint("item", "apple", me.x + 4, me.y + 4);
            await("apple picked up", () -> invCount(client, "apple") == 1);
            int from = invSlot(client, "apple");

            client.sendInvMove(from, 12);
            await("apple moved to slot 12", () -> invSlot(client, "apple") == 12);

            // Dropping puts it back into the world (it shows up in snapshots)
            // and removes it from the inventory.
            client.sendInvDrop(12, 1);
            await("apple left the inventory", () -> invCount(client, "apple") == 0);
            await("apple is back in the world", () -> {
                Snapshot s = client.latest();
                if (s == null) return false;
                // It may already have been re-picked-up (the player stands on
                // it); either the world drop or the re-pickup proves the spawn.
                for (EntityView item : s.items()) {
                    if ("apple".equals(item.key)) return true;
                }
                return invCount(client, "apple") == 1;
            });
        } finally {
            server.stop();
        }
    }

    @Test
    void eatingIsAServerSideRequestThatHealsAndConsumes() throws IOException {
        GameServer server = startServer();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Eater", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            var me = client.latest().player(client.localId());

            client.sendEntityPaint("item", "apple", me.x + 4, me.y + 4);
            await("apple picked up", () -> invCount(client, "apple") == 1);
            int slot = invSlot(client, "apple");

            // At full health the server refuses to waste the food.
            client.sendUseItem(slot);
            sleep(300);
            assertEquals(1, invCount(client, "apple"), "full health: not consumed");

            // Hurt the player with a painted zombie, then erase it.
            client.sendEntityPaint("mob", "zombie", me.x, me.y);
            await("the zombie hurt us", () -> {
                Snapshot s = client.latest();
                var p = s == null ? null : s.player(client.localId());
                return p != null && p.health < 99.9;
            });
            await("zombie visible", () -> !client.latest().mobs().isEmpty());
            client.sendEntityErase(client.latest().mobs().get(0).id);
            await("zombie gone", () -> client.latest().mobs().isEmpty());

            client.sendUseItem(slot);
            await("the apple was eaten", () -> invCount(client, "apple") == 0);
        } finally {
            server.stop();
        }
    }

    @Test
    void playModePlacementConsumesTheHeldBlockItem() throws IOException {
        GameServer server = startServer();
        int stone = BlockRegistry.standard().get("stone").id();
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Builder", 3000)) {
            await("first snapshot", () -> client.latest() != null);
            var me = client.latest().player(client.localId());

            // Without the item, play-mode placement is refused.
            client.sendBlockEdit(15, 1, stone, "play");
            sleep(300);
            assertEquals(0, server.world().level.tileAt(15, 1),
                    "you can't place blocks you don't have");

            // With it, placement works and spends the item.
            client.sendEntityPaint("item", "stone", me.x + 4, me.y + 4);
            await("stone item picked up", () -> invCount(client, "stone") == 1);
            client.sendBlockEdit(15, 1, stone, "play");
            await("block placed", () -> server.world().level.tileAt(15, 1) == stone);
            await("stone item consumed", () -> invCount(client, "stone") == 0);
        } finally {
            server.stop();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
