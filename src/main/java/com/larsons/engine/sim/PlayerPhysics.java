package com.larsons.engine.sim;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;

/**
 * The deterministic player-movement step, extracted from the play scene so the
 * <em>same</em> code runs in three places: single-player, client-side
 * prediction, and the authoritative server (requirement #3). Fixed {@code dt}
 * plus pure functions of (state, input, level, profile) is what lets client
 * and server agree on simulation results.
 *
 * <p><b>Collision.</b> Movement resolves as axis-separated AABB sweeps against
 * solid tiles ({@link #slideX}/{@link #slideY}): the full body collides with
 * <em>every</em> face — walls stop sideways movement, ceilings stop jumps, and
 * floors stop falls — instead of the old feet-only ground probe that let
 * players pass through walls. Mobs share the same helpers so both simulations
 * obey identical rules.
 *
 * <p><b>What collides is not the same shape in every format</b>, because the
 * body box does not mean the same thing in all three. Edge-on, the box
 * <em>is</em> the character: it is drawn inside those bounds and every face of
 * it can hit something, so the whole box sweeps ({@link #slideX}/{@link
 * #slideY}). On a plane the box is a patch of <em>floor</em> and the character
 * is a billboard standing on it — feet on the box's southern edge, head a whole
 * body north of them. Colliding the whole box there stops a character when
 * their <em>head</em> reaches a wall, which leaves their feet a full body-length
 * short of it: an invisible barrier most of a tile deep along the south face of
 * every raised block, and nothing at all along the north face. So the plan views
 * collide the ground under the feet instead — {@link #walkX}/{@link #walkY} over
 * the {@link #FOOT_FRACTION} footprint — and a character can walk up to a block
 * from any side and stand against it.
 *
 * <p><b>Perspective.</b> Each format simulates in its own space (see
 * {@link PerspectiveSpace}). The side-scrolling format runs the platformer
 * model (gravity down the screen, jumps, swimming); the plan-view formats
 * (top-down, isometric) walk the whole plane instead — both axes are steering,
 * diagonals are normalized so they aren't faster than the axes, and sprinting
 * applies in every direction. Jumping works in <em>all three</em>: on a plane
 * the world grid is the floor, so gravity has moved off it onto the elevation
 * axis and a jump is a hop along {@link PlayerState#z} that lifts the
 * character over their own shadow and sets them back down — same key, same
 * double jump, same stamina cost.
 *
 * <p><b>Jump is Space.</b> {@link PlayerInput#jump} is the only thing that
 * launches one, in every format. {@link PlayerInput#up} is a <em>direction</em>
 * — it strokes upward while swimming, climbs while flying, and walks north on
 * a plane — so holding it no longer bounces the character off the ground.
 *
 * <p><b>Facing.</b> Every step records the compass direction the character is
 * heading ({@link PlayerState#facing}): two directions in a side-scroller,
 * eight on the plane, which is what picks the directional sprite that draws
 * them.
 *
 * <p><b>Character traits.</b> The chosen
 * {@link com.larsons.engine.character.CharacterProfile} rides on the state —
 * speed multiplier, sprint permission, air-jump allowance, jump height — so
 * two players in the same level move differently while running one simulation.
 *
 * <p><b>Stamina.</b> Sprinting (Shift) multiplies ground speed while stamina
 * lasts and jumping takes a bite; standing or walking restores it. Mana
 * regenerates here too (spent by magic weapons in {@code World.playerShoot}).
 */
public final class PlayerPhysics {

    public static final double SPEED = 220;     // px/sec
    public static final double GRAVITY = 1500;  // px/sec^2
    public static final double JUMP = 560;      // px/sec

    // Sprint & resource flow (see PlayerState.stamina / .mana).
    public static final double SPRINT_FACTOR = 1.6;
    public static final double SPRINT_COST = 22;     // stamina/sec while sprinting
    public static final double JUMP_COST = 12;       // stamina per jump
    public static final double STAMINA_REGEN = 16;   // stamina/sec when not sprinting
    public static final double MANA_REGEN = 7;       // mana/sec

    // Swimming (the player's centre is inside a liquid block).
    public static final double SWIM_SPEED_FACTOR = 0.6;  // drag on horizontal speed
    public static final double SWIM_UP = 900;            // upward accel while holding up
    public static final double SWIM_SINK = 350;          // passive sink accel
    public static final double SWIM_MAX_RISE = 200;      // px/sec
    public static final double SWIM_MAX_SINK = 140;      // px/sec
    /**
     * Upward velocity of the water-exit jump: stroking up with the head clear
     * of the surface converts into a real jump. The capped swim rise speed is
     * too slow to clear a pool's lip, which used to trap swimmers bobbing at
     * the surface forever.
     */
    public static final double WATER_EXIT_JUMP = JUMP * 0.85;

    // Mid-air jumps: the character profile's allowance (1 by default — the
    // classic double jump); special items carried in the inventory raise
    // PlayerState.bonusAirJumps for triple/quad/infinite.
    public static final double AIR_JUMP_FACTOR = 0.92;   // of a grounded jump

    // Plan-view hop (top-down / isometric). The tile grid is the floor there,
    // so Space lifts the character along the elevation axis instead: they
    // rise, hang, and settle back down over their own shadow. Slower than a
    // side-scroll jump so it reads as a hop rather than a launch.
    public static final double HOP_SPEED = 320;          // px/sec upward
    public static final double HOP_GRAVITY = 900;        // px/sec^2 pulling back down
    /** How far a hop's peak lifts the sprite, as a fraction of its height. */
    public static final double HOP_DRAW_SCALE = 1.0;

    /**
     * How much a raised guard slows a fighter down. "Shield ready" is a
     * commitment: you take the blow instead of avoiding it, and you cannot
     * chase anybody while you are behind it.
     */
    public static final double GUARD_SPEED_FACTOR = 0.45;

    // Relic passives (see PlayerState.speedFactor / slowFall / canFly).
    public static final double FLY_ACCEL = 2600;         // Aether Wings climb accel
    public static final double FLY_MAX_RISE = 380;       // px/sec climbing
    public static final double SLOW_FALL_GRAVITY = 0.45; // gravity multiplier
    public static final double SLOW_FALL_MAX = 170;      // px/sec terminal velocity

    /**
     * Tolerance for "flush against a tile boundary": bodies clamp exactly
     * onto tile edges (a landing rests at precisely {@code row*ts - h}), and
     * the sweep treats an edge within EPS of a boundary as sitting on it, so
     * resting flush neither re-collides nor tunnels.
     */
    public static final double COLLISION_EPS = 0.001;

    /**
     * How much floor a body's feet cover on a plan view, as a fraction of the
     * body's size — the footprint {@link #walkX}/{@link #walkY} collide.
     *
     * <p>A third of the body, which is about what a standing figure's boots
     * actually cover and small enough that the character reads as touching
     * whatever they walk up to: they come to rest with half a footprint —
     * around a sixth of a tile — between their feet and the wall, on every
     * side. Making it smaller buys nothing (a wall is already flush at this
     * size) and costs the feet somewhere to stand: a footprint narrower than a
     * few pixels can slip between the tiles it is meant to be stopped by.
     *
     * <p>It does not widen what a body can fit through. Gaps are cut in whole
     * tiles, and the body box was already narrower than one.
     */
    public static final double FOOT_FRACTION = 0.34;

    private PlayerPhysics() {}

    /**
     * Advance {@code s} by one {@code dt} step. {@code perspective} is passed
     * explicitly because it may come from the camera (single-player allows
     * switching in-game) or from the profile (networked play, where physics
     * must not depend on a client's local view).
     */
    public static void step(PlayerState s, PlayerInput in, Level level, GameProfile profile,
                            Perspective perspective, double dt) {
        double size = profile.playerSize;
        double ts = level.tileSize;

        boolean inLiquid = level.liquidAt(
                (int) Math.floor((s.x + size / 2.0) / ts),
                (int) Math.floor((s.y + size / 2.0) / ts)) != null;

        // Which axis is down here, and whether the game type lets it pull: a
        // side-scroller with gravity switched off walks its plane like a plan
        // view does.
        PerspectiveSpace space = PerspectiveSpace.of(perspective);
        boolean sideScroll = space.gravityOnPlane() && profile.gravityEnabled;
        // Whether the world plane is the floor, and so whether what collides is
        // the ground under the feet rather than the whole body box. This asks
        // the projection rather than the gravity toggle on purpose: a
        // side-scroller with gravity switched off still stands its characters
        // edge-on against the screen, and there the box really is the body.
        boolean planView = space.hasElevation();
        // On a plane every direction is walking, so up/down count as movement
        // for sprinting too — sprinting north in a top-down level is the same
        // act as sprinting east.
        boolean wantsMove = in.left || in.right || (!sideScroll && (in.up || in.down));
        // Sprinting needs a character that can sprint and stamina to spend —
        // unless a running ultimate is carrying them (Overdrive).
        boolean sprinting = in.sprint && wantsMove && !inLiquid
                && s.sprintAllowed && (s.stamina > 0 || s.ultTireless);
        // Relic boots, the character's own pace, and any active ultimate all
        // scale the same base speed.
        double speed = SPEED * s.speedFactor * s.characterSpeed * s.ultSpeedFactor;
        if (inLiquid) speed *= SWIM_SPEED_FACTOR;
        if (sprinting) speed *= SPRINT_FACTOR;
        if (s.guarding) speed *= GUARD_SPEED_FACTOR;
        // Diagonals on a plane would otherwise travel √2 times as fast as the
        // axes. That is now PlayerInput's business rather than a special case
        // here: it hands back a unit vector, so every direction is one step and
        // the four diagonals stop being the only ones that needed saying.

        // A committed melee burst — a lunge or a dash — overrides steering for
        // as long as it lasts. It still collides with the world like any other
        // movement; what it doesn't do is let go of the direction it was
        // thrown in, which is exactly what makes it a commitment.
        boolean bursting = s.dashTime > 0;
        if (bursting) {
            s.dashTime = Math.max(0, s.dashTime - dt);
            if (s.dashTime <= 0) {
                s.dashVx = 0;
                s.dashVy = 0;
            }
        }

        double dx = 0;
        if (bursting) {
            dx = s.dashVx * dt;
            if (s.dashVx != 0) s.facingLeft = s.dashVx < 0;
        } else if (sideScroll) {
            // Edge-on, the keys are the axes: left is left and there is no
            // heading to turn them by.
            if (in.left) { dx -= speed * dt; s.facingLeft = true; }
            if (in.right) { dx += speed * dt; s.facingLeft = false; }
        } else {
            // On a plane the keys are a screen intent and the world direction
            // they mean depends on where the camera was pointing when they were
            // pressed — which is why the heading rides the input rather than
            // being asked of a camera the server does not have. C7.
            dx = in.moveX() * speed * dt;
        }
        boolean moving = dx != 0;
        double steerY = 0; // plan-view vertical steering, for the facing below

        if (sideScroll && inLiquid) {
            s.airJumpsUsed = 0; // water resets the double jump
            boolean headClear = level.liquidAt(
                    (int) Math.floor((s.x + size / 2.0) / ts),
                    (int) Math.floor((s.y + size * 0.25) / ts)) == null;
            if (in.up && headClear) {
                // Water-exit jump: enough to climb the pool's lip and land.
                s.vy = Math.min(s.vy, -WATER_EXIT_JUMP);
            } else {
                // Swimming: buoyant sink by default, stroke upward while held.
                s.vy += (in.up ? -SWIM_UP : SWIM_SINK) * dt;
                s.vy = Math.max(-SWIM_MAX_RISE, Math.min(SWIM_MAX_SINK, s.vy));
            }
            double ny = slideY(level, s.x, s.y, size, size, s.vy * dt);
            if (ny != s.y + s.vy * dt) s.vy = 0; // hit floor or ceiling
            s.y = ny;
            moving = moving || in.up || in.down;
        } else if (sideScroll) {
            boolean grounded = onGround(level, s.x, s.y, size, size);
            if (grounded && s.vy >= 0) {
                s.vy = 0;
                s.airJumpsUsed = 0;
                // Only the jump key (Space) jumps. Up is a direction, not a
                // jump: it swims, it flies, and on a plane it walks north —
                // binding it here meant holding W to swim or to steer fired
                // jumps too.
                if (in.jump) {
                    s.vy = -JUMP * s.jumpFactor;
                    spendJumpStamina(s);
                }
            } else {
                // Mid-air jumps on a fresh press: the character's allowance
                // (a double jump by default); carried items add more.
                if (in.jump && s.airJumpsUsed < s.airJumps + s.bonusAirJumps) {
                    s.airJumpsUsed++;
                    s.vy = -JUMP * AIR_JUMP_FACTOR * s.jumpFactor;
                    spendJumpStamina(s);
                }
                if (s.canFly && in.up) {
                    // Aether Wings: holding up climbs instead of falling.
                    s.vy = Math.max(s.vy - FLY_ACCEL * dt, -FLY_MAX_RISE);
                } else if (s.slowFall && s.vy >= 0) {
                    // Gravity Amulet: drift down under a soft terminal velocity.
                    s.vy = Math.min(s.vy + GRAVITY * SLOW_FALL_GRAVITY * dt,
                            SLOW_FALL_MAX);
                } else {
                    s.vy += GRAVITY * dt;
                }
            }
            double dy = s.vy * dt;
            double ny = slideY(level, s.x, s.y, size, size, dy);
            if (ny != s.y + dy) s.vy = 0; // landed, or bonked a ceiling
            s.y = ny;
            // Nothing hops in a side-scroller; keep the Z axis parked so a
            // level swapped mid-game never leaves the sprite floating.
            s.z = 0;
            s.vz = 0;
        } else {
            double dy = bursting ? s.dashVy * dt : in.moveY() * speed * dt;
            s.y = planView ? walkY(level, s.x, s.y, size, dy)
                    : slideY(level, s.x, s.y, size, size, dy);
            moving = moving || dy != 0;
            steerY = dy;
            stepHop(s, in, dt);
        }

        s.x = planView ? walkX(level, s.x, s.y, size, dx)
                : slideX(level, s.x, s.y, size, size, dx);
        clampToLevel(s, level, size);
        s.moving = moving;
        // Which way the character is drawn facing: left/right in a
        // side-scroller, all eight compass points on the plane. Standing still
        // keeps the last heading rather than snapping back to a default.
        s.facing = Facing.of(dx, steerY, perspective, s.facing);
        if (!sideScroll) s.facingLeft = s.facing.facingLeft();

        // Resource flow: sprint drains, everything else recovers.
        if (sprinting && !s.ultTireless) {
            s.stamina -= SPRINT_COST * dt;
        } else {
            s.stamina += STAMINA_REGEN * dt;
        }
        s.stamina = Math.max(0, Math.min(s.maxStamina, s.stamina));
        s.mana = Math.max(0, Math.min(s.maxMana, s.mana + MANA_REGEN * dt));
    }

    /**
     * Advance the plan-view hop: a fresh jump press off the ground launches
     * along Z, further presses spend the character's air jumps, and the
     * character falls back to Z=0. Steering keeps working mid-air, so a hop
     * carries you across a gap exactly as a side-scroll jump does.
     */
    private static void stepHop(PlayerState s, PlayerInput in, double dt) {
        if (s.z <= 0 && s.vz <= 0) {
            // Standing on the ground: nothing to integrate unless we launch.
            s.z = 0;
            s.vz = 0;
            s.airJumpsUsed = 0;
            if (!in.jump) return;
            s.vz = HOP_SPEED * s.jumpFactor;
            spendJumpStamina(s);
        } else if (in.jump && s.airJumpsUsed < s.airJumps + s.bonusAirJumps) {
            s.airJumpsUsed++;
            s.vz = HOP_SPEED * AIR_JUMP_FACTOR * s.jumpFactor;
            spendJumpStamina(s);
        }
        // Airborne — including the tick we took off on, so a hop reads as
        // airborne (and plays the jump animation) from its very first frame.
        s.vz -= HOP_GRAVITY * dt;
        s.z += s.vz * dt;
        if (s.z <= 0) {
            s.z = 0;
            s.vz = 0;
            s.airJumpsUsed = 0;
        }
    }

    /** A jump's stamina bite, which a tireless ultimate waives. */
    private static void spendJumpStamina(PlayerState s) {
        if (s.ultTireless) return;
        s.stamina = Math.max(0, s.stamina - JUMP_COST);
    }

    // --- shared AABB collision helpers (players and mobs) ------------------------

    /**
     * Move a {@code w}&times;{@code h} body at (x,y) horizontally by
     * {@code dx}, stopping flush against the first solid tile its leading edge
     * crosses. Returns the resulting x (compare with {@code x + dx} to detect
     * a hit).
     */
    public static double slideX(Level level, double x, double y, double w, double h, double dx) {
        if (dx == 0) return x;
        double ts = level.tileSize;
        int r0 = (int) Math.floor((y + COLLISION_EPS) / ts);
        int r1 = (int) Math.floor((y + h - COLLISION_EPS) / ts);
        if (dx > 0) {
            double edge = x + w;
            // First column strictly beyond the edge (flush-on-boundary counts
            // as still outside that column, so resting flush is stable).
            int scan = (int) Math.floor((edge - COLLISION_EPS) / ts) + 1;
            int end = (int) Math.floor((edge + dx) / ts);
            for (int col = scan; col <= end; col++) {
                for (int r = r0; r <= r1; r++) {
                    if (level.solidAt(col, r)) return col * ts - w;
                }
            }
        } else {
            double edge = x;
            int scan = (int) Math.floor((edge + COLLISION_EPS) / ts) - 1;
            int end = (int) Math.floor((edge + dx) / ts);
            for (int col = scan; col >= end; col--) {
                for (int r = r0; r <= r1; r++) {
                    if (level.solidAt(col, r)) return (col + 1) * ts;
                }
            }
        }
        return x + dx;
    }

    /**
     * Vertical twin of {@link #slideX}: falls land exactly on tile tops,
     * jumps stop flush under tile bottoms.
     */
    public static double slideY(Level level, double x, double y, double w, double h, double dy) {
        if (dy == 0) return y;
        double ts = level.tileSize;
        int c0 = (int) Math.floor((x + COLLISION_EPS) / ts);
        int c1 = (int) Math.floor((x + w - COLLISION_EPS) / ts);
        if (dy > 0) {
            double edge = y + h;
            int scan = (int) Math.floor((edge - COLLISION_EPS) / ts) + 1;
            int end = (int) Math.floor((edge + dy) / ts);
            for (int row = scan; row <= end; row++) {
                for (int c = c0; c <= c1; c++) {
                    if (level.solidAt(c, row)) return row * ts - h;
                }
            }
        } else {
            double edge = y;
            int scan = (int) Math.floor((edge + COLLISION_EPS) / ts) - 1;
            int end = (int) Math.floor((edge + dy) / ts);
            for (int row = scan; row >= end; row--) {
                for (int c = c0; c <= c1; c++) {
                    if (level.solidAt(c, row)) return (row + 1) * ts;
                }
            }
        }
        return y + dy;
    }

    /**
     * Whether a {@code w}&times;{@code h} body at (x,y) overlaps solid terrain
     * — the standing test, as opposed to the sweeps above. A teleport that
     * lands here has landed inside a wall.
     */
    public static boolean blocked(Level level, double x, double y, double w, double h) {
        double ts = level.tileSize;
        int c0 = (int) Math.floor(x / ts);
        int c1 = (int) Math.floor((x + w - COLLISION_EPS) / ts);
        int r0 = (int) Math.floor(y / ts);
        int r1 = (int) Math.floor((y + h - COLLISION_EPS) / ts);
        for (int c = c0; c <= c1; c++) {
            for (int r = r0; r <= r1; r++) {
                if (level.solidAt(c, r)) return true;
            }
        }
        return false;
    }

    // --- the plan-view footprint, and the sweeps over it -------------------------

    /** The side of the square patch of floor a {@code size} body stands on. */
    public static double footSize(double size) {
        return Math.max(1, size * FOOT_FRACTION);
    }

    /**
     * The west edge of that patch: the footprint is centred across the body,
     * under the middle of the sprite, because that is where the legs are.
     */
    public static double footLeft(double x, double size) {
        return x + (size - footSize(size)) / 2;
    }

    /**
     * The north edge of that patch, which straddles the body's base line
     * ({@code y + size}) rather than sitting above it.
     *
     * <p>Centring it on the feet is what makes the four approaches symmetric.
     * A footprint tucked <em>inside</em> the box would let a character put
     * their feet exactly on a wall's north face and still stop a whole
     * footprint short of its south face — the same lopsidedness in miniature.
     */
    public static double footTop(double y, double size) {
        return y + size - footSize(size) / 2;
    }

    /**
     * {@link #slideX} for a body walking a plane: the feet sweep, and the body
     * box is carried along with them. Returns the body's new x.
     */
    public static double walkX(Level level, double x, double y, double size, double dx) {
        double foot = footSize(size);
        double fx = footLeft(x, size);
        return x + (slideX(level, fx, footTop(y, size), foot, foot, dx) - fx);
    }

    /** {@link #slideY} over the same footprint. Returns the body's new y. */
    public static double walkY(Level level, double x, double y, double size, double dy) {
        double foot = footSize(size);
        double fy = footTop(y, size);
        return y + (slideY(level, footLeft(x, size), fy, foot, foot, dy) - fy);
    }

    /** Whether a plan-view body's feet are standing in solid terrain. */
    public static boolean footBlocked(Level level, double x, double y, double size) {
        double foot = footSize(size);
        return blocked(level, footLeft(x, size), footTop(y, size), foot, foot);
    }

    /**
     * Whether a body of {@code size} at (x,y) is standing in the cell
     * (col,row) — measured on whichever shape {@code space} collides with.
     *
     * <p>Block placement asks this so a player cannot brick themselves in. It
     * has to be the collision shape and not the body box: on a plane a
     * character's head routinely overlaps the wall they are standing against,
     * and refusing to build there would refuse the most ordinary placement
     * there is — laying a block on the far side of the one in front of you.
     */
    public static boolean standingIn(Level level, double x, double y, double size,
                                     PerspectiveSpace space, int col, int row) {
        boolean onFloor = space.hasElevation();
        double bx = onFloor ? footLeft(x, size) : x;
        double by = onFloor ? footTop(y, size) : y;
        double bs = onFloor ? footSize(size) : size;
        double ts = level.tileSize;
        return bx + bs > col * ts && bx < (col + 1) * ts
                && by + bs > row * ts && by < (row + 1) * ts;
    }

    /** Whether the body rests on solid ground (any tile under its bottom edge). */
    public static boolean onGround(Level level, double x, double y, double w, double h) {
        double ts = level.tileSize;
        int row = (int) Math.floor((y + h + 1) / ts);
        // The level's bottom edge is ground: bodies clamped there could never
        // probe a solid tile below and were stuck unable to jump.
        if (row >= level.height) return true;
        int c0 = (int) Math.floor(x / ts);
        int c1 = (int) Math.floor((x + w - COLLISION_EPS) / ts);
        for (int c = c0; c <= c1; c++) {
            if (level.solidAt(c, row)) return true;
        }
        return false;
    }

    public static boolean isSolid(Level level, double worldX, double worldY, double tileSize) {
        int col = (int) Math.floor(worldX / tileSize);
        int row = (int) Math.floor(worldY / tileSize);
        // Registry-mode levels distinguish solid terrain from passable
        // decoration (water, leaves, lights); legacy levels treat any tile as
        // solid, exactly as before.
        return level.solidAt(col, row);
    }

    private static void clampToLevel(PlayerState s, Level level, double size) {
        double maxX = Math.max(0, level.width * (double) level.tileSize - size);
        double maxY = Math.max(0, level.height * (double) level.tileSize - size);
        s.x = Math.max(0, Math.min(s.x, maxX));
        s.y = Math.max(0, Math.min(s.y, maxY));
    }
}
