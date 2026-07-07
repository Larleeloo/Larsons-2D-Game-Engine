package com.larsons.engine.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A player's simulated state: position (top-left, world pixels), vertical
 * velocity, and presentation flags. This is what the server snapshots and what
 * {@link PlayerPhysics} advances — shared by single-player, client prediction,
 * and the authoritative server so all three run the identical simulation.
 */
public final class PlayerState {

    public int id;
    public String name = "";
    public double x, y;
    public double vy;
    public boolean facingLeft;
    public boolean moving;
    /** Sequence number of the last {@link PlayerInput} the server applied. */
    public int lastSeq;

    /** Hit points; mobs subtract from this, food/potions restore it. */
    public static final double MAX_HEALTH = 100;
    public double health = MAX_HEALTH;

    public PlayerState() {}

    public PlayerState(int id, String name, double x, double y) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
    }

    public PlayerState copy() {
        PlayerState s = new PlayerState(id, name, x, y);
        s.vy = vy;
        s.facingLeft = facingLeft;
        s.moving = moving;
        s.lastSeq = lastSeq;
        s.health = health;
        return s;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("n", name);
        m.put("x", x);
        m.put("y", y);
        m.put("vy", vy);
        m.put("f", facingLeft);
        m.put("m", moving);
        m.put("q", lastSeq);
        m.put("h", health);
        return m;
    }

    public static PlayerState fromMap(Map<String, Object> m) {
        PlayerState s = new PlayerState();
        s.id = num(m.get("id"), 0);
        s.name = m.get("n") instanceof String str ? str : "";
        s.x = dbl(m.get("x"));
        s.y = dbl(m.get("y"));
        s.vy = dbl(m.get("vy"));
        s.facingLeft = Boolean.TRUE.equals(m.get("f"));
        s.moving = Boolean.TRUE.equals(m.get("m"));
        s.lastSeq = num(m.get("q"), 0);
        s.health = m.get("h") instanceof Number n ? n.doubleValue() : MAX_HEALTH;
        return s;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
