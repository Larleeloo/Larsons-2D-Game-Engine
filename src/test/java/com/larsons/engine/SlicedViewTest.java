package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.TerrainCache;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bottom of the camera's travel: flat on the floor, where the world is cut
 * open along the line the camera looks down and the picture becomes a side
 * elevation of the slice the focus is standing in.
 *
 * <p>Three things stop working at that tilt if nothing is done about them, and
 * each is a test below: the floor's depth stops reaching the screen (so a
 * painter sorting on screen rows has nothing to sort by), the projection stops
 * being invertible (so a click means a line rather than a point), and the near
 * half of the level is drawn over the far half (so the thing the camera is
 * pointed at is behind everything between it and the edge of the map).
 */
class SlicedViewTest {

    private static final int W = 480, H = 320, TILE = 32;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static Camera flat(Level lvl, double focusRow) {
        Camera cam = new Camera(lvl.perspective, W, H);
        cam.tileSize = lvl.tileSize;
        cam.setPitch(Camera.MIN_PITCH);
        cam.centerOn(lvl.width * TILE / 2.0, focusRow * TILE);
        return cam;
    }

    /** A floored level with a two-block wall across {@code wallRow}. */
    private static Level walled(int wallRow) {
        Level lvl = Level.empty("slice", 14, 12, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        int dirt = lvl.blocks.get("dirt").id();
        int stone = lvl.blocks.get("stone").id();
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, dirt);
        }
        for (int c = 3; c < 7; c++) lvl.setTile(c, wallRow, Level.LAYER_UPPER, stone);
        return lvl;
    }

    private static BufferedImage paint(Level lvl, Camera cam) {
        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        DrawTarget target = new Java2DTarget(g, W, H);
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam,
                TerrainPainter.visibleBounds(cam, lvl, W, H), 0.0, pass, null);
        pass.flush();
        g.dispose();
        return canvas;
    }

    private static int inked(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) n++;
            }
        }
        return n;
    }

    // --- the projection ----------------------------------------------------------

    @Test
    void theFloorCollapsesOntoOneRow() {
        Camera cam = flat(walled(6), 6);
        double[] near = cam.planar(0, 0);
        double[] far = cam.planar(0, 100 * TILE);
        assertEquals(near[1], far[1], 0.0,
                "a hundred tiles of depth still land on the same screen row");
        assertEquals(TILE, cam.planarDelta(TILE, 0)[0], 1e-9,
                "the axis across the view is untouched — that is what is left to see with");
        assertTrue(cam.sliced());
    }

    @Test
    void heightIsDrawnAtFullLength() {
        Camera cam = flat(walled(6), 6);
        assertEquals(1.0, cam.liftScale(), 1e-9,
                "flat on the floor, a block's vertical edge is drawn at its own length — "
                        + "which is what makes the picture a side elevation");
    }

    /**
     * The inverse is a choice rather than an inverse here, and the choice is
     * the slice the camera is looking at.
     */
    @Test
    void aClickLandsOnTheSliceTheCameraIsLookingAt() {
        Level lvl = walled(6);
        for (int eighth = 0; eighth < 8; eighth++) {
            Camera cam = flat(lvl, 6.5);
            cam.setYaw(eighth * Camera.EIGHTH_TURN);
            double focusDepth = cam.viewDepth(cam.x, cam.y);
            for (int sx : new int[]{0, W / 3, W / 2, W - 1}) {
                for (int sy : new int[]{0, H / 2, H - 1}) {
                    double[] w = cam.screenToWorld(sx, sy);
                    assertEquals(focusDepth, cam.viewDepth(w[0], w[1]), 1e-6,
                            "at " + eighth + "/8, (" + sx + "," + sy + ") landed off the slice");
                }
            }
        }
    }

    /**
     * And the pseudo-inverse stays linear, which is what the callers that use
     * it on a <em>direction</em> depend on: at this tilt a raised block draws
     * over its own cell and no other, so the step between layers is zero.
     */
    @Test
    void aRaisedBlockDrawsOverItsOwnCell() {
        Camera cam = flat(walled(6), 6);
        double[] step = cam.inversePlanar(0, TILE * cam.liftScale());
        assertEquals(0, step[0], 1e-9);
        assertEquals(0, step[1], 1e-9);
    }

    // --- depth -------------------------------------------------------------------

    /**
     * Sorting survives the collapse. This is the claim that would have failed
     * silently: with a screen-row key every cell ties, and which block covers
     * which falls to whatever order the sweep happened to visit them in.
     */
    @Test
    void depthStillOrdersCellsWhenTheScreenRowCannot() {
        Level lvl = walled(6);
        for (int eighth = 0; eighth < 8; eighth++) {
            Camera cam = flat(lvl, 6);
            cam.setYaw(eighth * Camera.EIGHTH_TURN);

            // Two cells separated *along this heading's view axis* — the pair
            // whose order the painter has to get right, and the pair a screen
            // row can no longer tell apart. Which two cells those are turns
            // with the camera, so they are taken from it rather than written
            // down for heading zero and assumed to hold at the rest.
            double[] back = cam.inverseViewAxis(-7.0 * TILE);
            double nearX = 7.5 * TILE, nearY = 5.5 * TILE;
            double farX = nearX + back[0], farY = nearY + back[1];

            int[] out = new int[2];
            cam.worldToScreen(nearX, nearY, out);
            int rowA = out[1];
            cam.worldToScreen(farX, farY, out);
            assertEquals(rowA, out[1],
                    "the premise: at " + eighth + "/8 both cells share a screen row");

            int near = cam.depthOf(nearX, nearY);
            int far = cam.depthOf(farX, farY);
            assertTrue(near > far,
                    "at " + eighth + "/8 the nearer of two cells seven tiles apart along "
                            + "the view axis did not sort in front (" + near + " vs " + far + ")");
        }
    }

    @Test
    void depthGrowsTowardTheCamera() {
        Camera cam = flat(walled(6), 6);          // heading 0: looking north
        assertTrue(cam.viewDepth(0, 9 * TILE) > cam.viewDepth(0, 2 * TILE),
                "the camera looks north, so the southern cell is the nearer one");
    }

    // --- the cut -----------------------------------------------------------------

    @Test
    void nothingInFrontOfTheFocusIsDrawn() {
        Level lvl = walled(6);
        // The wall behind the focus is the picture; the same wall in front of
        // it is what the cut is for.
        BufferedImage behind = paint(lvl, flat(lvl, 9));
        BufferedImage inFront = paint(lvl, flat(lvl, 2));

        assertTrue(inked(behind) > inked(inFront) + 200,
                "a wall behind the focus draws and the same wall in front of it does not: "
                        + inked(behind) + " vs " + inked(inFront));
    }

    @Test
    void theCutExposesTheFaceItOpens() {
        // A wall two rows deep, cut through the middle. The half in front is
        // gone, and the half left has to show the face the cut opened — the
        // very face the painter normally suppresses, because its neighbour is
        // solid.
        Level lvl = walled(6);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 3; c < 7; c++) lvl.setTile(c, 7, Level.LAYER_UPPER, stone);

        Camera cam = flat(lvl, 6.5);   // the cut falls between rows 6 and 7
        assertTrue(inked(paint(lvl, cam)) > 500,
                "the cut showed no cross-section at all — a sliced world with its "
                        + "interior faces missing is a world with holes where the "
                        + "interesting part should be");
    }

    @Test
    void actorsAreCutByTheSamePlaneTheTerrainIs() {
        Level lvl = walled(6);
        Camera cam = flat(lvl, 6);
        double behind = 3 * TILE, inFront = 10 * TILE;

        assertFalse(TerrainPainter.cutOff(cam, TILE, 7 * TILE, behind),
                "a body behind the cut is drawn");
        assertTrue(TerrainPainter.cutOff(cam, TILE, 7 * TILE, inFront),
                "a body in front of it is not — otherwise it would be seen through a "
                        + "wall the picture no longer contains");
        assertFalse(TerrainPainter.cutOff(cam, TILE, cam.x, cam.y),
                "and the focus itself is never cut");
    }

    @Test
    void nothingIsCutAtAnyOtherTilt() {
        Level lvl = walled(6);
        for (double deg : new double[]{1, 20, 60, 90}) {
            Camera cam = flat(lvl, 6);
            cam.setPitch(Math.toRadians(deg));
            assertFalse(cam.sliced(), deg + "° is not the sliced view");
            assertEquals(Integer.MAX_VALUE, TerrainPainter.sliceCut(cam, TILE),
                    "nothing is cut at " + deg + "°");
            assertFalse(TerrainPainter.cutOff(cam, TILE, 7 * TILE, 11 * TILE));
        }
    }

    /**
     * The sweep reaches behind the focus, and stops. Both halves matter: the
     * corners of a flat camera's viewport all invert onto one line, so without
     * widening there is nothing behind to draw — and with unbounded widening a
     * 65536-deep level would be swept end to end for a picture that stopped
     * changing at the far wall of the room.
     */
    @Test
    void theSweepReachesBehindTheFocusAndStops() {
        Level lvl = Level.empty("deep", 8, 4096, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        Camera cam = flat(lvl, 2048);

        int[] bounds = TerrainPainter.visibleBounds(cam, lvl, W, H);
        int rows = bounds[3] - bounds[1] + 1;
        assertTrue(rows > 8, "the sweep must reach behind the focus, not just its own row");
        assertTrue(rows <= TerrainPainter.SLICE_TILES + 8,
                "and it must stop: " + rows + " rows swept in a 4096-deep level");
    }

    // --- the floor cache ---------------------------------------------------------

    @Test
    void theFloorCacheStandsAsideWhenThereIsNoFloorToBake() {
        Camera cam = flat(walled(6), 6);
        assertFalse(TerrainCache.faithfulIn(cam),
                "every tile is a zero-height line here, so each chunk would bake a "
                        + "transparent sliver and the blits would land in sweep order "
                        + "rather than depth order");
    }

    // --- and the side-scroller is untouched --------------------------------------

    @Test
    void aSideScrollerIsNeverSliced() {
        Camera side = new Camera(Perspective.SIDE_SCROLL, W, H);
        side.setPitch(Camera.MIN_PITCH);
        assertFalse(side.sliced(), "a side view has no floor to lie flat on");
        assertEquals(Integer.MAX_VALUE, TerrainPainter.sliceCut(side, TILE));
        assertEquals(0, side.liftScale(), 0.0, "and no height axis to draw along");
    }
}
