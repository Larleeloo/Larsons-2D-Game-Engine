package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * One auto-battler unit species: a data row, exactly like the engine's other
 * registries ({@code MobRegistry}, {@code ItemRegistry}). Costs run 1-5 gold
 * (rarer units are stronger); cost 0 marks a PvE creep, which has no traits
 * and never appears in shops.
 *
 * <p>The {@code clazz} trait doubles as the unit's ability: every Mage casts a
 * fireball, every Healer heals the weakest ally, and so on — {@link BattleSim}
 * switches on it when mana fills. {@code spell} is the base magnitude of that
 * ability.
 */
public final class UnitDef {

    public final String key;
    public final String name;
    /** Shop cost in gold, 1-5; 0 = PvE creep. */
    public final int cost;
    /** Origin synergy, or {@code null} for creeps. */
    public final Trait origin;
    /** Class synergy (also selects the ability), or {@code null} for creeps. */
    public final Trait clazz;

    public final double hp;
    /** Attack damage per hit. */
    public final double ad;
    /** Attacks per second. */
    public final double attackSpeed;
    /** Attack range in board cells (1 = melee). */
    public final double range;
    public final double armor;
    /** Mana needed to cast; 0 = never casts (creeps). */
    public final double manaMax;
    /** Base ability magnitude (damage / heal / buff size, per the class). */
    public final double spell;

    public final Color body;
    public final Color accent;

    public UnitDef(String key, String name, int cost, Trait origin, Trait clazz,
                   double hp, double ad, double attackSpeed, double range,
                   double armor, double manaMax, double spell,
                   Color body, Color accent) {
        this.key = key;
        this.name = name;
        this.cost = cost;
        this.origin = origin;
        this.clazz = clazz;
        this.hp = hp;
        this.ad = ad;
        this.attackSpeed = attackSpeed;
        this.range = range;
        this.armor = armor;
        this.manaMax = manaMax;
        this.spell = spell;
        this.body = body;
        this.accent = accent;
    }

    public boolean isCreep() {
        return cost == 0;
    }

    /** Melee units want the front row; ranged the back. Used by bots + creeps. */
    public boolean melee() {
        return range <= 1.5;
    }

    /**
     * The stat multiplier for a star level: 2-star units are 1.8x, 3-star
     * 3.24x — the classic "each star is worth almost two of the previous".
     */
    public static double starMultiplier(int star) {
        return switch (Math.max(1, Math.min(3, star))) {
            case 2 -> 1.8;
            case 3 -> 3.24;
            default -> 1.0;
        };
    }

    /** Gold value of one unit at a star level (3 copies combine into a star). */
    public int value(int star) {
        int copies = 1;
        for (int i = 1; i < star; i++) copies *= 3;
        return cost * copies;
    }
}
