package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoPlayer;
import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.autobattler.BattleSim;
import com.larsons.engine.autobattler.Element;
import com.larsons.engine.autobattler.SynergyCategory;
import com.larsons.engine.autobattler.Trait;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The auto-battler's illustrated field guide: a tabbed, scrollable reference
 * that explains the whole game — the round loop and rules, the gold economy,
 * every synergy trait, the item recipe grid, the shop rarity odds, and the
 * full unit roster with all of their statistics. Diagrams are built from the
 * same data the game runs on ({@link AutoUnits}, {@link AutoItems},
 * {@link Trait}, {@link AutoGame}, {@link AutoPlayer}), so it can never drift
 * out of sync with the balance.
 *
 * <p>The icons are clickable: click a trait dot, an item gem, an odds cell, a
 * phase node, or a unit card to pop a detail card with the fine print (per-tier
 * effects, recipes, star-scaled stats, abilities). Reached from the lobby's
 * "How to Play" button; <b>Esc</b> closes a detail card, then leaves the guide.
 */
public class AutoBattlerGuideScene extends AbstractScene {

    private static final Color[] COST_COLORS = {
            new Color(150, 155, 170),  // 1: gray
            new Color(110, 185, 110),  // 2: green
            new Color(95, 145, 235),   // 3: blue
            new Color(190, 110, 220),  // 4: purple
            new Color(235, 185, 80)    // 5: gold
    };
    private static final String[] COST_NAMES = {
            "Common", "Uncommon", "Rare", "Epic", "Legendary"
    };

    private enum Tab {
        OVERVIEW("Rules"), ECONOMY("Economy"), TRAITS("Synergies"),
        ELEMENTS("Elements"), ITEMS("Items"), ODDS("Shop Odds"), UNITS("Units");
        final String label;
        Tab(String label) { this.label = label; }
    }

    /** A clickable region in the scrolling content, resolved during render. */
    private static final class Hotspot {
        final Rectangle box;
        final Runnable onClick;
        Hotspot(Rectangle box, Runnable onClick) {
            this.box = box;
            this.onClick = onClick;
        }
    }

    /** A pop-up detail card: a titled list of coloured lines with an optional icon. */
    private static final class Detail {
        final String title;
        final Color titleColor;
        final BufferedImage icon;
        final List<String> lines = new ArrayList<>();
        final List<Color> colors = new ArrayList<>();
        Detail(String title, Color titleColor, BufferedImage icon) {
            this.title = title;
            this.titleColor = titleColor;
            this.icon = icon;
        }
        Detail line(String text, Color color) {
            lines.add(text);
            colors.add(color);
            return this;
        }
        Detail line(String text) {
            return line(text, new Color(205, 210, 226));
        }
        Detail blank() {
            return line("", Color.WHITE);
        }
    }

    private final GameContext ctx;

    private Tab tab = Tab.OVERVIEW;
    private int scroll;
    private int contentHeight;
    /** Synergies-tab category filter; null shows every trait. */
    private SynergyCategory traitFilter;

    private final Rectangle backBtn = new Rectangle();
    private final List<Rectangle> tabRects = new ArrayList<>();
    private final List<Hotspot> hotspots = new ArrayList<>();
    private Detail detail;

    private int mouseX, mouseY;

    public AutoBattlerGuideScene(GameContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onEnter() {
        tab = Tab.OVERVIEW;
        scroll = 0;
        detail = null;
        traitFilter = null;
        hotspots.clear();
    }

    // ------------------------------------------------------------------ update

    @Override
    public void update(double dt, InputManager input) {
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();
        layoutChrome();

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (detail != null) {
                detail = null;
            } else {
                scenes.transitionTo("autolobby");
            }
            return;
        }

        // Wheel and arrow keys scroll the current tab's content.
        int wheel = input.getWheelRotation();
        if (wheel != 0) scroll += wheel * 48;
        if (input.isKeyJustPressed(KeyEvent.VK_DOWN)) scroll += 40;
        if (input.isKeyJustPressed(KeyEvent.VK_UP)) scroll -= 40;
        if (input.isKeyJustPressed(KeyEvent.VK_LEFT)) cycleTab(-1);
        if (input.isKeyJustPressed(KeyEvent.VK_RIGHT)) cycleTab(1);
        clampScroll();

        if (!input.isMouseJustPressed()) return;

        // A detail card is modal: any click dismisses it and nothing else.
        if (detail != null) {
            detail = null;
            ctx.sfx(AudioManager.Sfx.CLICK);
            return;
        }

        if (backBtn.contains(mouseX, mouseY)) {
            ctx.sfx(AudioManager.Sfx.CLICK);
            scenes.transitionTo("autolobby");
            return;
        }
        for (int i = 0; i < tabRects.size(); i++) {
            if (tabRects.get(i).contains(mouseX, mouseY)) {
                Tab picked = Tab.values()[i];
                if (picked != tab) {
                    tab = picked;
                    scroll = 0;
                }
                ctx.sfx(AudioManager.Sfx.CLICK);
                return;
            }
        }
        // Content icons — only inside the scrolling viewport.
        if (mouseY >= contentTop() && mouseY <= contentBottom()) {
            for (Hotspot h : hotspots) {
                if (h.box.contains(mouseX, mouseY)) {
                    h.onClick.run();
                    ctx.sfx(AudioManager.Sfx.CLICK);
                    return;
                }
            }
        }
    }

    private void cycleTab(int dir) {
        Tab[] all = Tab.values();
        tab = all[(tab.ordinal() + dir + all.length) % all.length];
        scroll = 0;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (contentBottom() - contentTop() - 16));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private int contentTop() {
        return 118;
    }

    private int contentBottom() {
        return viewportHeight - 34;
    }

    private void layoutChrome() {
        backBtn.setBounds(24, 22, 108, 34);
        tabRects.clear();
        Tab[] all = Tab.values();
        int barW = Math.min(viewportWidth - 48, 900);
        int x0 = (viewportWidth - barW) / 2;
        int tabW = barW / all.length;
        int y = 70, h = 34;
        for (int i = 0; i < all.length; i++) {
            tabRects.add(new Rectangle(x0 + i * tabW, y, tabW - 6, h));
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(Graphics2D g, float alpha) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(16, 18, 30));
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        layoutChrome();
        drawHeader(g);
        drawTabs(g);

        // Scrolling content, clipped to the viewport between chrome and footer.
        int top = contentTop();
        int bottom = contentBottom();
        Shape oldClip = g.getClip();
        g.setClip(0, top, viewportWidth, bottom - top);
        hotspots.clear();
        int startY = top + 10 - scroll;
        int endY = switch (tab) {
            case OVERVIEW -> drawOverview(g, startY);
            case ECONOMY -> drawEconomy(g, startY);
            case TRAITS -> drawTraits(g, startY);
            case ELEMENTS -> drawElements(g, startY);
            case ITEMS -> drawItems(g, startY);
            case ODDS -> drawOdds(g, startY);
            case UNITS -> drawUnits(g, startY);
        };
        contentHeight = endY - startY;
        g.setClip(oldClip);

        drawScrollHint(g);
        drawFooter(g);
        if (detail != null) drawDetail(g);
    }

    private void drawHeader(Graphics2D g) {
        g.setColor(new Color(245, 246, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        drawCentered(g, "Auto Battler — Field Guide", viewportWidth / 2, 44);

        boolean hot = backBtn.contains(mouseX, mouseY);
        g.setColor(hot ? new Color(60, 70, 100) : new Color(40, 46, 66));
        g.fillRoundRect(backBtn.x, backBtn.y, backBtn.width, backBtn.height, 10, 10);
        g.setColor(new Color(150, 160, 195));
        g.drawRoundRect(backBtn.x, backBtn.y, backBtn.width, backBtn.height, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        drawCentered(g, "‹ Back", backBtn.x + backBtn.width / 2, backBtn.y + 22);
    }

    private void drawTabs(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        for (int i = 0; i < tabRects.size(); i++) {
            Rectangle r = tabRects.get(i);
            boolean active = Tab.values()[i] == tab;
            boolean hot = r.contains(mouseX, mouseY);
            g.setColor(active ? new Color(70, 90, 130)
                    : hot ? new Color(44, 50, 72) : new Color(30, 34, 52));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(active ? new Color(255, 210, 90) : new Color(60, 66, 92));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
            g.setColor(active ? Color.WHITE : new Color(170, 178, 202));
            drawCentered(g, Tab.values()[i].label, r.x + r.width / 2, r.y + 22);
        }
    }

    private void drawScrollHint(Graphics2D g) {
        int max = Math.max(0, contentHeight - (contentBottom() - contentTop() - 16));
        if (max <= 0) return;
        // A slim scroll indicator down the right edge of the viewport.
        int top = contentTop() + 6, bot = contentBottom() - 6;
        int trackH = bot - top;
        int x = viewportWidth - 12;
        g.setColor(new Color(255, 255, 255, 22));
        g.fillRoundRect(x, top, 5, trackH, 5, 5);
        int thumbH = Math.max(30, (int) (trackH * (double) trackH / (trackH + max)));
        int thumbY = top + (int) ((trackH - thumbH) * (scroll / (double) max));
        g.setColor(new Color(150, 160, 195));
        g.fillRoundRect(x, thumbY, 5, thumbH, 5, 5);
    }

    private void drawFooter(Graphics2D g) {
        g.setColor(new Color(120, 126, 148));
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        drawCentered(g, "Click any icon for details  ·  scroll or ↑/↓  ·  ←/→ switch tabs  ·  Esc back",
                viewportWidth / 2, viewportHeight - 14);
    }

    // --- content geometry helpers -------------------------------------------------

    private int contentX() {
        int w = contentWidth();
        return (viewportWidth - w) / 2;
    }

    private int contentWidth() {
        return Math.min(viewportWidth - 64, 880);
    }

    private void hot(int x, int y, int w, int h, Runnable action) {
        hotspots.add(new Hotspot(new Rectangle(x, y, w, h), action));
    }

    private boolean hovered(int x, int y, int w, int h) {
        return new Rectangle(x, y, w, h).contains(mouseX, mouseY)
                && mouseY >= contentTop() && mouseY <= contentBottom();
    }

    private int heading(Graphics2D g, int x, int y, String text) {
        g.setColor(new Color(255, 214, 100));
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString(text, x, y);
        return y + 12;
    }

    /** Draw wrapped body text; returns the y below the last line. */
    private int paragraph(Graphics2D g, int x, int y, int width, String text, Color color) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        for (String line : wrap(fm, text, width)) {
            y += 21;
            g.drawString(line, x, y);
        }
        return y;
    }

    // ------------------------------------------------------------------ OVERVIEW

    private int drawOverview(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "How Auto Battler Works");
        y = paragraph(g, x, y, w,
                "An online round-based strategy game for 2–10 players in the Auto Chess / "
                + "Teamfight Tactics tradition. Each round you spend gold to recruit units, "
                + "position them on your half of a shared 8×8 board, then watch every player's "
                + "team fight automatically. Win fights to spare your health; lose and your life "
                + "total drops. At 0 HP you are eliminated — the last commander standing wins.",
                new Color(200, 206, 224));

        // The round-loop diagram: three clickable phase nodes with arrows.
        y += 34;
        int nodeW = Math.min(210, (w - 80) / 3);
        int nodeH = 74;
        int gap = (w - nodeW * 3) / 2;
        int ny = y;
        drawPhaseNode(g, x, ny, nodeW, nodeH, "1 · PLAN",
                "Shop, level, position", new Color(90, 150, 235), this::planDetail);
        drawArrow(g, x + nodeW, ny + nodeH / 2, x + nodeW + gap);
        drawPhaseNode(g, x + nodeW + gap, ny, nodeW, nodeH, "2 · FIGHT",
                "Boards lock, auto-combat", new Color(235, 130, 90), this::fightDetail);
        drawArrow(g, x + 2 * nodeW + gap, ny + nodeH / 2, x + 2 * (nodeW + gap));
        drawPhaseNode(g, x + 2 * (nodeW + gap), ny, nodeW, nodeH, "3 · RESULTS",
                "Take damage, gain gold", new Color(120, 200, 140), this::postDetail);
        y = ny + nodeH + 6;
        g.setColor(new Color(130, 136, 158));
        g.setFont(new Font("SansSerif", Font.ITALIC, 13));
        drawCentered(g, "↻ repeats until one player remains", x + w / 2, y + 14);
        y += 30;

        y = heading(g, x, y + 18, "Key Rules");
        y += 4;
        String[] rules = {
                "2–10 players; the last one alive wins the lobby.",
                "The board is 8×8 — you command your half, rows 4–7.",
                "You may field up to [your level] units. Buy XP to level up and field more.",
                "Three copies of a unit merge into a 2★; three 2★ merge into a 3★ (much stronger).",
                "Synergies: fielding several DIFFERENT units sharing a trait buffs your whole team.",
                "Rounds 1, 5, 10, 15… are PvE 'creep' rounds — they drop item components.",
                "With an odd number of players, someone fights a 'ghost' copy of another board.",
                "Lose a fight and take damage equal to 2 + the winner's surviving unit stars.",
        };
        for (String r : rules) {
            y = bullet(g, x, y, w, r);
        }
        return y + 20;
    }

    private int bullet(Graphics2D g, int x, int y, int w, String text) {
        g.setColor(new Color(255, 214, 100));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString("•", x, y + 20);
        int ny = paragraph(g, x + 18, y, w - 18, text, new Color(198, 204, 222));
        return ny + 8;
    }

    private void drawPhaseNode(Graphics2D g, int x, int y, int w, int h,
                               String title, String sub, Color color, Runnable detail) {
        boolean hot = hovered(x, y, w, h);
        g.setColor(hot ? new Color(40, 46, 70) : new Color(28, 32, 50));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(color);
        g.setStroke(new BasicStroke(hot ? 2.5f : 1.5f));
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setStroke(new BasicStroke(1f));
        g.setColor(color);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        drawCentered(g, title, x + w / 2, y + 32);
        g.setColor(new Color(180, 186, 208));
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        drawCentered(g, sub, x + w / 2, y + 54);
        hot(x, y, w, h, detail);
    }

    private void drawArrow(Graphics2D g, int x0, int y, int x1) {
        g.setColor(new Color(120, 128, 155));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(x0 + 4, y, x1 - 4, y);
        g.fillPolygon(new int[]{x1 - 4, x1 - 12, x1 - 12},
                new int[]{y, y - 5, y + 5}, 3);
        g.setStroke(new BasicStroke(1f));
    }

    private void planDetail() {
        detail = new Detail("Planning Phase", new Color(90, 150, 235), null)
                .line("Lasts about 30 seconds. Collect income, then spend gold:")
                .blank()
                .line("• Buy units from your 5-card shop.")
                .line("• Reroll the shop for " + AutoGame.REROLL_COST + "g to see new cards.")
                .line("• Buy XP for " + AutoGame.XP_COST + "g (+" + AutoGame.XP_PER_BUY
                        + " XP) to level up.")
                .line("• Drag units onto your half of the board (rows 4–7).")
                .line("• Equip items on units — two components combine.")
                .blank()
                .line("Three copies of a unit auto-merge into a 2★, and three 2★ into a 3★.",
                        new Color(255, 214, 100));
    }

    private void fightDetail() {
        detail = new Detail("Combat Phase", new Color(235, 130, 90), null)
                .line("Lasts up to 30 seconds. Boards lock and combat is fully automatic.")
                .blank()
                .line("• You're paired against another player each round.")
                .line("• Odd player counts fight a ghost copy of a random board.")
                .line("• Rounds 1, 5, 10, 15… fight PvE creeps that drop item components.")
                .blank()
                .line("Units seek the nearest enemy, attack on their attack-speed cadence,")
                .line("build mana as they fight, and cast their class ability when it fills.");
    }

    private void postDetail() {
        detail = new Detail("Results Phase", new Color(120, 200, 140), null)
                .line("A short results phase (~4s) resolves the round:")
                .blank()
                .line("• The loser takes 2 + the winner's surviving-unit stars in damage.")
                .line("• Win and loss streaks build bonus gold (see the Economy tab).")
                .line("• Winning a creep round earns bonus gold and an item drop.")
                .line("• Any player at 0 HP is eliminated; their units return to the pool.")
                .blank()
                .line("Then the next planning phase begins.", new Color(255, 214, 100));
    }

    // ------------------------------------------------------------------ ECONOMY

    private int drawEconomy(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Gold & Economy");
        y = paragraph(g, x, y, w,
                "Gold is everything. Each planning phase (from round 2 on) pays income, and "
                + "banking gold earns interest — so there's constant tension between spending "
                + "to get strong now and saving to snowball later.",
                new Color(200, 206, 224));

        // Income formula, drawn as summed chips.
        y = heading(g, x, y + 34, "Round Income");
        y += 14;
        int[] chipW = new int[5];
        String[] chips = {"Base  5", "Interest  +1 / 10g  (max +5)",
                "Streak  +1 / +2 / +3", "Win  +1"};
        Color[] chipCol = {new Color(70, 110, 70), new Color(70, 90, 130),
                new Color(120, 90, 130), new Color(120, 110, 60)};
        int cx = x;
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < chips.length; i++) {
            int cw = fm.stringWidth(chips[i]) + 24;
            if (cx + cw > x + w) { cx = x; y += 42; }
            g.setColor(chipCol[i]);
            g.fillRoundRect(cx, y, cw, 30, 10, 10);
            g.setColor(new Color(210, 216, 232));
            g.drawRoundRect(cx, y, cw, 30, 10, 10);
            g.setColor(Color.WHITE);
            g.drawString(chips[i], cx + 12, y + 20);
            cx += cw + 12;
            if (i < chips.length - 1) {
                g.setColor(new Color(150, 156, 178));
                g.drawString("+", cx - 10, y + 20);
                cx += 6;
            }
        }
        y += 42;
        y = paragraph(g, x, y, w,
                "Streak bonus: +1 at a 2-streak, +2 at 4, +3 at 6 (win OR loss streaks count). "
                + "Interest rewards a fat wallet: every 10 gold saved adds +1 income, up to +5 "
                + "at 50 gold.", new Color(180, 186, 208));

        // Costs.
        y = heading(g, x, y + 28, "Spending");
        y += 4;
        y = bullet(g, x, y, w, "Reroll the shop: " + AutoGame.REROLL_COST + " gold.");
        y = bullet(g, x, y, w, "Buy XP: " + AutoGame.XP_COST + " gold for +"
                + AutoGame.XP_PER_BUY + " XP. You also gain +" + AutoGame.XP_PER_ROUND
                + " XP free each round.");
        y = bullet(g, x, y, w, "Sell a unit to refund its full gold value back to the pool.");

        // Level ladder table.
        y = heading(g, x, y + 24, "Levels & Board Cap");
        y += 12;
        int rowH = 26;
        int col0 = x, col1 = x + 120, col2 = x + 320;
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Level", col0, y);
        g.drawString("XP to next", col1, y);
        g.drawString("Units you can field", col2, y);
        y += 6;
        g.setColor(new Color(60, 66, 92));
        g.drawLine(x, y, x + w, y);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (int lvl = 1; lvl <= AutoPlayer.MAX_LEVEL; lvl++) {
            y += rowH;
            boolean start = lvl == new AutoGame.Config().startLevel;
            g.setColor(start ? new Color(50, 58, 84) : new Color(0, 0, 0, 0));
            if (start) g.fillRoundRect(x - 6, y - 18, w + 12, rowH - 4, 8, 8);
            g.setColor(new Color(230, 234, 246));
            g.drawString("Lv " + lvl + (start ? "  (start)" : ""), col0, y);
            String need = lvl >= AutoPlayer.MAX_LEVEL ? "— (max)"
                    : AutoPlayer.XP_TO_NEXT[lvl] + " xp";
            g.setColor(new Color(180, 186, 208));
            g.drawString(need, col1, y);
            // Fielded-unit dots.
            int dot = 10;
            for (int i = 0; i < lvl; i++) {
                g.setColor(new Color(120, 170, 255));
                g.fillOval(col2 + i * (dot + 4), y - dot, dot, dot);
            }
            g.setColor(new Color(150, 156, 178));
            g.drawString(String.valueOf(lvl), col2 + lvl * (dot + 4) + 6, y);
        }
        AutoGame.Config c = new AutoGame.Config();
        y = paragraph(g, x, y + 20, w,
                "You start at level " + c.startLevel + " with " + c.startGold + " gold and "
                + c.startHp + " HP. Your board cap equals your level, so leveling is how you "
                + "field a bigger team.", new Color(180, 186, 208));
        return y + 20;
    }

    // ------------------------------------------------------------------ TRAITS

    private int drawTraits(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Synergies (Traits)");
        y = paragraph(g, x, y, w,
                "Every unit has one Origin and one Class. Field enough DIFFERENT units sharing "
                + "a trait to activate it at a tier — the more you stack, the stronger the bonus, "
                + "for your whole team, all combat. Click a trait for its per-tier effect.",
                new Color(200, 206, 224));
        y = paragraph(g, x, y + 6, w,
                "Every synergy belongs to one or more functional categories — its role icons "
                + "sit on the right of each row. Click a category chip to browse just the "
                + "Healing, Shielding, Damage (…) synergies. Support synergies mainly power "
                + "up the rest of your team.", new Color(170, 178, 202));
        y += 16;
        y = drawCategoryFilter(g, x, y, w);
        y += 16;

        int colGap = 24;
        int colW = (w - colGap) / 2;
        int leftX = x, rightX = x + colW + colGap;

        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(new Color(150, 200, 235));
        g.drawString("Origins", leftX, y);
        g.setColor(new Color(200, 150, 120));
        g.drawString("Classes", rightX, y);
        y += 8;

        int leftY = y, rightY = y;
        for (Trait t : Trait.values()) {
            if (traitFilter != null && !t.categories.contains(traitFilter)) continue;
            if (t.kind == Trait.Kind.ORIGIN) {
                leftY = drawTraitRow(g, leftX, leftY, colW, t);
            } else {
                rightY = drawTraitRow(g, rightX, rightY, colW, t);
            }
        }
        if (leftY == y && rightY == y) {
            g.setColor(new Color(140, 146, 168));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("No synergies in this category yet.", x, y + 24);
            return y + 48;
        }
        return Math.max(leftY, rightY) + 20;
    }

    /** The clickable category chip row ("All" plus one chip per category). */
    private int drawCategoryFilter(Graphics2D g, int x, int y, int w) {
        int chipH = 24;
        int cx = x;
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();

        int allW = fm.stringWidth("All") + 20;
        if (drawChip(g, cx, y, allW, chipH, "All", null, traitFilter == null)) {
            hot(cx, y, allW, chipH, () -> traitFilter = null);
        }
        cx += allW + 8;
        for (SynergyCategory cat : SynergyCategory.values()) {
            int cw = fm.stringWidth(cat.label) + 20 + 16;
            if (cx + cw > x + w) {
                cx = x;
                y += chipH + 8;
            }
            final SynergyCategory picked = cat;
            if (drawChip(g, cx, y, cw, chipH, cat.label, cat, traitFilter == cat)) {
                hot(cx, y, cw, chipH,
                        () -> traitFilter = traitFilter == picked ? null : picked);
            }
            cx += cw + 8;
        }
        return y + chipH + 4;
    }

    /** One filter chip; returns true so callers can register its hotspot. */
    private boolean drawChip(Graphics2D g, int x, int y, int w, int h, String label,
                             SynergyCategory cat, boolean active) {
        boolean hot = hovered(x, y, w, h);
        g.setColor(active ? new Color(70, 82, 118) : hot ? new Color(44, 50, 74)
                : new Color(30, 34, 52));
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(active ? new Color(150, 170, 220) : new Color(56, 62, 88));
        g.drawRoundRect(x, y, w, h, 12, 12);
        int tx = x + 10;
        if (cat != null) {
            g.drawImage(AutoSprites.categoryIcon(cat, 14), x + 8, y + h / 2 - 7, null);
            tx = x + 26;
        }
        g.setColor(active ? new Color(235, 238, 250) : new Color(180, 186, 208));
        g.drawString(label, tx, y + h / 2 + 4);
        return true;
    }

    private int drawTraitRow(Graphics2D g, int x, int y, int w, Trait t) {
        int h = 42;
        boolean hot = hovered(x, y, w, h);
        g.setColor(hot ? new Color(40, 46, 70) : new Color(28, 32, 50));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(56, 62, 88));
        g.drawRoundRect(x, y, w, h, 10, 10);

        // Trait dot.
        g.setColor(t.color);
        g.fillOval(x + 12, y + h / 2 - 9, 18, 18);
        g.setColor(t.color.darker());
        g.drawOval(x + 12, y + h / 2 - 9, 18, 18);

        g.setColor(new Color(232, 236, 248));
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(t.label, x + 40, y + h / 2 - 2);

        // Threshold pips ("2 / 4 / 6").
        StringBuilder marks = new StringBuilder();
        for (int th : t.thresholds) {
            if (marks.length() > 0) marks.append(" / ");
            marks.append(th);
        }
        g.setColor(new Color(150, 156, 178));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("activates at " + marks, x + 40, y + h / 2 + 14);

        int count = countUnitsWithTrait(t);
        g.setColor(new Color(120, 126, 148));
        String tag = count + " unit" + (count == 1 ? "" : "s");
        g.drawString(tag, x + w - g.getFontMetrics().stringWidth(tag) - 12, y + h / 2 + 14);

        // Role icons, top-right of the row.
        int icon = 13;
        int ix = x + w - icon - 10;
        for (int ci = t.categories.size() - 1; ci >= 0; ci--) {
            g.drawImage(AutoSprites.categoryIcon(t.categories.get(ci), icon),
                    ix - ci * (icon + 3), y + 7, null);
        }

        hot(x, y, w, h, () -> traitDetail(t));
        return y + h + 8;
    }

    private void traitDetail(Trait t) {
        Detail d = new Detail(t.label + "  ("
                + (t.kind == Trait.Kind.ORIGIN ? "Origin" : "Class") + ")", t.color, null);
        d.line(t.description, new Color(210, 216, 232));
        d.blank();
        for (int i = 0; i < t.thresholds.length; i++) {
            d.line("◆ " + t.thresholds[i] + " units  —  tier " + (i + 1), t.color);
        }
        d.blank();
        List<String> cats = new ArrayList<>();
        for (SynergyCategory cat : t.categories) cats.add(cat.label);
        d.line("Role: " + String.join(" · ", cats), new Color(180, 186, 208));
        if (t.isSupport()) {
            d.line("Support synergy — enhances the rest of your team.",
                    SynergyCategory.SUPPORT.color);
        }
        d.blank();
        List<String> members = new ArrayList<>();
        for (UnitDef def : AutoUnits.roster()) {
            if (def.origin == t || def.clazz == t) members.add(def.name);
        }
        d.line("Units: " + String.join(", ", members), new Color(170, 178, 202));
        detail = d;
    }

    private int countUnitsWithTrait(Trait t) {
        int n = 0;
        for (UnitDef def : AutoUnits.roster()) {
            if (def.origin == t || def.clazz == t) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ ELEMENTS

    private int drawElements(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Elemental Damage");
        y = paragraph(g, x, y, w,
                "Elements are a second strategic layer on top of synergies — they never replace "
                + "them. A unit can attack with up to two elements, resist up to two, and be "
                + "weak to up to two; plenty of units carry none, and plain damage is always "
                + "fine. Damage into a weakness is amplified, damage into a resistance is "
                + "dampened. Click an element for who uses and fears it.",
                new Color(200, 206, 224));
        int r10 = (int) Math.round(30 * BattleSim.elementIntensity(10));
        int r20 = (int) Math.round(30 * BattleSim.elementIntensity(20));
        y = paragraph(g, x, y + 6, w,
                "The swing GROWS round over round (about +" + r10 + "% into a weakness by "
                + "round 10, +" + r20 + "% by round 20), so scout your opponents and adapt: "
                + "late-game, countering the lobby's elements matters as much as your own "
                + "synergy bonuses.", new Color(170, 178, 202));
        y += 20;

        for (Element e : Element.values()) {
            int h = 46;
            boolean hot = hovered(x, y, w, h);
            g.setColor(hot ? new Color(40, 46, 70) : new Color(28, 32, 50));
            g.fillRoundRect(x, y, w, h, 10, 10);
            g.setColor(new Color(56, 62, 88));
            g.drawRoundRect(x, y, w, h, 10, 10);

            // Element diamond.
            int d = 18;
            int dx = x + 14, dy = y + h / 2 - d / 2;
            g.setColor(e.color);
            g.fillPolygon(new int[]{dx + d / 2, dx + d, dx + d / 2, dx},
                    new int[]{dy, dy + d / 2, dy + d, dy + d / 2}, 4);

            g.setColor(new Color(232, 236, 248));
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(e.label, x + 44, y + h / 2 - 2);
            g.setColor(new Color(150, 156, 178));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString(trim(g, e.description, w - 200), x + 44, y + h / 2 + 14);

            int users = countElementUsers(e);
            g.setColor(new Color(120, 126, 148));
            String tag = users + " attacker" + (users == 1 ? "" : "s");
            g.drawString(tag, x + w - g.getFontMetrics().stringWidth(tag) - 12,
                    y + h / 2 + 4);

            final Element fixed = e;
            hot(x, y, w, h, () -> elementDetail(fixed));
            y += h + 8;
        }

        y = heading(g, x, y + 16, "Adapting Your Build");
        y = paragraph(g, x, y, w,
                "Elemental relics drop from creep rounds (see the Items tab): infusion charms "
                + "add an element to any unit's attacks, the Radiation Core converts a unit to "
                + "pure Radiation, and the Prism Ward grants resistance to everything. Use them "
                + "to counter what your opponents are building without giving up your synergies.",
                new Color(180, 186, 208));
        return y + 24;
    }

    private int countElementUsers(Element e) {
        int n = 0;
        for (UnitDef def : AutoUnits.roster()) {
            if (def.attackElements.contains(e)) n++;
        }
        return n;
    }

    private void elementDetail(Element e) {
        Detail d = new Detail(e.label, e.color, null);
        d.line(e.description, new Color(210, 216, 232));
        d.blank();
        List<String> attackers = new ArrayList<>();
        List<String> resisters = new ArrayList<>();
        List<String> weaklings = new ArrayList<>();
        for (UnitDef def : AutoUnits.roster()) {
            if (def.attackElements.contains(e)) attackers.add(def.name);
            if (def.resistances.contains(e)) resisters.add(def.name);
            if (def.weaknesses.contains(e)) weaklings.add(def.name);
        }
        d.line("Attacks with it: " + (attackers.isEmpty() ? "nobody (relics only)"
                : String.join(", ", attackers)), e.color);
        d.blank();
        d.line("Resists it: " + (resisters.isEmpty() ? "nobody"
                : String.join(", ", resisters)), new Color(150, 200, 170));
        d.blank();
        d.line("Weak to it: " + (weaklings.isEmpty() ? "nobody"
                : String.join(", ", weaklings)), new Color(235, 150, 130));
        detail = d;
    }

    // ------------------------------------------------------------------ ITEMS

    private int drawItems(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Items");
        y = paragraph(g, x, y, w,
                "Item components drop from PvE creep rounds. Equip two components on the same "
                + "unit and they combine automatically into a powerful finished item. Click any "
                + "gem for its stats and recipe.", new Color(200, 206, 224));

        // Components row.
        y = heading(g, x, y + 26, "Components");
        y += 14;
        List<String> comps = AutoItems.componentKeys();
        int gem = 34;
        int cellW = (w) / comps.size();
        for (int i = 0; i < comps.size(); i++) {
            AutoItem it = AutoItems.get(comps.get(i));
            int gx = x + i * cellW;
            drawItemGem(g, it, gx, y, gem);
            g.setColor(new Color(226, 230, 244));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString(it.name, gx + gem + 8, y + 15);
            g.setColor(new Color(160, 168, 190));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(it.statLine(), gx + gem + 8, y + 31);
            hot(gx, y, cellW - 6, gem, () -> itemDetail(it));
        }
        y += gem + 20;

        // Combination matrix (5×5): row + column headers are components,
        // each cell the combined item they make.
        y = heading(g, x, y + 8, "Recipe Grid");
        y += 14;
        int n = comps.size();
        int head = 40;
        int cell = Math.min(64, (w - head) / n);
        int gridX = x + head;
        int gridY = y + head;

        // Column header gems.
        for (int c = 0; c < n; c++) {
            AutoItem it = AutoItems.get(comps.get(c));
            drawItemGem(g, it, gridX + c * cell + cell / 2 - 14, y + 4, 28);
        }
        // Row header gems + cells.
        for (int r = 0; r < n; r++) {
            AutoItem rowIt = AutoItems.get(comps.get(r));
            drawItemGem(g, rowIt, x + 4, gridY + r * cell + cell / 2 - 14, 28);
            for (int c = 0; c < n; c++) {
                String combKey = AutoItems.combine(comps.get(r), comps.get(c));
                AutoItem comb = combKey == null ? null : AutoItems.get(combKey);
                int bx = gridX + c * cell, by = gridY + r * cell;
                boolean hot = hovered(bx, by, cell - 3, cell - 3);
                g.setColor(hot ? new Color(44, 50, 74) : new Color(26, 30, 46));
                g.fillRoundRect(bx, by, cell - 3, cell - 3, 8, 8);
                g.setColor(new Color(50, 56, 80));
                g.drawRoundRect(bx, by, cell - 3, cell - 3, 8, 8);
                if (comb != null) {
                    drawItemGem(g, comb, bx + cell / 2 - 15, by + cell / 2 - 17, 28);
                    AutoItem fixed = comb;
                    hot(bx, by, cell - 3, cell - 3, () -> itemDetail(fixed));
                }
            }
        }
        y = gridY + n * cell + 8;
        y = paragraph(g, x, y, w,
                "The grid is symmetric — order doesn't matter. Two of the same component make "
                + "the strongest single-stat items (e.g. two swords → Deathblade).",
                new Color(170, 178, 202));

        // Elemental relics.
        y = heading(g, x, y + 18, "Elemental Relics");
        y = paragraph(g, x, y, w,
                "Relics also drop from creep rounds (less often than components). They carry "
                + "no stats — instead they rewire a unit's elemental affinity, and they never "
                + "combine. See the Elements tab for the damage rules.",
                new Color(200, 206, 224));
        y += 14;
        List<String> relics = AutoItems.relicKeys();
        int relicCellW = Math.max(150, w / Math.min(4, relics.size()));
        int rx = x, gem2 = 30;
        for (String key : relics) {
            AutoItem it = AutoItems.get(key);
            if (rx + relicCellW > x + w + 4) {
                rx = x;
                y += gem2 + 18;
            }
            drawItemGem(g, it, rx, y, gem2);
            g.setColor(new Color(226, 230, 244));
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.drawString(it.name, rx + gem2 + 8, y + 13);
            g.setColor(new Color(160, 168, 190));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(trim(g, it.statLine(), relicCellW - gem2 - 16),
                    rx + gem2 + 8, y + 27);
            AutoItem fixed = it;
            hot(rx, y, relicCellW - 6, gem2, () -> itemDetail(fixed));
            rx += relicCellW;
        }
        y += gem2 + 8;
        return y + 20;
    }

    private void drawItemGem(Graphics2D g, AutoItem it, int x, int y, int size) {
        g.drawImage(AutoSprites.item(it, size), x, y, null);
    }

    private void itemDetail(AutoItem it) {
        Detail d = new Detail(it.name, it.color, AutoSprites.item(it, 48));
        d.line(it.isComponent() ? "Component (drops from creep rounds)"
                : it.isRelic() ? "Elemental relic (rarer creep-round drop)"
                : "Combined item", new Color(180, 186, 208));
        d.blank();
        d.line((it.isRelic() ? "Effect: " : "Stats: ")
                        + (it.statLine().isEmpty() ? "—" : it.statLine()),
                new Color(210, 216, 232));
        if (it.isRelic()) {
            d.blank();
            d.line("Relics never combine — they rewire elemental affinity instead.",
                    new Color(160, 168, 190));
        } else if (!it.isComponent()) {
            AutoItem a = AutoItems.get(it.partA);
            AutoItem b = AutoItems.get(it.partB);
            d.blank();
            d.line("Recipe: " + (a == null ? it.partA : a.name) + "  +  "
                    + (b == null ? it.partB : b.name), new Color(255, 214, 100));
        } else {
            d.blank();
            d.line("Equip two components on one unit to combine them.",
                    new Color(160, 168, 190));
        }
        detail = d;
    }

    // ------------------------------------------------------------------ ODDS

    private int drawOdds(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Shop Odds & Rarity");
        y = paragraph(g, x, y, w,
                "Units come in five cost tiers — pricier units are rarer and stronger. Your "
                + "player level shifts the odds of each tier appearing in your shop. Click a "
                + "cell to read it out.", new Color(200, 206, 224));

        // Cost legend.
        y += 20;
        int cx = x;
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        for (int cost = 1; cost <= 5; cost++) {
            String lbl = cost + "g · " + COST_NAMES[cost - 1];
            int cw = g.getFontMetrics().stringWidth(lbl) + 30;
            if (cx + cw > x + w) { cx = x; y += 30; }
            g.setColor(COST_COLORS[cost - 1]);
            g.fillOval(cx, y, 14, 14);
            g.setColor(new Color(210, 216, 232));
            g.drawString(lbl, cx + 20, y + 12);
            cx += cw + 10;
        }
        y += 34;

        // Odds grid: rows = level, cols = cost.
        int labelW = 70;
        int cell = Math.min(120, (w - labelW) / 5);
        int rowH = 30;
        int gx = x + labelW;
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Level", x, y + 14);
        for (int cost = 1; cost <= 5; cost++) {
            g.setColor(COST_COLORS[cost - 1]);
            drawCentered(g, cost + "g", gx + (cost - 1) * cell + cell / 2, y + 14);
        }
        y += 22;
        for (int lvl = 1; lvl <= AutoUnits.SHOP_ODDS.length; lvl++) {
            int[] row = AutoUnits.SHOP_ODDS[lvl - 1];
            g.setColor(new Color(210, 216, 232));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString("Lv " + lvl, x, y + 20);
            for (int cost = 1; cost <= 5; cost++) {
                int pct = row[cost - 1];
                int bx = gx + (cost - 1) * cell, by = y;
                Color base = COST_COLORS[cost - 1];
                // Tint the cell by the probability.
                int a = (int) (25 + 190 * (pct / 100.0));
                boolean hot = hovered(bx, by, cell - 3, rowH - 3);
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                        pct == 0 ? 12 : a));
                g.fillRoundRect(bx, by, cell - 3, rowH - 3, 6, 6);
                if (hot) {
                    g.setColor(new Color(255, 255, 255, 120));
                    g.drawRoundRect(bx, by, cell - 3, rowH - 3, 6, 6);
                }
                g.setColor(pct == 0 ? new Color(90, 96, 116) : new Color(20, 22, 34));
                g.setFont(new Font("SansSerif", Font.BOLD, 13));
                drawCentered(g, pct + "%", bx + (cell - 3) / 2, by + 20);
                int fl = lvl, fc = cost, fp = pct;
                hot(bx, by, cell - 3, rowH - 3, () -> oddsDetail(fl, fc, fp));
            }
            y += rowH;
        }

        // Pool copies.
        y = heading(g, x, y + 22, "Shared Unit Pool");
        y += 14;
        y = paragraph(g, x, y, w,
                "All players draw from one shared pool, so contested units dry up. Copies of "
                + "each unit that exist per cost tier:", new Color(180, 186, 208));
        y += 14;
        int px = x;
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        for (int cost = 1; cost <= 5; cost++) {
            String lbl = cost + "g:  " + AutoUnits.POOL_COPIES[cost - 1] + " each";
            int pw = g.getFontMetrics().stringWidth(lbl) + 24;
            if (px + pw > x + w) { px = x; y += 40; }
            Color base = COST_COLORS[cost - 1];
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 60));
            g.fillRoundRect(px, y, pw, 30, 10, 10);
            g.setColor(base);
            g.drawRoundRect(px, y, pw, 30, 10, 10);
            g.setColor(new Color(230, 234, 246));
            g.drawString(lbl, px + 12, y + 20);
            px += pw + 12;
        }
        return y + 44;
    }

    private void oddsDetail(int level, int cost, int pct) {
        detail = new Detail("Level " + level + "  ·  " + cost + "g "
                + COST_NAMES[cost - 1], COST_COLORS[cost - 1], null)
                .line("At level " + level + ", each shop card has a " + pct + "% chance")
                .line("of being a " + cost + "-gold (" + COST_NAMES[cost - 1] + ") unit.")
                .blank()
                .line(pct == 0 ? "This tier can't appear yet — level up to unlock it."
                        : "There are " + AutoUnits.POOL_COPIES[cost - 1]
                        + " copies of each such unit in the shared pool.",
                        new Color(180, 186, 208));
    }

    // ------------------------------------------------------------------ UNITS

    private int drawUnits(Graphics2D g, int y) {
        int x = contentX(), w = contentWidth();
        y = heading(g, x, y + 12, "Units");
        y = paragraph(g, x, y, w,
                "The full roster, grouped by cost. Each card shows a unit's base (1★) stats. "
                + "Click a card for star-scaled stats and its ability.", new Color(200, 206, 224));
        y += 16;

        int cardW = 250, cardH = 92, gap = 12;
        int cols = Math.max(1, (w + gap) / (cardW + gap));
        cardW = (w - (cols - 1) * gap) / cols;

        for (int cost = 1; cost <= 5; cost++) {
            List<UnitDef> tier = AutoUnits.byCost(cost);
            if (tier.isEmpty()) continue;
            g.setColor(COST_COLORS[cost - 1]);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.drawString(cost + "g · " + COST_NAMES[cost - 1] + "  (" + tier.size() + ")", x, y + 16);
            y += 26;
            y = drawUnitGrid(g, x, y, cardW, cardH, gap, cols, tier);
            y += 16;
        }

        // Creeps.
        List<UnitDef> creeps = AutoUnits.byCost(0);
        if (!creeps.isEmpty()) {
            g.setColor(new Color(180, 150, 120));
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.drawString("PvE Creeps  (" + creeps.size() + ")", x, y + 16);
            y += 26;
            y = drawUnitGrid(g, x, y, cardW, cardH, gap, cols, creeps);
        }
        return y + 24;
    }

    private int drawUnitGrid(Graphics2D g, int x, int y, int cardW, int cardH,
                             int gap, int cols, List<UnitDef> units) {
        for (int i = 0; i < units.size(); i++) {
            int col = i % cols;
            int cx = x + col * (cardW + gap);
            if (col == 0 && i > 0) y += cardH + gap;
            drawUnitCard(g, cx, y, cardW, cardH, units.get(i));
        }
        return y + cardH;
    }

    private void drawUnitCard(Graphics2D g, int x, int y, int w, int h, UnitDef def) {
        boolean creep = def.isCreep();
        boolean hot = hovered(x, y, w, h);
        Color edge = creep ? new Color(150, 130, 110) : COST_COLORS[def.cost - 1];
        g.setColor(hot ? new Color(38, 44, 66) : new Color(26, 30, 46));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(edge);
        g.setStroke(new BasicStroke(hot ? 2f : 1.3f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setStroke(new BasicStroke(1f));

        int sprite = 52;
        g.drawImage(AutoSprites.unit(def, sprite, true), x + 6, y + h / 2 - sprite / 2 - 4, null);

        int tx = x + sprite + 14;
        g.setColor(new Color(232, 236, 248));
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString(trim(g, def.name, w - sprite - 46), tx, y + 20);
        if (!creep) {
            g.setColor(new Color(255, 214, 100));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            String costStr = def.cost + "g";
            g.drawString(costStr, x + w - g.getFontMetrics().stringWidth(costStr) - 10, y + 20);

            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(def.origin.color);
            g.drawString(def.origin.label, tx, y + 38);
            g.setColor(def.clazz.color);
            g.drawString(def.clazz.label, tx, y + 54);
            if (!def.attackElements.isEmpty()) {
                AutoSprites.drawElementPips(g, def.attackElements,
                        x + w - 8 - def.attackElements.size() * 6, y + 32, 9);
            }
        } else {
            g.setColor(new Color(160, 168, 190));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("PvE creep", tx, y + 38);
        }

        g.setColor(new Color(170, 178, 200));
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString((int) def.hp + " HP · " + (int) def.ad + " AD · " + def.attackSpeed + "/s",
                tx, y + h - 20);
        g.drawString("rng " + (int) def.range + " · " + (int) def.armor + " armor"
                + (def.manaMax > 0 ? " · " + (int) def.manaMax + " mana" : ""), tx, y + h - 6);

        hot(x, y, w, h, () -> unitDetail(def));
    }

    private void unitDetail(UnitDef def) {
        String star = def.isCreep() ? "" : "  ·  " + def.cost + "g";
        Color titleColor = def.isCreep() ? new Color(180, 150, 120)
                : COST_COLORS[def.cost - 1];
        Detail d = new Detail(def.name + star, titleColor, AutoSprites.unit(def, 48, true));
        if (!def.isCreep()) {
            d.line(def.origin.label + " · " + def.clazz.label + "  ("
                    + COST_NAMES[def.cost - 1] + ")", new Color(180, 186, 208));
        } else {
            d.line("PvE creep — appears in creep rounds, never in shops",
                    new Color(180, 186, 208));
        }
        d.blank();
        double m2 = UnitDef.starMultiplier(2), m3 = UnitDef.starMultiplier(3);
        d.line("HP:  " + (int) def.hp + "  →  " + (int) (def.hp * m2) + " (2★)  →  "
                + (int) (def.hp * m3) + " (3★)", new Color(120, 210, 130));
        d.line("Attack Damage:  " + (int) def.ad + "  →  " + (int) (def.ad * m2)
                + " (2★)  →  " + (int) (def.ad * m3) + " (3★)", new Color(235, 150, 120));
        d.line("Attack Speed: " + def.attackSpeed + "/s   ·   Range: " + (int) def.range
                + (def.range <= 1.5 ? " (melee)" : " (ranged)"), new Color(200, 206, 224));
        d.line("Armor: " + (int) def.armor
                + (def.manaMax > 0 ? "   ·   Mana: " + (int) def.manaMax : ""),
                new Color(200, 206, 224));
        if (!def.attackElements.isEmpty() || !def.resistances.isEmpty()
                || !def.weaknesses.isEmpty()) {
            d.blank();
            if (!def.attackElements.isEmpty()) {
                d.line("Attacks with: " + elementNames(def.attackElements),
                        def.attackElements.get(0).color);
            }
            if (!def.resistances.isEmpty()) {
                d.line("Resists: " + elementNames(def.resistances),
                        new Color(150, 200, 170));
            }
            if (!def.weaknesses.isEmpty()) {
                d.line("Weak to: " + elementNames(def.weaknesses),
                        new Color(235, 150, 130));
            }
        }
        if (!def.isCreep() && def.clazz != null) {
            d.blank();
            d.line("Ability (" + def.clazz.label + "):", new Color(255, 214, 100));
            d.line(abilityText(def.clazz), new Color(200, 206, 224));
            d.blank();
            d.line("Pool: " + AutoUnits.POOL_COPIES[def.cost - 1]
                    + " copies in the shared pool.", new Color(160, 168, 190));
        }
        detail = d;
    }

    private static String elementNames(List<Element> elements) {
        StringBuilder sb = new StringBuilder();
        for (Element e : elements) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.label);
        }
        return sb.toString();
    }

    private static String abilityText(Trait clazz) {
        return switch (clazz) {
            case WARRIOR -> "Empowered slash — a heavy bonus-damage strike on its target.";
            case ARCHER -> "Piercing volley — 150% attack damage plus ability power.";
            case MAGE -> "Fireball — big damage to the target and splash around it.";
            case ASSASSIN -> "Shadow strike — armor-ignoring burst; leaps to the enemy "
                    + "backline at the start of combat.";
            case GUARDIAN -> "Bulwark — shields and heals itself.";
            case HEALER -> "Mend — heals the lowest-health ally (boosted by the Healer trait).";
            case BRAWLER -> "Slam — damages the target and heals itself.";
            default -> "Attacks its nearest enemy.";
        };
    }

    // ------------------------------------------------------------------ DETAIL CARD

    private void drawDetail(Graphics2D g) {
        // Dim the scene behind the modal.
        g.setColor(new Color(8, 9, 16, 200));
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        int cardW = Math.min(520, viewportWidth - 80);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        FontMetrics fm = g.getFontMetrics();
        int textW = cardW - 40;

        // Pre-wrap all lines to compute the card height.
        List<String> flat = new ArrayList<>();
        List<Color> flatColor = new ArrayList<>();
        for (int i = 0; i < detail.lines.size(); i++) {
            String ln = detail.lines.get(i);
            if (ln.isEmpty()) {
                flat.add("");
                flatColor.add(Color.WHITE);
                continue;
            }
            for (String piece : wrap(fm, ln, textW)) {
                flat.add(piece);
                flatColor.add(detail.colors.get(i));
            }
        }

        int headH = detail.icon != null ? 60 : 44;
        int cardH = headH + flat.size() * 20 + 42;
        int cardX = (viewportWidth - cardW) / 2;
        int cardY = Math.max(40, (viewportHeight - cardH) / 2);

        g.setColor(new Color(20, 23, 38, 250));
        g.fillRoundRect(cardX, cardY, cardW, cardH, 16, 16);
        g.setColor(detail.titleColor);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(cardX, cardY, cardW, cardH, 16, 16);
        g.setStroke(new BasicStroke(1f));

        int tx = cardX + 20;
        int ty = cardY + 30;
        if (detail.icon != null) {
            g.drawImage(detail.icon, cardX + 18, cardY + 12, null);
            tx = cardX + 18 + detail.icon.getWidth() + 12;
        }
        g.setColor(detail.titleColor);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString(detail.title, tx, ty);

        int y = cardY + headH + 8;
        for (int i = 0; i < flat.size(); i++) {
            y += 20;
            if (flat.get(i).isEmpty()) continue;
            g.setColor(flatColor.get(i));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString(flat.get(i), cardX + 20, y);
        }

        g.setColor(new Color(130, 136, 158));
        g.setFont(new Font("SansSerif", Font.ITALIC, 12));
        drawCentered(g, "click anywhere to close", cardX + cardW / 2, cardY + cardH - 13);
    }

    // ------------------------------------------------------------------ util

    private void drawCentered(Graphics2D g, String s, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }

    private static List<String> wrap(FontMetrics fm, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(trial) > maxWidth && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String trim(Graphics2D g, String s, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxWidth) return s;
        String out = s;
        while (out.length() > 1 && fm.stringWidth(out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
