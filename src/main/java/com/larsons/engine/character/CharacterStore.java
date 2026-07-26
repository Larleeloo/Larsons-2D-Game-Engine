package com.larsons.engine.character;

import com.larsons.engine.level.LevelStore;
import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the character profiles a creator adds with the creative palette's
 * "+&nbsp;New Character" entry, and re-registers them into {@link Characters}
 * whenever the game type loads — the same shape as
 * {@link com.larsons.engine.config.CustomContentStore}, so a saved level that
 * names a character on its roster keeps working across sessions.
 *
 * <p>Profiles live in {@code characters.json} beside the game type's levels.
 */
public final class CharacterStore {

    /** Filename holding a game type's characters, beside its levels. */
    public static final String FILE_NAME = "characters.json";

    private final Path file;
    private final List<CharacterProfile> profiles = new ArrayList<>();

    public CharacterStore(String gameTypeName) {
        this.file = new LevelStore(gameTypeName).directory().resolve(FILE_NAME);
    }

    /** Root-dir override, mirroring {@link LevelStore}'s (used by tests). */
    public CharacterStore(String rootDir, String gameTypeName) {
        this.file = new LevelStore(rootDir, gameTypeName).directory().resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /** The profiles this store holds (the built-in default is not among them). */
    public List<CharacterProfile> all() {
        return new ArrayList<>(profiles);
    }

    /**
     * Load {@code characters.json} (if any) and register everything it defines.
     * Safe to call repeatedly — the registry is reset to the built-in default
     * first, so switching game types never leaks another type's characters.
     */
    public void loadAndRegister() {
        profiles.clear();
        Characters.reset();
        if (!Files.exists(file)) return;
        Map<String, Object> root;
        try {
            root = Json.asObject(Json.parse(Files.readString(file)));
        } catch (IOException | RuntimeException e) {
            System.err.println("CharacterStore: failed to read " + file + ": " + e.getMessage());
            return;
        }
        if (root.get("characters") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                try {
                    CharacterProfile p = CharacterProfile.fromMap(Json.asObject(raw));
                    profiles.add(p);
                    Characters.register(p);
                } catch (RuntimeException e) {
                    System.err.println("CharacterStore: bad character entry: " + e.getMessage());
                }
            }
        }
    }

    /** A unique, registry-safe key derived from a display name. */
    public static String keyFor(String name) {
        return com.larsons.engine.config.CustomContentStore.keyFor(name, Characters::exists);
    }

    /** Register + persist a new profile. */
    public void add(CharacterProfile profile) {
        if (profile == null) return;
        profile.normalize();
        Characters.register(profile);
        profiles.removeIf(p -> p.key.equals(profile.key));
        profiles.add(profile);
        save();
    }

    /** Whether this profile is one the creator made (vs. the built-in default). */
    public boolean isCustom(String key) {
        for (CharacterProfile p : profiles) {
            if (p.key.equals(key)) return true;
        }
        return false;
    }

    /**
     * Delete a creator-made profile: removed from {@code characters.json} and
     * unregistered live. Levels whose roster names it simply stop offering it.
     */
    public boolean remove(String key) {
        if (!profiles.removeIf(p -> p.key.equals(key))) return false;
        Characters.unregister(key);
        save();
        return true;
    }

    private void save() {
        List<Object> list = new ArrayList<>(profiles.size());
        for (CharacterProfile p : profiles) list.add(p.toMap());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("characters", list);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.stringify(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
