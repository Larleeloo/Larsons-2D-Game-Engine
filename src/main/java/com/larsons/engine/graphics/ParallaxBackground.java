package com.larsons.engine.graphics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Multi-layer parallax backdrop, ported from the Side-Scroller engine's
 * {@code ParallaxBackground}/{@code ParallaxLayer}. The original loaded
 * PNG/GIF layer art; this engine ships no assets, so layers are generated
 * procedurally (cloud band, far ridge, mid ridge, near hills) and tinted from
 * the level's background colour — every level gets a matching skyline for
 * free, and games can still swap in image-based layers by drawing before
 * tiles themselves.
 *
 * <p>Layers scroll at the original depth factors (far = slow). Vertical
 * parallax is a gentle fraction of camera Y so tall levels don't scroll the
 * sky off screen.
 */
public final class ParallaxBackground {

    private record Layer(BufferedImage image, double factorX, double factorY, int baseY) {}

    private static final int TILE_W = 1024;

    private final Layer[] layers;
    private final Color background;

    public ParallaxBackground(Color background, long seed) {
        this.background = background;
        Random rng = new Random(seed);
        layers = new Layer[]{
                new Layer(clouds(rng), 0.08, 0.02, 40),
                new Layer(ridge(rng, mix(background, Color.WHITE, 0.18f), 130, 60), 0.15, 0.04, 150),
                new Layer(ridge(rng, mix(background, Color.WHITE, 0.10f), 180, 90), 0.30, 0.08, 220),
                new Layer(ridge(rng, mix(background, Color.BLACK, 0.12f), 150, 120), 0.55, 0.12, 330),
        };
    }

    /**
     * Draw all layers behind the world. {@code camX}/{@code camY} are the
     * camera focus in world pixels; layers slide against it by their depth
     * factor and tile horizontally forever.
     */
    public void render(Graphics2D g, double camX, double camY, int viewportW, int viewportH) {
        for (Layer layer : layers) {
            BufferedImage img = layer.image;
            int w = img.getWidth();
            int y = (int) (viewportH - layer.baseY - img.getHeight() / 2.0
                    - camY * layer.factorY);
            // Keep the skyline on screen even in very tall levels.
            y = Math.max(-img.getHeight() / 2, Math.min(viewportH - img.getHeight() / 2, y));
            int offset = (int) Math.floor(camX * layer.factorX) % w;
            if (offset < 0) offset += w;
            for (int x = -offset; x < viewportW; x += w) {
                g.drawImage(img, x, y, null);
            }
        }
    }

    public Color background() {
        return background;
    }

    /** A tileable mountain/hill ridge silhouette. */
    private static BufferedImage ridge(Random rng, Color color, int height, int roughness) {
        BufferedImage img = new BufferedImage(TILE_W, height + 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int points = 16;
        int[] xs = new int[points + 3];
        int[] ys = new int[points + 3];
        int first = height - rng.nextInt(roughness);
        for (int i = 0; i <= points; i++) {
            xs[i] = i * TILE_W / points;
            // First and last heights match so the strip tiles seamlessly.
            ys[i] = (i == 0 || i == points) ? first : height - rng.nextInt(roughness);
        }
        xs[points + 1] = TILE_W;
        ys[points + 1] = img.getHeight();
        xs[points + 2] = 0;
        ys[points + 2] = img.getHeight();

        g.setColor(color);
        g.fillPolygon(xs, ys, xs.length);
        g.dispose();
        return img;
    }

    /** A sparse band of soft clouds. */
    private static BufferedImage clouds(Random rng) {
        BufferedImage img = new BufferedImage(TILE_W, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 26));
        for (int i = 0; i < 7; i++) {
            int cw = 90 + rng.nextInt(140);
            int ch = 18 + rng.nextInt(22);
            int x = rng.nextInt(TILE_W);
            int y = 10 + rng.nextInt(110);
            g.fillOval(x, y, cw, ch);
            g.fillOval(x + cw / 4, y - ch / 3, cw / 2, ch);
            if (x + cw > TILE_W) { // wrap for seamless tiling
                g.fillOval(x - TILE_W, y, cw, ch);
                g.fillOval(x - TILE_W + cw / 4, y - ch / 3, cw / 2, ch);
            }
        }
        g.dispose();
        return img;
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
