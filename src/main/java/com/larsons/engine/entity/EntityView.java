package com.larsons.engine.entity;

import java.util.Map;

/**
 * The render-side view of a replicated entity: what a multiplayer client
 * knows about a mob or dropped item from the latest snapshot. Mirrors
 * {@link Mob#toMap()} / {@link DroppedItem#toMap()} — clients don't simulate
 * these (the server is authoritative); they just draw them.
 */
public final class EntityView {

    public final int id;
    public final String key;
    public final double x, y;
    public final boolean facingLeft;
    public final double health;
    public final int aiState;   // Mob.AIState ordinal (drives hurt/dead tint)
    public final int count;     // dropped-item stack size

    private EntityView(int id, String key, double x, double y, boolean facingLeft,
                       double health, int aiState, int count) {
        this.id = id;
        this.key = key;
        this.x = x;
        this.y = y;
        this.facingLeft = facingLeft;
        this.health = health;
        this.aiState = aiState;
        this.count = count;
    }

    public static EntityView fromMap(Map<String, Object> m) {
        return new EntityView(
                num(m.get("id")),
                m.get("k") instanceof String s ? s : "",
                dbl(m.get("x")), dbl(m.get("y")),
                Boolean.TRUE.equals(m.get("f")),
                dbl(m.get("h")),
                num(m.get("s")),
                Math.max(1, num(m.get("n"))));
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
