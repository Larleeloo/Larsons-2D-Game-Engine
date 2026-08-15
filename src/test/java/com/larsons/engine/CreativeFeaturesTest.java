package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.level.PerlinNoise;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.LiquidSim;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the creative-mode feature set: the expanded block
 * registry, liquid simulation, swimming physics, level resizing, multiplayer
 * spawn points, the external door directory, decorations, and the Perlin
 * level generator.
 */
class CreativeFeaturesTest {

    // --- block registry -----------------------------------------------------------

    @Test
    void blockRegistryIsGreatlyExpandedWithStableLegacyIds() {
        BlockRegistry r = BlockRegistry.standard();
        // Legacy ids must never move.
        assertEquals("dirt", r.get(1).key());
        assertEquals("grass", r.get(2).key());
        assertEquals("stone", r.get(3).key());
        assertEquals("water", r.get(4).key());
        assertEquals("sand", r.get(5).key());
        assertEquals("torch", r.get(19).key());
        assertEquals("crystal", r.get(23).key());
        // The expanded palette.
        assertTrue(r.all().size() >= 80, "expected 80+ blocks, got " + r.all().size());
        assertNotNull(r.get("obsidian"));
        assertNotNull(r.get("glowstone"));
        assertNotNull(r.get("diamond_ore"));
        assertTrue(r.get("spikes").damage() > 0);
        // Liquids and their hidden flow twins.
        assertTrue(r.get("water").liquid());
        assertTrue(r.get("lava").liquid());
        assertTrue(r.get("lava").damage() > 0);
        assertEquals("water_flow", r.flowFor(r.get("water")).key());
        assertEquals("lava", r.sourceFor(r.get("lava_flow")).key());
        assertTrue(r.get("water_flow").isFlow());
        assertFalse(r.get("water").isFlow());
    }

    // --- level resize (the editor's size sliders) ----------------------------------

    @Test
    void resizePreservesTilesAndDropsOutOfBoundsEntities() {
        Level lvl = Level.empty("t", 10, 8, 32);
        lvl.tiles()[2][3] = 5;
        lvl.entities.add(new Level.EntitySpawn("mob", "zombie", 5 * 32, 2 * 32));
        lvl.entities.add(new Level.EntitySpawn("mob", "zombie", 9 * 32, 2 * 32));
        lvl.spawnX = 9 * 32;

        lvl.resize(6, 12);
        assertEquals(6, lvl.width);
        assertEquals(12, lvl.height);
        assertEquals(5, lvl.tileAt(3, 2), "overlapping content survives");
        assertEquals(1, lvl.entities.size(), "entities past the new edge are dropped");
        assertTrue(lvl.spawnX < 6 * 32, "spawn clamps back inside");

        lvl.resize(20, 12);
        assertEquals(5, lvl.tileAt(3, 2));
        assertEquals(0, lvl.tileAt(15, 2), "grown area starts empty");
    }

    // --- multiplayer spawn points ---------------------------------------------------

    @Test
    void spawnPointsDealOutRoundRobin() {
        Level lvl = Level.empty("t", 10, 10, 32);
        lvl.spawnX = 11;
        lvl.spawnY = 22;
        assertEquals(11, lvl.spawnPointFor(3)[0], "no mp spawns: the single marker");

        lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn", 100, 50));
        lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn", 200, 50));
        assertEquals(100, lvl.spawnPointFor(0)[0]);
        assertEquals(200, lvl.spawnPointFor(1)[0]);
        assertEquals(100, lvl.spawnPointFor(2)[0], "wraps around");
    }

    // --- liquids ---------------------------------------------------------------------

    private static Level liquidArena() {
        Level lvl = Level.empty("liquid", 12, 8, 32);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 0; c < lvl.width; c++) lvl.tiles()[lvl.height - 1][c] = stone;
        return lvl;
    }

    private static void tickLiquids(LiquidSim sim, Level lvl, int ticks) {
        for (int i = 0; i < ticks; i++) sim.step(lvl, LiquidSim.TICK);
    }

    @Test
    void waterFallsSpreadsAndDrainsWhenTheSourceIsRemoved() {
        Level lvl = liquidArena();
        int water = lvl.blocks.get("water").id();
        int flow = lvl.blocks.get("water_flow").id();
        lvl.tiles()[2][5] = water;

        LiquidSim sim = new LiquidSim();
        tickLiquids(sim, lvl, 2);
        assertEquals(flow, lvl.tileAt(5, 3), "water falls into the cell below");

        tickLiquids(sim, lvl, 20);
        assertEquals(flow, lvl.tileAt(5, 6), "the stream reaches the floor");
        assertEquals(flow, lvl.tileAt(7, 6), "and spreads along it");
        assertEquals(0, lvl.tileAt(11, 6), "but only to the liquid's range");

        lvl.setTile(5, 2, 0); // yank the source
        tickLiquids(sim, lvl, 3);
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                assertNotEquals(flow, lvl.tileAt(c, r), "all flow drains at " + c + "," + r);
            }
        }
    }

    @Test
    void waterQuenchesLavaIntoStoneAndObsidian() {
        Level lvl = liquidArena();
        int water = lvl.blocks.get("water").id();
        int lava = lvl.blocks.get("lava").id();
        int lavaFlow = lvl.blocks.get("lava_flow").id();
        lvl.tiles()[6][4] = water;
        lvl.tiles()[6][5] = lava;
        lvl.tiles()[6][7] = lavaFlow;
        lvl.tiles()[6][6] = water;

        new LiquidSim().step(lvl, LiquidSim.TICK);
        assertEquals(lvl.blocks.get("obsidian").id(), lvl.tileAt(5, 6),
                "source lava beside water becomes obsidian");
        assertEquals(lvl.blocks.get("stone").id(), lvl.tileAt(7, 6),
                "flowing lava beside water becomes stone");
    }

    @Test
    void playerSwimsInsteadOfFallingThroughWater() {
        Level lvl = liquidArena();
        int water = lvl.blocks.get("water").id();
        for (int r = 2; r < 7; r++) {
            for (int c = 2; c < 9; c++) lvl.tiles()[r][c] = water;
        }
        GameProfile p = new GameProfile("swim");
        PlayerState s = new PlayerState(0, "", 4 * 32, 3 * 32);

        // Sink passively: speed stays clamped far below terminal fall speed.
        PlayerInput idle = new PlayerInput(false, false, false, false, 1);
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(s, idle, lvl, p, p.perspective, 1 / 60.0);
        }
        assertTrue(s.vy <= PlayerPhysics.SWIM_MAX_SINK + 0.001,
                "sink speed is capped, was " + s.vy);

        // Stroke upward.
        double before = s.y;
        PlayerInput up = new PlayerInput(false, false, true, false, 2);
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(s, up, lvl, p, p.perspective, 1 / 60.0);
        }
        assertTrue(s.y < before, "holding up swims upward");
    }

    @Test
    void lavaBurnsPlayersAndRespawnsThemAtSpawnPoints() {
        Level lvl = liquidArena();
        lvl.tiles()[6][5] = lvl.blocks.get("lava").id();
        lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn", 64, 32));
        GameProfile p = new GameProfile("burn");
        World world = new World(lvl);
        PlayerState player = new PlayerState(0, "", 5 * 32, 6 * 32);

        world.step(1.0, List.of(player), p);
        assertTrue(player.health < PlayerState.MAX_HEALTH, "lava contact hurts");

        player.health = 0.5;
        world.step(1.0, List.of(player), p); // burns past zero -> respawn
        assertEquals(PlayerState.MAX_HEALTH, player.health);
        assertEquals(64, player.x, "respawns at the painted mp spawn");
    }

    // --- doors -------------------------------------------------------------------------

    @Test
    void doorDirectoryPersistsBesideTheGameTypesLevels(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "My Game");
        DoorDirectory doors = new DoorDirectory(store);
        assertTrue(doors.all().isEmpty());

        String key = doors.freshKey();
        doors.put(new DoorLink(key, "To the Caves", "caves", new Color(70, 130, 200)));
        doors.put(new DoorLink(doors.freshKey(), "Boss Arena", "arena", null));

        DoorDirectory reloaded = new DoorDirectory(store);
        assertEquals(2, reloaded.all().size());
        assertEquals("caves", reloaded.get(key).targetLevel());
        assertEquals("To the Caves", reloaded.get(key).label());

        reloaded.remove(key);
        assertNull(new DoorDirectory(store).get(key));
    }

    @Test
    void doorNearFindsTheClosestPaintedDoor() {
        Level lvl = Level.empty("d", 10, 10, 32);
        lvl.entities.add(new Level.EntitySpawn("door", "door_1", 100, 100));
        lvl.entities.add(new Level.EntitySpawn("door", "door_2", 300, 100));
        assertEquals("door_1", lvl.doorNear(110, 100, 40).type);
        assertNull(lvl.doorNear(200, 100, 40));
    }

    // --- decorations ---------------------------------------------------------------------

    @Test
    void decorRegistryOffersForegroundAndBackgroundScenery() {
        DecorRegistry r = DecorRegistry.standard();
        assertTrue(r.all().size() >= 12);
        assertNotNull(r.get("oak_tree"));
        assertNotNull(r.get("boulder"));
        assertTrue(r.get("pine_tree").sizeTiles() > 1);
        // Levels store decorations as plain entity spawns, layer in the kind.
        Level lvl = Level.empty("d", 8, 8, 32);
        lvl.entities.add(new Level.EntitySpawn("decor_bg", "oak_tree", 64, 128));
        lvl.entities.add(new Level.EntitySpawn("decor_fg", "boulder", 96, 128));
        Level reparsed = com.larsons.engine.level.LevelLoader.parse(lvl.toJson());
        assertEquals(2, reparsed.entities.size());
        assertEquals("decor_fg", reparsed.entities.get(1).kind);
    }

    // --- generator -------------------------------------------------------------------------

    @Test
    void perlinNoiseIsDeterministicAndBounded() {
        PerlinNoise a = new PerlinNoise(7);
        PerlinNoise b = new PerlinNoise(7);
        for (double x = 0; x < 10; x += 0.37) {
            double v = a.fbm(x, x * 0.7, 4, 0.5, 2.0);
            assertEquals(v, b.fbm(x, x * 0.7, 4, 0.5, 2.0), 1e-12);
            assertTrue(v > -1.7 && v < 1.7, "noise in sane range, was " + v);
        }
        assertNotEquals(a.noise(3.3, 4.4), new PerlinNoise(8).noise(3.3, 4.4));
    }

    @Test
    void generatorBuildsAPlayableMetroidvaniaTerrainLevel() {
        Level lvl = LevelGenerator.generate("Gen", 160, 100, 32, 1234);
        assertEquals(160, lvl.width);
        assertEquals(100, lvl.height);
        assertTrue(lvl.registryTiles);

        // Deterministic for the seed.
        Level again = LevelGenerator.generate("Gen", 160, 100, 32, 1234);
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                assertEquals(lvl.tileAt(c, r), again.tileAt(c, r));
            }
        }

        // Spawn stands in open air inside the level.
        int sc = (int) (lvl.spawnX / lvl.tileSize);
        int sr = (int) (lvl.spawnY / lvl.tileSize);
        assertTrue(sc >= 0 && sc < lvl.width && sr >= 0 && sr < lvl.height);
        assertEquals(0, lvl.tileAt(sc, sr), "spawn cell is air");

        // The bottom border contains the lava ocean.
        for (int c = 0; c < lvl.width; c++) {
            assertTrue(lvl.solidAt(c, lvl.height - 1), "solid floor at column " + c);
        }

        // It has the advertised content: caves/air underground, ores, liquids,
        // platforms, torches, decorations, mobs, items, and mp spawns.
        BlockRegistry blocks = lvl.blocks;
        int air = 0, ore = 0, liquid = 0, platform = 0, torch = 0;
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                int id = lvl.tileAt(c, r);
                Block b = blocks.get(id);
                if (id == 0) air++;
                else if (b != null && b.liquid()) liquid++;
                else if (b != null && b.key().endsWith("_ore")) ore++;
                else if (b != null && b.key().equals("platform")) platform++;
                else if (b != null && b.key().equals("torch")) torch++;
            }
        }
        assertTrue(air > lvl.width * lvl.height / 10, "carved caves/rooms exist");
        assertTrue(ore > 20, "ore veins exist: " + ore);
        assertTrue(liquid > 10, "water/lava exist: " + liquid);
        assertTrue(platform > 5, "platform ladders exist: " + platform);
        assertTrue(torch > 0, "rooms are lit");

        int mobs = 0, items = 0, decor = 0, mpSpawns = 0;
        for (Level.EntitySpawn e : lvl.entities) {
            switch (e.kind) {
                case "mob" -> mobs++;
                case "item" -> items++;
                case "decor_bg", "decor_fg" -> decor++;
                case "mp_spawn" -> mpSpawns++;
                default -> { }
            }
        }
        assertTrue(mobs > 0, "mobs placed");
        assertTrue(items > 0, "treasure placed");
        assertTrue(decor > 0, "decorations placed");
        assertEquals(4, mpSpawns, "multiplayer spawn points placed");

        // The generated level survives a save/load round trip.
        Level roundTrip = com.larsons.engine.level.LevelLoader.parse(lvl.toJson());
        assertEquals(lvl.width, roundTrip.width);
        assertEquals(lvl.entities.size(), roundTrip.entities.size());
    }

    // --- the creative scene itself ---------------------------------------------------

    @Test
    void creativeSceneUpdatesAndRendersHeadless() {
        System.setProperty("java.awt.headless", "true");
        var ctx = new com.larsons.engine.config.GameContext(null,
                new com.larsons.engine.config.GameTypeStore());
        ctx.setProfile(new GameProfile("Creative Test"));

        var scenes = new com.larsons.engine.scene.SceneManager();
        scenes.setViewport(800, 600);
        scenes.register("creative", new com.larsons.engine.demo.CreativeScene(ctx));
        scenes.setScene("creative");

        var input = new com.larsons.engine.input.InputManager();
        var frame = new java.awt.image.BufferedImage(800, 600,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = frame.createGraphics();
        for (int i = 0; i < 20; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        g.dispose();
        assertEquals("CreativeScene", scenes.current().name());
    }

    @Test
    void paletteTooltipsAndPlayTestPlayerRenderHeadless() {
        System.setProperty("java.awt.headless", "true");
        var ctx = new com.larsons.engine.config.GameContext(null,
                new com.larsons.engine.config.GameTypeStore());
        ctx.setProfile(new GameProfile("Creative Test"));

        var scenes = new com.larsons.engine.scene.SceneManager();
        scenes.setViewport(800, 600);
        scenes.register("creative", new com.larsons.engine.demo.CreativeScene(ctx));
        scenes.setScene("creative");

        var input = new com.larsons.engine.input.InputManager();
        var src = new java.awt.Container();
        var frame = new java.awt.image.BufferedImage(800, 600,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = frame.createGraphics();

        // Hover the palette grid: the entry under the mouse renders its
        // description tooltip beside the sidebar.
        input.mouseMoved(new java.awt.event.MouseEvent(src,
                java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0, 40, 320, 0, false));
        for (int i = 0; i < 5; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }

        // P enters play-test: the test player simulates and draws with the
        // shared player walk sprite (identical to the play scene's).
        input.keyPressed(new java.awt.event.KeyEvent(src,
                java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
                java.awt.event.KeyEvent.VK_P, 'p'));
        for (int i = 0; i < 10; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        g.dispose();
        assertEquals("CreativeScene", scenes.current().name());
    }
}
