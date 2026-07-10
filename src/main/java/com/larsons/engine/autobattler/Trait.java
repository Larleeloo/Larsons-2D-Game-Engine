package com.larsons.engine.autobattler;

import java.awt.Color;
import java.util.List;

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
 * Breakpoints are deliberately varied rather than a copied 3/6/9 ladder:
 * even ladders (2/4/6), odd ladders (3/5, 1/3/5), and short ladders coexist,
 * and tier values scale super-linearly (each tier is worth more than double
 * the last) so committing deep into a synergy stays exciting.
 *
 * <p>Every trait also declares the functional {@link SynergyCategory
 * categories} it belongs to (one or more), which the UI uses for icons,
 * filtering, and teaching new players what each synergy is for. Traits in
 * {@link SynergyCategory#SUPPORT} primarily enhance the rest of the team
 * (Holy, Guardian, Healer, Mystic) rather than defining an archetype.
 */
public enum Trait {

    // --- origins ---------------------------------------------------------------
    FOREST("Forest", Kind.ORIGIN, new int[]{2, 4}, new double[]{0.02, 0.05},
            new Color(96, 180, 90),
            "Forest units regenerate 2% / 5% max HP per second",
            SynergyCategory.HEALING, SynergyCategory.SUPPORT),
    EMBER("Ember", Kind.ORIGIN, new int[]{3, 5}, new double[]{25, 65},
            new Color(235, 110, 60),
            "Ember units gain +25 / +65 attack damage",
            SynergyCategory.DAMAGE),
    FROST("Frost", Kind.ORIGIN, new int[]{2, 4}, new double[]{0.15, 0.35},
            new Color(120, 190, 235),
            "Enemies attack 15% / 35% slower",
            SynergyCategory.CROWD_CONTROL, SynergyCategory.UTILITY),
    STORM("Storm", Kind.ORIGIN, new int[]{2, 5}, new double[]{0.30, 0.80},
            new Color(150, 130, 235),
            "Storm units deal +30% / +80% ability damage",
            SynergyCategory.MAGIC, SynergyCategory.DAMAGE),
    SHADOW("Shadow", Kind.ORIGIN, new int[]{3, 5}, new double[]{0.30, 0.60},
            new Color(110, 95, 140),
            "Shadow units gain +30% / +60% critical strike chance",
            SynergyCategory.DAMAGE),
    HOLY("Holy", Kind.ORIGIN, new int[]{2, 4}, new double[]{0.12, 0.30},
            new Color(235, 210, 120),
            "Your whole team gains +12% / +30% max HP",
            SynergyCategory.SUPPORT, SynergyCategory.TANK),
    WILD("Wild", Kind.ORIGIN, new int[]{2, 4}, new double[]{0.20, 0.50},
            new Color(190, 150, 80),
            "Wild units attack 20% / 50% faster",
            SynergyCategory.DAMAGE),
    MECH("Mech", Kind.ORIGIN, new int[]{2, 4}, new double[]{25, 60},
            new Color(150, 160, 175),
            "Mech units gain +25 / +60 armor",
            SynergyCategory.TANK),
    MYSTIC("Mystic", Kind.ORIGIN, new int[]{2, 4}, new double[]{15, 40},
            new Color(190, 160, 235),
            "Your whole team gains +15 / +40 spell power",
            SynergyCategory.SUPPORT, SynergyCategory.MAGIC),
    MERCHANT("Merchant", Kind.ORIGIN, new int[]{2, 3}, new double[]{1, 2},
            new Color(230, 190, 90),
            "Earn +1 / +2 extra gold at the start of each round",
            SynergyCategory.ECONOMY, SynergyCategory.UTILITY),

    // --- classes ---------------------------------------------------------------
    WARRIOR("Warrior", Kind.CLASS, new int[]{2, 4}, new double[]{20, 50},
            new Color(200, 90, 80),
            "Warriors gain +20 / +50 armor",
            SynergyCategory.DAMAGE, SynergyCategory.TANK),
    GUARDIAN("Guardian", Kind.CLASS, new int[]{2, 4, 6}, new double[]{10, 22, 45},
            new Color(110, 140, 200),
            "Your whole team gains +10 / +22 / +45 armor",
            SynergyCategory.SHIELDING, SynergyCategory.SUPPORT, SynergyCategory.TANK),
    ARCHER("Archer", Kind.CLASS, new int[]{2, 4, 6}, new double[]{0.20, 0.45, 0.85},
            new Color(140, 200, 110),
            "Archers attack 20% / 45% / 85% faster",
            SynergyCategory.DAMAGE, SynergyCategory.RANGE),
    MAGE("Mage", Kind.CLASS, new int[]{2, 4, 6}, new double[]{15, 35, 65},
            new Color(90, 160, 235),
            "Mages start combat with +15 / +35 / +65 mana",
            SynergyCategory.MAGIC, SynergyCategory.DAMAGE),
    ASSASSIN("Assassin", Kind.CLASS, new int[]{2, 3, 5}, new double[]{0.15, 0.30, 0.55},
            new Color(180, 100, 180),
            "Assassins leap to the enemy backline and gain +15% / +30% / +55% crit chance",
            SynergyCategory.DAMAGE, SynergyCategory.MOBILITY),
    HEALER("Healer", Kind.CLASS, new int[]{1, 3, 5}, new double[]{0.25, 0.60, 1.10},
            new Color(120, 220, 190),
            "Healing is 25% / 60% / 110% stronger",
            SynergyCategory.HEALING, SynergyCategory.SUPPORT),
    BRAWLER("Brawler", Kind.CLASS, new int[]{2, 4, 6}, new double[]{200, 450, 850},
            new Color(220, 160, 90),
            "Brawlers gain +200 / +450 / +850 max HP",
            SynergyCategory.TANK);

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
    /** The functional roles this synergy plays (never empty, often several). */
    public final List<SynergyCategory> categories;

    Trait(String label, Kind kind, int[] thresholds, double[] values,
          Color color, String description, SynergyCategory... categories) {
        this.label = label;
        this.kind = kind;
        this.thresholds = thresholds;
        this.values = values;
        this.color = color;
        this.description = description;
        this.categories = List.of(categories);
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

    /** True for synergies whose job is enhancing the rest of the team. */
    public boolean isSupport() {
        return categories.contains(SynergyCategory.SUPPORT);
    }
}
