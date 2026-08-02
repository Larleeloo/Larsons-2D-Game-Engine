package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.evolution.Ability;
import com.larsons.engine.evolution.Achievement;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.History;
import com.larsons.engine.evolution.Phenotype;
import com.larsons.engine.evolution.SpeciesRecord;
import com.larsons.engine.evolution.Trait;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * The reference book, in two distinct sections plus the achievement wall.
 *
 * <ul>
 *   <li><b>Game Catalog</b> — what the <em>current</em> game has discovered.
 *       Resetting the game empties this, because a reset is a real restart with
 *       everything to find again.</li>
 *   <li><b>History</b> — every organism this player has <em>ever</em>
 *       discovered, in any game, with the lifetime totals above it. Nothing is
 *       ever removed from here: a reset costs you the lab, never the
 *       collection.</li>
 * </ul>
 *
 * <p>Nothing in here ships with the game. Both sections contain exactly what
 * the simulation has already created — which is why they are worth filling in,
 * and why two players' books never look the same.
 *
 * <p>Sorting is by discovery time, complexity, or strand length; the detail
 * pane decodes whichever entry is selected all the way back down to its traits
 * and abilities.
 */
public class EvolutionCatalogScene extends AbstractScene {

    private static final Color BG = new Color(10, 13, 19);
    private static final Color PANEL = new Color(20, 24, 34);
    private static final Color PANEL_EDGE = new Color(70, 82, 104);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color TEXT_DIM = new Color(142, 154, 176);
    private static final Color GOLD = new Color(240, 208, 110);
    private static final Color GOOD = new Color(140, 220, 150);
    private static final Color TAB_ACTIVE = new Color(52, 64, 88);
    private static final Color TAB_HOVER = new Color(38, 46, 62);
    private static final Color ROW_SELECTED = new Color(46, 58, 80);
    private static final Color ROW_NAME = new Color(186, 196, 214);
    private static final Color MUTED = new Color(120, 128, 146);
    private static final Color BAR_TRACK = new Color(40, 48, 64);
    private static final Color UNLOCKED_FILL = new Color(30, 40, 34);
    private static final Color UNLOCKED_EDGE = new Color(110, 170, 120);
    private static final Color UNLOCKED_HINT = new Color(96, 132, 104);
    private static final Color LOCKED_HINT = new Color(96, 102, 118);

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 26);
    private static final Font BODY_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font TINY_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font STAT_FONT = new Font("SansSerif", Font.BOLD, 17);
    private static final Font EMPTY_FONT = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font DETAIL_TITLE_FONT = new Font("SansSerif", Font.BOLD, 20);
    private static final Font SEQUENCE_FONT = new Font("Monospaced", Font.PLAIN, 13);
    private static final Font CARD_TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);

    /** How the shelf is ordered. */
    private enum Sort {
        NEWEST("newest first"), COMPLEXITY("most complex"), LENGTH("longest strand");

        final String label;

        Sort(String label) { this.label = label; }
    }

    /** Which page of the book is open. */
    private enum Tab { CATALOG, HISTORY, ACHIEVEMENTS }

    private final GameContext ctx;
    private final EvolutionStore store;

    /** Every organism ever discovered — the user's permanent history. */
    private final List<SpeciesRecord> allTime = new ArrayList<>();
    /** What the current game has discovered, a subset of the history. */
    private final List<SpeciesRecord> thisGame = new ArrayList<>();
    /** The permanent record, read once when the book opens rather than per frame. */
    private History history = new History();
    private Sort sort = Sort.NEWEST;
    private Tab tab = Tab.CATALOG;
    /** Selection and scroll are per page, so switching tabs does not lose your place. */
    private int selectedCatalog, selectedHistory;
    private int scroll;
    private int mouseX, mouseY;

    private final List<Rectangle> rowRects = new ArrayList<>();
    private final Rectangle catalogTab = new Rectangle();
    private final Rectangle historyTab = new Rectangle();
    private final Rectangle achievementsTab = new Rectangle();
    private final Rectangle sortButton = new Rectangle();
    private final Rectangle backButton = new Rectangle();

    private static final SimpleDateFormat WHEN = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    public EvolutionCatalogScene(GameContext ctx) {
        this(ctx, new EvolutionStore());
    }

    public EvolutionCatalogScene(GameContext ctx, EvolutionStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public void onEnter() {
        ctx.lighting().setDarkness(0);
        ctx.applyLiveSettings();

        // The history is the whole collection, read off disk; the game catalog
        // is whichever of those the running game has found for itself.
        history = store.loadHistory();
        allTime.clear();
        allTime.addAll(history.allSpecies());

        thisGame.clear();
        if (scenes != null && scenes.get("evolution") instanceof EvolutionScene scene
                && scene.game() != null) {
            thisGame.addAll(scene.game().catalog().allSpecies());
        } else {
            EvolutionGame saved = store.load();
            if (saved != null) thisGame.addAll(saved.catalog().allSpecies());
        }

        applySort();
        selectedCatalog = 0;
        selectedHistory = 0;
        scroll = 0;
    }

    private void applySort() {
        sort(allTime);
        sort(thisGame);
    }

    private void sort(List<SpeciesRecord> list) {
        switch (sort) {
            case NEWEST -> list.sort(
                    Comparator.comparingLong((SpeciesRecord r) -> r.discoveredAt).reversed());
            case COMPLEXITY -> list.sort(Comparator
                    .comparingInt((SpeciesRecord r) -> r.phenotype().complexity()).reversed());
            case LENGTH -> list.sort(
                    Comparator.comparingInt((SpeciesRecord r) -> r.sequence.length()).reversed());
        }
    }

    /** The list the open page is showing. */
    private List<SpeciesRecord> visibleList() {
        return tab == Tab.HISTORY ? allTime : thisGame;
    }

    private int selectedIndex() {
        return tab == Tab.HISTORY ? selectedHistory : selectedCatalog;
    }

    private void setSelectedIndex(int i) {
        if (tab == Tab.HISTORY) selectedHistory = i;
        else selectedCatalog = i;
    }

    @Override
    public void update(double dt, InputManager input) {
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();

        if (KeyBinds.pressed(input, GameAction.MENU_BACK) || input.isKeyJustPressed(KeyEvent.VK_K)) {
            leave();
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_TAB)) {
            Tab[] pages = Tab.values();
            tab = pages[(tab.ordinal() + 1) % pages.length];
            scroll = 0;
        }

        int wheel = input.getWheelRotation();
        if (wheel != 0) scroll = Math.max(0, scroll + wheel * 3);
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) moveSelection(1);
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) moveSelection(-1);

        if (input.isMouseJustPressed()) {
            if (backButton.contains(mouseX, mouseY)) {
                leave();
                return;
            }
            if (catalogTab.contains(mouseX, mouseY)) tab = Tab.CATALOG;
            if (historyTab.contains(mouseX, mouseY)) tab = Tab.HISTORY;
            if (achievementsTab.contains(mouseX, mouseY)) tab = Tab.ACHIEVEMENTS;
            if (sortButton.contains(mouseX, mouseY)) {
                Sort[] all = Sort.values();
                sort = all[(sort.ordinal() + 1) % all.length];
                applySort();
                setSelectedIndex(0);
                scroll = 0;
            }
            for (int i = 0; i < rowRects.size(); i++) {
                if (rowRects.get(i).contains(mouseX, mouseY)) {
                    setSelectedIndex(scroll + i);
                    break;
                }
            }
        }
    }

    private void moveSelection(int delta) {
        List<SpeciesRecord> list = visibleList();
        if (list.isEmpty()) return;
        int next = Math.max(0, Math.min(list.size() - 1, selectedIndex() + delta));
        setSelectedIndex(next);
        if (next < scroll) scroll = next;
    }

    /** Back to wherever makes sense: the dish if one is running, else the lobby. */
    private void leave() {
        if (scenes.get("evolution") instanceof EvolutionScene scene && scene.game() != null) {
            scenes.transitionTo("evolution");
        } else {
            scenes.transitionTo("evolutionlobby");
        }
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, BG);

        target.drawText("Reference Book", 28, 42, TITLE_FONT, TEXT);
        String subtitle = switch (tab) {
            case CATALOG -> thisGame.size() + " discovered in this game — a reset starts "
                    + "this page over";
            case HISTORY -> allTime.size() + " organisms discovered across "
                    + history.gamesPlayed() + (history.gamesPlayed() == 1 ? " game" : " games")
                    + " — kept forever";
            case ACHIEVEMENTS -> "Permanent progress: a reset never takes one back";
        };
        target.drawText(subtitle, 28, 64, BODY_FONT, TEXT_DIM);

        drawChrome(target);
        switch (tab) {
            case CATALOG, HISTORY -> {
                if (tab == Tab.HISTORY) drawLifetimeStrip(target);
                drawSpeciesList(target);
                drawDetail(target);
            }
            case ACHIEVEMENTS -> drawAchievements(target);
        }

        target.drawText("Tab switches page · arrows and wheel scroll · Esc goes back",
                28, viewportHeight - 18, SMALL_FONT, TEXT_DIM);
    }

    private void drawChrome(DrawTarget target) {
        catalogTab.setBounds(viewportWidth - 570, 24, 132, 28);
        historyTab.setBounds(viewportWidth - 430, 24, 104, 28);
        achievementsTab.setBounds(viewportWidth - 318, 24, 140, 28);
        sortButton.setBounds(viewportWidth - 570, 58, 240, 26);
        backButton.setBounds(viewportWidth - 170, 24, 138, 28);

        drawButton(target, catalogTab, "Game Catalog", tab == Tab.CATALOG);
        drawButton(target, historyTab, "History", tab == Tab.HISTORY);
        drawButton(target, achievementsTab, "Achievements", tab == Tab.ACHIEVEMENTS);
        drawButton(target, backButton, "Back", false);
        if (tab != Tab.ACHIEVEMENTS) {
            drawButton(target, sortButton, "Sorted by " + sort.label, false);
        }
    }

    /**
     * The lifetime page: what this reference book has accumulated across every
     * experiment ever run against it, as opposed to the species list (which is
     * the same discoveries, one at a time) or the achievement wall.
     *
     * <p>This is also where the credit total that a lab reset hands back is
     * shown, since that number <em>is</em> the lifetime score.
     */
    private void drawButton(DrawTarget target, Rectangle r, String label, boolean active) {
        boolean hover = r.contains(mouseX, mouseY);
        target.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6,
                active ? TAB_ACTIVE : (hover ? TAB_HOVER : PANEL));
        target.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6, active ? GOLD : PANEL_EDGE);
        target.drawText(label,
                r.x + (r.width - target.textWidth(label, BODY_FONT)) / 2,
                r.y + (r.height + target.textAscent(BODY_FONT)) / 2 - 2,
                BODY_FONT, active ? GOLD : TEXT);
    }

    /**
     * The lifetime totals, as a compact strip above the history list: what this
     * player has accumulated across every game they have ever played, which is
     * exactly what a reset does not touch.
     */
    private void drawLifetimeStrip(DrawTarget target) {
        int x = 20;
        int y = 92;
        int w = viewportWidth - 40;
        int h = 66;
        target.fillRoundRect(x, y, w, h, 10, 10, PANEL);
        target.drawRoundRect(x, y, w, h, 10, 10, PANEL_EDGE);

        SpeciesRecord longest = history.longestStrand();
        SpeciesRecord complex = history.mostComplex();
        String[][] cells = {
                {"Organisms", String.valueOf(allTime.size())},
                {"Colonies", String.valueOf(history.combinationCount())},
                {"Games", String.valueOf(history.gamesPlayed())},
                {"Credits earned", String.valueOf(history.lifetimeCredit())},
                {"Shapes", history.shapesSeen().size() + "/"
                        + com.larsons.engine.evolution.BodyShape.values().length},
                {"Abilities", history.abilitiesSeen() + "/"
                        + com.larsons.engine.evolution.Ability.values().length},
                {"Deepest", "gen " + history.deepestGeneration()},
                {"Longest", longest == null ? "—" : longest.sequence.length() + "nt"},
                {"Most complex", complex == null ? "—" : String.valueOf(
                        complex.phenotype().complexity())},
        };
        int cellW = w / cells.length;
        for (int i = 0; i < cells.length; i++) {
            int cx = x + 14 + i * cellW;
            target.drawText(clip(target, TINY_FONT, cells[i][0], cellW - 16),
                    cx, y + 24, TINY_FONT, TEXT_DIM);
            target.drawText(clip(target, STAT_FONT, cells[i][1], cellW - 16),
                    cx, y + 48, STAT_FONT,
                    cells[i][0].startsWith("Credits") ? GOLD : TEXT);
        }
    }

    /** Where the list and detail panes start, leaving room for the strip. */
    private int listTop() {
        return tab == Tab.HISTORY ? 178 : 100;
    }

    private void drawSpeciesList(DrawTarget target) {
        List<SpeciesRecord> list = visibleList();
        int top = listTop();
        int listW = Math.min(560, viewportWidth / 2);
        int bottom = viewportHeight - 40;
        int rowH = 34;
        int visible = Math.max(1, (bottom - top) / rowH);

        target.fillRoundRect(20, top - 8, listW, bottom - top + 16, 10, 10, PANEL);
        target.drawRoundRect(20, top - 8, listW, bottom - top + 16, 10, 10, PANEL_EDGE);

        rowRects.clear();
        if (list.isEmpty()) {
            if (tab == Tab.HISTORY) {
                target.drawText("You have not discovered anything yet — run an experiment",
                        40, top + 30, EMPTY_FONT, TEXT_DIM);
                target.drawText("and let something divide.", 40, top + 52, EMPTY_FONT, TEXT_DIM);
            } else {
                target.drawText("This game has not found anything yet.",
                        40, top + 30, EMPTY_FONT, TEXT_DIM);
                target.drawText("Every strand it turns up lands here; your History keeps",
                        40, top + 52, EMPTY_FONT, TEXT_DIM);
                target.drawText("everything from every game, including the ones before this.",
                        40, top + 74, EMPTY_FONT, TEXT_DIM);
            }
            return;
        }

        int selected = Math.max(0, Math.min(list.size() - 1, selectedIndex()));
        scroll = Math.max(0, Math.min(Math.max(0, list.size() - visible), scroll));
        if (selected >= scroll + visible) scroll = selected - visible + 1;

        for (int i = 0; i < visible && scroll + i < list.size(); i++) {
            SpeciesRecord rec = list.get(scroll + i);
            Rectangle r = new Rectangle(28, top + i * rowH, listW - 16, rowH - 2);
            rowRects.add(r);
            boolean isSelected = scroll + i == selected;
            if (isSelected) {
                target.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6, ROW_SELECTED);
            }
            drawStrand(target, rec.genome(), r.x + 8, r.y + 10, 120);
            target.drawText(clip(target, BODY_FONT, rec.name, r.width - 260),
                    r.x + 140, r.y + 22, BODY_FONT, isSelected ? TEXT : ROW_NAME);
            String right = rec.sequence.length() + "nt · " + rec.phenotype().complexity() + "c";
            target.drawText(right,
                    r.x + r.width - target.textWidth(right, BODY_FONT) - 10, r.y + 22,
                    BODY_FONT, TEXT_DIM);
        }
    }

    private void drawDetail(DrawTarget target) {
        List<SpeciesRecord> list = visibleList();
        if (list.isEmpty()) return;
        SpeciesRecord rec = list.get(Math.max(0, Math.min(list.size() - 1, selectedIndex())));
        Phenotype p = rec.phenotype();

        int x = Math.min(600, viewportWidth / 2 + 20);
        int w = viewportWidth - x - 24;
        int y = listTop();
        int h = viewportHeight - y - 44;
        target.fillRoundRect(x, y - 8, w, h, 10, 10, PANEL);
        target.drawRoundRect(x, y - 8, w, h, 10, 10, PANEL_EDGE);

        int ty = y + 24;
        target.drawText(clip(target, DETAIL_TITLE_FONT, rec.name, w - 36), x + 18, ty,
                DETAIL_TITLE_FONT, TEXT);
        ty += 24;

        target.drawText("first seen " + WHEN.format(new Date(rec.discoveredAt))
                        + (rec.dish.isEmpty() ? "" : " in " + rec.dish)
                        + " · generation " + rec.generation,
                x + 18, ty, SMALL_FONT, TEXT_DIM);
        ty += 18;
        // Whether this entry is also in the current game's book is worth saying:
        // the two sections deliberately hold different things.
        boolean inGame = false;
        for (SpeciesRecord r : thisGame) {
            if (r.sequence.equals(rec.sequence)) inGame = true;
        }
        target.drawText(inGame ? "found in the current game"
                        : "not yet found in the current game",
                x + 18, ty, SMALL_FONT, inGame ? GOOD : MUTED);
        ty += 24;

        drawStrand(target, rec.genome(), x + 18, ty - 12, w - 60);
        ty += 16;
        target.drawText(rec.sequence, x + 18, ty, SEQUENCE_FONT, TEXT);
        ty += 28;

        target.fillRect(x + 18, ty - 12, 16, 16, p.color());
        target.drawText(p.shape().displayName() + " body", x + 42, ty, BODY_FONT, TEXT);
        ty += 20;
        target.drawText(clip(target, TINY_FONT, p.shape().description(), w - 36),
                x + 18, ty, TINY_FONT, TEXT_DIM);
        ty += 24;

        target.drawText("Traits", x + 18, ty, SMALL_FONT, TEXT);
        ty += 6;
        double max = 1;
        for (Trait t : Trait.values()) max = Math.max(max, p.trait(t));
        for (Trait t : Trait.values()) {
            double v = p.trait(t);
            if (v <= 0) continue;
            ty += 18;
            target.drawText(t.displayName(), x + 18, ty, SMALL_FONT, TEXT_DIM);
            int barX = x + 200;
            int barW = Math.max(40, w - 260);
            target.fillRect(barX, ty - 10, barW, 10, BAR_TRACK);
            target.fillRect(barX, ty - 10, (int) (barW * (v / max)), 10, GOOD);
            target.drawText(String.format("%.0f", v), barX + barW + 8, ty, SMALL_FONT, TEXT_DIM);
        }

        ty += 30;
        target.drawText("Abilities", x + 18, ty, SMALL_FONT, TEXT);
        if (p.abilities().isEmpty()) {
            ty += 18;
            target.drawText("none — a plain strand", x + 18, ty, SMALL_FONT, TEXT_DIM);
        }
        for (Ability a : p.abilities()) {
            ty += 18;
            if (ty > y + h - 60) break;
            target.fillRect(x + 18, ty - 9, 8, 8, a.color());
            target.drawText(clip(target, SMALL_FONT,
                            a.displayName() + "  (" + a.rule() + ")", w - 50),
                    x + 32, ty, SMALL_FONT, TEXT_DIM);
        }

        target.drawText("paid " + rec.credit + " credits on discovery",
                x + 18, y + h - 34, SMALL_FONT, GOLD);
    }

    private void drawAchievements(DrawTarget target) {
        Achievement[] all = Achievement.values();
        int cols = Math.max(1, (viewportWidth - 60) / 400);
        int cardW = (viewportWidth - 60 - (cols - 1) * 16) / cols;
        int cardH = 104;
        int x0 = 28;
        int y0 = 100;

        int unlocked = 0;
        for (Achievement a : all) if (history.isUnlocked(a)) unlocked++;
        target.drawText(unlocked + " of " + all.length + " unlocked", 28, 86, BODY_FONT, GOLD);

        // Bound the scroll to the wall's real height, and clip so no card can
        // spill over the footer hint.
        int rows = (all.length + cols - 1) / cols;
        int contentH = rows * (cardH + 10);
        int viewH = viewportHeight - y0 - 34;
        int maxScroll = Math.max(0, (contentH - viewH + 11) / 12);
        scroll = Math.min(scroll, maxScroll);

        target.pushClip(0, y0 - 6, viewportWidth, viewH + 6);
        for (int i = 0; i < all.length; i++) {
            Achievement a = all[i];
            int col = i % cols;
            int row = i / cols;
            int x = x0 + col * (cardW + 16);
            int y = y0 + row * (cardH + 10) - scroll * 12;
            if (y + cardH < y0 || y > viewportHeight) continue;
            boolean got = history.isUnlocked(a);

            target.fillRoundRect(x, y, cardW, cardH, 8, 8, got ? UNLOCKED_FILL : PANEL);
            target.drawRoundRect(x, y, cardW, cardH, 8, 8, got ? UNLOCKED_EDGE : PANEL_EDGE);

            target.drawText(clip(target, CARD_TITLE_FONT, a.title(), cardW - 24),
                    x + 12, y + 22, CARD_TITLE_FONT, got ? GOOD : MUTED);
            // Descriptions and hints wrap rather than clip: a hint cut off
            // mid-sentence is worse than no hint at all.
            int after = drawWrapped(target, TINY_FONT, TEXT_DIM,
                    a.description(), x + 12, y + 40, cardW - 24, 15, 2);
            drawWrapped(target, TINY_FONT, got ? UNLOCKED_HINT : LOCKED_HINT,
                    got ? "unlocked" : "hint: " + a.hint(),
                    x + 12, after + 16, cardW - 24, 15, 2);
        }
        target.popClip();
    }

    /** A strand drawn as its coloured nucleotides — the DNA itself, not a label. */
    private void drawStrand(DrawTarget target, Genome genome, int x, int y, int maxWidth) {
        int n = genome.length();
        int cell = Math.max(2, Math.min(10, maxWidth / Math.max(1, n)));
        for (int i = 1; i <= n; i++) {
            target.fillRect(x + (i - 1) * cell, y, Math.max(1, cell - 1), 12,
                    genome.at(i).color());
        }
    }

    /**
     * Draw text wrapped to {@code width} over at most {@code maxLines} lines,
     * returning the baseline of the last line drawn so the caller can stack
     * something under it.
     */
    private static int drawWrapped(DrawTarget target, Font font, Color color, String text,
                                   int x, int y, int width, int lineHeight, int maxLines) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        int lastY = y;
        for (int i = 0; i < words.length; i++) {
            String candidate = line.length() == 0 ? words[i] : line + " " + words[i];
            if (target.textWidth(candidate, font) > width && line.length() > 0) {
                lastY = y + lines * lineHeight;
                if (lines + 1 >= maxLines) {
                    // No room to wrap again: everything left goes on this line,
                    // clipped, so the text ends with an ellipsis rather than
                    // simply stopping mid-word.
                    StringBuilder tail = new StringBuilder(line);
                    for (int k = i; k < words.length; k++) tail.append(' ').append(words[k]);
                    target.drawText(clip(target, font, tail.toString(), width),
                            x, lastY, font, color);
                    return lastY;
                }
                target.drawText(line.toString(), x, lastY, font, color);
                lines++;
                line = new StringBuilder(words[i]);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lastY = y + lines * lineHeight;
            target.drawText(clip(target, font, line.toString(), width), x, lastY, font, color);
        }
        return lastY;
    }

    private static String clip(DrawTarget target, Font font, String s, int width) {
        if (target.textWidth(s, font) <= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 3 && target.textWidth(sb + "…", font) > width) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }
}
