package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.LiquidSim;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three level formats — side-scroller, top-down, isometric — as distinct
 * kinds of level: what each one is built from (the creative palette), how each
 * one round-trips through a saved file, and how the shared objects behave in
 * each of them.
 */
class LevelFormatTest {

    // --- identity & round-tripping ---------------------------------------------

    @Test
    void everyPerspectiveHasExactlyOneFormat() {
        assertEquals(Perspective.values().length, LevelFormat.values().length);
        for (Perspective p : Perspective.values()) {
            assertEquals(p, LevelFormat.of(p).perspective());
        }
    }

    @Test
    void onlyTheSideScrollerSimulatesGravity() {
        assertTrue(LevelFormat.SIDE_SCROLLER.gravity());
        assertFalse(LevelFormat.SIDE_SCROLLER.planar());
        assertTrue(LevelFormat.TOP_DOWN.planar());
        assertTrue(LevelFormat.ISOMETRIC.planar());
    }

    @Test
    void formatSurvivesTheSaveFileRoundTrip() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = format.starterLevel("Round Trip", 20, 12, 32);
            Level loaded = LevelLoader.parse(lvl.toJson());
            assertEquals(format, loaded.format(), "format of " + format);
            assertEquals(format.perspective(), loaded.perspective);
        }
    }

    @Test
    void levelsWrittenBeforeFormatsExistedLoadAsSideScrollers() {
        Level legacy = LevelLoader.parse("""
                { "tileSize": 32, "tiles": [[0,0],[1,1]] }
                """);
        assertEquals(LevelFormat.SIDE_SCROLLER, legacy.format());

        // A file that only names a perspective still resolves to its format.
        Level old = LevelLoader.parse("""
                { "perspective": "ISOMETRIC", "tileSize": 32, "tiles": [[0,0],[1,1]] }
                """);
        assertEquals(LevelFormat.ISOMETRIC, old.format());
    }

    @Test
    void unknownFormatTextFallsBackInsteadOfFailing() {
        assertEquals(LevelFormat.TOP_DOWN, LevelFormat.of("holographic", LevelFormat.TOP_DOWN));
        assertEquals(LevelFormat.ISOMETRIC, LevelFormat.of("isometric", LevelFormat.SIDE_SCROLLER));
        assertEquals(LevelFormat.ISOMETRIC, LevelFormat.of("ISOMETRIC", LevelFormat.SIDE_SCROLLER));
    }

    @Test
    void savedLevelsReportTheirFormatWithoutBeingLoaded(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "formats");
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = format.starterLevel(format.displayName() + " map", 24, 16, 32);
            store.save(lvl);
        }
        assertEquals(3, store.list().size());
        for (LevelFormat format : LevelFormat.values()) {
            // list(format) returns saved file stems; each must report its own
            // format, and each format must claim exactly one of the three.
            List<String> ofFormat = store.list(format);
            assertEquals(1, ofFormat.size(), format + " should have one level");
            assertEquals(format, store.formatOf(ofFormat.get(0)));
        }
    }

    // --- loading a level loads its format ----------------------------------------

    /**
     * A saved level's settings can never contradict the level's own format:
     * the game type's profile only carries the format new levels start in, and
     * applying a level's settings on load must leave the level's format alone.
     */
    @Test
    void savedSettingsCarryTheLevelsOwnFormatNotTheGameTypes() {
        GameProfile gameType = new GameProfile("side-scrolling game type");
        gameType.perspective = Perspective.SIDE_SCROLL;

        Level iso = LevelFormat.ISOMETRIC.starterLevel("Town", 20, 12, 32);
        iso.captureSettings(gameType);
        assertEquals(Perspective.ISOMETRIC, iso.settings.perspective);

        Level reloaded = LevelLoader.parse(iso.toJson());
        assertEquals(LevelFormat.ISOMETRIC, reloaded.format());
        assertEquals(Perspective.ISOMETRIC, reloaded.settings.perspective);

        // Applying those settings is what a scene does on load: it must arrive
        // in the level's format, not the game type's.
        gameType.applyFeaturesFrom(reloaded.settings);
        assertEquals(Perspective.ISOMETRIC, gameType.perspective);
    }

    /**
     * The creative-mode request the main menu makes is consumed once, so
     * re-entering the editor (from a paused game, say) stays on the level
     * being edited instead of restarting the chosen format.
     */
    @Test
    void theRequestedCreativeFormatIsConsumedOnce(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        assertNull(ctx.takeCreativeFormat(), "nothing requested by default");

        ctx.setCreativeFormat(LevelFormat.TOP_DOWN);
        assertEquals(LevelFormat.TOP_DOWN, ctx.takeCreativeFormat());
        assertNull(ctx.takeCreativeFormat(), "the request is spent");
    }

    // --- the creative palettes --------------------------------------------------

    @Test
    void pathsAndWallsBelongToThePlanViewFormatsOnly() {
        for (String key : LevelFormat.planViewBlocks()) {
            assertNotNull(BlockRegistry.standard().get(key), key + " must be a real block");
            assertFalse(LevelFormat.SIDE_SCROLLER.allowsBlock(key),
                    key + " is plan-view geometry");
            assertTrue(LevelFormat.TOP_DOWN.allowsBlock(key));
            assertTrue(LevelFormat.ISOMETRIC.allowsBlock(key));
        }
        assertTrue(LevelFormat.planViewBlocks().contains("stone_path"));
        assertTrue(LevelFormat.planViewBlocks().contains("hedge_wall"));
    }

    /**
     * Only the path/wall families are format-specific: every other block — and
     * so every mob, item, decoration and light built on the same palette — is
     * offered in all three creative modes.
     */
    @Test
    void everyOtherBlockIsPaintableInEveryFormat() {
        for (Block b : BlockRegistry.standard().all()) {
            if (LevelFormat.planViewBlocks().contains(b.key())) continue;
            for (LevelFormat format : LevelFormat.values()) {
                assertTrue(format.allowsBlock(b.key()),
                        b.key() + " should paint in " + format);
            }
        }
    }

    /** Hiding a family from a palette must not stop a level from using it. */
    @Test
    void aSideScrollerStillRendersAndCollidesWithWallsItAlreadyHas() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Has Walls", 20, 12, 32);
        int wall = lvl.blocks.get("stone_wall").id();
        assertTrue(lvl.setTile(5, 5, wall));
        assertTrue(lvl.solidAt(5, 5));
        assertNotNull(lvl.colorFor(wall));
        assertEquals(LevelFormat.SIDE_SCROLLER, LevelLoader.parse(lvl.toJson()).format());
    }

    // --- starter canvases & generators ------------------------------------------

    @Test
    void starterCanvasesMatchTheirFormat() {
        Level side = LevelFormat.SIDE_SCROLLER.starterLevel("Side", 20, 12, 32);
        assertTrue(side.solidAt(10, side.height - 1), "side-scroll canvas has a floor");
        assertFalse(side.solidAt(10, 0), "and open sky above it");

        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level plan = format.starterLevel("Plan", 20, 12, 32);
            assertTrue(plan.solidAt(0, 6), "plan-view canvas is walled at the edge");
            assertTrue(plan.solidAt(19, 6));
            assertTrue(plan.solidAt(10, 0));
            assertFalse(plan.solidAt(10, 6), "and open in the middle");
        }
    }

    @Test
    void theMazeGeneratorBuildsForThePlanViewFormats() {
        assertFalse(LevelFormat.SIDE_SCROLLER.defaultsToMaze());
        assertTrue(LevelFormat.TOP_DOWN.defaultsToMaze());
        assertTrue(LevelFormat.ISOMETRIC.defaultsToMaze());

        Level maze = LevelGenerator.generateMaze("Maze", 21, 21, 32, 7L,
                LevelFormat.ISOMETRIC);
        assertEquals(LevelFormat.ISOMETRIC, maze.format());
    }

    // --- shared objects behaving in every format --------------------------------

    /**
     * Mined drops pop out of the block in every format: a gravity world arcs
     * them upward, a plan-view world skids them across the floor — never "up
     * the screen", which is not a direction a top-down level has.
     */
    @Test
    void minedDropsScatterOnThePlaneAndArcUnderGravity() {
        World side = worldOf(LevelFormat.SIDE_SCROLLER);
        side.mineBlock(3, 3, true);
        DroppedItem sideDrop = side.items().get(0);
        assertTrue(sideDrop.vy < 0, "gravity worlds toss drops upward to fall back");

        World plan = worldOf(LevelFormat.TOP_DOWN);
        plan.mineBlock(3, 3, true);
        assertEquals(1, plan.items().size());
        DroppedItem planDrop = plan.items().get(0);
        assertTrue(Math.hypot(planDrop.vx, planDrop.vy) > 0, "the drop still pops out");

        // Successive drops fan out instead of all sliding the same way.
        plan.mineBlock(4, 3, true);
        DroppedItem second = plan.items().get(1);
        assertNotEquals(Math.atan2(planDrop.vy, planDrop.vx),
                Math.atan2(second.vy, second.vx), 1e-6);
    }

    @Test
    void planViewDropsSlideToRestInsteadOfFalling() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Drops", 20, 12, 32);
        DroppedItem drop = new DroppedItem(1, "coal", 1, 160, 160).toss(0, -260, false);
        for (int i = 0; i < 240; i++) drop.step(lvl, false, 1 / 60.0);
        assertTrue(Math.abs(drop.vx) < 1 && Math.abs(drop.vy) < 1, "the skid damps out");
        assertTrue(drop.y > 0 && drop.y < (lvl.height - 1) * 32,
                "and it stays on the plane instead of sinking to the bottom edge");
    }

    /**
     * A liquid source pools outward on a plane. Poured down a side-scroller it
     * reaches the floor below; on a top-down map "below" is just another
     * direction, so the same source spreads in all four.
     */
    @Test
    void liquidsPourDownwardUnderGravityAndPoolOutwardOnAPlane() {
        Level side = openLevel(LevelFormat.SIDE_SCROLLER);
        int water = side.blocks.get("water").id();
        side.setTile(10, 2, water);
        settleLiquids(side, true);
        assertFalse(isWater(side, 9, 2), "gravity pulls the stream down, not sideways in mid-air");
        assertTrue(isWater(side, 10, 3), "it falls");

        Level plan = openLevel(LevelFormat.TOP_DOWN);
        plan.setTile(10, 2, water);
        settleLiquids(plan, false);
        assertTrue(isWater(plan, 9, 2) && isWater(plan, 11, 2),
                "a plan-view source spreads along both sides");
        assertTrue(isWater(plan, 10, 1) && isWater(plan, 10, 3),
                "and up and down the plane");
    }

    @Test
    void wallsStopAPlanViewLiquidFromSpreadingThrough() {
        Level plan = openLevel(LevelFormat.TOP_DOWN);
        int wall = plan.blocks.get("stone_wall").id();
        for (int r = 0; r < plan.height; r++) plan.setTile(12, r, wall);
        plan.setTile(10, 5, plan.blocks.get("water").id());
        settleLiquids(plan, false);
        assertTrue(isWater(plan, 11, 5), "it reaches the wall");
        assertFalse(isWater(plan, 13, 5), "and stops there");
    }

    @Test
    void sandOnlyFallsWhereTheFormatHasGravity() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = openLevel(format);
            int sand = lvl.blocks.get("sand").id();
            lvl.setTile(10, 2, sand);
            settleLiquids(lvl, format.gravity());
            if (format.gravity()) {
                assertEquals(0, lvl.tileAt(10, 2), "sand falls in " + format);
            } else {
                assertEquals(sand, lvl.tileAt(10, 2), "sand stays put in " + format);
            }
        }
    }

    /** Mobs walk the whole plane in the plan-view formats, not just sideways. */
    @Test
    void mobsNavigateBothAxesOnAPlane() {
        Level plan = openLevel(LevelFormat.TOP_DOWN);
        MobDef def = MobRegistry.standard().get("zombie");
        assertNotNull(def);
        Mob mob = new Mob(1, def, 10 * 32, 8 * 32);
        PlayerState target = new PlayerState(1, "p", 10 * 32, 2 * 32); // straight "up"

        double startY = mob.y;
        for (int i = 0; i < 120; i++) {
            mob.step(plan, List.of(target), List.of(), false, true, 1 / 60.0);
        }
        assertTrue(mob.y < startY - 8, "the mob chased along the second axis");
    }

    /**
     * The shared object kinds all do their job in every format: items are
     * picked up, decorations are harvested, storage blocks hold their
     * contents, and hazards hurt. None of these are gravity features, so none
     * of them may quietly stop working when the level isn't a side-scroller.
     */
    @Test
    void itemsDecorationsContainersAndHazardsWorkInEveryFormat() {
        for (LevelFormat format : LevelFormat.values()) {
            GameProfile profile = profileFor(format);
            Level lvl = openLevel(format);
            lvl.entities.add(new Level.EntitySpawn("decor_bg", "oak_tree", 300, 300));
            World world = new World(lvl);

            // Items: a drop beside the player is collected.
            int[] pickedUp = {0};
            world.setPickupListener((player, key, count) -> pickedUp[0] += count);
            DroppedItem drop = world.spawnItem("coal", 2, 200, 200);
            assertNotNull(drop, "items spawn in " + format);
            drop.pickupDelay = 0;
            PlayerState p = new PlayerState(1, "p", 200, 200);
            world.step(1 / 60.0, List.of(p), profile);
            assertEquals(2, pickedUp[0], "pickup works in " + format);

            // Decorations: harvestable scenery breaks down into resources.
            World.ChopResult result = World.ChopResult.NONE;
            for (int i = 0; i < 8 && result != World.ChopResult.BROKEN; i++) {
                result = world.chopDecor(300, 300 - 32, true, true);
            }
            assertEquals(World.ChopResult.BROKEN, result, "chopping works in " + format);

            // Containers: a chest's second inventory lives in the level data.
            int chest = lvl.blocks.get("chest").id();
            lvl.setTile(6, 6, chest);
            lvl.openContainer(6, 6).add(new com.larsons.engine.entity.ItemStack("coal", 3));
            assertEquals(1, lvl.containerAt(6, 6).size(), "storage works in " + format);
            world.mineBlock(6, 6, true);
            assertNull(lvl.containerAt(6, 6), "mining a chest spills it in " + format);

            // Hazards: standing in lava hurts wherever the level is played.
            lvl.setTile(4, 4, lvl.blocks.get("lava").id());
            PlayerState burned = new PlayerState(2, "b", 4 * 32 + 8, 4 * 32 + 8);
            double before = burned.health;
            world.step(1 / 60.0, List.of(burned), profile);
            assertTrue(burned.health < before, "hazards hurt in " + format);
        }
    }

    /**
     * A ridden vehicle steers the whole plane in the plan-view formats — a
     * mount that could only be driven left and right would be unusable in a
     * top-down or isometric level.
     */
    @Test
    void vehiclesSteerBothAxesOnAPlane() {
        Level plan = openLevel(LevelFormat.TOP_DOWN);
        World world = new World(plan);
        GameProfile profile = profileFor(LevelFormat.TOP_DOWN);
        com.larsons.engine.entity.Vehicle horse = world.spawnVehicle("horse", 300, 300);
        assertNotNull(horse);
        PlayerState rider = new PlayerState(1, "p", 300, 300);

        com.larsons.engine.sim.PlayerInput up = new com.larsons.engine.sim.PlayerInput(
                false, false, true, false, 1);
        double startY = horse.y;
        for (int i = 0; i < 60; i++) world.driveVehicle(horse, rider, up, profile, 1 / 60.0);
        assertTrue(horse.y < startY - 8, "the mount rode up the plane");
    }

    /** Levels of different formats are the same kind of object to the world. */
    @Test
    void everyFormatBuildsAPlayableWorld() {
        for (LevelFormat format : LevelFormat.values()) {
            World world = worldOf(format);
            assertSame(format, world.level.format());
            PlayerState p = new PlayerState(1, "p", 96, 96);
            GameProfile profile = profileFor(format);
            for (int i = 0; i < 30; i++) world.step(1 / 60.0, List.of(p), profile);
            assertTrue(p.health > 0, format + " world steps without hurting the player");
        }
    }

    // --- helpers ----------------------------------------------------------------

    private static GameProfile profileFor(LevelFormat format) {
        GameProfile p = new GameProfile("format-test");
        p.perspective = format.perspective();
        p.normalize();
        return p;
    }

    /** A walled canvas with a patch of stone to mine at (3,3)/(4,3). */
    private static World worldOf(LevelFormat format) {
        Level lvl = format.starterLevel("World", 20, 12, 32);
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(3, 3, stone);
        lvl.setTile(4, 3, stone);
        return new World(lvl);
    }

    /** An empty (unwalled) canvas, so spreading isn't confined by the border. */
    private static Level openLevel(LevelFormat format) {
        Level lvl = Level.empty("Open", 20, 12, 32);
        lvl.setFormat(format);
        return lvl;
    }

    private static void settleLiquids(Level level, boolean gravityOn) {
        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 40; i++) sim.step(level, gravityOn, LiquidSim.TICK);
    }

    private static boolean isWater(Level level, int col, int row) {
        Block b = level.blockAt(col, row);
        return b != null && b.liquid() && "water".equals(level.blocks.sourceFor(b).key());
    }
}
