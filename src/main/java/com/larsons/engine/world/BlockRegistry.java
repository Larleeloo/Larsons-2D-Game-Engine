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
        // water, sand) so levels written against it stay recognizable.
        r.register(Block.terrain(1, "dirt", "Dirt", new Color(120, 90, 60)));
        r.register(Block.terrain(2, "grass", "Grass", new Color(90, 160, 80)));
        r.register(Block.terrain(3, "stone", "Stone", new Color(110, 110, 120)));
        r.register(Block.passable(4, "water", "Water", new Color(70, 120, 200)));
        r.register(Block.terrain(5, "sand", "Sand", new Color(220, 200, 120)));
        // The rest of the side-scroller's block set.
        r.register(Block.terrain(6, "cobblestone", "Cobblestone", new Color(130, 128, 122)));
        r.register(Block.terrain(7, "wood", "Wood", new Color(150, 110, 60)));
        r.register(Block.passable(8, "leaves", "Leaves", new Color(60, 130, 55)));
        r.register(Block.terrain(9, "brick", "Brick", new Color(160, 70, 60)));
        r.register(Block.passable(10, "glass", "Glass", new Color(190, 220, 235)));
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
        return r;
    }
}
