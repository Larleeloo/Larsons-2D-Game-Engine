package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime side of texture overrides: game code asks for the frame of a
 * skinned texture key at a point in time; if the player assigned a sprite
 * sheet to that key (via the skin customization menu / {@link SkinStore}),
 * the matching frame comes back — otherwise {@code null}, and the caller
 * draws its built-in procedural art. Every skinnable texture goes through
 * here, so dropping a PNG in {@code resources/skins/} and assigning it
 * overrides units (per animation state), items, projectiles, board tiles...
 *
 * <p>Sheets are sliced once and cached; a skin whose image can't be found is
 * silently inert (the procedural fallback keeps the game playable).
 */
public final class Skins {

    private static final Map<String, SkinDef> DEFS = new LinkedHashMap<>();
    private static final Map<String, List<BufferedImage>> FRAMES = new HashMap<>();

    private Skins() {}

    /** Replace the active skin set (what a {@link SkinStore#load()} feeds in). */
    public static synchronized void install(List<SkinDef> defs) {
        DEFS.clear();
        FRAMES.clear();
        for (SkinDef d : defs) {
            if (d.key != null && !d.key.isBlank()) DEFS.put(d.key, d);
        }
    }

    /** Add or replace one skin (the customization menu applies edits live). */
    public static synchronized void put(SkinDef def) {
        DEFS.put(def.key, def);
        FRAMES.remove(def.key);
    }

    public static synchronized void remove(String key) {
        DEFS.remove(key);
        FRAMES.remove(key);
    }

    public static synchronized SkinDef get(String key) {
        return DEFS.get(key);
    }

    public static synchronized List<SkinDef> all() {
        return new ArrayList<>(DEFS.values());
    }

    /**
     * The frame of {@code key}'s skin playing at {@code seconds}, or
     * {@code null} when the key has no (working) skin — the caller's cue to
     * draw its procedural default.
     */
    public static synchronized BufferedImage frame(String key, double seconds) {
        SkinDef def = DEFS.get(key);
        if (def == null) return null;
        List<BufferedImage> frames = FRAMES.get(key);
        if (frames == null) {
            frames = slice(def);
            FRAMES.put(key, frames);
        }
        if (frames.isEmpty()) return null;
        return frames.get(Math.min(frames.size() - 1, def.frameAt(seconds)));
    }

    /**
     * A unit's frame for an animation state ({@code unit/<key>/<state>}),
     * falling back to the unit's {@code idle} skin so one sheet is enough to
     * reskin a unit everywhere.
     */
    public static BufferedImage unitFrame(String unitKey, String stateKey, double seconds) {
        BufferedImage img = frame("unit/" + unitKey + "/" + stateKey, seconds);
        if (img == null && !"idle".equals(stateKey)) {
            img = frame("unit/" + unitKey + "/idle", seconds);
        }
        return img;
    }

    private static List<BufferedImage> slice(SkinDef def) {
        BufferedImage sheet = AssetLoader.loadImageOrNull(def.sheet);
        if (sheet == null || sheet.getWidth() < def.frameWidth
                || sheet.getHeight() < def.frameHeight) {
            return List.of();
        }
        List<BufferedImage> all = SpriteSheet
                .fromImage(sheet, def.frameWidth, def.frameHeight).frames();
        if (all.isEmpty()) return List.of();
        return List.copyOf(all.subList(0, Math.min(def.frameCount, all.size())));
    }
}
