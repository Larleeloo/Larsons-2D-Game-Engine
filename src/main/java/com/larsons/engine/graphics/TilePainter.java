package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;

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
 *
 * <p><b>Ported to {@link DrawTarget}.</b> This was the first painter moved off
 * {@link Graphics2D}, chosen because the warped case is the hardest thing the
 * engine draws and therefore the real test of whether the draw API is wide
 * enough: a backend that can serve this finds the axis-aligned blits easy. The
 * {@link Graphics2D} overload remains so unported callers keep working — it
 * wraps and delegates, so there is still exactly one implementation.
 */
public final class TilePainter {

    private TilePainter() {}

    /**
     * Draw {@code img} into the projected tile quad. {@code xs}/{@code ys}
     * hold the four projected corners in order: top-left, top-right,
     * bottom-right, bottom-left (the order both scenes project them in).
     */
    public static void drawTexture(DrawTarget target, BufferedImage img, int[] xs, int[] ys,
                                   boolean flat) {
        if (img == null) return;
        if (flat) {
            int x = Math.min(xs[0], xs[2]);
            int y = Math.min(ys[0], ys[2]);
            target.drawImage(img, x, y,
                    Math.abs(xs[2] - xs[0]) + 1, Math.abs(ys[2] - ys[0]) + 1);
            return;
        }
        target.drawImage(img, isoTransform(img, xs, ys));
    }

    /**
     * Map the image's unit square onto the diamond spanned by the top edge
     * (u) and left edge (v) of the projected quad.
     *
     * <p>Pulled out of the draw call because it is the entirety of the
     * isometric case and the only part with arithmetic worth testing on its
     * own — and because a backend that draws quads from four corners rather
     * than from a matrix still needs exactly this mapping to place them.
     */
    public static AffineTransform isoTransform(BufferedImage img, int[] xs, int[] ys) {
        double ux = (xs[1] - xs[0]) / (double) img.getWidth();
        double uy = (ys[1] - ys[0]) / (double) img.getWidth();
        double vx = (xs[3] - xs[0]) / (double) img.getHeight();
        double vy = (ys[3] - ys[0]) / (double) img.getHeight();
        return new AffineTransform(ux, uy, vx, vy, xs[0], ys[0]);
    }

    /** Graphics2D form, for callers not yet ported to {@link DrawTarget}. */
    public static void drawTexture(Graphics2D g, BufferedImage img, int[] xs, int[] ys,
                                   boolean flat) {
        if (img == null) return;
        drawTexture(Java2DTarget.unsized(g), img, xs, ys, flat);
    }
}
