package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.save.RunRecord;
import com.larsons.engine.save.SaveStore;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.ui.MenuTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The saved runs of the active game type: continue one, start one over, or
 * delete one.
 *
 * <p>Built the way {@link LevelSelectScene} is — a list, then the choices for
 * whatever is selected — because it answers the same shape of question about a
 * different noun. A game type is a folder of levels; a slot is a run through
 * them, and a player may reasonably have more than one going at once.
 *
 * <p>{@link #SLOTS} of them, because three is enough to keep a cautious save
 * and an experimental one without turning the screen into a filing cabinet.
 * Nothing in {@link SaveStore} imposes the limit; it is a decision about the
 * menu, made here.
 */
public class SaveSelectScene extends AbstractScene {

    public static final String NAME = "saveselect";

    /** How many slots the menu offers per game type. */
    public static final int SLOTS = 3;

    private final GameContext ctx;
    private Menu menu;
    private String status = "";

    /** The slot the confirmation below is about ({@code null} = not confirming). */
    private String confirming;
    private Menu confirmMenu;

    public SaveSelectScene(GameContext ctx) { this.ctx = ctx; }

    /** The slot names this menu offers, in order. */
    public static List<String> slotNames() {
        List<String> names = new ArrayList<>(SLOTS);
        for (int i = 1; i <= SLOTS; i++) names.add("slot" + i);
        return names;
    }

    /**
     * The saved run in a slot, or {@code null} when it is empty. Static so the
     * main menu can ask the same question without building a scene.
     */
    public static RunRecord runIn(String gameType, String slot) {
        return new SaveStore(gameType, slot).loadRun();
    }

    /**
     * The slot the main menu's <em>Continue</em> should resume: the one saved
     * most recently, or {@code null} when this game type has none.
     */
    public static String mostRecentSlot(String gameType) {
        String best = null;
        long bestAt = Long.MIN_VALUE;
        for (String slot : slotNames()) {
            RunRecord run = runIn(gameType, slot);
            if (run != null && run.savedAt >= bestAt) {
                bestAt = run.savedAt;
                best = slot;
            }
        }
        return best;
    }

    @Override
    public void onEnter() {
        confirming = null;
        status = "";
        buildMenu();
    }

    private void buildMenu() {
        GameProfile p = ctx.profile();
        menu = new Menu("Saved Runs")
                .subtitle(p.name + " · a run remembers where you were, what you were "
                        + "carrying, and every level you changed")
                .theme(MenuTheme.dark());
        for (String slot : slotNames()) {
            RunRecord run = runIn(p.name, slot);
            String label = label(slot, run);
            if (run == null) {
                menu.add(label, () -> startRun(slot, true));
            } else {
                menu.add(label, () -> startRun(slot, false));
            }
        }
        // The "start over" half only exists when there is something to start
        // over. On a game type nobody has played yet, every slot is already a
        // new run and a separator over an empty list is just furniture.
        List<String> used = new ArrayList<>();
        for (String slot : slotNames()) {
            if (runIn(p.name, slot) != null) used.add(slot);
        }
        if (!used.isEmpty()) {
            // A heading, not a choice: disabled so Enter cannot land on it.
            MenuItem separator = new MenuItem("— start one over or clear it —", () -> { });
            separator.enabled = false;
            menu.add(separator);
            for (String slot : used) {
                RunRecord run = runIn(p.name, slot);
                menu.add("New Run in " + pretty(slot) + " (replaces " + run.describe() + ")",
                        () -> confirm(slot));
            }
        }
        menu.add("Back", () -> scenes.transitionTo("menu"));
    }

    private static String pretty(String slot) {
        return "Slot " + slot.replace("slot", "");
    }

    private static String label(String slot, RunRecord run) {
        return run == null
                ? pretty(slot) + " — empty · start a new run"
                : "Continue " + pretty(slot) + " — " + run.describe();
    }

    /** Hand the choice to the play scene, which is what actually opens a run. */
    private void startRun(String slot, boolean fresh) {
        if (fresh) ctx.startNewRun(slot);
        else ctx.continueRun(slot);
        scenes.transitionTo("play");
    }

    /**
     * Starting a slot over destroys a run, so it asks first — and, as in the
     * game-type delete, "Keep" is the first and therefore default-selected
     * choice, so a stray Enter backs out rather than wiping an evening.
     */
    private void confirm(String slot) {
        RunRecord run = runIn(ctx.profile().name, slot);
        confirming = slot;
        confirmMenu = new Menu("Start " + pretty(slot) + " over?")
                .subtitle(run == null ? "" : "This permanently discards " + run.describe())
                .theme(MenuTheme.dark())
                .add("Keep this run", () -> { confirming = null; buildMenu(); })
                .add("Start over — discard it", () -> {
                    String target = confirming;
                    confirming = null;
                    startRun(target, true);
                })
                .add("Delete it and stay here", () -> {
                    String target = confirming;
                    confirming = null;
                    new SaveStore(ctx.profile().name, target).delete();
                    status = "Deleted " + pretty(target);
                    buildMenu();
                });
    }

    @Override
    public void update(double dt, InputManager input) {
        if (confirming != null) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                confirming = null;
                buildMenu();
                return;
            }
            confirmMenu.update(dt, input);
            return;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            scenes.transitionTo("menu");
            return;
        }
        menu.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        SceneChrome.backdrop(target, viewportWidth, viewportHeight);
        if (confirming != null) {
            confirmMenu.render(target, viewportWidth, viewportHeight);
            SceneChrome.hint(target, viewportHeight, "Esc to keep the run");
            return;
        }
        menu.render(target, viewportWidth, viewportHeight);
        if (!status.isEmpty()) SceneChrome.status(target, viewportHeight, status, SceneChrome.OK);
        SceneChrome.hint(target, viewportHeight,
                "A run is saved on its own every couple of minutes, at every door, "
                        + "and whenever you quit");
    }
}
