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

        double dx = 0;
        if (in.left) { dx -= SPEED * dt; s.facingLeft = true; }
        if (in.right) { dx += SPEED * dt; s.facingLeft = false; }
        boolean moving = dx != 0;

        boolean sideScroll = perspective == Perspective.SIDE_SCROLL && profile.gravityEnabled;
        if (sideScroll) {
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
            if (in.up) dy -= SPEED * dt;
            if (in.down) dy += SPEED * dt;
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
