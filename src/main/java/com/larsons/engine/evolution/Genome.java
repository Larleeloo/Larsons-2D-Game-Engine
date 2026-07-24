package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.Random;

/**
 * A strand of DNA: an immutable sequence of {@link Nucleotide}s.
 *
 * <p>The sequence <em>is</em> the species identity — two organisms are the same
 * species exactly when their strands read the same, which is what the catalog
 * keys discoveries on. Strands lengthen (and shorten) through
 * {@link #replicate}, so a lineage's DNA grows over generations rather than
 * being fixed at the start.
 *
 * <p>Decoding a strand into what an organism can actually do lives in
 * {@link Phenotype}; this class only deals with the letters themselves.
 */
public final class Genome {

    /** Strands are capped so the simulation stays bounded (and readable). */
    public static final int MAX_LENGTH = 48;
    /** A strand can never mutate below this; shorter is not viable. */
    public static final int MIN_LENGTH = 2;

    private final Nucleotide[] bases;
    private final String sequence; // cached: used as the species key everywhere

    public Genome(Nucleotide... bases) {
        if (bases.length == 0) throw new IllegalArgumentException("An empty strand is not a genome");
        this.bases = bases.clone();
        StringBuilder sb = new StringBuilder(bases.length);
        for (Nucleotide n : this.bases) sb.append(n.code());
        this.sequence = sb.toString();
    }

    /** Parse a strand written as {@code R}/{@code G}/{@code B} characters. */
    public static Genome of(String sequence) {
        String s = sequence == null ? "" : sequence.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("An empty strand is not a genome");
        if (s.length() > MAX_LENGTH) s = s.substring(0, MAX_LENGTH);
        Nucleotide[] out = new Nucleotide[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = Nucleotide.of(s.charAt(i));
        return new Genome(out);
    }

    /** Whether a string is a well-formed strand (used when loading saves). */
    public static boolean isValid(String sequence) {
        if (sequence == null || sequence.isEmpty() || sequence.length() > MAX_LENGTH) return false;
        for (int i = 0; i < sequence.length(); i++) {
            if (!Nucleotide.isCode(sequence.charAt(i))) return false;
        }
        return true;
    }

    /**
     * The starting strand for a colour the player picked: four nucleotides of
     * that colour. Four is below {@link BodyShape#MIN_LENGTH}, so every new
     * experiment starts as the plain square the design calls for — the three
     * colours still behave completely differently from the first second.
     */
    public static Genome starter(Nucleotide color) {
        return new Genome(color, color, color, color);
    }

    /** A random strand of {@code length} nucleotides (used by starter colonies). */
    public static Genome random(Random rng, int length) {
        int n = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
        Nucleotide[] out = new Nucleotide[n];
        Nucleotide[] all = Nucleotide.values();
        for (int i = 0; i < n; i++) out[i] = all[rng.nextInt(all.length)];
        return new Genome(out);
    }

    public int length() { return bases.length; }

    /** The nucleotide in {@code slot} (1-based, as the trait rules count them). */
    public Nucleotide at(int slot) { return bases[slot - 1]; }

    /** The strand as {@code R}/{@code G}/{@code B} characters — the species key. */
    public String sequence() { return sequence; }

    public int count(Nucleotide n) {
        int c = 0;
        for (Nucleotide b : bases) if (b == n) c++;
        return c;
    }

    /** Share of the strand that is {@code n}, in [0,1]. */
    public double fraction(Nucleotide n) {
        return (double) count(n) / bases.length;
    }

    /**
     * The organism's visible colour: the average of its nucleotides, so what a
     * cell looks like under the lens is a direct read-out of its balance of
     * hostility (red), wild-card machinery (green) and altruism (blue).
     *
     * <p>The three channels are the three colour <em>fractions</em>, rescaled so
     * the strongest channel is always full. A plain average is the same ratio
     * but washes every mixed strand out to the same muddy grey, which would
     * throw away the one thing the colour is supposed to tell you; normalising
     * keeps the ratio and makes it legible. A pure strand reads as its own
     * colour, an even mix reads white, and a red/green strand reads yellow.
     */
    public Color color() {
        double r = fraction(Nucleotide.R);
        double g = fraction(Nucleotide.G);
        double b = fraction(Nucleotide.B);
        double max = Math.max(r, Math.max(g, b)); // never 0: a strand has bases
        return new Color(clamp(235 * r / max), clamp(235 * g / max), clamp(235 * b / max));
    }

    /** Whether {@code motif} appears anywhere in the strand. */
    public boolean contains(String motif) {
        return sequence.contains(motif);
    }

    /** 1-based slot of the first {@code motif} occurrence, or {@code -1}. */
    public int indexOfMotif(String motif) {
        int i = sequence.indexOf(motif);
        return i < 0 ? -1 : i + 1;
    }

    /**
     * Copy this strand imperfectly — the core of the whole simulation.
     *
     * <p>{@code variability} (an organism's own evolved trait, in [0,1]) scales
     * three independent kinds of error, so the mutation rate is itself under
     * selection:
     * <ul>
     *   <li><b>Substitution</b> — a nucleotide is rewritten to a different colour.</li>
     *   <li><b>Insertion</b> — a nucleotide is spliced in, lengthening the strand
     *       (this is what lets DNA grow, unlock late abilities and reach the
     *       higher slots of the trait wheel).</li>
     *   <li><b>Deletion</b> — a nucleotide is dropped, shortening the strand.</li>
     * </ul>
     * Insertions are made slightly likelier than deletions, so lineages tend to
     * grow more complex over time without being forced to.
     */
    public Genome replicate(Random rng, double variability) {
        double v = Math.max(0, Math.min(1, variability));
        StringBuilder sb = new StringBuilder(sequence);
        Nucleotide[] all = Nucleotide.values();

        // Substitutions land a fixed number of times per copy rather than once
        // per slot. That matters: a per-slot rate would scramble long strands
        // faster than short ones, so nothing complex could ever breed true and
        // hard-won DNA would dissolve as fast as it accumulated.
        double expected = v * 2.0;
        int substitutions = (int) expected;
        if (rng.nextDouble() < expected - substitutions) substitutions++;
        for (int k = 0; k < substitutions; k++) {
            int i = rng.nextInt(sb.length());
            Nucleotide current = Nucleotide.of(sb.charAt(i));
            Nucleotide next;
            do {
                next = all[rng.nextInt(all.length)];
            } while (next == current);
            sb.setCharAt(i, next.code());
        }

        // Insertion, then deletion — one of each at most per replication, with
        // insertion the likelier of the two so strands drift toward complexity.
        if (sb.length() < MAX_LENGTH && rng.nextDouble() < v * 0.8) {
            int at = rng.nextInt(sb.length() + 1);
            sb.insert(at, all[rng.nextInt(all.length)].code());
        }
        if (sb.length() > MIN_LENGTH && rng.nextDouble() < v * 0.3) {
            sb.deleteCharAt(rng.nextInt(sb.length()));
        }

        return Genome.of(sb.toString());
    }

    /**
     * How many nucleotides differ between two strands, comparing slot by slot
     * and counting a length difference as that many changes. Used to tell kin
     * from strangers (sharing, kin defence) without a family tree.
     */
    public int distanceTo(Genome other) {
        int shared = Math.min(bases.length, other.bases.length);
        int d = Math.abs(bases.length - other.bases.length);
        for (int i = 0; i < shared; i++) {
            if (bases[i] != other.bases[i]) d++;
        }
        return d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Genome g && sequence.equals(g.sequence);
    }

    @Override
    public int hashCode() { return sequence.hashCode(); }

    @Override
    public String toString() { return sequence; }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
