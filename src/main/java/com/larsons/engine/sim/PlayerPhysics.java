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
 * <p>Side-scroll with gravity gets falling/jumping against solid tiles; every
 * other configuration moves freely on both axes. Movement is always clamped to
 * the level bounds.
 */
public final class PlayerPhysics {

    public static final double SPEED = 220;     // px/sec
    public static final double GRAVITY = 1500;  // px/sec^2
    public static final double JUMP = 560;      // px/sec

    // Swimming (the player's centre is inside a liquid block).
    public static final double SWIM_SPEED_FACTOR = 0.6;  // drag on horizontal speed
    public static final double SWIM_UP = 900;            // upward accel while holding up
    public static final double SWIM_SINK = 350;          // passive sink accel
    public static final double SWIM_MAX_RISE = 200;      // px/sec
    public static final double SWIM_MAX_SINK = 140;      // px/sec

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
        double speed = inLiquid ? SPEED * SWIM_SPEED_FACTOR : SPEED;

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
            s.y += s.vy * dt;
            if (s.vy > 0 && isSolid(level, s.x + size / 2.0, s.y + size, ts)) {
                s.y = Math.floor((s.y + size) / ts) * ts - size;
                s.vy = 0;
            }
            moving = moving || in.up || in.down;
        } else if (sideScroll) {
            boolean grounded = isSolid(level, s.x + size / 2.0, s.y + size + 1, ts);
            if (grounded && s.vy >= 0) {
                s.vy = 0;
                if (in.up) s.vy = -JUMP;
            } else {
                s.vy += GRAVITY * dt;
            }
            s.y += s.vy * dt;
            if (s.vy > 0 && isSolid(level, s.x + size / 2.0, s.y + size, ts)) {
                s.y = Math.floor((s.y + size) / ts) * ts - size;
                s.vy = 0;
            }
        } else {
            double dy = 0;
            if (in.up) dy -= speed * dt;
            if (in.down) dy += speed * dt;
            s.y += dy;
            moving = moving || dy != 0;
        }

        s.x += dx;
        clampToLevel(s, level, size);
        s.moving = moving;
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
