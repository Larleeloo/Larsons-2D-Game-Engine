package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera turns to eight compass points, and the projection turns with it —
 * all of it, in step.
 *
 * <p><b>Why the round trip is the assertion and not a rendered frame.</b> A yaw
 * enters the world through {@code Camera.planar}, and there are three ways out
 * of that class: the allocating {@code planar} the picking path uses, the
 * allocation-free {@code worldToScreen} the tile loop uses, and
 * {@code screenToWorld} that creative mode paints with. They are three separate
 * pieces of arithmetic saying the same thing, and the failure this step names is
 * exactly what happens when one of them is not turned with the others: the world
 * draws correctly and the mouse lands on the wrong block, or the wrong block
 * lights up under a cursor that is visibly over its neighbour. A golden frame
 * cannot see that — the picture is right. So the tests below check the three
 * paths against each other at every heading, rather than checking any one of
 * them against a picture.
 *
 * <p><b>And why every case runs at all eight headings rather than at one.</b>
 * The plan predicted the failure mode and it is worth restating, because it is
 * what makes a two-heading test worthless: a sign error in a rotation is right
 * at four of the eight headings and wrong at the other four. At 0° and 180° a
 * flipped sine changes nothing at all, because the sine is zero. Testing "a
 * rotation happens" at 90° and calling it done leaves a world that turns the
 * wrong way at 45°, 135°, 225° and 315° and correctly everywhere else — which
 * reads as the diagonals being broken rather than as the rotation being
 * mirrored.
 */
class CameraYawTest {

    private static final int W = 480, H = 360;
    private static final int TILE = 32;

    /**
     * The awkward zoom {@code CameraStabilityTest} argues for: at a whole number
     * of pixels per tile the lattice cannot go wrong, so a rigidity check at
     * {@code zoom = 1} proves nothing.
     */
    private static final double AWKWARD_ZOOM = 1.7;

    /** The eight headings, in the order a key press walks them. */
    private static double[] headings() {
        double[] yaws = new double[8];
        for (int i = 0; i < 8; i++) yaws[i] = i * Camera.EIGHTH_TURN;
        return yaws;
    }

    /**
     * The format §6.1 puts in scope. Side-scroll is tested separately, and does
     * not turn at all.
     *
     * <p>An array of one, kept as an array: this was two entries when the plan
     * views were two formats, and every claim below is a claim about a
     * <em>rotating projection</em> rather than about a particular one. It is
     * also the tilts that make several projections out of the one format, and
     * those are covered by {@code CameraTiltTest}.
     */
    private static final Perspective[] ROTATING = {Perspective.THREE_D};

    // --- the projection inverts ----------------------------------------------------

    /**
     * {@code inversePlanar(planar(p)) == p}, for a grid of world points at all
     * eight headings, in both rotating formats.
     *
     * <p>The step's own verification, and it is cheap because it needs no
     * rendering: a projection that does not invert is a projection that has lost
     * information, and everything downstream of it — picking, placement, mining,
     * the visible-bounds sweep — is then wrong in a way no amount of correct
     * drawing recovers.
     */
    @Test
    void theProjectionInvertsAtEveryHeading() {
        for (Perspective perspective : ROTATING) {
            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                for (double[] w : worldGrid()) {
                    double[] p = cam.planar(w[0], w[1]);
                    double[] back = cam.inversePlanar(p[0], p[1]);
                    assertTrue(Math.abs(back[0] - w[0]) < 1e-9
                                    && Math.abs(back[1] - w[1]) < 1e-9,
                            perspective + " at " + degrees(yaw) + "°: (" + w[0] + "," + w[1]
                                    + ") projected to (" + p[0] + "," + p[1]
                                    + ") and came back as (" + back[0] + "," + back[1] + ")");
                }
            }
        }
    }

    /**
     * The hot tile path and the picking path project the same point to the same
     * pixel, at every heading.
     *
     * <p>{@code worldToScreen(wx, wy, out)} inlines the projection to avoid two
     * allocations per corner; {@code worldToScreenX/Y} go through {@code planar}.
     * That duplication is deliberate and it is exactly the kind that rots: a yaw
     * added to one and not the other compiles, renders a rotated world, and puts
     * every click a tile out. So they are compared rather than trusted.
     */
    @Test
    void theTilePathAndThePickingPathAgreeAtEveryHeading() {
        for (Perspective perspective : ROTATING) {
            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                cam.centerOn(413.7, 288.3);
                int[] out = new int[2];
                for (double[] w : worldGrid()) {
                    cam.worldToScreen(w[0], w[1], out);
                    assertEquals(cam.worldToScreenX(w[0], w[1]), out[0],
                            perspective + " at " + degrees(yaw) + "°: the inlined tile path and "
                                    + "worldToScreenX disagree on x for (" + w[0] + "," + w[1] + ")");
                    assertEquals(cam.worldToScreenY(w[0], w[1]), out[1],
                            perspective + " at " + degrees(yaw) + "°: the inlined tile path and "
                                    + "worldToScreenY disagree on y for (" + w[0] + "," + w[1] + ")");
                }
            }
        }
    }

    /**
     * A screen pixel maps back to the world point it came from, at every
     * heading, to within the pixel the forward direction quantised it to.
     *
     * <p>The same statement {@code CameraStabilityTest} makes at heading zero,
     * asked of a turned camera. This is the mapping C9's editor places blocks
     * with; the plan says the round trip is what makes creative mode safe at a
     * non-zero yaw, so it is checked here rather than assumed there.
     */
    @Test
    void aScreenPixelMapsBackToItsWorldPointAtEveryHeading() {
        for (Perspective perspective : ROTATING) {
            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                int[] out = new int[2];
                for (int step = 0; step < 8; step++) {
                    cam.centerOn(400 + step * 0.13, 300 + step * 0.07);
                    for (double[] w : worldGrid()) {
                        cam.worldToScreen(w[0], w[1], out);
                        double[] back = cam.screenToWorld(out[0], out[1]);
                        // Two roundings forward, so two pixels' worth of world
                        // units back — doubled because a turned or tilted plane
                        // spends its rounding on both axes at once, as
                        // CameraStabilityTest also allows for. The tilt widens
                        // it in the same way and for the same reason: a
                        // foreshortened depth axis magnifies a screen pixel's
                        // worth of world when it is carried back.
                        double tolerance = 2.5 / cam.zoom * 2 / Math.sin(cam.pitch());
                        assertTrue(Math.abs(back[0] - w[0]) <= tolerance
                                        && Math.abs(back[1] - w[1]) <= tolerance,
                                perspective + " at " + degrees(yaw) + "°: (" + w[0] + "," + w[1]
                                        + ") projected to (" + out[0] + "," + out[1]
                                        + ") and came back as (" + back[0] + "," + back[1] + ")");
                    }
                }
            }
        }
    }

    /**
     * A click lands on the cell it is over, at every heading.
     *
     * <p>C9's precondition, and a stronger claim than the round trip above: it
     * is not enough for a screen pixel to map back to <em>near</em> the world
     * point it came from, because a creative-mode stroke resolves that point to
     * a whole cell and a half-pixel disagreement at a tile boundary paints the
     * neighbour. Every cell in a spread is projected to the pixel its centre
     * lands on, and that pixel has to resolve back to the cell it came from —
     * which is exactly what an editor does with a cursor, at whatever heading
     * the creator has turned to.
     */
    @Test
    void aClickLandsOnTheCellItIsOverAtEveryHeading() {
        for (Perspective perspective : ROTATING) {
            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                cam.centerOn(10.5 * TILE, 10.5 * TILE);
                int[] out = new int[2];

                for (int row = 6; row <= 14; row++) {
                    for (int col = 6; col <= 14; col++) {
                        cam.worldToScreen((col + 0.5) * TILE, (row + 0.5) * TILE, out);
                        double[] back = cam.screenToWorld(out[0], out[1]);
                        int gotCol = (int) Math.floor(back[0] / TILE);
                        int gotRow = (int) Math.floor(back[1] / TILE);
                        assertEquals(col + "," + row, gotCol + "," + gotRow,
                                perspective + " at " + degrees(yaw) + "°: the middle of "
                                        + "cell (" + col + "," + row + ") draws at pixel ("
                                        + out[0] + "," + out[1] + "), and a click there "
                                        + "paints cell (" + gotCol + "," + gotRow + ")");
                    }
                }
            }
        }
    }

    // --- it turns, and it turns the right way --------------------------------------

    /**
     * At heading <i>h</i>, the world direction <i>h</i> projects exactly the way
     * north projected at heading zero.
     *
     * <p>This is what "the camera faces <i>h</i>" means, stated so that it can
     * fail: the direction the camera is looking is the one that now points away
     * from the viewer, up the screen. It is the whole sign convention in one
     * assertion, and it is the assertion the plan's warning asks for — a
     * mirrored rotation passes at 0° and 180°, where the sine is zero, and fails
     * here at the other six.
     *
     * <p>It also states the direction of travel: turn the camera <em>right</em>
     * (yaw increasing, clockwise from north) and the world swings <em>left</em>.
     * Both halves are checked, because "it rotates" and "it rotates the way a
     * camera does" are different claims.
     */
    @Test
    void turningTheCameraToAHeadingPutsThatDirectionUpTheScreen() {
        for (Perspective perspective : ROTATING) {
            Camera north = camera(perspective);
            north.setYaw(0);
            double[] expected = north.planarDelta(0, -TILE);   // north, unturned

            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                // The compass direction `yaw`, clockwise from north, on a screen
                // whose +y is south.
                double dx = Math.sin(yaw) * TILE, dy = -Math.cos(yaw) * TILE;
                double[] actual = cam.planarDelta(dx, dy);

                assertTrue(Math.abs(actual[0] - expected[0]) < 1e-9
                                && Math.abs(actual[1] - expected[1]) < 1e-9,
                        perspective + ": at heading " + degrees(yaw) + "° the world direction "
                                + "(" + round(dx) + "," + round(dy) + ") — which is that heading — "
                                + "projected to (" + round(actual[0]) + "," + round(actual[1])
                                + ") but north at heading 0 projects to ("
                                + round(expected[0]) + "," + round(expected[1]) + "). "
                                + "The rotation is mirrored or off by a quarter turn; note that "
                                + "both mistakes are invisible at 0° and 180°");
            }
        }
    }

    /**
     * Every heading draws a different picture, and the eighth press comes home.
     *
     * <p>A rotation that quietly does nothing passes the round-trip test above
     * perfectly — the identity inverts beautifully. This is the negative control
     * for that: the eight headings must give eight distinct projections of the
     * same point, and the ninth press must land exactly back on the first.
     */
    @Test
    void theEightHeadingsAreDistinctAndAFullTurnComesBackExactly() {
        for (Perspective perspective : ROTATING) {
            Camera cam = camera(perspective);
            cam.centerOn(0, 0);
            TreeSet<String> seen = new TreeSet<>();
            int[] out = new int[2];

            for (double yaw : headings()) {
                cam.setYaw(yaw);
                cam.worldToScreen(5 * TILE, 3 * TILE, out);
                seen.add(out[0] + "," + out[1]);
            }
            assertEquals(8, seen.size(), perspective
                    + ": the eight headings put one world point at " + seen.size()
                    + " distinct screen positions, not 8 — some presses turn nothing");

            cam.setYaw(0);
            cam.worldToScreen(5 * TILE, 3 * TILE, out);
            int[] home = {out[0], out[1]};
            cam.setYaw(8 * Camera.EIGHTH_TURN);
            cam.worldToScreen(5 * TILE, 3 * TILE, out);
            assertArrayEquals(home, out, perspective
                    + ": a full turn does not land back on the heading it started from");
        }
    }

    /**
     * The four cardinal headings are exact axis swaps, not rotations by
     * 6.1e-17 radians.
     *
     * <p>{@code Math.cos(Math.PI / 2)} is not zero, and the camera rests at
     * these four headings for essentially the whole of play. Snapping the
     * cosine and sine keeps the turned projection <em>exactly</em> the unturned
     * one with its axes exchanged, which is what lets the world's pixel lattice
     * survive a turn unchanged.
     *
     * <p>Stated with the camera at the top of its travel, where the tilt
     * contributes a factor of exactly one and the expected values can be
     * written down. That is not a way of avoiding the tilt: it is the other
     * half of the same projection, it scales the depth axis by a number the
     * heading does not touch, and holding it fixed is what leaves the heading
     * as the only thing under test here. {@code CameraTiltTest} makes the
     * matching claim about the tilt.
     */
    @Test
    void theCardinalHeadingsAreExactAxisSwaps() {
        Camera cam = camera(Perspective.THREE_D);
        cam.setPitch(Camera.MAX_PITCH);
        double wx = 137.25, wy = -48.5;

        cam.setYaw(0);
        assertArrayEquals(new double[]{wx, wy}, cam.planar(wx, wy), 0.0,
                "heading 0 is not the identity");
        cam.setYaw(Math.PI / 2);
        assertArrayEquals(new double[]{wy, -wx}, cam.planar(wx, wy), 0.0,
                "a quarter turn east is not an exact axis swap");
        cam.setYaw(Math.PI);
        assertArrayEquals(new double[]{-wx, -wy}, cam.planar(wx, wy), 0.0,
                "a half turn is not an exact negation");
        cam.setYaw(3 * Math.PI / 2);
        assertArrayEquals(new double[]{-wy, wx}, cam.planar(wx, wy), 0.0,
                "three quarters west is not an exact axis swap");
    }

    // --- and the world still holds still while turned ------------------------------

    /**
     * D3's rigid sheet, at all eight headings: between any two camera positions,
     * every projected point moves by the same vector.
     *
     * <p>{@code Camera}'s own note predicted this would survive rotation — "the
     * split works because planar is linear, so it can be applied to the world
     * point and the camera point separately" — and a prediction in a comment is
     * worth what it is checked at. It is checked here. The pixel lattice
     * quantises the world in the world's own space, so turning that space turns
     * the lattice with it; had the rounding been done in screen space instead,
     * this test is where that would have shown up, as a shimmer that appears
     * only after the player rotates the camera.
     */
    @Test
    void theWorldMovesAsOneRigidSheetAtEveryHeading() {
        for (Perspective perspective : ROTATING) {
            for (double yaw : headings()) {
                Camera cam = camera(perspective);
                cam.setYaw(yaw);
                int[] out = new int[2];

                for (int step = 1; step <= 60; step++) {
                    cam.centerOn(400, 300);
                    List<int[]> before = project(cam, out);
                    cam.centerOn(400 + step * 0.037, 300 + step * 0.023);
                    List<int[]> after = project(cam, out);

                    int dx = after.get(0)[0] - before.get(0)[0];
                    int dy = after.get(0)[1] - before.get(0)[1];
                    for (int i = 1; i < before.size(); i++) {
                        assertEquals(dx, after.get(i)[0] - before.get(i)[0],
                                perspective + " at " + degrees(yaw) + "°: point " + i
                                        + " moved a different distance horizontally than point 0 — "
                                        + "the turned picture is not translating rigidly");
                        assertEquals(dy, after.get(i)[1] - before.get(i)[1],
                                perspective + " at " + degrees(yaw) + "°: point " + i
                                        + " moved a different distance vertically than point 0");
                    }
                }
            }
        }
    }

    // --- scope: the side view does not turn ----------------------------------------

    /**
     * A side-scroller ignores yaw completely, and says so.
     *
     * <p>§6.1's scope decision, made mechanical. There is no vertical axis on
     * screen to turn a side view around — the screen <em>is</em> the vertical
     * plane and +y is the direction gravity pulls — so a yaw there would tip the
     * world over rather than turn it. The heading is still stored, so a camera
     * carried from a plan-view level into a side-scroller and back does not
     * silently lose where it was looking.
     */
    @Test
    void aSideScrollerIsUnmovedByEveryHeading() {
        Camera cam = camera(Perspective.SIDE_SCROLL);
        cam.centerOn(413.7, 288.3);
        assertFalse(cam.rotates(), "a side-scroller claims to rotate");

        int[] out = new int[2];
        List<int[]> unturned = project(cam, out);

        for (double yaw : headings()) {
            cam.setYaw(yaw);
            assertEquals(yaw, cam.yaw(), 0.0,
                    "the heading was not stored on a side-scroller");
            List<int[]> turned = project(cam, out);
            for (int i = 0; i < unturned.size(); i++) {
                assertArrayEquals(unturned.get(i), turned.get(i),
                        "heading " + degrees(yaw) + "° moved point " + i + " in a side-scroller, "
                                + "where there is no vertical axis on screen to turn around");
            }
        }
    }

    /**
     * The heading the camera is at and the one it is turning towards are two
     * different numbers, and only the first reaches the picture.
     *
     * <p>C8 eases one onto the other, and this is the property that makes the
     * easing possible: a press aims the camera without moving it, and the
     * picture only changes when time passes. A {@code targetYaw} that projected
     * on its own would make a snap animation a teleport.
     */
    @Test
    void theHeadingBeingTurnedTowardsDoesNotItselfProject() {
        Camera cam = camera(Perspective.THREE_D);
        cam.centerOn(400, 300);
        int[] out = new int[2];
        List<int[]> before = project(cam, out);

        cam.turn(1);
        assertEquals(Camera.EIGHTH_TURN, cam.targetYaw(), 0.0, "the press aimed the camera");
        assertEquals(0.0, cam.yaw(), 0.0, "and the press alone moved it");

        List<int[]> after = project(cam, out);
        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals(before.get(i), after.get(i),
                    "point " + i + " moved on the press rather than on the animation");
        }
    }

    // --- helpers -------------------------------------------------------------------

    private static Camera camera(Perspective perspective) {
        Camera cam = new Camera(perspective, W, H);
        cam.tileSize = TILE;
        cam.zoom = AWKWARD_ZOOM;
        cam.centerOn(400, 300);
        return cam;
    }

    /**
     * World points on and off the tile grid, on both sides of the origin.
     *
     * <p>Off-grid and negative coordinates are the point: a rotation that is
     * accidentally written about the screen centre rather than the world origin
     * agrees with a correct one everywhere near the origin and diverges the
     * further out you go.
     */
    private static List<double[]> worldGrid() {
        List<double[]> points = new ArrayList<>();
        for (int i = -3; i <= 4; i++) {
            for (int j = -3; j <= 4; j++) {
                points.add(new double[]{i * TILE + 0.25 * i, j * TILE - 0.5 * j});
            }
        }
        return points;
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

    private static int degrees(double radians) {
        return (int) Math.round(Math.toDegrees(radians));
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
