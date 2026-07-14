package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.List;

/**
 * "Load Level" screen: lists the individual levels saved inside the active
 * game type and loads the one you pick. Game types are a folder grouping of
 * levels ({@link LevelStore}), and each level carries its own feature settings,
 * so choosing a level here decides both which world <em>and</em> which toggles
 * play — the level's saved settings apply when {@code PlayScene} loads it.
 */
public class LevelSelectScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;

    public LevelSelectScene(GameContext ctx) { this.ctx = ctx; }

    @Override
    public void onEnter() {
        // Rebuild every time so levels saved since last visit appear.
        GameProfile p = ctx.profile();
        LevelStore store = new LevelStore(p.name);
        menu = new Menu("Load Level")
                .subtitle(p.name + " · pick a level to play")
                .theme(MenuTheme.dark());

        List<String> names = store.list();
        for (String name : names) {
            menu.add(name, () -> {
                // Remember which level to load; PlayScene reads lastLevelPath
                // and applies that level's own settings on entry.
                p.lastLevelPath = store.fileFor(name).toString();
                ctx.save();
                scenes.transitionTo("play");
            });
        }
        if (names.isEmpty()) {
            menu.add("(no saved levels yet — make one in Creative Mode)",
                    () -> { if (p.creativeEnabled) scenes.transitionTo("creative"); });
        }
        menu.add("Back", () -> scenes.transitionTo("menu"));
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
        g.drawString("Each level loads with its own toggles · levels live under resources/levels/<game type>/",
                24, viewportHeight - 24);
    }
}
