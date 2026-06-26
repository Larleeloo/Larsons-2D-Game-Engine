package com.larsons.engine.graphics;

import java.awt.Graphics2D;

/**
 * Rendering backend abstraction.
 *
 * <p>The default backend ({@link Java2DRenderer}) draws with the JDK's Java2D
 * pipeline. That is what lets the engine run out of the box on any machine with
 * a JRE (requirement #4) — no native libraries, no GPU bindings.
 *
 * <p><b>Shader support (requirement #5) lives behind this seam.</b> Adding
 * shaders will likely need a different backend entirely — e.g. an OpenGL/LWJGL
 * renderer. Such a backend can implement this interface (or a richer successor)
 * so the game loop keeps the same lifecycle: {@code beginFrame() -> draw ->
 * present()}. The one piece of porting work it implies: scenes currently draw
 * via {@link Graphics2D}, so a GPU backend would introduce a backend-neutral
 * draw API (sprite batches, draw calls) that both backends implement. Keeping
 * the loop decoupled from the backend now is what makes that change additive
 * rather than a rewrite.
 */
public interface Renderer {

    /** Acquire the drawing surface for this frame and clear it to the background. */
    Graphics2D beginFrame();

    /** Flip/show the completed frame to the screen. */
    void present();

    int getWidth();

    int getHeight();

    void dispose();
}
