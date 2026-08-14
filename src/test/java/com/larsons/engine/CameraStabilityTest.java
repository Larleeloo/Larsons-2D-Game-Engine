package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shimmer: whether the world holds still relative to itself while the
 * camera moves.
 *
 * <p><b>Why this is a separate instrument from every parity test in the
 * project.</b> {@code GlParityTest} and {@code GlHiDpiParityTest} ask whether
 * two backends draw the same picture, and they answered yes — GL at 2× is
 * pixel-identical to Java2D upscaled, at every camera position they tried. That
 * exonerated both rasterisers and left the reported jitter unexplained, because
 * a comparison of two backends cannot see a defect they <em>share</em>. This
 * asks a different question of one backend: does frame <i>n+1</i> differ from
 * frame <i>n</i> by exactly the camera's movement, or by something else as well?
 *
 * <p><b>And why it is at a zoom nothing else uses.</b> Every test in this
 * project renders at {@code zoom = 1} or {@code 2}, where a 32-unit tile is 32
 * or 64 pixels wide — a whole number, so the old projection could not go wrong
 * and the bug is perfectly invisible. In the game the zoom is a {@code double}
 * the player drives with a held key ({@code camera.zoom + dt * 2}), so it is
 * almost never one of those. At {@code zoom = 1.7} a tile is 54.4 pixels wide,
 * and rounding each object's screen position against a sliding camera made
 * neighbouring tiles alternate between 54 and 55 pixels apart while the view
 * panned. That is the whole defect, and picking the zoom is what makes it
 * appear.
 *
 * <p><b>The floor was already fixed and the blocks were not, which is exactly
 * what was reported.</b> {@code TerrainCache} bakes floor chunks onto a
 * camera-free lattice, so cached ground is rigid; stacked blocks, mobs, dropped
 * items and decor are deliberately never cached — they join the depth pass — so
 * they kept the old arithmetic. A player looking at that sees steady ground with
 * shivering blocks on it. Hence the rendered check below draws stacked blocks
 * with the cache switched off.
 */
class CameraStabilityTest {

    private static final int TILE = 32;
    private static final int W = 480, H = 360;

    /**
     * A zoom that makes a tile a fractional number of pixels wide. See the class
     * note: at a whole number there is nothing to measure.
     */
    private static final double AWKWARD_ZOOM = 1.7;

    // --- the projection, on its own ------------------------------------------------

    /**
     * Two fixed points in the world stay the same number of pixels apart, no
     * matter where the camera is standing.
     *
     * <p>This is the property the eye is actually watching. An object's absolute
     * screen position must change as the camera moves — that is the camera
     * working. What must never change is the <em>distance between two of
     * them</em>, because a distance that breathes by a pixel is two blocks
     * sliding against each other, which is what a shimmer is.
     */
    @Test
    void theDistanceBetweenTwoThingsNeverChangesAsTheCameraMoves() {
        for (Perspective perspective : Perspective.values()) {
            Camera cam = camera(perspective);

            TreeSet<Integer> tileWidths = new TreeSet<>();
            TreeSet<Integer> longSpans = new TreeSet<>();
            TreeSet<Integer> verticals = new TreeSet<>();
            int[] a = new int[2], b = new int[2], c = new int[2];

            // A camera creeping by a fraction of a pixel per frame, which is
            // what following a walking player looks like.
            for (int step = 0; step < 500; step++) {
                cam.centerOn(400 + step * 0.031, 300 + step * 0.017);
                cam.worldToScreen(3 * TILE, 4 * TILE, a);
                cam.worldToScreen(4 * TILE, 4 * TILE, b);
                cam.worldToScreen(11 * TILE, 9 * TILE, c);
                tileWidths.add(b[0] - a[0]);
                longSpans.add(c[0] - a[0]);
                verticals.add(c[1] - a[1]);
            }

            assertEquals(1, tileWidths.size(), perspective
                    + ": one tile's on-screen width took values " + tileWidths
                    + " as the camera panned — neighbouring tiles are rounding "
                    + "independently, which is the shimmer");
            assertEquals(1, longSpans.size(), perspective
                    + ": the gap between two blocks eight tiles apart took values " + longSpans);
            assertEquals(1, verticals.size(), perspective
                    + ": the vertical gap between two blocks took values " + verticals);
        }
    }

    /**
     * The whole scene translates as one rigid sheet: between any two camera
     * positions every point on screen moves by the same vector.
     *
     * <p>The stronger statement of the test above, and the one that rules out a
     * defect that happens to keep <em>those</em> three points consistent. If
     * every projected point shares one delta then nothing in the picture can
     * move relative to anything else, whatever it is.
     */
    @Test
    void everythingOnScreenMovesByTheSameAmountWhenTheCameraDoes() {
        for (Perspective perspective : Perspective.values()) {
            Camera cam = camera(perspective);
            int[] out = new int[2];

            for (int step = 1; step <= 200; step++) {
                double fromX = 400, fromY = 300;
                double toX = 400 + step * 0.037, toY = 300 + step * 0.023;

                cam.centerOn(fromX, fromY);
                List<int[]> before = project(cam, out);
                cam.centerOn(toX, toY);
                List<int[]> after = project(cam, out);

                int dx = after.get(0)[0] - before.get(0)[0];
                int dy = after.get(0)[1] - before.get(0)[1];
                for (int i = 1; i < before.size(); i++) {
                    assertEquals(dx, after.get(i)[0] - before.get(i)[0], perspective
                            + ": point " + i + " moved a different distance horizontally than "
                            + "point 0 when the camera moved to " + toX + " — the picture is "
                            + "not translating rigidly");
                    assertEquals(dy, after.get(i)[1] - before.get(i)[1], perspective
                            + ": point " + i + " moved a different distance vertically");
                }
            }
        }
    }

    /**
     * Screen-to-world still inverts world-to-screen to within the pixel it is
     * quantised to.
     *
     * <p>Not a detail: this is the mapping creative mode paints with and the one
     * that decides which block a click mined. A forward projection that moved
     * without its inverse moving with it would put strokes in the wrong cell,
     * which is a worse bug than the one being fixed.
     */
    @Test
    void aScreenPixelStillMapsBackToTheWorldPointItCameFrom() {
        for (Perspective perspective : Perspective.values()) {
            Camera cam = camera(perspective);
            int[] out = new int[2];

            for (int step = 0; step < 60; step++) {
                cam.centerOn(400 + step * 0.13, 300 + step * 0.07);
                for (int tile = 0; tile < 12; tile++) {
                    double wx = tile * TILE + 7, wy = (tile % 5) * TILE + 11;
                    cam.worldToScreen(wx, wy, out);
                    double[] back = cam.screenToWorld(out[0], out[1]);
                    // One screen pixel is 1/zoom world units, and the forward
                    // direction rounds twice, so two pixels' worth is the honest
                    // bound. Isometric spends its rounding on both axes at once.
                    double tolerance = 2.5 / cam.zoom * (perspective == Perspective.ISOMETRIC ? 2 : 1);
                    assertTrue(Math.abs(back[0] - wx) <= tolerance
                                    && Math.abs(back[1] - wy) <= tolerance,
                            perspective + ": (" + wx + "," + wy + ") projected to ("
                                    + out[0] + "," + out[1] + ") and came back as ("
                                    + back[0] + "," + back[1] + ")");
                }
            }
        }
    }

    // --- and the same thing in pixels ----------------------------------------------

    /**
     * A wall of stacked blocks, drawn live, does not shimmer as the camera
     * creeps.
     *
     * <p>The pixel-level statement of all of the above, and deliberately the
     * same shape of assertion {@code TerrainCacheTest} makes about the baked
     * floor: each frame must equal the previous one shifted by the whole pixels
     * the camera moved. A rigid sheet matches exactly; anything rounding
     * independently does not.
     *
     * <p>Two things are set differently from that test on purpose. The cache is
     * <b>off</b>, because the cached path was already rigid and the live one is
     * where the bug lived. And the zoom is {@link #AWKWARD_ZOOM} rather than 1,
     * because at 1 the defect cannot occur — see the class note.
     */
    @Test
    void aWallOfBlocksDoesNotShimmerAsTheCameraCreeps() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, 40, 20);

        double startX = 500.0;
        BufferedImage prev = frameAt(lvl, startX);
        for (int step = 1; step <= 24; step++) {
            double camX = startX + step * 0.25;
            BufferedImage cur = frameAt(lvl, camX);

            // What the camera's own offset moved by between these two frames —
            // the only difference the picture is allowed to have.
            int dx = cameraOffset(camX) - cameraOffset(camX - 0.25);
            assertEquals(0, shiftedDiff(prev, cur, dx),
                    "the live sweep shifted non-rigidly at camX=" + camX
                            + " (expected a whole-sheet move of " + dx + "px) — blocks are "
                            + "rounding independently against a sliding camera again");
            prev = cur;
        }
    }

    // --- helpers -------------------------------------------------------------------

    private static Camera camera(Perspective perspective) {
        Camera cam = new Camera(perspective, W, H);
        cam.tileSize = TILE;
        cam.zoom = AWKWARD_ZOOM;
        return cam;
    }

    /** A spread of world points, projected through the camera as it stands. */
    private static List<int[]> project(Camera cam, int[] out) {
        List<int[]> points = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double wx = (i * 37 % 640) + 0.5 * (i % 3);
            double wy = (i * 53 % 480) + 0.25 * (i % 4);
            cam.worldToScreen(wx, wy, out);
            points.add(new int[]{out[0], out[1]});
        }
        return points;
    }

    /** The camera's single contribution to a horizontal screen position. */
    private static int cameraOffset(double camX) {
        return (int) Math.round(W / 2.0 - camX * AWKWARD_ZOOM);
    }

    /** A level with a floor and a layer of stacked blocks over it. */
    private static Level level(LevelFormat format, int w, int h) {
        Level lvl = Level.empty("shimmer", w, h, TILE);
        lvl.setFormat(format);
        List<Block> blocks = new ArrayList<>(lvl.blocks.all());
        Random rnd = new Random(11);
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                lvl.setTile(c, r, blocks.get(rnd.nextInt(blocks.size())).id());
                // Every third cell carries a stacked block, which is what the
                // depth pass draws live and what was reported as jittering.
                if ((c + r) % 3 == 0) lvl.setTile(c, r, Level.LAYER_UPPER, blocks.get(rnd.nextInt(blocks.size())).id());
            }
        }
        return lvl;
    }

    /** One live terrain pass — no cache — at a given camera x. */
    private static BufferedImage frameAt(Level lvl, double camX) {
        Camera cam = new Camera(lvl.perspective, W, H);
        cam.tileSize = TILE;
        cam.zoom = AWKWARD_ZOOM;
        cam.centerOn(camX, lvl.height * TILE / 2.0);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(24, 28, 38));
        g.fillRect(0, 0, W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(Java2DTarget.unsized(g), lvl, cam,
                new int[]{0, 0, lvl.width - 1, lvl.height - 1}, 0.0, pass, null, null, null);
        pass.flush();
        g.dispose();
        return img;
    }

    /**
     * Pixels where {@code b} disagrees with {@code a} shifted right by
     * {@code dx}, ignoring a margin — content scrolls in at the edges and that
     * is the camera working rather than the picture breaking.
     */
    private static int shiftedDiff(BufferedImage a, BufferedImage b, int dx) {
        int n = 0;
        for (int y = 20; y < H - 20; y++) {
            for (int x = 20; x < W - 20; x++) {
                int ax = x - dx;
                if (ax < 0 || ax >= W) continue;
                if (a.getRGB(ax, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }
}
