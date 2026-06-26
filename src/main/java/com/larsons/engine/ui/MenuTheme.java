package com.larsons.engine.ui;

import java.awt.Color;
import java.awt.Font;

/**
 * Visual styling for a {@link Menu} (requirement #6: menu customization).
 *
 * <p>All fields are public and mutable so a game can tweak any aspect — colours,
 * fonts, spacing, whether the menu paints its own background — or supply one of
 * the preset factories below as a starting point.
 */
public class MenuTheme {
    public Color background = new Color(18, 18, 28);
    public Color title = new Color(245, 245, 255);
    public Color item = new Color(180, 185, 200);
    public Color itemSelected = new Color(255, 210, 90);
    public Color itemDisabled = new Color(90, 90, 100);
    public Color accent = new Color(255, 210, 90);

    public Font titleFont = new Font("SansSerif", Font.BOLD, 48);
    public Font subtitleFont = new Font("SansSerif", Font.PLAIN, 20);
    public Font itemFont = new Font("SansSerif", Font.PLAIN, 26);

    public int itemSpacing = 54;
    public boolean drawBackground = true;

    public static MenuTheme defaultTheme() { return dark(); }

    public static MenuTheme dark() { return new MenuTheme(); }

    public static MenuTheme light() {
        MenuTheme t = new MenuTheme();
        t.background = new Color(235, 238, 245);
        t.title = new Color(20, 22, 30);
        t.item = new Color(60, 64, 80);
        t.itemSelected = new Color(200, 120, 20);
        t.itemDisabled = new Color(170, 174, 185);
        t.accent = new Color(200, 120, 20);
        return t;
    }
}
