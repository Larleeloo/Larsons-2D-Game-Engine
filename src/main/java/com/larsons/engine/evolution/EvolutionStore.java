package com.larsons.engine.evolution;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything the evolution game keeps on disk, all of it JSON, in two tiers.
 *
 * <ul>
 *   <li><b>The game</b> — {@code evolution/save.json}: the dishes and everything
 *       in them, the bench, the credit balance, and this game's
 *       {@link Catalog} (the sequences it has found). Resetting the game
 *       replaces all of it.</li>
 *   <li><b>The history</b> — {@code evolution/history.json}: every organism ever
 *       discovered, with the achievements, colony combinations and lifetime
 *       totals. This is the player's permanent collection and <b>nothing ever
 *       removes anything from it</b> — not a reset, not a new game, not deleting
 *       the save.</li>
 * </ul>
 *
 * <p>Discoveries used to be one JSON file each, named after the DNA that
 * produced it. That was the readable-artefact idea taken literally, and it did
 * not scale: a real collection is thousands of organisms, each file was ~450
 * bytes of which two thirds was decoded traits the loader recomputed and threw
 * away, and every one of them still cost a whole 4 KB disk block — 410 finds
 * came to 1.7 MB on disk to hold about 16 KB of facts. They are now rows in the
 * history (see {@link SpeciesRecord#ROW_FORMAT}), and any organism can still be
 * written out as a standalone decoded file on demand with
 * {@link #exportSpecies}. A folder of per-organism files from an older build is
 * folded into the history the first time it is read, and only then cleared
 * away.
 */
public final class EvolutionStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/evolution";
    public static final String SAVE_FILE = "save.json";
    public static final String HISTORY_FILE = "history.json";
    /** Where discoveries used to live, one file each; folded into the history on use. */
    private static final String LEGACY_HISTORY_DIR = "history";
    /** Older still, from before the history tier existed. Same treatment. */
    private static final String LEGACY_CATALOG_DIR = "catalog";

    private final Path dir;

    public EvolutionStore() {
        this(DEFAULT_DIR);
    }

    public EvolutionStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() { return dir; }

    public Path saveFile() { return dir.resolve(SAVE_FILE); }

    public Path historyFile() { return dir.resolve(HISTORY_FILE); }

    public boolean hasSave() { return Files.exists(saveFile()); }

    // --- the game save ---------------------------------------------------------------------

    /**
     * Write the game out, together with any newly discovered organisms and the
     * updated permanent record. Returns the save file's path.
     */
    public Path save(EvolutionGame game) {
        // A cell riding the spatula belongs to no dish, so put it back before
        // writing — otherwise saving mid-transfer would quietly lose it.
        game.cancelCarry();
        try {
            Files.createDirectories(dir);
            Files.writeString(saveFile(), Json.stringify(game.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        saveHistory(game.history());
        return saveFile();
    }

    /**
     * Load the saved game, with the player's permanent history attached and this
     * game's book resolved against it. Returns {@code null} when there is no
     * save, or when the one on disk cannot be read — a corrupt save should offer
     * a new game, not crash the menu.
     */
    public EvolutionGame load() {
        if (!hasSave()) return null;
        History history = loadHistory();
        try {
            Object parsed = Json.parse(Files.readString(saveFile()));
            return EvolutionGame.fromMap(Json.asObject(parsed), history);
        } catch (IOException | RuntimeException e) {
            System.err.println("EvolutionStore: unreadable save " + saveFile()
                    + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** Remove the save. The history is left alone — discoveries are permanent. */
    public boolean deleteSave() {
        try {
            return Files.deleteIfExists(saveFile());
        } catch (IOException e) {
            return false;
        }
    }

    // --- the permanent history ----------------------------------------------------------------

    /**
     * The player's whole collection: every organism ever discovered, with the
     * achievements, combinations and lifetime totals. Folders of per-organism
     * files from older builds are folded in on the way past.
     */
    public History loadHistory() {
        History history = new History();
        if (Files.exists(historyFile())) {
            try {
                Map<String, Object> m = Json.asObject(Json.parse(Files.readString(historyFile())));
                history = History.fromMap(m);
            } catch (IOException | RuntimeException e) {
                System.err.println("EvolutionStore: unreadable history " + historyFile()
                        + " (" + e.getMessage() + ")");
            }
        }
        absorbLegacyFolders(history);
        return history;
    }

    /** Write the permanent record. */
    public Path saveHistory(History history) {
        if (history == null) return historyFile();
        try {
            Files.createDirectories(dir);
            Files.writeString(historyFile(), Json.stringify(history.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return historyFile();
    }

    /**
     * Write one organism out as a standalone, fully decoded JSON file — traits,
     * abilities, shape and all. This is the readable artefact the design asks
     * for, produced on demand for the organism someone actually wants to look at
     * or share, rather than for all several thousand of them on every save.
     */
    public Path exportSpecies(SpeciesRecord rec, Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, Json.stringify(rec.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /**
     * Every organism ever discovered, newest first. This is the user's history:
     * it contains exactly what has been created, across every game they have
     * ever played, and nothing else.
     */
    public List<SpeciesRecord> loadSpecies() {
        List<SpeciesRecord> out = new ArrayList<>(loadHistory().allSpecies());
        out.sort(Comparator.comparingLong((SpeciesRecord r) -> r.discoveredAt).reversed());
        return out;
    }

    /** How many organisms the history holds. */
    public int speciesCount() {
        return loadHistory().speciesCount();
    }

    /** Read one discovery off disk, or {@code null} if it was never found. */
    public SpeciesRecord loadSpecies(String sequence) {
        return loadHistory().species(sequence);
    }

    /**
     * Fold a folder of per-organism files — {@code history/} from the builds
     * that wrote one file per discovery, or {@code catalog/} from before the
     * history tier existed — into the record, then clear it away.
     *
     * <p>Order matters: the files are read, merged and the whole history written
     * back <em>before</em> anything is deleted, so an interrupted or failed
     * migration leaves the collection where it was rather than half of it
     * nowhere. Nothing is deleted that is not already in the file that replaced
     * it.
     */
    private void absorbLegacyFolders(History history) {
        List<Path> folders = new ArrayList<>();
        for (String name : new String[]{LEGACY_HISTORY_DIR, LEGACY_CATALOG_DIR}) {
            Path folder = dir.resolve(name);
            if (Files.isDirectory(folder)) folders.add(folder);
        }
        if (folders.isEmpty()) return;

        List<Path> absorbed = new ArrayList<>();
        for (Path folder : folders) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path p : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    SpeciesRecord rec = readSpeciesFile(p);
                    if (rec == null) continue; // unreadable: it stays exactly where it is
                    history.restore(rec);
                    absorbed.add(p);
                }
            } catch (IOException e) {
                System.err.println("EvolutionStore: could not read " + folder
                        + " (" + e.getMessage() + ")");
                return; // leave everything alone rather than half-migrate
            }
        }

        if (!absorbed.isEmpty()) {
            try {
                saveHistory(history); // safely in one file before anything is removed
            } catch (RuntimeException e) {
                System.err.println("EvolutionStore: keeping the old organism files, the history"
                        + " could not be written (" + e.getMessage() + ")");
                return;
            }
            for (Path p : absorbed) deleteQuietly(p);
            System.out.println("EvolutionStore: folded " + absorbed.size()
                    + " per-organism files into " + historyFile());
        }
        // A folder is only removed once it is empty, so anything still in there
        // — something unreadable, something the game did not write — is kept.
        for (Path folder : folders) {
            try (Stream<Path> left = Files.list(folder)) {
                if (left.findAny().isEmpty()) deleteQuietly(folder);
            } catch (IOException ignored) {
                // cannot tell whether it is empty: leave it alone
            }
        }
    }

    private SpeciesRecord readSpeciesFile(Path p) {
        try {
            return SpeciesRecord.fromMap(Json.asObject(Json.parse(Files.readString(p))));
        } catch (IOException | RuntimeException e) {
            System.err.println("EvolutionStore: skipping unreadable species file "
                    + p + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            System.err.println("EvolutionStore: could not remove " + p
                    + " (" + e.getMessage() + ")");
        }
    }
}
