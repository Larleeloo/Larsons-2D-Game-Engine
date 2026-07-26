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
        // Diagonals on a plane would otherwise travel √2 times as fast as the
        // axes; normalizing the step keeps top-down/isometric speed uniform in
        // every direction.
        if (!sideScroll && (in.left ^ in.right) && (in.up ^ in.down)) {
            speed *= Math.sqrt(0.5);
        }

        double dx = 0;
        if (in.left) { dx -= speed * dt; s.facingLeft = true; }
        if (in.right) { dx += speed * dt; s.facingLeft = false; }
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
            double dy = 0;
            if (in.up) dy -= speed * dt;
            if (in.down) dy += speed * dt;
            s.y = slideY(level, s.x, s.y, size, size, dy);
            moving = moving || dy != 0;
            steerY = dy;
            stepHop(s, in, dt);
        }

        s.x = slideX(level, s.x, s.y, size, size, dx);
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
