package com.larsons.engine.gl;

import com.larsons.engine.core.MacGlLauncher;
import com.larsons.engine.graphics.Backends;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The half of the headless decision that only this module can see.
 *
 * <p>{@code MacGlLauncherTest} in the core checks that a macOS GL run is the
 * <em>only</em> launch that goes headless, and it can check every negative case
 * because the core's own test classpath is the plain jar's world — no GPU
 * backend on it at all. That is exactly why it cannot check the positive one.
 * This classpath has {@code :gl} on it, so {@code Backends.discover()} finds a
 * backend here and the branch that actually fires becomes reachable.
 *
 * <p><b>Why the branch is worth a test rather than a reading.</b> Without it
 * the window's close button does nothing on macOS: AWT's
 * {@code [NSApplication run]} takes thread 0 from inside {@code glfwPollEvents}
 * and never gives it back, so the engine's pump enters {@code pumpEvents()}
 * once and never loops again. Nothing about that is visible while playing —
 * events still flow — which is precisely why it survived until a crash log
 * happened to show the stack. See {@link MacGlLauncher#keepAwtOffTheFirstThread}.
 */
class GlHeadlessLaunchTest {

    private String savedOs;
    private String savedBackend;
    private String savedHeadless;

    @AfterEach
    void restoreProperties() {
        if (savedOs != null) System.setProperty("os.name", savedOs);
        if (savedBackend == null) System.clearProperty(Backends.PROPERTY);
        else System.setProperty(Backends.PROPERTY, savedBackend);
        // Restored with care: this fork draws real frames through AWT in the
        // parity tests, and a stray headless flag left behind would fail them
        // somewhere with no connection to this file.
        if (savedHeadless == null) System.clearProperty(MacGlLauncher.HEADLESS_PROPERTY);
        else System.setProperty(MacGlLauncher.HEADLESS_PROPERTY, savedHeadless);
    }

    /**
     * A macOS launch with a GPU backend on the classpath goes headless, so that
     * AWT never starts an {@code NSApplication} on the thread GLFW is pumping.
     */
    @Test
    void aMacGlLaunchKeepsAwtOffTheFirstThread() {
        // Faking os.name is enough to reach the branch, and on a real Mac the
        // property is already whatever the launcher set, so there is nothing
        // left to observe. Skip rather than assert something vacuous.
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("mac"),
                "this is a real Mac; the launcher has already decided");

        savedOs = System.getProperty("os.name");
        savedBackend = System.getProperty(Backends.PROPERTY);
        savedHeadless = System.getProperty(MacGlLauncher.HEADLESS_PROPERTY);
        System.setProperty("os.name", "Mac OS X");
        System.setProperty(Backends.PROPERTY, Backends.AUTO);
        System.clearProperty(MacGlLauncher.HEADLESS_PROPERTY);

        MacGlLauncher.keepAwtOffTheFirstThread();

        assertEquals("true", System.getProperty(MacGlLauncher.HEADLESS_PROPERTY),
                "a macOS launch heading for the GL backend did not go headless; AWT will "
                        + "take the first thread from under glfwPollEvents and the window's "
                        + "close button will stop working");
    }
}
