package com.larsons.engine.ui;

import com.larsons.engine.crafting.Recipe;
import com.larsons.engine.crafting.RecipeRegistry;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.InputManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The crafting/alchemy station overlay: a scrollable list of every recipe the
 * station offers (1–3 ingredients → output), with live have/need counts pulled
 * from the player's inventory. Craftable rows light up; clicking one consumes
 * the ingredients and pockets the result. Shared by the play scene and the
 * creative editor's play-test so both open the identical UI at a station.
 */
public final class CraftingPanel {

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font ROW_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private static final int ROW_H = 40;
    private static final int PANEL_W = 480;

    /** A completed craft: what was made and how many didn't fit the bag. */
    public record Crafted(Recipe recipe, int leftover) {}

    private final String station;
    private final List<Recipe> recipes;
    private final ItemRegistry items;
    private int scroll;
    // Hovered recipe (for the plain-text tooltip), sampled in update().
    private int hoverIdx = -1;
    private int hoverX, hoverY;

    public CraftingPanel(String station, RecipeRegistry registry, ItemRegistry items) {
        this.station = station;
        this.recipes = registry.forStation(station);
        this.items = items;
    }

    public String station() {
        return station;
    }

    /** Human title for the station kind. */
    public String title() {
        return Recipe.STATION_ALCHEMY.equals(station) ? "Alchemy Station" : "Crafting Table";
    }

    /**
     * Mouse interaction: wheel scrolls the list, clicking a craftable row
     * crafts it. Returns the completed craft this frame, or {@code null}.
     */
    public Crafted update(InputManager input, Inventory inv, int vw, int vh) {
        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            scroll = Math.max(0, Math.min(maxScroll(vh), scroll + wheel));
        }
        int mx = input.getMouseX(), my = input.getMouseY();
        int x0 = (vw - PANEL_W) / 2;
        int listTop = listTop(vh);
        // Track the hovered row every frame; render() shows its plain-text
        // recipe tooltip.
        hoverIdx = -1;
        hoverX = mx;
        hoverY = my;
        if (mx >= x0 && mx <= x0 + PANEL_W && my >= listTop) {
            int idx = scroll + (my - listTop) / ROW_H;
            if (idx >= 0 && idx < recipes.size()
                    && idx < scroll + visibleRows(vh)) {
                hoverIdx = idx;
            }
        }
        if (!input.isMouseJustPressed() || hoverIdx < 0) return null;
        Recipe recipe = recipes.get(hoverIdx);
        int leftover = recipe.craft(inv);
        return leftover < 0 ? null : new Crafted(recipe, leftover);
    }

    /** "2× Planks + 1× Stick → 4× Platform" — the hovered recipe in plain text. */
    private String recipeText(Recipe r) {
        StringBuilder sb = new StringBuilder();
        for (Recipe.Ingredient in : r.inputs()) {
            if (sb.length() > 0) sb.append(" + ");
            ItemDef def = items.get(in.key());
            sb.append(in.count()).append("× ").append(def != null ? def.name() : in.key());
        }
        ItemDef out = items.get(r.output());
        sb.append(" → ").append(r.outputCount()).append("× ")
                .append(out != null ? out.name() : r.output());
        return sb.toString();
    }

    private int panelTop(int vh) {
        return Math.max(40, vh / 2 - panelHeight(vh) / 2);
    }

    private int panelHeight(int vh) {
        return Math.min(vh - 80, 96 + recipes.size() * ROW_H);
    }

    private int listTop(int vh) {
        return panelTop(vh) + 62;
    }

    private int visibleRows(int vh) {
        return Math.max(1, (panelHeight(vh) - 78) / ROW_H);
    }

    private int maxScroll(int vh) {
        return Math.max(0, recipes.size() - visibleRows(vh));
    }

    public void render(Graphics2D g, int vw, int vh, Inventory inv, double animClock) {
        int x0 = (vw - PANEL_W) / 2;
        int y0 = panelTop(vh);
        int ph = panelHeight(vh);
        boolean alchemy = Recipe.STATION_ALCHEMY.equals(station);

        g.setColor(new Color(10, 10, 16, 235));
        g.fillRoundRect(x0 - 14, y0 - 14, PANEL_W + 28, ph + 28, 14, 14);
        g.setColor(alchemy ? new Color(190, 150, 255) : new Color(255, 210, 130));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x0 - 14, y0 - 14, PANEL_W + 28, ph + 28, 14, 14);

        g.setFont(TITLE_FONT);
        g.drawString(title(), x0, y0 + 14);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(170, 170, 190));
        g.drawString("Click a lit recipe to craft it (consumes the ingredients)"
                + " · scroll for more · [E]/[Esc] close", x0, y0 + 34);

        int listTop = listTop(vh);
        int rows = visibleRows(vh);
        scroll = Math.max(0, Math.min(maxScroll(vh), scroll));
        for (int i = 0; i < rows; i++) {
            int idx = scroll + i;
            if (idx >= recipes.size()) break;
            drawRow(g, recipes.get(idx), inv, x0, listTop + i * ROW_H, animClock);
        }
        if (maxScroll(vh) > 0) {
            g.setColor(new Color(255, 255, 255, 90));
            g.setFont(SMALL_FONT);
            g.drawString((scroll + 1) + "-" + Math.min(recipes.size(), scroll + rows)
                    + " / " + recipes.size(), x0 + PANEL_W - 60, y0 + 14);
        }
        drawHoverTooltip(g, vw, inv);
    }

    /** The plain-text recipe of the hovered row, in a tooltip by the cursor. */
    private void drawHoverTooltip(Graphics2D g, int vw, Inventory inv) {
        if (hoverIdx < 0 || hoverIdx >= recipes.size()) return;
        Recipe r = recipes.get(hoverIdx);
        String text = recipeText(r);
        String status = r.canCraft(inv) ? "Click to craft" : "Missing ingredients";
        g.setFont(ROW_FONT);
        int tw = Math.max(g.getFontMetrics().stringWidth(text),
                g.getFontMetrics().stringWidth(status));
        int tx = Math.max(8, Math.min(vw - tw - 24, hoverX + 16));
        int ty = hoverY - 44;
        g.setColor(new Color(6, 6, 12, 235));
        g.fillRoundRect(tx - 8, ty - 16, tw + 16, 42, 8, 8);
        g.setColor(new Color(255, 255, 255, 60));
        g.drawRoundRect(tx - 8, ty - 16, tw + 16, 42, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(text, tx, ty);
        g.setColor(r.canCraft(inv) ? new Color(150, 220, 150) : new Color(230, 130, 130));
        g.drawString(status, tx, ty + 18);
    }

    private void drawRow(Graphics2D g, Recipe r, Inventory inv, int x, int y, double animClock) {
        boolean can = r.canCraft(inv);
        g.setColor(new Color(255, 255, 255, can ? 34 : 12));
        g.fillRoundRect(x - 6, y, PANEL_W + 12, ROW_H - 6, 8, 8);
        if (can) {
            g.setColor(new Color(150, 220, 150, 120));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x - 6, y, PANEL_W + 12, ROW_H - 6, 8, 8);
        }

        // Output icon + name.
        ItemDef out = items.get(r.output());
        drawIcon(g, out, x + 2, y + 4, 26, animClock);
        g.setFont(ROW_FONT);
        g.setColor(can ? Color.WHITE : new Color(150, 150, 165));
        String name = (out != null ? out.name() : r.output())
                + (r.outputCount() > 1 ? " ×" + r.outputCount() : "");
        g.drawString(name, x + 34, y + 21);

        // Ingredients, right-aligned: icon + have/need per stack.
        int ix = x + PANEL_W - r.inputs().size() * 74;
        for (Recipe.Ingredient in : r.inputs()) {
            ItemDef def = items.get(in.key());
            drawIcon(g, def, ix, y + 6, 20, animClock);
            int have = inv.totalOf(in.key());
            g.setFont(SMALL_FONT);
            g.setColor(have >= in.count() ? new Color(150, 220, 150) : new Color(230, 120, 120));
            g.drawString(have + "/" + in.count(), ix + 23, y + 20);
            ix += 74;
        }
    }

    private void drawIcon(Graphics2D g, ItemDef def, int x, int y, int size, double animClock) {
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + def.key(), animClock);
        if (img == null) img = EntitySprites.item(def, size);
        g.drawImage(img, x, y, size, size, null);
    }
}
