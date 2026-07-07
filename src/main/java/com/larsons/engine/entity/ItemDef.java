package com.larsons.engine.entity;

import java.awt.Color;

/**
 * One item definition. Ported from the Side-Scroller engine's {@code Item}
 * class + 198 per-item subclasses, collapsed into a data row registered with
 * {@link ItemRegistry}. Categories and rarities are the original enums; the
 * rarity colour scheme (white → cyan) is kept so drops read the same.
 *
 * @param key        stable string key ("iron_sword")
 * @param name       display name
 * @param category   primary use
 * @param rarity     tier; tints the name + dropped-item beam
 * @param color      icon colour (procedural icons; no image assets)
 * @param maxStack   stack limit in inventories (1 = unstackable)
 * @param damage     weapon damage added to melee swings, or dealt by the
 *                   fired projectile (0 for non-weapons)
 * @param heal       health restored when consumed (0 for non-food)
 * @param blockKey   for BLOCK items: the {@code Block} this item places
 * @param projectile the {@link ProjectileDef} key this item fires when used
 *                   (ranged weapons) or turns into when thrown (throwables);
 *                   {@code null} for everything else
 * @param ammo       item key consumed per shot ({@code null} = free to fire;
 *                   throwables consume themselves)
 */
public record ItemDef(String key, String name, Category category, Rarity rarity,
                      Color color, int maxStack, double damage, double heal,
                      String blockKey, String projectile, String ammo) {

    /** Ported {@code ItemCategory}. */
    public enum Category {
        WEAPON, RANGED_WEAPON, TOOL, ARMOR, CLOTHING, BLOCK, FOOD, POTION,
        MATERIAL, KEY, ACCESSORY, THROWABLE, OTHER
    }

    /** Ported {@code ItemRarity}, colours intact. */
    public enum Rarity {
        COMMON(Color.WHITE, "Common"),
        UNCOMMON(new Color(30, 255, 30), "Uncommon"),
        RARE(new Color(30, 100, 255), "Rare"),
        EPIC(new Color(180, 30, 255), "Epic"),
        LEGENDARY(new Color(255, 165, 0), "Legendary"),
        MYTHIC(new Color(0, 255, 255), "Mythic");

        public final Color color;
        public final String displayName;

        Rarity(Color color, String displayName) {
            this.color = color;
            this.displayName = displayName;
        }
    }

    public static ItemDef material(String key, String name, Rarity rarity, Color color) {
        return new ItemDef(key, name, Category.MATERIAL, rarity, color, 64, 0, 0, null, null, null);
    }

    public static ItemDef weapon(String key, String name, Rarity rarity, Color color, double damage) {
        return new ItemDef(key, name, Category.WEAPON, rarity, color, 1, damage, 0, null, null, null);
    }

    public static ItemDef food(String key, String name, Color color, double heal) {
        return new ItemDef(key, name, Category.FOOD, Rarity.COMMON, color, 16, 0, heal, null, null, null);
    }

    public static ItemDef block(String key, String name, Color color, String blockKey) {
        return new ItemDef(key, name, Category.BLOCK, Rarity.COMMON, color, 64, 0, 0, blockKey, null, null);
    }

    /** A ranged weapon firing {@code projectile}, consuming {@code ammo} per shot. */
    public static ItemDef ranged(String key, String name, Rarity rarity, Color color,
                                 double damage, String projectile, String ammo) {
        return new ItemDef(key, name, Category.RANGED_WEAPON, rarity, color, 1,
                damage, 0, null, projectile, ammo);
    }

    /** A throwable stack: using it consumes one of itself and throws {@code projectile}. */
    public static ItemDef throwable(String key, String name, Rarity rarity, Color color,
                                    double damage, String projectile) {
        return new ItemDef(key, name, Category.THROWABLE, rarity, color, 64,
                damage, 0, null, projectile, key);
    }
}
