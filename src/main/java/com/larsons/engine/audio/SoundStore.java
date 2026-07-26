package com.larsons.engine.audio;

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
 * Persists the creator's sound assignments as {@code sounds.json} under the
 * resources sounds folder — the same "individual game files" model as
 * {@link com.larsons.engine.graphics.SkinStore}, which this mirrors. Loading
 * falls back to the classpath, so a sound set bundled inside a shared jar
 * still applies out of the box.
 *
 * <p>Only the <em>exceptions</em> live here: which sounds point at a file
 * outside the pack, and any per-sound volume/pitch/loop the creator set. The
 * audio itself stays in the {@link SoundPack} folder, so a pack can be handed
 * to someone else on its own.
 */
public final class SoundStore {

    /** Default on-disk location, under the project's resources folder. */
    public static final String DEFAULT_DIR = "src/main/resources/sounds";
    public static final String FILE_NAME = "sounds.json";

    private final Path dir;

    public SoundStore() {
        this(DEFAULT_DIR);
    }

    public SoundStore(String dir) {
        this.dir = Path.of(dir);
    }

    public Path directory() {
        return dir;
    }

    public Path file() {
        return dir.resolve(FILE_NAME);
    }

    /** Load every saved assignment; an absent/unreadable file is just "none". */
    public List<SoundDef> load() {
        String json = readJson();
        List<SoundDef> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<String, Object> root = Json.asObject(Json.parse(json));
            if (root.get("sounds") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) out.add(SoundDef.fromMap(Json.asObject(m)));
                }
            }
        } catch (RuntimeException e) {
            System.err.println("SoundStore: unreadable " + file() + " (" + e.getMessage() + ")");
        }
        return out;
    }

    /** Persist the full assignment set (the file is rewritten wholesale). */
    public Path save(List<SoundDef> sounds) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>(sounds.size());
        for (SoundDef s : sounds) list.add(s.toMap());
        root.put("sounds", list);
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
            System.err.println("SoundStore: could not read " + file()
                    + " (" + e.getMessage() + ")");
        }
        // Classpath fallback: sounds bundled inside a jar.
        try (InputStream in = SoundStore.class.getResourceAsStream("/sounds/" + FILE_NAME)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // treated as "no bundled assignments"
        }
        return null;
    }
}
