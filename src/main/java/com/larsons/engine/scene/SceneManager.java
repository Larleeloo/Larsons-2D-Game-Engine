package com.larsons.engine.scene;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers scenes by name and manages the active scene, including an optional
 * fade transition between scenes.
 */
public class SceneManager {

    private final Map<String, Scene> scenes = new HashMap<>();
    private Scene current;
    private Scene pending;
    private int width, height;

    // Simple cross-fade transition.
    private boolean transitioning;
    private boolean fadingOut;
    private float fade;                 // 0 = clear, 1 = black
    private float fadeSpeed = 3.5f;     // units per second

    public void setViewport(int w, int h) {
        this.width = w;
        this.height = h;
        if (current != null) current.onResize(w, h);
    }

    public void register(String name, Scene scene) {
        scenes.put(name, scene);
        if (scene instanceof AbstractScene as) {
            as.attach(this, width, height);
        }
    }

    public Scene get(String name) { return scenes.get(name); }

    public Scene current() { return current; }

    /** Switch immediately, with no transition. */
    public void setScene(String name) {
        Scene next = scenes.get(name);
        if (next == null) {
            System.err.println("SceneManager: no scene named '" + name + "'");
            return;
        }
        if (current != null) current.onExit();
        current = next;
        current.onResize(width, height);
        current.onEnter();
    }

    /** Switch with a fade-out / fade-in transition. */
    public void transitionTo(String name) {
        Scene next = scenes.get(name);
        if (next == null) {
            System.err.println("SceneManager: no scene named '" + name + "'");
            return;
        }
        if (transitioning) return;
        pending = next;
        transitioning = true;
        fadingOut = true;
        fade = 0f;
    }

    public void update(double dt, InputManager input) {
        if (transitioning) {
            if (fadingOut) {
                fade += fadeSpeed * (float) dt;
                if (fade >= 1f) {
                    fade = 1f;
                    if (current != null) current.onExit();
                    current = pending;
                    pending = null;
                    current.onResize(width, height);
                    current.onEnter();
                    fadingOut = false;
                }
            } else {
                fade -= fadeSpeed * (float) dt;
                if (fade <= 0f) {
                    fade = 0f;
                    transitioning = false;
                }
            }
            return; // pause scene logic during the brief transition
        }

        if (current != null) current.update(dt, input);
    }

    public void render(DrawTarget target, float alpha) {
        if (current != null) current.render(target, alpha);

        if (transitioning) {
            // The fade is scoped rather than set-and-restored, which is what a
            // batching backend needs and what Java2D wanted anyway.
            target.pushAlpha(Math.max(0f, Math.min(1f, fade)));
            target.fillRect(0, 0, width, height, Color.BLACK);
            target.popAlpha();
        }
    }

    public boolean isTransitioning() { return transitioning; }
}
