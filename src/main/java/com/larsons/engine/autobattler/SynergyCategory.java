package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * The functional role(s) a synergy plays, so players can read a trait's job at
 * a glance and browse "all the healing synergies" instead of memorising each
 * one. A {@link Trait} belongs to one or more categories (Regeneration is
 * Healing <em>and</em> Support; Archer is Damage <em>and</em> Range) — the
 * categories organise without restricting design.
 *
 * <p>{@link #SUPPORT} marks synergies that primarily enhance the rest of the
 * team rather than defining an archetype of their own.
 */
public enum SynergyCategory {

    SUPPORT("Support", new Color(150, 220, 200),
            "Enhances the rest of your team rather than itself"),
    DAMAGE("Damage", new Color(235, 120, 90),
            "Raises your team's damage output"),
    TANK("Tank", new Color(160, 170, 190),
            "Toughness: armor and max HP"),
    HEALING("Healing", new Color(120, 220, 140),
            "Restores health during combat"),
    SHIELDING("Shielding", new Color(120, 170, 235),
            "Mitigation for the whole team"),
    RANGE("Range", new Color(170, 210, 120),
            "Backline attackers striking from afar"),
    CROWD_CONTROL("Crowd Control", new Color(150, 190, 240),
            "Slows or disrupts the enemy team"),
    MAGIC("Magic", new Color(170, 140, 240),
            "Abilities, mana and spell power"),
    MOBILITY("Mobility", new Color(230, 200, 110),
            "Repositioning and reaching the backline"),
    ECONOMY("Economy", new Color(255, 214, 100),
            "Earns extra gold between fights"),
    UTILITY("Utility", new Color(200, 180, 160),
            "Flexible effects that don't fit one lane");

    public final String label;
    public final Color color;
    public final String description;

    SynergyCategory(String label, Color color, String description) {
        this.label = label;
        this.color = color;
        this.description = description;
    }
}
