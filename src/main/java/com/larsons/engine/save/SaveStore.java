package com.larsons.engine.save;

import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One save slot: a {@link RunRecord} and the levels that run has changed.
 *
 * <pre>
 * saves/&lt;game-type&gt;/&lt;slot&gt;/
 *     run.json              the player — see {@link RunRecord}
 *     levels/&lt;name&gt;.json    every level this run has modified
 * </pre>
 *
 * <p><b>A slot is a second levels root, and that is the whole mechanism.</b>
 * {@link LevelStore} already took its root directory as a constructor argument,
 * so pointing one at {@code <slot>/levels} gives a run its own private copy of
 * any level it has touched, in the format the engine already reads and writes.
 * No new serialization was needed for the world half of a save; what was
 * missing was somewhere to put it and something to mean "this run", which is
 * {@link RunRecord}.
 *
 * <p><b>Authored levels are templates and are never written here.</b>
 * {@link #levelFor} reads the run's copy when there is one and the authored
 * level when there is not, and {@link #saveLevel} only ever writes into the
 * slot. Before this existed, the pause menu's <em>Save Level</em> wrote back
 * over the level's authored file through a {@code LevelStore} rooted at
 * {@code resources/levels}, so playing a level edited it and there was no
 * pristine copy left to start a second run from.
 */
public final class SaveStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/saves";

    /** The run file's name inside a slot. */
    public static final String RUN_FILE = "run.json";

    /** The folder inside a slot that holds the run's own copies of levels. */
    public static final String LEVELS_DIR = "levels";

    /** The slot a run gets when the player has not asked for a particular one. */
    public static final String DEFAULT_SLOT = "slot1";

    private final Path root;
    private final String gameType;     // sanitized folder name
    private final String slot;         // sanitized folder name
    private final LevelStore authored; // where levels are authored: read-only here
    private final LevelStore runLevels;// this slot's own copies

    public SaveStore(String gameTypeName, String slotName) {
        this(DEFAULT_DIR, LevelStore.DEFAULT_DIR, gameTypeName, slotName);
    }

    /**
     * @param rootDir      where slots live ({@link #DEFAULT_DIR})
     * @param levelsDir    where levels are <em>authored</em>
     *                     ({@link LevelStore#DEFAULT_DIR}) — read from, never
     *                     written to
     * @param gameTypeName the game type this run belongs to
     * @param slotName     the slot within it
     */
    public SaveStore(String rootDir, String levelsDir, String gameTypeName, String slotName) {
        this.root = Path.of(rootDir);
        this.gameType = folderName(gameTypeName, "untitled");
        this.slot = folderName(slotName, DEFAULT_SLOT);
        this.authored = new LevelStore(levelsDir, gameTypeName);
        // Rooted at the slot with LEVELS_DIR standing in for the game type, so
        // the run's copies land in <slot>/levels/<name>.json. The game type is
        // already the folder above; naming it twice would only make the path
        // longer.
        this.runLevels = new LevelStore(directory().toString(), LEVELS_DIR);
    }

    /** Strip a name down to something safe to be a folder. */
    private static String folderName(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        String file = GameTypeStore.fileName(name); // sanitizes + ".json"
        return file.substring(0, file.length() - ".json".length());
    }

    /** This slot's folder. */
    public Path directory() {
        return root.resolve(gameType).resolve(slot);
    }

    /** The slot's name, as it will appear in a menu. */
    public String slot() { return slot; }

    public Path runFile() {
        return directory().resolve(RUN_FILE);
    }

    /** Whether this slot holds a saved run. */
    public boolean exists() {
        return Files.exists(runFile());
    }

    // --- the run ---------------------------------------------------------------

    /**
     * Read the saved run, or {@code null} when the slot is empty <em>or its
     * file cannot be understood</em>.
     *
     * <p>A corrupt save reports "no save" rather than throwing, on the same
     * rule the key-bind and skin stores follow: a file the player cannot fix
     * from inside the game must never be the reason the game will not start.
     * The slot is left on disk so it can be inspected or repaired by hand.
     */
    public RunRecord loadRun() {
        Path file = runFile();
        if (!Files.exists(file)) return null;
        try {
            return RunRecord.fromMap(Json.asObject(Json.parse(Files.readString(file))));
        } catch (IOException | RuntimeException e) {
            System.err.println("SaveStore: unreadable " + file + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Write the run record; returns the path it went to. */
    public Path saveRun(RunRecord run) {
        return saveRunData(run.toMap());
    }

    /**
     * Write an already-snapshotted run record.
     *
     * <p>Split from {@link #saveRun} so the two halves of a save can happen on
     * two different threads. {@code toMap()} reads live game state and so must
     * run on the thread that owns it; turning that map into text is by far the
     * more expensive half and touches nothing but the map, so it belongs
     * anywhere else. See {@link RunSession} for the measurements.
     */
    public Path saveRunData(Map<String, Object> data) {
        Path file = runFile();
        try {
            Files.createDirectories(directory());
            writeAtomically(file, Json.stringify(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    // --- the run's levels ------------------------------------------------------

    /**
     * The level to play: this run's copy if it has one, otherwise the authored
     * level, loaded fresh.
     *
     * <p>The one indirection the whole save system turns on. Everything else —
     * doors, autosave, <em>Continue</em> — is a caller of this method.
     *
     * @return the level, or {@code null} when neither copy exists
     */
    public Level levelFor(String levelName) {
        if (levelName == null || levelName.isBlank()) return null;
        if (runLevels.exists(levelName)) {
            try {
                return runLevels.load(levelName);
            } catch (RuntimeException e) {
                // A damaged run copy falls back to the authored level rather
                // than ending the run: losing the changes to one level is bad,
                // losing the save is worse.
                System.err.println("SaveStore: unreadable run copy of \"" + levelName
                        + "\" (" + e.getMessage() + ") — falling back to the authored level");
            }
        }
        return authored.exists(levelName) ? authored.load(levelName) : null;
    }

    /** Whether this run has its own copy of a level yet. */
    public boolean hasLevel(String levelName) {
        return runLevels.exists(levelName);
    }

    /**
     * Write a level into this run's slot. Never touches the authored copy —
     * see the class comment for why that is the point.
     */
    public Path saveLevel(Level level) {
        return saveLevelData(level.name, level.toMap());
    }

    /**
     * Write an already-snapshotted level into this run's slot, under
     * {@code levelName}. The background half of a save — see
     * {@link #saveRunData}.
     */
    public Path saveLevelData(String levelName, Map<String, Object> data) {
        Path file = runLevels.fileFor(levelName);
        try {
            Files.createDirectories(runLevels.directory());
            writeAtomically(file, Json.stringify(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /**
     * Write via a temporary file and a move, so a save that is interrupted —
     * the process killed, the machine losing power, the disk filling — leaves
     * either the previous save or the new one, never half of either.
     *
     * <p>A truncated {@code run.json} reads as "no save" (see
     * {@link #loadRun}), which is a mild way of describing losing a run that
     * was fine a second ago. Falls back to a plain write when the move cannot
     * be done atomically, which is better than not saving at all.
     */
    private static void writeAtomically(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The names of the levels this run has its own copies of. */
    public List<String> savedLevels() {
        return runLevels.list();
    }

    // --- slots -----------------------------------------------------------------

    /** Delete this slot and everything in it; returns whether anything went. */
    public boolean delete() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> paths = Files.walk(dir)) {
            // Deepest first, so a directory is empty by the time it is removed.
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    /**
     * Move a game type's whole saves folder, so renaming a game type keeps its
     * runs — the same thing {@code LevelStore.moveGameTypeFolderTo} does for
     * its levels, and needed for the same reason: a run belongs to a game type,
     * and a rename must not orphan it.
     *
     * @return whether a move happened (false when there is nothing to move)
     */
    public static boolean moveGameTypeSaves(String oldName, String newName) {
        return moveGameTypeSaves(DEFAULT_DIR, oldName, newName);
    }

    public static boolean moveGameTypeSaves(String rootDir, String oldName, String newName) {
        Path from = Path.of(rootDir).resolve(folderName(oldName, "untitled"));
        Path to = Path.of(rootDir).resolve(folderName(newName, "untitled"));
        if (!Files.isDirectory(from) || from.equals(to)) return false;
        try {
            if (Files.exists(to)) {
                // Refusing beats merging two game types' runs into one folder.
                System.err.println("SaveStore: not moving saves to " + to + " — it already exists");
                return false;
            }
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (IOException e) {
            System.err.println("SaveStore: could not move saves from " + from + " to " + to
                    + " (" + e.getMessage() + ")");
            return false;
        }
        return true;
    }

    /** Delete every run of a game type — for when the game type itself goes. */
    public static boolean deleteGameTypeSaves(String gameTypeName) {
        return deleteGameTypeSaves(DEFAULT_DIR, gameTypeName);
    }

    public static boolean deleteGameTypeSaves(String rootDir, String gameTypeName) {
        Path dir = Path.of(rootDir).resolve(folderName(gameTypeName, "untitled"));
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    /** The slot names that hold a run for {@code gameTypeName}, sorted. */
    public static List<String> slots(String gameTypeName) {
        return slots(DEFAULT_DIR, gameTypeName);
    }

    public static List<String> slots(String rootDir, String gameTypeName) {
        List<String> names = new ArrayList<>();
        Path dir = Path.of(rootDir).resolve(folderName(gameTypeName, "untitled"));
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(RUN_FILE)))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }
}
