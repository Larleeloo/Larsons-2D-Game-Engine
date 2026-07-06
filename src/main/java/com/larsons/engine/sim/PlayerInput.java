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

    public PlayerInput() {}

    public PlayerInput(boolean left, boolean right, boolean up, boolean down, int seq) {
        this.left = left;
        this.right = right;
        this.up = up;
        this.down = down;
        this.seq = seq;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", seq);
        m.put("l", left);
        m.put("r", right);
        m.put("u", up);
        m.put("d", down);
        return m;
    }

    public static PlayerInput fromMap(Map<String, Object> m) {
        PlayerInput in = new PlayerInput();
        in.seq = m.get("s") instanceof Number n ? n.intValue() : 0;
        in.left = Boolean.TRUE.equals(m.get("l"));
        in.right = Boolean.TRUE.equals(m.get("r"));
        in.up = Boolean.TRUE.equals(m.get("u"));
        in.down = Boolean.TRUE.equals(m.get("d"));
        return in;
    }
}
