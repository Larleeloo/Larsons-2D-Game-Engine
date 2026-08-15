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

    /**
     * Where this body stood one fixed step ago, so a frame can be drawn between
     * two steps rather than on top of the latest one — see
     * {@link StepInterpolation} for what that fixes and why the simulation is
     * unaffected by it. Never read by the simulation, never replicated: a
     * renderer's memory of the step before, kept on the body it belongs to.
     */
    public double prevX, prevY, prevZ;
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

    /**
     * How large this character is <em>drawn</em>, in world pixels — and, right
     * below it, how much floor they actually occupy. Two numbers because they
     * answer different questions: see {@link ActorSize}.
     *
     * <p>Zero means "whatever the game type says", which is what every body
     * built before characters had sizes of their own holds, and what
     * {@link #spriteSize(double)} resolves against the profile's default. The
     * simulation reads {@link #hitSize(double)} and nothing else — a sprite
     * cannot push anybody around — so a character can be redrawn at four times
     * the size without a single collision changing.
     */
    public double spriteSize;
    public double hitSize;

    /** {@link #spriteSize}, falling back to the game type's player size. */
    public double spriteSize(double profileSize) {
        return spriteSize > 0 ? spriteSize : profileSize;
    }

    /** {@link #hitSize}, falling back to the game type's player size. */
    public double hitSize(double profileSize) {
        return hitSize > 0 ? hitSize : profileSize;
    }

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

    /**
     * The highest this body has been since it was last on the ground — how far
     * a landing fell, which is the only thing fall damage needs and is not
     * something a position alone can answer.
     *
     * <p>Not networked and not copied: it describes a fall in progress, the
     * server computes the damage that matters, and a client that predicts a
     * landing slightly early reconciles on health like it does on everything
     * else.
     */
    public transient double fallPeakZ;

    /**
     * How tall one block is in this body's level, in world units — carried on
     * the state because {@link PlayerPhysics} measures a fall in blocks and a
     * level's tile size is not a constant.
     */
    public double blockHeight = com.larsons.engine.level.Level.BLOCK_HEIGHT * 32;

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

    // --- melee stance -----------------------------------------------------------
    // Mirrored onto the state each tick from the fighter's MeleeState (see
    // com.larsons.engine.combat.Melee#step) so that everything which needs to
    // know "is this player mid-swing / guarding / untouchable" — the damage
    // funnel below, the physics step, the renderer, the snapshot — reads one
    // place instead of reaching into the combat machine.

    /** The melee move being performed ({@code ""} = none); the animation state. */
    public String meleeAction = "";
    /** How far through that move, 0..1 — picks the frame of its sheet. */
    public double meleeProgress;
    /**
     * The item key in hand, which picks the wielded sheets and the weapon's
     * own sounds ({@code ""} = empty-handed). Replicated, so other players see
     * you swinging the sword you are actually holding.
     */
    public String heldKey = "";
    /** True while a raised guard is up: {@link #guardFraction} is soaked. */
    public boolean guarding;
    /** Fraction of an incoming blow a raised guard soaks, 0..1. */
    public double guardFraction;
    /** True while a parry window is open: a blow that lands in it is caught. */
    public boolean parrying;
    /** True while a dash's invulnerability frames run. */
    public boolean invulnerable;
    /**
     * Running total of blows this player's guard or parry has stopped.
     * Replicated, so the clang is heard online (where the server is the one
     * that resolved it) by comparing against the previous snapshot.
     */
    public int guardHits;

    /**
     * A committed melee burst — a lunge or a dash — as a velocity and the
     * seconds left on it. While it lasts {@link PlayerPhysics} moves the
     * player along it instead of by their steering, colliding as usual, which
     * is what makes the move a commitment rather than a speed boost.
     */
    public double dashVx, dashVy;
    public double dashTime;

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
     *
     * <p>This is the <em>unconditional</em> funnel — standing in lava, drowning,
     * anything a raised shield is no help against. A blow that a fighter can
     * actually do something about goes through {@link #takeBlow} instead.
     */
    public void hurt(double amount) {
        if (amount <= 0) return;
        health -= amount * (1.0 - Math.max(0, Math.min(1, ultDamageResist)));
    }

    /**
     * Take a <em>blow</em> — a mob's strike, a projectile, a blast, another
     * player's swing — which the melee stance gets a say in:
     *
     * <ol>
     *   <li>a dash's invulnerability frames avoid it outright;</li>
     *   <li>an open parry window catches it: no damage, and whoever threw it
     *       is left staggered (the attacker checks {@link #parrying});</li>
     *   <li>a raised guard soaks {@link #guardFraction} of it;</li>
     *   <li>whatever is left goes through {@link #hurt}.</li>
     * </ol>
     *
     * @return the damage that actually landed (0 when it was avoided)
     */
    public double takeBlow(double amount) {
        if (amount <= 0) return 0;
        if (invulnerable) return 0;
        if (parrying) {
            guardHits++;
            return 0;
        }
        double after = amount;
        if (guarding) {
            after *= 1.0 - Math.max(0, Math.min(1, guardFraction));
            guardHits++;
        }
        double before = health;
        hurt(after);
        return before - health;
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
        s.meleeAction = meleeAction;
        s.meleeProgress = meleeProgress;
        s.heldKey = heldKey;
        s.guarding = guarding;
        s.guardFraction = guardFraction;
        s.guardHits = guardHits;
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
        // The melee stance, so other players see the swing and hear the clang.
        // Absent while nothing is happening, like every other extra here.
        if (!meleeAction.isEmpty()) {
            m.put("ma", meleeAction);
            m.put("mg", meleeProgress);
        }
        if (!heldKey.isEmpty()) m.put("hk", heldKey);
        if (guarding) m.put("gu", true);
        if (guardHits > 0) m.put("gh", guardHits);
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
        s.meleeAction = m.get("ma") instanceof String a ? a : "";
        s.meleeProgress = dbl(m.get("mg"));
        s.heldKey = m.get("hk") instanceof String h ? h : "";
        s.guarding = Boolean.TRUE.equals(m.get("gu"));
        s.guardHits = num(m.get("gh"), 0);
        return s;
    }

    // --- render interpolation (see StepInterpolation) ----------------------------

    /**
     * Remember where this body is, immediately before a fixed step moves it.
     * Called by whoever owns the step — the scene that is going to draw the
     * result — rather than by {@link PlayerPhysics}, which is shared with a
     * headless server that draws nothing.
     */
    public void beginStep() {
        prevX = x;
        prevY = y;
        prevZ = z;
    }

    /** Where to draw this body, {@code alpha} of the way through the last step. */
    public double renderX(double alpha) { return StepInterpolation.at(prevX, x, alpha); }

    public double renderY(double alpha) { return StepInterpolation.at(prevY, y, alpha); }

    public double renderZ(double alpha) { return StepInterpolation.at(prevZ, z, alpha); }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
