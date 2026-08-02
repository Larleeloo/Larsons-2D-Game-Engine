package com.larsons.engine;

import com.larsons.engine.input.InputManager;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;
import org.junit.jupiter.api.BeforeAll;
import com.larsons.engine.graphics.draw.Java2DTarget;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for {@link Menu}'s scrolling: overflowing menus show a
 * draggable scroll bar, the wheel and thumb move the view without moving the
 * selection, and keyboard navigation keeps the selected item on screen.
 */
class MenuTest {

    private static final int W = 640, H = 360;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static Menu longMenu(int n) {
        Menu menu = new Menu("Title").subtitle("subtitle").theme(MenuTheme.dark());
        for (int i = 0; i < n; i++) menu.add("Item " + i, () -> { });
        return menu;
    }

    private static void renderOnce(Menu menu) {
        renderOnce(menu, W, H);
    }

    private static void renderOnce(Menu menu, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        menu.render(new Java2DTarget(g, w, h), w, h);
        g.dispose();
    }

    @Test
    void longMenusOverflowAndShowAScrollBar() {
        Menu menu = longMenu(30);
        renderOnce(menu);
        assertTrue(menu.items().get(0).width > 0, "first item starts visible");
        assertEquals(0, menu.items().get(29).width, "last item starts off-screen");
        assertTrue(menu.scrollThumbBounds().height > 0, "an overflowing menu shows a thumb");
    }

    @Test
    void shortMenusNeedNoScrollBar() {
        Menu menu = longMenu(3);
        renderOnce(menu, 640, 720); // the app's real window height
        assertTrue(menu.items().get(2).width > 0, "all items fit");
        assertEquals(0, menu.scrollThumbBounds().height, "no bar when everything fits");
    }

    @Test
    void mouseWheelScrollsWithoutMovingSelection() {
        Menu menu = longMenu(30);
        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(menu); // establish scroll metrics the wheel clamps against
        assertTrue(menu.items().get(0).width > 0, "first item starts visible");

        input.mouseWheelMoved(new MouseWheelEvent(src, MouseEvent.MOUSE_WHEEL, 0L, 0,
                10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 3));
        input.newFrame();
        menu.update(1.0 / 120.0, input);
        renderOnce(menu);

        assertEquals(3, menu.getScroll(), "wheel scrolls the view by its rotation");
        assertEquals(0, menu.getSelectedIndex(), "wheel must not move the selection");
        assertEquals(0, menu.items().get(0).width, "top item scrolled off");
    }

    @Test
    void keyboardNavigationScrollsSelectionIntoView() {
        Menu menu = longMenu(30);
        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(menu);
        assertEquals(0, menu.items().get(29).width, "last item starts hidden");

        for (int i = 0; i < 29; i++) {
            input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
            input.keyReleased(new KeyEvent(src, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
            input.newFrame();
            menu.update(1.0 / 120.0, input);
        }
        renderOnce(menu);

        assertEquals(29, menu.getSelectedIndex());
        assertTrue(menu.items().get(29).width > 0, "the selected item is scrolled into view");
        assertEquals(0, menu.items().get(0).width, "top items scrolled off");
    }

    @Test
    void draggingTheThumbScrollsWithoutMovingSelection() {
        Menu menu = longMenu(30);
        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(menu);

        Rectangle thumb = menu.scrollThumbBounds();
        assertTrue(thumb.height > 0, "overflowing menu shows a scroll-bar thumb");
        int grabX = thumb.x + thumb.width / 2;
        int grabY = thumb.y + thumb.height / 2;

        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, grabX, grabY, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, grabX, grabY, 1, false));
        input.newFrame();
        menu.update(1.0 / 120.0, input);

        Rectangle track = menu.scrollTrackBounds();
        int dragY = track.y + track.height;
        input.mouseDragged(new MouseEvent(src, MouseEvent.MOUSE_DRAGGED, 0L, 0, grabX, dragY, 0, false));
        input.newFrame();
        menu.update(1.0 / 120.0, input);
        renderOnce(menu);

        assertTrue(menu.getScroll() > 0, "dragging the thumb scrolls the view");
        assertEquals(0, menu.getSelectedIndex(), "dragging the thumb must not move the selection");
        assertTrue(menu.items().get(29).width > 0, "bottom item scrolled into view");
    }
}
