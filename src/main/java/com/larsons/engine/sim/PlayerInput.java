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
    /** Sprint intent (Shift held): faster while the player has stamina. */
    public boolean sprint;
    /**
     * Jump key <em>freshly pressed</em> this tick (edge-triggered by the
     * sender, unlike the level-triggered {@link #up}). Mid-air jumps key off
     * this so holding the key doesn't burn every air jump at once. Absent on
     * the wire when false, keeping old messages compatible.
     */
    public boolean jump;
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

    /**
     * Hold-to-mine intent: while the player holds the mouse over a block in
     * reach, every input names the cell being mined. The server accumulates
     * mining progress against the block's hardness (sped up by a matching
     * held tool) exactly like offline play, so online blocks have the same
     * durability instead of breaking on a click. Absent on the wire when not
     * mining.
     */
    public boolean mine;
    public int mineCol, mineRow;

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
        if (sprint) m.put("sp", true); // absent on the wire when false
        if (jump) m.put("j", true);
        if (selected != 0) m.put("h", selected);
        if (attack) {
            m.put("a", true);
            m.put("ax", aimX);
            m.put("ay", aimY);
        }
        if (mine) {
            m.put("mi", true);
            m.put("mc", mineCol);
            m.put("mr", mineRow);
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
        in.sprint = Boolean.TRUE.equals(m.get("sp"));
        in.jump = Boolean.TRUE.equals(m.get("j"));
        in.selected = m.get("h") instanceof Number n ? n.intValue() : 0;
        in.attack = Boolean.TRUE.equals(m.get("a"));
        in.aimX = m.get("ax") instanceof Number n ? n.doubleValue() : 0;
        in.aimY = m.get("ay") instanceof Number n ? n.doubleValue() : 0;
        in.mine = Boolean.TRUE.equals(m.get("mi"));
        in.mineCol = m.get("mc") instanceof Number n ? n.intValue() : 0;
        in.mineRow = m.get("mr") instanceof Number n ? n.intValue() : 0;
        return in;
    }
}
