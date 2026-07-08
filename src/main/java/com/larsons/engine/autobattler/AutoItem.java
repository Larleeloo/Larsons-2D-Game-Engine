package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * An auto-battler item: either a <em>component</em> (dropped by PvE creep
 * rounds) or a <em>combined</em> item made from two components — the classic
 * TFT item system. Effects are pure stat bundles applied when {@link BattleSim}
 * builds a combat unit, so the whole item table lives in data.
 */
public final class AutoItem {

    public final String key;
    public final String name;
    /** Component keys this item combines from, or {@code null} for components. */
    public final String partA, partB;

    public final double ad;        // flat attack damage
    public final double atkSpeed;  // fractional attack-speed bonus (0.25 = +25%)
    public final double spellPower; // ability damage/heal bonus in percent points
    public final double armor;
    public final double hp;

    public final Color color;

    public AutoItem(String key, String name, String partA, String partB,
                    double ad, double atkSpeed, double spellPower,
                    double armor, double hp, Color color) {
        this.key = key;
        this.name = name;
        this.partA = partA;
        this.partB = partB;
        this.ad = ad;
        this.atkSpeed = atkSpeed;
        this.spellPower = spellPower;
        this.armor = armor;
        this.hp = hp;
        this.color = color;
    }

    public boolean isComponent() {
        return partA == null;
    }

    /** Short stat summary for tooltips ("+20 AD, +25% AS"). */
    public String statLine() {
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
