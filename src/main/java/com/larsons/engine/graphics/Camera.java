package com.larsons.engine.graphics;

/**
 * A 2D camera that supports multiple perspectives (requirement #2).
 *
 * <p>World coordinates are mapped to the screen in two steps:
 * <ol>
 *   <li>a per-perspective "planar" projection (identity for orthographic
 *       perspectives; a diamond transform for {@link Perspective#ISOMETRIC}),</li>
 *   <li>then zoom and centering on the camera focus.</li>
 * </ol>
 * Because the projection is the only thing that changes between perspectives,
 * the same tile/sprite drawing code renders correctly in side-scroll, top-down,
 * and isometric views.
 *
 * <p><b>The world lands on a pixel lattice the camera cannot move, and that is
 * the fix for the shimmer.</b> The obvious way to write step 2 is to round the
 * whole thing at once — {@code round((world - camera) * zoom + viewport/2)} —
 * and that is what this did. It is wrong in a way that only shows up in motion.
 * The camera is a {@code double} and slides continuously, so every object
 * crosses its own rounding boundary at its own moment: at {@code zoom = 1.7} a
 * 32-unit tile is 54.4 pixels wide, and as the view pans each tile is
 * alternately rounded to 54 and to 55 while its neighbour is not. Neighbouring
 * blocks slide against one another by a pixel, over and over, for as long as the
 * camera moves. That is the "blocks jitter slightly" a player reports, and it is
 * a defect in the projection rather than in either rasteriser — both backends
 * draw exactly what this told them to.
 *
 * <p>So the rounding is split in two:
 *
 * <pre>
 *   screen = round(world * zoom)  +  round(viewport/2 - camera * zoom)
 *            \_________________/     \___________________________/
 *             the world's lattice     one offset, once, per frame
 *             — no camera term        — the same for everything
 * </pre>
 *
 * <p>The first term has no camera in it at all, so the pixel distance between
 * any two things in the world is fixed forever; the second is a single integer
 * that the whole scene translates by. The picture moves as one rigid sheet
 * instead of shivering against itself.
 *
 * <p><b>This is not a new idea here — it is
 * {@link TerrainCache}'s, applied to everything else.</b> That class worked the
 * argument out for baked floor chunks, for exactly this symptom ("as the view
 * moved a fraction of a pixel the chunks slid against one another and the
 * terrain visibly shook") and fixed it the same way. What it could not fix was
 * everything it deliberately does not cache: stacked blocks, mobs, dropped
 * items, decor and particles all go through the live sweep, which kept the old
 * arithmetic. A steady floor with shivering blocks on top of it is precisely
 * what was left.
 *
 * <p><b>What it costs.</b> Two roundings instead of one, so the whole scene can
 * sit up to a pixel from where a single rounding would have put it — including
 * the camera's own focus point, which is no longer guaranteed to land exactly at
 * the viewport centre. That error is <em>uniform</em>: everything shares it, so
 * nothing moves relative to anything else, which is the only property the eye
 * can see. A static half-pixel offset is invisible; a moving one is the bug.
 *
 * <p><b>It stays correct if the camera ever rotates.</b> The split works because
 * {@link #planar} is linear, so it can be applied to the world point and the
 * camera point separately and subtracted afterwards. A yaw belongs inside
 * {@code planar}, on both, and the arithmetic below does not change. This is not
 * snapping geometry to a screen-aligned grid, which would be wrong for a turned
 * view; it is quantising the world in its own space. That prediction is now
 * discharged rather than hoped for: {@link #setYaw} puts the rotation inside
 * {@code planar}, and {@code CameraYawTest} asserts the rigid-sheet property at
 * all eight headings.
 *
 * <h2>Yaw</h2>
 *
 * <p>{@link #yaw()} is the compass heading the camera faces, in radians,
 * <em>clockwise from world north</em> — north being −y, which is up the screen
 * at heading zero. The projection therefore rotates the world by the
 * <em>inverse</em>: turn the camera right and the world swings left, which is
 * what a camera does. At {@code yaw = π/2} the camera looks east, so world east
 * is the direction that now points up the screen.
 *
 * <p><b>Rotation belongs to the plan views only.</b> {@link Perspective#SIDE_SCROLL}
 * ignores yaw entirely: the screen there <em>is</em> the vertical plane, +y is
 * the direction gravity pulls rather than a ground-plane axis, and there is no
 * vertical axis on screen to turn around. Rotating it would tip the world over
 * rather than turn it. {@link Perspective#TOP_DOWN} and
 * {@link Perspective#ISOMETRIC} rotate; the isometric case rotates the grid on
 * the ground plane <em>first</em> and then views the result through the fixed
 * diamond, because the camera turns around the world's vertical axis and not
 * around the screen's.
 */
public class Camera {
    /**
     * The angle between two adjacent compass points — 45° in radians, and one
     * press of the rotate key once C8 binds it.
     */
    public static final double EIGHTH_TURN = Math.PI / 4;

    /** Focus position in world coordinates (the point centred on screen). */
    public double x, y;
    public double zoom = 1.0;
    public int viewportWidth, viewportHeight;

    private Perspective perspective;

    /**
     * The camera's heading, radians clockwise from world north; see the class
     * note. Private rather than public like {@link #x} because the projection
     * reads its cosine and sine on the hot path (four corners per tile) and
     * {@link #setYaw} is what keeps those two in step with it.
     */
    private double yaw;
    /**
     * The heading the camera is turning towards. Nothing here animates: this is
     * the goal C8's snap eases {@link #yaw} onto, and it is stored on the camera
     * because it is view state — per client, never networked (C10).
     */
    private double targetYaw;

    // cos/sin of yaw, maintained by setYaw. See snap() for why they are not
    // simply Math.cos/Math.sin of it.
    private double cosYaw = 1.0, sinYaw = 0.0;

    // Isometric projection parameters: one world tile (tileSize units on each
    // axis) projects to a diamond this many pixels wide/tall.
    public double isoTileWidth = 64;
    public double isoTileHeight = 32;
    /** World units per tile; used by the isometric projection. */
    public double tileSize = 32;

    public Camera(Perspective perspective, int viewportWidth, int viewportHeight) {
        this.perspective = perspective;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    public Perspective getPerspective() { return perspective; }

    public void setPerspective(Perspective p) { this.perspective = p; }

    public void setViewport(int w, int h) {
        this.viewportWidth = w;
        this.viewportHeight = h;
    }

    public void centerOn(double wx, double wy) {
        this.x = wx;
        this.y = wy;
    }

    /** The camera's heading, radians clockwise from world north. */
    public double yaw() { return yaw; }

    /**
     * Turn the camera to {@code radians}, clockwise from world north.
     *
     * <p>Has no effect on the picture in {@link Perspective#SIDE_SCROLL} — the
     * value is still stored, so a camera can be carried between levels of
     * different formats without a heading silently disappearing, but the
     * projection ignores it. See the class note.
     */
    public void setYaw(double radians) {
        this.yaw = radians;
        this.cosYaw = snap(Math.cos(radians));
        this.sinYaw = snap(Math.sin(radians));
    }

    /** The heading {@link #yaw} is easing towards; C8 drives it. */
    public double targetYaw() { return targetYaw; }

    /** Aim the camera at a heading. Nothing moves until something eases it. */
    public void setTargetYaw(double radians) { this.targetYaw = radians; }

    /** Whether yaw reaches the picture at all in this perspective. */
    public boolean rotates() { return perspective != Perspective.SIDE_SCROLL; }

    /**
     * A cosine or sine rounded to the exact value the heading means.
     *
     * <p>{@code Math.cos(Math.PI / 2)} is 6.1e-17, not zero. Left alone, a
     * quarter turn would be a rotation by 6.1e-17 radians rather than an exact
     * axis swap, and the projection at the four cardinal headings would no
     * longer be the unrotated one with its axes exchanged. That costs nothing
     * visible — 6.1e-17 rad over a large level is 1e-11 px — but the exactness
     * is worth having for the headings the camera actually rests at: it is what
     * lets a golden frame at 90° be the transpose of one at 0°, and what keeps
     * the world's pixel lattice (see the class note) exactly the lattice it was.
     */
    private static double snap(double v) {
        if (Math.abs(v) < 1e-12) return 0.0;
        if (Math.abs(v - 1.0) < 1e-12) return 1.0;
        if (Math.abs(v + 1.0) < 1e-12) return -1.0;
        return v;
    }

    /**
     * Planar projection of a world point, before zoom/centering: the whole of
     * the perspective and the yaw, and none of the camera, the zoom or the
     * rounding.
     *
     * <p>Public because the rotated view needs it. The visible region of the
     * world stops being a rectangle of cells the moment the camera turns, so
     * anything deciding what to sweep has to project the corners itself.
     */
    public double[] planar(double wx, double wy) {
        if (perspective == Perspective.SIDE_SCROLL) return new double[]{wx, wy};

        // Yaw first, on the ground plane: the camera turns around the world's
        // vertical axis, not around the screen's. The rotation is the inverse
        // of the heading — the camera turns right, the world swings left.
        double rx = wx * cosYaw + wy * sinYaw;
        double ry = -wx * sinYaw + wy * cosYaw;

        if (perspective == Perspective.ISOMETRIC) {
            double tx = rx / tileSize;
            double ty = ry / tileSize;
            return new double[]{
                    (tx - ty) * (isoTileWidth / 2.0),
                    (tx + ty) * (isoTileHeight / 2.0)
            };
        }
        return new double[]{rx, ry}; // TOP_DOWN: orthographic, turned
    }

    /** The exact inverse of {@link #planar}, undone in the opposite order. */
    public double[] inversePlanar(double px, double py) {
        if (perspective == Perspective.SIDE_SCROLL) return new double[]{px, py};

        double rx, ry;
        if (perspective == Perspective.ISOMETRIC) {
            double a = px / (isoTileWidth / 2.0);
            double b = py / (isoTileHeight / 2.0);
            rx = (a + b) / 2.0 * tileSize;
            ry = (b - a) / 2.0 * tileSize;
        } else {
            rx = px;
            ry = py;
        }
        return new double[]{
                rx * cosYaw - ry * sinYaw,
                rx * sinYaw + ry * cosYaw
        };
    }

    public int worldToScreenX(double wx, double wy) {
        double[] p = planar(wx, wy);
        double[] c = planar(x, y);
        return place(p[0], c[0], viewportWidth);
    }

    public int worldToScreenY(double wx, double wy) {
        double[] p = planar(wx, wy);
        double[] c = planar(x, y);
        return place(p[1], c[1], viewportHeight);
    }

    /**
     * One axis of step 2: a projected world coordinate placed on the pixel
     * lattice, plus the camera's single offset onto it.
     *
     * <p>The class note has the argument for why this is two roundings rather
     * than one. The arithmetic is in {@code long} because the first term is a
     * whole-world coordinate rather than a screen-relative one — a point far off
     * the edge of a large level projects to a number a screen never holds, and
     * callers do ask about those when they are deciding what to cull.
     *
     * @param planar       the world point, projected, before zoom
     * @param planarCamera the camera's focus, projected the same way
     * @param viewport     the viewport's extent on this axis
     */
    private int place(double planar, double planarCamera, int viewport) {
        long lattice = Math.round(planar * zoom);
        long offset = Math.round(viewport / 2.0 - planarCamera * zoom);
        long screen = lattice + offset;
        // Saturating rather than wrapping. A clipped-off coordinate draws
        // nothing, which is what was wanted; a wrapped one draws it on the
        // opposite side of the screen.
        if (screen > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (screen < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) screen;
    }

    /**
     * Project a world point into {@code out[0]} (screen x) and {@code out[1]}
     * (screen y) with no allocation — one projection instead of the two that
     * separate {@link #worldToScreenX}/{@link #worldToScreenY} calls cost.
     * This is the hot path for tile rendering (four corners per tile).
     */
    public void worldToScreen(double wx, double wy, int[] out) {
        double px, py, cx, cy;
        if (perspective == Perspective.SIDE_SCROLL) {
            px = wx;
            py = wy;
            cx = x;
            cy = y;
        } else {
            // The same two steps as planar(), inlined for both points at once.
            // They must stay the same two steps: this is the tile path and
            // planar() is the picking path, and a disagreement between them is
            // a mouse click landing on the wrong block.
            double rx = wx * cosYaw + wy * sinYaw;
            double ry = -wx * sinYaw + wy * cosYaw;
            double rcx = x * cosYaw + y * sinYaw;
            double rcy = -x * sinYaw + y * cosYaw;
            if (perspective == Perspective.ISOMETRIC) {
                double hw = isoTileWidth / 2.0, hh = isoTileHeight / 2.0;
                double tx = rx / tileSize, ty = ry / tileSize;
                px = (tx - ty) * hw;
                py = (tx + ty) * hh;
                double ctx = rcx / tileSize, cty = rcy / tileSize;
                cx = (ctx - cty) * hw;
                cy = (ctx + cty) * hh;
            } else {
                px = rx;
                py = ry;
                cx = rcx;
                cy = rcy;
            }
        }
        out[0] = place(px, cx, viewportWidth);
        out[1] = place(py, cy, viewportHeight);
    }

    /**
     * A world-plane displacement in projected (pre-zoom) screen units — the
     * projection applied to a direction rather than to a point. A vector on the
     * floor keeps pointing the same way across the floor when the camera turns
     * the grid into a diamond, which is what a cast shadow or any other
     * ground-plane direction needs.
     *
     * <p>Yaw comes free here, and by construction rather than by luck: rotation
     * is linear and fixes the origin, so a direction routed through this one
     * swings with the camera without this method knowing a camera can turn.
     */
    public double[] planarDelta(double dx, double dy) {
        double[] a = planar(0, 0);
        double[] b = planar(dx, dy);
        return new double[]{b[0] - a[0], b[1] - a[1]};
    }

    /**
     * Inverse mapping: screen pixel back to world coordinates.
     *
     * <p>Inverted against the same camera offset the forward direction adds,
     * rather than against the un-rounded {@code viewport/2 - camera * zoom} it
     * approximates. The two differ by less than a pixel, and less than a pixel
     * is exactly the size of the disagreement that puts a placed block in the
     * wrong cell when a creative-mode stroke lands on a tile boundary. The
     * remaining error is the lattice rounding in the forward direction, which no
     * inverse can undo and which is sub-pixel by construction.
     */
    public double[] screenToWorld(int sx, int sy) {
        double[] c = planar(x, y);
        double px = (sx - Math.round(viewportWidth / 2.0 - c[0] * zoom)) / zoom;
        double py = (sy - Math.round(viewportHeight / 2.0 - c[1] * zoom)) / zoom;
        return inversePlanar(px, py);
    }
}
