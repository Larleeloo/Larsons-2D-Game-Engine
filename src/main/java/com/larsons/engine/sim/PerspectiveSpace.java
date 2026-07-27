package com.larsons.engine.sim;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.LevelFormat;

/**
 * Which way is <em>up</em> in a level — the physical space each
 * {@link LevelFormat} simulates in, loaded alongside the level's tiles.
 *
 * <p>The three formats deliberately do not share one side-scrolling space with
 * the camera bent around it. Each one has its own axes:
 *
 * <ul>
 *   <li>{@link #SIDE_VIEW} — the screen <em>is</em> the vertical plane. Up is
 *       up the screen, gravity pulls along world +y, and there is no third
 *       axis ({@link #hasElevation()} is false). This is the classic
 *       platformer space and nothing about it changes.</li>
 *   <li>{@link #TOP_DOWN} — the screen is the <em>floor</em>, seen from
 *       straight above. Up has left the world plane entirely: it points out of
 *       the screen at the viewer. Gravity pulls along an elevation axis
 *       ({@code z}), and because rising means coming <em>closer to the
 *       camera</em>, height is drawn as a lift and a growth
 *       ({@link #heightGrowth()}).</li>
 *   <li>{@link #ISOMETRIC} — the floor again, tilted into a diamond. Up is
 *       oblique to the view: neither the screen's vertical nor a line straight
 *       at the camera, but the axis a parallel projection draws as a pure
 *       vertical lift with no change in size.</li>
 * </ul>
 *
 * <p><b>Why this exists.</b> Anything with a direction — a jump, a meteor
 * called down from the sky, an ember drifting upward, a shard raining down —
 * used to be authored in screen terms and simply replayed in every format. On
 * a plane that is wrong twice over: in top-down "up the screen" is
 * <em>north</em>, not up, and in isometric it is north-west. Effects ask this
 * enum which axis carries their vertical component instead, so an ember rises
 * off the floor toward the viewer in a top-down level and a meteor falls onto
 * the tile it was aimed at rather than flying in from the north.
 *
 * <p><b>Gravity does not switch off on a plane</b> — it turns. The pull is the
 * same {@link #gravity()} in all three formats; only the axis it acts on
 * changes, which is what {@link #hasElevation()} answers.
 */
public enum PerspectiveSpace {

    /** Side-scroller: gravity along world +y, no elevation axis. */
    SIDE_VIEW(Perspective.SIDE_SCROLL, false, 0, 0, "up the screen"),

    /** Top-down: the floor seen from above; up comes out of the screen. */
    TOP_DOWN(Perspective.TOP_DOWN, true, 1.0, 0.55, "out of the screen"),

    /** Isometric: the floor in a diamond; up runs along the screen's vertical. */
    ISOMETRIC(Perspective.ISOMETRIC, true, 1.0, 0, "along the screen's vertical");

    /**
     * The one gravity the engine has, px/sec². Which axis it pulls along is
     * the whole difference between these spaces — it matches the side-scroller
     * constants ({@link PlayerPhysics#GRAVITY}) so a plan-view fall reads with
     * the same weight as a side-scrolling one.
     */
    public static final double GRAVITY = 1500;

    private final Perspective perspective;
    private final boolean elevation;
    private final double screenLift;
    private final double heightGrowth;
    private final String upLabel;

    PerspectiveSpace(Perspective perspective, boolean elevation,
                     double screenLift, double heightGrowth, String upLabel) {
        this.perspective = perspective;
        this.elevation = elevation;
        this.screenLift = screenLift;
        this.heightGrowth = heightGrowth;
        this.upLabel = upLabel;
    }

    /** The space a camera projection simulates in. */
    public static PerspectiveSpace of(Perspective perspective) {
        if (perspective == null) return SIDE_VIEW;
        for (PerspectiveSpace s : values()) {
            if (s.perspective == perspective) return s;
        }
        return SIDE_VIEW;
    }

    /** The space a level format simulates in. */
    public static PerspectiveSpace of(LevelFormat format) {
        return format == null ? SIDE_VIEW : of(format.perspective());
    }

    /** The camera projection this space is seen through. */
    public Perspective perspective() { return perspective; }

    /**
     * Whether height is an axis of its own here — true on the plan-view
     * formats, where the world plane is the floor and "up" leaves it.
     */
    public boolean hasElevation() { return elevation; }

    /**
     * Whether "down" is a direction on the world plane (the side-scroller's
     * +y). The exact opposite of {@link #hasElevation()}, named for the code
     * that cares about falling rather than about jumping.
     */
    public boolean gravityOnPlane() { return !elevation; }

    /**
     * Whether background scenery is drawn <em>behind</em> the terrain.
     *
     * <p>Only the side view has a behind. Its blocks stand edge-on between the
     * camera and the distance, so a tree painted into the background layer is
     * meant to be hidden by the hill in front of it — that is the layer doing
     * its job. On a plane the very same blocks <em>are</em> the floor, and
     * behind the floor is under it: scenery drawn there could never be seen at
     * all, because every tile a decoration is planted on is painted over it.
     * So the plan views plant background scenery <em>on</em> the floor, and
     * "background" keeps its other meaning — behind the actors walking about.
     */
    public boolean scenerySitsBehindTerrain() { return !elevation; }

    /** The pull along whichever axis is down here, px/sec². */
    public double gravity() { return GRAVITY; }

    /**
     * Screen pixels of lift per world unit of elevation (before zoom). Zero in
     * the side view, which has no elevation to draw; one on the planes, where
     * a raised body is drawn above its own shadow.
     */
    public double screenLift() { return screenLift; }

    /**
     * How much bigger a body looks per {@code unit} of elevation. Only
     * top-down grows things: its up axis points at the camera, so a rising
     * meteor really is getting nearer. Isometric's up axis is oblique and
     * parallel-projected, so height moves a sprite without resizing it.
     */
    public double heightGrowth() { return heightGrowth; }

    /**
     * The draw scale for something {@code z} above the floor, where
     * {@code unit} is the height that counts as "one step closer" (a tile,
     * normally). Capped at two units so a shot called from far overhead stays
     * on screen.
     */
    public double heightScale(double z, double unit) {
        if (heightGrowth <= 0 || z <= 0 || unit <= 0) return 1;
        return 1 + heightGrowth * Math.min(2, z / unit);
    }

    /** Where "up" points here, for menus and HUD text ("out of the screen"). */
    public String upLabel() { return upLabel; }
}
