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
 */
public final class LevelStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/levels";

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

    /** Level names (file stems) saved for this game type, sorted. */
    public List<String> list() {
        List<String> names = new ArrayList<>();
        Path dir = directory();
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
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

    public Level load(String levelName) {
        return LevelLoader.load(fileFor(levelName).toString());
    }

    /** Persist a level; returns the path it was written to. */
    public Path save(Level level) {
        Path file = fileFor(level.name);
        try {
            Files.createDirectories(directory());
            Files.writeString(file, level.toJson());
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
