package com.larsons.engine.demo;

import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.scene.Scene;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.KeyBindForm;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * The controls screen: every action in the engine, and the key or mouse button
 * it sits on, rebindable on the spot.
 *
 * <p>Edits go straight into the {@linkplain KeyBinds#active() active} binds and
 * are written to {@code config/keybinds.json} as they are made, so a rebind is
 * in force in the very next frame — and still in force next launch — without a
 * save step to forget.
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
    private String returnScene = "startup";
    private String status = "";

    public KeyBindsScene() {
        this(new KeyBindStore());
    }

    public KeyBindsScene(KeyBindStore store) {
        this.store = store;
    }

    /**
     * Open the controls screen from {@code from}, returning there when done.
     * A no-op (bar a logged warning from the scene manager) if no controls
     * scene is registered, so callers need no null checks.
     */
    public static void open(SceneManager scenes, String from) {
        if (scenes == null) return;
        Scene scene = scenes.get(NAME);
        if (scene instanceof KeyBindsScene binds) binds.returnTo(from);
        scenes.transitionTo(NAME);
    }

    /** Where "Done" (and the back key) goes. */
    public void returnTo(String sceneName) {
        if (sceneName != null && !sceneName.isEmpty()) this.returnScene = sceneName;
    }

    /** The form being shown, exposed so tests can drive it. */
    public ConfigForm form() { return form; }

    @Override
    public void onEnter() {
        status = "";
        form = KeyBindForm.build("Controls", KeyBinds.active(), this::persist, this::done);
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
    public void render(Graphics2D g, float alpha) {
        g.setColor(new Color(18, 18, 28));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        form.render(g, viewportWidth, viewportHeight);

        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        if (form.isCapturing()) {
            GameAction action = form.capturingAction();
            g.setColor(new Color(255, 210, 90));
            g.drawString("Press any key or mouse button for \""
                            + (action == null ? "" : action.label()) + "\" · Esc to cancel",
                    24, viewportHeight - 44);
        } else if (!status.isEmpty()) {
            g.setColor(new Color(140, 200, 140));
            g.drawString(status, 24, viewportHeight - 44);
        }
        g.setColor(new Color(120, 120, 140));
        g.drawString(KeyBindForm.HINT, 24, viewportHeight - 24);
    }
}
