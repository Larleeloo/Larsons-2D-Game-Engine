package com.larsons.engine.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tick's worth of movement intent — the "input command" half of the
 * networking model (requirement #3: input commands in, state snapshots out).
 * The same value drives {@link PlayerPhysics} locally (prediction) and on the
 * server (authority), which is what keeps the two simulations in agreement.
 */
public final class PlayerInput {

    public boolean left, right, up, down;
    /** Client-assigned sequence number, echoed back in snapshots. */
    public int seq;

    /**
     * Attack intent this tick (a mouse click, edge-triggered by the sender)
     * aimed at ({@link #aimX}, {@link #aimY}) in world coordinates. The server
     * resolves what the swing hits — mobs in reach for melee, a projectile for
     * a held ranged weapon — so clients can't fabricate damage. Absent on the
     * wire when false, keeping old messages compatible.
     */
    public boolean attack;
    public double aimX, aimY;

    /**
     * The hotbar slot the player has selected. Riding the input command keeps
     * the server's view of "what am I holding" current, which is what melee
     * weapon damage, ranged shots, and block-place consumption resolve against.
     */
    public int selected;

    public PlayerInput() {}

    public PlayerInput(boolean left, boolean right, boolean up, boolean down, int seq) {
        this.left = left;
        this.right = right;
        this.up = up;
        this.down = down;
        this.seq = seq;
    }

    public PlayerInput attackAt(double aimX, double aimY) {
        this.attack = true;
        this.aimX = aimX;
        this.aimY = aimY;
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", seq);
        m.put("l", left);
        m.put("r", right);
        m.put("u", up);
        m.put("d", down);
        if (selected != 0) m.put("h", selected);
        if (attack) {
            m.put("a", true);
            m.put("ax", aimX);
            m.put("ay", aimY);
        }
        return m;
    }

    public static PlayerInput fromMap(Map<String, Object> m) {
        PlayerInput in = new PlayerInput();
        in.seq = m.get("s") instanceof Number n ? n.intValue() : 0;
        in.left = Boolean.TRUE.equals(m.get("l"));
        in.right = Boolean.TRUE.equals(m.get("r"));
        in.up = Boolean.TRUE.equals(m.get("u"));
        in.down = Boolean.TRUE.equals(m.get("d"));
        in.selected = m.get("h") instanceof Number n ? n.intValue() : 0;
        in.attack = Boolean.TRUE.equals(m.get("a"));
        in.aimX = m.get("ax") instanceof Number n ? n.doubleValue() : 0;
        in.aimY = m.get("ay") instanceof Number n ? n.doubleValue() : 0;
        return in;
    }
}
