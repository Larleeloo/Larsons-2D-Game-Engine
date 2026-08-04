package com.larsons.engine.gl;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.render.FrameError;
import com.larsons.engine.render.GoldenFrames;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * D0 — what the GL backend actually does at 2× HiDPI, and how it differs from
 * what the player sees on Java2D.
 *
 * <p><b>D0 began as a diagnostic and is kept as an assertion, because what it
 * found is worth holding.</b> The shimmer reported from a real level had two
 * plausible causes when this was written and both rested on a false premise — the scene hands the
 * backend <em>integer</em> logical coordinates, because {@code Camera.screenX}
 * rounds and {@code DrawTarget.drawImage} takes ints, so no sprite is ever
 * placed at a fractional position for a fractional device pixel to fall out of.
 *
 * <p>So the question this answers is the one nothing had asked: **every parity
 * measurement in this project has been taken at scale 1**, and the machine the
 * shimmer was seen on runs at scale 2. `GlParityTest` renders the golden
 * catalogue on a 1× surface and compares it to a 1× Java2D reference; it has
 * never seen the configuration the player is in.
 *
 * <p>The comparison here is deliberately made <em>as the player sees it</em>:
 * Java2D composes at logical size and the window server upscales that by two
 * with nearest neighbour, so the reference is upscaled the same way before being
 * subtracted from GL's native 2× output. Anything that survives that is a real
 * difference between the two pictures on screen.
 */
class GlHiDpiParityTest {

    private static final int W = 96, H = 64;
    private static final double SCALE = 2.0;
    /**
     * Discovered rather than copied, the way {@code GlParityTest} does it: a
     * constant here that disagreed with what {@code GoldenFrames} clears to
     * would show up as a whole-frame error and be read as a backend defect. It
     * was, on the first run of this diagnostic — 1.594 of pure clear-colour
     * mismatch, which is exactly the trap that comment was written about.
     */
    private static int background;

    private static GlContext context;
    private static GlSurface surface;
    private static GlTarget target;

    @BeforeAll
    static void openContext() {
        context = GlContext.offscreen(GlRenderer.SAMPLES);
        if (!context.available()) return;
        surface = new GlSurface(GlRenderer.SAMPLES);
        target = new GlTarget(W, H, SCALE);
        background = GoldenFrames.render(
                new GoldenFrames.Frame("probe", 1, 1, t -> {})).getRGB(0, 0) | 0xFF000000;
    }

    @AfterAll
    static void closeContext() {
        if (target != null) target.close();
        if (surface != null) surface.close();
        if (context != null) context.close();
    }

    @BeforeEach
    void requireContext() {
        assumeTrue(context != null && context.available(),
                () -> "no GL 3.3 core context: "
                        + (context == null ? "not created" : context.whyUnavailable()));
        context.makeCurrent();
    }

    /**
     * A wall of tiles swept across eight whole pixels — the terrain case, which
     * is what was reported as jittering. Pure sprite blits, no shapes, no text,
     * so anything here is the image path alone.
     */
    @Test
    void tilesAreIdenticalToJava2DAtEveryCameraPosition() {
        System.out.println();
        System.out.println("D0/a — tiles only, GL at scale 2 vs Java2D upscaled 2x nearest");
        System.out.println("camera x | mean channel error (of 255)");
        System.out.println("---------|----------------------------");
        double worst = 0;
        for (int offset = 0; offset < 8; offset++) {
            double error = compare("tiles-" + offset, t -> {}, offset, false);
            worst = Math.max(worst, error);
            System.out.printf("   %2d    | %8.3f%n", offset, error);
        }
        System.out.printf("worst: %.3f%n%n", worst);
        // Not "within the 3.58 bar" — exactly equal. A nearest-neighbour sprite
        // doubled by the panel and the same sprite drawn at twice the size are
        // the same pixels, or the backend is doing something to pixel art that
        // it should not be.
        assertEquals(0.0, worst, 1e-9,
                "GL at scale 2 no longer matches Java2D upscaled for plain sprites; "
                        + "the image path has acquired a HiDPI difference");
    }

    /**
     * The same sweep with the things that are <em>meant</em> to differ: an
     * antialiased shape and a text run. GL renders these at device resolution;
     * Java2D renders them at logical resolution and the panel doubles them. A
     * large number here is not a defect, it is the reason a GL frame looks
     * sharper — but it has never been quantified, and it wants separating from
     * the tile number above before anyone reads either.
     */
    @Test
    void shapesAndTextDifferByAConstantThatDoesNotMoveWithTheCamera() {
        double first = -1;
        System.out.println("D0/b — tiles + shapes + text, same comparison");
        System.out.println("camera x | mean channel error (of 255)");
        System.out.println("---------|----------------------------");
        for (int offset = 0; offset < 8; offset++) {
            double error = compare("mixed-" + offset, t -> {}, offset, true);
            System.out.printf("   %2d    | %8.3f%n", offset, error);
            if (first < 0) first = error;
            assertEquals(first, error, 1e-9,
                    "the shapes-and-text difference moved with the camera, which is the "
                            + "shimmer signature; a constant difference is only GL being sharper");
        }
        System.out.println();
    }

    /**
     * Frame-to-frame stability, which is what "jitter" actually means: how much
     * of the picture changes between one camera position and the next. A clean
     * one-pixel pan changes a predictable amount; a shimmer changes more, and
     * changes pixels that should have been carried across untouched.
     */
    @Test
    void aOnePixelPanChangesTheSamePixelsOnBothBackends() {
        System.out.println("D0/c — change between consecutive camera positions (tiles only)");
        System.out.println("step  | GL: px changed | Java2D(x2): px changed");
        System.out.println("------|----------------|-----------------------");
        int[] previousGl = null, previousJava = null;
        for (int offset = 0; offset < 8; offset++) {
            int[] gl = renderGl(offset, false);
            int[] java = FrameError.pixels(upscale(renderJava2D("j" + offset, offset, false)));
            if (previousGl != null) {
                int changedGl = differing(previousGl, gl);
                int changedJava = differing(previousJava, java);
                System.out.printf(" %d->%d  |      %6d    |        %6d%n",
                        offset - 1, offset, changedGl, changedJava);
                assertEquals(changedJava, changedGl,
                        "a one-pixel pan disturbed a different amount of the frame on GL "
                                + "than on Java2D — that is what a shimmer looks like from here");
            }
            previousGl = gl;
            previousJava = java;
        }
        System.out.println();
    }

    // --- the scene -------------------------------------------------------------

    /**
     * A tile wall positioned the way the engine positions one: the camera
     * offset is applied and rounded to a whole logical pixel before it reaches
     * the target, exactly as {@code Camera.screenX} does.
     */
    private static void paint(DrawTarget t, int cameraX, boolean withShapesAndText) {
        BufferedImage tile = tile();
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 9; column++) {
                t.drawImage(tile, column * 12 - cameraX, 8 + row * 11);
            }
        }
        if (withShapesAndText) {
            t.fillOval(20 - cameraX, 10, 25, 25, new Color(220, 90, 60, 200));
            t.drawText("jitter", 8 - cameraX, 58, null, Color.WHITE);
        }
    }

    /** A 12×11 pixel-art tile with internal detail, so a shift is visible. */
    private static BufferedImage tile() {
        BufferedImage image = new BufferedImage(12, 11, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 11; y++) {
                boolean edge = x == 0 || y == 0 || x == 11 || y == 10;
                boolean speck = ((x * 7 + y * 13) % 5) == 0;
                image.setRGB(x, y, edge ? 0xFF3A2A18 : (speck ? 0xFF6E5A34 : 0xFF87703F));
            }
        }
        return image;
    }

    // --- rendering both ways ---------------------------------------------------

    private double compare(String name, java.util.function.Consumer<DrawTarget> extra,
                           int cameraX, boolean withShapesAndText) {
        int[] gl = renderGl(cameraX, withShapesAndText);
        int[] java = FrameError.pixels(upscale(renderJava2D(name, cameraX, withShapesAndText)));
        return FrameError.meanChannelError(gl, java);
    }

    private int[] renderGl(int cameraX, boolean withShapesAndText) {
        surface.resize((int) (W * SCALE), (int) (H * SCALE));
        surface.bind();
        target.beginFrame(W, H);
        target.clear(background);
        paint(target, cameraX, withShapesAndText);
        target.endFrame();
        return surface.readPixels();
    }

    private static BufferedImage renderJava2D(String name, int cameraX,
                                              boolean withShapesAndText) {
        return GoldenFrames.render(new GoldenFrames.Frame(name, W, H,
                t -> paint(t, cameraX, withShapesAndText)));
    }

    /** Nearest-neighbour doubling — what the window server does to a 1× frame. */
    private static BufferedImage upscale(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage out = new BufferedImage((int) (w * SCALE), (int) (h * SCALE),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                out.setRGB(x, y, source.getRGB((int) (x / SCALE), (int) (y / SCALE)));
            }
        }
        return out;
    }

    private static int differing(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if ((a[i] & 0xFFFFFF) != (b[i] & 0xFFFFFF)) n++;
        }
        return n;
    }
}
