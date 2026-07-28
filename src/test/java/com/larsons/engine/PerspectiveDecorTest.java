package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DecorPainter;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decorations have to read as decorations in all three perspectives, and the
 * two ways they used not to:
 *
 * <ul>
 *   <li><b>Scenery fell behind the blocks.</b> "Background" was taken to mean
 *       "before the terrain" everywhere, which is right in a side view — the
 *       blocks are a wall standing between the camera and the distance — and
 *       ruinous on a plane, where the same blocks are the <em>floor</em>.
 *       Every tree, boulder and bush painted onto walkable ground was covered
 *       by the very tile it was planted on, so a top-down or isometric level
 *       could not be decorated at all.</li>
 *   <li><b>Block decor was drawn in screen space.</b> Grass grew straight up
 *       the screen and twigs straight across it whatever the camera was doing,
 *       so in isometric — where a block's top edge runs up and to the right —
 *       every detail tore loose from the face it was attached to. Crystals had
 *       their growth direction inverted on top of that, and grew into the
 *       block in every perspective.</li>
 * </ul>
 *
 * <p>Overlapping decorations were also drawn in whatever order the level file
 * happened to store them in, so a distant tree could cover one standing in
 * front of it.
 */
@Timeout(60)
class PerspectiveDecorTest {

    private static final int CANVAS = 320;
    private static final int TILE = 32;

    /** Slack allowed when asking whether a pixel landed on a tile, in pixels. */
    private static final double EDGE_SLACK = 2.0;

    /** The stand-in an actor is drawn in, in a colour no decoration uses. */
    private static final Color PLAYER = new Color(255, 0, 255);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void unskinned() {
        // Assertions read the procedural art, so make sure no sheet another
        // test installed is what actually draws.
        Skins.install(List.of());
    }

    // --- scenery on the terrain -------------------------------------------------------

    @Test
    void aTreePlantedOnAPlanViewFloorIsNotBuriedByIt() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = floored(format);
            plant(lvl, "decor_bg", "oak_tree", 15, 15);

            assertTrue(ink(lvl).count() > 200,
                    format + ": a tree painted on the floor is visible");

            // The same level with the layer order the side view wants: the
            // floor is painted over the tree and it disappears completely.
            assertEquals(0, inkWith(lvl, true).count(),
                    format + ": drawing scenery before the floor buries it — the bug");
        }
    }

    @Test
    void aSideScrollersBackgroundLayerStillHidesBehindItsTerrain() {
        // The side view is the reference: nothing here may change. Its blocks
        // really are something to stand behind, which is what the background
        // layer is for.
        Level lvl = floored(LevelFormat.SIDE_SCROLLER);
        plant(lvl, "decor_bg", "oak_tree", 15, 15);
        assertEquals(0, ink(lvl).count(),
                "background scenery is hidden by the hill in front of it");

        Level front = floored(LevelFormat.SIDE_SCROLLER);
        plant(front, "decor_fg", "oak_tree", 15, 15);
        assertTrue(ink(front).count() > 200,
                "…while the foreground layer covers the terrain");
    }

    @Test
    void everyFormatDrawsForegroundSceneryOverTheTerrain() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = floored(format);
            plant(lvl, "decor_fg", "boulder", 15, 15);
            assertTrue(ink(lvl).count() > 50,
                    format + ": foreground scenery covers the blocks");
        }
    }

    @Test
    void whichSideOfTheTerrainSceneryLandsOnIsTheSpacesAnswer() {
        assertTrue(PerspectiveSpace.SIDE_VIEW.scenerySitsBehindTerrain(),
                "only the side view has a behind to hide in");
        for (PerspectiveSpace plane : List.of(PerspectiveSpace.TOP_DOWN,
                PerspectiveSpace.ISOMETRIC)) {
            assertFalse(plane.scenerySitsBehindTerrain(),
                    plane + " plants scenery on the floor, not under it");
        }
    }

    // --- overlapping scenery ----------------------------------------------------------

    @Test
    void nearerSceneryCoversFartherScenery() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("overlap", 30, 30, TILE);
            lvl.setFormat(format);
            // Stored far-first is what a level file happens to hold; stored
            // near-first is what used to break, because the painter simply
            // replayed the list. Both orders must draw the same picture.
            Level reversed = Level.empty("overlap", 30, 30, TILE);
            reversed.setFormat(format);

            plant(lvl, "decor_bg", "oak_tree", 15, 15);          // far (up-screen)
            plant(lvl, "decor_bg", "blossom_tree", 15.6, 15.9);  // near, drawn over it
            plant(reversed, "decor_bg", "blossom_tree", 15.6, 15.9);
            plant(reversed, "decor_bg", "oak_tree", 15, 15);

            BufferedImage a = paint(lvl, false);
            BufferedImage b = paint(reversed, false);
            assertEquals(0, differing(a, b),
                    format + ": overlapping scenery stacks by depth, not by list order");

            // The two canopies really do contend for pixels, or the assertion
            // above would be true of any painter at all.
            int alone = ink(only(lvl, 0)).count() + ink(only(lvl, 1)).count();
            assertTrue(alone - ink(lvl).count() > 500,
                    format + ": the two canopies overlap, contested="
                            + (alone - ink(lvl).count()));

            // …and it is the near one on top: the blossom's pink wins the
            // pixels the two share.
            assertTrue(count(a, new Color(235, 170, 200), 40) > 200,
                    format + ": the nearer decoration is the one you see");
        }
    }

    // --- block decor -------------------------------------------------------------------

    @Test
    void blockDecorStaysWeldedToTheFaceItIsAttachedTo() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("faces", 30, 30, TILE);
            lvl.setFormat(format);
            lvl.setTile(15, 15, lvl.blocks.get("stone").id());
            lvl.surfaceDecor.add(new SurfaceDecor.Placement(15, 15, SurfaceDecor.Face.UP,
                    "grass_tuft", false, SurfaceDecor.Visibility.ALWAYS));

            Camera cam = camera(lvl);
            Ink tuft = ink(lvl);
            int[] anchor = project(cam, 15.5 * TILE, 15.0 * TILE);
            // The tuft may not drift more than half a tile from the middle of
            // the edge it grows out of. Drawn up the screen, isometric put it
            // most of a tile away, over the neighbouring diamond.
            double drift = Math.hypot(tuft.x() - anchor[0], tuft.y() - anchor[1]);
            assertTrue(drift < TILE * 0.5,
                    format + ": grass sits on its own block, drift was " + drift);
        }
    }

    @Test
    void blockDecorSitsOnTheBlockItDecoratesInThePlanViews() {
        // Seen from above there is no edge to cling to: the block's visible
        // surface is its top, so every style on every face it allows has to
        // land on the tile rather than out in the neighbouring cell.
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            for (SurfaceDecor def : SurfaceDecorRegistry.standard().all()) {
                for (SurfaceDecor.Face face : SurfaceDecor.Face.values()) {
                    if (!def.allows(face)) continue;
                    Level lvl = decorated(format, def.key(), face);
                    Camera cam = camera(lvl);
                    int onBlock = 0, painted = 0;
                    for (int[] px : inkPixels(lvl)) {
                        painted++;
                        if (onTile(cam, 15, 15, px[0], px[1])) onBlock++;
                    }
                    String what = format + " " + def.key() + " on " + face;
                    assertTrue(painted > 0, what + ": drew nothing at all");
                    // A blade may lean past a corner — grass does — but the
                    // detail belongs to the block, so nearly all of it and its
                    // middle land there. Rooted on the seam and growing into
                    // the next cell, as the edge-on layout does, neither did.
                    Ink middle = ink(lvl);
                    assertTrue(onTile(cam, 15, 15, (int) middle.x(), (int) middle.y()),
                            what + ": sits on the block, not beside it");
                    assertTrue(onBlock >= painted * 0.8, what + ": only " + onBlock
                            + " of " + painted + " pixels landed on the block");
                }
            }
        }
    }

    @Test
    void blockDecorStillClingsToTheEdgeOfItsFaceInASideView() {
        // Edge-on, a face really is the line between two cells, and a detail
        // straddles it: grass stands above the block it grows on and moss
        // hangs below the one it clings to. None of that changes.
        Level grass = decorated(LevelFormat.SIDE_SCROLLER, "grass_tuft",
                SurfaceDecor.Face.UP);
        Camera cam = camera(grass);
        int[] top = project(cam, 15.5 * TILE, 15.0 * TILE);
        assertTrue(ink(grass).y() < top[1],
                "a side view's grass stands up off the top of its block");

        Level moss = decorated(LevelFormat.SIDE_SCROLLER, "hanging_moss",
                SurfaceDecor.Face.DOWN);
        int[] bottom = project(cam, 15.5 * TILE, 16.0 * TILE);
        assertTrue(ink(moss).y() > bottom[1],
                "…and its moss hangs down off the underside");
    }

    @Test
    void heightBecomesALiftUpTheScreenOnAPlane() {
        // What "up" means on a floor: a standing detail lifts the same way
        // whichever side of a block it grows on, because it is leaving the
        // floor rather than the face. A detail lying on the surface has no
        // such freedom — it runs across the block, so it reverses with the
        // face it started from.
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Ink leftUp = liftOff(format, "crystal_growth", SurfaceDecor.Face.LEFT);
            Ink rightUp = liftOff(format, "crystal_growth", SurfaceDecor.Face.RIGHT);
            assertTrue(leftUp.y() < 0 && rightUp.y() < 0, format
                    + ": crystals stand up off the block on either side of it ("
                    + leftUp.y() + ", " + rightUp.y() + ")");

            Ink leftLay = liftOff(format, "roots", SurfaceDecor.Face.LEFT);
            Ink rightLay = liftOff(format, "roots", SurfaceDecor.Face.RIGHT);
            assertTrue(leftLay.x() * rightLay.x() < 0, format
                    + ": roots creep across the block, away from opposite faces ("
                    + leftLay.x() + ", " + rightLay.x() + ")");
        }
    }

    /** Where a detail's ink sits relative to where the face roots it. */
    private static Ink liftOff(LevelFormat format, String key, SurfaceDecor.Face face) {
        Level lvl = decorated(format, key, face);
        Camera cam = camera(lvl);
        double lean = 0.2; // FaceFrame.EDGE_LEAN
        int[] root = project(cam, (15.5 + face.dc * lean) * TILE,
                (15.5 + face.dr * lean) * TILE);
        Ink painted = ink(lvl);
        return new Ink(painted.count(), painted.x() - root[0], painted.y() - root[1]);
    }

    @Test
    void crystalsGrowAwayFromTheFaceTheySproutedOnInASideView() {
        for (SurfaceDecor.Face face : SurfaceDecor.Face.values()) {
            Level lvl = decorated(LevelFormat.SIDE_SCROLLER, "crystal_growth", face);
            Camera cam = camera(lvl);
            double ax = (15.5 + face.dc * 0.5) * TILE, ay = (15.5 + face.dr * 0.5) * TILE;
            int[] anchor = project(cam, ax, ay);
            int[] beyond = project(cam, ax + face.dc * TILE, ay + face.dr * TILE);
            double outX = beyond[0] - anchor[0], outY = beyond[1] - anchor[1];

            Ink grown = ink(lvl);
            double reach = ((grown.x() - anchor[0]) * outX + (grown.y() - anchor[1]) * outY)
                    / Math.hypot(outX, outY);
            assertTrue(reach > 1, face
                    + ": crystals grow away from the block, not into it (reach "
                    + reach + ")");
        }
    }

    // --- actors among the scenery --------------------------------------------------------

    @Test
    void aPlayerPassesBehindTheSceneryTheyWalkBehind() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = Level.empty("walk", 30, 30, TILE);
            lvl.setFormat(format);
            plant(lvl, "decor_bg", "boulder", 15, 15);
            Camera cam = camera(lvl);
            int[] feet = project(cam, 15.5 * TILE, 16 * TILE);

            // The same player, drawn over the same pixels, standing a little
            // north of the boulder and then a little south of it.
            int hidden = playerPixelsAmongScenery(lvl, cam, feet, feet[1] - 12);
            int seen = playerPixelsAmongScenery(lvl, cam, feet, feet[1] + 12);
            assertTrue(seen > 500, format + ": in front of it they are visible, " + seen);
            assertTrue(hidden < seen / 4, format
                    + ": behind it the boulder covers them, " + hidden + " vs " + seen);
        }
    }

    @Test
    void aSideScrollersPlayerKeepsItsFixedLayerOrder() {
        // A side view's layers do not depend on where anyone is standing: the
        // player is in front of the background scenery full stop, which is
        // what its pass-through pass preserves.
        Level lvl = Level.empty("walk", 30, 30, TILE);
        lvl.setFormat(LevelFormat.SIDE_SCROLLER);
        plant(lvl, "decor_bg", "boulder", 15, 15);
        Camera cam = camera(lvl);
        int[] feet = project(cam, 15.5 * TILE, 16 * TILE);

        assertFalse(DepthPass.of(com.larsons.engine.graphics.Perspective.SIDE_SCROLL)
                .isSorted(), "the side view draws straight through");
        assertEquals(playerPixelsAmongScenery(lvl, cam, feet, feet[1] - 12),
                playerPixelsAmongScenery(lvl, cam, feet, feet[1] + 12),
                "which side of the boulder they stand on changes nothing");
    }

    @Test
    void aDepthPassDrawsBackToFrontAndKeepsTiesInOrder() {
        StringBuilder order = new StringBuilder();
        DepthPass sorted = DepthPass.sorted();
        sorted.at(30, () -> order.append('c'));
        sorted.at(10, () -> order.append('a'));
        sorted.at(20, () -> order.append('b'));
        sorted.at(20, () -> order.append('B')); // same depth, added later
        assertEquals(0, order.length(), "a sorted pass waits to be flushed");
        sorted.flush();
        assertEquals("abBc", order.toString());
        sorted.flush();
        assertEquals("abBc", order.toString(), "flushing twice does not redraw");

        StringBuilder now = new StringBuilder();
        DepthPass immediate = DepthPass.immediate();
        immediate.at(30, () -> now.append('c'));
        immediate.at(10, () -> now.append('a'));
        assertEquals("ca", now.toString(), "a pass-through draws in call order");
    }

    @Test
    void depthRunsWithTheWalkInEveryPerspective() {
        // Everything sharing a pass is ordered on this one number, so it has
        // to grow as a body walks toward the viewer — south on a plane, and
        // down the screen in a side view.
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("depth", 30, 30, TILE);
            lvl.setFormat(format);
            Camera cam = camera(lvl);
            int north = cam.worldToScreenY(15.5 * TILE, 14 * TILE);
            int here = cam.worldToScreenY(15.5 * TILE, 15 * TILE);
            int south = cam.worldToScreenY(15.5 * TILE, 16 * TILE);
            assertTrue(north < here && here < south,
                    format + ": walking south comes toward the viewer");
        }
    }


    @Test
    void blockDecorScalesWithTheProjectedTileNotTheRawTileSize() {
        // An isometric tile is wider on screen than its world size, so a
        // detail measured in raw world pixels came out undersized against the
        // diamond it decorates.
        Level flat = Level.empty("tuft", 30, 30, TILE);
        flat.setFormat(LevelFormat.TOP_DOWN);
        flat.setTile(15, 15, flat.blocks.get("stone").id());
        flat.surfaceDecor.add(new SurfaceDecor.Placement(15, 15, SurfaceDecor.Face.UP,
                "grass_tuft", false, SurfaceDecor.Visibility.ALWAYS));

        Level iso = Level.empty("tuft", 30, 30, TILE);
        iso.setFormat(LevelFormat.ISOMETRIC);
        iso.setTile(15, 15, iso.blocks.get("stone").id());
        iso.surfaceDecor.add(new SurfaceDecor.Placement(15, 15, SurfaceDecor.Face.UP,
                "grass_tuft", false, SurfaceDecor.Visibility.ALWAYS));

        assertTrue(ink(iso).count() > ink(flat).count(),
                "isometric grass is drawn to the diamond's scale, not the world's");
    }

    // --- culling -------------------------------------------------------------------------

    @Test
    void sceneryOffTheEdgeOfTheScreenIsNotDrawn() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("cull", 200, 200, TILE);
            lvl.setFormat(format);
            plant(lvl, "decor_bg", "oak_tree", 15, 15);
            assertTrue(ink(lvl).count() > 200, format + ": scenery in view is drawn");

            Level away = Level.empty("cull", 200, 200, TILE);
            away.setFormat(format);
            plant(away, "decor_bg", "oak_tree", 150, 150);
            assertEquals(0, ink(away).count(),
                    format + ": scenery off screen costs nothing");
        }
    }

    // --- helpers --------------------------------------------------------------------------

    /** A level of this format, floored solid so nothing can hide "behind" it. */
    private static Level floored(LevelFormat format) {
        Level lvl = Level.empty("decor", 30, 30, TILE);
        lvl.setFormat(format);
        int stone = lvl.blocks.get("stone").id();
        for (int r = 0; r < 30; r++) {
            for (int c = 0; c < 30; c++) lvl.setTile(c, r, stone);
        }
        return lvl;
    }

    /** A level of this format whose one block wears {@code key} on {@code face}. */
    private static Level decorated(LevelFormat format, String key, SurfaceDecor.Face face) {
        Level lvl = Level.empty("decorated", 30, 30, TILE);
        lvl.setFormat(format);
        lvl.setTile(15, 15, lvl.blocks.get("stone").id());
        lvl.surfaceDecor.add(new SurfaceDecor.Placement(15, 15, face, key, false,
                SurfaceDecor.Visibility.ALWAYS));
        return lvl;
    }

    /**
     * Whether a screen pixel lands on the projected tile at (col,row), give or
     * take a pixel of stroke width and rounding.
     */
    private static boolean onTile(Camera cam, int col, int row, int sx, int sy) {
        double[] xs = new double[4], ys = new double[4];
        double[][] corners = {{col, row}, {col + 1, row}, {col + 1, row + 1}, {col, row + 1}};
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            int[] pt = project(cam, corners[i][0] * TILE, corners[i][1] * TILE);
            xs[i] = pt[0];
            ys[i] = pt[1];
            cx += xs[i] / 4;
            cy += ys[i] / 4;
        }
        // Grow the quad a couple of pixels so an antialiased edge still counts.
        for (int i = 0; i < 4; i++) {
            double dx = xs[i] - cx, dy = ys[i] - cy, len = Math.hypot(dx, dy);
            if (len > 0) {
                xs[i] += dx / len * EDGE_SLACK;
                ys[i] += dy / len * EDGE_SLACK;
            }
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            // The projected quad is wound the same way in every perspective,
            // so every edge must keep the point on the same side.
            double cross = (xs[j] - xs[i]) * (sy - ys[i]) - (ys[j] - ys[i]) * (sx - xs[i]);
            if (cross < 0) return false;
        }
        return true;
    }

    /** Pixels the decorations of a level painted, as screen {x, y} pairs. */
    private static List<int[]> inkPixels(Level lvl) {
        BufferedImage dressed = paint(lvl, false);
        BufferedImage plain = paint(bare(lvl), false);
        List<int[]> pixels = new java.util.ArrayList<>();
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (dressed.getRGB(x, y) != plain.getRGB(x, y)) pixels.add(new int[]{x, y});
            }
        }
        return pixels;
    }

    /**
     * How much of a player-sized block of colour survives being drawn among a
     * level's scenery at {@code depth} — the thing the scenes do when they
     * hand the player and the trees to the same {@link DepthPass}. The marker
     * covers the same pixels either way, so only its depth decides.
     */
    private static int playerPixelsAmongScenery(Level lvl, Camera cam, int[] feet,
                                                int depth) {
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        DepthPass standing = DepthPass.of(lvl.perspective);
        scenery(g, lvl, cam, false, standing);
        standing.at(depth, () -> {
            g.setColor(PLAYER);
            g.fillRect(feet[0] - 14, feet[1] - 28, 28, 28);
        });
        standing.flush();
        g.dispose();
        return count(canvas, PLAYER, 0);
    }

    /** The same tiles with none of the decorations. */
    private static Level bare(Level lvl) {
        Level plain = Level.empty(lvl.name, lvl.width, lvl.height, lvl.tileSize);
        plain.perspective = lvl.perspective;
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) plain.setTile(c, r, lvl.tileAt(c, r));
        }
        return plain;
    }

    /** The same level holding only its {@code keep}-th decoration. */
    private static Level only(Level lvl, int keep) {
        Level one = Level.empty(lvl.name, lvl.width, lvl.height, lvl.tileSize);
        one.perspective = lvl.perspective;
        one.entities.add(lvl.entities.get(keep));
        return one;
    }

    /** Paint a decoration of {@code kind} standing on the tile at (col,row). */
    private static void plant(Level lvl, String kind, String key, double col, double row) {
        lvl.entities.add(new Level.EntitySpawn(kind, key,
                (col + 0.5) * TILE, (row + 1) * TILE));
    }

    /** A camera on the middle of the test levels, at the levels' own format. */
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

    /**
     * What a level's decorations actually put on screen: the level rendered
     * the way the scenes do — scenery on whichever side of the terrain its
     * format calls for — minus the same level rendered without them, so the
     * terrain underneath never counts toward the answer.
     */
    private static Ink ink(Level lvl) {
        return inkWith(lvl, PerspectiveSpace.of(lvl.perspective).scenerySitsBehindTerrain());
    }

    /** {@link #ink} with the layer order forced, to show what the other one does. */
    private static Ink inkWith(Level lvl, boolean sceneryBehind) {
        BufferedImage dressed = paint(lvl, sceneryBehind);
        BufferedImage plain = paint(bare(lvl), sceneryBehind);
        long n = 0, sx = 0, sy = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (dressed.getRGB(x, y) == plain.getRGB(x, y)) continue;
                n++;
                sx += x;
                sy += y;
            }
        }
        return new Ink((int) n, n == 0 ? 0 : sx / (double) n, n == 0 ? 0 : sy / (double) n);
    }

    /** How many pixels a level's decorations painted, and where their middle is. */
    private record Ink(int count, double x, double y) {}

    /** The scenes' render order: background scenery, terrain, foreground scenery. */
    private static BufferedImage paint(Level lvl, boolean sceneryBehind) {
        Camera cam = camera(lvl);
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        if (sceneryBehind) scenery(g, lvl, cam, false);
        fillTiles(g, lvl, cam);
        if (!sceneryBehind) scenery(g, lvl, cam, false);
        scenery(g, lvl, cam, true);
        g.dispose();
        return canvas;
    }

    private static void scenery(Graphics2D g, Level lvl, Camera cam, boolean foreground) {
        DepthPass pass = DepthPass.sorted();
        scenery(g, lvl, cam, foreground, pass);
        pass.flush();
    }

    private static void scenery(Graphics2D g, Level lvl, Camera cam, boolean foreground,
                                DepthPass into) {
        DecorPainter.draw(g, lvl, cam, foreground, 0.5, into);
        SurfaceDecorPainter.draw(g, lvl, cam, new int[]{0, 0, lvl.width - 1, lvl.height - 1},
                foreground, 0.5, into);
    }

    /** Opaque tiles, like the scenes' {@code drawTiles} without the trimmings. */
    private static void fillTiles(Graphics2D g, Level lvl, Camera cam) {
        int[] xs = new int[4], ys = new int[4], corner = new int[2];
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) {
                if (lvl.tileAt(c, r) <= 0) continue;
                double wx = c * (double) TILE, wy = r * (double) TILE;
                cam.worldToScreen(wx, wy, corner); xs[0] = corner[0]; ys[0] = corner[1];
                cam.worldToScreen(wx + TILE, wy, corner); xs[1] = corner[0]; ys[1] = corner[1];
                cam.worldToScreen(wx + TILE, wy + TILE, corner); xs[2] = corner[0]; ys[2] = corner[1];
                cam.worldToScreen(wx, wy + TILE, corner); xs[3] = corner[0]; ys[3] = corner[1];
                g.setColor(lvl.colorFor(lvl.tileAt(c, r)));
                g.fillPolygon(xs, ys, 4);
            }
        }
    }

    private static int differing(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }

    /** Pixels within {@code tolerance} of a colour, per channel. */
    private static int count(BufferedImage img, Color want, int tolerance) {
        int n = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                int rgb = img.getRGB(x, y);
                if ((rgb >>> 24) == 0) continue;
                if (Math.abs(((rgb >> 16) & 0xff) - want.getRed()) <= tolerance
                        && Math.abs(((rgb >> 8) & 0xff) - want.getGreen()) <= tolerance
                        && Math.abs((rgb & 0xff) - want.getBlue()) <= tolerance) n++;
            }
        }
        return n;
    }
}
