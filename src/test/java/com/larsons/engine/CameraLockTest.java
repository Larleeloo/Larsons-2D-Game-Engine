package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CameraLock;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.Viewpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a level lets its camera stand.
 *
 * <p>Three axes, each restricted on its own and each defaulting to
 * unrestricted: the eight headings as a set, the tilt as a range with an
 * optional step, and the F5 cycle as a set. The tests below are grouped that
 * way, and the last group is the one that matters most — that a lock is
 * obeyed by <em>every</em> way of placing the camera, not only by the keys a
 * player presses.
 */
class CameraLockTest {

    private static final int W = 480, H = 360;

    private static Camera camera() {
        Camera cam = new Camera(Perspective.THREE_D, W, H);
        cam.tileSize = 32;
        return cam;
    }

    // --- the default ----------------------------------------------------------------

    @Test
    void aFreshLockRestrictsNothing() {
        CameraLock lock = CameraLock.free();
        assertTrue(lock.isFree());
        for (int h = 0; h < CameraLock.HEADINGS; h++) assertTrue(lock.allowsHeading(h));
        for (Viewpoint v : Viewpoint.values()) assertTrue(lock.allows(v));
        assertEquals(0, lock.minPitchDegrees(), 1e-9);
        assertEquals(90, lock.maxPitchDegrees(), 1e-9);
        assertEquals("free", lock.describe());
    }

    @Test
    void aLevelWithNoCameraBlockIsUnlocked() {
        // Every level saved before locks existed, and every one saved since
        // that never opened the section.
        GameProfile p = GameProfile.fromJson("{\"name\":\"Old\"}");
        assertTrue(p.cameraLock.isFree());
    }

    @Test
    void anUnlockedCameraWritesNoCameraBlock() {
        // An optional field that is always written is a format change.
        assertFalse(new GameProfile("Sandbox").toJson().contains("\"camera\""));
    }

    // --- headings -------------------------------------------------------------------

    @Test
    void aForbiddenHeadingIsSteppedOver() {
        CameraLock lock = CameraLock.free();
        lock.setHeadingAllowed(1, false);   // no north-east
        assertEquals(2, lock.nextHeading(0, +1), "the turn goes on to east");
        assertEquals(0, lock.nextHeading(2, -1), "and back to north the other way");
    }

    @Test
    void theLastHeadingCannotBeTakenAway() {
        // A camera has to point somewhere, and a creator clicking down the list
        // should not have the last click do the opposite of what it says.
        CameraLock lock = CameraLock.free();
        for (int h = 0; h < CameraLock.HEADINGS; h++) lock.setHeadingAllowed(h, false);
        assertEquals(1, lock.headingCount());
        assertTrue(lock.headingLocked());
    }

    @Test
    void aFixedCameraDoesNotTurn() {
        CameraLock lock = CameraLock.free();
        for (int h = 1; h < CameraLock.HEADINGS; h++) lock.setHeadingAllowed(h, false);

        Camera cam = camera();
        cam.setLock(lock);
        cam.turn(1);
        cam.stepYaw(Camera.SNAP_SECONDS);
        assertEquals(0, cam.heading(), "a one-heading level has no turn to make");
        assertFalse(cam.turning(), "and no animation to run either");
    }

    @Test
    void turningSkipsToTheNextAllowedHeadingAndArrivesThere() {
        // The animation has to travel the distance actually covered: a turn
        // that eased one eighth while the heading moved two would settle
        // somewhere neither heading is.
        CameraLock lock = CameraLock.free();
        lock.setHeadingAllowed(1, false);
        lock.setHeadingAllowed(2, false);

        Camera cam = camera();
        cam.setLock(lock);
        cam.turn(1);
        assertEquals(3, cam.heading(), "north-east and east are out, so it goes to south-east");
        cam.stepYaw(Camera.SNAP_SECONDS);
        assertEquals(3 * Camera.EIGHTH_TURN, cam.yaw(), 1e-12,
                "and the animation lands exactly on it");
        assertFalse(cam.turning());
    }

    @Test
    void placingTheCameraSnapsToTheNearestAllowedHeading() {
        CameraLock lock = CameraLock.free();
        for (int h = 0; h < CameraLock.HEADINGS; h++) lock.setHeadingAllowed(h, h == 4);

        Camera cam = camera();
        cam.setLock(lock);
        cam.setYaw(0);   // a level authored looking north, since locked to south
        assertEquals(4, cam.heading());
        assertEquals(4 * Camera.EIGHTH_TURN, cam.yaw(), 1e-12);
    }

    // --- tilt -----------------------------------------------------------------------

    @Test
    void theTiltIsHeldInsideItsRange() {
        CameraLock lock = CameraLock.free();
        lock.setMinPitchDegrees(30);
        lock.setMaxPitchDegrees(60);

        Camera cam = camera();
        cam.setLock(lock);
        for (int i = 0; i < 600; i++) cam.tilt(Camera.TILT_SPEED / 60.0);
        assertEquals(Math.toRadians(60), cam.pitch(), 1e-9, "the ceiling holds");
        for (int i = 0; i < 600; i++) cam.tilt(-Camera.TILT_SPEED / 60.0);
        assertEquals(Math.toRadians(30), cam.pitch(), 1e-9,
                "and so does the floor — the detent pulls toward 0 and is clamped back");
        assertFalse(cam.sliced(), "a level with a tilt floor cannot be sliced open");
    }

    @Test
    void equalEndsPinTheTiltToOneAngle() {
        CameraLock lock = CameraLock.free();
        lock.setMinPitchDegrees(45);
        lock.setMaxPitchDegrees(45);
        assertTrue(lock.pitchLocked());

        Camera cam = camera();
        cam.setLock(lock);
        cam.tilt(Math.toRadians(20));
        assertEquals(Math.toRadians(45), cam.pitch(), 1e-9);
    }

    /** Setting the high end below the low one moves the other rather than crossing it. */
    @Test
    void theRangeCannotBeInsideOut() {
        CameraLock lock = CameraLock.free();
        lock.setMinPitchDegrees(70);
        lock.setMaxPitchDegrees(20);
        assertTrue(lock.minPitchDegrees() <= lock.maxPitchDegrees());
    }

    /**
     * A step is how a creator names <em>exact</em> angles on an axis that is
     * otherwise continuous.
     */
    @Test
    void aStepTurnsTheRangeIntoAListOfExactAngles() {
        CameraLock lock = CameraLock.free();
        lock.setPitchStepDegrees(15);

        Camera cam = camera();
        cam.setLock(lock);
        for (double want : new double[]{0, 7, 8, 22, 44, 46, 89, 90}) {
            cam.setPitch(Math.toRadians(want));
            double got = cam.pitchDegrees();
            assertEquals(0, Math.round(got) % 15,
                    "asked for " + want + "° and got " + got + "°, which is not a step");
        }
    }

    @Test
    void theStepGridStartsAtTheLowEndOfTheRange() {
        // Otherwise the lowest angle a creator allowed is one nobody can rest at.
        CameraLock lock = CameraLock.free();
        lock.setMinPitchDegrees(30);
        lock.setMaxPitchDegrees(90);
        lock.setPitchStepDegrees(20);

        assertEquals(Math.toRadians(30), lock.clampPitch(Math.toRadians(29)), 1e-9);
        assertEquals(Math.toRadians(50), lock.clampPitch(Math.toRadians(47)), 1e-9);
    }

    // --- viewpoints -----------------------------------------------------------------

    @Test
    void thePlanViewCannotBeTurnedOff() {
        CameraLock lock = CameraLock.free();
        lock.setAllowed(Viewpoint.PLAN, false);
        assertTrue(lock.allows(Viewpoint.PLAN),
                "the plan view is what every level is authored through and what the "
                        + "others fall back to");
    }

    @Test
    void aLevelCanSwitchTheSolidViewsOff() {
        CameraLock lock = CameraLock.free();
        for (Viewpoint v : Viewpoint.values()) lock.setAllowed(v, false);
        assertFalse(lock.allowsAnySolidView());

        // The F5 cycle then collapses to the plan view exactly as a
        // side-scroller's does.
        assertSame(Viewpoint.PLAN, Viewpoint.PLAN.next(true, lock));
    }

    @Test
    void theCycleSkipsForbiddenStops() {
        CameraLock lock = CameraLock.free();
        lock.setAllowed(Viewpoint.FIRST_PERSON, false);
        assertSame(Viewpoint.THIRD_PERSON_BACK, Viewpoint.PLAN.next(true, lock));
    }

    @Test
    void aNullLockAllowsEverything() {
        // Every scene with no level loaded, and every test written before locks.
        assertSame(Viewpoint.FIRST_PERSON, Viewpoint.PLAN.next(true, null));
    }

    // --- it survives a save ----------------------------------------------------------

    @Test
    void aLockSurvivesASaveAsNamesRatherThanNumbers() {
        GameProfile p = new GameProfile("Side-On Puzzle");
        p.cameraLock.setHeadingAllowed(1, false);
        p.cameraLock.setHeadingAllowed(3, false);
        p.cameraLock.setMinPitchDegrees(0);
        p.cameraLock.setMaxPitchDegrees(0);
        p.cameraLock.setAllowed(Viewpoint.FIRST_PERSON, false);

        String json = p.toJson();
        assertTrue(json.contains("\"camera\""), "a lock that restricts something is written");
        // As names a person can read, and only the allowed ones: "ne" and "se"
        // were ruled out, so they are not on the list.
        assertTrue(json.contains("\"sw\""), json);
        assertFalse(json.contains("\"ne\""), json);
        assertTrue(json.contains("\"third\""), json);
        assertFalse(json.contains("\"first\""), json);

        GameProfile back = GameProfile.fromJson(json);
        assertFalse(back.cameraLock.allowsHeading(1));
        assertFalse(back.cameraLock.allowsHeading(3));
        assertTrue(back.cameraLock.allowsHeading(0));
        assertEquals(0, back.cameraLock.maxPitchDegrees(), 1e-9);
        assertFalse(back.cameraLock.allows(Viewpoint.FIRST_PERSON));
        assertTrue(back.cameraLock.allows(Viewpoint.THIRD_PERSON_BACK));
    }

    @Test
    void aLockTravelsWithTheLevelsSettings() {
        GameProfile level = new GameProfile("Level");
        level.cameraLock.setMaxPitchDegrees(30);

        GameProfile copy = level.copy();
        assertEquals(30, copy.cameraLock.maxPitchDegrees(), 1e-9);
        assertNotEquals(level.cameraLock, copy.cameraLock,
                "a copy must not share the original's lock object");

        GameProfile playing = new GameProfile("Game Type");
        playing.applyFeaturesFrom(level);
        assertEquals(30, playing.cameraLock.maxPitchDegrees(), 1e-9,
                "loading a level's settings puts its camera rules in force");
    }

    @Test
    void aFileThatAllowsNoHeadingAtAllIsNotObeyed() {
        // Hand-edited or truncated: a camera still has to point somewhere.
        CameraLock lock = CameraLock.fromMap(
                java.util.Map.of("headings", java.util.List.of()));
        assertEquals(CameraLock.HEADINGS, lock.headingCount());
    }

    /**
     * "Unlock the camera" resets in place. The settings form's rows are bound
     * to the lock object, so a reset that handed the profile a fresh one would
     * leave every toggle editing a lock nothing reads.
     */
    @Test
    void unlockingResetsInPlaceRatherThanReplacingTheLock() {
        GameProfile p = new GameProfile("Locked");
        CameraLock held = p.cameraLock;
        held.setHeadingAllowed(2, false);
        held.setMaxPitchDegrees(10);
        held.setAllowed(Viewpoint.FIRST_PERSON, false);

        held.reset();

        assertSame(held, p.cameraLock, "the profile still holds the object the form edits");
        assertTrue(held.isFree());
        assertTrue(p.cameraLock.allowsHeading(2));
    }

    @Test
    void describeSaysWhatIsLockedInWordsAPlayerCanRead() {
        CameraLock lock = CameraLock.free();
        lock.setMinPitchDegrees(0);
        lock.setMaxPitchDegrees(0);
        for (int h = 1; h < CameraLock.HEADINGS; h++) lock.setHeadingAllowed(h, h == 4);
        String text = lock.describe();
        assertTrue(text.contains("N") && text.contains("S"), text);
        assertTrue(text.contains("0°"), text);
    }
}
