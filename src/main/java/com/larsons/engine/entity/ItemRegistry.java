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
        // One placeable item per block, keyed like the block itself. Flowing
        // liquid twins are simulation artifacts — not placeable, not items.
        for (Block b : blocks.all()) {
            if (b.isFlow()) continue;
            r.register(ItemDef.block(b.key(), b.displayName(), b.color(), b.key()));
        }
        // Ores mined from ore blocks (block drops reference these).
        r.register(ItemDef.material("coal", "Coal", ItemDef.Rarity.COMMON, new Color(45, 45, 50)));
        r.register(ItemDef.material("copper", "Copper", ItemDef.Rarity.COMMON, new Color(190, 120, 85)));
        r.register(ItemDef.material("silver_ingot", "Silver Ingot", ItemDef.Rarity.UNCOMMON, new Color(210, 215, 225)));
        r.register(ItemDef.material("ruby", "Ruby", ItemDef.Rarity.EPIC, new Color(220, 70, 90)));
        r.register(ItemDef.material("emerald", "Emerald", ItemDef.Rarity.RARE, new Color(80, 200, 120)));
        r.register(ItemDef.material("amethyst", "Amethyst", ItemDef.Rarity.RARE, new Color(180, 130, 235)));
        // Materials (a representative slice of the original catalog).
        r.register(ItemDef.material("iron_ingot", "Iron Ingot", ItemDef.Rarity.UNCOMMON, new Color(200, 200, 205)));
        r.register(ItemDef.material("gold_ingot", "Gold Ingot", ItemDef.Rarity.RARE, new Color(235, 200, 80)));
        r.register(ItemDef.material("diamond", "Diamond", ItemDef.Rarity.EPIC, new Color(120, 230, 235)));
        r.register(ItemDef.material("rope", "Rope", ItemDef.Rarity.COMMON, new Color(190, 160, 110)));
        r.register(ItemDef.material("leather", "Leather", ItemDef.Rarity.COMMON, new Color(150, 100, 60)));
        r.register(ItemDef.material("scroll", "Scroll", ItemDef.Rarity.RARE, new Color(230, 220, 190)));
        r.register(ItemDef.material("dragon_egg", "Dragon Egg", ItemDef.Rarity.MYTHIC, new Color(120, 40, 130)));
        r.register(ItemDef.material("stick", "Stick", ItemDef.Rarity.COMMON, new Color(170, 135, 85)));
        // Tools: matching blocks (Block.tool) mine toolPower× faster.
        r.register(ItemDef.tool("wooden_pickaxe", "Wooden Pickaxe", ItemDef.Rarity.COMMON,
                new Color(165, 125, 75), "pickaxe", 2, 3));
        r.register(ItemDef.tool("stone_pickaxe", "Stone Pickaxe", ItemDef.Rarity.COMMON,
                new Color(135, 135, 140), "pickaxe", 3, 4));
        r.register(ItemDef.tool("iron_pickaxe", "Iron Pickaxe", ItemDef.Rarity.UNCOMMON,
                new Color(200, 200, 210), "pickaxe", 4.5, 5));
        r.register(ItemDef.tool("diamond_pickaxe", "Diamond Pickaxe", ItemDef.Rarity.EPIC,
                new Color(120, 230, 235), "pickaxe", 7, 6));
        r.register(ItemDef.tool("wooden_axe", "Wooden Axe", ItemDef.Rarity.COMMON,
                new Color(160, 120, 70), "axe", 2, 5));
        r.register(ItemDef.tool("iron_axe", "Iron Axe", ItemDef.Rarity.UNCOMMON,
                new Color(195, 195, 205), "axe", 4.5, 8));
        r.register(ItemDef.tool("diamond_axe", "Diamond Axe", ItemDef.Rarity.EPIC,
                new Color(115, 225, 230), "axe", 7, 11));
        r.register(ItemDef.tool("wooden_shovel", "Wooden Shovel", ItemDef.Rarity.COMMON,
                new Color(170, 130, 80), "shovel", 2, 2));
        r.register(ItemDef.tool("iron_shovel", "Iron Shovel", ItemDef.Rarity.UNCOMMON,
                new Color(205, 205, 215), "shovel", 4.5, 4));
        r.register(ItemDef.tool("diamond_shovel", "Diamond Shovel", ItemDef.Rarity.EPIC,
                new Color(125, 230, 240), "shovel", 7, 5));
        // Weapons (melee ladder + a couple of legendaries).
        r.register(ItemDef.weapon("wooden_sword", "Wooden Sword", ItemDef.Rarity.COMMON, new Color(160, 120, 70), 4));
        r.register(ItemDef.weapon("iron_sword", "Iron Sword", ItemDef.Rarity.UNCOMMON, new Color(200, 200, 210), 8));
        r.register(ItemDef.weapon("steel_sword", "Steel Sword", ItemDef.Rarity.RARE, new Color(170, 180, 200), 12));
        r.register(ItemDef.weapon("crystal_sword", "Crystal Sword", ItemDef.Rarity.EPIC, new Color(130, 220, 230), 16));
        r.register(ItemDef.weapon("battle_axe", "Battle Axe", ItemDef.Rarity.RARE, new Color(140, 120, 110), 14));
        r.register(ItemDef.weapon("legendary_sword", "Legendary Sword", ItemDef.Rarity.LEGENDARY, new Color(255, 180, 60), 22));
        r.register(ItemDef.weapon("frostmourne", "Frostmourne", ItemDef.Rarity.MYTHIC, new Color(140, 220, 255), 28));
        // Ranged weapons fire ProjectileRegistry entries; bows consume arrows,
        // staves fire freely (their rarity is the cost).
        r.register(ItemDef.ranged("wooden_bow", "Wooden Bow",
                ItemDef.Rarity.COMMON, new Color(150, 110, 60), 6, "arrow", "arrow"));
        r.register(ItemDef.ranged("longbow", "Longbow",
                ItemDef.Rarity.RARE, new Color(120, 90, 50), 12, "arrow", "arrow"));
        r.register(ItemDef.ranged("arcane_staff", "Arcane Staff",
                ItemDef.Rarity.RARE, new Color(120, 100, 220), 9, "magic_bolt", null));
        r.register(ItemDef.ranged("fire_staff", "Fire Staff",
                ItemDef.Rarity.EPIC, new Color(230, 110, 50), 15, "fireball", null));
        // Throwables consume themselves; physical ones land as recoverable drops.
        r.register(ItemDef.throwable("arrow", "Arrow",
                ItemDef.Rarity.COMMON, new Color(180, 180, 170), 4, "arrow"));
        r.register(ItemDef.throwable("rock", "Rock",
                ItemDef.Rarity.COMMON, new Color(140, 135, 130), 5, "rock"));
        r.register(ItemDef.throwable("throwing_knife", "Throwing Knife",
                ItemDef.Rarity.UNCOMMON, new Color(190, 195, 205), 8, "knife"));
        // Food.
        r.register(ItemDef.food("apple", "Apple", new Color(210, 60, 50), 10));
        r.register(ItemDef.food("bread", "Bread", new Color(205, 160, 90), 15));
        r.register(ItemDef.food("cheese", "Cheese", new Color(240, 205, 90), 12));
        r.register(ItemDef.food("cooked_meat", "Cooked Meat", new Color(160, 90, 60), 25));
        r.register(new ItemDef("golden_apple", "Golden Apple", ItemDef.Category.FOOD,
                ItemDef.Rarity.LEGENDARY, new Color(240, 210, 80), 4, 0, 100, null, null, null));
        // Potions & keys. The mana potion's restore amount is resolved by key
        // (see the scenes' consume handling); heal stays 0 so it never doubles
        // as food.
        r.register(new ItemDef("health_potion", "Health Potion", ItemDef.Category.POTION,
                ItemDef.Rarity.UNCOMMON, new Color(220, 70, 90), 8, 0, 40, null, null, null));
        r.register(new ItemDef("mana_potion", "Mana Potion", ItemDef.Category.POTION,
                ItemDef.Rarity.UNCOMMON, new Color(80, 110, 235), 8, 0, 0, null, null, null));
        r.register(new ItemDef("gold_key", "Gold Key", ItemDef.Category.KEY,
                ItemDef.Rarity.RARE, new Color(230, 195, 70), 8, 0, 0, null, null, null));
        // Movement accessories: the double jump is always active; carrying
        // these unlocks triple / quadruple / infinite jumping
        // (Inventory.airJumpBonus feeds PlayerState.bonusAirJumps).
        r.register(new ItemDef("feather_charm", "Feather Charm", ItemDef.Category.ACCESSORY,
                ItemDef.Rarity.RARE, new Color(230, 230, 250), 1, 0, 0, null, null, null));
        r.register(new ItemDef("sky_totem", "Sky Totem", ItemDef.Category.ACCESSORY,
                ItemDef.Rarity.EPIC, new Color(140, 190, 245), 1, 0, 0, null, null, null));
        r.register(new ItemDef("wings_of_icarus", "Wings of Icarus", ItemDef.Category.ACCESSORY,
                ItemDef.Rarity.MYTHIC, new Color(255, 240, 180), 1, 0, 0, null, null, null));
        return r;
    }

    /** Remove an item (deleting user-created custom content). */
    public void unregister(String key) {
        byKey.remove(key);
    }
}
