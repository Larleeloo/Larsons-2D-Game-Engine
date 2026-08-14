package com.larsons.engine.sim;

/**
 * How big an actor is, and the two answers that question has.
 *
 * <p><b>A sprite's size and a hitbox's size are different numbers.</b> They
 * were one number for as long as every character was drawn at whatever size
 * the tile grid implied, and one number is exactly what stops a creator using
 * their own art: a 64&times;64 character drawn at the engine's 0.9 of a tile
 * is a postage stamp, and drawing it bigger by making the body bigger would
 * wall the character out of every gap they used to fit through. So the sprite
 * says how large the character <em>looks</em> and the hitbox says how much
 * floor they <em>occupy</em>, and neither is derived from the other. A giant
 * with a small footprint slips down a corridor their shoulders overhang; a
 * small sprite with a wide footprint is a boulder that happens to be drawn as
 * a pebble. Both are legitimate, and both used to be impossible.
 *
 * <p><b>Sizes are authored in blocks, not pixels.</b> "Two blocks tall" means
 * the same thing in a game type with 16-pixel tiles and one with 96-pixel
 * tiles, and it is the sentence a creator can actually picture — which is what
 * lets one range serve every game type, and what makes {@link #MIN_TILES} a
 * limit worth stating rather than a pixel count that means nothing without
 * knowing the grid.
 */
public final class ActorSize {

    /**
     * The smallest an actor may be, in blocks.
     *
     * <p>A fifth of a block: small enough for a rat, a fairy or a thrown
     * familiar to read as tiny beside the tiles, and large enough to stay
     * visible at the zoom the plan views are usually played at. Below this a
     * body is a few pixels across and stops being a character at all —
     * including to the collision sweep, which needs a footprint it cannot
     * tunnel through a tile boundary in one step.
     */
    public static final double MIN_TILES = 0.2;

    /**
     * The largest an actor may be, in blocks.
     *
     * <p>Eight blocks fills a good part of the screen at any ordinary zoom — a
     * boss that towers over the walls it is walking between rather than one
     * that is merely bigger than the player. The cap exists so a slider has an
     * end and so the sprite sheet a creator draws has a known worst case, not
     * because anything breaks above it.
     */
    public static final double MAX_TILES = 8.0;

    /**
     * What an actor is by default, in blocks: the {@code 0.9} the engine has
     * always drawn and collided a player at, so a game type that never touches
     * these controls plays exactly as it did.
     */
    public static final double DEFAULT_TILES = 0.9;

    private ActorSize() {}

    /** {@code tiles} clamped to the range an actor may be authored in. */
    public static double clampTiles(double tiles) {
        if (Double.isNaN(tiles)) return DEFAULT_TILES;
        return Math.max(MIN_TILES, Math.min(MAX_TILES, tiles));
    }

    /**
     * {@code tiles} of a {@code tileSize} grid, in world pixels — at least one
     * pixel, because a body rounded away to nothing has no position at all.
     *
     * <p>Whole pixels. A body is a rectangle of world that a sweep clamps
     * against tile boundaries and a renderer scales art into, and both are
     * better off with an integer; more to the point it is what
     * {@link com.larsons.engine.config.GameProfile#playerSizeFor} has always
     * answered, so a game type that never touches these controls gets back the
     * size it has always had, to the pixel.
     */
    public static double pixels(double tiles, double tileSize) {
        return Math.max(1, Math.floor(clampTiles(tiles) * tileSize));
    }

    /** The inverse: how many blocks {@code pixels} of world is. */
    public static double tiles(double pixels, double tileSize) {
        return tileSize <= 0 ? DEFAULT_TILES : pixels / tileSize;
    }

    /** {@code pixels} clamped to the range, for sizes stored in world pixels. */
    public static double clampPixels(double pixels, double tileSize) {
        return pixels(tiles(pixels, tileSize), tileSize);
    }

    /** "1.4 blocks" — how a size is written in an editor row and a summary. */
    public static String label(double tiles) {
        return String.format("%.2f", clampTiles(tiles)) + " blocks";
    }
}
