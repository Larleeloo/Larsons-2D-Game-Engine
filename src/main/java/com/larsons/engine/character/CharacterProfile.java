package com.larsons.engine.character;

import com.larsons.engine.sim.ActorSize;
import com.larsons.engine.sim.PlayerState;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A playable character: a skin plus the traits that make them feel different
 * to control — how fast they move, whether they can sprint or double jump, how
 * high they jump, how much health/mana/stamina they carry, and which
 * {@link Ultimate} they bring.
 *
 * <p>Profiles are created the same way a custom block or item is: the creative
 * palette's Characters category leads with a "+ New Character" entry whose
 * form edits every field below, and Create registers it and writes it into the
 * game type's {@code characters.json} ({@link CharacterStore}). Each level then
 * chooses which of them players may pick from at its start
 * ({@code Level.characters}).
 *
 * <p>Traits are <em>multipliers or counts</em> rather than absolute physics
 * values, so a profile stays sensible no matter what the game type's own
 * movement settings are: {@link #speed} scales the base walk speed,
 * {@link #jumpHeight} scales jump velocity, and the rest are plain caps.
 */
public class CharacterProfile {

    /** The key of the built-in default character (never persisted). */
    public static final String DEFAULT_KEY = "default";

    public String key = DEFAULT_KEY;
    public String name = "Adventurer";

    // Skin: the two colours the pre-generated directional art is drawn from.
    // A texture pack (or the creative texture dialog) can replace it entirely
    // with real sheets under character/<key>/<state>[/<facing>].
    public Color body = new Color(70, 130, 220);
    public Color accent = new Color(245, 210, 170);

    /**
     * How large this character is drawn, in blocks, and how much floor they
     * occupy — independent numbers, both authored here rather than derived
     * from the game type's tile size. See {@link ActorSize} for why they are
     * two numbers and why blocks rather than pixels.
     *
     * <p>A creator bringing their own art sets {@link #spriteScale} until the
     * character looks the size they drew them, and leaves {@link #hitboxScale}
     * wherever the character should <em>fit</em> — a hero drawn three blocks
     * tall still walks down a one-block corridor if their footprint says so.
     */
    public double spriteScale = ActorSize.DEFAULT_TILES;
    public double hitboxScale = ActorSize.DEFAULT_TILES;

    // Movement traits.
    /** Walk/run speed multiplier. */
    public double speed = 1.0;
    /** Whether Shift sprints at all. */
    public boolean sprintEnabled = true;
    /** Mid-air jumps: 1 is the classic double jump, 0 grounds them. */
    public int airJumps = 1;
    /** Jump velocity multiplier (side-scroll jumps and plan-view hops alike). */
    public double jumpHeight = 1.0;

    // Resource pools.
    public double maxHealth = PlayerState.MAX_HEALTH;
    public double maxMana = PlayerState.MAX_MANA;
    public double maxStamina = PlayerState.MAX_STAMINA;

    /** Which {@link Ultimate} this character brings ({@code ""} = none). */
    public String ultimateKey = Ultimates.NONE;
    /** Ultimates can be switched off per profile without losing the choice. */
    public boolean ultimateEnabled = true;

    public CharacterProfile() {}

    public CharacterProfile(String key, String name) {
        this.key = key;
        this.name = name;
    }

    /** The engine's default character — what a level with no roster offers. */
    public static CharacterProfile defaultProfile() {
        CharacterProfile p = new CharacterProfile(DEFAULT_KEY, "Adventurer");
        p.ultimateKey = "overdrive";
        return p;
    }

    /** The ultimate this character actually fires, or {@code null}. */
    public Ultimate ultimate() {
        return ultimateEnabled ? Ultimates.get(ultimateKey) : null;
    }

    /**
     * Stamp this profile's traits onto a player's simulated state, sizing it
     * against a {@code tileSize}-pixel grid.
     *
     * <p>Pools are resized (and topped up), movement traits take effect on the
     * next physics step, the body takes its drawn and collided sizes, and the
     * ultimate meter starts empty.
     */
    public void applyTo(PlayerState s, double tileSize) {
        applyTo(s);
        if (s == null) return;
        s.spriteSize = ActorSize.pixels(spriteScale, tileSize);
        s.hitSize = ActorSize.pixels(hitboxScale, tileSize);
    }

    /**
     * Stamp everything but the sizes, for a caller with no grid to size
     * against — the body keeps whatever sizes it already had, which for a
     * fresh one means the game type's own.
     */
    public void applyTo(PlayerState s) {
        if (s == null) return;
        s.characterKey = key;
        s.maxHealth = Math.max(1, maxHealth);
        s.maxMana = Math.max(0, maxMana);
        s.maxStamina = Math.max(0, maxStamina);
        s.health = s.maxHealth;
        s.mana = s.maxMana;
        s.stamina = s.maxStamina;
        s.characterSpeed = Math.max(0.1, speed);
        s.sprintAllowed = sprintEnabled;
        s.airJumps = Math.max(0, airJumps);
        s.jumpFactor = Math.max(0.1, jumpHeight);
        s.ultimateKey = ultimateKey == null ? Ultimates.NONE : ultimateKey;
        s.ultimateEnabled = ultimateEnabled;
        s.ultCharge = 0;
        Ultimates.clearEffects(s);
    }

    public CharacterProfile copy() {
        return fromMap(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("body", hex(body));
        m.put("accent", hex(accent));
        // Absent on a profile that never moved them, so a character written
        // before sizes existed round-trips byte for byte.
        if (spriteScale != ActorSize.DEFAULT_TILES) m.put("spriteScale", spriteScale);
        if (hitboxScale != ActorSize.DEFAULT_TILES) m.put("hitboxScale", hitboxScale);
        m.put("speed", speed);
        m.put("sprint", sprintEnabled);
        m.put("airJumps", airJumps);
        m.put("jumpHeight", jumpHeight);
        m.put("maxHealth", maxHealth);
        m.put("maxMana", maxMana);
        m.put("maxStamina", maxStamina);
        m.put("ultimate", ultimateKey);
        m.put("ultimateEnabled", ultimateEnabled);
        return m;
    }

    public static CharacterProfile fromMap(Map<String, Object> m) {
        CharacterProfile p = new CharacterProfile();
        p.key = str(m.get("key"), p.key);
        p.name = str(m.get("name"), p.name);
        p.body = color(m.get("body"), p.body);
        p.accent = color(m.get("accent"), p.accent);
        p.spriteScale = dbl(m.get("spriteScale"), p.spriteScale);
        p.hitboxScale = dbl(m.get("hitboxScale"), p.hitboxScale);
        p.speed = dbl(m.get("speed"), p.speed);
        p.sprintEnabled = bool(m.get("sprint"), p.sprintEnabled);
        p.airJumps = num(m.get("airJumps"), p.airJumps);
        p.jumpHeight = dbl(m.get("jumpHeight"), p.jumpHeight);
        p.maxHealth = dbl(m.get("maxHealth"), p.maxHealth);
        p.maxMana = dbl(m.get("maxMana"), p.maxMana);
        p.maxStamina = dbl(m.get("maxStamina"), p.maxStamina);
        p.ultimateKey = str(m.get("ultimate"), p.ultimateKey);
        p.ultimateEnabled = bool(m.get("ultimateEnabled"), p.ultimateEnabled);
        p.normalize();
        return p;
    }

    /** Clamp traits into playable ranges after an edit or a load. */
    public void normalize() {
        if (key == null || key.isBlank()) key = DEFAULT_KEY;
        if (name == null || name.isBlank()) name = key;
        spriteScale = ActorSize.clampTiles(spriteScale);
        hitboxScale = ActorSize.clampTiles(hitboxScale);
        speed = clamp(speed, 0.25, 3.0);
        jumpHeight = clamp(jumpHeight, 0.25, 3.0);
        airJumps = (int) clamp(airJumps, 0, 8);
        maxHealth = clamp(maxHealth, 1, 1000);
        maxMana = clamp(maxMana, 0, 1000);
        maxStamina = clamp(maxStamina, 0, 1000);
        if (ultimateKey == null) ultimateKey = Ultimates.NONE;
        if (!ultimateKey.isEmpty() && Ultimates.get(ultimateKey) == null) {
            // An ability from a newer build: keep the character playable.
            ultimateKey = Ultimates.NONE;
        }
    }

    /** A one-line trait summary for menus and the palette tooltip. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (spriteScale != ActorSize.DEFAULT_TILES || hitboxScale != ActorSize.DEFAULT_TILES) {
            sb.append(ActorSize.label(spriteScale)).append(" tall");
            if (hitboxScale != spriteScale) {
                sb.append(" on ").append(ActorSize.label(hitboxScale)).append(" of floor");
            }
            sb.append(" · ");
        }
        sb.append((int) Math.round(speed * 100)).append("% speed");
        sb.append(" · ").append((int) Math.round(maxHealth)).append(" hp");
        if (maxMana > 0) sb.append(" · ").append((int) Math.round(maxMana)).append(" mana");
        if (maxStamina > 0) {
            sb.append(" · ").append((int) Math.round(maxStamina)).append(" stamina");
        }
        sb.append(airJumps > 0
                ? " · " + (airJumps == 1 ? "double jump" : airJumps + " air jumps")
                : " · no air jump");
        if (!sprintEnabled) sb.append(" · no sprint");
        Ultimate u = ultimate();
        if (u != null) sb.append(" · ").append(u.name());
        return sb.toString();
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color color(Object o, Color def) {
        if (!(o instanceof String s)) return def;
        try {
            String h = s.startsWith("#") ? s.substring(1) : s;
            return new Color(Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String str(Object o, String def) {
        return o instanceof String s && !s.isBlank() ? s : def;
    }

    private static boolean bool(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
