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
 * <p>Nothing here is selected automatically yet. Probing for a context, falling
 * back to Java2D and reporting which was chosen is B9 in
 * {@code RENDER_PLAN.md}; running the shader chain on the GPU is Job A.
 */
package com.larsons.engine.gl;
