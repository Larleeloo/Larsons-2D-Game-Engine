package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.crafting.Recipe;
import com.larsons.engine.crafting.RecipeRegistry;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Brush;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.sim.StatRuleEngine;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the creative-mode/engine feature set: giant chunked
 * levels, AABB collisions, block durability + tools, crafting, stamina/mana,
 * stat rules, brushes, and surface decor.
 */
class EngineFeatureTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // --- giant chunked levels ---------------------------------------------------

    @Test
    void giantLevelsUseSparseChunksAndKeepEdits() {
        Level lvl = Level.empty("Giant", Level.MAX_GIANT_SIZE, Level.MAX_GIANT_SIZE, 32);
        assertTrue(lvl.isChunked(), "65536x65536 must use chunked storage");
        assertEquals(0, lvl.tileAt(60000, 60000));

        assertTrue(lvl.setTile(60000, 60000, 1));
        assertEquals(1, lvl.tileAt(60000, 60000));
        assertTrue(lvl.setTile(3, 5, 2));
        // Only the touched chunks are resident.
        assertTrue(lvl.chunked.loadedCount() <= 4,
                "sparse storage should hold only touched chunks, had "
                        + lvl.chunked.loadedCount());

        // Snapshot/restore protects terrain across play-tests.
        Object snap = lvl.snapshotTiles();
        lvl.setTile(60000, 60000, 0);
        assertEquals(0, lvl.tileAt(60000, 60000));
        lvl.restoreTiles(snap);
        assertEquals(1, lvl.tileAt(60000, 60000));
    }

    @Test
    void giantLevelsSerializeEditedChunksOnly() {
        Level lvl = Level.empty("Giant Save", 4096, 4096, 32);
        assertTrue(lvl.isChunked());
        lvl.setTile(2000, 2000, 3);
        lvl.setTile(0, 0, 1);
        Level reload = LevelLoader.parse(lvl.toJson());
        assertTrue(reload.isChunked());
        assertEquals(4096, reload.width);
        assertEquals(3, reload.tileAt(2000, 2000));
        assertEquals(1, reload.tileAt(0, 0));
        assertEquals(0, reload.tileAt(1234, 1234));
    }

    @Test
    void giantGenerationIsLazyAndDeterministic() {
        Level a = LevelGenerator.generate("A", 65536, 65536, 32, 12345);
        Level b = LevelGenerator.generate("B", 65536, 65536, 32, 12345);
        assertTrue(a.isChunked());
        assertEquals(0, a.chunked.loadedCount(), "generation must be lazy");

        // Sampling tiles materializes only the touched chunks, identically
        // for the same seed. Row ~80% down is always below the surface line.
        boolean sawTerrain = false;
        for (int c = 100; c < 160; c++) {
            for (int r = 52000; r < 52060; r++) {
                assertEquals(a.tileAt(c, r), b.tileAt(c, r));
                if (a.tileAt(c, r) > 0) sawTerrain = true;
            }
        }
        assertTrue(sawTerrain, "the deep sample region should contain terrain");
        assertTrue(a.chunked.loadedCount() < 20, "only sampled chunks should load");

        // A save of an untouched generated world stays tiny and reloads with
        // the same generator.
        Level reload = LevelLoader.parse(a.toJson());
        assertEquals(a.tileAt(130, 52030), reload.tileAt(130, 52030));
    }

    // --- AABB collisions ---------------------------------------------------------

    /** A small arena: floor at row 8, a wall column at col 5 (rows 5..7). */
    private static Level arena() {
        Level lvl = Level.empty("Arena", 12, 10, 32);
        for (int c = 0; c < lvl.width; c++) lvl.setTile(c, 8, 3);
        for (int r = 5; r <= 7; r++) lvl.setTile(5, r, 3);
        return lvl;
    }

    @Test
    void wallsStopSidewaysMovement() {
        Level lvl = arena();
        GameProfile p = new GameProfile("t");
        PlayerState s = new PlayerState(0, "", 3 * 32, 7 * 32);
        PlayerInput in = new PlayerInput(false, true, false, false, 1); // hold right
        for (int i = 0; i < 240; i++) {
            PlayerPhysics.step(s, in, lvl, p, com.larsons.engine.graphics.Perspective.SIDE_SCROLL,
                    1 / 120.0);
        }
        // The wall at col 5 must stop the player flush against it (the
        // hitbox is slightly under one tile, so flush is 5*32 - playerSize).
        assertTrue(s.x <= 5 * 32 - p.playerSize + 0.01,
                "player passed through the wall: x=" + s.x);
        assertTrue(s.x > 4 * 32 - 8, "player should reach the wall: x=" + s.x);
    }

    @Test
    void ceilingsStopJumps() {
        Level lvl = Level.empty("Ceiling", 8, 8, 32);
        for (int c = 0; c < lvl.width; c++) {
            lvl.setTile(c, 6, 3); // floor
            lvl.setTile(c, 3, 3); // low ceiling
        }
        GameProfile p = new GameProfile("t");
        PlayerState s = new PlayerState(0, "", 2 * 32, 5 * 32);
        PlayerInput jump = new PlayerInput(false, false, true, false, 1);
        double minY = s.y;
        for (int i = 0; i < 240; i++) {
            PlayerPhysics.step(s, jump, lvl, p,
                    com.larsons.engine.graphics.Perspective.SIDE_SCROLL, 1 / 120.0);
            minY = Math.min(minY, s.y);
        }
        // The head may never enter the ceiling row (tiles end at y=4*32).
        assertTrue(minY >= 4 * 32 - 0.5, "player clipped into the ceiling: minY=" + minY);
    }

    @Test
    void sprintingDrainsStaminaAndRestingRestoresIt() {
        Level lvl = arena();
        GameProfile p = new GameProfile("t");
        PlayerState s = new PlayerState(0, "", 32, 7 * 32);
        PlayerInput run = new PlayerInput(true, false, false, false, 1);
        run.sprint = true;
        for (int i = 0; i < 120; i++) {
            PlayerPhysics.step(s, run, lvl, p,
                    com.larsons.engine.graphics.Perspective.SIDE_SCROLL, 1 / 120.0);
        }
        assertTrue(s.stamina < PlayerState.MAX_STAMINA - 10, "sprinting should cost stamina");
        double drained = s.stamina;
        PlayerInput idle = new PlayerInput(false, false, false, false, 2);
        for (int i = 0; i < 120; i++) {
            PlayerPhysics.step(s, idle, lvl, p,
                    com.larsons.engine.graphics.Perspective.SIDE_SCROLL, 1 / 120.0);
        }
        assertTrue(s.stamina > drained, "resting should restore stamina");
    }

    // --- block durability + tools --------------------------------------------------

    @Test
    void miningTakesTimeAndToolsSpeedItUp() {
        Level lvl = arena();
        World world = new World(lvl);
        Block stone = lvl.blocks.get("stone");
        assertTrue(stone.hardness() > 1, "stone should take over a second bare-handed");
        assertEquals("pickaxe", stone.tool());

        // Bare hands: not broken after 1s of the 1.6s hardness.
        assertNull(world.continueMining(5, 5, null, false, 0.5));
        assertNull(world.continueMining(5, 5, null, false, 0.5));
        assertTrue(world.miningProgress() > 0);
        // Finish it.
        Block mined = world.continueMining(5, 5, null, false, 0.8);
        assertNotNull(mined);
        assertEquals(0, lvl.tileAt(5, 5));

        // Diamond pickaxe (power 7): one 0.5s stroke breaks the next block.
        ItemDef pick = ItemRegistry.standard().get("diamond_pickaxe");
        assertNotNull(pick);
        assertNotNull(world.continueMining(5, 6, pick, false, 0.5));
        assertEquals(0, lvl.tileAt(5, 6));
    }

    // --- crafting -------------------------------------------------------------------

    @Test
    void recipesCombineIngredientsIntoNewItems() {
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("oak_log", 2);

        Recipe planks = RecipeRegistry.standard().forStation(Recipe.STATION_CRAFTING).stream()
                .filter(r -> r.output().equals("planks")
                        && r.inputs().get(0).key().equals("oak_log"))
                .findFirst().orElseThrow();
        assertTrue(planks.canCraft(inv));
        assertEquals(0, planks.craft(inv));
        assertEquals(1, inv.totalOf("oak_log"));
        assertEquals(4, inv.totalOf("planks"));

        // Alchemy: smelt an ingot.
        inv.add("iron_ore", 1);
        inv.add("coal", 1);
        Recipe smelt = RecipeRegistry.standard().forStation(Recipe.STATION_ALCHEMY).stream()
                .filter(r -> r.output().equals("iron_ingot"))
                .findFirst().orElseThrow();
        assertEquals(0, smelt.craft(inv));
        assertEquals(1, inv.totalOf("iron_ingot"));
        assertEquals(0, inv.totalOf("iron_ore"));
        // Missing ingredients refuse to craft.
        assertEquals(-1, smelt.craft(inv));
    }

    @Test
    void magicWeaponsSpendManaInsteadOfAmmo() {
        Level lvl = arena();
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("fire_staff", 1);
        inv.select(0);
        PlayerState s = new PlayerState(0, "", 64, 64);
        s.mana = 5; // not enough
        assertNull(world.playerShoot(s, inv, 300, 64));
        s.mana = 100;
        assertNotNull(world.playerShoot(s, inv, 300, 64));
        assertTrue(s.mana < 100, "casting should spend mana");
    }

    // --- stat rules -------------------------------------------------------------------

    @Test
    void statRulesFireRewardsAndConsumeItems() {
        StatRule once = new StatRule("blocks_mined", 3, "apple", 2, null, 0, false, true);
        StatRule trade = new StatRule("blocks_mined", 1, "iron_ingot", 1, "stone", 5, true, false);
        StatRuleEngine engine = new StatRuleEngine(List.of(once, trade));
        PlayerStats stats = new PlayerStats();
        Inventory inv = new Inventory(ItemRegistry.standard());

        assertTrue(engine.update(stats, inv).isEmpty());
        stats.add("blocks_mined", 3);
        // The one-shot fires; the trade waits for 5 stone in the bag.
        assertEquals(1, engine.update(stats, inv).size());
        assertEquals(2, inv.totalOf("apple"));
        assertTrue(engine.update(stats, inv).isEmpty(), "one-shot must not re-fire");

        inv.add("stone", 12);
        assertEquals(1, engine.update(stats, inv).size());
        assertEquals(7, inv.totalOf("stone"), "the trade consumes its stone");
        assertEquals(1, inv.totalOf("iron_ingot"));
        // Repeating rule: next multiple already reached (3 >= 2×1), pays again.
        assertEquals(1, engine.update(stats, inv).size());
        assertEquals(2, inv.totalOf("stone"));
    }

    // --- brushes -----------------------------------------------------------------------

    @Test
    void brushShapesStampMultipleCells() {
        assertEquals(1, Brush.cells(Brush.Shape.SQUARE, 1, 10, 10).size());
        assertEquals(9, Brush.cells(Brush.Shape.SQUARE, 3, 10, 10).size());
        assertEquals(3, Brush.cells(Brush.Shape.HLINE, 3, 10, 10).size());
        List<int[]> circle = Brush.cells(Brush.Shape.CIRCLE, 5, 10, 10);
        assertTrue(circle.size() > 9 && circle.size() <= 25,
                "circle 5 should be between a 3x3 and 5x5 stamp: " + circle.size());
        assertTrue(circle.stream().anyMatch(c -> c[0] == 10 && c[1] == 10));
    }

    // --- mob navigation ------------------------------------------------------------------

    @Test
    void mobsJumpWallsWhileChasing() {
        // Floor on row 8 with a single-block wall at col 5 — jumpable height.
        Level lvl = Level.empty("Hop", 12, 10, 32);
        for (int c = 0; c < lvl.width; c++) lvl.setTile(c, 8, 3);
        lvl.setTile(5, 7, 3);
        Mob zombie = new Mob(1, MobRegistry.standard().get("zombie"), 3 * 32, 7 * 32);
        PlayerState target = new PlayerState(0, "", 7 * 32, 7 * 32); // in detect range
        boolean jumped = false;
        for (int i = 0; i < 600 && !jumped; i++) {
            zombie.step(lvl, List.of(target), List.of(), true, true, 1 / 60.0);
            if (zombie.vy < -100) jumped = true;
        }
        assertTrue(jumped, "a chasing mob should hop the low wall");
    }

    // --- surface decor -----------------------------------------------------------------

    @Test
    void surfaceDecorSerializesAndFollowsItsBlock() {
        Level lvl = arena();
        lvl.surfaceDecor.add(new SurfaceDecor.Placement(3, 8, SurfaceDecor.Face.UP,
                "grass_tuft", false, SurfaceDecor.Visibility.OPEN_FACE));
        Level reload = LevelLoader.parse(lvl.toJson());
        assertEquals(1, reload.surfaceDecor.size());
        SurfaceDecor.Placement sd = reload.surfaceDecor.get(0);
        assertEquals("grass_tuft", sd.key());
        assertEquals(SurfaceDecor.Face.UP, sd.face());
        assertEquals(SurfaceDecor.Visibility.OPEN_FACE, sd.visibility());
        assertFalse(sd.foreground());

        // Mining the host block removes its decorations.
        reload.setTile(3, 8, 0);
        assertTrue(reload.surfaceDecor.isEmpty());
    }

    @Test
    void statRulesSerializeWithTheLevel() {
        Level lvl = arena();
        lvl.statRules.add(new StatRule("distance_traveled", 1000, "bread", 1,
                null, 0, true, true));
        Level reload = LevelLoader.parse(lvl.toJson());
        assertEquals(1, reload.statRules.size());
        StatRule rule = reload.statRules.get(0);
        assertEquals("distance_traveled", rule.stat());
        assertEquals(1000, rule.threshold());
        assertEquals("bread", rule.rewardItem());
        assertTrue(rule.repeat());
        assertTrue(rule.showBar());
    }

    // --- creative scene smoke test --------------------------------------------------------

    @Test
    void creativeSceneUpdatesAndRendersWithoutErrors() {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Feature Test Type"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register("creative", new CreativeScene(ctx));

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(800, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        scenes.setScene("creative");
        for (int i = 0; i < 20; i++) {
            input.newFrame();
            scenes.update(1.0 / 120.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        g.dispose();
        assertNotNull(scenes.current());
    }
}
