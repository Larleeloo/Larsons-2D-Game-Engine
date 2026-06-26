package com.larsons.engine.input;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * Keyboard + mouse state tracker.
 *
 * <p>Snapshot model: the game loop calls {@link #newFrame()} once per simulation
 * tick to capture the previous state, so scenes can query edge-triggered
 * "just pressed" input during {@code update}. Querying state (rather than
 * handling raw events in scenes) keeps input polling deterministic and is a
 * good fit for the fixed-timestep loop and for future networked input
 * (requirement #3).
 */
public class InputManager
        implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {

    private static final int MAX_KEYS = 256;
    private final boolean[] down = new boolean[MAX_KEYS];
    private final boolean[] prev = new boolean[MAX_KEYS];

    private volatile int mouseX, mouseY;
    private volatile boolean mouseDown;
    private boolean mousePrevDown;
    private int wheel;

    /** Snapshot current state as "previous" for edge detection. Call once per tick. */
    public void newFrame() {
        System.arraycopy(down, 0, prev, 0, MAX_KEYS);
        mousePrevDown = mouseDown;
        wheel = 0;
    }

    public boolean isKeyDown(int keyCode) {
        return keyCode >= 0 && keyCode < MAX_KEYS && down[keyCode];
    }

    public boolean isKeyJustPressed(int keyCode) {
        return keyCode >= 0 && keyCode < MAX_KEYS && down[keyCode] && !prev[keyCode];
    }

    public int getMouseX() { return mouseX; }

    public int getMouseY() { return mouseY; }

    public boolean isMouseDown() { return mouseDown; }

    public boolean isMouseJustPressed() { return mouseDown && !mousePrevDown; }

    public int getWheelRotation() { return wheel; }

    // --- KeyListener ---
    @Override public void keyPressed(KeyEvent e) {
        int c = e.getKeyCode();
        if (c >= 0 && c < MAX_KEYS) down[c] = true;
    }

    @Override public void keyReleased(KeyEvent e) {
        int c = e.getKeyCode();
        if (c >= 0 && c < MAX_KEYS) down[c] = false;
    }

    @Override public void keyTyped(KeyEvent e) {}

    // --- MouseListener ---
    @Override public void mousePressed(MouseEvent e) {
        mouseDown = true;
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override public void mouseReleased(MouseEvent e) {
        mouseDown = false;
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override public void mouseClicked(MouseEvent e) {}

    @Override public void mouseEntered(MouseEvent e) {}

    @Override public void mouseExited(MouseEvent e) {}

    // --- MouseMotionListener ---
    @Override public void mouseMoved(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override public void mouseDragged(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
    }

    // --- MouseWheelListener ---
    @Override public void mouseWheelMoved(MouseWheelEvent e) {
        wheel += e.getWheelRotation();
    }
}
