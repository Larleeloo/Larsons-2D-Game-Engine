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
 * to the screen. A level's projection is fixed for the life of that level: a
 * {@code com.larsons.engine.level.LevelFormat} is not a view of a world but a
 * kind of world, differing in which axis is up and in how many layers of blocks
 * its geometry is written in. Walking through a door into a level of another
 * format is how a game changes perspective.
 *
 * <p>This enum is only about <em>projection</em>. The matching physical space —
 * which axis is up, where gravity pulls, how height is drawn — is
 * {@code com.larsons.engine.sim.PerspectiveSpace}, which every simulation and
 * effect reads instead of assuming a side-scroller's screen.
 */
public enum Perspective {
    SIDE_SCROLL,
    TOP_DOWN,
    ISOMETRIC
}
