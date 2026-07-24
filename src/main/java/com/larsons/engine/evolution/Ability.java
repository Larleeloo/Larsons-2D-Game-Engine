package com.larsons.engine.evolution;

import java.awt.Color;

/**
 * A discrete ability a strand of DNA can unlock, as opposed to the sliding
 * magnitudes of {@link Trait}.
 *
 * <p>Nine of them come from the nine ordered <b>pairs</b> of nucleotides — every
 * adjacent pair in a strand means something, so a longer strand is a longer
 * sentence rather than just a bigger number. The last two are the document's
 * "eventually unlockable" abilities and additionally require a long strand, so
 * they only ever show up after a lineage has been evolving for a while.
 *
 * <table>
 *   <caption>Pair rules</caption>
 *   <tr><td>RR</td><td>{@link #PREDATION}</td></tr>
 *   <tr><td>RG</td><td>{@link #EXOTHERMY}</td></tr>
 *   <tr><td>RB</td><td>{@link #VENOM}</td></tr>
 *   <tr><td>GR</td><td>doubles the green's magnitude (no ability)</td></tr>
 *   <tr><td>GG</td><td>{@link #COMPLEX_DIGESTION}</td></tr>
 *   <tr><td>GB</td><td>{@link #ENDOTHERMY}</td></tr>
 *   <tr><td>BR</td><td>{@link #KIN_DEFENSE}</td></tr>
 *   <tr><td>BG</td><td>{@link #BROADCAST}</td></tr>
 *   <tr><td>BB</td><td>{@link #SHARING}</td></tr>
 * </table>
 */
public enum Ability {

    /** RR — can attack, kill and digest other organisms. */
    PREDATION("Predation", "RR", "Hunts and digests other cells.",
            new Color(226, 74, 66)),
    /** RG — radiates heat into the surrounding gel. */
    EXOTHERMY("Exothermy", "RG", "Radiates heat, warming everything nearby.",
            new Color(240, 140, 70)),
    /** RB — sabotages rather than kills: poisoned cells slow to a crawl. */
    VENOM("Venom", "RB", "Poisons rivals on contact, slowing them badly.",
            new Color(190, 90, 200)),
    /** GG — can break down complex energy, which plain cells cannot touch. */
    COMPLEX_DIGESTION("Complex digestion", "GG", "Can break down complex energy others cannot eat.",
            new Color(120, 210, 120)),
    /** GB — pulls heat out of the surrounding gel. */
    ENDOTHERMY("Endothermy", "GB", "Absorbs heat, cooling everything nearby.",
            new Color(110, 200, 235)),
    /** BR — strikes back at anything attacking a cell of the same strand. */
    KIN_DEFENSE("Kin defence", "BR", "Retaliates when a cell of the same DNA is attacked.",
            new Color(150, 160, 235)),
    /** BG — long-range communication: shares remembered food with everyone nearby. */
    BROADCAST("Broadcast", "BG", "Signals remembered food locations to distant cells.",
            new Color(90, 200, 210)),
    /** BB — donates energy to starving neighbours. */
    SHARING("Sharing", "BB", "Donates energy to starving neighbours.",
            new Color(88, 132, 232)),

    /**
     * Unlockable: a {@code GRG} motif at slot 9 or later, in a strand with
     * pattern recognition of at least 4. Tool users can pick up and spend the
     * limited-use tools dropped into the dish.
     */
    TOOL_USE("Tool use", "GRG late in a long strand", "Picks up and uses tools left in the dish.",
            new Color(235, 200, 110)),
    /**
     * Unlockable: a {@code BBB} motif in a strand of 14 or more. Multicellular
     * cells bind to their neighbours into colonies that pool energy and move
     * together — and a new binding counts as a discovery in its own right.
     */
    MULTICELLULAR("Multicellularity", "BBB in a strand of 14+", "Binds with neighbours into a shared colony.",
            new Color(200, 235, 150));

    private final String displayName;
    private final String rule;
    private final String description;
    private final Color color;

    Ability(String displayName, String rule, String description, Color color) {
        this.displayName = displayName;
        this.rule = rule;
        this.description = description;
        this.color = color;
    }

    public String displayName() { return displayName; }

    /** The DNA motif that unlocks this ability, for the in-game codex. */
    public String rule() { return rule; }

    public String description() { return description; }

    public Color color() { return color; }

    /** The ability an adjacent pair unlocks, or {@code null} for {@code GR}. */
    public static Ability forPair(Nucleotide first, Nucleotide second) {
        return switch (first) {
            case R -> switch (second) {
                case R -> PREDATION;
                case G -> EXOTHERMY;
                case B -> VENOM;
            };
            case G -> switch (second) {
                case R -> null; // GR is a magnitude modifier, not an ability
                case G -> COMPLEX_DIGESTION;
                case B -> ENDOTHERMY;
            };
            case B -> switch (second) {
                case R -> KIN_DEFENSE;
                case G -> BROADCAST;
                case B -> SHARING;
            };
        };
    }
}
