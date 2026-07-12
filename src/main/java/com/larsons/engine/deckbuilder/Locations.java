package com.larsons.engine.deckbuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The eight board locations of Council of Six — two per icon family, so every
 * icon always has somewhere to go but contested spots still run out (the
 * worker-placement squeeze). Registration order is board order.
 */
public final class Locations {

    private static final Map<String, LocationDef> ALL = new LinkedHashMap<>();

    private Locations() {}

    static {
        // key, name, icon, costGold, costLore, +gold, +lore, +might, +draw, +troops, +vp, bazaar
        register(new LocationDef("the_mines", "The Mines", LocationIcon.ECONOMY,
                0, 0, 2, 0, 0, 0, 0, 0, false));
        register(new LocationDef("grand_bazaar", "Grand Bazaar", LocationIcon.ECONOMY,
                0, 0, 1, 0, 0, 0, 0, 0, true));
        register(new LocationDef("library", "Library of Whispers", LocationIcon.KNOWLEDGE,
                0, 0, 0, 1, 0, 1, 0, 0, false));
        register(new LocationDef("alchemists_tower", "Alchemist's Tower", LocationIcon.KNOWLEDGE,
                1, 0, 0, 2, 0, 0, 0, 0, false));
        register(new LocationDef("war_camp", "War Camp", LocationIcon.MILITARY,
                0, 0, 0, 0, 1, 0, 1, 0, false));
        register(new LocationDef("mercenary_hall", "Mercenary Hall", LocationIcon.MILITARY,
                2, 0, 0, 0, 2, 0, 1, 0, false));
        register(new LocationDef("high_council", "High Council", LocationIcon.COUNCIL,
                0, 2, 0, 0, 0, 0, 0, 1, false));
        register(new LocationDef("crossroads", "The Crossroads", LocationIcon.COUNCIL,
                0, 0, 1, 1, 0, 0, 0, 0, false));
    }

    private static void register(LocationDef def) {
        ALL.put(def.key, def);
    }

    /** Look a location up by key; {@code null} when unknown. */
    public static LocationDef get(String key) {
        return key == null ? null : ALL.get(key);
    }

    /** Every location in board order. */
    public static List<LocationDef> all() {
        return List.copyOf(ALL.values());
    }
}
