package com.larsons.engine.world.gen;

import com.larsons.engine.world.gen.Biome.Realm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The biome set a generated world starts with: ten places to stand above
 * ground and eight to find under it.
 *
 * <p><b>These are defaults, not a fixed list.</b> Every one of them is written
 * through the same public fields the settings menu edits, and a creator can
 * retune any of them, turn any of them off, or add their own with the
 * <em>+ New biome</em> button. Nothing in the generator knows these keys —
 * {@link WorldGenerator} reads whatever list the level carries — so a world
 * built entirely out of biomes somebody invented works exactly as well as this
 * one.
 *
 * <p>The numbers were chosen so the world reads as varied at a walking pace:
 * heights spread from an ocean shelf under the water line to peaks near layer
 * 300, climates spread across the whole temperature and humidity range so no
 * single biome dominates, and each one has a decoration set distinctive enough
 * that you can tell where you are from the ground cover alone.
 */
public final class Biomes {

    private Biomes() {}

    /** A fresh copy of the standard biome list (18 biomes). */
    public static List<Biome> standard() {
        List<Biome> out = new ArrayList<>(18);
        out.add(plains());
        out.add(deciduous());
        out.add(pine());
        out.add(icy());
        out.add(mountainous());
        out.add(tropics());
        out.add(jungle());
        out.add(desert());
        out.add(village());
        out.add(ancientCity());
        out.add(regularCave());
        out.add(glowingRainbowCave());
        out.add(iceCave());
        out.add(acidCave());
        out.add(obsidianCave());
        out.add(lavaCave());
        out.add(waterCave());
        out.add(ancientTemple());
        for (Biome b : out) b.normalize();
        return out;
    }

    /**
     * The biome the <em>+ New biome</em> button creates: a working, recognizable
     * place of the given realm rather than an empty row.
     *
     * <p>A blank form is a worse starting point than a plain one. A creator who
     * adds a biome and walks out to find grey nothing has learned only that the
     * button did something; one who finds a meadow has a thing in front of them
     * to change.
     */
    public static Biome blank(Realm realm) {
        Biome b = realm == Realm.UNDERGROUND ? regularCave() : plains();
        b.key = realm == Realm.UNDERGROUND ? "new_cave" : "new_biome";
        b.name = realm == Realm.UNDERGROUND ? "New Cave" : "New Biome";
        b.weight = 20;
        b.normalize();
        return b;
    }

    // --- above ground ----------------------------------------------------------

    /** Open meadow: the world's baseline, and the most of it. */
    public static Biome plains() {
        Biome b = new Biome("plains", "Plains", Realm.SURFACE);
        b.weight = 70;
        b.targetLevel = 154;
        b.relief = 7;
        b.temperature = 55;
        b.humidity = 48;
        b.surfaceBlock = "grass";
        b.subsurfaceBlock = "dirt";
        b.stoneBlock = "stone";
        b.beachBlock = "sand";
        b.soilDepth = 4;
        b.treeDensity = 5;
        b.logBlock = "oak_log";
        b.leafBlock = "leaves";
        b.treeMinHeight = 4;
        b.treeMaxHeight = 7;
        b.grassDensity = 58;
        b.decorDensity = 20;
        b.decorBlocks = list("flower_red", "flower_yellow", "flower_blue",
                "tulip_pink", "lavender", "berry_bush");
        b.caveDensity = 50;
        b.oreRichness = 50;
        return b;
    }

    /** Broadleaf woodland: oak and birch over deep leaf litter. */
    public static Biome deciduous() {
        Biome b = new Biome("deciduous", "Deciduous Forest", Realm.SURFACE);
        b.weight = 55;
        b.targetLevel = 159;
        b.relief = 13;
        b.temperature = 48;
        b.humidity = 64;
        b.surfaceBlock = "grass";
        b.subsurfaceBlock = "dirt";
        b.stoneBlock = "stone";
        b.beachBlock = "sand";
        b.soilDepth = 5;
        b.treeDensity = 44;
        b.logBlock = "oak_log";
        b.leafBlock = "leaves";
        b.treeMinHeight = 5;
        b.treeMaxHeight = 9;
        b.grassDensity = 46;
        b.decorDensity = 26;
        b.decorBlocks = list("fern", "mushroom", "berry_bush", "flower_red",
                "flower_blue", "cherry_blossom");
        b.caveDensity = 55;
        b.oreRichness = 52;
        return b;
    }

    /** Conifer highland: dark needles over podzol, and it climbs. */
    public static Biome pine() {
        Biome b = new Biome("pine", "Pine Forest", Realm.SURFACE);
        b.weight = 50;
        b.targetLevel = 168;
        b.relief = 24;
        b.temperature = 26;
        b.humidity = 56;
        b.surfaceBlock = "podzol";
        b.subsurfaceBlock = "dirt";
        b.stoneBlock = "stone";
        b.beachBlock = "gravel";
        b.soilDepth = 4;
        b.treeDensity = 52;
        b.logBlock = "pine_log";
        b.leafBlock = "pine_needles";
        b.treeMinHeight = 7;
        b.treeMaxHeight = 13;
        b.grassDensity = 24;
        b.decorDensity = 26;
        b.decorBlocks = list("fern", "mushroom", "berry_bush", "tundra_shrub", "roots");
        b.caveDensity = 58;
        b.oreRichness = 56;
        return b;
    }

    /** Frozen tundra: snow over permafrost, and the water freezes. */
    public static Biome icy() {
        Biome b = new Biome("icy", "Icy Tundra", Realm.SURFACE);
        b.weight = 40;
        b.targetLevel = 157;
        b.relief = 15;
        b.temperature = 5;
        b.humidity = 34;
        b.surfaceBlock = "snow";
        b.subsurfaceBlock = "permafrost";
        b.stoneBlock = "stone";
        b.liquidBlock = "water";
        b.beachBlock = "packed_ice";
        b.soilDepth = 4;
        b.snowy = true;
        b.treeDensity = 9;
        b.logBlock = "pine_log";
        b.leafBlock = "pine_needles";
        b.treeMinHeight = 5;
        b.treeMaxHeight = 9;
        b.grassDensity = 6;
        b.decorDensity = 22;
        b.decorBlocks = list("icicle", "tundra_shrub", "blue_ice", "snow_layer", "dead_bush");
        b.caveDensity = 46;
        b.oreRichness = 54;
        return b;
    }

    /** Peaks: bare rock and snow caps, and the only thing in the world above 250. */
    public static Biome mountainous() {
        Biome b = new Biome("mountainous", "Mountains", Realm.SURFACE);
        b.weight = 24;
        b.targetLevel = 218;
        b.relief = 64;
        b.temperature = 24;
        b.humidity = 32;
        b.surfaceBlock = "stone";
        b.subsurfaceBlock = "gravel";
        b.stoneBlock = "granite";
        b.beachBlock = "gravel";
        b.soilDepth = 3;
        b.snowy = true;
        b.treeDensity = 4;
        b.logBlock = "pine_log";
        b.leafBlock = "pine_needles";
        b.treeMinHeight = 4;
        b.treeMaxHeight = 7;
        b.grassDensity = 8;
        b.decorDensity = 18;
        b.decorBlocks = list("mossy_cobble", "dead_bush", "tundra_shrub",
                "snow_layer", "amethyst_cluster");
        b.caveDensity = 74;
        b.oreRichness = 78;
        return b;
    }

    /** Warm coast: palms over pale sand, and coral in the shallows. */
    public static Biome tropics() {
        Biome b = new Biome("tropics", "Tropics", Realm.SURFACE);
        b.weight = 34;
        b.targetLevel = 152;
        b.relief = 6;
        b.temperature = 82;
        b.humidity = 66;
        b.surfaceBlock = "sand";
        b.subsurfaceBlock = "sandstone";
        b.stoneBlock = "limestone";
        b.beachBlock = "sand";
        b.soilDepth = 4;
        b.treeDensity = 20;
        b.logBlock = "palm_log";
        b.leafBlock = "palm_leaves";
        b.treeMinHeight = 6;
        b.treeMaxHeight = 10;
        b.grassDensity = 14;
        b.decorDensity = 34;
        b.decorBlocks = list("coral_pink", "coral_blue", "coral_yellow",
                "sea_grass", "sugar_cane", "lily_pad", "orchid");
        b.caveDensity = 40;
        b.oreRichness = 44;
        return b;
    }

    /** Rainforest: the densest canopy in the world, and the tallest trunks. */
    public static Biome jungle() {
        Biome b = new Biome("jungle", "Jungle", Realm.SURFACE);
        b.weight = 36;
        b.targetLevel = 162;
        b.relief = 20;
        b.temperature = 90;
        b.humidity = 94;
        b.surfaceBlock = "grass";
        b.subsurfaceBlock = "rich_soil";
        b.stoneBlock = "stone";
        b.beachBlock = "sand";
        b.soilDepth = 6;
        b.treeDensity = 68;
        b.logBlock = "jungle_log";
        b.leafBlock = "jungle_leaves";
        b.treeMinHeight = 9;
        b.treeMaxHeight = 17;
        b.grassDensity = 72;
        b.decorDensity = 42;
        b.decorBlocks = list("jungle_fern", "orchid", "bamboo_shoot", "vines",
                "berry_bush", "pumpkin", "glow_mushroom");
        b.caveDensity = 60;
        b.oreRichness = 52;
        return b;
    }

    /** Dunes: red and pale sand, cactus, and nothing green. */
    public static Biome desert() {
        Biome b = new Biome("desert", "Desert", Realm.SURFACE);
        b.weight = 44;
        b.targetLevel = 155;
        b.relief = 12;
        b.temperature = 97;
        b.humidity = 5;
        b.surfaceBlock = "sand";
        b.subsurfaceBlock = "sandstone";
        b.stoneBlock = "sandstone";
        b.beachBlock = "sand";
        b.soilDepth = 6;
        b.treeDensity = 3;
        b.logBlock = "cactus";
        b.leafBlock = "";
        b.treeMinHeight = 2;
        b.treeMaxHeight = 5;
        b.grassDensity = 3;
        b.decorDensity = 16;
        b.decorBlocks = list("desert_shrub", "dead_bush", "cactus", "red_sand", "terracotta");
        b.caveDensity = 44;
        b.oreRichness = 48;
        return b;
    }

    /** Settled country: farmland, thatched huts and a path between them. */
    public static Biome village() {
        Biome b = new Biome("village", "Village", Realm.SURFACE);
        b.weight = 22;
        b.targetLevel = 153;
        b.relief = 5;
        b.temperature = 58;
        b.humidity = 46;
        b.surfaceBlock = "grass";
        b.subsurfaceBlock = "dirt";
        b.stoneBlock = "stone";
        b.beachBlock = "sand";
        b.soilDepth = 4;
        b.treeDensity = 7;
        b.logBlock = "oak_log";
        b.leafBlock = "leaves";
        b.treeMinHeight = 4;
        b.treeMaxHeight = 7;
        b.grassDensity = 30;
        b.decorDensity = 24;
        b.decorBlocks = list("hay_bale", "pumpkin", "cobbled_path", "flower_yellow",
                "crate", "lantern");
        b.structureDensity = 62;
        b.structureBlock = "village_planks";
        b.structureAccentBlock = "village_roof";
        b.lightBlock = "lantern";
        b.lightDensity = 6;
        b.caveDensity = 40;
        b.oreRichness = 46;
        return b;
    }

    /** A city that was here first: stone terraces, pillars and glowing runes. */
    public static Biome ancientCity() {
        Biome b = new Biome("ancient_city", "Ancient City", Realm.SURFACE);
        b.weight = 8;
        b.targetLevel = 170;
        b.relief = 11;
        b.temperature = 44;
        b.humidity = 33;
        b.surfaceBlock = "stone_bricks";
        b.subsurfaceBlock = "cracked_bricks";
        b.stoneBlock = "stone";
        b.beachBlock = "gravel";
        b.soilDepth = 4;
        b.treeDensity = 2;
        b.logBlock = "dark_log";
        b.leafBlock = "leaves";
        b.treeMinHeight = 4;
        b.treeMaxHeight = 7;
        b.grassDensity = 10;
        b.decorDensity = 30;
        b.decorBlocks = list("cracked_bricks", "temple_pillar", "moss",
                "mossy_bricks", "rune_stone", "bone_block");
        b.structureDensity = 88;
        b.structureBlock = "ancient_bricks";
        b.structureAccentBlock = "gilded_bricks";
        b.lightBlock = "rune_stone";
        b.lightDensity = 13;
        b.caveDensity = 62;
        b.oreRichness = 60;
        return b;
    }

    // --- below ground ----------------------------------------------------------

    /** Plain rock caverns: what most of the underground is. */
    public static Biome regularCave() {
        Biome b = new Biome("regular_cave", "Caves", Realm.UNDERGROUND);
        b.weight = 60;
        b.targetLevel = 92;
        b.relief = 44;
        b.temperature = 48;
        b.humidity = 48;
        b.surfaceBlock = "stone";
        b.subsurfaceBlock = "stone";
        b.stoneBlock = "stone";
        b.liquidBlock = "";
        b.beachBlock = "gravel";
        b.soilDepth = 2;
        b.grassDensity = 0;
        b.decorDensity = 22;
        b.decorBlocks = list("stalagmite", "mushroom", "dripstone", "roots", "web");
        b.lightBlock = "glow_mushroom";
        b.lightDensity = 4;
        b.caveDensity = 56;
        b.oreRichness = 62;
        return b;
    }

    /** The bright one: crystals in every colour, lit from inside. */
    public static Biome glowingRainbowCave() {
        Biome b = new Biome("glowing_rainbow", "Glowing Rainbow Caves", Realm.UNDERGROUND);
        b.weight = 14;
        b.targetLevel = 72;
        b.relief = 30;
        b.temperature = 52;
        b.humidity = 60;
        b.surfaceBlock = "rainbow_moss";
        b.subsurfaceBlock = "marble";
        b.stoneBlock = "marble";
        b.liquidBlock = "";
        b.beachBlock = "rainbow_moss";
        b.soilDepth = 2;
        b.decorDensity = 46;
        b.decorBlocks = list("crystal_shard", "rainbow_glass", "glow_lichen",
                "amethyst_cluster", "neon_block");
        b.lightBlock = "rainbow_crystal";
        b.lightDensity = 34;
        b.caveDensity = 66;
        b.oreRichness = 70;
        return b;
    }

    /** Ice caverns: blue ice underfoot and frost hanging off the ceiling. */
    public static Biome iceCave() {
        Biome b = new Biome("ice_cave", "Ice Caves", Realm.UNDERGROUND);
        b.weight = 16;
        b.targetLevel = 124;
        b.relief = 30;
        b.temperature = 6;
        b.humidity = 44;
        b.surfaceBlock = "blue_ice";
        b.subsurfaceBlock = "packed_ice";
        b.stoneBlock = "permafrost";
        b.liquidBlock = "water";
        b.beachBlock = "packed_ice";
        b.soilDepth = 3;
        b.snowy = true;
        b.decorDensity = 34;
        b.decorBlocks = list("icicle", "glacier_ice", "snow_layer", "ice", "crystal_shard");
        b.lightBlock = "frost_lantern";
        b.lightDensity = 10;
        b.caveDensity = 58;
        b.oreRichness = 56;
        return b;
    }

    /** Acid caverns: green pools that eat anything that stands in them. */
    public static Biome acidCave() {
        Biome b = new Biome("acid_cave", "Acid Caves", Realm.UNDERGROUND);
        b.weight = 18;
        b.targetLevel = 82;
        b.relief = 28;
        b.temperature = 80;
        b.humidity = 68;
        b.surfaceBlock = "acid_stone";
        b.subsurfaceBlock = "slate";
        b.stoneBlock = "slate";
        b.liquidBlock = "acid";
        b.liquidLevelOffset = -6;
        b.beachBlock = "sulphur";
        b.soilDepth = 2;
        b.decorDensity = 32;
        b.decorBlocks = list("sulphur", "glow_lichen", "mushroom", "thorns", "cave_moss");
        b.lightBlock = "glow_lichen";
        b.lightDensity = 12;
        b.caveDensity = 62;
        b.oreRichness = 58;
        return b;
    }

    /** Obsidian caverns: black glass, and it is a long way down to them. */
    public static Biome obsidianCave() {
        Biome b = new Biome("obsidian_cave", "Obsidian Caves", Realm.UNDERGROUND);
        b.weight = 11;
        b.targetLevel = 44;
        b.relief = 26;
        b.temperature = 58;
        b.humidity = 24;
        b.surfaceBlock = "obsidian";
        b.subsurfaceBlock = "basalt";
        b.stoneBlock = "basalt";
        b.liquidBlock = "";
        b.beachBlock = "basalt";
        b.soilDepth = 2;
        b.decorDensity = 30;
        b.decorBlocks = list("obsidian_glass", "crystal_shard", "amethyst_ore", "ash");
        b.lightBlock = "obsidian_shard";
        b.lightDensity = 16;
        b.caveDensity = 54;
        b.oreRichness = 76;
        return b;
    }

    /** Lava caverns: the floor of the world, and it is on fire. */
    public static Biome lavaCave() {
        Biome b = new Biome("lava_cave", "Lava Caves", Realm.UNDERGROUND);
        b.weight = 16;
        b.targetLevel = 24;
        b.relief = 18;
        b.temperature = 98;
        b.humidity = 8;
        b.surfaceBlock = "lava_rock";
        b.subsurfaceBlock = "basalt";
        b.stoneBlock = "deepslate";
        b.liquidBlock = "lava";
        b.liquidLevelOffset = -4;
        b.beachBlock = "ash";
        b.soilDepth = 2;
        b.decorDensity = 30;
        b.decorBlocks = list("magma_stone", "ash", "charred_log", "ember_stone", "soul_sand");
        b.lightBlock = "magma_stone";
        b.lightDensity = 20;
        b.caveDensity = 60;
        b.oreRichness = 72;
        return b;
    }

    /** Flooded caverns: prismarine galleries you swim through. */
    public static Biome waterCave() {
        Biome b = new Biome("water_cave", "Water Caves", Realm.UNDERGROUND);
        b.weight = 14;
        b.targetLevel = 112;
        b.relief = 26;
        b.temperature = 42;
        b.humidity = 96;
        b.surfaceBlock = "prismarine";
        b.subsurfaceBlock = "limestone";
        b.stoneBlock = "limestone";
        b.liquidBlock = "water";
        b.liquidLevelOffset = 10;
        b.beachBlock = "sand";
        b.soilDepth = 3;
        b.decorDensity = 36;
        b.decorBlocks = list("sea_grass", "coral_blue", "coral_pink", "glow_lichen", "clay");
        b.lightBlock = "glow_lichen";
        b.lightDensity = 14;
        b.caveDensity = 64;
        b.oreRichness = 54;
        return b;
    }

    /** A buried temple: cut stone where there should be rock, and gold in it. */
    public static Biome ancientTemple() {
        Biome b = new Biome("ancient_temple", "Ancient Temple", Realm.UNDERGROUND);
        b.weight = 9;
        b.targetLevel = 62;
        b.relief = 20;
        b.temperature = 46;
        b.humidity = 30;
        b.surfaceBlock = "ancient_bricks";
        b.subsurfaceBlock = "cracked_bricks";
        b.stoneBlock = "stone";
        b.liquidBlock = "";
        b.beachBlock = "cracked_bricks";
        b.soilDepth = 3;
        b.decorDensity = 40;
        b.decorBlocks = list("bone_block", "fossil", "temple_pillar", "cracked_bricks",
                "gilded_bricks", "web");
        b.structureDensity = 96;
        b.structureBlock = "ancient_bricks";
        b.structureAccentBlock = "gilded_bricks";
        b.lightBlock = "rune_stone";
        b.lightDensity = 18;
        b.caveDensity = 50;
        b.oreRichness = 66;
        return b;
    }

    private static List<String> list(String... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }
}
