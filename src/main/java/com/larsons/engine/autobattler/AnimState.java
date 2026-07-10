package com.larsons.engine.autobattler;

/**
 * What a combat unit is visibly doing, replicated in combat snapshots so
 * clients can drive animations (and pick the matching skin sprite-sheet
 * strip). The server sets the state from the simulation; transient states
 * (attack, cast, hit) hold for a short window so ~15 Hz snapshots catch them.
 *
 * <p>{@link #priority} orders the states: a transient state only replaces the
 * current one while its window runs if it matters at least as much (a hit
 * flinch never interrupts a cast, death trumps everything).
 */
public enum AnimState {

    IDLE("i", 0),
    WALK("w", 1),
    HIT("h", 2),
    ATTACK("a", 3),
    CAST("c", 4),
    DEATH("d", 5);

    /** One-letter wire code carried in combat snapshots. */
    public final String code;
    public final int priority;

    AnimState(String code, int priority) {
        this.code = code;
        this.priority = priority;
    }

    /** Decode a wire code; unknown/absent codes read as {@link #IDLE}. */
    public static AnimState fromCode(String code) {
        for (AnimState s : values()) {
            if (s.code.equals(code)) return s;
        }
        return IDLE;
    }

    /** Lower-case name used in skin keys ({@code unit/<key>/<state>}). */
    public String key() {
        return name().toLowerCase();
    }
}
