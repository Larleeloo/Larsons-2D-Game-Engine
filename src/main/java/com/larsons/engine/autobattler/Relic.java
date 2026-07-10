package com.larsons.engine.autobattler;

import java.awt.Color;
import java.util.Random;

/**
 * A player relic: a match-long special ability every player carries, dealt at
 * random on round 1 and re-dealt every {@value AutoGame#RELIC_INTERVAL} rounds
 * — a rotating "hero power" layer on top of the economy, distinct from the
 * elemental relic <em>items</em> ({@link AutoItem.Kind#RELIC}) that units
 * hold. Passive relics work only while held; one-shot relics
 * ({@link #oneShot}) fire once when granted; and Zealot's synergy boost is
 * deliberately permanent — "for the rest of the game" — so it outlives its
 * own rotation.
 *
 * <p>All effects are read by {@link AutoGame} (economy, shops, combining,
 * selling, creeps, plunder) or folded into {@link BattleSim.TeamMods}
 * (damage amp, trait boosts), so the enum itself stays pure data.
 */
public enum Relic {

    BARGAINER("Bargainer", new Color(130, 200, 130),
            "Shop units are randomly discounted by 3 gold", false),
    WILD_HUNT("Wild Hunt", new Color(200, 150, 90),
            "Creep rounds spawn a random menagerie with greater rewards", false),
    PLUNDERER("Plunderer", new Color(230, 190, 90),
            "Winning a fight steals gold equal to the damage dealt", false),
    HARMONIZER("Harmonizer", new Color(170, 150, 230),
            "A unit gains a bonus synergy drawn from your current traits", true),
    MENDER("Mender", new Color(120, 220, 140),
            "Immediately restores 15-20 player HP", true),
    COLLECTOR("Collector", new Color(150, 180, 240),
            "Shops are far more likely to offer units you already own", false),
    APPRENTICE("Apprentice", new Color(210, 210, 230),
            "2-star upgrades need only 2 copies instead of 3", false),
    VOID_BRAND("Void Brand", new Color(190, 120, 210),
            "Your attacks carry a universal element every unit is weak to (+15% damage)", false),
    FENCE("Fence", new Color(235, 160, 100),
            "2-star and 3-star units sell for triple their value", false),
    ZEALOT("Zealot", new Color(235, 120, 105),
            "A random synergy becomes 1.5x as effective for the rest of the game", true);

    /** Damage amplifier granted by {@link #VOID_BRAND} (+15%). */
    public static final double VOID_BRAND_AMP = 1.15;
    /** Trait effectiveness multiplier granted by {@link #ZEALOT}. */
    public static final double ZEALOT_BOOST = 1.5;

    /** Stable wire key ({@code you} messages carry it). */
    public final String key;
    public final String label;
    public final Color color;
    public final String description;
    /** True when the effect fires once on grant instead of working passively. */
    public final boolean oneShot;

    Relic(String label, Color color, String description, boolean oneShot) {
        this.key = name().toLowerCase();
        this.label = label;
        this.color = color;
        this.description = description;
        this.oneShot = oneShot;
    }

    /** Decode a wire key; unknown/absent keys read as {@code null}. */
    public static Relic fromKey(String key) {
        for (Relic r : values()) {
            if (r.key.equals(key)) return r;
        }
        return null;
    }

    /** A random relic, avoiding {@code except} so re-deals always change hands. */
    public static Relic roll(Random rng, Relic except) {
        Relic[] all = values();
        Relic pick = all[rng.nextInt(all.length)];
        while (pick == except) pick = all[rng.nextInt(all.length)];
        return pick;
    }
}
