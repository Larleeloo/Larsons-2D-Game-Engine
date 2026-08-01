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
 * <p><b>Deliberately small.</b> Fourteen verbs, chosen by counting what the
 * engine really calls rather than by mirroring Graphics2D. A wide surface is a
 * wide GL backend, and every verb added here is a verb that has to be batched,
 * tested and kept consistent across backends. Anything expressible as a
 * combination of these is left to the caller.
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

    /** Filled ellipse inscribed in the given box, as {@code Graphics2D.fillOval}. */
    void fillOval(int x, int y, int w, int h, int argb);

    default void fillOval(int x, int y, int w, int h, Color color) {
        fillOval(x, y, w, h, color.getRGB());
    }

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

    void drawRect(int x, int y, int w, int h, int argb, float thickness);

    default void drawRect(int x, int y, int w, int h, Color color) {
        drawRect(x, y, w, h, color.getRGB(), 1f);
    }

    void drawOval(int x, int y, int w, int h, int argb, float thickness);

    default void drawOval(int x, int y, int w, int h, Color color) {
        drawOval(x, y, w, h, color.getRGB(), 1f);
    }

    void drawPolygon(int[] xs, int[] ys, int count, int argb, float thickness);

    default void drawPolygon(int[] xs, int[] ys, int count, Color color) {
        drawPolygon(xs, ys, count, color.getRGB(), 1f);
    }

    void drawLine(int x1, int y1, int x2, int y2, int argb, float thickness);

    default void drawLine(int x1, int y1, int x2, int y2, Color color) {
        drawLine(x1, y1, x2, y2, color.getRGB(), 1f);
    }

    // --- images ----------------------------------------------------------------

    /** Draw {@code image} at its natural size with its top-left at {@code (x, y)}. */
    void drawImage(BufferedImage image, int x, int y);

    /** Draw {@code image} scaled into the given box. */
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
