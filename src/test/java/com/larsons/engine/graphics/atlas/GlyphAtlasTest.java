package com.larsons.engine.graphics.atlas;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.render.FrameError;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether text drawn out of the atlas is the same text.
 *
 * <p>B0's goldens already answer that for the fifteen painters and sixteen
 * scenes they cover, and they answer it at exact equality. What they cannot do
 * is say <em>why</em> it holds, or notice it stopping to hold for a character
 * none of them happens to draw. These tests take the claim apart: that the
 * placement rule reconstructs {@code drawString} exactly, that it reconstructs
 * it for every printable ASCII character in every font the UI uses, that it
 * survives colour and translucency and a scaled destination, and that the
 * checks which decide when <em>not</em> to use the atlas actually fire.
 */
class GlyphAtlasTest {

    /** The fonts the engine's UI actually draws with, plus two awkward ones. */
    private static final List<Font> FONTS = List.of(
            new Font("SansSerif", Font.PLAIN, 14),
            new Font("SansSerif", Font.BOLD, 15),
            new Font("SansSerif", Font.PLAIN, 9),
            new Font("Monospaced", Font.PLAIN, 12),
            new Font("Serif", Font.ITALIC, 18),
            new Font("SansSerif", Font.BOLD, 28));

    private static final int W = 900;
    private static final int H = 80;
    private static final int BASELINE_X = 12;
    private static final int BASELINE_Y = 52;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // --- parity ----------------------------------------------------------------

    /**
     * The claim the whole step rests on, at the bar B0 set: not "close enough"
     * but the same picture, for the whole printable ASCII range.
     */
    @Test
    void everyPrintableCharacterRendersExactlyAsDrawStringDoes() {
        StringBuilder ascii = new StringBuilder();
        for (char c = 32; c < 127; c++) ascii.append(c);

        List<String> wrong = new ArrayList<>();
        for (Font font : FONTS) {
            double error = compare(font, ascii.toString(), 0xFFFFFFFF, 1);
            if (error != 0) wrong.add("%s: %.4f".formatted(font.getFontName(), error));
        }
        assertEquals(List.of(), wrong,
                "the atlas path is only worth having if it is not an approximation");
    }

    /** Colour is baked into the cell, so it has to be right for every colour. */
    @Test
    void colourAndTranslucencySurviveTheRoundTrip() {
        Font font = FONTS.get(0);
        for (int argb : List.of(0xFFFFFFFF, 0xFF3399CC, 0x80FFFFFF, 0x40204060, 0x00FFFFFF)) {
            assertEquals(0.0, compare(font, "Inventory 12/48", argb, 1), 0.0,
                    "text in colour 0x%08X came out different".formatted(argb));
        }
    }

    /**
     * Kerning-prone pairs and glyphs whose ink overhangs their advance — the
     * cases a cell sized from the advance box rather than the ink box would
     * clip, and the ones a naive integer layout would space wrongly.
     */
    @Test
    void overhangingAndTightlyKernedTextIsUnchanged() {
        for (Font font : FONTS) {
            for (String text : List.of("AVAWaToTy", "fi ffl jgy", "|||___///", "@#%&")) {
                assertEquals(0.0, compare(font, text, 0xFFEEDDCC, 1), 0.0,
                        font.getFontName() + " drew '" + text + "' differently");
            }
        }
    }

    /**
     * A 2× destination, which is a HiDPI panel reached through
     * {@code -Dlarsons.render.direct}. The cells are rasterised at 2× and
     * placed at device positions, so this is the check that the text is sharp
     * rather than a stretched 1× cell — a stretch would show up here as a
     * large error, not a small one.
     */
    @Test
    void aScaledDestinationIsExactToo() {
        for (Font font : FONTS) {
            assertEquals(0.0, compare(font, "AWjgy fi// | Hello", 0xFFEEDDCC, 2), 0.0,
                    font.getFontName() + " is not exact on a 2x destination");
        }
    }

    // --- the guards ------------------------------------------------------------

    /**
     * The negative control. A test that only ever sees zeroes cannot tell "the
     * atlas is exact" from "the comparison is broken", so this moves one glyph
     * by one pixel and insists the measurement notices.
     */
    @Test
    void theComparisonCanActuallyFail() {
        Font font = FONTS.get(0);
        BufferedImage direct = surface(1);
        Graphics2D g = graphics(direct, 1);
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString("Hello", BASELINE_X, BASELINE_Y);
        g.dispose();

        BufferedImage shifted = surface(1);
        Graphics2D g2 = graphics(shifted, 1);
        g2.setFont(font);
        g2.setColor(Color.WHITE);
        g2.drawString("Hello", BASELINE_X + 1, BASELINE_Y);
        g2.dispose();

        assertTrue(FrameError.meanChannelError(direct, shifted) > 0,
                "a one-pixel shift has to register, or every zero above means nothing");
    }

    /**
     * Text the atlas refuses still draws — through {@code drawString}, keyed by
     * its font, exactly as it did before this class existed. A run that cannot
     * be served must be a fallback, never a blank.
     */
    @Test
    void unshapeableTextFallsBackRatherThanVanishing() {
        Font font = FONTS.get(0);
        String arabic = "مرحبا";
        assertNull(GlyphAtlas.shared().pageFor(arabic, font, 0xFFFFFFFF, CONTEXT),
                "a right-to-left run needs shaping and must not be served glyph by glyph");

        BufferedImage image = surface(1);
        Graphics2D g = graphics(image, 1);
        Java2DTarget target = new Java2DTarget(g, W, H);
        target.drawText(arabic, BASELINE_X, BASELINE_Y, font, 0xFFFFFFFF);
        g.dispose();

        assertEquals(1, target.stats().glyphs(), "the run still has to be drawn");
        assertTrue(inked(image), "and it has to leave pixels behind");
    }

    /** A blank-only run is a successful lookup that draws nothing, not a failure. */
    @Test
    void aRunOfSpacesDrawsNothingAndBreaksNothing() {
        Font font = FONTS.get(0);
        assertNull(GlyphAtlas.shared().pageFor("   ", font, 0xFFFFFFFF, CONTEXT));
        assertEquals(0.0, compare(font, "a   b", 0xFFFFFFFF, 1), 0.0,
                "spaces inside a run still have to advance the pen");
    }

    /**
     * The two switches do what they say and nothing else.
     *
     * <p>{@code routing} decides whether a run is packed and keyed by its page;
     * {@code blitting} decides whether this backend draws from that page. Every
     * one of the four combinations has to produce the same pixels — the flags
     * exist to move where a draw comes from and what the instrument reports,
     * never what the player sees.
     */
    @Test
    void neitherSwitchChangesThePicture() {
        Font font = FONTS.get(0);
        boolean wasRouting = GlyphAtlas.routing();
        boolean wasBlitting = GlyphAtlas.blitting();
        try {
            BufferedImage reference = null;
            for (boolean routing : List.of(false, true)) {
                for (boolean blitting : List.of(false, true)) {
                    GlyphAtlas.setRouting(routing);
                    GlyphAtlas.setBlitting(blitting);

                    BufferedImage image = surface(1);
                    Graphics2D g = graphics(image, 1);
                    Java2DTarget target = new Java2DTarget(g, W, H);
                    target.drawText("Inventory 12/48", BASELINE_X, BASELINE_Y, font, 0xFFDDEEFF);
                    g.dispose();

                    // Routing off means no page, so the key is the font again.
                    assertEquals(1, target.stats().glyphs());
                    if (reference == null) reference = image;
                    else assertEquals(0.0, FrameError.meanChannelError(reference, image), 0.0,
                            "routing=" + routing + " blitting=" + blitting + " drew it differently");
                }
            }
        } finally {
            GlyphAtlas.setRouting(wasRouting);
            GlyphAtlas.setBlitting(wasBlitting);
        }
    }

    /**
     * Packing and blitting are separate decisions, and the batch key follows
     * the packing.
     *
     * <p>This is the arrangement the step ended up with and the one most likely
     * to be undone by a later reader who assumes the two must go together: the
     * shipping backend draws text with {@code drawString} because that is
     * faster, while still reporting the bind a batching backend would get. If
     * the key ever starts following the blitting instead, B10's table quietly
     * loses every text run in the engine.
     */
    @Test
    void theBatchKeyFollowsThePageEvenWhenJava2DDrawsTheTextItself() {
        Font font = FONTS.get(0);
        boolean wasBlitting = GlyphAtlas.blitting();
        try {
            GlyphAtlas.setBlitting(false);
            BufferedImage image = surface(1);
            Graphics2D g = graphics(image, 1);
            Java2DTarget target = new Java2DTarget(g, W, H);
            BufferedImage icon = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
            SpriteAtlas.Region region = SpriteAtlas.shared().register("glyph-test-icon", icon);
            assertNotNull(region, "the icon has to be packed for this to mean anything");

            target.drawRegion(region, 4, 4);
            target.drawText("Label", BASELINE_X, BASELINE_Y, font, 0xFFFFFFFF);
            target.drawRegion(region, 40, 4);
            g.dispose();

            assertEquals(3, target.stats().operations());
            assertEquals(1, target.stats().batches(),
                    "an icon, a label and another icon off one page is one bind, "
                            + "which is the whole of B6: " + target.stats());
        } finally {
            GlyphAtlas.setBlitting(wasBlitting);
        }
    }

    // --- packing ---------------------------------------------------------------

    /**
     * The design decision B5's correction forced: glyphs go on the sprite
     * atlas's pages, not a parallel atlas. Two atlases would leave an
     * icon-then-label row flushing between every pair, which is the whole thing
     * this step exists to fix.
     */
    @Test
    void glyphsArePackedIntoTheSpriteAtlasPages() {
        SpriteAtlas atlas = new SpriteAtlas();
        GlyphAtlas glyphs = new GlyphAtlas(atlas);
        BufferedImage sprite = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        SpriteAtlas.Region packed = atlas.register("a-sprite", sprite);
        assertNotNull(packed);

        BufferedImage page = glyphs.pageFor("Hello", FONTS.get(0), 0xFFFFFFFF, CONTEXT);
        assertNotNull(page, "the run should be servable");
        assertSame(packed.image(), page,
                "a glyph and a sprite have to land on the same page or the "
                        + "interleaved frames gain nothing");
    }

    /** A cell is rasterised once per style and reused, or this saves nothing. */
    @Test
    void aGlyphIsRasterisedOnceAndReused() {
        SpriteAtlas atlas = new SpriteAtlas();
        GlyphAtlas glyphs = new GlyphAtlas(atlas);
        Font font = FONTS.get(0);

        glyphs.pageFor("aaaa bbbb aaaa", font, 0xFFFFFFFF, CONTEXT);
        int afterFirst = glyphs.size();
        glyphs.pageFor("aaaa bbbb aaaa", font, 0xFFFFFFFF, CONTEXT);
        assertEquals(afterFirst, glyphs.size(), "the second ask must be a cache hit");
        assertEquals(2, afterFirst, "'a', 'b' and a space that rasterises to nothing");

        glyphs.pageFor("ab", font, 0xFF00FF00, CONTEXT);
        assertEquals(4, glyphs.size(),
                "a second colour is a second cell — Java2D cannot tint a shared mask");
        assertEquals(2, glyphs.styleCount());
    }

    /**
     * Every glyph is fenced by transparent pixels on all four sides.
     *
     * <p>The same bug B5 padded sprites against, and for the same reason: a
     * quad drawn at a non-integer scale samples half a texel past its edge, and
     * without a gutter that half texel is the letter next door. Java2D clamps
     * to the source rectangle and would never show it, so no golden frame can
     * catch this and the assertion has to be on the page itself. Glyphs are the
     * worse case, because they pack tightly and there are hundreds of them.
     */
    @Test
    void everyGlyphKeepsItsGutter() {
        SpriteAtlas atlas = new SpriteAtlas();
        GlyphAtlas atlasOfGlyphs = new GlyphAtlas(atlas);
        List<String> bled = new ArrayList<>();
        int checked = 0;
        for (Font font : FONTS) {
            GlyphAtlas.Glyphs glyphs = atlasOfGlyphs.glyphs(font, 0xFFFFFFFF, CONTEXT);
            for (char c = 33; c < 127; c++) {
                GlyphAtlas.Cell cell = glyphs.cell(c);
                if (cell == null || cell.blank()) continue;
                checked++;
                if (!fenced(cell.region())) {
                    bled.add(font.getFontName() + " '" + c + "'");
                }
            }
        }
        assertTrue(checked > 500, "only " + checked + " glyphs packed; nothing was measured");
        assertEquals(List.of(), bled, "these glyphs touch their neighbours on the page");
    }

    /** Whether the one-pixel border around {@code region} is fully transparent. */
    private static boolean fenced(SpriteAtlas.Region region) {
        BufferedImage page = region.image();
        for (int x = region.x() - 1; x <= region.x() + region.width(); x++) {
            if (opaqueAt(page, x, region.y() - 1)) return false;
            if (opaqueAt(page, x, region.y() + region.height())) return false;
        }
        for (int y = region.y() - 1; y <= region.y() + region.height(); y++) {
            if (opaqueAt(page, region.x() - 1, y)) return false;
            if (opaqueAt(page, region.x() + region.width(), y)) return false;
        }
        return true;
    }

    private static boolean opaqueAt(BufferedImage page, int x, int y) {
        if (x < 0 || y < 0 || x >= page.getWidth() || y >= page.getHeight()) return false;
        return (page.getRGB(x, y) >>> 24) != 0;
    }

    // --- helpers ---------------------------------------------------------------

    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, false);

    /**
     * Draw one run both ways onto the same background and subtract.
     *
     * <p>Both sides go through {@link Java2DTarget} so the comparison is
     * between the two paths inside it rather than between the engine and a
     * hand-written reference — a reference that drifted would prove nothing.
     */
    private static double compare(Font font, String text, int argb, int scale) {
        BufferedImage direct = draw(font, text, argb, scale, false);
        BufferedImage viaAtlas = draw(font, text, argb, scale, true);
        return FrameError.meanChannelError(direct, viaAtlas);
    }

    private static BufferedImage draw(Font font, String text, int argb,
                                      int scale, boolean fromPages) {
        boolean was = GlyphAtlas.blitting();
        try {
            GlyphAtlas.setBlitting(fromPages);
            BufferedImage image = surface(scale);
            Graphics2D g = graphics(image, scale);
            Java2DTarget target = new Java2DTarget(g, W, H);
            target.fillRect(0, 0, W, H, 0xFF203040);
            target.drawText(text, BASELINE_X, BASELINE_Y, font, argb);
            g.dispose();
            return image;
        } finally {
            GlyphAtlas.setBlitting(was);
        }
    }

    private static BufferedImage surface(int scale) {
        return new BufferedImage(W * scale, H * scale, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D graphics(BufferedImage image, int scale) {
        Graphics2D g = image.createGraphics();
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g;
    }

    private static boolean inked(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }
}
