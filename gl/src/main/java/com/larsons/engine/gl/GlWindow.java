package com.larsons.engine.gl;

import com.larsons.engine.graphics.BackendWindow;
import com.larsons.engine.input.InputManager;

import org.lwjgl.glfw.Callbacks;
import org.lwjgl.system.MemoryStack;

import java.awt.event.KeyEvent;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The GLFW window the GL backend brings with it, and the only window in the
 * process when that backend is chosen.
 *
 * <p><b>Who runs on which thread.</b> This object is created, shown, pumped and
 * destroyed on the thread that started the engine — GLFW requires its window
 * and event functions there, and on macOS that is a hard platform rule rather
 * than a convention. {@link GlRenderer} draws on the game loop's thread, which
 * is where the GL context ends up current. The two never call each other's
 * functions:
 *
 * <ul>
 *   <li>Everything GLFW here — {@code glfwPollEvents}, {@code glfwGetWindowSize},
 *       the callbacks — happens on the engine's thread.</li>
 *   <li>Everything GL, plus {@code glfwSwapBuffers} (a context function, not a
 *       window one), happens on the render thread.</li>
 *   <li>The size the renderer needs is <b>pushed</b> here by GLFW's own resize
 *       callbacks into volatile fields, rather than pulled by the render thread
 *       asking the window. That is the whole reason those fields exist: a
 *       renderer that called {@code glfwGetFramebufferSize} every frame would
 *       be correct on Linux, mostly correct on Windows, and wrong on the
 *       machine B10 profiles on.</li>
 * </ul>
 *
 * <p><b>Input is translated, not synthesised.</b> The callbacks below turn GLFW
 * events into {@link InputManager}'s verbs through {@link GlKeys}; the
 * manager's latching, edge detection and typed-character buffering are the same
 * code the AWT path uses. A scene cannot tell which window it is being played
 * in, and a key bind saved under one backend works under the other.
 *
 * <p><b>No vsync, deliberately.</b> The swap interval is set to zero so the
 * engine's own frame limiter stays the only thing pacing frames — the same
 * arrangement the Java2D backend has always had. Letting the driver block in
 * {@code swapBuffers} instead would quietly move the cap to the panel's refresh
 * rate and make B10's two profiles measure different things.
 */
public final class GlWindow implements BackendWindow {

    private final GlContext context;
    private final long handle;

    private volatile int width;
    private volatile int height;
    private volatile double scale = 1;

    private InputManager input;
    private boolean closed;

    /**
     * Wrap a context's window. The context must be available and current on
     * this thread; it is detached before this returns, so the render thread can
     * take it.
     */
    GlWindow(GlContext context, int width, int height) {
        if (!context.available()) {
            throw new IllegalArgumentException("no GL context: " + context.whyUnavailable());
        }
        this.context = context;
        this.handle = context.window();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);

        // The engine's limiter paces frames; see the class note.
        glfwSwapInterval(0);
        measure();
        installCallbacks();

        // Hand the context over. From here it belongs to whichever thread
        // calls GlRenderer.beginFrame() first, and this one only pumps events.
        context.detachCurrent();
    }

    /** The driver behind this window, for the frame report. */
    public String driver() { return context.describe(); }

    /** The context this window owns, for the renderer that draws into it. */
    GlContext context() { return context; }

    /** Device pixels per logical pixel, as the window currently reports it. */
    double scale() { return scale; }

    @Override
    public void show() {
        glfwShowWindow(handle);
        glfwFocusWindow(handle);
        measure();
    }

    @Override public int width() { return width; }

    @Override public int height() { return height; }

    @Override
    public void attachInput(InputManager input) { this.input = input; }

    @Override
    public void pumpEvents() { glfwPollEvents(); }

    @Override
    public boolean closeRequested() { return glfwWindowShouldClose(handle); }

    /**
     * Destroy the window and the context with it. Idempotent: GLFW's
     * init/terminate is process-wide and reference-counted, so a second close
     * would tear the library down under whatever else is still holding it —
     * which is not an exception, it is a segfault later.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            Callbacks.glfwFreeCallbacks(handle);
        } catch (Throwable ignored) {
            // A window already half torn down; the context close below is what matters.
        }
        context.close();
    }

    // --- events ----------------------------------------------------------------

    private void installCallbacks() {
        glfwSetKeyCallback(handle, (win, key, scancode, action, mods) -> {
            InputManager in = input;
            if (in == null) return;
            int awt = GlKeys.awtKey(key);
            if (awt == KeyEvent.VK_UNDEFINED) return;
            // GLFW_REPEAT is auto-repeat, which is not a rising edge — the same
            // distinction InputManager already draws for AWT's repeated events.
            if (action == GLFW_PRESS) in.pressKey(awt, GlKeys.modifiers(mods, awt));
            else if (action == GLFW_RELEASE) in.releaseKey(awt);
        });

        // Text input comes from the char callback rather than from key codes:
        // it is the one that has been through the keyboard layout, so a
        // Dvorak, AZERTY or dead-key user types what is on their keycaps.
        glfwSetCharCallback(handle, (win, codepoint) -> {
            InputManager in = input;
            if (in == null || codepoint > Character.MAX_VALUE) return;
            in.typeChar((char) codepoint);
        });

        glfwSetCursorPosCallback(handle, (win, x, y) -> {
            InputManager in = input;
            // Logical window coordinates, which is what the frame is drawn in
            // and therefore what every scene's hit testing expects.
            if (in != null) in.moveMouse((int) x, (int) y);
        });

        glfwSetMouseButtonCallback(handle, (win, button, action, mods) -> {
            InputManager in = input;
            if (in == null) return;
            int awt = GlKeys.awtButton(button);
            if (action == GLFW_PRESS) in.pressMouse(awt, GlKeys.modifiers(mods, KeyEvent.VK_UNDEFINED));
            else if (action == GLFW_RELEASE) in.releaseMouse(awt);
        });

        glfwSetScrollCallback(handle, (win, xOffset, yOffset) -> {
            InputManager in = input;
            int notches = GlKeys.wheelNotches(yOffset);
            if (in != null && notches != 0) in.scroll(notches);
        });

        // Both sizes, because they move independently: dragging a window to a
        // 2x panel changes the framebuffer without changing the logical size,
        // and that is exactly the case that makes text go soft if the renderer
        // is not told.
        glfwSetWindowSizeCallback(handle, (win, w, h) -> measure());
        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> measure());
    }

    /** Re-read the window's logical size and its device-pixel scale. */
    private void measure() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            int logicalW = Math.max(1, w.get(0)), logicalH = Math.max(1, h.get(0));
            glfwGetFramebufferSize(handle, w, h);
            int deviceW = Math.max(1, w.get(0));
            width = logicalW;
            height = logicalH;
            scale = deviceW / (double) logicalW;
        }
    }
}
