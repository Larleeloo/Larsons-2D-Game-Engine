package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.evolution.Ability;
import com.larsons.engine.evolution.Achievement;
import com.larsons.engine.evolution.Catalog;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.Phenotype;
import com.larsons.engine.evolution.SpeciesRecord;
import com.larsons.engine.evolution.Trait;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * The reference book: every strand any experiment has ever produced, read back
 * from the individual JSON files the catalog writes, plus the achievement wall.
 *
 * <p>Nothing in here ships with the game. The book contains exactly what the
 * simulation has already created — which is why it is worth filling in, and why
 * two players' books never look the same.
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

    /** How the shelf is ordered. */
    private enum Sort {
        NEWEST("newest first"), COMPLEXITY("most complex"), LENGTH("longest strand");

        final String label;

        Sort(String label) { this.label = label; }
    }

    /** Which page of the book is open. */
    private enum Tab { SPECIES, LIFETIME, ACHIEVEMENTS }

    private final GameContext ctx;
    private final EvolutionStore store;

    private final List<SpeciesRecord> species = new ArrayList<>();
    /** Unlocked achievements, read once when the book opens rather than per frame. */
    private Catalog catalog = new Catalog();
    private Sort sort = Sort.NEWEST;
    private Tab tab = Tab.SPECIES;
    private int selected;
    private int scroll;
    private int mouseX, mouseY;

    private final List<Rectangle> rowRects = new ArrayList<>();
    private final Rectangle speciesTab = new Rectangle();
    private final Rectangle lifetimeTab = new Rectangle();
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
        species.clear();
        species.addAll(store.loadSpecies());
        // The save's index carries the running totals (credit ever earned, runs,
        // combinations, achievements); the discoveries themselves live in their
        // own files. Fold the files back in so the lifetime page can report the
        // things derived from them — shapes and abilities seen, the deepest
        // lineage, the record holders — instead of an index full of zeroes.
        catalog = store.loadCatalogIndex();
        for (SpeciesRecord rec : species) catalog.restore(rec);
        applySort();
        selected = 0;
        scroll = 0;
    }

    private void applySort() {
        switch (sort) {
            case NEWEST -> species.sort(
                    Comparator.comparingLong((SpeciesRecord r) -> r.discoveredAt).reversed());
            case COMPLEXITY -> species.sort(Comparator
                    .comparingInt((SpeciesRecord r) -> r.phenotype().complexity()).reversed());
            case LENGTH -> species.sort(
                    Comparator.comparingInt((SpeciesRecord r) -> r.sequence.length()).reversed());
        }
    }

    @Override
    public void update(double dt, InputManager input) {
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE) || input.isKeyJustPressed(KeyEvent.VK_K)) {
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
        if (input.isKeyJustPressed(KeyEvent.VK_DOWN)) moveSelection(1);
        if (input.isKeyJustPressed(KeyEvent.VK_UP)) moveSelection(-1);

        if (input.isMouseJustPressed()) {
            if (backButton.contains(mouseX, mouseY)) {
                leave();
                return;
            }
            if (speciesTab.contains(mouseX, mouseY)) tab = Tab.SPECIES;
            if (lifetimeTab.contains(mouseX, mouseY)) tab = Tab.LIFETIME;
            if (achievementsTab.contains(mouseX, mouseY)) tab = Tab.ACHIEVEMENTS;
            if (sortButton.contains(mouseX, mouseY)) {
                Sort[] all = Sort.values();
                sort = all[(sort.ordinal() + 1) % all.length];
                applySort();
                selected = 0;
                scroll = 0;
            }
            for (int i = 0; i < rowRects.size(); i++) {
                if (rowRects.get(i).contains(mouseX, mouseY)) {
                    selected = scroll + i;
                    break;
                }
            }
        }
    }

    private void moveSelection(int delta) {
        if (species.isEmpty()) return;
        selected = Math.max(0, Math.min(species.size() - 1, selected + delta));
        if (selected < scroll) scroll = selected;
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
    public void render(Graphics2D g, float alpha) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(TEXT);
        g.drawString("Reference Book", 28, 42);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(TEXT_DIM);
        g.drawString(species.size() + " strands catalogued — everything here was discovered, "
                + "not written", 28, 64);

        drawChrome(g);
        switch (tab) {
            case SPECIES -> {
                drawSpeciesList(g);
                drawDetail(g);
            }
            case LIFETIME -> drawLifetime(g);
            case ACHIEVEMENTS -> drawAchievements(g);
        }

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(TEXT_DIM);
        g.drawString("Tab switches page · arrows and wheel scroll · Esc goes back",
                28, viewportHeight - 18);
    }

    private void drawChrome(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        speciesTab.setBounds(viewportWidth - 540, 24, 104, 28);
        lifetimeTab.setBounds(viewportWidth - 428, 24, 104, 28);
        achievementsTab.setBounds(viewportWidth - 316, 24, 140, 28);
        sortButton.setBounds(viewportWidth - 540, 58, 240, 26);
        backButton.setBounds(viewportWidth - 168, 24, 136, 28);

        drawButton(g, speciesTab, "Species", tab == Tab.SPECIES);
        drawButton(g, lifetimeTab, "Lifetime", tab == Tab.LIFETIME);
        drawButton(g, achievementsTab, "Achievements", tab == Tab.ACHIEVEMENTS);
        drawButton(g, backButton, "Back", false);
        if (tab == Tab.SPECIES) drawButton(g, sortButton, "Sorted by " + sort.label, false);
    }

    /**
     * The lifetime page: what this reference book has accumulated across every
     * experiment ever run against it, as opposed to the species list (which is
     * the same discoveries, one at a time) or the achievement wall.
     *
     * <p>This is also where the credit total that a lab reset hands back is
     * shown, since that number <em>is</em> the lifetime score.
     */
    private void drawLifetime(Graphics2D g) {
        int x = 28;
        int y = 100;
        int w = viewportWidth - 56;
        int h = viewportHeight - 150;
        g.setColor(PANEL);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(PANEL_EDGE);
        g.drawRoundRect(x, y, w, h, 10, 10);

        SpeciesRecord longest = catalog.longestStrand();
        SpeciesRecord complex = catalog.mostComplex();
        // The book on disk is the authority on how many strands exist; the
        // save's index carries the running totals that are not derivable from it.
        int strands = Math.max(species.size(), catalog.speciesCount());

        int ty = y + 40;
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(TEXT);
        g.drawString("Lifetime discoveries", x + 24, ty);
        ty += 12;

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(TEXT_DIM);
        g.drawString("Everything every experiment has ever turned up, kept across resets",
                x + 24, ty + 12);
        ty += 46;

        String[][] rows = {
                {"Strands catalogued", String.valueOf(strands)},
                {"Colony combinations", String.valueOf(catalog.combinationCount())},
                {"Credits ever earned", String.valueOf(catalog.creditEarned())},
                {"Experiments run", String.valueOf(catalog.runs())},
                {"Body shapes seen", catalog.shapesSeen().size() + " of "
                        + com.larsons.engine.evolution.BodyShape.values().length},
                {"Abilities seen", catalog.abilitiesSeen() + " of "
                        + com.larsons.engine.evolution.Ability.values().length},
                {"Deepest lineage", "generation " + catalog.deepestGeneration()},
                {"Achievements", catalog.unlockedAchievements().size() + " of "
                        + Achievement.values().length},
        };
        for (String[] row : rows) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 15));
            g.setColor(TEXT_DIM);
            g.drawString(row[0], x + 24, ty);
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.setColor(row[0].startsWith("Credits") ? GOLD : TEXT);
            g.drawString(row[1], x + 260, ty);
            ty += 28;
        }

        ty += 14;
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(TEXT);
        g.drawString("Record holders", x + 24, ty);
        ty += 26;
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (longest == null) {
            g.setColor(TEXT_DIM);
            g.drawString("Nothing catalogued yet.", x + 24, ty);
        } else {
            drawRecordHolder(g, "Longest strand", longest, x + 24, ty, w - 48);
            ty += 44;
            drawRecordHolder(g, "Most complex", complex, x + 24, ty, w - 48);
        }

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(GOLD);
        g.drawString("Resetting the lab returns all " + catalog.creditEarned()
                        + " of these credits to spend again — the discoveries are permanent.",
                x + 24, y + h - 22);
    }

    private void drawRecordHolder(Graphics2D g, String label, SpeciesRecord rec,
                                  int x, int y, int width) {
        if (rec == null) return;
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(TEXT_DIM);
        g.drawString(label, x, y);
        drawStrand(g, rec.genome(), x + 160, y - 11, Math.min(240, width - 200));
        g.setColor(TEXT);
        g.drawString(rec.name + "  (" + rec.sequence.length() + "nt · "
                + rec.phenotype().complexity() + " complexity)", x + 160, y + 18);
    }

    private void drawButton(Graphics2D g, Rectangle r, String label, boolean active) {
        boolean hover = r.contains(mouseX, mouseY);
        g.setColor(active ? new Color(52, 64, 88) : (hover ? new Color(38, 46, 62) : PANEL));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
        g.setColor(active ? GOLD : PANEL_EDGE);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 6, 6);
        g.setColor(active ? GOLD : TEXT);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2,
                r.y + (r.height + fm.getAscent()) / 2 - 2);
    }

    private void drawSpeciesList(Graphics2D g) {
        int top = 96;
        int listW = Math.min(560, viewportWidth / 2);
        int bottom = viewportHeight - 40;
        int rowH = 34;
        int visible = Math.max(1, (bottom - top) / rowH);

        g.setColor(PANEL);
        g.fillRoundRect(20, top - 8, listW, bottom - top + 16, 10, 10);
        g.setColor(PANEL_EDGE);
        g.drawRoundRect(20, top - 8, listW, bottom - top + 16, 10, 10);

        rowRects.clear();
        if (species.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 15));
            g.setColor(TEXT_DIM);
            g.drawString("Nothing catalogued yet — run an experiment and let something",
                    40, top + 30);
            g.drawString("divide. Every strand that has never existed lands here.", 40, top + 52);
            return;
        }

        scroll = Math.max(0, Math.min(Math.max(0, species.size() - visible), scroll));
        if (selected >= scroll + visible) scroll = selected - visible + 1;

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (int i = 0; i < visible && scroll + i < species.size(); i++) {
            SpeciesRecord rec = species.get(scroll + i);
            Rectangle r = new Rectangle(28, top + i * rowH, listW - 16, rowH - 2);
            rowRects.add(r);
            boolean isSelected = scroll + i == selected;
            if (isSelected) {
                g.setColor(new Color(46, 58, 80));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 6, 6);
            }
            drawStrand(g, rec.genome(), r.x + 8, r.y + 10, 120);
            g.setColor(isSelected ? TEXT : new Color(186, 196, 214));
            g.drawString(rec.name, r.x + 140, r.y + 22);
            g.setColor(TEXT_DIM);
            String right = rec.sequence.length() + "nt · " + rec.phenotype().complexity() + "c";
            g.drawString(right, r.x + r.width - g.getFontMetrics().stringWidth(right) - 10,
                    r.y + 22);
        }
    }

    private void drawDetail(Graphics2D g) {
        if (species.isEmpty()) return;
        SpeciesRecord rec = species.get(Math.max(0, Math.min(species.size() - 1, selected)));
        Phenotype p = rec.phenotype();

        int x = Math.min(600, viewportWidth / 2 + 20);
        int w = viewportWidth - x - 24;
        int y = 96;
        int h = viewportHeight - 140;
        g.setColor(PANEL);
        g.fillRoundRect(x, y - 8, w, h, 10, 10);
        g.setColor(PANEL_EDGE);
        g.drawRoundRect(x, y - 8, w, h, 10, 10);

        int ty = y + 24;
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(TEXT);
        g.drawString(rec.name, x + 18, ty);
        ty += 24;

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(TEXT_DIM);
        g.drawString("first seen " + WHEN.format(new Date(rec.discoveredAt))
                + (rec.dish.isEmpty() ? "" : " in " + rec.dish)
                + " · generation " + rec.generation, x + 18, ty);
        ty += 26;

        drawStrand(g, rec.genome(), x + 18, ty - 12, w - 60);
        ty += 16;
        g.setFont(new Font("Monospaced", Font.PLAIN, 13));
        g.setColor(TEXT);
        g.drawString(rec.sequence, x + 18, ty);
        ty += 28;

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(p.color());
        g.fillRect(x + 18, ty - 12, 16, 16);
        g.setColor(TEXT);
        g.drawString(p.shape().displayName() + " body", x + 42, ty);
        ty += 20;
        g.setColor(TEXT_DIM);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(p.shape().description(), x + 18, ty);
        ty += 24;

        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(TEXT);
        g.drawString("Traits", x + 18, ty);
        ty += 6;
        double max = 1;
        for (Trait t : Trait.values()) max = Math.max(max, p.trait(t));
        for (Trait t : Trait.values()) {
            double v = p.trait(t);
            if (v <= 0) continue;
            ty += 18;
            g.setColor(TEXT_DIM);
            g.drawString(t.displayName(), x + 18, ty);
            int barX = x + 200;
            int barW = Math.max(40, w - 260);
            g.setColor(new Color(40, 48, 64));
            g.fillRect(barX, ty - 10, barW, 10);
            g.setColor(GOOD);
            g.fillRect(barX, ty - 10, (int) (barW * (v / max)), 10);
            g.setColor(TEXT_DIM);
            g.drawString(String.format("%.0f", v), barX + barW + 8, ty);
        }

        ty += 30;
        g.setColor(TEXT);
        g.drawString("Abilities", x + 18, ty);
        if (p.abilities().isEmpty()) {
            ty += 18;
            g.setColor(TEXT_DIM);
            g.drawString("none — a plain strand", x + 18, ty);
        }
        for (Ability a : p.abilities()) {
            ty += 18;
            if (ty > y + h - 60) break;
            g.setColor(a.color());
            g.fillRect(x + 18, ty - 9, 8, 8);
            g.setColor(TEXT_DIM);
            g.drawString(a.displayName() + "  (" + a.rule() + ")", x + 32, ty);
        }

        ty = y + h - 34;
        g.setColor(GOLD);
        g.drawString("paid " + rec.credit + " credits on discovery", x + 18, ty);
    }

    private void drawAchievements(Graphics2D g) {
        Achievement[] all = Achievement.values();
        int cols = Math.max(1, (viewportWidth - 60) / 400);
        int cardW = (viewportWidth - 60 - (cols - 1) * 16) / cols;
        int cardH = 104;
        int x0 = 28;
        int y0 = 100;

        int unlocked = 0;
        for (Achievement a : all) if (catalog.isUnlocked(a)) unlocked++;
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(GOLD);
        g.drawString(unlocked + " of " + all.length + " unlocked", 28, 86);

        // Bound the scroll to the wall's real height, and clip so no card can
        // spill over the footer hint.
        int rows = (all.length + cols - 1) / cols;
        int contentH = rows * (cardH + 10);
        int viewH = viewportHeight - y0 - 34;
        int maxScroll = Math.max(0, (contentH - viewH + 11) / 12);
        scroll = Math.min(scroll, maxScroll);

        java.awt.Shape oldClip = g.getClip();
        g.setClip(0, y0 - 6, viewportWidth, viewH + 6);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < all.length; i++) {
            Achievement a = all[i];
            int col = i % cols;
            int row = i / cols;
            int x = x0 + col * (cardW + 16);
            int y = y0 + row * (cardH + 10) - scroll * 12;
            if (y + cardH < y0 || y > viewportHeight) continue;
            boolean got = catalog.isUnlocked(a);

            g.setColor(got ? new Color(30, 40, 34) : PANEL);
            g.fillRoundRect(x, y, cardW, cardH, 8, 8);
            g.setColor(got ? new Color(110, 170, 120) : PANEL_EDGE);
            g.drawRoundRect(x, y, cardW, cardH, 8, 8);

            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.setColor(got ? GOOD : new Color(120, 128, 146));
            g.drawString(clip(g, a.title(), cardW - 24), x + 12, y + 22);
            // Descriptions and hints wrap rather than clip: a hint cut off
            // mid-sentence is worse than no hint at all.
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(TEXT_DIM);
            int after = drawWrapped(g, a.description(), x + 12, y + 40, cardW - 24, 15, 2);
            g.setColor(got ? new Color(96, 132, 104) : new Color(96, 102, 118));
            drawWrapped(g, got ? "unlocked" : "hint: " + a.hint(),
                    x + 12, after + 16, cardW - 24, 15, 2);
        }
        g.setClip(oldClip);
    }

    /** A strand drawn as its coloured nucleotides — the DNA itself, not a label. */
    private void drawStrand(Graphics2D g, Genome genome, int x, int y, int maxWidth) {
        int n = genome.length();
        int cell = Math.max(2, Math.min(10, maxWidth / Math.max(1, n)));
        for (int i = 1; i <= n; i++) {
            g.setColor(genome.at(i).color());
            g.fillRect(x + (i - 1) * cell, y, Math.max(1, cell - 1), 12);
        }
    }

    /**
     * Draw text wrapped to {@code width} over at most {@code maxLines} lines,
     * returning the baseline of the last line drawn so the caller can stack
     * something under it.
     */
    private static int drawWrapped(Graphics2D g, String text, int x, int y, int width,
                                   int lineHeight, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        int lastY = y;
        for (int i = 0; i < words.length; i++) {
            String candidate = line.length() == 0 ? words[i] : line + " " + words[i];
            if (fm.stringWidth(candidate) > width && line.length() > 0) {
                lastY = y + lines * lineHeight;
                if (lines + 1 >= maxLines) {
                    // No room to wrap again: everything left goes on this line,
                    // clipped, so the text ends with an ellipsis rather than
                    // simply stopping mid-word.
                    StringBuilder tail = new StringBuilder(line);
                    for (int k = i; k < words.length; k++) tail.append(' ').append(words[k]);
                    g.drawString(clip(g, tail.toString(), width), x, lastY);
                    return lastY;
                }
                g.drawString(line.toString(), x, lastY);
                lines++;
                line = new StringBuilder(words[i]);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lastY = y + lines * lineHeight;
            g.drawString(clip(g, line.toString(), width), x, lastY);
        }
        return lastY;
    }

    private static String clip(Graphics2D g, String s, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 3 && fm.stringWidth(sb + "…") > width) sb.deleteCharAt(sb.length() - 1);
        return sb + "…";
    }
}
