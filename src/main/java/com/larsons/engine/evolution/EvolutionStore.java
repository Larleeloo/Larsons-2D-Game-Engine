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
 * Everything the evolution game keeps on disk, all of it JSON.
 *
 * <p>Two separate things live here, deliberately:
 * <ul>
 *   <li><b>The save</b> — {@code evolution/save.json}, one file holding the
 *       dishes and everything in them, the credit balance, the bench and the
 *       book's index. Loading it puts the player back exactly where they left.</li>
 *   <li><b>The catalog</b> — {@code evolution/catalog/<DNA>.json}, <em>one file
 *       per discovered strand</em>. The design document asks for catalogued
 *       organisms to be stored as unique JSON files rather than looked up in a
 *       reference table, so each discovery really is its own artefact on disk,
 *       named after the DNA that produced it and readable on its own.</li>
 * </ul>
 *
 * <p>The catalog outlives any individual save: starting a new experiment does
 * not erase what previous ones found, which is what makes the book worth
 * filling in.
 */
public final class EvolutionStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/evolution";
    public static final String SAVE_FILE = "save.json";
    public static final String CATALOG_DIR = "catalog";

    private final Path dir;

    public EvolutionStore() {
        this(DEFAULT_DIR);
    }

    public EvolutionStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() { return dir; }

    public Path saveFile() { return dir.resolve(SAVE_FILE); }

    public Path catalogDirectory() { return dir.resolve(CATALOG_DIR); }

    public boolean hasSave() { return Files.exists(saveFile()); }

    // --- the save ---------------------------------------------------------------------

    /**
     * Write the experiment out, plus a JSON file for every strand catalogued
     * since the last save. Returns the save file's path.
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
        writeNewSpecies(game.catalog());
        return saveFile();
    }

    /**
     * Load the saved experiment, with its reference book repopulated from the
     * individual species files. Returns {@code null} when there is no save, or
     * when the one on disk cannot be read — a corrupt save should offer a new
     * game, not crash the menu.
     */
    public EvolutionGame load() {
        if (!hasSave()) return null;
        try {
            Object parsed = Json.parse(Files.readString(saveFile()));
            EvolutionGame game = EvolutionGame.fromMap(Json.asObject(parsed));
            if (game == null) return null;
            for (SpeciesRecord rec : loadSpecies()) game.catalog().restore(rec);
            // Restored records are already on disk; do not rewrite them.
            game.catalog().drainPendingWrites();
            return game;
        } catch (IOException | RuntimeException e) {
            System.err.println("EvolutionStore: unreadable save " + saveFile()
                    + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Just the book's index from the save — which achievements are unlocked and
     * which colony combinations have formed — without touching the species
     * files. The reference book needs this every time it opens, and a full
     * {@link #load()} would re-parse every discovery on disk to get it.
     */
    public Catalog loadCatalogIndex() {
        if (!hasSave()) return new Catalog();
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(saveFile())));
            Object catalog = root.get("catalog");
            return catalog instanceof Map<?, ?> m ? Catalog.fromMap(Json.asObject(m)) : new Catalog();
        } catch (IOException | RuntimeException e) {
            return new Catalog();
        }
    }

    /** Remove the save (the catalog is left alone — discoveries are permanent). */
    public boolean deleteSave() {
        try {
            return Files.deleteIfExists(saveFile());
        } catch (IOException e) {
            return false;
        }
    }

    // --- the catalog ----------------------------------------------------------------------

    /** Write out every strand catalogued since the last call. */
    public void writeNewSpecies(Catalog catalog) {
        List<SpeciesRecord> pending = catalog.drainPendingWrites();
        if (pending.isEmpty()) return;
        try {
            Files.createDirectories(catalogDirectory());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (SpeciesRecord rec : pending) writeSpecies(rec);
    }

    /**
     * Write one discovery as its own file, named after its DNA. A strand is
     * only ever discovered once, so an existing file is left untouched — the
     * first sighting is the one that counts.
     */
    public Path writeSpecies(SpeciesRecord rec) {
        Path file = speciesFile(rec.sequence);
        try {
            Files.createDirectories(catalogDirectory());
            if (!Files.exists(file)) Files.writeString(file, Json.stringify(rec.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public Path speciesFile(String sequence) {
        return catalogDirectory().resolve(sequence + ".json");
    }

    /**
     * Every discovery on disk, newest first. This is what the reference book
     * scene reads: it contains exactly what has been created, and nothing else.
     */
    public List<SpeciesRecord> loadSpecies() {
        List<SpeciesRecord> out = new ArrayList<>();
        Path catDir = catalogDirectory();
        if (!Files.isDirectory(catDir)) return out;
        try (Stream<Path> files = Files.list(catDir)) {
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

    /** How many discoveries the book holds, without parsing them all. */
    public int speciesFileCount() {
        Path catDir = catalogDirectory();
        if (!Files.isDirectory(catDir)) return 0;
        try (Stream<Path> files = Files.list(catDir)) {
            return (int) files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Read one discovery straight off disk, or {@code null} if it is not catalogued. */
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
}
