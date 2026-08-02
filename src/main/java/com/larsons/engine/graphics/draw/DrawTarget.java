package com.larsons.engine.graphics.draw;

import java.awt.Color;
import java.awt.Font;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Everything the engine can draw, named without reference to how it is drawn.
 *
 * <p><b>Why this exists.</b> Scenes currently draw by calling
 * {@link java.awt.Graphics2D} directly — about nine hundred call sites across
 * forty-three files. Graphics2D is an immediate-mode API bound to Java2D, so
 * every one of those calls is both a drawing instruction and a commitment to
 * the CPU renderer. Profiling says scene drawing is what costs (15.4 ms of a
 * 22 ms frame on an M1 Air), which makes a GPU scene renderer the work worth
 * doing — and it cannot begin while the instructions and the renderer are the
 * same thing.
 *
 * <p>So this is the seam. It names the drawing operations the engine actually
 * performs, and nothing else. {@link Java2DTarget} implements it over
 * Graphics2D, so today's renderer keeps working unchanged; a GL backend
 * implements the same verbs by appending to vertex buffers and flushing on
 * state change. Painters written against this interface do not know or care
 * which they are talking to.
 *
 * <p><b>Deliberately small, and sized by measurement.</b> The verbs here were
 * chosen by counting what the engine really calls rather than by mirroring
 * Graphics2D. A wide surface is a wide GL backend, and every verb added is a
 * verb that has to be batched, tested and kept consistent across backends.
 * Anything expressible as a combination of these is left to the caller.
 *
 * <p>The first cut covered what the <em>world</em> draws. The UI draws
 * different things, so B1 counted those too and added exactly what the count
 * demanded: rounded rectangles (161 fills, 82 outlines — the most-used shape
 * in the engine), arcs (32 sites, all cooldown rings and radial meters),
 * gradients (12 linear, 6 radial), and dashed lines. Nothing was added on the
 * grounds that a drawing API "ought to" have it. Two things the audit
 * explicitly declined to add are recorded on {@link #drawDashedLine} (caps and
 * joins) and in {@link #pushAlpha} (non-{@code SRC_OVER} composites).
 *
 * <p><b>Integer coordinates.</b> The call sites this replaces are integer
 * pixel work, and matching them exactly is what lets the Java2D
 * implementation be a refactor with no visual change at all — the property
 * that makes this migration safe to do a painter at a time. A GL backend
 * widens to float on the way into its vertex buffer, which costs nothing. The
 * one exception is {@link #drawImage(BufferedImage, AffineTransform)}, because
 * an isometric tile is a sheared parallelogram and there is no integer form
 * of that.
 *
 * <p><b>Colours are packed {@code 0xAARRGGBB} ints</b> — the layout of a
 * {@code BufferedImage} raster and of the shader pipeline, and free of
 * allocation on a path that runs thousands of times a frame. {@link Color}
 * overloads exist so migrating a call site stays a one-line mechanical change.
 *
 * <p><b>State is a stack, not a setting.</b> Graphics2D lets a caller set a
 * clip or a composite and leave it set, which works only because every drawing
 * call is immediate. A batching backend reorders work, so state has to be
 * scoped: push it, draw, pop it. Every {@code push} has exactly one
 * {@code pop}, and implementations may assume it.
 */
public interface DrawTarget {

    // --- surface ---------------------------------------------------------------

    /** Width of the drawable area in pixels. */
    int width();

    /** Height of the drawable area in pixels. */
    int height();

    /** Fill the whole surface with one colour. */
    void clear(int argb);

    default void clear(Color color) { clear(color.getRGB()); }

    // --- filled shapes ---------------------------------------------------------

    void fillRect(int x, int y, int w, int h, int argb);

    default void fillRect(int x, int y, int w, int h, Color color) {
        fillRect(x, y, w, h, color.getRGB());
    }

    /**
     * Rounded rectangle, as {@code Graphics2D.fillRoundRect}. {@code arcW} and
     * {@code arcH} are the full width and height of the corner ellipse, not
     * its radius — the same convention Graphics2D uses, so a ported call site
     * keeps its numbers.
     *
     * <p>The most-called shape in the UI by a distance: 161 sites, against 82
     * for its outline and none at all in the world painters. Panels, buttons,
     * slots, tooltips and cards are all this one verb.
     */
    void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH, int argb);

    default void fillRoundRect(int x, int y, int w, int h, int arcW, int arcH, Color color) {
        fillRoundRect(x, y, w, h, arcW, arcH, color.getRGB());
    }

    /** Filled ellipse inscribed in the given box, as {@code Graphics2D.fillOval}. */
    void fillOval(int x, int y, int w, int h, int argb);

    default void fillOval(int x, int y, int w, int h, Color color) {
        fillOval(x, y, w, h, color.getRGB());
    }

    /**
     * Filled pie slice of the ellipse inscribed in the given box, as
     * {@code Graphics2D.fillArc}: {@code startDeg} measured from three
     * o'clock, {@code arcDeg} sweeping counter-clockwise.
     *
     * <p><b>Why an arc is a real verb and not a polygon fan.</b> There are
     * only 32 arc sites and every one of them is a UI element — a cooldown
     * ring, a radial meter. Emulating them with polygons antialiases visibly
     * differently along the curve, which is exactly the "nothing changed
     * except everything looks slightly wrong" that the golden frames exist to
     * catch and that is miserable to chase afterwards. A GL backend
     * tessellates this itself, with a segment count scaled by the on-screen
     * radius, where it has the size to scale by.
     */
    void fillArc(int x, int y, int w, int h, int startDeg, int arcDeg, int argb);

    default void fillArc(int x, int y, int w, int h, int startDeg, int arcDeg, Color color) {
        fillArc(x, y, w, h, startDeg, arcDeg, color.getRGB());
    }

    // --- gradients -------------------------------------------------------------

    /**
     * Fill {@code (x, y, w, h)} with a linear ramp running from
     * {@code (x0, y0)} to {@code (x1, y1)}, in the same coordinate space as
     * the rectangle. Beyond either end the ramp holds its end colour, matching
     * a non-cyclic {@code GradientPaint}.
     *
     * <p>Every site is a full-viewport or full-panel backdrop, which is why
     * this takes a rectangle rather than an arbitrary shape: a gradient over a
     * general path would mean either a stencil pass or a per-pixel shader on
     * the GL backend, to serve zero call sites.
     */
    void fillLinearGradient(int x, int y, int w, int h,
                            int x0, int y0, int argb0,
                            int x1, int y1, int argb1);

    default void fillLinearGradient(int x, int y, int w, int h,
                                    int x0, int y0, Color c0,
                                    int x1, int y1, Color c1) {
        fillLinearGradient(x, y, w, h, x0, y0, c0.getRGB(), x1, y1, c1.getRGB());
    }

    /**
     * Fill the disc of {@code radius} about {@code (cx, cy)} with a radial
     * ramp. {@code fractions} are distances from the centre in [0,1], strictly
     * increasing, and {@code argbStops} is the colour at each — the argument
     * order and semantics of {@code RadialGradientPaint}, one array per
     * concept rather than a paint object, because this is called per item per
     * frame and a paint per call is a paint per frame per item.
     *
     * <p>The two arrays must be the same length. Every current site is a
     * rarity halo: opaque tint at the centre falling to fully transparent at
     * the rim, which is why the disc rather than a bounding box is the filled
     * region — the corners are always transparent anyway.
     */
    void fillRadialGradient(int cx, int cy, int radius, float[] fractions, int[] argbStops);

    /**
     * Filled polygon over the first {@code count} points. The arrays may be
     * longer than {@code count} — callers reuse scratch arrays rather than
     * allocating per shape, which is the whole reason this takes a count.
     */
    void fillPolygon(int[] xs, int[] ys, int count, int argb);

    default void fillPolygon(int[] xs, int[] ys, int count, Color color) {
        fillPolygon(xs, ys, count, color.getRGB());
    }

    /**
     * Fill an arbitrary shape — the general case, and the only verb here that
     * is not a primitive.
     *
     * <p>It exists for one real requirement: a frame's cast shadows are
     * accumulated into a single path and filled once, because filling them
     * separately would stack translucent black wherever two shadows overlap
     * and band the floor. "These regions, with the alpha applied once" is a
     * different operation from "these regions, drawn one after another", and
     * no amount of {@link #fillPolygon} expresses it.
     *
     * <p>A GPU backend tessellates this through
     * {@link java.awt.Shape#getPathIterator(java.awt.geom.AffineTransform, double)},
     * whose flattening argument turns any curve into the triangles it needs.
     * That is more work per call than a quad, so prefer the primitives when
     * they say what you mean; reach for this when the union is the point.
     */
    void fillShape(Shape shape, int argb);

    default void fillShape(Shape shape, Color color) {
        fillShape(shape, color.getRGB());
    }

    // --- outlines --------------------------------------------------------------
    //
    // Each outline verb comes in three forms: packed argb with an explicit
    // thickness, a Color at 1px, and a Color with an explicit thickness. The
    // third was missing until B3 and its absence showed: every ported call
    // site that stated a width — and after this migration that is every call
    // site that used to lean on the ambient stroke — had to write
    // `SOME_COLOUR.getRGB()` inline, which is noise at best and an invitation
    // to drop the alpha at worst.

    void drawRect(int x, int y, int w, int h, int argb, float thickness);

    default void drawRect(int x, int y, int w, int h, Color color) {
        drawRect(x, y, w, h, color.getRGB(), 1f);
    }

    default void drawRect(int x, int y, int w, int h, Color color, float thickness) {
        drawRect(x, y, w, h, color.getRGB(), thickness);
    }

    /** Outline of {@link #fillRoundRect}, with the same corner convention. */
    void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH,
                       int argb, float thickness);

    default void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH, Color color) {
        drawRoundRect(x, y, w, h, arcW, arcH, color.getRGB(), 1f);
    }

    default void drawRoundRect(int x, int y, int w, int h, int arcW, int arcH,
                               Color color, float thickness) {
        drawRoundRect(x, y, w, h, arcW, arcH, color.getRGB(), thickness);
    }

    void drawOval(int x, int y, int w, int h, int argb, float thickness);

    default void drawOval(int x, int y, int w, int h, Color color) {
        drawOval(x, y, w, h, color.getRGB(), 1f);
    }

    default void drawOval(int x, int y, int w, int h, Color color, float thickness) {
        drawOval(x, y, w, h, color.getRGB(), thickness);
    }

    /** Outline of {@link #fillArc} — the curve alone, not the two radii. */
    void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg,
                 int argb, float thickness);

    default void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg, Color color) {
        drawArc(x, y, w, h, startDeg, arcDeg, color.getRGB(), 1f);
    }

    default void drawArc(int x, int y, int w, int h, int startDeg, int arcDeg,
                         Color color, float thickness) {
        drawArc(x, y, w, h, startDeg, arcDeg, color.getRGB(), thickness);
    }

    void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness);

    default void drawPolygon(int[] xs, int[] ys, int count, Color color) {
        drawPolygon(xs, ys, count, color.getRGB(), 1f);
    }

    default void drawPolygon(int[] xs, int[] ys, int count, Color color, float thickness) {
        drawPolygon(xs, ys, count, color.getRGB(), thickness);
    }

    void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness);

    default void drawLine(int x1, int y1, int x2, int y2, Color color) {
        drawLine(x1, y1, x2, y2, color.getRGB(), 1f);
    }

    default void drawLine(int x1, int y1, int x2, int y2, Color color, float thickness) {
        drawLine(x1, y1, x2, y2, color.getRGB(), thickness);
    }

    /**
     * A line broken into {@code dash}-long marks separated by {@code gap}-long
     * spaces, with round caps and joins.
     *
     * <p><b>Why this exists and caps/joins do not.</b> The stroke audit for
     * this step went through all 135 {@code setStroke} sites. Every one is
     * either a plain width — already covered by the {@code thickness} argument
     * the outline verbs take — or a dash pattern, and every dash site also
     * asks for round caps, because a dash with butt caps reads as a different
     * decoration entirely. So caps and joins get no member of their own: there
     * is no site that varies them independently, and a knob nothing turns is a
     * knob both backends have to implement and test for nobody.
     *
     * <p>Rounded ends are therefore part of what this verb <em>is</em>, not a
     * default that might change. A GL backend emits one quad per mark with a
     * semicircle at each end; nothing has to know about {@code BasicStroke}.
     */
    void drawDashedLine(int x1, int y1, int x2, int y2, int argb,
                        float thickness, float dash, float gap);

    default void drawDashedLine(int x1, int y1, int x2, int y2, Color color,
                                float thickness, float dash, float gap) {
        drawDashedLine(x1, y1, x2, y2, color.getRGB(), thickness, dash, gap);
    }

    // --- images ----------------------------------------------------------------

    /** Draw {@code image} at its natural size with its top-left at {@code (x, y)}. */
    void drawImage(BufferedImage image, int x, int y);

    /**
     * Draw {@code image} scaled into the given box.
     *
     * <p><b>A negative width or height mirrors it</b> about that axis, with
     * {@code (x, y)} still the corner the box is measured from — the idiom the
     * engine already uses to face a sprite the other way from one sheet. Free
     * on both backends: Java2D accepts it directly, and a GPU backend swaps the
     * quad's texture coordinates.
     */
    void drawImage(BufferedImage image, int x, int y, int w, int h);

    /**
     * Draw a sub-rectangle of {@code image} into the given box — one frame out
     * of a sprite sheet, without slicing a sub-image per draw.
     */
    void drawImage(BufferedImage image, int dx, int dy, int dw, int dh,
                   int sx, int sy, int sw, int sh);

    /**
     * Draw {@code image} through an arbitrary transform: the isometric case,
     * where a square tile texture is warped onto a diamond. A GL backend gets
     * this for free by transforming the quad's four corners.
     */
    void drawImage(BufferedImage image, AffineTransform transform);

    // --- text ------------------------------------------------------------------

    /**
     * Draw {@code text} with its baseline starting at {@code (x, y)} — the
     * same anchor as {@code Graphics2D.drawString}.
     *
     * <p>This is the verb a GL backend cannot implement cheaply: there are no
     * fonts on a GPU, only textures, so it means baking a glyph atlas per
     * {@link Font} at load time and emitting one quad per character.
     */
    void drawText(String text, int x, int y, Font font, int argb);

    default void drawText(String text, int x, int y, Font font, Color color) {
        drawText(text, x, y, font, color.getRGB());
    }

    /** Width of {@code text} in pixels — layout needs this before drawing. */
    int textWidth(String text, Font font);

    /** Distance from the top of a line of {@code font} to its baseline. */
    int textAscent(Font font);

    /** Full line height for {@code font}, ascent plus descent plus leading. */
    int textHeight(Font font);

    // --- scoped state ----------------------------------------------------------

    /**
     * Restrict drawing to the intersection of the current clip and this
     * rectangle, until the matching {@link #popClip()}.
     */
    void pushClip(int x, int y, int w, int h);

    void popClip();

    /**
     * Multiply everything drawn until the matching {@link #popAlpha()} by
     * {@code alpha} in [0,1].
     *
     * <p><b>This is the whole of the engine's compositing.</b> B1's composite
     * audit walked all 28 {@code setComposite} sites: 24 are
     * {@code AlphaComposite.SRC_OVER} with an alpha, which is exactly this,
     * and the only two that are not — {@code AlphaComposite.Clear} in
     * {@code TerrainCache} and {@code EntitySprites} — are punching
     * transparent holes in an image being <em>baked</em> with
     * {@code createGraphics()}, not drawn to a frame. Baking is Java2D by
     * definition and stays that way, so no destination-modifying blend mode
     * has to cross this interface. That matters more than it sounds: a GL
     * backend implements this as a multiply into the vertex colour, which
     * costs nothing and does not break the batch, whereas {@code CLEAR} or
     * {@code DST_OUT} would mean a blend-state change and a flush per push.
     */
    void pushAlpha(float alpha);

    void popAlpha();

    /**
     * Apply {@code transform} to everything drawn until the matching
     * {@link #popTransform()}, on top of any transform already pushed.
     */
    void pushTransform(AffineTransform transform);

    void popTransform();

    // --- instrumentation -------------------------------------------------------

    /**
     * How many drawing operations this target has been given since the counter
     * was last reset. This is the number that predicts what a batching backend
     * would buy: Java2D pays per call, a GL backend pays per <em>batch</em>, so
     * the ratio between calls and distinct states is the win available.
     */
    DrawStats stats();
}
