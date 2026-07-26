package com.larsons.engine.character;

import com.larsons.engine.sim.PlayerState;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in catalogue of {@link Ultimate} abilities, plus the meter
 * bookkeeping every scene shares.
 *
 * <p>Eight abilities cover the usual archetypes — a damage buff, a burst, a
 * defensive cooldown, a mobility strike, an artillery volley, a crowd-control
 * field, a sustain drain, and a terrain-wrecking stomp. A character profile
 * picks one (or none), and can have it switched off entirely
 * ({@link CharacterProfile#ultimateEnabled}).
 *
 * <p>Charging is deliberately in one place: {@link #charge} is called once per
 * tick with the elapsed time, and {@link #chargeFromDamage} whenever the
 * player lands a hit, so the offline world, the play-test, and the
 * authoritative server all fill the meter at the same rate.
 */
public final class Ultimates {

    /** The key meaning "this character has no ultimate". */
    public static final String NONE = "";

    private static final Map<String, Ultimate> BY_KEY = new LinkedHashMap<>();

    static {
        register(new Ultimate("overdrive", "Overdrive",
                "Move half again as fast and hit twice as hard for 8 seconds — stamina never runs dry.",
                Ultimate.Kind.OVERDRIVE, 95, 0.006, 8, 2.0, 0,
                new Color(255, 190, 60)));
        register(new Ultimate("nova_burst", "Nova Burst",
                "Detonate a ring of arcane force around you, damaging everything within four tiles.",
                Ultimate.Kind.NOVA, 85, 0.007, 0, 55, 150,
                new Color(140, 220, 255)));
        register(new Ultimate("bulwark", "Bulwark",
                "Shrug off 80% of incoming damage and regenerate steadily for 7 seconds.",
                Ultimate.Kind.BULWARK, 90, 0.005, 7, 0.2, 0,
                new Color(150, 220, 150)));
        register(new Ultimate("blink_strike", "Blink Strike",
                "Flash to where you are aiming, cutting down everything along the way.",
                Ultimate.Kind.BLINK_STRIKE, 80, 0.008, 0, 45, 260,
                new Color(200, 150, 255)));
        register(new Ultimate("meteor_volley", "Meteor Volley",
                "Call five meteors down onto the spot you are aiming at.",
                Ultimate.Kind.METEOR_VOLLEY, 100, 0.006, 0, 5, 300,
                new Color(255, 130, 70)));
        register(new Ultimate("time_dilation", "Time Dilation",
                "Freeze the tempo: everything within six tiles crawls for 6 seconds.",
                Ultimate.Kind.TIME_DILATION, 90, 0.005, 6, 1.0, 220,
                new Color(120, 200, 255)));
        register(new Ultimate("life_siphon", "Life Siphon",
                "Drain the life out of everything near you for 5 seconds, healing yourself.",
                Ultimate.Kind.LIFE_SIPHON, 95, 0.006, 5, 16, 170,
                new Color(220, 90, 140)));
        register(new Ultimate("earthshatter", "Earthshatter",
                "Slam the ground: a shockwave hurls enemies back and the terrain around you shatters.",
                Ultimate.Kind.EARTHSHATTER, 105, 0.005, 0, 40, 190,
                new Color(190, 150, 95)));
    }

    private Ultimates() {}

    private static void register(Ultimate u) {
        BY_KEY.put(u.key(), u);
    }

    /** Every ability, in catalogue order. */
    public static List<Ultimate> all() {
        return new ArrayList<>(BY_KEY.values());
    }

    /** The ability with this key, or {@code null} (including for {@link #NONE}). */
    public static Ultimate get(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** Ability keys with a leading "(none)" entry — what a profile form cycles. */
    public static String[] choices() {
        List<String> names = new ArrayList<>();
        names.add(noneLabel());
        for (Ultimate u : BY_KEY.values()) names.add(u.name());
        return names.toArray(new String[0]);
    }

    /** The label {@link #choices} uses for "no ultimate". */
    public static String noneLabel() {
        return "(none)";
    }

    /** The key behind a {@link #choices} label. */
    public static String keyForChoice(String label) {
        for (Ultimate u : BY_KEY.values()) {
            if (u.name().equals(label)) return u.key();
        }
        return NONE;
    }

    /** The {@link #choices} label for a key. */
    public static String choiceForKey(String key) {
        Ultimate u = get(key);
        return u == null ? noneLabel() : u.name();
    }

    // --- meter bookkeeping -----------------------------------------------------------

    /**
     * The ultimate a player can actually fire: the one their character profile
     * selected, unless the profile switched ultimates off. {@code null} when
     * they have none.
     */
    public static Ultimate of(PlayerState s) {
        if (s == null || !s.ultimateEnabled) return null;
        return get(s.ultimateKey);
    }

    /**
     * Advance the meter for one tick of play. Sustained abilities count down
     * while active and never charge at the same time, so an ultimate can't
     * refill itself mid-cast.
     */
    public static void charge(PlayerState s, double dt) {
        if (s == null) return;
        if (s.ultActive > 0) {
            s.ultActive = Math.max(0, s.ultActive - dt);
            if (s.ultActive == 0) clearEffects(s);
            return;
        }
        Ultimate u = of(s);
        if (u == null) {
            s.ultCharge = 0;
            return;
        }
        s.ultCharge = Math.min(1.0, s.ultCharge + u.chargeForTime(dt));
    }

    /** Credit damage the player dealt toward their meter. */
    public static void chargeFromDamage(PlayerState s, double damage) {
        if (s == null || damage <= 0 || s.ultActive > 0) return;
        Ultimate u = of(s);
        if (u == null) return;
        s.ultCharge = Math.min(1.0, s.ultCharge + u.chargeForDamage(damage));
    }

    /** Whether the meter is full and the player is not already mid-cast. */
    public static boolean ready(PlayerState s) {
        return s != null && s.ultActive <= 0 && s.ultCharge >= 1.0 && of(s) != null;
    }

    /**
     * Put the per-tick modifiers a sustained ultimate grants back to neutral.
     * Called when the effect ends and whenever a player is (re)spawned.
     */
    public static void clearEffects(PlayerState s) {
        if (s == null) return;
        s.ultActive = 0;
        s.ultActiveKey = NONE;
        s.ultSpeedFactor = 1.0;
        s.ultDamageFactor = 1.0;
        s.ultDamageResist = 0;
        s.ultTireless = false;
    }

    /**
     * Apply the modifiers of whatever sustained ultimate is running. Called
     * every tick by the world so the effect is live for physics and combat
     * alike, and so it lapses on its own when the timer expires.
     */
    public static void applyActiveEffects(PlayerState s) {
        if (s == null) return;
        if (s.ultActive <= 0) {
            clearEffects(s);
            return;
        }
        Ultimate u = get(s.ultActiveKey);
        if (u == null) {
            clearEffects(s);
            return;
        }
        s.ultSpeedFactor = 1.0;
        s.ultDamageFactor = 1.0;
        s.ultDamageResist = 0;
        s.ultTireless = false;
        switch (u.kind()) {
            case OVERDRIVE -> {
                s.ultSpeedFactor = 1.5;
                s.ultDamageFactor = u.power();
                s.ultTireless = true;
            }
            case BULWARK -> s.ultDamageResist = 1.0 - u.power();
            default -> { /* the rest resolve in the world, not on the player */ }
        }
    }

    /** Serialize a player's meter for the wire / a save. */
    public static Map<String, Object> toMap(PlayerState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", s.ultimateKey);
        m.put("c", s.ultCharge);
        if (s.ultActive > 0) {
            m.put("a", s.ultActive);
            m.put("ak", s.ultActiveKey);
        }
        return m;
    }
}
