package com.larsons.engine.audio;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A drop-in sound pack: one folder of audio files, sorted into subfolders
 * named after the thing they belong to, that gives the game its voice
 * <em>by file name</em> — no menu visit required.
 *
 * <pre>
 *   sounds/
 *   ├── soundpack.json     master volume, pitch variation (+ per-sound overrides)
 *   ├── SOUND_KEYS.txt     every sound the game can make and the file to name it
 *   ├── README.txt
 *   ├── player/            jump.wav, swim.wav, hurt.wav …
 *   ├── blocks/            dirt_break.wav, stone_place.wav …
 *   ├── mobs/              slime_attack.wav …
 *   ├── ultimates/         meteor_volley_activate.wav, meteor_volley_impact.wav …
 *   ├── music/             level.mp3, boss.mp3 …
 *   └── …                  liquids/ · lights/ · items/ · projectiles/ · decor/ ·
 *                          block_decor/ · vehicles/ · particles/ · ui/ · world/ ·
 *                          ambient/ · doors/ · cutscenes/ · minigame/
 * </pre>
 *
 * <p>The folder lives <b>next to the runnable jar</b>, exactly like the
 * {@link com.larsons.engine.graphics.TexturePack} it is modelled on, so a
 * player handed the {@code share/} folder just drops audio files into it.
 * Running from IntelliJ scaffolds one inside {@code share/} (see
 * {@link com.larsons.engine.core.ShareJar}) with the folders, the key list,
 * and a starter config already written.
 *
 * <p><b>WAV and MP3.</b> {@code .wav}, {@code .mp3}, {@code .aif(f)} and
 * {@code .au} all load — MP3 through the engine's own
 * {@link Mp3Decoder}, everything else through the JDK. A sound key with no
 * file in the pack falls back to silence (or, for the handful of actions the
 * engine has always made a noise for, to a built-in synthesized voice), so a
 * pack can be one file or a thousand.
 *
 * <p>Each sound may carry its own volume, pitch and loop setting, saved into
 * the pack's {@code soundpack.json} so the exception travels with the folder
 * — the same arrangement as the texture pack's per-key frame overrides, and
 * editable from creative mode's sound editor.
 */
public final class SoundPack {

    /** Folder name of the pack, relative to the jar / share folder. */
    public static final String DIR_NAME = "sounds";
    /** Mirrors {@code ShareJar.DEFAULT_DIR}; the pack ships inside it. */
    public static final String SHARE_DIR_NAME = "share";

    public static final String CONFIG_FILE = "soundpack.json";
    public static final String KEYS_FILE = "SOUND_KEYS.txt";
    public static final String README_FILE = "README.txt";

    /** Pack-wide default playback volume. */
    public static final double DEFAULT_VOLUME = 1.0;
    /** Pack-wide default playback pitch (1 = as recorded). */
    public static final double DEFAULT_PITCH = 1.0;
    /**
     * How far a sound's pitch drifts either way, as a fraction, when the
     * "fresh pitch" option is on. Minecraft-ish: enough that a run of
     * footsteps or block breaks never sounds like the same click twice,
     * small enough that nothing sounds broken.
     */
    public static final double DEFAULT_PITCH_VARIATION = 0.08;
    /** Ceiling on the pitch-variation setting. */
    public static final double MAX_PITCH_VARIATION = 0.5;

    /** Audio types a pack file may use, in lookup order. */
    private static final String[] EXTENSIONS = {".wav", ".mp3", ".ogg", ".aiff", ".aif", ".au"};

    /** How one sound plays: its level, its pitch, and whether it repeats. */
    public record Playback(double volume, double pitch, boolean loop, boolean varyPitch) {
        public Playback {
            volume = clamp(volume, 0, 4);
            pitch = clamp(pitch, 0.25, 4);
        }
    }

    /** The pack's defaults plus its per-sound exceptions. */
    private record Config(Playback defaults, double pitchVariation,
                          Map<String, Playback> overrides) {}

    /** Game-type override of the pack location ("" = find it automatically). */
    private static volatile String dirOverride = "";
    private static Config config;
    private static Path configRoot;
    /** Resolved audio file per sound key ({@code null} = none in the pack). */
    private static final Map<String, Path> FILES = new HashMap<>();

    private SoundPack() {}

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    // --- location -------------------------------------------------------------------

    /**
     * Point the pack at a specific folder; blank restores automatic lookup
     * (next to the jar, then the working directory, then {@code share/}).
     * Changing it drops the resolved sounds so the new pack applies live.
     */
    public static void useDir(String dir) {
        String next = dir == null ? "" : dir.trim();
        // Pushed from the profile on every live-settings sync, so the
        // unchanged case costs a string compare and no lock at all.
        if (next.equals(dirOverride)) return;
        synchronized (SoundPack.class) {
            if (next.equals(dirOverride)) return;
            dirOverride = next;
        }
        reload();
    }

    /** The folder currently searched for audio (it need not exist yet). */
    public static synchronized Path root() {
        if (!dirOverride.isBlank()) return Path.of(dirOverride);
        return autoRoot();
    }

    /** True when the pack folder is actually there to read sounds from. */
    public static synchronized boolean exists() {
        return Files.isDirectory(root());
    }

    /**
     * Where the pack lives when no game type names one: beside the running
     * jar (how a shared game is played), else a {@code sounds/} folder in the
     * working directory, else the one IntelliJ scaffolds in {@code share/}.
     */
    private static Path autoRoot() {
        Path jarDir = runningJarDir();
        if (jarDir != null && Files.isDirectory(jarDir.resolve(DIR_NAME))) {
            return jarDir.resolve(DIR_NAME);
        }
        Path here = Path.of(DIR_NAME);
        if (Files.isDirectory(here)) return here;
        Path shared = Path.of(SHARE_DIR_NAME, DIR_NAME);
        if (Files.isDirectory(shared)) return shared;
        return jarDir != null ? jarDir.resolve(DIR_NAME) : shared;
    }

    /** Folder of the jar this code runs from, or {@code null} from classes. */
    private static Path runningJarDir() {
        try {
            URL loc = SoundPack.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            return Files.isRegularFile(p) && p.toString().endsWith(".jar")
                    ? p.toAbsolutePath().getParent() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Re-read the config and re-scan for audio — what picks up files added to
     * the folder while the game is running. The decoded-clip cache is dropped
     * too, so a replaced file is heard rather than remembered.
     */
    public static void reload() {
        forgetFiles();
        SoundLoader.clearCache();
    }

    private static synchronized void forgetFiles() {
        config = null;
        configRoot = null;
        FILES.clear();
    }

    // --- lookup ---------------------------------------------------------------------

    /** The pack file backing {@code key}, or {@code null} when there is none. */
    public static synchronized Path fileFor(String key) {
        settings();          // a moved pack drops both caches together
        Path root = configRoot;
        if (FILES.containsKey(key)) return FILES.get(key);
        Path found = null;
        if (Files.isDirectory(root)) {
            search:
            for (String rel : SoundKeys.paths(key)) {
                for (String ext : EXTENSIONS) {
                    Path candidate = root.resolve(rel + ext);
                    if (Files.isRegularFile(candidate)) {
                        found = candidate;
                        break search;
                    }
                }
            }
        }
        FILES.put(key, found);
        return found;
    }

    /** The file name a creator should give {@code key}, e.g. {@code player/jump.wav}. */
    public static String fileNameFor(String key) {
        return SoundKeys.preferredFile(key);
    }

    /** Whether the pack currently holds a file for {@code key}. */
    public static synchronized boolean has(String key) {
        return fileFor(key) != null;
    }

    // --- settings -------------------------------------------------------------------

    /** The volume/pitch/loop every sound in the pack plays at by default. */
    public static synchronized Playback defaults() {
        return settings().defaults();
    }

    /**
     * How far pitch drifts either way when the fresh-pitch option is on, as a
     * fraction of the sound's own pitch. Saved with the pack so a creator's
     * chosen feel travels with the folder.
     */
    public static synchronized double pitchVariation() {
        return settings().pitchVariation();
    }

    /** Set the pack's pitch spread; {@code 0} makes every playback identical. */
    public static void setPitchVariation(double amount) {
        synchronized (SoundPack.class) {
            double v = clamp(amount, 0, MAX_PITCH_VARIATION);
            Config c = settings();
            if (v == c.pitchVariation()) return;
            config = new Config(c.defaults(), v, c.overrides());
            writeConfig();
        }
    }

    /** The settings {@code key} plays at: its own override, else the defaults. */
    public static synchronized Playback playbackFor(String key) {
        Config c = settings();
        Playback own = c.overrides().get(key);
        if (own != null) return own;
        // Music and ambience repeat by nature; everything else fires once.
        Playback d = c.defaults();
        return SoundKeys.isLooping(key)
                ? new Playback(d.volume(), d.pitch(), true, d.varyPitch() && !SoundKeys.isMusic(key))
                : d;
    }

    /** Whether {@code key} departs from the pack's defaults. */
    public static synchronized boolean hasOverride(String key) {
        return settings().overrides().containsKey(key);
    }

    /**
     * Give one sound its own volume/pitch/loop, saved into the pack's
     * {@code soundpack.json} so the exception travels with the folder.
     * Settings equal to what the key would play at anyway clear the override.
     */
    public static synchronized void setOverride(String key, double volume, double pitch,
                                                boolean loop, boolean varyPitch) {
        Config c = settings();
        Playback p = new Playback(volume, pitch, loop, varyPitch);
        c.overrides().remove(key);
        if (p.equals(playbackFor(key))) {   // back to the inherited setting
            writeConfig();
            return;
        }
        c.overrides().put(key, p);
        writeConfig();
    }

    /** Put {@code key} back on the pack's defaults. */
    public static synchronized void clearOverride(String key) {
        Config c = settings();
        if (c.overrides().remove(key) != null) writeConfig();
    }

    /** Change the defaults the whole pack plays at. */
    public static void setDefaults(double volume, double pitch, boolean varyPitch) {
        synchronized (SoundPack.class) {
            Config c = settings();
            Playback p = new Playback(volume, pitch, false, varyPitch);
            if (p.equals(c.defaults())) return;
            config = new Config(p, c.pitchVariation(), c.overrides());
            writeConfig();
        }
        SoundLoader.clearCache();
    }

    private static Config settings() {
        Path root = root();
        if (config == null || !root.equals(configRoot)) {
            FILES.clear();
            configRoot = root;
            config = readConfig(root);
        }
        return config;
    }

    private static Config readConfig(Path root) {
        Playback defaults = new Playback(DEFAULT_VOLUME, DEFAULT_PITCH, false, true);
        double variation = DEFAULT_PITCH_VARIATION;
        Map<String, Playback> overrides = new LinkedHashMap<>();
        Path file = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(file)) return new Config(defaults, variation, overrides);
        try {
            Map<String, Object> m = Json.asObject(Json.parse(Files.readString(file)));
            defaults = playback(m, defaults);
            if (m.get("pitchVariation") instanceof Number n) variation = n.doubleValue();
            if (m.get("overrides") instanceof Map<?, ?> raw) {
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> v) {
                        overrides.put(String.valueOf(e.getKey()),
                                playback(Json.asObject(v), defaults));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("SoundPack: unreadable " + file + " (" + e.getMessage()
                    + ") — using the pack defaults");
        }
        return new Config(defaults, clamp(variation, 0, MAX_PITCH_VARIATION), overrides);
    }

    /** Read one playback spec, inheriting anything the map leaves out. */
    private static Playback playback(Map<String, Object> m, Playback inherit) {
        return new Playback(
                m.get("volume") instanceof Number n ? n.doubleValue() : inherit.volume(),
                m.get("pitch") instanceof Number n ? n.doubleValue() : inherit.pitch(),
                m.get("loop") instanceof Boolean b ? b : inherit.loop(),
                m.get("varyPitch") instanceof Boolean b ? b : inherit.varyPitch());
    }

    private static void writeConfig() {
        Path root = root();
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(CONFIG_FILE), configJson(settings()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String configJson(Config c) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("volume", c.defaults().volume());
        root.put("pitch", c.defaults().pitch());
        root.put("varyPitch", c.defaults().varyPitch());
        root.put("pitchVariation", c.pitchVariation());
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Playback> e : c.overrides().entrySet()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("volume", e.getValue().volume());
            p.put("pitch", e.getValue().pitch());
            p.put("loop", e.getValue().loop());
            p.put("varyPitch", e.getValue().varyPitch());
            overrides.put(e.getKey(), p);
        }
        root.put("overrides", overrides);
        return Json.stringify(root);
    }

    // --- scaffolding ----------------------------------------------------------------

    /**
     * Create (or refresh) an empty sound pack at {@code root}: one folder per
     * family, a README, and a {@code SOUND_KEYS.txt} listing every sound the
     * game can make and the file name to give it. The folders and
     * documentation are rewritten each time so the key list tracks the
     * content that exists now — including anything the creator has just made
     * with the palette's "+" buttons; an existing {@code soundpack.json} is
     * left alone so nobody's settings are clobbered.
     */
    public static Path scaffold(Path root) throws IOException {
        Files.createDirectories(root);
        for (String folder : SoundKeys.folders()) {
            Files.createDirectories(root.resolve(folder));
        }
        Files.writeString(root.resolve(README_FILE), readmeText());
        Files.writeString(root.resolve(KEYS_FILE), keysText());
        Path cfg = root.resolve(CONFIG_FILE);
        if (!Files.exists(cfg)) {
            Files.writeString(cfg, configJson(new Config(
                    new Playback(DEFAULT_VOLUME, DEFAULT_PITCH, false, true),
                    DEFAULT_PITCH_VARIATION, new LinkedHashMap<>())));
        }
        return root;
    }

    /** Rewrite the key list in the pack that is in use right now. */
    public static Path refreshKeyList() throws IOException {
        Path root = root();
        Files.createDirectories(root);
        Path file = root.resolve(KEYS_FILE);
        Files.writeString(file, keysText());
        return file;
    }

    private static String readmeText() {
        return """
                LARSON'S 2D GAME ENGINE — SOUND PACK
                ====================================

                Drop audio files in this folder and the game uses them. Keep the
                folder beside the game's .jar file and it is found
                automatically — nothing to configure, no menu to visit.

                WAV or MP3 (also AIFF and AU). Nothing here is required: every
                sound in the game defaults to SILENCE, so a pack can be one file
                or a thousand, and whatever you don't supply simply stays quiet.

                NAMING
                  Each subfolder is a family of sounds, and each file is named
                  after the object and the action it belongs to:

                      player/jump.wav              the player jumping
                      player/swim.wav              swimming (repeats while you swim)
                      player/run.wav               sprinting footsteps
                      player/ult_activate.wav      firing an ultimate
                      blocks/dirt_break.wav        breaking Dirt
                      blocks/stone_place.wav       placing Stone
                      liquids/water_splash.wav     falling into Water
                      mobs/slime_attack.wav        the Slime attacking
                      mobs/slime.wav               the Slime, every action it has
                      items/iron_sword_use.wav     swinging the Iron Sword
                      projectiles/meteor_fire.wav  a meteor being called down
                      projectiles/meteor_flight.wav  the meteor falling (repeats)
                      projectiles/meteor_impact.wav  the meteor crashing
                      ultimates/meteor_volley_activate.wav   casting the volley
                      music/level.mp3              the level's music
                      ui/click.wav                 a menu click

                  A file named after the OBJECT alone covers every action it has,
                  so mobs/slime.wav gives the Slime one voice for spawning,
                  attacking, being hurt and dying — until you add
                  mobs/slime_death.wav, which then wins for that one action.

                  %s lists the exact name for every sound in the game,
                  including anything you created yourself with the palette's
                  "+ New ..." buttons.

                FRESH PITCH
                  By default every sound plays at a slightly different pitch each
                  time — the same trick Minecraft uses so a run of footsteps or
                  block breaks never sounds like a stuck record. The spread is
                  "pitchVariation" in %s (%.2f = plus or minus %.0f%%);
                  set it to 0, or turn the option off in the sound editor, to
                  play every sound exactly as recorded. Music is never
                  pitch-varied.

                SETTINGS
                  %s holds the volume and pitch the whole pack plays at,
                  and an "overrides" block for individual sounds:

                      "overrides": {
                        "music/level":     { "volume": 0.5, "loop": true },
                        "player/run":      { "pitch": 1.2 }
                      }

                  You can edit that by hand, or set it from the game: creative
                  mode -> SOUNDS palette -> Sound Editor..., which lists every
                  sound in the game, says whether this folder has a file for it
                  yet, and writes your changes back here.

                IN-GAME CONTROLS
                  Creative mode's SOUNDS palette:
                    * "Sound Editor..." lists every sound the game can make.
                      Click one to set its file, volume, pitch and looping, or to
                      preview it.
                    * "Use sound pack folder" is ON by default for every sound —
                      this folder supplies the audio, and silence is the fallback.
                    * Turn it OFF (or fill in "Sound file elsewhere") to point one
                      sound at a file anywhere on disk instead.
                    * "Rescan sound pack folder" picks up files you added while
                      the game was running.
                """.formatted(KEYS_FILE, CONFIG_FILE, DEFAULT_PITCH_VARIATION,
                DEFAULT_PITCH_VARIATION * 100, CONFIG_FILE);
    }

    /** The generated key list: every sound, its file name, and its sound key. */
    private static String keysText() {
        StringBuilder sb = new StringBuilder();
        sb.append("LARSON'S 2D GAME ENGINE — SOUND KEYS\n");
        sb.append("====================================\n\n");
        sb.append("Every sound the game can make, the file to name your audio, and\n");
        sb.append("the sound key behind it. Drop a WAV or MP3 at the listed path in\n");
        sb.append("this folder and it applies on the next launch (or use \"Rescan\n");
        sb.append("sound pack folder\" in the creative editor's sound editor).\n\n");
        sb.append("Anything you do not supply is SILENT — that is the default for\n");
        sb.append("every entry below.\n");

        Map<String, List<SoundKeys.Entry>> byCategory = new LinkedHashMap<>();
        for (SoundKeys.Entry e : SoundKeys.all()) {
            byCategory.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<SoundKeys.Entry>> group : byCategory.entrySet()) {
            List<SoundKeys.Entry> entries = group.getValue();
            sb.append("\n\n").append(group.getKey().toUpperCase())
                    .append("  ->  ").append(entries.get(0).folder()).append("/\n");
            sb.append("  (a file named after the object alone — e.g. ")
                    .append(entries.get(0).folder()).append('/')
                    .append(baseName(entries.get(0))).append(".wav — covers every\n")
                    .append("   action state below until you add a more specific file)\n\n");
            for (SoundKeys.Entry e : entries) {
                sb.append(String.format("  %-40s %-26s %s%n",
                        e.folder() + "/" + e.file() + ".wav",
                        e.name() + (e.state().isEmpty() ? "" : " (" + e.state() + ")"),
                        e.key()));
            }
        }
        sb.append("""

                CUSTOM CONTENT
                  Blocks, mobs, items, decorations and characters you create with
                  the palette's "+ New ..." buttons get the same set of action
                  states as everything else, named after the key you gave the
                  object. This list is regenerated every time the game is launched
                  from IntelliJ (and from the sound editor's "Rewrite %s"),
                  so create your objects and it will name their sounds for you.
                """.formatted(KEYS_FILE));
        return sb.toString();
    }

    /** The object part of an entry's file name, without the action state. */
    private static String baseName(SoundKeys.Entry e) {
        String file = e.file();
        String suffix = "_" + e.state();
        return file.endsWith(suffix) ? file.substring(0, file.length() - suffix.length()) : file;
    }

    /** The folders a scaffolded pack contains. */
    public static List<String> folders() {
        return SoundKeys.folders();
    }
}
