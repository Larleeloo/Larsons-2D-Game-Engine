package com.larsons.engine.entity;

import java.awt.Color;

/**
 * One mob species definition. Ported from the Side-Scroller engine, where each
 * mob was its own class ({@code ZombieMob}, {@code WolfMob}, …23 files);
 * merged into a data row so games add species by registering data (see
 * {@link MobRegistry}). Sprites are procedural (body/accent colours + shape
 * hints) since the engine ships no image assets.
 *
 * @param key         stable string key ("zombie"), stored in levels + wire
 * @param displayName palette name
 * @param body        main sprite colour
 * @param accent      secondary sprite colour (eyes/belly)
 * @param size        world-pixel size the species is <em>drawn</em> at
 *                    (square). Independent of {@link #hitbox()}, which is how
 *                    much floor it occupies — see
 *                    {@link com.larsons.engine.sim.ActorSize} for why a
 *                    species has two sizes and not one
 * @param hitboxSize  world-pixel footprint that collides, or {@code 0} to
 *                    occupy exactly as much floor as it is drawn on, which is
 *                    what every species registered before the two came apart
 *                    still asks for
 * @param speed       walk speed, px/sec
 * @param maxHealth   hit points
 * @param damage      contact/attack damage to players
 * @param temperament HOSTILE chases players; NEUTRAL retaliates only;
 *                    PASSIVE flees when hurt
 * @param detectRange world px within which hostiles notice a player
 * @param attackRange world px within which an attack lands
 * @param flying      ignores gravity (bat, dragon)
 * @param projectile  {@link ProjectileDef} key this species fires at players
 *                    in range instead of striking with melee ({@code null} =
 *                    melee only). Ranged species pair this with a long
 *                    {@code attackRange} so they open fire from a distance.
 * @param ability     special behaviour layered onto the shared AI (see
 *                    {@link Ability}); {@link Ability#NONE} for plain mobs
 * @param abilityArg  ability parameter: the mob key SUMMON spawns / SPLIT
 *                    breaks into, or the projectile key whose colours a
 *                    DEATH_BURST explosion borrows; {@code null} otherwise
 * @param weapon      the {@link ItemDef} key this species fights with
 *                    ({@code null} = claws and teeth). An armed species
 *                    inherits that weapon's melee timings, its swing sounds,
 *                    and any {@code wield/<item>} art drawn for it, exactly
 *                    like a player holding the same thing — see
 *                    {@code com.larsons.engine.combat.MeleeProfiles}
 */
public record MobDef(String key, String displayName, Color body, Color accent,
                     double size, double hitboxSize,
                     double speed, double maxHealth, double damage,
                     Temperament temperament, double detectRange,
                     double attackRange, boolean flying,
                     String projectile, Ability ability, String abilityArg,
                     String weapon) {

    /** Ported from the side-scroller's mob registry categories. */
    public enum Temperament { HOSTILE, NEUTRAL, PASSIVE }

    /**
     * A species' unique trick, run by {@link Mob#step} / resolved by the
     * {@link com.larsons.engine.world.World} — all simulation-side, so every
     * ability behaves identically offline and on the authoritative server
     * (clients just render the replicated result).
     */
    public enum Ability {
        /** Plain melee/ranged AI, no extra trick. */
        NONE,
        /** Pounces at chased players with long arcing jumps (panther). */
        LEAP,
        /** Winds up, then dashes at triple speed in a straight line (boar). */
        CHARGE,
        /** Blinks next to a chased player instead of walking (wraith). */
        TELEPORT,
        /** Periodically calls in a minion of {@code abilityArg} (necromancer). */
        SUMMON,
        /** Death breaks it into two {@code abilityArg} children (giant slime). */
        SPLIT,
        /** Death detonates an area blast styled after {@code abilityArg}. */
        DEATH_BURST,
        /** Slowly knits wounds closed while alive (troll). */
        REGEN,
        /** Melee hits drink half the damage back as health (vampire). */
        LIFESTEAL,
        /** Cycles a briefly-invulnerable glowing guard stance (golem). */
        SHIELD
    }

    /**
     * How much floor this species occupies, world pixels — its own footprint,
     * or its drawn size when it was registered before the two came apart.
     *
     * <p>Everything that collides, chases, or measures where a mob <em>is</em>
     * asks this; {@link #size()} is only ever how large it is drawn. A dragon
     * can therefore fill the screen and still fit through the cave mouth its
     * artist drew it squeezing into.
     */
    public double hitbox() {
        return hitboxSize > 0 ? hitboxSize : size;
    }

    /** This species again, redrawn at {@code drawSize} without moving its feet. */
    public MobDef drawnAt(double drawSize) {
        return new MobDef(key, displayName, body, accent, drawSize, hitbox(), speed,
                maxHealth, damage, temperament, detectRange, attackRange, flying,
                projectile, ability, abilityArg, weapon);
    }

    /** This species again, standing on {@code footprint} world pixels of floor. */
    public MobDef standingOn(double footprint) {
        return new MobDef(key, displayName, body, accent, size, footprint, speed,
                maxHealth, damage, temperament, detectRange, attackRange, flying,
                projectile, ability, abilityArg, weapon);
    }

    /** Pre-hitbox constructor shape: drawn and collided at one size. */
    public MobDef(String key, String displayName, Color body, Color accent,
                  double size, double speed, double maxHealth, double damage,
                  Temperament temperament, double detectRange,
                  double attackRange, boolean flying,
                  String projectile, Ability ability, String abilityArg,
                  String weapon) {
        this(key, displayName, body, accent, size, 0, speed, maxHealth, damage,
                temperament, detectRange, attackRange, flying,
                projectile, ability, abilityArg, weapon);
    }

    /** Pre-weapon constructor shape, kept so existing registrations read the same. */
    public MobDef(String key, String displayName, Color body, Color accent,
                  double size, double speed, double maxHealth, double damage,
                  Temperament temperament, double detectRange,
                  double attackRange, boolean flying,
                  String projectile, Ability ability, String abilityArg) {
        this(key, displayName, body, accent, size, speed, maxHealth, damage,
                temperament, detectRange, attackRange, flying,
                projectile, ability, abilityArg, null);
    }

    /** Pre-ability constructor shape, kept so existing registrations read the same. */
    public MobDef(String key, String displayName, Color body, Color accent,
                  double size, double speed, double maxHealth, double damage,
                  Temperament temperament, double detectRange,
                  double attackRange, boolean flying) {
        this(key, displayName, body, accent, size, speed, maxHealth, damage,
                temperament, detectRange, attackRange, flying,
                null, Ability.NONE, null, null);
    }

    /** This species fights at range (fires {@link #projectile} while attacking). */
    public boolean ranged() {
        return projectile != null;
    }

    /** Whether this species carries a weapon rather than fighting bare. */
    public boolean armed() {
        return weapon != null && !weapon.isBlank();
    }

    /** This species again, issued {@code itemKey} to fight with. */
    public MobDef armedWith(String itemKey) {
        return new MobDef(key, displayName, body, accent, size, hitboxSize, speed,
                maxHealth, damage, temperament, detectRange, attackRange, flying,
                projectile, ability, abilityArg, itemKey);
    }

    public static MobDef hostile(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp, double dmg) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.HOSTILE, 220, 34, false);
    }

    public static MobDef passive(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp) {
        return new MobDef(key, name, body, accent, size, speed, hp, 0,
                Temperament.PASSIVE, 160, 0, false);
    }

    public static MobDef neutral(String key, String name, Color body, Color accent,
                                 double size, double speed, double hp, double dmg) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.NEUTRAL, 180, 30, false);
    }

    /**
     * A ranged hostile: keeps a {@code range}-px firing distance and looses
     * {@code projectile} at players instead of swinging.
     */
    public static MobDef ranged(String key, String name, Color body, Color accent,
                                double size, double speed, double hp, double dmg,
                                String projectile, double range) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.HOSTILE, Math.max(260, range * 1.4), range, false,
                projectile, Ability.NONE, null);
    }

    /** A flying ranged hostile (imps, banshees, dragons). */
    public static MobDef flyingRanged(String key, String name, Color body, Color accent,
                                      double size, double speed, double hp, double dmg,
                                      String projectile, double range) {
        return new MobDef(key, name, body, accent, size, speed, hp, dmg,
                Temperament.HOSTILE, Math.max(260, range * 1.4), range, true,
                projectile, Ability.NONE, null);
    }
}
