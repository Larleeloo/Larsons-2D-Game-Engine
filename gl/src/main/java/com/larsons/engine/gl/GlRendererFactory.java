package com.larsons.engine.gl;

import com.larsons.engine.graphics.Backend;
import com.larsons.engine.graphics.RendererFactory;

/**
 * How this module offers itself to an engine that has never heard of it.
 *
 * <p>Registered in {@code META-INF/services/com.larsons.engine.graphics.RendererFactory},
 * so the core finds it with {@link java.util.ServiceLoader} when this jar is on
 * the classpath and finds nothing when it is not. That one file is the entire
 * coupling between the two projects, in the only direction invariant 1 allows:
 * this project knows the engine, the engine does not know this project, and
 * {@code ModuleBoundaryTest} fails the build if a core source so much as names
 * it.
 *
 * <p><b>Probing is creating.</b> There is no cheaper question to ask a driver
 * than "give me a GL 3.3 core context" — a capability string can be present on
 * a machine where creation still fails, and this backend needs the context
 * anyway. So the probe <em>is</em> the window, and a failure at any stage (no
 * driver, no 3.3, no display, LWJGL natives built for another OS) comes back as
 * {@link Backend#unavailable} with the driver's own words in it.
 *
 * <p><b>{@code -Dlarsons.render.gl.version=<major>.<minor>}</b> overrides the
 * profile asked for. Its first purpose is the negative control: requesting a
 * version no driver has proves the engine's fallback engages and says so, on a
 * machine where GL otherwise works. Its second is the field — a player whose
 * driver misbehaves at 3.3 can be asked to try something else in a bug report
 * without a new build.
 */
public final class GlRendererFactory implements RendererFactory {

    /** What {@code -Dlarsons.render.backend} calls this backend. */
    public static final String ID = "gl";

    /** Overrides the core-profile version requested. See the class note. */
    public static final String VERSION_PROPERTY = "larsons.render.gl.version";

    @Override
    public String id() { return ID; }

    @Override
    public Backend create(Request request) {
        int[] version = requestedVersion();
        // A single-sample window, and that is not an oversight. Since A1 the
        // scene is drawn into a multisampled offscreen surface and blitted
        // here, so coverage sampling belongs to that surface. Asking for it on
        // the window as well makes the present a multisample-to-multisample
        // blit, which is a second resolve the driver has to do by hand — 14 ms
        // a frame of it on a software rasteriser, measured, where the intended
        // path is one hardware resolve.
        GlContext context = GlContext.window(request.width(), request.height(),
                request.title(), 0, version[0], version[1]);
        if (!context.available()) {
            String why = context.whyUnavailable();
            context.close();
            return Backend.unavailable(ID, why);
        }
        try {
            GlWindow window = new GlWindow(context, request.width(), request.height());
            return Backend.of(ID, new GlRenderer(window, request.background()),
                    window, context.describe());
        } catch (Throwable t) {
            // A context that reported itself usable and then was not. Closing
            // it here rather than leaving it open matters: an abandoned GLFW
            // window keeps the library initialised and the process alive after
            // the engine has fallen back and moved on.
            context.close();
            return Backend.unavailable(ID, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** The core profile to ask for: 3.3, or whatever the property overrides it to. */
    private static int[] requestedVersion() {
        String raw = System.getProperty(VERSION_PROPERTY);
        if (raw == null || raw.isBlank()) return new int[] {GlContext.MAJOR, GlContext.MINOR};
        String[] parts = raw.strip().split("\\.");
        try {
            int major = Integer.parseInt(parts[0].strip());
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].strip()) : 0;
            return new int[] {major, minor};
        } catch (RuntimeException e) {
            System.err.println("[gl] ignoring -D" + VERSION_PROPERTY + "=" + raw
                    + " (expected <major>.<minor>)");
            return new int[] {GlContext.MAJOR, GlContext.MINOR};
        }
    }
}
