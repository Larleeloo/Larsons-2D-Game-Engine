package com.larsons.engine.world;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of {@link Block} definitions a world can be painted with.
 *
 * <p>Ported from the Side-Scroller engine's singleton {@code BlockRegistry} +
 * {@code BlockType} enum, improved two ways while moving over:
 * <ul>
 *   <li><b>Data-driven:</b> blocks are registered rows, not enum constants, so
 *       a game extends the set with {@link #register} instead of editing
 *       engine sources.</li>
 *   <li><b>Deterministic ids:</b> every block has a stable numeric id that is
 *       what level tile grids store and what block edits send over the wire —
 *       clients and servers always agree on what id 7 means.</li>
 * </ul>
 *
 * <p>{@link #standard()} returns the shared default set: the side-scroller's
 * terrain/nature/ore/weather blocks plus its creative-palette light entities
 * (torch, campfire, lantern, magic, crystal) folded in as light-emitting
 * blocks. The first five ids intentionally line up with the legacy palette
 * order ({@code dirt, grass, stone, water, sand}) so old palette-based levels
 * keep their meaning if reinterpreted against the registry.
 */
public final class BlockRegistry {

    private final Map<Integer, Block> byId = new LinkedHashMap<>();
    private final Map<String, Block> byKey = new LinkedHashMap<>();

    private static final BlockRegistry STANDARD = createStandard();

    /** The engine's built-in block set (shared, immutable by convention). */
    public static BlockRegistry standard() {
        return STANDARD;
    }

    /** Register a block; rejects duplicate ids/keys so ids stay stable. */
    public void register(Block b) {
        if (byId.containsKey(b.id())) {
            throw new IllegalArgumentException("Duplicate block id " + b.id());
        }
        if (byKey.containsKey(b.key())) {
            throw new IllegalArgumentException("Duplicate block key " + b.key());
        }
        byId.put(b.id(), b);
        byKey.put(b.key(), b);
    }

    /**
     * Replace a registered block's durability (hardness seconds + preferred
     * tool class) in place. Registrations stay readable and ids stable while
     * the mining feel is tuned per family below.
     */
    public void tune(String key, double hardness, String tool) {
        Block b = byKey.get(key);
        if (b == null) return;
        Block tuned = b.withDurability(hardness, tool);
        byId.put(tuned.id(), tuned);
        byKey.put(tuned.key(), tuned);
    }

    /** Toggle a registered block's gravity behaviour in place (sand, gravel…). */
    public void setFalling(String key, boolean falling) {
        Block b = byKey.get(key);
        if (b == null || b.falling() == falling) return;
        Block next = b.withFalling(falling);
        byId.put(next.id(), next);
        byKey.put(next.key(), next);
    }

    /**
     * Remove a block (deleting user-created custom content). Levels that still
     * reference the id render it as the loud magenta placeholder.
     */
    public void unregister(String key) {
        Block b = byKey.remove(key);
        if (b != null) byId.remove(b.id());
    }

    /** The block with this id, or {@code null} (id 0 = empty). */
    public Block get(int id) {
        return byId.get(id);
    }

    /** The block with this key, or {@code null}. */
    public Block get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All blocks, in registration order (drives the creative palette). */
    public List<Block> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public boolean isSolid(int id) {
        Block b = byId.get(id);
        return b != null && b.solid();
    }

    /** Render colour for a tile id, or {@code null} for empty/unknown. */
    public Color colorOf(int id) {
        Block b = byId.get(id);
        return b == null ? null : b.color();
    }

    private static BlockRegistry createStandard() {
        BlockRegistry r = new BlockRegistry();
        // Ids 1-5 mirror the legacy level palette order (dirt, grass, stone,
        // water, sand) so levels written against it stay recognizable. Water
        // (id 4) is now a real liquid source: old levels' pools start flowing.
        r.register(Block.terrain(1, "dirt", "Dirt", new Color(120, 90, 60)));
        r.register(Block.terrain(2, "grass", "Grass", new Color(90, 160, 80)));
        r.register(Block.terrain(3, "stone", "Stone", new Color(110, 110, 120)));
        r.register(Block.liquidSource(4, "water", "Water",
                new Color(70, 120, 200, 175), 0, 0, null));
        r.register(Block.terrain(5, "sand", "Sand", new Color(220, 200, 120)));
        // The rest of the side-scroller's block set.
        r.register(Block.terrain(6, "cobblestone", "Cobblestone", new Color(130, 128, 122)));
        r.register(Block.terrain(7, "wood", "Wood", new Color(150, 110, 60)));
        r.register(Block.passable(8, "leaves", "Leaves", new Color(60, 130, 55)));
        r.register(Block.terrain(9, "brick", "Brick", new Color(160, 70, 60)));
        // Glass is a solid pane with a translucent colour, so whatever is
        // behind it shows through (translucent colours render translucent).
        r.register(Block.terrain(10, "glass", "Glass", new Color(200, 228, 242, 80)));
        r.register(new Block(11, "coal_ore", "Coal Ore", new Color(80, 80, 85),
                true, 0, null, "coal"));
        r.register(new Block(12, "iron_ore", "Iron Ore", new Color(170, 145, 125),
                true, 0, null, "iron_ore"));
        r.register(new Block(13, "gold_ore", "Gold Ore", new Color(200, 170, 80),
                true, 0, null, "gold_ore"));
        r.register(Block.terrain(14, "snow", "Snow", new Color(235, 240, 245)));
        r.register(Block.passable(15, "ice", "Ice", new Color(160, 200, 235)));
        r.register(Block.terrain(16, "moss", "Moss", new Color(85, 120, 70)));
        r.register(Block.passable(17, "vines", "Vines", new Color(70, 110, 60)));
        r.register(Block.terrain(18, "platform", "Platform", new Color(140, 120, 90)));
        // Side-scroller creative-palette lights, folded in as blocks.
        r.register(Block.light(19, "torch", "Torch", new Color(250, 190, 90),
                5, new Color(255, 200, 120)));
        r.register(Block.light(20, "campfire", "Campfire", new Color(235, 120, 60),
                7, new Color(255, 170, 90)));
        r.register(Block.light(21, "lantern", "Lantern", new Color(245, 225, 150),
                6, new Color(255, 240, 180)));
        r.register(Block.light(22, "magic_light", "Magic Light", new Color(150, 110, 240),
                6, new Color(180, 140, 255)));
        r.register(Block.light(23, "crystal", "Crystal", new Color(110, 220, 230),
                5, new Color(150, 240, 250)));

        // --- expanded palette (ids 24+; never renumber) ------------------------
        // Stone family.
        r.register(Block.terrain(24, "deepslate", "Deepslate", new Color(70, 70, 80)));
        r.register(Block.terrain(25, "granite", "Granite", new Color(150, 105, 90)));
        r.register(Block.terrain(26, "marble", "Marble", new Color(215, 215, 220)));
        r.register(Block.terrain(27, "basalt", "Basalt", new Color(55, 52, 60)));
        r.register(Block.terrain(28, "obsidian", "Obsidian", new Color(35, 25, 50)));
        r.register(Block.terrain(29, "sandstone", "Sandstone", new Color(205, 180, 120)));
        r.register(Block.terrain(30, "clay", "Clay", new Color(160, 150, 160)));
        r.register(Block.terrain(31, "gravel", "Gravel", new Color(135, 130, 125)));
        r.register(Block.terrain(32, "mud", "Mud", new Color(95, 75, 55)));
        r.register(Block.terrain(33, "limestone", "Limestone", new Color(190, 185, 165)));
        r.register(Block.terrain(34, "slate", "Slate", new Color(90, 95, 110)));
        r.register(Block.terrain(35, "packed_ice", "Packed Ice", new Color(140, 180, 225)));
        // Wood & plant matter.
        r.register(Block.terrain(36, "oak_log", "Oak Log", new Color(120, 90, 55)));
        r.register(Block.terrain(37, "birch_log", "Birch Log", new Color(210, 205, 190)));
        r.register(Block.terrain(38, "dark_log", "Dark Log", new Color(75, 55, 40)));
        r.register(Block.terrain(39, "planks", "Planks", new Color(175, 135, 80)));
        r.register(Block.terrain(40, "birch_planks", "Birch Planks", new Color(215, 195, 150)));
        r.register(Block.terrain(41, "dark_planks", "Dark Planks", new Color(100, 75, 55)));
        r.register(Block.terrain(42, "bamboo", "Bamboo", new Color(150, 185, 90)));
        r.register(Block.terrain(43, "thatch", "Thatch", new Color(200, 175, 100)));
        // Building.
        r.register(Block.terrain(44, "stone_bricks", "Stone Bricks", new Color(125, 125, 130)));
        r.register(Block.terrain(45, "mossy_bricks", "Mossy Bricks", new Color(105, 125, 100)));
        r.register(Block.terrain(46, "dark_bricks", "Dark Bricks", new Color(80, 75, 85)));
        r.register(Block.terrain(47, "tile_floor", "Tile Floor", new Color(170, 165, 175)));
        r.register(Block.terrain(48, "metal_plate", "Metal Plate", new Color(145, 150, 160)));
        r.register(Block.terrain(49, "pillar", "Pillar", new Color(200, 195, 185)));
        r.register(Block.terrain(50, "bookshelf", "Bookshelf", new Color(140, 100, 65)));
        r.register(Block.terrain(51, "crate", "Crate", new Color(165, 125, 75)));
        r.register(Block.terrain(52, "barrel", "Barrel", new Color(130, 95, 60)));
        // Ores (drop their material item).
        r.register(new Block(53, "copper_ore", "Copper Ore", new Color(170, 120, 95),
                true, 0, null, "copper"));
        r.register(new Block(54, "silver_ore", "Silver Ore", new Color(190, 195, 205),
                true, 0, null, "silver_ingot"));
        r.register(new Block(55, "diamond_ore", "Diamond Ore", new Color(130, 200, 210),
                true, 0, null, "diamond"));
        r.register(new Block(56, "ruby_ore", "Ruby Ore", new Color(180, 80, 95),
                true, 0, null, "ruby"));
        r.register(new Block(57, "emerald_ore", "Emerald Ore", new Color(90, 180, 120),
                true, 0, null, "emerald"));
        r.register(new Block(58, "amethyst_ore", "Amethyst Ore", new Color(150, 110, 190),
                true, 0, null, "amethyst"));
        // Nature (passable).
        r.register(Block.passable(59, "flower_red", "Red Flower", new Color(220, 80, 80)));
        r.register(Block.passable(60, "flower_yellow", "Yellow Flower", new Color(235, 210, 90)));
        r.register(Block.passable(61, "flower_blue", "Blue Flower", new Color(110, 140, 235)));
        r.register(Block.passable(62, "tall_grass", "Tall Grass", new Color(105, 170, 90)));
        r.register(Block.passable(63, "fern", "Fern", new Color(80, 140, 75)));
        r.register(Block.passable(64, "dead_bush", "Dead Bush", new Color(160, 130, 85)));
        r.register(Block.passable(65, "mushroom", "Mushroom", new Color(190, 130, 100)));
        r.register(Block.passable(66, "roots", "Roots", new Color(110, 85, 60)));
        r.register(Block.passable(67, "web", "Cobweb", new Color(225, 225, 230)));
        // Hazards.
        r.register(new Block(68, "cactus", "Cactus", new Color(95, 160, 85),
                true, 0, null, "cactus", false, 6));
        r.register(Block.hazard(69, "thorns", "Thorns", new Color(120, 110, 70), 8));
        r.register(Block.hazard(70, "spikes", "Spikes", new Color(175, 180, 190), 30));
        // Lights.
        r.register(Block.solidLight(71, "glowstone", "Glowstone", new Color(240, 210, 120),
                7, new Color(255, 230, 160)));
        r.register(Block.light(72, "glow_mushroom", "Glow Mushroom", new Color(140, 230, 200),
                4, new Color(160, 255, 220)));
        r.register(Block.light(73, "amethyst_cluster", "Amethyst Cluster", new Color(180, 130, 235),
                5, new Color(200, 160, 255)));
        r.register(Block.solidLight(74, "ember_stone", "Ember Stone", new Color(120, 60, 45),
                4, new Color(255, 140, 80)));
        r.register(Block.light(75, "neon_block", "Neon Block", new Color(90, 240, 190),
                6, new Color(120, 255, 210)));
        // Liquids: sources are paintable; *_flow twins belong to LiquidSim.
        r.register(new Block(76, "water_flow", "Water (flowing)",
                new Color(80, 130, 210, 140), false, 0, null, null, true, 0));
        r.register(Block.liquidSource(77, "lava", "Lava",
                new Color(235, 110, 40, 230), 30, 5, new Color(255, 150, 70)));
        r.register(new Block(78, "lava_flow", "Lava (flowing)",
                new Color(240, 130, 50, 210), false, 4, new Color(255, 150, 70),
                null, true, 30));
        r.register(Block.liquidSource(79, "acid", "Acid",
                new Color(130, 210, 60, 190), 12, 2, new Color(160, 240, 90)));
        r.register(new Block(80, "acid_flow", "Acid (flowing)",
                new Color(140, 220, 70, 160), false, 2, new Color(160, 240, 90),
                null, true, 12));
        // Odds and ends.
        r.register(Block.passable(81, "cloud", "Cloud", new Color(240, 245, 250, 180)));
        r.register(Block.terrain(82, "snow_bricks", "Snow Bricks", new Color(225, 232, 240)));
        r.register(Block.terrain(83, "scaffold", "Scaffold", new Color(185, 150, 95)));
        // Crafting stations (see com.larsons.engine.crafting): stand next to
        // one and press E in play/test to open its combine UI.
        r.register(Block.terrain(84, "crafting_table", "Crafting Table",
                new Color(185, 140, 80)));
        r.register(Block.solidLight(85, "alchemy_station", "Alchemy Station",
                new Color(120, 90, 170), 3, new Color(190, 150, 255)));
        // Storage: like the barrel (52), the chest carries a second inventory
        // saved with the level (Block.container / Level.containers).
        r.register(Block.terrain(86, "chest", "Chest", new Color(170, 125, 65)));
        // Pathways & walls, primarily for top-down / isometric levels (and the
        // maze generator): paths are walkable floor markings, walls obstruct.
        r.register(Block.passable(87, "stone_path", "Stone Path", new Color(165, 160, 150)));
        r.register(Block.passable(88, "gravel_path", "Gravel Path", new Color(145, 138, 128)));
        r.register(Block.passable(89, "wood_path", "Wood Path", new Color(180, 140, 90)));
        r.register(Block.terrain(90, "stone_wall", "Stone Wall", new Color(105, 105, 115)));
        r.register(Block.terrain(91, "brick_wall", "Brick Wall", new Color(150, 75, 65)));
        r.register(Block.terrain(92, "hedge_wall", "Hedge Wall", new Color(70, 125, 60)));

        tuneDurability(r);
        // Loose granular blocks obey gravity (creators opt custom blocks in
        // with the "+ New Block" form's falling toggle).
        r.setFalling("sand", true);
        r.setFalling("gravel", true);
        return r;
    }

    /**
     * Block durability: how long each family takes to mine bare-handed, and
     * which tool class ({@code pickaxe}/{@code axe}/{@code shovel}) speeds it
     * up. Untuned solid blocks default to 1s/no tool; passables break
     * instantly.
     */
    private static void tuneDurability(BlockRegistry r) {
        // Soft earth: shovels.
        for (String k : new String[]{"dirt", "grass", "sand", "gravel", "mud",
                "clay", "snow", "snow_bricks"}) {
            r.tune(k, 0.6, "shovel");
        }
        // Rock and masonry: pickaxes.
        for (String k : new String[]{"stone", "cobblestone", "brick", "sandstone",
                "limestone", "slate", "stone_bricks", "mossy_bricks", "dark_bricks",
                "tile_floor", "pillar", "marble", "stone_wall", "brick_wall"}) {
            r.tune(k, 1.6, "pickaxe");
        }
        for (String k : new String[]{"deepslate", "granite", "basalt", "metal_plate"}) {
            r.tune(k, 2.6, "pickaxe");
        }
        r.tune("obsidian", 8.0, "pickaxe");
        // Ores sit in rock and take a little longer than their host stone.
        for (String k : new String[]{"coal_ore", "iron_ore", "gold_ore", "copper_ore",
                "silver_ore", "diamond_ore", "ruby_ore", "emerald_ore", "amethyst_ore"}) {
            r.tune(k, 3.0, "pickaxe");
        }
        // Woody things: axes.
        for (String k : new String[]{"wood", "oak_log", "birch_log", "dark_log",
                "planks", "birch_planks", "dark_planks", "bamboo", "thatch",
                "bookshelf", "crate", "barrel", "chest", "scaffold", "platform",
                "crafting_table", "hedge_wall"}) {
            r.tune(k, 1.2, "axe");
        }
        // Fragile blocks crumble fast with anything.
        r.tune("glass", 0.15, null);
        r.tune("ice", 0.4, "pickaxe");
        r.tune("packed_ice", 0.8, "pickaxe");
        r.tune("glowstone", 0.5, "pickaxe");
        r.tune("ember_stone", 1.8, "pickaxe");
        r.tune("neon_block", 0.5, "pickaxe");
        r.tune("cactus", 0.5, "axe");
        r.tune("alchemy_station", 1.6, "pickaxe");
    }

    /** The flow twin for a liquid source (or {@code null} for non-liquids). */
    public Block flowFor(Block source) {
        if (source == null || !source.liquid() || source.isFlow()) return null;
        return get(source.key() + "_flow");
    }

    /** The source block a flow twin belongs to (identity for sources). */
    public Block sourceFor(Block liquid) {
        if (liquid == null || !liquid.liquid()) return null;
        if (!liquid.isFlow()) return liquid;
        return get(liquid.key().substring(0, liquid.key().length() - "_flow".length()));
    }
}
