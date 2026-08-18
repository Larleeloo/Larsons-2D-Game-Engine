package com.larsons.engine.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programmable per-run stat counters — the raw material for map-maker logic:
 * scenes bump these as gameplay happens (a block mined, a metre travelled, a
 * mob slain…), the HUD can chart any of them as a live bar, and
 * {@link StatRuleEngine} evaluates the level's
 * {@link com.larsons.engine.level.StatRule}s against them ("if you've mined
 * 50 blocks → receive X", "every 1000 px travelled → …").
 *
 * <p>Counters are open-ended: any string key works, so custom content and
 * future features can track new things without touching this class. The
 * {@link #TRACKED} array is the curated list the creative editor's rule
 * dialog offers.
 */
public final class PlayerStats {

    /** The stat keys the editor's Stat Rules dialog offers (more can exist). */
    public static final String[] TRACKED = {
            "blocks_mined", "blocks_placed", "items_picked_up", "distance_traveled",
            "jumps", "mobs_killed", "crafts", "deaths", "damage_taken", "shots_fired",
            // Melee: blows caught outright, and blows a raised guard soaked.
            "parries", "blocks",
    };

    /** Human-readable label for a stat key ("blocks_mined" → "Blocks Mined"). */
    public static String label(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        boolean cap = true;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                cap = true;
            } else {
                sb.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            }
        }
        return sb.toString();
    }

    private final Map<String, Double> counters = new LinkedHashMap<>();

    /** Add {@code amount} to a counter (creates it at 0 first). */
    public void add(String key, double amount) {
        if (key == null || amount == 0) return;
        counters.merge(key, amount, Double::sum);
    }

    /** Current value of a counter (0 when never touched). */
    public double get(String key) {
        Double v = counters.get(key);
        return v == null ? 0 : v;
    }

    /** All non-zero counters, in first-touched order (HUD/debug listing). */
    public Map<String, Double> all() {
        return counters;
    }

    public void reset() {
        counters.clear();
    }

    /**
     * Replace every counter from saved data (see
     * {@link com.larsons.engine.save.RunRecord}). Counters are open-ended by
     * design, so a save written by a build that tracked something this one does
     * not is carried through untouched rather than dropped — the rule that
     * lets custom content invent a stat without touching this class applies
     * just as much to reading one back.
     */
    public void restore(Map<String, ?> saved) {
        counters.clear();
        if (saved == null) return;
        for (Map.Entry<String, ?> e : saved.entrySet()) {
            if (e.getKey() != null && e.getValue() instanceof Number n) {
                counters.put(e.getKey(), n.doubleValue());
            }
        }
    }
}
