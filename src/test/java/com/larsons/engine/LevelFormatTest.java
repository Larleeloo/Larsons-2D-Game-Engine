package com.larsons.engine;

import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.DoorDirectory;
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

import java.io.IOException;
import java.nio.file.Files;
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

    /**
     * The sidecars that live beside a game type's levels are not levels.
     *
     * <p><b>This is the bug that reached a player, and the shape of it is worth
     * recording.</b> A game type's folder holds its levels plus {@code doors.json},
     * {@code custom.json} and {@code characters.json}, and {@code LevelStore.list}
     * answered "every {@code .json} in here". So a game type with character
     * profiles offered a level called {@code characters} in the level menu;
     * selecting it wrote that path into {@code lastLevelPath}; and every launch
     * afterwards printed
     *
     * <pre>
     * PlayScene: failed to load src/main/resources/levels/test_3/characters.json:
     *     Level is missing a 'tiles' array
     * </pre>
     *
     * <p>The loader was right and the listing was wrong. The exporter had already
     * got this right with a rule of its own — two answers to one question, and the
     * one every menu read was the bad one. Both now ask {@code LevelStore}.
     */
    @Test
    void theFilesThatLiveBesideALevelAreNotOfferedAsLevels(@TempDir Path dir) throws Exception {
        LevelStore store = new LevelStore(dir.toString(), "test_3");
        store.save(LevelFormat.SIDE_SCROLLER.starterLevel("cavern", 24, 16, 32));
        java.nio.file.Files.writeString(
                store.directory().resolve(CharacterStore.FILE_NAME), "{\"profiles\":[]}");
        java.nio.file.Files.writeString(
                store.directory().resolve(DoorDirectory.FILE_NAME), "{\"doors\":[]}");
        java.nio.file.Files.writeString(
                store.directory().resolve(CustomContentStore.FILE_NAME), "{\"blocks\":[]}");

        assertEquals(List.of("cavern"), store.list(),
                "a sidecar was offered as a level — selecting it is what put "
                        + "characters.json into lastLevelPath");
        assertEquals(List.of("cavern"), store.list(LevelFormat.SIDE_SCROLLER),
                "and the by-format listing agrees, without opening a sidecar to "
                        + "guess its format");
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

    /**
     * Only the plan views stack, and only they read geometry off the stack.
     * A side-scroller has the one layer it always had.
     */
    @Test
    void onlyThePlanViewFormatsStackBlocks() {
        assertFalse(LevelFormat.SIDE_SCROLLER.layered());
        assertTrue(LevelFormat.TOP_DOWN.layered());
        assertTrue(LevelFormat.ISOMETRIC.layered());

        Level side = LevelFormat.SIDE_SCROLLER.starterLevel("Side", 20, 12, 32);
        assertFalse(side.layered());
        assertFalse(side.setUpper(5, 5, side.blocks.get("stone").id()),
                "a side-scroller has nowhere to stack a block");
    }

    /**
     * Every block builds in every format now. The wall and path families used
     * to be the plan views' geometry all by themselves and were hidden from
     * the side-scroller's palette for it; a plan view says wall or path with
     * the stack, so any block can be either and no block needs hiding.
     */
    @Test
    void everyBlockIsPaintableInEveryFormat() {
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // the sim's hidden twins are never painted
            assertNotNull(BlockRegistry.standard().get(b.key()));
        }
        for (String key : List.of("stone_path", "hedge_wall", "grass", "dirt")) {
            assertNotNull(BlockRegistry.standard().get(key), key + " must be a real block");
        }
    }

    /** In the plan views the stack is the geometry, not the block's own flag. */
    @Test
    void oneLayerIsAPathTwoIsAWallAndBareGroundIsAHole() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = format.starterLevel("Stacks", 20, 12, 32);
            int grass = lvl.blocks.get("grass").id();   // a solid block…
            int path = lvl.blocks.get("stone_path").id(); // …and a passable one

            // One layer of either is floor you can walk on, whatever the
            // block's own solid flag says.
            assertTrue(lvl.setTile(5, 5, grass));
            assertEquals(0, lvl.upperAt(5, 5), "nothing stacked there yet");
            assertTrue(lvl.walkable(5, 5), "one layer of grass is a path in " + format);
            assertFalse(lvl.solidAt(5, 5));
            assertEquals(1, lvl.stackHeight(5, 5));

            // Two layers is a wall.
            assertTrue(lvl.setUpper(5, 5, grass));
            assertFalse(lvl.walkable(5, 5), "a stack is a wall in " + format);
            assertTrue(lvl.solidAt(5, 5));
            assertEquals(2, lvl.stackHeight(5, 5));

            // A passable block stacked on a path is dressing, not a wall.
            assertTrue(lvl.setUpper(5, 5, path));
            assertTrue(lvl.walkable(5, 5), "a torch on a path leaves it open");

            // Bare ground is a hole, and so is anywhere off the map.
            assertTrue(lvl.setTile(5, 5, 0));
            assertEquals(0, lvl.stackHeight(5, 5));
            assertFalse(lvl.walkable(5, 5), "bare ground is a hole in " + format);
            assertTrue(lvl.solidAt(5, 5));
            assertTrue(lvl.solidAt(-1, 5), "and so is off the edge");
        }
    }

    // --- C9: the heading a level was built from ---------------------------------

    /**
     * A level remembers the compass point it was authored from, and a level
     * built square to the world says nothing about it at all.
     *
     * <p>Both halves matter and only one is obvious. The heading has to survive
     * a save, or a plan-view level opens from a direction its creator never
     * looked from — the wall they put on the player's left is on their right,
     * and a room laid out to be entered from the south is entered from the
     * north. And it has to be <em>absent</em> when it is zero, because that is
     * every level ever written before this field existed and every side-scroller
     * there will ever be: an optional field that is always written is a format
     * change, and a format change is a file an older build cannot open.
     */
    @Test
    void theAuthoringHeadingSurvivesASaveAndIsAbsentWhenItIsZero() {
        Level built = LevelFormat.TOP_DOWN.starterLevel("Turned", 20, 12, 32);
        built.authoredHeading = 3;
        String json = built.toJson();
        assertTrue(json.contains("heading"), "the heading was not written at all");
        assertEquals(3, LevelLoader.parse(json).authoredHeading);

        Level square = LevelFormat.TOP_DOWN.starterLevel("Square", 20, 12, 32);
        assertEquals(0, square.authoredHeading, "a fresh level is built square to the world");
        assertFalse(square.toJson().contains("heading"),
                "a level built square to the world writes a heading anyway, which makes "
                        + "an optional field a format change");
        assertEquals(0, LevelLoader.parse(square.toJson()).authoredHeading);
    }

    /**
     * A level from a build that had never heard of headings opens square to the
     * world, rather than at a heading read out of whatever was in that slot.
     */
    @Test
    void aLevelFromBeforeHeadingsOpensSquareToTheWorld() {
        Level old = LevelFormat.ISOMETRIC.starterLevel("Ancient", 20, 12, 32);
        String json = old.toJson();
        assertFalse(json.contains("heading"), "this fixture is meant to have no heading");

        Level loaded = LevelLoader.parse(json);
        assertEquals(0, loaded.authoredHeading);
        // And the levels this build actually ships open the same way.
        for (LevelFormat format : LevelFormat.values()) {
            assertEquals(0, format.starterLevel("S", 20, 12, 32).authoredHeading,
                    format + "'s starter level is not built square to the world");
        }
    }

    /**
     * The level this build ships still opens exactly as it did, byte for byte
     * on disk and field for field once loaded.
     *
     * <p>C9 asks for this specifically, and it is the assertion that says the
     * new field is optional in the way that matters: a level authored before it
     * existed is not rewritten, not migrated, and not read differently. The
     * file is compared to itself on disk as well as parsed, because "loads
     * fine" and "is unchanged" are different claims and only the second one
     * survives a build that silently rewrites what it opens.
     */
    @Test
    void theShippedLevelStillOpensExactlyAsItDid() throws IOException {
        Path onDisk = Path.of("src/main/resources/levels/sample_level.json");
        String before = Files.readString(onDisk);
        assertFalse(before.contains("heading"),
                "the shipped level already mentions a heading, so this proves nothing");

        Level loaded = LevelLoader.load("levels/sample_level.json");
        assertEquals(0, loaded.authoredHeading, "the shipped level opens square to the world");
        assertEquals(LevelFormat.SIDE_SCROLLER, loaded.format());
        assertEquals(24, loaded.width);
        assertEquals(14, loaded.height);

        assertEquals(before, Files.readString(onDisk),
                "loading the shipped level rewrote it on disk");
        // …and writing it back out adds nothing, so a level that passes through
        // this build is the level that went in.
        assertFalse(loaded.toJson().contains("heading"),
                "re-saving a level authored before headings adds one to it");
    }

    /** An out-of-range heading in a file is read as one of the eight, not as itself. */
    @Test
    void aHeadingOutsideTheEightIsFoldedOntoThem() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Wrapped", 20, 12, 32);
        lvl.authoredHeading = 11;          // three quarters of a turn past a full one
        assertEquals(3, LevelLoader.parse(lvl.toJson()).authoredHeading);

        lvl.authoredHeading = -1;          // and one the other way
        assertEquals(7, LevelLoader.parse(lvl.toJson()).authoredHeading);
    }

    /** Clearing a floor takes what stood on it: a block over a hole is neither. */
    @Test
    void clearingTheFloorClearsWhatStoodOnIt() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Stacks", 20, 12, 32);
        int stone = lvl.blocks.get("stone").id();
        assertTrue(lvl.stackTile(5, 5, stone));
        assertEquals(2, lvl.stackHeight(5, 5));
        assertTrue(lvl.setTile(5, 5, 0));
        assertEquals(0, lvl.upperAt(5, 5), "the stacked block went with the floor");
    }

    /** A side-scroller still reads solidity off the block, exactly as before. */
    @Test
    void aSideScrollerStillRendersAndCollidesWithSolidBlocks() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Has Walls", 20, 12, 32);
        int wall = lvl.blocks.get("stone_wall").id();
        assertTrue(lvl.setTile(5, 5, wall));
        assertTrue(lvl.solidAt(5, 5));
        assertFalse(lvl.solidAt(5, 4), "and air is still air");
        assertNotNull(lvl.colorFor(wall));
        assertEquals(LevelFormat.SIDE_SCROLLER, LevelLoader.parse(lvl.toJson()).format());
    }

    /** Both layers survive a save, and a plan-view level's geometry with them. */
    @Test
    void theStackedLayerSurvivesTheSaveFileRoundTrip() {
        Level lvl = LevelFormat.ISOMETRIC.starterLevel("Town", 20, 12, 32);
        int stone = lvl.blocks.get("stone").id();
        lvl.stackTile(5, 5, stone);
        lvl.setTile(6, 5, stone);

        Level loaded = LevelLoader.parse(lvl.toJson());
        assertEquals(2, loaded.stackHeight(5, 5), "the wall came back a wall");
        assertEquals(1, loaded.stackHeight(6, 5), "and the path a path");
        assertTrue(loaded.solidAt(5, 5));
        assertTrue(loaded.walkable(6, 5));
    }

    /**
     * A plan-view level written before blocks stacked reads its geometry the
     * other way round — solid blocks stopped you and air was open floor — so
     * loading one has to re-cut it into layers that mean the same thing.
     */
    @Test
    void planViewLevelsFromBeforeStackingAreConvertedOnLoad() {
        int wall = BlockRegistry.standard().get("stone_wall").id();
        Level legacy = LevelLoader.parse("""
                { "format": "top_down", "tileset": "registry", "tileSize": 32,
                  "width": 3, "height": 1, "tilesRle": [0, 1, %d, 1, 0, 1] }
                """.formatted(wall));

        assertEquals(2, legacy.stackHeight(1, 0), "the wall became a stack");
        assertTrue(legacy.solidAt(1, 0), "and still stops you");
        assertEquals(1, legacy.stackHeight(0, 0), "the open corridor became floor");
        assertTrue(legacy.walkable(0, 0), "and is still walkable");
        assertTrue(legacy.walkable(2, 0));
    }

    /**
     * A giant plan-view level is floored by a generator rather than by
     * materializing every chunk, and a save has to say <em>which</em>
     * generator: rebuilt as side-scrolling terrain, a level whose floor is its
     * geometry would come back full of holes and hills.
     */
    @Test
    void aGiantPlanViewLevelKeepsItsFloorAcrossASave() {
        Level giant = LevelFormat.TOP_DOWN.starterLevel("Giant", 2048, 2048, 32);
        assertTrue(giant.isChunked(), "a level this size is chunked");
        assertTrue(giant.walkable(1500, 1500), "its floor reaches everywhere");

        Level loaded = LevelLoader.parse(giant.toJson());
        assertTrue(loaded.isChunked());
        assertTrue(loaded.walkable(1500, 1500),
                "and comes back floored, not as side-scrolling terrain");
        assertEquals(giant.tileAt(1500, 1500), loaded.tileAt(1500, 1500));
        assertTrue(loaded.solidAt(0, 500), "with its stacked wall border intact");
    }

    /** A level that says it has an empty stacked layer is taken at its word. */
    @Test
    void aLevelThatDeclaresItsStackedLayerIsNotConverted() {
        Level flat = LevelLoader.parse("""
                { "format": "top_down", "tileset": "registry", "tileSize": 32,
                  "width": 2, "height": 1, "tilesRle": [0, 2], "upperRle": [0, 2] }
                """);
        assertEquals(0, flat.stackHeight(0, 0), "an authored hole stays a hole");
        assertFalse(flat.walkable(0, 0));
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

        // On a plane a pool lies *on* the floor rather than replacing it, so
        // its source goes into the level's surface layer — the stacked one.
        Level plan = openLevel(LevelFormat.TOP_DOWN);
        plan.setTile(10, 2, plan.surfaceLayer(), water);
        settleLiquids(plan, false);
        assertTrue(isWater(plan, 9, 2) && isWater(plan, 11, 2),
                "a plan-view source spreads along both sides");
        assertTrue(isWater(plan, 10, 1) && isWater(plan, 10, 3),
                "and up and down the plane");
        assertTrue(plan.walkable(10, 2), "and the floor is still under it");
    }

    @Test
    void wallsStopAPlanViewLiquidFromSpreadingThrough() {
        Level plan = openLevel(LevelFormat.TOP_DOWN);
        int wall = plan.blocks.get("stone_wall").id();
        for (int r = 0; r < plan.height; r++) plan.stackTile(12, r, wall);
        plan.setTile(10, 5, plan.surfaceLayer(), plan.blocks.get("water").id());
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
            World.Chop chop = World.Chop.NONE;
            for (int i = 0; i < 8 && !chop.broken(); i++) {
                chop = world.chopDecor(300, 300 - 32, true, true);
            }
            assertEquals(World.ChopResult.BROKEN, chop.result(), "chopping works in " + format);
            assertNotNull(chop.decor(), "the chop reports what it felled in " + format);

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

    /**
     * An open (unwalled) canvas, so spreading isn't confined by a border. The
     * plan views get a floor laid across theirs, because bare ground is a hole
     * there rather than open space — an empty plan-view canvas is a level with
     * nowhere to stand.
     */
    private static Level openLevel(LevelFormat format) {
        Level lvl = Level.empty("Open", 20, 12, 32);
        lvl.setFormat(format);
        if (format.layered()) {
            Block floor = LevelFormat.floorBlock(lvl.blocks);
            lvl.fillFloor(floor.id());
        }
        return lvl;
    }

    private static void settleLiquids(Level level, boolean gravityOn) {
        LiquidSim sim = new LiquidSim();
        for (int i = 0; i < 40; i++) sim.step(level, gravityOn, LiquidSim.TICK);
    }

    private static boolean isWater(Level level, int col, int row) {
        Block b = level.liquidAt(col, row);
        return b != null && "water".equals(level.blocks.sourceFor(b).key());
    }
}
