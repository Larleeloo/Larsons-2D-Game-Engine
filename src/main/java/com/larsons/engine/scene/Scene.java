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
     * backend implements the same verbs. Every scene draws through it and
     * none reaches past it for a {@code Graphics2D} — the paragraph that used
     * to describe how to do that, and counted the scenes still doing it, went
     * away with the last of them in B3.
     *
     * <p><b>{@code alpha} is not optional for a scene that scrolls.</b> It is how
     * far past the last completed fixed step real time has got, and a frame does
     * not contain a whole number of steps — so a scene that ignores it draws the
     * world 1.8, 3.7 or 5.5 px along on successive frames where 3.7 was due, and
     * a player calls that the blocks shaking. Every scene here was handed this
     * parameter and none of them read it for most of the engine's life; see
     * {@link com.larsons.engine.sim.StepInterpolation} for the measurements and
     * the fix. A scene with a fixed camera and nothing moving under it may
     * ignore it, and should say so where it does.
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
