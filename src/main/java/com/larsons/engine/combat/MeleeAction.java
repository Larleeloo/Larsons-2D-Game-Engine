package com.larsons.engine.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * The moves a melee fighter has — the five things a player or a mob can do
 * with whatever is in its hands, each one an <em>animation state</em> and a
 * family of <em>sound states</em> of its own:
 *
 * <pre>
 *   SWING    strike with the held weapon (the classic attack)
 *   PARRY    a short catching window: a blow that lands in it is turned aside
 *   LUNGE    a committed thrust — the fighter travels, and it lands harder
 *   DASH     a burst of footwork, briefly untouchable, that deals nothing
 *   SHIELD   a held guard stance that soaks a fraction of everything
 * </pre>
 *
 * <p>Each action names a sheet — {@code wield/<item>/<action>} for the fighter
 * holding that item, {@code player/<action>} / {@code mob/<key>/<action>} for
 * the fighter itself, {@code item/<item>/<action>} for the object in hand —
 * and a pair of sound keys (the move starting, and the move connecting). Every
 * one of them falls back to idle when a creator has drawn nothing for it (see
 * {@link MeleeSprites}), which is what makes the whole set optional: a game
 * with no combat art still swings, parries and dashes, it just does it in the
 * art it already had.
 *
 * <p>{@link #NONE} is "not doing any of this", the resting value of a
 * {@link MeleeState}; it is deliberately ordinal 0 so the compact wire form is
 * absent from a snapshot when nothing is happening.
 */
public enum MeleeAction {

    /** Not mid-move. The resting state, and what an unsupported action reads as. */
    NONE("none", "None", false, false, false, "", ""),

    /** The plain attack: wind up, strike, recover. */
    SWING("swing", "Swing", true, false, false, "swing", "swing_hit"),

    /**
     * A catching window. A melee blow that lands while it is open is turned
     * aside for no damage and staggers whoever threw it; an incoming
     * projectile is knocked back the way it came.
     */
    PARRY("parry", "Parry", false, false, false, "parry", "parry_success"),

    /** A committed thrust: the fighter travels with it and it lands harder. */
    LUNGE("lunge", "Lunge", true, true, false, "lunge", "lunge_hit"),

    /** Evasive footwork: a fast burst along the facing, untouchable while it lasts. */
    DASH("dash", "Dash", false, true, false, "dash", ""),

    /**
     * The guard stance — "shield ready". Unlike the others this is
     * <em>held</em>: it stays up until it is released, soaking
     * {@link MeleeProfile#blockFraction()} of everything that gets through.
     */
    SHIELD("shield", "Shield", false, false, true, "shield_up", "shield_block");

    private final String key;
    private final String label;
    private final boolean offensive;
    private final boolean travels;
    private final boolean held;
    private final String startSound;
    private final String hitSound;

    MeleeAction(String key, String label, boolean offensive, boolean travels,
                boolean held, String startSound, String hitSound) {
        this.key = key;
        this.label = label;
        this.offensive = offensive;
        this.travels = travels;
        this.held = held;
        this.startSound = startSound;
        this.hitSound = hitSound;
    }

    /** Texture/sound key segment, and the animation state name ({@code "swing"}). */
    public String key() {
        return key;
    }

    /** Menu name. */
    public String label() {
        return label;
    }

    /** Whether the active window lands damage (SWING, LUNGE). */
    public boolean offensive() {
        return offensive;
    }

    /** Whether the move carries the fighter along its facing (LUNGE, DASH). */
    public boolean travels() {
        return travels;
    }

    /** Whether the move is a held stance rather than a one-shot (SHIELD). */
    public boolean held() {
        return held;
    }

    /** The sound key state played as the move begins ({@code "swing"}). */
    public String startSound() {
        return startSound;
    }

    /**
     * The sound key state played when the move connects — a swing hitting, a
     * parry catching, a raised shield eating a blow. Blank when the move has
     * nothing to connect with (a dash).
     */
    public String hitSound() {
        return hitSound;
    }

    /** The sound key state played as a held stance drops ({@code shield_down}). */
    public String endSound() {
        return held ? key + "_down" : "";
    }

    /** Every real move, in menu order ({@link #NONE} excluded). */
    public static List<MeleeAction> moves() {
        List<MeleeAction> out = new ArrayList<>(values().length - 1);
        for (MeleeAction a : values()) {
            if (a != NONE) out.add(a);
        }
        return out;
    }

    /** The animation-state names of every move, for the texture catalogues. */
    public static List<String> states() {
        List<String> out = new ArrayList<>(values().length - 1);
        for (MeleeAction a : moves()) out.add(a.key);
        return out;
    }

    /**
     * The sound-state names of every move — each one's start, its connect, and
     * the release of a held stance — for the sound catalogue.
     */
    public static List<String> soundStates() {
        List<String> out = new ArrayList<>();
        for (MeleeAction a : moves()) {
            out.add(a.startSound);
            if (!a.hitSound.isEmpty()) out.add(a.hitSound);
            if (!a.endSound().isEmpty()) out.add(a.endSound());
        }
        return out;
    }

    /** Parse a {@link #key()} back into a move; unknown names read as {@link #NONE}. */
    public static MeleeAction byKey(String key) {
        if (key == null || key.isBlank()) return NONE;
        String k = key.trim().toLowerCase();
        for (MeleeAction a : values()) {
            if (a.key.equals(k)) return a;
        }
        return NONE;
    }

    /** Parse a wire ordinal back into a move (out-of-range reads as {@link #NONE}). */
    public static MeleeAction byOrdinal(int ordinal) {
        MeleeAction[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NONE;
    }
}
