package com.larsons.engine.ui;

import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, customizable menu (requirement #6: menu customization).
 *
 * <p>A menu is a title (+ optional subtitle) over a vertical list of
 * {@link MenuItem}s, navigable by keyboard (the {@code MENU_UP} /
 * {@code MENU_DOWN} / {@code MENU_SELECT} key binds, up/down + enter/space out
 * of the box) and mouse (hover to select, click to activate). Appearance comes
 * entirely from a
 * {@link MenuTheme}, and items are added fluently, so building a custom menu is
 * a few chained calls.
 *
 * <p>Menus with more entries than fit on screen <b>scroll</b>: the mouse wheel
 * and a draggable scroll bar down the right edge move the view, keyboard
 * navigation pulls the selection back into view, and off-screen rows are not
 * clickable. A menu that fits shows no bar and is centred as before.
 *
 * <p>Titles and items are shortened with an ellipsis when they are wider than
 * the viewport (see {@link UiText}) — a menu is often titled with a name the
 * creator typed, so no amount of care over the built-in copy can guarantee the
 * text fits.
 */
public class Menu {

    /** Space kept clear either side of the title and subtitle. */
    private static final int SIDE_MARGIN = 24;
    /** Space either side of an item, leaving room for the '>' caret and bar. */
    private static final int ITEM_MARGIN = 48;
    private String title;
    private String subtitle;
    private final List<MenuItem> items = new ArrayList<>();
    private MenuTheme theme = MenuTheme.defaultTheme();
    private int selected = 0;

    // Scrolling state for menus taller than the viewport, mirroring
    // ConfigForm: the layout is captured during render() and hit-tested a frame
    // later in update(). The wheel and scroll-bar drag move the view directly;
    // keyboard navigation pulls the selected row into view (followSelection).
    private int scroll;              // index of the first visible item
    private int visibleCount = 1;    // items that fit; recomputed each render
    private int maxScroll;           // items.size() - visibleCount, clamped >= 0
    private final Rectangle scrollTrack = new Rectangle();
    private final Rectangle scrollThumb = new Rectangle();
    // Where the title and subtitle actually landed, recorded during render.
    private final Rectangle titleBox = new Rectangle();
    private final Rectangle subtitleBox = new Rectangle();
    private boolean draggingThumb;
    private int dragGrabOffset;      // cursor offset inside the thumb at grab time
    private boolean followSelection; // bring the selection into view next render

    public Menu(String title) { this.title = title; }

    public Menu title(String t) { this.title = t; return this; }

    public Menu subtitle(String s) { this.subtitle = s; return this; }

    public Menu theme(MenuTheme t) { this.theme = t; return this; }

    public Menu add(MenuItem item) { items.add(item); return this; }

    public Menu add(String label, Runnable action) {
        items.add(new MenuItem(label, action));
        return this;
    }

    public Menu add(MenuItem.Label label, Runnable action) {
        items.add(new MenuItem(label, action));
        return this;
    }

    public MenuTheme theme() { return theme; }

    public List<MenuItem> items() { return items; }

    public int getSelectedIndex() { return selected; }

    /** Index of the first visible item (top of the scrolled window). */
    public int getScroll() { return scroll; }

    /** Scroll-bar track hit box (computed during render; 0-size when it fits). */
    public Rectangle scrollTrackBounds() { return scrollTrack; }

    /** Scroll-bar thumb hit box (computed during render; 0-size when it fits). */
    public Rectangle scrollThumbBounds() { return scrollThumb; }

    /** Where the title was drawn (computed during render), after any shortening. */
    public Rectangle titleBounds() { return titleBox; }

    /** Where the subtitle was drawn (0-size when the menu has none). */
    public Rectangle subtitleBounds() { return subtitleBox; }

    public void update(double dt, InputManager input) {
        if (items.isEmpty()) return;

        // Navigation runs on the player's own binds (defaults: arrows + W/S,
        // Enter/Space to choose), so a rebind in the controls menu moves every
        // menu in the engine at once.
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) move(1);
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) move(-1);
        if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
            items.get(selected).activate();
            return;
        }

        // Mouse wheel scrolls the view directly, leaving the selection put.
        int wheel = input.getWheelRotation();
        if (wheel != 0) scroll = clampScroll(scroll + wheel);

        int mx = input.getMouseX();
        int my = input.getMouseY();
        boolean click = input.isMouseJustPressed();

        // The scroll bar takes precedence over item interaction while in use.
        if (handleScrollBar(input, mx, my, click)) return;

        // Mouse: hover selects, click activates. Off-screen items have 0-size
        // hit boxes (see render), so only visible rows respond.
        for (int k = 0; k < items.size(); k++) {
            MenuItem it = items.get(k);
            if (it.enabled && it.contains(mx, my)) {
                selected = k;
                if (click) {
                    it.activate();
                    return;
                }
            }
        }
    }

    private void move(int dir) {
        int n = items.size();
        for (int step = 0; step < n; step++) {
            selected = (selected + dir + n) % n;
            if (items.get(selected).enabled) {
                followSelection = true;
                return;
            }
        }
    }

    private int clampScroll(int s) {
        return Math.max(0, Math.min(maxScroll, s));
    }

    /**
     * Start/continue/finish a scroll-bar thumb drag, or jump the view when the
     * track is clicked. Returns true while the pointer is working the bar, so
     * the caller skips item hit-testing this frame. Geometry comes from the
     * previous render (see {@link #drawScrollBar}).
     */
    private boolean handleScrollBar(InputManager input, int mx, int my, boolean click) {
        if (draggingThumb) {
            if (!input.isMouseDown()) {
                draggingThumb = false;
                return true;
            }
            int travel = scrollTrack.height - scrollThumb.height;
            if (travel > 0) {
                int rel = Math.max(0, Math.min(travel, my - dragGrabOffset - scrollTrack.y));
                scroll = Math.round((float) rel / travel * maxScroll);
            }
            return true;
        }
        if (!click || maxScroll <= 0 || scrollTrack.width <= 0) return false;
        if (scrollThumb.contains(mx, my)) {
            draggingThumb = true;
            dragGrabOffset = my - scrollThumb.y;
            return true;
        }
        if (scrollTrack.contains(mx, my)) {
            // Click on the track jumps so the thumb centres on the pointer.
            int travel = scrollTrack.height - scrollThumb.height;
            int rel = Math.max(0, Math.min(travel, my - scrollTrack.y - scrollThumb.height / 2));
            scroll = travel > 0 ? Math.round((float) rel / travel * maxScroll) : 0;
            return true;
        }
        return false;
    }

    public void render(Graphics2D g, int viewportW, int viewportH) {
        if (theme.drawBackground) {
            g.setColor(theme.background);
            g.fillRect(0, 0, viewportW, viewportH);
        }

        // Title + subtitle.
        int textW = viewportW - 2 * SIDE_MARGIN;
        g.setFont(theme.titleFont);
        g.setColor(theme.title);
        drawCentered(g, title, viewportW / 2, viewportH / 4, textW, titleBox);
        g.setFont(theme.subtitleFont);
        g.setColor(theme.item);
        drawCentered(g, subtitle, viewportW / 2, viewportH / 4 + 42, textW, subtitleBox);

        // Items — scrolled when they overflow the region below the subtitle.
        g.setFont(theme.itemFont);
        FontMetrics fm = g.getFontMetrics();
        int spacing = Math.max(fm.getHeight() + 8, theme.itemSpacing);
        int regionTop = viewportH / 4 + 84;               // below the subtitle
        int regionBottom = Math.max(regionTop + spacing, viewportH - 56); // above scene hints
        int regionH = regionBottom - regionTop;

        visibleCount = Math.max(1, regionH / spacing);
        maxScroll = Math.max(0, items.size() - visibleCount);
        if (followSelection) {
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + visibleCount) scroll = selected - visibleCount + 1;
            followSelection = false;
        }
        scroll = clampScroll(scroll);

        // Centre the block vertically when it all fits; top-align when scrolling.
        int rowsShown = Math.min(items.size(), visibleCount);
        int firstY = regionTop + fm.getAscent()
                + (maxScroll == 0 ? Math.max(0, (regionH - rowsShown * spacing) / 2) : 0);

        for (int k = 0; k < items.size(); k++) {
            MenuItem it = items.get(k);
            // Reset the hit box; only visible rows get a real one so off-screen
            // items can't be hovered or clicked.
            it.x = it.y = it.width = it.height = 0;
            if (k < scroll || k >= scroll + visibleCount) continue;

            String label = UiText.fit(fm, it.text(), viewportW - 2 * ITEM_MARGIN);
            int tw = fm.stringWidth(label);
            int x = viewportW / 2 - tw / 2;
            int y = firstY + (k - scroll) * spacing;

            it.x = x - 16;
            it.y = y - fm.getAscent() - 6;
            it.width = tw + 32;
            it.height = fm.getHeight() + 12;

            if (!it.enabled) {
                g.setColor(theme.itemDisabled);
            } else if (k == selected) {
                g.setColor(theme.itemSelected);
                g.drawString(">", x - 28, y);
            } else {
                g.setColor(theme.item);
            }
            g.drawString(label, x, y);
        }

        drawScrollBar(g, viewportW, regionTop, regionBottom);
    }

    /**
     * Draw a scroll bar down the right margin when the menu overflows, sizing
     * the thumb to the visible fraction and positioning it by {@link #scroll}.
     * Records the track/thumb boxes for {@link #handleScrollBar} next frame.
     */
    private void drawScrollBar(Graphics2D g, int viewportW, int regionTop, int regionBottom) {
        scrollTrack.setBounds(0, 0, 0, 0);
        scrollThumb.setBounds(0, 0, 0, 0);
        if (maxScroll <= 0) return; // everything fits; no bar needed

        int barW = 8;
        int barX = viewportW - 22;
        int barTop = regionTop;
        int barH = regionBottom - regionTop;
        scrollTrack.setBounds(barX, barTop, barW, barH);

        int rows = items.size();
        int thumbH = Math.min(barH, Math.max(28, Math.round((float) visibleCount / rows * barH)));
        int travel = barH - thumbH;
        int thumbY = barTop + Math.round((float) scroll / maxScroll * travel);
        scrollThumb.setBounds(barX, thumbY, barW, thumbH);

        g.setColor(new Color(255, 255, 255, 28));
        g.fillRoundRect(barX, barTop, barW, barH, barW, barW);
        g.setColor(draggingThumb ? theme.accent : theme.item);
        g.fillRoundRect(barX, thumbY, barW, thumbH, barW, barW);
    }

    /**
     * Draw {@code s} centred on {@code cx}, shortened to {@code maxW} if it is
     * wider than that, and record where it landed in {@code box}.
     */
    private void drawCentered(Graphics2D g, String s, int cx, int cy, int maxW, Rectangle box) {
        box.setBounds(0, 0, 0, 0);
        if (s == null) return;
        FontMetrics fm = g.getFontMetrics();
        String shown = UiText.fit(fm, s, maxW);
        int tw = fm.stringWidth(shown);
        box.setBounds(cx - tw / 2, cy - fm.getAscent(), tw, fm.getHeight());
        g.drawString(shown, cx - tw / 2, cy);
    }
}
