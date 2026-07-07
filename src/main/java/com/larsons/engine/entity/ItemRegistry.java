package com.larsons.engine.entity;

import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog of items a game knows about. Ported from the Side-Scroller
 * engine's {@code ItemRegistry}; the original's 198 per-item classes become
 * data rows here, and one block-item is derived automatically for every block
 * in a {@link BlockRegistry} (so painting/mining stays consistent with the
 * block set without duplicating it).
 */
public final class ItemRegistry {

    private final Map<String, ItemDef> byKey = new LinkedHashMap<>();

    private static final ItemRegistry STANDARD = createStandard(BlockRegistry.standard());

    public static ItemRegistry standard() {
        return STANDARD;
    }

    public void register(ItemDef def) {
        if (byKey.containsKey(def.key())) {
            throw new IllegalArgumentException("Duplicate item key " + def.key());
        }
        byKey.put(def.key(), def);
    }

    /** The item with this key, or {@code null}. */
    public ItemDef get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All items in registration order. */
    public List<ItemDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    /** All items sorted by rarity (mythic first) then name — the palette's "Rarity" sort. */
    public List<ItemDef> allByRarity() {
        List<ItemDef> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparing((ItemDef d) -> d.rarity().ordinal()).reversed()
                .thenComparing(ItemDef::name));
        return list;
    }

    private static ItemRegistry createStandard(BlockRegistry blocks) {
        ItemRegistry r = new ItemRegistry();
        // One placeable item per block, keyed like the block itself.
        for (Block b : blocks.all()) {
            r.register(ItemDef.block(b.key(), b.displayName(), b.color(), b.key()));
        }
        // Ores mined from ore blocks (block drops reference these).
        r.register(ItemDef.material("coal", "Coal", ItemDef.Rarity.COMMON, new Color(45, 45, 50)));
        // Materials (a representative slice of the original catalog).
        r.register(ItemDef.material("iron_ingot", "Iron Ingot", ItemDef.Rarity.UNCOMMON, new Color(200, 200, 205)));
        r.register(ItemDef.material("gold_ingot", "Gold Ingot", ItemDef.Rarity.RARE, new Color(235, 200, 80)));
        r.register(ItemDef.material("diamond", "Diamond", ItemDef.Rarity.EPIC, new Color(120, 230, 235)));
        r.register(ItemDef.material("rope", "Rope", ItemDef.Rarity.COMMON, new Color(190, 160, 110)));
        r.register(ItemDef.material("leather", "Leather", ItemDef.Rarity.COMMON, new Color(150, 100, 60)));
        r.register(ItemDef.material("scroll", "Scroll", ItemDef.Rarity.RARE, new Color(230, 220, 190)));
        r.register(ItemDef.material("dragon_egg", "Dragon Egg", ItemDef.Rarity.MYTHIC, new Color(120, 40, 130)));
        // Weapons (melee ladder + a couple of legendaries).
        r.register(ItemDef.weapon("wooden_sword", "Wooden Sword", ItemDef.Rarity.COMMON, new Color(160, 120, 70), 4));
        r.register(ItemDef.weapon("iron_sword", "Iron Sword", ItemDef.Rarity.UNCOMMON, new Color(200, 200, 210), 8));
        r.register(ItemDef.weapon("steel_sword", "Steel Sword", ItemDef.Rarity.RARE, new Color(170, 180, 200), 12));
        r.register(ItemDef.weapon("crystal_sword", "Crystal Sword", ItemDef.Rarity.EPIC, new Color(130, 220, 230), 16));
        r.register(ItemDef.weapon("battle_axe", "Battle Axe", ItemDef.Rarity.RARE, new Color(140, 120, 110), 14));
        r.register(ItemDef.weapon("legendary_sword", "Legendary Sword", ItemDef.Rarity.LEGENDARY, new Color(255, 180, 60), 22));
        r.register(ItemDef.weapon("frostmourne", "Frostmourne", ItemDef.Rarity.MYTHIC, new Color(140, 220, 255), 28));
        // Ranged.
        r.register(new ItemDef("wooden_bow", "Wooden Bow", ItemDef.Category.RANGED_WEAPON,
                ItemDef.Rarity.COMMON, new Color(150, 110, 60), 1, 6, 0, null));
        r.register(new ItemDef("longbow", "Longbow", ItemDef.Category.RANGED_WEAPON,
                ItemDef.Rarity.RARE, new Color(120, 90, 50), 1, 12, 0, null));
        r.register(new ItemDef("fire_staff", "Fire Staff", ItemDef.Category.RANGED_WEAPON,
                ItemDef.Rarity.EPIC, new Color(230, 110, 50), 1, 15, 0, null));
        r.register(new ItemDef("arrow", "Arrow", ItemDef.Category.THROWABLE,
                ItemDef.Rarity.COMMON, new Color(180, 180, 170), 64, 0, 0, null));
        // Food.
        r.register(ItemDef.food("apple", "Apple", new Color(210, 60, 50), 10));
        r.register(ItemDef.food("bread", "Bread", new Color(205, 160, 90), 15));
        r.register(ItemDef.food("cheese", "Cheese", new Color(240, 205, 90), 12));
        r.register(ItemDef.food("cooked_meat", "Cooked Meat", new Color(160, 90, 60), 25));
        r.register(new ItemDef("golden_apple", "Golden Apple", ItemDef.Category.FOOD,
                ItemDef.Rarity.LEGENDARY, new Color(240, 210, 80), 4, 0, 100, null));
        // Potions & keys.
        r.register(new ItemDef("health_potion", "Health Potion", ItemDef.Category.POTION,
                ItemDef.Rarity.UNCOMMON, new Color(220, 70, 90), 8, 0, 40, null));
        r.register(new ItemDef("gold_key", "Gold Key", ItemDef.Category.KEY,
                ItemDef.Rarity.RARE, new Color(230, 195, 70), 8, 0, 0, null));
        return r;
    }
}
