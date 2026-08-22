package com.larsons.engine.graphics.draw;

import com.larsons.engine.graphics.atlas.GlyphAtlas;
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
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
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

    /** The colour Graphics2D is holding, so setting it again costs nothing. */
    private int paintArgb;
    private Color paint;

    // Glyph-atlas state. The scale is the destination's, tracked rather than
    // asked for per draw; the rest is a one-entry memo and two scratch buffers,
    // so a text run costs no allocation once its style has been seen.
    private double deviceScale;
    private Font glyphsFont;
    private int glyphsArgb;
    private FontRenderContext glyphsContext;
    private GlyphAtlas.Glyphs glyphs;
    private GlyphAtlas.Cell[] cells;
    private int[] advances;

    public Java2DTarget(Graphics2D g, int width, int height) {
        this.g = g;
        this.width = width;
        this.height = height;
        refreshDeviceScale();
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
        paint(argb);
        g.fillRect(0, 0, width, height);
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fillRect(x, y, w, h);
    }

    @Override
    public void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fillRoundRect(x, y, w, h, arcW, arcH);
    }

    @Override
    public void fillOval(int x, int y, int w, int h, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fillOval(x, y, w, h);
    }

    @Override
    public void fillArc(int x, int y, int w, int h, int startDeg, int arcDeg, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fillArc(x, y, w, h, startDeg, arcDeg);
    }

    @Override
    public void fillPolygon(int[] xs, int[] ys, int count, int argb) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fillPolygon(xs, ys, count);
    }

    @Override
    public void fillShape(Shape shape, int argb) {
        if (shape == null) return;
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        g.fill(shape);
    }

    // --- outlines --------------------------------------------------------------

    @Override
    public void drawRect(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawRect(x, y, w, h);
    }

    @Override
    public void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH,
                              int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawRoundRect(x, y, w, h, arcW, arcH);
    }

    @Override
    public void drawOval(int x, int y, int w, int h, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawOval(x, y, w, h);
    }

    @Override
    public void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg,
                        int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawArc(x, y, w, h, startDeg, arcDeg);
    }

    @Override
    public void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawPolygon(xs, ys, count);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
        applyStroke(thickness);
        g.drawLine(x1, y1, x2, y2);
    }

    @Override
    public void drawDashedLine(int x1, int y1, int x2, int y2, int argb,
                               float thickness, float dash, float gap) {
        stats.record(DrawStats.Kind.SHAPE, null);
        paint(argb);
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

    /**
     * Put a packed ARGB on the {@link Graphics2D}, building a {@link Color} for
     * it only when it is not the one already there.
     *
     * <p>The same argument as {@link #applyStroke}, and it matters here for the
     * same reason it did not before: the solid views put several thousand flat
     * quads down a frame and a run of them is nearly always one colour — a
     * face's fill and then its own outline, a coarse box's three faces, a ring
     * of hillside in one block's colour. A {@code Color} per call was an object
     * per shape and a fresh {@code SurfaceData} colour lookup with it.
     */
    private void paint(int argb) {
        if (paint == null || argb != paintArgb) {
            paintArgb = argb;
            paint = new Color(argb, true);
        }
        g.setColor(paint);
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

    /**
     * Draw a run of text, keyed for batching by the atlas page its glyphs are
     * packed into.
     *
     * <p><b>Packed, and — on this backend — still drawn by the font system.</b>
     * B6 set out to replace {@code drawString} with quads from a glyph atlas
     * and measuring it said not to, for a reason worth stating plainly: Java2D
     * already has a glyph atlas. It is the font system's glyph cache, and
     * {@code drawString} rasterises a whole run out of it in one dispatch,
     * where a per-character blit pays the general image pipeline's setup every
     * character — ~300 ns a glyph against ~83 ns, whichever way the cells are
     * arranged. So the packing is kept, because that is what a GL backend
     * needs and what makes an icon and the label beside it one texture bind,
     * and the drawing stays where it is fastest. {@code -Dlarsons.render.glyphblit=true}
     * takes the other path; {@code GlyphAtlas} carries the numbers.
     *
     * <p><b>The key is a claim, and it is a checked one.</b> Recording the page
     * says a batching backend would keep one texture bound across this run and
     * the sprites either side of it. That is the same statement B5's
     * {@code drawRegion} makes and it is true for the same reason — but here
     * the cells are not merely packed, they are proved drawable: with blitting
     * on, {@code GlyphAtlasTest} renders every printable ASCII character in six
     * fonts through both paths and demands exactly 0.00 error, coloured,
     * translucent and on a 2× destination.
     *
     * <p>One operation is recorded, not one per character. A run is one quad
     * per glyph in a GL backend and they all land in the same batch, so
     * counting them individually would inflate {@code operations()} without
     * changing {@code batches()} and quietly improve every merge ratio in the
     * table B3 and B5 published. The number has to keep meaning what it meant.
     */
    @Override
    public void drawText(String text, int x, int y, Font font, int argb) {
        if (text == null || text.isEmpty()) return;
        BufferedImage page = font == null || !GlyphAtlas.routing()
                ? null : resolveRun(text, font, argb);
        stats.record(DrawStats.Kind.TEXT, page != null ? page : font);

        if (page != null && GlyphAtlas.blitting() && deviceScale > 0
                && blitRun(text, x, y, font, page)) {
            return;
        }
        g.setFont(font);
        paint(argb);
        g.drawString(text, x, y);
    }

    /**
     * Pack every glyph of {@code text} and return the page they share, or
     * {@code null} if they do not share one — a character the atlas will not
     * serve, a run split across two pages, or a run with no ink in it at all.
     * Fills {@link #cells} as a side effect, for the blitting path.
     */
    private BufferedImage resolveRun(String text, Font font, int argb) {
        int n = text.length();
        GlyphAtlas.Glyphs glyphs = glyphsFor(font, argb);
        GlyphAtlas.Cell[] run = cellScratch(n);
        BufferedImage page = null;
        for (int i = 0; i < n; i++) {
            GlyphAtlas.Cell cell = glyphs.cell(text.charAt(i));
            if (cell == null) return null;
            run[i] = cell;
            if (cell.blank()) continue;
            BufferedImage image = cell.region().image();
            if (page == null) page = image;
            else if (page != image) return null;   // two pages is two binds
        }
        return page;
    }

    /** Blit the resolved run, or refuse it and leave the caller to draw it. */
    private boolean blitRun(String text, int x, int y, Font font, BufferedImage page) {
        return deviceScale == 1
                ? blitRunUnscaled(text, x, y, font, cells, page)
                : blitScaledRun(text, x, y, font, cells, page);
    }

    /**
     * An untransformed destination — the common case, and the one where a
     * glyph's device position is its user-space position and the advances are
     * the integers {@link FontMetrics} already reports.
     */
    private boolean blitRunUnscaled(String text, int x, int y, Font font,
                                    GlyphAtlas.Cell[] cells, BufferedImage page) {
        int n = text.length();
        FontMetrics fm = metrics(font);
        int[] advances = advanceScratch(n);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            advances[i] = fm.charWidth(text.charAt(i));
            sum += advances[i];
        }
        // The one property the placement rests on. If the glyphs do not add up
        // to the run, this font does not lay out one character at a time here.
        if (sum != fm.stringWidth(text)) return false;

        int pen = x;
        for (int i = 0; i < n; i++) {
            blitCell(cells[i], page, pen, y);
            pen += advances[i];
        }
        return true;
    }

    /**
     * A destination under a whole-number scale — a HiDPI panel reached through
     * {@code -Dlarsons.render.direct}, which is the only way the engine's frame
     * graphics ever carries one.
     *
     * <p>Positions come from a {@link GlyphVector} laid out in the
     * destination's own context and are rounded into device pixels, because
     * that is where {@code drawString} puts them; the blit then happens with
     * the transform temporarily set aside so the integer device rectangle is
     * not re-scaled on its way to the surface. The cells are already device
     * resolution, so the text is as sharp as the panel, which is the whole
     * reason to handle this case rather than fall back and let it blur.
     */
    private boolean blitScaledRun(String text, int x, int y, Font font,
                                  GlyphAtlas.Cell[] cells, BufferedImage page) {
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), text);
        AffineTransform user = g.getTransform();
        int originX = (int) Math.round(user.getTranslateX() + x * deviceScale);
        int originY = (int) Math.round(user.getTranslateY() + y * deviceScale);

        g.setTransform(new AffineTransform());
        try {
            for (int i = 0; i < text.length(); i++) {
                Point2D at = gv.getGlyphPosition(i);
                blitCell(cells[i], page,
                        originX + (int) Math.round(at.getX() * deviceScale),
                        originY + (int) Math.round(at.getY() * deviceScale));
            }
        } finally {
            g.setTransform(user);
        }
        return true;
    }

    /** One glyph, blitted out of its page at the pen position it belongs to. */
    private void blitCell(GlyphAtlas.Cell cell, BufferedImage page, int penX, int penY) {
        if (cell.blank()) return;
        SpriteAtlas.Region region = cell.region();
        int dx = penX + cell.offsetX();
        int dy = penY + cell.offsetY();
        g.drawImage(page, dx, dy, dx + region.width(), dy + region.height(),
                region.x(), region.y(),
                region.x() + region.width(), region.y() + region.height(), null);
    }

    /**
     * The atlas's cells for this style, memoised for the run that follows it.
     *
     * <p>Consecutive draws in one style are the common case — a menu is a
     * column of identical labels — and the memo turns the whole column into
     * one map lookup. The {@link FontRenderContext} is fetched here rather
     * than cached because {@code pushTransform} can change it mid-frame, and
     * a stale context would rasterise at the wrong scale.
     */
    private GlyphAtlas.Glyphs glyphsFor(Font font, int argb) {
        FontRenderContext frc = g.getFontRenderContext();
        if (glyphs != null && argb == glyphsArgb && font.equals(glyphsFont)
                && frc.equals(glyphsContext)) {
            return glyphs;
        }
        glyphsFont = font;
        glyphsArgb = argb;
        glyphsContext = frc;
        glyphs = GlyphAtlas.shared().glyphs(font, argb, frc);
        return glyphs;
    }

    private GlyphAtlas.Cell[] cellScratch(int length) {
        if (cells == null || cells.length < length) {
            cells = new GlyphAtlas.Cell[Math.max(64, length)];
        }
        return cells;
    }

    private int[] advanceScratch(int length) {
        if (advances == null || advances.length < length) {
            advances = new int[Math.max(64, length)];
        }
        return advances;
    }

    /**
     * Whether the destination transform is one glyph cells can be blitted
     * through, and at what scale — or {@code -1} for one they cannot.
     *
     * <p>A cell is a rectangle of pixels placed at an integer device position.
     * That is exact under a whole-number scale with an integer translation and
     * nothing else: a rotation or a shear would have to resample every glyph,
     * a fractional scale would land the run's origin between pixels, and in
     * both cases {@code drawString} is both correct and the only thing that
     * is. Recomputed when the transform stack moves rather than per draw,
     * because text is drawn far more often than it is transformed.
     */
    private void refreshDeviceScale() {
        AffineTransform t = g.getTransform();
        double sx = t.getScaleX();
        deviceScale = t.getShearX() == 0 && t.getShearY() == 0
                && sx > 0 && sx == t.getScaleY() && sx == Math.rint(sx)
                && t.getTranslateX() == Math.rint(t.getTranslateX())
                && t.getTranslateY() == Math.rint(t.getTranslateY())
                ? sx : -1;
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
        refreshDeviceScale();
    }

    @Override
    public void popTransform() {
        if (transforms == null || transforms.isEmpty()) return;
        stats.record(DrawStats.Kind.STATE, null);
        g.setTransform(transforms.pop());
        refreshDeviceScale();
    }

    /**
     * Stand-in for "no clip", because {@link ArrayDeque} rejects nulls and a
     * null clip (the whole surface) is the common starting state.
     */
    private static final Shape NO_CLIP = new Rectangle(Integer.MIN_VALUE / 2,
            Integer.MIN_VALUE / 2, Integer.MAX_VALUE, Integer.MAX_VALUE);
}
