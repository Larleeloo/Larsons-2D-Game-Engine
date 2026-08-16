package com.larsons.engine.graphics;

/**
 * Where the player's camera stands — the {@code F5} cycle every 3D game has.
 *
 * <p><b>This is not {@link Perspective}, and the distinction is the whole
 * reason it is a separate enum.</b> A {@code Perspective} is a property of the
 * <em>level</em>: a {@code com.larsons.engine.level.LevelFormat} is not a view
 * of a world but a kind of world, and walking through a door into a level of
 * another format is how a game changes it. A {@code Viewpoint} is a property of
 * the <em>player</em>, chosen while playing, never networked, and thrown away
 * when they leave. One is what the world is; the other is where you are
 * standing to look at it.
 *
 * <p>{@link #PLAN} is the engine's own view — the level's projection, drawn by
 * {@link Camera} exactly as it always has been. The other three put the camera
 * <em>in</em> the world at an eye height and look along a heading, drawn by
 * {@link SolidPainter} through an {@link EyeCamera}, which is what makes them
 * read like Minecraft rather than like a plan view someone zoomed in on.
 *
 * <p><b>Why {@code PLAN} is in the cycle at all.</b> Minecraft has three modes
 * because it only ever had one kind of world. This engine's native view is the
 * plan one — every level was authored through it, and its terrain cache, its
 * depth pass and its eight-point camera are all built for it — so removing it
 * from the cycle would make the toggle a one-way door. It is also first, which
 * is what keeps the key a <em>feature</em> rather than a change: a session
 * starts exactly where it always did and nothing about an existing level looks
 * different until somebody presses F5.
 *
 * <p><b>Only where there is a height axis.</b> A side-scroller's screen
 * <em>is</em> the vertical plane — there is no third axis to stand an eye in,
 * and no heading to look along — so {@link #availableIn} refuses the solid
 * modes there and the cycle collapses to {@link #PLAN} alone. That is the same
 * answer {@link Camera#rotates()} gives for the same reason.
 */
public enum Viewpoint {

    /** The level's own projection: what the engine has always drawn. */
    PLAN("Plan view", 0, true, false),

    /** Behind the eyes. The body is not drawn; the held object is. */
    FIRST_PERSON("First person", 0, false, false),

    /**
     * Over the shoulder, looking the way the player looks — Minecraft's
     * second F5 stop, and the one people actually play in.
     */
    THIRD_PERSON_BACK("Third person", 3.4, true, false),

    /**
     * In front, looking back at the player — Minecraft's third stop. The
     * camera stands where the player is looking and turns round, so you see
     * your own face.
     */
    THIRD_PERSON_FRONT("Third person, front", 3.4, true, true);

    private final String label;
    private final double distanceTiles;
    private final boolean showsSelf;
    private final boolean reversed;

    Viewpoint(String label, double distanceTiles, boolean showsSelf, boolean reversed) {
        this.label = label;
        this.distanceTiles = distanceTiles;
        this.showsSelf = showsSelf;
        this.reversed = reversed;
    }

    /** What the HUD calls this view. */
    public String label() { return label; }

    /**
     * Whether this view is drawn by {@link SolidPainter} through an
     * {@link EyeCamera} rather than by {@link Camera}'s flat projection.
     *
     * <p>The one question every call site in a scene actually asks, and the
     * reason it is phrased as a capability rather than as {@code != PLAN}:
     * what changes between the two is which renderer runs, not which enum
     * constant is held.
     */
    public boolean solid() { return this != PLAN; }

    /**
     * How far behind the eye the camera is pulled, in tiles. Zero in first
     * person, where the camera <em>is</em> the eye.
     */
    public double distanceTiles() { return distanceTiles; }

    /**
     * Whether the player's own body is drawn. False in first person only —
     * the same rule every 3D game follows, and for the same reason: a
     * billboard drawn at the camera's own position fills the screen.
     */
    public boolean showsSelf() { return showsSelf; }

    /**
     * Whether the camera looks back along the player's heading rather than
     * along it. True in {@link #THIRD_PERSON_FRONT} alone.
     */
    public boolean reversed() { return reversed; }

    /**
     * Whether this view can be used in a space with (or without) a height
     * axis. Only {@link #PLAN} survives a side-scroller; see the class note.
     */
    public boolean availableIn(boolean hasElevation) {
        return hasElevation || this == PLAN;
    }

    /**
     * The next stop in the cycle, skipping anything this space cannot show.
     *
     * <p>Written as a bounded walk rather than {@code (ordinal + 1) % length}
     * so a space that allows only one view returns that view instead of
     * looping forever, and so adding a mode later cannot introduce a stop the
     * cycle silently jumps over.
     */
    public Viewpoint next(boolean hasElevation) {
        Viewpoint[] all = values();
        for (int i = 1; i <= all.length; i++) {
            Viewpoint candidate = all[(ordinal() + i) % all.length];
            if (candidate.availableIn(hasElevation)) return candidate;
        }
        return this;
    }
}
