package com.larsons.engine.config;

import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.core.ShareJar;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A shareable game package: one {@code .larsonsengine} file that bundles a
 * whole game type — its {@link GameProfile}, every level in it, and the
 * {@code doors.json} / {@code custom.json} / {@code characters.json} that wire
 * those levels together and
 * define their custom content. This is how a creator hands a finished game to
 * someone else: a single file that sits next to the runnable jar and imports
 * itself at launch.
 *
 * <p>Levels are never packaged on their own — a package always carries its
 * game type, since a level only means something inside the game type whose
 * features, doors, and custom blocks it was built against.
 *
 * <p><b>Format.</b> The file is a single JSON document (the engine already has
 * its own {@link Json} parser, so packages stay dependency-free and readable):
 * <pre>
 * {
 *   "larsonsengine": 1,                     // format version
 *   "name": "My Platformer",                // the game type's display name
 *   "gameType": { ...GameProfile fields... },
 *   "levels": { "level_one": { ...level... }, ... },
 *   "doors":  { ...doors.json... },          // present only if the type has doors
 *   "custom": { ...custom.json... }          // present only if it has custom content
 *   "characters": { ...characters.json... }  // present only if it has character profiles
 * }
 * </pre>
 *
 * <p><b>Finalize.</b> Exporting with {@code finalized} on stamps the packaged
 * {@link GameProfile#finalized} flag, so the recipient's copy is play-only —
 * its levels can be played but not opened in creative mode or edited (see
 * {@code MainMenuScene} / {@code LevelSelectScene}). The creator's own local
 * copy is untouched and stays editable.
 *
 * <p><b>Import at launch.</b> {@link #importDropIns()} scans the folder holding
 * the jar (or, when running from an IDE/Gradle, the {@code share/} folder where
 * the shareable jar is built) for {@code .larsonsengine} files and imports any
 * whose game type isn't already present — so dropping a package beside the jar
 * and launching is all it takes to install it. An already-installed game type
 * is left alone, so a player's local edits are never clobbered by a re-scan.
 *
 * <p><b>Forward compatibility.</b> A package exported today is meant to keep
 * loading in every future build. Three things guarantee that: the file is
 * <em>versioned</em> ({@link #FORMAT_KEY}); the readers ({@link GameProfile#fromMap}
 * and the level loader) <em>default anything missing and ignore anything
 * unknown</em>, so a build never chokes on a field it doesn't recognize; and
 * import runs {@link #migrate} to upgrade an older schema to the current one
 * before reading it. Newer-than-current packages import best-effort rather than
 * being refused. The one rule future changes must follow is spelled out on
 * {@link #migrate}: only add keys (with safe defaults), never repurpose or drop
 * one — and when a shape must truly change, add a migration step.
 */
public final class GamePackage {

    /** The package file extension checked for beside the jar at launch. */
    public static final String EXTENSION = ".larsonsengine";

    /** Root key carrying the format version; also marks a file as a package. */
    public static final String FORMAT_KEY = "larsonsengine";

    /** Current package format version. */
    public static final int FORMAT_VERSION = 1;

    private GamePackage() {}

    // --- export -------------------------------------------------------------------

    /**
     * Build the in-memory package for {@code profile}'s game type, reading its
     * levels (and doors/custom content) from {@code levels}. When
     * {@code finalized} is true the packaged profile is marked play-only.
     */
    public static Map<String, Object> build(GameProfile profile, LevelStore levels,
                                            boolean finalized) {
        GameProfile exported = profile.copy();
        exported.finalized = finalized;
        exported.normalize();

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put(FORMAT_KEY, FORMAT_VERSION);
        pkg.put("name", exported.name);
        pkg.put("gameType", exported.toMap());
        pkg.put("levels", collectLevels(levels));

        Path dir = levels.directory();
        Path doors = dir.resolve(DoorDirectory.FILE_NAME);
        if (Files.exists(doors)) pkg.put("doors", readJson(doors));
        Path custom = dir.resolve(CustomContentStore.FILE_NAME);
        if (Files.exists(custom)) pkg.put("custom", readJson(custom));
        // The game type's playable characters travel with it, so a level's
        // roster still names real profiles on the machine that receives it.
        Path characters = dir.resolve(CharacterStore.FILE_NAME);
        if (Files.exists(characters)) pkg.put("characters", readJson(characters));
        return pkg;
    }

    /**
     * Export {@code profile}'s game type to
     * {@code <outDir>/<sanitized-name>.larsonsengine}, overwriting any previous
     * export. Returns the file written.
     */
    public static Path export(GameProfile profile, LevelStore levels, Path outDir,
                              boolean finalized) throws IOException {
        Files.createDirectories(outDir);
        Path out = outDir.resolve(packageFileName(profile.name));
        Files.writeString(out, Json.stringify(build(profile, levels, finalized)));
        return out;
    }

    /** The package filename a game type exports to: {@code <safe-name>.larsonsengine}. */
    public static String packageFileName(String gameTypeName) {
        String jsonName = GameTypeStore.fileName(gameTypeName); // "<safe>.json"
        return jsonName.substring(0, jsonName.length() - ".json".length()) + EXTENSION;
    }

    /**
     * Every level in the game type, keyed by file stem. What counts as a level
     * in that folder is {@link LevelStore#isLevelFile}'s answer rather than a
     * second copy of the rule here — see the note on {@code LevelStore}'s
     * sidecar list for what having two of them cost.
     */
    private static Map<String, Object> collectLevels(LevelStore levels) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path dir = levels.directory();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files
                    .filter(LevelStore::isLevelFile)
                    .sorted()
                    .toList();
            for (Path p : sorted) {
                String f = p.getFileName().toString();
                String stem = f.substring(0, f.length() - ".json".length());
                out.put(stem, readJson(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // --- import -------------------------------------------------------------------

    /**
     * Scan the jar's folder (or {@code share/} in dev) for {@code .larsonsengine}
     * files and install any game type not already present. Returns the display
     * names imported. Safe to call every launch — installed types are skipped.
     */
    public static List<String> importDropIns() {
        return importAll(dropInDir(),
                Path.of(GameTypeStore.DEFAULT_DIR),
                Path.of(LevelStore.DEFAULT_DIR));
    }

    /**
     * Import every {@code .larsonsengine} file in {@code searchDir} whose game
     * type isn't already installed under {@code gametypesDir}/{@code levelsRoot}.
     * Returns the display names actually imported (skips are silent-ish: logged,
     * not returned).
     */
    public static List<String> importAll(Path searchDir, Path gametypesDir, Path levelsRoot) {
        List<String> imported = new ArrayList<>();
        if (searchDir == null || !Files.isDirectory(searchDir)) return imported;
        List<Path> packages;
        try (Stream<Path> files = Files.list(searchDir)) {
            packages = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(EXTENSION))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.err.println("[package] Could not scan " + searchDir + ": " + e.getMessage());
            return imported;
        }
        for (Path pkg : packages) {
            try {
                String name = importFile(pkg, gametypesDir, levelsRoot, false);
                if (name != null) {
                    imported.add(name);
                    System.out.println("[package] Imported game type \"" + name
                            + "\" from " + pkg.getFileName());
                }
            } catch (RuntimeException e) {
                System.err.println("[package] Failed to import " + pkg + ": " + e.getMessage());
            }
        }
        return imported;
    }

    /**
     * Install one package: write its game type profile under {@code gametypesDir}
     * and its levels (plus doors/custom content) under {@code levelsRoot}. When
     * {@code overwrite} is false and a game type of the same name already exists,
     * nothing is written and {@code null} is returned. Returns the imported
     * display name on success, or {@code null} when skipped or invalid.
     */
    public static String importFile(Path packageFile, Path gametypesDir, Path levelsRoot,
                                    boolean overwrite) {
        Map<String, Object> root;
        try {
            root = Json.asObject(Json.parse(Files.readString(packageFile)));
        } catch (IOException | RuntimeException e) {
            System.err.println("[package] Unreadable package " + packageFile + ": " + e.getMessage());
            return null;
        }
        if (!(root.get(FORMAT_KEY) instanceof Number versionNum)
                || !(root.get("gameType") instanceof Map<?, ?>)) {
            System.err.println("[package] Not a valid .larsonsengine file: " + packageFile);
            return null;
        }
        int version = versionNum.intValue();
        if (version > FORMAT_VERSION) {
            // A package from a newer build. We can't know its future shape, but
            // the readers default missing keys and ignore unknown ones, so a
            // best-effort import still yields a playable game type.
            System.err.println("[package] " + packageFile.getFileName()
                    + " was exported by a newer version (format v" + version
                    + "; this build reads v" + FORMAT_VERSION + "). Importing best-effort.");
        }
        // Upgrade an older schema to the current one before reading it. This is
        // what keeps game types exported by past versions loadable here.
        migrate(root);

        GameProfile profile = GameProfile.fromMap(Json.asObject(root.get("gameType")));
        profile.normalize();

        Path profileFile = gametypesDir.resolve(GameTypeStore.fileName(profile.name));
        Path levelsDir = new LevelStore(levelsRoot.toString(), profile.name).directory();
        if (!overwrite && (Files.exists(profileFile) || Files.isDirectory(levelsDir))) {
            System.out.println("[package] Skipping \"" + profile.name
                    + "\" (already installed) from " + packageFile.getFileName());
            return null;
        }

        try {
            Files.createDirectories(gametypesDir);
            Files.writeString(profileFile, profile.toJson());

            Files.createDirectories(levelsDir);
            if (root.get("levels") instanceof Map<?, ?> levels) {
                for (Map.Entry<?, ?> e : levels.entrySet()) {
                    Path lf = levelsDir.resolve(GameTypeStore.fileName(String.valueOf(e.getKey())));
                    com.larsons.engine.level.LevelFile.write(lf, Json.stringify(e.getValue()));
                }
            }
            if (root.get("doors") != null) {
                Files.writeString(levelsDir.resolve(DoorDirectory.FILE_NAME),
                        Json.stringify(root.get("doors")));
            }
            if (root.get("custom") != null) {
                Files.writeString(levelsDir.resolve(CustomContentStore.FILE_NAME),
                        Json.stringify(root.get("custom")));
            }
            if (root.get("characters") != null) {
                Files.writeString(levelsDir.resolve(CharacterStore.FILE_NAME),
                        Json.stringify(root.get("characters")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return profile.name;
    }

    // --- forward compatibility ----------------------------------------------------

    /**
     * Upgrade a package's map from the schema version it was written with to the
     * current {@link #FORMAT_VERSION}, applying one migration per increment. This
     * is the hook that keeps game types exported by <em>older</em> versions
     * loadable in <em>newer</em> ones: the file's stored {@link #FORMAT_KEY}
     * version routes it through the right upgrade steps.
     *
     * <p>v1 is the original schema, so there is nothing to migrate yet. When a
     * future change alters the shape of the package, bump {@link #FORMAT_VERSION}
     * and add a {@code case} here that rewrites the previous shape into the new
     * one — e.g. {@code case 1 -> upgrade1to2(root);}. The compatibility contract
     * this preserves is deliberately narrow, so it stays easy to honour:
     * <ul>
     *   <li>never remove or repurpose the meaning of an existing key — only add
     *       new ones, with safe defaults (both {@link GameProfile#fromMap} and
     *       the level loader already default anything missing);</li>
     *   <li>when a key's shape must genuinely change, add a migration step here
     *       keyed off the version it changed at.</li>
     * </ul>
     * Follow that and every {@code .larsonsengine} file ever exported keeps
     * importing into every future build.
     */
    private static void migrate(Map<String, Object> root) {
        int version = root.get(FORMAT_KEY) instanceof Number n ? n.intValue() : FORMAT_VERSION;
        while (version < FORMAT_VERSION) {
            switch (version) {
                // case 1 -> upgrade1to2(root);   // added when FORMAT_VERSION hits 2
                default -> { /* no migration defined; tolerant readers cover it */ }
            }
            version++;
        }
        root.put(FORMAT_KEY, FORMAT_VERSION);
    }

    // --- locations ----------------------------------------------------------------

    /**
     * Where packages are read from and written to: the folder holding the
     * running jar when packaged, or the {@code share/} folder (where the
     * shareable jar is auto-built) when running from an IDE/Gradle.
     */
    public static Path dropInDir() {
        try {
            URL loc = GamePackage.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Path.of(loc.toURI());
                if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                    Path parent = p.getParent();
                    if (parent != null) return parent;
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            // fall through to the dev-mode share folder
        }
        return Path.of(ShareJar.DEFAULT_DIR);
    }

    private static Object readJson(Path file) {
        try {
            // Level files are deflated on disk (LevelFile) and the sidecars
            // beside them are not; reading both through the same door is what
            // keeps a package's contents plain JSON whichever it collected.
            return Json.parse(com.larsons.engine.level.LevelFile.read(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
