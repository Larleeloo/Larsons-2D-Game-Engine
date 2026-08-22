package com.larsons.engine.level;

import com.larsons.engine.config.GameTypeStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Saves and lists the levels created inside a game type — the "per-game-type
 * level saving" the engine's roadmap called for, which the creative editor
 * (ported from the Side-Scroller engine) needs a home for.
 *
 * <p>Levels live under {@code src/main/resources/levels/<game-type>/} so they
 * sit beside the bundled sample and are managed together with their game
 * type: switching types switches which levels you see.
 *
 * <p>A game type holds levels of every {@link LevelFormat} side by side —
 * {@link #formatOf} and {@link #list(LevelFormat)} report which format each
 * saved level was built in, so menus can group them without loading them.
 */
public final class LevelStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/levels";

    /**
     * The files that live in a game type's folder without being levels.
     *
     * <p><b>This list existed in one place and was needed in two, and the place
     * it was missing from is the one every menu reads.</b> A game type's folder
     * holds its levels plus the sidecars that wire them together —
     * {@code doors.json}, {@code custom.json}, {@code characters.json} — and
     * {@link #list()} answered "every {@code .json} in here", so a type with
     * character profiles reported a level called {@code characters}. Selecting
     * it wrote the sidecar's path into {@link
     * com.larsons.engine.config.GameProfile#lastLevelPath}, and the next launch
     * printed
     *
     * <pre>
     * PlayScene: failed to load src/main/resources/levels/test_3/characters.json:
     *     Level is missing a 'tiles' array
     * </pre>
     *
     * <p>which is the loader correctly refusing a roster of characters. The
     * export path had the same question to answer and answered it properly, so
     * a packaged game type never contained the phantom level that the menu
     * offered — the two disagreed, and the folder's owner is the one that should
     * be believed. It lives here now and the exporter asks.
     *
     * <p>Matched case-insensitively, because the folder may sit on a
     * case-insensitive filesystem where {@code Characters.json} is the same file.
     */
    private static final java.util.Set<String> SIDECARS = java.util.Set.of(
            DoorDirectory.FILE_NAME,
            com.larsons.engine.config.CustomContentStore.FILE_NAME,
            com.larsons.engine.character.CharacterStore.FILE_NAME);

    private final Path root;
    private final String gameType; // sanitized folder name

    public LevelStore(String gameTypeName) {
        this(DEFAULT_DIR, gameTypeName);
    }

    public LevelStore(String rootDir, String gameTypeName) {
        this.root = Path.of(rootDir);
        String file = GameTypeStore.fileName(gameTypeName); // "<safe>.json"
        this.gameType = file.substring(0, file.length() - ".json".length());
    }

    public Path directory() {
        return root.resolve(gameType);
    }

    /**
     * Whether a file in a game type's folder is one of its levels — a
     * {@code .json} that is not one of the {@link #SIDECARS}.
     *
     * <p>Public because the exporter has to make the same judgement about the
     * same folder, and two implementations of it is how the folder came to
     * contain a level that could not be loaded.
     */
    public static boolean isLevelFile(Path file) {
        String f = file.getFileName().toString();
        return f.length() > ".json".length()
                && f.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                && !SIDECARS.contains(f.toLowerCase(java.util.Locale.ROOT));
    }

    /** Level names (file stems) saved for this game type, sorted. */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        Path dir = directory();
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(LevelStore::isLevelFile)
                    .sorted()
                    .forEach(p -> {
                        String f = p.getFileName().toString();
                        names.add(f.substring(0, f.length() - ".json".length()));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    public boolean exists(String levelName) {
        return Files.exists(fileFor(levelName));
    }

    /**
     * The {@link LevelFormat} a saved level was built in, read from the start
     * of its file — listing a folder by format doesn't decode any tiles.
     * Missing or unreadable files report {@link LevelFormat#SIDE_SCROLLER},
     * the format levels that predate the field were built in.
     */
    public LevelFormat formatOf(String levelName) {
        Path file = fileFor(levelName);
        if (!Files.exists(file)) return LevelFormat.SIDE_SCROLLER;
        try {
            // Through LevelFile, because a level on disk is deflated and the
            // point of reading a header rather than the file is that it stays
            // cheap — see LevelFile.head, which inflates the first block and
            // stops.
            String head = LevelFile.head(file, HEADER_CHARS);
            if (head.isEmpty()) return LevelFormat.SIDE_SCROLLER;
            return LevelLoader.peekFormat(head, LevelFormat.SIDE_SCROLLER);
        } catch (IOException e) {
            return LevelFormat.SIDE_SCROLLER;
        }
    }

    /** This game type's level names that were built in {@code format}, sorted. */
    public List<String> list(LevelFormat format) {
        List<String> names = new ArrayList<>();
        for (String name : list()) {
            if (formatOf(name) == format) names.add(name);
        }
        return names;
    }

    /**
     * Characters of a level file read to find its format. The format keys lead
     * the JSON object, so this is generous even for the pretty-printed form.
     */
    private static final int HEADER_CHARS = 512;

    public Level load(String levelName) {
        return LevelLoader.load(fileFor(levelName).toString());
    }

    /**
     * Persist a level; returns the path it was written to.
     *
     * <p><b>Deflated, and still a {@code .json}.</b> The bytes are the same
     * JSON {@link Level#toJson()} has always produced, run through gzip — see
     * {@link LevelFile}, which is also what reads them back and what still
     * reads every level written before this. The name is unchanged because the
     * <em>content</em> is unchanged: a level file is a level file, and a player
     * who has one in a folder, a profile that points at one by path, and a
     * package that carries one all go on working.
     */
    public Path save(Level level) {
        Path file = fileFor(level.name);
        try {
            Files.createDirectories(directory());
            LevelFile.write(file, level.toJson());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public Path fileFor(String levelName) {
        String file = GameTypeStore.fileName(levelName); // sanitizes + ".json"
        return directory().resolve(file);
    }

    /** Delete a saved level's file; returns whether one existed. */
    public boolean delete(String levelName) {
        try {
            return Files.deleteIfExists(fileFor(levelName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Move this game type's whole levels folder — its levels plus the
     * {@code doors.json} / {@code custom.json} that live beside them — to the
     * folder for {@code newGameTypeName} under the same root, so a game-type
     * rename keeps its levels. Returns whether a move happened (false when the
     * folder is absent or already at the target name). Throws if the target
     * folder already exists (the caller guards against clobbering another type).
     */
    public boolean moveGameTypeFolderTo(String newGameTypeName) {
        Path from = directory();
        Path to = new LevelStore(root.toString(), newGameTypeName).directory();
        if (from.equals(to) || !Files.isDirectory(from)) return false;
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Delete this game type's whole levels folder — every level plus the
     * {@code doors.json} / {@code custom.json} that live beside them — used when
     * a game type itself is deleted. Returns whether a folder existed and was
     * removed (false when there was nothing there, so calling it twice is safe).
     */
    public boolean deleteGameTypeFolder() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            // Reverse (deepest-first) order so children are removed before the
            // directories that contain them.
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }
}
