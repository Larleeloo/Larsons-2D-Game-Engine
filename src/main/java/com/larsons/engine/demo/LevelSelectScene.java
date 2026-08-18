package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.nio.file.Path;
import java.util.List;

/**
 * "Load Level" screen: lists the individual levels saved inside the active
 * game type and, once you pick one, offers to <b>Play</b> it, open it in the
 * <b>creative editor</b>, or <b>Edit Settings</b> for it. Game types are a
 * folder grouping of levels ({@link LevelStore}), and each level carries its
 * own feature toggles — so this is the one place those per-level settings are
 * edited: the toggles live on the level, not the game type or the in-game
 * pause menu.
 *
 * <p>Three views:
 * <ul>
 *   <li><b>List</b> — the game type's levels; click one.</li>
 *   <li><b>Actions</b> — <i>Play</i>, <i>Edit in Creative</i> or <i>Edit
 *       Settings</i> for that level.</li>
 *   <li><b>Edit</b> — a feature form bound to the level's own settings, saved
 *       back into the level file.</li>
 * </ul>
 *
 * <p>Opening a saved level in the editor is this screen's job because this is
 * where the game type's levels are listed. Creative mode's own entry on the
 * main menu builds a <em>new</em> level ({@link NewLevelScene}); the editor
 * itself reopens whichever level it was left on. Between them there was no way
 * to say "edit that one" without first playing it and pausing.
 */
public class LevelSelectScene extends AbstractScene {

    private enum View { LIST, ACTIONS, EDIT }

    private final GameContext ctx;
    /** Where this game type's levels live; overridden by tests (see {@link LevelStore}). */
    private final String levelsRoot;
    private View view = View.LIST;
    private Menu menu;
    private ConfigForm form;
    private String selectedLevel;  // level name chosen in the list
    private Level editLevel;       // the level loaded for settings editing
    private String status = "";

    public LevelSelectScene(GameContext ctx) { this(ctx, LevelStore.DEFAULT_DIR); }

    /** Root-dir override, mirroring {@link LevelStore}'s (used by tests). */
    LevelSelectScene(GameContext ctx, String levelsRoot) {
        this.ctx = ctx;
        this.levelsRoot = levelsRoot;
    }

    @Override
    public void onEnter() {
        view = View.LIST;
        selectedLevel = null;
        editLevel = null;
        status = "";
        buildListMenu();
    }

    private LevelStore store() { return new LevelStore(levelsRoot, ctx.profile().name); }

    /**
     * The list of levels in the active game type, each tagged with the format
     * it was built in — a game type holds side-scrolling, top-down and
     * isometric levels side by side, and playing one just loads it in its own
     * format.
     */
    private void buildListMenu() {
        GameProfile p = ctx.profile();
        menu = new Menu("Load Level")
                .subtitle(p.name + " · pick a level")
                .theme(MenuTheme.dark());
        LevelStore store = store();
        List<String> names = store.list();
        for (String name : names) {
            LevelFormat format = store.formatOf(name);
            menu.add(name + "  ·  " + format.displayName(), () -> openActions(name));
        }
        if (names.isEmpty()) {
            String empty = p.finalized || !p.creativeEnabled
                    ? "(no levels in this game type)"
                    : "(no saved levels yet — make one in Creative Mode)";
            // Making one starts where the main menu starts: the New Level
            // screen, which names and sets up the level before creating it.
            menu.add(empty, () -> {
                if (p.creativeEnabled && !p.finalized) {
                    scenes.transitionTo(NewLevelScene.NAME);
                }
            });
        }
        menu.add("Back", () -> scenes.transitionTo("menu"));
    }

    /**
     * Play (and, for editable game types, build or configure) the chosen level.
     * The level opens in the creative mode its own format is authored in, so
     * picking a top-down level here is the same editor picking Top-Down on the
     * main menu opens.
     */
    private void openActions(String name) {
        GameProfile p = ctx.profile();
        selectedLevel = name;
        view = View.ACTIONS;
        LevelFormat format = store().formatOf(name);
        // A finalized (published) game type is play-only — no building, no
        // settings editing.
        menu = new Menu(name)
                .subtitle(format.displayName() + " · "
                        + (p.finalized ? "play this level" : "play, build or configure this level"))
                .theme(MenuTheme.dark())
                .add("Play Level", this::playSelected);
        if (!p.finalized) {
            if (p.creativeEnabled) {
                menu.add("Edit in Creative (" + format.displayName() + ")", this::editInCreative);
            }
            menu.add("Edit Settings", this::openEditor);
        }
        menu.add("Back", () -> { view = View.LIST; buildListMenu(); });
    }

    private void playSelected() {
        GameProfile p = ctx.profile();
        // PlayScene loads lastLevelPath and applies that level's own settings.
        p.lastLevelPath = store().fileFor(selectedLevel).toString();
        ctx.save();
        scenes.transitionTo("play");
    }

    /**
     * Open the chosen level in the creative editor, in the format it was built
     * in and with its own settings in force — the same thing the pause menu's
     * <i>Edit in Creative</i> does for the level being played, without having
     * to play it first.
     */
    private void editInCreative() {
        Level level;
        try {
            level = store().load(selectedLevel);
        } catch (RuntimeException e) {
            status = "Could not load \"" + selectedLevel + "\": " + e.getMessage();
            return;
        }
        // The level's own creative toggle is what the pause menu obeys, so it
        // is obeyed here too — and Edit Settings, one row down, is where it is
        // turned back on.
        if (level.settings != null && !level.settings.creativeEnabled) {
            status = "\"" + selectedLevel + "\" has creative mode turned off — "
                    + "turn it on in Edit Settings to build in it";
            return;
        }
        handToCreative(ctx, store().fileFor(selectedLevel), level);
        scenes.transitionTo("creative");
    }

    /**
     * Make {@code level} the one the editor opens: the game type's current
     * level (so the editor returns to it), its own settings in force, and the
     * loaded object itself handed over rather than read from disk again.
     */
    static void handToCreative(GameContext ctx, Path file, Level level) {
        GameProfile p = ctx.profile();
        p.lastLevelPath = file.toString();
        ctx.save();
        ctx.applyLevelSettings(level.settings);
        ctx.setPendingCreativeLevel(level);
    }

    /** Open the per-level editor: rename the level and edit its own settings. */
    private void openEditor() {
        try {
            editLevel = store().load(selectedLevel);
        } catch (RuntimeException e) {
            status = "Could not load \"" + selectedLevel + "\": " + e.getMessage();
            return;
        }
        // Legacy levels (no settings of their own) start from the game type's
        // template, so the form always has something concrete to edit.
        if (editLevel.settings == null) editLevel.settings = ctx.profile().copy();
        view = View.EDIT;
        status = "";
        buildEditForm();
    }

    private void buildEditForm() {
        form = new ConfigForm("Settings — " + editLevel.name).theme(MenuTheme.dark());
        form.addText("Level name", () -> editLevel.name, v -> editLevel.name = v, 32);
        // The level's own format: which creative mode edits it and how it
        // plays. The only format control there is — everything below is this
        // level's own feature settings.
        form.addEnum("Level format", LevelFormat.values(),
                () -> editLevel.format(), v -> editLevel.setFormat(v));
        // The level's character roster and its feature toggles: the same rows
        // the New Level screen asks before the level exists (see ProfileForms).
        // The creative editor's Characters palette has the roster control too;
        // this is the one here because per-level settings are edited on this
        // screen.
        ProfileForms.addRosterOptions(form, ctx.profile().name, editLevel.characters);
        ProfileForms.addFeatureOptions(form, editLevel.settings);
        ProfileForms.addCameraOptions(form, editLevel.settings);
        form.addAction("Save", this::saveEdited);
        form.addAction("Back", () -> openActions(selectedLevel));
    }

    /** Persist the level's new name + settings, renaming its file if needed. */
    private void saveEdited() {
        LevelStore store = store();
        Path oldFile = store.fileFor(selectedLevel);
        editLevel.name = editLevel.name == null || editLevel.name.isBlank()
                ? "Untitled" : editLevel.name.trim();
        // The level's format is the level's, so its saved settings must not
        // claim a different one when they are applied on load.
        editLevel.settings.perspective = editLevel.perspective;
        editLevel.settings.normalize();
        Path newFile = store.save(editLevel);
        if (!newFile.equals(oldFile)) {
            // Renamed: drop the old file and follow the rename everywhere it's
            // referenced (the game type's "last played" pointer).
            store.delete(selectedLevel);
            GameProfile p = ctx.profile();
            if (oldFile.toString().equals(p.lastLevelPath)) {
                p.lastLevelPath = newFile.toString();
                ctx.save();
            }
            selectedLevel = editLevel.name;
        }
        buildEditForm(); // refresh the title after a possible rename
        status = "Saved \"" + editLevel.name + "\"";
    }

    @Override
    public void update(double dt, InputManager input) {
        // Esc steps back one view (edit → actions → list → main menu).
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            switch (view) {
                case EDIT -> openActions(selectedLevel);
                case ACTIONS -> { view = View.LIST; buildListMenu(); }
                case LIST -> scenes.transitionTo("menu");
            }
            return;
        }
        if (view == View.EDIT) {
            form.update(dt, input);
        } else {
            menu.update(dt, input);
        }
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        SceneChrome.backdrop(target, viewportWidth, viewportHeight);
        if (view == View.EDIT) {
            form.render(target, viewportWidth, viewportHeight);
        } else {
            menu.render(target, viewportWidth, viewportHeight);
        }
        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, SceneChrome.OK);
        }
        SceneChrome.hint(target, viewportHeight, hint());
    }

    private String hint() {
        return switch (view) {
            case LIST -> "Each level loads in its own format, with its own toggles · Esc to go back";
            case ACTIONS -> "Play the level, build in it, or edit the settings it plays with · Esc to go back";
            case EDIT -> "Rename the level or change its toggles · type to edit the name · Save to keep · Esc to go back";
        };
    }
}
