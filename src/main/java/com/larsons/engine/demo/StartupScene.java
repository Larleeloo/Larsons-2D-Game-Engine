package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;

import java.util.List;

/**
 * Launch screen: pick an existing game type to keep creating within it, or
 * create a new one. Existing game types are loaded from
 * {@code resources/gametypes/}.
 *
 * <p>The engine's other games — the auto battler, the deckbuilder, the
 * evolution simulator — are not rows in that list. They are separate games, and
 * they sit in the corner of the screen as their own picture buttons
 * ({@link MiniGameButtons}), each opening its own lobby and each carrying its
 * own controls.
 */
public class StartupScene extends AbstractScene {
    private final GameContext ctx;
    private Menu menu;
    /** The mini games' corner buttons; built on the first entry. */
    private MiniGameButtons miniGames;

    public StartupScene(GameContext ctx) { this.ctx = ctx; }

    /** The corner buttons — the mini games, as pictures. */
    public MiniGameButtons miniGameButtons() {
        if (miniGames == null) miniGames = new MiniGameButtons(scenes);
        return miniGames;
    }

    @Override
    public void onEnter() {
        // Rebuild every time so newly created/saved game types appear.
        menu = new Menu("Larson's Game Engine")
                .subtitle("Choose a game to continue, or create a new one")
                .theme(MenuTheme.dark());

        List<GameProfile> profiles = ctx.store().listProfiles();
        for (GameProfile p : profiles) {
            // The name and nothing else. A game type used to be listed with the
            // perspective its profile happened to carry — "Dungeon Crawl
            // (SIDE_SCROLL)" — which was three kinds of wrong at once: it was an
            // enum constant shown to a player, it named a *level's* property on
            // a row that stands for a folder of levels, and a game type holding
            // a side-scrolling dungeon and a 3D overworld could only ever
            // display one of them. What a game is called is what it is called.
            menu.add(p.name, () -> {
                ctx.setProfile(p);
                scenes.transitionTo("menu");
            });
        }

        menu.add("+ Create New Game", () -> {
            ctx.setProfile(new GameProfile());
            scenes.transitionTo("editor");
        });
        // The mini games are no longer rows in this list: they are separate
        // games and they live in the corner of the screen as their own buttons
        // (see MiniGameButtons), which is what keeps this list a list of game
        // types.
        if (miniGames == null) miniGames = new MiniGameButtons(scenes);
        // No controls row here. Key binds belong to the game they are pressed
        // in: the engine's are on the game type's own menu and in its pause
        // screen, and each mini game keeps its own on its lobby (see
        // KeyBindsScene.openFor). The launch screen is the one place in the
        // engine where no game is running, which made it the one place the
        // question "which controls?" had no answer.
        menu.add("Quit", () -> System.exit(0));
    }

    /** The launch menu, exposed so tests can assert what the game offers. */
    public Menu menu() { return menu; }

    @Override
    public void update(double dt, InputManager input) {
        // The corner buttons get the pointer first: they sit over the bottom
        // of the menu's own region, and a click that opened a mini game should
        // not also land on whatever row happens to reach under it. Only the
        // pointer is taken — the keyboard still works the list, so a cursor
        // parked in the corner cannot trap someone navigating by arrow keys.
        MiniGameButtons corner = miniGameButtons();
        if (corner.update(input, viewportWidth, viewportHeight)) return;
        if (corner.hovered() && input.isMouseJustPressed()) return;
        menu.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        menu.render(target, viewportWidth, viewportHeight);
        miniGameButtons().render(target, viewportWidth, viewportHeight);
        SceneChrome.hint(target, viewportHeight,
                "Game types are saved as JSON under resources/gametypes/ · "
                        + "the mini games are in the corner");
    }
}
