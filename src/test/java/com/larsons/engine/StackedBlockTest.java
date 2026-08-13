package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second layer of blocks the plan-view formats build in, and what it is
 * for.
 *
 * <p>Top-down and isometric levels used to say "wall" and "path" with two
 * families of block, which is a thing the camera cannot actually show: seen
 * from above, a wall and the floor beside it are both squares, and the only
 * difference between them was a colour the player had to learn. So the plan
 * views stack instead — one layer of block is floor to walk on, two is a
 * barrier — and a stacked block is <em>drawn</em> as one: standing off its own
 * floor tile, showing the side face that lift exposes, and casting a shadow
 * that says how tall it is. Bare ground is a hole, because a plan view has no
 * "down" to fall along and nowhere to stand is nowhere to go.
 *
 * <p>Height also makes the layers real: a wall is not a layer painted over the
 * actors but a thing standing among them, so walking north behind one hides
 * you and walking south past it does not.
 */
@Timeout(60)
class StackedBlockTest {

    private static final int CANVAS = 320;
    private static final int TILE = 32;

    /** The stand-in an actor is drawn in, in a colour no block uses. */
    private static final Color ACTOR = new Color(255, 0, 255);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void unskinned() {
        // The assertions read procedural art, so no sheet another test
        // installed may be what actually draws.
        Skins.install(List.of());
    }

    // --- height and shadow ------------------------------------------------------

    /**
     * A stacked block is drawn standing up: it reaches above the tile it
     * occupies, which is the whole reason the second layer exists. A single
     * layer stays flat inside its own tile, as a floor should.
     */
    @Test
    void aStackedBlockStandsAboveItsOwnFloorTile() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level flat = floored(format);
            Level wall = floored(format);
            wall.setUpper(15, 15, wall.blocks.get("stone").id());

            int lift = TerrainPainter.liftPixels(camera(flat), TILE);
            assertTrue(lift > 0, format + ": a plan view lifts stacked blocks");

            // The top edge of the drawn cell moves up the screen by the lift.
            assertTrue(topOfCell(wall, 15, 15) < topOfCell(flat, 15, 15) - lift / 2,
                    format + ": the stacked block reaches above its floor tile");
        }
    }

    /** The shadow is what makes that height legible from straight above. */
    @Test
    void aStackedBlockCastsAShadowAndAFloorTileDoesNot() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level flat = floored(format);
            Level wall = floored(format);
            wall.setUpper(15, 15, wall.blocks.get("stone").id());

            long flatDark = darkPixels(paint(flat));
            long wallDark = darkPixels(paint(wall));
            assertTrue(wallDark > flatDark + 100, format
                    + ": the stack darkens the floor around it, " + wallDark
                    + " vs " + flatDark);
        }
    }

    /** A side-scroller stacks nothing, so nothing about its terrain changes. */
    @Test
    void aSideScrollerDrawsItsOneLayerExactlyAsBefore() {
        Level lvl = floored(LevelFormat.SIDE_SCROLLER);
        assertFalse(lvl.layered());
        assertEquals(0, TerrainPainter.liftPixels(camera(lvl), TILE),
                "there is no height axis to lift along in a side view");
        assertEquals(topOfCell(lvl, 15, 15), topOfCell(floored(LevelFormat.SIDE_SCROLLER), 15, 15));
    }

    // --- layering against the actors --------------------------------------------

    /**
     * A wall belongs among the things standing on the floor, not in a layer of
     * its own: whether it is in front of a player is decided by where each of
     * them stands, the same rule that already decided it for a tree.
     */
    @Test
    void aPlayerPassesBehindAWallAndInFrontOfTheNextOne() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = floored(format);
            lvl.setUpper(15, 15, lvl.blocks.get("stone").id());
            Camera cam = camera(lvl);
            int[] feet = project(cam, 15.5 * TILE, 16 * TILE);

            // The same marker over the same pixels, standing a little north of
            // the wall and then a little south of it.
            int hidden = actorPixelsAmongTerrain(lvl, cam, feet, feet[1] - 16);
            int seen = actorPixelsAmongTerrain(lvl, cam, feet, feet[1] + 16);
            assertTrue(seen > 400, format + ": south of the wall they are visible, " + seen);
            assertTrue(hidden < seen / 2, format
                    + ": north of it the wall covers them, " + hidden + " vs " + seen);
        }
    }

    // --- what the stack means ---------------------------------------------------

    /** Mining takes a stack apart from the top: wall, then path, then hole. */
    @Test
    void miningTakesAWallDownToAPathAndThenToAHole() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        lvl.stackTile(15, 15, stone);
        World world = new World(lvl);

        assertEquals(Level.LAYER_UPPER, world.mineLayer(15, 15));
        assertNotNull(world.mineBlock(15, 15, false), "the wall comes off first");
        assertEquals(1, lvl.stackHeight(15, 15));
        assertTrue(lvl.walkable(15, 15), "leaving a path to walk along");

        assertEquals(Level.LAYER_GROUND, world.mineLayer(15, 15));
        assertNotNull(world.mineBlock(15, 15, false), "and the path comes off next");
        assertEquals(0, lvl.stackHeight(15, 15));
        assertFalse(lvl.walkable(15, 15), "leaving a hole nobody can cross");
        assertNull(world.mineBlock(15, 15, false), "there is nothing left to mine");
    }

    /** Placing builds the stack back up in the same order. */
    @Test
    void placingFloorsAHoleBeforeStandingAnythingOnIt() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(15, 15, 0);
        World world = new World(lvl);

        assertEquals(Level.LAYER_GROUND, lvl.placeLayer(15, 15));
        assertTrue(world.placeBlock(15, 15, stone), "the hole is floored first");
        assertTrue(lvl.walkable(15, 15));

        assertEquals(Level.LAYER_UPPER, lvl.placeLayer(15, 15));
        assertTrue(world.placeBlock(15, 15, stone), "then the block stands on it");
        assertFalse(lvl.walkable(15, 15), "which is what makes it a wall");

        assertEquals(-1, lvl.placeLayer(15, 15), "and the cell is full");
        assertFalse(world.placeBlock(15, 15, stone));
    }

    /** A side-scroller keeps the single-layer rule it always had. */
    @Test
    void aSideScrollerStillPlacesAndMinesOneLayer() {
        Level lvl = Level.empty("side", 30, 30, TILE);
        lvl.setFormat(LevelFormat.SIDE_SCROLLER);
        int stone = lvl.blocks.get("stone").id();
        World world = new World(lvl);

        assertEquals(Level.LAYER_GROUND, lvl.placeLayer(15, 15));
        assertTrue(world.placeBlock(15, 15, stone));
        assertTrue(lvl.solidAt(15, 15));
        assertEquals(-1, lvl.placeLayer(15, 15), "the cell is full with one block");
        assertNotNull(world.mineBlock(15, 15, false));
        assertFalse(lvl.solidAt(15, 15), "and mining it leaves open air, not a hole");
    }

    // --- the height axis, as rotation has to address it -------------------------

    /**
     * The stack, read as a height: 0 is a hole, 1 is floor, 2 is a wall, and
     * there is no 3.
     *
     * <p>C2 of the render plan asks for "a {@code heightAt(col, row)} accessor
     * returning 0/1/2" before the camera can rotate, because a rotated view has
     * to know how tall a cell is to know which of its faces the camera can see.
     * {@link Level#stackHeight} already is that accessor — it was written for
     * mining, which takes a stack apart from the top down, and the two uses want
     * the same number. What was missing is this: the range it may answer in was
     * documented and never asserted, and C4 is about to depend on it. A cell
     * that answered 3 would be a face-visibility bug rather than a level-format
     * one, and it would surface as geometry drawn at the wrong height.
     *
     * <p><b>The ceiling of two is a decision, not a limit waiting to be
     * raised</b> — recorded in the plan at C2. It is asserted here because a
     * third layer would arrive as a silently taller answer from this method,
     * long before anything drew it.
     */
    @Test
    void theHeightAxisIsZeroOneOrTwoAndNeverThree() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = floored(format);
            int stone = lvl.blocks.get("stone").id();

            lvl.setTile(15, 15, 0);
            assertEquals(0, lvl.stackHeight(15, 15), format + ": bare ground is a hole");

            lvl.setTile(15, 15, lvl.blocks.get("stone_path").id());
            assertEquals(1, lvl.stackHeight(15, 15), format + ": one layer is a floor");

            // The side-scroller has nowhere to put a second block and refuses
            // the edit, so its height axis tops out at 1. That is the §6.1 scope
            // rule seen from the level format's side: the format with no second
            // layer is exactly the format that does not rotate.
            boolean stacked = lvl.setUpper(15, 15, stone);
            assertEquals(lvl.layered(), stacked,
                    format + ": only a layered format accepts a stacked block");
            assertEquals(lvl.layered() ? 2 : 1, lvl.stackHeight(15, 15),
                    format + ": two layers is a wall");

            // Nothing can go on top of a full cell, so nothing can answer 3.
            assertEquals(-1, lvl.placeLayer(15, 15), format + ": the cell is full");
            assertTrue(lvl.stackHeight(15, 15) <= 2, format
                    + ": the stack limit is two, and raising it is a decision C2 recorded "
                    + "against — it reaches liquids, pathfinding, the palette and the "
                    + "save format");

            // Outside the level is a hole too, which is what the sweep meets
            // first when the view turns and its bounds stop being the grid's.
            assertEquals(0, lvl.stackHeight(-1, 15), format + ": off the west edge");
            assertEquals(0, lvl.stackHeight(lvl.width, 15), format + ": off the east edge");
        }
    }

    /**
     * Height is geometry and walkability is solidity, and they are deliberately
     * not the same question.
     *
     * <p>The obvious reading of "height 2 means a wall" is wrong, and it would
     * have been a natural thing to assert. A torch standing on a path is two
     * blocks deep and you can still walk through it — {@link Level#walkable}
     * asks the stacked block whether it is solid, and dressing is not a barrier.
     * That distinction matters to the step this one exists for: C4 derives
     * <em>visible faces</em> from height, so a torch has a side face to show
     * when the camera turns, while collision goes on asking {@code walkable} and
     * gets a different answer for the same cell.
     *
     * <p>Written down here because a test asserting the tidier rule would have
     * passed today — nothing yet reads height for drawing — and would have made
     * the torch un-drawable the moment something did.
     */
    @Test
    void heightIsGeometryAndWalkabilityIsSolidity() {
        Level lvl = floored(LevelFormat.TOP_DOWN);

        lvl.setUpper(15, 15, lvl.blocks.get("stone").id());
        assertEquals(2, lvl.stackHeight(15, 15));
        assertFalse(lvl.walkable(15, 15), "a stone block stood on a path is a wall");

        lvl.setUpper(16, 15, lvl.blocks.get("torch").id());
        assertEquals(2, lvl.stackHeight(16, 15),
                "a torch stands on the floor, so the cell is two blocks deep");
        assertTrue(lvl.walkable(16, 15),
                "and you walk through it, because it is dressing rather than a barrier");
    }

    /** A generated maze is built in the layers, walls standing on a floor. */
    @Test
    void theMazeGeneratorStandsItsWallsOnAFloor() {
        Level maze = LevelGenerator.generateMaze("maze", 21, 21, TILE, 5L,
                LevelFormat.TOP_DOWN);
        int floors = 0, walls = 0;
        for (int r = 0; r < maze.height; r++) {
            for (int c = 0; c < maze.width; c++) {
                assertTrue(maze.tileAt(c, r) > 0,
                        "every cell of a maze has a floor at (" + c + "," + r + ")");
                if (maze.stackHeight(c, r) == 2) {
                    walls++;
                } else {
                    floors++;
                }
            }
        }
        assertTrue(walls > 0 && floors > 0, "a maze is walls and corridors");
        assertTrue(maze.walkable((int) (maze.spawnX / TILE) + 1,
                        (int) (maze.spawnY / TILE) + 1)
                        || maze.walkable((int) (maze.spawnX / TILE),
                        (int) (maze.spawnY / TILE)),
                "and you can stand where it spawns you");
    }

    // --- mining feedback --------------------------------------------------------

    /**
     * The crack overlay has to survive the frame it is drawn in.
     *
     * <p>Two things used to eat it. It measured a cell as the gap between two
     * opposite corners, which is zero in isometric — a diamond's left and right
     * corners are the other pair — so it drew nothing there at all. And it was
     * painted outside the depth queue, before that queue was flushed, so every
     * stacked block landed on top of it: in a top-down level the mined block
     * covered its own cracks, and in an isometric one the walls to the south
     * stood up over the cell to their north-west and covered even a floor
     * tile's. So this asks the whole render — queue and flush — not the
     * overlay in isolation.
     */
    @Test
    void miningCracksSurviveTheFrameInEveryProjection() {
        for (LevelFormat format : LevelFormat.values()) {
            // Mining a floor tile in the open: nothing stands over it, so the
            // overlay has only itself to fail at — which is what the zero-width
            // isometric cell did.
            assertTrue(crackPixelsOnBlock(floored(format), 15, 15) > 20,
                    format + ": the crack overlay is drawn at all");

            // Mining a wall, with more walls around it — a plan-view level
            // being mined looks like this, and the mined block is the one that
            // used to paint over its own cracks.
            Level walls = floored(format);
            int stone = walls.blocks.get("stone").id();
            for (int r = 14; r <= 16; r++) {
                for (int c = 14; c <= 16; c++) walls.setUpper(c, r, stone);
            }
            assertTrue(crackPixelsOnBlock(walls, 15, 15) > 20, format
                    + ": a stacked block's cracks are not covered by the stack itself");
        }

        // On a stack they rise with the block they belong to, rather than
        // staying on the floor beside the wall being mined.
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level flat = floored(format);
            Level wall = floored(format);
            wall.setUpper(15, 15, wall.blocks.get("stone").id());
            assertTrue(crackCentreY(wall, 15, 15) < crackCentreY(flat, 15, 15),
                    format + ": cracks rise onto the stacked block");
        }
    }

    /**
     * The overlay has to be the size of the block it is on. A block is not the
     * same size along both screen axes — an isometric tile projects to a
     * diamond twice as wide as it is tall — so a pattern measured along the
     * width alone came out at twice the block's height there, spilling over the
     * tiles around it.
     */
    @Test
    void miningCracksAreTheSizeOfTheBlockTheyAreOn() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = floored(format);
            int[] ink = inkBounds(crackInk(lvl, 15, 15));
            int[] tile = tileBounds(camera(lvl), 15, 15);

            // Cracks spider a little past the edge of what they are breaking,
            // which is the look; twice its size is not.
            double wide = ink[2] / (double) tile[2];
            double tall = ink[3] / (double) tile[3];
            assertTrue(wide < 1.4, format + ": the overlay is "
                    + Math.round(wide * 100) + "% of the block's width");
            assertTrue(tall < 1.4, format + ": the overlay is "
                    + Math.round(tall * 100) + "% of the block's height");
            // …and it is not so small it has stopped reading as a break.
            assertTrue(wide > 0.5 && tall > 0.5,
                    format + ": the overlay still covers the block");
        }
    }

    // --- the light direction ----------------------------------------------------

    /**
     * Where the sun stands is the level's to say, and turning it turns every
     * shadow with it — the point of the setting being that a creator can see
     * the change rather than take it on trust.
     */
    @Test
    void turningTheSunTurnsTheShadows() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level northWest = floored(format);
            northWest.setUpper(15, 15, northWest.blocks.get("stone").id());
            assertEquals(Level.DEFAULT_LIGHT_ANGLE, northWest.lightAngle);

            Level northEast = floored(format);
            northEast.setUpper(15, 15, northEast.blocks.get("stone").id());
            northEast.lightAngle = 45;   // sun swung to the other shoulder

            double[] a = shadowCentre(northWest, 15, 15);
            double[] b = shadowCentre(northEast, 15, 15);
            assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) > 2, format
                    + ": the shadow moved with the sun (" + a[0] + "," + a[1]
                    + " vs " + b[0] + "," + b[1] + ")");
            // North-west light throws the shadow east; north-east throws it west.
            assertTrue(a[0] > b[0], format + ": and it swung the right way");
        }
    }

    /** The bearing survives a save, because it is a look the level owns. */
    @Test
    void theLightDirectionIsSavedWithTheLevel() {
        Level lvl = LevelFormat.ISOMETRIC.starterLevel("Sun", 20, 12, 32);
        lvl.lightAngle = 120;
        assertEquals(120.0, LevelLoader.parse(lvl.toJson()).lightAngle);
        // A level that never moved its sun says nothing and loads the default.
        Level plain = LevelFormat.ISOMETRIC.starterLevel("Plain", 20, 12, 32);
        assertFalse(plain.toJson().contains("lightAngle"));
        assertEquals(Level.DEFAULT_LIGHT_ANGLE, LevelLoader.parse(plain.toJson()).lightAngle);
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * How many crack pixels land on the mined block <em>itself</em> — within a
     * quarter-tile of where that block is drawn.
     *
     * <p>Counting the whole overlay would not do: the cracks radiate a little
     * past the tile onto its neighbours, so even an overlay completely painted
     * over still leaves a fringe. What says the overlay is working is ink at
     * the middle of the block being mined, which is exactly what a block drawn
     * on top of it takes away.
     */
    private static int crackPixelsOnBlock(Level lvl, int col, int row) {
        Camera cam = camera(lvl);
        int lift = lvl.upperAt(col, row) > 0 ? TerrainPainter.liftPixels(cam, TILE) : 0;
        int[] centre = project(cam, (col + 0.5) * TILE, (row + 0.5) * TILE);
        centre[1] -= lift;
        int[] edge = project(cam, (col + 1.0) * TILE, (row + 0.5) * TILE);
        double radius = Math.max(4, Math.hypot(edge[0] - centre[0],
                edge[1] - (centre[1] + lift)) * 0.5);
        int on = 0;
        for (int[] px : crackInk(lvl, col, row)) {
            if (Math.hypot(px[0] - centre[0], px[1] - centre[1]) <= radius) on++;
        }
        return on;
    }

    /** The mean screen row of the crack overlay's pixels. */
    private static double crackCentreY(Level lvl, int col, int row) {
        List<int[]> ink = crackInk(lvl, col, row);
        return ink.stream().mapToInt(p -> p[1]).average().orElse(0);
    }

    /**
     * The pixels the crack overlay adds to a <em>finished</em> frame, as
     * {x, y} pairs — the whole terrain render with the stroke in it, against
     * the same render without, so anything the pass draws over the cracks
     * counts against them the way it does on screen.
     */
    private static List<int[]> crackInk(Level lvl, int col, int row) {
        return differing(paint(lvl, null),
                paint(lvl, new TerrainPainter.Mining(col, row, 0.9)));
    }

    /** The mean position of the shadow a stacked block casts. */
    private static double[] shadowCentre(Level lvl, int col, int row) {
        int stacked = lvl.upperAt(col, row);
        BufferedImage with = paint(lvl);
        lvl.setUpper(col, row, 0);
        BufferedImage without = paint(lvl);
        lvl.setUpper(col, row, stacked);
        // Below the block's own tile: only its shadow reaches down there, so
        // the block and its side faces can't drag the answer around.
        int[] base = project(camera(lvl), (col + 0.5) * TILE, (row + 1.0) * TILE);
        long n = 0, sx = 0, sy = 0;
        for (int[] px : differing(with, without)) {
            if (px[1] <= base[1]) continue;
            n++;
            sx += px[0];
            sy += px[1];
        }
        return n == 0 ? new double[]{0, 0} : new double[]{sx / (double) n, sy / (double) n};
    }

    /** {@code {x, y, width, height}} of a set of screen pixels. */
    private static int[] inkBounds(List<int[]> ink) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] px : ink) {
            minX = Math.min(minX, px[0]);
            maxX = Math.max(maxX, px[0]);
            minY = Math.min(minY, px[1]);
            maxY = Math.max(maxY, px[1]);
        }
        return ink.isEmpty() ? new int[]{0, 0, 0, 0}
                : new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** {@code {x, y, width, height}} of the tile at (col,row) as projected. */
    private static int[] tileBounds(Camera cam, int col, int row) {
        List<int[]> corners = new java.util.ArrayList<>(4);
        double[][] cs = {{col, row}, {col + 1, row}, {col + 1, row + 1}, {col, row + 1}};
        for (double[] c : cs) corners.add(project(cam, c[0] * TILE, c[1] * TILE));
        return inkBounds(corners);
    }

    /** Pixels where two frames disagree, as {x, y} pairs. */
    private static List<int[]> differing(BufferedImage a, BufferedImage b) {
        List<int[]> out = new java.util.ArrayList<>();
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) out.add(new int[]{x, y});
            }
        }
        return out;
    }

    /** A level of this format, floored everywhere so there are no holes in it. */
    private static Level floored(LevelFormat format) {
        Level lvl = Level.empty("stacks", 30, 30, TILE);
        lvl.setFormat(format);
        Block floor = lvl.blocks.get("stone_path");
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, floor.id());
        }
        return lvl;
    }

    private static Camera camera(Level lvl) {
        Camera cam = new Camera(lvl.perspective, CANVAS, CANVAS);
        cam.tileSize = lvl.tileSize;
        cam.centerOn(15.5 * TILE, 15.5 * TILE);
        return cam;
    }

    private static int[] project(Camera cam, double wx, double wy) {
        int[] out = new int[2];
        cam.worldToScreen(wx, wy, out);
        return out;
    }

    /** The level's terrain, drawn the way the scenes draw it. */
    private static BufferedImage paint(Level lvl) {
        return paint(lvl, null);
    }

    /**
     * The level's terrain the way the scenes draw it — one depth pass, queued
     * and then flushed — optionally with a hold-to-mine stroke in it.
     */
    private static BufferedImage paint(Level lvl, TerrainPainter.Mining mining) {
        Camera cam = camera(lvl);
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        DepthPass standing = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam,
                new int[]{0, 0, lvl.width - 1, lvl.height - 1},
                0.5, standing, null, mining);
        standing.flush();
        g.dispose();
        return canvas;
    }

    /**
     * The topmost screen row the cell at (col,row) paints anything on, found by
     * comparing the level against the same level with that cell cleared. A
     * stacked block claims rows above its own tile; a floor tile does not.
     */
    private static int topOfCell(Level lvl, int col, int row) {
        BufferedImage with = paint(lvl);
        int floor = lvl.tileAt(col, row), stacked = lvl.upperAt(col, row);
        lvl.setUpper(col, row, 0);
        lvl.setTile(col, row, 0);
        BufferedImage without = paint(lvl);
        lvl.setTile(col, row, floor);
        lvl.setUpper(col, row, stacked);
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (with.getRGB(x, y) != without.getRGB(x, y)) return y;
            }
        }
        return CANVAS;
    }

    /** Pixels darker than the plain floor — the cast shadow, and the side faces. */
    private static long darkPixels(BufferedImage img) {
        long dark = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                int rgb = img.getRGB(x, y);
                if ((rgb >>> 24) == 0) continue;
                int lum = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
                if (lum < 120) dark++;
            }
        }
        return dark;
    }

    /**
     * How much of an actor-sized marker survives being drawn among the terrain
     * at {@code depth} — what the scenes do when they hand the player and the
     * stacked blocks to the same {@link DepthPass}.
     */
    private static int actorPixelsAmongTerrain(Level lvl, Camera cam, int[] feet,
                                               int depth) {
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Java2DTarget target = Java2DTarget.unsized(g);
        DepthPass standing = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam, new int[]{0, 0, lvl.width - 1, lvl.height - 1},
                0.5, standing, null);
        standing.at(depth, () ->
                target.fillRect(feet[0] - 14, feet[1] - 28, 28, 28, ACTOR));
        standing.flush();
        g.dispose();
        int seen = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (canvas.getRGB(x, y) == ACTOR.getRGB()) seen++;
            }
        }
        return seen;
    }
}
