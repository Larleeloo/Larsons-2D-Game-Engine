package com.larsons.engine.graphics;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches images.
 *
 * <p>Lookup order: classpath first (so assets bundled inside the jar work out
 * of the box), then the working directory. A missing image resolves to a
 * generated checkerboard placeholder instead of throwing, so a project stays
 * runnable while its art is still being produced — useful for the "editing
 * outline" the engine is meant to provide (requirement #6).
 */
public final class AssetLoader {
    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private AssetLoader() {}

    public static BufferedImage loadImage(String path) {
        BufferedImage cached = CACHE.get(path);
        if (cached != null) return cached;

        BufferedImage img = readImage(path);
        if (img == null) {
            System.err.println("AssetLoader: missing image '" + path + "' - using placeholder");
            img = placeholder(64, 64);
        }
        CACHE.put(path, img);
        return img;
    }

    private static BufferedImage readImage(String path) {
        // 1) classpath (bundled resources / inside the jar)
        String cp = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = AssetLoader.class.getResourceAsStream(cp)) {
            if (in != null) return ImageIO.read(in);
        } catch (IOException ignored) {
            // fall through to filesystem
        }
        // 2) filesystem (working directory)
        File f = new File(path);
        if (f.exists()) {
            try {
                return ImageIO.read(f);
            } catch (IOException ignored) {
                // fall through to null
            }
        }
        return null;
    }

    /** A magenta/black checkerboard so missing assets are obvious but non-fatal. */
    public static BufferedImage placeholder(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        int cell = Math.max(4, Math.min(w, h) / 4);
        for (int y = 0; y < h; y += cell) {
            for (int x = 0; x < w; x += cell) {
                boolean even = ((x / cell) + (y / cell)) % 2 == 0;
                g.setColor(even ? new Color(255, 0, 220) : new Color(30, 30, 30));
                g.fillRect(x, y, cell, cell);
            }
        }
        g.dispose();
        return img;
    }

    public static void clearCache() { CACHE.clear(); }
}
