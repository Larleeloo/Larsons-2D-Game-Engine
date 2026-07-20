package com.larsons.engine.entity;

import java.awt.Color;

/**
 * One projectile definition. Ported from the Side-Scroller engine's
 * {@code ProjectileEntity.ProjectileType} enum (arrows, bolts, fireballs,
 * thrown rocks…), collapsed into a data row registered with
 * {@link ProjectileRegistry} — the same enum-to-registry move blocks, mobs,
 * and items already went through, so games add projectile kinds by
 * registering data instead of editing the engine.
 *
 * @param key             stable string key ("arrow"), stored on the wire
 * @param name            display name
 * @param color           sprite/trail tint (procedural sprites; no assets)
 * @param speed           muzzle speed, world px/sec
 * @param damage          default damage when the firing item has none
 * @param gravityFactor   fraction of world gravity applied in side-scroll
 *                        (0 = flies straight: magic; ~0.35 = arrow arc;
 *                        ~0.8 = a lobbed rock). Ignored without gravity.
 * @param radius          collision radius, world px
 * @param lifetime        seconds before it despawns mid-air
 * @param explosionRadius area damage radius on impact, world px (0 = none)
 * @param lightRadius     glow radius in <em>tiles</em> fed to the lighting
 *                        pass (0 = no glow) — matches {@code Block.lightRadius}
 * @param lightColor      glow colour (used when {@code lightRadius > 0})
 * @param trail           spark-trail colour, or {@code null} for no trail
 * @param dropItem        item key dropped where a physical projectile lands
 *                        so it can be recovered ({@code null} for magic)
 * @param element         elemental school: shapes impact particles and applies
 *                        a status on hit (burn / chill / poison / chained
 *                        lightning — see {@code World}); {@link Element#NONE}
 *                        for plain shots
 * @param breakRadius     terrain-shattering radius on impact, world px
 *                        (0 = none). Broken blocks pop their drops and, online,
 *                        broadcast as authoritative block events — this is what
 *                        bombs and harvest orbs are made of. Honours the game
 *                        type's block-editing toggle.
 */
public record ProjectileDef(String key, String name, Color color, double speed,
                            double damage, double gravityFactor, double radius,
                            double lifetime, double explosionRadius,
                            double lightRadius, Color lightColor, Color trail,
                            String dropItem, Element element, double breakRadius) {

    /**
     * Elemental schools. Each maps to a particle style on impact and a combat
     * side effect resolved by the {@code World}: FIRE burns, ICE chills,
     * LIGHTNING chains, POISON sickens, ARCANE and VOID are pure magic (VOID
     * additionally powers warp effects like the Warp Staff's owner-teleport),
     * EARTH is the school of terrain-shattering blasts.
     */
    public enum Element { NONE, FIRE, ICE, LIGHTNING, POISON, ARCANE, VOID, EARTH }

    /** Pre-element constructor shape, kept so existing registrations read the same. */
    public ProjectileDef(String key, String name, Color color, double speed,
                         double damage, double gravityFactor, double radius,
                         double lifetime, double explosionRadius,
                         double lightRadius, Color lightColor, Color trail,
                         String dropItem) {
        this(key, name, color, speed, damage, gravityFactor, radius, lifetime,
                explosionRadius, lightRadius, lightColor, trail, dropItem,
                Element.NONE, 0);
    }

    /** True when this projectile glows and should feed the lighting pass. */
    public boolean glows() {
        return lightRadius > 0;
    }

    public static ProjectileDef physical(String key, String name, Color color,
                                         double speed, double damage,
                                         double gravityFactor, String dropItem) {
        return new ProjectileDef(key, name, color, speed, damage, gravityFactor,
                4, 3.0, 0, 0, null, null, dropItem);
    }

    public static ProjectileDef magic(String key, String name, Color color,
                                      double speed, double damage,
                                      double lightRadius, Color trail) {
        return new ProjectileDef(key, name, color, speed, damage, 0,
                5, 2.5, 0, lightRadius, color, trail, null);
    }

    /** A straight-flying elemental bolt: glows, trails, applies its status on hit. */
    public static ProjectileDef elemental(String key, String name, Color color,
                                          double speed, double damage,
                                          Element element, Color trail) {
        return new ProjectileDef(key, name, color, speed, damage, 0,
                5, 2.5, 0, 2.0, color, trail, null, element, 0);
    }

    /** An arcing bomb: explodes for area damage and shatters terrain around it. */
    public static ProjectileDef bomb(String key, String name, Color color,
                                     double speed, double damage,
                                     double explosionRadius, double breakRadius) {
        return new ProjectileDef(key, name, color, speed, damage, 0.7,
                6, 4.0, explosionRadius, 1.5, new Color(255, 200, 120),
                new Color(255, 170, 80), null, Element.EARTH, breakRadius);
    }
}
