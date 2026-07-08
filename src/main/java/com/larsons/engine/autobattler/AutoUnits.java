package com.larsons.engine.autobattler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The auto-battler's unit registry: the purchasable roster (28 units across
 * five cost tiers, each with an origin + class synergy) plus the PvE creeps,
 * and the two economy tables every auto-battler needs — how many copies of
 * each unit exist in the shared pool, and the shop rarity odds per player
 * level. Data-driven like the engine's other registries: adding a unit is one
 * {@code register(...)} row.
 */
public final class AutoUnits {

    private static final Map<String, UnitDef> UNITS = new LinkedHashMap<>();

    /** Copies of each unit in the shared pool, indexed by cost-1 (TFT-style). */
    public static final int[] POOL_COPIES = {29, 22, 18, 12, 10};

    /**
     * Shop odds (%) of each cost tier by player level 1-9. Rows sum to 100;
     * index [level-1][cost-1].
     */
    public static final int[][] SHOP_ODDS = {
            {100, 0, 0, 0, 0},   // level 1
            {100, 0, 0, 0, 0},   // level 2
            {75, 25, 0, 0, 0},   // level 3
            {55, 30, 15, 0, 0},  // level 4
            {45, 33, 20, 2, 0},  // level 5
            {30, 40, 25, 5, 0},  // level 6
            {19, 30, 35, 15, 1}, // level 7
            {17, 24, 32, 24, 3}, // level 8
            {10, 15, 30, 30, 15} // level 9
    };

    static {
        // --- cost 1 ---------------------------------------------------------
        register("squire", "Squire", 1, Trait.HOLY, Trait.WARRIOR,
                600, 45, 0.70, 1, 25, 80, 60, c(228, 214, 160), c(255, 240, 190));
        register("sapling", "Sapling Guard", 1, Trait.FOREST, Trait.GUARDIAN,
                650, 40, 0.60, 1, 35, 90, 70, c(96, 170, 90), c(60, 120, 60));
        register("ember_imp", "Ember Imp", 1, Trait.EMBER, Trait.MAGE,
                450, 35, 0.70, 3, 10, 70, 90, c(225, 105, 60), c(255, 190, 90));
        register("frost_archer", "Frost Archer", 1, Trait.FROST, Trait.ARCHER,
                450, 45, 0.75, 4, 10, 90, 70, c(140, 200, 235), c(220, 245, 255));
        register("storm_pugilist", "Storm Pugilist", 1, Trait.STORM, Trait.BRAWLER,
                700, 40, 0.65, 1, 20, 100, 80, c(150, 130, 230), c(210, 200, 255));
        register("shade_rat", "Shade Rat", 1, Trait.SHADOW, Trait.ASSASSIN,
                500, 50, 0.80, 1, 15, 100, 90, c(115, 100, 140), c(190, 170, 220));
        register("cog_pup", "Cog Pup", 1, Trait.MECH, Trait.BRAWLER,
                750, 38, 0.60, 1, 25, 110, 70, c(150, 160, 175), c(220, 225, 235));

        // --- cost 2 ---------------------------------------------------------
        register("wild_axeman", "Wild Axeman", 2, Trait.WILD, Trait.WARRIOR,
                800, 60, 0.75, 1, 30, 90, 80, c(190, 150, 80), c(120, 85, 40));
        register("cleric", "Wandering Cleric", 2, Trait.HOLY, Trait.HEALER,
                550, 40, 0.65, 3, 15, 60, 140, c(240, 230, 190), c(230, 195, 100));
        register("vine_ranger", "Vine Ranger", 2, Trait.FOREST, Trait.ARCHER,
                550, 60, 0.80, 4, 12, 90, 90, c(110, 185, 100), c(70, 130, 65));
        register("ember_duelist", "Ember Duelist", 2, Trait.EMBER, Trait.ASSASSIN,
                650, 65, 0.85, 1, 20, 100, 110, c(235, 120, 70), c(255, 205, 110));
        register("frost_adept", "Frost Adept", 2, Trait.FROST, Trait.MAGE,
                550, 45, 0.70, 3, 12, 60, 130, c(120, 185, 230), c(235, 250, 255));
        register("storm_herald", "Storm Herald", 2, Trait.STORM, Trait.MAGE,
                580, 45, 0.70, 3, 12, 70, 140, c(160, 140, 235), c(220, 210, 255));
        register("iron_sentinel", "Iron Sentinel", 2, Trait.MECH, Trait.GUARDIAN,
                950, 45, 0.60, 1, 50, 120, 80, c(135, 145, 160), c(200, 210, 225));

        // --- cost 3 ---------------------------------------------------------
        register("dusk_stalker", "Dusk Stalker", 3, Trait.SHADOW, Trait.ASSASSIN,
                750, 85, 0.90, 1, 25, 100, 150, c(100, 85, 130), c(200, 170, 235));
        register("grove_druid", "Grove Druid", 3, Trait.FOREST, Trait.HEALER,
                700, 50, 0.70, 3, 20, 70, 200, c(85, 160, 85), c(180, 230, 150));
        register("ember_knight", "Ember Knight", 3, Trait.EMBER, Trait.WARRIOR,
                1050, 75, 0.75, 1, 45, 100, 120, c(215, 95, 55), c(255, 200, 100));
        register("frost_colossus", "Frost Colossus", 3, Trait.FROST, Trait.BRAWLER,
                1250, 65, 0.60, 1, 35, 120, 130, c(110, 175, 225), c(230, 248, 255));
        register("storm_sniper", "Storm Sniper", 3, Trait.STORM, Trait.ARCHER,
                650, 85, 0.85, 5, 15, 100, 140, c(145, 125, 230), c(215, 205, 255));
        register("dawn_paladin", "Dawn Paladin", 3, Trait.HOLY, Trait.GUARDIAN,
                1150, 60, 0.65, 1, 55, 110, 130, c(235, 215, 140), c(255, 245, 210));

        // --- cost 4 ---------------------------------------------------------
        register("wild_monarch", "Wild Monarch", 4, Trait.WILD, Trait.BRAWLER,
                1500, 95, 0.80, 1, 40, 130, 180, c(200, 155, 75), c(140, 95, 45));
        register("void_reaper", "Void Reaper", 4, Trait.SHADOW, Trait.MAGE,
                850, 70, 0.75, 3, 20, 80, 260, c(90, 75, 120), c(185, 155, 225));
        register("mech_titan", "Mech Titan", 4, Trait.MECH, Trait.GUARDIAN,
                1700, 80, 0.60, 1, 70, 140, 160, c(120, 130, 145), c(210, 220, 235));
        register("ash_phoenix", "Ash Phoenix", 4, Trait.EMBER, Trait.MAGE,
                900, 75, 0.80, 3, 20, 70, 240, c(240, 130, 60), c(255, 220, 120));
        register("feral_ranger", "Feral Ranger", 4, Trait.WILD, Trait.ARCHER,
                900, 100, 0.90, 4, 20, 110, 170, c(180, 145, 85), c(230, 200, 140));

        // --- cost 5 ---------------------------------------------------------
        register("storm_dragon", "Storm Dragon", 5, Trait.STORM, Trait.MAGE,
                1600, 110, 0.80, 2, 40, 100, 350, c(140, 120, 235), c(235, 225, 255));
        register("high_seraph", "High Seraph", 5, Trait.HOLY, Trait.HEALER,
                1300, 90, 0.75, 3, 30, 80, 380, c(245, 235, 190), c(255, 215, 120));
        register("eclipse_blade", "Eclipse Blade", 5, Trait.SHADOW, Trait.WARRIOR,
                1500, 130, 0.90, 1, 45, 100, 300, c(80, 70, 105), c(215, 190, 245));

        // --- PvE creeps (cost 0: no traits, never in shops) -------------------
        creep("creep_slime", "Slime", 300, 25, 0.6, 1, 5, c(120, 200, 120));
        creep("creep_wolf", "Dire Wolf", 700, 55, 0.8, 1, 15, c(140, 130, 125));
        creep("creep_ogre", "Ogre Bruiser", 1600, 90, 0.6, 1, 35, c(160, 140, 90));
        creep("creep_drake", "Elder Drake", 3000, 140, 0.7, 2, 50, c(200, 90, 90));
    }

    private AutoUnits() {}

    private static void register(String key, String name, int cost, Trait origin,
                                 Trait clazz, double hp, double ad, double as,
                                 double range, double armor, double mana,
                                 double spell, Color body, Color accent) {
        UNITS.put(key, new UnitDef(key, name, cost, origin, clazz,
                hp, ad, as, range, armor, mana, spell, body, accent));
    }

    private static void creep(String key, String name, double hp, double ad,
                              double as, double range, double armor, Color body) {
        UNITS.put(key, new UnitDef(key, name, 0, null, null,
                hp, ad, as, range, armor, 0, 0, body, body.brighter()));
    }

    private static Color c(int r, int g, int b) {
        return new Color(r, g, b);
    }

    public static UnitDef get(String key) {
        return UNITS.get(key);
    }

    /** Every registered unit, creeps included, in registration order. */
    public static List<UnitDef> all() {
        return new ArrayList<>(UNITS.values());
    }

    /** The purchasable roster (creeps excluded). */
    public static List<UnitDef> roster() {
        List<UnitDef> out = new ArrayList<>();
        for (UnitDef d : UNITS.values()) if (!d.isCreep()) out.add(d);
        return out;
    }

    public static List<UnitDef> byCost(int cost) {
        List<UnitDef> out = new ArrayList<>();
        for (UnitDef d : UNITS.values()) if (d.cost == cost) out.add(d);
        return out;
    }

    /**
     * The PvE creep wave for a round (rounds 1, 5, 10, 15... are creep
     * rounds), scaling up with the stage of the game.
     */
    public static List<String> creepWave(int round) {
        if (round <= 1) return List.of("creep_slime", "creep_slime", "creep_slime");
        if (round <= 5) return List.of("creep_wolf", "creep_wolf", "creep_wolf", "creep_wolf");
        if (round <= 10) return List.of("creep_ogre", "creep_ogre", "creep_ogre", "creep_ogre");
        if (round <= 15) return List.of("creep_drake", "creep_drake", "creep_drake");
        List<String> wave = new ArrayList<>(Collections.nCopies(5, "creep_drake"));
        return wave;
    }

    /** True when a round fights creeps instead of another player. */
    public static boolean isCreepRound(int round) {
        return round == 1 || round % 5 == 0;
    }
}
