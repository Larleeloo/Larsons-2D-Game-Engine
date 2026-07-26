package com.larsons.engine.entity;

import com.larsons.engine.graphics.Facing;
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

    /** Speed a thrown stack leaves the player's hands at, world px/sec. */
    public static final double THROW_SPEED = 170;
    /** Upward lob added only where gravity can bring the stack back down. */
    public static final double THROW_LOB = 180;

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

    /**
     * Toss appropriately for the level's format: a gravity world keeps the
     * kick as given (drops arc up and bounce), while on a plane the same
     * energy becomes a skid in some direction across the floor. Callers pass
     * the side-scrolling kick they always did; without this, an upward kick in
     * a top-down level sent every drop sliding north.
     *
     * <p>The scatter direction comes from the entity id (the golden angle, so
     * consecutive drops fan out) rather than a random draw — the multiplayer
     * server and its clients must agree on where a drop landed.
     */
    public DroppedItem toss(double kickX, double kickY, boolean gravityOn) {
        if (gravityOn) return toss(kickX, kickY);
        double speed = Math.hypot(kickX, kickY) * PLANE_KICK_FACTOR;
        double angle = id * GOLDEN_ANGLE;
        return toss(Math.cos(angle) * speed, Math.sin(angle) * speed);
    }

    /**
     * Throw this drop out in front of a player facing {@code facing}. A
     * side-scroller lobs it forward off their back, the way it always has. On
     * a plane there are eight ways to be facing and no "up" to lob toward, so
     * the stack skids out along the direction the player is actually looking —
     * dropping something while walking north used to fling it due east.
     */
    public DroppedItem tossForward(Facing facing, boolean gravityOn) {
        if (gravityOn) {
            boolean left = facing != null && facing.facingLeft();
            return toss(left ? -THROW_SPEED : THROW_SPEED, -THROW_LOB);
        }
        Facing dir = facing == null ? Facing.SOUTH : facing;
        double len = Math.max(0.001, Math.hypot(dir.dx(), dir.dy()));
        return toss(dir.dx() / len * THROW_SPEED, dir.dy() / len * THROW_SPEED);
    }

    /** Plan-view scatter keeps part of the arc's energy (no fall to add to it). */
    private static final double PLANE_KICK_FACTOR = 0.5;

    /** Golden angle in radians: successive ids scatter without clustering. */
    private static final double GOLDEN_ANGLE = 2.39996322972865332;

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
