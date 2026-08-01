package com.larsons.engine.scene;

import com.larsons.engine.input.InputManager;

import com.larsons.engine.graphics.draw.DrawTarget;

/**
 * Convenience base class for scenes: provides no-op lifecycle hooks and tracks
 * the current viewport size plus a reference to the {@link SceneManager} (for
 * triggering scene switches from within a scene).
 */
public abstract class AbstractScene implements Scene {

    protected SceneManager scenes;
    protected int viewportWidth;
    protected int viewportHeight;

    /** Wired up by {@link SceneManager#register}. */
    void attach(SceneManager scenes, int width, int height) {
        this.scenes = scenes;
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    @Override public void onEnter() {}

    @Override public void onExit() {}

    @Override public void onResize(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    @Override public abstract void update(double dt, InputManager input);

    @Override public abstract void render(DrawTarget target, float alpha);

    @Override public String name() { return getClass().getSimpleName(); }
}
