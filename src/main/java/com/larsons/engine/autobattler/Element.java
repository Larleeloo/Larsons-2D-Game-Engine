package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * An elemental damage type — a strategic layer on top of (not instead of) the
 * synergy system. Units can carry up to two attack elements, two resistances,
 * and two weaknesses ({@link UnitDef}); not every unit has any. Hitting a
 * weakness amplifies the damage, hitting a resistance dampens it, and the
 * swing grows round over round ({@link BattleSim#elementIntensity}) so element
 * matching matters more and more as a match goes on — scout your opponents and
 * adapt.
 *
 * <p>{@link #RADIATION} is the late-game element: no unit below cost 4 attacks
 * with it natively, so it effectively unlocks as lobbies level up (a Radiation
 * Core relic converts any unit's attacks to it).
 */
public enum Element {

    FIRE("Fire", "f", new Color(235, 90, 60),
            "Burning damage. Forest and Cryo units tend to be weak to it."),
    CRYO("Cryo", "c", new Color(120, 200, 240),
            "Freezing damage. Ember units tend to be weak to it."),
    CORROSIVE("Corrosive", "o", new Color(140, 220, 80),
            "Acid damage. Mech units tend to be weak to it."),
    EXPLOSIVE("Explosive", "x", new Color(240, 150, 70),
            "Blast damage. Rarely resisted, rarely amplified."),
    ELECTRIC("Electric", "e", new Color(140, 160, 250),
            "Shock damage. Shadow units tend to be weak to it."),
    RADIATION("Radiation", "r", new Color(215, 230, 100),
            "Late-game damage carried by cost 4+ units and the Radiation Core.");

    public final String label;
    /** One-letter wire code carried in combat events. */
    public final String code;
    public final Color color;
    public final String description;

    Element(String label, String code, Color color, String description) {
        this.label = label;
        this.code = code;
        this.color = color;
        this.description = description;
    }

    /** Decode a wire code; unknown/absent codes read as {@code null}. */
    public static Element fromCode(String code) {
        for (Element e : values()) {
            if (e.code.equals(code)) return e;
        }
        return null;
    }
}
