package com.larsons.engine.demo;

import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * Demo main menu. Shows how to build a customizable {@link Menu}
 * (requirement #6: menu customization) and how a scene triggers transitions to
 * other scenes.
 */
public class MainMenuScene extends AbstractScene {
    private Menu menu;

    @Override
    public void onEnter() {
        MenuTheme theme = MenuTheme.dark();
        menu = new Menu("Larson's 2D Game Engine")
                .subtitle("a generic 2D engine starter")
                .theme(theme)
                .add("Play Demo Level", () -> scenes.transitionTo("play"))
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
