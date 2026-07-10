package com.larsons.engine.autobattler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The auto-battler's unit registry: the purchasable roster (43 units across
 * five cost tiers, each with an origin + class synergy, so every synergy has
 * several carriers and no single build path is forced) plus the PvE creeps,
 * and the two economy tables every auto-battler needs — how many copies of
 * each unit exist in the shared pool, and the shop rarity odds per player
 * level. Data-driven like the engine's other registries: adding a unit is one
 * {@code register(...)} row.
 *
 * <p>Elemental affinities ride along per unit ({@code atk}/{@code res}/
 * {@code weak} helpers, up to two each): Ember units burn and fear Cryo,
 * Frost units freeze and fear Fire, Mechs fear Corrosive, and so on. Plenty
 * of units carry none — plain damage is always viable.
 */
public final class AutoUnits {

    /**
     * The Wisp: the roster's Io. It fights as a modest Mystic Healer, but its
     * real job is fusing — a 1-star Wisp can stand in for the missing copy of
     * any pair you own, upgrading them on the spot ({@code fuse} action).
     */
    public static final String WISP = "wisp";

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
                650, 40, 0.60, 1, 35, 90, 70, c(96, 170, 90), c(60, 120, 60),
                atk(), res(), weak(Element.FIRE));
        register("ember_imp", "Ember Imp", 1, Trait.EMBER, Trait.MAGE,
                450, 35, 0.70, 3, 10, 70, 90, c(225, 105, 60), c(255, 190, 90),
                atk(Element.FIRE), res(Element.FIRE), weak(Element.CRYO));
        register("frost_archer", "Frost Archer", 1, Trait.FROST, Trait.ARCHER,
                450, 45, 0.75, 4, 10, 90, 70, c(140, 200, 235), c(220, 245, 255),
                atk(Element.CRYO), res(Element.CRYO), weak(Element.FIRE));
        register("storm_pugilist", "Storm Pugilist", 1, Trait.STORM, Trait.BRAWLER,
                700, 40, 0.65, 1, 20, 100, 80, c(150, 130, 230), c(210, 200, 255),
                atk(Element.ELECTRIC), res(Element.ELECTRIC), weak());
        register("shade_rat", "Shade Rat", 1, Trait.SHADOW, Trait.ASSASSIN,
                500, 50, 0.80, 1, 15, 100, 90, c(115, 100, 140), c(190, 170, 220),
                atk(Element.CORROSIVE), res(), weak(Element.ELECTRIC));
        register("cog_pup", "Cog Pup", 1, Trait.MECH, Trait.BRAWLER,
                750, 38, 0.60, 1, 25, 110, 70, c(150, 160, 175), c(220, 225, 235),
                atk(), res(Element.EXPLOSIVE), weak(Element.CORROSIVE, Element.ELECTRIC));
        register("moss_shellback", "Moss Shellback", 1, Trait.FOREST, Trait.BRAWLER,
                780, 35, 0.60, 1, 30, 110, 70, c(105, 150, 85), c(75, 105, 60),
                atk(), res(Element.CORROSIVE), weak(Element.FIRE));
        register("coin_page", "Coin Page", 1, Trait.MERCHANT, Trait.WARRIOR,
                620, 42, 0.70, 1, 22, 90, 60, c(215, 185, 110), c(255, 225, 140));
        register("star_novice", "Star Novice", 1, Trait.MYSTIC, Trait.MAGE,
                460, 33, 0.70, 3, 10, 65, 95, c(185, 160, 230), c(235, 220, 255));
        register("thorn_cub", "Thorn Cub", 1, Trait.WILD, Trait.ASSASSIN,
                520, 48, 0.85, 1, 12, 100, 85, c(170, 135, 75), c(120, 160, 90));

        // --- cost 2 ---------------------------------------------------------
        register("wild_axeman", "Wild Axeman", 2, Trait.WILD, Trait.WARRIOR,
                800, 60, 0.75, 1, 30, 90, 80, c(190, 150, 80), c(120, 85, 40));
        register("cleric", "Wandering Cleric", 2, Trait.HOLY, Trait.HEALER,
                550, 40, 0.65, 3, 15, 60, 140, c(240, 230, 190), c(230, 195, 100));
        register("vine_ranger", "Vine Ranger", 2, Trait.FOREST, Trait.ARCHER,
                550, 60, 0.80, 4, 12, 90, 90, c(110, 185, 100), c(70, 130, 65),
                atk(Element.CORROSIVE), res(), weak(Element.FIRE));
        register("ember_duelist", "Ember Duelist", 2, Trait.EMBER, Trait.ASSASSIN,
                650, 65, 0.85, 1, 20, 100, 110, c(235, 120, 70), c(255, 205, 110),
                atk(Element.FIRE), res(Element.FIRE), weak(Element.CRYO));
        register("frost_adept", "Frost Adept", 2, Trait.FROST, Trait.MAGE,
                550, 45, 0.70, 3, 12, 60, 130, c(120, 185, 230), c(235, 250, 255),
                atk(Element.CRYO), res(Element.CRYO), weak(Element.FIRE));
        register("storm_herald", "Storm Herald", 2, Trait.STORM, Trait.MAGE,
                580, 45, 0.70, 3, 12, 70, 140, c(160, 140, 235), c(220, 210, 255),
                atk(Element.ELECTRIC), res(Element.ELECTRIC), weak());
        register("iron_sentinel", "Iron Sentinel", 2, Trait.MECH, Trait.GUARDIAN,
                950, 45, 0.60, 1, 50, 120, 80, c(135, 145, 160), c(200, 210, 225),
                atk(), res(Element.EXPLOSIVE), weak(Element.CORROSIVE));
        register("glacier_warden", "Glacier Warden", 2, Trait.FROST, Trait.GUARDIAN,
                900, 45, 0.60, 1, 45, 110, 85, c(130, 190, 225), c(225, 245, 255),
                atk(Element.CRYO), res(Element.CRYO), weak(Element.FIRE));
        register("cinder_priest", "Cinder Priest", 2, Trait.EMBER, Trait.HEALER,
                580, 42, 0.65, 3, 14, 65, 150, c(220, 120, 75), c(255, 215, 130),
                atk(Element.FIRE), res(Element.FIRE), weak(Element.CRYO));
        register("gloom_warden", "Gloom Warden", 2, Trait.SHADOW, Trait.GUARDIAN,
                880, 48, 0.62, 1, 42, 115, 80, c(105, 90, 135), c(180, 160, 215),
                atk(), res(Element.CORROSIVE), weak(Element.ELECTRIC));
        register("gale_runner", "Gale Runner", 2, Trait.STORM, Trait.ASSASSIN,
                600, 62, 0.85, 1, 16, 95, 110, c(155, 140, 235), c(215, 210, 255),
                atk(Element.ELECTRIC), res(Element.ELECTRIC), weak());
        register("rune_dancer", "Rune Dancer", 2, Trait.MYSTIC, Trait.ASSASSIN,
                620, 60, 0.80, 1, 18, 100, 105, c(175, 150, 225), c(230, 215, 255));
        register("guild_broker", "Guild Broker", 2, Trait.MERCHANT, Trait.ARCHER,
                540, 58, 0.75, 4, 12, 95, 90, c(210, 175, 100), c(250, 220, 140));

        // --- cost 3 ---------------------------------------------------------
        register("dusk_stalker", "Dusk Stalker", 3, Trait.SHADOW, Trait.ASSASSIN,
                750, 85, 0.90, 1, 25, 100, 150, c(100, 85, 130), c(200, 170, 235),
                atk(Element.CORROSIVE), res(), weak(Element.ELECTRIC));
        register("grove_druid", "Grove Druid", 3, Trait.FOREST, Trait.HEALER,
                700, 50, 0.70, 3, 20, 70, 200, c(85, 160, 85), c(180, 230, 150),
                atk(), res(), weak(Element.FIRE));
        register("ember_knight", "Ember Knight", 3, Trait.EMBER, Trait.WARRIOR,
                1050, 75, 0.75, 1, 45, 100, 120, c(215, 95, 55), c(255, 200, 100),
                atk(Element.FIRE), res(Element.FIRE), weak(Element.CRYO));
        register("frost_colossus", "Frost Colossus", 3, Trait.FROST, Trait.BRAWLER,
                1250, 65, 0.60, 1, 35, 120, 130, c(110, 175, 225), c(230, 248, 255),
                atk(Element.CRYO), res(Element.CRYO), weak(Element.FIRE));
        register("storm_sniper", "Storm Sniper", 3, Trait.STORM, Trait.ARCHER,
                650, 85, 0.85, 5, 15, 100, 140, c(145, 125, 230), c(215, 205, 255),
                atk(Element.ELECTRIC), res(), weak());
        register("dawn_paladin", "Dawn Paladin", 3, Trait.HOLY, Trait.GUARDIAN,
                1150, 60, 0.65, 1, 55, 110, 130, c(235, 215, 140), c(255, 245, 210),
                atk(), res(Element.RADIATION), weak());
        register("bazaar_golem", "Bazaar Golem", 3, Trait.MERCHANT, Trait.GUARDIAN,
                1200, 60, 0.60, 1, 55, 120, 120, c(200, 170, 105), c(245, 215, 145),
                atk(), res(Element.EXPLOSIVE), weak());
        register("clockwork_archer", "Clockwork Archer", 3, Trait.MECH, Trait.ARCHER,
                700, 80, 0.85, 4, 20, 100, 135, c(160, 165, 180), c(225, 230, 240),
                atk(Element.EXPLOSIVE), res(), weak(Element.CORROSIVE));
        register("moon_oracle", "Moon Oracle", 3, Trait.MYSTIC, Trait.HEALER,
                720, 48, 0.70, 3, 18, 70, 210, c(170, 150, 220), c(235, 225, 255));
        register(WISP, "Wisp", 3, Trait.MYSTIC, Trait.HEALER,
                560, 38, 0.70, 2, 14, 70, 160, c(200, 235, 250), c(255, 255, 255));

        // --- cost 4 ---------------------------------------------------------
        register("wild_monarch", "Wild Monarch", 4, Trait.WILD, Trait.BRAWLER,
                1500, 95, 0.80, 1, 40, 130, 180, c(200, 155, 75), c(140, 95, 45));
        register("void_reaper", "Void Reaper", 4, Trait.SHADOW, Trait.MAGE,
                850, 70, 0.75, 3, 20, 80, 260, c(90, 75, 120), c(185, 155, 225),
                atk(Element.RADIATION, Element.CORROSIVE), res(Element.CORROSIVE), weak());
        register("mech_titan", "Mech Titan", 4, Trait.MECH, Trait.GUARDIAN,
                1700, 80, 0.60, 1, 70, 140, 160, c(120, 130, 145), c(210, 220, 235),
                atk(Element.EXPLOSIVE), res(Element.EXPLOSIVE), weak(Element.CORROSIVE));
        register("ash_phoenix", "Ash Phoenix", 4, Trait.EMBER, Trait.MAGE,
                900, 75, 0.80, 3, 20, 70, 240, c(240, 130, 60), c(255, 220, 120),
                atk(Element.FIRE, Element.EXPLOSIVE), res(Element.FIRE), weak(Element.CRYO));
        register("feral_ranger", "Feral Ranger", 4, Trait.WILD, Trait.ARCHER,
                900, 100, 0.90, 4, 20, 110, 170, c(180, 145, 85), c(230, 200, 140));
        register("panda_sage", "Panda Sage", 4, Trait.MYSTIC, Trait.BRAWLER,
                1550, 90, 0.75, 1, 45, 120, 190, c(235, 235, 240), c(45, 45, 55),
                atk(), res(Element.CRYO), weak());

        // --- cost 5 ---------------------------------------------------------
        register("storm_dragon", "Storm Dragon", 5, Trait.STORM, Trait.MAGE,
                1600, 110, 0.80, 2, 40, 100, 350, c(140, 120, 235), c(235, 225, 255),
                atk(Element.ELECTRIC), res(Element.ELECTRIC, Element.CRYO), weak());
        register("high_seraph", "High Seraph", 5, Trait.HOLY, Trait.HEALER,
                1300, 90, 0.75, 3, 30, 80, 380, c(245, 235, 190), c(255, 215, 120),
                atk(), res(Element.RADIATION, Element.CORROSIVE), weak());
        register("eclipse_blade", "Eclipse Blade", 5, Trait.SHADOW, Trait.WARRIOR,
                1500, 130, 0.90, 1, 45, 100, 300, c(80, 70, 105), c(215, 190, 245),
                atk(Element.RADIATION), res(Element.CORROSIVE), weak());
        register("verdant_avatar", "Verdant Avatar", 5, Trait.FOREST, Trait.GUARDIAN,
                1900, 100, 0.65, 1, 60, 120, 320, c(90, 165, 95), c(190, 235, 160),
                atk(), res(Element.CORROSIVE), weak(Element.FIRE));

        // --- PvE creeps (cost 0: no traits, never in shops) -------------------
        // Round 1 creeps are soft on purpose: everyone starts at level 1 with a
        // single fielded unit, and the opener should be winnable solo.
        creep("creep_slime", "Slime", 150, 15, 0.6, 1, 0, c(120, 200, 120));
        creep("creep_bat", "Cave Bat", 320, 28, 0.9, 1, 5, c(120, 110, 140));
        creep("creep_wolf", "Dire Wolf", 700, 55, 0.8, 1, 15, c(140, 130, 125));
        creep("creep_spider", "Venom Spider", 620, 48, 0.75, 1, 10, c(110, 160, 80));
        creep("creep_bandit", "Bandit Cutpurse", 780, 60, 0.8, 1, 15, c(150, 120, 90));
        creep("creep_shaman", "Gnoll Shaman", 700, 65, 0.7, 3, 10, c(180, 150, 110));
        creep("creep_ogre", "Ogre Bruiser", 1600, 90, 0.6, 1, 35, c(160, 140, 90));
        creep("creep_golem", "Stone Golem", 2100, 85, 0.5, 1, 55, c(150, 155, 170));
        creep("creep_wraith", "Grave Wraith", 1500, 115, 0.8, 2, 20, c(130, 140, 170));
        creep("creep_drake", "Elder Drake", 3000, 140, 0.7, 2, 50, c(200, 90, 90));
        creep("creep_titan", "Ancient Titan", 4600, 175, 0.6, 2, 60, c(190, 170, 120));
    }

    private AutoUnits() {}

    private static void register(String key, String name, int cost, Trait origin,
                                 Trait clazz, double hp, double ad, double as,
                                 double range, double armor, double mana,
                                 double spell, Color body, Color accent) {
        UNITS.put(key, new UnitDef(key, name, cost, origin, clazz,
                hp, ad, as, range, armor, mana, spell, body, accent));
    }

    private static void register(String key, String name, int cost, Trait origin,
                                 Trait clazz, double hp, double ad, double as,
                                 double range, double armor, double mana,
                                 double spell, Color body, Color accent,
                                 List<Element> atk, List<Element> res,
                                 List<Element> weak) {
        UNITS.put(key, new UnitDef(key, name, cost, origin, clazz,
                hp, ad, as, range, armor, mana, spell, atk, res, weak, body, accent));
    }

    private static void creep(String key, String name, double hp, double ad,
                              double as, double range, double armor, Color body) {
        UNITS.put(key, new UnitDef(key, name, 0, null, null,
                hp, ad, as, range, armor, 0, 0, body, body.brighter()));
    }

    private static Color c(int r, int g, int b) {
        return new Color(r, g, b);
    }

    private static List<Element> atk(Element... e) {
        return List.of(e);
    }

    private static List<Element> res(Element... e) {
        return List.of(e);
    }

    private static List<Element> weak(Element... e) {
        return List.of(e);
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
     * rounds): mixed packs rather than one species, scaling up with the
     * stage of the game.
     */
    public static List<String> creepWave(int round) {
        if (round <= 1) return List.of("creep_slime", "creep_slime");
        if (round <= 5) {
            return List.of("creep_wolf", "creep_wolf", "creep_bat", "creep_spider");
        }
        if (round <= 10) {
            return List.of("creep_ogre", "creep_ogre", "creep_bandit", "creep_shaman");
        }
        if (round <= 15) {
            return List.of("creep_drake", "creep_golem", "creep_wraith", "creep_shaman");
        }
        if (round <= 20) {
            return List.of("creep_drake", "creep_drake", "creep_wraith", "creep_golem");
        }
        return List.of("creep_titan", "creep_drake", "creep_drake",
                "creep_wraith", "creep_wraith");
    }

    /**
     * The species eligible for a round's <em>randomized</em> wave (the Wild
     * Hunt relic): the stage's regular pack plus its neighbours, so the random
     * draw stays roughly stage-appropriate while feeling wild.
     */
    public static List<String> creepPool(int round) {
        if (round <= 1) return List.of("creep_slime", "creep_bat");
        if (round <= 5) {
            return List.of("creep_wolf", "creep_bat", "creep_spider", "creep_bandit");
        }
        if (round <= 10) {
            return List.of("creep_ogre", "creep_bandit", "creep_shaman",
                    "creep_spider", "creep_golem");
        }
        if (round <= 15) {
            return List.of("creep_drake", "creep_golem", "creep_wraith",
                    "creep_shaman", "creep_ogre");
        }
        return List.of("creep_titan", "creep_drake", "creep_wraith", "creep_golem");
    }

    /**
     * A Wild Hunt wave: one creep bigger than the round's regular pack, drawn
     * at random from the stage's pool — harder, in exchange for the relic's
     * richer rewards.
     */
    public static List<String> randomCreepWave(int round, Random rng) {
        List<String> pool = creepPool(round);
        int size = creepWave(round).size() + 1;
        List<String> wave = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            wave.add(pool.get(rng.nextInt(pool.size())));
        }
        return wave;
    }

    /** True when a round fights creeps instead of another player. */
    public static boolean isCreepRound(int round) {
        return round == 1 || round % 5 == 0;
    }
}
