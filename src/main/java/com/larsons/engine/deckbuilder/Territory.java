package com.larsons.engine.deckbuilder;

import java.awt.Color;

/**
 * The four contested territories of Council of Six. Troops deployed there
 * persist between rounds (Inis-style presence), and at the end of every round
 * whoever holds a strict majority in a territory scores a victory point.
 */
public enum Territory {
    HIGHLANDS("The Highlands", new Color(150, 170, 200)),
    RIVERLANDS("The Riverlands", new Color(90, 175, 190)),
    EMBER_WASTES("The Ember Wastes", new Color(215, 120, 75)),
    EMERALD_VALE("The Emerald Vale", new Color(110, 190, 120));

    public final String label;
    public final Color color;

    Territory(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    /** Parse a wire code ({@link #name()}); {@code null} when unknown. */
    public static Territory fromCode(String code) {
        if (code == null) return null;
        for (Territory t : values()) {
            if (t.name().equals(code)) return t;
        }
        return null;
    }
}
