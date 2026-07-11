package com.larsons.engine.crafting;

import com.larsons.engine.entity.Inventory;

import java.util.List;

/**
 * One crafting recipe: combine one to three ingredient stacks into an output
 * item at a station. Recipes are pure data — {@link RecipeRegistry} holds the
 * catalog and {@link #craft} applies one against an {@link Inventory}.
 *
 * @param station     where it can be made: {@link #STATION_CRAFTING} (the
 *                    crafting table) or {@link #STATION_ALCHEMY} (the alchemy
 *                    station: smelting, brewing, transmutation)
 * @param inputs      1–3 ingredient stacks, all required
 * @param output      item key produced
 * @param outputCount how many are produced per craft
 */
public record Recipe(String station, List<Ingredient> inputs, String output, int outputCount) {

    public static final String STATION_CRAFTING = "crafting";
    public static final String STATION_ALCHEMY = "alchemy";

    /** One required ingredient stack. */
    public record Ingredient(String key, int count) {}

    public Recipe {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 3) {
            throw new IllegalArgumentException("Recipes take 1-3 ingredients");
        }
        inputs = List.copyOf(inputs);
        if (outputCount <= 0) outputCount = 1;
    }

    /** Whether {@code inv} holds every ingredient. */
    public boolean canCraft(Inventory inv) {
        for (Ingredient in : inputs) {
            if (inv.totalOf(in.key()) < in.count()) return false;
        }
        return true;
    }

    /**
     * Consume the ingredients and add the output to {@code inv}. Returns how
     * many output items did <em>not</em> fit (0 = all pocketed; the caller
     * drops leftovers into the world), or -1 when ingredients were missing.
     */
    public int craft(Inventory inv) {
        if (!canCraft(inv)) return -1;
        for (Ingredient in : inputs) {
            inv.remove(in.key(), in.count());
        }
        return inv.add(output, outputCount);
    }
}
