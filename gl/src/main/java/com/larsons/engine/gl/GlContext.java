package com.larsons.engine.gl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * A GL 3.3 core context and the window it belongs to.
 *
 * <p><b>Lifted from {@code GlShaderHarness}, which had already paid for the
 * mistakes.</b> The harness in the core's test sources has been creating one of
 * these for the shader parity work since before this module existed, and three
 * of the things it does are not obvious:
 *
 * <ul>
 *   <li><b>The VAO.</b> A core profile draws <em>nothing</em> without a bound
 *       vertex array and reports no error for it — not a GL error, not a
 *       warning, not a black screen with a message. A first GL backend that
 *       forgets it looks like every other reason a renderer might draw nothing,
 *       and there is no way to tell them apart from the outside.</li>
 *   <li><b>Forward compatibility.</b> Without {@code GLFW_OPENGL_FORWARD_COMPAT}
 *       macOS refuses to hand out anything past 2.1, so a context that works
 *       everywhere else fails on the machine this project profiles on.</li>
 *   <li><b>Failure is a return value, not an exception.</b> Every way this can
 *       fail — no driver, no 3.3, headless CI, a display the process cannot
 *       reach — arrives as {@link #available()} being false with a reason in
 *       {@link #whyUnavailable()}. B9 turns that into a fallback to Java2D and
 *       a line in the frame report; the tests here turn it into a skip. Both
 *       need to ask rather than catch.</li>
 * </ul>
 *
 * <p><b>GLFW is global and is reference-counted here.</b> {@code glfwInit} and
 * {@code glfwTerminate} are process-wide, so a second context created while a
 * first is alive must not re-init, and closing one must not terminate the
 * library out from under the other. A test class that opens a context per
 * method would otherwise leave the second one drawing into a torn-down library,
 * which manifests as a segfault in the JVM rather than a test failure.
 */
public final class GlContext implements AutoCloseable {

    /** How many live contexts are holding GLFW open. Guarded by the class. */
    private static int liveContexts;

    private final long window;
    private final int vao;
    private final boolean ready;
    private final String unavailable;
    private final String vendor;
    private final String renderer;
    private final String version;
    private final int samples;
    private boolean closed;

    /**
     * A hidden context for rendering that is never presented — the parity
     * tests, and anything that wants a GPU without a window on screen.
     *
     * @param samples multisample coverage to request, or 0 for none
     */
    public static GlContext offscreen(int samples) {
        return new GlContext(1, 1, "larsons-gl", false, samples);
    }

    /** A visible window of the given size, for {@link GlRenderer}. */
    public static GlContext window(int width, int height, String title, int samples) {
        return new GlContext(width, height, title, true, samples);
    }

    private GlContext(int width, int height, String title, boolean visible, int samples) {
        long created = MemoryUtil.NULL;
        int createdVao = 0;
        boolean ok = false;
        String why = "";
        String gotVendor = "", gotRenderer = "", gotVersion = "";
        int gotSamples = 0;
        boolean initialised = false;
        try {
            synchronized (GlContext.class) {
                if (liveContexts > 0 || glfwInit()) {
                    initialised = true;
                    liveContexts++;
                } else {
                    why = "glfwInit failed";
                }
            }
            if (initialised) {
                GLFWErrorCallback.createPrint(System.err).set();
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_VISIBLE, visible ? GLFW_TRUE : GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
                glfwWindowHint(GLFW_SAMPLES, Math.max(0, samples));
                created = glfwCreateWindow(Math.max(1, width), Math.max(1, height),
                        title, MemoryUtil.NULL, MemoryUtil.NULL);
                if (created == MemoryUtil.NULL) {
                    why = "no GL 3.3 core context";
                } else {
                    glfwMakeContextCurrent(created);
                    GLCapabilities caps = GL.createCapabilities();
                    if (!caps.OpenGL33) {
                        why = "driver reports a context below GL 3.3";
                    } else {
                        // See the class note. Nothing draws without this and
                        // nothing says so.
                        createdVao = glGenVertexArrays();
                        glBindVertexArray(createdVao);
                        gotVendor = string(GL_VENDOR);
                        gotRenderer = string(GL_RENDERER);
                        gotVersion = string(GL_VERSION);
                        gotSamples = glGetInteger(GL_SAMPLES);
                        ok = true;
                    }
                }
            }
        } catch (Throwable t) {
            // UnsatisfiedLinkError included: a machine with no GLFW native at
            // all is exactly the machine that must fall back rather than crash.
            why = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (!ok && initialised) releaseGlfw();

        this.window = ok ? created : MemoryUtil.NULL;
        this.vao = createdVao;
        this.ready = ok;
        this.unavailable = ok ? "" : (why.isEmpty() ? "unknown" : why);
        this.vendor = gotVendor;
        this.renderer = gotRenderer;
        this.version = gotVersion;
        this.samples = gotSamples;
        this.closed = !ok;
    }

    /** Whether a usable context exists. Callers ask; they do not catch. */
    public boolean available() { return ready; }

    /** Why not, when {@link #available()} is false — for a log or a skip message. */
    public String whyUnavailable() { return unavailable; }

    /** The GLFW window handle, for input and buffer swaps. */
    public long window() { return window; }

    /** {@code GL_VENDOR}. B9 puts this in the frame report. */
    public String vendor() { return vendor; }

    /** {@code GL_RENDERER} — the string that says "llvmpipe" when it matters. */
    public String renderer() { return renderer; }

    /** {@code GL_VERSION}, as the driver states it. */
    public String version() { return version; }

    /** Multisample coverage actually granted, which can be less than asked. */
    public int samples() { return samples; }

    /** Make this context current on the calling thread. */
    public void makeCurrent() {
        if (ready) glfwMakeContextCurrent(window);
    }

    /** Present the back buffer. */
    public void swapBuffers() {
        if (ready) glfwSwapBuffers(window);
    }

    /** One line naming the driver, for a log or a report. */
    public String describe() {
        return ready ? vendor + " / " + renderer + " / " + version
                : "unavailable (" + unavailable + ")";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (vao != 0) glDeleteVertexArrays(vao);
            if (window != MemoryUtil.NULL) glfwDestroyWindow(window);
        } catch (Throwable ignored) {
            // Tearing down a context that may already be half gone.
        }
        releaseGlfw();
    }

    private static void releaseGlfw() {
        synchronized (GlContext.class) {
            if (liveContexts > 0 && --liveContexts == 0) {
                try {
                    glfwTerminate();
                } catch (Throwable ignored) {
                    // Same.
                }
            }
        }
    }

    private static String string(int name) {
        String s = glGetString(name);
        return s == null ? "" : s;
    }
}
