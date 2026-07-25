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
 *
 * <p>Typed characters ({@link #consumeTypedChars()}) work the same way: they
 * belong to the tick they were typed in and expire at the next one. A buffer
 * that instead held every keystroke until something read it would let unrelated
 * typing — walking a level with {@code WASD} — reappear inside the next text
 * field to be opened.
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
    // Right + middle buttons (the creative editor erases with right-click and
    // pick-blocks with middle-click); latched exactly like the left button.
    private volatile boolean rightMouseDown;
    private boolean rightMousePressedLatch;
    private boolean rightMouseJustPressed;
    private volatile boolean middleMouseDown;
    private boolean middleMousePressedLatch;
    private boolean middleMouseJustPressed;
    private int wheel;
    private int wheelLatch;

    // Printable characters typed since the last newFrame() (for text fields),
    // and the buffer promoted for the current tick. Typing is edge state like
    // a press: it belongs to the tick it happened in, not to whenever someone
    // next asks for it.
    private final StringBuilder typedLatch = new StringBuilder();
    private final StringBuilder typed = new StringBuilder();
    private static final int MAX_TYPED_BUFFER = 256;

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
        rightMouseJustPressed = rightMousePressedLatch;
        rightMousePressedLatch = false;
        middleMouseJustPressed = middleMousePressedLatch;
        middleMousePressedLatch = false;
        wheel = wheelLatch;
        wheelLatch = 0;
        // Characters typed since the previous tick become readable this tick;
        // anything the previous tick left unread expires here rather than
        // waiting in line for the next text field that happens to open.
        typed.setLength(0);
        typed.append(typedLatch);
        typedLatch.setLength(0);
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

    public boolean isRightMouseDown() { return rightMouseDown; }

    public boolean isRightMouseJustPressed() { return rightMouseJustPressed; }

    public boolean isMiddleMouseJustPressed() { return middleMouseJustPressed; }

    public int getWheelRotation() { return wheel; }

    /**
     * Return and clear the printable characters typed during <em>this</em> tick
     * (for text input fields). Control characters (Enter, Backspace, etc.) are
     * excluded; handle those via {@link #isKeyJustPressed}.
     *
     * <p>Characters no one consumes are dropped by the next {@link #newFrame()}
     * rather than accumulating. That is what keeps keystrokes from turning up
     * where they were never aimed: panning a level with {@code WASD} used to
     * pile "wasawdsds" into a shared buffer that the next text field to open —
     * a new block's name, say — would swallow whole on its first frame.
     */
    public synchronized String consumeTypedChars() {
        if (typed.length() == 0) return "";
        String s = typed.toString();
        typed.setLength(0);
        return s;
    }

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

    @Override public synchronized void keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        // Keep only printable characters; drop control chars (Enter/Backspace/etc).
        if (c >= 0x20 && c != 0x7f && typedLatch.length() < MAX_TYPED_BUFFER) {
            typedLatch.append(c);
        }
    }

    // --- MouseListener ---
    @Override public synchronized void mousePressed(MouseEvent e) {
        switch (e.getButton()) {
            case MouseEvent.BUTTON3 -> {
                rightMouseDown = true;
                rightMousePressedLatch = true;
            }
            case MouseEvent.BUTTON2 -> {
                middleMouseDown = true;
                middleMousePressedLatch = true;
            }
            default -> {
                mouseDown = true;
                mousePressedLatch = true;
            }
        }
        mouseX = e.getX();
        mouseY = e.getY();
    }

    @Override public synchronized void mouseReleased(MouseEvent e) {
        switch (e.getButton()) {
            case MouseEvent.BUTTON3 -> rightMouseDown = false;
            case MouseEvent.BUTTON2 -> middleMouseDown = false;
            default -> mouseDown = false;
        }
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
