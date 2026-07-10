package com.larsons.engine.autobattler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The shared unit pool: every copy of every unit that exists in one match.
 * Shops draw from it and selling returns to it, so players compete for the
 * same finite copies — the scarcity mechanic that makes contested comps a
 * real decision in TFT / Auto Chess.
 */
public final class UnitPool {

    private final Map<String, Integer> remaining = new HashMap<>();

    public UnitPool() {
        for (UnitDef d : AutoUnits.roster()) {
            remaining.put(d.key, AutoUnits.POOL_COPIES[d.cost - 1]);
        }
    }

    public int remaining(String key) {
        return remaining.getOrDefault(key, 0);
    }

    /** Take one copy out of the pool; false when none are left. */
    public boolean take(String key) {
        int n = remaining(key);
        if (n <= 0) return false;
        remaining.put(key, n - 1);
        return true;
    }

    public void giveBack(String key, int copies) {
        if (copies > 0 && remaining.containsKey(key)) {
            remaining.put(key, remaining.get(key) + copies);
        }
    }

    /** How much the Collector relic over-weights already-owned units in draws. */
    public static final int FAVORED_WEIGHT = 4;

    /**
     * Draw a random unit of the given cost tier (weighted by remaining copies,
     * so nearly-drained units show up less). Returns {@code null} when the
     * tier is exhausted. The drawn copy is removed from the pool.
     */
    public String draw(int cost, Random rng) {
        return draw(cost, rng, Set.of());
    }

    /**
     * Like {@link #draw(int, Random)}, but keys in {@code favored} count
     * {@value FAVORED_WEIGHT}x their remaining copies — the Collector relic's
     * "more likely to roll what you already own".
     */
    public String draw(int cost, Random rng, Set<String> favored) {
        List<UnitDef> tier = AutoUnits.byCost(cost);
        int total = 0;
        for (UnitDef d : tier) total += weight(d.key, favored);
        if (total <= 0) return null;
        int pick = rng.nextInt(total);
        for (UnitDef d : tier) {
            pick -= weight(d.key, favored);
            if (pick < 0) {
                take(d.key);
                return d.key;
            }
        }
        return null; // unreachable
    }

    private int weight(String key, Set<String> favored) {
        return remaining(key) * (favored.contains(key) ? FAVORED_WEIGHT : 1);
    }

    /** For tests/debugging: total copies left across a cost tier. */
    public int tierRemaining(int cost) {
        int total = 0;
        for (UnitDef d : AutoUnits.byCost(cost)) total += remaining(d.key);
        return total;
    }

    /** Draw a full shop of {@code slots} units using the level's rarity odds. */
    public List<String> rollShop(int level, int slots, Random rng) {
        return rollShop(level, slots, rng, Set.of());
    }

    /**
     * Roll a shop with {@code favored} unit keys over-weighted within each
     * tier (see {@link #draw(int, Random, Set)}).
     */
    public List<String> rollShop(int level, int slots, Random rng, Set<String> favored) {
        int[] odds = AutoUnits.SHOP_ODDS[Math.max(1, Math.min(9, level)) - 1];
        List<String> shop = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            int roll = rng.nextInt(100);
            int cost = 1;
            for (int c = 0; c < odds.length; c++) {
                roll -= odds[c];
                if (roll < 0) {
                    cost = c + 1;
                    break;
                }
            }
            String key = draw(cost, rng, favored);
            // A drained tier falls back to cheaper tiers, then to nothing.
            for (int c = cost - 1; key == null && c >= 1; c--) key = draw(c, rng, favored);
            shop.add(key); // may be null (empty card)
        }
        return shop;
    }
}
