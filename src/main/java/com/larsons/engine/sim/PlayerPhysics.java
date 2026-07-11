package com.larsons.engine.sim;

import com.larsons.engine.config.GameProfile;
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

        boolean wantsMove = in.left || in.right;
        boolean sprinting = in.sprint && wantsMove && !inLiquid && s.stamina > 0;
        double speed = SPEED;
        if (inLiquid) speed *= SWIM_SPEED_FACTOR;
        if (sprinting) speed *= SPRINT_FACTOR;

        double dx = 0;
        if (in.left) { dx -= speed * dt; s.facingLeft = true; }
        if (in.right) { dx += speed * dt; s.facingLeft = false; }
        boolean moving = dx != 0;

        boolean sideScroll = perspective == Perspective.SIDE_SCROLL && profile.gravityEnabled;
        if (sideScroll && inLiquid) {
            // Swimming: buoyant sink by default, stroke upward while held —
            // fast enough to climb out of a one-tile lip at the surface.
            s.vy += (in.up ? -SWIM_UP : SWIM_SINK) * dt;
            s.vy = Math.max(-SWIM_MAX_RISE, Math.min(SWIM_MAX_SINK, s.vy));
            double ny = slideY(level, s.x, s.y, size, size, s.vy * dt);
            if (ny != s.y + s.vy * dt) s.vy = 0; // hit floor or ceiling
            s.y = ny;
            moving = moving || in.up || in.down;
        } else if (sideScroll) {
            boolean grounded = onGround(level, s.x, s.y, size, size);
            if (grounded && s.vy >= 0) {
                s.vy = 0;
                if (in.up) {
                    s.vy = -JUMP;
                    s.stamina = Math.max(0, s.stamina - JUMP_COST);
                }
            } else {
                s.vy += GRAVITY * dt;
            }
            double dy = s.vy * dt;
            double ny = slideY(level, s.x, s.y, size, size, dy);
            if (ny != s.y + dy) s.vy = 0; // landed, or bonked a ceiling
            s.y = ny;
        } else {
            double dy = 0;
            if (in.up) dy -= speed * dt;
            if (in.down) dy += speed * dt;
            s.y = slideY(level, s.x, s.y, size, size, dy);
            moving = moving || dy != 0;
        }

        s.x = slideX(level, s.x, s.y, size, size, dx);
        clampToLevel(s, level, size);
        s.moving = moving;

        // Resource flow: sprint drains, everything else recovers.
        if (sprinting) {
            s.stamina -= SPRINT_COST * dt;
        } else {
            s.stamina += STAMINA_REGEN * dt;
        }
        s.stamina = Math.max(0, Math.min(PlayerState.MAX_STAMINA, s.stamina));
        s.mana = Math.max(0, Math.min(PlayerState.MAX_MANA, s.mana + MANA_REGEN * dt));
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
