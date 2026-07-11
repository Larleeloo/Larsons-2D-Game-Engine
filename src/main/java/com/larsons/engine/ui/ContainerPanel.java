package com.larsons.engine.ui;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The storage-block overlay for chests and barrels: a second inventory that
 * lives in the level data ({@code Level.containers}), opened with E next to
 * the block. Clicking a stored stack takes it into the player's inventory;
 * clicking an empty container slot (or pressing Q) deposits the selected
 * hotbar stack. Contents save/load with the level, so stashes persist.
 */
public final class ContainerPanel {

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private static final int SLOT = 46;
    private static final int PAD = 6;
    private static final int COLS = 6;

    private final Level level;
    private final int col, row;
    private final String title;
    private final ItemRegistry items;

    public ContainerPanel(Level level, int col, int row, String title, ItemRegistry items) {
        this.level = level;
        this.col = col;
        this.row = row;
        this.title = title;
        this.items = items;
    }

    /** Whether the backing block still exists (mined away = panel closes). */
    public boolean valid() {
        var block = level.blockAt(col, row);
        return block != null && block.container();
    }

    private List<ItemStack> contents() {
        return level.openContainer(col, row);
    }

    private int rows() {
        return (Level.CONTAINER_SLOTS + COLS - 1) / COLS;
    }

    private int[] origin(int vw, int vh) {
        int gw = COLS * (SLOT + PAD) - PAD;
        int gh = rows() * (SLOT + PAD) - PAD;
        return new int[]{(vw - gw) / 2, (vh - gh) / 2 - 30};
    }

    /**
     * Mouse/keyboard interaction. Returns {@code true} when anything moved
     * (the caller plays the click feedback).
     */
    public boolean update(InputManager input, Inventory inv, int vw, int vh) {
        List<ItemStack> box = contents();
        // Q deposits the selected hotbar stack.
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_Q)) {
            return deposit(inv, box);
        }
        if (!input.isMouseJustPressed()) return false;
        int slot = slotAt(input.getMouseX(), input.getMouseY(), vw, vh);
        if (slot < 0) return false;
        if (slot < box.size()) {
            // Take the clicked stack into the player's inventory.
            ItemStack s = box.get(slot);
            int leftover = inv.add(s.key, s.count);
            if (leftover == s.count) return false; // bag full
            if (leftover > 0) {
                s.count = leftover;
            } else {
                box.remove(slot);
            }
            return true;
        }
        return deposit(inv, box);
    }

    /** Move the selected hotbar stack into the container. */
    private boolean deposit(Inventory inv, List<ItemStack> box) {
        ItemStack held = inv.selectedStack();
        if (held == null || box.size() >= Level.CONTAINER_SLOTS) return false;
        // Merge into an existing stack of the same item first.
        ItemDef def = items.get(held.key);
        int max = def != null ? def.maxStack() : 64;
        for (ItemStack s : box) {
            if (s.key.equals(held.key) && s.count < max) {
                int take = Math.min(held.count, max - s.count);
                s.count += take;
                inv.removeAt(inv.selectedIndex(), take);
                return true;
            }
        }
        ItemStack moved = new ItemStack(held.key, held.count);
        moved.wear = held.wear;
        box.add(moved);
        inv.removeAt(inv.selectedIndex(), held.count);
        return true;
    }

    /** The container slot under a screen point, or -1. */
    private int slotAt(int sx, int sy, int vw, int vh) {
        int[] o = origin(vw, vh);
        int c = Math.floorDiv(sx - o[0], SLOT + PAD);
        int r = Math.floorDiv(sy - o[1], SLOT + PAD);
        if (c < 0 || c >= COLS || r < 0 || r >= rows()) return -1;
        if (sx - o[0] - c * (SLOT + PAD) >= SLOT) return -1;
        if (sy - o[1] - r * (SLOT + PAD) >= SLOT) return -1;
        int idx = r * COLS + c;
        return idx < Level.CONTAINER_SLOTS ? idx : -1;
    }

    public void render(Graphics2D g, int vw, int vh, double animClock) {
        int[] o = origin(vw, vh);
        int gw = COLS * (SLOT + PAD) - PAD;
        int gh = rows() * (SLOT + PAD) - PAD;
        List<ItemStack> box = contents();

        g.setColor(new Color(10, 10, 16, 235));
        g.fillRoundRect(o[0] - 20, o[1] - 56, gw + 40, gh + 92, 14, 14);
        g.setColor(new Color(255, 210, 130));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(o[0] - 20, o[1] - 56, gw + 40, gh + 92, 14, 14);
        g.setFont(TITLE_FONT);
        g.drawString(title, o[0], o[1] - 32);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(170, 170, 190));
        g.drawString("Click a stack to take it · click an empty slot or [Q] to stash"
                + " the selected hotbar stack · [E]/[Esc] close", o[0], o[1] - 14);

        for (int i = 0; i < Level.CONTAINER_SLOTS; i++) {
            int cx = o[0] + (i % COLS) * (SLOT + PAD);
            int cy = o[1] + (i / COLS) * (SLOT + PAD);
            g.setColor(new Color(255, 255, 255, 24));
            g.fillRoundRect(cx, cy, SLOT, SLOT, 8, 8);
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(cx, cy, SLOT, SLOT, 8, 8);
            if (i >= box.size()) continue;
            ItemStack s = box.get(i);
            ItemDef def = items.get(s.key);
            if (def == null) continue;
            BufferedImage img = Skins.frame("item/" + s.key, animClock);
            if (img == null) img = EntitySprites.item(def, 32);
            g.drawImage(img, cx + 6, cy + 6, SLOT - 12, SLOT - 12, null);
            if (s.count > 1) {
                String n = String.valueOf(s.count);
                int tw = g.getFontMetrics().stringWidth(n);
                g.setColor(Color.BLACK);
                g.drawString(n, cx + SLOT - tw - 3, cy + SLOT - 3);
                g.setColor(Color.WHITE);
                g.drawString(n, cx + SLOT - tw - 4, cy + SLOT - 4);
            }
        }
    }
}
