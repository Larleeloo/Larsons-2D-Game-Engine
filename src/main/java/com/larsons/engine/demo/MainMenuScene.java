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

/**
 * Main menu for the active game type. Shows the game type's name and lets the
 * creator play/create a level, edit the type's features, or switch to a
 * different game type.
 */
public class MainMenuScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;

    public MainMenuScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        GameProfile p = ctx.profile();
        menu = new Menu(p.name)
                .subtitle("game type · " + p.perspective)
                .theme(MenuTheme.dark())
                .add("Play / Create Level", () -> scenes.transitionTo("play"))
                .add("Multiplayer (Host / Join)", () -> scenes.transitionTo("multiplayer"))
                .add("Edit Features", () -> scenes.transitionTo("editor"))
                .add("Change Game Type", () -> scenes.transitionTo("startup"))
                .add("Quit", () -> System.exit(0));
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
        g.drawString("Arrow keys / mouse to navigate, Enter to select",
                24, viewportHeight - 24);
    }
}
