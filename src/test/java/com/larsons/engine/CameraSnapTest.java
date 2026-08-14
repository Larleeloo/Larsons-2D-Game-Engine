package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputBinding;
import com.larsons.engine.input.KeyBinds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snap between compass points: that it animates, that it always arrives,
 * and that it never rests anywhere else.
 *
 * <p><b>"Never rests between" is the assertion the rest of Job C is built
 * on.</b> A camera left a hair off a compass point is not a cosmetic problem:
 * at 44.99° {@code TerrainCache} refuses to bake the floor (C3), a tile texture
 * stops being an upright blit and becomes a warp (C4), and the cardinal
 * headings stop being exact axis swaps (C1). Every one of those is a real cost
 * paid for ever, by a camera that looks settled. So the tests below drive the
 * animation through randomised abuse and assert the resting position exactly,
 * not to a tolerance.
 */
class CameraSnapTest {

    private static final double FRAME = 1.0 / 60.0;

    /** Enough frames for any single snap to finish, with room to spare. */
    private static final int SETTLE_FRAMES = 60;

    // --- it arrives, and only ever on a compass point -----------------------------

    /**
     * After any sequence of presses, however abusive, the camera settles on an
     * exact multiple of 45° — and on the one the presses add up to.
     *
     * <p>C8's own verification. The sequence deliberately includes presses
     * during a snap, presses in both directions, and bursts faster than the
     * animation can absorb, because that is where a queue either drops
     * something it should have kept or keeps something it should have dropped.
     * The expected heading is tracked alongside by the same rule the camera
     * documents — <b>queue one, drop the rest</b> — so the test knows where the
     * camera should have ended up rather than merely that it ended up
     * somewhere legal.
     */
    @Test
    void anySequenceOfPressesSettlesOnACompassPoint() {
        Random rnd = new Random(20260814);
        for (int run = 0; run < 40; run++) {
            Camera cam = camera(Perspective.TOP_DOWN);
            int lastHeading = cam.heading();

            for (int press = 0; press < 30; press++) {
                cam.turn(rnd.nextBoolean() ? 1 : -1);

                // Let the animation run for a random slice of a snap — often
                // less than one, so the next press lands mid-turn.
                int frames = rnd.nextInt(20);
                for (int f = 0; f < frames; f++) {
                    cam.stepYaw(FRAME);
                    assertOnACompassPointOrTurning(cam);
                    // Never two points at once: "do not blend two turns" is
                    // what stops a queue from short-cutting across the compass
                    // when presses arrive faster than the animation.
                    int gap = Math.floorMod(cam.heading() - lastHeading, 8);
                    assertTrue(gap == 0 || gap == 1 || gap == 7, "run " + run
                            + " jumped from point " + lastHeading + " to " + cam.heading()
                            + " — two turns were blended into one");
                    lastHeading = cam.heading();
                }
            }

            // Everything in flight, and everything queued behind it, finishes.
            for (int f = 0; f < SETTLE_FRAMES * 2; f++) cam.stepYaw(FRAME);
            assertFalse(cam.turning(), "run " + run + " never stopped turning");
            assertRestsOnACompassPoint(cam, "run " + run);
        }
    }

    /**
     * A single press turns exactly one compass point, in the direction pressed,
     * from every one of the eight.
     *
     * <p>The plain case, asserted from every starting heading because a wrap at
     * north is where an implementation that eases toward a normalised target
     * goes the long way round — seven eighths of a circle backwards to arrive
     * at the same place.
     */
    @Test
    void onePressTurnsOnePointFromEveryHeading() {
        for (int from = 0; from < 8; from++) {
            for (int step : new int[]{1, -1}) {
                Camera cam = camera(Perspective.TOP_DOWN);
                cam.setYaw(from * Camera.EIGHTH_TURN);
                cam.turn(step);

                // How far the camera actually swept, which is not the same as
                // how far the number moved: a snap that completes across north
                // lands on the normalised heading, so `yaw` can step by a whole
                // turn at the instant it settles while the picture does not
                // move at all (see Camera.stepYaw). Folding each frame's step
                // into (-180°, 180°] measures the camera rather than the
                // representation of its angle.
                double travelled = 0;
                double previous = cam.yaw();
                for (int f = 0; f < SETTLE_FRAMES; f++) {
                    cam.stepYaw(FRAME);
                    travelled += Math.abs(shortestWay(cam.yaw() - previous));
                    previous = cam.yaw();
                }
                assertEquals(Camera.EIGHTH_TURN, travelled, 1e-9, "from " + from
                        + "/8 a press of " + step + " swept " + Math.toDegrees(travelled)
                        + "° rather than 45° — the turn is going the long way round");

                assertEquals(Math.floorMod(from + step, 8), cam.heading(),
                        "from " + from + "/8, a press of " + step);
                assertRestsOnACompassPoint(cam, "from " + from + "/8 pressing " + step);
            }
        }
    }

    /**
     * The camera moves over time rather than on the press, and takes about the
     * stated duration to get there.
     *
     * <p>The animation is the whole reason C8 exists — without it an eight-point
     * camera is a teleport and a player loses track of which way they were
     * facing — so "it animates" is worth asserting rather than assuming from
     * the presence of a duration constant.
     */
    @Test
    void aTurnTakesTimeRatherThanHappeningOnThePress() {
        Camera cam = camera(Perspective.TOP_DOWN);
        cam.turn(1);
        assertEquals(0.0, cam.yaw(), 0.0, "the press alone moved the camera");
        assertTrue(cam.turning(), "the press did not start a turn");

        cam.stepYaw(Camera.SNAP_SECONDS / 2);
        assertNotEquals(0.0, cam.yaw(), "half way through, the camera has not moved");
        assertTrue(cam.yaw() > 0 && cam.yaw() < Camera.EIGHTH_TURN,
                "half way through, the camera is outside the turn it is making");
        assertTrue(cam.turning(), "the turn finished in half its stated time");

        cam.stepYaw(Camera.SNAP_SECONDS / 2);
        assertFalse(cam.turning(), "the turn did not finish in its stated time");
        assertEquals(Camera.EIGHTH_TURN, cam.yaw(), 0.0);
    }

    /** And it eases: the middle of a turn moves faster than either end. */
    @Test
    void theTurnEasesInAndOutRatherThanSweepingFlat() {
        Camera cam = camera(Perspective.TOP_DOWN);
        cam.turn(1);

        List<Double> steps = new ArrayList<>();
        double previous = cam.yaw();
        int frames = (int) Math.round(Camera.SNAP_SECONDS / FRAME);
        for (int f = 0; f < frames; f++) {
            cam.stepYaw(FRAME);
            steps.add(cam.yaw() - previous);
            previous = cam.yaw();
        }
        assertTrue(steps.size() >= 6, "the snap is too short to say anything about");
        double first = steps.get(0), middle = steps.get(steps.size() / 2);
        double last = steps.get(steps.size() - 1);
        assertTrue(middle > first * 1.5, "the turn starts at full speed rather than "
                + "easing in (" + first + " then " + middle + " per frame)");
        assertTrue(middle > last * 1.5, "the turn stops dead rather than easing out ("
                + middle + " then " + last + " per frame)");
    }

    // --- the decision C8 asked to be recorded --------------------------------------

    /**
     * A press during a snap is queued; a second one is dropped.
     *
     * <p>The recorded decision, made mechanical. Three presses in the same
     * frame move the camera two points, not three: one turns now, one waits,
     * and the rest are thrown away. A held key therefore walks the world round
     * one point at a time and stops when the key does, instead of spinning for
     * seconds after it comes up.
     */
    @Test
    void oneTurnIsQueuedAndTheRestAreDropped() {
        Camera cam = camera(Perspective.TOP_DOWN);
        cam.turn(1);
        cam.turn(1);
        cam.turn(1);
        cam.turn(1);

        for (int f = 0; f < SETTLE_FRAMES * 2; f++) cam.stepYaw(FRAME);
        assertFalse(cam.turning());
        assertEquals(2, cam.heading(), "four presses in one frame turned the camera "
                + cam.heading() + " points; the rule is queue one, drop the rest");
        assertRestsOnACompassPoint(cam, "four presses at once");
    }

    /** The queued press may be the other way, and is honoured as pressed. */
    @Test
    void aQueuedTurnMayBeBackTheWayItCame() {
        Camera cam = camera(Perspective.TOP_DOWN);
        cam.turn(1);
        cam.stepYaw(Camera.SNAP_SECONDS / 3);
        cam.turn(-1);

        for (int f = 0; f < SETTLE_FRAMES * 2; f++) cam.stepYaw(FRAME);
        assertEquals(0, cam.heading(), "a press one way and then the other should come "
                + "back to where it started");
        assertRestsOnACompassPoint(cam, "there and back");
    }

    /**
     * The eight headings are eight, and pressing one way eight times comes
     * home.
     *
     * <p>The camera keeps its heading as a whole number rather than adding 45°
     * to a {@code double} for the length of a session, and this is what that is
     * for: after a full circle the heading is not merely close to where it
     * started, it is bit-for-bit the same camera.
     */
    @Test
    void afullCircleReturnsToTheExactHeadingItLeft() {
        Camera cam = camera(Perspective.TOP_DOWN);
        cam.centerOn(400, 300);
        int[] before = new int[2];
        cam.worldToScreen(5 * 32, 3 * 32, before);
        int[] atStart = {before[0], before[1]};

        Set<Integer> visited = new TreeSet<>();
        for (int turn = 0; turn < 8; turn++) {
            cam.turn(1);
            for (int f = 0; f < SETTLE_FRAMES; f++) cam.stepYaw(FRAME);
            visited.add(cam.heading());
            assertRestsOnACompassPoint(cam, "after " + (turn + 1) + " turns");
        }
        assertEquals(8, visited.size(), "a full circle visited " + visited.size()
                + " distinct headings, not 8");

        int[] after = new int[2];
        cam.worldToScreen(5 * 32, 3 * 32, after);
        assertEquals(0, cam.heading());
        assertEquals(0.0, cam.yaw(), 0.0, "a full circle did not come back to exactly 0");
        assertEquals(atStart[0], after[0], "a full circle moved the world horizontally");
        assertEquals(atStart[1], after[1], "a full circle moved the world vertically");
    }

    // --- scope, and the keys -------------------------------------------------------

    /** A side-scroller cannot be turned, however many times the key is pressed. */
    @Test
    void aSideScrollerCannotBeTurned() {
        Camera cam = camera(Perspective.SIDE_SCROLL);
        for (int i = 0; i < 20; i++) {
            cam.turn(i % 2 == 0 ? 1 : -1);
            cam.stepYaw(FRAME);
            assertFalse(cam.turning(), "a side-scroller started a turn");
            assertEquals(0.0, cam.yaw(), 0.0, "a side-scroller turned");
            assertEquals(0, cam.heading());
        }
    }

    /**
     * The two rotate keys are bound, rebindable, and share a key with nothing
     * the player can press at the same moment.
     *
     * <p>C8 asks for them to go through the key-bind system so they are
     * rebindable, and that half is easy. The other half is picking keys, and
     * <b>the plan's own suggestion was wrong</b>: it proposed {@code Q} and
     * {@code E}, and both ship bound — {@code Q} drops one of the held stack,
     * {@code E} interacts with doors, chests and mounts.
     *
     * <p><b>{@code KeyBinds.conflicts} would not have caught it, and finding
     * that out is worth more than the keys.</b> It reports collisions inside a
     * {@linkplain GameAction.Category category}, on the argument that two
     * categories overlapping is not a conflict — the left mouse button attacks
     * in play and paints in the editor, and never both at once. That argument
     * holds for play against editing, and for the world against an open menu.
     * It does <em>not</em> hold between {@code CAMERA} and {@code ITEMS}, which
     * are both live in the same frame of the same game: binding rotation to
     * {@code Q} would have turned the camera and dropped an item on one press,
     * and every conflict check in the project would have called it fine.
     *
     * <p>So this asks the question the categories cannot: no action the player
     * can reach without opening anything may share a key with either rotate
     * key. Menus and the editor are excluded deliberately — those really are
     * other contexts, which is why {@code Space} may both jump and confirm.
     */
    @Test
    void theRotateKeysShareNoKeyWithAnythingLiveAtTheSameMoment() {
        KeyBinds binds = KeyBinds.defaults();
        List<GameAction> rotate = List.of(GameAction.ROTATE_LEFT, GameAction.ROTATE_RIGHT);
        // Everything the player can press with the world in front of them and
        // nothing open over it.
        Set<GameAction.Category> live = Set.of(GameAction.Category.MOVEMENT,
                GameAction.Category.COMBAT, GameAction.Category.ITEMS,
                GameAction.Category.CAMERA);

        for (GameAction key : rotate) {
            assertFalse(binds.bindings(key).isEmpty(), key + " ships bound to nothing");
            for (GameAction other : GameAction.values()) {
                if (other == key || rotate.contains(other)) continue;
                if (!live.contains(other.category())) continue;
                for (InputBinding b : binds.bindings(other)) {
                    assertFalse(binds.bindings(key).contains(b), key + " and " + other
                            + " both answer to " + b + ". They are in different categories, "
                            + "so KeyBinds.conflicts calls it fine — and they are live in "
                            + "the same frame, so the player turns the camera and does "
                            + "the other thing at once");
                }
            }
        }
        assertNotEquals(binds.bindings(GameAction.ROTATE_LEFT),
                binds.bindings(GameAction.ROTATE_RIGHT),
                "both rotate keys are the same key");

        // Rebindable, like everything else in the controls menu.
        binds.set(GameAction.ROTATE_LEFT, 0, InputBinding.key(java.awt.event.KeyEvent.VK_K));
        assertTrue(binds.bindings(GameAction.ROTATE_LEFT)
                        .contains(InputBinding.key(java.awt.event.KeyEvent.VK_K)),
                "the rotate key cannot be rebound");
    }

    // --- helpers -------------------------------------------------------------------

    /** An angle difference folded into (-180°, 180°]. */
    private static double shortestWay(double radians) {
        double t = Math.IEEEremainder(radians, Math.PI * 2);
        return t <= -Math.PI ? t + Math.PI * 2 : t;
    }

    private static Camera camera(Perspective perspective) {
        Camera cam = new Camera(perspective, 480, 360);
        cam.tileSize = 32;
        return cam;
    }

    /** At rest, on an exact compass point, with the heading agreeing. */
    private static void assertRestsOnACompassPoint(Camera cam, String where) {
        assertFalse(cam.turning(), where + ": still turning");
        assertEquals(cam.heading() * Camera.EIGHTH_TURN, cam.yaw(), 0.0, where
                + ": the camera rests at " + Math.toDegrees(cam.yaw()) + "°, which is not "
                + "compass point " + cam.heading() + ". A camera a hair off a compass "
                + "point costs the floor cache, the upright tile blit and the exact axis "
                + "swap, for ever, while looking settled");
        assertEquals(cam.yaw(), cam.targetYaw(), 0.0, where
                + ": at rest the camera is not where it was aiming");
    }

    /** Either resting on a compass point, or genuinely mid-snap between two. */
    private static void assertOnACompassPointOrTurning(Camera cam) {
        if (cam.turning()) return;
        assertEquals(cam.heading() * Camera.EIGHTH_TURN, cam.yaw(), 0.0,
                "the camera stopped between compass points at "
                        + Math.toDegrees(cam.yaw()) + "°");
    }

}
