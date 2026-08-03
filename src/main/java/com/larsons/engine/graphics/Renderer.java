package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.shader.ShaderChain;

/**
 * Rendering backend abstraction.
 *
 * <p>The default backend ({@link Java2DRenderer}) draws with the JDK's Java2D
 * pipeline. That is what lets the engine run out of the box on any machine with
 * a JRE (requirement #4) — no native libraries, no GPU bindings.
 *
 * <p><b>A second implementation is chosen at startup when one is available.</b>
 * {@link Backends} probes the classpath for a {@link RendererFactory} and uses
 * what it finds, falling back here with a stated reason when there is nothing to
 * find or nothing that works. The engine cannot name a backend outside its own
 * jar and does not try to: see {@link RendererFactory} for why that indirection
 * is structural. A backend from outside also brings its own
 * {@link BackendWindow}, because the engine's is an AWT canvas that only
 * {@link Java2DRenderer} can draw into.
 *
 * <p><b>Nothing about this interface names Java2D.</b> {@link #beginFrame()}
 * hands back a {@link DrawTarget}, so a frame is a sequence of backend-neutral
 * drawing verbs from the moment it is acquired to the moment it is presented.
 * That was not true until B4: it used to return a {@code Graphics2D}, which
 * meant every scene in the engine was handed the CPU renderer by the loop
 * itself, and a GPU backend could not be written without changing the loop.
 * Now a backend is free to return a target that appends to a vertex buffer,
 * and neither {@code Engine} nor any scene can tell.
 *
 * <p><b>Shader support (requirement #5) lives behind this seam too.</b> Every
 * backend honours a {@link ShaderChain} of post-processing passes. Each
 * {@link com.larsons.engine.graphics.shader.ShaderPass} is defined GLSL-first
 * (real GPU fragment shader source) with a semantically identical CPU
 * fallback; the default backend executes the CPU side, while a GPU backend
 * (e.g. OpenGL/LWJGL) can implement this same interface, compile each pass's
 * {@code glsl()} directly, and keep the loop's {@code beginFrame() -> draw ->
 * present()} lifecycle unchanged.
 */
public interface Renderer {

    /**
     * Acquire this frame's drawing surface, cleared to the background colour.
     *
     * <p>One target per frame, owned by the backend: its {@link DrawTarget#stats()}
     * are therefore that frame's own draw-call tally, starting at zero, which
     * is the number {@code DrawStats.mergeRatio()} is read off. The returned
     * target is valid until {@link #present()} and must not be retained past
     * it.
     */
    DrawTarget beginFrame();

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
