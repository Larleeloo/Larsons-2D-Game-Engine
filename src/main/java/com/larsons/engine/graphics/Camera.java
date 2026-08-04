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
 * view; it is quantising the world in its own space.
 */
public class Camera {
    /** Focus position in world coordinates (the point centred on screen). */
    public double x, y;
    public double zoom = 1.0;
    public int viewportWidth, viewportHeight;

    private Perspective perspective;

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

    /** Planar projection of a world point, before zoom/centering. */
    private double[] planar(double wx, double wy) {
        if (perspective == Perspective.ISOMETRIC) {
            double tx = wx / tileSize;
            double ty = wy / tileSize;
            return new double[]{
                    (tx - ty) * (isoTileWidth / 2.0),
                    (tx + ty) * (isoTileHeight / 2.0)
            };
        }
        return new double[]{wx, wy}; // SIDE_SCROLL / TOP_DOWN: orthographic
    }

    private double[] inversePlanar(double px, double py) {
        if (perspective == Perspective.ISOMETRIC) {
            double a = px / (isoTileWidth / 2.0);
            double b = py / (isoTileHeight / 2.0);
            double tx = (a + b) / 2.0;
            double ty = (b - a) / 2.0;
            return new double[]{tx * tileSize, ty * tileSize};
        }
        return new double[]{px, py};
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
        if (perspective == Perspective.ISOMETRIC) {
            double hw = isoTileWidth / 2.0, hh = isoTileHeight / 2.0;
            double tx = wx / tileSize, ty = wy / tileSize;
            px = (tx - ty) * hw;
            py = (tx + ty) * hh;
            double ctx = x / tileSize, cty = y / tileSize;
            cx = (ctx - cty) * hw;
            cy = (ctx + cty) * hh;
        } else {
            px = wx;
            py = wy;
            cx = x;
            cy = y;
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
