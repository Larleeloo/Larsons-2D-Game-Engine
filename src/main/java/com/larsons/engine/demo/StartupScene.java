package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
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
        menu.add("Council of Six (Deckbuilder, 2-6 Online)",
                () -> scenes.transitionTo("decklobby"));
        menu.add("Evolution (Artificial Life Simulator)",
                () -> scenes.transitionTo("evolutionlobby"));
        // Controls are a property of the player, not of a game type, so they
        // are reachable before one is even chosen.
        menu.add("Controls (Key Binds)", () -> KeyBindsScene.open(scenes, "startup"));
        menu.add("Quit", () -> System.exit(0));
    }

    /** The launch menu, exposed so tests can assert what the game offers. */
    public Menu menu() { return menu; }

    @Override
    public void update(double dt, InputManager input) {
        menu.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        // Not yet ported off Graphics2D; see Java2DTarget.graphicsOf.
        Graphics2D g = Java2DTarget.graphicsOf(target);
        menu.render(g, viewportWidth, viewportHeight);
        g.setColor(new Color(120, 120, 140));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Game types are saved as JSON under resources/gametypes/",
                24, viewportHeight - 24);
    }
}
