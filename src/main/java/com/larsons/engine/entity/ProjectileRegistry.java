package com.larsons.engine.entity;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog of projectile kinds a game knows about. Ported from the
 * Side-Scroller engine's {@code ProjectileEntity} type table; the original's
 * hard-coded enum cases become data rows here, referenced by key from
 * {@link ItemDef#projectile()} (what a ranged weapon fires / a throwable
 * becomes) and stored on the wire in snapshots.
 */
public final class ProjectileRegistry {

    private final Map<String, ProjectileDef> byKey = new LinkedHashMap<>();

    private static final ProjectileRegistry STANDARD = createStandard();

    public static ProjectileRegistry standard() {
        return STANDARD;
    }

    public void register(ProjectileDef def) {
        if (byKey.containsKey(def.key())) {
            throw new IllegalArgumentException("Duplicate projectile key " + def.key());
        }
        byKey.put(def.key(), def);
    }

    /** The projectile with this key, or {@code null}. */
    public ProjectileDef get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase().trim());
    }

    /** All projectiles in registration order. */
    public List<ProjectileDef> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }

    private static ProjectileRegistry createStandard() {
        ProjectileRegistry r = new ProjectileRegistry();
        // Physical projectiles arc under gravity and land as recoverable items.
        r.register(ProjectileDef.physical("arrow", "Arrow",
                new Color(200, 190, 170), 640, 6, 0.35, "arrow"));
        r.register(ProjectileDef.physical("rock", "Rock",
                new Color(140, 135, 130), 480, 5, 0.8, "rock"));
        r.register(ProjectileDef.physical("knife", "Throwing Knife",
                new Color(190, 195, 205), 620, 8, 0.45, "throwing_knife"));
        // Magic flies straight, glows (feeds the lighting pass), and leaves a trail.
        r.register(ProjectileDef.magic("magic_bolt", "Magic Bolt",
                new Color(140, 150, 255), 540, 8, 1.8, new Color(110, 120, 255)));
        // Fireball: magic + an explosion; the glow is what bloom loves at night.
        r.register(new ProjectileDef("fireball", "Fireball",
                new Color(255, 140, 40), 440, 14, 0, 7, 3.0, 56,
                2.5, new Color(255, 170, 70), new Color(255, 120, 30), null));
        return r;
    }
}
