package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>New Level</b> screen: the settings a level is created <em>with</em>.
 * Reached from the main menu's <em>Creative Mode</em> picker, one step after
 * the level format is chosen and one step before the editor opens.
 *
 * <p><b>Why the level is not created at the picker.</b> Choosing a format used
 * to drop the creator straight into the editor on a canvas called "New
 * Side-Scroller Level", sized 60&times;24, carrying whatever toggles happened
 * to be active — every one of those decisions already made, and each of them
 * only changeable afterwards somewhere else (the name in the editor's <em>Save</em>
 * dialog, the size in its <em>New Level</em> dialog, the toggles in <em>Load
 * Level → Edit Settings</em>). Asking first is the same set of questions in one
 * place, at the moment they are actually being decided, so the level that
 * appears is the level that was asked for.
 *
 * <p>The form is the per-level settings form — {@link ProfileForms}, the same
 * rows <em>Edit Settings</em> shows — above the two things only a new level has
 * to answer: what it is called and how big the canvas is. <b>Create Level</b>
 * builds the starter canvas for the chosen format, saves it into the game type
 * (so it is listed under <em>Load Level</em> and is what the editor returns to),
 * and opens the editor on it.
 */
public class NewLevelScene extends AbstractScene {

    /** The name this scene is registered under. */
    public static final String NAME = "newlevel";

    /** The starter canvas a level begins at, in tiles, unless it is resized here. */
    private static final int DEFAULT_WIDTH = 60, DEFAULT_HEIGHT = 24;

    /** Smallest canvas the sliders offer — enough room to build something in. */
    private static final int MIN_SIZE = 8;

    /**
     * Largest canvas the sliders offer. Bigger maps are a deliberate choice
     * (they are chunked, and a 65536&sup2; one is not a canvas anybody drags a
     * slider to), so they are behind the same override toggle the editor's own
     * New Level dialog uses.
     */
    private static final int STANDARD_MAX_SIZE = 1024;

    private final GameContext ctx;

    // The level being described. Nothing here is a Level yet: a canvas is only
    // allocated when Create Level is chosen, so setting the size to 65536 while
    // deciding costs nothing.
    private LevelFormat format = LevelFormat.SIDE_SCROLLER;
    private String levelName = "";
    private int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT;
    private boolean overrideMapSize;
    private GameProfile settings = new GameProfile();
    private final List<String> roster = new ArrayList<>();

    /**
     * Whether {@link #levelName} is still the name this screen suggested. A
     * suggested name follows the format row ("New 3D Level"); a name the
     * creator typed is theirs and is left alone.
     */
    private boolean nameIsSuggested = true;

    /**
     * The game type's last-edited level when it is in the chosen format — the
     * one the picker used to reopen by itself. Offered as a choice here instead
     * of happening instead of what was asked for. Blank when there isn't one.
     */
    private String continueLevel = "";

    private ConfigForm form;
    private String status = "";

    public NewLevelScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        LevelFormat requested = ctx.takeCreativeFormat();
        format = requested != null ? requested : LevelFormat.of(ctx.profile().perspective);
        // A new level starts from the configuration in force and then carries
        // its own copy of it, which is what saving a level has always written.
        settings = ctx.profile().copy();
        roster.clear();
        width = DEFAULT_WIDTH;
        height = DEFAULT_HEIGHT;
        overrideMapSize = false;
        levelName = suggestedName(format);
        nameIsSuggested = true;
        status = "";
        continueLevel = lastLevelInFormat();
        buildForm();
    }

    private LevelStore store() { return new LevelStore(ctx.profile().name); }

    private String suggestedName(LevelFormat format) {
        return suggestedName(store(), format);
    }

    /**
     * A name that is free in {@code store}: "New 3D Level", then "New
     * Top-Down Level 2", and so on. Creating a level must never quietly land on
     * top of one that is already there, and the first thing this screen shows
     * is the name it would use.
     */
    static String suggestedName(LevelStore store, LevelFormat format) {
        String base = "New " + format.displayName() + " Level";
        if (!store.exists(base)) return base;
        for (int i = 2; i <= 999; i++) {
            String candidate = base + " " + i;
            if (!store.exists(candidate)) return candidate;
        }
        return base;
    }

    /**
     * The game type's last-edited level if it was built in the chosen format,
     * else blank. Found by matching the profile's {@code lastLevelPath} against
     * the game type's own files, so the name shown is the one the level is
     * listed under everywhere else.
     */
    private String lastLevelInFormat() {
        String last = ctx.profile().lastLevelPath;
        if (last == null || last.isEmpty()) return "";
        LevelStore store = store();
        Path file = Path.of(last).toAbsolutePath().normalize();
        for (String name : store.list()) {
            if (store.fileFor(name).toAbsolutePath().normalize().equals(file)) {
                return store.formatOf(name) == format ? name : "";
            }
        }
        return "";
    }

    /**
     * The screen: what the level is, then <b>Create Level</b>, then the
     * settings it will carry. The action sits above the toggle list rather than
     * below it because the rows above it are the ones a new level cannot do
     * without — everything under it has a working default and can be changed
     * again later in <em>Edit Settings</em>.
     */
    private void buildForm() {
        form = new ConfigForm("New Level — " + format.displayName())
                .theme(MenuTheme.dark());
        form.addText("Level name", () -> levelName,
                v -> { levelName = v; nameIsSuggested = false; }, 32);
        // The format arrives chosen from the picker; changing it here is the
        // same decision, not a second one, so the suggested name follows it.
        form.addEnum("Level format", LevelFormat.values(), () -> format, v -> {
            if (v == null || v == format) return;
            format = v;
            if (nameIsSuggested) levelName = suggestedName(format);
            continueLevel = lastLevelInFormat();
            status = "";
            rebuildForm();
        });
        form.addNote(format.description());
        form.addToggle("Override map size (up to " + Level.MAX_GIANT_SIZE + ")",
                () -> overrideMapSize, v -> {
                    overrideMapSize = v;
                    if (!v) {
                        // Back on the sliders, so the size has to be one they
                        // can show — a giant left behind here would be created
                        // while the row said 1024.
                        width = Math.min(width, STANDARD_MAX_SIZE);
                        height = Math.min(height, STANDARD_MAX_SIZE);
                    }
                    rebuildForm();
                });
        if (overrideMapSize) {
            form.addInt("Width (tiles)", () -> width, v -> width = v,
                    MIN_SIZE, Level.MAX_GIANT_SIZE, 512);
            form.addInt("Height (tiles)", () -> height, v -> height = v,
                    MIN_SIZE, Level.MAX_GIANT_SIZE, 512);
        } else {
            form.addSlider("Width (tiles)", () -> width, v -> width = v,
                    MIN_SIZE, STANDARD_MAX_SIZE);
            form.addSlider("Height (tiles)", () -> height, v -> height = v,
                    MIN_SIZE, STANDARD_MAX_SIZE);
        }

        form.addAction("Create Level", this::createLevel);
        if (!continueLevel.isEmpty()) {
            form.addAction("Continue editing \"" + continueLevel + "\" instead",
                    () -> scenes.transitionTo("creative"));
        }
        form.addNote("Everything below is this level's own settings — the same "
                + "list Load Level → Edit Settings shows, saved into the level "
                + "when it is created.");
        ProfileForms.addRosterOptions(form, ctx.profile().name, roster);
        ProfileForms.addFeatureOptions(form, settings);
        form.addAction("Back", () -> scenes.transitionTo("menu"));
    }

    /**
     * Rebuild the form from a row that changed which rows there are (the
     * format, the size override), keeping the cursor on the row that was
     * touched rather than throwing it back to the top of the screen.
     */
    private void rebuildForm() {
        int cursor = form.getSelectedIndex();
        buildForm();
        form.select(cursor);
    }

    /**
     * Create the level this screen describes and open the editor on it: the
     * format's starter canvas at the chosen size, carrying the name, roster and
     * settings asked for. It is saved straight away, so a level that has been
     * created is a level that exists — listed under <em>Load Level</em>, and
     * the one the editor comes back to.
     */
    private void createLevel() {
        String name = levelName == null || levelName.isBlank()
                ? "Untitled" : levelName.trim();
        LevelStore store = store();
        if (store.exists(name)) {
            // Saving would overwrite that level's file. The suggested name is
            // free by construction, so this is only reachable by typing one.
            status = "A level named \"" + name + "\" already exists in "
                    + ctx.profile().name + " — pick another name";
            return;
        }

        Level level;
        try {
            level = create(store, name, format, width, height, settings, roster);
        } catch (RuntimeException e) {
            status = "Could not create \"" + name + "\": " + e.getMessage();
            return;
        }

        GameProfile p = ctx.profile();
        p.lastLevelPath = store.fileFor(level.name).toString();
        ctx.save();
        // The editor opens the level this screen built, with the settings it
        // was given — not the game type's, and not the last level's.
        ctx.applyLevelSettings(level.settings);
        ctx.setPendingCreativeLevel(level);
        scenes.transitionTo("creative");
    }

    /**
     * Build a level from this screen's answers and save it into {@code store}.
     * Separated from the screen so the creation itself — the starter canvas,
     * the settings the level carries, and the file it lands in — can be checked
     * without a form in front of it.
     */
    static Level create(LevelStore store, String name, LevelFormat format,
                        int width, int height, GameProfile settings,
                        List<String> roster) {
        Level level = format.starterLevel(name, width, height, settings.tileSize);
        level.characters.addAll(roster);
        // captureSettings pins the saved perspective to the level's own format,
        // so the copy stored inside the level can never contradict it.
        level.captureSettings(settings);
        level.settings.normalize();
        store.save(level);
        return level;
    }

    @Override
    public void update(double dt, InputManager input) {
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            scenes.transitionTo("menu");
            return;
        }
        form.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        SceneChrome.backdrop(target, viewportWidth, viewportHeight);
        form.render(target, viewportWidth, viewportHeight);
        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, SceneChrome.PROMPT);
        }
        SceneChrome.hint(target, viewportHeight,
                "Name it, size it, set what it plays with · \"Create Level\" builds it "
                        + "and opens the editor · Esc to go back");
    }
}
