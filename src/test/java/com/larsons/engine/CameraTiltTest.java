package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 3D camera's second axis: the free vertical movement that goes with the
 * eight-point turn.
 *
 * <p>{@code CameraYawTest} is the companion, and the two are deliberately
 * separate tests of separate claims. A heading rests on one of eight points and
 * is snapped to be exact there; a tilt rests wherever the player let go of the
 * key. Everything below is about that difference and about the two numbers the
 * tilt puts into the projection — the ground's foreshortening and the height's
 * lift.
 */
class CameraTiltTest {

    private static final int W = 480, H = 360, TILE = 32;

    private static Camera camera() {
        Camera cam = new Camera(Perspective.THREE_D, W, H);
        cam.tileSize = TILE;
        cam.centerOn(0, 0);
        return cam;
    }

    // --- the tilt moves freely, and stays inside its travel ------------------------

    @Test
    void aFreshCameraStandsAtItsDefaultTilt() {
        assertEquals(Camera.DEFAULT_PITCH, camera().pitch(), 1e-12);
        assertTrue(Camera.DEFAULT_PITCH > Camera.MIN_PITCH
                        && Camera.DEFAULT_PITCH < Camera.MAX_PITCH,
                "a camera that starts at the end of its travel can only be moved one way");
    }

    /**
     * The whole point of the axis: unlike the heading, it rests where it is put.
     *
     * <p>A single frame's worth of the look key is a fraction of a degree, and
     * the camera has to keep it — a tilt that snapped to the nearest of anything
     * would be a second eight-point control, and there would be no reason to
     * have two.
     */
    @Test
    void theTiltRestsWhereverItIsLeft() {
        Camera cam = camera();
        double before = cam.pitch();
        cam.tilt(Camera.TILT_SPEED / 60.0);   // one frame of the key held
        assertNotEquals(before, cam.pitch(), "one frame of the key moved nothing");
        assertEquals(before + Camera.TILT_SPEED / 60.0, cam.pitch(), 1e-12,
                "the tilt is not snapped to anything");
    }

    @Test
    void theTiltStopsAtBothEndsOfItsTravel() {
        Camera up = camera();
        for (int i = 0; i < 600; i++) up.tilt(Camera.TILT_SPEED / 60.0);
        assertEquals(Camera.MAX_PITCH, up.pitch(), 1e-12, "straight down is as far as it goes");

        Camera down = camera();
        for (int i = 0; i < 600; i++) down.tilt(-Camera.TILT_SPEED / 60.0);
        assertEquals(Camera.MIN_PITCH, down.pitch(), 1e-12,
                "and a floor seen edge-on is as low as it goes");
        assertTrue(Math.sin(down.pitch()) > 0.2,
                "the lowest tilt must still leave a ground plane to invert");
    }

    @Test
    void aSideScrollerHasNoFloorToStandOver() {
        Camera side = new Camera(Perspective.SIDE_SCROLL, W, H);
        assertFalse(side.tilts());
        double before = side.pitch();
        side.tilt(Camera.TILT_SPEED);
        assertEquals(before, side.pitch(), 0.0, "a side view's camera does not tilt");
        assertEquals(0, side.liftScale(), 0.0, "and has no height axis to lift along");
        assertFalse(side.overhead(), "nor can it be overhead");
    }

    // --- what the tilt does to the picture -----------------------------------------

    /**
     * The depth axis foreshortens and the other one does not. That asymmetry is
     * the tilt: a camera raised over a plane sees the direction it is looking
     * along compressed by {@code sin(pitch)} and the direction across its view
     * unchanged.
     */
    @Test
    void raisingTheCameraOpensOutTheFloorAlongTheDepthAxisOnly() {
        double lastDepth = -1;
        for (double deg : new double[]{20, 40, 60, 75, 90}) {
            Camera cam = camera();
            cam.setPitch(Math.toRadians(deg));

            double across = cam.planarDelta(TILE, 0)[0];
            double depth = cam.planarDelta(0, TILE)[1];

            assertEquals(TILE, across, 1e-9,
                    "the axis across the view is not foreshortened, at " + deg + "°");
            assertEquals(TILE * Math.sin(Math.toRadians(deg)), depth, 1e-9,
                    "the depth axis carries the whole of the tilt, at " + deg + "°");
            assertTrue(depth > lastDepth, "raising the camera opens the floor out");
            lastDepth = depth;
        }
        assertEquals(TILE, lastDepth, 1e-9, "straight down, a tile is its own size again");
    }

    /**
     * And height goes the other way: the lower the camera, the taller a block
     * stands. Down to a floor rather than to nothing — see {@link Camera#MIN_LIFT}.
     */
    @Test
    void loweringTheCameraStandsBlocksUp() {
        Camera low = camera();
        low.setPitch(Camera.MIN_PITCH);
        Camera high = camera();
        high.setPitch(Camera.MAX_PITCH);

        assertTrue(low.liftScale() > high.liftScale(),
                "a block is taller from a camera brought down over the floor");
        assertEquals(Math.cos(Camera.MIN_PITCH), low.liftScale(), 1e-9,
                "a vertical edge is drawn at cos(pitch) of its length");
        assertEquals(Camera.MIN_LIFT, high.liftScale(), 1e-9,
                "straight down, a block keeps a floor of height rather than none — a wall "
                        + "drawn zero pixels tall is a wall that has left the level");
    }

    /**
     * The projection still inverts at every tilt, which is what picking,
     * placing and mining all rest on.
     */
    @Test
    void theProjectionInvertsAtEveryTilt() {
        for (double deg : new double[]{20, 33, 60, 75, 90}) {
            for (int eighth = 0; eighth < 8; eighth++) {
                Camera cam = camera();
                cam.setYaw(eighth * Camera.EIGHTH_TURN);
                cam.setPitch(Math.toRadians(deg));
                for (double[] w : new double[][]{{0, 0}, {97.5, -213.25}, {-1024, 768}}) {
                    double[] p = cam.planar(w[0], w[1]);
                    double[] back = cam.inversePlanar(p[0], p[1]);
                    assertEquals(w[0], back[0], 1e-6,
                            "x at " + deg + "°, " + eighth + "/8");
                    assertEquals(w[1], back[1], 1e-6,
                            "y at " + deg + "°, " + eighth + "/8");
                }
            }
        }
    }

    /** Turning and tilting are independent: neither disturbs the other. */
    @Test
    void theHeadingAndTheTiltDoNotDisturbEachOther() {
        Camera cam = camera();
        cam.setPitch(Math.toRadians(42));
        cam.turn(1);
        cam.stepYaw(Camera.SNAP_SECONDS);
        assertEquals(Math.toRadians(42), cam.pitch(), 1e-12, "a turn moved the tilt");
        assertEquals(1, cam.heading(), "and the turn still landed on a compass point");

        cam.tilt(Math.toRadians(10));
        assertEquals(Camera.EIGHTH_TURN, cam.yaw(), 1e-12, "a tilt moved the heading");
    }

    // --- the overhead threshold -----------------------------------------------------

    /**
     * 75° exactly, and the boundary is inclusive: the requirement is "75 degrees
     * or greater", and a threshold that took effect at 75.0001 would be a
     * different rule that happened to look the same in a screenshot.
     */
    @Test
    void seventyFiveDegreesIsOverhead() {
        Camera cam = camera();

        cam.setPitch(Math.toRadians(74.9));
        assertFalse(cam.overhead(), "just under the threshold is not overhead");

        cam.setPitch(Camera.OVERHEAD_PITCH);
        assertTrue(cam.overhead(), "75° itself is overhead — 'or greater' includes it");

        cam.setPitch(Math.toRadians(89));
        assertTrue(cam.overhead(), "and everything above it");

        assertEquals(75, Math.toDegrees(Camera.OVERHEAD_PITCH), 1e-9);
    }

    /**
     * Reached by holding the key rather than by being assigned, which is how a
     * player actually gets there — and the case a strict {@code >=} on a sum of
     * floating-point steps can miss.
     */
    @Test
    void theThresholdIsReachedByHoldingTheKey() {
        Camera cam = camera();
        cam.setPitch(Camera.MIN_PITCH);
        boolean everOverhead = false;
        for (int frame = 0; frame < 600 && !everOverhead; frame++) {
            cam.tilt(Camera.TILT_SPEED / 120.0);
            if (cam.pitch() >= Camera.OVERHEAD_PITCH) everOverhead = cam.overhead();
        }
        assertTrue(everOverhead,
                "holding the look key from the bottom of the travel never reported overhead");
    }

    // --- the board diamond is not a tiltable camera ---------------------------------

    @Test
    void aBoardDiamondIgnoresTheTilt() {
        Camera board = camera();
        board.useBoardDiamond(64, 32);
        double[] before = board.planar(TILE, TILE);

        board.setPitch(Camera.MIN_PITCH);

        assertFalse(board.tilts(), "a board is drawn from one angle for ever");
        assertFalse(board.overhead(), "and is never 'overhead' in the sprite sense");
        assertArrayEqualsExact(before, board.planar(TILE, TILE),
                "the tilt reached a board's projection");
        assertEquals(1.0, board.liftScale(), 1e-9,
                "a cube's vertical edge in a 64-wide diamond is half that width");
    }

    private static void assertArrayEqualsExact(double[] expected, double[] actual, String why) {
        assertEquals(expected[0], actual[0], 0.0, why);
        assertEquals(expected[1], actual[1], 0.0, why);
    }
}
