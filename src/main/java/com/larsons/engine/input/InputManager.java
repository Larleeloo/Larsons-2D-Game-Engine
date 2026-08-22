package com.larsons.engine.input;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
 * <p><b>Any key, any button.</b> State is kept in sets rather than fixed-size
 * arrays, so keys outside the usual range (F13-F24, the media and input-method
 * keys) and mouse buttons beyond the familiar three (the side buttons on a
 * gaming mouse) are tracked like everything else. Custom key binds can
 * therefore land on anything the hardware actually sends — see
 * {@link KeyBinds}.
 *
 * <p><b>Two window systems, one latch.</b> This class implements AWT's listener
 * interfaces because the default backend draws into an AWT {@code Canvas}, but
 * the edge detection above is not AWT's and does not depend on it. A backend
 * that brings its own window — B9's GL one, whose events come from GLFW —
 * reports through {@link #pressKey}, {@link #releaseKey}, {@link #typeChar},
 * {@link #pressMouse}, {@link #releaseMouse}, {@link #moveMouse} and
 * {@link #scroll}, which is precisely what the AWT listeners call. Scenes
 * cannot tell the difference, and there is only one implementation of the part
 * that is easy to get wrong.
 *
 * <p>Typed characters ({@link #consumeTypedChars()}) work the same way as
 * presses: they belong to the tick they were typed in and expire at the next
 * one. A buffer that instead held every keystroke until something read it would
 * let unrelated typing — walking a level with {@code WASD} — reappear inside
 * the next text field to be opened. {@link #consumeAnyPress()} follows suit,
 * handing the controls menu the one press it is waiting for.
 */
public class InputManager
        implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {

    // Live physical state, mutated by AWT events on the event-dispatch thread.
    private final Set<Integer> down = new HashSet<>();
    // Presses recorded since the last newFrame(); promoted to justPressed there.
    private final Set<Integer> pressedLatch = new HashSet<>();
    // Rising-edge state visible to the current tick's update().
    private final Set<Integer> justPressed = new HashSet<>();

    // Mouse buttons, tracked by button number exactly like keys are by code.
    private final Set<Integer> buttonsDown = new HashSet<>();
    private final Set<Integer> buttonPressedLatch = new HashSet<>();
    private final Set<Integer> buttonJustPressed = new HashSet<>();

    private volatile int mouseX, mouseY;
    private int wheel;
    private int wheelLatch;

    // How far the hand has moved since something last read it, and where the
    // last move event put the pointer. See moveMouse and consumeMouseMotion.
    private int lastMoveX = UNSEEN, lastMoveY;
    private int motionX, motionY;
    private boolean warpPending;
    private int warpX, warpY;

    /** No move event has been seen yet, so there is no motion to measure from. */
    private static final int UNSEEN = Integer.MIN_VALUE;

    // Presses in the order they arrived, for binding capture (see
    // consumeAnyPress). Scoped to a tick like every other edge state.
    private final Deque<InputBinding> pressLatch = new ArrayDeque<>();
    private final Deque<InputBinding> presses = new ArrayDeque<>();
    private static final int MAX_PRESS_QUEUE = 16;

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
        justPressed.clear();
        justPressed.addAll(pressedLatch);
        pressedLatch.clear();
        buttonJustPressed.clear();
        buttonJustPressed.addAll(buttonPressedLatch);
        buttonPressedLatch.clear();
        wheel = wheelLatch;
        wheelLatch = 0;
        presses.clear();
        presses.addAll(pressLatch);
        pressLatch.clear();
        // Characters typed since the previous tick become readable this tick;
        // anything the previous tick left unread expires here rather than
        // waiting in line for the next text field that happens to open.
        typed.setLength(0);
        typed.append(typedLatch);
        typedLatch.setLength(0);
    }

    public synchronized boolean isKeyDown(int keyCode) {
        return down.contains(keyCode);
    }

    public synchronized boolean isKeyJustPressed(int keyCode) {
        return justPressed.contains(keyCode);
    }

    public int getMouseX() { return mouseX; }

    public int getMouseY() { return mouseY; }

    /** Whether mouse {@code button} ({@link MouseEvent#BUTTON1} etc) is held. */
    public synchronized boolean isMouseButtonDown(int button) {
        return buttonsDown.contains(button);
    }

    /** Whether mouse {@code button} went down during this tick. */
    public synchronized boolean isMouseButtonJustPressed(int button) {
        return buttonJustPressed.contains(button);
    }

    public boolean isMouseDown() { return isMouseButtonDown(MouseEvent.BUTTON1); }

    public boolean isMouseJustPressed() { return isMouseButtonJustPressed(MouseEvent.BUTTON1); }

    public boolean isRightMouseDown() { return isMouseButtonDown(MouseEvent.BUTTON3); }

    public boolean isRightMouseJustPressed() {
        return isMouseButtonJustPressed(MouseEvent.BUTTON3);
    }

    public boolean isMiddleMouseDown() { return isMouseButtonDown(MouseEvent.BUTTON2); }

    public boolean isMiddleMouseJustPressed() {
        return isMouseButtonJustPressed(MouseEvent.BUTTON2);
    }

    public synchronized int getWheelRotation() { return wheel; }

    /**
     * Take the first key or mouse button pressed during this tick, as a
     * {@link InputBinding} ready to be assigned to an action — this is how the
     * controls menu listens for "press the input you want".
     *
     * <p>The binding carries whichever of Ctrl/Shift/Alt were held at the
     * moment of the press, so holding them while pressing {@code S} records
     * {@code Ctrl+S} rather than a bare {@code S}. Returns {@code null} when
     * nothing was pressed this tick.
     */
    public synchronized InputBinding consumeAnyPress() {
        return presses.pollFirst();
    }

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

    // --- input, whatever the window system ------------------------------------
    //
    // The seven methods below are the whole of what a window has to tell this
    // class. AWT's listeners unwrap their events into them and a non-AWT
    // backend (B9's GLFW window) calls them directly, so the latching above —
    // which is the part that is easy to get subtly wrong — exists once and both
    // window systems share it. Synthesising AWT events for the second caller
    // was the alternative, and it needs a `Component` to name as their source,
    // which is exactly what a backend with no AWT window does not have.

    /**
     * A key went down. {@code modifiers} is a mask of {@link InputBinding#CTRL},
     * {@link InputBinding#SHIFT} and {@link InputBinding#ALT}, excluding the key
     * being pressed — see {@link #modifiersOf}.
     */
    public synchronized void pressKey(int keyCode, int modifiers) {
        if (keyCode == KeyEvent.VK_UNDEFINED) return;
        if (down.add(keyCode)) { // record only the rising edge (key repeat is not one)
            pressedLatch.add(keyCode);
            recordPress(InputBinding.key(keyCode, modifiers));
        }
    }

    /** A key came back up. */
    public synchronized void releaseKey(int keyCode) {
        down.remove(keyCode);
    }

    /** A character was typed, for text fields. Control characters are dropped. */
    public synchronized void typeChar(char c) {
        if (c >= 0x20 && c != 0x7f && typedLatch.length() < MAX_TYPED_BUFFER) {
            typedLatch.append(c);
        }
    }

    /** A mouse button went down at the current pointer position. */
    public synchronized void pressMouse(int button, int modifiers) {
        if (buttonsDown.add(button)) {
            buttonPressedLatch.add(button);
            recordPress(InputBinding.mouse(button, modifiers));
        }
    }

    /** A mouse button came back up. */
    public synchronized void releaseMouse(int button) {
        buttonsDown.remove(button);
    }

    /**
     * The pointer moved, in the same coordinates the frame is drawn in.
     *
     * <p>Two things are recorded, and a view that steers with the mouse needs
     * the second one: where the pointer <em>is</em>, and how far the hand
     * <em>moved</em>. They stop being the same number the moment something
     * puts the pointer back — see {@link #consumeMouseMotion} and
     * {@link #pointerWarped}.
     */
    public synchronized void moveMouse(int x, int y) {
        if (warpPending && x == warpX && y == warpY) {
            // The pointer arriving where the game put it is not the hand
            // moving, so it starts a fresh origin rather than counting.
            warpPending = false;
        } else if (lastMoveX != UNSEEN) {
            motionX += x - lastMoveX;
            motionY += y - lastMoveY;
        }
        lastMoveX = x;
        lastMoveY = y;
        mouseX = x;
        mouseY = y;
    }

    /**
     * Tell the tracker the game has just put the pointer at ({@code x},
     * {@code y}) itself, so the move event that lands there is not read as the
     * player having flung the mouse across the desk.
     *
     * <p><b>Matched on the position rather than on a timestamp</b> because the
     * event is delivered asynchronously: a real move that was already in the
     * queue when the warp went out arrives after it, and discarding "the next
     * event" would throw that one away and count the warp instead. Only an
     * event landing exactly on the requested pixel is the warp.
     */
    public synchronized void pointerWarped(int x, int y) {
        warpPending = true;
        warpX = x;
        warpY = y;
    }

    /**
     * How far the hand has moved since this was last called, into
     * {@code out} as {@code {dx, dy}}, and reset to zero.
     *
     * <p><b>What a mouse-look reads instead of the position.</b> A locked
     * pointer is held in the middle of the window, so its position says nothing
     * at all; the motion between events is the whole of what the player did,
     * and it survives every recentre because {@link #pointerWarped} tells this
     * class which of the moves were its own.
     */
    public synchronized void consumeMouseMotion(int[] out) {
        if (out == null || out.length < 2) return;
        out[0] = motionX;
        out[1] = motionY;
        motionX = 0;
        motionY = 0;
    }

    /**
     * Throw away the motion accumulated so far — what a view calls as it takes
     * the mouse over, so travel the pointer did while it was an arrow is not
     * banked as one flick of the camera.
     */
    public synchronized void discardMouseMotion() {
        motionX = 0;
        motionY = 0;
    }

    /** The wheel turned, positive away from the user — AWT's sign convention. */
    public synchronized void scroll(int notches) {
        wheelLatch += notches;
    }

    // --- KeyListener ---
    @Override public synchronized void keyPressed(KeyEvent e) {
        pressKey(e.getKeyCode(), modifiersOf(e, e.getKeyCode()));
    }

    @Override public synchronized void keyReleased(KeyEvent e) {
        releaseKey(e.getKeyCode());
    }

    @Override public synchronized void keyTyped(KeyEvent e) {
        typeChar(e.getKeyChar());
    }

    // --- MouseListener ---
    @Override public synchronized void mousePressed(MouseEvent e) {
        moveMouse(e.getX(), e.getY());
        pressMouse(buttonOf(e), modifiersOf(e, KeyEvent.VK_UNDEFINED));
    }

    @Override public synchronized void mouseReleased(MouseEvent e) {
        moveMouse(e.getX(), e.getY());
        releaseMouse(buttonOf(e));
    }

    /**
     * The button an event names, reading an unreported one as the left button:
     * a press event always came from <em>some</em> button, and the left one is
     * what a source that does not say means in practice.
     */
    private static int buttonOf(MouseEvent e) {
        int button = e.getButton();
        return button == MouseEvent.NOBUTTON ? MouseEvent.BUTTON1 : button;
    }

    @Override public void mouseClicked(MouseEvent e) {}

    @Override public void mouseEntered(MouseEvent e) {}

    @Override public void mouseExited(MouseEvent e) {}

    // --- MouseMotionListener ---
    @Override public void mouseMoved(MouseEvent e) {
        moveMouse(e.getX(), e.getY());
    }

    @Override public void mouseDragged(MouseEvent e) {
        moveMouse(e.getX(), e.getY());
    }

    // --- MouseWheelListener ---
    @Override public synchronized void mouseWheelMoved(MouseWheelEvent e) {
        scroll(e.getWheelRotation());
    }

    private void recordPress(InputBinding binding) {
        if (!binding.isBound()) return;
        if (pressLatch.size() >= MAX_PRESS_QUEUE) pressLatch.pollFirst();
        pressLatch.addLast(binding);
    }

    /**
     * Which of Ctrl/Shift/Alt were held for an event. The key being pressed is
     * excluded from its own modifiers, so pressing Shift on its own is recorded
     * as the Shift key (bindable as "sprint") rather than as a modified
     * nothing.
     */
    private static int modifiersOf(java.awt.event.InputEvent e, int pressedKey) {
        int mods = 0;
        if (e.isControlDown() && pressedKey != KeyEvent.VK_CONTROL) mods |= InputBinding.CTRL;
        if (e.isShiftDown() && pressedKey != KeyEvent.VK_SHIFT) mods |= InputBinding.SHIFT;
        if (e.isAltDown() && pressedKey != KeyEvent.VK_ALT) mods |= InputBinding.ALT;
        return mods;
    }
}
