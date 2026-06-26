package com.larsons.engine.core;

import com.larsons.engine.input.InputManager;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Canvas;
import java.awt.Dimension;

/**
 * Hosts the rendering {@link Canvas} inside a Swing {@link JFrame}.
 *
 * <p>A heavyweight AWT {@code Canvas} (rather than a Swing {@code JPanel}) is
 * used because it supports a {@code BufferStrategy} for active, double-buffered
 * rendering — the standard high-performance Java2D setup, and a good match for
 * the 120 FPS target.
 */
public class GameWindow {
    private final JFrame frame;
    private final Canvas canvas;

    public GameWindow(EngineConfig config) {
        frame = new JFrame(config.title);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(config.resizable);

        canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(config.width, config.height));
        canvas.setBackground(config.backgroundColor);
        canvas.setFocusable(true);
        // Let the game handle Tab etc. instead of focus traversal.
        canvas.setFocusTraversalKeysEnabled(false);

        frame.add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /** Show the window. Call before creating a BufferStrategy on the canvas. */
    public void show() {
        frame.setVisible(true);
        canvas.requestFocus();
    }

    public void attachInput(InputManager input) {
        canvas.addKeyListener(input);
        canvas.addMouseListener(input);
        canvas.addMouseMotionListener(input);
        canvas.addMouseWheelListener(input);
    }

    public Canvas getCanvas() { return canvas; }

    public JFrame getFrame() { return frame; }

    public int getWidth() { return canvas.getWidth(); }

    public int getHeight() { return canvas.getHeight(); }
}
