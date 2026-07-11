package com.larsons.engine.world;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.larsons.engine.world.SurfaceDecor.Face;
import com.larsons.engine.world.SurfaceDecor.Style;

/**
 * The set of {@link SurfaceDecor} definitions the creative palette offers —
 * the same data-driven pattern as {@link BlockRegistry}/{@link DecorRegistry}:
 * games extend the set with {@link #register}, and {@link #standard()} ships
 * the built-in details (grass tufts, hanging moss, twigs, icicles…).
 */
public final class SurfaceDecorRegistry {

    private final Map<String, SurfaceDecor> byKey = new LinkedHashMap<>();

    private static final SurfaceDecorRegistry STANDARD = createStandard();

    public static SurfaceDecorRegistry standard() {
        return STANDARD;
    }

    public void register(SurfaceDecor d) {
        if (byKey.containsKey(d.key())) {
            throw new IllegalArgumentException("Duplicate surface decor key " + d.key());
        }
        byKey.put(d.key(), d);
    }

    /** The definition with this key, or {@code null}. */
    public SurfaceDecor get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All definitions, in registration order (drives the creative palette). */
    public List<SurfaceDecor> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    private static SurfaceDecorRegistry createStandard() {
        SurfaceDecorRegistry r = new SurfaceDecorRegistry();
        r.register(new SurfaceDecor("grass_tuft", "Tall Grass Tuft", Style.GRASS_TUFT,
                new Color(105, 175, 90), new Color(80, 145, 70),
                EnumSet.of(Face.UP), false));
        r.register(new SurfaceDecor("wildflowers", "Wildflowers", Style.FLOWERS,
                new Color(225, 110, 120), new Color(235, 210, 90),
                EnumSet.of(Face.UP), false));
        r.register(new SurfaceDecor("hanging_moss", "Hanging Moss", Style.HANGING_MOSS,
                new Color(95, 140, 85), new Color(70, 110, 65),
                EnumSet.of(Face.DOWN), true));
        r.register(new SurfaceDecor("icicles", "Icicles", Style.ICICLES,
                new Color(190, 220, 245), new Color(150, 195, 235),
                EnumSet.of(Face.DOWN), true));
        r.register(new SurfaceDecor("twigs", "Twigs", Style.TWIGS,
                new Color(130, 100, 60), new Color(95, 75, 45),
                EnumSet.of(Face.LEFT, Face.RIGHT), true));
        r.register(new SurfaceDecor("side_mushrooms", "Shelf Mushrooms", Style.MUSHROOMS,
                new Color(205, 150, 110), new Color(235, 225, 205),
                EnumSet.of(Face.LEFT, Face.RIGHT), true));
        r.register(new SurfaceDecor("cobweb_corner", "Cobweb", Style.COBWEB,
                new Color(230, 230, 235), new Color(200, 200, 210),
                EnumSet.allOf(Face.class), true));
        r.register(new SurfaceDecor("roots", "Roots", Style.ROOTS,
                new Color(115, 85, 55), new Color(90, 70, 45),
                EnumSet.of(Face.DOWN, Face.LEFT, Face.RIGHT), false));
        r.register(new SurfaceDecor("crystal_growth", "Crystal Growth", Style.CRYSTALS,
                new Color(130, 215, 230), new Color(185, 245, 255),
                EnumSet.allOf(Face.class), true));
        r.register(new SurfaceDecor("dripstone", "Dripping Water", Style.DRIP,
                new Color(120, 170, 220), new Color(90, 140, 200),
                EnumSet.of(Face.DOWN), true));
        return r;
    }
}
