package com.larsons.engine.sim;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.level.StatRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a level's {@link StatRule}s against the run's
 * {@link PlayerStats} each tick, firing rewards/consumptions as thresholds
 * are crossed.
 *
 * <p><b>The fire counts belong to the run, not to this object.</b> One engine
 * exists per level being played, and a level is entered and left many times in
 * one run — every door transition builds a new one. The counts therefore have
 * to outlive it, and {@link com.larsons.engine.save.RunRecord} is what holds
 * them, keyed by level, restored through {@link #restore(int[])} whenever a
 * level is entered again.
 *
 * <p>That indirection is not decoration. When these counts started at zero on
 * every entry while {@link PlayerStats} carried on accumulating, a one-shot
 * rule fired again on every return: "mine 50 blocks → take a diamond" paid out
 * once, and then once more each time the player stepped through a door and
 * back, forever. Two halves of one mechanism with two different lifetimes is
 * the whole of that bug.
 *
 * <p>Firing semantics:
 * <ul>
 *   <li>One-shot rules fire the first time {@code stat >= threshold}.</li>
 *   <li>Repeating rules fire at every multiple: threshold, 2×, 3×…</li>
 *   <li>A rule with a consumption only fires while the inventory holds the
 *       required items — otherwise it waits (checked again next tick), which
 *       is what makes "if you have ≥ 10 stone → trade for an ingot" work.</li>
 * </ul>
 */
public final class StatRuleEngine {

    private final List<StatRule> rules;
    private final int[] fired; // times each rule has fired this run

    public StatRuleEngine(List<StatRule> rules) {
        this.rules = rules;
        this.fired = new int[rules.size()];
    }

    /** A rule that fired this tick, for status text / sound feedback. */
    public record Fired(StatRule rule) {}

    /**
     * Check every rule; apply those due. Rewards go into {@code inv} (null =
     * no inventory: reward-less consume rules still gate correctly).
     * Returns the rules fired this tick.
     */
    public List<Fired> update(PlayerStats stats, Inventory inv) {
        List<Fired> out = List.of();
        for (int i = 0; i < rules.size(); i++) {
            StatRule rule = rules.get(i);
            if (!rule.repeat() && fired[i] > 0) continue;
            double due = rule.threshold() * (fired[i] + 1);
            if (stats.get(rule.stat()) < due) continue;

            // Consumption gates the firing: wait until the player can pay.
            if (rule.consumeItem() != null && inv != null) {
                if (inv.totalOf(rule.consumeItem()) < rule.consumeCount()) continue;
                inv.remove(rule.consumeItem(), rule.consumeCount());
            }
            if (rule.rewardItem() != null && inv != null && rule.rewardCount() > 0) {
                inv.add(rule.rewardItem(), rule.rewardCount());
            }
            fired[i]++;
            if (out.isEmpty()) out = new ArrayList<>();
            out.add(new Fired(rule));
        }
        return out;
    }

    /** Progress [0,1] toward a rule's next firing (drives HUD bars). */
    public double progress(StatRule rule, PlayerStats stats) {
        int idx = rules.indexOf(rule);
        if (idx < 0) return 0;
        if (!rule.repeat() && fired[idx] > 0) return 1;
        double due = rule.threshold() * (fired[idx] + 1);
        double prev = rule.threshold() * fired[idx];
        return Math.max(0, Math.min(1, (stats.get(rule.stat()) - prev) / (due - prev)));
    }

    /** How many times a rule has fired this run. */
    public int firedCount(StatRule rule) {
        int idx = rules.indexOf(rule);
        return idx < 0 ? 0 : fired[idx];
    }

    /**
     * The fire counts, in rule order, for the run to keep while this level is
     * not the one being played.
     */
    public int[] firedCounts() {
        return fired.clone();
    }

    /**
     * Put saved counts back, as far as they line up with the rules this level
     * actually has.
     *
     * <p>Deliberately tolerant of a length mismatch rather than strict about
     * it: a level whose author added or removed a rule since the save was
     * written is an ordinary thing to open, and the honest answer for the rules
     * that still line up is their saved count. The alternative — refusing the
     * whole array — would silently re-arm every one-shot reward in the level,
     * which is precisely the failure this method exists to prevent.
     */
    public void restore(int[] counts) {
        if (counts == null) return;
        for (int i = 0; i < fired.length && i < counts.length; i++) {
            fired[i] = Math.max(0, counts[i]);
        }
    }
}
