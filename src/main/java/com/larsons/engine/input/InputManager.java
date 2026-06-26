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
 * <p>Scenes poll input during {@code update} rather than handling raw events,
 * which keeps input deterministic and a good fit for the fixed-timestep loop
 * and future networked input (requirement #3).
 *
 * <p><b>Edge detection.</b> AWT delivers input events on the event-dispatch
 * thread, asynchronously to the game loop. A naive "compare this frame's state
 * to last frame's" scheme misses presses: a held button stays {@code true} for
 * many ticks at 120&nbsp;Hz, so by the time the next snapshot is taken the
 * press has already been folded into the previous state and the rising edge is
 * never observed. Instead, each press is <em>latched</em> the moment it happens
 * in the event handler, and {@link #newFrame()} promotes any latched presses to
 * "just pressed" for exactly the next tick. This way no press is ever lost,
 * regardless of when the event arrives relative to the loop — and even a press
 * released within a single frame (a fast tap) is still reported.
 */
public class InputManager
        implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {

    private static final int MAX_KEYS = 256;

    // Live physical state, mutated by AWT events on the event-dispatch thread.
    private final boolean[] down = new boolean[MAX_KEYS];
    // Presses recorded since the last newFrame(); promoted to justPressed there.
    private final boolean[] pressedLatch = new boolean[MAX_KEYS];
    // Rising-edge state visible to the current tick's update().
    private final boolean[] justPressed = new boolean[MAX_KEYS];

    private volatile int mouseX, mouseY;
    private volatile boolean mouseDown;
    private boolean mousePressedLatch;
    private boolean mouseJustPressed;
    private int wheel;
    private int wheelLatch;

    /**
     * Promote events accumulated since the previous call into the state scenes
     * read this tick. Call once per simulation tick, before scene updates.
     */
    public synchronized void newFrame() {
        for (int c = 0; c < MAX_KEYS; c++) {
            justPressed[c] = pressedLatch[c];
            pressedLatch[c] = false;
        }
        mouseJustPressed = mousePressedLatch;
        mousePressedLatch = false;
        wheel = wheelLatch;
        wheelLatch = 0;
    }

    public boolean isKeyDown(int keyCode) {
        return keyCode >= 0 && keyCode < MAX_KEYS && down[keyCode];
    }

    public boolean isKeyJustPressed(int keyCode) {
        return keyCode >= 0 && keyCode < MAX_KEYS && justPressed[keyCode];
    }

    public int getMouseX() { return mouseX; }

    public int getMouseY() { return mouseY; }

    public boolean isMouseDown() { return mouseDown; }

    public boolean isMouseJustPressed() { return mouseJustPressed; }

    public int getWheelRotation() { return wheel; }

    // --- KeyListener ---
    @Override public synchronized void keyPressed(KeyEvent e) {
        int c = e.getKeyCode();
        if (c >= 0 && c < MAX_KEYS) {
            if (!down[c]) pressedLatch[c] = true; // record only the rising edge
            down[c] = true;
        }
    }

    @Override public synchronized void keyReleased(KeyEvent e) {
        int c = e.getKeyCode();
        if (c >= 0 && c < MAX_KEYS) down[c] = false;
    }

    @Override public void keyTyped(KeyEvent e) {}

    // --- MouseListener ---
    @Override public synchronized void mousePressed(MouseEvent e) {
        mouseDown = true;
        mousePressedLatch = true;
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override public synchronized void mouseReleased(MouseEvent e) {
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
    @Override public synchronized void mouseWheelMoved(MouseWheelEvent e) {
        wheelLatch += e.getWheelRotation();
    }
}
