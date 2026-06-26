package com.larsons.engine.graphics;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferStrategy;

/**
 * Default {@link Renderer}: active rendering onto an AWT {@link Canvas} via a
 * double-buffered {@link BufferStrategy}.
 *
 * <p>Active rendering (the game loop drives painting, rather than Swing's
 * passive {@code repaint()}) pairs naturally with the fixed-timestep loop and
 * makes hitting a 120 FPS target (requirement #1) straightforward.
 */
public class Java2DRenderer implements Renderer {
    private final Canvas canvas;
    private final Color clearColor;
    private BufferStrategy strategy;
    private Graphics2D currentGraphics;

    public Java2DRenderer(Canvas canvas, Color clearColor) {
        this.canvas = canvas;
        this.clearColor = clearColor;
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
        currentGraphics = (Graphics2D) strategy.getDrawGraphics();
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
