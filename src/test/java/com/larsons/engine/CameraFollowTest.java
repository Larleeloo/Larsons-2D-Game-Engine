package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.StepInterpolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "the camera follows the player" means once the player can be 512 blocks
 * up — {@code HEIGHT_PLAN.md} Job T, step T4.
 *
 * <p><b>The camera already followed the player, on the plane.</b> W5 built
 * {@link Camera#elevation()} for the other axis and it worked; what it did not
 * do was make the two impossible to set apart, and they were set apart in three
 * places. The play scene moved the plane every <em>frame</em> and the lift once
 * per <em>simulation step</em>; the creative play-test moved the plane and
 * never touched the lift at all; and a cutscene moved the plane and inherited
 * whatever lift the player had been standing at.
 *
 * <p>Every one of those is invisible while a column is eight blocks tall, which
 * is exactly why all three survived to be found by raising the ceiling. So this
 * tests the property rather than the three call sites: <b>following something
 * is one act, and framing something that is not a body is another.</b>
 */
@Timeout(60)
class CameraFollowTest {

    private static final int TILE = 32;
    private static final int VIEW = 480;

    /** Following a body takes its height with it; framing a mark does not. */
    @Test
    void followTakesTheHeightAndFrameOnLeavesItBehind() {
        for (Perspective p : List.of(Perspective.THREE_D)) {
            Camera cam = camera(p);

            cam.follow(100, 200, 0);
            assertEquals(0, cam.elevation(), 1e-9, p + ": on the floor there is no lift");

            cam.follow(100, 200, 10 * TILE);
            assertEquals(10 * TILE * cam.liftScale(), cam.elevation(), 1e-9,
                    p + ": ten blocks up is ten blocks of lift");
            assertEquals(100, cam.x, 1e-9, p + ": and the plane is where it was put");
            assertEquals(200, cam.y, 1e-9);

            cam.frameOn(100, 200);
            assertEquals(0, cam.elevation(), 1e-9,
                    p + ": a mark on the ground is not a body — the lift goes back to zero");
        }
    }

    /**
     * A side-scroller has no height axis to lift along: its screen <em>is</em>
     * the vertical plane. Following a body there must not move the picture.
     */
    @Test
    void aSideScrollerIsNotLiftedByFollowingAClimbingBody() {
        Camera cam = camera(Perspective.SIDE_SCROLL);
        cam.follow(100, 200, 400 * TILE);
        assertEquals(0, cam.liftScale(), 1e-9, "a side view has no lift scale");
        assertEquals(0, cam.elevation(), 1e-9, "so following cannot lift it");
    }

    /**
     * A player standing on a 400-block tower is drawn in the same place on
     * screen as one standing on the floor — which is the whole of what
     * "the camera follows the player" asks for, said as a measurement.
     */
    @Test
    void aPlayerOnATowerIsFramedExactlyWhereAPlayerOnTheFloorIs() {
        for (Perspective p : List.of(Perspective.THREE_D)) {
            Camera cam = camera(p);
            int[] onFloor = new int[2], onTower = new int[2];

            cam.follow(10.5 * TILE, 10.5 * TILE, 0);
            screenOf(cam, 10.5 * TILE, 10.5 * TILE, 0, onFloor);

            for (int layer : new int[]{1, 8, 100, 400, Level.MAX_LAYERS - 1}) {
                double z = layer * Level.BLOCK_HEIGHT * TILE;
                cam.follow(10.5 * TILE, 10.5 * TILE, z);
                screenOf(cam, 10.5 * TILE, 10.5 * TILE, z, onTower);
                assertEquals(onFloor[0], onTower[0], 1,
                        p + ": at layer " + layer + ", across the screen");
                assertEquals(onFloor[1], onTower[1], 1,
                        p + ": at layer " + layer + ", up the screen");
            }
        }
    }

    /**
     * The lift is sampled at the same instant as the plane.
     *
     * <p><b>This is the judder.</b> {@link StepInterpolation}'s class note
     * spends a page on why a camera sampled at an uneven cadence makes the
     * whole world shake, and the play scene applied it to {@code x} and
     * {@code y} only: the lift kept whatever the last completed simulation step
     * set. So a climbing player's <em>ground</em> moved smoothly under a
     * <em>height</em> that stepped at the beat between the render and
     * simulation rates. Frames drawn between two steps must interpolate both or
     * neither.
     */
    @Test
    void theLiftIsInterpolatedWithThePlaneAndNotLeftOnTheLastStep() {
        Camera cam = camera(Perspective.THREE_D);
        PlayerState body = new PlayerState(0, "climber", 10 * TILE, 10 * TILE);

        // One simulation step of a body climbing: it moves on the plane and up.
        body.beginStep();
        body.x += 4;
        body.y += 4;
        body.z = TILE;

        double lastLift = -1;
        for (double alpha = 0; alpha <= 1.0001; alpha += 0.125) {
            float a = (float) Math.min(1.0, alpha);
            cam.follow(body.renderX(a), body.renderY(a), body.renderZ(a));

            // The frame's lift is the frame's z, not the step's.
            assertEquals(body.renderZ(a) * cam.liftScale(), cam.elevation(), 1e-9,
                    "at alpha " + a + " the lift is the interpolated height");
            // And it advances every frame rather than sitting still and jumping.
            assertTrue(cam.elevation() > lastLift,
                    "at alpha " + a + " the lift moved with the frame");
            lastLift = cam.elevation();
        }

        // Sanity: the interpolation is doing something — a mid-frame is
        // genuinely between the two steps rather than snapped to either.
        assertNotEquals(0.0, body.renderZ(0.5f), 1e-9);
        assertNotEquals(body.z, body.renderZ(0.5f), 1e-9);
        assertEquals(StepInterpolation.at(0, TILE, 0.5), body.renderZ(0.5f), 1e-9);
    }

    /**
     * Following is rigid, not sprung — the height axis obeys the same rule the
     * plane already does. Two plan documents went to real lengths to make this
     * camera a rigid sheet on a pixel lattice, and a spring on one axis would
     * be a different feel and a different bug surface.
     */
    @Test
    void followingIsRigidSoTheWorldStillMovesAsOneSheet() {
        Camera cam = camera(Perspective.THREE_D);
        int[] near = new int[2], far = new int[2];

        // Two fixed points in the world, measured against each other as the
        // camera follows a body climbing a tower. Their pixel separation may
        // not change, at any height.
        int expectedDx = 0, expectedDy = 0;
        for (int layer = 0; layer < 64; layer++) {
            cam.follow(10.5 * TILE + layer * 0.37, 10.5 * TILE + layer * 0.11,
                    layer * Level.BLOCK_HEIGHT * TILE);
            cam.worldToScreen(4 * TILE, 4 * TILE, near);
            cam.worldToScreen(9 * TILE, 7 * TILE, far);
            if (layer == 0) {
                expectedDx = far[0] - near[0];
                expectedDy = far[1] - near[1];
            } else {
                assertEquals(expectedDx, far[0] - near[0],
                        "at layer " + layer + ": the world did not shear across");
                assertEquals(expectedDy, far[1] - near[1],
                        "at layer " + layer + ": the world did not shear up");
            }
        }
    }

    /** Where a body at (wx, wy, z) actually lands on screen. */
    private static void screenOf(Camera cam, double wx, double wy, double z, int[] out) {
        cam.worldToScreen(wx, wy, out);
        out[1] -= (int) Math.round(z * cam.liftScale() * cam.zoom);
    }

    private static Camera camera(Perspective p) {
        Camera cam = new Camera(p, VIEW, VIEW);
        cam.tileSize = TILE;
        return cam;
    }
}
