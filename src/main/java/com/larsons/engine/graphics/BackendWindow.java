package com.larsons.engine.graphics;

import com.larsons.engine.input.InputManager;

/**
 * A window a rendering backend brings with it, when the backend is not the AWT
 * one the engine opens by default.
 *
 * <p><b>This interface is the answer to B9's actual question.</b> The engine
 * opens a Swing {@code JFrame} hosting an AWT {@code Canvas}, and a GL backend
 * arrives with a GLFW window of its own. Two windowing systems, and the plan
 * said the resolution was a decision rather than a detail. The decision is:
 * <b>whichever backend is chosen owns the only window</b>. When the GPU backend
 * is selected, no {@code JFrame} is ever constructed; when it is not, nothing
 * here is either. They never coexist, so there is no second event queue, no
 * second focus owner, and no question about which window a keystroke belongs
 * to.
 *
 * <p><b>Why the engine drives the pump rather than the backend.</b> GLFW
 * requires that its event queue be pumped from the thread that created the
 * window — on macOS that is the process's first thread, not a thread the
 * renderer happens to be on. The engine's game loop runs on a thread of its
 * own and always has. So the loop renders, and {@code Engine.start()} — still
 * on the thread the caller started the engine from — calls
 * {@link #pumpEvents()} until {@link #closeRequested()}. AWT needs none of
 * this because it has its own event-dispatch thread; that is precisely why the
 * default path does not implement this interface.
 *
 * <p><b>Input arrives as verbs, not as AWT events.</b> {@link #attachInput}
 * hands the window an {@link InputManager} to report presses to. Synthesising
 * {@code java.awt.event.KeyEvent}s to feed the same listener methods would need
 * an AWT {@code Component} to name as their source, which is the one thing a
 * non-AWT backend does not have — so {@code InputManager} grew a small
 * injection API instead, and the AWT listeners now call the same methods.
 * One latching implementation, two sources.
 */
public interface BackendWindow extends AutoCloseable {

    /** Make the window visible. Called once, before the first frame. */
    void show();

    /** Current width in logical pixels. */
    int width();

    /** Current height in logical pixels. */
    int height();

    /**
     * Report keyboard and mouse activity to this manager from now on.
     * Called before {@link #show()}.
     */
    void attachInput(InputManager input);

    /**
     * Drain the platform event queue once, delivering input to the attached
     * manager. Called repeatedly from the thread that created the window.
     */
    void pumpEvents();

    /** Whether the player has asked to close the window. */
    boolean closeRequested();

    /** Destroy the window. Never throws. */
    @Override
    void close();
}
