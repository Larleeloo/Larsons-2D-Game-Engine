package com.larsons.engine.evolution;

/**
 * The eight expressed traits a green (wild card) nucleotide can encode.
 *
 * <p>Which one a green codes for is decided by the <b>slot</b> it occupies:
 * {@code slot % 8} indexes this list, so a green in slot 3 is light emission,
 * a green in slot 11 is light emission again but stronger (magnitude is the
 * slot number). That is the rule the design document gives as its example —
 * "a green nucleotide in slot 3 of a DNA sequence means light emission of 3"
 * — generalised to the whole wheel. See {@link Phenotype} for the modifiers
 * the following nucleotide applies.
 *
 * <p>The wheel order matters and must not be shuffled: existing saved species
 * decode against it.
 */
public enum Trait {

    /** Slot 1, 9, 17… — how fast the organism swims. */
    SPEED("Speed", "Swim speed. Fast cells find food first but burn more energy."),
    /** Slot 2, 10, 18… — how quickly it can take energy in. */
    CONSUMPTION_RATE("Consumption rate", "How fast energy is drawn out of a resource."),
    /** Slot 3, 11, 19… — the document's worked example. */
    LIGHT_EMISSION("Light emission", "Bioluminescence. Lights the dish, but costs upkeep."),
    /** Slot 4, 12, 20… — width of the survivable temperature band. */
    HEAT_TOLERANCE("Heat tolerance", "Width of the temperature band the cell survives in."),
    /** Slot 5, 13, 21… — energy kept per unit eaten (the rest becomes waste). */
    EFFICIENCY("Digestive efficiency", "Share of a meal turned into energy instead of waste."),
    /** Slot 6, 14, 22… — how many food locations it can remember. */
    MEMORY("Memory", "How many food locations the cell remembers after leaving them."),
    /** Slot 7, 15, 23… — sight range and obstacle navigation. */
    VISION("Vision", "Sight range, line-of-sight food seeking and obstacle navigation."),
    /** Slot 8, 16, 24… — recognising dangerous colour patterns. */
    PATTERN_RECOGNITION("Pattern recognition", "Reads other cells' colours to spot predators and kin.");

    private final String displayName;
    private final String description;

    Trait(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }

    public String description() { return description; }

    /**
     * The trait a green nucleotide in {@code slot} (1-based) codes for. The
     * wheel repeats every 8 slots, so longer strands revisit traits at higher
     * magnitudes rather than running out of meanings.
     */
    public static Trait forSlot(int slot) {
        Trait[] wheel = values();
        int idx = (slot - 1) % wheel.length;
        if (idx < 0) idx += wheel.length;
        return wheel[idx];
    }
}
