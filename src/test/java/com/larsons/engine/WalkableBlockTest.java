package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing on blocks — {@code HEIGHT_PLAN.md} Job W.
 *
 * <p>The plan views have had a height axis since blocks learned to stack, and
 * until now it was only ever something that <em>stopped</em> you: a wall is
 * impassable because two layers means "no", not because there is anything on
 * top of it. This is the job where the axis becomes somewhere a body can be,
 * and the whole of it follows from one number that used to be the literal
 * zero — the floor {@link PlayerState#z} settles onto.
 *
 * <p><b>Every test here runs the real step.</b> Nothing pokes {@code z}
 * directly and asserts a helper: the questions worth asking are about what
 * happens when a player walks, and the helpers have already been wrong in ways
 * that only showed up once something drove them (see the plan's V4, where the
 * accessor was right and the example in the step was not).
 */
@Timeout(60)
class WalkableBlockTest {

    private static final int TILE = 32;
    private static final double DT = 1.0 / 60;

    // --- the gate ----------------------------------------------------------------

    /**
     * A level that has not asked for the height axis plays exactly as it did.
     *
     * <p>This is the plan's second invariant, and it is at real risk here: a
     * hop's apex is about 57 world units against a block of 17.6, so the moment
     * landing on a column is possible every wall in every saved level is
     * climbable and every maze is traversable over its own walls. That is the
     * feature working, which is why it is the level's decision — and a level
     * saved before the decision existed has not made it.
     */
    @Test
    void aLevelThatNeverAskedForHeightIsUnchanged() {
        Level lvl = walled(false);
        assertFalse(lvl.verticality(), "a level says nothing, so the answer is no");

        PlayerState s = onCell(lvl, 4, 5);
        // Hop, and hold east into the wall for a good while.
        for (int i = 0; i < 120; i++) {
            step(lvl, s, input(true, false, i < 2));
        }
        assertEquals(0, s.z, 1e-9, "a hop still comes back to the one floor there is");
        assertEquals(5, footCol(s), "and the wall is still a wall — stopped west of it");
    }

    /** With the axis on, the same wall is somewhere to stand. */
    @Test
    void withTheAxisOnAHopLandsOnTheWall() {
        Level lvl = walled(true);
        PlayerState s = onCell(lvl, 4, 5);
        double top = lvl.surfaceZ(2);

        for (int i = 0; i < 120; i++) step(lvl, s, input(true, false, i < 2));
        assertEquals(top, s.z, 1e-6, "the player is standing on top of the wall");
        assertTrue(footCol(s) >= 6, "having crossed onto it, feet in column " + footCol(s));
    }

    // --- what holds a body up ----------------------------------------------------

    /** A body settles onto the column under its feet, however deep that is. */
    @Test
    void aBodySettlesOntoTheColumnUnderIt() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = floored(format, true);
            int stone = lvl.blocks.get("stone").id();
            for (int layer = 1; layer < 4; layer++) lvl.setTile(5, 5, layer, stone);

            // Dropped in from above the tower, the body lands on its top.
            PlayerState s = onCell(lvl, 5, 5);
            s.z = lvl.surfaceZ(4) + 200;
            for (int i = 0; i < 240; i++) step(lvl, s, input(false, false, false));
            assertEquals(lvl.surfaceZ(4), s.z, 1e-6,
                    format + ": four blocks deep, so its top is three blocks up");
        }
    }

    /**
     * A torch is a whole block of geometry and holds nobody up, because you
     * walk through it rather than onto it. The distinction V4 had to correct
     * its own worked example over, arriving here as behaviour.
     */
    @Test
    void aTorchIsGeometryAndNotAFloor() {
        Level lvl = floored(LevelFormat.TOP_DOWN, true);
        lvl.setTile(5, 5, Level.LAYER_UPPER, lvl.blocks.get("torch").id());
        assertEquals(2, lvl.stackHeight(5, 5), "two blocks of geometry");

        PlayerState s = onCell(lvl, 5, 5);
        for (int i = 0; i < 60; i++) step(lvl, s, input(false, false, false));
        assertEquals(0, s.z, 1e-9, "and the player stands on the floor under it");
    }

    // --- step up, step down ------------------------------------------------------

    /** An ordinary block is a wall: you get on top of it by jumping or not at all. */
    @Test
    void anOrdinaryBlockIsNotWalkedUp() {
        Level lvl = walled(true);
        PlayerState s = onCell(lvl, 4, 5);
        for (int i = 0; i < 180; i++) step(lvl, s, input(true, false, false));

        assertEquals(0, s.z, 1e-9, "walking at a wall does not climb it");
        assertEquals(5, footCol(s), "and it stops you west of it");
    }

    /** A stair is walked up, which is what a stair is for. */
    @Test
    void aStairIsWalkedUpWithoutJumping() {
        Level lvl = floored(LevelFormat.TOP_DOWN, true);
        int stairs = lvl.blocks.get("stone_stairs").id();
        int stone = lvl.blocks.get("stone").id();
        // A staircase east of the spawn, rising a block per column, opening
        // onto a plateau at the top — otherwise walking east simply carries
        // the climber off the far side of the last step.
        for (int r = 0; r < lvl.height; r++) {
            for (int step = 1; step <= 3; step++) lvl.setTile(5 + step, r, step, stairs);
            for (int c = 9; c < lvl.width; c++) {
                for (int layer = 1; layer <= 3; layer++) lvl.setTile(c, r, layer, stairs);
            }
        }
        // Each step is solid up to its own height: column 6 one deep, 7 two,
        // 8 three, and the plateau beyond them three.
        for (int r = 0; r < lvl.height; r++) {
            lvl.setTile(6, r, 1, stairs);
            lvl.setTile(7, r, 1, stairs);
            lvl.setTile(7, r, 2, stairs);
            lvl.setTile(8, r, 1, stairs);
            lvl.setTile(8, r, 2, stairs);
            lvl.setTile(8, r, 3, stairs);
        }

        PlayerState s = onCell(lvl, 4, 5);
        for (int i = 0; i < 240; i++) step(lvl, s, input(true, false, false));
        assertEquals(lvl.surfaceZ(4), s.z, 1e-6,
                "walked all the way up the staircase, ending on the top step");

        // The same staircase built out of plain blocks stops them at the first.
        Level walls = floored(LevelFormat.TOP_DOWN, true);
        for (int r = 0; r < walls.height; r++) walls.setTile(6, r, 1, stone);
        PlayerState blocked = onCell(walls, 4, 5);
        for (int i = 0; i < 240; i++) step(walls, blocked, input(true, false, false));
        assertEquals(0, blocked.z, 1e-9, "a plain block of the same height is a wall");
        assertEquals(5, footCol(blocked), "and stops them west of it");
    }

    /** Walking off a ledge falls, and does not hand out a free jump on the way. */
    @Test
    void walkingOffALedgeFallsAndSpendsNoJump() {
        Level lvl = floored(LevelFormat.TOP_DOWN, true);
        int stone = lvl.blocks.get("stone").id();
        // A plateau three deep, from the west edge out to column 6.
        for (int c = 0; c <= 6; c++) {
            for (int r = 0; r < lvl.height; r++) {
                lvl.setTile(c, r, 1, stone);
                lvl.setTile(c, r, 2, stone);
            }
        }
        PlayerState s = onCell(lvl, 4, 5);
        s.z = lvl.surfaceZ(3);
        step(lvl, s, input(false, false, false));
        assertEquals(lvl.surfaceZ(3), s.z, 1e-6, "standing on the plateau");
        assertEquals(0, s.airJumpsUsed, "and rested, so the jumps are back");

        // Walk east, off the edge.
        for (int i = 0; i < 240; i++) step(lvl, s, input(true, false, false));
        assertEquals(0, s.z, 1e-6, "and comes to rest on the floor below");
        assertTrue(footCol(s) >= 7, "having walked off the edge, feet in column " + footCol(s));
    }

    // --- falling -----------------------------------------------------------------

    /** A fall hurts only where the level asked for it to. */
    @Test
    void fallDamageIsOffUntilALevelAsksForIt() {
        for (boolean hurts : List.of(false, true)) {
            Level lvl = floored(LevelFormat.TOP_DOWN, true);
            lvl.settings.fallDamageEnabled = hurts;
            PlayerState s = onCell(lvl, 4, 5);
            double full = s.health;
            s.z = lvl.blockHeight() * 30;   // dropped from a great height
            for (int i = 0; i < 600; i++) step(lvl, s, input(false, false, false));

            assertEquals(0, s.z, 1e-6, "landed either way");
            if (hurts) {
                assertTrue(s.health < full, "a long fall hurt, " + s.health);
            } else {
                assertEquals(full, s.health, 1e-9, "and by default it does not");
            }
        }
    }

    /** A hop never hurts, whatever the level says about falling. */
    @Test
    void aHopIsNeverFarEnoughToHurt() {
        Level lvl = floored(LevelFormat.TOP_DOWN, true);
        lvl.settings.fallDamageEnabled = true;
        PlayerState s = onCell(lvl, 4, 5);
        double full = s.health;
        for (int i = 0; i < 240; i++) step(lvl, s, input(false, false, i % 60 == 0));
        assertEquals(full, s.health, 1e-9, "jumping on the spot is free");
    }

    // --- building ----------------------------------------------------------------

    /** A player is in the way of a block at their own height, and not of one above. */
    @Test
    void aBlockMayBeLaidOverAPlayersHeadButNotThroughThem() {
        Level lvl = floored(LevelFormat.TOP_DOWN, true);
        PlayerState s = onCell(lvl, 5, 5);
        var space = com.larsons.engine.sim.PerspectiveSpace.of(lvl.perspective);

        assertTrue(PlayerPhysics.standingIn(lvl, s.x, s.y, SIZE, space, 5, 5, s.z, 1),
                "the block at their feet's height would be inside them");
        assertFalse(PlayerPhysics.standingIn(lvl, s.x, s.y, SIZE, space, 5, 5, s.z, 4),
                "one four layers up is over their head and fine");
        assertFalse(PlayerPhysics.standingIn(lvl, s.x, s.y, SIZE, space, 8, 5, s.z, 1),
                "and one in the next column but one is nowhere near them");
    }

    // --- helpers -----------------------------------------------------------------

    private static Level floored(LevelFormat format, boolean vertical) {
        Level lvl = Level.empty("walk", 16, 12, TILE);
        lvl.setFormat(format);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        GameProfile settings = new GameProfile("walk-test");
        settings.verticality = vertical;
        lvl.settings = settings;
        return lvl;
    }

    /**
     * A floored level raised one block from column 6 eastward — a low plateau
     * with its west face at column 6.
     *
     * <p>A plateau rather than a single wall of blocks, because a body that
     * hops onto a one-column wall and keeps walking east walks straight off the
     * far side of it and lands back on the floor. That is correct behaviour and
     * it makes for a fixture that cannot tell "never got up" from "got up and
     * came down again" — which is exactly how the first version of this file
     * read.
     */
    private static Level walled(boolean vertical) {
        Level lvl = floored(LevelFormat.TOP_DOWN, vertical);
        int stone = lvl.blocks.get("stone").id();
        for (int c = 6; c < lvl.width; c++) {
            for (int r = 0; r < lvl.height; r++) lvl.setTile(c, r, 1, stone);
        }
        return lvl;
    }

    /**
     * The hitbox a test body collides with — the default player size, spelled
     * out because where the feet land is measured against it.
     */
    private static final double SIZE = new GameProfile("size").playerSize;

    /**
     * A player whose <em>foot patch</em> is centred on the middle of cell
     * (col,row).
     *
     * <p>Worth the arithmetic, because the obvious spawn is wrong in a way that
     * looks like a physics bug. A body's (x,y) is the top-left of its box and
     * its feet are a body-length south of that ({@link PlayerPhysics#footTop}),
     * so "spawn at 5.5 tiles" puts the feet in row 6 — and puts the patch's
     * east edge already inside column 6, where the sweep correctly declines to
     * collide with a cell the body is standing in. The first version of this
     * file did exactly that and read as a wall that stopped nobody.
     */
    private static PlayerState onCell(Level lvl, int col, int row) {
        PlayerState s = new PlayerState(1, "walker",
                (col + 0.5) * TILE - SIZE / 2.0, (row + 0.5) * TILE - SIZE);
        s.blockHeight = lvl.blockHeight();
        return s;
    }

    /** The column the body's feet are standing in. */
    private static int footCol(PlayerState s) {
        return (int) Math.floor(
                (PlayerPhysics.footLeft(s.x, SIZE) + PlayerPhysics.footSize(SIZE) / 2) / TILE);
    }

    private static PlayerInput input(boolean east, boolean south, boolean jump) {
        PlayerInput in = new PlayerInput();
        in.right = east;
        in.down = south;
        in.jump = jump;
        return in;
    }

    private static void step(Level lvl, PlayerState s, PlayerInput in) {
        GameProfile profile = lvl.settings;
        PlayerPhysics.step(s, in, lvl, profile, lvl.perspective, DT);
    }
}
