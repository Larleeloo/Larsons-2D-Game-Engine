package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.Nucleotide;
import com.larsons.engine.evolution.Phenotype;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * The front door to the evolution game: start a new experiment (choosing the
 * colour of the single organism you begin with), carry on with a saved one, or
 * open the reference book of everything previous experiments turned up.
 *
 * <p>The colour choice is the only decision the design document puts before the
 * simulation starts, and it matters: red begins hostile, blue begins altruistic,
 * green begins with the wild-card machinery. Each is shown with the strand it
 * actually starts as and what that strand decodes to, so the choice is
 * informed rather than cosmetic.
 */
public class EvolutionLobbyScene extends AbstractScene {

    private static final Color BG = new Color(10, 13, 19);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color TEXT_DIM = new Color(140, 152, 174);

    private final GameContext ctx;
    private final EvolutionStore store;

    private Menu menu;
    private Menu colorMenu;
    private boolean choosingColor;
    private String status = "";

    public EvolutionLobbyScene(GameContext ctx) {
        this(ctx, new EvolutionStore());
    }

    public EvolutionLobbyScene(GameContext ctx, EvolutionStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public void onEnter() {
        choosingColor = false;
        status = "";
        // Standalone mode: drop the active game type's post-FX while in here.
        ctx.lighting().setDarkness(0);
        ctx.applyLiveSettings();
        buildMenu();
    }

    private void buildMenu() {
        boolean hasSave = store.hasSave();
        int catalogued = store.speciesFileCount();

        menu = new Menu("Evolution")
                .subtitle("An artificial life simulator — write nothing, discover everything")
                .theme(MenuTheme.dark())
                .add("New Experiment", this::startColorChoice);
        if (hasSave) {
            menu.add("Continue Experiment", this::continueGame);
        }
        menu.add("Reference Book (" + catalogued + " catalogued)",
                () -> scenes.transitionTo("evolutioncatalog"));
        if (hasSave) {
            menu.add("Reset the Lab (respend your credits)", this::startReset);
        }
        menu.add("Back to Game Types", () -> scenes.transitionTo("startup"));
    }

    private void startColorChoice() {
        choosingColor = true;
        colorMenu = new Menu("Choose your first organism")
                .subtitle("One square cell, a hundred energy orbs, and whatever happens next")
                .theme(MenuTheme.dark());
        for (Nucleotide n : new Nucleotide[]{Nucleotide.R, Nucleotide.G, Nucleotide.B}) {
            Phenotype p = Phenotype.of(Genome.starter(n));
            String label = capitalize(n.displayName()) + "  ·  " + Genome.starter(n).sequence()
                    + "  ·  " + p.summary().toLowerCase();
            colorMenu.add(label, () -> startNewGame(n));
        }
        colorMenu.add("Cancel", () -> {
            choosingColor = false;
            buildMenu();
        });
    }

    /**
     * Begin a new run. The previous save is replaced, but the reference book on
     * disk is left alone — discoveries from earlier experiments stay discovered.
     */
    private void startNewGame(Nucleotide color) {
        EvolutionGame game = EvolutionGame.newGame(color);
        for (com.larsons.engine.evolution.SpeciesRecord rec : store.loadSpecies()) {
            game.catalog().restore(rec);
        }
        game.catalog().drainPendingWrites(); // restored records are already on disk
        store.save(game);
        handOff(game);
    }

    /**
     * Reset from the menu: the same redistribution the pause menu offers, for a
     * player who wants to start the lab over without loading into it first.
     */
    private void startReset() {
        EvolutionGame game = store.load();
        if (game == null) {
            status = "There is no experiment to reset";
            buildMenu();
            return;
        }
        choosingColor = true;
        colorMenu = new Menu("Reset the lab")
                .subtitle("Keeps your reference book · returns all "
                        + game.catalog().creditEarned() + " credits it has ever earned")
                .theme(MenuTheme.dark());
        for (Nucleotide n : new Nucleotide[]{Nucleotide.R, Nucleotide.G, Nucleotide.B}) {
            Phenotype p = Phenotype.of(Genome.starter(n));
            colorMenu.add(capitalize(n.displayName()) + "  ·  " + Genome.starter(n).sequence()
                    + "  ·  " + p.summary().toLowerCase(), () -> {
                game.resetExperiment(n);
                store.save(game);
                handOff(game);
            });
        }
        colorMenu.add("Cancel", () -> {
            choosingColor = false;
            buildMenu();
        });
    }

    private void continueGame() {
        EvolutionGame game = store.load();
        if (game == null) {
            status = "That save could not be read — start a new experiment instead";
            buildMenu();
            return;
        }
        handOff(game);
    }

    private void handOff(EvolutionGame game) {
        if (scenes.get("evolution") instanceof EvolutionScene scene) {
            scene.adopt(game);
            scenes.transitionTo("evolution");
        } else {
            status = "The evolution scene is not registered";
        }
    }

    @Override
    public void update(double dt, InputManager input) {
        if (choosingColor) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
                choosingColor = false;
                buildMenu();
                return;
            }
            colorMenu.update(dt, input);
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            scenes.transitionTo("startup");
            return;
        }
        menu.update(dt, input);
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        if (choosingColor) {
            colorMenu.render(g, viewportWidth, viewportHeight);
            drawColorSwatches(g);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.setColor(TEXT_DIM);
            g.drawString("Red hunts · blue cooperates · green is the wild card · Esc to go back",
                    24, viewportHeight - 24);
            return;
        }

        menu.render(g, viewportWidth, viewportHeight);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (!status.isEmpty()) {
            g.setColor(new Color(235, 150, 120));
            g.drawString(status, 24, viewportHeight - 44);
        }
        g.setColor(TEXT_DIM);
        g.drawString("Saves and the catalog live under " + store.directory(),
                24, viewportHeight - 24);
    }

    /**
     * Draw each starting strand as its actual coloured nucleotides, immediately
     * left of the menu row it belongs to.
     *
     * <p>{@link Menu} records every item's hit box while it renders, and the
     * menu's own layout moves with the window size and the number of rows — so
     * the swatches are positioned from those boxes rather than from guessed
     * offsets, which is what used to leave them drifting out of line with the
     * text they annotate. Must therefore be called after the menu has rendered.
     */
    private void drawColorSwatches(Graphics2D g) {
        List<MenuItem> items = colorMenu.items();
        Nucleotide[] order = {Nucleotide.R, Nucleotide.G, Nucleotide.B};
        int lastBottom = 0;
        for (int i = 0; i < order.length && i < items.size(); i++) {
            MenuItem item = items.get(i);
            if (item.width <= 0) continue; // scrolled out of view
            Genome starter = Genome.starter(order[i]);
            int cell = 9;
            int totalW = starter.length() * cell;
            int x = item.x - totalW - 16;
            int y = item.y + (item.height - 12) / 2;
            for (int slot = 1; slot <= starter.length(); slot++) {
                g.setColor(starter.at(slot).color());
                g.fillRect(x + (slot - 1) * cell, y, cell - 1, 12);
            }
            lastBottom = Math.max(lastBottom, item.y + item.height);
        }
        if (lastBottom == 0) return;

        // Parked above the control hint rather than under the last swatch: the
        // menu has a Cancel row below these three, and anchoring to the swatches
        // put this note straight through it.
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(TEXT);
        FontMetrics fm = g.getFontMetrics();
        String note = "Every strand starts four nucleotides long — body shapes appear at six";
        g.drawString(note, viewportWidth / 2 - fm.stringWidth(note) / 2, viewportHeight - 58);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
