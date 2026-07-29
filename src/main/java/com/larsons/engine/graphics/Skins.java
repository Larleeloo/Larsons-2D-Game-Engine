package com.larsons.engine.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime side of texture overrides: game code asks for the frame of a
 * skinned texture key at a point in time; if a sprite sheet backs that key,
 * the matching frame comes back — otherwise {@code null}, and the caller
 * draws its built-in procedural art. Every skinnable texture goes through
 * here, so one place decides what a block, mob, item, unit or board tile
 * actually looks like.
 *
 * <p>Two sources feed a key, in this order:
 * <ol>
 *   <li>the assignment the player made in a texture dialog / skin menu
 *       ({@link SkinStore}), when it opted out of the texture pack;</li>
 *   <li>the drop-in {@link TexturePack} folder next to the jar, which is
 *       consulted for every key by default — so a creator fills a folder with
 *       correctly-named PNGs and the game picks them up untouched;</li>
 *   <li>the assignment again, as the fallback for a pack that has no file for
 *       this key; and failing that, {@code null} (procedural art).</li>
 * </ol>
 *
 * <p>Sheets are sliced once and cached; a skin whose image can't be found is
 * silently inert (the procedural fallback keeps the game playable).
 */
public final class Skins {

    private static final Map<String, SkinDef> DEFS = new LinkedHashMap<>();
    private static final Map<String, List<BufferedImage>> FRAMES = new HashMap<>();
    /** What actually renders each key, resolved once (a null value = nothing). */
    private static final Map<String, SkinDef> EFFECTIVE = new HashMap<>();

    private Skins() {}

    /** Replace the active skin set (what a {@link SkinStore#load()} feeds in). */
    public static synchronized void install(List<SkinDef> defs) {
        DEFS.clear();
        clearCache();
        for (SkinDef d : defs) {
            if (d.key != null && !d.key.isBlank()) DEFS.put(d.key, d);
        }
    }

    /** Add or replace one skin (the customization menu applies edits live). */
    public static synchronized void put(SkinDef def) {
        DEFS.put(def.key, def);
        forget(def.key);
    }

    public static synchronized void remove(String key) {
        DEFS.remove(key);
        forget(key);
    }

    public static synchronized SkinDef get(String key) {
        return DEFS.get(key);
    }

    public static synchronized List<SkinDef> all() {
        return new ArrayList<>(DEFS.values());
    }

    /**
     * Drop every resolved texture, so the next draw re-reads the assignments
     * and re-scans the texture pack folder — what "rescan the texture pack"
     * does after PNGs are added while the game is running.
     */
    public static synchronized void clearCache() {
        FRAMES.clear();
        EFFECTIVE.clear();
    }

    private static void forget(String key) {
        FRAMES.remove(key);
        EFFECTIVE.remove(key);
    }

    /**
     * The definition that actually renders {@code key} — the player's
     * assignment, the texture pack's file for it, or {@code null} when
     * neither exists and the built-in art stands.
     */
    public static synchronized SkinDef effective(String key) {
        if (EFFECTIVE.containsKey(key)) return EFFECTIVE.get(key);
        SkinDef resolved = resolve(key);
        EFFECTIVE.put(key, resolved);
        return resolved;
    }

    private static SkinDef resolve(String key) {
        SkinDef def = DEFS.get(key);
        // Opted out of the pack: the assigned sheet is the whole answer.
        if (def != null && !def.usePack) return def.hasSheet() ? def : null;
        SkinDef packed = TexturePack.lookup(key);
        if (packed != null) return packed;
        return def != null && def.hasSheet() ? def : null;
    }

    /**
     * The frame of {@code key}'s skin playing at {@code seconds}, or
     * {@code null} when the key has no (working) skin — the caller's cue to
     * draw its procedural default.
     */
    public static synchronized BufferedImage frame(String key, double seconds) {
        SkinDef def = effective(key);
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
     * The frame of {@code key}'s sheet at {@code progress} (0..1) through a
     * <em>one-shot</em> animation, rather than at a point in time: the sheet
     * is stretched over the whole action, so a melee swing's art plays exactly
     * once across the swing however long that particular weapon takes to
     * throw it. Returns {@code null} when the key has no (working) skin, like
     * {@link #frame}.
     */
    public static synchronized BufferedImage progressFrame(String key, double progress) {
        SkinDef def = effective(key);
        if (def == null) return null;
        List<BufferedImage> frames = FRAMES.get(key);
        if (frames == null) {
            frames = slice(def);
            FRAMES.put(key, frames);
        }
        if (frames.isEmpty()) return null;
        double t = progress < 0 ? 0 : Math.min(0.999999, progress);
        return frames.get(Math.min(frames.size() - 1, (int) (t * frames.size())));
    }

    /**
     * The icon a menu should show for a texture key: the first frame of
     * whatever texture actually renders it, scaled into a {@code size} square,
     * or {@code fallback} (the caller's built-in art) when nothing does. This
     * is what keeps the creative palette's swatches honest — a reskinned block
     * looks reskinned in the sidebar too.
     */
    public static BufferedImage icon(String key, BufferedImage fallback, int size) {
        BufferedImage frame = frame(key, 0);
        if (frame == null || size <= 0) return fallback;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // Pixel art stays crisp at icon size; smooth scaling would blur it.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        double scale = Math.min(size / (double) frame.getWidth(),
                size / (double) frame.getHeight());
        int w = Math.max(1, (int) Math.round(frame.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(frame.getHeight() * scale));
        g.drawImage(frame, (size - w) / 2, (size - h) / 2, w, h, null);
        g.dispose();
        return out;
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
