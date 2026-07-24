package com.larsons.engine.evolution;

/**
 * The silhouette a strand grows into. Shape follows from what the DNA can do
 * ("the shape of organism can determine its abilities and tools"), and then
 * feeds back as a modifier — so a lineage that evolves predation also gets the
 * body that makes predation work.
 *
 * <p>Everything starts as a {@link #SQUARE}: shapes only differentiate once a
 * strand reaches {@link #MIN_LENGTH} nucleotides, which keeps the player's
 * first organism the plain square the design document asks for.
 */
public enum BodyShape {

    /** The primordial body. No modifiers, no specialisation. */
    SQUARE("Square", "The primordial body — no specialisation either way.",
            1.0, 1.0, 1.0, 1.0),
    /** Hunter: fast and sharp, but flimsy. */
    TRIANGLE("Triangle", "A hunting prow: quick and sharp, but thin-walled.",
            1.15, 1.25, 0.85, 1.05),
    /** Altruist: cheap to run and tough, but slow. */
    CIRCLE("Circle", "Smooth and cheap to run, hard to puncture, not quick.",
            0.9, 0.85, 1.3, 0.85),
    /** Light emitter: the glow carries further. */
    STAR("Star", "Radiating arms throw light much further.",
            0.95, 0.9, 0.9, 1.0),
    /** Multicellular: built to bind to neighbours. */
    HEXAGON("Hexagon", "Tessellating walls built for binding into colonies.",
            0.85, 0.95, 1.25, 0.95),
    /** Tool user: manipulator faces for holding tools. */
    DIAMOND("Diamond", "Faceted manipulators that hold and work tools.",
            1.0, 1.05, 1.0, 1.15),
    /** Complex digester: a wide gut cross-section. */
    CROSS("Cross", "A branching gut that swallows complex energy whole.",
            0.85, 0.9, 1.05, 1.2),
    /** Scout: all eyes. */
    PENTAGON("Pentagon", "Eye-spots on every face; sees trouble coming.",
            1.05, 0.95, 1.0, 1.0);

    /** Strands shorter than this stay {@link #SQUARE} whatever they encode. */
    public static final int MIN_LENGTH = 6;

    private final String displayName;
    private final String description;
    private final double speedMul;
    private final double attackMul;
    private final double defenseMul;
    private final double efficiencyMul;

    BodyShape(String displayName, String description,
              double speedMul, double attackMul, double defenseMul, double efficiencyMul) {
        this.displayName = displayName;
        this.description = description;
        this.speedMul = speedMul;
        this.attackMul = attackMul;
        this.defenseMul = defenseMul;
        this.efficiencyMul = efficiencyMul;
    }

    public String displayName() { return displayName; }

    public String description() { return description; }

    public double speedMultiplier() { return speedMul; }

    public double attackMultiplier() { return attackMul; }

    public double defenseMultiplier() { return defenseMul; }

    public double efficiencyMultiplier() { return efficiencyMul; }

    /** Star bodies throw their bioluminescence noticeably further. */
    public double lightMultiplier() { return this == STAR ? 1.4 : 1.0; }

    /** Pentagons see further; everything else uses its plain vision range. */
    public double visionMultiplier() { return this == PENTAGON ? 1.35 : 1.0; }
}
