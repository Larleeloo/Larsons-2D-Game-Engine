package com.larsons.engine.gl;

import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.profile.FrameProfiler;

import java.awt.Color;

import static org.lwjgl.opengl.GL33C.*;

/**
 * {@link Renderer} over a GL 3.3 core context: {@code beginFrame()} hands back
 * a {@link GlTarget}, {@code present()} swaps buffers.
 *
 * <p><b>The loop does not change and cannot tell.</b> That was the point of B4
 * — {@code Renderer.beginFrame()} started returning a {@code DrawTarget}
 * instead of a {@code Graphics2D}, so a backend became free to return one that
 * appends to a vertex buffer. This class is what that freedom was for, and
 * nothing in {@code Engine}, {@code GameLoop} or any of the eighteen scenes
 * needed a line changed to accept it.
 *
 * <p><b>Everything here runs on the render thread, and the window does not.</b>
 * {@link GlWindow} is created and pumped on the thread that started the engine;
 * this object binds the context on its first frame and keeps it. So the GL
 * resources below — the program, the batch, the atlas textures — are allocated
 * lazily in {@link #beginFrame()} rather than in the constructor, because the
 * constructor runs on the wrong thread for that. It is the sort of laziness
 * that looks like a micro-optimisation and is actually a correctness
 * requirement: objects created against a context that is current somewhere else
 * belong to nothing.
 *
 * <p><b>Since A1 the frame is drawn offscreen, not at the window.</b>
 * {@link #beginFrame()} binds a {@link GlSurface} sized to the drawable and
 * {@link #present()} blits it to the back buffer. That costs one driver-side
 * resolve and buys the thing Job A's whole economic case rests on: the finished
 * scene is already a GPU texture ({@link #sceneTexture()}), so post-processing
 * becomes a shader reading it rather than a readback and an upload every frame.
 *
 * <p><b>What this class still does not do.</b> A {@link ShaderChain} attached
 * here is <em>not</em> executed, and it says so once rather than quietly
 * dropping the passes — a renderer that silently ignores post-processing looks
 * exactly like one whose post-processing has no effect. Running them is A2-A4,
 * and they now have a texture to run against.
 */
public final class GlRenderer implements Renderer {

    /** Coverage samples requested, matching {@link GlSurface}'s offscreen bar. */
    public static final int SAMPLES = 4;

    private final GlWindow window;
    private final GlContext context;
    private final Color clearColor;

    /** Built on the render thread's first frame. See the class note. */
    private GlTarget target;

    /**
     * A1: the frame is drawn here rather than at the window, and blitted on
     * present.
     *
     * <p><b>This is what makes Job A cheap, and it is the whole economic
     * argument for having done Job B first.</b> Once the scene lands in a GPU
     * texture, post-processing is a shader reading that texture — no readback,
     * no upload, no per-frame transfer. The alternative order would have meant
     * copying the frame to the CPU and back every frame to save work that was
     * not the bottleneck, which is why §1 rejected it.
     *
     * <p>Sized to the drawable, in device pixels, because that is the
     * resolution the picture is drawn at. D0 established that this is also
     * correct rather than merely faithful: GL at 2× is pixel-identical to
     * Java2D upscaled for sprites, so there is nothing to gain by rendering
     * smaller and everything to lose in text sharpness.
     */
    private GlSurface surface;

    private int width;
    private int height;
    private double scale = 1;

    private FrameProfiler profiler;
    private ShaderChain shaders;
    private boolean warnedAboutShaders;

    /** Whether this frame was drawn offscreen. Decided per frame. */
    private boolean offscreen;

    /** {@code -Dlarsons.render.offscreen=auto|always|never}. */
    public static final String OFFSCREEN_PROPERTY = "larsons.render.offscreen";

    /**
     * Whether to draw this frame into {@link #surface} rather than at the
     * window.
     *
     * <p><b>A1 binds the surface only when something is going to read it, and
     * the reason is a measurement.</b> The offscreen path costs one multisample
     * resolve per frame. On a GPU that is a hardware blit and effectively free,
     * which is the assumption A1 was written under — but on a software
     * rasteriser it is 4 samples × 921,600 pixels of CPU work, measured here at
     * <b>14–19 ms a frame</b> under llvmpipe, which would take a playable
     * software-GL configuration (a VM, a remote desktop, an old integrated
     * driver) and make it unplayable.
     *
     * <p>Since the GL backend does not yet run the shader chain, an
     * unconditional surface would have bought exactly nothing and cost that. So
     * the default is {@code auto}: offscreen when a chain with passes is
     * attached — which is precisely when A2 will need the texture — and straight
     * at the window otherwise, which is what every frame did before A1 and at
     * the same price. {@code always} and {@code never} force it, for measuring
     * the difference on a given machine rather than taking this note on trust.
     */
    private boolean offscreenWanted() {
        String mode = System.getProperty(OFFSCREEN_PROPERTY, "auto").strip().toLowerCase();
        if (mode.equals("always")) return true;
        if (mode.equals("never")) return false;
        return shaders != null && shaders.hasPasses();
    }

    /**
     * A renderer over a window someone else made and still owns.
     *
     * <p>{@link GlRendererFactory} is what builds these; there is deliberately
     * no static {@code create} that makes a window and a renderer together,
     * because that arrangement leaves two objects each believing they own the
     * window. They do not: the window owns the GLFW context, this owns the
     * vertex buffers and textures, and {@link #dispose()} releases only the
     * second. The engine closes them in that order.
     */
    public GlRenderer(GlWindow window, Color clearColor) {
        this.window = window;
        this.context = window.context();
        this.clearColor = clearColor == null ? new Color(24, 28, 38) : clearColor;
        this.width = window.width();
        this.height = window.height();
        this.scale = window.scale();
    }

    /** The window this draws into — the engine shows and pumps it. */
    public GlWindow window() { return window; }

    /** The driver behind this renderer, for a log or the frame report. */
    public GlContext context() { return context; }

    @Override
    public DrawTarget beginFrame() {
        long started = profiler == null ? 0L : profiler.begin();
        try {
            context.makeCurrent();
            measure();
            if (target == null) {
                target = new GlTarget(width, height, scale);
                surface = new GlSurface(SAMPLES);
            } else {
                target.scale(scale);
            }
            // Bind before the target starts: everything from here to present()
            // lands in the offscreen surface, including the clear.
            offscreen = offscreenWanted();
            if (offscreen) {
                surface.resize(deviceWidth(), deviceHeight());
                surface.bind();
            } else {
                GlSurface.unbind();
            }
            target.beginFrame(width, height);
            target.clear(clearColor.getRGB());
            return target;
        } finally {
            if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
        }
    }

    @Override
    public void present() {
        long started = profiler == null ? 0L : profiler.begin();
        if (target != null) target.endFrame();
        if (shaders != null && shaders.hasPasses() && !warnedAboutShaders) {
            warnedAboutShaders = true;
            System.err.println("[gl] post-processing passes are attached but not run: "
                    + "running them on the GPU is A2-A4. The scene now renders to an "
                    + "offscreen surface (A1), which is what they will read from.");
        }
        if (offscreen && surface != null) {
            GlSurface.unbind();
            surface.blitToWindow(deviceWidth(), deviceHeight());
        }
        context.swapBuffers();
        if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
    }

    @Override public int getWidth() { return width; }

    @Override public int getHeight() { return height; }

    @Override
    public void setShaderChain(ShaderChain chain) { this.shaders = chain; }

    @Override
    public void setProfiler(FrameProfiler profiler) { this.profiler = profiler; }

    /**
     * The scene as a GPU texture, for A2's shader chain to sample, or 0 when
     * this frame went straight at the window and there is no such texture.
     */
    public int sceneTexture() {
        return offscreen && surface != null ? surface.resolvedTexture() : 0;
    }

    /**
     * Release this renderer's GL objects. <b>Not the window</b> — the engine
     * closes that next, and closing it twice terminates GLFW under the second
     * caller.
     *
     * <p>Called on the thread that owns the window, after the render thread has
     * stopped, so the context is usually current on neither. The target's
     * buffers, textures and program are therefore not deleted one at a time in
     * that case: destroying the context frees every object in it, and a
     * {@code glDeleteBuffers} aimed at no current context is a crash where a
     * no-op was wanted. A caller that does still hold the context — a test
     * driving this on one thread — gets the deterministic teardown.
     */
    @Override
    public void dispose() {
        if (context.currentOnThisThread()) {
            if (target != null) {
                target.close();
                target = null;
            }
            if (surface != null) {
                surface.close();
                surface = null;
            }
        }
    }

    /** The offscreen surface this frame was drawn into, for tests to read back. */
    GlSurface surface() { return surface; }

    int deviceWidth() { return Math.max(1, (int) Math.round(width * scale)); }

    int deviceHeight() { return Math.max(1, (int) Math.round(height * scale)); }

    /**
     * Window size in logical pixels and the scale to device pixels, as the
     * window last observed them.
     *
     * <p>Read from {@link GlWindow}'s fields rather than asked of GLFW: window
     * queries belong to the thread that created the window and this is not that
     * thread. The window updates them from its resize callbacks, which is also
     * how a drag between a 1× and a 2× panel is noticed — a change to the
     * framebuffer under a window whose logical size never moved.
     */
    private void measure() {
        width = window.width();
        height = window.height();
        scale = window.scale();
        glViewport(0, 0, (int) Math.round(width * scale), (int) Math.round(height * scale));
    }
}
