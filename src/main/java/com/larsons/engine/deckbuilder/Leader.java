package com.larsons.engine.deckbuilder;

import java.awt.Color;

/**
 * The six playable leaders of Council of Six — the game-night crew themselves,
 * each with one simple always-on passive (Dune Imperium leader style: strong
 * flavour, one sentence of rules). Passives are resolved server-side in
 * {@link DeckGame}; this enum is pure data shared by both ends of the wire.
 */
public enum Leader {

    LARSON("Larson", "The Architect",
            "Starts every round with +1 bonus gold.",
            new Color(235, 185, 80)),

    MATT("Matt", "The Tactician",
            "The first time he deploys troops each round, he deploys one extra.",
            new Color(110, 185, 110)),

    DUSTIN("Dustin", "The Spellslinger",
            "Draws a 6-card hand instead of 5 — he knows every combo.",
            new Color(95, 145, 235)),

    KRIS("Kris", "The Quartermaster",
            "Round start: +1 gold per 4 gold held (max +2).",
            new Color(190, 110, 220)),

    BELLA("Bella", "The Envoy",
            "Once per round, may place an agent on an occupied location.",
            new Color(230, 130, 170)),

    ERIC("Eric", "The Warlord",
            "Automatically commits +1 might to every conflict.",
            new Color(220, 90, 80));

    public final String friendName;
    public final String title;
    public final String passive;
    public final Color color;

    Leader(String friendName, String title, String passive, Color color) {
        this.friendName = friendName;
        this.title = title;
        this.passive = passive;
        this.color = color;
    }

    /** Parse a wire code ({@link #name()}); {@code null} when unknown. */
    public static Leader fromCode(String code) {
        if (code == null) return null;
        for (Leader l : values()) {
            if (l.name().equals(code)) return l;
        }
        return null;
    }
}
