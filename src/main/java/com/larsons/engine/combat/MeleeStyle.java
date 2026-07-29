package com.larsons.engine.combat;

import com.larsons.engine.combat.MeleeProfile.Move;

import java.util.EnumMap;
import java.util.Map;

/**
 * The melee presets: the handful of ways a held object can fight, each one a
 * complete set of {@link MeleeProfile.Move} timings. A dagger flicks, a hammer
 * commits, a shield guards and bashes; everything else is a variation on those.
 *
 * <p>{@link MeleeProfiles} picks a style for an item (from its category, its
 * tool class, and its name) and a game type can override the choice per item,
 * so a creator's "Obsidian Cleaver" fights like an axe without writing any
 * timings — and a creator who <em>wants</em> its own timings registers a
 * {@link MeleeProfile} instead.
 *
 * <p>Every style can {@link MeleeAction#DASH} — footwork belongs to the
 * fighter, not the weapon — and every style can raise a guard, however badly.
 * {@link MeleeAction#LUNGE} is the one move a style may lack: a pickaxe and a
 * staff have no thrust in them.
 */
public enum MeleeStyle {

    /** Bare hands: short, fast, weak, and always available. */
    FIST("Fists", 42, 90, 8, 0.15),

    /** Knives: the shortest reach and the quickest recovery, murderous lunge. */
    DAGGER("Dagger", 46, 70, 6, 0.20),

    /** The all-rounder: a real arc, a real parry, a real lunge. */
    SWORD("Sword", 64, 110, 14, 0.30),

    /** Slow, wide, and heavy on the follow-through. */
    AXE("Axe", 62, 130, 22, 0.22),

    /** Long and narrow: it out-reaches everything and thrusts hardest. */
    SPEAR("Spear", 86, 55, 12, 0.24),

    /** The slowest and the most punishing; a parry with one is a bad idea. */
    HAMMER("Hammer", 60, 140, 34, 0.26),

    /** Made for the guard stance: the best parry and the best block, a poor hit. */
    SHIELD("Shield", 40, 100, 26, 0.55),

    /** Pickaxes, axes, shovels: they swing, but they were made for rock. */
    TOOL("Tool", 54, 100, 10, 0.18),

    /** Bows and staves used up close: a pommel strike and not much else. */
    STAFF("Staff", 48, 90, 8, 0.20);

    private final String label;
    private final double reach;
    private final double arc;
    private final double knockback;
    private final double blockFraction;

    MeleeStyle(String label, double reach, double arc, double knockback,
               double blockFraction) {
        this.label = label;
        this.reach = reach;
        this.arc = arc;
        this.knockback = knockback;
        this.blockFraction = blockFraction;
    }

    public String label() {
        return label;
    }

    public double reach() {
        return reach;
    }

    /** The style's baseline guard, before the item's tier improves it. */
    public double blockFraction() {
        return blockFraction;
    }

    /**
     * This style's profile for one item. {@code tier} is the item's rarity
     * ordinal (0 = common … 5 = mythic); it buys a slightly stronger guard, so
     * a Tower Shield stops more than a plank does without needing its own
     * timings.
     */
    public MeleeProfile profile(String itemKey, int tier) {
        double guard = Math.min(0.85, blockFraction + Math.max(0, tier) * 0.03);
        return new MeleeProfile(itemKey == null ? "" : itemKey, this, reach, arc,
                knockback, guard, moves());
    }

    /** {@link #profile(String, int)} at the baseline tier. */
    public MeleeProfile profile(String itemKey) {
        return profile(itemKey, 0);
    }

    /** The timings of every move this style can perform. */
    private Map<MeleeAction, Move> moves() {
        Map<MeleeAction, Move> m = new EnumMap<>(MeleeAction.class);
        switch (this) {
            case FIST -> {
                m.put(MeleeAction.SWING, new Move(0.06, 0.10, 0.12, 0.28, 2, 1.00, 0));
                m.put(MeleeAction.PARRY, new Move(0.04, 0.16, 0.22, 1.10, 8, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.10, 0.18, 0.18, 1.60, 14, 1.30, 430));
                m.put(MeleeAction.DASH, new Move(0.02, 0.18, 0.12, 0.90, 16, 0, 620));
                m.put(MeleeAction.SHIELD, new Move(0.12, 0, 0.16, 0.35, 0, 0, 0));
            }
            case DAGGER -> {
                m.put(MeleeAction.SWING, new Move(0.04, 0.08, 0.08, 0.20, 2, 0.90, 0));
                m.put(MeleeAction.PARRY, new Move(0.03, 0.20, 0.16, 0.85, 7, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.07, 0.16, 0.14, 1.20, 12, 1.60, 520));
                m.put(MeleeAction.DASH, new Move(0.02, 0.20, 0.10, 0.75, 14, 0, 700));
                m.put(MeleeAction.SHIELD, new Move(0.10, 0, 0.14, 0.30, 0, 0, 0));
            }
            case SWORD -> {
                m.put(MeleeAction.SWING, new Move(0.08, 0.12, 0.14, 0.38, 4, 1.00, 0));
                m.put(MeleeAction.PARRY, new Move(0.04, 0.22, 0.20, 0.90, 9, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.12, 0.18, 0.20, 1.50, 16, 1.50, 480));
                m.put(MeleeAction.DASH, new Move(0.02, 0.18, 0.14, 1.00, 18, 0, 620));
                m.put(MeleeAction.SHIELD, new Move(0.12, 0, 0.16, 0.35, 0, 0, 0));
            }
            case AXE -> {
                m.put(MeleeAction.SWING, new Move(0.16, 0.14, 0.24, 0.62, 7, 1.15, 0));
                m.put(MeleeAction.PARRY, new Move(0.07, 0.16, 0.26, 1.30, 12, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.18, 0.18, 0.26, 2.00, 20, 1.70, 430));
                m.put(MeleeAction.DASH, new Move(0.03, 0.16, 0.18, 1.40, 22, 0, 540));
                m.put(MeleeAction.SHIELD, new Move(0.16, 0, 0.20, 0.40, 0, 0, 0));
            }
            case SPEAR -> {
                m.put(MeleeAction.SWING, new Move(0.10, 0.12, 0.18, 0.48, 5, 1.05, 0));
                m.put(MeleeAction.PARRY, new Move(0.05, 0.20, 0.22, 1.00, 9, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.10, 0.22, 0.18, 1.30, 15, 1.80, 560));
                m.put(MeleeAction.DASH, new Move(0.02, 0.18, 0.14, 1.10, 18, 0, 600));
                m.put(MeleeAction.SHIELD, new Move(0.14, 0, 0.18, 0.38, 0, 0, 0));
            }
            case HAMMER -> {
                m.put(MeleeAction.SWING, new Move(0.22, 0.16, 0.30, 0.80, 9, 1.30, 0));
                m.put(MeleeAction.PARRY, new Move(0.09, 0.14, 0.30, 1.60, 14, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.22, 0.20, 0.30, 2.40, 24, 2.00, 400));
                m.put(MeleeAction.DASH, new Move(0.04, 0.14, 0.22, 1.70, 26, 0, 500));
                m.put(MeleeAction.SHIELD, new Move(0.18, 0, 0.22, 0.45, 0, 0, 0));
            }
            case SHIELD -> {
                // A bash, not a strike — but the guard and the catch are the best there is.
                m.put(MeleeAction.SWING, new Move(0.10, 0.12, 0.20, 0.70, 6, 0.60, 0));
                m.put(MeleeAction.PARRY, new Move(0.03, 0.26, 0.14, 0.70, 6, 0, 0));
                m.put(MeleeAction.LUNGE, new Move(0.10, 0.24, 0.22, 1.60, 18, 1.10, 520));
                m.put(MeleeAction.DASH, new Move(0.03, 0.16, 0.16, 1.20, 20, 0, 560));
                m.put(MeleeAction.SHIELD, new Move(0.08, 0, 0.12, 0.25, 0, 0, 0));
            }
            case TOOL -> {
                // No thrust: nobody fences with a shovel.
                m.put(MeleeAction.SWING, new Move(0.10, 0.12, 0.16, 0.45, 4, 1.00, 0));
                m.put(MeleeAction.PARRY, new Move(0.06, 0.16, 0.24, 1.30, 11, 0, 0));
                m.put(MeleeAction.DASH, new Move(0.03, 0.18, 0.14, 1.10, 18, 0, 600));
                m.put(MeleeAction.SHIELD, new Move(0.14, 0, 0.18, 0.38, 0, 0, 0));
            }
            case STAFF -> {
                m.put(MeleeAction.SWING, new Move(0.12, 0.10, 0.18, 0.55, 5, 0.50, 0));
                m.put(MeleeAction.PARRY, new Move(0.06, 0.18, 0.22, 1.20, 10, 0, 0));
                m.put(MeleeAction.DASH, new Move(0.02, 0.18, 0.14, 1.00, 18, 0, 620));
                m.put(MeleeAction.SHIELD, new Move(0.14, 0, 0.18, 0.38, 0, 0, 0));
            }
        }
        return m;
    }
}
