package com.larsons.engine.graphics;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the player's texture overrides as {@code skins.json} under the
 * resources skins folder, next to the sheet images they reference — the same
 * "individual game files" model as {@link com.larsons.engine.config.GameTypeStore}.
 * Loading falls back to the classpath, so a skin set bundled inside a shared
 * jar still applies out of the box.
 */
public final class SkinStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/skins";
    public static final String FILE_NAME = "skins.json";

    private final Path dir;

    public SkinStore() {
        this(DEFAULT_DIR);
    }

    public SkinStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() {
        return dir;
    }

    public Path file() {
        return dir.resolve(FILE_NAME);
    }

    /** Load every saved skin; an absent/unreadable file is just "no skins". */
    public List<SkinDef> load() {
        String json = readJson();
        List<SkinDef> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> root = Json.asObject(Json.parse(json));
            if (root.get("skins") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) out.add(SkinDef.fromMap(Json.asObject(m)));
                }
            }
        } catch (RuntimeException e) {
            System.err.println("SkinStore: unreadable " + file() + " (" + e.getMessage() + ")");
        }
        return out;
    }

    /** Persist the full skin set (the file is rewritten wholesale). */
    public Path save(List<SkinDef> skins) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>(skins.size());
        for (SkinDef s : skins) list.add(s.toMap());
        root.put("skins", list);
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), Json.stringify(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file();
    }

    private String readJson() {
        try {
            if (Files.exists(file())) return Files.readString(file());
        } catch (IOException e) {
            System.err.println("SkinStore: could not read " + file() + " (" + e.getMessage() + ")");
        }
        // Classpath fallback: skins bundled inside a jar.
        try (InputStream in = SkinStore.class.getResourceAsStream("/skins/" + FILE_NAME)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // treated as "no bundled skins"
        }
        return null;
    }
}
