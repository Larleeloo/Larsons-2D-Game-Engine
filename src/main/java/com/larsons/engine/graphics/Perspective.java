package com.larsons.engine.graphics;

/**
 * Supported 2D camera projections (requirement #2: multiple 2D perspectives).
 *
 * <p>{@link #SIDE_SCROLL} and {@link #TOP_DOWN} share an orthographic transform
 * but differ in gameplay convention — side-scrollers typically apply gravity
 * along +Y while top-down games allow free movement on both axes.
 * {@link #ISOMETRIC} projects a square tile grid into a diamond.
 *
 * <p>The {@link Camera} consumes this enum to choose how world coordinates map
 * to the screen, so a game can switch perspective at runtime.
 */
public enum Perspective {
    SIDE_SCROLL,
    TOP_DOWN,
    ISOMETRIC;

    /** Next perspective in declaration order (wraps). Handy for demos/toggles. */
    public Perspective next() {
        Perspective[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
