package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

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
 * level, in <em>Load Level → Edit Settings</em>, next to the level it applies
 * to. The profile is still saved as JSON into {@code resources/gametypes/} —
 * it carries the name, the texture pack, the sound pack and which level was
 * last played.
 */
public class GameTypeEditorScene extends AbstractScene {
    private final GameContext ctx;
    private ConfigForm form;
    private String status = "";

    public GameTypeEditorScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        GameProfile p = ctx.profile();
        status = "";

        // The game type's own feature values are always the defaults, so the
        // template every new level starts from is one predictable thing rather
        // than whatever a previous session happened to leave behind.
        p.resetFeaturesToDefaults();

        form = new ConfigForm("Create / Edit Game Type").theme(MenuTheme.dark());
        form.addText("Game type name", () -> p.name, v -> p.name = v, 40);
        form.addNote("A game type is just a folder of levels. Every level carries its own "
                + "settings — format, gravity, mobs, combat, lighting, shaders, zoom — and you "
                + "edit them per level in Load Level → Edit Settings, next to the level "
                + "they apply to.");
        form.addAction("Save Game Type", () -> {
            p.normalize();
            ctx.save();
            ctx.applyLiveSettings();
            status = "Saved to " + ctx.store().fileFor(p.name);
        });
        form.addAction("Start Creating", () -> {
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
    public void render(Graphics2D g, float alpha) {
        g.setColor(new Color(18, 18, 28));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        form.render(g, viewportWidth, viewportHeight);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.setColor(new Color(140, 200, 140));
        if (!status.isEmpty()) g.drawString(status, 24, viewportHeight - 44);
        g.setColor(new Color(120, 120, 140));
        g.drawString("Up/Down select · Left/Right adjust · Enter/click activate · wheel/scroll bar to scroll · type to name",
                24, viewportHeight - 24);
    }
}
