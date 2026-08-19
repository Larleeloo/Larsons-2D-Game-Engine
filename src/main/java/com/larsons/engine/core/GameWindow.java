package com.larsons.engine.core;

import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.Pointer;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

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
        // Keyboard events only reach a focused component, so re-grab focus
        // whenever the canvas is clicked (e.g. after the user alt-tabs away).
        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
            }
        });

        frame.add(canvas);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /** Show the window. Call before creating a BufferStrategy on the canvas. */
    public void show() {
        frame.setVisible(true);
        // Request focus on the EDT after the window is realized; calling it
        // synchronously right after setVisible can be ignored on some platforms.
        SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    public void attachInput(InputManager input) {
        canvas.addKeyListener(input);
        canvas.addMouseListener(input);
        canvas.addMouseMotionListener(input);
        canvas.addMouseWheelListener(input);
        Pointer.install(new AwtPointer());
    }

    /**
     * The pointer, as AWT can offer it: a blank cursor to hide it, and a
     * {@link Robot} to put it back in the middle of the window.
     *
     * <p><b>Why a Robot.</b> AWT has no pointer lock — there is no "hold the
     * cursor here" in the API — so a first-person view either reads raw motion
     * and gives up when the pointer reaches the edge of the window, or moves the
     * pointer back itself. A Robot is the only way to do the second, and it is
     * allowed to fail: a headless process has none, and a desktop that refuses
     * one is a desktop where the view falls back to reading motion alone
     * ({@link Pointer#canWarp()}). Constructed once, lazily, because
     * constructing one at start-up on a machine that will never use it is a
     * permissions prompt for nothing.
     */
    private final class AwtPointer implements Pointer.Handler {
        private Robot robot;
        private boolean robotTried;

        @Override
        public void setPointerVisible(boolean visible) {
            canvas.setCursor(visible ? Cursor.getDefaultCursor() : blankCursor());
        }

        @Override
        public boolean canWarpPointer() {
            return robot() != null && canvas.isShowing();
        }

        @Override
        public boolean warpPointer(int x, int y) {
            Robot r = robot();
            if (r == null || !canvas.isShowing()) return false;
            try {
                Point origin = canvas.getLocationOnScreen();
                r.mouseMove(origin.x + x, origin.y + y);
                return true;
            } catch (IllegalComponentStateException e) {
                // The window went away between the check and the move.
                return false;
            }
        }

        private Robot robot() {
            if (!robotTried) {
                robotTried = true;
                try {
                    robot = new Robot();
                } catch (AWTException | SecurityException | UnsupportedOperationException e) {
                    robot = null; // no pointer warping here; see the class note
                }
            }
            return robot;
        }
    }

    /** A cursor with nothing in it — how AWT says "do not draw the pointer". */
    private static Cursor blankCursor() {
        if (BLANK != null) return BLANK;
        BLANK = Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0),
                "blank");
        return BLANK;
    }

    private static Cursor BLANK;

    public Canvas getCanvas() { return canvas; }

    public JFrame getFrame() { return frame; }

    public int getWidth() { return canvas.getWidth(); }

    public int getHeight() { return canvas.getHeight(); }
}
