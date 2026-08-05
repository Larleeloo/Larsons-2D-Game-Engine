package com.larsons.engine;

import com.larsons.engine.core.GameLoop;
import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.draw.DrawTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is pacing the frames, and what happens when two things think they are.
 *
 * <p><b>This exists because two individually correct pacers cost a third of the
 * frame rate, and nothing in the build noticed.</b> A profile from an M1 Air
 * with vsync on measured the GL renderer's {@code present} at a p99 of
 * <b>16.752 ms</b> — one refresh period at 60 Hz, so the swap really was
 * blocking on the panel — and the game loop's own limiter idling a further
 * <b>11.975 ms</b> at p50 on top of it. That is 21.5 ms a frame against a
 * 16.67 ms budget: <b>46 FPS while asking for 60</b>.
 *
 * <p>The failure is not that frames were slow. It is that they were <em>uneven</em>.
 * The limiter schedules on an absolute timeline from {@code System.nanoTime} and
 * the display refreshes on an unrelated one, so the phase between them drifts;
 * whenever the limiter's deadline lands just after a refresh boundary, that
 * frame's swap misses the boundary and blocks a whole further period. The loop
 * alternates between one refresh and two, and so does the apparent motion of
 * everything on screen. A world drawn rigidly and delivered unevenly looks like
 * a world that is not rigid — which is why this is filed with the shimmer and
 * not with performance.
 *
 * <p>What is checked here is the decision, not the timing. Timing assertions in
 * a test suite measure the build agent's scheduler; the decision is a pure
 * function and is the part that regressed.
 */
class FramePacingTest {

    /** A backend that waits for the panel, as the GL one does with vsync on. */
    private static final Renderer PACED = new StubRenderer(true);

    /** And one that does not, as Java2D does not. */
    private static final Renderer UNPACED = new StubRenderer(false);

    /**
     * A loop told that something else is pacing stops enforcing the player's
     * cap — and does not stop enforcing anything at all.
     */
    @Test
    void aPacedLoopStandsDownButKeepsARunawayGuard() {
        GameLoop loop = new GameLoop(120, 60, dt -> {}, alpha -> {});

        assertEquals(60, loop.effectiveCap(), "an unpaced loop enforces the cap it was given");
        assertFalse(loop.isExternallyPaced());

        loop.setExternallyPaced(true);
        assertTrue(loop.isExternallyPaced());
        assertTrue(loop.effectiveCap() > 60,
                "a display-paced loop is still capping at " + loop.effectiveCap()
                        + " FPS, so it is pacing frames the display has already paced");
        assertTrue(loop.effectiveCap() <= 1000,
                "a display-paced loop dropped its cap entirely (" + loop.effectiveCap()
                        + "); a driver that reports vsync and does not honour it would then "
                        + "spin a core and feed the fixed-step simulation frame times of "
                        + "nearly zero");
    }

    /**
     * The cap the player chose is not quietly rewritten — the profiler measures
     * headroom against it, and a report whose budget changed to something nobody
     * asked for cannot be compared with the one before it.
     */
    @Test
    void theRequestedCapSurvivesBeingStoodDownFrom() {
        GameLoop loop = new GameLoop(120, 60, dt -> {}, alpha -> {});
        loop.setExternallyPaced(true);

        assertEquals(60, loop.getTargetFps(),
                "standing down from the cap must not change what the cap is");
    }

    /** And it follows the cap when the player changes it mid-run. */
    @Test
    void thePacedGuardStaysAboveWhateverCapIsAskedFor() {
        GameLoop loop = new GameLoop(120, 60, dt -> {}, alpha -> {});
        loop.setExternallyPaced(true);
        loop.setTargetFps(360);

        assertTrue(loop.effectiveCap() >= 360,
                "a paced loop capped below the rate the player asked for (" + loop.effectiveCap()
                        + " < 360), which would pace frames the display is not pacing");
    }

    /**
     * Which backends declare that they pace. The default has to be "no",
     * because a limiter that stands down for a backend that presents
     * immediately is a loop with no limiter at all.
     */
    @Test
    void onlyABackendThatWaitsForThePanelClaimsToPace() {
        assertTrue(PACED.presentationIsPaced());
        assertFalse(UNPACED.presentationIsPaced(),
                "a backend that presents without waiting claimed to be pacing frames");
        assertFalse(new Renderer() {
            @Override public DrawTarget beginFrame() { return null; }
            @Override public void present() {}
            @Override public int getWidth() { return 0; }
            @Override public int getHeight() { return 0; }
            @Override public void dispose() {}
        }.presentationIsPaced(), "the default must be 'no', not 'yes'");
    }

    /**
     * And the loop really does run: a paced loop that never limits still ticks,
     * still renders, and still stops.
     *
     * <p>The one timing-adjacent thing worth checking, and it is checked as a
     * count rather than as a rate. A `setExternallyPaced` that accidentally made
     * `nsPerFrame` zero or negative would hang here rather than fail an
     * assertion about milliseconds.
     */
    @Test
    @Timeout(10)
    void aPacedLoopStillTicksAndStops() throws InterruptedException {
        AtomicInteger updates = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();
        GameLoop loop = new GameLoop(120, 60,
                dt -> updates.incrementAndGet(), alpha -> renders.incrementAndGet());
        loop.setExternallyPaced(true);

        loop.start();
        Thread.sleep(200);
        loop.stop();
        assertTrue(loop.awaitStop(2000), "a paced loop did not stop when asked");

        assertTrue(updates.get() > 0, "a paced loop ran no simulation steps");
        assertTrue(renders.get() > 0, "a paced loop rendered nothing");
    }

    /** A renderer that exists only to answer one question. */
    private record StubRenderer(boolean paced) implements Renderer {
        @Override public DrawTarget beginFrame() { return null; }
        @Override public void present() {}
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public void dispose() {}
        @Override public boolean presentationIsPaced() { return paced; }
    }
}
