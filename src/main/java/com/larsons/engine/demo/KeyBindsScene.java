package com.larsons.engine.demo;

import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.scene.Scene;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.KeyBindForm;

import java.util.List;


/**
 * The controls screen: the actions of whichever game sent the player here, and
 * the key or mouse button each sits on, rebindable on the spot.
 *
 * <p>Edits go straight into the {@linkplain KeyBinds#active() active} binds and
 * are written to {@code config/keybinds.json} as they are made, so a rebind is
 * in force in the very next frame — and still in force next launch — without a
 * save step to forget.
 *
 * <p><b>One screen, scoped by who opened it.</b> The engine's own menus open it
 * on the engine's groups; each mini game opens it on its own
 * ({@link #openFor}), because a mini game is a separate game with a separate
 * keyboard and its player is looking for its keys rather than for the creative
 * editor's. The binds themselves are one set either way — the player's — so a
 * rebind made anywhere is the rebind everywhere it applies.
 *
 * <p>Menus all over the engine open this screen, so it remembers who sent it
 * here ({@link #returnTo}) and goes back there when the player is done. Use
 * {@link #open} to do both in one call.
 */
public class KeyBindsScene extends AbstractScene {

    /** The name this scene is registered under. */
    public static final String NAME = "keybinds";

    private final KeyBindStore store;
    private ConfigForm form;
    private String returnScene = "menu";
    private String status = "";
    /** Which groups this visit shows, and what the screen is called. */
    private List<GameAction.Category> scope = GameAction.Category.engineGroups();
    private String title = "Controls";

    public KeyBindsScene() {
        this(new KeyBindStore());
    }

    public KeyBindsScene(KeyBindStore store) {
        this.store = store;
    }

    /**
     * Open the engine's controls from {@code from}, returning there when done.
     * A no-op (bar a logged warning from the scene manager) if no controls
     * scene is registered, so callers need no null checks.
     */
    public static void open(SceneManager scenes, String from) {
        open(scenes, from, "Controls", GameAction.Category.engineGroups());
    }

    /**
     * Open one mini game's controls: its own group, plus the menu keys, which
     * every screen in the engine is navigated with and which a player who has
     * rebound them needs to see wherever they are.
     */
    public static void openFor(SceneManager scenes, String from,
                               GameAction.Category miniGame) {
        open(scenes, from, miniGame.label() + " Controls",
                List.of(miniGame, GameAction.Category.INTERFACE));
    }

    /** Open the controls screen on {@code groups}, returning to {@code from}. */
    public static void open(SceneManager scenes, String from, String title,
                            List<GameAction.Category> groups) {
        if (scenes == null) return;
        Scene scene = scenes.get(NAME);
        if (scene instanceof KeyBindsScene binds) {
            binds.returnTo(from);
            binds.show(title, groups);
        }
        scenes.transitionTo(NAME);
    }

    /** Where "Done" (and the back key) goes. */
    public void returnTo(String sceneName) {
        if (sceneName != null && !sceneName.isEmpty()) this.returnScene = sceneName;
    }

    /** What the next visit lists, and what it is called. */
    public void show(String title, List<GameAction.Category> groups) {
        this.title = title == null || title.isBlank() ? "Controls" : title;
        this.scope = groups == null || groups.isEmpty()
                ? GameAction.Category.engineGroups() : List.copyOf(groups);
    }

    /** The groups this screen is currently showing. */
    public List<GameAction.Category> scope() { return scope; }

    /** The form being shown, exposed so tests can drive it. */
    public ConfigForm form() { return form; }

    @Override
    public void onEnter() {
        status = "";
        form = KeyBindForm.build(title, KeyBinds.active(), this::persist, this::done, scope);
    }

    private void persist() {
        status = store.trySave(KeyBinds.active())
                ? "Saved to " + store.file()
                : "Could not write " + store.file() + " — binds apply for this session";
    }

    private void done() {
        persist();
        scenes.transitionTo(returnScene);
    }

    @Override
    public void update(double dt, InputManager input) {
        // While a slot is listening, the form owns every press — including the
        // one that would otherwise back out of the screen.
        if (!form.isCapturing() && KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            done();
            return;
        }
        form.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        SceneChrome.backdrop(target, viewportWidth, viewportHeight);
        form.render(target, viewportWidth, viewportHeight);

        if (form.isCapturing()) {
            GameAction action = form.capturingAction();
            SceneChrome.status(target, viewportHeight,
                    "Press any key or mouse button for \""
                            + (action == null ? "" : action.label()) + "\" · Esc to cancel",
                    SceneChrome.PROMPT);
        } else if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status, SceneChrome.OK);
        }
        SceneChrome.hint(target, viewportHeight, KeyBindForm.HINT);
    }
}
