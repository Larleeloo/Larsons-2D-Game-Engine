package com.larsons.engine.entity;

import com.larsons.engine.entity.ProjectileDef.Element;

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
 * becomes), from {@link MobDef#projectile()} (what a ranged mob looses at
 * players), and stored on the wire in snapshots.
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
        // Heavy stone-golem boulder: slow, hard-hitting, drops mineable stone.
        r.register(ProjectileDef.physical("boulder", "Boulder",
                new Color(120, 115, 110), 380, 14, 0.9, "stone"));
        // Treant thorn: a fast dart of hardened wood.
        r.register(ProjectileDef.physical("thorn", "Thorn",
                new Color(110, 140, 70), 560, 7, 0.25, "stick"));
        // Magic flies straight, glows (feeds the lighting pass), and leaves a trail.
        r.register(ProjectileDef.magic("magic_bolt", "Magic Bolt",
                new Color(140, 150, 255), 540, 8, 1.8, new Color(110, 120, 255)));
        // Fireball: magic + an explosion; the glow is what bloom loves at night.
        r.register(new ProjectileDef("fireball", "Fireball",
                new Color(255, 140, 40), 440, 14, 0, 7, 3.0, 56,
                2.5, new Color(255, 170, 70), new Color(255, 120, 30), null,
                Element.FIRE, 0));

        // --- elemental bolts: each applies its school's status on hit ------------
        // Fire imps and the Ember Wand spit these: small, quick, they burn.
        r.register(ProjectileDef.elemental("ember_bolt", "Ember Bolt",
                new Color(255, 120, 50), 500, 6, Element.FIRE, new Color(255, 150, 60)));
        // Ice witches: a chilling sliver that slows whatever it sticks.
        r.register(ProjectileDef.elemental("ice_shard", "Ice Shard",
                new Color(150, 220, 255), 560, 7, Element.ICE, new Color(190, 235, 255)));
        // The Frost Staff's heavier orb: slower, colder, harder.
        r.register(ProjectileDef.elemental("frost_orb", "Frost Orb",
                new Color(110, 200, 250), 420, 12, Element.ICE, new Color(170, 225, 255)));
        // Storm callers + the Storm Staff: near-instant, and it chains.
        r.register(ProjectileDef.elemental("lightning_bolt", "Lightning Bolt",
                new Color(255, 245, 140), 900, 10, Element.LIGHTNING, new Color(255, 250, 190)));
        // Venom spitters: an arcing glob that sickens on a hit.
        r.register(new ProjectileDef("poison_glob", "Poison Glob",
                new Color(140, 200, 60), 430, 6, 0.5, 6, 3.0, 0,
                1.5, new Color(150, 210, 80), new Color(120, 180, 60), null,
                Element.POISON, 0));
        // Wraiths and banshees: a wail of shadow.
        r.register(ProjectileDef.elemental("shadow_bolt", "Shadow Bolt",
                new Color(120, 80, 170), 520, 9, Element.ARCANE, new Color(100, 60, 140)));
        // Void stalkers + the Void Staff: hungry nothing.
        r.register(ProjectileDef.elemental("void_bolt", "Void Bolt",
                new Color(90, 40, 140), 620, 13, Element.VOID, new Color(60, 20, 100)));
        // The Warp Staff's bolt: harmless-ish, but its impact point becomes the
        // caster's next footstep (owner-teleport, resolved by the World).
        r.register(ProjectileDef.elemental("warp_bolt", "Warp Bolt",
                new Color(190, 130, 255), 760, 2, Element.VOID, new Color(210, 160, 255)));
        // Spider queens: webbing that saps the stamina out of a sprinter.
        r.register(new ProjectileDef("web_shot", "Web",
                new Color(235, 235, 225), 460, 2, 0.2, 6, 2.5, 0,
                0, null, new Color(240, 240, 230), null, Element.ICE, 0));

        // --- area & terrain -------------------------------------------------------
        // Thrown bombs: a blast that hurts and a crater that mines itself.
        r.register(ProjectileDef.bomb("bomb", "Bomb",
                new Color(60, 60, 66), 420, 18, 64, 44));
        r.register(ProjectileDef.bomb("mega_bomb", "Mega Bomb",
                new Color(160, 50, 50), 380, 34, 110, 84));
        // The Meteor Staff calls these down from above the aim point.
        r.register(new ProjectileDef("meteor", "Meteor",
                new Color(255, 110, 30), 520, 20, 0.25, 9, 5.0, 80,
                3.0, new Color(255, 150, 60), new Color(255, 120, 40), null,
                Element.FIRE, 40));
        // The Harvest Staff's gentle orb: shatters terrain into drops, hurts no one.
        r.register(new ProjectileDef("harvest_orb", "Harvest Orb",
                new Color(180, 230, 120), 480, 0, 0, 6, 2.5, 0,
                2.0, new Color(190, 235, 130), new Color(160, 215, 110), null,
                Element.EARTH, 58));
        return r;
    }
}
