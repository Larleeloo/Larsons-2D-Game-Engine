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

    /** Remove a species (deleting user-created custom content). */
    public void unregister(String key) {
        byKey.remove(key);
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

        // --- ranged marksmen: fire projectiles from their long attack range ---
        r.register(MobDef.ranged("skeleton_archer", "Skeleton Archer",
                new Color(235, 235, 220), new Color(120, 90, 50), 30, 75, 28, 6, "arrow", 200));
        r.register(MobDef.ranged("goblin_slinger", "Goblin Slinger",
                new Color(120, 170, 75), new Color(150, 140, 130), 24, 100, 20, 5, "rock", 170));
        r.register(MobDef.ranged("dark_ranger", "Dark Ranger",
                new Color(70, 60, 80), new Color(180, 60, 200), 30, 110, 45, 8, "knife", 220));

        // --- elemental casters: bolts that burn, chill, chain, and sicken ---
        r.register(MobDef.flyingRanged("fire_imp", "Fire Imp",
                new Color(230, 90, 40), new Color(255, 200, 80), 20, 120, 18, 5, "ember_bolt", 160));
        r.register(MobDef.ranged("pyromancer", "Pyromancer",
                new Color(200, 70, 40), new Color(255, 170, 60), 30, 70, 40, 12, "fireball", 210));
        r.register(MobDef.ranged("ice_witch", "Ice Witch",
                new Color(140, 190, 230), new Color(240, 250, 255), 30, 70, 35, 8, "ice_shard", 200));
        r.register(MobDef.ranged("storm_caller", "Storm Caller",
                new Color(90, 100, 140), new Color(255, 245, 150), 32, 65, 45, 10, "lightning_bolt", 240));
        r.register(MobDef.ranged("venom_spitter", "Venom Spitter",
                new Color(110, 160, 60), new Color(180, 220, 90), 26, 90, 26, 6, "poison_glob", 170));
        r.register(MobDef.flyingRanged("banshee", "Banshee",
                new Color(200, 210, 230), new Color(120, 80, 170), 30, 105, 32, 8, "shadow_bolt", 190));
        r.register(MobDef.flyingRanged("ancient_dragon", "Ancient Dragon",
                new Color(120, 40, 50), new Color(255, 190, 70), 72, 90, 400, 26, "fireball", 260));

        // --- ability specialists: each species has its own trick ---
        r.register(new MobDef("shadow_panther", "Shadow Panther",
                new Color(45, 40, 55), new Color(180, 220, 90), 30, 150, 40, 10,
                MobDef.Temperament.HOSTILE, 260, 32, false,
                null, MobDef.Ability.LEAP, null));
        r.register(new MobDef("wild_boar", "Wild Boar",
                new Color(95, 70, 55), new Color(220, 200, 180), 32, 110, 45, 9,
                MobDef.Temperament.NEUTRAL, 200, 30, false,
                null, MobDef.Ability.CHARGE, null));
        r.register(new MobDef("sand_scorpion", "Sand Scorpion",
                new Color(200, 170, 110), new Color(120, 90, 50), 28, 95, 30, 7,
                MobDef.Temperament.HOSTILE, 210, 30, false,
                "poison_glob", MobDef.Ability.CHARGE, null));
        r.register(new MobDef("shadow_wraith", "Shadow Wraith",
                new Color(60, 50, 90), new Color(200, 220, 255), 28, 90, 30, 9,
                MobDef.Temperament.HOSTILE, 260, 34, true,
                null, MobDef.Ability.TELEPORT, null));
        r.register(new MobDef("frost_revenant", "Frost Revenant",
                new Color(120, 170, 210), new Color(230, 245, 255), 32, 85, 55, 10,
                MobDef.Temperament.HOSTILE, 250, 190, false,
                "ice_shard", MobDef.Ability.TELEPORT, null));
        r.register(new MobDef("void_stalker", "Void Stalker",
                new Color(50, 25, 80), new Color(190, 130, 255), 34, 115, 90, 14,
                MobDef.Temperament.HOSTILE, 300, 210, false,
                "void_bolt", MobDef.Ability.TELEPORT, null));
        r.register(new MobDef("necromancer", "Necromancer",
                new Color(70, 80, 60), new Color(160, 220, 160), 32, 60, 55, 9,
                MobDef.Temperament.HOSTILE, 260, 200, false,
                "shadow_bolt", MobDef.Ability.SUMMON, "zombie"));
        r.register(new MobDef("spider_queen", "Spider Queen",
                new Color(40, 35, 45), new Color(220, 60, 60), 44, 90, 90, 10,
                MobDef.Temperament.HOSTILE, 260, 180, false,
                "web_shot", MobDef.Ability.SUMMON, "spider"));
        r.register(new MobDef("giant_slime", "Giant Slime",
                new Color(70, 180, 110), new Color(45, 130, 80), 44, 40, 60, 8,
                MobDef.Temperament.HOSTILE, 200, 34, false,
                null, MobDef.Ability.SPLIT, "slime"));
        r.register(new MobDef("boomshroom", "Boomshroom",
                new Color(220, 90, 80), new Color(245, 230, 200), 24, 55, 18, 4,
                MobDef.Temperament.HOSTILE, 190, 24, false,
                null, MobDef.Ability.DEATH_BURST, "fireball"));
        r.register(new MobDef("troll", "Troll",
                new Color(100, 130, 90), new Color(70, 90, 60), 46, 60, 120, 16,
                MobDef.Temperament.HOSTILE, 220, 40, false,
                null, MobDef.Ability.REGEN, null));
        r.register(new MobDef("treant", "Treant",
                new Color(90, 110, 60), new Color(60, 80, 45), 50, 45, 140, 12,
                MobDef.Temperament.NEUTRAL, 220, 180, false,
                "thorn", MobDef.Ability.REGEN, null));
        r.register(new MobDef("vampire", "Vampire",
                new Color(90, 30, 40), new Color(230, 220, 210), 30, 120, 60, 11,
                MobDef.Temperament.HOSTILE, 250, 32, false,
                null, MobDef.Ability.LIFESTEAL, null));
        r.register(new MobDef("stone_golem", "Stone Golem",
                new Color(130, 130, 135), new Color(90, 200, 220), 48, 45, 150, 15,
                MobDef.Temperament.HOSTILE, 220, 190, false,
                "boulder", MobDef.Ability.SHIELD, null));
        r.register(new MobDef("royal_guard", "Royal Guard",
                new Color(200, 180, 90), new Color(150, 40, 40), 32, 80, 90, 13,
                MobDef.Temperament.HOSTILE, 230, 34, false,
                null, MobDef.Ability.SHIELD, null));
        r.register(new MobDef("phoenix", "Phoenix",
                new Color(255, 140, 40), new Color(255, 220, 110), 36, 130, 70, 12,
                MobDef.Temperament.NEUTRAL, 240, 170, true,
                "ember_bolt", MobDef.Ability.DEATH_BURST, "fireball"));

        // --- more wildlife & fliers: colour on the peaceful side of the world ---
        r.register(new MobDef("yeti", "Yeti",
                new Color(225, 235, 245), new Color(120, 160, 200), 44, 80, 110, 14,
                MobDef.Temperament.NEUTRAL, 220, 38, false,
                null, MobDef.Ability.NONE, null));
        r.register(new MobDef("harpy", "Harpy",
                new Color(170, 130, 90), new Color(230, 200, 150), 28, 150, 35, 8,
                MobDef.Temperament.HOSTILE, 260, 28, true,
                null, MobDef.Ability.NONE, null));
        r.register(new MobDef("griffin", "Griffin",
                new Color(200, 170, 110), new Color(240, 235, 220), 40, 140, 80, 12,
                MobDef.Temperament.NEUTRAL, 240, 34, true,
                null, MobDef.Ability.NONE, null));
        r.register(new MobDef("ember_wisp", "Ember Wisp",
                new Color(255, 170, 80), new Color(255, 230, 150), 14, 100, 8, 2,
                MobDef.Temperament.PASSIVE, 160, 0, true,
                null, MobDef.Ability.NONE, null));
        r.register(new MobDef("plague_rat", "Plague Rat",
                new Color(110, 100, 95), new Color(150, 200, 80), 18, 130, 12, 4,
                MobDef.Temperament.HOSTILE, 200, 22, false,
                null, MobDef.Ability.NONE, null));
        r.register(MobDef.passive("turtle", "Turtle",
                new Color(90, 140, 100), new Color(150, 120, 80), 22, 35, 30));
        r.register(MobDef.passive("penguin", "Penguin",
                new Color(40, 45, 60), new Color(240, 240, 235), 22, 80, 14));
        r.register(new MobDef("firefly", "Firefly",
                new Color(120, 140, 60), new Color(240, 255, 140), 10, 90, 4, 0,
                MobDef.Temperament.PASSIVE, 160, 0, true,
                null, MobDef.Ability.NONE, null));
        return r;
    }
}
