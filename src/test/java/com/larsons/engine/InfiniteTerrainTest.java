package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.gen.Biome;
import com.larsons.engine.world.gen.Biomes;
import com.larsons.engine.world.gen.TerrainSettings;
import com.larsons.engine.world.gen.WorldExpansion;
import com.larsons.engine.world.gen.WorldGenerator;
import com.larsons.engine.world.gen.WorldLod;
import com.larsons.engine.world.gen.WorldTerrain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Infinite procedurally generated terrain: the world a 3D level grows beyond
 * its own bounds.
 *
 * <p>The things worth pinning here are the ones a generated world would
 * otherwise be quietly wrong about. <b>Determinism</b>, because chunks are
 * built on background threads, evicted under memory pressure and rebuilt when
 * revisited — a column that comes back different is a world that rearranges
 * itself behind the player. <b>The vertical layout</b>, because "land above
 * 150, sea at 149 and below" is the contract everything else in the world is
 * measured against. <b>What a save costs</b>, because writing the terrain down
 * instead of the seed is the difference between a level file and a disk image.
 * And <b>the sweep bounds</b>, because a 512-layer column that has to be walked
 * from the floor is not a slow frame, it is no frame at all.
 */
@Timeout(120)
class InfiniteTerrainTest {

    private static TerrainSettings settings(long seed) {
        TerrainSettings t = new TerrainSettings();
        t.enabled = true;
        t.seed = seed;
        // Small enough that a test's world is cheap, still many chunks across.
        t.worldSize = TerrainSettings.MIN_WORLD_SIZE;
        t.normalize();
        return t;
    }

    private static WorldGenerator generator(long seed) {
        TerrainSettings t = settings(seed);
        return new WorldGenerator(t, BlockRegistry.standard(), t.seed);
    }

    /** A small 3D level with infinite terrain asked for. */
    private static Level worldLevel(long seed) {
        Level lvl = LevelFormat.THREE_D.starterLevel("World", 64, 64, 32);
        lvl.settings = new GameProfile();
        lvl.settings.perspective = lvl.perspective;
        lvl.settings.verticality = true;
        lvl.settings.terrain = settings(seed);
        lvl.applyTerrainSettings();
        return lvl;
    }

    // --- determinism ---------------------------------------------------------------

    /**
     * The same chunk generated twice is the same chunk, block for block.
     *
     * <p>This is the invariant the whole design rests on: pristine chunks are
     * dropped when the loaded budget is passed and rebuilt when the player
     * comes back, so anything that made generation depend on order — a shared
     * scratch buffer, a field written during a build, a hash that took the
     * chunk's coordinates instead of the cell's — would show up as terrain that
     * changes shape while your back is turned.
     */
    @Test
    void aChunkGeneratesIdenticallyEveryTime() {
        WorldGenerator gen = generator(9001);
        int[][] first = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        int[][] second = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        gen.generateChunk(11, 7, first);
        // Generate other chunks in between, so anything held over between calls
        // is holding the wrong thing by the time the second build happens.
        int[][] scratch = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        gen.generateChunk(12, 7, scratch);
        gen.generateChunk(11, 8, scratch);
        gen.generateChunk(40, 40, scratch);
        gen.generateChunk(11, 7, second);
        for (int i = 0; i < first.length; i++) {
            assertArrayEqualsInts(first[i], second[i], "column " + i);
        }
    }

    /** Two generators built from the same seed build the same world. */
    @Test
    void theSameSeedIsTheSameWorld() {
        WorldGenerator a = generator(4242);
        WorldGenerator b = generator(4242);
        WorldGenerator other = generator(4243);
        boolean anyDifference = false;
        for (int col = 0; col < 400; col += 17) {
            for (int row = 0; row < 400; row += 17) {
                assertEquals(a.surfaceHeight(col, row), b.surfaceHeight(col, row),
                        "same seed, same ground at " + col + "," + row);
                if (a.surfaceHeight(col, row) != other.surfaceHeight(col, row)) {
                    anyDifference = true;
                }
            }
        }
        assertTrue(anyDifference, "a different seed has to be a different world");
    }

    /**
     * A tree whose trunk is in one chunk still hangs over the next one, and
     * hangs over it the same way whichever chunk is built first.
     *
     * <p>Canopies are the one thing in the pipeline that reaches across a chunk
     * boundary, so they are where an ordering bug would live. Generating the
     * two chunks in both orders and comparing the seam is what catches it.
     */
    @Test
    void chunkSeamsMatchWhicheverSideIsBuiltFirst() {
        WorldGenerator gen = generator(777);
        int[][] left = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        int[][] rightFirst = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        int[][] rightSecond = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        gen.generateChunk(6, 6, rightFirst);
        gen.generateChunk(5, 6, left);
        gen.generateChunk(6, 6, rightSecond);
        for (int i = 0; i < rightFirst.length; i++) {
            assertArrayEqualsInts(rightFirst[i], rightSecond[i],
                    "a neighbour being built cannot change this chunk, column " + i);
        }
    }

    // --- the vertical layout -------------------------------------------------------

    /**
     * Land is at 150 and above, the sea is at 149 and below — the contract the
     * whole world is measured against.
     */
    @Test
    void groundStandsAtOneFiftyAndTheSeaFillsToOneFortyNine() {
        TerrainSettings t = settings(31337);
        assertEquals(150, t.groundLevel, "terrain above ground starts here");
        assertEquals(149, t.seaLevel, "and the sea is this and below");

        WorldGenerator gen = new WorldGenerator(t, BlockRegistry.standard(), t.seed);
        BlockRegistry blocks = BlockRegistry.standard();
        int[][] chunk = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        int dryLand = 0, flooded = 0;
        for (int cx = 0; cx < 6; cx++) {
            for (int cy = 0; cy < 6; cy++) {
                gen.generateChunk(cx * 3, cy * 3, chunk);
                for (int[] column : chunk) {
                    if (column == null || column.length == 0) continue;
                    int top = column[column.length - 2];
                    for (int layer = 0; layer < top; layer++) {
                        Block b = blocks.get(WorldTerrain.at(column, layer));
                        if (b == null || !b.liquid()) continue;
                        // Water only ever appears at or under the water line;
                        // a lava lake deep in a cave is not the sea.
                        if ("water".equals(b.key())) {
                            assertTrue(layer <= t.seaLevel,
                                    "sea water above the water line, at layer " + layer);
                        }
                    }
                    if (top > t.groundLevel) dryLand++;
                }
            }
        }
        assertTrue(dryLand > 0, "somewhere in the world is above the ground level");
        // The coastline is a slow field, so it is looked for over a wider
        // stretch of the world than a handful of chunks covers.
        for (int col = 0; col < 6000; col += 29) {
            for (int row = 0; row < 6000; row += 29) {
                if (gen.surfaceHeight(col, row) <= t.seaLevel) flooded++;
            }
        }
        assertTrue(flooded > 0, "and somewhere in it is under the sea");
    }

    /** The height axis a biome may target is the level's own: 0 to 512. */
    @Test
    void biomesAimAnywhereInTheHeightAxis() {
        assertEquals(Level.MAX_LAYERS, TerrainSettings.MAX_LEVEL,
                "a biome's target level is a layer of the level it generates");
        Biome b = new Biome("test", "Test", Biome.Realm.SURFACE);
        b.targetLevel = 900;
        b.normalize();
        assertEquals(512, b.targetLevel, "clamped to the top of the world");
        b.targetLevel = -5;
        b.normalize();
        assertEquals(0, b.targetLevel, "and to the bottom of it");
    }

    /**
     * The world is varied: ground from under the water line to well up the
     * height axis, and most of the biome list actually appearing.
     *
     * <p>A world that is technically infinite and everywhere the same is the
     * failure mode worth testing for, because it is the one that passes every
     * other test in this file.
     */
    @Test
    void theWorldIsVariedAcrossItsBiomes() {
        WorldGenerator gen = generator(4242);
        Set<String> seen = new HashSet<>();
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int col = 0; col < 4000; col += 13) {
            for (int row = 0; row < 4000; row += 13) {
                int h = gen.surfaceHeight(col, row);
                lowest = Math.min(lowest, h);
                highest = Math.max(highest, h);
                seen.add(gen.biomeAt(col, row).key);
            }
        }
        assertTrue(seen.size() >= 8,
                "a world that is everywhere the same is not a world; saw " + seen);
        assertTrue(lowest < TerrainSettings.DEFAULT_SEA_LEVEL,
                "somewhere is under the sea, got " + lowest);
        assertTrue(highest > TerrainSettings.DEFAULT_GROUND_LEVEL + 40,
                "somewhere is a mountain, got " + highest);
    }

    /**
     * The world is built out of a lot more than dirt and stone: the vibrancy
     * requirement, as a number.
     */
    @Test
    void generatedGroundUsesTheWholePalette() {
        WorldGenerator gen = generator(4242);
        BlockRegistry blocks = BlockRegistry.standard();
        Map<String, Integer> used = new HashMap<>();
        int[][] chunk = new int[WorldTerrain.CHUNK * WorldTerrain.CHUNK][];
        for (int cx = 0; cx < 8; cx++) {
            for (int cy = 0; cy < 8; cy++) {
                gen.generateChunk(cx * 3, cy * 3, chunk);
                for (int[] column : chunk) {
                    if (column == null) continue;
                    for (int i = 1; i < column.length; i += 2) {
                        Block b = blocks.get(column[i]);
                        if (b != null) used.merge(b.key(), 1, Integer::sum);
                    }
                }
            }
        }
        assertTrue(used.size() >= 60,
                "a vibrant world uses more than a handful of blocks; got "
                        + used.size() + ": " + used.keySet());
        // The blocks that only exist because biomes needed them.
        for (String key : new String[]{"jungle_leaves", "palm_leaves", "pine_needles"}) {
            assertTrue(used.containsKey(key), "no " + key + " anywhere in the world");
        }
    }

    // --- the level becomes a world -------------------------------------------------

    /** Turning the setting on expands the level and puts it in the middle. */
    @Test
    void turningItOnExpandsTheLevelIntoAWorld() {
        Level lvl = LevelFormat.THREE_D.starterLevel("Home", 64, 48, 32);
        lvl.settings = new GameProfile();
        double spawnX = lvl.spawnX, spawnY = lvl.spawnY;
        assertFalse(lvl.isWorld(), "a level starts as a level");

        lvl.settings.terrain = settings(5150);
        lvl.applyTerrainSettings();

        assertTrue(lvl.isWorld(), "and becomes a world when asked");
        WorldTerrain world = lvl.terrain();
        assertNotNull(world);
        assertEquals(TerrainSettings.MIN_WORLD_SIZE, lvl.width, "the bounds are the world's");
        assertEquals(TerrainSettings.MIN_WORLD_SIZE, lvl.height);
        int[] authored = world.authoredBounds();
        assertTrue(authored[0] > 0 && authored[1] > 0,
                "the level ends up in the middle, not in a corner");
        assertEquals(spawnX + authored[0] * 32.0, lvl.spawnX, 0.001,
                "the spawn moves with the terrain");
        assertEquals(spawnY + authored[1] * 32.0, lvl.spawnY, 0.001);
    }

    /**
     * The authored level's blocks survive the move, lifted to the shoreline
     * with rock filled in underneath — and the generator does not write inside
     * its bounds.
     */
    @Test
    void theAuthoredLevelSurvivesAndTheWorldStartsAtItsEdge() {
        Level lvl = LevelFormat.THREE_D.starterLevel("Home", 64, 48, 32);
        lvl.settings = new GameProfile();
        int floorId = lvl.tileAt(20, 20);
        assertTrue(floorId > 0, "the starter canvas has a floor to carry across");

        lvl.settings.terrain = settings(5150);
        lvl.applyTerrainSettings();
        int[] authored = lvl.terrain().authoredBounds();
        int ground = lvl.settings.terrain.groundLevel;

        assertEquals(floorId, lvl.tileAt(authored[0] + 20, authored[1] + 20, ground),
                "the authored floor is at the shoreline");
        assertTrue(lvl.tileAt(authored[0] + 20, authored[1] + 20, ground - 1) > 0,
                "and there is rock under it rather than sky");
        assertEquals(0, lvl.tileAt(authored[0] + 20, authored[1] + 20, ground + 40),
                "nothing was grown on top of the authored level");

        // Outside the authored rectangle, the world generates itself.
        int outside = authored[0] + authored[2] + 200;
        assertTrue(lvl.columnDepth(outside, authored[1] + 20) > ground - 40,
                "generated ground beyond the level's bounds");
    }

    /** A side-scroller is not a plane and does not grow one. */
    @Test
    void aWorldIsAsTallAsThePlanViewAllows() {
        Level lvl = worldLevel(2024);
        assertEquals(Level.MAX_LAYERS, lvl.layerLimit(),
                "a world's storage is columns, so height costs nothing per cell");
    }

    // --- what a save costs ---------------------------------------------------------

    /**
     * A world round-trips through the level file, and what is written is the
     * seed and the edits rather than the terrain.
     */
    @Test
    void aWorldSavesItsSeedAndItsEditsAndNotItsTerrain() {
        Level lvl = worldLevel(6006);
        WorldTerrain world = lvl.terrain();
        int[] authored = world.authoredBounds();
        int col = authored[0] + authored[2] + 100, row = authored[1] + 50;

        // Walk somewhere: the chunk loads, and nothing is edited.
        int before = lvl.columnDepth(col, row);
        assertTrue(before > 0, "there is ground out there");
        int exploredOnly = lvl.toJson().length();

        // Now change one block out there and save again.
        Block marker = BlockRegistry.standard().get("gilded_bricks");
        assertNotNull(marker);
        assertTrue(lvl.setTile(col, row, before, marker.id()), "placed a block");
        String json = lvl.toJson();
        assertTrue(json.length() > exploredOnly,
                "an edited chunk has to be written down");

        Level reloaded = LevelLoader.parse(json);
        assertTrue(reloaded.isWorld(), "it comes back as a world");
        assertEquals(marker.id(), reloaded.tileAt(col, row, before),
                "the edit survives the round trip");
        assertEquals(before + 1, reloaded.columnDepth(col, row),
                "and so does the ground it was placed on");

        // Ground nobody touched comes back from the seed, identically.
        int untouchedCol = authored[0] + authored[2] + 400;
        assertEquals(lvl.columnDepth(untouchedCol, row),
                reloaded.columnDepth(untouchedCol, row),
                "pristine ground rebuilds from the seed");
    }

    /**
     * Turning it off stops the world growing and keeps what has been explored:
     * the chunk you walked through is still there, and the one you never
     * reached is not.
     */
    @Test
    void turningItOffKeepsWhatWasExplored() {
        Level lvl = worldLevel(8080);
        WorldTerrain world = lvl.terrain();
        int[] authored = world.authoredBounds();
        int visitedCol = authored[0] + authored[2] + 100, row = authored[1] + 40;
        int visitedDepth = lvl.columnDepth(visitedCol, row);
        assertTrue(visitedDepth > 0, "ground where the player walked");

        // Somewhere nobody has been.
        int farCol = visitedCol + 2000;

        lvl.settings.terrain.enabled = false;
        lvl.applyTerrainSettings();
        assertFalse(world.generating(), "generation stops");

        String json = lvl.toJson();
        Level reloaded = LevelLoader.parse(json);
        assertEquals(visitedDepth, reloaded.columnDepth(visitedCol, row),
                "the ground that was explored is still there");
        assertEquals(0, reloaded.columnDepth(farCol, row),
                "and the world stops where the exploring did");
    }

    // --- the sweep bounds ----------------------------------------------------------

    /**
     * A buried column is not swept, and nothing that could be seen is missed.
     *
     * <p>The renderer looks at the layers {@code visibleLayers} hands it. In
     * the middle of a mountain that has to be almost none of them, or every
     * visible cell pays a hundred and fifty reads to discover they are all
     * walled in. The second half of the test is the one that matters more: a
     * layer with an empty neighbour has to be on the list, or the world is
     * drawn with holes in it.
     */
    @Test
    void aBuriedColumnIsSkippedAndAnExposedOneIsNot() {
        Level lvl = worldLevel(1234);
        int[] authored = lvl.terrain().authoredBounds();
        int base = authored[0] + authored[2] + 200;
        int[] layers = new int[Level.MAX_LAYERS];

        long swept = 0, columns = 0, deep = 0;
        for (int col = base; col < base + 48; col++) {
            for (int row = authored[1]; row < authored[1] + 48; row++) {
                int depth = lvl.columnDepth(col, row);
                int count = lvl.visibleLayers(col, row, layers);
                Set<Integer> listed = new HashSet<>();
                int previous = 0;
                for (int i = 0; i < count; i++) {
                    assertTrue(layers[i] > previous, "layers come back in order");
                    assertTrue(layers[i] < depth, "and inside the column");
                    previous = layers[i];
                    listed.add(layers[i]);
                }
                // Nothing that could be seen may be left off the list.
                for (int layer = 1; layer < depth; layer++) {
                    if (lvl.tileAt(col, row, layer) <= 0) continue;
                    boolean exposed = lvl.tileAt(col, row, layer - 1) <= 0
                            || lvl.tileAt(col, row, layer + 1) <= 0
                            || lvl.tileAt(col - 1, row, layer) <= 0
                            || lvl.tileAt(col + 1, row, layer) <= 0
                            || lvl.tileAt(col, row - 1, layer) <= 0
                            || lvl.tileAt(col, row + 1, layer) <= 0;
                    if (exposed) {
                        assertTrue(listed.contains(layer),
                                "an exposed block was skipped at " + col + ","
                                        + row + " layer " + layer);
                    }
                }
                swept += count;
                deep += depth;
                columns++;
            }
        }
        assertTrue(columns > 0);
        double sweptPerColumn = swept / (double) columns;
        double depthPerColumn = deep / (double) columns;
        assertTrue(depthPerColumn > 100, "these are deep columns: " + depthPerColumn);
        assertTrue(sweptPerColumn < depthPerColumn / 5,
                "a buried column has to be skipped, not walked: " + sweptPerColumn
                        + " layers swept of " + depthPerColumn);
    }

    /**
     * <b>The ground under a flower is drawn.</b> The reported fault, and the
     * reason it was invisible in the code: the sweep skipped a layer whose six
     * neighbours were all <em>non-empty</em>, and a flower is non-empty. So
     * every column with anything growing on it lost its top block — the one
     * the player is looking at — and the world was see-through wherever the
     * generator had scattered a decoration, which on grassland is everywhere.
     *
     * <p>The test walks generated ground rather than a hand-placed flower on
     * purpose: this is the path a world takes, and the hand-built path
     * ({@link #anOrdinaryLevelSweepsEveryLayer}) never had the bug.
     */
    @Test
    void theGroundUnderSomethingGrowingIsStillDrawn() {
        Level lvl = worldLevel(1234);
        int[] authored = lvl.terrain().authoredBounds();
        int[] layers = new int[Level.MAX_LAYERS];
        int planted = 0;
        for (int col = authored[0] + authored[2] + 100; col < authored[0] + authored[2] + 164;
                col++) {
            for (int row = authored[1]; row < authored[1] + 64; row++) {
                int depth = lvl.columnDepth(col, row);
                if (depth < 3) continue;
                Block top = lvl.blocks.get(lvl.tileAt(col, row, depth - 1));
                if (top == null || !top.plant()) continue;   // nothing growing here
                int under = depth - 2;
                Block ground = lvl.blocks.get(lvl.tileAt(col, row, under));
                if (ground == null || !ground.covers()) continue;
                planted++;

                int count = lvl.visibleLayers(col, row, layers);
                boolean listed = false;
                for (int i = 0; i < count; i++) {
                    if (layers[i] == under) listed = true;
                }
                assertTrue(listed, "the block under the plant at " + col + "," + row
                        + " (layer " + under + " of " + depth + ") was not drawn");
            }
        }
        assertTrue(planted > 20, "this patch of world should be growing something: " + planted);
    }

    /**
     * A neighbour you can see <em>through</em> does not hide the face behind
     * it either — the same fault as the flower, wearing glass.
     */
    @Test
    void aPaneOfGlassDoesNotHideTheWallBehindIt() {
        Level lvl = worldLevel(77);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 60, row = authored[1] + 60;
        int depth = lvl.columnDepth(col, row);
        int layer = Math.max(1, depth - 6);          // well inside the rock
        int stone = lvl.blocks.get("stone").id();
        int glass = lvl.blocks.get("glass").id();
        for (int d = -1; d <= 1; d++) {
            for (int e = -1; e <= 1; e++) {
                for (int l = layer - 1; l <= layer + 1; l++) {
                    lvl.setTile(col + d, row + e, l, stone);
                }
            }
        }
        int[] layers = new int[Level.MAX_LAYERS];
        assertFalse(listed(lvl, col, row, layer, layers),
                "walled in on all six sides by rock, it is not drawn");

        lvl.setTile(col + 1, row, layer, glass);
        assertTrue(listed(lvl, col, row, layer, layers),
                "with a pane beside it, the face you can see through it is drawn");
    }

    /**
     * A lake is not a face per layer of its depth. The rule that puts the
     * ground back under a flower — draw against anything that does not cover —
     * would say every cell of forty blocks of water has six visible faces, so
     * it is qualified by the one exception that is always right: a block never
     * shows a face to <em>itself</em>.
     */
    @Test
    void waterAgainstWaterIsNotASurface() {
        Level lvl = worldLevel(99);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 90, row = authored[1] + 90;
        int water = lvl.blocks.get("water").id();
        int floor = lvl.columnDepth(col, row);
        for (int d = -1; d <= 1; d++) {
            for (int e = -1; e <= 1; e++) {
                for (int l = floor; l < floor + 24; l++) lvl.setTile(col + d, row + e, l, water);
            }
        }
        int[] layers = new int[Level.MAX_LAYERS];
        int count = lvl.visibleLayers(col, row, layers);
        int inside = 0;
        for (int i = 0; i < count; i++) {
            if (layers[i] > floor && layers[i] < floor + 23) inside++;
        }
        assertTrue(inside <= 2, "the middle of the pool has no faces in it: " + inside
                + " of " + count + " layers drawn");
    }

    private static boolean listed(Level lvl, int col, int row, int layer, int[] layers) {
        int count = lvl.visibleLayers(col, row, layers);
        for (int i = 0; i < count; i++) {
            if (layers[i] == layer) return true;
        }
        return false;
    }

    // --- the column mesh -----------------------------------------------------------

    /**
     * <b>A column's faces are worked out once and kept.</b> This is the chunk
     * mesh, in the shape this engine's storage takes, and it is the largest
     * thing the detailed sweep stopped doing: the question "is this face
     * exposed" is answered from five columns of neighbours and cannot change
     * unless one of them does, while a frame used to ask it again for every
     * cell of the view distance a hundred and twenty times a second.
     */
    @Test
    void aColumnsFacesAreWorkedOutOnceAndKept() {
        Level lvl = worldLevel(2468);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 70, row = authored[1] + 70;

        int[] first = lvl.columnFaces(col, row);
        assertNotNull(first);
        assertTrue(first.length > 0, "there is ground here");
        assertSame(first, lvl.columnFaces(col, row), "asked twice, answered once");

        // Every entry names a layer that is actually filled, and at least one
        // face of it — an entry with no faces would be work with nothing to do.
        int previous = -1;
        for (int packed : first) {
            int layer = (packed >>> WorldTerrain.FACE_BITS) & WorldTerrain.LAYER_MASK;
            int mask = packed & ((1 << WorldTerrain.FACE_BITS) - 1);
            int id = packed >>> (WorldTerrain.FACE_BITS + WorldTerrain.LAYER_BITS);
            assertTrue(layer > previous, "faces come back in order");
            previous = layer;
            assertNotEquals(0, mask, "a listed layer has a face");
            assertEquals(lvl.tileAt(col, row, layer), id, "and it carries its own block");
        }
    }

    /**
     * Putting a block down forgets the mesh of the column it landed in
     * <em>and</em> of the four beside it — a face is a fact about two cells, so
     * a block placed here is a face that stopped existing next door.
     */
    @Test
    void aBlockPlacedForgetsItsNeighboursMeshes() {
        Level lvl = worldLevel(1357);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 70, row = authored[1] + 70;
        int layer = Math.max(1, lvl.columnDepth(col, row));

        int[] before = lvl.columnFaces(col + 1, row);
        assertNotNull(before);
        assertTrue(lvl.setTile(col, row, layer, lvl.blocks.get("stone").id()));
        assertNotSame(before, lvl.columnFaces(col + 1, row),
                "the neighbour's mesh has to be worked out again");

        // …and the answer has to be the new one: the face the neighbour used to
        // show toward this column is now against a block.
        assertTrue(lvl.columnFaces(col, row).length > 0, "the new block is drawn");
    }

    // --- the level-of-detail tree --------------------------------------------------

    /**
     * <b>Flat ground is a handful of quads, not a thousand.</b> A tile is
     * {@value com.larsons.engine.world.gen.WorldLod#SAMPLES}&sup2; samples of
     * the surface; greedy meshing merges every run that shares a height and a
     * block before anything is drawn, which is what makes a horizon two hundred
     * and fifty-six chunks deep affordable — the plains and the seabed that
     * make up most of any world collapse to almost nothing.
     */
    @Test
    void theDistantTreeMergesGroundIntoRuns() {
        Level lvl = worldLevel(864);
        WorldLod lod = lvl.terrain().lod();
        assertNotNull(lod);
        // A tile the test's world actually has: its worldSize is the smallest
        // the settings offer, so the coarser levels are only a tile or two
        // across it.
        int level = 1;
        int tile = Math.min(1, lod.tilesAcross(level) - 1);
        int[] mesh = null;
        for (int i = 0; i < 400 && mesh == null; i++) {
            mesh = lod.tile(level, tile, tile);
            if (mesh == null) sleep();
        }
        assertNotNull(mesh, "the tile is built on a worker and arrives");
        int boxes = mesh.length / WorldLod.BOX_STRIDE;
        assertTrue(boxes > 0, "a tile of world has ground in it");
        assertTrue(boxes < WorldLod.SAMPLES * WorldLod.SAMPLES / 2,
                "greedy meshing has to be worth having: " + boxes + " boxes of "
                        + (WorldLod.SAMPLES * WorldLod.SAMPLES) + " samples");

        // Every box is a rectangle inside its own tile, at a height on the axis.
        int side = WorldLod.tileCells(level);
        for (int i = 0; i < mesh.length; i += WorldLod.BOX_STRIDE) {
            assertTrue(mesh[i] < mesh[i + 2], "a box has width");
            assertTrue(mesh[i + 1] < mesh[i + 3], "and depth");
            assertTrue(mesh[i] >= tile * side && mesh[i + 2] <= (tile + 1) * side,
                    "and stays inside its tile");
            assertTrue(mesh[i + 4] >= 0 && mesh[i + 4] < TerrainSettings.MAX_LEVEL);
        }
        assertSame(mesh, lod.tile(level, tile, tile), "built once, then kept");
    }

    /**
     * The level a tile is drawn at grows with how far away it is — which is the
     * whole level-of-detail policy, and the reason each ring of the horizon
     * costs what the one inside it did rather than nine times as much.
     */
    @Test
    void theDistantTreeGetsCoarserWithDistance() {
        int ts = 32;
        int near = WorldLod.levelFor(64 * ts, ts, 0.10);
        int mid = WorldLod.levelFor(512 * ts, ts, 0.10);
        int far = WorldLod.levelFor(4096 * ts, ts, 0.10);
        assertTrue(near <= mid, "further is never finer: " + near + " then " + mid);
        assertTrue(mid < far, "and a long way further is coarser: " + mid + " then " + far);
        assertTrue(far <= WorldLod.MAX_LEVEL, "and it stops at the coarsest there is");
        assertTrue(WorldLod.stepCells(far) > WorldLod.stepCells(near),
                "a coarser level samples the ground less often");
    }

    /** An ordinary level's sweep is unchanged: every layer, from the floor up. */
    @Test
    void anOrdinaryLevelSweepsEveryLayer() {
        Level lvl = LevelFormat.THREE_D.starterLevel("Flat", 32, 32, 32);
        int[] layers = new int[Level.MAX_LAYERS];
        int count = lvl.visibleLayers(4, 4, layers);
        assertEquals(Math.max(0, lvl.columnDepth(4, 4) - 1), count,
                "nothing to skip on a level three blocks deep");
        if (count > 0) assertEquals(1, layers[0], "and it starts at the floor");
    }

    /**
     * The coarse horizon answers without generating the ground it describes —
     * which is what lets it reach 2048 blocks.
     */
    @Test
    void theHorizonIsAnsweredWithoutGeneratingIt() {
        Level lvl = worldLevel(555);
        WorldTerrain world = lvl.terrain();
        int loadedBefore = world.loadedChunks();
        // Three: the tallest ground, what it is made of, and the lowest ground
        // in the same group — which is what the horizon occludes with.
        int[] out = new int[3];
        // Somewhere a very long way off, in the coarse pass's own group size.
        int col = 3000, row = 3000;
        // The first ask queues the sample tile; it lands on a worker thread.
        for (int i = 0; i < 200 && !world.groupTop(col, row, col + 16, row + 16, out); i++) {
            sleep();
        }
        assertTrue(world.groupTop(col, row, col + 16, row + 16, out),
                "the horizon eventually has an answer");
        assertTrue(out[0] > 0, "and it is a height");
        assertTrue(world.loadedChunks() <= loadedBefore + 1,
                "asking about the horizon must not generate it: "
                        + loadedBefore + " -> " + world.loadedChunks());
        world.close();
    }

    // --- the plan view of a world ---------------------------------------------------

    /**
     * The plan view draws a generated world as a map: one quad per column, in
     * the material on top of it, and nothing lifted.
     *
     * <p>Lifting a column of three hundred one tile per layer — which is what
     * this view does to a hand-built level, correctly — would draw a
     * cross-section of the crust three hundred tiles up the screen. The check
     * that matters is the count: one draw per visible cell, not one per layer
     * of every visible cell.
     */
    @Test
    void thePlanViewOfAWorldIsAMap() {
        Level lvl = worldLevel(3003);
        int[] authored = lvl.terrain().authoredBounds();
        com.larsons.engine.graphics.Camera camera =
                new com.larsons.engine.graphics.Camera(lvl.perspective, 320, 240);
        camera.tileSize = lvl.tileSize;
        camera.centerOn((authored[0] + authored[2] + 120) * 32.0,
                (authored[1] + 120) * 32.0);
        int[] bounds = com.larsons.engine.graphics.TerrainPainter.visibleBounds(
                camera, lvl, 320, 240);
        int cells = (bounds[2] - bounds[0] + 1) * (bounds[3] - bounds[1] + 1);
        assertTrue(cells > 0 && cells < 4000,
                "a flat world needs no lift margin, so the sweep stays tight: " + cells);

        var target = new com.larsons.engine.graphics.draw.RecordingTarget(320, 240);
        com.larsons.engine.graphics.DepthPass raised =
                com.larsons.engine.graphics.DepthPass.of(lvl.perspective);
        com.larsons.engine.graphics.TerrainPainter.draw(target, lvl, camera, bounds,
                0, raised, null);
        raised.flush();
        int drawn = target.commands().size();
        assertTrue(drawn > 0, "the map is drawn");
        assertTrue(drawn <= cells * 3,
                "one quad per column, not one per layer of every column: "
                        + drawn + " draws over " + cells + " cells");
    }

    /** Aiming in that view lands on the cell under the cursor, at its own top. */
    @Test
    void aimingAtAWorldLandsOnTheGround() {
        Level lvl = worldLevel(3004);
        int[] authored = lvl.terrain().authoredBounds();
        double wx = (authored[0] + authored[2] + 120) * 32.0;
        double wy = (authored[1] + 120) * 32.0;
        com.larsons.engine.graphics.Camera camera =
                new com.larsons.engine.graphics.Camera(lvl.perspective, 320, 240);
        camera.tileSize = lvl.tileSize;
        camera.centerOn(wx, wy);
        var aim = com.larsons.engine.graphics.TerrainPainter.pick(camera, lvl, 160, 120);
        assertNotNull(aim, "the cursor is over ground");
        assertEquals((int) (wx / 32), aim.col(), 1, "the cell under the cursor");
        assertEquals((int) (wy / 32), aim.row(), 1);
        assertEquals(lvl.topFilledLayer(aim.col(), aim.row()), aim.layer(),
                "and the top of that column");
    }

    // --- biomes are data -----------------------------------------------------------

    /** The standard set is the ten places above ground and eight below it. */
    @Test
    void theStandardBiomesAreTheOnesTheWorldWasAskedFor() {
        TerrainSettings t = new TerrainSettings();
        Set<String> surface = new HashSet<>();
        Set<String> underground = new HashSet<>();
        for (Biome b : t.biomes) {
            (b.realm == Biome.Realm.SURFACE ? surface : underground).add(b.key);
        }
        for (String key : new String[]{"pine", "deciduous", "icy", "mountainous",
                "tropics", "jungle", "desert", "plains", "village", "ancient_city"}) {
            assertTrue(surface.contains(key), "missing above-ground biome: " + key);
        }
        for (String key : new String[]{"glowing_rainbow", "ice_cave", "acid_cave",
                "obsidian_cave", "regular_cave", "lava_cave", "water_cave",
                "ancient_temple"}) {
            assertTrue(underground.contains(key), "missing below-ground biome: " + key);
        }
    }

    /** Every biome names blocks that are actually in the registry. */
    @Test
    void everyStandardBiomeNamesRealBlocks() {
        BlockRegistry blocks = BlockRegistry.standard();
        for (Biome b : Biomes.standard()) {
            for (String key : new String[]{b.surfaceBlock, b.subsurfaceBlock,
                    b.stoneBlock, b.beachBlock, b.liquidBlock, b.logBlock,
                    b.leafBlock, b.lightBlock, b.structureBlock,
                    b.structureAccentBlock}) {
                if (key == null || key.isBlank()) continue;
                assertNotNull(blocks.get(key), b.key + " names a block that is not "
                        + "in the registry: " + key);
            }
            for (String key : b.decorBlocks) {
                assertNotNull(blocks.get(key),
                        b.key + " decorates with a block that is not in the registry: " + key);
            }
        }
    }

    /** The "+" button adds a working biome with a key of its own. */
    @Test
    void theNewBiomeButtonAddsAWorkingBiome() {
        TerrainSettings t = new TerrainSettings();
        int before = t.biomes.size();
        Biome added = t.addBiome(Biome.Realm.UNDERGROUND);
        Biome again = t.addBiome(Biome.Realm.UNDERGROUND);
        assertEquals(before + 2, t.biomes.size());
        assertFalse(added.key.equals(again.key), "two new biomes get two keys");
        assertEquals(Biome.Realm.UNDERGROUND, added.realm);
        assertTrue(added.weight > 0, "and it generates rather than sitting there");
        assertNotNull(BlockRegistry.standard().get(added.surfaceBlock));
    }

    /** A creator's biomes round-trip through the level file with everything else. */
    @Test
    void biomesSaveWithTheLevel() {
        Level lvl = worldLevel(4001);
        Biome mine = lvl.settings.terrain.addBiome(Biome.Realm.SURFACE);
        mine.name = "Glass Flats";
        mine.surfaceBlock = "glass";
        mine.targetLevel = 311;
        mine.weight = 77;
        mine.decorBlocks.clear();
        mine.decorBlocks.add("neon_block");

        Level reloaded = LevelLoader.parse(lvl.toJson());
        Biome back = null;
        for (Biome b : reloaded.settings.terrain.biomes) {
            if ("Glass Flats".equals(b.name)) back = b;
        }
        assertNotNull(back, "a creator's biome has to survive a save");
        assertEquals("glass", back.surfaceBlock);
        assertEquals(311, back.targetLevel);
        assertEquals(77, back.weight);
        assertEquals(java.util.List.of("neon_block"), back.decorBlocks);
    }

    /** A world built only out of one creator's biome is built out of that biome. */
    @Test
    void aWorldOfOneInventedBiomeIsMadeOfIt() {
        TerrainSettings t = settings(31);
        t.biomes.clear();
        Biome only = new Biome("glassland", "Glassland", Biome.Realm.SURFACE);
        only.weight = 100;
        only.targetLevel = 200;
        only.relief = 6;
        only.surfaceBlock = "glass";
        only.subsurfaceBlock = "marble";
        only.stoneBlock = "marble";
        only.caveDensity = 0;
        only.normalize();
        t.biomes.add(only);
        t.normalize();

        WorldGenerator gen = new WorldGenerator(t, BlockRegistry.standard(), t.seed);
        Block glass = BlockRegistry.standard().get("glass");
        assertNotNull(glass);
        int checked = 0, onTarget = 0;
        for (int col = 0; col < 4000; col += 53) {
            for (int row = 0; row < 4000; row += 53) {
                int h = gen.surfaceHeight(col, row);
                assertTrue(h <= 200 + only.relief + 1,
                        "nothing rises above the one target level; got " + h);
                if (Math.abs(h - 200) <= only.relief + 1) {
                    onTarget++;
                    assertEquals(glass.id(), gen.surfaceBlockId(col, row),
                            "and dry land wears its own surface block");
                }
                checked++;
            }
        }
        assertTrue(checked > 100);
        // The rest is ocean basin, which is the continent field rather than the
        // biome — a world of one biome still has a coastline.
        assertTrue(onTarget > checked / 3,
                "a good deal of it stands where it was aimed: " + onTarget + "/" + checked);
    }

    /** Turning a biome off takes it out of the world. */
    @Test
    void aDisabledBiomeIsNotGenerated() {
        TerrainSettings t = settings(99);
        for (Biome b : t.biomes) {
            if (b.realm == Biome.Realm.SURFACE) b.enabled = "desert".equals(b.key);
        }
        t.normalize();
        WorldGenerator gen = new WorldGenerator(t, BlockRegistry.standard(), t.seed);
        for (int col = 0; col < 900; col += 41) {
            for (int row = 0; row < 900; row += 41) {
                assertEquals("desert", gen.biomeAt(col, row).key,
                        "only the biome left on may generate");
            }
        }
    }

    /**
     * Retuning the world's recipe regrows the ground and leaves what was built
     * on it standing.
     *
     * <p>There is no honest way to keep the old terrain and call it the new
     * settings, so the untouched world is rebuilt. What can be kept is
     * everything the creator put there themselves, and it is.
     */
    @Test
    void changingTheRecipeRegrowsTheGroundAndKeepsWhatWasBuilt() {
        Level lvl = worldLevel(2718);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 220, row = authored[1] + 220;
        int ground = lvl.columnDepth(col, row);
        Block marker = BlockRegistry.standard().get("gilded_bricks");
        assertNotNull(marker);
        assertTrue(lvl.setTile(col, row, ground + 3, marker.id()), "built something");
        long seedBefore = lvl.terrain().seed();

        // Every surface biome now aims a long way higher.
        for (Biome b : lvl.settings.terrain.biomes) {
            if (b.realm == Biome.Realm.SURFACE) b.targetLevel += 60;
        }
        lvl.applyTerrainSettings();

        assertEquals(seedBefore, lvl.terrain().seed(), "the seed did not change");
        assertTrue(lvl.columnDepth(col, row) > ground + 3,
                "the ground grew to the new recipe");
        assertEquals(marker.id(), lvl.tileAt(col, row, ground + 3),
                "and what was built on it is still there");
    }

    /** Typing a new seed builds a different world under the same buildings. */
    @Test
    void anewSeedIsANewWorldUnderTheSameBuildings() {
        Level lvl = worldLevel(11);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 260, row = authored[1] + 260;
        Block marker = BlockRegistry.standard().get("gilded_bricks");
        int at = lvl.columnDepth(col, row) + 6;
        assertTrue(lvl.setTile(col, row, at, marker.id()));

        int[] before = new int[8];
        for (int i = 0; i < before.length; i++) {
            before[i] = lvl.columnDepth(col + 40 + i * 13, row + 40);
            assertTrue(before[i] > 0, "sampled inside the world");
        }

        lvl.settings.terrain.seed = 999333;
        lvl.applyTerrainSettings();
        assertEquals(999333, lvl.terrain().seed());
        assertEquals(marker.id(), lvl.tileAt(col, row, at), "the building stayed");

        boolean different = false;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != lvl.columnDepth(col + 40 + i * 13, row + 40)) different = true;
        }
        assertTrue(different, "a new seed has to be new ground");
    }

    /** Reloading a level unchanged does not rebuild its world. */
    @Test
    void aPlainReloadLeavesTheWorldAlone() {
        Level lvl = worldLevel(1919);
        long seed = lvl.terrain().seed();
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 90, row = authored[1] + 90;
        int depth = lvl.columnDepth(col, row);

        Level reloaded = LevelLoader.parse(lvl.toJson());
        assertEquals(seed, reloaded.terrain().seed());
        assertEquals(depth, reloaded.columnDepth(col, row),
                "loading a level must not quietly regenerate it");
    }

    // --- settings ------------------------------------------------------------------

    /**
     * The render distance slider reaches ninety chunks and distant generation
     * reaches two hundred and fifty-six — and the painter accepts both.
     */
    @Test
    void theSlidersReachWhatTheyWereAskedTo() {
        assertEquals(16, TerrainSettings.BLOCKS_PER_CHUNK, "a chunk is Minecraft's chunk");
        assertEquals(90 * 16, TerrainSettings.MAX_RENDER_DISTANCE);
        assertEquals(256 * 16, TerrainSettings.MAX_DISTANT_DISTANCE);
        assertEquals(TerrainSettings.MAX_RENDER_DISTANCE,
                com.larsons.engine.graphics.SolidPainter.MAX_VIEW_TILES,
                "the painter has to accept what the slider offers");
        assertEquals(TerrainSettings.MAX_DISTANT_DISTANCE,
                com.larsons.engine.graphics.SolidPainter.MAX_DISTANT_TILES);

        TerrainSettings t = new TerrainSettings();
        t.renderDistance = 5000;
        t.distantDistance = 99999;
        t.normalize();
        assertEquals(90 * 16, t.renderDistance);
        assertEquals(256 * 16, t.distantDistance);
    }

    /** The settings round-trip through a level's own settings block. */
    @Test
    void terrainSettingsSaveWithTheLevelSettings() {
        GameProfile p = new GameProfile();
        p.terrain.enabled = true;
        p.terrain.seed = 123456789L;
        p.terrain.renderDistance = 96;
        p.terrain.distantDistance = 1024;
        p.terrain.caveDensity = 17;
        p.terrain.worldSize = 4096;

        GameProfile back = GameProfile.fromMap(p.toMap());
        assertTrue(back.terrain.enabled);
        assertEquals(123456789L, back.terrain.seed);
        assertEquals(96, back.terrain.renderDistance);
        assertEquals(1024, back.terrain.distantDistance);
        assertEquals(17, back.terrain.caveDensity);
        assertEquals(4096, back.terrain.worldSize);
        assertEquals(p.terrain.biomes.size(), back.terrain.biomes.size());
    }

    /** A level saved before any of this existed loads as a level, not a world. */
    @Test
    void anOldLevelIsStillALevel() {
        Level lvl = LevelFormat.THREE_D.starterLevel("Old", 24, 24, 32);
        lvl.settings = new GameProfile();
        String json = lvl.toJson();
        assertFalse(json.contains("\"world\""), "nothing to write for a level");
        Level back = LevelLoader.parse(json);
        assertFalse(back.isWorld());
        assertNull(back.terrain());
        assertEquals(24, back.width);
    }

    // --- walking in a world --------------------------------------------------------

    /**
     * A world turns the height axis on, because a landscape you cannot climb is
     * a wall.
     *
     * <p>With it off, a plan view reads the whole column — a floor tile is a
     * path, two stacked blocks are a barrier — and every column of a generated
     * world is a hundred and fifty blocks of rock. A player would stand on a
     * mountain range unable to take a step, which is not a preference anybody
     * would have chosen.
     */
    @Test
    void aWorldStandsOnItsOwnGround() {
        Level lvl = LevelFormat.THREE_D.starterLevel("Home", 64, 48, 32);
        lvl.settings = new GameProfile();
        assertFalse(lvl.settings.verticality, "off until something asks for it");
        lvl.settings.terrain = settings(4004);
        lvl.applyTerrainSettings();
        assertTrue(lvl.settings.verticality, "a world is somewhere a body can be");
        assertTrue(lvl.verticality());
    }

    /**
     * A body walks a generated landscape: it stands on the ground, climbs what
     * it can step onto, and is stopped by what it cannot.
     */
    @Test
    void abodyWalksTheLandscape() {
        Level lvl = worldLevel(90210);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 180, row = authored[1] + 180;
        int ground = lvl.supportHeight(col, row);
        assertTrue(ground > 100, "the ground under a body is the landscape, got " + ground);

        double ts = lvl.tileSize;
        double z = lvl.surfaceZ(ground);
        assertEquals(z, com.larsons.engine.sim.PlayerPhysics.groundZ(
                        lvl, col * ts + ts / 2, row * ts + ts / 2, ts * 0.8), 0.001,
                "standing on the top of the column");
        assertFalse(com.larsons.engine.sim.PlayerPhysics.barrierAt(lvl, col, row, z),
                "the ground you are standing on is not a wall");
        assertTrue(com.larsons.engine.sim.PlayerPhysics.barrierAt(lvl, col, row, 0),
                "and it is one from the bottom of the world");
    }

    // --- editing a world -----------------------------------------------------------

    /** Blocks placed and mined in a generated world behave like blocks. */
    @Test
    void aWorldIsStillSomethingYouCanBuildIn() {
        Level lvl = worldLevel(60);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 300, row = authored[1] + 300;
        int ground = lvl.columnDepth(col, row);
        Block brick = BlockRegistry.standard().get("stone_bricks");
        assertNotNull(brick);

        assertTrue(lvl.setTile(col, row, ground, brick.id()), "place");
        assertEquals(brick.id(), lvl.tileAt(col, row, ground));
        assertEquals(ground + 1, lvl.columnDepth(col, row), "the column grew");

        assertTrue(lvl.setTile(col, row, ground, 0), "mine it again");
        assertEquals(0, lvl.tileAt(col, row, ground));
        assertEquals(ground, lvl.columnDepth(col, row), "and the column shrank back");

        // Mining a block partway down leaves everything above it alone.
        int below = ground - 4;
        int above = lvl.tileAt(col, row, below + 1);
        assertTrue(lvl.setTile(col, row, below, 0), "dig into the ground");
        assertEquals(0, lvl.tileAt(col, row, below));
        assertEquals(above, lvl.tileAt(col, row, below + 1),
                "digging one block out does not drop the column above it");
    }

    /** A play-test snapshot puts a world back exactly as it was. */
    @Test
    void aPlayTestCannotEatTheWorld() {
        Level lvl = worldLevel(4321);
        int[] authored = lvl.terrain().authoredBounds();
        int col = authored[0] + authored[2] + 160, row = authored[1] + 160;
        int depth = lvl.columnDepth(col, row);

        Object snapshot = lvl.snapshotTiles();
        for (int layer = Math.max(1, depth - 30); layer < depth; layer++) {
            lvl.setTile(col, row, layer, 0);
        }
        assertTrue(lvl.columnDepth(col, row) < depth, "the play-test mined it out");

        lvl.restoreTiles(snapshot);
        assertEquals(depth, lvl.columnDepth(col, row), "and the level put it back");
    }

    // --- streaming ------------------------------------------------------------------

    /**
     * Digging down stops streaming the surface: a player under the ground keeps
     * far fewer chunks resident than the same player standing on it.
     *
     * <p><b>A chunk is a column of world, and walking into one does not mean
     * the player has anything to do with all of it.</b> Sixty blocks down a
     * shaft the render distance still says twenty tiles and the four walls of
     * the shaft say four, so the disc of surface ground that used to be
     * generated — and, with {@code saveExplored} on, written into the save —
     * was ground the player could not see a single block of.
     */
    @Test
    void diggingDownStopsStreamingTheSurface() {
        Level surface = worldLevel(31337);
        Level buried = worldLevel(31337);
        int ts = surface.tileSize;
        int[] authored = surface.terrain().authoredBounds();
        // Well outside the authored rectangle, where the generator runs.
        double x = (authored[0] + authored[2] + 300) * (double) ts;
        double y = (authored[1] + 300) * (double) ts;
        int col = (int) (x / ts), row = (int) (y / ts);
        double ground = surface.surfaceZ(Math.max(1, surface.groundedDepth(col, row) + 1));

        surface.streamTerrain(x, y, ground, 48);
        buried.streamTerrain(x, y, ground - 40 * ts, 48);
        for (int i = 0; i < 400; i++) sleep();

        assertTrue(buried.terrain().loadedChunks() < surface.terrain().loadedChunks(),
                "forty blocks down should stream less than standing on top: "
                        + buried.terrain().loadedChunks() + " vs "
                        + surface.terrain().loadedChunks());
        surface.terrain().close();
        buried.terrain().close();
    }

    /**
     * A caller with no height to offer streams exactly as much as it always
     * did — an editor panning a map, and every call written before the height
     * was there to pass.
     */
    @Test
    void aCallerWithNoHeightStreamsTheFullRing() {
        Level withoutZ = worldLevel(4242);
        Level withZ = worldLevel(4242);
        int ts = withoutZ.tileSize;
        int[] authored = withoutZ.terrain().authoredBounds();
        double x = (authored[0] + authored[2] + 300) * (double) ts;
        double y = (authored[1] + 300) * (double) ts;
        int col = (int) (x / ts), row = (int) (y / ts);
        double ground = withZ.surfaceZ(Math.max(1, withZ.groundedDepth(col, row) + 1));

        withoutZ.streamTerrain(x, y, 48);
        withZ.streamTerrain(x, y, ground, 48);
        for (int i = 0; i < 400; i++) sleep();

        // Within a chunk of each other rather than exactly equal: the builds
        // land on a worker pool, so which of them has finished at the moment
        // the count is taken is not something either level decides.
        assertTrue(Math.abs(withZ.terrain().loadedChunks()
                        - withoutZ.terrain().loadedChunks()) <= 2,
                "no height offered reads as standing on the ground: "
                        + withoutZ.terrain().loadedChunks() + " vs "
                        + withZ.terrain().loadedChunks());
        withoutZ.terrain().close();
        withZ.terrain().close();
    }

    private static void assertArrayEqualsInts(int[] a, int[] b, String message) {
        if (a == null && b == null) return;
        assertNotNull(a, message);
        assertNotNull(b, message);
        assertEquals(a.length, b.length, message + " (run count)");
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], message + " (run " + i + ")");
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
