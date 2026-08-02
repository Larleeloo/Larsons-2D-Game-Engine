package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.History;
import com.larsons.engine.evolution.Nucleotide;
import com.larsons.engine.evolution.Phenotype;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
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
 *
 * <p>Starting over is <b>New Experiment</b> and nothing else. It replaces the
 * saved lab with the opening state and keeps every organism in your history,
 * which is exactly what a reset did — so there is one way to do it here rather
 * than two rows that read differently and behave identically. (The pause menu
 * still offers the same restart mid-game, where there is no new-experiment row
 * beside it to be confused with.)
 */
public class EvolutionLobbyScene extends AbstractScene {

    private static final Color BG = new Color(10, 13, 19);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color TEXT_DIM = new Color(140, 152, 174);
    private static final Color STATUS = new Color(235, 150, 120);
    private static final Font NOTE_FONT = new Font("SansSerif", Font.PLAIN, 13);

    private final GameContext ctx;
    private final EvolutionStore store;

    private Menu menu;
    private Menu colorMenu;
    private boolean choosingColor;
    private String status = "";
    /** Discoveries on the permanent record, read once when the menu is built. */
    private int discovered;

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
        discovered = store.speciesCount();

        menu = new Menu("Evolution")
                .subtitle("An artificial life simulator — write nothing, discover everything")
                .theme(MenuTheme.dark())
                .add("New Experiment", this::startColorChoice);
        if (hasSave) {
            menu.add("Continue Experiment", this::continueGame);
        }
        menu.add("Reference Book (" + discovered + " organisms in your history)",
                () -> scenes.transitionTo("evolutioncatalog"));
        menu.add("Controls (Key Binds)",
                () -> KeyBindsScene.open(scenes, "evolutionlobby"));
        menu.add("Back to Game Types", () -> scenes.transitionTo("startup"));
    }

    /** The menu on screen, so the scene can be walked in tests. */
    public Menu menu() { return choosingColor ? colorMenu : menu; }

    /**
     * Pick the colour to start from. When there is already a saved experiment
     * this is also how a player starts over, so the subtitle says plainly what
     * it costs them — the lab, never the collection.
     */
    private void startColorChoice() {
        choosingColor = true;
        String subtitle = store.hasSave()
                ? "Replaces the saved experiment · your "
                        + discovered + " discovered organisms are kept"
                : "One square cell, a hundred energy orbs, and whatever happens next";
        colorMenu = new Menu("Choose your first organism")
                .subtitle(subtitle)
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
     * Begin a new run: an empty game catalog and a fresh lab, carrying the
     * player's permanent history forward. Everything earlier games discovered
     * stays on the record; this game simply has not found any of it yet.
     */
    private void startNewGame(Nucleotide color) {
        History history = store.loadHistory();
        boolean firstEver = history.speciesCount() == 0;
        if (!firstEver) history.countGame();
        EvolutionGame game = EvolutionGame.newGame(color, new java.util.Random().nextLong(), history);
        store.save(game);
        handOff(game);
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
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                choosingColor = false;
                buildMenu();
                return;
            }
            colorMenu.update(dt, input);
            return;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            scenes.transitionTo("startup");
            return;
        }
        menu.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, BG);

        if (choosingColor) {
            colorMenu.render(target, viewportWidth, viewportHeight);
            drawColorSwatches(target);
            target.drawText("Red hunts · blue cooperates · green is the wild card · Esc to go back",
                    24, viewportHeight - 24, SceneChrome.BODY, TEXT_DIM);
            return;
        }

        menu.render(target, viewportWidth, viewportHeight);
        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, STATUS);
        }
        target.drawText("Saves and your discovery history live under " + store.directory(),
                24, viewportHeight - 24, SceneChrome.BODY, TEXT_DIM);
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
    private void drawColorSwatches(DrawTarget target) {
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
                target.fillRect(x + (slot - 1) * cell, y, cell - 1, 12,
                        starter.at(slot).color());
            }
            lastBottom = Math.max(lastBottom, item.y + item.height);
        }
        if (lastBottom == 0) return;

        // Parked above the control hint rather than under the last swatch: the
        // menu has a Cancel row below these three, and anchoring to the swatches
        // put this note straight through it.
        String note = "Every strand starts four nucleotides long — body shapes appear at six";
        target.drawText(note,
                viewportWidth / 2 - target.textWidth(note, NOTE_FONT) / 2,
                viewportHeight - 58, NOTE_FONT, TEXT);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
