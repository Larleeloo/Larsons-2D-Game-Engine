package com.larsons.engine.sim;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.level.StatRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a level's {@link StatRule}s against the run's
 * {@link PlayerStats} each tick, firing rewards/consumptions as thresholds
 * are crossed. One engine instance per run — it owns the per-rule "how many
 * times has this fired" state, so re-entering a level starts fresh.
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
}
