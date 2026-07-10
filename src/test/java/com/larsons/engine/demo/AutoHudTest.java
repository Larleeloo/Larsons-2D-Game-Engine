package com.larsons.engine.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "no on-screen text overlaps" guarantee, made checkable: every HUD
 * region the auto-battler scene draws text into comes from {@link AutoHud},
 * and this test asserts the regions stay pairwise disjoint across window
 * sizes, item-bench fill levels, and panel row counts — including the
 * worst cases (a full 12-item bench, 10 players).
 */
@Timeout(30)
class AutoHudTest {

    private static final int[][] SIZES = {{960, 540}, {1280, 720}, {1600, 900}, {1920, 1080}};
    private static final int[] ITEM_COUNTS = {0, 5, 12};

    /** Every distinct text-bearing region for one configuration. */
    private static Map<String, Rectangle> regions(int w, int h, int items) {
        Map<String, Rectangle> r = new LinkedHashMap<>();
        r.put("title", AutoHud.titleBand(w));
        r.put("leftPanel", AutoHud.leftPanel(w, h, items));
        r.put("standings", AutoHud.standingsPanel(w, h));
        r.put("bench", AutoHud.benchStrip(w, h));
        r.put("economy", AutoHud.economyLine(w, h));
        if (items > 0) r.put("itemBench", AutoHud.itemBench(w, h, items));
        r.put("xpButton", AutoHud.xpButton(w, h));
        r.put("rerollButton", AutoHud.rerollButton(w, h));
        r.put("sellButton", AutoHud.sellButton(w, h));
        for (int i = 0; i < 5; i++) {
            r.put("shopCard" + i, AutoHud.shopCard(w, h, i));
        }
        return r;
    }

    @Test
    void hudTextRegionsNeverOverlap() {
        for (int[] size : SIZES) {
            for (int items : ITEM_COUNTS) {
                Map<String, Rectangle> regions = regions(size[0], size[1], items);
                var names = regions.keySet().toArray(new String[0]);
                for (int i = 0; i < names.length; i++) {
                    for (int j = i + 1; j < names.length; j++) {
                        Rectangle a = regions.get(names[i]);
                        Rectangle b = regions.get(names[j]);
                        assertFalse(a.intersects(b),
                                names[i] + " overlaps " + names[j] + " at "
                                        + size[0] + "x" + size[1] + " with " + items
                                        + " items: " + a + " vs " + b);
                    }
                }
            }
        }
    }

    @Test
    void everythingAboveTheShopBarStaysAboveIt() {
        for (int[] size : SIZES) {
            int w = size[0], h = size[1];
            Rectangle shopBar = AutoHud.shopBar(w, h);
            for (String name : new String[]{"bench", "economy", "itemBench",
                    "leftPanel", "standings"}) {
                Rectangle r = switch (name) {
                    case "bench" -> AutoHud.benchStrip(w, h);
                    case "economy" -> AutoHud.economyLine(w, h);
                    case "itemBench" -> AutoHud.itemBench(w, h, 12);
                    case "leftPanel" -> AutoHud.leftPanel(w, h, 12);
                    default -> AutoHud.standingsPanel(w, h);
                };
                assertTrue(r.y + r.height <= shopBar.y,
                        name + " reaches into the shop bar at " + w + "x" + h);
            }
        }
    }

    @Test
    void shopBarControlsSitInsideTheBar() {
        for (int[] size : SIZES) {
            int w = size[0], h = size[1];
            Rectangle bar = AutoHud.shopBar(w, h);
            assertTrue(bar.contains(AutoHud.xpButton(w, h)));
            assertTrue(bar.contains(AutoHud.rerollButton(w, h)));
            assertTrue(bar.contains(AutoHud.sellButton(w, h)));
            for (int i = 0; i < 5; i++) {
                assertTrue(bar.contains(AutoHud.shopCard(w, h, i)),
                        "card " + i + " inside the bar at " + w + "x" + h);
            }
        }
    }

    /** The drag-and-drop tests (and muscle memory) rely on this geometry. */
    @Test
    void benchGeometryIsStable() {
        Rectangle slot0 = AutoHud.benchSlot(960, 540, 0);
        int benchX = 960 / 2 - (58 * 9 + 8 * 4) / 2;
        int benchY = (540 - 116) - 58 - 10;
        assertTrue(slot0.x == benchX && slot0.y == benchY
                && slot0.width == 58 && slot0.height == 58, "bench slot geometry: " + slot0);
    }
}
