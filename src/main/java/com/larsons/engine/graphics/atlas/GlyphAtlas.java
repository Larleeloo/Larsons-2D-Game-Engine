package com.larsons.engine.graphics.atlas;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glyphs rasterised once and packed into {@link SpriteAtlas}'s pages, so a run
 * of text is quads out of the same texture the sprites came from.
 *
 * <p><b>Why this shares B5's pages instead of building its own.</b> B5 measured
 * the thing that decides it: {@code crafting-panel} is icon, name, count, icon,
 * name, count, and it stayed at 1.09× with every sprite in the engine fully
 * atlased, because an atlas merges <em>neighbours</em> and every icon's
 * neighbours are text. A second, separate glyph atlas leaves that frame exactly
 * where it was — the flush simply moves from "different image" to "different
 * texture", which costs the same. One shared page is the only arrangement in
 * which the interleaving stops mattering, and {@link SpriteAtlas#register}
 * already takes any {@link BufferedImage}, so the packing side of it is free.
 *
 * <p><b>Cells carry colour, and that is not a compromise.</b> A GL backend
 * would store one coverage mask per glyph and multiply the colour in at the
 * vertex; Java2D has no such multiply, so a shared mask would need a tinted
 * copy per draw and the step would cost more than it saved. Keying the cell on
 * {@code (font, colour, rendering context, char)} instead means the cell that
 * lands on the page is exactly the pixels the destination wants, for both
 * backends — GL just never asks for the second colour. The engine draws text in
 * a handful of colours per font, so the multiplier on page space is small and
 * measured rather than assumed (see {@code GlyphBatchingTest}).
 *
 * <p><b>Cells are device pixels.</b> The rasterisation follows the destination's
 * own {@link FontRenderContext}, which carries its scale, its antialiasing hint
 * and its fractional-metrics hint. On a 2× panel that means the cell holds 2×
 * pixels and is blitted into a 1× logical box, so text stays crisp instead of
 * being rasterised at 1× and stretched — the failure the plan warned about. It
 * also means a cell is only ever reused by a destination that renders text the
 * same way, because the context is part of the key.
 *
 * <p>The plan named {@code DeviceProfile.displayScale} as the source for that
 * scale. The destination is a better one and is used instead: it is what the
 * text is actually going onto, where {@code displayScale} is a property of the
 * <em>default</em> screen, and the two differ on a second monitor, on a window
 * dragged between panels, and on this engine's own shipping path — which
 * composes into an unscaled offscreen image whatever the panel is. Asking the
 * destination is right in every one of those cases and never wrong in the case
 * {@code displayScale} would have handled. A backend with no
 * {@link java.awt.Graphics2D} to ask — the GL one — supplies the context
 * itself, and {@code displayScale} is what it should build it from.
 *
 * <p><b>What is deliberately not here.</b> Layout. {@code textWidth},
 * {@code textAscent} and {@code textHeight} keep answering from
 * {@link java.awt.FontMetrics}, so changing how a glyph is rasterised cannot
 * move a single UI element by a pixel. This class only answers "where do these
 * pixels live"; the caller decides where they go.
 *
 * <p>Thread-safe. Styles are looked up from a concurrent map and each style
 * rasterises under its own monitor, which is uncontended in practice — one
 * render thread does all of this.
 */
public final class GlyphAtlas {

    /**
     * Characters this class will not serve, by Unicode category.
     *
     * <p>The atlas positions glyphs one at a time from per-character advances.
     * That is exact for scripts where a character is a glyph and glyphs do not
     * interact — which is what the engine's UI draws — and wrong for anything
     * that needs shaping: a combining mark has to be positioned against the
     * base it modifies, and a right-to-left run has to be reordered before it
     * is placed. Refusing those here is not a limitation being hidden; it is
     * the check that keeps the fallback honest, because the caller then draws
     * the run through {@code Graphics2D.drawString}, which does shape it.
     */
    private static boolean shapeable(char c) {
        if (Character.isISOControl(c) || Character.isSurrogate(c)) return false;
        return switch (Character.getType(c)) {
            case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
                 Character.ENCLOSING_MARK, Character.FORMAT, Character.UNASSIGNED,
                 Character.PRIVATE_USE -> false;
            default -> switch (Character.getDirectionality(c)) {
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                     Character.DIRECTIONALITY_ARABIC_NUMBER,
                     Character.DIRECTIONALITY_UNDEFINED -> false;
                default -> true;
            };
        };
    }

    /** Transparent border around each cell, matching {@link SpriteAtlas#PAD}. */
    private static final int PAD = 1;

    // --- the shared atlas ------------------------------------------------------

    private static final GlyphAtlas SHARED = new GlyphAtlas(SpriteAtlas.shared());

    /**
     * Whether {@code drawText} resolves through the atlas.
     *
     * <p>Separate from the sprite atlas's switch on purpose. Two independent
     * flags are what let the draw-call report attribute a change to one step or
     * the other in the same process, over the same frames — the arrangement B5
     * needed and this step inherits — and
     * {@code -Dlarsons.render.glyphs=false} is the one-flag way back to
     * {@code drawString} for a machine whose text comes out wrong, without
     * giving up the sprite atlas too.
     */
    private static volatile boolean routing =
            !"false".equalsIgnoreCase(System.getProperty("larsons.render.glyphs", "true"));

    /**
     * Whether the Java2D backend draws text out of the pages rather than
     * through {@code drawString}. <b>Off by default, on the measurement.</b>
     *
     * <p>Java2D already has a glyph atlas. It is called the glyph cache, it
     * lives inside the font system, and {@code drawString} rasterises a string
     * out of it in one dispatch with one tight coverage loop. Replacing that
     * with one {@code drawImage} per character costs <b>~300 ns per glyph
     * against ~83 ns</b> — measured on this project's own frames, and the same
     * to within noise for every source form tried: page sub-rectangle, cached
     * sub-image, loose cell, premultiplied cell. The overhead is the per-call
     * dispatch, so no arrangement of the pixels can recover it. On the frames
     * that draw the most text the whole-frame cost was 10–18% worse.
     *
     * <p>So the packing is kept and the blitting is not, because they answer
     * different questions. The packing is what a GL backend needs and what
     * {@code DrawStats} reports; the blitting is how this backend would have
     * used it, and this backend has something faster. Turning this on is how
     * {@code GlyphAtlasTest} proves the cells hold the right pixels at the
     * right offsets — a claim that would otherwise go unchecked until B8, on a
     * backend that does not exist yet.
     */
    private static volatile boolean blitting =
            "true".equalsIgnoreCase(System.getProperty("larsons.render.glyphblit", "false"));

    public static GlyphAtlas shared() { return SHARED; }

    /** Whether text draws currently resolve through the atlas. */
    public static boolean routing() { return routing; }

    /** Turn glyph routing on or off. See the field's note on why it exists. */
    public static void setRouting(boolean enabled) { routing = enabled; }

    /** Whether Java2D blits glyphs from the pages. See {@link #blitting}. */
    public static boolean blitting() { return blitting; }

    /** Turn page blitting on or off. See the field's note on why it is off. */
    public static void setBlitting(boolean enabled) { blitting = enabled; }

    // --- a cell ----------------------------------------------------------------

    /**
     * One glyph's pixels and where they sit relative to the pen.
     *
     * <p>{@code offsetX} and {@code offsetY} are device pixels from the pen
     * position — the point a {@code drawString} would have started this glyph
     * at, on the baseline — to the cell's top-left corner. Both are usually
     * negative in {@code y} and slightly negative in {@code x}, because a glyph
     * sits above its baseline and its ink can start left of its advance.
     *
     * <p>A blank cell ({@link #region()} {@code == null}) is a character that
     * rasterises to nothing — a space, most obviously. It is a successful
     * lookup that draws nothing, which is different from an unservable one.
     */
    public record Cell(SpriteAtlas.Region region, int offsetX, int offsetY) {

        static final Cell BLANK = new Cell(null, 0, 0);

        /** Whether this cell has pixels to draw. */
        public boolean blank() { return region == null; }
    }

    /** A character this atlas will not serve; cached so it is decided once. */
    private static final Cell UNSERVABLE = new Cell(null, Integer.MIN_VALUE, 0);

    // --- a style ---------------------------------------------------------------

    /** What makes two glyph cells interchangeable. */
    private record Style(Font font, int argb, FontRenderContext frc) {}

    /**
     * Every cell for one font, colour and rendering context.
     *
     * <p>Held per style rather than per character because that is the unit a
     * text run asks for: a run is one style and many characters, so one map
     * lookup serves the whole run and the per-character lookup is an array
     * index. ASCII gets that array; anything rarer falls to a map, which is
     * the right trade for a UI that is overwhelmingly Latin.
     */
    public final class Glyphs {

        private final Style style;
        private final Cell[] ascii = new Cell[128];
        private Map<Character, Cell> rare;

        private Glyphs(Style style) { this.style = style; }

        /**
         * Where {@code c}'s pixels live, rasterising and packing it on first
         * ask, or {@code null} if this atlas will not serve it — too large to
         * pack, needs shaping, or the pages are full. A {@code null} is the
         * caller's cue to draw the whole run through Java2D, which is what it
         * did before this class existed.
         */
        public synchronized Cell cell(char c) {
            Cell cached = c < 128 ? ascii[c] : (rare == null ? null : rare.get(c));
            if (cached != null) return cached == UNSERVABLE ? null : cached;

            Cell made = rasterise(style, c);
            Cell stored = made == null ? UNSERVABLE : made;
            if (c < 128) {
                ascii[c] = stored;
            } else {
                if (rare == null) rare = new HashMap<>();
                rare.put(c, stored);
            }
            return made;
        }
    }

    // --- state -----------------------------------------------------------------

    private final SpriteAtlas atlas;
    private final Map<Style, Glyphs> styles = new ConcurrentHashMap<>();
    private int packed;
    private int refused;

    /** A private atlas — tests build these; the engine uses {@link #shared()}. */
    public GlyphAtlas(SpriteAtlas atlas) { this.atlas = atlas; }

    /** The cells for one text style, created on first ask. */
    public Glyphs glyphs(Font font, int argb, FontRenderContext frc) {
        return styles.computeIfAbsent(new Style(font, argb, frc), Glyphs::new);
    }

    /**
     * The page every glyph of {@code text} in this style shares, or
     * {@code null} if the run cannot be served from one page.
     *
     * <p>The question a batching backend actually asks: not "can each of these
     * be atlased" but "is this whole run one texture bind". A run split across
     * two pages is two binds and must not be counted as one, which is why this
     * refuses rather than returning the first page it saw.
     */
    public BufferedImage pageFor(String text, Font font, int argb, FontRenderContext frc) {
        if (text == null || text.isEmpty() || font == null) return null;
        Glyphs glyphs = glyphs(font, argb, frc);
        BufferedImage page = null;
        for (int i = 0; i < text.length(); i++) {
            Cell cell = glyphs.cell(text.charAt(i));
            if (cell == null) return null;
            if (cell.blank()) continue;
            BufferedImage image = cell.region().image();
            if (page == null) page = image;
            else if (page != image) return null;
        }
        return page;
    }

    /** How many distinct glyph cells are packed. */
    public int size() { return packed; }

    /** How many were asked for and turned away — unshapeable, too large, no room. */
    public int refusedCount() { return refused; }

    /** How many (font, colour, context) styles have been rasterised. */
    public int styleCount() { return styles.size(); }

    /** Forget every cell. Tests use this after clearing the sprite atlas. */
    public synchronized void clear() {
        styles.clear();
        packed = 0;
        refused = 0;
    }

    @Override
    public String toString() {
        return "%d glyphs over %d style%s%s".formatted(packed, styles.size(),
                styles.size() == 1 ? "" : "s",
                refused > 0 ? ", " + refused + " not packed" : "");
    }

    // --- rasterisation ---------------------------------------------------------

    /**
     * Draw one glyph into its own image at the destination's resolution, pack
     * it, and record where the ink sits relative to the pen.
     *
     * <p>The cell is sized from {@link GlyphVector#getPixelBounds}, which is
     * the exact device-pixel box the glyph's ink occupies — not the advance
     * box, which clips the overhang of an italic {@code f} or a script capital,
     * and not a generous margin, which would waste most of a page on the
     * whitespace above short letters.
     */
    private synchronized Cell rasterise(Style style, char c) {
        if (!shapeable(c)) {
            refused++;
            return null;
        }
        Font font = style.font();
        FontRenderContext frc = style.frc();
        String one = String.valueOf(c);
        GlyphVector gv = font.createGlyphVector(frc, one);
        Rectangle ink = gv.getPixelBounds(frc, 0, 0);
        if (ink.width <= 0 || ink.height <= 0) return Cell.BLANK;
        if (ink.width > SpriteAtlas.MAX_SPRITE || ink.height > SpriteAtlas.MAX_SPRITE) {
            refused++;
            return null;
        }

        int w = ink.width + PAD * 2;
        int h = ink.height + PAD * 2;
        BufferedImage cell = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cell.createGraphics();
        try {
            // Match the destination's text rendering exactly: the same
            // antialiasing and fractional-metrics hints the context was built
            // with, and the same transform, so a 2× destination gets a 2× cell.
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    frc.getAntiAliasingHint());
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    frc.getFractionalMetricsHint());
            AffineTransform tx = frc.getTransform();
            double scale = tx.getScaleX() == 0 ? 1 : tx.getScaleX();
            g.setTransform(tx);
            g.translate((-ink.x + PAD) / scale, (-ink.y + PAD) / scale);
            g.setFont(font);
            g.setColor(new java.awt.Color(style.argb(), true));
            g.drawString(one, 0, 0);
        } finally {
            g.dispose();
        }

        SpriteAtlas.Region region = atlas.register(null, cell);
        if (region == null) {
            refused++;
            return null;
        }
        packed++;
        return new Cell(region, ink.x - PAD, ink.y - PAD);
    }
}
