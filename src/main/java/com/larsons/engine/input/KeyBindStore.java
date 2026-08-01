package com.larsons.engine.input;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists {@link KeyBinds} as {@code config/keybinds.json} under the resources
 * folder — the same "plain files next to the game" model as
 * {@link com.larsons.engine.config.GameTypeStore} and
 * {@link com.larsons.engine.graphics.SkinStore}, so a player's controls can be
 * copied between machines (or handed to a friend) as one readable file.
 *
 * <p>Binds are a property of the person playing, not of a game type, so there
 * is a single file rather than one per profile: rebinding jump once means jump
 * is rebound in every game type, every level and every mode.
 *
 * <p>Loading falls back to the classpath so a bundled default set inside a jar
 * still applies, and a missing or unreadable file is simply "the shipped
 * defaults" rather than an error — the game must always start with working
 * controls.
 */
public final class KeyBindStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/config";
    public static final String FILE_NAME = "keybinds.json";

    private final Path dir;

    public KeyBindStore() {
        this(DEFAULT_DIR);
    }

    public KeyBindStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() { return dir; }

    public Path file() { return dir.resolve(FILE_NAME); }

    /** Load the saved binds, or the shipped defaults when there are none. */
    public KeyBinds load() {
        String json = readJson();
        if (json == null || json.isBlank()) return KeyBinds.defaults();
        try {
            return KeyBinds.fromMap(Json.asObject(Json.parse(json)));
        } catch (RuntimeException e) {
            System.err.println("KeyBindStore: unreadable " + file() + " (" + e.getMessage() + ")");
            return KeyBinds.defaults();
        }
    }

    /** Write the binds out (the file is rewritten wholesale). */
    public Path save(KeyBinds binds) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), Json.stringify(binds.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file();
    }

    /**
     * Save without letting a failure reach the player: the controls menu writes
     * on every change, and a read-only install directory must cost the player
     * their rebind for this session at worst, not crash the menu they are
     * standing in.
     */
    public boolean trySave(KeyBinds binds) {
        try {
            save(binds);
            return true;
        } catch (RuntimeException e) {
            System.err.println("KeyBindStore: could not write " + file()
                    + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private String readJson() {
        try {
            if (Files.exists(file())) return Files.readString(file());
        } catch (IOException e) {
            System.err.println("KeyBindStore: could not read " + file()
                    + " (" + e.getMessage() + ")");
        }
        try (InputStream in = KeyBindStore.class.getResourceAsStream("/config/" + FILE_NAME)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // treated as "no bundled binds"
        }
        return null;
    }
}
