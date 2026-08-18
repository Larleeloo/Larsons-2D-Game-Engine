package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic movement step shared by single-player, client prediction,
 * and the server (requirement #3). Determinism is the property everything
 * else relies on, so it gets its own test.
 */
class PlayerPhysicsTest {

    private static final double DT = 1.0 / 60.0;

    /** 10x6 tiles, solid floor on the bottom row, 32px tiles. */
    private static Level floorLevel() {
        return LevelLoader.parse("""
                {
                  "tileSize": 32,
                  "tiles": [
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [0,0,0,0,0,0,0,0,0,0],
                    [1,1,1,1,1,1,1,1,1,1]
                  ],
                  "spawn": { "x": 64, "y": 96 }
                }
                """);
    }

    private static GameProfile profile() {
        GameProfile p = new GameProfile("physics-test");
        p.gravityEnabled = true;
        p.playerSize = 32;
        return p;
    }

    @Test
    void gravityPullsThePlayerOntoTheFloorAndStops() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 0);

        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 300; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        // Floor row starts at y=160; a 32px player rests at y=128.
        assertEquals(128, s.y, 0.001, "player should land exactly on the floor");
        assertEquals(0, s.vy, 0.001, "vertical velocity should be zero at rest");
    }

    @Test
    void jumpLaunchesUpAndLandsBack() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 128); // standing on the floor

        PlayerInput jump = new PlayerInput(false, false, false, false, 1);
        jump.jump = true; // Space, the only jump key
        PlayerPhysics.step(s, jump, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < 0, "jump should set upward velocity");
        assertTrue(s.y < 128, "player should leave the ground");

        PlayerInput idle = new PlayerInput();
        double peak = s.y;
        for (int i = 0; i < 300; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
            peak = Math.min(peak, s.y);
        }
        assertTrue(peak < 60, "jump should gain meaningful height, peaked at " + peak);
        assertEquals(128, s.y, 0.001, "player should land back on the floor");
    }

    @Test
    void horizontalMovementMatchesSpeedAndSetsFacing() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 128);

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        PlayerPhysics.step(s, right, level, p, Perspective.SIDE_SCROLL, DT);
        assertEquals(64 + PlayerPhysics.SPEED * DT, s.x, 1e-9);
        assertTrue(!s.facingLeft && s.moving);

        PlayerInput left = new PlayerInput(true, false, false, false, 2);
        PlayerPhysics.step(s, left, level, p, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.facingLeft);
    }

    @Test
    void topDownMovesFreelyOnBothAxesWithoutGravity() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        PlayerPhysics.step(s, right, level, p, Perspective.THREE_D, DT);
        assertEquals(64 + PlayerPhysics.SPEED * DT, s.x, 1e-9);
        assertEquals(64, s.y, 1e-9);

        PlayerInput up = new PlayerInput(false, false, true, false, 2);
        PlayerPhysics.step(s, up, level, p, Perspective.THREE_D, DT);
        assertEquals(64 - PlayerPhysics.SPEED * DT, s.y, 1e-9, "up walks on the plane");
        assertEquals(0, s.vy, 1e-9, "no gravity in top-down");
    }

    /**
     * A diagonal on the plane covers the same distance per second as an axis
     * does: without normalizing, moving up-right in a top-down or isometric
     * level travelled √2 times as fast as moving right.
     */
    @Test
    void topDownDiagonalsAreNotFasterThanTheAxes() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);

        PlayerInput upRight = new PlayerInput(false, true, true, false, 1);
        PlayerPhysics.step(s, upRight, level, p, Perspective.THREE_D, DT);

        double dx = s.x - 64, dy = s.y - 64;
        assertEquals(PlayerPhysics.SPEED * DT, Math.hypot(dx, dy), 1e-9,
                "diagonal step covers one step's distance");
        assertEquals(dx, -dy, 1e-9, "and splits it evenly between the axes");
    }

    /**
     * Sprinting is a plan-view act in every direction: holding shift while
     * walking "north" (which a side-scroller has no equivalent of) both speeds
     * the player up and spends stamina.
     */
    @Test
    void topDownSprintAppliesToVerticalMovement() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 64);
        double stamina = s.stamina;

        PlayerInput sprintUp = new PlayerInput(false, false, true, false, 1);
        sprintUp.sprint = true;
        PlayerPhysics.step(s, sprintUp, level, p, Perspective.THREE_D, DT);

        assertEquals(64 - PlayerPhysics.SPEED * PlayerPhysics.SPRINT_FACTOR * DT, s.y, 1e-9);
        assertTrue(s.stamina < stamina, "sprinting north spends stamina");
    }

    @Test
    void movementIsClampedToTheLevelBounds() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 0, 64);

        PlayerInput left = new PlayerInput(true, false, false, false, 1);
        for (int i = 0; i < 60; i++) {
            PlayerPhysics.step(s, left, level, p, Perspective.THREE_D, DT);
        }
        assertEquals(0, s.x, 1e-9, "left edge clamps");

        PlayerInput right = new PlayerInput(false, true, false, false, 2);
        for (int i = 0; i < 600; i++) {
            PlayerPhysics.step(s, right, level, p, Perspective.THREE_D, DT);
        }
        assertEquals(10 * 32 - 32, s.x, 1e-9, "right edge clamps to width - playerSize");
    }

    // --- C7: the keys are a screen intent, and the heading says what they mean ---

    /**
     * Pressing "up" walks away from the viewer at every heading, not toward
     * world north.
     *
     * <p>This is the whole of C7 stated as one property. "Up" is a screen
     * direction — the player means "away from me" — and which world direction
     * that is depends on where the camera was pointing. The expected answer is
     * taken from the camera rather than restated: the heading's own direction
     * is by construction the one that projects to straight up the screen (C1),
     * so it is what walking "up" has to produce.
     */
    @Test
    void upWalksAwayFromTheViewerAtEveryHeading() {
        Level level = floorLevel();
        GameProfile p = profile();

        for (int eighth = 0; eighth < 8; eighth++) {
            double yaw = eighth * Camera.EIGHTH_TURN;
            PlayerState s = new PlayerState(1, "t", 64, 64);
            PlayerInput up = new PlayerInput(false, false, true, false, 1);
            up.yaw = yaw;
            PlayerPhysics.step(s, up, level, p, Perspective.THREE_D, DT);

            // The compass direction the camera faces, which C1 defines as the
            // one pointing away from the viewer.
            double step = PlayerPhysics.SPEED * DT;
            double expectedX = 64 + Math.sin(yaw) * step;
            double expectedY = 64 - Math.cos(yaw) * step;

            assertEquals(expectedX, s.x, 1e-9, "at heading " + (eighth * 45)
                    + "° pressing up moved to x=" + s.x + " rather than away from the "
                    + "viewer, which is x=" + expectedX);
            assertEquals(expectedY, s.y, 1e-9, "at heading " + (eighth * 45)
                    + "° pressing up moved to y=" + s.y + " rather than y=" + expectedY);
        }
    }

    /**
     * A step is a step in every direction at every heading, and the four keys
     * still walk four different ways.
     *
     * <p>The negative control for the test above: an input rotation that
     * collapsed — always returning the same direction, or scaling with the
     * heading — would satisfy a single case. Distance is asserted because
     * rotating a vector must not change its length, which is exactly what a
     * sign error in one term of the rotation does.
     */
    @Test
    void everyKeyAtEveryHeadingIsOneStepAndFourDistinctDirections() {
        Level level = floorLevel();
        GameProfile p = profile();
        double step = PlayerPhysics.SPEED * DT;

        for (int eighth = 0; eighth < 8; eighth++) {
            java.util.Set<String> headings = new java.util.HashSet<>();
            for (int key = 0; key < 4; key++) {
                PlayerState s = new PlayerState(1, "t", 96, 96);
                PlayerInput in = new PlayerInput(key == 0, key == 1, key == 2, key == 3, 1);
                in.yaw = eighth * Camera.EIGHTH_TURN;
                PlayerPhysics.step(s, in, level, p, Perspective.THREE_D, DT);

                double moved = Math.hypot(s.x - 96, s.y - 96);
                assertEquals(step, moved, 1e-9, "at heading " + (eighth * 45)
                        + "° key " + key + " covered " + moved + " rather than one step — "
                        + "turning a direction must not change how far it goes");
                headings.add(Math.round((s.x - 96) * 1e6) + "," + Math.round((s.y - 96) * 1e6));
            }
            assertEquals(4, headings.size(), "at heading " + (eighth * 45)
                    + "° the four movement keys walked " + headings.size()
                    + " distinct ways, not 4");
        }
    }

    /** A diagonal is still one step's distance once the view has turned. */
    @Test
    void aTurnedDiagonalIsStillOneStep() {
        Level level = floorLevel();
        GameProfile p = profile();

        for (int eighth = 0; eighth < 8; eighth++) {
            PlayerState s = new PlayerState(1, "t", 96, 96);
            PlayerInput upRight = new PlayerInput(false, true, true, false, 1);
            upRight.yaw = eighth * Camera.EIGHTH_TURN;
            PlayerPhysics.step(s, upRight, level, p, Perspective.THREE_D, DT);

            assertEquals(PlayerPhysics.SPEED * DT, Math.hypot(s.x - 96, s.y - 96), 1e-9,
                    "at heading " + (eighth * 45) + "° a diagonal is not one step");
        }
    }

    /**
     * A side-scroller ignores the heading on its inputs entirely.
     *
     * <p>§6.1's scope rule, arriving at the simulation. The keys mean different
     * things edge-on — left and right walk, up and down swim and climb — so
     * they are read as the separate booleans they are there, and a heading that
     * somehow reached the input cannot bend a side-scroller's movement.
     */
    @Test
    void aSideScrollerIgnoresTheHeadingOnItsInput() {
        Level level = floorLevel();
        GameProfile p = profile();

        PlayerState plain = new PlayerState(1, "a", 64, 0);
        PlayerState turned = new PlayerState(2, "b", 64, 0);
        for (int i = 0; i < 120; i++) {
            PlayerInput a = new PlayerInput(i % 40 < 20, i % 40 >= 20, i % 30 == 0, false, i);
            PlayerInput b = new PlayerInput(a.left, a.right, a.up, a.down, i);
            b.yaw = (i % 8) * Camera.EIGHTH_TURN;
            PlayerPhysics.step(plain, a, level, p, Perspective.SIDE_SCROLL, DT);
            PlayerPhysics.step(turned, b, level, p, Perspective.SIDE_SCROLL, DT);
            assertEquals(plain.x, turned.x, 0.0, "x diverged at step " + i);
            assertEquals(plain.y, turned.y, 0.0, "y diverged at step " + i);
        }
    }

    /**
     * The heading survives the wire, so a predicting client and the server step
     * the same input.
     *
     * <p>C7 calls this a determinism boundary, and the boundary is the
     * serialisation: prediction runs the input object it just built, and the
     * server runs one rebuilt from a map of primitives. A field that is applied
     * before the wire and dropped on it produces two simulations that disagree
     * by the whole rotation — which the player sees as rubber-banding every
     * time they turn the camera, and only while they are turning it, and only
     * online.
     */
    @Test
    void theHeadingSurvivesTheWireSoBothSidesStepTheSameInput() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState predicted = new PlayerState(1, "client", 96, 96);
        PlayerState authoritative = new PlayerState(1, "server", 96, 96);

        for (int i = 0; i < 240; i++) {
            PlayerInput local = new PlayerInput(i % 50 < 25, i % 50 >= 25,
                    i % 30 < 15, i % 30 >= 15, i);
            // A camera being turned while the player runs: the heading changes
            // under them, tick by tick, which is the case that rubber-bands.
            local.yaw = i * 0.01;

            PlayerInput overTheWire = PlayerInput.fromMap(local.toMap());
            assertEquals(local.yaw, overTheWire.yaw, 0.0,
                    "the heading did not survive the wire at tick " + i);

            PlayerPhysics.step(predicted, local, level, p, Perspective.THREE_D, DT);
            PlayerPhysics.step(authoritative, overTheWire, level, p, Perspective.THREE_D, DT);
            assertEquals(predicted.x, authoritative.x, 0.0,
                    "prediction and authority disagree on x at tick " + i);
            assertEquals(predicted.y, authoritative.y, 0.0,
                    "prediction and authority disagree on y at tick " + i);
        }
        assertTrue(Math.hypot(predicted.x - 96, predicted.y - 96) > 1,
                "the player never actually moved, so this compared two players "
                        + "standing still");
    }

    /**
     * An input from a build that had never heard of headings still means what
     * it said.
     *
     * <p>The heading is absent on the wire when it is zero, which is every
     * side-scroller and every plan view at rest, so the common message is the
     * byte-for-byte one it always was and an older client's is read as heading
     * zero rather than as garbage.
     */
    @Test
    void anInputFromBeforeHeadingsReadsAsUnturned() {
        java.util.Map<String, Object> old = new java.util.LinkedHashMap<>();
        old.put("s", 7);
        old.put("l", false);
        old.put("r", true);
        old.put("u", false);
        old.put("d", false);

        PlayerInput in = PlayerInput.fromMap(old);
        assertEquals(0.0, in.yaw, 0.0, "a message with no heading is not unturned");
        assertEquals(1.0, in.moveX(), 1e-9, "and right still means east");
        assertEquals(0.0, in.moveY(), 1e-9);

        PlayerInput unturned = new PlayerInput(false, true, false, false, 7);
        assertFalse(unturned.toMap().containsKey("y"),
                "an unturned input puts a heading on the wire for every tick of every "
                        + "side-scroller ever played");
    }

    /**
     * Every scene stamps the heading onto the input it builds.
     *
     * <p><b>The fourth time this job has needed saying.</b> Everything above
     * exercises the rotation by setting {@code yaw} itself, so all of it passes
     * on a build where no scene ever sets it and the whole feature is inert.
     * The heading has exactly one source — the camera the player is looking
     * through — and one place to be attached, which is the input command being
     * built from that frame's keys. So the scan asks for it there.
     *
     * <p>It is anchored on <em>every</em> input built from the movement keys,
     * not on the first one found. The first draft looked for one per scene and
     * passed on a build where the play-test input was unstamped, because
     * {@code CreativeScene}'s first use of the movement binds is the editor's
     * camera pan several thousand lines earlier — which turned out to want the
     * same rotation for the same reason, and is why the scan is written this
     * way rather than tightened until it went quiet.
     *
     * <p><b>Two spellings are accepted, because there are now two cameras.</b>
     * A scene that can be played in a first-person view has a heading in the
     * flat {@code Camera} and another in the {@code EyeCamera}, and only one of
     * them is the one the player is looking along; {@code PlayScene.viewYaw()}
     * is the accessor that picks. The property being scanned for is unchanged —
     * the heading of the camera being looked through, attached to the input
     * built from that frame's keys — and naming the accessor as well as the
     * bare call is what keeps the scan on that property rather than on the
     * spelling it had when there was only one camera to ask.
     */
    @Test
    void everySceneStampsTheHeadingOnTheInputItSteps() throws IOException {
        int checked = 0;
        for (String scene : List.of("PlayScene", "CreativeScene")) {
            String source = Files.readString(
                    Path.of("src/main/java/com/larsons/engine/demo", scene + ".java"));
            for (int at = source.indexOf("new PlayerInput("); at >= 0;
                 at = source.indexOf("new PlayerInput(", at + 1)) {
                String after = source.substring(at,
                        Math.min(source.length(), at + 1200));
                // Only the ones built from the movement binds: a scene also
                // sends an empty input when it disconnects, and a heading on a
                // tick with no movement in it is neither required nor wrong.
                if (!after.contains("GameAction.MOVE_LEFT")) continue;
                checked++;
                assertTrue(after.contains("yaw = camera.viewYaw()")
                                || after.contains("yaw = viewYaw()"), scene + " builds an "
                        + "input from the movement keys without stamping the heading on "
                        + "it. Every test in this class sets the heading itself and would "
                        + "still pass; what would not work is the game, where pressing up "
                        + "walks north however the camera is turned");
            }
        }
        assertTrue(checked >= 3, "the scan found only " + checked + " movement inputs "
                + "across the two scenes, which is too few to be looking at the right "
                + "thing");
    }

    @Test
    void identicalInputsProduceIdenticalTrajectories() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState a = new PlayerState(1, "a", 64, 0);
        PlayerState b = new PlayerState(2, "b", 64, 0);

        for (int i = 0; i < 240; i++) {
            PlayerInput in = new PlayerInput(i % 60 < 30, i % 60 >= 30, i % 90 == 0, false, i);
            PlayerPhysics.step(a, in, level, p, Perspective.SIDE_SCROLL, DT);
            PlayerPhysics.step(b, in, level, p, Perspective.SIDE_SCROLL, DT);
            assertEquals(a.x, b.x, 0.0, "x must be bit-identical at step " + i);
            assertEquals(a.y, b.y, 0.0, "y must be bit-identical at step " + i);
            assertEquals(a.vy, b.vy, 0.0, "vy must be bit-identical at step " + i);
        }
    }

    // --- what collides, and it is not the same shape in every format ------------

    /**
     * A plan-view character can walk right up to a raised block from every
     * side, and stops the same distance out on all four.
     *
     * <p>On a plane the body box is a patch of <em>floor</em> and the sprite is
     * a billboard standing on it: feet on the box's southern edge, head a whole
     * body north of them. Sweeping the whole box therefore stopped a character
     * when their <em>head</em> reached a wall, leaving their feet a full
     * body-length short of it — an invisible barrier most of a tile deep along
     * the south face of every stacked block, and nothing at all along the north
     * face, where the same rule let the feet land exactly on the wall. It is
     * the asymmetry that gives it away: a hitbox that is right can be argued
     * with, one that answers differently depending on which way you walked into
     * the same block cannot be.
     */
    @Test
    void feetWalkUpToARaisedBlockFromEverySide() {
        for (LevelFormat format : List.of(LevelFormat.THREE_D)) {
            Level lvl = walledPlane(format);
            GameProfile p = profile();
            Perspective view = format.perspective();
            double size = p.playerSize;
            // The wall at (5,5) occupies world [160,192] on both axes.
            double lane = 5 * 32;

            double fromSouth = feet(walkInto(lvl, p, view, lane, 8 * 32, 0, -1), size)[1] - 192;
            double fromNorth = 160 - feet(walkInto(lvl, p, view, lane, 2 * 32, 0, 1), size)[1];
            double fromWest = 160 - feet(walkInto(lvl, p, view, 2 * 32, lane, 1, 0), size)[0];
            double fromEast = feet(walkInto(lvl, p, view, 8 * 32, lane, -1, 0), size)[0] - 192;

            // Half a footprint out, whichever way you came — the footprint
            // straddles the feet, so each approach spends half of it.
            double expected = PlayerPhysics.footSize(size) / 2;
            for (double gap : List.of(fromSouth, fromNorth, fromWest, fromEast)) {
                assertEquals(expected, gap, 0.5, format + ": stopped " + gap
                        + "px from the wall, expected half a footprint");
            }
            assertTrue(fromSouth < size / 3, format
                    + ": walking north stopped " + fromSouth + "px short of the block — "
                    + "that is the head colliding, not the feet");
        }
    }

    /**
     * Edge-on the box really is the character, and all of it still collides.
     *
     * <p>This is the other half of the rule and the easier one to break while
     * fixing the first: a side-scroller's floor is under the body box, so a
     * footprint hovering around the feet would have the player land somewhere
     * other than flush on it — and gravity would then keep pulling.
     */
    @Test
    void aSideScrollerStillLandsItsWholeBodyOnTheFloor() {
        Level level = floorLevel();
        GameProfile p = profile();
        PlayerState s = new PlayerState(1, "t", 64, 0);

        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 300; i++) {
            PlayerPhysics.step(s, idle, level, p, Perspective.SIDE_SCROLL, DT);
        }
        assertEquals(128, s.y, 0.001, "the body box rests on the floor row, not the feet");
    }

    /**
     * "Am I standing there?" is asked of the collision shape, which is what
     * lets a plan-view player build on the cell in front of them.
     *
     * <p>Block placement refuses a cell the player occupies, so that nobody can
     * brick themselves in. Measured on the body box, a character standing
     * against a wall occupies the wall's own cell — their head is inside it —
     * and the most ordinary placement in a plan view, laying a block on the far
     * side of the one you are facing, would be refused for a player who had
     * done nothing but walk up to it.
     */
    @Test
    void standingInAsksTheCollisionShapeAndNotTheBodyBox() {
        Level lvl = walledPlane(LevelFormat.THREE_D);
        GameProfile p = profile();
        double size = p.playerSize;
        PlayerState s = walkInto(lvl, p, Perspective.THREE_D, 5 * 32, 8 * 32, 0, -1);
        PerspectiveSpace plane = PerspectiveSpace.of(Perspective.THREE_D);

        assertTrue(s.y + size > 5 * 32 && s.y < 6 * 32,
                "the sprite's head does reach into the wall's cell");
        assertFalse(PlayerPhysics.standingIn(lvl, s.x, s.y, size, plane, 5, 5),
                "but the player is not standing in it, so a block may be placed there");
        assertTrue(PlayerPhysics.standingIn(lvl, s.x, s.y, size, plane, 5, 6),
                "they are standing on the cell south of it");

        // Edge-on, the whole box is still where the player is.
        PerspectiveSpace wall = PerspectiveSpace.of(Perspective.SIDE_SCROLL);
        assertTrue(PlayerPhysics.standingIn(lvl, 5 * 32, 5 * 32, size, wall, 5, 5));
    }

    /** A layered plan-view level, floored throughout, with one wall at (5,5). */
    private static Level walledPlane(LevelFormat format) {
        Level lvl = Level.empty("plane", 12, 12, 32);
        lvl.setFormat(format);
        int path = lvl.blocks.get("stone_path").id();
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, path);
        }
        lvl.setTile(5, 5, Level.LAYER_UPPER, lvl.blocks.get("stone").id());
        assertTrue(lvl.solidAt(5, 5), format + ": the stack is a wall");
        return lvl;
    }

    /** Walk a fresh player from (x0,y0) in one direction until they stop. */
    private static PlayerState walkInto(Level lvl, GameProfile p, Perspective view,
                                        double x0, double y0, int ix, int iy) {
        PlayerState s = new PlayerState(1, "t", x0, y0);
        PlayerInput in = new PlayerInput(ix < 0, ix > 0, iy < 0, iy > 0, 1);
        for (int i = 0; i < 400; i++) PlayerPhysics.step(s, in, lvl, p, view, DT);
        return s;
    }

    /** Where the sprite's feet come to rest: the base line of the billboard. */
    private static double[] feet(PlayerState s, double size) {
        return new double[]{s.x + size / 2, s.y + size};
    }
}
