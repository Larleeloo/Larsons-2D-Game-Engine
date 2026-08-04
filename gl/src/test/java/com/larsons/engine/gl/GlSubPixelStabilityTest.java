package com.larsons.engine.gl;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.render.FrameError;
import com.larsons.engine.render.GoldenFrames;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shimmer question asked of the GL backend at the settings a player is
 * actually in — a camera that moves a fraction of a pixel, and a zoom that makes
 * a tile a fractional number of pixels wide.
 *
 * <p><b>{@link GlHiDpiParityTest} answers a narrower question than its name
 * suggests, and the gap is why this exists.</b> That test sweeps the camera in
 * <em>whole</em> pixels, positions its tiles by integer arithmetic of its own
 * rather than through {@link Camera}, and uses a 12×11 tile — so every
 * coordinate in it is a whole number before it reaches the backend, at every
 * step. Its answer (GL at 2× is pixel-identical to Java2D upscaled) is true and
 * was worth having. It simply cannot reach the case where a shimmer can exist,
 * because it never produces a fractional quantity for anything to round.
 *
 * <p>So this drives a real {@code Camera} at {@code zoom = 1.7}, where a
 * 32-unit tile is 54.4 pixels wide, and creeps it a quarter of a pixel at a
 * time. Two questions, and they are different:
 *
 * <ol>
 *   <li><b>Is the GL frame rigid?</b> Each frame must equal the previous one
 *       shifted by exactly the whole pixels the camera moved. This is the
 *       property {@code CameraStabilityTest} holds on the CPU side; holding it
 *       here as well is what says the GL path adds nothing of its own —
 *       batching, atlas sampling and the multisample resolve all had the
 *       opportunity.</li>
 *   <li><b>Does GL still agree with Java2D?</b> At the same fractional
 *       positions, which is the comparison D0 made only at whole ones.</li>
 * </ol>
 *
 * <p>Together they close the question the other way round from a fix: if both
 * hold, whatever a player still sees moving is not the geometry, and the search
 * belongs somewhere else. That is the only honest way to stop looking here.
 */
class GlSubPixelStabilityTest {

    private static final int W = 96, H = 64;
    private static final double SCALE = 2.0;

    /** A tile is 54.4 logical pixels at this zoom — see the class note. */
    private static final double ZOOM = 1.7;
    private static final int TILE = 32;

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
     * A GL frame moves as one rigid sheet when the camera creeps.
     *
     * <p>The camera's contribution to a screen position is one rounded integer
     * per axis, so the difference between two frames is entirely predictable:
     * the picture shifts by that integer's change and nothing else about it may
     * move. Every pixel outside a margin has to match.
     */
    @Test
    void aGlFrameShiftsRigidlyAsTheCameraCreeps() {
        int[] previous = null;
        int previousOffset = 0;
        for (int step = 0; step <= 16; step++) {
            double cameraX = 400 + step * 0.25;
            int offset = cameraOffset(cameraX);
            int[] frame = renderGl(cameraX);
            if (previous != null) {
                int shift = (int) ((offset - previousOffset) * SCALE);
                assertEquals(0, differingShifted(previous, frame, shift),
                        "the GL frame changed by more than the camera moved at cameraX="
                                + cameraX + " (expected a whole-sheet shift of " + shift
                                + " device px) — something in the GL path is rounding "
                                + "against the camera rather than with it");
            }
            previous = frame;
            previousOffset = offset;
        }
    }

    /**
     * Neither backend moves more than the other, which is the comparison that
     * settles whose defect a shimmer is.
     *
     * <p><b>Deliberately not an assertion that the two frames are equal.</b>
     * They are not, and should not be: at a fractional zoom GL picks texels at
     * device resolution while Java2D picks them at logical resolution and the
     * window server doubles the result, so GL is legitimately sharper and the
     * two differ by around 2.6 of 255. D0 could assert exact equality because it
     * only ever drew at whole-pixel sizes, where that difference cannot arise.
     * Asserting it here would be demanding that GL throw away the resolution it
     * exists to provide.
     *
     * <p>What must match is the <em>movement</em>. If GL disturbs more of the
     * frame than Java2D does between two camera positions, the extra is
     * something the GL path is doing on its own — which it was, in the sampler,
     * to the tune of 28–169 pixels a step against Java2D's nought.
     */
    @Test
    void glDisturbsNoMoreOfTheFrameThanJava2DDoes() {
        int[] previousGl = null, previousJava = null;
        int previousOffset = 0;
        for (int step = 0; step <= 16; step++) {
            double cameraX = 400 + step * 0.25;
            int offset = cameraOffset(cameraX);
            int[] gl = renderGl(cameraX);
            int[] java = FrameError.pixels(upscale(renderJava2D("subpixel-" + step, cameraX)));
            if (previousGl != null) {
                int shift = (int) ((offset - previousOffset) * SCALE);
                assertEquals(differingShifted(previousJava, java, shift),
                        differingShifted(previousGl, gl, shift),
                        "GL disturbed a different amount of the frame than Java2D between "
                                + "camera positions ending at " + cameraX
                                + " — that is what a backend-specific shimmer looks like "
                                + "from here");
            }
            previousGl = gl;
            previousJava = java;
            previousOffset = offset;
        }
    }

    /**
     * And the two still describe the same picture, held to the parity bar the
     * rest of this module is held to rather than to exact equality — see the
     * test above for why exact equality is the wrong claim here.
     */
    @Test
    void glStillAgreesWithJava2DWithinTheParityBar() {
        double worst = 0;
        for (int step = 0; step <= 16; step++) {
            double cameraX = 400 + step * 0.25;
            int[] gl = renderGl(cameraX);
            int[] java = FrameError.pixels(upscale(renderJava2D("bar-" + step, cameraX)));
            worst = Math.max(worst, FrameError.meanChannelError(gl, java));
        }
        assertTrue(worst < 3.58, "GL and Java2D have diverged at fractional camera "
                + "positions beyond the bar every other parity number in this module is "
                + "held to: mean channel error " + worst);
    }

    // --- the scene -------------------------------------------------------------

    /** The camera's single contribution to a horizontal screen position. */
    private static int cameraOffset(double cameraX) {
        return (int) Math.round(W / 2.0 - cameraX * ZOOM);
    }

    private static Camera camera(double cameraX) {
        Camera cam = new Camera(Perspective.SIDE_SCROLL, W, H);
        cam.tileSize = TILE;
        cam.zoom = ZOOM;
        cam.centerOn(cameraX, 200);
        return cam;
    }

    /**
     * A wall of tiles placed through the real projection — each one's corners
     * from {@code Camera.worldToScreen}, and its drawn size the distance
     * between them, exactly as {@code TerrainPainter} does it.
     */
    private static void paint(DrawTarget t, double cameraX) {
        Camera cam = camera(cameraX);
        BufferedImage tile = tile();
        int[] a = new int[2], b = new int[2];
        for (int row = 4; row < 9; row++) {
            for (int column = 10; column < 20; column++) {
                cam.worldToScreen(column * (double) TILE, row * (double) TILE, a);
                cam.worldToScreen((column + 1) * (double) TILE, (row + 1) * (double) TILE, b);
                t.drawImage(tile, a[0], a[1], b[0] - a[0], b[1] - a[1]);
            }
        }
    }

    /** A pixel-art tile with internal detail, so a shift of one pixel shows. */
    private static BufferedImage tile() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                boolean edge = x == 0 || y == 0 || x == 15 || y == 15;
                boolean speck = ((x * 7 + y * 13) % 5) == 0;
                image.setRGB(x, y, edge ? 0xFF3A2A18 : (speck ? 0xFF6E5A34 : 0xFF87703F));
            }
        }
        return image;
    }

    // --- rendering both ways ---------------------------------------------------

    private int[] renderGl(double cameraX) {
        surface.resize((int) (W * SCALE), (int) (H * SCALE));
        surface.bind();
        target.beginFrame(W, H);
        target.clear(background);
        paint(target, cameraX);
        target.endFrame();
        return surface.readPixels();
    }

    private static BufferedImage renderJava2D(String name, double cameraX) {
        return GoldenFrames.render(new GoldenFrames.Frame(name, W, H, t -> paint(t, cameraX)));
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

    /**
     * Pixels where {@code b} disagrees with {@code a} shifted right by
     * {@code dx}, ignoring a margin — content scrolls in at the edges, and that
     * is the camera working rather than the picture breaking.
     */
    private static int differingShifted(int[] a, int[] b, int dx) {
        int width = (int) (W * SCALE), height = (int) (H * SCALE);
        int margin = 8;
        int n = 0;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                int ax = x - dx;
                if (ax < 0 || ax >= width) continue;
                if ((a[y * width + ax] & 0xFFFFFF) != (b[y * width + x] & 0xFFFFFF)) n++;
            }
        }
        return n;
    }
}
