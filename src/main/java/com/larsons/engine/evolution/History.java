package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The player's permanent record: every organism they have <em>ever</em>
 * discovered, in any game, plus the achievements those discoveries unlocked.
 *
 * <p>This is the tier that survives everything. Resetting the game wipes the
 * lab, the credits and the current {@link Catalog} completely — a full restart,
 * not a soft one — but nothing is ever removed from here. A strand discovered
 * once is discovered forever, which is what makes starting over safe: you can
 * throw the experiment away without throwing away what it found.
 *
 * <p>The split is deliberate:
 * <ul>
 *   <li>{@link Catalog} is <b>this game's</b> book — what the current
 *       experiment has turned up, and what it has been paid for. Reset clears it.</li>
 *   <li>{@code History} is <b>the user's</b> book — every organism from every
 *       game that ever ran. Reset does not touch it.</li>
 * </ul>
 *
 * <p>Each entry is written to disk as its own JSON file (see
 * {@link EvolutionStore}); this class holds the in-memory index of them plus
 * the totals that are not derivable from the files themselves.
 */
public final class History {

    private final Map<String, SpeciesRecord> species = new LinkedHashMap<>();
    /** Colony signature ("DNA+DNA+…") -> epoch millis it first formed, ever. */
    private final Map<String, Long> combinations = new LinkedHashMap<>();
    private final Set<Achievement> unlocked = EnumSet.noneOf(Achievement.class);
    private final Set<BodyShape> shapesSeen = EnumSet.noneOf(BodyShape.class);

    private int gamesPlayed = 1;
    /** Credit earned across every game ever played, as a lifetime score. */
    private int lifetimeCredit;

    private final List<SpeciesRecord> pendingWrites = new ArrayList<>();
    private final List<Achievement> announcements = new ArrayList<>();

    // --- recording ------------------------------------------------------------------

    /**
     * Add a discovery to the permanent record. Returns {@code true} the first
     * time this strand has ever been seen — by any game — which is what the
     * scene reports as a genuinely new organism rather than a rediscovery.
     */
    public boolean record(SpeciesRecord rec) {
        if (rec == null) return false;
        lifetimeCredit += rec.credit;
        if (species.containsKey(rec.sequence)) return false;
        species.put(rec.sequence, rec);
        pendingWrites.add(rec);
        checkSpeciesAchievements(rec);
        return true;
    }

    /**
     * Re-add an entry read back from disk: it is already recorded and already
     * written, so it neither pays nor queues a write.
     */
    public void restore(SpeciesRecord rec) {
        if (rec == null || species.containsKey(rec.sequence)) return;
        species.put(rec.sequence, rec);
        shapesSeen.add(rec.phenotype().shape());
    }

    /** Note a colony combination permanently. True the first time ever. */
    public boolean recordCombination(String signature) {
        if (signature == null || !signature.contains("+")) return false;
        if (combinations.containsKey(signature)) return false;
        combinations.put(signature, System.currentTimeMillis());
        unlock(Achievement.SYMBIOSIS);
        return true;
    }

    /** Count another game started (a reset, or a brand new experiment). */
    public void countGame() {
        gamesPlayed++;
    }

    // --- achievements ------------------------------------------------------------------

    /**
     * Achievements are permanent user progress, so they live here rather than in
     * the per-game catalog: a reset never takes one back.
     */
    public boolean unlock(Achievement a) {
        if (!unlocked.add(a)) return false;
        announcements.add(a);
        return true;
    }

    public boolean isUnlocked(Achievement a) { return unlocked.contains(a); }

    public Set<Achievement> unlockedAchievements() { return unlocked; }

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

    // --- reading the record ---------------------------------------------------------------

    public Collection<SpeciesRecord> allSpecies() { return species.values(); }

    public SpeciesRecord species(String sequence) { return species.get(sequence); }

    /** Whether this strand has ever been discovered, in any game. */
    public boolean knows(String sequence) { return species.containsKey(sequence); }

    public int speciesCount() { return species.size(); }

    public Set<String> combinations() { return combinations.keySet(); }

    public int combinationCount() { return combinations.size(); }

    public int gamesPlayed() { return gamesPlayed; }

    public int lifetimeCredit() { return lifetimeCredit; }

    public Set<BodyShape> shapesSeen() { return shapesSeen; }

    /** The longest strand ever recorded, or {@code null} while empty. */
    public SpeciesRecord longestStrand() {
        SpeciesRecord best = null;
        for (SpeciesRecord r : species.values()) {
            if (best == null || r.sequence.length() > best.sequence.length()) best = r;
        }
        return best;
    }

    /** The most elaborate strand ever recorded, or {@code null} while empty. */
    public SpeciesRecord mostComplex() {
        SpeciesRecord best = null;
        for (SpeciesRecord r : species.values()) {
            if (best == null || r.phenotype().complexity() > best.phenotype().complexity()) best = r;
        }
        return best;
    }

    public int deepestGeneration() {
        int best = 0;
        for (SpeciesRecord r : species.values()) best = Math.max(best, r.generation);
        return best;
    }

    /** How many distinct abilities have ever been seen. */
    public int abilitiesSeen() {
        Set<Ability> seen = EnumSet.noneOf(Ability.class);
        for (SpeciesRecord r : species.values()) seen.addAll(r.phenotype().abilities());
        return seen.size();
    }

    /** Entries recorded since the last call, for the store to write to disk. */
    public List<SpeciesRecord> drainPendingWrites() {
        List<SpeciesRecord> out = new ArrayList<>(pendingWrites);
        pendingWrites.clear();
        return out;
    }

    // --- persistence -----------------------------------------------------------------------

    /**
     * The index. The discoveries themselves live in their own files, so this
     * carries only what is not derivable from them: the combinations, the
     * achievements, and the lifetime totals.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 2);
        m.put("combinations", Packed.signatures(combinations));
        List<Object> ach = new ArrayList<>();
        for (Achievement a : unlocked) ach.add(a.name());
        m.put("achievements", ach);
        m.put("gamesPlayed", gamesPlayed);
        m.put("lifetimeCredit", lifetimeCredit);
        return m;
    }

    public static History fromMap(Map<String, Object> m) {
        History h = new History();
        if (m == null) return h;
        Packed.readSignatures(m.get("combinations"), h.combinations);
        if (m.get("achievements") instanceof List<?> list) {
            for (Object o : list) {
                try {
                    h.unlocked.add(Achievement.valueOf(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // an achievement that no longer exists: skip it
                }
            }
        }
        if (m.get("gamesPlayed") instanceof Number n) h.gamesPlayed = Math.max(1, n.intValue());
        if (m.get("lifetimeCredit") instanceof Number n) h.lifetimeCredit = n.intValue();
        return h;
    }
}
