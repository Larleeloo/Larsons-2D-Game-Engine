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
 * <p><b>What that split does not buy is safety from a resize.</b> The two
 * threads keep off each other's <em>functions</em>, and they still share one
 * drawable: the platform reallocates the back buffer on this thread when the
 * player drags the window's edge, and the render thread is drawing into it at
 * the time. On macOS that terminated the process. {@link GlDrawableLock} is
 * the arrangement that stops it, and it is worth reading before assuming the
 * thread split above is the whole story.
 *
 * <p><b>Input is translated, not synthesised.</b> The callbacks below turn GLFW
 * events into {@link InputManager}'s verbs through {@link GlKeys}; the
 * manager's latching, edge detection and typed-character buffering are the same
 * code the AWT path uses. A scene cannot tell which window it is being played
 * in, and a key bind saved under one backend works under the other.
 *
 * <p><b>Vsync is on, and B9 had it off for a reason that expired.</b> B9 set the
 * swap interval to zero so the engine's frame limiter was the only thing pacing
 * frames, which kept B10's two profiles measuring the same thing. That was right
 * for the measurement and wrong for the player: a frame presented at an
 * arbitrary phase against a 60 Hz panel tears, and the apparent motion of a
 * large regular pattern — a wall of terrain blocks — judders. Java2D never had
 * this problem because on macOS it presents through the window server, which
 * composites on the refresh whether anyone asked it to or not.
 *
 * <p>D0 is why this is the suspect rather than the rasteriser: it measured GL at
 * 2× against Java2D upscaled and found <b>0.000</b> mean channel error on
 * sprites at every camera position, with both backends changing exactly the same
 * pixels per one-pixel pan. Nothing about the picture moves; only when it
 * reaches the panel does.
 *
 * <p>{@code -Dlarsons.render.vsync=off} restores the old behaviour, which is
 * what an uncapped benchmark wants. <b>It changes what a frame profile means:</b>
 * with vsync on, {@code swapBuffers} blocks until the refresh, so the wait moves
 * out of the limiter's {@code idle} stage and into {@code present}. A profile
 * taken with it on is not comparable to B10's without saying so.
 */
public final class GlWindow implements BackendWindow {

    private final GlContext context;
    private final long handle;

    private volatile int width;
    private volatile int height;
    private volatile int deviceWidth;
    private volatile int deviceHeight;
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
        this.deviceWidth = this.width;
        this.deviceHeight = this.height;

        // Present on the panel's refresh unless someone is benchmarking. See
        // the class note for why this changed after B10 and what it does to a
        // frame profile.
        glfwSwapInterval(vsyncRequested() ? 1 : 0);
        measure();
        installCallbacks();

        // Hand the context over. From here it belongs to whichever thread
        // calls GlRenderer.beginFrame() first, and this one only pumps events.
        context.detachCurrent();
    }

    /** {@code -Dlarsons.render.vsync=off} to present as fast as frames are made. */
    public static final String VSYNC_PROPERTY = "larsons.render.vsync";

    /** Whether frames should wait for the panel's refresh. On unless refused. */
    public static boolean vsyncRequested() {
        String raw = System.getProperty(VSYNC_PROPERTY);
        if (raw == null || raw.isBlank()) return true;
        raw = raw.strip().toLowerCase();
        return !(raw.equals("off") || raw.equals("false") || raw.equals("0"));
    }

    /** The driver behind this window, for the frame report. */
    public String driver() { return context.describe(); }

    /** The context this window owns, for the renderer that draws into it. */
    GlContext context() { return context; }

    /** Device pixels per logical pixel, as the window currently reports it. */
    double scale() { return scale; }

    /**
     * The drawable's width in device pixels, as GLFW reports it.
     *
     * <p>Published beside {@link #scale()} rather than left to be derived from
     * it. They are two answers to one question only while the window is still:
     * a drag between panels of different scale moves the logical size and the
     * framebuffer size at different moments, and a renderer that multiplied one
     * by the other during that window would size its surface and its blit from
     * a pair that was never simultaneously true.
     */
    int framebufferWidth() { return deviceWidth; }

    /** The drawable's height in device pixels. See {@link #framebufferWidth()}. */
    int framebufferHeight() { return deviceHeight; }

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

    /**
     * Re-read the window's logical size, its drawable size and the scale
     * between them.
     *
     * <p>Published in that order — sizes first, scale last — so a render thread
     * that catches this mid-update sees a scale that goes with sizes it has
     * already been able to read, rather than the other way round. It is a
     * one-frame difference either way and costs nothing to get the useful way
     * round.
     */
    private void measure() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowSize(handle, w, h);
            int logicalW = Math.max(1, w.get(0)), logicalH = Math.max(1, h.get(0));
            glfwGetFramebufferSize(handle, w, h);
            int deviceW = Math.max(1, w.get(0)), deviceH = Math.max(1, h.get(0));
            width = logicalW;
            height = logicalH;
            deviceWidth = deviceW;
            deviceHeight = deviceH;
            scale = deviceW / (double) logicalW;
        }
    }
}
