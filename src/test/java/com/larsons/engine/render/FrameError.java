package com.larsons.engine.render;

import java.awt.image.BufferedImage;

/**
 * The codebase's one definition of "the same picture": mean absolute error
 * across every colour channel of every pixel, out of 255.
 *
 * <p><b>Why one definition.</b> {@code ShaderParityTest} needed this first, to
 * decide whether a GLSL pass and its CPU twin agree. The golden frames
 * ({@link GoldenFrames}) need exactly the same question answered about a
 * painter before and after a port, and the GL backend will need it a third
 * time in B8. Three metrics would mean three different bars for "unchanged",
 * and an argument every time one of them disagreed — so there is one, it lives
 * here, and all three call it.
 *
 * <p><b>Why not equality.</b> For a pure refactor the answer is 0.00 and
 * equality would do. For a rasteriser swap it is not: the CPU passes work in
 * 8-bit integers with lookup tables while the GPU works in floats and rounds
 * once at the end, so a channel landing a step either side of a boundary is
 * expected and meaningless. A mean catches a wrong formula, a swapped channel
 * or a missing uniform — the failures that matter — while ignoring the last
 * bit. The precedent is recorded: {@code bloom} at 3.58/255 was accepted as
 * the same picture.
 *
 * <p>Alpha is deliberately not compared. Frames are composed onto an opaque
 * surface before they reach a human eye, so an alpha difference that survives
 * composition already shows up in the colour channels, and one that does not
 * is not visible.
 */
public final class FrameError {

    private FrameError() {}

    /** Mean absolute difference per colour channel between two ARGB rasters. */
    public static double meanChannelError(int[] a, int[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "different sizes: " + a.length + " vs " + b.length + " pixels");
        }
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            total += Math.abs(((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF));
            total += Math.abs(((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF));
            total += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return total / (double) (a.length * 3);
    }

    /**
     * As above for two images. Images of different sizes are not "very
     * different pictures" — they are a broken comparison, and reporting a
     * number for them would hide that, so this throws.
     */
    public static double meanChannelError(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            throw new IllegalArgumentException("different sizes: %dx%d vs %dx%d"
                    .formatted(a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight()));
        }
        return meanChannelError(pixels(a), pixels(b));
    }

    /**
     * An image's pixels as packed {@code 0xAARRGGBB}.
     *
     * <p>Deliberately {@code getRGB} rather than reaching into the raster's
     * {@code DataBufferInt}: grabbing the backing array un-accelerates that
     * image permanently (invariant 6), and a comparison has no business
     * changing the thing it is comparing.
     */
    public static int[] pixels(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        return image.getRGB(0, 0, w, h, null, 0, w);
    }

    /**
     * Where the two images differ most, as {@code "(x, y) expected #rrggbb got
     * #rrggbb"} — the first thing anyone wants after a failed comparison, and
     * far more use than the mean on its own.
     */
    public static String worstPixel(BufferedImage a, BufferedImage b) {
        int[] pa = pixels(a), pb = pixels(b);
        int worst = -1, worstAt = -1;
        for (int i = 0; i < pa.length; i++) {
            int d = Math.abs(((pa[i] >> 16) & 0xFF) - ((pb[i] >> 16) & 0xFF))
                    + Math.abs(((pa[i] >> 8) & 0xFF) - ((pb[i] >> 8) & 0xFF))
                    + Math.abs((pa[i] & 0xFF) - (pb[i] & 0xFF));
            if (d > worst) {
                worst = d;
                worstAt = i;
            }
        }
        if (worst <= 0) return "no pixel differs";
        int w = a.getWidth();
        return "worst at (%d, %d): expected #%06X, got #%06X".formatted(
                worstAt % w, worstAt / w, pa[worstAt] & 0xFFFFFF, pb[worstAt] & 0xFFFFFF);
    }
}
