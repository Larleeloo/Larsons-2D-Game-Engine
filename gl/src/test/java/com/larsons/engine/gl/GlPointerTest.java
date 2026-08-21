package com.larsons.engine.gl;

import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
import static org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
import static org.lwjgl.glfw.GLFW.glfwGetInputMode;

/**
 * The pointer, on the window that actually has one.
 *
 * <p><b>Why this test exists.</b> A first-person view hides the cursor by
 * asking {@link Pointer}, and for the whole life of that view the GLFW window
 * had registered nothing to ask — so on the GL backend the request went
 * nowhere and the desktop's arrow slid across the world. Registering a handler
 * was the obvious half of the fix and it was not enough: <b>every GLFW window
 * function may only be called from the thread that created the window</b>, and
 * a scene asks from the game loop's thread, which is a different one on this
 * backend. A request made from there is undefined behaviour — dropped on one
 * platform, a crash on another — so it is recorded and carried out by
 * {@link GlWindow#pumpEvents()}, which runs where it is allowed to.
 *
 * <p>That is what is checked here, and it is checked <em>across threads on
 * purpose</em>: a test that asked for the pointer on this thread would pass
 * against the broken version.
 *
 * <p>Skips itself where there is no driver, as the rest of this module does;
 * {@code xvfb-run ./gradlew :gl:test} runs it in full under a software
 * rasteriser.
 */
class GlPointerTest {

    private static final RendererFactory.Request REQUEST =
            new RendererFactory.Request(320, 200, "larsons-gl-pointer",
                    new Color(24, 28, 38));

    @AfterEach
    void letGoOfThePointer() {
        Pointer.install(null);
    }

    /**
     * Hidden, and hidden by the thread that is allowed to hide it.
     *
     * <p>The middle assertion is the one that matters: after the game loop has
     * asked and before the window has pumped, GLFW must not have been touched.
     * Reversing it — applying the change where the request was made — is the
     * version of this that "worked on my machine" and crashed on a Mac.
     */
    @Test
    void aFirstPersonViewHidesTheCursorFromTheGameLoopsThread() throws Exception {
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlWindow window = (GlWindow) choice.backend().window();
        long handle = window.context().window();
        try {
            window.attachInput(new InputManager());
            assertEquals(GLFW_CURSOR_NORMAL, glfwGetInputMode(handle, GLFW_CURSOR),
                    "a window starts with an ordinary cursor over it");
            assertTrue(Pointer.canWarp(), "GLFW can put the cursor where it is told");

            onAnotherThread(() -> Pointer.setVisible(false));
            assertEquals(GLFW_CURSOR_NORMAL, glfwGetInputMode(handle, GLFW_CURSOR),
                    "the loop's thread may not call GLFW, so nothing can have changed yet");

            window.pumpEvents();
            assertEquals(GLFW_CURSOR_DISABLED, glfwGetInputMode(handle, GLFW_CURSOR),
                    "…and the pump, which is the thread that may, carries it out");
            assertTrue(Pointer.held(),
                    "GLFW_CURSOR_DISABLED is a lock, so the view need not recentre anything");

            onAnotherThread(Pointer::restore);
            window.pumpEvents();
            assertEquals(GLFW_CURSOR_NORMAL, glfwGetInputMode(handle, GLFW_CURSOR),
                    "a paused game is a menu, and a menu is worked with the pointer");
        } finally {
            choice.backend().renderer().dispose();
            window.close();
        }
    }

    /**
     * A closed window lets go of the pointer, and nothing can ask it for one
     * afterwards.
     *
     * <p>The second half is the one with teeth. The handler holds a raw GLFW
     * window handle; a scene that hid the cursor on the way out — which is
     * exactly what leaving a first-person view does — would otherwise reach a
     * destroyed window through it, and that is a segfault rather than an
     * exception. The mode itself cannot be read back here because the window it
     * belonged to no longer exists, which is the same reason the release has to
     * happen inside {@code close()} rather than at the next pump.
     */
    @Test
    void aClosedWindowLetsGoOfThePointer() throws Exception {
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlWindow window = (GlWindow) choice.backend().window();
        long handle = window.context().window();
        window.attachInput(new InputManager());
        onAnotherThread(() -> Pointer.setVisible(false));
        window.pumpEvents();
        assertEquals(GLFW_CURSOR_DISABLED, glfwGetInputMode(handle, GLFW_CURSOR));

        choice.backend().renderer().dispose();
        window.close();

        assertFalse(Pointer.canWarp(), "the window is gone, so nothing may be asked of it");
        Pointer.setVisible(false); // would reach a freed handle if it were still attached
        Pointer.restore();
    }

    private static void onAnotherThread(Runnable work) throws InterruptedException {
        Thread loop = new Thread(work, "not-the-pump");
        loop.start();
        loop.join();
    }
}
