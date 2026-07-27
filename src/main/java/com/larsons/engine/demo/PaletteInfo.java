package com.larsons.engine.demo;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.SurfaceDecor;

/**
 * Human-readable descriptions for the creative palette: one short sentence
 * per paintable object explaining what it does, composed from its definition
 * data — so user-created custom blocks/mobs/items describe themselves the
 * same way the built-ins do. Shown as the palette's hover tooltips
 * ({@link CreativeScene}).
 *
 * <p>These are tooltips, not documentation, so the phrasing stays clipped:
 * a description is a lead clause naming what the object <em>is</em> followed
 * by short clauses for whatever else is true of it. Objects with several
 * traits (a falling, container, mineable block) would otherwise stack up into
 * a paragraph beside a 52-pixel icon.
 */
public final class PaletteInfo {

    private PaletteInfo() {}

    /** What painting this block gives the level. */
    public static String describe(Block b) {
        StringBuilder sb = new StringBuilder();
        if (b.liquid()) {
            sb.append("Liquid — flows and spreads; swimmable.");
        } else if (b.solid()) {
            sb.append("Solid — collides with players and mobs.");
        } else {
            sb.append("Passable — walk straight through it.");
        }
        if (b.emitsLight()) {
            sb.append(" Casts light ").append(trim(b.lightRadius())).append(" tiles.");
        }
        if (b.damage() > 0) {
            sb.append(" Hazard: ").append(trim(b.damage())).append(" health/sec on contact.");
        }
        if (b.falling()) {
            sb.append(" Falls when unsupported.");
        }
        if (b.container()) {
            sb.append(" Stores items — press E beside it in play.");
        }
        if (b.solid() && !b.liquid()) {
            if (b.hardness() <= 0) {
                sb.append(" Breaks instantly.");
            } else {
                sb.append(" Mines in ~").append(trim(b.hardness())).append("s by hand");
                if (b.tool() != null) sb.append(" (").append(article(b.tool())).append(" is faster)");
                sb.append('.');
            }
        }
        if (b.drops() != null && !b.drops().isBlank()) {
            sb.append(" Drops ").append(itemName(b.drops())).append('.');
        }
        return sb.toString();
    }

    /** What using/carrying this item does. */
    public static String describe(ItemDef d) {
        String special = specialItemText(d.key());
        if (special != null) return special;
        StringBuilder sb = new StringBuilder();
        switch (d.category()) {
            case WEAPON -> sb.append("Melee weapon — swings deal ")
                    .append(trim(d.damage())).append(" damage.");
            case RANGED_WEAPON -> {
                sb.append("Ranged weapon — fires ").append(pretty(d.projectile()))
                        .append(" for ").append(trim(d.damage())).append(" damage");
                sb.append(d.ammo() != null
                        ? ", consuming one " + itemName(d.ammo()) + " per shot."
                        : ", costing mana per cast.");
            }
            case TOOL -> {
                sb.append(cap(d.toolClass())).append(" — mines matching blocks ")
                        .append(trim(d.toolPower())).append("× faster");
                if (d.damage() > 0) {
                    sb.append(", swings for ").append(trim(d.damage())).append(" damage");
                }
                sb.append('.');
                if (d.maxDurability() > 0) {
                    sb.append(" Wears out after ").append(d.maxDurability()).append(" blocks.");
                }
            }
            case ARMOR -> sb.append("Armor — protective gear to wear.");
            case CLOTHING -> sb.append("Clothing — a cosmetic outfit piece.");
            case BLOCK -> sb.append("Places ")
                    .append(d.blockKey() != null ? pretty(d.blockKey()) : "a block")
                    .append(" when used from the hotbar.");
            case FOOD -> sb.append("Food — press F to eat and restore ")
                    .append(trim(d.heal())).append(" health.");
            case POTION -> {
                sb.append("Potion — drink");
                if (d.heal() > 0) sb.append(" to restore ").append(trim(d.heal())).append(" health");
                sb.append('.');
            }
            case MATERIAL -> sb.append("Material — an ingredient for crafting recipes.");
            case KEY -> sb.append("Key item — opens the way forward.");
            case ACCESSORY -> sb.append("Accessory — a trinket carried in the inventory.");
            case THROWABLE -> sb.append("Throwable — throws itself as ")
                    .append(pretty(d.projectile())).append(" for ")
                    .append(trim(d.damage())).append(" damage; one is used per throw.");
            default -> sb.append("A curiosity with no special use — yet.");
        }
        if (d.maxStack() > 1) sb.append(" Stacks to ").append(d.maxStack()).append('.');
        return sb.toString();
    }

    /**
     * The engine's special-cased items (movement relics, salvo weapons,
     * [F]-activated relics) do more than their category says — describe the
     * real behaviour instead.
     */
    private static String specialItemText(String key) {
        return switch (key == null ? "" : key) {
            case "feather_charm" -> "Relic — carried, it grants a third mid-air jump.";
            case "sky_totem" -> "Relic — carried, it grants two extra mid-air jumps.";
            case "wings_of_icarus" -> "Relic — carried, jump again in mid-air forever.";
            case "hermes_boots" -> "Relic — carried, every step moves 35% faster.";
            case "gravity_amulet" -> "Relic — carried, falls turn slow and gentle.";
            case "aether_wings" -> "Relic — carried, hold jump to fly upward.";
            case "magnet_charm" -> "Relic — carried, dropped items vacuum in from farther away.";
            case "power_gauntlet" -> "Relic — carried, melee swings hit 6 harder.";
            case "nova_crystal" -> "Active relic — hold it and press F to detonate an"
                    + " arcane blast around you (costs 30 mana).";
            case "tremor_totem" -> "Active relic — hold it and press F to shatter the"
                    + " surrounding terrain into drops (costs 25 mana).";
            case "meteor_staff" -> "Ranged weapon — calls three meteors down from the"
                    + " sky onto the aim point, costing mana per cast.";
            case "scatter_bow" -> "Ranged weapon — fans three arrows per shot,"
                    + " consuming one arrow.";
            default -> null;
        };
    }

    /** How this mob behaves once painted into the level. */
    public static String describe(MobDef d) {
        StringBuilder sb = new StringBuilder();
        switch (d.temperament()) {
            case HOSTILE -> sb.append("Hostile — chases players on sight.");
            case NEUTRAL -> sb.append("Neutral — retaliates when hurt.");
            case PASSIVE -> sb.append("Passive — harmless; flees when hurt.");
        }
        sb.append(' ').append(trim(d.maxHealth())).append(" HP");
        if (d.damage() > 0) sb.append(", ").append(trim(d.damage())).append(" damage");
        if (d.flying()) sb.append(", flies");
        if (d.ranged()) sb.append(", fires ").append(pretty(d.projectile())).append(" at range");
        sb.append('.');
        String trick = abilityText(d);
        if (trick != null) sb.append(' ').append(trick);
        return sb.toString();
    }

    private static String abilityText(MobDef d) {
        return switch (d.ability()) {
            case LEAP -> "Pounces at its target with long leaps.";
            case CHARGE -> "Winds up, then charges in a straight line at triple speed.";
            case TELEPORT -> "Blinks right next to its target instead of walking.";
            case SUMMON -> "Periodically summons " + pretty(d.abilityArg()) + " minions.";
            case SPLIT -> "Splits into two " + pretty(d.abilityArg()) + "s when killed.";
            case DEATH_BURST -> "Explodes in an area blast when killed.";
            case REGEN -> "Slowly regenerates health while alive.";
            case LIFESTEAL -> "Heals itself for half the damage its hits deal.";
            case SHIELD -> "Cycles a briefly invulnerable guard stance.";
            default -> null;
        };
    }

    /** What this decoration is (purely visual scenery). */
    public static String describe(Decor d) {
        return "Scenery ~" + trim(d.sizeTiles()) + (d.sizeTiles() == 1 ? " tile" : " tiles")
                + " tall — visual only; paints to the background or foreground layer.";
    }

    /** What this block-surface detail is and where it can attach. */
    public static String describe(SurfaceDecor d) {
        StringBuilder faces = new StringBuilder();
        for (SurfaceDecor.Face f : SurfaceDecor.Face.values()) {
            if (!d.allows(f)) continue;
            if (faces.length() > 0) faces.append('/');
            faces.append(f.name().toLowerCase());
        }
        return "Surface detail — attaches to a block's " + faces
                + " face and goes with it; visual only.";
    }

    /** "iron_sword" → the registered display name, or a prettified key. */
    private static String itemName(String key) {
        ItemDef def = ItemRegistry.standard().get(key);
        return def != null ? def.name() : pretty(key);
    }

    /** "fire_ball" → "fire ball" (a readable fallback for raw keys). */
    private static String pretty(String key) {
        return key == null ? "nothing" : key.replace('_', ' ');
    }

    /** "axe" → "an axe", "pickaxe" → "a pickaxe". */
    private static String article(String noun) {
        if (noun == null || noun.isEmpty()) return "a tool";
        return ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun;
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "Tool";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 2.0 → "2", 2.5 → "2.5" — numbers without trailing noise. */
    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
