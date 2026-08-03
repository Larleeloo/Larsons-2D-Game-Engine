package com.larsons.engine.gl;

import org.junit.jupiter.api.Test;

import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry, checked without a driver.
 *
 * <p><b>Why this test exists separately from the parity test.</b> The parity
 * test needs a GL context and reports one number per frame; on a machine with
 * no driver it skips, and when it does run, a stroke that is a third of a pixel
 * too wide reads as 0.4 mean error and passes. Neither of those is a reason to
 * leave the geometry unchecked — so the rules it has to obey are asserted here,
 * exactly, on any machine, in milliseconds.
 *
 * <p>Two properties get most of the attention, because they are the two whose
 * failures are invisible until something else goes wrong:
 * <ul>
 *   <li><b>A stroke covers each pixel once.</b> Overlapping quads look correct
 *       at full alpha and darken at the joins at any other, so the bug ships
 *       and appears the day somebody fades a border.</li>
 *   <li><b>The even-odd fill is only used where it is lossless.</b> The one
 *       non-zero path the engine fills is a union of overlapping shadows, and
 *       even-odd would punch a hole through every overlap — the exact opposite
 *       of what the call site exists to do.</li>
 * </ul>
 */
class GlPathsTest {

    /** Collects triangles so a test can measure what was emitted. */
    private static final class Sink implements GlPaths.Tri {
        final List<float[]> triangles = new ArrayList<>();

        @Override
        public void tri(float ax, float ay, float bx, float by, float cx, float cy) {
            triangles.add(new float[]{ax, ay, bx, by, cx, cy});
        }

        double area() {
            double total = 0;
            for (float[] t : triangles) total += Math.abs(signedArea(t));
            return total;
        }

        double signedTotal() {
            double total = 0;
            for (float[] t : triangles) total += signedArea(t);
            return total;
        }

        static double signedArea(float[] t) {
            return ((t[2] - t[0]) * (t[5] - t[1]) - (t[4] - t[0]) * (t[3] - t[1])) / 2;
        }
    }

    // --- flattening ------------------------------------------------------------

    @Test
    void aRectangleFlattensToFourCornersAndNoRepeatedPoint() {
        List<GlPaths.Contour> contours = GlPaths.flatten(new Rectangle2D.Float(2, 3, 10, 20));
        assertEquals(1, contours.size());
        GlPaths.Contour contour = contours.get(0);
        assertTrue(contour.closed(), "a rectangle is a closed contour");
        assertEquals(4, contour.size(),
                "a closed contour must not repeat its first point: " + contour.size());
    }

    @Test
    void aCurveIsSubdividedUntilItIsFlatEnoughAndNoFurther() {
        // The sag of a chord across an arc of angle a is r(1 - cos(a/2)). If the
        // flattener honoured its tolerance, no chord sags more than FLATNESS.
        GlPaths.Contour circle = GlPaths.flatten(new Ellipse2D.Float(0, 0, 200, 200)).get(0);
        double worst = 0;
        for (int i = 0; i < circle.size(); i++) {
            int j = (i + 1) % circle.size();
            double mx = (circle.x(i) + circle.x(j)) / 2 - 100;
            double my = (circle.y(i) + circle.y(j)) / 2 - 100;
            worst = Math.max(worst, 100 - Math.hypot(mx, my));
        }
        assertTrue(worst <= GlPaths.FLATNESS + 1e-3,
                "a chord sagged %.3f px, past the %.2f tolerance".formatted(worst, GlPaths.FLATNESS));
        assertTrue(circle.size() < 200,
                "a 100px circle took " + circle.size() + " segments, which is not "
                        + "a tolerance-driven count");
    }

    @Test
    void aShapeWithAHoleFlattensToTwoContours() {
        Area ring = new Area(new Ellipse2D.Float(0, 0, 40, 40));
        ring.subtract(new Area(new Ellipse2D.Float(10, 10, 20, 20)));
        assertEquals(2, GlPaths.flatten(ring).size());
    }

    // --- convexity -------------------------------------------------------------

    @Test
    void theConvexTestSaysYesToTheShapesTheUiFills() {
        assertTrue(GlPaths.convex(GlPaths.flatten(new Rectangle2D.Float(0, 0, 10, 10)).get(0)));
        assertTrue(GlPaths.convex(GlPaths.flatten(
                new RoundRectangle2D.Float(0, 0, 60, 30, 12, 12)).get(0)));
        assertTrue(GlPaths.convex(GlPaths.flatten(new Ellipse2D.Float(0, 0, 40, 25)).get(0)));
        assertTrue(GlPaths.convex(GlPaths.flatten(
                        new Arc2D.Float(0, 0, 40, 40, 0, 90, Arc2D.PIE)).get(0)),
                "a quarter pie is convex and must take the cheap path");
    }

    @Test
    void theConvexTestSaysNoWhereAFanWouldBeWrong() {
        // A pie wider than a half turn: the two radii fold back past the centre.
        assertFalse(GlPaths.convex(GlPaths.flatten(
                new Arc2D.Float(0, 0, 40, 40, 0, 270, Arc2D.PIE)).get(0)));
        // An arrowhead — the shape a fan from vertex zero would fill outside.
        assertFalse(GlPaths.convex(new GlPaths.Contour(
                new float[]{0, 0, 10, 10, 20, 0, 10, 30}, true)));
    }

    @Test
    void aFanOfAConvexContourCoversItsAreaExactly() {
        Sink sink = new Sink();
        GlPaths.fan(GlPaths.flatten(new Rectangle2D.Float(0, 0, 10, 20)).get(0), sink);
        assertEquals(200.0, sink.area(), 1e-3);
        assertEquals(2, sink.triangles.size());
    }

    // --- stroking --------------------------------------------------------------

    @Test
    void aStrokedRectangleIsARingOfExactlyTheRightArea() {
        Sink sink = new Sink();
        GlPaths.stroke(GlPaths.flatten(new Rectangle2D.Float(10, 10, 100, 60)).get(0),
                4f, GlPaths.Cap.SQUARE, sink);
        // Centred on the path: half the width falls outside the rectangle and
        // half inside, so the ring runs from 104×64 down to 96×56.
        double expected = 104 * 64 - 96 * 56;
        assertEquals(expected, sink.area(), 0.5,
                "a 4px stroke around a 100×60 rectangle should cover its ring");
    }

    /**
     * The one that matters at anything under full alpha: the triangles of a
     * stroke must tile its ring, not pile up on it. Total emitted area equal to
     * the ring's area is exactly the statement that nothing was covered twice.
     */
    @Test
    void strokeTrianglesDoNotOverlapEachOther() {
        for (float width : new float[]{1f, 3f, 8f}) {
            Sink sink = new Sink();
            GlPaths.stroke(GlPaths.flatten(
                            new RoundRectangle2D.Float(0, 0, 80, 40, 16, 16)).get(0),
                    width, GlPaths.Cap.SQUARE, sink);
            double outer = areaOf(new Area(new RoundRectangle2D.Float(
                    -width / 2, -width / 2, 80 + width, 40 + width,
                    16 + width, 16 + width)));
            double inner = areaOf(new Area(new RoundRectangle2D.Float(
                    width / 2, width / 2, 80 - width, 40 - width,
                    Math.max(0, 16 - width), Math.max(0, 16 - width))));
            double ring = outer - inner;
            assertEquals(ring, sink.area(), ring * 0.03,
                    ("a %.0fpx stroke emitted %.1f px² of triangles for a %.1f px² ring — "
                            + "the difference is geometry drawn twice, which double-blends "
                            + "at any alpha below one").formatted(width, sink.area(), ring));
        }
    }

    @Test
    void aSquareCapExtendsTheLineByHalfItsWidthAtEachEnd() {
        Sink sink = new Sink();
        GlPaths.stroke(new GlPaths.Contour(new float[]{0, 0, 100, 0}, false),
                10f, GlPaths.Cap.SQUARE, sink);
        assertEquals(110 * 10.0, sink.area(), 1e-2);
    }

    @Test
    void aButtCapDoesNotExtendIt() {
        Sink sink = new Sink();
        GlPaths.stroke(new GlPaths.Contour(new float[]{0, 0, 100, 0}, false),
                10f, GlPaths.Cap.BUTT, sink);
        assertEquals(100 * 10.0, sink.area(), 1e-2);
    }

    @Test
    void aRoundCapAddsHalfADiscAtEachEnd() {
        Sink sink = new Sink();
        GlPaths.stroke(new GlPaths.Contour(new float[]{0, 0, 100, 0}, false),
                10f, GlPaths.Cap.ROUND, sink);
        double expected = 100 * 10.0 + Math.PI * 25;
        assertEquals(expected, sink.area(), expected * 0.01);
    }

    @Test
    void aDegenerateContourDoesNotProduceNaN() {
        Sink sink = new Sink();
        // A repeated point is what a flattened curve hands over at a cusp, and
        // a normalised zero-length segment is a NaN that takes the whole batch
        // with it rather than drawing one wrong triangle.
        GlPaths.stroke(new GlPaths.Contour(new float[]{5, 5, 5, 5, 20, 5}, false),
                3f, GlPaths.Cap.SQUARE, sink);
        for (float[] t : sink.triangles) {
            for (float v : t) assertTrue(Float.isFinite(v), "emitted a non-finite vertex");
        }
    }

    @Test
    void aSharpCornerBevelsRatherThanSpiking() {
        Sink sink = new Sink();
        // A 4-degree hairpin: an unlimited miter here reaches ~28× the half
        // width and puts a spike halfway across the screen.
        GlPaths.stroke(new GlPaths.Contour(new float[]{0, 0, 100, 0, 0, 7}, false),
                6f, GlPaths.Cap.SQUARE, sink);
        float furthest = 0;
        for (float[] t : sink.triangles) {
            for (int i = 0; i < 6; i += 2) furthest = Math.max(furthest, t[i]);
        }
        assertTrue(furthest < 100 + GlPaths.MITER_LIMIT * 3 + 1,
                "the miter reached x=" + furthest + ", past its own limit");
    }

    // --- dashes ----------------------------------------------------------------

    @Test
    void aDashedLineDrawsTheOnFractionAndNotTheOff() {
        Sink sink = new Sink();
        GlPaths.dashedLine(0, 0, 100, 0, 4f, 10f, 10f, sink);
        // Ten marks would be 100px; half of them are gaps, so five 10px marks
        // plus their round caps.
        double marks = 5 * (10 * 4.0 + Math.PI * 4);
        assertEquals(marks, sink.area(), marks * 0.05);
    }

    // --- winding ---------------------------------------------------------------

    @Test
    void aNonZeroPathIsNormalisedBeforeItIsFilledEvenOdd() {
        // Two overlapping squares in one non-zero path: the union is 175, and
        // even-odd over the raw path would give 175 - 2*25 = 125 by cancelling
        // the overlap. This is the terrain shadow union, in miniature.
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        path.append(new Rectangle2D.Double(0, 0, 10, 10), false);
        path.append(new Rectangle2D.Double(5, 5, 10, 10), false);

        java.awt.Shape normalised = GlPaths.normalise(path);
        assertEquals(175.0, areaOf(new Area(normalised)), 1e-6);
        assertEquals(PathIterator.WIND_NON_ZERO,
                path.getPathIterator(null).getWindingRule(),
                "the fixture must actually be a non-zero path or it proves nothing");

        double evenOdd = evenOddArea(GlPaths.flatten(normalised));
        assertEquals(175.0, evenOdd, 1e-3,
                "after normalising, even-odd coverage must equal the union — this is "
                        + "the property that lets the stencil count parity in one bit");
    }

    @Test
    void anEvenOddPathIsLeftAloneBecauseItIsAlreadySafe() {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        path.append(new Rectangle2D.Double(0, 0, 10, 10), false);
        assertTrue(GlPaths.normalise(path) == path,
                "normalising costs an Area construction and must not happen "
                        + "for the shapes that do not need it");
    }

    /** Even-odd coverage area, the way the stencil counts it. */
    private static double evenOddArea(List<GlPaths.Contour> contours) {
        Sink sink = new Sink();
        for (GlPaths.Contour c : contours) GlPaths.coverageFan(c, sink);
        return Math.abs(sink.signedTotal());
    }

    // --- clipping --------------------------------------------------------------

    @Test
    void clippingARectangleToAHalfPlaneKeepsTheSideTheNormalPointsInto() {
        float[] square = {0, 0, 10, 0, 10, 10, 0, 10};
        // A line running down the screen from (5,0): its normal is (-10, 0), so
        // the kept half is the smaller-x one. Both directions are asserted,
        // because a clipper that kept everything would pass a one-sided check.
        float[] kept = GlPaths.clipHalfPlane(square, new float[]{5, 0, 5, 10});
        assertEquals(4, kept.length / 2);
        for (int i = 0; i < kept.length; i += 2) {
            assertTrue(kept[i] <= 5 + 1e-4, "kept a point at x=" + kept[i]);
        }
        float[] other = GlPaths.clipHalfPlane(square, new float[]{5, 10, 5, 0});
        assertEquals(4, other.length / 2);
        for (int i = 0; i < other.length; i += 2) {
            assertTrue(other[i] >= 5 - 1e-4, "reversing the line kept x=" + other[i]);
        }
    }

    @Test
    void clippingByALineThatMissesKeepsEverything() {
        float[] square = {0, 0, 10, 0, 10, 10, 0, 10};
        float[] kept = GlPaths.clipHalfPlane(square, new float[]{15, 0, 15, 10});
        assertEquals(4, kept.length / 2);
        assertEquals(0f, kept[0]);
    }

    private static double areaOf(Area area) {
        // Shoelace over the flattened boundary: Area has no area() of its own,
        // and every use here is a shape a quarter-pixel tolerance describes
        // well enough to compare against triangle counts.
        double total = 0;
        PathIterator it = area.getPathIterator(null, GlPaths.FLATNESS / 8);
        double[] seg = new double[6];
        double startX = 0, startY = 0, x = 0, y = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(seg)) {
                case PathIterator.SEG_MOVETO -> {
                    startX = x = seg[0];
                    startY = y = seg[1];
                }
                case PathIterator.SEG_LINETO -> {
                    total += x * seg[1] - seg[0] * y;
                    x = seg[0];
                    y = seg[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    total += x * startY - startX * y;
                    x = startX;
                    y = startY;
                }
                default -> throw new IllegalStateException("unflattened segment");
            }
            it.next();
        }
        return Math.abs(total) / 2;
    }
}
