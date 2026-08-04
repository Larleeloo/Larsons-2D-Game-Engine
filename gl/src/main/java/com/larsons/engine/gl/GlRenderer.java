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
 * <p><b>A frame is also the unit the drawable is held for.</b>
 * {@link #beginFrame()} takes the platform's drawable lock and {@link #present()}
 * gives it back once the swap has happened, so a window resize running on the
 * other thread waits rather than reallocating the back buffer inside a
 * half-finished frame. That crashed the process on macOS;
 * {@link GlDrawableLock} carries the report.
 *
 * <p><b>Since A1 the frame is drawn offscreen, not at the window.</b>
 * {@link #beginFrame()} binds a {@link GlSurface} sized to the drawable and
 * {@link #present()} blits it to the back buffer. That costs one driver-side
 * resolve and buys the thing Job A's whole economic case rests on: the finished
 * scene is already a GPU texture ({@link #sceneTexture()}), so post-processing
 * becomes a shader reading it rather than a readback and an upload every frame.
 *
 * <p><b>And since A2 the chain runs here too.</b> A {@link ShaderChain} with
 * passes is executed as real GLSL against that texture by {@link GlShaderChain}
 * — an FBO ping-pong with no transfer in either direction — and the result is
 * what gets blitted to the back buffer. Before A2 the passes were dropped with
 * a line on stderr, which meant a GL build had no day/night lighting at all;
 * that was a correctness defect rather than a missing optimisation, and it is
 * the reason Job A outranked the rest of the plan once Job B closed.
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

    /**
     * The drawable in device pixels, as GLFW last reported it.
     *
     * <p><b>Asked of the window rather than computed from the logical size.</b>
     * {@code round(width * scale)} agrees with the framebuffer nearly always and
     * is a derivation of a number the window already has — and the two disagree
     * exactly when a frame is at its most fragile, in the middle of a drag
     * between panels of different scale, where the logical size and the
     * framebuffer size move at different moments. A blit sized from the stale
     * half of that pair writes the wrong rectangle into the back buffer.
     */
    private int deviceWidth = 1;
    private int deviceHeight = 1;

    /**
     * Whether this frame holds the platform's drawable lock, and therefore owes
     * a release. See {@link GlDrawableLock}.
     */
    private boolean drawableLocked;

    private FrameProfiler profiler;
    private ShaderChain shaders;

    /**
     * A2's post-processing, built lazily on the render thread for the same
     * reason everything else here is — see the class note.
     */
    private GlShaderChain chain;

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
        this.deviceWidth = window.framebufferWidth();
        this.deviceHeight = window.framebufferHeight();
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
            // From here to the end of present() this thread owns the drawable,
            // so a window resize on the platform's own thread waits for the
            // frame instead of reallocating the back buffer inside it. Taken
            // before measure(), because the sizes measure() reads are the ones
            // the resize is in the middle of changing. See GlDrawableLock for
            // the crash this answers, and note the guard: a beginFrame() that
            // is not followed by a present() — a test driving this by hand —
            // must not stack a second lock on the first.
            if (!drawableLocked) drawableLocked = context.lockDrawable();
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

    /**
     * Finish the frame: run the chain if there is one, then put the result on
     * screen.
     *
     * <p><b>The chain is timed as its own stage, exactly as the CPU chain is.</b>
     * {@code Java2DRenderer} closes out {@code PRESENT}, times
     * {@code SHADERS} around {@code ShaderChain.apply}, and reopens
     * {@code PRESENT} afterwards; this does the same, so the two backends'
     * reports are the same shape and can be set side by side. The number this
     * one puts in {@code SHADERS} comes from GPU timer queries rather than from
     * this thread's clock — see {@link GlShaderChain}.
     */
    @Override
    public void present() {
        long started = profiler == null ? 0L : profiler.begin();
        try {
            if (target != null) target.endFrame();
            boolean shaded = false;
            if (offscreen && surface != null) {
                if (shaders != null && shaders.hasPasses()) {
                    if (chain == null) chain = new GlShaderChain();
                    // The passes read a single-sample texture, so the coverage
                    // samples have to be collapsed into one first. A1's straight
                    // multisample-to-window blit did that resolve on the way out;
                    // with a chain in the way it happens here instead.
                    surface.resolve();
                    if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
                    shaded = chain.run(surface.resolvedTexture(),
                            deviceWidth(), deviceHeight(), width, height, shaders, profiler);
                    if (profiler != null) started = profiler.begin();
                }
                GlSurface.unbind();
                if (shaded) {
                    chain.blitToWindow(deviceWidth(), deviceHeight());
                } else {
                    surface.blitToWindow(deviceWidth(), deviceHeight());
                }
            } else if (shaders != null && shaders.hasPasses()) {
                warnPassesDropped();
            }
            context.swapBuffers();
            if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
        } finally {
            // The drawable goes back to the platform only once the frame is on
            // screen — the swap is the command that most needs the back buffer
            // to still be the one this frame was drawn for. In a `finally`
            // because a frame that threw has to release it too: a lock held by
            // a thread that has moved on is a hung window rather than a crash,
            // which is not an improvement.
            releaseDrawable();
        }
    }

    /** Hand the drawable back, if this frame took it. */
    private void releaseDrawable() {
        if (!drawableLocked) return;
        drawableLocked = false;
        context.unlockDrawable();
    }

    /**
     * The one route by which a chain can still go unrun, said once.
     *
     * <p>Post-processing reads the offscreen surface, so forcing
     * {@code -Dlarsons.render.offscreen=never} with passes attached drops them.
     * That combination only happens deliberately — it is the flag A1 added for
     * measuring the resolve's cost on a given machine — but "deliberately" is
     * not "knowingly", and a renderer that silently ignores post-processing
     * looks exactly like one whose post-processing has no effect. That
     * confusion cost §5.0 a whole column of a comparison table.
     */
    private void warnPassesDropped() {
        if (warnedPassesDropped) return;
        warnedPassesDropped = true;
        System.err.println("[gl] " + OFFSCREEN_PROPERTY + " is forcing this frame "
                + "straight at the window, so the " + shaders.passes().size()
                + " attached shader pass(es) are not being run: the chain reads the "
                + "offscreen surface. Use auto or always to get them.");
    }

    private boolean warnedPassesDropped;

    @Override public int getWidth() { return width; }

    @Override public int getHeight() { return height; }

    @Override
    public void setShaderChain(ShaderChain chain) { this.shaders = chain; }

    @Override
    public void setProfiler(FrameProfiler profiler) { this.profiler = profiler; }

    /**
     * The scene as a GPU texture — what {@link GlShaderChain} samples — or 0
     * when this frame went straight at the window and there is no such texture.
     *
     * <p>Valid after {@link GlSurface#resolve()}, which {@link #present()}
     * calls before running the chain.
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
            if (chain != null) {
                chain.close();
                chain = null;
            }
        }
    }

    /** The offscreen surface this frame was drawn into, for tests to read back. */
    GlSurface surface() { return surface; }

    /** The post-processing this renderer ran, or null if it has never had any. */
    GlShaderChain shaderChain() { return chain; }

    int deviceWidth() { return deviceWidth; }

    int deviceHeight() { return deviceHeight; }

    /**
     * Window size in logical pixels, the drawable in device pixels, and the
     * scale between them, as the window last observed all three.
     *
     * <p>Read from {@link GlWindow}'s fields rather than asked of GLFW: window
     * queries belong to the thread that created the window and this is not that
     * thread. The window updates them from its resize callbacks, which is also
     * how a drag between a 1× and a 2× panel is noticed — a change to the
     * framebuffer under a window whose logical size never moved.
     *
     * <p>All four are sampled here, once, and the rest of the frame uses these
     * copies. The window's fields are volatile and a resize can move them at any
     * instant; a frame that read them again at present time could set a viewport
     * from one size and blit to another.
     */
    private void measure() {
        width = window.width();
        height = window.height();
        scale = window.scale();
        deviceWidth = Math.max(1, window.framebufferWidth());
        deviceHeight = Math.max(1, window.framebufferHeight());
        glViewport(0, 0, deviceWidth, deviceHeight);
    }
}
