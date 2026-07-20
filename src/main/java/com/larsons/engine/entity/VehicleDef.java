package com.larsons.engine.entity;

import java.awt.Color;

/**
 * One vehicle/mount species definition — data rows like {@link MobDef}, so
 * games add rideables by registering data (see {@link VehicleRegistry}).
 * Sprites are procedural (body/accent colours + the kind's silhouette).
 *
 * <p>Vehicles are world entities the simulation owns: spawned by using their
 * matching item (a saddle, a boat, a drill kit…), mounted with [E], driven
 * with the normal movement keys, and — online — simulated by the
 * authoritative server and replicated in snapshots exactly like mobs, so a
 * gallop or a dragon strafe looks identical on every client.
 *
 * @param key           stable string key ("horse"), stored on the wire
 * @param name          display name ("[E] Ride Horse")
 * @param body          main sprite colour
 * @param accent        secondary sprite colour (mane/sail/rotor)
 * @param size          world-pixel size (square)
 * @param speed         top speed while ridden, px/sec
 * @param jump          jump velocity for ground mounts, px/sec
 * @param kind          movement model (see {@link Kind})
 * @param projectile    {@link ProjectileDef} key the vehicle fires when its
 *                      rider attacks (a war dragon's fireball); {@code null}
 *                      for unarmed rides. Shots cost nothing but are
 *                      cooldown-limited, and are owned by the rider so PvP
 *                      and kill-credit rules apply normally
 * @param contactDamage damage dealt to mobs rammed at speed (boar tusks,
 *                      warhorse barding); 0 = harmless collisions
 * @param sourceItem    item key this vehicle packs back into when a player
 *                      swings at the empty vehicle (and the item that spawns
 *                      it via [F] use), closing the loop so mounts are never
 *                      lost — {@code null} = not packable
 */
public record VehicleDef(String key, String name, Color body, Color accent,
                         double size, double speed, double jump, Kind kind,
                         String projectile, double contactDamage,
                         String sourceItem) {

    /** Movement model a vehicle simulates with (see {@link Vehicle#step}). */
    public enum Kind {
        /** Gallops and jumps under gravity (horse, boar). */
        GROUND,
        /** Ignores gravity; up/down steer freely (carpet, broom, dragon). */
        FLYING,
        /** Floats on liquids and is fast across them; sluggish ashore. */
        BOAT,
        /** A ground machine that grinds through terrain it drives into. */
        DRILL
    }
}
