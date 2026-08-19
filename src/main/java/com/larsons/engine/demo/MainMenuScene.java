package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GamePackage;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.save.RunRecord;
import com.larsons.engine.save.SaveStore;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Main menu for the active game type. Shows the game type's name and lets the
 * creator continue a run, pick one of the game type's levels, create a level,
 * edit the game type itself, or switch to a different one. Game types are a
 * folder grouping of levels, and each level carries its own feature toggles, so
 * <em>Level Select</em> is where a specific level (and its settings) is chosen.
 */
public class MainMenuScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;

    private String status = "";

    // Inline "export game type" sub-form.
    private boolean exporting;
    private ConfigForm exportForm;
    private boolean exportFinalized;

    // Inline "delete game type" confirmation — destructive, so it is guarded by
    // an explicit warning and a second, deliberate choice (see startDelete).
    private boolean deleting;
    private Menu deleteMenu;
    private int deleteLevelCount;

    /** The delete confirmation reads on a red-tinted backdrop, not the usual near-black. */
    private static final Color DANGER_BACKDROP = new Color(28, 16, 16);
    private static final Font WARNING_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Color WARNING = new Color(235, 120, 120);

    public MainMenuScene(GameContext ctx) { this.ctx = ctx; }

    /** The menu as it currently stands, exposed so tests can read the entries. */
    public Menu menu() {
        if (menu == null) buildMenu();
        return menu;
    }

    @Override
    public void onEnter() {
        exporting = false;
        deleting = false;
        status = "";
        buildMenu();
    }

    private void buildMenu() {
        GameProfile p = ctx.profile();
        menu = new Menu(p.name)
                .subtitle("game type · levels in any format"
                        + (p.finalized ? " · finalized (play-only)" : ""))
                .theme(MenuTheme.dark());
        // Continue comes first when there is something to continue, because it
        // is what somebody returning to the game came here to press. A game
        // type with no saved run does not show it at all rather than showing it
        // greyed out — an entry that has never once worked is just clutter.
        String recent = SaveSelectScene.mostRecentSlot(p.name);
        if (recent != null) {
            RunRecord run = SaveSelectScene.runIn(p.name, recent);
            menu.add("Continue — " + run.describe(), () -> {
                ctx.continueRun(recent);
                scenes.transitionTo("play");
            });
        }
        // "Level Select" and nothing beside it. There used to be a "Play Level"
        // row above this one, which opened whichever level the profile happened
        // to point at last — a play button whose level was chosen days ago,
        // somewhere else, and which the menu could not name. Choosing the level
        // is the same click, one screen along, and that screen says what it is
        // about to open.
        menu.add("Level Select", () -> scenes.transitionTo("levelselect"))
                .add("Saved Runs", () -> scenes.transitionTo(SaveSelectScene.NAME));
        // A finalized (published) game type is play-only: no creative editing,
        // feature edits, renames, or re-exports — just play its levels.
        if (p.creativeEnabled && !p.finalized) {
            menu.add("Creative Mode (new level)", this::openCreativePicker);
        }
        menu.add("Multiplayer (Host / Join)", () -> scenes.transitionTo("multiplayer"));
        if (!p.finalized) {
            // One entry for editing the game type, not two. "Rename Game Type"
            // used to sit here beside it and the two had grown into the same
            // screen: what a game type still has of its own is its name, so the
            // editor is the rename (see GameTypeRename).
            menu.add("Edit Game Type (name, features)", () -> scenes.transitionTo("editor"))
                    .add("Export Game Type (.larsonsengine)", this::startExport);
        }
        // Controls belong to the player rather than to the game type, so the
        // entry is here whether the type is finalized or not.
        menu.add("Controls (Key Binds)", () -> KeyBindsScene.open(scenes, "menu"));
        // Deleting is library management (removing a type you no longer want),
        // not content editing, so it stays available even for finalized types.
        menu.add("Delete Game Type", this::startDelete)
                .add("Change Game Type", () -> scenes.transitionTo("startup"))
                .add("Quit", () -> System.exit(0));
    }

    /**
     * The creative-mode picker: one entry per {@link LevelFormat}, because the
     * three formats are three creative modes. Picking one is the first of two
     * questions — it leads to the {@link NewLevelScene} settings screen, where
     * the level is named, sized and given the toggles it will play with, and
     * the level itself is created from there. The counts show how many levels
     * of each format the game type already holds.
     */
    private void openCreativePicker() {
        GameProfile p = ctx.profile();
        LevelStore store = new LevelStore(p.name);
        Menu picker = new Menu("Creative Mode")
                .subtitle(p.name + " · which kind of level are you building?")
                .theme(MenuTheme.dark());
        for (LevelFormat format : LevelFormat.values()) {
            int saved = store.list(format).size();
            picker.add(format.displayName() + "  (" + saved
                            + (saved == 1 ? " level)" : " levels)"),
                    () -> {
                        ctx.setCreativeFormat(format);
                        scenes.transitionTo(NewLevelScene.NAME);
                    });
        }
        picker.add("Back", this::buildMenu);
        menu = picker;
    }

    private void startExport() {
        exportFinalized = false;
        status = "";
        exporting = true;
        exportForm = new ConfigForm("Export Game Type").theme(MenuTheme.dark());
        exportForm.addToggle("Finalize (recipients can only play)",
                () -> exportFinalized, v -> exportFinalized = v);
        exportForm.addNote("A finalized copy plays its levels but cannot be "
                + "edited, renamed, or re-exported.");
        exportForm.addAction("Export", this::applyExport);
        exportForm.addAction("Cancel", () -> { exporting = false; buildMenu(); });
    }

    /**
     * Package the active game type — its profile and every level (with the
     * doors and custom content wiring them together) — into a single
     * {@code .larsonsengine} file next to the jar (or in {@code share/} in dev),
     * ready to hand to someone else. Finalizing marks the packaged copy
     * play-only without touching this editable local copy.
     */
    private void applyExport() {
        GameProfile p = ctx.profile();
        boolean finalize = exportFinalized;
        exporting = false;
        try {
            Path out = GamePackage.export(p, new LevelStore(p.name),
                    GamePackage.dropInDir(), finalize);
            status = "Exported " + (finalize ? "finalized " : "") + "game type to " + out;
        } catch (IOException | RuntimeException e) {
            status = "Export failed: " + e.getMessage();
        }
        buildMenu();
    }

    /**
     * Open the delete confirmation for the active game type. "Cancel" is the
     * first (and therefore default-selected) choice, so a stray Enter backs out
     * instead of deleting; removing the type is the deliberate second choice.
     * The number of levels about to be lost is captured up front for the warning.
     */
    private void startDelete() {
        GameProfile p = ctx.profile();
        status = "";
        deleting = true;
        deleteLevelCount = new LevelStore(p.name).list().size();
        deleteMenu = new Menu("Delete \"" + p.name + "\"?")
                .subtitle("This permanently removes the game type — it cannot be undone")
                .theme(MenuTheme.dark())
                .add("Cancel — keep this game type", () -> deleting = false)
                .add("Delete permanently", this::applyDelete);
    }

    /**
     * Permanently remove the active game type: its whole levels folder (levels,
     * doors, and custom content) and then its profile JSON. Afterwards there is
     * no active type to return to, so drop back to the startup picker where a
     * different type can be chosen or a new one created.
     */
    private void applyDelete() {
        GameProfile p = ctx.profile();
        deleting = false;
        try {
            new LevelStore(p.name).deleteGameTypeFolder(); // levels + doors + custom content
            SaveStore.deleteGameTypeSaves(p.name);         // every run through them
            ctx.store().delete(p.name);                    // the gametypes/<name>.json profile
        } catch (RuntimeException e) {
            status = "Delete failed: " + e.getMessage();
            buildMenu();
            return;
        }
        scenes.transitionTo("startup");
    }

    @Override
    public void update(double dt, InputManager input) {
        if (exporting) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                exporting = false;
                return;
            }
            exportForm.update(dt, input);
        } else if (deleting) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                deleting = false; // Esc backs out of the destructive confirmation
                return;
            }
            deleteMenu.update(dt, input);
        } else {
            menu.update(dt, input);
        }
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        if (exporting) {
            SceneChrome.backdrop(target, viewportWidth, viewportHeight);
            exportForm.render(target, viewportWidth, viewportHeight);
            SceneChrome.hint(target, viewportHeight,
                    "Bundles this game type + all its levels into one .larsonsengine file · Esc to cancel");
            return;
        }
        if (deleting) {
            target.fillRect(0, 0, viewportWidth, viewportHeight, DANGER_BACKDROP);
            deleteMenu.render(target, viewportWidth, viewportHeight);
            // Spell out exactly what will be lost, in a warning colour, in the
            // gap between the confirmation's subtitle and its choices.
            int cx = viewportWidth / 2;
            int wy = viewportHeight / 4 + 96;
            String levels = deleteLevelCount == 1 ? "1 level" : deleteLevelCount + " levels";
            drawCentered(target, "Deletes this game type and all " + levels + " inside it.", cx, wy);
            drawCentered(target, "Its doors and custom content are removed too.", cx, wy + 24);
            SceneChrome.hint(target, viewportHeight,
                    "Choose \"Delete permanently\" to confirm · Esc to cancel");
            return;
        }
        menu.render(target, viewportWidth, viewportHeight);
        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, SceneChrome.OK);
        }
        SceneChrome.hint(target, viewportHeight,
                "Arrow keys / mouse to navigate, Enter to select");
    }

    /** The two warning lines, centred on {@code cx}, in the delete confirmation. */
    private static void drawCentered(DrawTarget target, String s, int cx, int cy) {
        target.drawText(s, cx - target.textWidth(s, WARNING_FONT) / 2, cy,
                WARNING_FONT, WARNING);
    }
}
