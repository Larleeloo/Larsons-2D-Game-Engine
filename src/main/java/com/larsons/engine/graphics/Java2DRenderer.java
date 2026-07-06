package com.larsons.engine.graphics;

import com.larsons.engine.graphics.shader.ShaderChain;

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
 * <p><b>Shaders.</b> When a {@link ShaderChain} with active passes is attached,
 * the frame is drawn into an offscreen {@code INT_RGB} image instead, the chain
 * runs over its raw pixel array (in parallel row stripes — the CPU stand-in for
 * fragment-shader parallelism), and the result is blitted to the canvas. With
 * no passes active the offscreen hop is skipped entirely, so shaders cost
 * nothing when disabled.
 */
public class Java2DRenderer implements Renderer {
    private final Canvas canvas;
    private final Color clearColor;
    private BufferStrategy strategy;
    private Graphics2D currentGraphics;

    private ShaderChain shaders;
    private BufferedImage offscreen;
    private boolean offscreenFrame;

    public Java2DRenderer(Canvas canvas, Color clearColor) {
        this.canvas = canvas;
        this.clearColor = clearColor;
    }

    @Override
    public void setShaderChain(ShaderChain chain) {
        this.shaders = chain;
    }

    private void ensureStrategy() {
        if (strategy == null) {
            // Must be created after the canvas is displayable (window shown).
            canvas.createBufferStrategy(2);
            strategy = canvas.getBufferStrategy();
        }
    }

    @Override
    public Graphics2D beginFrame() {
        ensureStrategy();
        offscreenFrame = shaders != null && shaders.hasPasses();
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
        return currentGraphics;
    }

    @Override
    public void present() {
        if (currentGraphics != null) {
            currentGraphics.dispose();
            currentGraphics = null;
        }
        if (offscreenFrame && offscreen != null) {
            int[] pixels = ((DataBufferInt) offscreen.getRaster().getDataBuffer()).getData();
            shaders.apply(pixels, offscreen.getWidth(), offscreen.getHeight());
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
