package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the camera follows a body <em>up</em>, and why it does not follow every
 * inch of it.
 *
 * <p>Two bugs live on this axis and they pull in opposite directions. Follow
 * the height rigidly and a jump moves the whole world instead of the character:
 * the player hangs motionless in the middle of the screen while the ground
 * drops away and comes back, once per hop and once per step of a staircase,
 * which reads as the ground moving. Follow it not at all and a player who
 * climbs a tower walks off the top of their own viewport. The answer is slack —
 * the camera ignores everything inside {@link Camera#FOLLOW_SLACK_BLOCKS} and
 * glides over the rest — and it is opt-in, because a cutscene's camera and a
 * test's camera are placed rather than followed.
 *
 * <p>The other half of the axis is the tilt. How far a block of height carries
 * something up the screen depends on where the camera is standing
 * ({@link Camera#liftScale()}), so a lift measured in <em>pixels</em> when a
 * body was followed is a lift measured against a camera angle that has since
 * moved: holding the look key while standing on a tower made the world jump,
 * further the higher the player stood. The lift is kept in world units and
 * converted at the moment it is used, which cannot go stale.
 */
class CameraHeightFollowTest {

    private static final int TILE = 32;

    private static Camera camera() {
        Camera cam = new Camera(Perspective.THREE_D, 640, 480);
        cam.tileSize = TILE;
        cam.setPitch(Camera.DEFAULT_PITCH);
        return cam;
    }

    /** A jump's whole arc fits inside the slack, so the ground does not move. */
    @Test
    void aHopDoesNotMoveTheGround() {
        Camera cam = camera();
        cam.setHeightFollow(Camera.HeightFollow.EASED);
        cam.follow(10 * TILE, 10 * TILE, 0);
        double resting = cam.elevation();

        // A hop reaches about 1.8 blocks at the engine's hop speed; walk it up
        // and back down a tick at a time, the way the physics does.
        double peak = 1.8 * TILE;
        for (int i = 0; i <= 20; i++) {
            double z = peak * Math.sin(Math.PI * i / 20.0);
            cam.follow(10 * TILE, 10 * TILE, z);
            cam.stepFollow(1 / 60.0);
            assertEquals(resting, cam.elevation(), 1e-9,
                    "the camera held still through the hop, at z = " + z);
        }
    }

    /** A climb past the slack is followed — smoothly, and all the way. */
    @Test
    void aClimbIsFollowedOnceItPassesTheSlack() {
        Camera cam = camera();
        cam.setHeightFollow(Camera.HeightFollow.EASED);
        cam.follow(10 * TILE, 10 * TILE, 0);

        // Eight blocks up, a block at a time — a body climbing a tower.
        double z = 0;
        for (int block = 1; block <= 8; block++) {
            z = block * (double) TILE;
            cam.follow(10 * TILE, 10 * TILE, z);
            for (int frame = 0; frame < 30; frame++) cam.stepFollow(1 / 60.0);
        }
        // The ease is asymptotic — it closes a fixed fraction of what is left
        // each frame — so it is given a second to settle before being measured.
        for (int frame = 0; frame < 120; frame++) cam.stepFollow(1 / 60.0);
        double slack = Camera.FOLLOW_SLACK_BLOCKS * TILE;
        assertEquals((z - slack) * cam.liftScale(), cam.elevation(), 1.0,
                "the camera settles a slack's worth below the climber, not on the floor");
        assertTrue(cam.restHeight() > 0, "and it did move");
    }

    /** A placement — a door, a respawn — is not eased into. */
    @Test
    void aTeleportPlacesTheCameraRatherThanSlidingToIt() {
        Camera cam = camera();
        cam.setHeightFollow(Camera.HeightFollow.EASED);
        cam.follow(10 * TILE, 10 * TILE, 0);
        cam.follow(10 * TILE, 10 * TILE, 40 * TILE); // through a door, onto a tower
        assertEquals(40 * TILE, cam.restHeight(), 1e-9,
                "a height no body could reach in one step is a placement");
    }

    /** Rigid following is what it always was, and is still the default. */
    @Test
    void rigidFollowingTracksEveryInch() {
        Camera cam = camera();
        assertEquals(Camera.HeightFollow.RIGID, cam.heightFollow());
        for (int i = 0; i <= 10; i++) {
            double z = i * 2.0;
            cam.follow(0, 0, z);
            assertEquals(z * cam.liftScale(), cam.elevation(), 1e-9);
        }
    }

    /**
     * The lift is re-read from the tilt, so raising the camera over a player
     * standing on a tower moves the picture by exactly the tilt's own change
     * and not by a lift measured at some earlier angle.
     */
    @Test
    void theLiftFollowsTheTiltInsteadOfBeingFrozenAtFollowTime() {
        Camera cam = camera();
        cam.follow(10 * TILE, 10 * TILE, 12 * TILE);
        double before = cam.elevation();
        assertEquals(12 * TILE * cam.liftScale(), before, 1e-9);

        // Hold the look key: the camera rises, and the elevation follows the
        // scale rather than staying where it was measured.
        for (int frame = 0; frame < 30; frame++) {
            cam.tilt(Camera.TILT_SPEED / 60.0);
            assertEquals(12 * TILE * cam.liftScale(), cam.elevation(), 1e-9,
                    "at pitch " + Math.round(cam.pitchDegrees()) + "°");
        }
        assertTrue(cam.elevation() < before,
                "a camera raised toward straight down flattens the height axis");
    }
}
