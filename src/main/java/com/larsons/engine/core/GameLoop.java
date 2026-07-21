package com.larsons.engine.core;

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

    public GameLoop(int updateRate, int targetFps, Update update, Render render) {
        this.updateRate = Math.max(1, updateRate);
        this.targetFps = Math.max(1, targetFps);
        this.update = update;
        this.render = render;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    public void stop() { running = false; }

    public double getFps() { return fps; }

    public int getTargetFps() { return targetFps; }

    /** Adjust the render frame cap at runtime (e.g. from a settings menu). */
    public void setTargetFps(int fps) { this.targetFps = Math.max(1, fps); }

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

            int maxUpdates = 8; // cap catch-up to avoid a spiral of death
            while (accumulator >= nsPerUpdate && maxUpdates-- > 0) {
                update.update(1.0 / updateRate);
                accumulator -= nsPerUpdate;
            }

            double alpha = Math.max(0.0, Math.min(1.0, accumulator / nsPerUpdate));
            render.render(alpha);

            frames++;
            if (frameStart - fpsTimer >= 1_000_000_000L) {
                fps = frames;
                frames = 0;
                fpsTimer += 1_000_000_000L;
            }

            // Frame limiter, recomputed each iteration so the cap can change at
            // runtime.
            long nsPerFrame = (long) (1_000_000_000.0 / targetFps);
            nextFrame += nsPerFrame;
            long now = System.nanoTime();
            if (nextFrame <= now) {
                // Fell behind (hitch or cap change): restart the schedule rather
                // than racing to catch up.
                nextFrame = now;
            } else if (!waitUntil(nextFrame)) {
                break; // interrupted
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
