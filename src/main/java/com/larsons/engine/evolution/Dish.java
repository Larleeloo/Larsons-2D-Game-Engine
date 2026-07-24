package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntConsumer;
import java.util.function.IntToDoubleFunction;

/**
 * One Petri dish: a circular pool of gel and everything living, dead or
 * dissolved in it. This is where the artificial life actually happens.
 *
 * <p>Every rule here is local — a cell only ever reacts to what is within its
 * own senses — and the interesting behaviour (blooms, crashes, predator/prey
 * cycles, colonies) is emergent rather than scripted. The pressures that make
 * selection bite are all present at once:
 *
 * <ul>
 *   <li><b>Scarcity.</b> Energy is finite. Corpses and digested waste recycle
 *       back into orbs, so a dish is a closed loop that a well-adapted
 *       population can ride indefinitely and a greedy one drains and starves in.</li>
 *   <li><b>Crowding.</b> Upkeep rises with local density, so a bloom eats its
 *       own margin and thins itself out.</li>
 *   <li><b>Temperature.</b> Cells outside their evolved comfort band burn extra
 *       energy; exothermic and endothermic strands (and the player's heat
 *       sources) reshape that landscape for everyone around them.</li>
 *   <li><b>Predation.</b> Hostile strands hunt; strands with pattern
 *       recognition see them coming and run.</li>
 *   <li><b>Death.</b> Old age and starvation both kill, so nothing stagnates —
 *       the only way a strand persists is by replicating faster than it dies.</li>
 * </ul>
 *
 * <p>The dish is pure simulation: no AWT drawing, no scene state, so the whole
 * ecology can be run headlessly in tests at whatever speed is convenient.
 */
public final class Dish {

    /** Population ceiling, so a runaway bloom cannot bog the frame down. */
    public static final int MAX_ORGANISMS = 260;
    /** Cap on suspended food, for the same reason. */
    public static final int MAX_ORBS = 1500;
    /** Default dish radius, in dish units. */
    public static final double DEFAULT_RADIUS = 360;
    /** Room-temperature gel, in °C. */
    public static final double AMBIENT_TEMP = 24;

    private static final double MAX_STEP = 0.05;   // sub-stepped above this
    private static final int TEMP_GRID = 24;       // temperature cells across
    private static final double TEMP_INTERVAL = 0.2;
    private static final double CROWD_RADIUS = 30;
    private static final double SHARE_RANGE = 46;
    private static final double ATTACK_COOLDOWN = 1.1;
    private static final double BROADCAST_INTERVAL = 1.5;

    // --- passive dish contents ----------------------------------------------------

    /** A body, dissolving back into the food supply at its strand's own rate. */
    public static final class Corpse {
        public double x, y;
        public double energy;
        public double rate;      // decomposition multiplier from the strand
        public double age;
        public Color color;
        /** Dissolved but not yet released as an orb; keeps decay energy-neutral. */
        double pending;

        public Corpse(double x, double y, double energy, double rate, Color color) {
            this.x = x;
            this.y = y;
            this.energy = energy;
            this.rate = rate;
            this.color = color;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("energy", energy);
            m.put("rate", rate);
            m.put("rgb", color.getRGB());
            return m;
        }

        static Corpse fromMap(Map<String, Object> m) {
            return new Corpse(num(m.get("x")), num(m.get("y")), num(m.get("energy")),
                    Math.max(0.05, num(m.get("rate"))), new Color((int) num(m.get("rgb"))));
        }
    }

    /** What sloppy digestion leaves behind; slowly re-dissolves into energy. */
    public static final class Waste {
        public double x, y;
        public double amount;
        public double age;

        public Waste(double x, double y, double amount) {
            this.x = x;
            this.y = y;
            this.amount = amount;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("amount", amount);
            m.put("age", age);
            return m;
        }

        static Waste fromMap(Map<String, Object> m) {
            Waste w = new Waste(num(m.get("x")), num(m.get("y")), num(m.get("amount")));
            w.age = num(m.get("age"));
            return w;
        }
    }

    /** An inert pillar. Blocks movement and line of sight. */
    public static final class Barrier {
        public double x, y, radius;

        public Barrier(double x, double y, double radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("r", radius);
            return m;
        }

        static Barrier fromMap(Map<String, Object> m) {
            return new Barrier(num(m.get("x")), num(m.get("y")), Math.max(4, num(m.get("r"))));
        }
    }

    /** A hot or cold spot the player drops in to reshape the thermal landscape. */
    public static final class HeatSource {
        public double x, y, radius;
        /** Degrees per second pushed into the gel; negative for a cold sink. */
        public double power;

        public HeatSource(double x, double y, double radius, double power) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.power = power;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("r", radius);
            m.put("power", power);
            return m;
        }

        static HeatSource fromMap(Map<String, Object> m) {
            return new HeatSource(num(m.get("x")), num(m.get("y")),
                    Math.max(10, num(m.get("r"))), num(m.get("power")));
        }
    }

    /**
     * A cloud of mutagen. Every division inside it copies DNA far more sloppily,
     * so the player can deliberately crank the mutation rate on one corner of a
     * dish and see what falls out.
     */
    public static final class Mutagen {
        public double x, y, radius;
        public double secondsLeft;
        /** Multiplier applied to an organism's own variability while inside. */
        public double strength;

        public Mutagen(double x, double y, double radius, double secondsLeft, double strength) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.secondsLeft = secondsLeft;
            this.strength = strength;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("r", radius);
            m.put("left", secondsLeft);
            m.put("strength", strength);
            return m;
        }

        static Mutagen fromMap(Map<String, Object> m) {
            return new Mutagen(num(m.get("x")), num(m.get("y")),
                    Math.max(20, num(m.get("r"))), num(m.get("left")),
                    Math.max(1, num(m.get("strength"))));
        }
    }

    /** A lamp shone into a dark corner of the slide; burns down and goes out. */
    public static final class Spotlight {
        public double x, y, radius;
        public double secondsLeft;

        public Spotlight(double x, double y, double radius, double secondsLeft) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.secondsLeft = secondsLeft;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            m.put("r", radius);
            m.put("left", secondsLeft);
            return m;
        }

        static Spotlight fromMap(Map<String, Object> m) {
            return new Spotlight(num(m.get("x")), num(m.get("y")),
                    Math.max(20, num(m.get("r"))), num(m.get("left")));
        }
    }

    // --- events the scene turns into particles, floaters and toasts -----------------

    /** Something worth showing or scoring happened. */
    public enum EventKind { BIRTH, DEATH, KILL, EAT, BIND, TOOL }

    /**
     * A single notable moment, drained by the scene (for particles and toasts)
     * and by {@link EvolutionGame} (which catalogues the births and bindings).
     * {@code generation} is only meaningful on a {@link EventKind#BIRTH}, and
     * {@code detail} carries a colony signature or a tool name where relevant.
     */
    public record Event(EventKind kind, double x, double y, Color color,
                        Genome genome, int generation, String detail) {}

    // --- state -----------------------------------------------------------------------

    public String name;
    public final double radius;
    public double ambientTemp = AMBIENT_TEMP;

    private final List<Organism> organisms = new ArrayList<>();
    private final List<EnergyOrb> orbs = new ArrayList<>();
    private final List<Corpse> corpses = new ArrayList<>();
    private final List<Waste> wastes = new ArrayList<>();
    private final List<Barrier> barriers = new ArrayList<>();
    private final List<HeatSource> heatSources = new ArrayList<>();
    private final List<Spotlight> spotlights = new ArrayList<>();
    private final List<Mutagen> mutagens = new ArrayList<>();
    private final List<DishTool> tools = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    private final Random rng;
    private final long seed;
    /**
     * Unlit patches of slide, as {@code {x, y, radius}}. The lamp under the
     * stage does not light the dish evenly, so a few pools of shadow sit in it
     * where foraging is genuinely harder — which is what gives bioluminescence
     * and the spotlight tool something to be good at.
     */
    private final List<double[]> darkPatches = new ArrayList<>();
    private final double[] temperature = new double[TEMP_GRID * TEMP_GRID];
    private double tempAccumulator;
    private double broadcastAccumulator;
    private double clock;
    private int nextOrganismId = 1;
    private int nextColonyId = 1;
    /** Colony id -> the set of distinct strands in it, so re-binding is not re-scored. */
    private final Map<Integer, String> colonySignatures = new HashMap<>();

    // Rebuilt every step; lets a cell find its neighbours without scanning the dish.
    private final SpatialIndex organismIndex = new SpatialIndex();
    private final SpatialIndex orbIndex = new SpatialIndex();
    private final List<Organism> luminous = new ArrayList<>();

    public Dish(String name) {
        this(name, DEFAULT_RADIUS, new Random().nextLong());
    }

    public Dish(String name, double radius, long seed) {
        this.name = name;
        this.radius = radius;
        this.seed = seed;
        this.rng = new Random(seed);
        Arrays.fill(temperature, AMBIENT_TEMP);

        // Shadows are part of a dish's identity, so they come from its seed and
        // are laid out once, here, rather than drifting between sessions.
        Random shadowRng = new Random(seed * 31 + 7);
        int patches = 2 + shadowRng.nextInt(3);
        for (int i = 0; i < patches; i++) {
            double a = shadowRng.nextDouble() * Math.PI * 2;
            double d = radius * (0.25 + shadowRng.nextDouble() * 0.6);
            darkPatches.add(new double[]{
                    Math.cos(a) * d, Math.sin(a) * d,
                    radius * (0.18 + shadowRng.nextDouble() * 0.16)});
        }
    }

    /** The unlit pools on this slide, as {@code {x, y, radius}} (the scene shades these). */
    public List<double[]> darkPatches() { return darkPatches; }

    public long seed() { return seed; }

    // --- accessors --------------------------------------------------------------------

    public List<Organism> organisms() { return organisms; }

    public List<EnergyOrb> orbs() { return orbs; }

    public List<Corpse> corpses() { return corpses; }

    public List<Waste> wastes() { return wastes; }

    public List<Barrier> barriers() { return barriers; }

    public List<HeatSource> heatSources() { return heatSources; }

    public List<Spotlight> spotlights() { return spotlights; }

    public List<Mutagen> mutagens() { return mutagens; }

    public List<DishTool> tools() { return tools; }

    public double clock() { return clock; }

    /** Living cells right now. */
    public int population() { return organisms.size(); }

    /**
     * Whether this dish still has life in it — living cells, or bodies still
     * dissolving that a neighbouring dish's spatula transfer could feed on.
     */
    public boolean hasLife() { return !organisms.isEmpty(); }

    /** Distinct strands currently alive, i.e. how many species live here. */
    public int speciesCount() {
        Set<String> seen = new LinkedHashSet<>();
        for (Organism o : organisms) seen.add(o.genome.sequence());
        return seen.size();
    }

    /** Total energy banked in living cells, food, bodies and waste. */
    public double totalEnergy() {
        double sum = 0;
        for (Organism o : organisms) sum += o.energy;
        for (EnergyOrb orb : orbs) sum += orb.energy;
        for (Corpse c : corpses) sum += c.energy;
        for (Waste w : wastes) sum += w.amount;
        return sum;
    }

    /** Drain the notable moments since the last call. */
    public List<Event> drainEvents() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    public boolean contains(double x, double y) {
        return x * x + y * y <= radius * radius;
    }

    // --- seeding and player tools ---------------------------------------------------

    /** Place a new organism, clamped inside the glass. Returns it, or null if full. */
    public Organism spawn(Genome genome, double x, double y) {
        if (organisms.size() >= MAX_ORGANISMS) return null;
        double[] p = clampInside(x, y, Phenotype.of(genome).size());
        Organism o = new Organism(nextOrganismId++, genome, p[0], p[1]);
        o.wanderAngle = rng.nextDouble() * Math.PI * 2;
        organisms.add(o);
        return o;
    }

    /** Drop food in. Returns false when the dish is already saturated. */
    public boolean dropOrb(EnergyOrb.Kind kind, double x, double y) {
        if (orbs.size() >= MAX_ORBS) return false;
        double[] p = clampInside(x, y, 4);
        orbs.add(new EnergyOrb(kind, p[0], p[1]));
        return true;
    }

    /** Scatter {@code n} orbs at random inside the dish (the shop's bulk feed). */
    public int scatterOrbs(EnergyOrb.Kind kind, int n) {
        int placed = 0;
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = radius * Math.sqrt(rng.nextDouble()) * 0.95;
            if (dropOrb(kind, Math.cos(a) * r, Math.sin(a) * r)) placed++;
        }
        return placed;
    }

    public void addBarrier(double x, double y, double r) {
        double[] p = clampInside(x, y, r);
        barriers.add(new Barrier(p[0], p[1], r));
    }

    public void addHeatSource(double x, double y, double r, double power) {
        double[] p = clampInside(x, y, 0);
        heatSources.add(new HeatSource(p[0], p[1], r, power));
    }

    public void addSpotlight(double x, double y, double r, double seconds) {
        double[] p = clampInside(x, y, 0);
        spotlights.add(new Spotlight(p[0], p[1], r, seconds));
    }

    public void addMutagen(double x, double y, double r, double seconds, double strength) {
        double[] p = clampInside(x, y, 0);
        mutagens.add(new Mutagen(p[0], p[1], r, seconds, strength));
    }

    /** Multiplier on replication error for a division happening at this point. */
    public double mutagenAt(double x, double y) {
        double strongest = 1;
        for (Mutagen m : mutagens) {
            double d = Math.hypot(x - m.x, y - m.y);
            if (d <= m.radius) strongest = Math.max(strongest, m.strength);
        }
        return strongest;
    }

    public void dropTool(DishTool.Kind kind, double x, double y, double uses) {
        double[] p = clampInside(x, y, 0);
        tools.add(new DishTool(kind, p[0], p[1], uses));
    }

    /** Lift an organism out (the transfer spatula); it keeps all of its state. */
    public boolean remove(Organism o) {
        if (o.tool != null) {
            o.tool.carrierId = -1;
            o.tool = null;
        }
        return organisms.remove(o);
    }

    /** Re-home an organism lifted out of another dish. */
    public void adopt(Organism o, double x, double y) {
        double[] p = clampInside(x, y, o.pheno.size());
        Organism copy = new Organism(nextOrganismId++, o.genome, p[0], p[1]);
        copy.energy = o.energy;
        copy.age = o.age;
        copy.generation = o.generation;
        for (double[] m : o.memory) copy.remember(m[0], m[1]);
        copy.wanderAngle = rng.nextDouble() * Math.PI * 2;
        organisms.add(copy);
    }

    /** The organism nearest a point within {@code maxDist}, or {@code null}. */
    public Organism organismAt(double x, double y, double maxDist) {
        Organism best = null;
        double bestD = maxDist * maxDist;
        for (Organism o : organisms) {
            double d = (o.x - x) * (o.x - x) + (o.y - y) * (o.y - y);
            if (d <= bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    // --- environment queries ------------------------------------------------------------

    /** Gel temperature at a point, in °C (what the thermometer reads). */
    public double temperatureAt(double x, double y) {
        int gx = tempCell(x), gy = tempCell(y);
        return temperature[gy * TEMP_GRID + gx];
    }

    /**
     * How well lit a point is, in [0,1]. The slide is dim by default, so
     * bioluminescent strands — and the player's spotlight — genuinely change
     * what the cells around them can find.
     */
    public double illuminationAt(double x, double y) {
        double light = 1;
        for (double[] p : darkPatches) {
            // Shadow bites deepest at the middle of a patch and fades at its rim.
            light -= 0.85 * falloff(x - p[0], y - p[1], p[2]);
        }
        light = Math.max(0.1, light);
        for (Spotlight s : spotlights) {
            light += falloff(x - s.x, y - s.y, s.radius);
        }
        for (Organism o : luminous) {
            light += falloff(x - o.x, y - o.y, o.effectiveLightRadius()) * 0.9;
        }
        for (HeatSource h : heatSources) {
            if (h.power > 0) light += falloff(x - h.x, y - h.y, h.radius) * 0.35;
        }
        return Math.min(1, light);
    }

    /** Living cells that are currently emitting light (the scene draws these). */
    public List<Organism> luminousOrganisms() { return luminous; }

    // --- the simulation ------------------------------------------------------------------

    /**
     * Advance the whole dish. Large steps are sub-stepped internally, so a
     * fast-forwarding time warp stays as stable as real-time play.
     */
    public void step(double dt) {
        if (dt <= 0) return;
        while (dt > 0) {
            double slice = Math.min(MAX_STEP, dt);
            stepOnce(slice);
            dt -= slice;
        }
    }

    private void stepOnce(double dt) {
        clock += dt;
        rebuildIndices();
        updateTemperature(dt);

        broadcastAccumulator += dt;
        boolean broadcastTick = broadcastAccumulator >= BROADCAST_INTERVAL;
        if (broadcastTick) broadcastAccumulator = 0;

        Map<Integer, List<Organism>> colonies = gatherColonies();
        applyColonyEffects(colonies, dt);

        List<Organism> newborns = new ArrayList<>();
        for (int i = 0; i < organisms.size(); i++) {
            Organism o = organisms.get(i);
            if (!o.alive) continue;
            updateOrganism(o, dt, broadcastTick, newborns);
        }
        organisms.addAll(newborns);

        decayCorpses(dt);
        decayWaste(dt);
        updateSpotlights(dt);
        updateTools(dt);
        sweepDead();
    }

    private void updateOrganism(Organism o, double dt, boolean broadcastTick,
                                List<Organism> newborns) {
        o.age += dt;
        if (o.venom > 0) o.venom = Math.max(0, o.venom - dt);
        if (o.attackCooldown > 0) o.attackCooldown -= dt;
        if (o.hitFlash > 0) o.hitFlash = Math.max(0, o.hitFlash - dt);

        // Upkeep: base metabolism, plus crowding, plus thermal stress. This is
        // the whole selective pressure in three lines.
        double crowd = crowdingAt(o);
        double temp = temperatureAt(o.x, o.y);
        double stress = Math.max(0, Math.abs(temp - o.pheno.tempOptimum()) - o.pheno.tempTolerance());
        o.energy -= (o.pheno.metabolism() + crowd * 0.22 + stress * 0.16) * dt;

        steerAndMove(o, dt);
        eat(o, dt);
        if (o.pheno.has(Ability.PREDATION)) hunt(o);
        if (o.pheno.has(Ability.SHARING)) share(o, dt);
        if (broadcastTick && o.pheno.has(Ability.BROADCAST)) broadcast(o);
        if (o.pheno.has(Ability.TOOL_USE) && o.tool == null) pickUpTool(o);
        if (o.pheno.has(Ability.MULTICELLULAR)) tryBind(o);

        if (o.energy >= Organism.REPLICATE_AT && organisms.size() + newborns.size() < MAX_ORGANISMS) {
            Organism child = o.divide(nextOrganismId++, rng, mutagenAt(o.x, o.y));
            double[] p = clampInside(child.x, child.y, child.pheno.size());
            child.x = p[0];
            child.y = p[1];
            newborns.add(child);
            events.add(new Event(EventKind.BIRTH, child.x, child.y, child.pheno.color(),
                    child.genome, child.generation, null));
        }

        if (o.energy <= 0 || o.age > o.pheno.lifespan()) {
            die(o, EventKind.DEATH);
        }
    }

    /**
     * Pick somewhere to be and swim there. In priority order a cell flees a
     * predator it recognised, chases food it can see, hunts prey if it is a
     * predator, returns to food it remembers, or wanders.
     *
     * <p>Sight range is scaled by how well lit the spot is, so a dark corner of
     * the slide really is harder to forage in — which is what gives light
     * emission and the spotlight tool their teeth.
     */
    private void steerAndMove(Organism o, double dt) {
        double light = 0.35 + 0.65 * illuminationAt(o.x, o.y);
        double sense = o.pheno.senseRadius() * light;
        o.goalTimer -= dt;

        Organism threat = o.pheno.recognition() > 0.15 ? nearestThreat(o, sense) : null;
        if (threat != null) {
            // Run directly away; fleeing overrides whatever it was doing.
            o.goal = new double[]{o.x + (o.x - threat.x) * 3, o.y + (o.y - threat.y) * 3};
            o.goalTimer = 0.6;
        } else if (o.goal == null || o.goalTimer <= 0) {
            EnergyOrb food = nearestVisibleFood(o, sense);
            if (food != null) {
                o.goal = new double[]{food.x, food.y};
                o.goalTimer = 2.5;
            } else if (o.pheno.has(Ability.PREDATION)) {
                Organism prey = nearestPrey(o, sense);
                o.goal = prey != null ? new double[]{prey.x, prey.y} : null;
                o.goalTimer = 1.5;
            }
            if (o.goal == null) {
                double[] mem = o.closestMemory();
                if (mem != null && dist(o.x, o.y, mem[0], mem[1]) > 20) {
                    o.goal = new double[]{mem[0], mem[1]};
                    o.goalTimer = 3;
                } else {
                    if (mem != null) o.memory.remove(mem); // nothing there any more
                    o.wanderAngle += (rng.nextDouble() - 0.5) * 1.6;
                    o.goal = new double[]{
                            o.x + Math.cos(o.wanderAngle) * 120,
                            o.y + Math.sin(o.wanderAngle) * 120};
                    o.goalTimer = 1.2 + rng.nextDouble();
                }
            }
        }

        double gx = o.goal[0] - o.x;
        double gy = o.goal[1] - o.y;
        double len = Math.hypot(gx, gy);
        if (len > 0.001) {
            gx /= len;
            gy /= len;
        }
        // Steer around pillars rather than grinding along them.
        double[] avoid = avoidBarriers(o);
        gx += avoid[0] * 1.8;
        gy += avoid[1] * 1.8;
        double al = Math.hypot(gx, gy);
        if (al > 0.001) {
            gx /= al;
            gy /= al;
        }

        double speed = o.effectiveSpeed();
        double blend = Math.min(1, dt * 6);
        o.vx += (gx * speed - o.vx) * blend;
        o.vy += (gy * speed - o.vy) * blend;
        o.x += o.vx * dt;
        o.y += o.vy * dt;

        resolveCollisions(o);
    }

    /** Push out of pillars and back inside the glass, killing the wall-normal velocity. */
    private void resolveCollisions(Organism o) {
        double size = o.pheno.size();
        for (Barrier b : barriers) {
            double dx = o.x - b.x, dy = o.y - b.y;
            double d = Math.hypot(dx, dy);
            double min = b.radius + size;
            if (d < min && d > 0.001) {
                o.x = b.x + dx / d * min;
                o.y = b.y + dy / d * min;
                o.vx *= 0.4;
                o.vy *= 0.4;
                o.goalTimer = 0; // rethink where it was going
            }
        }
        double[] p = clampInside(o.x, o.y, size);
        if (p[0] != o.x || p[1] != o.y) {
            o.x = p[0];
            o.y = p[1];
            o.vx *= -0.4;
            o.vy *= -0.4;
            o.goalTimer = 0;
        }
    }

    /** A unit push away from any pillar directly in front of the cell. */
    private double[] avoidBarriers(Organism o) {
        double ax = 0, ay = 0;
        double look = o.pheno.size() + 26;
        for (Barrier b : barriers) {
            double dx = o.x - b.x, dy = o.y - b.y;
            double d = Math.hypot(dx, dy);
            double range = b.radius + look;
            if (d < range && d > 0.001) {
                double push = (range - d) / range;
                ax += dx / d * push;
                ay += dy / d * push;
            }
        }
        return new double[]{ax, ay};
    }

    private void eat(Organism o, double dt) {
        double reach = o.pheno.size() + 5;
        final double[] gained = {0};
        orbIndex.query(o.x, o.y, reach + 6, i -> {
            EnergyOrb orb = orbs.get(i);
            if (orb.spent()) return;
            if (orb.kind == EnergyOrb.Kind.COMPLEX && !o.pheno.has(Ability.COMPLEX_DIGESTION)) return;
            if (dist(o.x, o.y, orb.x, orb.y) > reach + orb.radius()) return;
            double bite = Math.min(orb.energy, o.pheno.eatRate() * dt);
            orb.energy -= bite;
            gained[0] += bite;
            o.remember(orb.x, orb.y);
            if (orb.spent()) {
                o.goal = null;   // nothing left here; look for the next meal now
                o.goalTimer = 0;
            }
        });
        if (gained[0] <= 0) return;

        double eff = o.effectiveEfficiency();
        o.energy += gained[0] * eff;
        double waste = gained[0] * (1 - eff);
        if (waste > 0.4) addWaste(o.x, o.y, waste);
        if (gained[0] > 2) {
            events.add(new Event(EventKind.EAT, o.x, o.y, o.pheno.color(), null, 0, null));
        }
    }

    /**
     * Attack a neighbour that is not kin. Success is attack versus defence with
     * a random swing, so a hunt is never a certainty; a kill hands the victim's
     * banked energy (and part of its body) to the hunter, minus digestive loss.
     */
    private void hunt(Organism o) {
        if (o.attackCooldown > 0) return;
        double reach = o.pheno.size() * 1.5 + 6;
        Organism[] target = {null};
        double[] bestD = {Double.MAX_VALUE};
        organismIndex.query(o.x, o.y, reach + 14, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive) return;
            if (o.genome.distanceTo(other.genome) <= 1) return; // never eats its own strand
            double d = dist(o.x, o.y, other.x, other.y);
            if (d < reach + other.pheno.size() && d < bestD[0]) {
                bestD[0] = d;
                target[0] = other;
            }
        });
        Organism victim = target[0];
        if (victim == null) return;

        o.attackCooldown = ATTACK_COOLDOWN;
        double swing = 0.6 + rng.nextDouble() * 0.8;
        double defence = victim.pheno.defense() * (victim.colonyId >= 0 ? 1.25 : 1.0);
        if (o.effectiveAttack() * swing > defence) {
            double spoils = (victim.energy + victim.pheno.size() * 2.2) * o.effectiveEfficiency();
            o.energy += spoils;
            events.add(new Event(EventKind.KILL, victim.x, victim.y, o.pheno.color(),
                    victim.genome, victim.generation, null));
            die(victim, null); // the kill event already covers it
        } else {
            victim.energy -= 5;
            victim.hitFlash = 0.35;
            if (o.pheno.has(Ability.VENOM)) victim.venom = 6;
            // Kin defence: anything of the victim's strand nearby hits back.
            organismIndex.query(victim.x, victim.y, SHARE_RANGE, i -> {
                Organism ally = organisms.get(i);
                if (ally == victim || !ally.alive) return;
                if (!ally.pheno.has(Ability.KIN_DEFENSE)) return;
                if (ally.genome.distanceTo(victim.genome) > 2) return;
                o.energy -= 4;
                o.hitFlash = 0.35;
            });
        }
    }

    /** Donate energy to a starving cell of the same strand. */
    private void share(Organism o, double dt) {
        if (o.energy < Organism.REPLICATE_AT * 0.35) return;
        organismIndex.query(o.x, o.y, SHARE_RANGE, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive || !other.starving()) return;
            if (o.genome.distanceTo(other.genome) > 3) return;
            double give = Math.min(7 * dt, o.energy * 0.2);
            o.energy -= give;
            other.energy += give;
        });
    }

    /** Tell everyone in earshot where the food is. */
    private void broadcast(Organism o) {
        if (o.memory.isEmpty()) return;
        double range = o.pheno.senseRadius() * 2.5;
        organismIndex.query(o.x, o.y, range, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive || other.pheno.memorySlots() <= 0) return;
            for (double[] m : o.memory) other.remember(m[0], m[1]);
        });
    }

    private void pickUpTool(Organism o) {
        for (DishTool t : tools) {
            if (t.held() || t.spent()) continue;
            if (dist(o.x, o.y, t.x, t.y) > o.pheno.size() + 10) continue;
            t.carrierId = o.id;
            o.tool = t;
            events.add(new Event(EventKind.TOOL, o.x, o.y, t.kind.color(), o.genome,
                    o.generation, t.kind.displayName()));
            return;
        }
    }

    /**
     * Bind to a neighbouring multicellular cell. Colonies merge rather than
     * compete, so a dish that discovers multicellularity tends to consolidate
     * into a few large bodies — and each new <em>combination</em> of strands is
     * a discovery in its own right.
     */
    private void tryBind(Organism o) {
        double range = o.pheno.size() * 2.4;
        organismIndex.query(o.x, o.y, range + 12, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive || !other.pheno.has(Ability.MULTICELLULAR)) return;
            if (dist(o.x, o.y, other.x, other.y) > range + other.pheno.size()) return;
            if (o.colonyId >= 0 && o.colonyId == other.colonyId) return;

            if (o.colonyId < 0 && other.colonyId < 0) {
                int id = nextColonyId++;
                o.colonyId = id;
                other.colonyId = id;
            } else if (o.colonyId < 0) {
                o.colonyId = other.colonyId;
            } else if (other.colonyId < 0) {
                other.colonyId = o.colonyId;
            } else {
                int keep = Math.min(o.colonyId, other.colonyId);
                int drop = Math.max(o.colonyId, other.colonyId);
                for (Organism m : organisms) if (m.colonyId == drop) m.colonyId = keep;
                colonySignatures.remove(drop);
            }
            events.add(new Event(EventKind.BIND, o.x, o.y, o.pheno.color(), o.genome,
                    o.generation, null));
        });
    }

    private Map<Integer, List<Organism>> gatherColonies() {
        Map<Integer, List<Organism>> byId = new LinkedHashMap<>();
        for (Organism o : organisms) {
            if (o.colonyId >= 0) byId.computeIfAbsent(o.colonyId, k -> new ArrayList<>()).add(o);
        }
        // A colony of one is just a cell; let it go.
        byId.entrySet().removeIf(e -> {
            if (e.getValue().size() < 2) {
                e.getValue().get(0).colonyId = -1;
                colonySignatures.remove(e.getKey());
                return true;
            }
            return false;
        });
        colonySignatures.keySet().retainAll(byId.keySet());
        return byId;
    }

    /**
     * Colony life: members pool energy toward the colony average and are pulled
     * gently toward its centre, so a colony moves and starves as one body. A
     * colony whose membership of <em>distinct strands</em> has changed emits a
     * {@link EventKind#BIND} event carrying its signature, which is what the
     * catalog scores as a new combination.
     */
    private void applyColonyEffects(Map<Integer, List<Organism>> colonies, double dt) {
        for (Map.Entry<Integer, List<Organism>> e : colonies.entrySet()) {
            List<Organism> members = e.getValue();
            double cx = 0, cy = 0, energy = 0;
            Set<String> strands = new TreeSet<>();
            for (Organism m : members) {
                cx += m.x;
                cy += m.y;
                energy += m.energy;
                strands.add(m.genome.sequence());
            }
            int n = members.size();
            cx /= n;
            cy /= n;
            double mean = energy / n;
            double pull = Math.min(1, dt * 0.9);
            for (Organism m : members) {
                m.energy += (mean - m.energy) * Math.min(1, dt * 0.5);
                m.vx += (cx - m.x) * pull * 0.6;
                m.vy += (cy - m.y) * pull * 0.6;
            }

            String signature = String.join("+", strands);
            if (strands.size() >= 2 && !signature.equals(colonySignatures.get(e.getKey()))) {
                colonySignatures.put(e.getKey(), signature);
                events.add(new Event(EventKind.BIND, cx, cy,
                        members.get(0).pheno.color(), null, 0, signature));
            }
        }
    }

    /** Kill a cell: it drops whatever it carried and becomes a dissolving body. */
    private void die(Organism o, EventKind event) {
        if (!o.alive) return;
        o.alive = false;
        if (o.tool != null) {
            o.tool.carrierId = -1;
            o.tool.x = o.x;
            o.tool.y = o.y;
            o.tool = null;
        }
        double body = Math.max(4, o.pheno.size() * 2.4 + Math.max(0, o.energy));
        corpses.add(new Corpse(o.x, o.y, body, o.pheno.decomposition(), o.pheno.color()));
        if (event != null) {
            events.add(new Event(event, o.x, o.y, o.pheno.color(), o.genome,
                    o.generation, null));
        }
    }

    private void addWaste(double x, double y, double amount) {
        // Coalesce with a nearby pile rather than littering the dish with motes.
        for (Waste w : wastes) {
            if (Math.abs(w.x - x) < 18 && Math.abs(w.y - y) < 18) {
                w.amount += amount;
                return;
            }
        }
        if (wastes.size() < 400) wastes.add(new Waste(x, y, amount));
    }

    /**
     * Bodies dissolve into orbs at their strand's own decomposition rate. Decay
     * is strictly energy-neutral: what leaves the body accumulates and is handed
     * back to the dish as whole orbs, so nothing is created or quietly lost —
     * the recycling loop is exactly as generous as the bodies feeding it.
     */
    private void decayCorpses(double dt) {
        final double orbSize = 6;
        for (int i = corpses.size() - 1; i >= 0; i--) {
            Corpse c = corpses.get(i);
            c.age += dt;
            double release = Math.min(c.energy, 4.0 * c.rate * dt);
            c.energy -= release;
            c.pending += release;
            while (c.pending >= orbSize && orbs.size() < MAX_ORBS) {
                c.pending -= orbSize;
                spawnDecayOrb(c, orbSize);
            }
            if (c.energy <= 0.01) {
                if (c.pending > 0.5 && orbs.size() < MAX_ORBS) spawnDecayOrb(c, c.pending);
                corpses.remove(i);
            }
        }
    }

    private void spawnDecayOrb(Corpse c, double energy) {
        double a = rng.nextDouble() * Math.PI * 2;
        double r = rng.nextDouble() * 14;
        double[] p = clampInside(c.x + Math.cos(a) * r, c.y + Math.sin(a) * r, 3);
        orbs.add(new EnergyOrb(EnergyOrb.Kind.SIMPLE, p[0], p[1], energy));
    }

    /** Waste re-mineralises far more slowly than a body does. */
    private void decayWaste(double dt) {
        for (int i = wastes.size() - 1; i >= 0; i--) {
            Waste w = wastes.get(i);
            w.age += dt;
            if (w.age < 12) continue;
            if (orbs.size() < MAX_ORBS) {
                orbs.add(new EnergyOrb(EnergyOrb.Kind.SIMPLE, w.x, w.y, w.amount * 0.85));
            }
            wastes.remove(i);
        }
    }

    private void updateSpotlights(double dt) {
        for (int i = spotlights.size() - 1; i >= 0; i--) {
            Spotlight s = spotlights.get(i);
            s.secondsLeft -= dt;
            if (s.secondsLeft <= 0) spotlights.remove(i);
        }
        for (int i = mutagens.size() - 1; i >= 0; i--) {
            Mutagen m = mutagens.get(i);
            m.secondsLeft -= dt;
            if (m.secondsLeft <= 0) mutagens.remove(i);
        }
    }

    private void updateTools(double dt) {
        for (int i = tools.size() - 1; i >= 0; i--) {
            DishTool t = tools.get(i);
            if (t.held()) t.uses -= dt;
            if (t.spent()) {
                for (Organism o : organisms) {
                    if (o.tool == t) o.tool = null;
                }
                tools.remove(i);
            }
        }
    }

    private void sweepDead() {
        organisms.removeIf(o -> !o.alive);
        orbs.removeIf(EnergyOrb::spent);
    }

    // --- environment simulation --------------------------------------------------------

    /**
     * Diffuse heat, apply the player's sources and the dish's own exothermic and
     * endothermic strands, then relax gently back toward room temperature.
     * Runs on its own slower cadence — temperature does not need 120 Hz.
     */
    private void updateTemperature(double dt) {
        tempAccumulator += dt;
        if (tempAccumulator < TEMP_INTERVAL) return;
        double step = tempAccumulator;
        tempAccumulator = 0;

        for (HeatSource h : heatSources) {
            applyHeat(h.x, h.y, h.radius, h.power * step);
        }
        for (Organism o : organisms) {
            if (o.pheno.has(Ability.EXOTHERMY)) applyHeat(o.x, o.y, 70, 5.5 * step);
            if (o.pheno.has(Ability.ENDOTHERMY)) applyHeat(o.x, o.y, 70, -5.5 * step);
        }

        double[] next = new double[temperature.length];
        double blend = Math.min(0.5, step * 1.2);
        for (int gy = 0; gy < TEMP_GRID; gy++) {
            for (int gx = 0; gx < TEMP_GRID; gx++) {
                int i = gy * TEMP_GRID + gx;
                double sum = 0;
                int n = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int nx = gx + ox, ny = gy + oy;
                        if (nx < 0 || ny < 0 || nx >= TEMP_GRID || ny >= TEMP_GRID) continue;
                        sum += temperature[ny * TEMP_GRID + nx];
                        n++;
                    }
                }
                double diffused = temperature[i] + (sum / n - temperature[i]) * blend;
                next[i] = diffused + (ambientTemp - diffused) * Math.min(0.4, step * 0.35);
            }
        }
        System.arraycopy(next, 0, temperature, 0, temperature.length);
    }

    private void applyHeat(double x, double y, double r, double degrees) {
        int gx = tempCell(x), gy = tempCell(y);
        int span = Math.max(1, (int) Math.ceil(r / (2 * radius / TEMP_GRID)));
        for (int oy = -span; oy <= span; oy++) {
            for (int ox = -span; ox <= span; ox++) {
                int nx = gx + ox, ny = gy + oy;
                if (nx < 0 || ny < 0 || nx >= TEMP_GRID || ny >= TEMP_GRID) continue;
                double falloff = 1 - Math.hypot(ox, oy) / (span + 0.5);
                if (falloff <= 0) continue;
                temperature[ny * TEMP_GRID + nx] += degrees * falloff;
            }
        }
    }

    private int tempCell(double v) {
        int c = (int) ((v + radius) / (2 * radius) * TEMP_GRID);
        return Math.max(0, Math.min(TEMP_GRID - 1, c));
    }

    // --- neighbour queries ----------------------------------------------------------------

    /**
     * Rebuild the neighbour grids for this tick. Organisms then move while the
     * grid still holds their positions from the start of the tick — a drift of
     * at most one tick of swimming, well inside the 64-unit cell, so a lookup
     * can at worst miss a neighbour that just crossed a cell boundary and will
     * be found again next tick. Rebuilding after every move instead would cost
     * far more than that is worth.
     */
    private void rebuildIndices() {
        organismIndex.build(-radius, -radius, radius * 2, 64, organisms.size(),
                i -> organisms.get(i).x, i -> organisms.get(i).y);
        orbIndex.build(-radius, -radius, radius * 2, 64, orbs.size(),
                i -> orbs.get(i).x, i -> orbs.get(i).y);
        luminous.clear();
        for (Organism o : organisms) {
            if (o.effectiveLightRadius() > 0) luminous.add(o);
        }
    }

    private double crowdingAt(Organism o) {
        int[] n = {0};
        organismIndex.query(o.x, o.y, CROWD_RADIUS, i -> {
            Organism other = organisms.get(i);
            if (other != o && dist(o.x, o.y, other.x, other.y) <= CROWD_RADIUS) n[0]++;
        });
        return Math.min(8, n[0]);
    }

    /** The closest orb this cell can both see and digest, respecting line of sight. */
    private EnergyOrb nearestVisibleFood(Organism o, double sense) {
        EnergyOrb[] best = {null};
        double[] bestD = {sense};
        boolean canNavigate = o.pheno.trait(Trait.VISION) >= 6;
        orbIndex.query(o.x, o.y, sense, i -> {
            EnergyOrb orb = orbs.get(i);
            if (orb.spent()) return;
            if (orb.kind == EnergyOrb.Kind.COMPLEX && !o.pheno.has(Ability.COMPLEX_DIGESTION)) return;
            double d = dist(o.x, o.y, orb.x, orb.y);
            if (d >= bestD[0]) return;
            // Food behind a pillar is only worth chasing if the cell can plan
            // its way around one.
            if (!canNavigate && blocked(o.x, o.y, orb.x, orb.y)) return;
            bestD[0] = d;
            best[0] = orb;
        });
        return best[0];
    }

    private Organism nearestPrey(Organism o, double sense) {
        Organism[] best = {null};
        double[] bestD = {sense};
        organismIndex.query(o.x, o.y, sense, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive) return;
            if (o.genome.distanceTo(other.genome) <= 1) return;
            double d = dist(o.x, o.y, other.x, other.y);
            if (d < bestD[0]) {
                bestD[0] = d;
                best[0] = other;
            }
        });
        return best[0];
    }

    /**
     * The nearest predator this cell recognises as dangerous. Recognition is a
     * chance to read the threat at all, so a strand with weak pattern
     * recognition sometimes swims straight into a hunter's mouth.
     */
    private Organism nearestThreat(Organism o, double sense) {
        Organism[] best = {null};
        double[] bestD = {sense};
        organismIndex.query(o.x, o.y, sense, i -> {
            Organism other = organisms.get(i);
            if (other == o || !other.alive || !other.pheno.has(Ability.PREDATION)) return;
            if (o.genome.distanceTo(other.genome) <= 1) return;
            if (rng.nextDouble() > o.pheno.recognition()) return;
            double d = dist(o.x, o.y, other.x, other.y);
            if (d < bestD[0]) {
                bestD[0] = d;
                best[0] = other;
            }
        });
        return best[0];
    }

    /** Whether a pillar sits on the straight line between two points. */
    private boolean blocked(double x1, double y1, double x2, double y2) {
        for (Barrier b : barriers) {
            if (segmentHitsCircle(x1, y1, x2, y2, b.x, b.y, b.radius)) return true;
        }
        return false;
    }

    private static boolean segmentHitsCircle(double x1, double y1, double x2, double y2,
                                             double cx, double cy, double r) {
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0.0001 ? 0 : ((cx - x1) * dx + (cy - y1) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double px = x1 + dx * t, py = y1 + dy * t;
        return (px - cx) * (px - cx) + (py - cy) * (py - cy) <= r * r;
    }

    private double[] clampInside(double x, double y, double margin) {
        double limit = Math.max(1, radius - margin - 2);
        double d = Math.hypot(x, y);
        if (d <= limit) return new double[]{x, y};
        if (d < 0.001) return new double[]{limit, 0};
        return new double[]{x / d * limit, y / d * limit};
    }

    private static double falloff(double dx, double dy, double r) {
        if (r <= 0) return 0;
        double t = 1 - Math.hypot(dx, dy) / r;
        return t <= 0 ? 0 : t * t;
    }

    private static double dist(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // --- persistence ------------------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("radius", radius);
        m.put("ambient", ambientTemp);
        m.put("seed", seed);
        m.put("clock", clock);
        m.put("nextOrganismId", nextOrganismId);
        m.put("nextColonyId", nextColonyId);
        m.put("organisms", mapAll(organisms, Organism::toMap));
        m.put("orbs", mapAll(orbs, EnergyOrb::toMap));
        m.put("corpses", mapAll(corpses, Corpse::toMap));
        m.put("waste", mapAll(wastes, Waste::toMap));
        m.put("barriers", mapAll(barriers, Barrier::toMap));
        m.put("heat", mapAll(heatSources, HeatSource::toMap));
        m.put("spotlights", mapAll(spotlights, Spotlight::toMap));
        m.put("mutagens", mapAll(mutagens, Mutagen::toMap));
        m.put("tools", mapAll(tools, DishTool::toMap));
        return m;
    }

    private static <T> List<Object> mapAll(List<T> items,
                                           java.util.function.Function<T, Map<String, Object>> f) {
        List<Object> out = new ArrayList<>(items.size());
        for (T t : items) out.add(f.apply(t));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** Rebuild a dish from a save. Unreadable entries are skipped, never fatal. */
    public static Dish fromMap(Map<String, Object> m, long seed) {
        String name = m.get("name") == null ? "Dish" : String.valueOf(m.get("name"));
        double radius = m.containsKey("radius") ? num(m.get("radius")) : DEFAULT_RADIUS;
        // The dish's own seed laid out its shadows, so a save restores them by
        // restoring the seed; the caller's seed is only a fallback for old saves.
        long dishSeed = m.get("seed") instanceof Number n ? n.longValue() : seed;
        Dish d = new Dish(name, radius <= 0 ? DEFAULT_RADIUS : radius, dishSeed);
        d.ambientTemp = m.containsKey("ambient") ? num(m.get("ambient")) : AMBIENT_TEMP;
        d.clock = num(m.get("clock"));

        for (Map<String, Object> om : listOf(m.get("organisms"))) {
            Organism o = Organism.fromMap(om);
            if (o != null) d.organisms.add(o);
        }
        for (Map<String, Object> om : listOf(m.get("orbs"))) d.orbs.add(EnergyOrb.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("corpses"))) d.corpses.add(Corpse.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("waste"))) d.wastes.add(Waste.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("barriers"))) d.barriers.add(Barrier.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("heat"))) d.heatSources.add(HeatSource.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("spotlights"))) {
            d.spotlights.add(Spotlight.fromMap(om));
        }
        for (Map<String, Object> om : listOf(m.get("mutagens"))) d.mutagens.add(Mutagen.fromMap(om));
        for (Map<String, Object> om : listOf(m.get("tools"))) d.tools.add(DishTool.fromMap(om));

        // Re-link carried tools, and make sure fresh ids never collide with loaded ones.
        for (DishTool t : d.tools) {
            if (!t.held()) continue;
            boolean linked = false;
            for (Organism o : d.organisms) {
                if (o.id == t.carrierId) {
                    o.tool = t;
                    linked = true;
                    break;
                }
            }
            if (!linked) t.carrierId = -1;
        }
        int maxId = 0;
        int maxColony = 0;
        for (Organism o : d.organisms) {
            maxId = Math.max(maxId, o.id);
            maxColony = Math.max(maxColony, o.colonyId);
        }
        d.nextOrganismId = Math.max((int) num(m.get("nextOrganismId")), maxId + 1);
        d.nextColonyId = Math.max((int) num(m.get("nextColonyId")), maxColony + 1);
        return d;
    }

    // --- spatial index ----------------------------------------------------------------------

    /**
     * A uniform grid over the dish, rebuilt each tick, so neighbour lookups cost
     * roughly nothing instead of scanning every cell in the dish. Stored as
     * counting-sorted buckets (a start table plus an item table) to keep the
     * per-tick rebuild allocation-free once it has warmed up.
     */
    private static final class SpatialIndex {
        private double minX, minY, cellSize = 1;
        private int cols, rows;
        private int[] start = new int[1];
        private int[] items = new int[1];
        private int count;

        void build(double minX, double minY, double size, double cellSize,
                   int n, IntToDoubleFunction xs, IntToDoubleFunction ys) {
            this.minX = minX;
            this.minY = minY;
            this.cellSize = cellSize;
            this.cols = Math.max(1, (int) Math.ceil(size / cellSize));
            this.rows = cols;
            this.count = n;

            int cells = cols * rows;
            if (start.length < cells + 1) start = new int[cells + 1];
            else Arrays.fill(start, 0, cells + 1, 0);
            if (items.length < Math.max(1, n)) items = new int[Math.max(16, n * 2)];
            if (n == 0) return;

            for (int i = 0; i < n; i++) start[cellIndex(xs.applyAsDouble(i), ys.applyAsDouble(i)) + 1]++;
            for (int c = 0; c < cells; c++) start[c + 1] += start[c];
            int[] cursor = new int[cells];
            for (int i = 0; i < n; i++) {
                int c = cellIndex(xs.applyAsDouble(i), ys.applyAsDouble(i));
                items[start[c] + cursor[c]++] = i;
            }
        }

        /** Visit every item whose cell overlaps the box around ({@code x},{@code y}). */
        void query(double x, double y, double r, IntConsumer visit) {
            if (count == 0) return;
            int x0 = clampCol((int) Math.floor((x - r - minX) / cellSize));
            int x1 = clampCol((int) Math.floor((x + r - minX) / cellSize));
            int y0 = clampRow((int) Math.floor((y - r - minY) / cellSize));
            int y1 = clampRow((int) Math.floor((y + r - minY) / cellSize));
            for (int gy = y0; gy <= y1; gy++) {
                for (int gx = x0; gx <= x1; gx++) {
                    int c = gy * cols + gx;
                    for (int k = start[c]; k < start[c + 1]; k++) visit.accept(items[k]);
                }
            }
        }

        private int cellIndex(double x, double y) {
            int gx = clampCol((int) Math.floor((x - minX) / cellSize));
            int gy = clampRow((int) Math.floor((y - minY) / cellSize));
            return gy * cols + gx;
        }

        private int clampCol(int c) { return Math.max(0, Math.min(cols - 1, c)); }

        private int clampRow(int r) { return Math.max(0, Math.min(rows - 1, r)); }
    }
}
