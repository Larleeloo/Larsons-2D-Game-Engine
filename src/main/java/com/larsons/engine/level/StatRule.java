package com.larsons.engine.level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One map-maker-programmable stat trigger, saved with the level: <em>"when
 * tracked stat {@code stat} reaches {@code threshold}, optionally consume
 * {@code consumeCount}&times;{@code consumeItem} from the player's inventory
 * and give {@code rewardCount}&times;{@code rewardItem}"</em>.
 *
 * <p>Examples the creative editor's Stat Rules dialog can express:
 * <ul>
 *   <li>"if you have mined 50 blocks → receive a health potion" (once)</li>
 *   <li>"every 1000 px travelled → receive 1 bread" (repeating)</li>
 *   <li>"if you hold ≥ 10 stone → consume 10 stone, receive 1 iron ingot"
 *       (repeating, gated on the consumption)</li>
 * </ul>
 *
 * <p>Rules are pure data; {@link com.larsons.engine.sim.StatRuleEngine}
 * evaluates them each tick against the run's
 * {@link com.larsons.engine.sim.PlayerStats}. Repeating rules fire once per
 * {@code threshold} step (at threshold, 2&times;threshold, …); with
 * {@link #showBar} the HUD draws a live progress bar toward the next firing.
 *
 * @param stat         tracked stat key (see {@code PlayerStats.TRACKED})
 * @param threshold    stat value that fires the rule (&gt; 0)
 * @param rewardItem   item key granted on fire ({@code null}/empty = none)
 * @param rewardCount  how many of the reward item
 * @param consumeItem  item key required+consumed on fire ({@code null} = none)
 * @param consumeCount how many to consume (rule waits until the player has them)
 * @param repeat       fire every {@code threshold} step instead of once
 * @param showBar      draw a HUD progress bar for this rule
 */
public record StatRule(String stat, double threshold, String rewardItem, int rewardCount,
                       String consumeItem, int consumeCount, boolean repeat, boolean showBar) {

    public StatRule {
        if (stat == null || stat.isBlank()) throw new IllegalArgumentException("stat required");
        if (threshold <= 0) threshold = 1;
        if (rewardCount < 0) rewardCount = 0;
        if (consumeCount < 0) consumeCount = 0;
        rewardItem = blankToNull(rewardItem);
        consumeItem = blankToNull(consumeItem);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stat", stat);
        m.put("threshold", threshold);
        if (rewardItem != null) {
            m.put("reward", rewardItem);
            m.put("rewardCount", rewardCount);
        }
        if (consumeItem != null) {
            m.put("consume", consumeItem);
            m.put("consumeCount", consumeCount);
        }
        m.put("repeat", repeat);
        m.put("showBar", showBar);
        return m;
    }

    public static StatRule fromMap(Map<?, ?> m) {
        return new StatRule(
                m.get("stat") instanceof String s ? s : "blocks_mined",
                m.get("threshold") instanceof Number n ? n.doubleValue() : 1,
                m.get("reward") instanceof String s ? s : null,
                m.get("rewardCount") instanceof Number n ? n.intValue() : 1,
                m.get("consume") instanceof String s ? s : null,
                m.get("consumeCount") instanceof Number n ? n.intValue() : 1,
                Boolean.TRUE.equals(m.get("repeat")),
                Boolean.TRUE.equals(m.get("showBar")));
    }
}
