package com.larsons.engine.graphics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One texture override: a sprite sheet assigned to a game texture key,
 * defined by frame pixel width/height, frame count, and a playback rate of
 * 0-120 sprite frames per second (0 = a static image, frame 0 only).
 *
 * <p>Keys are slash-separated paths naming what gets reskinned, e.g.
 * {@code unit/squire/attack} (a unit's animation state),
 * {@code item/sword}, {@code projectile/arrow}, or {@code board/tile_a}.
 * Serializable to/from JSON maps so a whole set persists as a
 * {@code skins.json} in the player's game files (see {@link SkinStore}).
 */
public final class SkinDef {

    public static final double MAX_FPS = 120;

    public final String key;
    /** Sheet image path, resolved classpath-first then filesystem. */
    public final String sheet;
    public final int frameWidth;
    public final int frameHeight;
    public final int frameCount;
    /** Sprite frames per second in [0, {@value #MAX_FPS}]; 0 = static. */
    public final double fps;

    public SkinDef(String key, String sheet, int frameWidth, int frameHeight,
                   int frameCount, double fps) {
        this.key = key;
        this.sheet = sheet;
        this.frameWidth = Math.max(1, frameWidth);
        this.frameHeight = Math.max(1, frameHeight);
        this.frameCount = Math.max(1, frameCount);
        this.fps = clampFps(fps);
    }

    /** Playback rate clamped to the supported 0-120 fps range. */
    public static double clampFps(double fps) {
        return Math.max(0, Math.min(MAX_FPS, fps));
    }

    /** The frame index playing at {@code seconds} into the animation. */
    public int frameAt(double seconds) {
        if (fps <= 0 || frameCount <= 1) return 0;
        return (int) Math.floor(seconds * fps) % frameCount;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("sheet", sheet);
        m.put("frameWidth", frameWidth);
        m.put("frameHeight", frameHeight);
        m.put("frameCount", frameCount);
        m.put("fps", fps);
        return m;
    }

    public static SkinDef fromMap(Map<String, Object> m) {
        return new SkinDef(
                str(m.get("key")), str(m.get("sheet")),
                intOf(m.get("frameWidth")), intOf(m.get("frameHeight")),
                intOf(m.get("frameCount")),
                m.get("fps") instanceof Number n ? n.doubleValue() : 0);
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 1;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }
}
