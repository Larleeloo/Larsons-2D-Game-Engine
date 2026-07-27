package com.larsons.engine.demo;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.ui.UiText;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Text layout across the engine's screens: no label may be drawn over the
 * control beside it, nothing may run outside the column or the window, and the
 * copy the engine actually ships has to fit without being shortened.
 *
 * <p>The widgets shorten anything too long as a backstop, so these tests come
 * in two kinds: the ones that hand a widget deliberately absurd content and
 * check it still lands inside its box, and the ones that walk the real screens
 * and fail if the backstop had to do anything — because a label the player sees
 * cut off is a label that wants rewriting, not truncating.
 */
@Timeout(60)
class TextLayoutTest {

    private static final int W = 1280, H = 720;

    /** A path of the length a creator really does paste into a sheet field. */
    private static final String LONG_PATH =
            "/home/creator/games/my-platformer/art/characters/hero_walk_sheet_final_v2.png";

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // --- helpers ------------------------------------------------------------------

    /** Lay the form out in a viewport tall enough that no row scrolls away. */
    private static Graphics2D layout(ConfigForm form, int viewportW) {
        BufferedImage img = new BufferedImage(viewportW, 4000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        form.render(g, viewportW, 4000);
        return g;
    }

    private static int contentW(int viewportW) {
        return Math.min(640, viewportW - 80);
    }

    private static int contentX(int viewportW) {
        return (viewportW - contentW(viewportW)) / 2;
    }

    /**
     * Every row of {@code form}: the label stays inside the content column and
     * clear of the control it labels, and the control stays in the column too.
     */
    private static void assertRowsAreClean(ConfigForm form, int viewportW) {
        int left = contentX(viewportW);
        int right = left + contentW(viewportW);
        for (ConfigForm.Option o : form.options()) {
            Rectangle label = o.labelBounds();
            if (label.width == 0) continue; // scrolled off, or an empty label
            String what = o.control() + " row \"" + o.label() + "\"";
            assertTrue(label.x >= left, what + " starts left of the column");
            assertTrue(label.x + label.width <= right, what + " runs past the column");

            for (Rectangle box : new Rectangle[]{o.mainBounds(), o.decBounds(), o.incBounds()}) {
                if (box.width == 0) continue;
                assertTrue(box.x >= left && box.x + box.width <= right,
                        what + " has a control outside the column");
                if (o.control() == ConfigForm.Control.ACTION) {
                    // An action's label is the text inside its own button.
                    assertTrue(box.contains(label), what + " label escapes its button");
                } else {
                    assertFalse(label.intersects(box), what + " label is drawn over its control");
                }
            }
        }
    }

    /** Rows whose label had to be shortened to fit — these want rewriting. */
    private static String shortenedLabels(ConfigForm form, Graphics2D g) {
        g.setFont(form.theme().itemFont);
        FontMetrics fm = g.getFontMetrics();
        StringBuilder bad = new StringBuilder();
        for (ConfigForm.Option o : form.options()) {
            if (o.control() == ConfigForm.Control.NOTE) continue; // notes wrap
            Rectangle label = o.labelBounds();
            if (label.width == 0) continue;
            int full = fm.stringWidth(o.label());
            if (full > label.width) {
                bad.append("\n  cut ").append(full - label.width).append("px: ").append(o.label());
            }
        }
        return bad.toString();
    }

    // --- the widgets hold the line on content they cannot control -----------------

    @Test
    void aWordyLabelIsNeverDrawnOverTheControlBesideIt() {
        String wordy = "A deliberately long-winded setting label that explains itself at length";
        ConfigForm form = new ConfigForm("Adversarial").theme(MenuTheme.dark());
        form.addToggle(wordy, () -> true, v -> { });
        form.addText(wordy, () -> LONG_PATH, v -> { }, 96);
        form.addInt(wordy, () -> 12345, v -> { }, 0, 99999, 1);
        form.addEnum(wordy, new String[]{"a value long enough to crowd the label out"},
                () -> "a value long enough to crowd the label out", v -> { });
        form.addSlider(wordy, () -> 50, v -> { }, 0, 100);
        form.addAction(wordy, () -> { });

        Graphics2D g = layout(form, W);
        assertRowsAreClean(form, W);
        g.dispose();
    }

    @Test
    void rowsStayInsideTheColumnOnASmallWindow() {
        ConfigForm form = new ConfigForm("Adversarial").theme(MenuTheme.dark());
        form.addText("Sheet elsewhere (PNG)", () -> LONG_PATH, v -> { }, 96);
        form.addAction("Import sheet image…  (opens a file browser)", () -> { });

        Graphics2D g = layout(form, 700); // narrower than the 640 + margins design size
        assertRowsAreClean(form, 700);
        g.dispose();
    }

    /**
     * The value of a text field is content — a pasted path is far wider than
     * the field — so nothing may be painted to the right of the field's edge.
     */
    @Test
    void aLongValueStaysInsideItsTextField() {
        ConfigForm form = new ConfigForm("Texture").theme(MenuTheme.dark());
        // The first row takes the selection, so the row under test carries no
        // highlight wash and every lit pixel in its band is drawn text.
        form.addAction("Browse…", () -> { });
        form.addText("Sheet elsewhere (PNG)", () -> LONG_PATH, v -> { }, 96);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        form.render(g, W, H);
        g.dispose();

        Rectangle row = form.options().get(1).rowBounds();
        int fieldRight = contentX(W) + contentW(W);
        for (int y = row.y; y < row.y + row.height; y++) {
            for (int x = fieldRight + 3; x < W; x++) {
                assertEquals(0xFF000000, img.getRGB(x, y),
                        "the field's value spilled past its right edge at " + x + "," + y);
            }
        }
    }

    @Test
    void aLongMenuTitleAndItemsStayInsideTheWindow() {
        String typed = "A Very Long Game Type Name That Somebody Actually Typed In";
        Menu menu = new Menu("Delete \"" + typed + "\"?")
                .subtitle("This permanently removes the game type — it cannot be undone")
                .theme(MenuTheme.dark())
                .add("Cancel — keep this game type", () -> { })
                .add(typed + "  ·  Side-Scroller", () -> { });

        for (int viewportW : new int[]{W, 800}) {
            BufferedImage img = new BufferedImage(viewportW, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            menu.render(g, viewportW, H);
            g.dispose();

            assertInside(menu.titleBounds(), viewportW, "title");
            assertInside(menu.subtitleBounds(), viewportW, "subtitle");
            for (var item : menu.items()) {
                assertInside(new Rectangle(item.x, item.y, item.width, item.height),
                        viewportW, "item \"" + item.text() + "\"");
            }
        }
    }

    private static void assertInside(Rectangle box, int viewportW, String what) {
        if (box.width == 0) return;
        assertTrue(box.x >= 0, what + " starts off the left edge (" + viewportW + "px wide)");
        assertTrue(box.x + box.width <= viewportW,
                what + " runs off the right edge (" + viewportW + "px wide)");
    }

    @Test
    void captionRowsAreSkippedByTheSelection() {
        ConfigForm form = new ConfigForm("Notes").theme(MenuTheme.dark());
        form.addToggle("First", () -> true, v -> { });
        form.addNote("Explains the row above; there is nothing here to activate.");
        form.addToggle("Second", () -> true, v -> { });

        Graphics2D g = layout(form, W);
        InputManager input = new InputManager();
        Component src = new Canvas();
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        g.dispose();

        assertEquals(2, form.getSelectedIndex(), "Down should step over the caption to \"Second\"");
        assertFalse(form.options().get(1).isSelectable(), "a caption is not selectable");
    }

    // --- the copy the engine actually ships ---------------------------------------

    @Test
    void theGameTypeFeatureFormNeedsNoShortening() {
        GameProfile p = new GameProfile("Platformer");
        ConfigForm form = new ConfigForm("Create / Edit Game Type").theme(MenuTheme.dark());
        form.addText("Game type name", () -> p.name, v -> p.name = v, 40);
        ProfileForms.addFeatureOptions(form, p);

        Graphics2D g = layout(form, W);
        assertRowsAreClean(form, W);
        String bad = shortenedLabels(form, g);
        g.dispose();
        assertTrue(bad.isEmpty(), "feature labels do not fit their controls:" + bad);
    }

    /**
     * The creative palette's hover tooltips. They wrap, so the check is on how
     * tall they get: a description that needs more than a handful of lines is
     * one nobody reads off a 52-pixel icon.
     */
    @Test
    void everyPaletteDescriptionStaysShortEnoughToRead() {
        // CreativeScene draws them wrapped at 320px in its 11pt small font.
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int wrapW = 320, maxLines = 4;

        StringBuilder bad = new StringBuilder();
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // hidden from the palette
            check(bad, fm, wrapW, maxLines, "block " + b.key(), PaletteInfo.describe(b));
        }
        for (MobDef d : MobRegistry.standard().all()) {
            check(bad, fm, wrapW, maxLines, "mob " + d.key(), PaletteInfo.describe(d));
        }
        for (ItemDef d : ItemRegistry.standard().all()) {
            check(bad, fm, wrapW, maxLines, "item " + d.key(), PaletteInfo.describe(d));
        }
        for (Decor d : DecorRegistry.standard().all()) {
            check(bad, fm, wrapW, maxLines, "decor " + d.key(), PaletteInfo.describe(d));
        }
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            check(bad, fm, wrapW, maxLines, "surface " + d.key(), PaletteInfo.describe(d));
        }
        g.dispose();
        assertTrue(bad.isEmpty(), "palette tooltips are too wordy:" + bad);
    }

    private static void check(StringBuilder bad, FontMetrics fm, int wrapW, int maxLines,
                              String what, String text) {
        int lines = UiText.wrap(fm, text, wrapW).size();
        if (lines > maxLines) {
            bad.append("\n  ").append(what).append(" wraps to ").append(lines)
                    .append(" lines: ").append(text);
        }
    }
}
