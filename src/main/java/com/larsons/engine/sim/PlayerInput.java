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

    /**
     * Movement intent, held. These are <em>directions</em> and nothing else:
     * {@link #up} strokes upward while swimming, climbs while flying, and
     * walks north in a plan-view level — it never jumps. Jumping is
     * {@link #jump}.
     */
    public boolean left, right, up, down;
    /** Sprint intent (Shift held): faster while the player has stamina. */
    public boolean sprint;
    /**
     * The jump key (Space) <em>freshly pressed</em> this tick — edge-triggered
     * by the sender, unlike the level-triggered {@link #up}, so holding it
     * doesn't burn every air jump at once. This is the only thing that
     * launches a jump, in every level format. Absent on the wire when false,
     * keeping old messages compatible.
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
     * A melee move requested this tick beyond the plain attack — the parry,
     * lunge and dash keys — as the move's key ({@code ""} = none). Like
     * {@link #attack} it is edge-triggered by the sender and validated by the
     * server against what the player is actually holding, so a client can't
     * lunge with a loaf of bread or ignore a cooldown. Absent on the wire when
     * empty, keeping old messages compatible.
     */
    public String melee = "";

    /**
     * The guard key ("shield ready") <em>held</em>, level-triggered like
     * {@link #sprint}: the stance stays up for as long as this does.
     */
    public boolean shield;

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

    /**
     * Which layer of that cell the player is aiming at.
     *
     * <p>Rides the command for the same reason {@link #yaw} does: the aim is
     * derived from a camera the server deliberately never sees (C10), and a
     * crosshair on the side of a wall names one <em>box</em> of a column rather
     * than the column. Without it the server re-derived "the top of the stack"
     * and mined a different block from the one the player was pointing at.
     */
    public int mineLayer;

    /**
     * The camera heading the player was looking along when they pressed these
     * keys, in radians — zero in a level that does not turn.
     *
     * <p><b>This rides the input command because it has to.</b> The keys above
     * are screen directions: "up" means away from the viewer, and where that is
     * in the world depends on where the camera was pointing. The camera is
     * per-client view state and is deliberately never networked (C10), so the
     * server has no camera to ask — which leaves exactly one place the heading
     * can come from, and it is here, travelling with the tick it belongs to.
     *
     * <p>The alternative is worse than it looks. If prediction rotated the
     * input and the server did not, every step would disagree; if the server
     * used the client's <em>current</em> heading instead of the one this tick
     * was pressed at, then a player turning the camera while running would have
     * their in-flight inputs re-interpreted underneath them, and the further
     * behind the connection was the more they would rubber-band. The yaw is
     * part of what the player pressed, not part of where they are now.
     */
    public double yaw;

    public PlayerInput() {}

    public PlayerInput(boolean left, boolean right, boolean up, boolean down, int seq) {
        this.left = left;
        this.right = right;
        this.up = up;
        this.down = down;
        this.seq = seq;
    }

    /**
     * The world direction these keys mean, as a unit vector — {@link #moveX}
     * across and {@link #moveY} down the world plane.
     *
     * <p>The keys are a screen intent; this is what the player was actually
     * asking for. Two things happen here and both used to happen in
     * {@link PlayerPhysics}:
     *
     * <ul>
     *   <li><b>The diagonal is normalised.</b> Pressing two keys used to travel
     *       √2 times as fast as pressing one, and the fix was a
     *       {@code speed *= √0.5} beside the branch that noticed. A unit vector
     *       says the same thing once, for every direction rather than for the
     *       four diagonals.</li>
     *   <li><b>The heading is applied</b>, by rotating the screen intent into
     *       the world — the inverse of what the camera does to a world point on
     *       its way to the screen. Press "up" with the camera looking east and
     *       the character walks east, which is away from the viewer, which is
     *       what "up" means.</li>
     * </ul>
     *
     * <p>Only the plan views ask. A side-scroller reads the keys as the
     * separate things they are there — left and right walk, up and down swim
     * and climb — and has no heading to turn by in any case.
     */
    public double moveX() {
        double len = intentLength();
        if (len == 0) return 0;
        return (intentX() * cosYaw() - intentY() * sinYaw()) / len;
    }

    public double moveY() {
        double len = intentLength();
        if (len == 0) return 0;
        return (intentX() * sinYaw() + intentY() * cosYaw()) / len;
    }

    private double intentX() { return (right ? 1 : 0) - (left ? 1 : 0); }

    private double intentY() { return (down ? 1 : 0) - (up ? 1 : 0); }

    private double intentLength() { return Math.hypot(intentX(), intentY()); }

    /**
     * The heading's cosine and sine, rounded to the exact value at a compass
     * point — {@code Math.cos(Math.PI / 2)} is 6.1e-17, not zero, and the
     * camera rests at those four headings for nearly the whole of play.
     * Without this, walking north with the camera looking east would creep
     * sideways by 6e-17 of a step per tick: harmless in a position and not
     * harmless in a test that asks whether pressing one key moves along one
     * axis. {@code Camera.setYaw} does the same thing for the same reason, and
     * the two are deliberately not shared — this package simulates and that one
     * draws, and a heading is the only thing they have in common.
     */
    private double cosYaw() { return snap(Math.cos(yaw)); }

    private double sinYaw() { return snap(Math.sin(yaw)); }

    private static double snap(double v) {
        if (Math.abs(v) < 1e-12) return 0.0;
        if (Math.abs(v - 1.0) < 1e-12) return 1.0;
        if (Math.abs(v + 1.0) < 1e-12) return -1.0;
        return v;
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
            m.put("ml", mineLayer);
        }
        if (!melee.isEmpty()) m.put("ml", melee);
        if (shield) m.put("sd", true);
        // Absent on the wire in a level that does not turn, which is every
        // side-scroller and every plan view at rest — so the common case costs
        // nothing and an older client's messages still mean what they said.
        if (yaw != 0) m.put("y", yaw);
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
        in.mineLayer = m.get("ml") instanceof Number n ? n.intValue() : 0;
        in.melee = m.get("ml") instanceof String s ? s : "";
        in.shield = Boolean.TRUE.equals(m.get("sd"));
        in.yaw = m.get("y") instanceof Number n ? n.doubleValue() : 0;
        return in;
    }
}
