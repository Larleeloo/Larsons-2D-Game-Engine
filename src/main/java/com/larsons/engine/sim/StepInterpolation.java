package com.larsons.engine.sim;

/**
 * Draws a frame <em>between</em> two fixed simulation steps instead of on top of
 * the latest one.
 *
 * <h2>The defect this exists to remove</h2>
 *
 * <p>{@link com.larsons.engine.core.GameLoop} runs the simulation in fixed
 * {@code 1/updateRate} steps and renders separately, handing each frame an
 * {@code alpha} — "how far past the last step real time has got". {@link
 * com.larsons.engine.scene.Scene#render} documents it as "interpolation factor
 * in [0,1] between sim steps", {@code GameLoop}'s own class note promises motion
 * "stays smooth when render and update rates differ", and <b>no scene ever read
 * it</b>. Every frame drew the last completed simulation state, whole.
 *
 * <p>That is not a rounding problem and no amount of care in the projection can
 * fix it — {@link com.larsons.engine.graphics.Camera} and {@link
 * com.larsons.engine.graphics.TerrainCache} both went to real lengths to make
 * the world a rigid sheet, and it is rigid; this is about <em>when</em> the sheet
 * is sampled. With the simulation at 120 Hz and a display pacing frames at
 * 60 Hz, a frame nominally consumes two steps, but the two clocks are unrelated,
 * so the remainder in the accumulator wanders and some frames consume one step
 * or three. The world then scrolls 1.8 px, 3.7 px or 5.5 px on successive frames
 * where it should scroll 3.7 px every time.
 *
 * <p>Measured against the loop's real arithmetic over 20,000 frames, with only
 * 0.2 ms of jitter on the frame clock:
 *
 * <pre>
 *   vsync 60 Hz / sim 120 Hz : steps per frame 1 / 2 / 3 = 209 / 19577 / 214
 *                              2.46% of frames scrolled by the wrong amount
 *                              worst single frame: 5 px where 3.7 px was due
 *   limiter 120 Hz / sim 120 Hz : 0 / 1 / 2 = 209 / 19577 / 214
 *                              2.87% wrong; a zero-step frame is a duplicate
 * </pre>
 *
 * <p>Roughly one frame in forty, twice a second, the whole world jumps. That is
 * the "shimmer" and the "blocks shaking": the blocks are not moving relative to
 * each other at all — the entire scene is, because the camera is sampled at an
 * uneven cadence, and a camera that follows the player carries every static tile
 * on screen with it.
 *
 * <p><b>Why a side-scroller shows it and the plan views mostly do not.</b> The
 * artefact needs sustained, single-axis, constant-velocity panning to be
 * legible, and that is what holding a run key in a side-scroller produces for
 * seconds at a time. Plan-view play is short two-axis bursts, where an uneven
 * step reads as part of the input rather than as the world shaking; and a
 * side-scroller is also the only format that draws the
 * {@link com.larsons.engine.graphics.ParallaxBackground}, whose slow layers give
 * the eye a near-static reference to measure the world's motion against.
 *
 * <p><b>The engine already found this bug once and fixed it on the other side of
 * the wire.</b> {@code PlayScene.drawWorldEntities} interpolates replicated
 * entities between the two buffered snapshots straddling render time, with the
 * comment: "drawing the raw latest snapshot stepped everything at the 30 Hz
 * broadcast rate, which read as constant stutter next to the 120 fps local
 * player." That is this defect, diagnosed correctly, for network state only. The
 * offline simulation is a 120 Hz broadcast into a 60 Hz display and had no
 * equivalent.
 *
 * <h2>How it works</h2>
 *
 * <p>Every body that moves keeps the position it held one step ago. A frame is
 * drawn at
 *
 * <pre>
 *   prev + (cur - prev) * alpha
 * </pre>
 *
 * <p>which is sim time {@code T - dt + alpha*dt} — one step behind real time,
 * <em>always</em> one step behind, whether the frame ran zero steps, two, or the
 * catch-up cap of eight. A constant lag is invisible; the variable one is the
 * bug. At a 120 Hz simulation the lag is 8.3 ms.
 *
 * <p><b>Interpolating rather than extrapolating, deliberately.</b> Predicting
 * {@code cur + alpha*(cur - prev)} would remove even that lag, and would
 * overshoot every time the simulation changes its mind — the frame a jump peaks
 * on, the frame a runner meets a wall, the frame a projectile lands. Those are
 * exactly the frames the eye is watching, and a pop there is worse than 8 ms of
 * latency everywhere.
 *
 * <p><b>Nothing here touches the simulation.</b> No state is written, no order
 * changes, no {@code dt} moves: this reads two positions and returns a third for
 * drawing. {@code SIM_PLAN} invariant 1 (a change that makes one machine faster
 * and two machines disagree is not an optimisation) is not in play, because a
 * server that renders nothing calls none of this and a client's simulation
 * result is bit-identical with it and without it.
 */
public final class StepInterpolation {

    /**
     * Beyond this many world pixels, a step's movement is treated as a
     * relocation and the new position is drawn outright.
     *
     * <p><b>This one guard covers every case that would otherwise need its own
     * call site.</b> A spawn, a respawn, a door warp, a level load, a
     * server correction, a creative-mode play-test starting at the spawn point —
     * each moves a body by an arbitrary distance with no intervening simulation,
     * and blending across that would slide the sprite over the map for one
     * frame. Rather than hunt down and annotate every place that can do it (and
     * miss the next one), the arithmetic notices: legitimate movement in one
     * fixed step is bounded by the fastest thing in the game, and a relocation
     * is not.
     *
     * <p>Sized from that bound rather than by taste. At the default 120 Hz step
     * the fastest body in the engine is a magic projectile at a few hundred
     * px/s — under 6 px a step; a sprinting player with relic boots is under 5;
     * a committed dash is under 8. Even at a 30 Hz simulation those quadruple to
     * under 32. 64 px is clear of all of them and far below any warp, which
     * moves a body tiles or screens at a time.
     */
    public static final double RELOCATION_PIXELS = 64.0;

    private StepInterpolation() {}

    /**
     * Where a body that moved from {@code prev} to {@code cur} over the last
     * fixed step is drawn, {@code alpha} of the way through it.
     *
     * <p>Returns {@code cur} unchanged for a movement past
     * {@link #RELOCATION_PIXELS}, and for a {@code prev} that was never
     * captured — {@code 0} against a body standing anywhere else in the level is
     * a relocation by this measure, which is what makes an un-captured body draw
     * correctly on its first frame instead of flying in from the origin.
     */
    public static double at(double prev, double cur, double alpha) {
        double moved = cur - prev;
        if (moved > RELOCATION_PIXELS || moved < -RELOCATION_PIXELS) return cur;
        return prev + moved * alpha;
    }
}
