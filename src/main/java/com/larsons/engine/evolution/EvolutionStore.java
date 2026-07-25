package com.larsons.engine.evolution;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *   <li><b>The history</b> — {@code evolution/history/<DNA>.json}, <em>one file
 *       per organism ever discovered</em>, plus {@code evolution/history.json}
 *       for the achievements, colony combinations and lifetime totals. This is
 *       the player's permanent collection and <b>nothing ever removes anything
 *       from it</b> — not a reset, not a new game, not deleting the save.</li>
 * </ul>
 *
 * <p>Storing each discovery as its own file, named after the DNA that produced
 * it, is what the design document asks for: catalogued organisms are unique
 * JSON artefacts rather than rows looked up in a reference table the game
 * shipped with.
 */
public final class EvolutionStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/evolution";
    public static final String SAVE_FILE = "save.json";
    public static final String HISTORY_FILE = "history.json";
    public static final String HISTORY_DIR = "history";
    /** Where discoveries lived before the history tier existed; migrated on use. */
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

    public Path historyDirectory() { return dir.resolve(HISTORY_DIR); }

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
            EvolutionGame game = EvolutionGame.fromMap(Json.asObject(parsed), history);
            if (game == null) return null;
            // The history was read from disk, so none of it is pending a write.
            game.history().drainPendingWrites();
            return game;
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
     * The player's whole collection: the index (achievements, combinations,
     * lifetime totals) with every discovered organism folded back in from its
     * own file.
     */
    public History loadHistory() {
        migrateLegacyCatalog();
        History history = new History();
        if (Files.exists(historyFile())) {
            try {
                Map<String, Object> m = Json.asObject(Json.parse(Files.readString(historyFile())));
                history = History.fromMap(m);
            } catch (IOException | RuntimeException e) {
                System.err.println("EvolutionStore: unreadable history index " + historyFile()
                        + " (" + e.getMessage() + ")");
            }
        }
        for (SpeciesRecord rec : loadSpecies()) history.restore(rec);
        history.drainPendingWrites(); // restored entries are already on disk
        return history;
    }

    /** Write the history index and any organisms discovered since the last save. */
    public Path saveHistory(History history) {
        if (history == null) return historyFile();
        try {
            Files.createDirectories(dir);
            Files.writeString(historyFile(), Json.stringify(history.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeNewSpecies(history);
        return historyFile();
    }

    /** Write out every organism recorded since the last call. */
    public void writeNewSpecies(History history) {
        List<SpeciesRecord> pending = history.drainPendingWrites();
        if (pending.isEmpty()) return;
        try {
            Files.createDirectories(historyDirectory());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (SpeciesRecord rec : pending) writeSpecies(rec);
    }

    /**
     * Write one discovery as its own file, named after its DNA. An organism is
     * only ever discovered once, so an existing file is left untouched — the
     * first sighting is the one that counts, and nothing overwrites it.
     */
    public Path writeSpecies(SpeciesRecord rec) {
        Path file = speciesFile(rec.sequence);
        try {
            Files.createDirectories(historyDirectory());
            if (!Files.exists(file)) Files.writeString(file, Json.stringify(rec.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public Path speciesFile(String sequence) {
        return historyDirectory().resolve(sequence + ".json");
    }

    /**
     * Every organism ever discovered, newest first. This is the user's history:
     * it contains exactly what has been created, across every game they have
     * ever played, and nothing else.
     */
    public List<SpeciesRecord> loadSpecies() {
        migrateLegacyCatalog();
        List<SpeciesRecord> out = new ArrayList<>();
        Path historyDir = historyDirectory();
        if (!Files.isDirectory(historyDir)) return out;
        try (Stream<Path> files = Files.list(historyDir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                try {
                    SpeciesRecord rec = SpeciesRecord.fromMap(
                            Json.asObject(Json.parse(Files.readString(p))));
                    if (rec != null) out.add(rec);
                } catch (IOException | RuntimeException e) {
                    System.err.println("EvolutionStore: skipping unreadable species file "
                            + p + " (" + e.getMessage() + ")");
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparingLong((SpeciesRecord r) -> r.discoveredAt).reversed());
        return out;
    }

    /** How many organisms the history holds, without parsing them all. */
    public int speciesFileCount() {
        migrateLegacyCatalog();
        Path historyDir = historyDirectory();
        if (!Files.isDirectory(historyDir)) return 0;
        try (Stream<Path> files = Files.list(historyDir)) {
            return (int) files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Read one discovery straight off disk, or {@code null} if never found. */
    public SpeciesRecord loadSpecies(String sequence) {
        Path file = speciesFile(sequence);
        if (!Files.exists(file)) return null;
        try {
            Map<String, Object> m = Json.asObject(Json.parse(Files.readString(file)));
            return SpeciesRecord.fromMap(m);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Fold an older layout's {@code catalog/} folder into {@code history/}.
     * Discoveries made before the permanent-history split are the same thing the
     * history is for, so they are moved rather than left stranded — a player who
     * already has a collection keeps it.
     */
    private void migrateLegacyCatalog() {
        Path legacy = dir.resolve(LEGACY_CATALOG_DIR);
        if (!Files.isDirectory(legacy)) return;
        try {
            Files.createDirectories(historyDirectory());
            try (Stream<Path> files = Files.list(legacy)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    Path target = historyDirectory().resolve(p.getFileName());
                    try {
                        if (Files.exists(target)) Files.delete(p);
                        else Files.move(p, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        System.err.println("EvolutionStore: could not migrate " + p
                                + " (" + e.getMessage() + ")");
                    }
                });
            }
            // Only removed once it is empty, so nothing is ever lost in the move.
            try (Stream<Path> left = Files.list(legacy)) {
                if (left.findAny().isEmpty()) Files.delete(legacy);
            }
        } catch (IOException e) {
            System.err.println("EvolutionStore: could not migrate the old catalog folder ("
                    + e.getMessage() + ")");
        }
    }
}
