package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A decoded strand: everything a {@link Genome} means, worked out once and then
 * read by the simulation every tick.
 *
 * <p>This class is the rule book the whole game is built on, and every rule in
 * it is hard-coded and deterministic — the same strand always decodes to the
 * same organism. What is <em>not</em> deterministic is which strands the player
 * ever sees, since that comes out of mutation and selection in the dish.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Red</b> nucleotides encode hostility, <b>blue</b> encode altruism;
 *       each is simply its share of the strand.</li>
 *   <li><b>Green</b> is a wild card read by <b>slot</b>: a green in slot
 *       {@code s} expresses {@link Trait#forSlot(int)} with magnitude
 *       {@code s} — a green in slot 3 is "light emission of 3", the design
 *       document's worked example.</li>
 *   <li>The nucleotide <b>after</b> a green modifies it: a following red
 *       <b>doubles</b> the magnitude, a following blue <b>halves</b> it but
 *       refines digestion, and a following green adds one and chains on.</li>
 *   <li>Every adjacent <b>pair</b> also unlocks an {@link Ability} (see that
 *       enum's table), so the same letters read twice: once for magnitude, once
 *       for capability.</li>
 *   <li>The strand's <b>tail</b> (its last third) governs copying fidelity:
 *       greens there make replication sloppier, so offspring variability is
 *       itself an evolved trait.</li>
 *   <li>Green content over the whole strand sets how fast the body rots after
 *       death, returning its energy to the dish.</li>
 *   <li>{@link BodyShape} follows from the abilities and traits above, and
 *       then feeds back as a multiplier on speed, attack, defence and digestion.</li>
 * </ol>
 *
 * <p>Raw magnitudes are unbounded (a 48-slot strand can pile a lot into one
 * trait), so every gameplay number runs through a saturating curve: more DNA
 * always helps, but with diminishing returns, which keeps late-game organisms
 * strong without being unkillable.
 */
public final class Phenotype {

    // --- the decoded strand ---------------------------------------------------

    private final Genome genome;
    private final Map<Trait, Double> traits;
    private final Set<Ability> abilities;
    private final BodyShape shape;
    private final Color color;

    private final double hostility;   // share of red, [0,1]
    private final double altruism;    // share of blue, [0,1]
    private final double variability; // replication error rate, [0.10, 0.45]
    private final double decomposition; // corpse decay multiplier

    // --- gameplay numbers the simulation reads every tick ----------------------

    private final double size;          // radius in dish units
    private final double speed;         // dish units per second
    private final double senseRadius;   // how far it can see food and rivals
    private final double eatRate;       // energy per second drawn from a resource
    private final double efficiency;    // share of a meal kept, the rest is waste
    private final double lightRadius;   // 0 when the strand emits no light
    private final int memorySlots;      // remembered food locations
    private final double recognition;   // pattern recognition, [0,1]
    private final double tempOptimum;   // most comfortable temperature, °C
    private final double tempTolerance; // half-width of the survivable band, °C
    private final double attack;
    private final double defense;
    private final double metabolism;    // energy per second just to stay alive
    private final double lifespan;      // seconds of old age

    /**
     * Decoded strands are shared by every organism carrying them, so decoding is
     * cached. Bounded so a long, wildly mutating run cannot grow it without end.
     */
    private static final int CACHE_LIMIT = 4096;
    private static final Map<String, Phenotype> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Phenotype> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    /** Decode a strand (cached — decoding the same sequence twice is free). */
    public static Phenotype of(Genome genome) {
        return CACHE.computeIfAbsent(genome.sequence(), s -> new Phenotype(genome));
    }

    private Phenotype(Genome g) {
        this.genome = g;
        int len = g.length();

        // --- rule 2 & 3: greens are wild cards read by slot ---------------------
        Map<Trait, Double> raw = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) raw.put(t, 0.0);
        double refinement = 0; // green-then-blue pairs quietly refine digestion
        for (int slot = 1; slot <= len; slot++) {
            if (g.at(slot) != Nucleotide.G) continue;
            Trait trait = Trait.forSlot(slot);
            double magnitude = slot;
            if (slot < len) {
                switch (g.at(slot + 1)) {
                    case R -> magnitude *= 2;          // red amplifies
                    case B -> { magnitude *= 0.5; refinement += 1; } // blue tempers
                    case G -> magnitude += 1;          // green chains
                }
            }
            raw.merge(trait, magnitude, Double::sum);
        }
        raw.merge(Trait.EFFICIENCY, refinement, Double::sum);
        this.traits = Collections.unmodifiableMap(raw);

        // --- rule 4: every adjacent pair unlocks an ability ----------------------
        EnumSet<Ability> unlocked = EnumSet.noneOf(Ability.class);
        for (int slot = 1; slot < len; slot++) {
            Ability a = Ability.forPair(g.at(slot), g.at(slot + 1));
            if (a != null) unlocked.add(a);
        }
        // The two "eventually unlockable" abilities: long strands only.
        int toolMotif = g.indexOfMotif("GRG");
        if (toolMotif >= 9 && raw.get(Trait.PATTERN_RECOGNITION) >= 4) unlocked.add(Ability.TOOL_USE);
        if (len >= 14 && g.contains("BBB")) unlocked.add(Ability.MULTICELLULAR);
        this.abilities = Collections.unmodifiableSet(unlocked);

        // --- rule 1: hostility and altruism are simply colour shares -------------
        this.hostility = g.fraction(Nucleotide.R);
        this.altruism = g.fraction(Nucleotide.B);

        // --- rule 5: the tail governs copying fidelity ---------------------------
        int tailLen = Math.max(1, len / 3);
        int tailGreens = 0;
        for (int slot = len - tailLen + 1; slot <= len; slot++) {
            if (g.at(slot) == Nucleotide.G) tailGreens++;
        }
        this.variability = 0.10 + 0.35 * ((double) tailGreens / tailLen);

        // --- rule 6: green bodies rot faster -------------------------------------
        this.decomposition = 0.25 + 0.75 * g.fraction(Nucleotide.G);

        // --- rule 7: shape, then its feedback ------------------------------------
        this.shape = pickShape(len, raw, unlocked, hostility, altruism);
        this.color = g.color();

        // --- gameplay numbers -----------------------------------------------------
        double speedT = raw.get(Trait.SPEED);
        double visionT = raw.get(Trait.VISION);
        double eatT = raw.get(Trait.CONSUMPTION_RATE);
        double effT = raw.get(Trait.EFFICIENCY);
        double lightT = raw.get(Trait.LIGHT_EMISSION);
        double heatT = raw.get(Trait.HEAT_TOLERANCE);
        double memT = raw.get(Trait.MEMORY);
        double patT = raw.get(Trait.PATTERN_RECOGNITION);

        this.size = Math.min(16.0, 6.0 + len * 0.35);
        this.speed = (26 + 54 * saturate(speedT, 10)) * shape.speedMultiplier();
        this.senseRadius = (95 + 150 * saturate(visionT, 10)) * shape.visionMultiplier();
        this.eatRate = 14 + 30 * saturate(eatT, 10);
        this.efficiency = Math.min(0.97,
                (0.55 + 0.40 * saturate(effT, 10)) * shape.efficiencyMultiplier());
        this.lightRadius = lightT <= 0 ? 0
                : Math.min(300, (34 + 20 * Math.sqrt(lightT)) * shape.lightMultiplier());
        this.memorySlots = (int) Math.min(8, Math.floor(memT / 3));
        this.recognition = saturate(patT, 10);
        this.tempTolerance = 5 + 20 * saturate(heatT, 10);
        double optimum = 24;
        if (unlocked.contains(Ability.EXOTHERMY)) optimum += 7;
        if (unlocked.contains(Ability.ENDOTHERMY)) optimum -= 7;
        this.tempOptimum = optimum;
        this.attack = (0.35 + 1.4 * hostility) * shape.attackMultiplier()
                * (unlocked.contains(Ability.PREDATION) ? 1.0 : 0.2);
        this.defense = (0.45 + 0.6 * altruism + len * 0.012) * shape.defenseMultiplier();
        // Upkeep: bigger, faster, brighter bodies all cost more to run, which is
        // what stops every trait from being free to pile on.
        this.metabolism = 0.40
                + 0.018 * len
                + 0.005 * speed
                + 0.0022 * lightRadius
                + 0.008 * eatRate
                + (unlocked.contains(Ability.PREDATION) ? 0.12 : 0);
        this.lifespan = 150 + 90 * saturate(heatT + effT, 14);
    }

    /**
     * Shape follows the strand's strongest commitment, in priority order, and
     * only once the strand is long enough to differentiate at all — everything
     * shorter than {@link BodyShape#MIN_LENGTH} stays the primordial square.
     */
    private static BodyShape pickShape(int len, Map<Trait, Double> raw, Set<Ability> abilities,
                                       double hostility, double altruism) {
        if (len < BodyShape.MIN_LENGTH) return BodyShape.SQUARE;
        if (abilities.contains(Ability.MULTICELLULAR)) return BodyShape.HEXAGON;
        if (abilities.contains(Ability.TOOL_USE)) return BodyShape.DIAMOND;
        if (raw.get(Trait.LIGHT_EMISSION) >= 6) return BodyShape.STAR;
        if (abilities.contains(Ability.PREDATION) && hostility >= 0.4) return BodyShape.TRIANGLE;
        if (abilities.contains(Ability.COMPLEX_DIGESTION) && raw.get(Trait.EFFICIENCY) >= 4) {
            return BodyShape.CROSS;
        }
        if (altruism >= 0.5) return BodyShape.CIRCLE;
        if (raw.get(Trait.VISION) >= 6) return BodyShape.PENTAGON;
        return BodyShape.SQUARE;
    }

    /** Diminishing returns: {@code x/(x+half)}, so more DNA always helps a little. */
    private static double saturate(double x, double half) {
        if (x <= 0) return 0;
        return x / (x + half);
    }

    // --- accessors --------------------------------------------------------------

    public Genome genome() { return genome; }

    /** Raw magnitude of a wild-card trait (0 when the strand never encodes it). */
    public double trait(Trait t) { return traits.getOrDefault(t, 0.0); }

    public Map<Trait, Double> traits() { return traits; }

    public Set<Ability> abilities() { return abilities; }

    public boolean has(Ability a) { return abilities.contains(a); }

    public BodyShape shape() { return shape; }

    public Color color() { return color; }

    public double hostility() { return hostility; }

    public double altruism() { return altruism; }

    public double variability() { return variability; }

    public double decomposition() { return decomposition; }

    public double size() { return size; }

    public double speed() { return speed; }

    public double senseRadius() { return senseRadius; }

    public double eatRate() { return eatRate; }

    public double efficiency() { return efficiency; }

    /** Share of every meal that becomes waste instead of energy. */
    public double wasteFraction() { return 1 - efficiency; }

    public double lightRadius() { return lightRadius; }

    public int memorySlots() { return memorySlots; }

    public double recognition() { return recognition; }

    public double tempOptimum() { return tempOptimum; }

    public double tempTolerance() { return tempTolerance; }

    public double attack() { return attack; }

    public double defense() { return defense; }

    public double metabolism() { return metabolism; }

    public double lifespan() { return lifespan; }

    /**
     * How much this strand is worth as a discovery: longer strands, more
     * unlocked abilities and more strongly expressed traits all pay more, which
     * is what makes deliberately breeding complexity the point of the game.
     */
    public int complexity() {
        int score = genome.length();
        score += abilities.size() * 3;
        for (double v : traits.values()) {
            if (v >= 4) score += 1;
            if (v >= 12) score += 1;
        }
        if (abilities.contains(Ability.TOOL_USE)) score += 5;
        if (abilities.contains(Ability.MULTICELLULAR)) score += 5;
        return score;
    }

    /** A short human-readable summary for the HUD ("Triangle · predation, venom"). */
    public String summary() {
        StringBuilder sb = new StringBuilder(shape.displayName());
        if (!abilities.isEmpty()) {
            sb.append(" · ");
            int i = 0;
            for (Ability a : abilities) {
                if (i++ > 0) sb.append(", ");
                sb.append(a.displayName().toLowerCase());
            }
        }
        return sb.toString();
    }
}
