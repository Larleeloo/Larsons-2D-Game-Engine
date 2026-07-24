package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A catalogued discovery: one strand that has actually existed in one of the
 * player's dishes, together with what it decoded to and where it came from.
 *
 * <p>Records are written out as one JSON file each. Nothing in the game ships
 * with a species list — the reference book only ever contains what the
 * simulation has already produced, which is the point: the fun is finding out
 * what is possible, not looking it up.
 */
public final class SpeciesRecord {

    public final String sequence;
    public final String name;
    /** Epoch millis when this strand was first seen. */
    public final long discoveredAt;
    /** The dish it first appeared in. */
    public final String dish;
    /** How many divisions separated it from the strand the player seeded. */
    public final int generation;
    /** Shop credit this discovery paid out. */
    public final int credit;

    private final Phenotype pheno;

    public SpeciesRecord(String sequence, String name, long discoveredAt, String dish,
                         int generation, int credit) {
        this.sequence = sequence;
        this.name = name;
        this.discoveredAt = discoveredAt;
        this.dish = dish;
        this.generation = generation;
        this.credit = credit;
        this.pheno = Phenotype.of(Genome.of(sequence));
    }

    /** Catalogue a strand seen right now, naming it and pricing the discovery. */
    public static SpeciesRecord of(Genome genome, String dish, int generation) {
        Phenotype p = Phenotype.of(genome);
        return new SpeciesRecord(genome.sequence(), nameFor(genome), System.currentTimeMillis(),
                dish, generation, p.complexity());
    }

    public Genome genome() { return Genome.of(sequence); }

    public Phenotype phenotype() { return pheno; }

    // --- naming ------------------------------------------------------------------

    private static final String[] GENUS_SUFFIX = {
            "coccus", "bacter", "monas", "fex", "spira", "derma", "phaga", "zoon"};
    private static final String[] EPITHET = {
            "vulgaris", "ferox", "lucens", "altruis", "vorax", "errans", "nexus", "profundus",
            "tepidus", "glacialis", "radians", "tenax", "avidus", "placidus", "sagax", "obscurus"};

    /**
     * A stable binomial name for a strand: the genus stem comes from whichever
     * colour dominates the DNA, and the rest is a deterministic function of the
     * sequence itself — so the same strand is always the same species by name,
     * in this run and in every future one.
     *
     * <p>A two-character strain code is appended because a long run catalogues
     * thousands of strands and a binomial alone would collide often enough to
     * put two different species under one name in the book. The code is drawn
     * from the same hash, so it stays stable too.
     */
    public static String nameFor(Genome g) {
        double r = g.fraction(Nucleotide.R);
        double gr = g.fraction(Nucleotide.G);
        double b = g.fraction(Nucleotide.B);
        String stem;
        if (r >= gr && r >= b) stem = "Rubi";
        else if (gr >= b) stem = "Viri";
        else stem = "Cyano";
        int h = Math.abs(g.sequence().hashCode());
        String strain = Integer.toString(h % 1296 + 1296, 36).toUpperCase().substring(1);
        return stem + GENUS_SUFFIX[h % GENUS_SUFFIX.length]
                + " " + EPITHET[(h / GENUS_SUFFIX.length) % EPITHET.length]
                + " " + strain;
    }

    // --- persistence ---------------------------------------------------------------

    /**
     * The record as JSON. The decoded traits and abilities are written out
     * alongside the raw strand so a catalog file is readable on its own,
     * without having to run the decoder to know what it was looking at.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dna", sequence);
        m.put("name", name);
        m.put("discoveredAt", discoveredAt);
        m.put("dish", dish);
        m.put("generation", generation);
        m.put("credit", credit);
        m.put("length", sequence.length());
        m.put("shape", pheno.shape().name());
        m.put("colorRgb", pheno.color().getRGB());
        m.put("hostility", round(pheno.hostility()));
        m.put("altruism", round(pheno.altruism()));
        m.put("variability", round(pheno.variability()));
        m.put("complexity", pheno.complexity());

        Map<String, Object> traits = new LinkedHashMap<>();
        for (Trait t : Trait.values()) {
            double v = pheno.trait(t);
            if (v > 0) traits.put(t.name(), round(v));
        }
        m.put("traits", traits);

        List<Object> abilities = new ArrayList<>();
        for (Ability a : pheno.abilities()) abilities.add(a.name());
        m.put("abilities", abilities);
        return m;
    }

    /** Read a record back. Returns {@code null} for a file that is not one. */
    public static SpeciesRecord fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String dna = String.valueOf(m.get("dna"));
        if (!Genome.isValid(dna)) return null;
        String name = m.get("name") == null ? nameFor(Genome.of(dna)) : String.valueOf(m.get("name"));
        return new SpeciesRecord(dna, name,
                (long) num(m.get("discoveredAt")),
                m.get("dish") == null ? "" : String.valueOf(m.get("dish")),
                (int) num(m.get("generation")),
                (int) num(m.get("credit")));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
