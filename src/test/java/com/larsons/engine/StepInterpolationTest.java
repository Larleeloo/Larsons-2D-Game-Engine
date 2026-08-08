package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.core.FrameCadence;
import com.larsons.engine.core.GameLoop;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.StepInterpolation;
import com.larsons.engine.world.World;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other shimmer: whether the world is <em>sampled</em> evenly in time.
 *
 * <p><b>Why this is a separate instrument from {@code CameraStabilityTest}.</b>
 * That one asks whether two things in the world stay the same distance apart
 * while the camera moves, and the answer is yes — {@code Camera} quantises the
 * world onto a lattice with no camera term in it, so the picture is a rigid
 * sheet and every pixel of it moves together. It cannot see this defect at all,
 * because this one moves the whole sheet: it is about <em>when</em> the sheet is
 * sampled, not about how it is drawn. A rigidly-drawn world sampled at an uneven
 * cadence looks exactly like a world that is not rigid, and a player reports both
 * as "the blocks are shaking".
 *
 * <p><b>The defect, in one sentence.</b> {@code GameLoop} computes an
 * interpolation {@code alpha}, {@code Scene.render} documents it, and no scene
 * read it — so every frame drew the last completed 120 Hz simulation step whole,
 * while the display presented frames on an unrelated clock, and the number of
 * steps a frame contained varied between one and three.
 *
 * <p>What is checked here is the arithmetic, not the timing — the same split
 * {@code FramePacingTest} makes, and for the same reason: an assertion about
 * frame times measures the build agent's scheduler, while the cadence and the
 * blend are pure functions and are the parts that regressed.
 */
class StepInterpolationTest {

    private static final int UPDATE_RATE = 120;

    /**
     * One fixed step, in nanoseconds — as a {@code double}, which is how
     * {@link FrameCadence} holds it. Rounding it to a whole nanosecond first puts
     * a systematic 4 parts per billion between this test's idea of elapsed time
     * and the accumulator's, which the exact linearity assertion below correctly
     * notices.
     */
    private static final double STEP_NANOS = 1_000_000_000.0 / UPDATE_RATE;

    /** A 60 Hz display, which is what the reports from the Air were taken on. */
    private static final double REFRESH_HZ = 60.0;

    /** {@link com.larsons.engine.sim.PlayerPhysics#SPEED}, in world px/sec. */
    private static final double SPEED = 220;

    /** A zoom that is not a whole number, as the player's zoom key leaves it. */
    private static final double ZOOM = 1.7;

    private static final int FRAMES = 20_000;

    // --- the cadence, on its own ----------------------------------------------------

    /**
     * A frame does not contain a whole number of simulation steps, and that is
     * the source of everything below.
     *
     * <p>Nominally a 16.67 ms frame owes exactly two 8.33 ms steps. It does not:
     * the frame clock and the step size are unrelated quantities, the remainder
     * in the accumulator wanders, and whenever it crosses a step boundary the
     * frame runs three steps or one. This asserts that both happen, because a
     * fix for something that never happens is not worth having and a test that
     * cannot see the defect cannot prove the fix.
     */
    @Test
    void aFrameOwesAVaryingNumberOfSimulationSteps() {
        TreeSet<Integer> counts = new TreeSet<>();
        int ones = 0, threes = 0;
        FrameCadence cadence = new FrameCadence(UPDATE_RATE);
        for (long elapsed : jitteryFrameClock(FRAMES)) {
            int steps = cadence.stepsFor(elapsed, GameLoop.MAX_CATCH_UP_STEPS);
            counts.add(steps);
            if (steps == 1) ones++;
            if (steps == 3) threes++;
        }

        assertEquals(new TreeSet<>(java.util.List.of(1, 2, 3)), counts,
                "a 60 Hz frame clock against a 120 Hz simulation should owe 1, 2 or 3 steps");
        assertTrue(ones > 0 && threes > 0,
                "the uneven frames are the defect and this run had ones=" + ones
                        + " threes=" + threes + " — with none of them there is "
                        + "nothing here to fix");
    }

    /**
     * The accumulator neither gains nor loses time: over a long run the steps it
     * hands out add up to the real time that went in.
     *
     * <p>This is the property that makes the fixed step worth having at all — the
     * simulation runs at its own rate rather than at whatever rate the display
     * picked — and it is what a "fix" that quantised the frame clock would
     * quietly break.
     */
    @Test
    void theCadenceKeepsSimulationTimeEqualToRealTime() {
        long[] clock = jitteryFrameClock(FRAMES);
        long realNanos = 0;
        int steps = 0;
        FrameCadence cadence = new FrameCadence(UPDATE_RATE);
        for (long elapsed : clock) {
            realNanos += elapsed;
            steps += cadence.stepsFor(elapsed, GameLoop.MAX_CATCH_UP_STEPS);
        }
        double owed = realNanos / STEP_NANOS;
        assertTrue(Math.abs(owed - steps) <= 1.0,
                "the simulation ran " + steps + " steps for " + owed + " steps' worth "
                        + "of real time — the accumulator is drifting");
    }

    /**
     * A frame that overran past the catch-up cap drops the excess rather than
     * carrying it, which is the only policy that recovers.
     */
    @Test
    void aHitchIsDroppedAtTheCapRatherThanCarried() {
        FrameCadence cadence = new FrameCadence(UPDATE_RATE);
        assertEquals(GameLoop.MAX_CATCH_UP_STEPS,
                cadence.stepsFor((long) (STEP_NANOS * 400), GameLoop.MAX_CATCH_UP_STEPS),
                "a four-hundred-step hitch is capped");
        // The next ordinary frame is ordinary again: the backlog is gone.
        assertEquals(2, cadence.stepsFor((long) (STEP_NANOS * 2), GameLoop.MAX_CATCH_UP_STEPS),
                "the frame after a capped hitch still owes the backlog — that is "
                        + "the spiral the cap exists to prevent");
    }

    // --- what the blend does with it -------------------------------------------------

    /**
     * <b>The claim, exactly.</b> The interpolated position of a body moving at a
     * constant speed is a linear function of elapsed real time, one fixed step
     * behind it — whatever number of steps each frame happened to run.
     *
     * <p>This is stronger than "smoother" and it is why interpolation is the fix
     * rather than a mitigation. Let a frame end having run {@code N} steps in
     * total with {@code a} of a step left in the accumulator. The body's
     * simulated position is {@code N·s} and the position it held before the last
     * step it took is {@code (N-1)·s}, so the drawn position is
     * {@code (N-1)·s + a·s = s·(N + a - 1)}. Real elapsed time, measured in
     * steps, is exactly {@code N + a} — that is what the accumulator holding the
     * remainder <em>means</em>. So drawn position is {@code s·(t - 1)}: linear in
     * real time, with a constant one-step lag and no dependence on the step count
     * at all.
     *
     * <p>A constant lag is invisible. The variable one is the bug, and this is
     * the assertion that it is gone.
     */
    @Test
    void theDrawnPositionIsLinearInRealTimeWhateverTheStepCountWas() {
        FrameCadence cadence = new FrameCadence(UPDATE_RATE);
        PlayerState body = new PlayerState();
        double perStep = SPEED / UPDATE_RATE;
        long realNanos = 0;

        for (long elapsed : jitteryFrameClock(FRAMES)) {
            realNanos += elapsed;
            int steps = cadence.stepsFor(elapsed, GameLoop.MAX_CATCH_UP_STEPS);
            for (int i = 0; i < steps; i++) {
                body.beginStep();          // what PlayScene.update does, per step
                body.x += perStep;         // ...and what the physics does after it
            }

            double drawn = body.renderX(cadence.alpha());
            double stepsOfRealTime = realNanos / STEP_NANOS;
            assertEquals(perStep * (stepsOfRealTime - 1), drawn, 1e-6,
                    "the drawn position left real time's straight line after "
                            + stepsOfRealTime + " steps' worth of it");
        }
    }

    /**
     * And the same run, in the pixels a player is looking at: the world's
     * on-screen scroll per frame is even once it is interpolated and is not
     * before.
     *
     * <p>The measure is the change in the camera's single per-frame offset, which
     * is what {@code Camera.place} adds to every object in the scene — so this is
     * the whole picture's motion, terrain and blocks and all. Measured over
     * 20,000 frames of a 220 px/s run at zoom 1.7:
     *
     * <pre>
     *   60 Hz display    raw : scroll px/frame  -10:78  -9:136  -7:4567  -6:15009  -4:22  -3:187
     *                        : 3.62% of frames changed speed by more than 1 px, worst 7
     *                  fixed : scroll px/frame  -7:4684  -6:15314  -5:1
     *                        : 0.01%, worst 2
     *   144 Hz display   raw : 27.63% of frames — 3,329 of them drew no new step at all
     *                  fixed : 0.00%
     * </pre>
     *
     * <p>One pixel is unavoidable and is not the defect: a 220 px/s pan at zoom
     * 1.7 is 6.23 px a frame, and an integer lattice can only render that as an
     * alternation of 6 and 7. That alternation is regular, which is what the eye
     * reads as smooth motion. What the fix removes is the irregular part — a
     * whole simulation step of movement appearing or vanishing between one frame
     * and the next.
     *
     * <p><b>The 144 Hz row is the case worth not missing.</b> A 120 Hz simulation
     * cannot feed a faster display, so better than one frame in four contained no
     * new step and repeated the one before it. That is the same defect at its most
     * severe, and it is the configuration a player with a good monitor is in.
     */
    @Test
    void theWorldScrollsByAnEvenAmountOnlyOnceItIsInterpolated() {
        for (double refreshHz : new double[]{60.0, 144.0}) {
            long[] clock = jitteryFrameClock(FRAMES, refreshHz);
            Scroll interpolated = scrollOf(clock, true);
            Scroll raw = scrollOf(clock, false);

            assertTrue(interpolated.unevenFraction < 0.001 && interpolated.worstJerk <= 2,
                    refreshHz + " Hz: an interpolated pan changed its on-screen speed "
                            + "unevenly on " + interpolated + " — only the lattice's own "
                            + "1 px, and the occasional clock-jitter rounding of it, is "
                            + "allowed");
            assertTrue(raw.unevenFraction > 0.01 && raw.worstJerk >= 3,
                    refreshHz + " Hz: the un-interpolated pan measured " + raw
                            + ", so this run does not reproduce the defect and proves "
                            + "nothing about the fix");
        }
    }

    /** How evenly a pan scrolled: the worst jerk in pixels, and how often one happened. */
    private record Scroll(int worstJerk, double unevenFraction) {
        @Override
        public String toString() {
            return "%.2f%% of frames, worst %d px".formatted(unevenFraction * 100, worstJerk);
        }
    }

    /**
     * The evenness of the world's on-screen scroll over {@code clock}, drawing
     * either the interpolated position or the raw simulated one.
     */
    private static Scroll scrollOf(long[] clock, boolean interpolate) {
        FrameCadence cadence = new FrameCadence(UPDATE_RATE);
        PlayerState body = new PlayerState();
        double perStep = SPEED / UPDATE_RATE;
        int worst = 0, uneven = 0, pairs = 0;
        long previousOffset = Long.MIN_VALUE;
        long previousScroll = Long.MIN_VALUE;

        for (long elapsed : clock) {
            int steps = cadence.stepsFor(elapsed, GameLoop.MAX_CATCH_UP_STEPS);
            for (int i = 0; i < steps; i++) {
                body.beginStep();
                body.x += perStep;
            }
            double focus = interpolate ? body.renderX(cadence.alpha()) : body.x;
            // Camera.place's single per-frame term, which the whole scene shares.
            long offset = Math.round(-focus * ZOOM);
            if (previousOffset != Long.MIN_VALUE) {
                long scroll = offset - previousOffset;
                if (previousScroll != Long.MIN_VALUE) {
                    int jerk = (int) Math.abs(scroll - previousScroll);
                    worst = Math.max(worst, jerk);
                    if (jerk > 1) uneven++;
                    pairs++;
                }
                previousScroll = scroll;
            }
            previousOffset = offset;
        }
        return new Scroll(worst, pairs == 0 ? 0 : (double) uneven / pairs);
    }

    // --- the standstill and the teleport ---------------------------------------------

    /**
     * A body that did not move is drawn in the same place at every alpha.
     *
     * <p>Not a triviality: this is what makes a paused frame, an open character
     * choice and a running cutscene hold still. The scenes record a standstill on
     * every tick before any of those can return early, precisely so that two
     * positions are never left straddling a step nothing took — which would show
     * as the world sliding back and forth while nothing is happening.
     */
    @Test
    void aBodyThatDidNotMoveDoesNotMoveAtAnyAlpha() {
        PlayerState body = new PlayerState();
        body.x = 1234.5;
        body.y = 678.25;
        body.beginStep();
        for (double alpha = 0; alpha <= 1.0; alpha += 0.05) {
            assertEquals(1234.5, body.renderX(alpha), 0.0);
            assertEquals(678.25, body.renderY(alpha), 0.0);
        }
    }

    /**
     * A relocation is drawn where the body went, not smeared across the gap.
     *
     * <p>One guard for every case that produces one — a spawn, a respawn, a door
     * warp, a level load, a server correction — because annotating each call site
     * would work until the next site was added.
     */
    @Test
    void aRelocationIsDrawnAtItsDestinationRatherThanBlendedAcross() {
        double from = 100, warpedTo = from + StepInterpolation.RELOCATION_PIXELS + 1;
        assertEquals(warpedTo, StepInterpolation.at(from, warpedTo, 0.0), 0.0,
                "a warp forward was blended, which draws the body mid-flight");
        assertEquals(from - 500, StepInterpolation.at(from, from - 500, 0.5), 0.0,
                "a warp backward was blended");

        // And ordinary movement, which is what the guard must not catch. The
        // fastest legitimate step in the engine is a fraction of the threshold.
        assertEquals(102.5, StepInterpolation.at(100, 105, 0.5), 1e-9);
    }

    /**
     * A body whose previous position was never captured draws where it is.
     *
     * <p>Every entity is spawned with its position assigned after construction,
     * so its remembered position is the origin until the first step captures one.
     * The origin is a relocation away from anywhere else in a level, so the same
     * guard covers it — which is why no constructor has to remember to prime it.
     */
    @Test
    void aFreshlySpawnedBodyDrawsWhereItIsRatherThanFlyingInFromTheOrigin() {
        Mob mob = new Mob(1, MobRegistry.standard().all().iterator().next(), 2400, 960);
        assertEquals(2400, mob.renderX(0.0), 0.0);
        assertEquals(960, mob.renderY(0.0), 0.0);

        // And once it has been stepped, it interpolates normally.
        mob.beginStep();
        mob.x += 3;
        assertEquals(2401.5, mob.renderX(0.5), 1e-9);
    }

    /**
     * The blend is a rendering concern and writes nothing: asking where to draw a
     * body leaves the body exactly as the simulation left it.
     *
     * <p>{@code SIM_PLAN} invariant 1 — a change that makes one machine faster
     * and two machines disagree is not an optimisation — turns on this. A server
     * that renders nothing never calls any of it, and a client's simulated result
     * has to be identical with the blend and without it or multiplayer desyncs
     * under load and looks like a network fault.
     */
    @Test
    void askingWhereToDrawABodyDoesNotMoveIt() {
        PlayerState body = new PlayerState();
        body.x = 40;
        body.y = 50;
        body.z = 6;
        body.beginStep();
        body.x = 43;
        body.y = 52;
        body.z = 9;

        for (double alpha = 0; alpha <= 1; alpha += 0.125) {
            body.renderX(alpha);
            body.renderY(alpha);
            body.renderZ(alpha);
        }
        assertEquals(43, body.x, 0.0);
        assertEquals(52, body.y, 0.0);
        assertEquals(9, body.z, 0.0);
        assertNotEquals(body.x, body.prevX, "the captured position is still the old one");
    }

    // --- the capture, where a world owns it ------------------------------------------

    /**
     * A world's bodies are captured once per tick, not once per caller.
     *
     * <p>A scene captures at the top of its tick and then moves things before it
     * calls {@code World.step} — driving a ridden vehicle is the case that
     * exists. A second capture inside {@code step} would throw away the part of
     * the tick that had already happened, and the blend would cover only the
     * remainder: the ridden vehicle would sit a fraction of a step from the rider
     * on its saddle. This pins the latch that prevents it, and pins the other
     * direction too — a caller that never captures still gets one.
     */
    @Test
    void aWorldCapturesOncePerTickHoweverManyCallersAskFor() {
        Level level = Level.empty("capture", 30, 20, 32);
        World world = new World(level);
        GameProfile profile = new GameProfile("capture");
        profile.mobsEnabled = false;   // nothing is allowed to move on its own here
        profile.itemsEnabled = false;

        DroppedItem drop = world.spawnItem("stone", 1, 300, 200);
        drop.beginStep();              // a first capture, as if from a spawn tick

        // The scene's capture at the top of the tick, then work that moves the
        // body, then the step — which must not re-capture over the movement.
        world.beginStep();
        drop.x += 4;
        world.step(1.0 / UPDATE_RATE, java.util.List.of(new PlayerState()), profile);
        assertEquals(302, drop.renderX(0.5), 1e-9,
                "the step re-captured over movement the tick had already made, so "
                        + "half a step of it is missing from the blend");

        // And a caller that does not capture for itself (the headless server, and
        // every test) still gets one from the step.
        double before = drop.x;
        drop.x += 6;
        world.step(1.0 / UPDATE_RATE, java.util.List.of(new PlayerState()), profile);
        assertEquals(before + 6, drop.renderX(0.0), 1e-9,
                "an uncaptured tick left a stale previous position behind");
    }

    // --- the clock -------------------------------------------------------------------

    /**
     * Frame lengths from a display pacing at {@link #REFRESH_HZ} with a little
     * jitter on them — a fixed seed, so the run is the same every time and a
     * failure is reproducible.
     *
     * <p>The jitter is 0.2 ms, which is small: the point is that it does not take
     * a badly-behaved machine to produce the defect, only a frame clock that is
     * not a whole multiple of the step. A perfectly regular clock would be the
     * one case where the old code was correct, and no real display is one.
     */
    private static long[] jitteryFrameClock(int frames) {
        return jitteryFrameClock(frames, REFRESH_HZ);
    }

    /** The same, at a stated refresh rate. */
    private static long[] jitteryFrameClock(int frames, double refreshHz) {
        long[] out = new long[frames];
        double nominal = 1_000_000_000.0 / refreshHz;
        Random rnd = new Random(7);
        for (int i = 0; i < frames; i++) {
            out[i] = Math.max(1, Math.round(nominal + rnd.nextGaussian() * 200_000.0));
        }
        return out;
    }
}
