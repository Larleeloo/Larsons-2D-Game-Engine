package com.larsons.engine.demo;

import java.awt.Rectangle;

/**
 * The auto-battler HUD's screen geometry, in one place so the scene's
 * update hit-testing, its rendering, and the layout tests all agree — and so
 * "no two text panels overlap" is a checkable property instead of a hope.
 * Every region is computed from the viewport size (plus the row/item counts
 * that stretch a panel), never from what happens to be drawn this frame.
 *
 * <p>Vertical map, top to bottom: the title band, then the side panels (left:
 * synergies while planning / the damage meter during combat; right: the
 * standings), then the bench strip, then the shop bar. The item bench hangs on
 * the left above the economy readout; panels that could grow into a neighbour
 * expose a bound the scene uses to cap their row counts.
 */
final class AutoHud {

    static final int SHOP_BAR_HEIGHT = 116;

    /** Left/right side panel width (synergies, damage meter, standings). */
    static final int PANEL_WIDTH = 205;
    static final int PANEL_TOP = 64;
    static final int SYNERGY_ROW_HEIGHT = 26;
    static final int STANDING_ROW_HEIGHT = 27;
    static final int DAMAGE_ROW_HEIGHT = 24;

    static final int BENCH_SLOT = 58;
    static final int ITEM_SLOT = 36;

    private AutoHud() {}

    static int shopBarY(int viewportHeight) {
        return viewportHeight - SHOP_BAR_HEIGHT;
    }

    static Rectangle shopBar(int w, int h) {
        return new Rectangle(0, shopBarY(h), w, SHOP_BAR_HEIGHT);
    }

    static Rectangle xpButton(int w, int h) {
        return new Rectangle(16, shopBarY(h) + 14, 120, 40);
    }

    static Rectangle rerollButton(int w, int h) {
        return new Rectangle(16, shopBarY(h) + 62, 120, 40);
    }

    static Rectangle sellButton(int w, int h) {
        return new Rectangle(w - 170, shopBarY(h) + 34, 150, 48);
    }

    /** The shop-lock toggle, tucked above the sell button in the bar's corner. */
    static Rectangle lockButton(int w, int h) {
        return new Rectangle(w - 52, shopBarY(h) + 4, 38, 26);
    }

    /** The once-per-round "remove items" button, under the sell button. */
    static Rectangle unequipButton(int w, int h) {
        return new Rectangle(w - 170, shopBarY(h) + 86, 150, 24);
    }

    /** One of the three formation buttons (gather/spread/flip) under the title. */
    static Rectangle arrangeButton(int w, int index) {
        int bw = 86, gap = 8;
        int x = w / 2 - (bw * 3 + gap * 2) / 2 + index * (bw + gap);
        return new Rectangle(x, 62, bw, 22);
    }

    /** The current-relic badge in the free top-left corner. */
    static Rectangle relicBadge(int w) {
        return new Rectangle(10, 8, 195, 46);
    }

    /**
     * Shop cards shrink on narrow viewports so five of them always fit between
     * the economy buttons (left) and the sell button (right) without touching
     * either.
     */
    static Rectangle shopCard(int w, int h, int index) {
        int cardW = Math.min(150, (w - 352) / 5 - 10);
        int cardH = SHOP_BAR_HEIGHT - 20;
        int cardsX = Math.max(152, w / 2 - (cardW * 5 + 4 * 10) / 2);
        return new Rectangle(cardsX + index * (cardW + 10), shopBarY(h) + 10, cardW, cardH);
    }

    static Rectangle benchSlot(int w, int h, int index) {
        int benchX = w / 2 - (BENCH_SLOT * 9 + 8 * 4) / 2;
        int benchY = shopBarY(h) - BENCH_SLOT - 10;
        return new Rectangle(benchX + index * (BENCH_SLOT + 4), benchY, BENCH_SLOT, BENCH_SLOT);
    }

    /** The whole bench strip (all nine slots), for overlap checks. */
    static Rectangle benchStrip(int w, int h) {
        Rectangle first = benchSlot(w, h, 0);
        Rectangle last = benchSlot(w, h, 8);
        return new Rectangle(first.x, first.y, last.x + last.width - first.x, first.height);
    }

    /**
     * Item bench slots stack in two columns on the lower left, growing upward
     * from above the economy readout (so a full bench never covers the gold
     * text).
     */
    static Rectangle itemSlot(int w, int h, int index) {
        int col = index % 2, row = index / 2;
        return new Rectangle(14 + col * 40, shopBarY(h) - 104 - row * 40, ITEM_SLOT, ITEM_SLOT);
    }

    /** Bounding box of {@code count} item slots plus their "Items" label. */
    static Rectangle itemBench(int w, int h, int count) {
        int rows = Math.max(1, (Math.max(1, count) + 1) / 2);
        Rectangle bottom = itemSlot(w, h, 0);
        int top = bottom.y - (rows - 1) * 40 - 18; // 18 for the label above
        return new Rectangle(14, top, 80, bottom.y + bottom.height - top);
    }

    /**
     * The gold / level / XP / streak readout: two short lines in the lower
     * left, narrow enough to never reach the (centred) bench strip.
     */
    static Rectangle economyLine(int w, int h) {
        return new Rectangle(14, shopBarY(h) - 52, 185, 44);
    }

    /** The centred round/phase title text at the top of the screen. */
    static Rectangle titleBand(int w) {
        return new Rectangle(w / 2 - 260, 8, 520, 52);
    }

    /**
     * The left side panel (synergies while planning, the damage meter in
     * combat). Its bottom is clamped above the item bench, whose height
     * depends on how many items the player holds.
     */
    static Rectangle leftPanel(int w, int h, int itemCount) {
        int bottom = (itemCount > 0 ? itemBench(w, h, itemCount).y : itemSlot(w, h, 0).y) - 8;
        return new Rectangle(10, PANEL_TOP, PANEL_WIDTH, Math.max(30, bottom - PANEL_TOP));
    }

    /** The standings panel down the right edge, clamped above the bench row. */
    static Rectangle standingsPanel(int w, int h) {
        int bottom = benchSlot(w, h, 0).y - 8;
        return new Rectangle(w - PANEL_WIDTH - 6, PANEL_TOP, PANEL_WIDTH,
                Math.max(30, bottom - PANEL_TOP));
    }

    /** The board-scouting overlay panel, centred over the board. */
    static Rectangle viewPanel(int w, int h) {
        int pw = Math.min(720, w - 80);
        int ph = Math.min(430, h - 130);
        return new Rectangle((w - pw) / 2, (h - ph) / 2 - 24, pw, ph);
    }
}
