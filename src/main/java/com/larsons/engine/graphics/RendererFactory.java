package com.larsons.engine.graphics;

import java.awt.Color;

/**
 * How a rendering backend outside the core offers itself to the engine.
 *
 * <p><b>This is a {@link java.util.ServiceLoader} service, and that is the
 * whole point.</b> Invariant 1 says the core ships with zero runtime
 * dependencies and never names the GL module;
 * {@code ModuleBoundaryTest.noCoreSourceNamesTheGlModule} enforces it by
 * reading every source file in {@code src/main/java}. So the core cannot
 * construct a GPU renderer, cannot import one, and cannot even mention one in a
 * comment. What it can do is declare the shape of one and look for
 * implementations on the classpath it was launched with:
 *
 * <pre>
 *   java -jar build/libs/Larsons-Game-Engine-0.1.0.jar   → nothing found, Java2D
 *   java -jar gl/build/libs/larsons-engine-gl.jar           → one found, GL
 * </pre>
 *
 * <p>Both jars contain the same engine classes. The second also contains a
 * backend and a {@code META-INF/services} entry naming it, and that entry is
 * the entire coupling between them. Reflection by class name would have worked
 * too and would have put the module's name back in the core as a string
 * literal, which is the same mistake with an extra step.
 *
 * <p><b>A factory reports failure by returning, not by throwing.</b> No driver,
 * no GL 3.3, a headless session, LWJGL natives missing for this OS — all of
 * them are the ordinary case for some player, and all of them arrive as
 * {@link Backend#unavailable}. {@link Backends} catches throwables anyway,
 * because a native library that is not there tends to announce itself as an
 * {@link UnsatisfiedLinkError} rather than politely.
 */
public interface RendererFactory {

    /**
     * The name this backend answers to in
     * {@code -Dlarsons.render.backend=<id>}. Lower case, no spaces.
     */
    String id();

    /**
     * Build the backend, or explain why this machine cannot have it.
     *
     * <p>Called at most once per launch, on the thread that constructed the
     * engine. <b>A backend must bring its own {@link BackendWindow}</b>, and
     * must create it here: the engine's own window is an AWT canvas that only
     * the Java2D renderer can draw into, and the thread that creates a window
     * is the thread the engine will pump its events on. One that returns a
     * renderer with no window is treated as unavailable.
     */
    Backend create(Request request);

    /** What the engine wants: a window this big, with this title, cleared to this colour. */
    record Request(int width, int height, String title, Color background) {

        public Request {
            width = Math.max(1, width);
            height = Math.max(1, height);
            if (title == null) title = "";
            if (background == null) background = Color.BLACK;
        }
    }
}
