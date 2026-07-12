package com.larsons.engine.deckbuilder;

/**
 * One board location in Council of Six — an agent slot with an icon
 * requirement, an optional entry cost, and flat gains, all data on this row
 * (the worker-placement half of the game). Exactly one agent fits; an
 * occupied location blocks everyone else until the round ends (Bella's
 * passive being the one exception).
 */
public final class LocationDef {

    public final String key;
    public final String name;
    public final LocationIcon icon;

    /** Entry costs, paid when the agent is placed. */
    public final int costGold;
    public final int costLore;

    /** Gains when an agent lands here. */
    public final int gainGold;
    public final int gainLore;
    public final int gainMight;
    public final int gainDraw;
    public final int gainTroops;
    public final int gainVp;
    /** When true, the placer's market buys cost 1 less for the rest of the round. */
    public final boolean bazaarDiscount;

    public LocationDef(String key, String name, LocationIcon icon,
                       int costGold, int costLore,
                       int gainGold, int gainLore, int gainMight, int gainDraw,
                       int gainTroops, int gainVp, boolean bazaarDiscount) {
        this.key = key;
        this.name = name;
        this.icon = icon;
        this.costGold = costGold;
        this.costLore = costLore;
        this.gainGold = gainGold;
        this.gainLore = gainLore;
        this.gainMight = gainMight;
        this.gainDraw = gainDraw;
        this.gainTroops = gainTroops;
        this.gainVp = gainVp;
        this.bazaarDiscount = bazaarDiscount;
    }

    /** One-line effect summary for the board tile. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (costGold > 0 || costLore > 0) {
            sb.append("Pay ");
            if (costGold > 0) sb.append(costGold).append(" gold");
            if (costGold > 0 && costLore > 0) sb.append(" + ");
            if (costLore > 0) sb.append(costLore).append(" lore");
            sb.append(": ");
        }
        appendGain(sb, gainGold, "gold");
        appendGain(sb, gainLore, "lore");
        appendGain(sb, gainMight, "might");
        appendGain(sb, gainDraw, "draw");
        appendGain(sb, gainTroops, "troop");
        appendGain(sb, gainVp, "VP");
        if (bazaarDiscount) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') sb.append("  ");
            sb.append("buys -1 gold");
        }
        return sb.toString();
    }

    private static void appendGain(StringBuilder sb, int n, String what) {
        if (n <= 0) return;
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != ' ') sb.append("  ");
        sb.append('+').append(n).append(' ').append(what);
    }
}
