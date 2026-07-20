package com.larsons.engine.entity;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rideable vehicles and mounts a game knows about, keyed like every other
 * registry. Each standard vehicle pairs with the item that spawns it (see
 * {@link VehicleDef#sourceItem()}), so rides are obtained, carried, deployed,
 * and packed back up through the ordinary item economy — which is also what
 * makes them work online without any creative-palette changes.
 */
public final class VehicleRegistry {

    private final Map<String, VehicleDef> byKey = new LinkedHashMap<>();

    private static final VehicleRegistry STANDARD = createStandard();

    public static VehicleRegistry standard() {
        return STANDARD;
    }

    public void register(VehicleDef def) {
        if (byKey.containsKey(def.key())) {
            throw new IllegalArgumentException("Duplicate vehicle key " + def.key());
        }
        byKey.put(def.key(), def);
    }

    /** The vehicle with this key, or {@code null}. */
    public VehicleDef get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** The vehicle a given item deploys, or {@code null} (not a vehicle item). */
    public VehicleDef bySourceItem(String itemKey) {
        if (itemKey == null) return null;
        for (VehicleDef d : byKey.values()) {
            if (itemKey.equals(d.sourceItem())) return d;
        }
        return null;
    }

    /** All vehicles in registration order. */
    public List<VehicleDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    private static VehicleRegistry createStandard() {
        VehicleRegistry r = new VehicleRegistry();
        // Ground mounts: fast legs and a real jump.
        r.register(new VehicleDef("horse", "Horse",
                new Color(140, 100, 65), new Color(80, 55, 35), 44, 420, 640,
                VehicleDef.Kind.GROUND, null, 0, "horse_saddle"));
        r.register(new VehicleDef("battle_boar", "Battle Boar",
                new Color(100, 75, 60), new Color(230, 210, 190), 40, 340, 560,
                VehicleDef.Kind.GROUND, null, 14, "boar_saddle"));
        r.register(new VehicleDef("ostrich", "Ostrich",
                new Color(90, 85, 80), new Color(235, 225, 210), 38, 470, 760,
                VehicleDef.Kind.GROUND, null, 0, "ostrich_saddle"));
        // Fliers: free vertical steering, no gravity while ridden.
        r.register(new VehicleDef("magic_carpet", "Magic Carpet",
                new Color(170, 60, 90), new Color(240, 200, 90), 40, 300, 0,
                VehicleDef.Kind.FLYING, null, 0, "magic_carpet"));
        r.register(new VehicleDef("broom", "Broomstick",
                new Color(120, 85, 50), new Color(220, 190, 120), 36, 400, 0,
                VehicleDef.Kind.FLYING, null, 0, "broomstick"));
        r.register(new VehicleDef("war_dragon", "War Dragon",
                new Color(60, 120, 90), new Color(240, 200, 90), 60, 360, 0,
                VehicleDef.Kind.FLYING, "fireball", 10, "dragon_horn"));
        // Water craft.
        r.register(new VehicleDef("boat", "Boat",
                new Color(150, 110, 70), new Color(230, 225, 210), 42, 380, 0,
                VehicleDef.Kind.BOAT, null, 0, "oak_boat"));
        // The tunneler: drives through terrain, leaving mined drops behind.
        r.register(new VehicleDef("drill_machine", "Drill Machine",
                new Color(180, 150, 60), new Color(120, 120, 130), 42, 200, 480,
                VehicleDef.Kind.DRILL, null, 6, "drill_kit"));
        return r;
    }
}
