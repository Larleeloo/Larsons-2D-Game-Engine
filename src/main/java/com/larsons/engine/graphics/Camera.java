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
        return (int) Math.round((p[0] - c[0]) * zoom + viewportWidth / 2.0);
    }

    public int worldToScreenY(double wx, double wy) {
        double[] p = planar(wx, wy);
        double[] c = planar(x, y);
        return (int) Math.round((p[1] - c[1]) * zoom + viewportHeight / 2.0);
    }

    /** Inverse mapping: screen pixel back to world coordinates. */
    public double[] screenToWorld(int sx, int sy) {
        double[] c = planar(x, y);
        double px = (sx - viewportWidth / 2.0) / zoom + c[0];
        double py = (sy - viewportHeight / 2.0) / zoom + c[1];
        return inversePlanar(px, py);
    }
}
