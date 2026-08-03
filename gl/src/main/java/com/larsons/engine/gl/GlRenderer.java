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
 * <p><b>What this class still does not do.</b> A {@link ShaderChain} attached
 * here is <em>not</em> executed, and it says so once rather than quietly
 * dropping the passes — a renderer that silently ignores post-processing looks
 * exactly like one whose post-processing has no effect. That is Job A. Running
 * the CPU chain instead would mean reading the frame back and uploading it
 * again, which is the two-transfers-per-frame arrangement the plan rejects
 * Job-A-before-Job-B for in the first place.
 */
public final class GlRenderer implements Renderer {

    /** Coverage samples requested, matching {@link GlSurface}'s offscreen bar. */
    public static final int SAMPLES = 4;

    private final GlWindow window;
    private final GlContext context;
    private final Color clearColor;

    /** Built on the render thread's first frame. See the class note. */
    private GlTarget target;

    private int width;
    private int height;
    private double scale = 1;

    private FrameProfiler profiler;
    private ShaderChain shaders;
    private boolean warnedAboutShaders;

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
            if (target == null) target = new GlTarget(width, height, scale);
            else target.scale(scale);
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
                    + "GPU shader execution is Job A. Use the Java2D backend for now.");
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
        if (target != null && context.currentOnThisThread()) {
            target.close();
            target = null;
        }
    }

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
