package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
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

    public MainMenuScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        renaming = false;
        status = "";
        buildMenu();
    }

    private void buildMenu() {
        GameProfile p = ctx.profile();
        menu = new Menu(p.name)
                .subtitle("game type · " + p.perspective)
                .theme(MenuTheme.dark())
                .add("Play Level", () -> scenes.transitionTo("play"))
                .add("Load Level", () -> scenes.transitionTo("levelselect"));
        if (p.creativeEnabled) {
            menu.add("Creative Mode (paint a level)", () -> scenes.transitionTo("creative"));
        }
        menu.add("Multiplayer (Host / Join)", () -> scenes.transitionTo("multiplayer"))
                .add("Edit Features", () -> scenes.transitionTo("editor"))
                .add("Rename Game Type", this::startRename)
                .add("Change Game Type", () -> scenes.transitionTo("startup"))
                .add("Quit", () -> System.exit(0));
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

    @Override
    public void update(double dt, InputManager input) {
        if (renaming) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
                renaming = false;
                return;
            }
            renameForm.update(dt, input);
        } else {
            menu.update(dt, input);
        }
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        if (renaming) {
            g.setColor(new Color(18, 18, 28));
            g.fillRect(0, 0, viewportWidth, viewportHeight);
            renameForm.render(g, viewportWidth, viewportHeight);
            g.setColor(new Color(120, 120, 140));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("Type the new name · Enter/click Rename · Esc to cancel",
                    24, viewportHeight - 24);
            return;
        }
        menu.render(g, viewportWidth, viewportHeight);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (!status.isEmpty()) {
            g.setColor(new Color(140, 200, 140));
            g.drawString(status, 24, viewportHeight - 44);
        }
        g.setColor(new Color(120, 120, 140));
        g.drawString("Arrow keys / mouse to navigate, Enter to select",
                24, viewportHeight - 24);
    }
}
