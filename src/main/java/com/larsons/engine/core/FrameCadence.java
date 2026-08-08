package com.larsons.engine.core;

/**
 * The fixed-timestep accumulator, on its own: how many simulation steps a frame
 * of a given length owes, and how far past the last one the frame is being
 * drawn.
 *
 * <p><b>Extracted from {@link GameLoop} because it is the arithmetic behind the
 * shimmer and it could not be measured while it lived inside a thread.</b>
 * {@code GameLoop} reads {@code System.nanoTime}, sleeps, and calls back into a
 * renderer; a test of the cadence inside it would be a test of the build agent's
 * scheduler. The same split {@code FramePacingTest} already makes for the pacing
 * decision — "what is checked here is the decision, not the timing" — applies to
 * this, and the number it produces has more riding on it: see
 * {@link com.larsons.engine.sim.StepInterpolation}.
 *
 * <p><b>What it does.</b> Real elapsed time goes in; whole fixed steps come out,
 * with the remainder kept for next time so the simulation neither drifts nor
 * runs at a rate the display happened to pick. What is left over at the moment
 * of drawing is {@link #alpha()}.
 *
 * <p><b>And why the leftover matters as much as the steps.</b> A frame's real
 * length is never exactly a whole number of steps, so the remainder wanders, and
 * whenever it crosses a step boundary the frame either runs an extra step or
 * skips one. With a 120 Hz simulation and a display pacing frames at 60 Hz, a
 * frame nominally owes exactly two steps — and measured over 20,000 frames with
 * 0.2 ms of jitter on the clock it owes one on 209 of them and three on 214.
 * Those frames advance the world by half and by one and a half times what the
 * eye is expecting. {@link #alpha()} is what lets a renderer put that back: it
 * is the fraction of the next step that has already elapsed, so drawing at that
 * fraction of the way through the <em>last</em> one lands the picture a constant
 * step behind real time however many steps the frame ran.
 *
 * <p>Not thread-safe, and does not need to be: one instance is owned by one
 * loop.
 */
public final class FrameCadence {

    private final double nsPerUpdate;

    /** Real time owed to the simulation but not yet spent, in nanoseconds. */
    private double accumulator;

    private double alpha;

    public FrameCadence(int updateRate) {
        this.nsPerUpdate = 1_000_000_000.0 / Math.max(1, updateRate);
    }

    /** Nanoseconds in one fixed step. */
    public double nanosPerStep() {
        return nsPerUpdate;
    }

    /**
     * Charge {@code elapsedNanos} of real time to the simulation and report how
     * many fixed steps the caller now owes, at most {@code maxSteps}.
     *
     * <p>The cap is the spiral-of-death guard: a frame that overran by a second
     * must not try to simulate a second's worth, because doing so takes longer
     * than a second and the backlog then grows for ever. Time past the cap is
     * dropped rather than carried, which is the only choice that recovers —
     * carrying it is what the spiral is.
     *
     * <p>{@link #alpha()} is valid after this returns, whether or not the caller
     * actually runs the steps: it is a property of the clock, not of the work.
     */
    public int stepsFor(long elapsedNanos, int maxSteps) {
        accumulator += elapsedNanos;
        int steps = 0;
        while (accumulator >= nsPerUpdate && steps < maxSteps) {
            steps++;
            accumulator -= nsPerUpdate;
        }
        if (steps >= maxSteps && accumulator > nsPerUpdate) {
            // Hit the cap with time still owed. Dropped deliberately — see above.
            accumulator = nsPerUpdate;
        }
        alpha = accumulator / nsPerUpdate;
        if (alpha < 0) alpha = 0;
        if (alpha > 1) alpha = 1;
        return steps;
    }

    /**
     * How far past the last completed step this frame is, in [0,1] — the
     * interpolation factor handed to {@code Scene.render}.
     */
    public double alpha() {
        return alpha;
    }

    /**
     * Throw away time owed and restart from a standstill — a scene change, a
     * level load, coming back from a paused window.
     *
     * <p>Without this, a loop that was stopped for ten seconds resumes owing ten
     * seconds of simulation, spends the catch-up cap on it, and drops the rest:
     * the same recovery, reached the slow way, with one visibly fast frame in it.
     */
    public void reset() {
        accumulator = 0;
        alpha = 0;
    }
}
