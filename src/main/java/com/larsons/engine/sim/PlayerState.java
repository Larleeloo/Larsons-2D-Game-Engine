package com.larsons.engine.sim;

import com.larsons.engine.graphics.Facing;

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
    /**
     * The compass direction the character faces, which picks the sprite that
     * draws them. A side-scroller only ever holds EAST or WEST (kept in step
     * with {@link #facingLeft}); the plan-view formats use all eight, so a
     * player walking north-east is drawn from behind rather than in profile.
     */
    public Facing facing = Facing.EAST;
    /** Sequence number of the last {@link PlayerInput} the server applied. */
    public int lastSeq;

    /** Hit points; mobs subtract from this, food/potions restore it. */
    public static final double MAX_HEALTH = 100;
    public double health = MAX_HEALTH;

    /** Stamina: sprinting and jumping spend it, standing still restores it. */
    public static final double MAX_STAMINA = 100;
    public double stamina = MAX_STAMINA;

    /** Mana: magic weapons (staves) spend it; it regenerates slowly. */
    public static final double MAX_MANA = 100;
    public double mana = MAX_MANA;

    // --- character profile (simulation-side; set once when a character is
    // chosen at level start — see com.larsons.engine.character.CharacterProfile).
    /** Which character profile is being played ({@code ""} = the default). */
    public String characterKey = "";
    /** This character's pools, which may differ from the engine defaults. */
    public double maxHealth = MAX_HEALTH;
    public double maxStamina = MAX_STAMINA;
    public double maxMana = MAX_MANA;
    /** Walk-speed multiplier from the character (relics scale on top of it). */
    public double characterSpeed = 1.0;
    /** Whether this character can sprint at all. */
    public boolean sprintAllowed = true;
    /** Mid-air jumps the character brings; 1 is the classic double jump. */
    public int airJumps = 1;
    /** Jump-velocity multiplier (side-scroll jumps and plan-view hops alike). */
    public double jumpFactor = 1.0;

    // --- ultimate ability (simulation-side; see character.Ultimates) --------------
    /** The ability this character brings ({@code ""} = none). */
    public String ultimateKey = "";
    /** Whether the profile has its ultimate switched on. */
    public boolean ultimateEnabled = true;
    /** Meter fill, 0..1; charges with time and damage dealt. */
    public double ultCharge;
    /** Seconds left on a sustained ultimate (0 = nothing running). */
    public double ultActive;
    /** Which ability is running, while {@link #ultActive} lasts. */
    public String ultActiveKey = "";
    /** Speed multiplier granted by the running ultimate. */
    public double ultSpeedFactor = 1.0;
    /** Outgoing-damage multiplier granted by the running ultimate. */
    public double ultDamageFactor = 1.0;
    /** Fraction of incoming damage the running ultimate absorbs, 0..1. */
    public double ultDamageResist;
    /** Whether the running ultimate makes sprinting and jumping free. */
    public boolean ultTireless;

    // Mid-air jump bookkeeping (simulation-side; not replicated in snapshots).
    // The character profile's airJumps (1 by default: the double jump) sets the
    // allowance; carried special items raise bonusAirJumps for
    // triple/quadruple/effectively-infinite jumping — scenes refresh it from
    // the inventory each tick (Inventory.airJumpBonus).
    public int airJumpsUsed;
    public int bonusAirJumps;

    /**
     * Plan-view hop: height above the ground in world px, and its vertical
     * speed. Top-down and isometric levels have no gravity axis on the tile
     * grid, so Space lifts the character along a separate Z axis — they are
     * drawn raised over their own shadow, and land back where they took off
     * (plus whatever steering happened mid-air). Side-scrolling levels leave
     * both at zero and jump with {@link #vy} as they always have.
     */
    public double z, vz;

    // Relic passives (simulation-side; not replicated). Refreshed from the
    // carried inventory each tick by Inventory.applyPassivesTo — on the
    // authoritative server from the server-side inventory, locally from the
    // mirrored one, so prediction and authority agree.
    /** Ground-speed multiplier (Hermes Boots). */
    public double speedFactor = 1.0;
    /** Falls gently under reduced gravity (Gravity Amulet). */
    public boolean slowFall;
    /** Holding jump/up flies (Aether Wings). */
    public boolean canFly;
    /** Extra dropped-item vacuum range in px (Magnet Charm). */
    public double pickupBonus;
    /** Extra melee damage added to swings (Power Gauntlet). */
    public double meleeBonus;

    /** Id of the vehicle this player is riding, or -1 (simulation-side; the
     *  replicated vehicle carries the rider id for everyone else to render). */
    public int riding = -1;

    public PlayerState() {}

    public PlayerState(int id, String name, double x, double y) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
    }

    /** Whether the character is off the ground on a plan-view hop. */
    public boolean hopping() {
        return z > 0.01;
    }

    /**
     * Take {@code amount} damage, after whatever a running ultimate absorbs.
     * Every place a player is hurt goes through here so defensive ultimates
     * (and anything added later) apply once, in one place.
     */
    public void hurt(double amount) {
        if (amount <= 0) return;
        health -= amount * (1.0 - Math.max(0, Math.min(1, ultDamageResist)));
    }

    /** Refill every pool to this character's caps (respawn, level start). */
    public void restore() {
        health = maxHealth;
        stamina = maxStamina;
        mana = maxMana;
    }

    public PlayerState copy() {
        PlayerState s = new PlayerState(id, name, x, y);
        s.vy = vy;
        s.facingLeft = facingLeft;
        s.facing = facing;
        s.moving = moving;
        s.lastSeq = lastSeq;
        s.health = health;
        s.stamina = stamina;
        s.mana = mana;
        s.z = z;
        s.vz = vz;
        s.characterKey = characterKey;
        s.maxHealth = maxHealth;
        s.maxStamina = maxStamina;
        s.maxMana = maxMana;
        s.ultimateKey = ultimateKey;
        s.ultimateEnabled = ultimateEnabled;
        s.ultCharge = ultCharge;
        s.ultActive = ultActive;
        s.ultActiveKey = ultActiveKey;
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
        m.put("st", stamina);
        m.put("mn", mana);
        // Presentation extras, absent when they carry no information, so old
        // clients and old save files keep parsing these messages.
        if (facing != Facing.EAST) m.put("d", facing.key());
        if (z > 0) m.put("z", z);
        if (maxHealth != MAX_HEALTH) m.put("mh", maxHealth);
        if (maxStamina != MAX_STAMINA) m.put("ms", maxStamina);
        if (maxMana != MAX_MANA) m.put("mm", maxMana);
        if (!characterKey.isEmpty()) m.put("ch", characterKey);
        if (ultCharge > 0) m.put("uc", ultCharge);
        if (ultActive > 0) {
            m.put("ua", ultActive);
            m.put("uk", ultActiveKey);
        }
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
        s.stamina = m.get("st") instanceof Number n ? n.doubleValue() : MAX_STAMINA;
        s.mana = m.get("mn") instanceof Number n ? n.doubleValue() : MAX_MANA;
        Facing dir = m.get("d") instanceof String d ? Facing.byKey(d) : null;
        // Pre-directional messages carry only the left/right flag.
        s.facing = dir != null ? dir : Facing.side(s.facingLeft);
        s.z = dbl(m.get("z"));
        s.maxHealth = m.get("mh") instanceof Number n ? n.doubleValue() : MAX_HEALTH;
        s.maxStamina = m.get("ms") instanceof Number n ? n.doubleValue() : MAX_STAMINA;
        s.maxMana = m.get("mm") instanceof Number n ? n.doubleValue() : MAX_MANA;
        s.characterKey = m.get("ch") instanceof String c ? c : "";
        s.ultCharge = dbl(m.get("uc"));
        s.ultActive = dbl(m.get("ua"));
        s.ultActiveKey = m.get("uk") instanceof String u ? u : "";
        return s;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
