package com.larsons.engine.graphics;

/**
 * A camera that stands <em>in</em> the world at an eye and looks along a
 * heading — the pinhole projection the first- and third-person views are drawn
 * through.
 *
 * <p><b>How this differs from {@link Camera}, which is the other camera in this
 * package.</b> {@code Camera} hangs over the world and flattens it: its
 * projection is <em>parallel</em>, so a block a hundred tiles away is drawn
 * exactly as large as the one under your feet, and its "position" is a point on
 * the world plane that lands in the middle of the screen. This one has a
 * position in three dimensions, a direction it faces, and a field of view, and
 * it divides by depth. That division is the whole of what makes a picture read
 * as 3D: parallel lines converge, a corridor narrows, and walking forward makes
 * things grow.
 *
 * <p>They are separate classes rather than one camera with a mode because they
 * share nothing but a viewport. The flat camera's whole design — a pixel
 * lattice the camera cannot move, an eight-point heading that snaps to exact
 * axis swaps, a terrain cache that bakes chunks only at those headings — exists
 * to make a <em>parallel</em> projection stop shimmering, and none of it means
 * anything once there is a perspective divide. Folding this into it would have
 * put a branch through every one of those decisions.
 *
 * <h2>Axes, and which way is which</h2>
 *
 * <p>The world plane is the one the rest of the engine uses: {@code +x} east,
 * {@code +y} south, so north is {@code -y}. Height is {@code z}, zero on the
 * floor and rising, in the same world units as {@code x} and {@code y} — one
 * block is {@code Level.tileSize} on every axis (see {@code Level.BLOCK_HEIGHT},
 * which is what makes that true).
 *
 * <p>{@link #yaw()} is the compass heading the eye faces, radians clockwise
 * from north — <b>the same convention {@link Camera#yaw()} uses</b>, so a
 * heading can be handed from one camera to the other when the player toggles
 * between the views and mean the same direction. Forward is therefore
 * {@code (sin yaw, -cos yaw)} and right is {@code (cos yaw, sin yaw)}.
 *
 * <p>{@link #pitch()} is how far the eye is tilted off the horizontal, radians,
 * <b>positive looking up</b>. Bounded at just under a quarter turn by
 * {@link #MAX_PITCH}: straight up and straight down are where the yaw axis and
 * the view axis line up and a heading stops meaning anything, which is why
 * every game that has ever had a mouse-look stops just short of them.
 *
 * <h2>The projection</h2>
 *
 * <p>Two rotations and a divide. A world point is taken relative to the eye,
 * turned by the yaw onto {@code (right, forward)}, tilted by the pitch onto
 * {@code (high, depth)}, and then divided:
 *
 * <pre>
 *   screenX = centreX + right * focal / depth
 *   screenY = centreY - high  * focal / depth
 *   focal   = (viewportHeight / 2) / tan(fov / 2)
 * </pre>
 *
 * <p>{@code depth} is distance along the view axis, and it is the number
 * everything else here is about: it is what the divide needs to be positive
 * for, what {@link #clipNear} exists to guarantee, and what a painter sorts
 * on.
 *
 * <p><b>Why {@code focal} is measured against the viewport's height.</b> The
 * field of view given to {@link #setFov} is the <em>vertical</em> one, so
 * widening the window widens what you can see rather than stretching what was
 * already there. Deriving it from the width instead is the classic way to give
 * a wide monitor a fish-eye and a narrow one a keyhole.
 */
public final class EyeCamera {

    /**
     * The default vertical field of view — 70°, which is Minecraft's own
     * default and reads as "normal" to anyone who has played one of these.
     * Narrower feels like a scope; much wider bows the edges of the screen,
     * because a flat projection plane cannot help it.
     */
    public static final double DEFAULT_FOV = Math.toRadians(70);

    /**
     * How far the eye may tilt: just under a quarter turn. See the class note
     * on why not exactly one.
     */
    public static final double MAX_PITCH = Math.toRadians(89);

    /**
     * How close to the eye geometry is cut off, in world units — a fortieth of
     * a block at the engine's default tile size.
     *
     * <p>Something has to be, and the reason is arithmetic rather than taste:
     * the projection divides by depth, so a face touching the eye projects to
     * infinity and a face just behind it projects to a <em>mirrored</em> finite
     * point, which draws as a wild streak across the screen rather than as
     * nothing. {@link #clipNear} cuts polygons against this plane instead of
     * dropping them, so a wall you walk up to fills the screen properly rather
     * than vanishing at the moment your nose touches it.
     */
    public static final double NEAR = 0.8;

    private double x, y, z;
    private double yaw, pitch;
    private double fov = DEFAULT_FOV;
    private int viewportWidth = 1, viewportHeight = 1;

    // Kept in step with yaw/pitch by their setters: the projection reads all
    // four on the hot path (four corners per block face, thousands per frame).
    private double cosYaw = 1, sinYaw = 0;
    private double cosPitch = 1, sinPitch = 0;

    public EyeCamera() {}

    public EyeCamera(int viewportWidth, int viewportHeight) {
        setViewport(viewportWidth, viewportHeight);
    }

    // --- placement -----------------------------------------------------------

    /** Stand the eye at a world point; {@code z} is height above the floor. */
    public void place(double wx, double wy, double wz) {
        this.x = wx;
        this.y = wy;
        this.z = wz;
    }

    public double x() { return x; }

    public double y() { return y; }

    public double z() { return z; }

    /** The heading the eye faces, radians clockwise from north. */
    public double yaw() { return yaw; }

    /** The tilt off the horizontal, radians, positive looking up. */
    public double pitch() { return pitch; }

    /** Aim the eye. The pitch is clamped to {@link #MAX_PITCH}. */
    public void look(double yawRadians, double pitchRadians) {
        this.yaw = yawRadians;
        this.pitch = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitchRadians));
        this.cosYaw = snap(Math.cos(this.yaw));
        this.sinYaw = snap(Math.sin(this.yaw));
        this.cosPitch = snap(Math.cos(this.pitch));
        this.sinPitch = snap(Math.sin(this.pitch));
    }

    /**
     * A cosine or sine rounded to the exact value the angle means, for the
     * same reason {@code Camera} does it: {@code Math.cos(Math.PI / 2)} is
     * 6.1e-17 rather than zero, and an eye looking due east should be looking
     * due east and not 6.1e-17 radians off it.
     */
    private static double snap(double v) {
        if (Math.abs(v) < 1e-12) return 0.0;
        if (Math.abs(v - 1.0) < 1e-12) return 1.0;
        if (Math.abs(v + 1.0) < 1e-12) return -1.0;
        return v;
    }

    public void setViewport(int w, int h) {
        this.viewportWidth = Math.max(1, w);
        this.viewportHeight = Math.max(1, h);
    }

    public int viewportWidth() { return viewportWidth; }

    public int viewportHeight() { return viewportHeight; }

    /** The vertical field of view, radians. */
    public double fov() { return fov; }

    /** Set the vertical field of view, clamped to something a screen can show. */
    public void setFov(double radians) {
        this.fov = Math.max(Math.toRadians(20), Math.min(Math.toRadians(130), radians));
    }

    // --- directions ----------------------------------------------------------

    /** East component of the direction the eye is looking. */
    public double dirX() { return sinYaw * cosPitch; }

    /** South component of the direction the eye is looking. */
    public double dirY() { return -cosYaw * cosPitch; }

    /** Upward component of the direction the eye is looking. */
    public double dirZ() { return sinPitch; }

    /** East component of "forward" flattened onto the ground plane. */
    public double forwardX() { return sinYaw; }

    /** South component of "forward" flattened onto the ground plane. */
    public double forwardY() { return -cosYaw; }

    /** East component of "right" — always on the ground plane. */
    public double rightX() { return cosYaw; }

    /** South component of "right" — always on the ground plane. */
    public double rightY() { return sinYaw; }

    // --- projection ----------------------------------------------------------

    /** Pixels from the projection plane to the eye; see the class note. */
    public double focal() {
        return (viewportHeight / 2.0) / Math.tan(fov / 2.0);
    }

    /** Screen column the view axis passes through. */
    public double centreX() { return viewportWidth / 2.0; }

    /** Screen row the view axis passes through. */
    public double centreY() { return viewportHeight / 2.0; }

    /**
     * The screen row the horizon falls on — where a point at eye height an
     * infinite distance away would land.
     *
     * <p>Falls out of the projection with the depth taken to infinity:
     * {@code centreY + focal * tan(pitch)}. Looking up moves it <em>down</em>
     * the screen, which is what looking up does.
     */
    public double horizonY() {
        return centreY() + focal() * Math.tan(pitch);
    }

    /**
     * A world point in the eye's own frame: {@code out[0]} right of the view
     * axis, {@code out[1]} above it, {@code out[2]} along it.
     *
     * <p>The intermediate the whole class is built on, exposed because
     * clipping has to happen <em>here</em> — before the divide, where the
     * geometry is still linear and an edge crossing the near plane can be cut
     * by interpolation. After the divide there is nothing left to interpolate.
     *
     * <p>Writes into a caller-owned array rather than returning one: this runs
     * four times per block face and thousands of times per frame, and the
     * allocation would cost more than the arithmetic.
     */
    public void toEye(double wx, double wy, double wz, double[] out) {
        double dx = wx - x, dy = wy - y, dz = wz - z;
        // Yaw, on the ground plane: the eye turns about the world's vertical.
        double right = dx * cosYaw + dy * sinYaw;
        double forward = dx * sinYaw - dy * cosYaw;
        // Pitch, about the right axis. Positive pitch looks up, so a point
        // level with the eye moves down the frame.
        out[0] = right;
        out[1] = -forward * sinPitch + dz * cosPitch;
        out[2] = forward * cosPitch + dz * sinPitch;
    }

    /** Screen column of an eye-frame point; only meaningful for {@code depth > 0}. */
    public double screenX(double right, double depth) {
        return centreX() + right * focal() / depth;
    }

    /** Screen row of an eye-frame point; only meaningful for {@code depth > 0}. */
    public double screenY(double high, double depth) {
        return centreY() - high * focal() / depth;
    }

    /**
     * Project a world point straight to the screen: {@code out[0]} column,
     * {@code out[1]} row, {@code out[2]} depth along the view axis.
     *
     * @return {@code false} when the point is at or behind the near plane, in
     *         which case {@code out[0]} and {@code out[1]} are meaningless —
     *         a caller with a single point (a light, a sprite's anchor) drops
     *         it, and a caller with a polygon uses {@link #clipNear} instead
     */
    public boolean project(double wx, double wy, double wz, double[] out) {
        toEye(wx, wy, wz, out);
        double depth = out[2];
        if (depth <= NEAR) return false;
        double f = focal();
        double right = out[0], high = out[1];
        out[0] = centreX() + right * f / depth;
        out[1] = centreY() - high * f / depth;
        return true;
    }

    /**
     * How much larger than its world size something at {@code depth} is drawn:
     * the projection's scale factor, {@code focal / depth}.
     *
     * <p>What a billboard is sized by, and what makes walking toward a mob
     * make it grow.
     */
    public double scaleAt(double depth) {
        return depth <= NEAR ? 0 : focal() / depth;
    }

    /**
     * Cut a polygon against the near plane, in the eye's own frame.
     *
     * <p>Sutherland–Hodgman against the single plane {@code depth = NEAR}: walk
     * the edges, keep every vertex in front of it, and where an edge crosses,
     * emit the crossing point. One plane rather than six because the other five
     * are handled better elsewhere — the sides and top by simply letting the
     * rasteriser clip, which it does for free and exactly, and the far plane by
     * the view distance the caller sweeps to. The near plane is the only one
     * that <em>cannot</em> be left to the rasteriser, because the geometry
     * behind it does not project to somewhere off screen: it projects to the
     * wrong place on it.
     *
     * @param eyeVerts flat {@code right, high, depth} triples, {@code count} of
     *                 them, in winding order
     * @param out      receives the clipped polygon, same layout; must hold at
     *                 least {@code (count + 1) * 3} values
     * @return how many vertices were written — {@code 0} when the polygon is
     *         entirely behind the near plane
     */
    public static int clipNear(double[] eyeVerts, int count, double[] out) {
        int written = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double ax = eyeVerts[i * 3], ay = eyeVerts[i * 3 + 1], ad = eyeVerts[i * 3 + 2];
            double bx = eyeVerts[j * 3], by = eyeVerts[j * 3 + 1], bd = eyeVerts[j * 3 + 2];
            boolean aIn = ad >= NEAR, bIn = bd >= NEAR;
            if (aIn) {
                out[written * 3] = ax;
                out[written * 3 + 1] = ay;
                out[written * 3 + 2] = ad;
                written++;
            }
            if (aIn != bIn) {
                double t = (NEAR - ad) / (bd - ad);
                out[written * 3] = ax + (bx - ax) * t;
                out[written * 3 + 1] = ay + (by - ay) * t;
                out[written * 3 + 2] = NEAR;
                written++;
            }
        }
        return written;
    }
}
