package com.larsons.engine.core;

import com.larsons.engine.profile.FrameProfiler;

import java.util.concurrent.locks.LockSupport;

/**
 * Fixed-timestep game loop running on a dedicated thread.
 *
 * <p>Design (mapping to the project requirements):
 * <ul>
 *   <li><b>Fixed update rate, decoupled from rendering.</b> Simulation advances
 *       in fixed {@code 1/updateRate} steps, which keeps it deterministic — the
 *       foundation the networked play (requirement #3) is built on: the server
 *       ticks the same fixed-step simulation clients predict with.</li>
 *   <li><b>Render cap.</b> Rendering is limited to {@code targetFps}
 *       (requirement #1: 120 FPS) and receives an interpolation {@code alpha}
 *       so motion stays smooth when render and update rates differ.</li>
 *   <li><b>Precise, CPU-friendly pacing.</b> {@code Thread.sleep} alone
 *       oversleeps by a scheduler quantum (commonly 1–2 ms, more on Windows),
 *       which at 120 FPS (8.3 ms frames) costs real frames. The limiter sleeps
 *       coarsely to ~2 ms before the deadline, then parks in short slices for
 *       the remainder — near-exact wakeups without busy-spinning a core. Frames
 *       are also scheduled on an absolute timeline (next deadline advances by
 *       exactly one frame period) so timing errors don't accumulate as drift.</li>
 *   <li><b>Spiral-of-death guard.</b> Catch-up updates per frame are capped so a
 *       hitch can't snowball into an ever-growing update backlog.</li>
 * </ul>
 */
public final class GameLoop implements Runnable {

    /** Fixed-step simulation callback. */
    @FunctionalInterface
    public interface Update {
        void update(double dt);
    }

    /** Render callback; {@code alpha} is the interpolation factor in [0,1]. */
    @FunctionalInterface
    public interface Render {
        void render(double alpha);
    }

    private final int updateRate;
    private volatile int targetFps;   // render cap; adjustable at runtime
    private final Update update;
    private final Render render;

    private volatile boolean running;
    private Thread thread;
    private volatile double fps;

    /**
     * Whether the renderer's {@code present()} already waits for the display.
     * See {@link #setExternallyPaced}.
     */
    private volatile boolean externallyPaced;

    /**
     * The cap the limiter falls back to when something else is pacing frames.
     *
     * <p>Not a frame rate anyone is aiming for — a runaway guard. A driver that
     * reports vsync and does not actually block would otherwise leave the loop
     * with nothing limiting it at all, spinning a core and running the
     * simulation's catch-up path against a frame time of nearly zero. Far above
     * any refresh rate this will meet, so on a display that really is pacing the
     * frames this is never reached.
     */
    private static final int PACED_SAFETY_FPS = 240;

    /**
     * Optional frame instrumentation. The loop owns the two stages nobody else
     * can see: the fixed-step simulation, and the time the limiter spends
     * waiting — the headroom that says whether a frame had anything to spare.
     */
    private FrameProfiler profiler;

    public GameLoop(int updateRate, int targetFps, Update update, Render render) {
        this.updateRate = Math.max(1, updateRate);
        this.targetFps = Math.max(1, targetFps);
        this.update = update;
        this.render = render;
    }

    /** Attach the profiler this loop reports update and idle time to. */
    public void setProfiler(FrameProfiler profiler) {
        this.profiler = profiler;
        if (profiler != null) profiler.setTargetFps(targetFps);
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    public void stop() { running = false; }

    /** Whether the loop is still ticking. */
    public boolean isRunning() { return running; }

    /**
     * Wait for the loop thread to finish, up to {@code millis} (0 for no
     * limit). Returns whether it did.
     *
     * <p>Shutdown needs this rather than a flag: a GPU backend's resources
     * belong to a context the loop thread was using, so freeing them while it
     * is still inside a frame is a segfault, not a leak. The bound is there so
     * a wedged frame delays the exit rather than preventing it.
     */
    public boolean awaitStop(long millis) {
        Thread t = thread;
        if (t == null) return true;
        try {
            t.join(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    public double getFps() { return fps; }

    public int getTargetFps() { return targetFps; }

    /** Adjust the render frame cap at runtime (e.g. from a settings menu). */
    public void setTargetFps(int fps) {
        this.targetFps = Math.max(1, fps);
        FrameProfiler p = profiler;
        // Headroom is measured against the cap, so the profiler has to follow it.
        if (p != null) p.setTargetFps(this.targetFps);
    }

    /**
     * Tell the loop that the renderer's {@code present()} already waits for the
     * display, so this limiter must stand down.
     *
     * <p><b>Two pacers is the whole bug, and it measured as a third of the
     * frame rate the game was asking for.</b> With vsync on, an M1 Air profile
     * put {@code present} at a p99 of 16.752 ms — one refresh period, so the
     * swap was blocking — and this limiter's {@code idle} at a p50 of 11.975 ms
     * on top of it. 21.5 ms a frame against a 16.67 ms budget: <b>46 FPS while
     * asking for 60</b>, delivered at whatever phase two unrelated schedules
     * drifted into. See {@link com.larsons.engine.graphics.Renderer#presentationIsPaced()}
     * for why two correct pacers compose badly.
     *
     * <p>Standing down is not the same as doing nothing. The cap becomes
     * {@link #PACED_SAFETY_FPS} rather than infinity, because a driver that
     * claims vsync and does not honour it would otherwise leave the loop
     * unlimited — and an unlimited loop is not merely wasteful here, it feeds
     * the fixed-step simulation frame times near zero and puts it in the
     * catch-up path permanently. The guard is far above any refresh rate this
     * will meet, so where the display really is pacing frames it never engages.
     *
     * <p>{@code targetFps} is deliberately left alone: it is what the player
     * asked for and what {@link FrameProfiler} measures headroom against, and a
     * report whose budget silently changed to something nobody chose is a report
     * that cannot be compared with the one before it.
     */
    public void setExternallyPaced(boolean paced) {
        this.externallyPaced = paced;
    }

    /** Whether something other than this limiter is pacing frames. */
    public boolean isExternallyPaced() { return externallyPaced; }

    /** The cap the limiter is actually enforcing, which is not always {@link #getTargetFps()}. */
    public int effectiveCap() {
        return externallyPaced ? Math.max(targetFps, PACED_SAFETY_FPS) : targetFps;
    }

    @Override
    public void run() {
        final double nsPerUpdate = 1_000_000_000.0 / updateRate;

        long lastTime = System.nanoTime();
        double accumulator = 0;

        long fpsTimer = System.nanoTime();
        int frames = 0;

        // Absolute schedule for the frame limiter: each frame's deadline is the
        // previous deadline plus one frame period, so oversleep on one frame is
        // recovered on the next instead of accumulating as drift.
        long nextFrame = System.nanoTime();

        while (running) {
            long frameStart = System.nanoTime();
            accumulator += (frameStart - lastTime);
            lastTime = frameStart;

            FrameProfiler prof = profiler;

            int maxUpdates = 8; // cap catch-up to avoid a spiral of death
            int stepsThisFrame = 0;
            while (accumulator >= nsPerUpdate && maxUpdates-- > 0) {
                stepsThisFrame++;
                // Every catch-up step is timed; they sum into one frame's
                // update cost, which is what a frame actually paid.
                long updateStart = prof == null ? 0L : prof.begin();
                try {
                    update.update(1.0 / updateRate);
                } finally {
                    if (prof != null) prof.record(FrameProfiler.Stage.UPDATE, updateStart);
                }
                accumulator -= nsPerUpdate;
            }
            // SIM_PLAN S1: without this, a 21 ms update stage is either one slow
            // step or eight ordinary ones and the report cannot say which —
            // which is a different bug with a different fix in each case.
            if (prof != null) prof.recordUpdateSteps(stepsThisFrame);

            double alpha = Math.max(0.0, Math.min(1.0, accumulator / nsPerUpdate));
            render.render(alpha);

            frames++;
            if (frameStart - fpsTimer >= 1_000_000_000L) {
                fps = frames;
                frames = 0;
                fpsTimer += 1_000_000_000L;
            }

            // Frame limiter, recomputed each iteration so the cap can change at
            // runtime — and so that a backend which starts pacing frames itself
            // (vsync) takes effect on the next frame rather than the next run.
            long nsPerFrame = (long) (1_000_000_000.0 / effectiveCap());
            nextFrame += nsPerFrame;
            long now = System.nanoTime();
            if (nextFrame <= now) {
                // Fell behind (hitch or cap change): restart the schedule rather
                // than racing to catch up. No idle time is recorded, which is
                // exactly right — a frame that overran had none.
                nextFrame = now;
                if (prof != null) prof.endFrame();
            } else {
                long idleStart = prof == null ? 0L : prof.begin();
                boolean uninterrupted = waitUntil(nextFrame);
                if (prof != null) {
                    prof.record(FrameProfiler.Stage.IDLE, idleStart);
                    prof.endFrame();
                }
                if (!uninterrupted) break; // interrupted
            }
        }
    }

    /**
     * Wait until an absolute {@code System.nanoTime()} deadline with much finer
     * precision than a bare {@code Thread.sleep}: sleep coarsely while more
     * than ~2 ms remains, then park in 100 µs slices for the tail. Returns
     * {@code false} if interrupted.
     *
     * <p>Public because the multiplayer servers pace their fixed-rate tick
     * loops with it too: a bare sleep oversleeps by a scheduler quantum
     * (~15 ms on Windows), which would drop a 60 Hz server to ~40 real ticks
     * per second — the simulation everyone plays on would run slow and every
     * client would feel permanently laggy no matter the ping.
     */
    public static boolean waitUntil(long deadlineNanos) {
        final long coarseMargin = 2_000_000L; // trust sleep() up to 2 ms early
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > coarseMargin) {
            long ms = (remaining - coarseMargin) / 1_000_000L;
            try {
                Thread.sleep(Math.max(1, ms));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        while (deadlineNanos - System.nanoTime() > 0) {
            LockSupport.parkNanos(100_000L);
            if (Thread.currentThread().isInterrupted()) return false;
        }
        return true;
    }
}
