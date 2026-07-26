package com.larsons.engine.character;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The live registry of playable {@link CharacterProfile}s, in the same spirit
 * as the block/mob/item registries: a game type's {@link CharacterStore} loads
 * its {@code characters.json} in here on entry, and everything that needs to
 * resolve a character key — the creative palette, a level's roster, the
 * character picker at level start, the renderer — reads it from one place.
 *
 * <p>The built-in {@link CharacterProfile#DEFAULT_KEY default} character is
 * always registered, so a game type that never created one still has something
 * to play as and a level with an empty roster is never unplayable.
 */
public final class Characters {

    private static final Map<String, CharacterProfile> BY_KEY = new LinkedHashMap<>();

    static {
        reset();
    }

    private Characters() {}

    /** Drop every custom profile, leaving only the built-in default. */
    public static synchronized void reset() {
        BY_KEY.clear();
        CharacterProfile def = CharacterProfile.defaultProfile();
        BY_KEY.put(def.key, def);
    }

    /** Add or replace a profile (the store and the editor both do this). */
    public static synchronized void register(CharacterProfile profile) {
        if (profile == null || profile.key == null || profile.key.isBlank()) return;
        profile.normalize();
        BY_KEY.put(profile.key, profile);
    }

    /** Remove a profile; the built-in default cannot be removed. */
    public static synchronized boolean unregister(String key) {
        if (CharacterProfile.DEFAULT_KEY.equals(key)) return false;
        return BY_KEY.remove(key) != null;
    }

    /** The profile with this key, or {@code null}. */
    public static synchronized CharacterProfile get(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The profile with this key, or the built-in default (never {@code null}). */
    public static synchronized CharacterProfile getOrDefault(String key) {
        CharacterProfile p = get(key);
        return p != null ? p : BY_KEY.get(CharacterProfile.DEFAULT_KEY);
    }

    /** Every registered profile, default first, then creation order. */
    public static synchronized List<CharacterProfile> all() {
        return new ArrayList<>(BY_KEY.values());
    }

    public static synchronized boolean exists(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /**
     * The profiles a level offers at its start: those on its roster that still
     * exist, or — when the roster is empty or names only deleted profiles —
     * every registered profile, so a level is never unplayable.
     */
    public static synchronized List<CharacterProfile> rosterFor(List<String> keys) {
        List<CharacterProfile> out = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                CharacterProfile p = BY_KEY.get(key);
                if (p != null && !out.contains(p)) out.add(p);
            }
        }
        return out.isEmpty() ? all() : out;
    }
}
