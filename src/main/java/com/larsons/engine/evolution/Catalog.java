package com.larsons.engine.evolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>current game's</b> book: the strands and colony combinations this
 * experiment has turned up, and the credit they paid.
 *
 * <p>Nothing here is pre-populated, and nothing here is permanent. Resetting
 * the game empties this completely — that is what makes a reset a real restart,
 * with everything to find again and every credit to re-earn. The permanent
 * record of every organism ever discovered lives in {@link History}, which a
 * reset never touches.
 *
 * <p>A strand pays when it is new <em>to this game</em>. Rediscovering
 * something a previous game found still pays, because this game has not found
 * it yet; what it does not do is add to the permanent record twice.
 */
public final class Catalog {

    private final Map<String, SpeciesRecord> species = new LinkedHashMap<>();
    /** Colony signature ("DNA+DNA+…") -> epoch millis it formed in this game. */
    private final Map<String, Long> combinations = new LinkedHashMap<>();
    /** Credit this game has been paid for discoveries. */
    private int creditEarned;

    // --- discovery ------------------------------------------------------------------

    /**
     * Catalogue a strand for this game. Returns the new record, or {@code null}
     * if this game has already found this exact sequence — a species is unique
     * exactly when its DNA reads differently from everything already found.
     */
    public SpeciesRecord record(Genome genome, String dish, int generation) {
        String key = genome.sequence();
        if (species.containsKey(key)) return null;
        SpeciesRecord rec = SpeciesRecord.of(genome, dish, generation);
        species.put(key, rec);
        creditEarned += rec.credit;
        return rec;
    }

    /**
     * Catalogue a colony combination — two or more <em>different</em> strands
     * bound into one body. Returns true the first time each forms in this game.
     */
    public boolean recordCombination(String signature) {
        if (signature == null || !signature.contains("+")) return false;
        if (combinations.containsKey(signature)) return false;
        combinations.put(signature, System.currentTimeMillis());
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

    /** Empty the book for a fresh game. */
    public void clear() {
        species.clear();
        combinations.clear();
        creditEarned = 0;
    }

    /** Re-add a record loaded from disk without re-paying its credit. */
    public void restore(SpeciesRecord rec) {
        if (rec == null || species.containsKey(rec.sequence)) return;
        species.put(rec.sequence, rec);
    }

    // --- reading the book ---------------------------------------------------------------

    public Collection<SpeciesRecord> allSpecies() { return species.values(); }

    public SpeciesRecord species(String sequence) { return species.get(sequence); }

    public boolean knows(String sequence) { return species.containsKey(sequence); }

    public int speciesCount() { return species.size(); }

    public Set<String> combinations() { return combinations.keySet(); }

    public int combinationCount() { return combinations.size(); }

    /** Credit this game has earned from discoveries. */
    public int creditEarned() { return creditEarned; }

    /** Distinct sequences found in this game. */
    public Set<String> sequences() {
        return new LinkedHashSet<>(species.keySet());
    }

    // --- persistence -----------------------------------------------------------------------

    /**
     * This game's book, as saved. Only the sequences are stored: the full record
     * for each already exists as its own file in the permanent history, so
     * writing it twice would just be a second copy to keep in step.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("species", new ArrayList<>(species.keySet()));
        List<Object> combos = new ArrayList<>();
        for (Map.Entry<String, Long> e : combinations.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("signature", e.getKey());
            c.put("at", e.getValue());
            combos.add(c);
        }
        m.put("combinations", combos);
        m.put("creditEarned", creditEarned);
        return m;
    }

    /**
     * Read this game's book back. Sequences are resolved against {@code history}
     * so each entry keeps its original discovery date and name; anything the
     * history has somehow lost is decoded fresh rather than dropped.
     */
    public static Catalog fromMap(Map<String, Object> m, History history) {
        Catalog c = new Catalog();
        if (m == null) return c;
        if (m.get("species") instanceof List<?> list) {
            for (Object o : list) {
                String dna = String.valueOf(o);
                if (!Genome.isValid(dna)) continue;
                SpeciesRecord known = history == null ? null : history.species(dna);
                c.species.put(dna, known != null ? known
                        : SpeciesRecord.of(Genome.of(dna), "", 0));
            }
        }
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
        if (m.get("creditEarned") instanceof Number n) c.creditEarned = n.intValue();
        return c;
    }
}
