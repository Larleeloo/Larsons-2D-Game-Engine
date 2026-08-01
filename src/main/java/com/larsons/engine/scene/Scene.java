package com.larsons.engine.scene;

import com.larsons.engine.input.InputManager;

import com.larsons.engine.graphics.draw.DrawTarget;

/**
 * A distinct game state — a menu, a level, a cutscene, etc.
 *
 * <p>Update and render are deliberately separate so the engine can update at a
 * fixed simulation rate while rendering at a (possibly different) frame rate.
 * The {@code alpha} passed to {@link #render} is the interpolation factor
 * between the previous and current simulation states, for smooth motion.
 */
public interface Scene {

    /** Called when this scene becomes active. */
    void onEnter();

    /**
     * Advance game logic by a fixed time step.
     *
     * @param dt    seconds elapsed for this tick (e.g. 1/120)
     * @param input current input state
     */
    void update(double dt, InputManager input);

    /**
     * Draw the scene.
     *
     * <p>The target is backend-neutral: today it wraps Java2D, and a GPU
     * backend implements the same verbs. Scenes not yet ported off
     * {@code Graphics2D} start by unwrapping it with
     * {@link com.larsons.engine.graphics.draw.Java2DTarget#graphicsOf}, and
     * the number of scenes still doing that is the migration's progress bar.
     *
     * @param target the drawing surface for this frame
     * @param alpha  interpolation factor in [0,1] between sim steps
     */
    void render(DrawTarget target, float alpha);

    /** Called when this scene is replaced. */
    void onExit();

    /** Called when the window/viewport size changes. */
    default void onResize(int width, int height) {}

    String name();
}
