package com.larsons.engine.deckbuilder;

import java.awt.Color;

/**
 * The four agent-icon families of Council of Six. Every card carries one or
 * more icons and every board location demands one: a card can only send an
 * agent to a location whose icon it carries — the Dune Imperium placement
 * rule, kept to four easy-to-read colours.
 */
public enum LocationIcon {
    ECONOMY("Economy", new Color(235, 185, 80)),
    KNOWLEDGE("Knowledge", new Color(95, 145, 235)),
    MILITARY("Military", new Color(220, 90, 80)),
    COUNCIL("Council", new Color(150, 110, 220));

    public final String label;
    public final Color color;

    LocationIcon(String label, Color color) {
        this.label = label;
        this.color = color;
    }
}
