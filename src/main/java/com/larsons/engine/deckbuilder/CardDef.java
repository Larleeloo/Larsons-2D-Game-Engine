package com.larsons.engine.deckbuilder;

import java.util.Set;

/**
 * One card definition in Council of Six — a pure data row, like the engine's
 * other registries. Cards are deliberately simple (the anti-Magic rule): every
 * effect is a number on this row, there is no per-card scripting.
 *
 * <p>A card can be used one of two ways in a round:
 * <ul>
 *   <li><b>Played</b> on an agent turn: it sends an agent to any location
 *       matching one of its {@link #icons}, and its {@code play*} effects
 *       resolve on top of the location's.</li>
 *   <li><b>Revealed</b> (left in hand when you reveal): its {@code reveal*}
 *       values are added up — gold to spend in the market, might committed to
 *       the round's conflict.</li>
 * </ul>
 */
public final class CardDef {

    public final String key;
    public final String name;
    /** Market price in gold; 0 marks a starter-deck card (never in the market). */
    public final int cost;
    /** Locations this card can send an agent to (icon match). */
    public final Set<LocationIcon> icons;

    // Effects when played on an agent turn.
    public final int playGold;
    public final int playLore;
    public final int playMight;
    public final int playDraw;
    public final int playTroops;
    public final int playVp;

    // Effects when still in hand at reveal.
    public final int revealGold;
    public final int revealMight;

    public final String flavor;

    public CardDef(String key, String name, int cost, Set<LocationIcon> icons,
                   int playGold, int playLore, int playMight, int playDraw,
                   int playTroops, int playVp,
                   int revealGold, int revealMight, String flavor) {
        this.key = key;
        this.name = name;
        this.cost = cost;
        this.icons = icons;
        this.playGold = playGold;
        this.playLore = playLore;
        this.playMight = playMight;
        this.playDraw = playDraw;
        this.playTroops = playTroops;
        this.playVp = playVp;
        this.revealGold = revealGold;
        this.revealMight = revealMight;
        this.flavor = flavor;
    }

    /** One-line effect summary for tooltips and card faces. */
    public String playSummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, playGold, "gold");
        append(sb, playLore, "lore");
        append(sb, playMight, "might");
        append(sb, playDraw, "draw");
        append(sb, playTroops, "troop");
        append(sb, playVp, "VP");
        return sb.isEmpty() ? "—" : sb.toString();
    }

    /** One-line reveal summary for tooltips and card faces. */
    public String revealSummary() {
        StringBuilder sb = new StringBuilder();
        append(sb, revealGold, "gold");
        append(sb, revealMight, "might");
        return sb.isEmpty() ? "—" : sb.toString();
    }

    private static void append(StringBuilder sb, int n, String what) {
        if (n <= 0) return;
        if (!sb.isEmpty()) sb.append("  ");
        sb.append('+').append(n).append(' ').append(what);
    }
}
