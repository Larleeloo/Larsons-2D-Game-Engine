/**
 * The OpenGL 3.3 backend: {@link com.larsons.engine.gl.GlTarget} implements the
 * engine's {@link com.larsons.engine.graphics.draw.DrawTarget} by appending to
 * vertex buffers, and {@link com.larsons.engine.gl.GlRenderer} implements
 * {@link com.larsons.engine.graphics.Renderer} over a GLFW window.
 *
 * <p><b>This project depends on the core; the core does not know it exists.</b>
 * That direction is the engine's first invariant — a player with a bare JRE and
 * no GL module gets a working game — and it is enforced from both sides rather
 * than intended: {@code :verifyNoRuntimeDependencies} fails the core's
 * {@code jar} task if anything external reaches its runtime classpath, and
 * {@code ModuleBoundaryTest} fails the build if a core source so much as names
 * {@code org.lwjgl} or this package.
 *
 * <p><b>How the engine finds this without being allowed to name it.</b>
 * {@link com.larsons.engine.gl.GlRendererFactory} is registered in
 * {@code META-INF/services/com.larsons.engine.graphics.RendererFactory}, and
 * the core's {@code Backends} loads whatever the classpath it was launched with
 * provides — this backend from {@code :gl:glDist}, nothing at all from the
 * plain jar. That one file is the whole of the coupling, which is what lets the
 * boundary above be a build failure rather than an intention (B9).
 *
 * <p><b>Two threads, on purpose.</b>
 * {@link com.larsons.engine.gl.GlWindow} is created, shown, pumped and destroyed
 * on the thread that starts the engine, because GLFW's window and event
 * functions belong there and on macOS the platform enforces it;
 * {@link com.larsons.engine.gl.GlRenderer} takes the context on the game loop's
 * thread and keeps it. Neither calls the other's functions, and the window
 * pushes its size to the renderer rather than being asked.
 *
 * <p>Running the shader chain on the GPU is still Job A: a chain attached to
 * {@code GlRenderer} is not executed, and it says so once on stderr rather than
 * dropping the passes quietly.
 */
package com.larsons.engine.gl;
