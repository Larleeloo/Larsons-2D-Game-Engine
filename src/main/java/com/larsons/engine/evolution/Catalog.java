package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reference book: every strand the player's dishes have ever produced,
 * every colony combination that has ever formed, and the achievements those
 * discoveries unlocked.
 *
 * <p>Nothing is pre-populated. A brand new game has an empty book, and it fills
 * only with what the simulation actually produces — the design document is
 * explicit that discoveries are stored as they happen rather than looked up in
 * a table shipped with the game.
 *
 * <p>Newly catalogued strands queue up in {@link #drainPendingWrites()} so
 * {@link EvolutionStore} can write each one out as its own JSON file, and newly
 * unlocked achievements queue up in {@link #drainAnnouncements()} for the HUD
 * to pop.
 */
public final class Catalog {

    private final Map<String, SpeciesRecord> species = new LinkedHashMap<>();
    /** Colony signature ("DNA+DNA+…") -> epoch millis it first formed. */
    private final Map<String, Long> combinations = new LinkedHashMap<>();
    private final Set<Achievement> unlocked = EnumSet.noneOf(Achievement.class);
    private final Set<BodyShape> shapesSeen = EnumSet.noneOf(BodyShape.class);

    private final List<SpeciesRecord> pendingWrites = new ArrayList<>();
    private final List<Achievement> announcements = new ArrayList<>();

    /**
     * Total credit this book has ever paid out, across every experiment. This is
     * the player's lifetime score, and it is what a reset hands back so the same
     * discoveries can be spent on a different lab — see
     * {@link EvolutionGame#resetExperiment()}.
     */
    private int creditEarned;
    /** How many experiments have been started against this book. */
    private int runs = 1;

    // --- discovery ------------------------------------------------------------------

    /**
     * Catalogue a strand. Returns the new record, or {@code null} if this exact
     * sequence has been seen before — a species is unique exactly when its DNA
     * reads differently from everything already in the book.
     */
    public SpeciesRecord record(Genome genome, String dish, int generation) {
        String key = genome.sequence();
        if (species.containsKey(key)) return null;
        SpeciesRecord rec = SpeciesRecord.of(genome, dish, generation);
        species.put(key, rec);
        pendingWrites.add(rec);
        creditEarned += rec.credit;
        checkSpeciesAchievements(rec);
        return rec;
    }

    /**
     * Catalogue a colony combination — two or more <em>different</em> strands
     * bound into one body. Returns true the first time each combination forms.
     */
    public boolean recordCombination(String signature) {
        if (signature == null || !signature.contains("+")) return false;
        if (combinations.containsKey(signature)) return false;
        combinations.put(signature, System.currentTimeMillis());
        unlock(Achievement.SYMBIOSIS);
        return true;
    }

    /** Credit a colony combination is worth: half the complexity it brings together. */
    public int combinationCredit(String signature) {
        int sum = 0;
        for (String dna : signature.split("\\+")) {
            if (Genome.isValid(dna)) sum += Phenotype.of(Genome.of(dna)).complexity();
        }
        return Math.max(5, sum / 2);
    }

    // --- achievements ------------------------------------------------------------------

    /** Unlock one; returns true only the first time, and queues an announcement. */
    public boolean unlock(Achievement a) {
        if (!unlocked.add(a)) return false;
        announcements.add(a);
        return true;
    }

    public boolean isUnlocked(Achievement a) { return unlocked.contains(a); }

    public Set<Achievement> unlockedAchievements() { return unlocked; }

    /** Everything a single discovery can unlock on its own. */
    private void checkSpeciesAchievements(SpeciesRecord rec) {
        Phenotype p = rec.phenotype();
        Genome g = rec.genome();

        unlock(Achievement.FIRST_LIFE);
        if (species.size() >= 10) unlock(Achievement.TEN_SPECIES);
        if (species.size() >= 50) unlock(Achievement.FIFTY_SPECIES);
        if (species.size() >= 100) unlock(Achievement.HUNDRED_SPECIES);

        for (Ability a : p.abilities()) unlock(Achievement.forAbility(a));

        if (p.trait(Trait.LIGHT_EMISSION) >= 6) unlock(Achievement.GLOWWORM);
        if (g.length() >= 24) unlock(Achievement.LONG_STRAND);
        if (g.length() >= Genome.MAX_LENGTH) unlock(Achievement.MAXIMAL);
        if (rec.generation >= 50) unlock(Achievement.DEEP_LINEAGE);
        if (g.fraction(Nucleotide.R) >= 0.3 && g.fraction(Nucleotide.G) >= 0.3
                && g.fraction(Nucleotide.B) >= 0.3) {
            unlock(Achievement.FULL_SPECTRUM);
        }

        shapesSeen.add(p.shape());
        if (shapesSeen.size() >= BodyShape.values().length) unlock(Achievement.POLYMORPH);
    }

    /** Take the achievements unlocked since the last call (for HUD pop-ups). */
    public List<Achievement> drainAnnouncements() {
        List<Achievement> out = new ArrayList<>(announcements);
        announcements.clear();
        return out;
    }

    // --- reading the book ---------------------------------------------------------------

    public Collection<SpeciesRecord> allSpecies() { return species.values(); }

    public SpeciesRecord species(String sequence) { return species.get(sequence); }

    public boolean knows(String sequence) { return species.containsKey(sequence); }

    public int speciesCount() { return species.size(); }

    public Set<String> combinations() { return combinations.keySet(); }

    public int combinationCount() { return combinations.size(); }

    /** Credit this book has paid out across every experiment ever run. */
    public int creditEarned() { return creditEarned; }

    /** How many experiments have been started against this book. */
    public int runs() { return runs; }

    /** Count another experiment started (a reset, or a fresh game over this book). */
    public void countRun() { runs++; }

    /** The longest strand in the book, or {@code null} while it is empty. */
    public SpeciesRecord longestStrand() {
        SpeciesRecord best = null;
        for (SpeciesRecord r : species.values()) {
            if (best == null || r.sequence.length() > best.sequence.length()) best = r;
        }
        return best;
    }

    /** The most elaborate strand in the book, or {@code null} while it is empty. */
    public SpeciesRecord mostComplex() {
        SpeciesRecord best = null;
        for (SpeciesRecord r : species.values()) {
            if (best == null || r.phenotype().complexity() > best.phenotype().complexity()) best = r;
        }
        return best;
    }

    /** The deepest lineage the book has recorded. */
    public int deepestGeneration() {
        int best = 0;
        for (SpeciesRecord r : species.values()) best = Math.max(best, r.generation);
        return best;
    }

    /** How many distinct abilities have ever been seen, out of all of them. */
    public int abilitiesSeen() {
        Set<Ability> seen = EnumSet.noneOf(Ability.class);
        for (SpeciesRecord r : species.values()) seen.addAll(r.phenotype().abilities());
        return seen.size();
    }

    public Set<BodyShape> shapesSeen() { return shapesSeen; }

    /** Records catalogued since the last call, for the store to write to disk. */
    public List<SpeciesRecord> drainPendingWrites() {
        List<SpeciesRecord> out = new ArrayList<>(pendingWrites);
        pendingWrites.clear();
        return out;
    }

    /** Re-add a record loaded from its JSON file without re-paying its credit. */
    public void restore(SpeciesRecord rec) {
        if (rec == null || species.containsKey(rec.sequence)) return;
        species.put(rec.sequence, rec);
        shapesSeen.add(rec.phenotype().shape());
    }

    // --- persistence -----------------------------------------------------------------------

    /**
     * The book's index. The species themselves live in their own files (see
     * {@link EvolutionStore}), so this only carries what is not derivable from
     * them: which combinations have formed, which achievements are unlocked and
     * the running credit total.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> combos = new ArrayList<>();
        for (Map.Entry<String, Long> e : combinations.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("signature", e.getKey());
            c.put("at", e.getValue());
            combos.add(c);
        }
        m.put("combinations", combos);
        List<Object> ach = new ArrayList<>();
        for (Achievement a : unlocked) ach.add(a.name());
        m.put("achievements", ach);
        m.put("creditEarned", creditEarned);
        m.put("runs", runs);
        return m;
    }

    public static Catalog fromMap(Map<String, Object> m) {
        Catalog c = new Catalog();
        if (m == null) return c;
        if (m.get("combinations") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> cm) {
                    Object sig = cm.get("signature");
                    Object at = cm.get("at");
                    if (sig != null) {
                        c.combinations.put(String.valueOf(sig),
                                at instanceof Number n ? n.longValue() : 0L);
                    }
                }
            }
        }
        if (m.get("achievements") instanceof List<?> list) {
            for (Object o : list) {
                try {
                    c.unlocked.add(Achievement.valueOf(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // an achievement that no longer exists: skip it
                }
            }
        }
        if (m.get("creditEarned") instanceof Number n) c.creditEarned = n.intValue();
        if (m.get("runs") instanceof Number n) c.runs = Math.max(1, n.intValue());
        return c;
    }

    /** Distinct sequences currently in the book (used by tests and the UI). */
    public Set<String> sequences() {
        return new LinkedHashSet<>(species.keySet());
    }
}
