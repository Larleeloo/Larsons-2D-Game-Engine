package com.larsons.engine.graphics;

import com.larsons.engine.graphics.shader.ShaderChain;

import java.awt.Graphics2D;

/**
 * Rendering backend abstraction.
 *
 * <p>The default backend ({@link Java2DRenderer}) draws with the JDK's Java2D
 * pipeline. That is what lets the engine run out of the box on any machine with
 * a JRE (requirement #4) — no native libraries, no GPU bindings.
 *
 * <p><b>Shader support (requirement #5) lives behind this seam.</b> Every
 * backend honours a {@link ShaderChain} of post-processing passes. Each
 * {@link com.larsons.engine.graphics.shader.ShaderPass} is defined GLSL-first
 * (real GPU fragment shader source) with a semantically identical CPU
 * fallback; the default backend executes the CPU side, while a GPU backend
 * (e.g. OpenGL/LWJGL) can implement this same interface, compile each pass's
 * {@code glsl()} directly, and keep the loop's {@code beginFrame() -> draw ->
 * present()} lifecycle unchanged. The remaining porting work for full GPU
 * scene rendering is a backend-neutral draw API, since scenes currently draw
 * via {@link Graphics2D}.
 */
public interface Renderer {

    /** Acquire the drawing surface for this frame and clear it to the background. */
    Graphics2D beginFrame();

    /** Flip/show the completed frame to the screen. */
    void present();

    int getWidth();

    int getHeight();

    void dispose();

    /**
     * Attach the post-processing chain this backend should run on each
     * presented frame. Backends are free to execute it however they like
     * (CPU stripes, GPU FBO ping-pong) as long as pass order is preserved.
     */
    default void setShaderChain(ShaderChain chain) {}

    /**
     * Attach the profiler this backend reports its own timings to. A backend
     * is expected to separate the cost of running the shader chain
     * ({@link com.larsons.engine.profile.FrameProfiler.Stage#SHADERS}) from the
     * cost of acquiring, blitting and flipping the frame
     * ({@link com.larsons.engine.profile.FrameProfiler.Stage#PRESENT}), because
     * those two answer different questions about whether a GPU backend is
     * worth building.
     */
    default void setProfiler(com.larsons.engine.profile.FrameProfiler profiler) {}
}
