package com.larsons.engine.evolution;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The whole experiment: every Petri dish, the credit balance, the bench
 * inventory, the reference book, and the rules that tie them together.
 *
 * <p>This is the game layer above the biology. {@link Dish} decides what
 * happens to cells; this class decides what it <em>means</em> — which strands
 * count as discoveries, what they pay, what the player can buy with that, and
 * when the run is over.
 *
 * <p>The loop the design document lays out, end to end:
 * <ol>
 *   <li>Start with one dish, one square organism of the chosen colour, and a
 *       hundred energy orbs to place by hand.</li>
 *   <li>Cells eat, replicate imperfectly, and are pruned by hunger, heat,
 *       crowding and each other.</li>
 *   <li>Every strand that has never existed before is catalogued and pays shop
 *       credit scaled to how complex it is — as does every new colony
 *       combination that multicellular strands invent between themselves.</li>
 *   <li>Credit buys food, dishes, instruments and pressures, which produce more
 *       novelty, which pays more credit.</li>
 *   <li>The run ends when every dish is dead and there is no way left to seed
 *       another one.</li>
 * </ol>
 *
 * <p>Deliberately non-deterministic: unless a seed is supplied for tests, each
 * run draws a fresh one, so no two experiments unfold the same way.
 */
public final class EvolutionGame {

    /** Time-warp settings the dial cycles through (needs the instrument). */
    public static final double[] TIME_SCALES = {0.25, 0.5, 1, 2, 4, 8};

    /** A short line of feedback for the HUD to pop as a toast. */
    public record Notice(String text, Color color) {}

    private final List<Dish> dishes = new ArrayList<>();
    private final Inventory inventory;
    /** This game's book. A reset empties it. */
    private final Catalog catalog;
    /** The player's permanent record. A reset never touches it. */
    private final History history;
    private final Random rng;
    private final long seed;

    private int activeDish;
    private int credits;
    private double timeScale = 1;
    private double playTime;
    private boolean gameOver;
    private Nucleotide startingColor = Nucleotide.G;

    /** Organism currently on the spatula, mid-transfer between dishes. */
    private Organism carried;
    private int carriedFrom = -1;

    private final List<Notice> notices = new ArrayList<>();
    /**
     * Events from the dish currently on the stage, kept for the scene to turn
     * into particles. Capped, so running headless (tests, a long fast-forward
     * with nobody watching) cannot grow it without bound.
     */
    private final List<Dish.Event> presentation = new ArrayList<>();
    private static final int MAX_PRESENTATION_EVENTS = 400;

    private EvolutionGame(long seed, Inventory inventory, Catalog catalog, History history) {
        this.seed = seed;
        this.rng = new Random(seed);
        this.inventory = inventory;
        this.catalog = catalog;
        this.history = history;
    }

    /**
     * Begin a new experiment: one dish, one square organism of the chosen
     * colour swimming in it, and a hundred energy orbs on the bench waiting to
     * be dropped in wherever the player likes.
     */
    public static EvolutionGame newGame(Nucleotide startingColor) {
        return newGame(startingColor, new Random().nextLong());
    }

    /** As {@link #newGame(Nucleotide)}, with a fixed seed (tests use this). */
    public static EvolutionGame newGame(Nucleotide startingColor, long seed) {
        return newGame(startingColor, seed, new History());
    }

    /**
     * Begin a new game against an existing permanent record — what the lobby
     * does, so a new experiment starts with an empty book of its own but keeps
     * everything the player has ever discovered.
     */
    public static EvolutionGame newGame(Nucleotide startingColor, long seed, History history) {
        EvolutionGame g = new EvolutionGame(seed, Inventory.starting(), new Catalog(),
                history == null ? new History() : history);
        g.startingColor = startingColor;
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, seed);
        g.dishes.add(dish);
        Organism first = dish.spawn(Genome.starter(startingColor), 0, 0);
        if (first != null) g.catalogue(dish, first.genome, first.generation);
        return g;
    }

    // --- accessors -----------------------------------------------------------------

    public List<Dish> dishes() { return dishes; }

    public Dish activeDish() { return dishes.get(Math.max(0, Math.min(dishes.size() - 1, activeDish))); }

    public int activeDishIndex() { return activeDish; }

    public void setActiveDish(int index) {
        if (index >= 0 && index < dishes.size()) activeDish = index;
    }

    /** Move to the next dish, wrapping around. */
    public void cycleDish(int delta) {
        if (dishes.isEmpty()) return;
        activeDish = Math.floorMod(activeDish + delta, dishes.size());
    }

    public Inventory inventory() { return inventory; }

    /** This game's book of discoveries. Emptied by a reset. */
    public Catalog catalog() { return catalog; }

    /** Every organism this player has ever discovered, in any game. */
    public History history() { return history; }

    public int credits() { return credits; }

    public double timeScale() { return timeScale; }

    public double playTime() { return playTime; }

    public boolean isGameOver() { return gameOver; }

    public long seed() { return seed; }

    public Nucleotide startingColor() { return startingColor; }

    public Organism carried() { return carried; }

    /** Total living cells across every dish. */
    public int totalPopulation() {
        int n = 0;
        for (Dish d : dishes) n += d.population();
        return n;
    }

    /** Take the feedback lines queued since the last call. */
    public List<Notice> drainNotices() {
        List<Notice> out = new ArrayList<>(notices);
        notices.clear();
        return out;
    }

    /** Take what happened in the dish on the stage, for the scene to draw. */
    public List<Dish.Event> drainPresentationEvents() {
        List<Dish.Event> out = new ArrayList<>(presentation);
        presentation.clear();
        return out;
    }

    /**
     * Step the time-warp dial. Without the instrument the experiment always runs
     * at real time.
     */
    public void cycleTimeScale(int delta) {
        if (!inventory.owns(ShopItem.TIME_WARP)) {
            timeScale = 1;
            return;
        }
        int idx = 0;
        for (int i = 0; i < TIME_SCALES.length; i++) {
            if (Math.abs(TIME_SCALES[i] - timeScale) < 0.001) idx = i;
        }
        idx = Math.max(0, Math.min(TIME_SCALES.length - 1, idx + delta));
        timeScale = TIME_SCALES[idx];
    }

    // --- the loop --------------------------------------------------------------------

    /**
     * Advance every dish, then read what happened: births of strands never seen
     * before are catalogued and paid for, new colony combinations likewise, and
     * the run's end condition is re-checked.
     */
    public void step(double dt) {
        if (gameOver || dt <= 0) return;
        playTime += dt;
        double scaled = dt * timeScale;

        for (int i = 0; i < dishes.size(); i++) {
            Dish dish = dishes.get(i);
            dish.step(scaled);
            boolean onStage = i == activeDish;
            for (Dish.Event e : dish.drainEvents()) {
                if (onStage && presentation.size() < MAX_PRESENTATION_EVENTS) presentation.add(e);
                switch (e.kind()) {
                    case BIRTH -> {
                        if (e.genome() != null) catalogue(dish, e.genome(), e.generation());
                    }
                    case BIND -> {
                        if (e.detail() != null) catalogueCombination(e.detail());
                    }
                    default -> { /* deaths, kills, meals and tool pickups are presentation only */ }
                }
            }
        }

        checkStandingAchievements();
        checkGameOver();
    }

    /**
     * Record a strand if it is new, pay for it, and announce it. Credit scales
     * with complexity, so breeding longer, more capable DNA is what actually
     * funds the lab.
     */
    private void catalogue(Dish dish, Genome genome, int generation) {
        SpeciesRecord rec = catalog.record(genome, dish.name, generation);
        if (rec == null) return;
        credits += rec.credit;
        // New to this game either way; the history says whether the player has
        // ever seen it before, which is a genuinely different thing to be told.
        boolean firstEver = history.record(rec);
        notices.add(new Notice((firstEver ? "New species: " : "Rediscovered: ")
                + rec.name + "  +" + rec.credit + "c", rec.phenotype().color()));
        drainAchievementNotices();
    }

    private void catalogueCombination(String signature) {
        if (!catalog.recordCombination(signature)) return;
        int credit = catalog.combinationCredit(signature);
        credits += credit;
        history.recordCombination(signature);
        int parts = signature.split("\\+").length;
        notices.add(new Notice("New colony combination (" + parts + " strands)  +" + credit + "c",
                new Color(200, 235, 150)));
        drainAchievementNotices();
    }

    private void drainAchievementNotices() {
        for (Achievement a : history.drainAnnouncements()) {
            notices.add(new Notice("Achievement: " + a.title(), new Color(255, 214, 120)));
        }
    }

    /** Achievements that depend on the state of the lab rather than one strand. */
    private void checkStandingAchievements() {
        if (credits >= 500) history.unlock(Achievement.WEALTHY);
        if (dishes.size() >= 6) history.unlock(Achievement.LABORATORY);
        for (Dish d : dishes) {
            if (d.population() >= 200) {
                history.unlock(Achievement.TEEMING);
                break;
            }
        }
        drainAchievementNotices();
    }

    /**
     * The run ends when every dish has run out of life — but only once there is
     * genuinely no way back, i.e. the player can neither place a starter colony
     * nor afford one. Ending a run while the player still holds the means to
     * reseed would be a bookkeeping accident rather than a defeat.
     */
    private void checkGameOver() {
        for (Dish d : dishes) {
            if (d.hasLife()) return;
        }
        boolean canReseed = inventory.count(ShopItem.STARTER_COLONY) > 0
                || credits >= ShopItem.STARTER_COLONY.price();
        if (!canReseed) {
            gameOver = true;
            notices.add(new Notice("Every dish is sterile — the experiment is over.",
                    new Color(235, 120, 120)));
        }
    }

    // --- resetting the lab ----------------------------------------------------------------

    /**
     * Start the game completely over.
     *
     * <p>Everything the current game holds is discarded — dishes, food,
     * pressures, instruments, the organisms, the credit balance and this game's
     * {@link Catalog} — leaving exactly the opening state: one dish, one square
     * organism of the chosen colour, a hundred energy orbs, and nothing found
     * yet. Every strand is therefore worth discovering (and being paid for)
     * again, which is the point of starting over.
     *
     * <p>What is <b>not</b> touched is {@link History}: every organism the
     * player has ever discovered stays on the permanent record, along with their
     * achievements and lifetime totals. Resetting costs you your lab, never your
     * collection.
     *
     * @param startingColor the colour of the single organism the new dish begins with
     */
    public void resetExperiment(Nucleotide startingColor) {
        cancelCarry();
        this.startingColor = startingColor;
        dishes.clear();
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, rng.nextLong());
        dishes.add(dish);
        activeDish = 0;

        catalog.clear();
        inventory.clear();
        inventory.grantStartingEnergy();
        credits = 0;
        timeScale = 1;
        playTime = 0;
        gameOver = false;
        notices.clear();
        presentation.clear();
        history.countGame();

        Organism first = dish.spawn(Genome.starter(startingColor), 0, 0);
        if (first != null) catalogue(dish, first.genome, first.generation);
        notices.add(new Notice("Game reset — " + history.speciesCount()
                + " organisms kept in your history", new Color(255, 214, 120)));
    }

    // --- the shop ---------------------------------------------------------------------

    public boolean canAfford(ShopItem item) {
        return credits >= item.price();
    }

    /**
     * Buy one item. A new Petri dish goes straight onto the bench (there is
     * nothing to place); everything else lands in the inventory to be used.
     */
    public boolean buy(ShopItem item) {
        if (gameOver || !canAfford(item)) return false;
        if (item.permanent() && inventory.owns(item)) return false;
        credits -= item.price();

        if (item == ShopItem.PETRI_DISH) {
            Dish dish = new Dish("Dish " + (dishes.size() + 1), Dish.DEFAULT_RADIUS, rng.nextLong());
            dishes.add(dish);
            activeDish = dishes.size() - 1;
            notices.add(new Notice("New dish on the microscope stage", new Color(150, 210, 255)));
        } else {
            inventory.grant(item);
            notices.add(new Notice("Bought " + item.displayName(), new Color(200, 220, 255)));
        }
        if (item == ShopItem.TIME_WARP) timeScale = 1;
        checkStandingAchievements();
        return true;
    }

    // --- placing things in a dish ----------------------------------------------------

    /** Drop one energy orb, spending it from the bench. */
    public boolean feed(EnergyOrb.Kind kind, double x, double y) {
        ShopItem item = kind == EnergyOrb.Kind.COMPLEX
                ? ShopItem.ENERGY_COMPLEX : ShopItem.ENERGY_SIMPLE;
        if (inventory.count(item) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y) || !dish.dropOrb(kind, x, y)) return false;
        inventory.consume(item);
        return true;
    }

    /** Seed three randomly-written organisms, the shop's way back from a crash. */
    public boolean placeStarterColony(double x, double y) {
        if (inventory.count(ShopItem.STARTER_COLONY) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        int placed = 0;
        for (int i = 0; i < 3; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = 14 + rng.nextDouble() * 22;
            Genome g = Genome.random(rng, 4 + rng.nextInt(3));
            Organism o = dish.spawn(g, x + Math.cos(a) * r, y + Math.sin(a) * r);
            if (o != null) {
                catalogue(dish, o.genome, 0);
                placed++;
            }
        }
        if (placed == 0) return false;
        inventory.consume(ShopItem.STARTER_COLONY);
        if (gameOver && totalPopulation() > 0) gameOver = false;
        return true;
    }

    public boolean placeBarrier(double x, double y) {
        if (inventory.count(ShopItem.BARRIER) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        dish.addBarrier(x, y, 22);
        inventory.consume(ShopItem.BARRIER);
        return true;
    }

    /** Place an exothermic source, or an endothermic sink when {@code cold}. */
    public boolean placeHeatSource(double x, double y, boolean cold) {
        ShopItem item = cold ? ShopItem.COLD_SOURCE : ShopItem.HEAT_SOURCE;
        if (inventory.count(item) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        dish.addHeatSource(x, y, 130, cold ? -14 : 14);
        inventory.consume(item);
        return true;
    }

    public boolean placeSpotlight(double x, double y) {
        if (inventory.count(ShopItem.SPOTLIGHT) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        dish.addSpotlight(x, y, 170, 90);
        inventory.consume(ShopItem.SPOTLIGHT);
        return true;
    }

    public boolean placeMutagen(double x, double y) {
        if (inventory.count(ShopItem.MUTAGEN) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        dish.addMutagen(x, y, 150, 60, 3);
        inventory.consume(ShopItem.MUTAGEN);
        return true;
    }

    /** Drop a random cell tool for tool-using strands to find, fight over and wear out. */
    public boolean placeToolKit(double x, double y) {
        if (inventory.count(ShopItem.TOOL_KIT) <= 0) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        DishTool.Kind[] kinds = DishTool.Kind.values();
        dish.dropTool(kinds[rng.nextInt(kinds.length)], x, y, 45);
        inventory.consume(ShopItem.TOOL_KIT);
        return true;
    }

    // --- the transfer spatula -----------------------------------------------------------

    /**
     * Lift the organism nearest a point out of the active dish. It rides the
     * spatula (out of the simulation entirely) until it is dropped somewhere.
     */
    public Organism liftOrganism(double x, double y) {
        if (carried != null || inventory.count(ShopItem.TRANSFER_SPATULA) <= 0) return null;
        Dish dish = activeDish();
        Organism o = dish.organismAt(x, y, 26);
        if (o == null) return null;
        dish.remove(o);
        carried = o;
        carriedFrom = activeDish;
        return o;
    }

    /**
     * Put the carried organism down. Dropping it into a different dish is what
     * the spatula is for and spends it; putting it back where it came from is
     * free, so a misclick costs nothing.
     */
    public boolean dropCarried(double x, double y) {
        if (carried == null) return false;
        Dish dish = activeDish();
        if (!dish.contains(x, y)) return false;
        dish.adopt(carried, x, y);
        boolean moved = activeDish != carriedFrom;
        if (moved) {
            inventory.consume(ShopItem.TRANSFER_SPATULA);
            catalogue(dish, carried.genome, carried.generation);
            notices.add(new Notice("Transferred " + carried.genome.sequence() + " to " + dish.name,
                    carried.pheno.color()));
        }
        carried = null;
        carriedFrom = -1;
        if (gameOver && totalPopulation() > 0) gameOver = false;
        return true;
    }

    /** Put a carried organism back where it was picked up (an explicit cancel). */
    public void cancelCarry() {
        if (carried == null) return;
        Dish from = dishes.get(Math.max(0, Math.min(dishes.size() - 1, carriedFrom)));
        from.adopt(carried, carried.x, carried.y);
        carried = null;
        carriedFrom = -1;
    }

    // --- persistence ----------------------------------------------------------------------

    /**
     * The whole game as JSON. Version 2 writes the dense per-cell lists as
     * {@link Packed} blocks; version 1 saves (an object per cell, per orb, per
     * body) still load, so upgrading costs the player nothing.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 2);
        m.put("seed", seed);
        m.put("startingColor", startingColor.name());
        m.put("credits", credits);
        m.put("timeScale", timeScale);
        m.put("playTime", playTime);
        m.put("activeDish", activeDish);
        m.put("gameOver", gameOver);
        m.put("inventory", inventory.toMap());
        m.put("catalog", catalog.toMap());
        List<Object> ds = new ArrayList<>(dishes.size());
        for (Dish d : dishes) ds.add(d.toMap());
        m.put("dishes", ds);
        return m;
    }

    /**
    public static EvolutionGame fromMap(Map<String, Object> m) {
        return fromMap(m, new History());
    }

    /**
     * Rebuild a saved game against the player's permanent record, which is
     * loaded separately (see {@link EvolutionStore}) because it outlives any
     * one save file.
     */
    @SuppressWarnings("unchecked")
    public static EvolutionGame fromMap(Map<String, Object> m, History history) {
        if (m == null) return null;
        History hist = history == null ? new History() : history;
        long seed = m.get("seed") instanceof Number n ? n.longValue() : new Random().nextLong();
        Inventory inv = m.get("inventory") instanceof Map<?, ?> im
                ? Inventory.fromMap((Map<String, Object>) im) : new Inventory();
        Catalog cat = m.get("catalog") instanceof Map<?, ?> cm
                ? Catalog.fromMap((Map<String, Object>) cm, hist) : new Catalog();

        EvolutionGame g = new EvolutionGame(seed, inv, cat, hist);
        g.credits = (int) num(m.get("credits"));
        g.playTime = num(m.get("playTime"));
        g.timeScale = m.containsKey("timeScale") ? Math.max(0.25, num(m.get("timeScale"))) : 1;
        g.gameOver = Boolean.TRUE.equals(m.get("gameOver"));
        try {
            g.startingColor = Nucleotide.valueOf(String.valueOf(m.get("startingColor")));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            g.startingColor = Nucleotide.G;
        }

        if (m.get("dishes") instanceof List<?> list) {
            int i = 0;
            for (Object o : list) {
                if (o instanceof Map<?, ?> dm) {
                    g.dishes.add(Dish.fromMap((Map<String, Object>) dm, seed + (i++) + 1));
                }
            }
        }
        if (g.dishes.isEmpty()) {
            g.dishes.add(new Dish("Dish 1", Dish.DEFAULT_RADIUS, seed));
        }
        g.activeDish = Math.max(0, Math.min(g.dishes.size() - 1, (int) num(m.get("activeDish"))));
        return g;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
