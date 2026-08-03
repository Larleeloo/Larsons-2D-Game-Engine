package com.larsons.engine.gl;

import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.profile.FrameProfiler;

import java.awt.Color;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * {@link Renderer} over a GL 3.3 window: {@code beginFrame()} hands back a
 * {@link GlTarget}, {@code present()} swaps buffers.
 *
 * <p><b>The loop does not change and cannot tell.</b> That was the point of B4
 * — {@code Renderer.beginFrame()} started returning a {@code DrawTarget}
 * instead of a {@code Graphics2D}, so a backend became free to return one that
 * appends to a vertex buffer. This class is what that freedom was for, and
 * nothing in {@code Engine}, {@code GameLoop} or any of the eighteen scenes
 * needed a line changed to accept it.
 *
 * <p><b>What this class does not do, and where it lands instead.</b>
 *
 * <ul>
 *   <li><b>Choosing itself.</b> Nothing constructs this yet. B9 is backend
 *       selection: probing for a context, falling back to
 *       {@code Java2DRenderer} when there is none, honouring
 *       {@code -Dlarsons.render.backend}, and reporting which was chosen. That
 *       step also has to decide how a GLFW window coexists with the AWT
 *       {@code GameWindow} the engine opens today — they are two windowing
 *       systems and the answer is a decision, not a detail, which is why it is
 *       a step of its own rather than something smuggled in here.</li>
 *   <li><b>Running the shader chain.</b> Job A. A chain attached here is
 *       <em>not</em> executed, and this says so once rather than quietly
 *       dropping the passes — a renderer that silently ignores post-processing
 *       looks exactly like one whose post-processing has no effect. Running the
 *       CPU chain instead would mean reading the frame back and uploading it
 *       again, which is the two-transfers-per-frame arrangement the plan
 *       rejects Job-A-before-Job-B for in the first place.</li>
 * </ul>
 */
public final class GlRenderer implements Renderer {

    /** Coverage samples requested, matching {@link GlSurface}'s offscreen bar. */
    public static final int SAMPLES = 4;

    private final GlContext context;
    private final GlTarget target;
    private final Color clearColor;

    private int width;
    private int height;
    private double scale = 1;

    private FrameProfiler profiler;
    private ShaderChain shaders;
    private boolean warnedAboutShaders;

    /**
     * A renderer, or {@code null} when this machine cannot give one.
     *
     * <p>Null rather than an exception because the caller's next move is
     * always the same — use Java2D and say why — and an exception would make
     * the ordinary case of a machine without a GL driver look like a fault.
     * The reason is not lost: pass a context in and ask it.
     */
    public static GlRenderer create(int width, int height, String title, Color clearColor) {
        GlContext context = GlContext.window(width, height, title, SAMPLES);
        if (!context.available()) {
            context.close();
            return null;
        }
        return new GlRenderer(context, clearColor);
    }

    public GlRenderer(GlContext context, Color clearColor) {
        if (!context.available()) {
            throw new IllegalArgumentException(
                    "no GL context: " + context.whyUnavailable());
        }
        this.context = context;
        this.clearColor = clearColor == null ? new Color(24, 28, 38) : clearColor;
        context.makeCurrent();
        measure();
        this.target = new GlTarget(width, height, scale);
    }

    /** The driver behind this renderer, for a log or B9's frame report. */
    public GlContext context() { return context; }

    @Override
    public DrawTarget beginFrame() {
        long started = profiler == null ? 0L : profiler.begin();
        try {
            context.makeCurrent();
            measure();
            target.scale(scale);
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
        target.endFrame();
        if (shaders != null && shaders.hasPasses() && !warnedAboutShaders) {
            warnedAboutShaders = true;
            System.err.println("[gl] post-processing passes are attached but not run: "
                    + "GPU shader execution is Job A. Use the Java2D backend for now.");
        }
        context.swapBuffers();
        glfwPollEvents();
        if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
    }

    @Override public int getWidth() { return width; }

    @Override public int getHeight() { return height; }

    @Override
    public void setShaderChain(ShaderChain chain) { this.shaders = chain; }

    @Override
    public void setProfiler(FrameProfiler profiler) { this.profiler = profiler; }

    @Override
    public void dispose() {
        target.close();
        context.close();
    }

    /**
     * Window size in logical pixels and the scale to device pixels.
     *
     * <p>Asked every frame, of the window, rather than remembered from
     * construction. A window can be resized, and it can be dragged between a
     * 1× and a 2× panel without being resized at all — which changes the
     * framebuffer under a window whose logical size never moved. This is the
     * GL-side answer to the same question B6 settled for text: ask the
     * destination, not the default screen.
     */
    private void measure() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            glfwGetWindowSize(context.window(), w, h);
            int logicalW = Math.max(1, w.get(0)), logicalH = Math.max(1, h.get(0));
            glfwGetFramebufferSize(context.window(), w, h);
            int deviceW = Math.max(1, w.get(0));
            width = logicalW;
            height = logicalH;
            scale = deviceW / (double) logicalW;
        }
        glViewport(0, 0, (int) Math.round(width * scale), (int) Math.round(height * scale));
    }
}
