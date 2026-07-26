package com.larsons.engine.entity;

import com.larsons.engine.graphics.Facing;

import java.util.Map;

/**
 * The render-side view of a replicated entity: what a multiplayer client
 * knows about a mob, dropped item, projectile, or vehicle from the latest
 * snapshot. Mirrors {@link Mob#toMap()} / {@link DroppedItem#toMap()} /
 * {@link Projectile#toMap()} / {@link Vehicle#toMap()} — clients don't
 * simulate these (the server is authoritative); they just draw them.
 */
public final class EntityView {

    public final int id;
    public final String key;
    public final double x, y;
    public final boolean facingLeft;
    public final double health;
    public final int aiState;   // Mob.AIState ordinal (drives hurt/dead tint)
    public final int count;     // dropped-item stack size
    public final double vx, vy; // projectile velocity (drives sprite rotation)
    public final int status;    // mob status bits (burn/chill/poison/shield tint)
    public final int rider;     // vehicle rider player id, or -1 when riderless
    /** The compass direction a mob faces, which picks its directional sprite. */
    public final Facing facing;

    private EntityView(int id, String key, double x, double y, boolean facingLeft,
                       double health, int aiState, int count, double vx, double vy,
                       int status, int rider, Facing facing) {
        this.id = id;
        this.key = key;
        this.x = x;
        this.y = y;
        this.facingLeft = facingLeft;
        this.facing = facing;
        this.health = health;
        this.aiState = aiState;
        this.count = count;
        this.vx = vx;
        this.vy = vy;
        this.status = status;
        this.rider = rider;
    }

    public static EntityView fromMap(Map<String, Object> m) {
        boolean facingLeft = Boolean.TRUE.equals(m.get("f"));
        Facing dir = m.get("d") instanceof String d ? Facing.byKey(d) : null;
        return new EntityView(
                num(m.get("id"), 0),
                m.get("k") instanceof String s ? s : "",
                dbl(m.get("x")), dbl(m.get("y")),
                facingLeft,
                dbl(m.get("h")),
                num(m.get("s"), 0),
                Math.max(1, num(m.get("n"), 0)),
                dbl(m.get("vx")), dbl(m.get("vy")),
                num(m.get("e"), 0),
                num(m.get("p"), -1),
                // Pre-directional snapshots carry only the left/right flag.
                dir != null ? dir : Facing.side(facingLeft));
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
