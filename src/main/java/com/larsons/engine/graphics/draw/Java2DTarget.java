package com.larsons.engine.graphics.draw;

import com.larsons.engine.graphics.atlas.SpriteAtlas;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
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
 * <p><b>The migration seam is closed.</b> This class used to publish a static
 * {@code graphicsOf(DrawTarget)} that any painter could call to unwrap the
 * frame's target back into a Graphics2D, and counting those calls was how the
 * port was tracked. B3 took the count to zero and B4 deleted the method, so
 * there is no longer a supported way to reach Java2D from code that was handed
 * a {@link DrawTarget}. {@link #graphics()} survives because it is
 * <em>Java2D-local</em>: you need a concrete {@code Java2DTarget} in hand to
 * call it, which backend-neutral code by definition does not have, and
 * {@code SealedSeamTest} fails the build if a scene, widget or effect names
 * this class at all.
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
     * The Graphics2D this target draws through — for Java2D-side code that has
     * one of these already, not a way back out of {@link DrawTarget}.
     *
     * <p>The distinction is the whole of B4. A caller reaches this only by
     * holding a {@code Java2DTarget}, so it is unreachable from anything
     * written against the interface, and no amount of it makes a scene
     * un-portable. What made {@code graphicsOf} dangerous was that it was
     * static and took any {@code DrawTarget}: one line at the top of a render
     * method and the whole body below it belonged to Java2D again.
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
    public void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillRoundRect(x, y, w, h, arcW, arcH);
    }

    @Override
    public void fillOval(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillOval(x, y, w, h);
    }

    @Override
    public void fillArc(int x, int y, int w, int h, int startDeg, int arcDeg, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillArc(x, y, w, h, startDeg, arcDeg);
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fillPolygon(xs, ys, count);
    }

    @Override
    public void fillShape(Shape shape, int argb) {
        if (shape == null) return;
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        g.fill(shape);
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
    public void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH,
                              int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawRoundRect(x, y, w, h, arcW, arcH);
    }

    @Override
    public void drawOval(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawOval(x, y, w, h);
    }

    @Override
    public void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg,
                        int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        applyStroke(thickness);
        g.drawArc(x, y, w, h, startDeg, arcDeg);
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

    @Override
    public void drawDashedLine(int x1, int y1, int x2, int y2, int argb,
                               float thickness, float dash, float gap) {
        stats.record(DrawStats.Kind.SHAPE, null);
        g.setColor(new Color(argb, true));
        // Not routed through applyStroke: that caches on width alone, and a
        // dashed stroke and a plain one of the same width are different
        // strokes. Caching them together would draw a dashed line solid, or
        // the next solid line dashed, depending on which came first — and
        // both look like a painter bug rather than a cache bug.
        g.setStroke(new BasicStroke(Math.max(0.01f, thickness),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f,
                new float[]{Math.max(0.01f, dash), Math.max(0.01f, gap)}, 0f));
        g.drawLine(x1, y1, x2, y2);
        // The cached plain stroke is no longer what Graphics2D holds.
        stroke = null;
        strokeWidth = -1;
    }

    // --- gradients -------------------------------------------------------------

    @Override
    public void fillLinearGradient(int x, int y, int w, int h,
                                   int x0, int y0, int argb0,
                                   int x1, int y1, int argb1) {
        stats.record(DrawStats.Kind.GRADIENT, null);
        Paint previous = g.getPaint();
        g.setPaint(new GradientPaint(x0, y0, new Color(argb0, true),
                x1, y1, new Color(argb1, true)));
        g.fillRect(x, y, w, h);
        g.setPaint(previous);
    }

    @Override
    public void fillRadialGradient(int cx, int cy, int radius,
                                   float[] fractions, int[] argbStops) {
        if (radius < 1 || fractions.length == 0
                || fractions.length != argbStops.length) return;
        stats.record(DrawStats.Kind.GRADIENT, null);
        Color[] colors = new Color[argbStops.length];
        for (int i = 0; i < argbStops.length; i++) colors[i] = new Color(argbStops[i], true);
        Paint previous = g.getPaint();
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx, cy), radius, fractions, colors));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setPaint(previous);
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

    /**
     * A sprite draw out of an atlas page: the source-rectangle
     * {@code drawImage} this class already had, keyed for batching on the
     * <em>page</em> rather than the sprite.
     *
     * <p>Java2D itself gains nothing here — it blits per call either way. What
     * changes is what {@link DrawStats} reports, and it is not flattery: a
     * batching backend really would keep one texture bound across the whole run
     * of regions, so the run really is one draw call there. Recording it any
     * other way would understate the only number B10 has to decide on.
     */
    @Override
    public void drawRegion(SpriteAtlas.Region region, int x, int y, int w, int h) {
        if (region == null) return;
        BufferedImage page = region.image();
        stats.record(DrawStats.Kind.IMAGE, page);
        g.drawImage(page, x, y, x + w, y + h,
                region.x(), region.y(),
                region.x() + region.width(), region.y() + region.height(), null);
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y) {
        if (image == null) return;
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null) {
            drawRegion(region, x, y, region.width(), region.height());
            return;
        }
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, x, y, null);
    }

    @Override
    public void drawImage(BufferedImage image, int x, int y, int w, int h) {
        if (image == null) return;
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null) {
            drawRegion(region, x, y, w, h);
            return;
        }
        stats.record(DrawStats.Kind.IMAGE, image);
        g.drawImage(image, x, y, w, h, null);
    }

    @Override
    public void drawImage(BufferedImage image, int dx, int dy, int dw, int dh,
                          int sx, int sy, int sw, int sh) {
        if (image == null) return;
        // A sub-rectangle of an atlased sprite is still a sub-rectangle of the
        // page, just shifted — but only while it stays inside the sprite. A
        // request that reaches past the sprite's own bounds would pull in the
        // gutter or the neighbour, so it draws from the loose image instead,
        // which is where those pixels honestly are.
        SpriteAtlas.Region region = SpriteAtlas.regionOf(image);
        if (region != null && sx >= 0 && sy >= 0
                && sx + sw <= region.width() && sy + sh <= region.height()) {
            BufferedImage page = region.image();
            int px = region.x() + sx;
            int py = region.y() + sy;
            stats.record(DrawStats.Kind.IMAGE, page);
            g.drawImage(page, dx, dy, dx + dw, dy + dh, px, py, px + sw, py + sh, null);
            return;
        }
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
    public void pushClip(Shape shape) {
        stats.record(DrawStats.Kind.STATE, null);
        if (clips == null) clips = new ArrayDeque<>();
        clips.push(g.getClip() == null ? NO_CLIP : g.getClip());
        if (shape != null) g.clip(shape);
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
