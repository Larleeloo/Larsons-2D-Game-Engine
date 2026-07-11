package com.larsons.engine.entity;

import com.larsons.engine.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An item lying in the world, waiting to be picked up — mined block drops,
 * mob drops, and items painted into levels. Ported from the Side-Scroller
 * engine's {@code ItemEntity}, including its little bounce physics (items
 * pop out and settle) from the loot-game scene.
 */
public final class DroppedItem {

    private static final double GRAVITY = 1500;
    private static final double BOUNCE = 0.45;   // energy kept per bounce
    private static final double FRICTION = 6.0;  // horizontal damping per second

    public final int id;
    public final String key;   // ItemRegistry key
    public int count;
    public double x, y;        // top-left of a small square (world px)
    public double vx, vy;
    public static final double SIZE = 14;

    /** Seconds before it may be picked up (so mined drops don't teleport in). */
    public double pickupDelay = 0.4;

    /** Seconds since the drop spawned; drives the perspective idle animation. */
    public double age;

    public DroppedItem(int id, String key, int count, double x, double y) {
        this.id = id;
        this.key = key;
        this.count = Math.max(1, count);
        this.x = x;
        this.y = y;
    }

    /** Toss with a small random-ish kick, like blocks breaking in the original. */
    public DroppedItem toss(double kickX, double kickY) {
        this.vx = kickX;
        this.vy = kickY;
        return this;
    }

    public void step(Level level, boolean gravityOn, double dt) {
        if (pickupDelay > 0) pickupDelay -= dt;
        age += dt;
        double ts = level.tileSize;
        x += vx * dt;
        vx -= vx * Math.min(1, FRICTION * dt);
        if (gravityOn) {
            // Side-scroll: drops arc under gravity and bounce to rest.
            vy += GRAVITY * dt;
            y += vy * dt;
            int col = (int) Math.floor((x + SIZE / 2) / ts);
            int rowBelow = (int) Math.floor((y + SIZE) / ts);
            if (vy > 0 && level.solidAt(col, rowBelow)) {
                y = Math.floor((y + SIZE) / ts) * ts - SIZE;
                vy = Math.abs(vy) > 60 ? -vy * BOUNCE : 0;
            }
        } else {
            // Top-down / isometric: the toss becomes a planar scatter — drops
            // skid outward across the floor and slide to rest (renderers add
            // the hovering bob + shadow for these perspectives).
            y += vy * dt;
            vy -= vy * Math.min(1, FRICTION * dt);
        }
        x = Math.max(0, Math.min(x, level.width * (double) level.tileSize - SIZE));
        y = Math.max(0, Math.min(y, level.height * (double) level.tileSize - SIZE));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", key);
        m.put("n", count);
        m.put("x", x);
        m.put("y", y);
        return m;
    }
}
