package com.larsons.engine.graphics;

/**
 * The two kinds of world the engine builds: a side-scroller and a 3D one.
 *
 * <p>{@link #SIDE_SCROLL} is the classic platformer — the screen <em>is</em> the
 * vertical plane, gravity pulls down it, and there is no third axis. Nothing
 * about it turns, tilts, or stacks.
 *
 * <p>{@link #THREE_D} is the other one: the world grid is the <em>floor</em>,
 * height is an axis of its own, and the camera stands off the floor looking down
 * at it. Two things about that camera are the player's to move —
 * {@link Camera#turn} swings it round the eight compass points, and
 * {@link Camera#tilt} raises and lowers it freely between
 * {@link Camera#MIN_PITCH} and {@link Camera#MAX_PITCH} — and between them they
 * cover every plan view this engine used to spell out as a separate kind of
 * world. A camera brought most of the way down is the oblique three-quarter view
 * that used to be called "isometric"; a camera taken to the top of its travel is
 * the straight-down view that used to be called "top-down". They were never two
 * worlds, only two places to stand in one, and the tilt is what makes that
 * visible instead of a decision taken once when the level was created.
 *
 * <p>The {@link Camera} consumes this enum to choose how world coordinates map
 * to the screen. A level's kind is fixed for the life of that level: a
 * {@code com.larsons.engine.level.LevelFormat} is not a view of a world but a
 * kind of world, differing in which axis is up and in how many layers of blocks
 * its geometry is written in. Walking through a door into a level of the other
 * kind is how a game changes between them.
 *
 * <p>This enum is only about <em>projection</em>. The matching physical space —
 * which axis is up, where gravity pulls, how height is drawn — is
 * {@code com.larsons.engine.sim.PerspectiveSpace}, which every simulation and
 * effect reads instead of assuming a side-scroller's screen.
 *
 * <p><b>Older files still load.</b> Levels and game types saved when the plan
 * views were two separate formats carry {@code "TOP_DOWN"} or
 * {@code "ISOMETRIC"}; both parse to {@link #THREE_D}, which is the world they
 * always were.
 */
public enum Perspective {

    /** The side view: gravity down the screen, one layer of blocks, no tilt. */
    SIDE_SCROLL("Side-Scroller"),

    /** The floor seen from a camera that turns around it and tilts over it. */
    THREE_D("3D");

    private final String label;

    Perspective(String label) { this.label = label; }

    /** What a player-facing menu calls this — never the enum name. */
    public String label() { return label; }

    /**
     * The perspective a saved name means, tolerating the names the two former
     * plan-view formats were written under. Unknown text falls back to
     * {@code def}, so a file from a future version still loads.
     */
    public static Perspective of(String text, Perspective def) {
        if (text == null || text.isBlank()) return def;
        String s = text.trim().toUpperCase();
        return switch (s) {
            case "SIDE_SCROLL", "SIDE_SCROLLER" -> SIDE_SCROLL;
            // The two plan views this replaced, and the names they were saved
            // under. A level authored as either one is a 3D level; only the
            // camera's resting tilt ever told them apart.
            case "THREE_D", "3D", "TOP_DOWN", "ISOMETRIC" -> THREE_D;
            default -> def;
        };
    }

    @Override
    public String toString() { return label; }
}
