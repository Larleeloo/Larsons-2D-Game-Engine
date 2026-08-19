package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeRename;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;


/**
 * Launch-time game-type editor: name the game type. That is the whole of it.
 *
 * <p><b>A game type is a folder of levels, not a configuration.</b> Every level
 * carries the settings it plays with ({@link com.larsons.engine.level.Level#settings}),
 * so anything set here could only ever be the template a newly created level
 * starts from — a value that acts at a distance, days later, from a screen the
 * creator has long since left, and that the level's own settings form will
 * cheerfully contradict. Asking the same question in two places, where only one
 * of them is visible at the point it takes effect, is how a map maker ends up
 * with a level whose mobs are off for no reason they can see.
 *
 * <p>So the feature set is fixed at the engine defaults
 * ({@link GameProfile#resetFeaturesToDefaults()}) and every decision is made per
 * level, in <em>Level Select → Edit Settings</em>, next to the level it applies
 * to. The profile is still saved as JSON into {@code resources/gametypes/} —
 * it carries the name, the texture pack, the sound pack and which level was
 * last played.
 */
public class GameTypeEditorScene extends AbstractScene {
    private final GameContext ctx;
    private ConfigForm form;
    private String status = "";
    /** The name being typed, applied by {@link #applyName()} rather than live. */
    private String pendingName = "";

    public GameTypeEditorScene(GameContext ctx) { this.ctx = ctx; }

    /**
     * Put the typed name into force, renaming the game type's whole folder when
     * it has one. Returns whether the screen may go on — a refused rename (the
     * name is taken, or the levels could not be moved) says so and stays put
     * rather than continuing under a name that did not take.
     */
    private boolean applyName() {
        GameTypeRename.Result result = GameTypeRename.apply(ctx, pendingName);
        if (!result.message().isEmpty()) status = result.message();
        if (!result.renamed() && !result.message().isEmpty()) {
            // The only messages a no-op rename carries are refusals; a name
            // that did not change is silent.
            pendingName = ctx.profile().name;
            return false;
        }
        return true;
    }

    @Override
    public void onEnter() {
        GameProfile p = ctx.profile();
        status = "";

        // The game type's own feature values are always the defaults, so the
        // template every new level starts from is one predictable thing rather
        // than whatever a previous session happened to leave behind.
        p.resetFeaturesToDefaults();

        // The name is edited into a field of its own rather than straight into
        // the profile: writing it there and saving would file a *second* game
        // type under the new name and leave the levels behind under the old one.
        // Applying it is a rename (GameTypeRename), which moves the folder, the
        // doors, the custom content and the saved runs with it.
        pendingName = p.name;
        form = new ConfigForm("Create / Edit Game Type").theme(MenuTheme.dark());
        form.addText("Game type name", () -> pendingName, v -> pendingName = v, 40);
        form.addNote("A game type is just a folder of levels. Every level carries its own "
                + "settings — format, gravity, mobs, combat, lighting, shaders, zoom — and you "
                + "edit them per level in Level Select → Edit Settings, next to the level "
                + "they apply to.");
        form.addAction("Save Game Type", () -> {
            if (!applyName()) return;
            p.normalize();
            ctx.save();
            ctx.applyLiveSettings();
            status = "Saved to " + ctx.store().fileFor(p.name);
        });
        form.addAction("Start Creating", () -> {
            if (!applyName()) return;
            p.normalize();
            ctx.applyLiveSettings();
            scenes.transitionTo("menu");
        });
        // Key binds are the player's, not the game type's, so this opens the
        // shared controls screen rather than saving anything into the profile.
        form.addAction("Controls (Key Binds)", () -> KeyBindsScene.open(scenes, "editor"));
        form.addAction("Back", () -> scenes.transitionTo("startup"));
    }

    @Override
    public void update(double dt, InputManager input) {
        form.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        SceneChrome.backdrop(target, viewportWidth, viewportHeight);
        form.render(target, viewportWidth, viewportHeight);

        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, SceneChrome.OK);
        }
        SceneChrome.hint(target, viewportHeight,
                "Up/Down select · Left/Right adjust · Enter/click activate · wheel/scroll bar to scroll · type to name");
    }
}
