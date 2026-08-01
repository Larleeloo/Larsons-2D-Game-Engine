package com.larsons.engine.ui;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;

import java.awt.AlphaComposite;
import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The storage-block overlay for chests and barrels: a second inventory that
 * lives in the level data ({@code Level.containers}), opened with E next to
 * the block. Clicking a stored stack takes it into the player's inventory;
 * clicking an empty container slot (or pressing Q) deposits the held or
 * selected hotbar stack. Contents save/load with the level, so stashes
 * persist.
 *
 * <p>The panel is laid out <em>beside</em> the player's inventory panel
 * rather than over it: the scene shows the inventory on the left and this
 * panel on the right ({@link #pairedInventoryLeft}), centred as a pair, so
 * moving items between the two is a matter of looking left and right.
 *
 * <p>Opening and closing are animated: the panel starts in the
 * {@code OPENING} state (it grows and fades in while the block's lid swings
 * up — {@link #drawLid}), sits {@code OPEN} while in use, and
 * {@link #beginClose()} plays the reverse before the scene discards it once
 * {@link #closed()} reports the animation finished.
 */
public final class ContainerPanel {

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private static final int SLOT = 46;
    private static final int PAD = 6;
    private static final int COLS = 6;

    /** Gap between the paired inventory and container panels. */
    public static final int PANEL_GAP = 24;

    /** Background width of the player-inventory panel (same slot metrics). */
    public static final int INVENTORY_PANEL_W =
            Inventory.COLS * (SLOT + PAD) - PAD + 40;

    /** Animation lifecycle: opening → open → closing → closed. */
    public enum State { OPENING, OPEN, CLOSING, CLOSED }

    private static final double OPEN_TIME = 0.22;
    private static final double CLOSE_TIME = 0.16;

    private final Level level;
    private final int col, row;
    private final String title;
    private final ItemRegistry items;

    private State state = State.OPENING;
    private double stateTime;

    public ContainerPanel(Level level, int col, int row, String title, ItemRegistry items) {
        this.level = level;
        this.col = col;
        this.row = row;
        this.title = title;
        this.items = items;
    }

    /** Tile column of the backing chest/barrel block. */
    public int col() {
        return col;
    }

    /** Tile row of the backing chest/barrel block. */
    public int row() {
        return row;
    }

    /** Whether the backing block still exists (mined away = panel closes). */
    public boolean valid() {
        var block = level.blockAt(col, row);
        return block != null && block.container();
    }

    // --- open/close animation ------------------------------------------------------

    /** Advances the open/close animation; call once per frame. */
    public void tick(double dt) {
        stateTime += dt;
        if (state == State.OPENING && stateTime >= OPEN_TIME) {
            state = State.OPEN;
        } else if (state == State.CLOSING && stateTime >= CLOSE_TIME) {
            state = State.CLOSED;
        }
    }

    /** Starts the closing animation (no-op when already closing/closed). */
    public void beginClose() {
        if (state == State.OPENING || state == State.OPEN) {
            state = State.CLOSING;
            stateTime = 0;
        }
    }

    public State state() {
        return state;
    }

    /** True once the closing animation has finished — discard the panel. */
    public boolean closed() {
        return state == State.CLOSED;
    }

    /** Input is only accepted while fully open (not mid-animation). */
    public boolean interactive() {
        return state == State.OPEN;
    }

    /**
     * 0 = fully closed, 1 = fully open. Drives the panel's scale/fade and
     * the lid swing on the block itself.
     */
    public double openness() {
        return switch (state) {
            case OPENING -> ease(Math.min(1, stateTime / OPEN_TIME));
            case OPEN -> 1;
            case CLOSING -> 1 - ease(Math.min(1, stateTime / CLOSE_TIME));
            case CLOSED -> 0;
        };
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t); // smoothstep
    }

    // --- side-by-side geometry -----------------------------------------------------

    private List<ItemStack> contents() {
        return level.openContainer(col, row);
    }

    private int rows() {
        return (Level.CONTAINER_SLOTS + COLS - 1) / COLS;
    }

    /**
     * Left background edge of the paired panels: inventory + gap + container,
     * centred as a group (clamped so small windows keep the pair on screen).
     */
    public static int pairedInventoryLeft(int vw) {
        int total = INVENTORY_PANEL_W + PANEL_GAP + panelWidth();
        return Math.max(8, (vw - total) / 2);
    }

    /** Background width of this panel. */
    public static int panelWidth() {
        return COLS * (SLOT + PAD) - PAD + 40;
    }

    /** Top-left of the container grid, to the right of the inventory panel. */
    private int[] origin(int vw, int vh) {
        int x = pairedInventoryLeft(vw) + INVENTORY_PANEL_W + PANEL_GAP + 20;
        // Background top aligned with the inventory panel's background top.
        int invGh = Inventory.ROWS * (SLOT + PAD) - PAD;
        int y = (vh - invGh) / 2 - 52 + 56;
        return new int[]{x, y};
    }

    /** Panel background bounds {x, y, w, h}. */
    public int[] bounds(int vw, int vh) {
        int[] o = origin(vw, vh);
        int gw = COLS * (SLOT + PAD) - PAD;
        int gh = rows() * (SLOT + PAD) - PAD;
        return new int[]{o[0] - 20, o[1] - 56, gw + 40, gh + 92};
    }

    /** Whether a screen point falls inside the panel background. */
    public boolean contains(int sx, int sy, int vw, int vh) {
        int[] b = bounds(vw, vh);
        return sx >= b[0] && sx <= b[0] + b[2] && sy >= b[1] && sy <= b[1] + b[3];
    }

    // --- interaction ---------------------------------------------------------------

    /**
     * Mouse/keyboard interaction. {@code cursorSlot} is the inventory slot the
     * scene's inventory cursor holds (-1 for none); a held stack deposits in
     * preference to the selected hotbar stack. Returns {@code true} when
     * anything moved (the caller plays the click feedback).
     */
    public boolean update(InputManager input, Inventory inv, int cursorSlot, int vw, int vh) {
        if (!interactive()) return false;
        List<ItemStack> box = contents();
        // Q deposits the held/selected stack.
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_Q)) {
            return deposit(inv, box, cursorSlot);
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
        return deposit(inv, box, cursorSlot);
    }

    /** Move the held (cursor) stack, or the selected hotbar stack, into the container. */
    private boolean deposit(Inventory inv, List<ItemStack> box, int cursorSlot) {
        int from = cursorSlot >= 0 ? cursorSlot : inv.selectedIndex();
        ItemStack held = inv.slot(from);
        if (held == null || box.size() >= Level.CONTAINER_SLOTS) return false;
        // Merge into an existing stack of the same item first.
        ItemDef def = items.get(held.key);
        int max = def != null ? def.maxStack() : 64;
        for (ItemStack s : box) {
            if (s.key.equals(held.key) && s.count < max) {
                int take = Math.min(held.count, max - s.count);
                s.count += take;
                inv.removeAt(from, take);
                return true;
            }
        }
        ItemStack moved = new ItemStack(held.key, held.count);
        moved.wear = held.wear;
        box.add(moved);
        inv.removeAt(from, held.count);
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

    // --- rendering -----------------------------------------------------------------

    public void render(Graphics2D g, int vw, int vh, double animClock) {
        double open = openness();
        if (open <= 0.01) return;
        int[] o = origin(vw, vh);
        int gw = COLS * (SLOT + PAD) - PAD;
        int gh = rows() * (SLOT + PAD) - PAD;

        // Opening/closing: the panel grows out of / shrinks into its centre
        // while fading, so the chest visibly "opens" rather than popping.
        AffineTransform oldTx = g.getTransform();
        Composite oldComp = g.getComposite();
        if (open < 1) {
            double s = 0.7 + 0.3 * open;
            double cx = o[0] + gw / 2.0, cy = o[1] + gh / 2.0;
            g.translate(cx, cy);
            g.scale(s, s);
            g.translate(-cx, -cy);
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, (float) open));
        }

        List<ItemStack> box = contents();

        g.setColor(new Color(10, 10, 16, 235));
        g.fillRoundRect(o[0] - 20, o[1] - 56, gw + 40, gh + 92, 14, 14);
        g.setColor(new Color(255, 210, 130));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(o[0] - 20, o[1] - 56, gw + 40, gh + 92, 14, 14);
        g.setFont(TITLE_FONT);
        // The title is the block's name, which a creator may have made long.
        g.drawString(UiText.fit(g.getFontMetrics(), title, gw), o[0], o[1] - 32);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(170, 170, 190));
        g.drawString("Click takes · empty slot / [Q] stashes · [E] close",
                o[0], o[1] - 14);

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

        g.setTransform(oldTx);
        g.setComposite(oldComp);
    }

    /**
     * The chest/barrel lid, drawn over the container's projected tile quad as
     * the panel opens and closes. The lid band hinges on the tile's top edge
     * and swings from flat on the block (closed) up above it (open), the
     * interior darkening underneath — a fake-3D flip that works in every
     * perspective because it's built from the quad's own corners
     * ({@code xs}/{@code ys}: TL, TR, BR, BL).
     */
    public static void drawLid(DrawTarget target, int[] xs, int[] ys, double open, Color base) {
        if (open <= 0.01) return; // fully closed = the plain tile
        double depth = 0.34; // lid depth as a fraction of the tile
        // The interior shows where the lid swung away.
        int[] ix = {xs[0], xs[1], edgeX(xs, depth, true), edgeX(xs, depth, false)};
        int[] iy = {ys[0], ys[1], edgeY(ys, depth, true), edgeY(ys, depth, false)};
        target.fillPolygon(ix, iy, 4, new Color(0, 0, 0, (int) (130 * open)));
        // Free edge travels depth → -depth: through zero it reads as the lid
        // rotating toward the viewer, past it as standing open above the block.
        double t = depth * (1 - 2 * open);
        int[] lx = {xs[0], xs[1], edgeX(xs, t, true), edgeX(xs, t, false)};
        int[] ly = {ys[0], ys[1], edgeY(ys, t, true), edgeY(ys, t, false)};
        // Highlight fades in near the closed endpoints so the swing starts
        // and ends in the tile's own colours instead of popping.
        double emph = Math.min(1, open * 4);
        target.fillPolygon(lx, ly, 4,
                mix(base, open > 0.5 ? base.darker() : base.brighter(), emph));
        target.drawPolygon(lx, ly, 4,
                mix(base.darker(), base.darker().darker(), emph).getRGB(), 1.5f);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(lerp(a.getRed(), b.getRed(), t),
                lerp(a.getGreen(), b.getGreen(), t),
                lerp(a.getBlue(), b.getBlue(), t));
    }

    /** X at parameter {@code t} down the quad's right (or left) side edge. */
    private static int edgeX(int[] xs, double t, boolean right) {
        return right ? lerp(xs[1], xs[2], t) : lerp(xs[0], xs[3], t);
    }

    /** Y at parameter {@code t} down the quad's right (or left) side edge. */
    private static int edgeY(int[] ys, double t, boolean right) {
        return right ? lerp(ys[1], ys[2], t) : lerp(ys[0], ys[3], t);
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }
}
