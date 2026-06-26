package com.larsons.engine.core;

/**
 * Fixed-timestep game loop running on a dedicated thread.
 *
 * <p>Design (mapping to the project requirements):
 * <ul>
 *   <li><b>Fixed update rate, decoupled from rendering.</b> Simulation advances
 *       in fixed {@code 1/updateRate} steps, which keeps it deterministic — the
 *       right foundation for the networked play planned later (requirement #3).</li>
 *   <li><b>Render cap.</b> Rendering is limited to {@code targetFps}
 *       (requirement #1: 120 FPS) and receives an interpolation {@code alpha}
 *       so motion stays smooth when render and update rates differ.</li>
 *   <li><b>CPU-friendly.</b> The loop sleeps until the next frame is due rather
 *       than busy-spinning, so it won't peg a core.</li>
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
    private final int targetFps;
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

    @Override
    public void run() {
        final double nsPerUpdate = 1_000_000_000.0 / updateRate;
        final long nsPerFrame = (long) (1_000_000_000.0 / targetFps);

        long lastTime = System.nanoTime();
        double accumulator = 0;

        long fpsTimer = System.nanoTime();
        int frames = 0;

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

            // Frame limiter: sleep the remaining time until the next frame.
            long elapsed = System.nanoTime() - frameStart;
            long sleep = nsPerFrame - elapsed;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
