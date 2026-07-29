package com.larsons.engine.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.larsons.engine.crafting.Recipe.Ingredient;

/**
 * The catalog of {@link Recipe}s a game knows about — same data-driven pattern
 * as the block/item registries: games extend the set with {@link #register}.
 *
 * <p>{@link #standard()} ships a progression built almost entirely from
 * resources found in generated worlds: chop logs → planks and sticks → build
 * the crafting table itself → wooden tools/weapons → mine ores → smelt ingots
 * at the alchemy station → metal and gem gear, potions, and lights. The goal
 * is that most of the item catalog is reachable from the environment.
 */
public final class RecipeRegistry {

    private final List<Recipe> recipes = new ArrayList<>();

    private static final RecipeRegistry STANDARD = createStandard();

    public static RecipeRegistry standard() {
        return STANDARD;
    }

    public void register(Recipe r) {
        recipes.add(r);
    }

    /** Every recipe, registration order. */
    public List<Recipe> all() {
        return Collections.unmodifiableList(recipes);
    }

    /** Recipes craftable at the given station kind. */
    public List<Recipe> forStation(String station) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : recipes) {
            if (r.station().equals(station)) out.add(r);
        }
        return out;
    }

    private static Recipe craft(String output, int n, Ingredient... inputs) {
        return new Recipe(Recipe.STATION_CRAFTING, List.of(inputs), output, n);
    }

    private static Recipe brew(String output, int n, Ingredient... inputs) {
        return new Recipe(Recipe.STATION_ALCHEMY, List.of(inputs), output, n);
    }

    private static Ingredient of(String key, int count) {
        return new Ingredient(key, count);
    }

    private static RecipeRegistry createStandard() {
        RecipeRegistry r = new RecipeRegistry();

        // --- crafting table: wood, tools, weapons, building ---
        r.register(craft("planks", 4, of("oak_log", 1)));
        r.register(craft("planks", 4, of("wood", 1)));
        r.register(craft("birch_planks", 4, of("birch_log", 1)));
        r.register(craft("dark_planks", 4, of("dark_log", 1)));
        r.register(craft("stick", 4, of("planks", 2)));
        r.register(craft("crafting_table", 1, of("planks", 4)));
        r.register(craft("alchemy_station", 1, of("stone", 4), of("amethyst", 2)));
        r.register(craft("torch", 4, of("coal", 1), of("stick", 1)));
        r.register(craft("lantern", 1, of("torch", 1), of("iron_ingot", 1)));
        r.register(craft("campfire", 1, of("oak_log", 3), of("coal", 1)));
        r.register(craft("rope", 2, of("vines", 2)));
        r.register(craft("platform", 4, of("planks", 2), of("stick", 1)));
        r.register(craft("scaffold", 4, of("bamboo", 3), of("rope", 1)));
        r.register(craft("bookshelf", 1, of("planks", 4), of("scroll", 1)));
        r.register(craft("stone_bricks", 4, of("stone", 4)));
        r.register(craft("glass", 2, of("sand", 3)));
        // Tools: material + sticks.
        r.register(craft("wooden_pickaxe", 1, of("planks", 3), of("stick", 2)));
        r.register(craft("stone_pickaxe", 1, of("stone", 3), of("stick", 2)));
        r.register(craft("iron_pickaxe", 1, of("iron_ingot", 3), of("stick", 2)));
        r.register(craft("diamond_pickaxe", 1, of("diamond", 3), of("stick", 2)));
        r.register(craft("wooden_axe", 1, of("planks", 3), of("stick", 2)));
        r.register(craft("iron_axe", 1, of("iron_ingot", 3), of("stick", 2)));
        r.register(craft("diamond_axe", 1, of("diamond", 3), of("stick", 2)));
        r.register(craft("wooden_shovel", 1, of("planks", 1), of("stick", 2)));
        r.register(craft("iron_shovel", 1, of("iron_ingot", 1), of("stick", 2)));
        r.register(craft("diamond_shovel", 1, of("diamond", 1), of("stick", 2)));
        // Weapons.
        r.register(craft("wooden_sword", 1, of("planks", 2), of("stick", 1)));
        r.register(craft("iron_sword", 1, of("iron_ingot", 2), of("stick", 1)));
        r.register(craft("steel_sword", 1, of("iron_ingot", 2), of("coal", 2), of("stick", 1)));
        r.register(craft("crystal_sword", 1, of("crystal", 2), of("diamond", 1), of("stick", 1)));
        r.register(craft("battle_axe", 1, of("iron_ingot", 3), of("stick", 2)));
        // The other melee schools, each reachable from the same ore ladder.
        r.register(craft("iron_dagger", 1, of("iron_ingot", 1), of("stick", 1)));
        r.register(craft("shadow_dagger", 1, of("iron_dagger", 1), of("shadow_essence", 2)));
        r.register(craft("iron_spear", 1, of("iron_ingot", 2), of("stick", 3)));
        r.register(craft("dragon_lance", 1, of("iron_spear", 1), of("fire_essence", 3), of("gold_ingot", 2)));
        r.register(craft("war_hammer", 1, of("iron_ingot", 4), of("stone", 4), of("stick", 2)));
        r.register(craft("earthbreaker", 1, of("war_hammer", 1), of("diamond", 3), of("ruby", 2)));
        // Shields: carried to be raised (hold [C] for the guard stance).
        r.register(craft("wooden_shield", 1, of("planks", 5), of("stick", 2)));
        r.register(craft("iron_shield", 1, of("wooden_shield", 1), of("iron_ingot", 3)));
        r.register(craft("tower_shield", 1, of("iron_shield", 1), of("iron_ingot", 4), of("stone", 4)));
        r.register(craft("aegis", 1, of("tower_shield", 1), of("gold_ingot", 3), of("emerald", 2)));
        r.register(craft("legendary_sword", 1, of("gold_ingot", 2), of("diamond", 2), of("ruby", 1)));
        r.register(craft("wooden_bow", 1, of("stick", 3), of("rope", 1)));
        r.register(craft("longbow", 1, of("wooden_bow", 1), of("rope", 1), of("iron_ingot", 1)));
        r.register(craft("scatter_bow", 1, of("longbow", 1), of("storm_essence", 1), of("rope", 2)));
        r.register(craft("arrow", 4, of("stick", 1), of("stone", 1)));
        r.register(craft("throwing_knife", 2, of("iron_ingot", 1), of("stick", 1)));
        // Vehicles & mounts: craft the item, [F] deploys the ride.
        r.register(craft("horse_saddle", 1, of("leather", 3), of("iron_ingot", 1)));
        r.register(craft("boar_saddle", 1, of("horse_saddle", 1), of("leather", 2)));
        r.register(craft("ostrich_saddle", 1, of("horse_saddle", 1), of("rope", 2)));
        r.register(craft("oak_boat", 1, of("planks", 4), of("rope", 1)));
        r.register(craft("drill_kit", 1, of("iron_ingot", 4), of("diamond", 2), of("coal", 4)));
        // Food.
        r.register(craft("bread", 1, of("tall_grass", 3)));

        // --- alchemy station: smelting, brewing, transmutation ---
        r.register(brew("iron_ingot", 1, of("iron_ore", 1), of("coal", 1)));
        r.register(brew("gold_ingot", 1, of("gold_ore", 1), of("coal", 1)));
        r.register(brew("silver_ingot", 1, of("silver_ore", 1), of("coal", 1)));
        r.register(brew("copper", 1, of("copper_ore", 1), of("coal", 1)));
        r.register(brew("diamond", 1, of("diamond_ore", 1), of("coal", 2)));
        r.register(brew("ruby", 1, of("ruby_ore", 1), of("coal", 2)));
        r.register(brew("emerald", 1, of("emerald_ore", 1), of("coal", 2)));
        r.register(brew("amethyst", 1, of("amethyst_ore", 1), of("coal", 2)));
        r.register(brew("health_potion", 1, of("mushroom", 1), of("flower_red", 2)));
        r.register(brew("mana_potion", 1, of("glow_mushroom", 1), of("flower_blue", 2)));
        r.register(brew("golden_apple", 1, of("apple", 1), of("gold_ingot", 2)));
        r.register(brew("arcane_staff", 1, of("stick", 2), of("amethyst", 2), of("scroll", 1)));
        r.register(brew("fire_staff", 1, of("stick", 2), of("ruby", 2), of("coal", 4)));
        // Elemental staves & relics: brewed from the essences their mobs drop.
        r.register(brew("ember_wand", 1, of("stick", 1), of("fire_essence", 2)));
        r.register(brew("frost_staff", 1, of("stick", 2), of("frost_essence", 3), of("amethyst", 1)));
        r.register(brew("storm_staff", 1, of("stick", 2), of("storm_essence", 3), of("silver_ingot", 1)));
        r.register(brew("venom_staff", 1, of("stick", 2), of("venom_gland", 3)));
        r.register(brew("void_staff", 1, of("stick", 2), of("void_shard", 2), of("shadow_essence", 2)));
        r.register(brew("warp_staff", 1, of("stick", 2), of("shadow_essence", 3), of("amethyst", 2)));
        r.register(brew("meteor_staff", 1, of("fire_staff", 1), of("fire_essence", 4), of("dragon_egg", 1)));
        r.register(brew("harvest_staff", 1, of("stick", 2), of("emerald", 2), of("leaves", 4)));
        r.register(brew("bomb", 2, of("coal", 3), of("rope", 1)));
        r.register(brew("mega_bomb", 1, of("bomb", 3), of("fire_essence", 1)));
        r.register(brew("hermes_boots", 1, of("leather", 2), of("gold_ingot", 2), of("storm_essence", 1)));
        r.register(brew("gravity_amulet", 1, of("amethyst", 2), of("rope", 1)));
        r.register(brew("aether_wings", 1, of("feather_charm", 1), of("frost_essence", 2), of("diamond", 2)));
        r.register(brew("magnet_charm", 1, of("iron_ingot", 3), of("copper", 2)));
        r.register(brew("power_gauntlet", 1, of("leather", 2), of("iron_ingot", 3), of("ruby", 1)));
        r.register(brew("nova_crystal", 1, of("crystal", 2), of("storm_essence", 2), of("diamond", 1)));
        r.register(brew("tremor_totem", 1, of("stone", 4), of("emerald", 1)));
        r.register(brew("magic_carpet", 1, of("leather", 3), of("shadow_essence", 2)));
        r.register(brew("broomstick", 1, of("stick", 2), of("tall_grass", 4), of("shadow_essence", 1)));
        r.register(brew("dragon_horn", 1, of("dragon_egg", 1), of("gold_ingot", 3), of("fire_essence", 3)));
        r.register(brew("glowstone", 2, of("glow_mushroom", 2), of("stone", 1)));
        r.register(brew("magic_light", 1, of("amethyst", 1), of("torch", 1)));
        r.register(brew("crystal", 2, of("diamond", 1), of("stone", 2)));
        r.register(brew("neon_block", 2, of("crystal", 1), of("glowstone", 1)));
        r.register(brew("scroll", 1, of("leather", 2)));
        r.register(brew("cheese", 2, of("cooked_meat", 1), of("mushroom", 1)));
        r.register(brew("obsidian", 1, of("basalt", 2), of("lava", 1)));
        return r;
    }
}
