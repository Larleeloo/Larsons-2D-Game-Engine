package com.larsons.engine.render;

import com.larsons.engine.core.MacGlLauncher;
import com.larsons.engine.graphics.Backends;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The first-thread relaunch stays out of the way of every launch that does not
 * need it.
 *
 * <p><b>What this can and cannot check.</b> The relaunch itself spawns a second
 * JVM and only ever happens on macOS with a GPU backend on the classpath, so the
 * successful path is not something a Linux build agent can exercise — that one
 * is verified by launching the GL distribution on a Mac. What <em>is</em> worth
 * pinning here is the opposite: that the mechanism is inert for everybody else.
 * A relaunch that fires when it should not is a game that starts twice, or a
 * Swing window on a thread AWT does not own, and both of those are worse than
 * the problem it exists to solve.
 *
 * <p>The macOS branch is reachable from here because {@code os.name} is an
 * ordinary system property: faking it proves the guard that stops a plain jar
 * relaunching is the classpath scan and not merely the platform check.
 */
class MacGlLauncherTest {

    private String savedOs;
    private String savedBackend;

    @AfterEach
    void restoreProperties() {
        if (savedOs != null) System.setProperty("os.name", savedOs);
        if (savedBackend == null) System.clearProperty(Backends.PROPERTY);
        else System.setProperty(Backends.PROPERTY, savedBackend);
        System.clearProperty(MacGlLauncher.CHILD_PROPERTY);
    }

    private void pretendMac() {
        savedOs = System.getProperty("os.name");
        System.setProperty("os.name", "Mac OS X");
    }

    private void request(String backend) {
        savedBackend = System.getProperty(Backends.PROPERTY);
        System.setProperty(Backends.PROPERTY, backend);
    }

    /** Every Linux and Windows player, and every CI agent. */
    @Test
    void nothingHappensOffMac() {
        assertFalse(MacGlLauncher.relaunchIfNeeded(new String[0]),
                "a non-macOS launch tried to relaunch itself");
    }

    /**
     * The plain jar on a Mac. There is no GPU backend on this classpath — the
     * core's own tests are the plain jar's world — so there is nothing a first
     * thread would buy, and spawning a second JVM to find that out would double
     * every launch of the shipping artefact.
     */
    @Test
    void nothingHappensOnAMacWithNoGpuBackendOnTheClasspath() {
        pretendMac();
        assertFalse(MacGlLauncher.relaunchIfNeeded(new String[0]),
                "a launch with no GPU backend on the classpath relaunched anyway");
    }

    /**
     * The one-flag workaround has to stay one flag. A player told to pass
     * {@code -Dlarsons.render.backend=java2d} because GL is misbehaving must not
     * get a process spawned to look for GL on the way.
     */
    @Test
    void nothingHappensWhenJava2DWasAskedForByName() {
        pretendMac();
        request(Backends.JAVA2D);
        assertFalse(MacGlLauncher.relaunchIfNeeded(new String[0]),
                "-Dlarsons.render.backend=java2d still triggered a GL relaunch");
    }

    /** A child can never spawn a child; that is a fork bomb with a window. */
    @Test
    void theRelaunchedChildNeverRelaunchesAgain() {
        pretendMac();
        request("gl");
        System.setProperty(MacGlLauncher.CHILD_PROPERTY, "true");
        assertFalse(MacGlLauncher.relaunchIfNeeded(new String[0]),
                "the relaunched child tried to relaunch itself");
    }

    /** And the escape hatch works, for whoever needs to turn this off. */
    @Test
    void theMechanismCanBeSwitchedOff() {
        pretendMac();
        request("gl");
        String saved = System.getProperty(MacGlLauncher.ENABLED_PROPERTY);
        System.setProperty(MacGlLauncher.ENABLED_PROPERTY, "false");
        try {
            assertFalse(MacGlLauncher.relaunchIfNeeded(new String[0]),
                    "-D" + MacGlLauncher.ENABLED_PROPERTY + "=false was ignored");
        } finally {
            if (saved == null) System.clearProperty(MacGlLauncher.ENABLED_PROPERTY);
            else System.setProperty(MacGlLauncher.ENABLED_PROPERTY, saved);
        }
    }
}
