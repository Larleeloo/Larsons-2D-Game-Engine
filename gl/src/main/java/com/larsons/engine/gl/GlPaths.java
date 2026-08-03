package com.larsons.engine.gl;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Turning shapes into triangles. No GL in here at all, on purpose.
 *
 * <p><b>Why the geometry is a separate class with no driver in it.</b> This is
 * the half of a GPU backend that can be wrong in ways a picture does not
 * obviously show — a stroke a third of a pixel too wide, a fan wound the wrong
 * way, a miter that spikes at a sharp corner — and it is also the half that a
 * machine with no GL driver can check. Split out, every rule below is a unit
 * test that runs on any machine in milliseconds; folded into {@link GlTarget},
 * none of them would run in headless CI at all, and the only instrument left
 * would be a whole-frame mean error that says "something moved" about eleven
 * hundred triangles.
 *
 * <p><b>Curves come from {@code java.awt.geom}, not from a circle formula.</b>
 * The plan says to tessellate ovals and arcs "with a segment count scaled by
 * on-screen radius". Flattening {@link java.awt.geom.Ellipse2D} and
 * {@link java.awt.geom.Arc2D} through a {@link PathIterator} at a fixed
 * flatness does that and does it better: the segment count comes out of a
 * bound on the actual error rather than out of a guess correlated with it, and
 * — the part that matters here — the vertices are the ones Java2D would have
 * rasterised, so the only difference left between the two backends on a curve
 * is antialiasing. A hand-rolled circle would put a second difference in, and
 * the two would be indistinguishable in the error metric.
 */
public final class GlPaths {

    private GlPaths() {}

    /**
     * Flattening tolerance in pixels: the most a chord may sag away from the
     * curve it replaces.
     *
     * <p>A quarter-pixel is below what a single sample of antialiasing can
     * express, so tightening it further buys nothing anyone can see and costs
     * triangles on every rounded panel in the UI.
     */
    public static final double FLATNESS = 0.25;

    /** {@code BasicStroke}'s default miter limit, matched deliberately. */
    public static final float MITER_LIMIT = 10f;

    /** Line caps, matching {@link java.awt.BasicStroke}'s three. */
    public enum Cap { BUTT, SQUARE, ROUND }

    /** Where triangles go. Implemented by {@link GlTarget} over its batch. */
    @FunctionalInterface
    public interface Tri {
        void tri(float ax, float ay, float bx, float by, float cx, float cy);
    }

    /**
     * One closed or open run of points, {@code x0, y0, x1, y1, …}.
     *
     * <p>A closed contour does <em>not</em> repeat its first point at the end.
     * Every consumer here would then have to know whether a given contour did,
     * and the one that guessed wrong would emit a zero-length final segment —
     * which is invisible in a fill and a stray cap in a stroke.
     */
    public record Contour(float[] xy, boolean closed) {

        public int size() { return xy.length / 2; }

        public float x(int i) { return xy[i * 2]; }

        public float y(int i) { return xy[i * 2 + 1]; }
    }

    // --- flattening ------------------------------------------------------------

    /**
     * A shape as straight-line contours, with curves subdivided to
     * {@link #FLATNESS}.
     *
     * <p>Degenerate contours — fewer than two distinct points — are dropped
     * rather than returned empty, because every caller would otherwise have to
     * check, and the one that forgot would divide by a zero-length segment.
     */
    public static List<Contour> flatten(Shape shape) {
        return flatten(shape, FLATNESS);
    }

    public static List<Contour> flatten(Shape shape, double flatness) {
        List<Contour> contours = new ArrayList<>(4);
        if (shape == null) return contours;
        PathIterator it = shape.getPathIterator(null, flatness);
        float[] seg = new float[6];
        FloatList current = new FloatList();
        boolean closed = false;
        while (!it.isDone()) {
            switch (it.currentSegment(seg)) {
                case PathIterator.SEG_MOVETO -> {
                    emit(contours, current, closed);
                    current = new FloatList();
                    closed = false;
                    current.add(seg[0], seg[1]);
                }
                case PathIterator.SEG_LINETO -> current.add(seg[0], seg[1]);
                case PathIterator.SEG_CLOSE -> {
                    closed = true;
                    emit(contours, current, true);
                    current = new FloatList();
                    closed = false;
                }
                default -> throw new IllegalStateException(
                        "a flattened path iterator produced a curve segment");
            }
            it.next();
        }
        emit(contours, current, closed);
        return contours;
    }

    /**
     * Whether {@code shape} needs the even-odd rule applied to a
     * <em>normalised</em> path to be filled correctly.
     *
     * <p>The stencil fill in {@link GlTarget} counts coverage parity, which is
     * the even-odd rule. A non-zero path whose contours overlap fills
     * differently under the two rules — and the engine has exactly one such
     * caller, the terrain shadow union, where the whole point is that
     * overlapping shadows fill <em>once</em> rather than stacking. Feeding that
     * to an even-odd rasteriser would punch a hole wherever two shadows crossed,
     * which is the opposite of what the call site exists to do.
     *
     * <p>{@link Area} is the answer and it is exact: it re-expresses any path as
     * non-overlapping contours, under which the two rules agree by construction.
     * It is also slow, which is why this asks first — every other shape the
     * engine fills is already simple and pays nothing.
     */
    public static Shape normalise(Shape shape) {
        if (shape == null) return null;
        PathIterator it = shape.getPathIterator(null);
        if (it.getWindingRule() == PathIterator.WIND_EVEN_ODD) return shape;
        return new Area(shape);
    }

    private static void emit(List<Contour> out, FloatList points, boolean closed) {
        float[] xy = points.trimmed();
        int n = xy.length / 2;
        // Java2D closes a path by walking back to the start and *then* issuing
        // SEG_CLOSE, so a rectangle arrives as five points, not four. Left in,
        // that final zero-length edge is a stray cap on every stroked shape and
        // a degenerate triangle in every fan — neither of which is visible in a
        // picture, both of which are wrong.
        while (closed && n > 1
                && xy[(n - 1) * 2] == xy[0] && xy[(n - 1) * 2 + 1] == xy[1]) {
            float[] trimmed = new float[(n - 1) * 2];
            System.arraycopy(xy, 0, trimmed, 0, trimmed.length);
            xy = trimmed;
            n--;
        }
        if (n < 2) return;
        out.add(new Contour(xy, closed));
    }

    // --- filling ---------------------------------------------------------------

    /**
     * Whether a contour is convex, and therefore safe to fill as a fan from its
     * first vertex.
     *
     * <p>The distinction is worth making rather than always taking the general
     * path: a convex fill is triangles in the open batch, and the general fill
     * is a stencil pass that flushes it. Almost everything the engine fills is
     * convex — rectangles, rounded rectangles, ovals, most pie slices, isometric
     * diamonds — so the cheap case is the common one, and the test for it is
     * three multiplies per vertex.
     *
     * <p>Collinear vertices count as convex, which matters because a flattened
     * curve is full of them.
     */
    public static boolean convex(Contour contour) {
        float[] xy = contour.xy();
        int n = xy.length / 2;
        if (n < 3) return false;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n, k = (i + 2) % n;
            float ax = xy[j * 2] - xy[i * 2], ay = xy[j * 2 + 1] - xy[i * 2 + 1];
            float bx = xy[k * 2] - xy[j * 2], by = xy[k * 2 + 1] - xy[j * 2 + 1];
            float cross = ax * by - ay * bx;
            if (cross > 1e-4f) {
                if (sign < 0) return false;
                sign = 1;
            } else if (cross < -1e-4f) {
                if (sign > 0) return false;
                sign = -1;
            }
        }
        return true;
    }

    /** Fill a convex contour as a triangle fan from its first vertex. */
    public static void fan(Contour contour, Tri sink) {
        float[] xy = contour.xy();
        int n = xy.length / 2;
        for (int i = 1; i + 1 < n; i++) {
            sink.tri(xy[0], xy[1],
                    xy[i * 2], xy[i * 2 + 1],
                    xy[(i + 1) * 2], xy[(i + 1) * 2 + 1]);
        }
    }

    /**
     * A fan from an <em>arbitrary</em> anchor, which is what a stencil fill
     * wants: the triangles overlap and wind both ways, and the stencil's parity
     * count sorts out which pixels are inside. Correct for any contour,
     * concave, self-intersecting or holed, which is the whole reason the
     * general fill goes through a stencil rather than a triangulator.
     */
    public static void coverageFan(Contour contour, Tri sink) {
        float[] xy = contour.xy();
        int n = xy.length / 2;
        if (n < 3) return;
        // The fan from vertex 0 spans every edge of the contour including the
        // closing one, which is implied by the last triangle rather than drawn
        // — so an unclosed contour needs no extra work here. A filled path is
        // closed by definition, which is exactly why the general fill can take
        // one whether the caller closed it or not.
        float ax = xy[0], ay = xy[1];
        for (int i = 1; i + 1 < n; i++) {
            sink.tri(ax, ay, xy[i * 2], xy[i * 2 + 1],
                    xy[(i + 1) * 2], xy[(i + 1) * 2 + 1]);
        }
    }

    // --- stroking --------------------------------------------------------------

    /**
     * The outline of a contour as triangles, {@code width} wide, centred on the
     * path.
     *
     * <p><b>Why a stroker and not {@code BasicStroke.createStrokedShape}.</b>
     * Java2D will hand back the exact outline as a {@link Shape}, and filling
     * that would be shorter code and geometrically perfect. It would also be an
     * annulus — an outer contour and an inner one — which is not convex, so
     * every outline in the engine would take the stencil path and flush the
     * batch. The UI draws 82 rounded-rectangle outlines; that is 82 flushes a
     * frame to save writing this, and B6 named flat-shape batching as the thing
     * that should move five specific frames. It cannot move them from behind a
     * stencil.
     *
     * <p><b>Joins share their vertices, so nothing overlaps.</b> Each segment
     * contributes one quad and adjacent quads meet at the same two mitered
     * points, which matters more than it sounds: an outline drawn as
     * independently-offset quads overlaps itself at every corner, and at
     * anything under full alpha those corners come out darker than the line —
     * a bug that is invisible in the opaque case and appears the day somebody
     * fades a border out.
     *
     * <p>Past the miter limit the outer side bevels and the inner side keeps
     * the miter, which is the arrangement that stays connected: bevelling both
     * would open a wedge on the inside of a sharp turn.
     */
    public static void stroke(Contour contour, float width, Cap cap, Tri sink) {
        float[] xy = contour.xy();
        int n = xy.length / 2;
        float half = Math.max(0.005f, width) * 0.5f;
        if (n < 2) return;

        int segments = contour.closed() ? n : n - 1;
        // Unit direction and normal per segment, skipping zero-length ones — a
        // flattened curve can repeat a point and a normalised zero vector is a
        // NaN that quietly takes the whole batch with it.
        float[] dx = new float[segments], dy = new float[segments];
        int[] from = new int[segments];
        int kept = 0;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % n;
            float ex = xy[j * 2] - xy[i * 2], ey = xy[j * 2 + 1] - xy[i * 2 + 1];
            float len = (float) Math.sqrt(ex * ex + ey * ey);
            if (len < 1e-6f) continue;
            dx[kept] = ex / len;
            dy[kept] = ey / len;
            from[kept] = i;
            kept++;
        }
        if (kept == 0) {
            // A path that is one point: a round cap is a dot, anything else
            // draws nothing, which is what Java2D does too.
            if (cap == Cap.ROUND) disc(xy[0], xy[1], half, sink);
            return;
        }

        for (int s = 0; s < kept; s++) {
            int i = from[s];
            int j = (from[s] + 1) % n;
            float nx = -dy[s], ny = dx[s];
            boolean firstSeg = s == 0 && !contour.closed();
            boolean lastSeg = s == kept - 1 && !contour.closed();

            float sx = xy[i * 2], sy = xy[i * 2 + 1];
            float ex = xy[j * 2], ey = xy[j * 2 + 1];
            if (cap == Cap.SQUARE) {
                if (firstSeg) { sx -= dx[s] * half; sy -= dy[s] * half; }
                if (lastSeg) { ex += dx[s] * half; ey += dy[s] * half; }
            }

            // Start and end offsets: mitered into the neighbouring segment
            // where there is one, square to this segment where there is not.
            float[] start = firstSeg ? offsets(sx, sy, nx, ny, half)
                    : join(sx, sy, dx[(s - 1 + kept) % kept], dy[(s - 1 + kept) % kept],
                            dx[s], dy[s], half, false);
            float[] end = lastSeg ? offsets(ex, ey, nx, ny, half)
                    : join(ex, ey, dx[s], dy[s], dx[(s + 1) % kept], dy[(s + 1) % kept],
                            half, true);

            quad(start[0], start[1], start[2], start[3],
                    end[2], end[3], end[0], end[1], sink);

            // The wedge a refused miter leaves on the outside of the corner.
            // Emitted only by the segment arriving at the joint, never by both:
            // the two would cover the same triangle, and two coats of a
            // half-transparent border is the artefact this whole stroker is
            // arranged to avoid.
            if (!lastSeg && end.length > 4) {
                sink.tri(ex, ey, end[4], end[5], end[6], end[7]);
            }

            if (cap == Cap.ROUND) {
                if (firstSeg) halfDisc(sx, sy, -dx[s], -dy[s], half, sink);
                if (lastSeg) halfDisc(ex, ey, dx[s], dy[s], half, sink);
            }
        }
    }

    /** Left and right offset points for an end with nothing to join to. */
    private static float[] offsets(float px, float py, float nx, float ny, float half) {
        return new float[]{px + nx * half, py + ny * half, px - nx * half, py - ny * half};
    }

    /**
     * The two offset points where segments {@code (adx, ady)} and
     * {@code (bdx, bdy)} meet at {@code (px, py)}, mitered.
     *
     * <p>Returns four floats — left then right — or eight when the miter was
     * refused, the extra four being the two outer-side points a bevel triangle
     * needs.
     *
     * @param incoming whether the caller is the segment arriving at this joint
     *                 rather than the one leaving it. It only matters when the
     *                 miter is refused: the two segments then meet the bevel at
     *                 <em>different</em> points, and a shared answer would skew
     *                 one of the two quads and overlap the wedge between them.
     */
    private static float[] join(float px, float py,
                                float adx, float ady, float bdx, float bdy,
                                float half, boolean incoming) {
        float anx = -ady, any = adx;
        float bnx = -bdy, bny = bdx;
        float mx = anx + bnx, my = any + bny;
        float len = (float) Math.sqrt(mx * mx + my * my);
        if (len < 1e-6f) {
            // A hairpin: the two normals cancel. There is no miter, only a cap
            // pointing back the way it came.
            return offsets(px, py, anx, any, half);
        }
        mx /= len;
        my /= len;
        float cos = mx * anx + my * any;          // cos(theta/2)
        float miter = half / Math.max(1e-6f, cos);
        if (miter <= MITER_LIMIT * half) {
            return new float[]{px + mx * miter, py + my * miter,
                    px - mx * miter, py - my * miter};
        }
        // Too sharp to miter. The inner side keeps the miter so the strip stays
        // joined; the outer side bevels across the two segment normals.
        float turn = adx * bdy - ady * bdx;       // >0 turns towards the left normal
        if (turn == 0) return offsets(px, py, anx, any, half);
        float outward = turn > 0 ? -1f : 1f;      // away from the direction of turn
        float clamped = MITER_LIMIT * half;
        float ox1 = px + anx * half * outward;
        float oy1 = py + any * half * outward;
        float ox2 = px + bnx * half * outward;
        float oy2 = py + bny * half * outward;
        float mine = incoming ? ox1 : ox2;
        float mineY = incoming ? oy1 : oy2;
        float ix = px - mx * clamped * outward;
        float iy = py - my * clamped * outward;
        boolean leftIsOuter = turn < 0;
        float lx = leftIsOuter ? mine : ix, ly = leftIsOuter ? mineY : iy;
        float rx = leftIsOuter ? ix : mine, ry = leftIsOuter ? iy : mineY;
        return new float[]{lx, ly, rx, ry, ox1, oy1, ox2, oy2};
    }

    /** A round cap: half a disc, opening away from {@code (dx, dy)}. */
    private static void halfDisc(float cx, float cy, float dx, float dy,
                                 float radius, Tri sink) {
        int steps = arcSteps(radius, Math.PI);
        float startAngle = (float) Math.atan2(dy, dx) - (float) (Math.PI / 2);
        float step = (float) Math.PI / steps;
        float px = cx + radius * (float) Math.cos(startAngle);
        float py = cy + radius * (float) Math.sin(startAngle);
        for (int i = 1; i <= steps; i++) {
            float a = startAngle + step * i;
            float qx = cx + radius * (float) Math.cos(a);
            float qy = cy + radius * (float) Math.sin(a);
            sink.tri(cx, cy, px, py, qx, qy);
            px = qx;
            py = qy;
        }
    }

    private static void disc(float cx, float cy, float radius, Tri sink) {
        int steps = arcSteps(radius, Math.PI * 2);
        float step = (float) (Math.PI * 2) / steps;
        float px = cx + radius, py = cy;
        for (int i = 1; i <= steps; i++) {
            float qx = cx + radius * (float) Math.cos(step * i);
            float qy = cy + radius * (float) Math.sin(step * i);
            sink.tri(cx, cy, px, py, qx, qy);
            px = qx;
            py = qy;
        }
    }

    /**
     * Segments needed to hold an arc of {@code sweep} radians at {@code radius}
     * within {@link #FLATNESS} — the same error bound the path flattener works
     * to, so a round cap is not visibly coarser than the curve it ends.
     */
    public static int arcSteps(double radius, double sweep) {
        if (radius <= FLATNESS) return 3;
        double perSegment = 2 * Math.acos(1 - FLATNESS / radius);
        return Math.max(3, (int) Math.ceil(Math.abs(sweep) / perSegment));
    }

    // --- dashes ----------------------------------------------------------------

    /**
     * A straight line broken into round-capped marks.
     *
     * <p>Round caps are not a default here, they are part of what the verb is —
     * {@code DrawTarget.drawDashedLine} says so and the stroke audit behind it
     * found no site that wanted anything else. Each mark is stroked
     * independently, so no two marks share geometry and the caps cannot
     * overlap into a double-blended blob at low alpha.
     */
    public static void dashedLine(float x1, float y1, float x2, float y2,
                                  float width, float dash, float gap, Tri sink) {
        float ex = x2 - x1, ey = y2 - y1;
        float length = (float) Math.sqrt(ex * ex + ey * ey);
        if (length < 1e-6f) return;
        float dx = ex / length, dy = ey / length;
        float on = Math.max(0.01f, dash), off = Math.max(0.01f, gap);
        float at = 0;
        while (at < length) {
            float end = Math.min(length, at + on);
            stroke(new Contour(new float[]{
                    x1 + dx * at, y1 + dy * at, x1 + dx * end, y1 + dy * end
            }, false), width, Cap.ROUND, sink);
            at = end + off;
        }
    }

    // --- polygon clipping ------------------------------------------------------

    /**
     * The part of a convex polygon on the {@code >= 0} side of a line, by
     * Sutherland–Hodgman.
     *
     * <p>Used by the linear gradient, where it earns its place. A gradient
     * holds its end colours beyond its two endpoints, so the colour across the
     * filled rectangle is linear in the middle band and constant outside it —
     * three different functions over three regions. Interpolating one set of
     * corner colours across the whole rectangle would be exact only when both
     * endpoints fall outside it, and wrong by however much of the ramp got
     * clamped otherwise. Cutting the rectangle at the two lines first makes
     * every piece a region where the colour really is affine, and vertex
     * interpolation exact rather than approximate.
     *
     * @param line {@code {ax, ay, bx, by}}. The kept half is the one the
     *             normal {@code (-(by-ay), bx-ax)} points into — so for a line
     *             running down the screen, the side with the smaller x. Stated
     *             as the vector rather than as "left" or "right" because those
     *             two words swap meaning between a y-up and a y-down coordinate
     *             system, and this engine is y-down.
     */
    public static float[] clipHalfPlane(float[] poly, float[] line) {
        int n = poly.length / 2;
        if (n == 0) return poly;
        float nx = -(line[3] - line[1]);
        float ny = line[2] - line[0];
        FloatList out = new FloatList();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float px = poly[i * 2], py = poly[i * 2 + 1];
            float qx = poly[j * 2], qy = poly[j * 2 + 1];
            float dp = (px - line[0]) * nx + (py - line[1]) * ny;
            float dq = (qx - line[0]) * nx + (qy - line[1]) * ny;
            if (dp >= 0) out.add(px, py);
            if ((dp >= 0) != (dq >= 0)) {
                float t = dp / (dp - dq);
                out.add(px + (qx - px) * t, py + (qy - py) * t);
            }
        }
        return out.trimmed();
    }

    // --- a growable float array ------------------------------------------------

    /** Because {@code List<Float>} boxes, and these are vertex coordinates. */
    static final class FloatList {
        private float[] data = new float[32];
        private int size;

        void add(float a, float b) {
            if (size + 2 > data.length) {
                float[] bigger = new float[data.length * 2];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            data[size++] = a;
            data[size++] = b;
        }

        int size() { return size; }

        float[] trimmed() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    /** Two triangles, wound the same way, from four corners in order. */
    static void quad(float ax, float ay, float bx, float by,
                     float cx, float cy, float dx, float dy, Tri sink) {
        sink.tri(ax, ay, bx, by, cx, cy);
        sink.tri(ax, ay, cx, cy, dx, dy);
    }
}
