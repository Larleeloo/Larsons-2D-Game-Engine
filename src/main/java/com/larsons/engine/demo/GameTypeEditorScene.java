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
 * Launch-time game-type editor: name the game type and set the <em>default</em>
 * features its levels start from. Toggles are stored per level, so these are the
 * template a newly created level inherits (and can then diverge from); each
 * level saves its own settings. Edits the active {@link GameProfile} in place
 * and saves it (as JSON) into {@code resources/gametypes/}.
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
        form = new ConfigForm("Create / Edit Game Type").theme(MenuTheme.dark());
        form.addText("Game type name", () -> p.name, v -> p.name = v, 40);
        ProfileForms.addFeatureOptions(form, p);
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
