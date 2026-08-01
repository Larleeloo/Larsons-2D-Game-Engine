package com.larsons.engine;

import com.larsons.engine.graphics.TilePainter;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend-neutral draw API (Job B, step one).
 *
 * <p>Two things are being pinned down. First, that {@link Java2DTarget} still
 * draws what the engine drew before — the property that makes porting painters
 * one at a time a refactor rather than a rewrite. Second, that a painter's
 * output can be asserted on without a display, which is what makes the rest of
 * the migration checkable at all.
 */
class DrawTargetTest {

    private static BufferedImage image(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /** A real Graphics2D over an offscreen image, for the Java2D backend. */
    private static Graphics2D offscreen(BufferedImage into) {
        return into.createGraphics();
    }

    // --- the Java2D backend draws real pixels -----------------------------------

    @Test
    void filledShapesReachThePixels() {
        BufferedImage surface = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = offscreen(surface);
        DrawTarget target = new Java2DTarget(g, 32, 32);

        target.clear(0xFF000000);
        target.fillRect(4, 4, 8, 8, 0xFFFF0000);
        g.dispose();

        assertEquals(0xFFFF0000, surface.getRGB(5, 5), "the rect should be red");
        assertEquals(0xFF000000, surface.getRGB(20, 20), "outside it should be the clear colour");
    }

    @Test
    void clipsConfineDrawingAndPoppingReleasesThem() {
        BufferedImage surface = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = offscreen(surface);
        DrawTarget target = new Java2DTarget(g, 32, 32);
        target.clear(0xFF000000);

        target.pushClip(0, 0, 8, 8);
        target.fillRect(0, 0, 32, 32, 0xFF00FF00);
        target.popClip();
        target.fillRect(20, 20, 4, 4, 0xFF0000FF);
        g.dispose();

        assertEquals(0xFF00FF00, surface.getRGB(4, 4), "inside the clip");
        assertEquals(0xFF000000, surface.getRGB(16, 16), "the clip held");
        assertEquals(0xFF0000FF, surface.getRGB(21, 21), "and was released by the pop");
    }

    @Test
    void alphaIsScopedToItsPush() {
        BufferedImage surface = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = offscreen(surface);
        DrawTarget target = new Java2DTarget(g, 8, 8);
        target.clear(0xFF000000);

        target.pushAlpha(0.5f);
        target.fillRect(0, 0, 4, 8, 0xFFFFFFFF);
        target.popAlpha();
        target.fillRect(4, 0, 4, 8, 0xFFFFFFFF);
        g.dispose();

        int blended = surface.getRGB(1, 1) & 0xFF;
        int solid = surface.getRGB(5, 1) & 0xFF;
        assertTrue(blended > 0 && blended < 255,
                "half alpha over black should be grey, was " + blended);
        assertEquals(255, solid, "after the pop, drawing is opaque again");
    }

    @Test
    void transformsAreScopedToTheirPush() {
        BufferedImage surface = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = offscreen(surface);
        DrawTarget target = new Java2DTarget(g, 32, 32);
        target.clear(0xFF000000);

        target.pushTransform(AffineTransform.getTranslateInstance(16, 16));
        target.fillRect(0, 0, 4, 4, 0xFFFF0000);
        target.popTransform();
        target.fillRect(0, 0, 4, 4, 0xFF00FF00);
        g.dispose();

        assertEquals(0xFFFF0000, surface.getRGB(17, 17), "drawn through the translation");
        assertEquals(0xFF00FF00, surface.getRGB(1, 1), "and at the origin after the pop");
    }

    @Test
    void unbalancedPopsAreIgnoredRatherThanThrowing() {
        // A half-migrated frame will get this wrong at some point; losing a
        // clip is recoverable, taking the render thread down is not.
        Graphics2D g = offscreen(image(8, 8));
        DrawTarget target = new Java2DTarget(g, 8, 8);

        target.popClip();
        target.popAlpha();
        target.popTransform();
        target.fillRect(0, 0, 4, 4, 0xFFFFFFFF);
        g.dispose();
    }

    @Test
    void nullImagesAreSkippedNotDrawn() {
        // Texture lookups miss all the time (a block with no sheet of its own).
        RecordingTarget target = new RecordingTarget(64, 64);
        target.drawImage(null, 0, 0);
        target.drawImage(null, 0, 0, 8, 8);
        target.drawImage(null, new AffineTransform());

        assertTrue(target.commands().isEmpty(), "a missing texture draws nothing");
        assertEquals(0, target.stats().operations());
    }

    @Test
    void anUnsizedTargetReportsNoSizeAndClearsNothing() {
        RecordingTarget probe = new RecordingTarget(0, 0);
        Graphics2D g = offscreen(image(8, 8));
        Java2DTarget target = Java2DTarget.unsized(g);

        assertEquals(0, target.width());
        assertEquals(0, target.height());
        target.clear(0xFFFF0000);   // must not guess a size
        assertEquals(0, target.stats().operations(), "an unsized clear is not an operation");
        g.dispose();
        assertEquals(0, probe.stats().operations());
    }

    @Test
    void theGraphicsSeamIsTheOneStillHandedOut() {
        // Unported painters need it; the count of uses is the work remaining.
        Graphics2D g = offscreen(image(4, 4));
        assertSame(g, Java2DTarget.unsized(g).graphics());
        g.dispose();
    }

    // --- the recorder --------------------------------------------------------------

    @Test
    void theRecorderKeepsOperationsInOrder() {
        RecordingTarget target = new RecordingTarget(100, 100);
        target.fillRect(0, 0, 10, 10, Color.RED);
        target.drawLine(0, 0, 10, 10, Color.BLUE);
        target.fillOval(2, 2, 6, 6, Color.GREEN);

        assertEquals(List.of("fillRect", "drawLine", "fillOval"), target.ops());
    }

    @Test
    void recordedShapesCarryTheirArgumentsBack() {
        RecordingTarget target = new RecordingTarget(100, 100);
        target.fillRect(3, 5, 7, 11, 0xFF123456);

        RecordingTarget.Cmd.Shape shape =
                target.ofType(RecordingTarget.Cmd.Shape.class).get(0);
        assertArrayEqualsInt(new int[]{3, 5, 7, 11}, shape.coords());
        assertEquals(0xFF123456, shape.argb());
    }

    @Test
    void colourOverloadsMatchTheirIntForms() {
        RecordingTarget viaColor = new RecordingTarget(10, 10);
        RecordingTarget viaInt = new RecordingTarget(10, 10);
        viaColor.fillRect(1, 2, 3, 4, new Color(0x80, 0x40, 0x20));
        viaInt.fillRect(1, 2, 3, 4, new Color(0x80, 0x40, 0x20).getRGB());

        assertEquals(
                target(viaColor).argb(), target(viaInt).argb(),
                "the Color overload must not change the packed value");
    }

    private static RecordingTarget.Cmd.Shape target(RecordingTarget t) {
        return t.ofType(RecordingTarget.Cmd.Shape.class).get(0);
    }

    @Test
    void polygonsRecordOnlyTheUsedPoints() {
        // Painters reuse oversized scratch arrays rather than allocating.
        RecordingTarget target = new RecordingTarget(64, 64);
        int[] xs = {0, 10, 10, 0, 999, 999};
        int[] ys = {0, 0, 10, 10, 999, 999};
        target.fillPolygon(xs, ys, 4, Color.WHITE);

        RecordingTarget.Cmd.Shape poly =
                target.ofType(RecordingTarget.Cmd.Shape.class).get(0);
        assertEquals(8, poly.coords().length, "four points, interleaved");
        assertArrayEqualsInt(new int[]{0, 0, 10, 0, 10, 10, 0, 10}, poly.coords());
    }

    @Test
    void textMetricsNeedNoDisplay() {
        RecordingTarget target = new RecordingTarget(100, 100, 7, 11, 14);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

        assertEquals(21, target.textWidth("abc", font));
        assertEquals(11, target.textAscent(font));
        assertEquals(14, target.textHeight(font));
        assertEquals(0, target.textWidth("", font));
    }

    @Test
    void emptyTextDrawsNothing() {
        RecordingTarget target = new RecordingTarget(64, 64);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        target.drawText("", 0, 0, font, Color.WHITE);
        target.drawText(null, 0, 0, font, Color.WHITE);

        assertTrue(target.commands().isEmpty());
    }

    // --- batching statistics ---------------------------------------------------------

    @Test
    void consecutiveShapesMergeIntoOneBatch() {
        RecordingTarget target = new RecordingTarget(64, 64);
        for (int i = 0; i < 50; i++) target.fillRect(i, 0, 1, 1, Color.RED);

        DrawStats stats = target.stats();
        assertEquals(50, stats.operations());
        assertEquals(1, stats.batches(), "solid geometry batches together");
        assertEquals(50.0, stats.mergeRatio(), 1e-9);
    }

    @Test
    void alternatingTexturesDefeatBatching() {
        // The finding that would say "fix the atlases before writing OpenGL".
        RecordingTarget target = new RecordingTarget(64, 64);
        BufferedImage a = image(8, 8), b = image(8, 8);
        for (int i = 0; i < 10; i++) {
            target.drawImage(a, 0, 0);
            target.drawImage(b, 8, 0);
        }

        assertEquals(20, target.stats().operations());
        assertEquals(20, target.stats().batches(),
                "every switch of source image starts a new draw call");
        assertEquals(1.0, target.stats().mergeRatio(), 1e-9);
    }

    @Test
    void runsOfOneTextureBatchAndTheNextTextureBreaksIt() {
        RecordingTarget target = new RecordingTarget(64, 64);
        BufferedImage a = image(8, 8), b = image(8, 8);
        for (int i = 0; i < 10; i++) target.drawImage(a, i, 0);
        for (int i = 0; i < 10; i++) target.drawImage(b, i, 8);

        assertEquals(20, target.stats().operations());
        assertEquals(2, target.stats().batches(), "one batch per texture run");
    }

    @Test
    void stateChangesAlwaysBreakABatch() {
        RecordingTarget target = new RecordingTarget(64, 64);
        target.fillRect(0, 0, 1, 1, Color.RED);
        target.fillRect(1, 0, 1, 1, Color.RED);
        target.pushClip(0, 0, 4, 4);
        target.fillRect(2, 0, 1, 1, Color.RED);
        target.popClip();

        DrawStats stats = target.stats();
        assertEquals(5, stats.operations());
        assertEquals(2, stats.stateChanges());
        assertEquals(4, stats.batches(),
                "two shape runs plus the two state changes that split them");
    }

    @Test
    void statsResetBetweenFrames() {
        RecordingTarget target = new RecordingTarget(64, 64);
        target.fillRect(0, 0, 1, 1, Color.RED);
        DrawStats snapshot = target.stats().copy();
        target.stats().reset();
        target.fillRect(0, 0, 1, 1, Color.RED);

        assertEquals(1, snapshot.operations(), "the copy is not live");
        assertEquals(1, target.stats().operations(), "the new frame counts from zero");
    }

    // --- the first ported painter ------------------------------------------------------

    @Test
    void anOrthographicTileIsOneAxisAlignedBlit() {
        RecordingTarget target = new RecordingTarget(256, 256);
        BufferedImage tile = image(16, 16);
        int[] xs = {10, 42, 42, 10};
        int[] ys = {20, 20, 52, 52};

        TilePainter.drawTexture(target, tile, xs, ys, true);

        List<RecordingTarget.Cmd.Image> drawn =
                target.ofType(RecordingTarget.Cmd.Image.class);
        assertEquals(1, drawn.size(), "one tile is one draw");
        assertEquals("drawImageScaled", drawn.get(0).op());
        assertArrayEqualsInt(new int[]{10, 20, 33, 33}, drawn.get(0).coords());
    }

    @Test
    void anIsometricTileIsOneWarpedBlit() {
        // The hardest operation the engine performs, and the reason the draw
        // API carries a transform verb at all.
        RecordingTarget target = new RecordingTarget(256, 256);
        BufferedImage tile = image(16, 16);
        int[] xs = {100, 132, 100, 68};
        int[] ys = {50, 66, 82, 66};

        TilePainter.drawTexture(target, tile, xs, ys, false);

        List<RecordingTarget.Cmd.Image> drawn =
                target.ofType(RecordingTarget.Cmd.Image.class);
        assertEquals(1, drawn.size(), "a diamond is still one draw, not four triangles");
        assertEquals("drawImageTransformed", drawn.get(0).op());
        assertNotNull(drawn.get(0).transform());
    }

    @Test
    void theIsoTransformMapsTheImageOntoTheDiamondsCorners() {
        // A GL backend places the quad from corners rather than a matrix, so
        // the mapping itself has to be right, not merely present.
        BufferedImage tile = image(16, 16);
        int[] xs = {100, 132, 100, 68};
        int[] ys = {50, 66, 82, 66};
        AffineTransform tx = TilePainter.isoTransform(tile, xs, ys);

        assertCorner(tx, 0, 0, xs[0], ys[0]);           // image top-left
        assertCorner(tx, 16, 0, xs[1], ys[1]);          // top-right
        assertCorner(tx, 0, 16, xs[3], ys[3]);          // bottom-left
        assertCorner(tx, 16, 16, xs[2], ys[2]);         // bottom-right
    }

    @Test
    void aMissingTileTextureDrawsNothing() {
        RecordingTarget target = new RecordingTarget(64, 64);
        TilePainter.drawTexture(target, null, new int[4], new int[4], true);
        assertTrue(target.commands().isEmpty());
    }

    @Test
    void theGraphics2DOverloadStillWorksForUnportedCallers() {
        BufferedImage surface = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = offscreen(surface);
        BufferedImage tile = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = tile.createGraphics();
        tg.setColor(Color.RED);
        tg.fillRect(0, 0, 4, 4);
        tg.dispose();

        TilePainter.drawTexture(g, tile, new int[]{0, 8, 8, 0}, new int[]{0, 0, 8, 8}, true);
        g.dispose();

        assertEquals(0xFFFF0000, surface.getRGB(2, 2),
                "the compatibility overload must draw the same pixels");
    }

    private static void assertCorner(AffineTransform tx, double sx, double sy,
                                     int expectedX, int expectedY) {
        double[] point = {sx, sy};
        tx.transform(point, 0, point, 0, 1);
        assertEquals(expectedX, point[0], 0.001, "corner x");
        assertEquals(expectedY, point[1], 0.001, "corner y");
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length, "length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }
}
