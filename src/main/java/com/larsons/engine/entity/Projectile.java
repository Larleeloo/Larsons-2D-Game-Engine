package com.larsons.engine.entity;

import com.larsons.engine.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A projectile in flight — an arrow, thrown rock, or magic bolt. Ported from
 * the Side-Scroller engine's {@code ProjectileEntity}, reduced to pure
 * simulation state in this engine's style: the {@link ProjectileDef} (via its
 * key) carries all behaviour, rendering is the scene's job, and
 * {@link #step} is a deterministic function of (state, level, dt) so the same
 * code runs in single-player and on the authoritative multiplayer server
 * (clients just render what snapshots say).
 */
public final class Projectile {

    /** Matches the gravity constant mobs and dropped items fall with. */
    private static final double GRAVITY = 1500;

    public final int id;
    public final ProjectileDef def;
    /** Player id that fired it (its owner is never hit by it). */
    public final int ownerId;
    public double x, y;        // centre, world px
    public double vx, vy;
    /** Damage dealt on hit: the firing weapon's, or the def's default. */
    public double damage;

    private double life;
    private boolean dead;

    public Projectile(int id, ProjectileDef def, int ownerId,
                      double x, double y, double vx, double vy) {
        this.id = id;
        this.def = def;
        this.ownerId = ownerId;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.damage = def.damage();
        this.life = def.lifetime();
    }

    public boolean dead() {
        return dead;
    }

    public void kill() {
        dead = true;
    }

    /**
     * Advance one tick. Returns {@code true} if the projectile just impacted
     * terrain (a solid tile, the level edge) or timed out — the caller
     * (the {@link com.larsons.engine.world.World}) resolves what happens next
     * (explosion, recoverable drop) before removing it.
     */
    public boolean step(Level level, boolean gravityOn, double dt) {
        if (dead) return false;
        if ((life -= dt) <= 0) {
            dead = true;
            return true;
        }
        if (gravityOn && def.gravityFactor() > 0) {
            vy += GRAVITY * def.gravityFactor() * dt;
        }
        x += vx * dt;
        y += vy * dt;

        double ts = level.tileSize;
        if (level.solidAt((int) Math.floor(x / ts), (int) Math.floor(y / ts))) {
            dead = true;
            return true;
        }
        if (x < 0 || y < 0 || x > level.width * ts || y > level.height * ts) {
            dead = true;
            return true;
        }
        return false;
    }

    // --- wire form (what snapshots carry) --------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", def.key());
        m.put("x", x);
        m.put("y", y);
        m.put("vx", vx);
        m.put("vy", vy);
        return m;
    }
}
