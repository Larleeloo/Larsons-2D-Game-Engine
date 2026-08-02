package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GamePackage;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main menu for the active game type. Shows the game type's name and lets the
 * creator play the last level, load one of the game type's individual levels,
 * create a level, edit the type's default features, rename the game type, or
 * switch to a different one. Game types are a folder grouping of levels, and
 * each level carries its own feature toggles, so "Load Level" is where a
 * specific level (and its settings) is chosen.
 */
public class MainMenuScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;

    // Inline "rename game type" sub-form (null menu view otherwise).
    private boolean renaming;
    private ConfigForm renameForm;
    private String pendingName = "";
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

    public MainMenuScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        renaming = false;
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
                .theme(MenuTheme.dark())
                .add("Play Level", () -> scenes.transitionTo("play"))
                .add("Load Level", () -> scenes.transitionTo("levelselect"));
        // A finalized (published) game type is play-only: no creative editing,
        // feature edits, renames, or re-exports — just play its levels.
        if (p.creativeEnabled && !p.finalized) {
            menu.add("Creative Mode (pick a level format)", this::openCreativePicker);
        }
        menu.add("Multiplayer (Host / Join)", () -> scenes.transitionTo("multiplayer"));
        if (!p.finalized) {
            menu.add("Edit Features", () -> scenes.transitionTo("editor"))
                    .add("Rename Game Type", this::startRename)
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
     * three formats are three creative modes. Each opens the editor building
     * for that format — continuing this game type's last level when it was
     * built in the same format, or starting a fresh canvas in it otherwise —
     * and the counts show how many levels of each format the game type holds.
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
                        scenes.transitionTo("creative");
                    });
        }
        picker.add("Back", this::buildMenu);
        menu = picker;
    }

    private void startRename() {
        pendingName = ctx.profile().name;
        status = "";
        renaming = true;
        renameForm = new ConfigForm("Rename Game Type").theme(MenuTheme.dark());
        renameForm.addText("Game type name", () -> pendingName, v -> pendingName = v, 40);
        renameForm.addAction("Rename", this::applyRename);
        renameForm.addAction("Cancel", () -> renaming = false);
    }

    /**
     * Rename the active game type: move its levels folder (levels, doors,
     * custom content) and rewrite its profile under the new name, so the whole
     * grouping follows the rename. Refuses to overwrite another existing type.
     */
    private void applyRename() {
        GameProfile p = ctx.profile();
        String newName = pendingName == null || pendingName.isBlank() ? "" : pendingName.trim();
        renaming = false;
        if (newName.isEmpty() || newName.equals(p.name)) {
            buildMenu();
            return;
        }

        GameTypeStore store = ctx.store();
        String oldName = p.name;
        Path oldProfile = store.fileFor(oldName);
        Path newProfile = store.fileFor(newName);
        LevelStore oldLevels = new LevelStore(oldName);
        Path oldLevelsDir = oldLevels.directory();
        Path newLevelsDir = new LevelStore(newName).directory();
        boolean differentFile = !newProfile.equals(oldProfile);

        if (differentFile && (Files.exists(newProfile) || Files.exists(newLevelsDir))) {
            status = "A game type named \"" + newName + "\" already exists";
            buildMenu();
            return;
        }

        try {
            oldLevels.moveGameTypeFolderTo(newName); // levels + doors + custom content
        } catch (RuntimeException e) {
            status = "Could not move levels: " + e.getMessage();
            buildMenu();
            return;
        }

        p.name = newName;
        // The "last played level" pointer lives under the old folder — repoint
        // it into the moved one.
        if (differentFile && !p.lastLevelPath.isEmpty()) {
            Path last = Path.of(p.lastLevelPath);
            if (last.startsWith(oldLevelsDir)) {
                p.lastLevelPath = newLevelsDir.resolve(oldLevelsDir.relativize(last)).toString();
            }
        }
        ctx.save(); // writes gametypes/<new>.json
        if (differentFile) store.delete(oldName);
        status = "Renamed to \"" + newName + "\"";
        buildMenu();
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
        if (renaming) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                renaming = false;
                return;
            }
            renameForm.update(dt, input);
        } else if (exporting) {
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
        // Not yet ported off Graphics2D; see Java2DTarget.graphicsOf.
        Graphics2D g = Java2DTarget.graphicsOf(target);
        if (renaming) {
            g.setColor(new Color(18, 18, 28));
            g.fillRect(0, 0, viewportWidth, viewportHeight);
            renameForm.render(target, viewportWidth, viewportHeight);
            g.setColor(new Color(120, 120, 140));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("Type the new name · Enter/click Rename · Esc to cancel",
                    24, viewportHeight - 24);
            return;
        }
        if (exporting) {
            g.setColor(new Color(18, 18, 28));
            g.fillRect(0, 0, viewportWidth, viewportHeight);
            exportForm.render(target, viewportWidth, viewportHeight);
            g.setColor(new Color(120, 120, 140));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("Bundles this game type + all its levels into one .larsonsengine file · Esc to cancel",
                    24, viewportHeight - 24);
            return;
        }
        if (deleting) {
            g.setColor(new Color(28, 16, 16));
            g.fillRect(0, 0, viewportWidth, viewportHeight);
            deleteMenu.render(target, viewportWidth, viewportHeight);
            // Spell out exactly what will be lost, in a warning colour, in the
            // gap between the confirmation's subtitle and its choices.
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.setColor(new Color(235, 120, 120));
            int cx = viewportWidth / 2;
            int wy = viewportHeight / 4 + 96;
            String levels = deleteLevelCount == 1 ? "1 level" : deleteLevelCount + " levels";
            drawCentered(g, "Deletes this game type and all " + levels + " inside it.", cx, wy);
            drawCentered(g, "Its doors and custom content are removed too.", cx, wy + 24);
            g.setColor(new Color(120, 120, 140));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("Choose \"Delete permanently\" to confirm · Esc to cancel",
                    24, viewportHeight - 24);
            return;
        }
        menu.render(target, viewportWidth, viewportHeight);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (!status.isEmpty()) {
            g.setColor(new Color(140, 200, 140));
            g.drawString(status, 24, viewportHeight - 44);
        }
        g.setColor(new Color(120, 120, 140));
        g.drawString("Arrow keys / mouse to navigate, Enter to select",
                24, viewportHeight - 24);
    }

    private static void drawCentered(Graphics2D g, String s, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy);
    }
}
