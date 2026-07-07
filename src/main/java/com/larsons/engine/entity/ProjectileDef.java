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
 */
public record ProjectileDef(String key, String name, Color color, double speed,
                            double damage, double gravityFactor, double radius,
                            double lifetime, double explosionRadius,
                            double lightRadius, Color lightColor, Color trail,
                            String dropItem) {

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
}
