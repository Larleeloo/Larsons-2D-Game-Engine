package com.larsons.engine.world;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of {@link Decor} definitions the creative palette offers — same
 * data-driven pattern as {@link BlockRegistry}: games extend the scenery by
 * registering rows, and {@link #standard()} ships a spread of trees, rocks,
 * plants, and cave growths that the level generator also draws from.
 */
public final class DecorRegistry {

    private final Map<String, Decor> byKey = new LinkedHashMap<>();

    private static final DecorRegistry STANDARD = createStandard();

    public static DecorRegistry standard() {
        return STANDARD;
    }

    public void register(Decor d) {
        if (byKey.containsKey(d.key())) {
            throw new IllegalArgumentException("Duplicate decor key " + d.key());
        }
        byKey.put(d.key(), d);
    }

    /** The decoration with this key, or {@code null}. */
    public Decor get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All decorations, in registration order (drives the creative palette). */
    public List<Decor> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    /** Remove a decoration (deleting user-created custom content). */
    public void unregister(String key) {
        byKey.remove(key);
    }

    private static DecorRegistry createStandard() {
        DecorRegistry r = new DecorRegistry();
        // Trees.
        r.register(new Decor("oak_tree", "Oak Tree", Decor.Shape.TREE,
                new Color(80, 145, 70), new Color(115, 85, 50), 4.5));
        r.register(new Decor("pine_tree", "Pine Tree", Decor.Shape.PINE,
                new Color(55, 110, 75), new Color(95, 70, 45), 5.0));
        r.register(new Decor("autumn_tree", "Autumn Tree", Decor.Shape.TREE,
                new Color(210, 130, 55), new Color(110, 80, 50), 4.2));
        r.register(new Decor("blossom_tree", "Blossom Tree", Decor.Shape.TREE,
                new Color(235, 170, 200), new Color(120, 90, 60), 4.2));
        r.register(new Decor("dead_tree", "Dead Tree", Decor.Shape.DEAD_TREE,
                new Color(120, 100, 80), new Color(90, 75, 60), 4.0));
        // Rocks.
        r.register(new Decor("boulder", "Boulder", Decor.Shape.ROCK,
                new Color(130, 128, 125), new Color(95, 93, 90), 1.6));
        r.register(new Decor("mossy_boulder", "Mossy Boulder", Decor.Shape.ROCK,
                new Color(120, 125, 110), new Color(85, 125, 80), 1.6));
        r.register(new Decor("stones", "Stones", Decor.Shape.STONES,
                new Color(145, 140, 135), new Color(110, 105, 100), 0.9));
        // Plants.
        r.register(new Decor("bush", "Bush", Decor.Shape.BUSH,
                new Color(75, 135, 65), new Color(55, 105, 50), 1.4));
        r.register(new Decor("berry_bush", "Berry Bush", Decor.Shape.BUSH,
                new Color(70, 125, 60), new Color(200, 70, 90), 1.4));
        r.register(new Decor("cactus_plant", "Cactus", Decor.Shape.CACTUS,
                new Color(95, 160, 85), new Color(70, 125, 65), 2.2));
        r.register(new Decor("fallen_log", "Fallen Log", Decor.Shape.LOG,
                new Color(125, 95, 60), new Color(95, 70, 45), 1.0));
        // Cave growths.
        r.register(new Decor("stalagmite", "Stalagmite", Decor.Shape.STALAGMITE,
                new Color(120, 115, 125), new Color(90, 85, 95), 1.8));
        r.register(new Decor("glow_crystal", "Glow Crystal", Decor.Shape.CRYSTAL,
                new Color(120, 220, 235), new Color(180, 245, 255), 1.6));
        r.register(new Decor("amethyst_spikes", "Amethyst Spikes", Decor.Shape.CRYSTAL,
                new Color(170, 120, 230), new Color(210, 170, 255), 1.6));
        r.register(new Decor("cave_mushrooms", "Cave Mushrooms", Decor.Shape.MUSHROOM,
                new Color(200, 140, 110), new Color(235, 225, 210), 1.1));
        return r;
    }
}
