package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.Vehicle;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.GameServer;
import com.larsons.engine.net.Protocol;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for vehicles and mounts: the registry and its item links,
 * mounting/dismounting with server-grade validation, the four movement models
 * (gallop, fly, float, drill), armed vehicles, pack-up recovery, the wire
 * form snapshots carry — and an end-to-end online ride over loopback.
 */
@Timeout(30)
class VehicleTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("Vehicle Test");
        p.audioEnabled = false;
        return p;
    }

    private static Level flatLevel(int width, int height) {
        Level lvl = Level.empty("Flat", width, height, 32);
        int dirt = BlockRegistry.standard().get("dirt").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles[lvl.height - 1][c] = dirt;
        }
        return lvl;
    }

    private static void step(World world, GameProfile p, PlayerState player, double seconds) {
        for (int i = 0; i < (int) (seconds * 60); i++) {
            world.step(1.0 / 60, List.of(player), p);
        }
    }

    // --- registry ---------------------------------------------------------------

    @Test
    void vehicleItemsAndRegistryLinkBothWays() {
        VehicleRegistry vehicles = VehicleRegistry.standard();
        ItemRegistry items = ItemRegistry.standard();
        assertTrue(vehicles.all().size() >= 8, "a real garage, got " + vehicles.all().size());
        for (VehicleDef def : vehicles.all()) {
            assertNotNull(items.get(def.sourceItem()),
                    def.key() + "'s source item must be a registered item");
            assertEquals(def, vehicles.bySourceItem(def.sourceItem()),
                    "using the item resolves back to the vehicle");
        }
        assertNull(vehicles.bySourceItem("apple"), "food is not a vehicle");
        assertNotNull(vehicles.get("horse"));
        assertEquals(VehicleDef.Kind.FLYING, vehicles.get("magic_carpet").kind());
        assertEquals(VehicleDef.Kind.BOAT, vehicles.get("boat").kind());
        assertEquals(VehicleDef.Kind.DRILL, vehicles.get("drill_machine").kind());
        assertEquals("fireball", vehicles.get("war_dragon").projectile());
    }

    // --- mounting & riding -------------------------------------------------------

    @Test
    void mountRideAndDismountAHorse() {
        Level lvl = flatLevel(80, 10);
        GameProfile p = profile();
        World world = new World(lvl);
        Vehicle horse = world.spawnVehicle("horse", 300, 240);
        PlayerState rider = new PlayerState(1, "rider", 290, 240);

        assertNotNull(world.mountableNear(rider.x, rider.y), "the horse is in reach");
        assertTrue(world.mount(rider, horse.id, p));
        assertEquals(rider.id, horse.riderId);
        assertEquals(horse.id, rider.riding);
        assertFalse(world.mount(new PlayerState(2, "second", 300, 240), horse.id, p),
                "one saddle, one rider");

        // Gallop right for a second: the horse (and its rider) covers real
        // ground, far more than walking speed would.
        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        double startX = horse.x;
        for (int i = 0; i < 60; i++) {
            world.driveVehicle(horse, rider, right, p, 1.0 / 60);
            world.step(1.0 / 60, List.of(rider), p);
        }
        assertTrue(horse.x > startX + 260, "galloped " + (horse.x - startX) + " px");
        assertEquals(horse.seatX(p.playerSize), rider.x, 0.001, "rider glued to the saddle");
        assertEquals(horse.seatY(p.playerSize), rider.y, 0.001);

        // Jump: grounded + up launches the horse.
        PlayerInput jump = new PlayerInput(false, false, true, false, 2);
        world.driveVehicle(horse, rider, jump, p, 1.0 / 60);
        assertTrue(horse.vy < -300, "the horse jumped, vy=" + horse.vy);

        assertNotNull(world.dismount(rider));
        assertEquals(-1, rider.riding);
        assertEquals(-1, horse.riderId);
    }

    @Test
    void mountingValidatesDistance() {
        World world = new World(flatLevel(80, 10));
        Vehicle horse = world.spawnVehicle("horse", 1000, 240);
        PlayerState far = new PlayerState(1, "far", 100, 240);
        assertFalse(world.mount(far, horse.id, profile()),
                "can't teleport onto a mount across the map");
        assertNull(world.mountableNear(far.x, far.y));
    }

    @Test
    void idleVehiclesSettleUnderGravity() {
        Level lvl = flatLevel(40, 10);
        GameProfile p = profile();
        World world = new World(lvl);
        Vehicle horse = world.spawnVehicle("horse", 200, 60); // in the air
        step(world, p, new PlayerState(1, "watcher", 600, 240), 2.0);
        double floorTop = (lvl.height - 1) * 32;
        assertEquals(floorTop - horse.def.size(), horse.y, 1.0,
                "the empty horse dropped and stands on the floor");
    }

    @Test
    void boatsFloatUpToTheSurface() {
        Level lvl = flatLevel(40, 12);
        int water = BlockRegistry.standard().get("water").id();
        for (int r = 6; r < lvl.height - 1; r++) {
            for (int c = 5; c < 20; c++) lvl.tiles[r][c] = water;
        }
        GameProfile p = profile();
        World world = new World(lvl);
        Vehicle boat = world.spawnVehicle("boat", 10 * 32, 9 * 32); // submerged
        step(world, p, new PlayerState(1, "watcher", 1100, 100), 3.0);
        assertTrue(boat.y < 8 * 32, "buoyancy lifted the hull toward the surface, y=" + boat.y);
    }

    @Test
    void carpetsFlyWhereverSteered() {
        Level lvl = flatLevel(60, 20);
        GameProfile p = profile();
        World world = new World(lvl);
        Vehicle carpet = world.spawnVehicle("magic_carpet", 300, 500);
        PlayerState rider = new PlayerState(1, "rider", 300, 500);
        assertTrue(world.mount(rider, carpet.id, p));

        PlayerInput upRight = new PlayerInput(false, true, true, false, 1);
        double startX = carpet.x, startY = carpet.y;
        for (int i = 0; i < 90; i++) {
            world.driveVehicle(carpet, rider, upRight, p, 1.0 / 60);
            world.step(1.0 / 60, List.of(rider), p);
        }
        assertTrue(carpet.x > startX + 150, "flew right");
        assertTrue(carpet.y < startY - 100, "and climbed — no gravity while ridden");
    }

    @Test
    void drillMachinesGrindThroughTerrain() {
        Level lvl = flatLevel(60, 10);
        int dirt = BlockRegistry.standard().get("dirt").id();
        // A solid wall ahead of the drill.
        for (int r = 4; r < lvl.height - 1; r++) {
            for (int c = 14; c < 20; c++) lvl.tiles[r][c] = dirt;
        }
        GameProfile p = profile();
        World world = new World(lvl);
        double floorTop = (lvl.height - 1) * 32;
        Vehicle drill = world.spawnVehicle("drill_machine", 10 * 32, floorTop - 42);
        PlayerState rider = new PlayerState(1, "rider", 10 * 32, floorTop - 60);
        assertTrue(world.mount(rider, drill.id, p));

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        for (int i = 0; i < 240; i++) {
            world.driveVehicle(drill, rider, right, p, 1.0 / 60);
            world.step(1.0 / 60, List.of(rider), p);
        }
        assertTrue(drill.x > 14 * 32, "the drill bored into the wall, x=" + drill.x);
        assertFalse(world.pollBlockChanges().isEmpty(),
                "each ground tile broadcasts as an authoritative block change");
        assertTrue(world.items().stream().anyMatch(d -> "dirt".equals(d.key)),
                "drilled blocks drop their items");
    }

    @Test
    void warDragonsBreatheFireOnACooldown() {
        World world = new World(flatLevel(60, 20));
        GameProfile p = profile();
        Vehicle dragon = world.spawnVehicle("war_dragon", 300, 400);
        PlayerState rider = new PlayerState(1, "rider", 300, 400);
        assertTrue(world.mount(rider, dragon.id, p));

        Projectile breath = world.vehicleShoot(dragon, rider, 800, 400);
        assertNotNull(breath, "the dragon fires");
        assertEquals("fireball", breath.def.key());
        assertEquals(rider.id, breath.ownerId, "the rider owns the shot (kill credit, PvP rules)");
        assertNull(world.vehicleShoot(dragon, rider, 800, 400), "cooldown gates the next breath");

        Vehicle horse = world.spawnVehicle("horse", 500, 400);
        assertNull(world.vehicleShoot(horse, rider, 800, 400), "horses are unarmed");
    }

    @Test
    void emptyVehiclesPackBackIntoTheirItems() {
        World world = new World(flatLevel(60, 10));
        Vehicle horse = world.spawnVehicle("horse", 300, 240);
        assertNotNull(world.packUpVehicle(horse.x + 20, horse.y + 20, true));
        assertTrue(world.vehicles().isEmpty());
        assertTrue(world.items().stream().anyMatch(d -> "horse_saddle".equals(d.key)),
                "the horse folded back into its saddle");

        Vehicle ridden = world.spawnVehicle("horse", 300, 240);
        PlayerState rider = new PlayerState(1, "rider", 300, 240);
        assertTrue(world.mount(rider, ridden.id, profile()));
        assertNull(world.packUpVehicle(ridden.x + 20, ridden.y + 20, true),
                "a ridden vehicle can't be packed up under its rider");
    }

    // --- wire form ---------------------------------------------------------------

    @Test
    void vehiclesRideTheWire() {
        Vehicle horse = new Vehicle(9, VehicleRegistry.standard().get("horse"), 100, 200);
        EntityView empty = EntityView.fromMap(Json.asObject(
                Json.parse(Json.stringifyCompact(horse.toMap()))));
        assertEquals(9, empty.id);
        assertEquals("horse", empty.key);
        assertEquals(-1, empty.rider, "riderless on the wire");

        horse.riderId = 4;
        EntityView ridden = EntityView.fromMap(Json.asObject(
                Json.parse(Json.stringifyCompact(horse.toMap()))));
        assertEquals(4, ridden.rider);

        String state = Protocol.state(1, List.of(), List.of(), List.of(), List.of(),
                List.of(horse.toMap()), 0.25);
        Map<String, Object> decoded = Protocol.decode(state);
        assertTrue(decoded.get("veh") instanceof List<?> l && l.size() == 1,
                "snapshots carry the vehicle list");
    }

    // --- end-to-end online ride ---------------------------------------------------

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 8_000_000_000L;
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
    void ridingWorksOverTheNetwork() throws IOException {
        // A wide flat level with a horse standing at the spawn.
        String levelJson = """
                {
                  "name": "Stable",
                  "tileSize": 32,
                  "tiles": [
                    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                    [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]
                  ],
                  "spawn": { "x": 64, "y": 96 },
                  "entities": [ { "kind": "vehicle", "type": "horse", "x": 80, "y": 84 } ]
                }
                """;
        GameProfile profile = new GameProfile("Stable Type");
        profile.gravityEnabled = true;
        GameServer server = new GameServer(profile, levelJson);
        server.start(0);
        try (GameClient client = GameClient.connect("127.0.0.1", server.getPort(), "Rider", 3000)) {
            await("horse replicated in snapshots", () -> {
                Snapshot s = client.latest();
                return s != null && !s.vehicles().isEmpty();
            });
            EntityView horse = client.latest().vehicles().get(0);
            assertEquals("horse", horse.key);
            assertEquals(-1, horse.rider);

            client.sendMount(horse.id);
            await("server seats the rider", () -> {
                Snapshot s = client.latest();
                EntityView v = s == null ? null : s.vehicleRiddenBy(client.localId());
                return v != null && v.id == horse.id;
            });

            // Gallop right: both the vehicle and the seated player advance.
            double startX = client.latest().vehicleById(horse.id).x;
            client.sendInput(new PlayerInput(false, true, false, false, 1));
            await("the horse gallops", () -> {
                Snapshot s = client.latest();
                EntityView v = s == null ? null : s.vehicleById(horse.id);
                return v != null && v.x > startX + 100
                        && Math.abs(s.player(client.localId()).x
                        - (v.x + (VehicleRegistry.standard().get("horse").size()
                        - profile.playerSize) / 2)) < 2;
            });

            client.sendInput(new PlayerInput(false, false, false, false, 2));
            client.sendDismount();
            await("dismounted", () -> {
                Snapshot s = client.latest();
                return s != null && s.vehicleRiddenBy(client.localId()) == null;
            });
        } finally {
            server.stop();
        }
    }
}
