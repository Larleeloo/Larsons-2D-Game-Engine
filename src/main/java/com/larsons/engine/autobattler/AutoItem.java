package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * An auto-battler item: a <em>component</em> (dropped by PvE creep rounds), a
 * <em>combined</em> item made from two components — the classic TFT item
 * system — or an <em>elemental relic</em>, which changes a unit's elemental
 * affinity instead of its stats. Stat effects are pure bundles applied when
 * {@link BattleSim} builds a combat unit; relic effects rewire the unit's
 * {@link Element} lists there too, so the whole item table lives in data.
 */
public final class AutoItem {

    /** What slot in the item system this entry occupies. */
    public enum Kind { COMPONENT, COMBINED, RELIC }

    /** How an elemental relic changes its holder ({@code null} on stat items). */
    public enum ElementEffect {
        /** Adds {@link #element} to the unit's attack elements. */
        INFUSE,
        /** Replaces the unit's attack elements with just {@link #element}. */
        CONVERT,
        /** Grants resistance to every element (and clears weaknesses). */
        WARD
    }

    public final String key;
    public final String name;
    public final Kind kind;
    /** Component keys this item combines from, or {@code null} otherwise. */
    public final String partA, partB;

    public final double ad;        // flat attack damage
    public final double atkSpeed;  // fractional attack-speed bonus (0.25 = +25%)
    public final double spellPower; // ability damage/heal bonus in percent points
    public final double armor;
    public final double hp;

    /** The element a relic works with ({@code null} on stat items and WARD). */
    public final Element element;
    public final ElementEffect effect;

    public final Color color;

    public AutoItem(String key, String name, Kind kind, String partA, String partB,
                    double ad, double atkSpeed, double spellPower,
                    double armor, double hp, Element element,
                    ElementEffect effect, Color color) {
        this.key = key;
        this.name = name;
        this.kind = kind;
        this.partA = partA;
        this.partB = partB;
        this.ad = ad;
        this.atkSpeed = atkSpeed;
        this.spellPower = spellPower;
        this.armor = armor;
        this.hp = hp;
        this.element = element;
        this.effect = effect;
        this.color = color;
    }

    public boolean isComponent() {
        return kind == Kind.COMPONENT;
    }

    public boolean isRelic() {
        return kind == Kind.RELIC;
    }

    /** Short effect summary for tooltips ("+20 AD, +25% AS" / "Attacks gain Fire"). */
    public String statLine() {
        if (effect != null) {
            return switch (effect) {
                case INFUSE -> "Attacks gain " + element.label;
                case CONVERT -> "Attacks become pure " + element.label;
                case WARD -> "Resists every element";
            };
        }
        StringBuilder sb = new StringBuilder();
        if (ad > 0) append(sb, "+" + (int) ad + " AD");
        if (atkSpeed > 0) append(sb, "+" + (int) Math.round(atkSpeed * 100) + "% AS");
        if (spellPower > 0) append(sb, "+" + (int) spellPower + " SP");
        if (armor > 0) append(sb, "+" + (int) armor + " Armor");
        if (hp > 0) append(sb, "+" + (int) hp + " HP");
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }
}
