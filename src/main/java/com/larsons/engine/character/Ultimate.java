package com.larsons.engine.character;

import java.awt.Color;

/**
 * One ultimate ability: a signature move that charges as you play and fires
 * once, spending the whole meter.
 *
 * <p>Charge works the way an Overwatch ultimate does — a slow passive trickle
 * ({@link #chargeSeconds}, the time to fill doing nothing else) plus a much
 * faster gain per point of damage dealt ({@link #chargePerDamage}), so a
 * player who fights earns theirs long before a player who hides. The meter is
 * a 0..1 fraction on the player's state; {@link Ultimates#charge} advances it
 * and {@link com.larsons.engine.world.World#useUltimate} spends it.
 *
 * <p>Every ability is written to work identically in all three perspectives:
 * effects are radial, aimed, or self-targeted, and none of them assume a
 * "down" — so the same ultimate reads the same in a side-scroller, a top-down
 * map, and an isometric one.
 *
 * @param key             stable key, stored in character profiles and levels
 * @param name            display name
 * @param description     one line shown in the profile editor and the HUD
 * @param kind            which effect {@code World} resolves
 * @param chargeSeconds   seconds of play to fill the meter from time alone
 * @param chargePerDamage meter fraction gained per point of damage dealt
 * @param duration        seconds the effect lasts (0 = instant)
 * @param power           effect magnitude: damage, multiplier, or heal rate
 * @param radius          effect reach in world pixels (0 = not radial)
 * @param color           the meter's tint and the colour of its particles
 */
public record Ultimate(String key, String name, String description, Kind kind,
                       double chargeSeconds, double chargePerDamage,
                       double duration, double power, double radius, Color color) {

    /** What an ultimate actually does when it fires. */
    public enum Kind {
        /** Self-buff: faster, harder-hitting, tireless for a while. */
        OVERDRIVE,
        /** Instant radial blast centred on the caster, damage falling off. */
        NOVA,
        /** Self-buff: most incoming damage shrugged off, health regenerating. */
        BULWARK,
        /** Teleport toward the aim point, hurting everything passed through. */
        BLINK_STRIKE,
        /** A volley of projectiles called down on the aim point. */
        METEOR_VOLLEY,
        /** Everything nearby is chilled to a crawl for a while. */
        TIME_DILATION,
        /** Drains nearby enemies over time, healing the caster. */
        LIFE_SIPHON,
        /** Radial knockback + damage, and the terrain around you shatters. */
        EARTHSHATTER
    }

    /** Whether the ability keeps running after it fires (vs. resolving at once). */
    public boolean sustained() {
        return duration > 0;
    }

    /** The meter fraction {@code damage} points of damage dealt are worth. */
    public double chargeForDamage(double damage) {
        return Math.max(0, damage) * chargePerDamage;
    }

    /** The meter fraction {@code dt} seconds of play are worth. */
    public double chargeForTime(double dt) {
        return chargeSeconds <= 0 ? 0 : Math.max(0, dt) / chargeSeconds;
    }
}
