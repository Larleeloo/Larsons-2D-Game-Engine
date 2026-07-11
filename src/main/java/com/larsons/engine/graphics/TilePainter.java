package com.larsons.engine.graphics;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Draws a tile's sprite-sheet texture into its projected quad. Orthographic
 * perspectives (side-scroll, top-down) are an axis-aligned blit; isometric
 * tiles are a sheared parallelogram, so the texture is warped through an
 * {@link AffineTransform} built from the quad's edge vectors — the same PNG a
 * creator assigns "translates" into every perspective instead of silently
 * falling back to the procedural colour in isometric view.
 */
public final class TilePainter {

    private TilePainter() {}

    /**
     * Draw {@code img} into the projected tile quad. {@code xs}/{@code ys}
     * hold the four projected corners in order: top-left, top-right,
     * bottom-right, bottom-left (the order both scenes project them in).
     */
    public static void drawTexture(Graphics2D g, BufferedImage img, int[] xs, int[] ys,
                                   boolean flat) {
        if (img == null) return;
        if (flat) {
            int x = Math.min(xs[0], xs[2]);
            int y = Math.min(ys[0], ys[2]);
            g.drawImage(img, x, y, Math.abs(xs[2] - xs[0]) + 1,
                    Math.abs(ys[2] - ys[0]) + 1, null);
            return;
        }
        // Isometric: map the image's unit square onto the diamond spanned by
        // the top edge (u) and left edge (v) of the projected quad.
        double ux = (xs[1] - xs[0]) / (double) img.getWidth();
        double uy = (ys[1] - ys[0]) / (double) img.getWidth();
        double vx = (xs[3] - xs[0]) / (double) img.getHeight();
        double vy = (ys[3] - ys[0]) / (double) img.getHeight();
        AffineTransform tx = new AffineTransform(ux, uy, vx, vy, xs[0], ys[0]);
        g.drawImage(img, tx, null);
    }
}
