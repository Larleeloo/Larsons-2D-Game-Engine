package com.larsons.engine.config;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists {@link PlayerSettings} as {@code config/player.json} under the
 * resources folder — deliberately the same shape, the same folder and the same
 * failure rules as {@link com.larsons.engine.input.KeyBindStore}, because it is
 * the same kind of thing: one file describing the person playing, which no
 * game type, level or server may write to.
 *
 * <p>Loading falls back to the classpath so a bundled default set inside a jar
 * still applies, and a missing or unreadable file is simply "the defaults"
 * rather than an error — the game must always start with a volume.
 */
public final class PlayerSettingsStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/config";
    public static final String FILE_NAME = "player.json";

    private final Path dir;

    public PlayerSettingsStore() {
        this(DEFAULT_DIR);
    }

    public PlayerSettingsStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() { return dir; }

    public Path file() { return dir.resolve(FILE_NAME); }

    /** Load the saved settings, or the defaults when there are none. */
    public PlayerSettings load() {
        String json = readJson();
        if (json == null || json.isBlank()) return new PlayerSettings();
        try {
            return PlayerSettings.fromMap(Json.asObject(Json.parse(json)));
        } catch (RuntimeException e) {
            System.err.println("PlayerSettingsStore: unreadable " + file()
                    + " (" + e.getMessage() + ")");
            return new PlayerSettings();
        }
    }

    /** Write the settings out (the file is rewritten wholesale). */
    public Path save(PlayerSettings settings) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), Json.stringify(settings.toMap()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file();
    }

    /**
     * Save without letting a failure reach the player: the options menu writes
     * on every slider drag, and a read-only install directory must cost the
     * player their setting for this session at worst, not crash the menu they
     * are standing in.
     */
    public boolean trySave(PlayerSettings settings) {
        try {
            save(settings);
            return true;
        } catch (RuntimeException e) {
            System.err.println("PlayerSettingsStore: could not write " + file()
                    + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private String readJson() {
        try {
            if (Files.exists(file())) return Files.readString(file());
        } catch (IOException e) {
            System.err.println("PlayerSettingsStore: could not read " + file()
                    + " (" + e.getMessage() + ")");
        }
        try (InputStream in =
                     PlayerSettingsStore.class.getResourceAsStream("/config/" + FILE_NAME)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // treated as "no bundled settings"
        }
        return null;
    }
}
