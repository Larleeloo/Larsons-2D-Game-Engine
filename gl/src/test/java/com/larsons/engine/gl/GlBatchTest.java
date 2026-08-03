package com.larsons.engine.gl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The vertex buffer holds whole triangles, and a frame bigger than it still
 * draws the right picture.
 *
 * <p><b>Why this test exists.</b> {@link GlBatch} grew its buffer when a batch
 * hit 4,096 vertices, and 4,096 is not a multiple of three. The grow flushed
 * mid-triangle; {@code glDrawArrays} drew the 1,365 whole triangles and ignored
 * the leftover vertex; the two vertices still to come were written at the head
 * of the new buffer; and from that point on every triangle in the frame was
 * assembled from vertices two places apart — the corners of <em>different</em>
 * sprites and glyphs. It looked like entities with lines coming out of them and
 * text that had been shuffled.
 *
 * <p><b>Why nothing caught it.</b> Every frame in B0's golden catalogue is small
 * — the busiest issues 374 operations — and they change texture often enough
 * that no single batch ever reached 4,096 vertices. `GlParityTest` was green on
 * all 32 while a real level, at 2,942 operations a frame merging into a handful
 * of batches, crossed the boundary inside the first frame and corrupted every
 * frame after it. The catalogue tested the backend's <em>vocabulary</em>
 * thoroughly and its <em>volume</em> not at all.
 *
 * <p>So this test is about volume and nothing else: one texture, one batch,
 * enough rectangles to cross the boundary several times, each in a colour
 * derived from its own index. If the triangle stream ever desynchronises, a
 * rectangle wears a neighbour's colour and the assertion names the first index
 * where it happened.
 */
class GlBatchTest {

    /** Big enough to cross the first growth at 4,095 vertices several times over. */
    private static final int WIDTH = 640, HEIGHT = 480;
    private static final int CELL = 8, INSET = 1, SIZE = CELL - 2 * INSET;

    private static GlContext context;
    private static GlSurface surface;
    private static GlTarget target;

    @BeforeAll
    static void openContext() {
        context = GlContext.offscreen(GlRenderer.SAMPLES);
        if (!context.available()) return;
        surface = new GlSurface(GlRenderer.SAMPLES);
        target = new GlTarget(WIDTH, HEIGHT);
    }

    @AfterAll
    static void closeContext() {
        if (target != null) target.close();
        if (surface != null) surface.close();
        if (context != null) context.close();
    }

    // --- the arithmetic, which needs no driver ----------------------------------

    /**
     * The invariant the class documents, checked as a pure function so it holds
     * on a build agent with no GPU — which is where this bug would have been
     * cheapest to catch and where it was invisible for three steps.
     */
    @Test
    void everyCapacityIsAWholeNumberOfTriangles() {
        for (int requested : new int[] {4096, 8192, 16384, 32768, 65536}) {
            int rounded = GlBatch.wholeTriangles(requested);
            assertEquals(0, rounded % 3,
                    requested + " rounds to " + rounded + ", which is "
                            + (rounded % 3) + " vertices into a triangle — a flush there "
                            + "desynchronises every triangle after it");
            assertTrue(rounded <= requested && rounded > requested - 3,
                    "rounding down should lose at most two vertices, not " + (requested - rounded));
        }
    }

    @Test
    void doublingAWholeNumberOfTrianglesStaysOne() {
        int capacity = GlBatch.wholeTriangles(4096);
        for (int growth = 0; growth < 6; growth++) {
            capacity = GlBatch.wholeTriangles(capacity * 2);
            assertEquals(0, capacity % 3, "capacity drifted off a triangle boundary while growing");
        }
    }

    /** A buffer can never round to nothing, however small it is asked to be. */
    @Test
    void theSmallestBufferIsStillOneTriangle() {
        assertEquals(3, GlBatch.wholeTriangles(0));
        assertEquals(3, GlBatch.wholeTriangles(2));
        assertEquals(3, GlBatch.wholeTriangles(3));
    }

    // --- the picture, which does ------------------------------------------------

    @BeforeEach
    void requireContext() {
        assumeTrue(context != null && context.available(),
                () -> "no GL 3.3 core context: "
                        + (context == null ? "not created" : context.whyUnavailable()));
        context.makeCurrent();
    }

    /**
     * 4,800 rectangles in one batch — 28,800 vertices, crossing the growth
     * boundary at 4,095, 8,190 and 16,380 — and every one of them lands where it
     * was put, in the colour it was given.
     *
     * <p>Flat fills all sample the same white texel, so nothing here forces a
     * texture change and the whole frame is one batch. That is the arrangement a
     * real level produces and the one the golden catalogue never did.
     */
    @Test
    void aFrameLargerThanTheVertexBufferIsNotScrambled() {
        int columns = WIDTH / CELL, rows = HEIGHT / CELL;
        int rectangles = columns * rows;

        surface.resize(WIDTH, HEIGHT);
        surface.bind();
        target.beginFrame(WIDTH, HEIGHT);
        target.clear(0xFF000000);
        for (int i = 0; i < rectangles; i++) {
            int column = i % columns, row = i / columns;
            target.fillRect(column * CELL + INSET, row * CELL + INSET, SIZE, SIZE,
                    new Color(colourOf(i), true));
        }
        target.endFrame();

        assertTrue(rectangles * 6 > 4095 * 3,
                "this test is meant to cross the growth boundary several times; it draws only "
                        + rectangles * 6 + " vertices");

        int[] pixels = surface.readPixels();
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < rectangles && wrong.size() < 5; i++) {
            int column = i % columns, row = i / columns;
            int x = column * CELL + CELL / 2, y = row * CELL + CELL / 2;
            int found = pixels[y * WIDTH + x];
            int expected = colourOf(i);
            if (found != expected) {
                wrong.add("rectangle %d at (%d,%d): expected %08X, found %08X"
                        .formatted(i, x, y, expected, found));
            }
        }
        assertEquals(List.of(), wrong,
                "the triangle stream desynchronised — rectangles are wearing each other's "
                        + "corners. The first bad index says which vertex the batch flushed "
                        + "on:\n  " + String.join("\n  ", wrong));
    }

    /**
     * The same volume with text in it, because the report that started this
     * named text first and glyphs are the smallest quads the engine emits —
     * so they are the ones a two-vertex shift scrambles most visibly.
     */
    @Test
    void aFrameOfManyGlyphsIsNotScrambled() {
        surface.resize(WIDTH, HEIGHT);
        surface.bind();
        target.beginFrame(WIDTH, HEIGHT);
        target.clear(0xFF000000);

        // 30 rows of 55 characters — a few thousand glyph quads — plus a filled
        // bar behind each row, so shapes and glyphs share one batch the way a
        // HUD makes them. The bottom quarter of the surface is left deliberately
        // empty; that is where the assertion looks.
        int rows = 30, lineHeight = 12;
        int lastRowBottom = rows * lineHeight;
        String line = "The quick brown fox jumps over the lazy dog 0123456789 ";
        for (int row = 0; row < rows; row++) {
            target.fillRect(0, row * lineHeight, WIDTH, lineHeight - 1, new Color(0x20FFFFFF, true));
            target.drawText(line, 2, row * lineHeight + 9, null, Color.WHITE);
        }
        target.endFrame();

        assertTrue(target.drawCalls() >= 1, "a frame of 30 text rows issued no draw call");

        // Checked for structure rather than for exact glyphs: a desynchronised
        // stream throws long triangles across the whole surface, so the giveaway
        // is ink well below the last row anything was drawn on.
        int[] pixels = surface.readPixels();
        int inkBelowTheText = 0;
        for (int y = lastRowBottom + 8; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if ((pixels[y * WIDTH + x] & 0xFFFFFF) != 0) inkBelowTheText++;
            }
        }
        assertTrue(HEIGHT - (lastRowBottom + 8) > 80, "the empty region has to be worth checking");
        assertEquals(0, inkBelowTheText,
                inkBelowTheText + " pixels were painted below the last row of text, which is "
                        + "where stray triangles from a shifted vertex stream land");
    }

    /**
     * A distinct, fully opaque colour per index, never equal to the clear.
     *
     * <p>The three channels advance at different rates so that neighbouring
     * rectangles — the ones a two-vertex shift swaps — are far apart in colour
     * rather than one step apart, and the blue channel is forced odd so no
     * index can land on the black the frame was cleared to.
     */
    private static int colourOf(int index) {
        int r = (index * 37) & 0xFF;
        int g = (index * 91 + 40) & 0xFF;
        int b = ((index * 173 + 90) & 0xFE) | 1;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
