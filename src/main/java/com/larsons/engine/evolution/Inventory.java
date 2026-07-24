package com.larsons.engine.evolution;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the player has on the bench: counts of consumables and the set of
 * permanent instruments they have bought.
 *
 * <p>A new experiment starts with the hundred energy orbs the design document
 * specifies and nothing else, so the first credits have to be earned out of
 * that opening handful of food.
 */
public final class Inventory {

    /** Energy orbs a brand-new experiment is handed. */
    public static final int STARTING_ENERGY = 100;

    private final Map<ShopItem, Integer> counts = new EnumMap<>(ShopItem.class);

    /** A fresh bench: 100 simple energy orbs, no tools, no instruments. */
    public static Inventory starting() {
        Inventory inv = new Inventory();
        inv.counts.put(ShopItem.ENERGY_SIMPLE, STARTING_ENERGY);
        return inv;
    }

    public int count(ShopItem item) {
        return counts.getOrDefault(item, 0);
    }

    public boolean has(ShopItem item) {
        return count(item) > 0;
    }

    /** Owned permanently (thermometer, scanner, time warp). */
    public boolean owns(ShopItem instrument) {
        return instrument.permanent() && count(instrument) > 0;
    }

    public void add(ShopItem item, int n) {
        if (n <= 0) return;
        counts.merge(item, n, Integer::sum);
    }

    /** Spend one; returns false when there is none left (or it is permanent). */
    public boolean consume(ShopItem item) {
        if (item.permanent()) return owns(item);
        int have = count(item);
        if (have <= 0) return false;
        if (have == 1) counts.remove(item);
        else counts.put(item, have - 1);
        return true;
    }

    /** Apply a purchase: instruments flip on, consumables stack up. */
    public void grant(ShopItem item) {
        if (item.permanent()) counts.put(item, 1);
        else add(item, item.quantity());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<ShopItem, Integer> e : counts.entrySet()) {
            m.put(e.getKey().name(), e.getValue());
        }
        return m;
    }

    public static Inventory fromMap(Map<String, Object> m) {
        Inventory inv = new Inventory();
        if (m == null) return inv;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            try {
                ShopItem item = ShopItem.valueOf(e.getKey());
                if (e.getValue() instanceof Number n) inv.counts.put(item, n.intValue());
            } catch (IllegalArgumentException ignored) {
                // an item from a newer build: drop it rather than fail the load
            }
        }
        return inv;
    }
}
