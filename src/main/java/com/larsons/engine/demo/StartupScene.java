package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.List;

/**
 * Launch screen: pick an existing game type to keep creating within it, or
 * create a new one. Existing game types are loaded from
 * {@code resources/gametypes/}.
 */
public class StartupScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;

    public StartupScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        // Rebuild every time so newly created/saved game types appear.
        menu = new Menu("Larson's 2D Game Engine")
                .subtitle("Choose a game type to continue, or create a new one")
                .theme(MenuTheme.dark());

        List<GameProfile> profiles = ctx.store().listProfiles();
        for (GameProfile p : profiles) {
            menu.add(p.name + "   (" + p.perspective + ")", () -> {
                ctx.setProfile(p);
                scenes.transitionTo("menu");
            });
        }

        menu.add("+ Create New Game Type", () -> {
            ctx.setProfile(new GameProfile());
            scenes.transitionTo("editor");
        });
        menu.add("Auto Battler (2-10 Online)", () -> scenes.transitionTo("autolobby"));
        menu.add("Quit", () -> System.exit(0));
    }

    @Override
    public void update(double dt, InputManager input) {
        menu.update(dt, input);
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        menu.render(g, viewportWidth, viewportHeight);
        g.setColor(new Color(120, 120, 140));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Game types are saved as JSON under resources/gametypes/",
                24, viewportHeight - 24);
    }
}
