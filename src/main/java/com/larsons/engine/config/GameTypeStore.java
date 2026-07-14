package com.larsons.engine.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads and writes {@link GameProfile} "game types" as JSON files.
 *
 * <p>Per the design, game types live under the resources folder
 * ({@code src/main/resources/gametypes/} by default). New profiles are written
 * there so they sit alongside any bundled examples and are picked up next launch.
 * Loading falls back to the classpath, so example profiles bundled inside a jar
 * still load when running from outside the project tree.
 *
 * <p>The save directory is configurable (the constructor) so tests can use a
 * temporary location.
 */
public class GameTypeStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/gametypes";

    private final Path dir;

    public GameTypeStore() {
        this(DEFAULT_DIR);
    }

    public GameTypeStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() {
        return dir;
    }

    /** Display names of all game types found on disk. */
    public List<String> listNames() {
        List<String> names = new ArrayList<>();
        for (GameProfile p : listProfiles()) {
            names.add(p.name);
        }
        return names;
    }

    /** Load every game type found in the directory (skips unreadable files). */
    public List<GameProfile> listProfiles() {
        List<GameProfile> profiles = new ArrayList<>();
        if (!Files.isDirectory(dir)) return profiles;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            profiles.add(GameProfile.fromJson(Files.readString(p)));
                        } catch (RuntimeException | IOException e) {
                            System.err.println("GameTypeStore: skipping unreadable profile "
                                    + p + " (" + e.getMessage() + ")");
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return profiles;
    }

    public boolean exists(String name) {
        return Files.exists(fileFor(name)) || classpathStream(name) != null;
    }

    /** Load a profile by its (display) name. Filesystem first, then classpath. */
    public GameProfile load(String name) {
        Path file = fileFor(name);
        try {
            if (Files.exists(file)) {
                return GameProfile.fromJson(Files.readString(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (InputStream in = classpathStream(name)) {
            if (in != null) {
                return GameProfile.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("Game type not found: " + name);
    }

    /** Persist a profile as {@code <dir>/<sanitized-name>.json}. */
    public Path save(GameProfile profile) {
        profile.normalize();
        Path file = fileFor(profile.name);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, profile.toJson());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public Path fileFor(String name) {
        return dir.resolve(fileName(name));
    }

    /** Delete a game type's profile file; returns whether one existed. */
    public boolean delete(String name) {
        try {
            return Files.deleteIfExists(fileFor(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Turn a display name into a safe filename. */
    public static String fileName(String name) {
        String base = name == null ? "" : name.trim();
        base = base.replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", "_");
        if (base.isEmpty()) base = "untitled";
        return base.toLowerCase() + ".json";
    }

    private static InputStream classpathStream(String name) {
        return GameTypeStore.class.getResourceAsStream("/gametypes/" + fileName(name));
    }
}
