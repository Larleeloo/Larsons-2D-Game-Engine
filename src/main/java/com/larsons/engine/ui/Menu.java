package com.larsons.engine.ui;

import com.larsons.engine.input.InputManager;

import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, customizable menu (requirement #6: menu customization).
 *
 * <p>A menu is a title (+ optional subtitle) over a vertical list of
 * {@link MenuItem}s, navigable by keyboard (up/down + enter/space) and mouse
 * (hover to select, click to activate). Appearance comes entirely from a
 * {@link MenuTheme}, and items are added fluently, so building a custom menu is
 * a few chained calls.
 */
public class Menu {
    private String title;
    private String subtitle;
    private final List<MenuItem> items = new ArrayList<>();
    private MenuTheme theme = MenuTheme.defaultTheme();
    private int selected = 0;

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

    public void update(double dt, InputManager input) {
        if (items.isEmpty()) return;

        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_DOWN)
                || input.isKeyJustPressed(java.awt.event.KeyEvent.VK_S)) {
            move(1);
        }
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_UP)
                || input.isKeyJustPressed(java.awt.event.KeyEvent.VK_W)) {
            move(-1);
        }
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_ENTER)
                || input.isKeyJustPressed(java.awt.event.KeyEvent.VK_SPACE)) {
            items.get(selected).activate();
            return;
        }

        // Mouse: hover selects, click activates.
        int mx = input.getMouseX();
        int my = input.getMouseY();
        for (int k = 0; k < items.size(); k++) {
            MenuItem it = items.get(k);
            if (it.enabled && it.contains(mx, my)) {
                selected = k;
                if (input.isMouseJustPressed()) {
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
            if (items.get(selected).enabled) break;
        }
    }

    public void render(Graphics2D g, int viewportW, int viewportH) {
        if (theme.drawBackground) {
            g.setColor(theme.background);
            g.fillRect(0, 0, viewportW, viewportH);
        }

        // Title + subtitle.
        g.setFont(theme.titleFont);
        g.setColor(theme.title);
        drawCentered(g, title, viewportW / 2, viewportH / 4);
        if (subtitle != null) {
            g.setFont(theme.subtitleFont);
            g.setColor(theme.item);
            drawCentered(g, subtitle, viewportW / 2, viewportH / 4 + 42);
        }

        // Items.
        g.setFont(theme.itemFont);
        FontMetrics fm = g.getFontMetrics();
        int startY = viewportH / 2;
        for (int k = 0; k < items.size(); k++) {
            MenuItem it = items.get(k);
            String label = it.text();
            int tw = fm.stringWidth(label);
            int x = viewportW / 2 - tw / 2;
            int y = startY + k * theme.itemSpacing;

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
    }

    private void drawCentered(Graphics2D g, String s, int cx, int cy) {
        if (s == null) return;
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy);
    }
}
