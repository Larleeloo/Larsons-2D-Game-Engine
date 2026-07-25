package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One living cell in a dish: a {@link Genome}, the {@link Phenotype} it decodes
 * to, and the mutable state the simulation pushes around — position, energy,
 * age, what it remembers, what it is carrying and which colony it belongs to.
 *
 * <p>Behaviour lives in {@link Dish#step} rather than here, because almost
 * every decision a cell makes depends on its neighbours; this class holds the
 * per-cell state and the small amount of logic that is purely local (upkeep,
 * memory, held-tool modifiers, replication).
 */
public final class Organism {

    /** Energy an organism must bank before it can divide. */
    public static final double REPLICATE_AT = 70;
    /** Energy burned by the act of dividing (lost, not passed on). */
    public static final double REPLICATE_COST = 14;
    /** Energy a freshly seeded organism starts with. */
    public static final double START_ENERGY = 45;

    public final int id;
    public final Genome genome;
    public final Phenotype pheno;

    public double x, y;
    public double vx, vy;
    public double energy = START_ENERGY;
    public double age;
    public boolean alive = true;
    public int generation;

    /** Colony this cell is bound into, or {@code -1} when free-swimming. */
    public int colonyId = -1;
    /** Tool being carried, or {@code null}. */
    public DishTool tool;

    /** Seconds of venom left on this cell; while poisoned it can barely move. */
    public double venom;
    /** Cooldown between attacks, so predators cannot machine-gun prey. */
    public double attackCooldown;
    /** Brief flash after being hit, purely so the scene can draw it. */
    public double hitFlash;
    /** Time since this cell last showed a visible donation pulse. */
    double shareTimer;
    /** Remembered food locations, newest last, capped by the memory trait. */
    public final List<double[]> memory = new ArrayList<>(4);

    // Steering state, kept between ticks so movement is smooth rather than jittery.
    double wanderAngle;
    double[] goal;          // {x, y} currently being swum toward, or null
    double goalTimer;       // seconds before the current goal is re-evaluated

    public Organism(int id, Genome genome, double x, double y) {
        this.id = id;
        this.genome = genome;
        this.pheno = Phenotype.of(genome);
        this.x = x;
        this.y = y;
    }

    // --- effective stats (phenotype plus whatever tool is being carried) --------

    public double effectiveSpeed() {
        double s = pheno.speed();
        if (holding(DishTool.Kind.FLAGELLUM)) s *= 1.6;
        if (venom > 0) s *= 0.35;
        return s;
    }

    public double effectiveAttack() {
        double a = pheno.attack();
        if (holding(DishTool.Kind.SCALPEL)) a *= 2.0;
        return a;
    }

    public double effectiveEfficiency() {
        double e = pheno.efficiency();
        if (holding(DishTool.Kind.SIEVE)) e = Math.min(0.98, e + 0.25);
        return e;
    }

    public double effectiveLightRadius() {
        double r = pheno.lightRadius();
        if (holding(DishTool.Kind.LANTERN)) r = Math.max(r, 150);
        return r;
    }

    public boolean holding(DishTool.Kind kind) {
        return tool != null && tool.kind == kind && !tool.spent();
    }

    /** How full this cell is, in [0,1] — drives the "starving" behaviour. */
    public double fullness() {
        return Math.max(0, Math.min(1, energy / REPLICATE_AT));
    }

    public boolean starving() {
        return energy < REPLICATE_AT * 0.25;
    }

    // --- memory -----------------------------------------------------------------

    /**
     * Remember a food location, keeping only as many as the strand's memory
     * trait allows and never storing the same spot twice. Strands with no
     * memory trait simply forget everything the moment they swim away.
     */
    public void remember(double fx, double fy) {
        int slots = pheno.memorySlots();
        if (slots <= 0) return;
        for (double[] m : memory) {
            if (Math.abs(m[0] - fx) < 24 && Math.abs(m[1] - fy) < 24) return;
        }
        memory.add(new double[]{fx, fy});
        while (memory.size() > slots) memory.remove(0);
    }

    /** The remembered spot closest to where the cell is now, or {@code null}. */
    public double[] closestMemory() {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] m : memory) {
            double d = (m[0] - x) * (m[0] - x) + (m[1] - y) * (m[1] - y);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    // --- replication --------------------------------------------------------------

    /**
     * Divide: the parent pays {@link #REPLICATE_COST}, what remains is split
     * evenly, and the child's strand is an imperfect copy whose error rate is
     * the parent's own evolved {@link Phenotype#variability()}, scaled by
     * {@code mutagenScale} for divisions happening inside a mutagen cloud. The
     * child is nudged off the parent's position so the two do not sit on top of
     * one another.
     */
    public Organism divide(int childId, java.util.Random rng, double mutagenScale) {
        double pool = Math.max(0, energy - REPLICATE_COST);
        energy = pool * 0.5;
        Genome childGenome = genome.replicate(rng, pheno.variability() * Math.max(1, mutagenScale));
        double angle = rng.nextDouble() * Math.PI * 2;
        double offset = pheno.size() * 1.6;
        Organism child = new Organism(childId, childGenome,
                x + Math.cos(angle) * offset, y + Math.sin(angle) * offset);
        child.energy = pool * 0.5;
        child.generation = generation + 1;
        child.wanderAngle = rng.nextDouble() * Math.PI * 2;
        // Children inherit what their parent knew — cultural transmission is
        // free, genetic memory is not: a child with no memory slots drops it.
        for (double[] m : memory) child.remember(m[0], m[1]);
        return child;
    }

    // --- persistence ---------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("dna", genome.sequence());
        m.put("x", x);
        m.put("y", y);
        m.put("vx", vx);
        m.put("vy", vy);
        m.put("energy", energy);
        m.put("age", age);
        m.put("generation", generation);
        m.put("colony", colonyId);
        if (venom > 0) m.put("venom", venom);
        if (!memory.isEmpty()) {
            List<Object> mem = new ArrayList<>(memory.size());
            for (double[] p : memory) mem.add(List.of(p[0], p[1]));
            m.put("memory", mem);
        }
        return m;
    }

    public static Organism fromMap(Map<String, Object> m) {
        String dna = String.valueOf(m.get("dna"));
        if (!Genome.isValid(dna)) return null;
        Organism o = new Organism((int) num(m.get("id")), Genome.of(dna),
                num(m.get("x")), num(m.get("y")));
        o.vx = num(m.get("vx"));
        o.vy = num(m.get("vy"));
        o.energy = num(m.get("energy"));
        o.age = num(m.get("age"));
        o.generation = (int) num(m.get("generation"));
        o.colonyId = m.containsKey("colony") ? (int) num(m.get("colony")) : -1;
        o.venom = num(m.get("venom"));
        if (m.get("memory") instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof List<?> p && p.size() >= 2) {
                    o.memory.add(new double[]{num(p.get(0)), num(p.get(1))});
                }
            }
        }
        return o;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
