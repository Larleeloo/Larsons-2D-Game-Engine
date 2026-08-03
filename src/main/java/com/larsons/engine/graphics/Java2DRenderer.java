package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.profile.FrameProfiler;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Default {@link Renderer}: active rendering onto an AWT {@link Canvas} via a
 * double-buffered {@link BufferStrategy}.
 *
 * <p>Active rendering (the game loop drives painting, rather than Swing's
 * passive {@code repaint()}) pairs naturally with the fixed-timestep loop and
 * makes hitting a 120 FPS target (requirement #1) straightforward.
 *
 * <p><b>Every frame is composed offscreen</b> — drawn into an {@code INT_RGB}
 * {@link BufferedImage}, then blitted to the canvas as one image. A
 * {@link ShaderChain} with active passes runs over that image's raw pixel array
 * (in parallel row stripes — the CPU stand-in for fragment-shader parallelism)
 * in between.
 *
 * <p><b>Why offscreen always, and not only for shaders.</b> This used to draw
 * straight at the {@link BufferStrategy} whenever no pass was active, on the
 * reasonable-sounding theory that skipping the copy must be cheaper. Profiling
 * an M1 MacBook Air said otherwise, by a factor of nine: the same scene cost
 * 133 ms drawn at the window and 15 ms drawn into an image. Two things drive
 * that, and they compound:
 *
 * <ul>
 *   <li><b>Antialiased shapes are not accelerated.</b> The scene issues
 *       hundreds of small antialiased fills, strokes and glyphs per frame, and
 *       platform pipelines (Metal on macOS, OpenGL/D3D elsewhere) rasterize
 *       those in software regardless. Drawn at a hardware-backed surface, each
 *       one is a software render followed by its own upload; drawn into an
 *       image, they are plain writes into an {@code int[]} with no round trip
 *       at all.</li>
 *   <li><b>HiDPI multiplies it.</b> A hardware surface is sized in <em>device</em>
 *       pixels, so on a 2x display the same scene covers four times the area.
 *       The offscreen image is sized in logical pixels and upscaled once on
 *       the blit — which for nearest-neighbour pixel art is the correct
 *       presentation anyway, not a compromise.</li>
 * </ul>
 *
 * <p>So the copy is not a cost to be avoided; it is what turns a few hundred
 * unaccelerated round trips into one blit that hardware handles well. The old
 * path is still reachable via {@code -Dlarsons.render.direct=true} for
 * measuring the difference on a given machine.
 *
 * <p><b>Shaders cost a little more than their passes.</b> Reaching the pixels
 * means {@code DataBufferInt.getData()}, and Java2D stops caching an image in
 * video memory once a caller holds a pointer into its raster — so the final
 * blit of a shaded frame gives up hardware acceleration. That is why
 * {@link com.larsons.engine.profile.FrameProfiler.Stage#SHADERS} and
 * {@link com.larsons.engine.profile.FrameProfiler.Stage#PRESENT} are timed
 * separately: turning passes on can move both numbers.
 */
public class Java2DRenderer implements Renderer {
    private final Canvas canvas;
    private final Color clearColor;
    private BufferStrategy strategy;
    private Graphics2D currentGraphics;

    private ShaderChain shaders;
    private BufferedImage offscreen;
    private boolean offscreenFrame;
    private FrameProfiler profiler;

    /**
     * Escape hatch back to drawing straight at the window
     * ({@code -Dlarsons.render.direct=true}). Kept so the two paths can be
     * measured against each other on a given machine rather than taken on
     * trust — that comparison is what identified the cost in the first place.
     */
    private final boolean directToSurface =
            "true".equalsIgnoreCase(System.getProperty("larsons.render.direct"));

    public Java2DRenderer(Canvas canvas, Color clearColor) {
        this.canvas = canvas;
        this.clearColor = clearColor;
    }

    /** Whether frames are composed offscreen and blitted (the default). */
    public boolean isOffscreen() { return !directToSurface; }

    @Override
    public void setShaderChain(ShaderChain chain) {
        this.shaders = chain;
    }

    @Override
    public void setProfiler(FrameProfiler profiler) {
        this.profiler = profiler;
    }

    private void ensureStrategy() {
        if (strategy == null) {
            // Must be created after the canvas is displayable (window shown).
            canvas.createBufferStrategy(2);
            strategy = canvas.getBufferStrategy();
        }
    }

    @Override
    public DrawTarget beginFrame() {
        long started = profiler == null ? 0L : profiler.begin();
        try {
            return acquireFrame();
        } finally {
            if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, started);
        }
    }

    /**
     * Acquire and clear this frame's surface. Timed as presentation overhead.
     *
     * <p>The {@link Java2DTarget} is built here rather than by the caller, and
     * it is the only one built for the frame. That is what makes its
     * {@link com.larsons.engine.graphics.draw.DrawStats} the frame's own tally
     * — a second target over the same Graphics2D would keep a second, partial
     * count, and the caller has no way to know which of the two is the answer.
     */
    private DrawTarget acquireFrame() {
        ensureStrategy();
        offscreenFrame = !directToSurface;
        if (offscreenFrame) {
            int w = getWidth(), h = getHeight();
            if (offscreen == null || offscreen.getWidth() != w || offscreen.getHeight() != h) {
                offscreen = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            currentGraphics = offscreen.createGraphics();
        } else {
            currentGraphics = (Graphics2D) strategy.getDrawGraphics();
        }
        currentGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        // Sprite art is pixel-based; nearest-neighbour keeps it crisp when scaled.
        currentGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        currentGraphics.setColor(clearColor);
        currentGraphics.fillRect(0, 0, getWidth(), getHeight());
        return new Java2DTarget(currentGraphics, getWidth(), getHeight());
    }

    @Override
    public void present() {
        long presentStart = profiler == null ? 0L : profiler.begin();
        if (currentGraphics != null) {
            currentGraphics.dispose();
            currentGraphics = null;
        }
        if (offscreenFrame && offscreen != null) {
            if (shaders != null && shaders.hasPasses()) {
                int[] pixels = ((DataBufferInt) offscreen.getRaster().getDataBuffer()).getData();

                // The shader chain is timed on its own: it is the budget a GPU
                // shader backend would compete for, and it is the one part of
                // the frame whose cost scales with pixel count rather than
                // with how much the scene drew.
                if (profiler != null) {
                    profiler.record(FrameProfiler.Stage.PRESENT, presentStart);
                    long shaderStart = profiler.begin();
                    try {
                        shaders.apply(pixels, offscreen.getWidth(), offscreen.getHeight());
                    } finally {
                        profiler.record(FrameProfiler.Stage.SHADERS, shaderStart);
                    }
                    presentStart = profiler.begin();
                } else {
                    shaders.apply(pixels, offscreen.getWidth(), offscreen.getHeight());
                }
            }

            // One blit of one finished image, whatever the scene did to build
            // it. This is the operation hardware pipelines are good at.
            Graphics2D g = (Graphics2D) strategy.getDrawGraphics();
            try {
                g.drawImage(offscreen, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        if (strategy != null && !strategy.contentsLost()) {
            strategy.show();
        }
        // Helps keep animation smooth on some platforms (notably Linux).
        Toolkit.getDefaultToolkit().sync();
        if (profiler != null) profiler.record(FrameProfiler.Stage.PRESENT, presentStart);
    }

    @Override
    public int getWidth() { return Math.max(1, canvas.getWidth()); }

    @Override
    public int getHeight() { return Math.max(1, canvas.getHeight()); }

    @Override
    public void dispose() {
        if (currentGraphics != null) {
            currentGraphics.dispose();
            currentGraphics = null;
        }
    }
}
