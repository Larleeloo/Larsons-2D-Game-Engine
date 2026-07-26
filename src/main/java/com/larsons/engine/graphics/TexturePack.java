package com.larsons.engine.graphics;

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
 * A drop-in texture pack: one folder of sprite sheets, sorted into subfolders
 * named after the creative palette's categories, that reskins the game
 * <em>by file name</em> — no menu visit required.
 *
 * <pre>
 *   textures/
 *   ├── texturepack.json     universal frame size / frame count / fps (+ per-key overrides)
 *   ├── TEXTURE_KEYS.txt     every object's texture key and the file name to give it
 *   ├── README.txt
 *   ├── blocks/    dirt.png, stone.png …
 *   ├── liquids/   water.png …
 *   ├── mobs/      slime.png (all states) or slime_walk.png (one state) …
 *   ├── items/     iron_sword.png …
 *   └── …          decor/ · block_decor/ · player/ · units/ · projectiles/ · board/
 * </pre>
 *
 * <p>The folder lives <b>next to the runnable jar</b>, so a player who was
 * handed the {@code share/} folder just drops PNGs into it. Running from
 * IntelliJ scaffolds one inside {@code share/} (see
 * {@link com.larsons.engine.core.ShareJar}) with the folders, the key list,
 * and a starter config already written.
 *
 * <p>Every sheet in the pack plays with the <b>same universal settings</b> —
 * {@value #DEFAULT_FRAME_SIZE}×{@value #DEFAULT_FRAME_SIZE} pixel frames,
 * {@value #DEFAULT_FRAME_COUNT} frames at {@value #DEFAULT_FPS} fps — so a
 * whole pack is drawn to one spec. Any single texture can override that,
 * either by editing {@code texturepack.json} or from the creative editor's
 * texture dialog (which writes the override back here).
 *
 * <p>The pack is consulted for <em>every</em> texture key by default
 * ({@link Skins}); a key with no file in the pack keeps its built-in
 * procedural icon, and each object can opt out of the pack (or point at a
 * sheet somewhere else entirely) from the texture dialog.
 */
public final class TexturePack {

    /** Folder name of the pack, relative to the jar / share folder. */
    public static final String DIR_NAME = "textures";
    /** Mirrors {@code ShareJar.DEFAULT_DIR}; the pack ships inside it. */
    public static final String SHARE_DIR_NAME = "share";

    public static final String CONFIG_FILE = "texturepack.json";
    public static final String KEYS_FILE = "TEXTURE_KEYS.txt";
    public static final String README_FILE = "README.txt";

    /** Universal frame size every sheet in the pack is drawn at. */
    public static final int DEFAULT_FRAME_SIZE = 32;
    /** Universal animation length (frames per sheet). */
    public static final int DEFAULT_FRAME_COUNT = 3;
    /** Universal playback rate, sprite frames per second. */
    public static final double DEFAULT_FPS = 3;

    /** Image types a pack sheet may use, in lookup order. */
    private static final String[] EXTENSIONS = {".png", ".gif", ".jpg", ".jpeg"};

    /** How one sheet is sliced and played. */
    public record Frames(int width, int height, int count, double fps) {
        public Frames {
            width = Math.max(1, width);
            height = Math.max(1, height);
            count = Math.max(1, count);
            fps = SkinDef.clampFps(fps);
        }
    }

    /** The pack's universal settings plus its per-key exceptions. */
    private record Config(Frames defaults, Map<String, Frames> overrides) {}

    /** Game-type override of the pack location ("" = find it automatically). */
    private static volatile String dirOverride = "";
    private static Config config;
    private static Path configRoot;
    /** Resolved sheet file per texture key ({@code null} = none in the pack). */
    private static final Map<String, Path> FILES = new HashMap<>();

    private TexturePack() {}

    // --- location -------------------------------------------------------------------

    /**
     * Point the pack at a specific folder; blank restores automatic lookup
     * (next to the jar, then the working directory, then {@code share/}).
     * Changing it drops the resolved textures so the new pack applies live.
     */
    public static void useDir(String dir) {
        String next = dir == null ? "" : dir.trim();
        // Pushed from the profile on every live-settings sync, so the
        // unchanged case costs a string compare and no lock at all.
        if (next.equals(dirOverride)) return;
        synchronized (TexturePack.class) {
            if (next.equals(dirOverride)) return;
            dirOverride = next;
        }
        reload();
    }

    /** The folder currently searched for sheets (it need not exist yet). */
    public static synchronized Path root() {
        if (!dirOverride.isBlank()) return Path.of(dirOverride);
        return autoRoot();
    }

    /** True when the pack folder is actually there to read sheets from. */
    public static synchronized boolean exists() {
        return Files.isDirectory(root());
    }

    /**
     * Where the pack lives when no game type names one: beside the running
     * jar (how a shared game is played), else a {@code textures/} folder in
     * the working directory, else the one IntelliJ scaffolds in
     * {@code share/}. Nothing exists yet on a first run — then the jar's
     * folder is the answer, because that is where the player will make it.
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
            URL loc = TexturePack.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            return Files.isRegularFile(p) && p.toString().endsWith(".jar")
                    ? p.toAbsolutePath().getParent() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Re-read the config and re-scan for sheets — what picks up PNGs added to
     * the folder while the game is running. The resolved-texture cache is
     * dropped outside this class's lock, so the two caches are never taken in
     * opposite orders by a render and a rescan.
     */
    public static void reload() {
        forgetFiles();
        Skins.clearCache();
    }

    private static synchronized void forgetFiles() {
        config = null;
        configRoot = null;
        FILES.clear();
    }

    // --- lookup ---------------------------------------------------------------------

    /**
     * The pack's sheet for a texture key as a ready-to-slice {@link SkinDef},
     * or {@code null} when the pack has no file for it (the caller's cue to
     * fall back to the built-in art).
     */
    public static synchronized SkinDef lookup(String key) {
        Path file = fileFor(key);
        if (file == null) return null;
        Frames f = framesFor(key);
        return new SkinDef(key, file.toString().replace('\\', '/'),
                f.width(), f.height(), f.count(), f.fps(), true);
    }

    /** The pack file backing {@code key}, or {@code null} when there is none. */
    public static synchronized Path fileFor(String key) {
        settings();          // a moved pack drops both caches together
        Path root = configRoot;
        if (FILES.containsKey(key)) return FILES.get(key);
        Path found = null;
        if (Files.isDirectory(root)) {
            search:
            for (String rel : TextureKeys.paths(key)) {
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

    /** The file name a creator should give {@code key}, e.g. {@code blocks/dirt.png}. */
    public static String fileNameFor(String key) {
        return TextureKeys.preferredFile(key);
    }

    /** Whether the pack currently holds a sheet for {@code key}. */
    public static synchronized boolean has(String key) {
        return fileFor(key) != null;
    }

    // --- settings -------------------------------------------------------------------

    /** The universal frame size / length / rate every sheet plays at. */
    public static synchronized Frames defaults() {
        return settings().defaults();
    }

    /** The settings {@code key} plays at: its own override, else the defaults. */
    public static synchronized Frames framesFor(String key) {
        Config c = settings();
        Frames own = c.overrides().get(key);
        return own != null ? own : c.defaults();
    }

    /** Whether {@code key} departs from the pack's universal settings. */
    public static synchronized boolean hasOverride(String key) {
        return settings().overrides().containsKey(key);
    }

    /**
     * Give one texture its own frame size/length/rate, saved into the pack's
     * {@code texturepack.json} so the exception travels with the folder.
     * Settings equal to the pack defaults clear the override instead.
     */
    public static synchronized void setOverride(String key, int width, int height,
                                                int count, double fps) {
        Config c = settings();
        Frames f = new Frames(width, height, count, fps);
        if (f.equals(c.defaults())) {
            clearOverride(key);
            return;
        }
        if (f.equals(c.overrides().get(key))) return;
        c.overrides().put(key, f);
        writeConfig();
    }

    /** Put {@code key} back on the pack's universal settings. */
    public static synchronized void clearOverride(String key) {
        Config c = settings();
        if (c.overrides().remove(key) != null) writeConfig();
    }

    /** Change the universal settings the whole pack is drawn to. */
    public static void setDefaults(int width, int height, int count, double fps) {
        synchronized (TexturePack.class) {
            Frames f = new Frames(width, height, count, fps);
            Config c = settings();
            if (f.equals(c.defaults())) return;
            config = new Config(f, c.overrides());
            writeConfig();
        }
        Skins.clearCache();
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
        Frames defaults = new Frames(DEFAULT_FRAME_SIZE, DEFAULT_FRAME_SIZE,
                DEFAULT_FRAME_COUNT, DEFAULT_FPS);
        Map<String, Frames> overrides = new LinkedHashMap<>();
        Path file = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(file)) return new Config(defaults, overrides);
        try {
            Map<String, Object> m = Json.asObject(Json.parse(Files.readString(file)));
            defaults = frames(m, defaults);
            if (m.get("overrides") instanceof Map<?, ?> raw) {
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> v) {
                        overrides.put(String.valueOf(e.getKey()),
                                frames(Json.asObject(v), defaults));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("TexturePack: unreadable " + file + " (" + e.getMessage()
                    + ") — using the universal defaults");
        }
        return new Config(defaults, overrides);
    }

    /** Read one frame spec, inheriting anything the map leaves out. */
    private static Frames frames(Map<String, Object> m, Frames inherit) {
        int size = intOf(m.get("frameSize"), 0);
        return new Frames(
                intOf(m.get("frameWidth"), size > 0 ? size : inherit.width()),
                intOf(m.get("frameHeight"), size > 0 ? size : inherit.height()),
                intOf(m.get("frameCount"), inherit.count()),
                m.get("fps") instanceof Number n ? n.doubleValue() : inherit.fps());
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
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
        root.put("frameWidth", c.defaults().width());
        root.put("frameHeight", c.defaults().height());
        root.put("frameCount", c.defaults().count());
        root.put("fps", c.defaults().fps());
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Frames> e : c.overrides().entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("frameWidth", e.getValue().width());
            f.put("frameHeight", e.getValue().height());
            f.put("frameCount", e.getValue().count());
            f.put("fps", e.getValue().fps());
            overrides.put(e.getKey(), f);
        }
        root.put("overrides", overrides);
        return Json.stringify(root);
    }

    // --- scaffolding ----------------------------------------------------------------

    /**
     * Create (or refresh) an empty texture pack at {@code root}: one folder
     * per palette category, a README, and a {@code TEXTURE_KEYS.txt} listing
     * every object's file name. The folders and documentation are rewritten
     * each time so the key list tracks the content that exists now; an
     * existing {@code texturepack.json} is left alone so nobody's frame
     * settings are clobbered.
     */
    public static Path scaffold(Path root) throws IOException {
        Files.createDirectories(root);
        for (String folder : TextureKeys.folders()) {
            Files.createDirectories(root.resolve(folder));
        }
        Files.writeString(root.resolve(README_FILE), readmeText());
        Files.writeString(root.resolve(KEYS_FILE), keysText());
        Path cfg = root.resolve(CONFIG_FILE);
        if (!Files.exists(cfg)) {
            Files.writeString(cfg, configJson(new Config(
                    new Frames(DEFAULT_FRAME_SIZE, DEFAULT_FRAME_SIZE,
                            DEFAULT_FRAME_COUNT, DEFAULT_FPS),
                    new LinkedHashMap<>())));
        }
        return root;
    }

    private static String readmeText() {
        return """
                LARSON'S 2D GAME ENGINE — TEXTURE PACK
                ======================================

                Drop sprite sheets in this folder and the game uses them. Keep
                the folder beside the game's .jar file and it is found
                automatically — nothing to configure, no menu to visit.

                NAMING
                  Each subfolder is a creative-palette category, and each file is
                  named after the object it reskins:

                      blocks/dirt.png            the Dirt block
                      liquids/water.png          the Water liquid
                      lights/torch.png           the Torch light
                      mobs/slime.png             the Slime, every animation state
                      mobs/slime_walk.png        the Slime, walking only
                      items/iron_sword.png       the Iron Sword
                      decor/oak_tree.png         the Oak Tree decoration
                      block_decor/moss.png       the Moss block detail
                      player/idle.png            the player standing still
                      units/squire.png           the auto-battler Squire

                  %s lists the exact name for every object in the
                  game, including anything you created yourself. Anything you
                  don't supply keeps its built-in icon, so a pack can be one file
                  or a thousand.

                  PNG is preferred; GIF and JPG also load.

                SHEET FORMAT
                  Every sheet in this folder is drawn to the same spec, set in
                  %s:

                      %d x %d pixel frames, %d frames, %.0f fps

                  Frames are laid out left-to-right then top-to-bottom, so the
                  default sheet is a %d x %d image. A still image is fine too —
                  give that texture "frameCount": 1 in the "overrides" block.

                  To give one texture its own size/length/rate, add it to
                  "overrides" (keyed by texture key, e.g. "block/dirt"), or set it
                  from the creative editor: right-click the palette icon, edit the
                  frame fields, Apply — the override is written back here.

                IN-GAME CONTROLS
                  Right-click any palette icon in creative mode:
                    * "Use texture pack folder" is ON by default — this folder
                      supplies the texture, and the built-in icon is the fallback.
                    * Turn it OFF (or fill in "Sheet elsewhere") to point that one
                      object at a sheet anywhere on disk instead.
                    * The "Pack file:" row names the file that object wants and
                      says whether it is here yet; click it to rescan after
                      adding sheets while the game is running.
                """.formatted(KEYS_FILE, CONFIG_FILE,
                DEFAULT_FRAME_SIZE, DEFAULT_FRAME_SIZE, DEFAULT_FRAME_COUNT, DEFAULT_FPS,
                DEFAULT_FRAME_SIZE * DEFAULT_FRAME_COUNT, DEFAULT_FRAME_SIZE);
    }

    /** The generated key list: every object, its file name, and its texture key. */
    private static String keysText() {
        StringBuilder sb = new StringBuilder();
        sb.append("LARSON'S 2D GAME ENGINE — TEXTURE KEYS\n");
        sb.append("======================================\n\n");
        sb.append("Every object you can reskin, the file to name your sheet, and the\n");
        sb.append("texture key behind it. Drop a sheet at the listed path in this\n");
        sb.append("folder and it applies on the next launch (or use \"Rescan texture\n");
        sb.append("pack folder\" in the creative editor's texture dialog).\n\n");
        sb.append("Default sheet spec: ").append(DEFAULT_FRAME_SIZE).append('x')
                .append(DEFAULT_FRAME_SIZE).append(" frames, ").append(DEFAULT_FRAME_COUNT)
                .append(" frames, ").append((int) DEFAULT_FPS).append(" fps — see ")
                .append(CONFIG_FILE).append(".\n");

        // Group by palette category: the registries interleave liquids and
        // lights with the plain blocks, and one heading per category reads
        // far better than the order they happen to be registered in.
        Map<String, List<TextureKeys.Entry>> byCategory = new LinkedHashMap<>();
        for (TextureKeys.Entry e : TextureKeys.all()) {
            byCategory.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<TextureKeys.Entry>> group : byCategory.entrySet()) {
            List<TextureKeys.Entry> entries = group.getValue();
            TextureKeys.Entry first = entries.get(0);
            sb.append("\n\n").append(group.getKey().toUpperCase())
                    .append("  ->  ").append(first.folder()).append("/\n");
            if (!first.states().isEmpty()) {
                sb.append("  (one sheet reskins every state; add _<state> to the file\n")
                        .append("   name for a per-state sheet — states: ")
                        .append(String.join(", ", first.states())).append(")\n");
            }
            sb.append('\n');
            for (TextureKeys.Entry e : entries) {
                sb.append(String.format("  %-34s %-30s %s%n",
                        e.folder() + "/" + e.file() + ".png", e.name(), e.key()));
            }
        }
        sb.append("""

                CUSTOM CONTENT
                  Blocks, mobs, items and decorations you create with the palette's
                  "+ New …" buttons follow the same rule: the file is named after
                  the key you gave the object (shown in its texture dialog). This
                  list is regenerated every time the game is launched from
                  IntelliJ, so create your objects and it will name them for you.
                """);
        return sb.toString();
    }

    /** The folders a scaffolded pack contains. */
    public static List<String> folders() {
        return TextureKeys.folders();
    }
}
