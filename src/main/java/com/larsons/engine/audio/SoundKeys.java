package com.larsons.engine.audio;

import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.Characters;
import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every place the level-loader game makes a sound, and the file name a
 * {@link SoundPack} expects for it — the audio twin of
 * {@link com.larsons.engine.graphics.TextureKeys}.
 *
 * <p>A sound key names an <em>object</em> and an <em>action state</em>:
 *
 * <pre>
 *   player/jump            -&gt; player/jump.wav
 *   player/swim            -&gt; player/swim.wav
 *   character/rogue/hurt   -&gt; player/rogue_hurt.wav
 *   block/dirt/break       -&gt; blocks/dirt_break.wav
 *   block/water/splash     -&gt; liquids/water_splash.wav
 *   mob/slime/attack       -&gt; mobs/slime_attack.wav
 *   item/iron_sword/use    -&gt; items/iron_sword_use.wav
 *   projectile/meteor/fire -&gt; projectiles/meteor_fire.wav
 *   ultimate/meteor_volley/activate -&gt; ultimates/meteor_volley_activate.wav
 *   music/level            -&gt; music/level.mp3
 * </pre>
 *
 * <p>Keys fall back one segment at a time, exactly like texture keys, so
 * {@code mob/slime/attack} tries {@code mobs/slime_attack} and then
 * {@code mobs/slime}: one file gives a mob every sound it makes, and a
 * creator who wants a distinct death cry just adds another file.
 *
 * <p>{@link #all()} reads the <em>live</em> registries, so blocks, mobs,
 * items, decorations and characters made with the palette's "+ New …"
 * buttons appear in the catalogue — and in the generated
 * {@code SOUND_KEYS.txt} — alongside the built-ins, with the same action
 * states as everything else of their kind.
 *
 * <p>Every one of these defaults to <b>silence</b>. A handful of keys — the
 * ones the engine had synthesized effects for before sound assets existed —
 * keep a built-in voice as their fallback (see {@link SoundSynth}); the rest
 * make no sound at all until a WAV or MP3 with the right name is dropped
 * into the pack.
 */
public final class SoundKeys {

    // Folder names inside the pack, one per family of sounds.
    public static final String PLAYER = "player";
    public static final String BLOCKS = "blocks";
    public static final String LIQUIDS = "liquids";
    public static final String LIGHTS = "lights";
    public static final String MOBS = "mobs";
    public static final String ITEMS = "items";
    public static final String DECOR = "decor";
    public static final String BLOCK_DECOR = "block_decor";
    public static final String PROJECTILES = "projectiles";
    public static final String ULTIMATES = "ultimates";
    public static final String VEHICLES = "vehicles";
    public static final String PARTICLES = "particles";
    public static final String MUSIC = "music";
    public static final String UI = "ui";
    public static final String WORLD = "world";
    public static final String AMBIENT = "ambient";
    public static final String DOORS = "doors";
    public static final String CUTSCENES = "cutscenes";
    public static final String MINIGAME = "minigame";
    /** Where a key from an unrecognised namespace lands. */
    public static final String OTHER = "other";

    /**
     * The player's action states — everything a character does that can be
     * heard. Footsteps ({@code walk} / {@code run} / {@code swim}) and the
     * sustained ultimate ({@code ult_loop}) repeat while the state holds; the
     * rest fire once as the action happens.
     */
    public static final List<String> PLAYER_STATES = List.of(
            "spawn", "walk", "run", "jump", "double_jump", "land", "fall",
            "swim", "swim_enter", "swim_exit", "hurt", "die", "respawn",
            "attack", "attack_hit", "shoot", "mine", "mine_break", "chop",
            "place", "pickup", "drop", "eat", "drink", "heal", "craft",
            "open_inventory", "close_inventory", "sprint_start",
            "stamina_empty", "mana_empty", "ult_charged", "ult_activate",
            "ult_loop", "ult_end", "mount", "dismount", "door_enter",
            "teleport", "perspective_switch");

    /** What a solid block can be heard doing. */
    public static final List<String> BLOCK_STATES =
            List.of("place", "break", "mine", "step", "hit");

    /** Liquids trade mining for splashing, swimming, and a lapping ambience. */
    public static final List<String> LIQUID_STATES =
            List.of("place", "break", "splash", "swim", "flow", "ambient");

    /** Light-emitting blocks hum or crackle as well as being built with. */
    public static final List<String> LIGHT_STATES =
            List.of("place", "break", "mine", "step", "hit", "ambient");

    /** A mob's life cycle, from spawning to dying. */
    public static final List<String> MOB_STATES =
            List.of("spawn", "idle", "step", "attack", "hurt", "death");

    /** What holding, swinging or consuming an item sounds like. */
    public static final List<String> ITEM_STATES =
            List.of("use", "equip", "pickup", "drop", "craft");

    /** A shot's life: leaving the weapon, in flight, landing, detonating. */
    public static final List<String> PROJECTILE_STATES =
            List.of("fire", "flight", "impact", "explode");

    /**
     * An ultimate's life: the meter filling, the cast, the sustained body of
     * the effect, what it does where it lands, and the effect ending. This is
     * what gives a Meteor Volley a cast, a falling roar and a crash.
     */
    public static final List<String> ULTIMATE_STATES =
            List.of("charged", "activate", "loop", "impact", "end");

    /** Trees and rocks: chopped at, felled, or rustling where they stand. */
    public static final List<String> DECOR_STATES = List.of("hit", "break", "ambient");

    /** Block details are walked over and knocked off. */
    public static final List<String> SURFACE_STATES = List.of("step", "break");

    /** Vehicles: getting in, running, working, and being wrecked. */
    public static final List<String> VEHICLE_STATES =
            List.of("mount", "dismount", "engine", "drill", "horn", "break");

    /** Particle effects make one noise: the burst itself. */
    public static final List<String> PARTICLE_STATES = List.of("burst");

    /** The music tracks a level-loader game switches between. */
    public static final List<String> MUSIC_TRACKS = List.of(
            "menu", "creative", "level", "combat", "boss", "victory",
            "defeat", "credits");

    /** Menu and HUD feedback. */
    public static final List<String> UI_SOUNDS = List.of(
            "click", "select", "back", "open", "close", "error", "hover",
            "tab", "type", "paint", "erase");

    /** Things the world itself does, rather than any one object in it. */
    public static final List<String> WORLD_SOUNDS = List.of(
            "level_load", "level_save", "level_generate", "daybreak",
            "nightfall", "chest_open", "chest_close", "craft_station",
            "stat_rule", "explosion", "player_join", "player_leave");

    /** Looping beds that play under everything else. */
    public static final List<String> AMBIENT_SOUNDS =
            List.of("day", "night", "cave", "underwater", "wind");

    /** Doors between levels. */
    public static final List<String> DOOR_SOUNDS = List.of("open", "close", "travel", "locked");

    /** Cutscene playback. */
    public static final List<String> CUTSCENE_SOUNDS = List.of("start", "line", "end", "skip");

    /** Mini-game refereeing: the events the HUD announces. */
    public static final List<String> MINIGAME_SOUNDS = List.of(
            "start", "countdown", "score", "flag_taken", "flag_dropped",
            "flag_captured", "payload_move", "round_end", "victory", "defeat");

    /**
     * One catalogue row: the object, the pack folder its sounds belong in,
     * the base file name, the sound key, and the action state the key is for.
     * {@code category} groups rows in the editor and the generated key list.
     */
    public record Entry(String category, String folder, String key, String file,
                        String name, String state) {}

    private SoundKeys() {}

    /** Every pack folder, in the order the key list documents them. */
    public static List<String> folders() {
        return List.of(PLAYER, BLOCKS, LIQUIDS, LIGHTS, MOBS, ITEMS, DECOR,
                BLOCK_DECOR, PROJECTILES, ULTIMATES, VEHICLES, PARTICLES,
                MUSIC, UI, WORLD, AMBIENT, DOORS, CUTSCENES, MINIGAME);
    }

    /**
     * The relative pack paths (no extension) a sound key accepts, most
     * specific first — {@code mobs/slime_attack} before the catch-all
     * {@code mobs/slime}, and a block accepted from whichever of
     * blocks/liquids/lights folders its palette category is.
     */
    public static List<String> paths(String key) {
        if (key == null || key.isBlank()) return List.of();
        String[] parts = key.split("/");
        String rest = join(parts, 1, parts.length);
        return switch (parts[0]) {
            case "block" -> blockPaths(parts);
            case "mob" -> progressive(MOBS, parts);
            case "item" -> progressive(ITEMS, parts);
            case "decor" -> progressive(DECOR, parts);
            case "surface" -> progressive(BLOCK_DECOR, parts);
            case "projectile" -> progressive(PROJECTILES, parts);
            case "ultimate" -> progressive(ULTIMATES, parts);
            case "vehicle" -> progressive(VEHICLES, parts);
            case "particle" -> progressive(PARTICLES, parts);
            case "player" -> progressive(PLAYER, parts);
            // A character's own voice lives beside the player's, prefixed
            // with the profile key: player/rogue_hurt.wav.
            case "character" -> progressive(PLAYER, parts);
            case "music" -> List.of(MUSIC + "/" + rest);
            case "ui" -> List.of(UI + "/" + rest);
            case "world" -> List.of(WORLD + "/" + rest);
            case "ambient" -> List.of(AMBIENT + "/" + rest);
            case "door" -> List.of(DOORS + "/" + rest);
            case "cutscene" -> List.of(CUTSCENES + "/" + rest);
            case "minigame" -> List.of(MINIGAME + "/" + rest);
            default -> List.of(OTHER + "/" + key.replace('/', '_'));
        };
    }

    /** {@code folder/a_b}, {@code folder/a} — every prefix, longest first. */
    private static List<String> progressive(String folder, String[] parts) {
        List<String> out = new ArrayList<>(Math.max(1, parts.length - 1));
        for (int end = parts.length; end > 1; end--) {
            out.add(folder + "/" + join(parts, 1, end));
        }
        return out.isEmpty() ? List.of(folder + "/" + parts[0]) : out;
    }

    /**
     * A block's candidate paths, with <em>its own</em> palette folder first —
     * water is looked for in {@code liquids/} before {@code blocks/}, a torch
     * in {@code lights/} — so the folder a creator is told to use in
     * {@code SOUND_KEYS.txt} is also the one that wins. All three folders are
     * still accepted, because a pack that files everything under
     * {@code blocks/} should work too.
     */
    private static List<String> blockPaths(String[] parts) {
        String own = BLOCKS;
        Block b = parts.length > 1 ? BlockRegistry.standard().get(parts[1]) : null;
        if (b != null) own = b.liquid() ? LIQUIDS : b.emitsLight() ? LIGHTS : BLOCKS;
        List<List<String>> lists = new ArrayList<>(3);
        lists.add(progressive(own, parts));
        for (String folder : List.of(BLOCKS, LIQUIDS, LIGHTS)) {
            if (!folder.equals(own)) lists.add(progressive(folder, parts));
        }
        return bySpecificity(lists);
    }

    /**
     * Merge equally-deep candidate lists so specificity beats folder: a
     * liquid's {@code liquids/water_splash} wins over the generic
     * {@code blocks/water}, rather than losing to it because "blocks" was
     * searched first.
     */
    private static List<String> bySpecificity(List<List<String>> lists) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        for (List<String> l : lists) depth = Math.max(depth, l.size());
        for (int i = 0; i < depth; i++) {
            for (List<String> l : lists) {
                if (i < l.size()) out.add(l.get(i));
            }
        }
        return out;
    }

    /** The file a creator should drop in for {@code key} (the preferred path). */
    public static String preferredFile(String key) {
        List<String> paths = paths(key);
        return paths.isEmpty() ? "" : paths.get(0) + ".wav";
    }

    /**
     * The whole catalogue, grouped by category and ordered the way the sound
     * editor lists it. Reads the live registries, so a block or mob a creator
     * just made with "+ New …" is in here with a full set of action states.
     */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();

        // The player first: it is what a creator wants to hear most.
        for (String state : PLAYER_STATES) {
            out.add(new Entry("Player", PLAYER, "player/" + state, state,
                    "Player", state));
        }
        for (CharacterProfile c : Characters.all()) {
            for (String state : PLAYER_STATES) {
                out.add(new Entry("Characters", PLAYER,
                        "character/" + c.key + "/" + state, c.key + "_" + state,
                        c.name, state));
            }
        }
        for (Ultimate u : Ultimates.all()) {
            for (String state : ULTIMATE_STATES) {
                out.add(new Entry("Ultimate abilities", ULTIMATES,
                        "ultimate/" + u.key() + "/" + state, u.key() + "_" + state,
                        u.name(), state));
            }
        }
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // the liquid sim's hidden flow twins
            String folder = b.liquid() ? LIQUIDS : b.emitsLight() ? LIGHTS : BLOCKS;
            String category = b.liquid() ? "Liquids" : b.emitsLight() ? "Lights" : "Blocks";
            List<String> states = b.liquid() ? LIQUID_STATES
                    : b.emitsLight() ? LIGHT_STATES : BLOCK_STATES;
            for (String state : states) {
                out.add(new Entry(category, folder, "block/" + b.key() + "/" + state,
                        b.key() + "_" + state, b.displayName(), state));
            }
        }
        for (MobDef d : MobRegistry.standard().all()) {
            for (String state : MOB_STATES) {
                out.add(new Entry("Mobs", MOBS, "mob/" + d.key() + "/" + state,
                        d.key() + "_" + state, d.displayName(), state));
            }
        }
        Set<String> itemKeys = new LinkedHashSet<>();
        for (ItemDef d : ItemRegistry.standard().all()) {
            if (!itemKeys.add(d.key())) continue;
            for (String state : ITEM_STATES) {
                out.add(new Entry("Items", ITEMS, "item/" + d.key() + "/" + state,
                        d.key() + "_" + state, d.name(), state));
            }
        }
        for (ProjectileDef d : ProjectileRegistry.standard().all()) {
            for (String state : PROJECTILE_STATES) {
                out.add(new Entry("Projectiles", PROJECTILES,
                        "projectile/" + d.key() + "/" + state, d.key() + "_" + state,
                        d.name(), state));
            }
        }
        for (Decor d : DecorRegistry.standard().all()) {
            for (String state : DECOR_STATES) {
                out.add(new Entry("Decorations", DECOR, "decor/" + d.key() + "/" + state,
                        d.key() + "_" + state, d.name(), state));
            }
        }
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            for (String state : SURFACE_STATES) {
                out.add(new Entry("Block decorations", BLOCK_DECOR,
                        "surface/" + d.key() + "/" + state, d.key() + "_" + state,
                        d.name(), state));
            }
        }
        for (VehicleDef d : VehicleRegistry.standard().all()) {
            for (String state : VEHICLE_STATES) {
                out.add(new Entry("Vehicles", VEHICLES, "vehicle/" + d.key() + "/" + state,
                        d.key() + "_" + state, d.name(), state));
            }
        }
        for (Particles.Style style : Particles.Style.values()) {
            String kind = Particles.textureKind(style);
            for (String state : PARTICLE_STATES) {
                out.add(new Entry("Particles", PARTICLES, particle(kind, state),
                        kind + "_" + state, Particles.styleName(style), state));
            }
        }
        for (String track : MUSIC_TRACKS) {
            out.add(new Entry("Music", MUSIC, "music/" + track, track,
                    "Music", track));
        }
        for (String s : AMBIENT_SOUNDS) {
            out.add(new Entry("Ambience", AMBIENT, "ambient/" + s, s, "Ambience", s));
        }
        for (String s : WORLD_SOUNDS) {
            out.add(new Entry("World", WORLD, "world/" + s, s, "World", s));
        }
        for (String s : DOOR_SOUNDS) {
            out.add(new Entry("Doors", DOORS, "door/" + s, s, "Door", s));
        }
        for (String s : CUTSCENE_SOUNDS) {
            out.add(new Entry("Cutscenes", CUTSCENES, "cutscene/" + s, s, "Cutscene", s));
        }
        for (String s : MINIGAME_SOUNDS) {
            out.add(new Entry("Mini games", MINIGAME, "minigame/" + s, s, "Mini game", s));
        }
        for (String s : UI_SOUNDS) {
            out.add(new Entry("Interface", UI, "ui/" + s, s, "Interface", s));
        }
        return out;
    }

    /** The catalogue's categories, in list order (the editor's group filter). */
    public static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Entry e : all()) {
            if (!out.contains(e.category())) out.add(e.category());
        }
        return out;
    }

    /** The rows of one category, or every row when {@code category} is blank. */
    public static List<Entry> inCategory(String category) {
        if (category == null || category.isBlank()) return all();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.category().equals(category)) out.add(e);
        }
        return out;
    }

    // --- key builders (so callers never hand-splice strings) --------------------

    /** {@code player/<state>}, or the character's own key when it has one. */
    public static String player(String state) {
        return "player/" + state;
    }

    /**
     * A named character's voice for an action state, e.g.
     * {@code character/rogue/hurt}. A blank key means the default player.
     */
    public static String character(String characterKey, String state) {
        return characterKey == null || characterKey.isBlank()
                ? player(state) : "character/" + characterKey + "/" + state;
    }

    public static String block(String blockKey, String state) {
        return "block/" + blockKey + "/" + state;
    }

    public static String mob(String mobKey, String state) {
        return "mob/" + mobKey + "/" + state;
    }

    public static String item(String itemKey, String state) {
        return "item/" + itemKey + "/" + state;
    }

    public static String projectile(String projectileKey, String state) {
        return "projectile/" + projectileKey + "/" + state;
    }

    public static String ultimate(String ultimateKey, String state) {
        return "ultimate/" + ultimateKey + "/" + state;
    }

    public static String decor(String decorKey, String state) {
        return "decor/" + decorKey + "/" + state;
    }

    public static String surface(String surfaceKey, String state) {
        return "surface/" + surfaceKey + "/" + state;
    }

    public static String vehicle(String vehicleKey, String state) {
        return "vehicle/" + vehicleKey + "/" + state;
    }

    public static String particle(String kind, String state) {
        return "particle/" + kind + "/" + state;
    }

    public static String music(String track) {
        return "music/" + track;
    }

    public static String ui(String name) {
        return "ui/" + name;
    }

    public static String world(String name) {
        return "world/" + name;
    }

    public static String ambient(String name) {
        return "ambient/" + name;
    }

    public static String door(String name) {
        return "door/" + name;
    }

    public static String cutscene(String name) {
        return "cutscene/" + name;
    }

    public static String minigame(String name) {
        return "minigame/" + name;
    }

    /**
     * The sound an impact from the world simulation makes.
     *
     * <p>{@link com.larsons.engine.world.World} reports what happened as a
     * short tag — a projectile key, {@code ultimate_<ability>}, or one of a
     * few effect names — and this turns it into the sound key to play, so
     * the play scene and creative mode's play-test both get it right without
     * either of them keeping its own table.
     *
     * @param fxKey     the {@code World.Impact} tag
     * @param explosion whether the simulation called it an explosion
     * @return the keys to try in order, most specific first
     */
    public static List<String> impact(String fxKey, boolean explosion) {
        if (fxKey == null || fxKey.isBlank()) return List.of(world("explosion"));
        if (fxKey.startsWith("ultimate_")) {
            // The ability's own landing sound, then a generic one, so a new
            // ultimate is audible before anyone records anything for it.
            String ability = fxKey.substring("ultimate_".length());
            return List.of(ultimate(ability, "impact"),
                    explosion ? world("explosion") : player("attack_hit"));
        }
        return switch (fxKey) {
            // Relic and ability effects the simulation names in its own terms.
            case "nova", "tremor" -> List.of(world("explosion"));
            case "blink" -> List.of(player("teleport"));
            case "warp" -> List.of(player("teleport"));
            case "summon" -> List.of(world("spawn_wave"), player("craft"));
            case "chain" -> List.of(world("explosion"));
            case "revive" -> List.of(player("heal"));
            case "mount" -> List.of(player("mount"));
            // Anything else is a projectile, named by its own key.
            default -> explosion
                    ? List.of(projectile(fxKey, "explode"), world("explosion"))
                    : List.of(projectile(fxKey, "impact"), player("attack_hit"));
        };
    }

    /** Whether a key names music (which is looped, and never pitch-varied). */
    public static boolean isMusic(String key) {
        return key != null && key.startsWith("music/");
    }

    /**
     * Whether a key names a sound that plays continuously while its state
     * holds, rather than firing once — ambience, engines, a sustained
     * ultimate, a projectile in flight. These loop until stopped.
     */
    public static boolean isLooping(String key) {
        if (key == null) return false;
        if (isMusic(key) || key.startsWith("ambient/")) return true;
        return key.endsWith("/ambient") || key.endsWith("/loop")
                || key.endsWith("/engine") || key.endsWith("/flight");
    }

    /** Join key segments {@code [from, to)} with underscores: the file name. */
    private static String join(String[] parts, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(to, parts.length); i++) {
            if (sb.length() > 0) sb.append('_');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
