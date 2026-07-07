package com.larsons.engine.entity;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mob species a game can spawn / the creative palette can paint. Ported
 * from the Side-Scroller engine's {@code MobRegistry} (which switched over 23
 * hard-coded classes); here species are data rows, so registering a new one is
 * one {@link #register} call. {@link #standard()} carries a representative set
 * of the original roster — humanoids, quadrupeds, and specials.
 */
public final class MobRegistry {

    private final Map<String, MobDef> byKey = new LinkedHashMap<>();

    private static final MobRegistry STANDARD = createStandard();

    public static MobRegistry standard() {
        return STANDARD;
    }

    public void register(MobDef def) {
        if (byKey.containsKey(def.key())) {
            throw new IllegalArgumentException("Duplicate mob key " + def.key());
        }
        byKey.put(def.key(), def);
    }

    /** The species with this key, or {@code null}. */
    public MobDef get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All species in registration order (drives the creative palette). */
    public List<MobDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    private static MobRegistry createStandard() {
        MobRegistry r = new MobRegistry();
        // Humanoids (side-scroller: humanoid/)
        r.register(MobDef.hostile("zombie", "Zombie", new Color(90, 140, 80), new Color(200, 60, 60), 30, 60, 40, 8));
        r.register(MobDef.hostile("skeleton", "Skeleton", new Color(225, 225, 210), new Color(40, 40, 40), 30, 80, 30, 7));
        r.register(MobDef.hostile("goblin", "Goblin", new Color(110, 160, 70), new Color(240, 200, 60), 24, 110, 24, 5));
        r.register(MobDef.hostile("orc", "Orc", new Color(80, 110, 60), new Color(200, 190, 170), 34, 70, 55, 10));
        r.register(MobDef.hostile("bandit", "Bandit", new Color(120, 90, 90), new Color(60, 50, 50), 30, 100, 35, 7));
        r.register(MobDef.hostile("knight", "Knight", new Color(170, 175, 190), new Color(220, 60, 60), 32, 75, 70, 12));
        r.register(MobDef.hostile("mage", "Mage", new Color(100, 80, 180), new Color(240, 230, 140), 30, 65, 30, 11));
        // Quadrupeds (side-scroller: quadruped/)
        r.register(MobDef.neutral("wolf", "Wolf", new Color(130, 130, 140), new Color(220, 220, 230), 28, 130, 30, 6));
        r.register(MobDef.neutral("bear", "Bear", new Color(110, 80, 55), new Color(70, 50, 35), 40, 85, 80, 14));
        r.register(MobDef.passive("fox", "Fox", new Color(220, 120, 50), new Color(245, 240, 235), 24, 120, 16));
        r.register(MobDef.passive("deer", "Deer", new Color(170, 130, 90), new Color(240, 230, 210), 30, 140, 20));
        r.register(MobDef.passive("cow", "Cow", new Color(90, 70, 60), new Color(235, 235, 230), 34, 55, 26));
        r.register(MobDef.passive("pig", "Pig", new Color(235, 160, 170), new Color(200, 110, 120), 28, 60, 22));
        r.register(MobDef.passive("sheep", "Sheep", new Color(230, 228, 220), new Color(60, 55, 50), 28, 60, 20));
        r.register(MobDef.passive("rabbit", "Rabbit", new Color(200, 190, 180), new Color(240, 235, 230), 16, 150, 8));
        r.register(MobDef.passive("frog", "Frog", new Color(90, 180, 90), new Color(50, 110, 50), 14, 90, 6));
        // Specials (side-scroller: special/)
        r.register(new MobDef("slime", "Slime", new Color(90, 200, 120), new Color(60, 150, 90),
                26, 45, 20, 4, MobDef.Temperament.HOSTILE, 180, 26, false));
        r.register(new MobDef("bat", "Bat", new Color(80, 70, 100), new Color(230, 80, 80),
                18, 130, 12, 3, MobDef.Temperament.HOSTILE, 240, 22, true));
        r.register(new MobDef("spider", "Spider", new Color(50, 45, 50), new Color(200, 40, 40),
                26, 115, 25, 6, MobDef.Temperament.HOSTILE, 220, 28, false));
        r.register(new MobDef("dragon", "Dragon", new Color(180, 60, 60), new Color(240, 180, 60),
                56, 100, 200, 20, MobDef.Temperament.HOSTILE, 320, 48, true));
        return r;
    }
}
