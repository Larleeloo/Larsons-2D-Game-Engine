package com.larsons.engine.graphics.draw;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * {@link DrawTarget} over {@link Graphics2D} — the backend the engine ships.
 *
 * <p>Every verb maps to the Graphics2D call the engine was already making,
 * with the same arguments in the same order, so a painter ported to this
 * interface draws exactly the pixels it drew before. That is the point: the
 * migration is a refactor, one painter at a time, with nothing to look at
 * afterwards. A GL backend can then replace this class rather than the
 * hundreds of call sites behind it.
 *
 * <p><b>{@link #graphics()} is the migration seam.</b> Painters that have not
 * been ported yet still need the raw Graphics2D, and a half-ported frame has
 * to interleave the two correctly. Reaching for it is not a failure — it is
 * how a large migration proceeds without a flag day — but every use is a call
 * site the GPU backend will not be able to serve, so the count of them is the
 * work remaining.
 *
 * <p><b>State stacks.</b> Clip, alpha and transform are pushed and popped
 * rather than set, because a batching backend cannot honour "set and leave
 * set". Here they are implemented by remembering the previous value and
 * restoring it, which is what Graphics2D wants anyway — and it means an
 * unbalanced push shows up as a visibly wrong frame rather than as silent
 * corruption two painters later.
 */
public final class Java2DTarget implements DrawTarget {

    private final Graphics2D g;
    private final int width;
    private final int height;
    private final DrawStats stats = new DrawStats();

    // Allocated on first use. Most painters never push state at all, and a
    // target is cheap enough to wrap around a Graphics2D that already exists —
    // three eagerly-built deques would make that wrapping cost more than the
    // drawing.
    private Deque<Shape> clips;
    private Deque<Composite> composites;
    private Deque<AffineTransform> transforms;

    /** Reused so setting a stroke width does not allocate per outline. */
    private float strokeWidth = -1;
    private Stroke stroke;

    public Java2DTarget(Graphics2D g, int width, int height) {
        this.g = g;
        this.width = width;
        this.height = height;
    }

    /**
     * Wrap a {@link Graphics2D} whose surface size is not known here — for
     * painters that draw only within bounds they were handed and never ask
     * {@link #width()}, {@link #height()} or {@link #clear}. Those three
     * report zero and do nothing rather than guessing at a size.
     */
    public static Java2DTarget unsized(Graphics2D g) {
        return new Java2DTarget(g, 0, 0);
    }

    /**
     * The underlying Graphics2D, for painters not yet ported. See the class
     * note: legitimate during the migration, and the thing that has to reach
     * zero before a GPU backend can draw a whole frame.
     */
    public Graphics2D graphics() { return g; }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override public DrawStats stats() { return stats; }

    // --- filled shapes ---------------------------------------------------------

    @Override
    public void clear(int argb) {
        if (width <= 0 || height <= 0) return;   // unsized: nothing to clear
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillRect(0, 0, width, height);
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, w, h);
    }

    @Override
    public void fillOval(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillOval(x, y, w, h);
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillPolygon(xs, ys, count);
    }

    // --- outlines --------------------------------------------------------------

    @Override
    public void drawRect(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawRect(x, y, w, h);
    }

    @Override
    public void drawOval(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawOval(x, y, w, h);
    }

    @Override
    public void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawPolygon(xs, ys, count);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawLine(x1, y1, x2, y2);
    }

    /** Only build a {@link BasicStroke} when the width actually changed. */
    private void applyStroke(float thickness) {
        float w = Math.max(0.01f, thickness);
        if (stroke == null || w != strokeWidth) {
            strokeWidth = w;
            stroke = new BasicStroke(w);
        }
        g.setStroke(stroke);
    }

    // --- images ----------------------------------------------------------------

    @Override
    public void drawImage(BufferedImage image, int x, int y) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, x, y, null);
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y, int w, int h) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, x, y, w, h, null);
    }

    @Override
    public void drawImage(BufferedImage image, int dx, int dy, int dw, int dh,
                          int sx, int sy, int sw, int sh) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, dx, dy, dx + dw, dy + dh, sx, sy, sx + sw, sy + sh, null);
    }

    @Override
    public void drawImage(BufferedImage image, AffineTransform transform) {
        if (image == null) return;
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, transform, null);
    }

    // --- text ------------------------------------------------------------------

    @Override
    public void drawText(String text, int x, int y, Font font, int argb) {
        if (text == null || text.isEmpty()) return;
        stats.record(DrawStats.Kind.TEXT, font);
        g.setFont(font);
        g.setColor(new Color(argb, true));
        g.drawString(text, x, y);
    }

    @Override
    public int textWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        return metrics(font).stringWidth(text);
    }

    @Override
    public int textAscent(Font font) {
        return metrics(font).getAscent();
    }

    @Override
    public int textHeight(Font font) {
        FontMetrics fm = metrics(font);
        return fm.getAscent() + fm.getDescent() + fm.getLeading();
    }

    /** Measurement must not count as drawing, so it does not touch stats. */
    private FontMetrics metrics(Font font) {
        return font == null ? g.getFontMetrics() : g.getFontMetrics(font);
    }

    // --- scoped state ----------------------------------------------------------

    @Override
    public void pushClip(int x, int y, int w, int h) {
        stats.record(DrawStats.Kind.STATE, null);
        if (clips == null) clips = new ArrayDeque<>();
        clips.push(g.getClip() == null ? NO_CLIP : g.getClip());
        g.clipRect(x, y, w, h);
    }

    @Override
    public void popClip() {
        if (clips == null || clips.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        Shape previous = clips.pop();
        g.setClip(previous == NO_CLIP ? null : previous);
    }

    @Override
    public void pushAlpha(float alpha) {
        stats.record(DrawStats.Kind.STATE, null);
        if (composites == null) composites = new ArrayDeque<>();
        composites.push(g.getComposite());
        float a = Math.max(0f, Math.min(1f, alpha));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
    }

    @Override
    public void popAlpha() {
        if (composites == null || composites.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        g.setComposite(composites.pop());
    }

    @Override
    public void pushTransform(AffineTransform transform) {
        stats.record(DrawStats.Kind.STATE, null);
        if (transforms == null) transforms = new ArrayDeque<>();
        transforms.push(g.getTransform());
        if (transform != null) g.transform(transform);
    }

    @Override
    public void popTransform() {
        if (transforms == null || transforms.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        g.setTransform(transforms.pop());
    }

    /**
     * Stand-in for "no clip", because {@link ArrayDeque} rejects nulls and a
     * null clip (the whole surface) is the common starting state.
     */
    private static final Shape NO_CLIP = new Rectangle(Integer.MIN_VALUE / 2,
            Integer.MIN_VALUE / 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
}
