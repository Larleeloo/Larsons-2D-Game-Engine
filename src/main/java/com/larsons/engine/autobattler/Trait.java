package com.larsons.engine.autobattler;

import java.awt.Color;

/**
 * A synergy trait for the auto-battler: fielding enough <em>different</em>
 * units that share a trait activates it at a tier, buffing the team for the
 * whole combat (the Teamfight Tactics / Auto Chess model). Every unit has one
 * {@link Kind#ORIGIN} trait and one {@link Kind#CLASS} trait; PvE creeps have
 * neither.
 *
 * <p>Each trait carries its activation thresholds and the effect magnitude at
 * each tier — {@link BattleSim} reads these when it builds combat units, so
 * adding or rebalancing a synergy is an edit here, not in the simulation.
 */
public enum Trait {

    // --- origins ---------------------------------------------------------------
    FOREST("Forest", Kind.ORIGIN, new int[]{2, 3}, new double[]{0.02, 0.045},
            new Color(96, 180, 90),
            "Forest units regenerate 2% / 4.5% max HP per second"),
    EMBER("Ember", Kind.ORIGIN, new int[]{2, 4}, new double[]{20, 50},
            new Color(235, 110, 60),
            "Ember units gain +20 / +50 attack damage"),
    FROST("Frost", Kind.ORIGIN, new int[]{2, 3}, new double[]{0.15, 0.35},
            new Color(120, 190, 235),
            "Enemies attack 15% / 35% slower"),
    STORM("Storm", Kind.ORIGIN, new int[]{2, 4}, new double[]{0.30, 0.70},
            new Color(150, 130, 235),
            "Storm units deal +30% / +70% ability damage"),
    SHADOW("Shadow", Kind.ORIGIN, new int[]{2, 3}, new double[]{0.25, 0.50},
            new Color(110, 95, 140),
            "Shadow units gain +25% / +50% critical strike chance"),
    HOLY("Holy", Kind.ORIGIN, new int[]{2, 3}, new double[]{0.12, 0.28},
            new Color(235, 210, 120),
            "Your whole team gains +12% / +28% max HP"),
    WILD("Wild", Kind.ORIGIN, new int[]{2, 3}, new double[]{0.20, 0.50},
            new Color(190, 150, 80),
            "Wild units attack 20% / 50% faster"),
    MECH("Mech", Kind.ORIGIN, new int[]{2, 3}, new double[]{25, 60},
            new Color(150, 160, 175),
            "Mech units gain +25 / +60 armor"),

    // --- classes ---------------------------------------------------------------
    WARRIOR("Warrior", Kind.CLASS, new int[]{2, 4}, new double[]{20, 45},
            new Color(200, 90, 80),
            "Warriors gain +20 / +45 armor"),
    GUARDIAN("Guardian", Kind.CLASS, new int[]{2, 4}, new double[]{10, 25},
            new Color(110, 140, 200),
            "Your whole team gains +10 / +25 armor"),
    ARCHER("Archer", Kind.CLASS, new int[]{2, 4}, new double[]{0.25, 0.60},
            new Color(140, 200, 110),
            "Archers attack 25% / 60% faster"),
    MAGE("Mage", Kind.CLASS, new int[]{2, 4}, new double[]{20, 45},
            new Color(90, 160, 235),
            "Mages start combat with +20 / +45 mana"),
    ASSASSIN("Assassin", Kind.CLASS, new int[]{2, 3}, new double[]{0.20, 0.45},
            new Color(180, 100, 180),
            "Assassins leap to the enemy backline and gain +20% / +45% crit chance"),
    HEALER("Healer", Kind.CLASS, new int[]{1, 2}, new double[]{0.30, 0.70},
            new Color(120, 220, 190),
            "Healing is 30% / 70% stronger"),
    BRAWLER("Brawler", Kind.CLASS, new int[]{2, 4}, new double[]{250, 600},
            new Color(220, 160, 90),
            "Brawlers gain +250 / +600 max HP");

    /** Whether the trait is a unit's origin (tribe) or class (role). */
    public enum Kind { ORIGIN, CLASS }

    public final String label;
    public final Kind kind;
    /** Unit counts that activate tier 1, tier 2, ... (ascending). */
    public final int[] thresholds;
    /** Effect magnitude at each tier (same length as {@link #thresholds}). */
    public final double[] values;
    public final Color color;
    public final String description;

    Trait(String label, Kind kind, int[] thresholds, double[] values,
          Color color, String description) {
        this.label = label;
        this.kind = kind;
        this.thresholds = thresholds;
        this.values = values;
        this.color = color;
        this.description = description;
    }

    /** The active tier for {@code count} distinct units: 0 = inactive, 1 = first tier... */
    public int tier(int count) {
        int t = 0;
        for (int i = 0; i < thresholds.length; i++) {
            if (count >= thresholds[i]) t = i + 1;
        }
        return t;
    }

    /** Effect magnitude at the active tier for {@code count} units (0 when inactive). */
    public double value(int count) {
        int t = tier(count);
        return t == 0 ? 0 : values[t - 1];
    }
}
