package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Block textures pre-shaded for the face they are drawn on — what lets the
 * solid views draw a textured cube that still reads as a cube.
 *
 * <p><b>Why the shade is baked rather than blended.</b> {@link SolidPainter}
 * shades every face by which way it points: the top brightest, north/south
 * next, east/west darker, the underside darkest. With a flat colour that is one
 * multiply. With a texture it would be a second pass per face — a translucent
 * black quad over each blit — which doubles the draw calls on the hottest loop
 * in the engine and, worse, cannot brighten anything, so a top face would have
 * to be the unshaded one and every other face a wash over it.
 *
 * <p>There are only four shades and a level has a handful of block textures, so
 * the whole product is a few dozen small images. Baking them once and blitting
 * the right one costs nothing per frame and keeps a textured face exactly one
 * draw call, which is the number the plan view pays too.
 *
 * <p>The cache is keyed on the source image's identity and the shade, and
 * bounded: a texture pack rescan hands out new images and the old entries fall
 * off the end rather than being invalidated by hand.
 */
public final class SolidTextures {

    /** How many shaded copies to keep — a level's blocks times four faces, with room. */
    private static final int MAX_ENTRIES = 256;

    /** Shades are bucketed to this many steps, so tiny differences share a bake. */
    private static final int SHADE_STEPS = 32;

    private static final Map<Long, BufferedImage> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private SolidTextures() {}

    /**
     * {@code source} with every pixel multiplied by {@code shade}, keeping
     * alpha — the image to blit onto a face with that much light on it.
     *
     * @return the shaded copy, or {@code source} itself when the shade is
     *         effectively 1
     */
    public static synchronized BufferedImage shaded(BufferedImage source, double shade) {
        if (source == null) return null;
        int step = (int) Math.round(Math.max(0, Math.min(1, shade)) * SHADE_STEPS);
        if (step >= SHADE_STEPS) return source;
        long key = ((long) System.identityHashCode(source) << 8) | step;
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;

        double factor = step / (double) SHADE_STEPS;
        int w = source.getWidth(), h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int a = argb >>> 24;
                int r = (int) (((argb >> 16) & 0xFF) * factor);
                int g = (int) (((argb >> 8) & 0xFF) * factor);
                int b = (int) ((argb & 0xFF) * factor);
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        CACHE.put(key, out);
        return out;
    }

    /** Drop every bake — what a texture-pack rescan does to the pools it feeds. */
    public static synchronized void clearCache() {
        CACHE.clear();
    }
}
