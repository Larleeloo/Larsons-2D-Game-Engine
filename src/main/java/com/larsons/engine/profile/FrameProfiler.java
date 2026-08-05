package com.larsons.engine.profile;

import com.larsons.engine.graphics.draw.DrawStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a frame's time actually goes, measured rather than guessed.
 *
 * <p><b>Why this exists.</b> The engine's next large decision is whether to
 * move rendering onto the GPU, and there are two separable jobs behind that
 * question: shading the finished frame on the GPU (the
 * {@code com.larsons.engine.graphics.shader} chain) and drawing the scene
 * itself on the GPU (everything that currently issues {@code Graphics2D}
 * calls). They cost very different amounts to build, and which one is worth
 * doing depends entirely on which one dominates a real frame. A frame counter
 * alone cannot answer that — 45 FPS tells you the frame is slow, not which
 * part of it is — so this splits the frame into the stages that map onto the
 * work being considered:
 *
 * <ul>
 *   <li>{@link Stage#UPDATE} — fixed-step simulation. Not a rendering cost at
 *       all; a GPU backend would not move it. Large numbers here mean the
 *       graphics work is not the problem.</li>
 *   <li>{@link Stage#SCENE} — the scene's own drawing, i.e. every
 *       {@code Graphics2D} call the current scene issues. <b>This is the
 *       budget a GPU scene renderer competes for.</b></li>
 *   <li>{@link Stage#SHADERS} — the post-processing chain, broken down
 *       per pass by {@link #recordPass}. <b>This is the budget a GPU shader
 *       backend competes for</b>, and the per-pass split says whether one
 *       effect (bloom, typically) is carrying the whole cost.</li>
 *   <li>{@link Stage#PRESENT} — acquiring the buffer, blitting the finished
 *       frame and flipping it. Largely fixed platform cost, and the floor any
 *       backend has to beat.</li>
 *   <li>{@link Stage#OVERLAY} — the profiler's own HUD. Recorded so it can be
 *       subtracted rather than silently inflating the frame it is measuring;
 *       see {@link Snapshot#workMsExcludingOverlay()}.</li>
 *   <li>{@link Stage#IDLE} — time the frame limiter spent waiting. This is
 *       the <em>headroom</em>, and on a weak machine it is the number that
 *       matters most: a frame with idle time to spare is not one that needs a
 *       new renderer.</li>
 * </ul>
 *
 * <p><b>Cost when disabled.</b> {@link #begin()} returns {@code 0} after a
 * single volatile read and every {@code record} call returns immediately, so
 * an un-profiled build pays one predictable branch per stage per frame.
 *
 * <p><b>Threading.</b> Every mutation and every snapshot is built on the game
 * loop thread, which is the only thread that updates, renders and presents.
 * The sole value that crosses threads is the immutable {@link Snapshot}
 * published to {@link #latest()}, so a HUD, a settings screen or a test can
 * read results without locking and without perturbing the measurement.
 */
public final class FrameProfiler {

    /** Frames retained for percentile statistics (five seconds at 120 FPS). */
    public static final int WINDOW = 600;

    /** How often a fresh {@link Snapshot} is published for outside readers. */
    private static final long PUBLISH_INTERVAL_NANOS = 500_000_000L;

    /** The stages a frame is split into, in the order they run. */
    public enum Stage {
        UPDATE("update"),
        SCENE("scene"),
        SHADERS("shaders"),
        PRESENT("present"),
        OVERLAY("overlay"),
        IDLE("idle");

        private final String label;

        Stage(String label) { this.label = label; }

        public String label() { return label; }
    }

    private static final Stage[] STAGES = Stage.values();

    private volatile boolean enabled;
    private volatile int targetFps = 120;
    private volatile Snapshot latest = Snapshot.EMPTY;

    /** Ring buffer of per-frame totals, in nanoseconds, one row per stage. */
    private final long[][] samples = new long[STAGES.length][WINDOW];

    /**
     * The frame being accumulated. A stage can be recorded more than once in a
     * frame — {@link Stage#UPDATE} runs once per catch-up step — so records sum
     * into here and only roll into the ring at {@link #endFrame()}.
     */
    private final long[] pending = new long[STAGES.length];

    /** Per-shader-pass ring buffers, keyed by pass name in chain order. */
    private final Map<String, long[]> passSamples = new LinkedHashMap<>();
    private final Map<String, Long> passPending = new LinkedHashMap<>();

    /**
     * Named phases <em>inside</em> {@link Stage#SCENE}, in the order a scene
     * draws them.
     *
     * <p>Knowing the scene costs 19 ms is the same kind of half-answer a frame
     * counter gives: it says the drawing is slow, not which drawing. A profile
     * from an M1 Air showed scene at 83% of the frame with no way to tell
     * terrain from sprites from HUD — and those have completely different
     * fixes. So a scene names its phases and they are timed like the shader
     * passes are.
     */
    private final Map<String, long[]> sectionSamples = new LinkedHashMap<>();
    private final Map<String, Long> sectionPending = new LinkedHashMap<>();

    // Per-frame draw-call counts, in the same ring slot as that frame's timings.
    private final int[] drawOps = new int[WINDOW];
    private final int[] drawBatches = new int[WINDOW];
    private final int[] drawImages = new int[WINDOW];
    private final int[] drawGlyphs = new int[WINDOW];

    /**
     * Fixed-step simulation steps run in each frame — SIM_PLAN S1's second
     * measurement. See {@link #recordUpdateSteps}.
     */
    private final int[] updateSteps = new int[WINDOW];
    private int updateStepsPending;

    private int cursor;      // next ring slot to write
    private int recorded;    // frames written, saturating at WINDOW
    private long totalFrames;
    private long lastPublish;

    /** Whether stages are being timed at all. */
    public boolean isEnabled() { return enabled; }

    /**
     * Turn measurement on or off. Enabling clears any previous window so a
     * measurement never blends two different scenes together.
     */
    public void setEnabled(boolean on) {
        if (on == enabled) return;
        if (on) reset();
        enabled = on;
    }

    /** The frame cap the headroom figures are measured against. */
    public void setTargetFps(int fps) {
        this.targetFps = Math.max(1, fps);
    }

    public int targetFps() { return targetFps; }

    /**
     * Whether the renderer's present already waits for the display, which
     * changes what {@code idle} means and therefore how a report must be read.
     *
     * <p><b>Recorded because otherwise the next profile looks like a
     * regression.</b> When the game loop paces frames, the wait shows up as
     * {@code idle} and headroom is the share of the budget nobody used. When the
     * display paces them, the same wait moves into {@code present} and
     * {@code idle} falls to nearly nothing — the same machine doing the same
     * work, reported in a different place. Two profiles that disagree about
     * which is which cannot be compared, and a reader with no way to tell would
     * reasonably conclude the headroom had vanished.
     */
    public void setExternallyPaced(boolean paced) { this.externallyPaced = paced; }

    /** See {@link #setExternallyPaced}. */
    public boolean externallyPaced() { return externallyPaced; }

    private volatile boolean externallyPaced;

    /** Discard the current window and start measuring afresh. */
    public void reset() {
        for (long[] row : samples) Arrays.fill(row, 0L);
        Arrays.fill(pending, 0L);
        Arrays.fill(drawOps, 0);
        Arrays.fill(drawBatches, 0);
        Arrays.fill(drawImages, 0);
        Arrays.fill(drawGlyphs, 0);
        Arrays.fill(updateSteps, 0);
        updateStepsPending = 0;
        passSamples.clear();
        passPending.clear();
        sectionSamples.clear();
        sectionPending.clear();
        cursor = 0;
        recorded = 0;
        totalFrames = 0;
        lastPublish = System.nanoTime();
        latest = Snapshot.EMPTY;
    }

    /**
     * Timestamp to hand back to {@link #record}, or {@code 0} when profiling is
     * off. Callers pair it with a {@code try/finally} so a stage is still
     * closed out if the work throws.
     */
    public long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    /** Add the elapsed time since {@code startNanos} to {@code stage}. */
    public void record(Stage stage, long startNanos) {
        if (!enabled || startNanos == 0L) return;
        recordElapsed(stage, System.nanoTime() - startNanos);
    }

    /**
     * Add a duration measured by something other than this thread's clock.
     *
     * <p><b>For work whose cost is not the caller's wall time</b> — a GPU
     * timer query, principally. A GL draw call returns as soon as the command
     * is queued, so timing one with {@link #begin()} and {@link #record} would
     * charge the frame a few microseconds of submission for milliseconds of
     * shading, and the report would say a stage was nearly free at exactly the
     * moment it became the expensive one. The backend that knows how to ask
     * the GPU measures it and hands the answer here.
     */
    public void recordElapsed(Stage stage, long nanos) {
        if (!enabled || nanos <= 0) return;
        pending[stage.ordinal()] += nanos;
    }

    /**
     * Record what the frame asked the renderer to draw.
     *
     * <p>This is the other half of the GPU question. The stage timings say
     * scene drawing is what costs; this says whether that cost is a few
     * expensive operations or a great many cheap ones, and how many draw calls
     * a batching backend would collapse them into. A frame of ten thousand
     * operations merging into thirty batches is one a GPU renderer transforms;
     * ten thousand merging into eight thousand is one whose <em>art</em> needs
     * atlasing first, and no backend work would rescue it.
     */
    public void recordDraws(DrawStats frameStats) {
        if (!enabled || frameStats == null) return;
        drawOps[cursor] = frameStats.operations();
        drawBatches[cursor] = frameStats.batches();
        drawImages[cursor] = frameStats.images();
        drawGlyphs[cursor] = frameStats.glyphs();
    }

    /**
     * Add the elapsed time since {@code startNanos} to one shader pass. Passes
     * are identified by {@link com.larsons.engine.graphics.shader.ShaderPass#name()},
     * so the breakdown follows the chain even as a settings menu reconfigures
     * it — a pass that stops running simply stops accumulating.
     */
    public void recordPass(String passName, long startNanos) {
        if (!enabled || startNanos == 0L || passName == null) return;
        recordPassElapsed(passName, System.nanoTime() - startNanos);
    }

    /**
     * The same for one shader pass, measured elsewhere — see
     * {@link #recordElapsed(Stage, long)}. This is how a GPU backend reports a
     * per-pass breakdown that can be set beside the CPU chain's, which is the
     * comparison the whole post-processing job is judged on.
     */
    public void recordPassElapsed(String passName, long nanos) {
        if (!enabled || passName == null || nanos <= 0) return;
        passPending.merge(passName, nanos, Long::sum);
    }

    /**
     * Add the elapsed time since {@code startNanos} to one named phase of the
     * scene's drawing — terrain, entities, HUD.
     *
     * <p>Sections are nested inside {@link Stage#SCENE} rather than beside it,
     * so they do not have to add up to it: a scene can name the phases worth
     * naming and leave the rest as the remainder.
     */
    public void recordSection(String sectionName, long startNanos) {
        recordSection(Stage.SCENE, sectionName, startNanos);
    }

    /**
     * The same, for a phase of any stage — SIM_PLAN's S1.
     *
     * <p><b>One mechanism, two owners, and the asymmetry it removes was the
     * reason a whole plan could not start.</b> {@code scene} has had a six-way
     * breakdown since B0 and {@code update} was one number, so a profile could
     * say the simulation stalled and could not say what in it stalled. That is
     * why {@code RENDER_PLAN}'s Job B could be planned from measurements and
     * {@code SIM_PLAN} could not: the instrument answered one of the two
     * questions.
     *
     * <p>Sections are stored under their owning stage rather than in a flat
     * namespace, so a {@code terrain} phase of the scene and a {@code terrain}
     * phase of the simulation could coexist without either quietly absorbing
     * the other's samples — which is exactly the kind of thing that produces a
     * confident wrong answer.
     */
    public void recordSection(Stage owner, String sectionName, long startNanos) {
        if (!enabled || startNanos == 0L || sectionName == null || owner == null) return;
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed <= 0) return;
        sectionPending.merge(owner.label() + SECTION_SEPARATOR + sectionName, elapsed, Long::sum);
    }

    /** Separates a section's owning stage from its own name in the stored key. */
    public static final String SECTION_SEPARATOR = "/";

    /**
     * How many fixed-step simulation steps this frame ran.
     *
     * <p><b>The measurement that resolves SIM_PLAN's second ambiguity, and it
     * is the one nobody could reason their way past.</b> The loop simulates at
     * 120 Hz against a 60 Hz render cap, so an ordinary frame runs two steps
     * and a frame that has fallen behind runs up to eight. {@link Stage#UPDATE}
     * sums them. So a 21 ms update is either <b>one</b> step that took 21 ms or
     * <b>eight</b> ordinary ones — a single expensive operation, or a feedback
     * failure where one slow frame makes the next frame pay for it several
     * times over. Those have different causes and different fixes, and no
     * amount of staring at a single number distinguishes them.
     */
    public void recordUpdateSteps(int steps) {
        if (!enabled || steps < 0) return;
        updateStepsPending += steps;
    }

    /**
     * Close out the frame: roll every pending stage into the window and, twice
     * a second, publish a fresh {@link Snapshot} for outside readers.
     */
    public void endFrame() {
        if (!enabled) return;

        for (int i = 0; i < STAGES.length; i++) {
            samples[i][cursor] = pending[i];
            pending[i] = 0L;
        }
        rollNamed(passSamples, passPending);
        rollNamed(sectionSamples, sectionPending);
        updateSteps[cursor] = updateStepsPending;
        updateStepsPending = 0;

        cursor = (cursor + 1) % WINDOW;
        // The next slot is cleared as it is reached, so a frame that records
        // no draws reads as zero rather than inheriting the lap before it.
        drawOps[cursor] = 0;
        drawBatches[cursor] = 0;
        drawImages[cursor] = 0;
        drawGlyphs[cursor] = 0;
        updateSteps[cursor] = 0;
        if (recorded < WINDOW) recorded++;
        totalFrames++;

        long now = System.nanoTime();
        if (now - lastPublish >= PUBLISH_INTERVAL_NANOS) {
            lastPublish = now;
            latest = buildSnapshot();
        }
    }

    /**
     * Roll one frame of named timings into their rings. A name that ran
     * previously but not this frame records a zero, or its old samples would
     * drift forward and overstate work that has stopped happening — an effect
     * switched off in a menu, or a scene phase that no longer runs.
     */
    private void rollNamed(Map<String, long[]> rings, Map<String, Long> pending) {
        for (Map.Entry<String, Long> e : pending.entrySet()) {
            rings.computeIfAbsent(e.getKey(), k -> new long[WINDOW])[cursor] = e.getValue();
        }
        for (Map.Entry<String, long[]> e : rings.entrySet()) {
            if (!pending.containsKey(e.getKey())) e.getValue()[cursor] = 0L;
        }
        pending.clear();
    }

    /**
     * The most recently published statistics. Safe to read from any thread;
     * {@link Snapshot#EMPTY} until the first half-second of frames is in.
     */
    public Snapshot latest() { return latest; }

    /**
     * Statistics for the frames measured so far, built on demand. Intended for
     * an end-of-run report; the HUD reads {@link #latest()} instead so it never
     * pays for a sort.
     */
    public Snapshot snapshot() {
        return buildSnapshot();
    }

    private Snapshot buildSnapshot() {
        if (recorded == 0) return Snapshot.EMPTY;

        List<Stats> stageStats = new ArrayList<>(STAGES.length);
        for (Stage stage : STAGES) {
            stageStats.add(statsOf(stage.label(), samples[stage.ordinal()]));
        }
        List<Stats> passStats = new ArrayList<>(passSamples.size());
        for (Map.Entry<String, long[]> e : passSamples.entrySet()) {
            passStats.add(statsOf(e.getKey(), e.getValue()));
        }
        List<Stats> sectionStats = new ArrayList<>(sectionSamples.size());
        for (Map.Entry<String, long[]> e : sectionSamples.entrySet()) {
            sectionStats.add(statsOf(e.getKey(), e.getValue()));
        }

        double budgetMs = 1000.0 / targetFps;
        return new Snapshot(recorded, totalFrames, targetFps, budgetMs,
                List.copyOf(stageStats), List.copyOf(passStats),
                List.copyOf(sectionStats),
                new Draws(mean(drawOps), mean(drawBatches),
                        mean(drawImages), mean(drawGlyphs)),
                externallyPaced, stepsStats());
    }

    /** The window's step-count distribution. See {@link Steps}. */
    private Steps stepsStats() {
        if (recorded == 0) return Steps.NONE;
        int[] sorted = Arrays.copyOf(updateSteps, recorded);
        Arrays.sort(sorted);
        long total = 0;
        for (int v : sorted) total += v;
        return new Steps(total / (double) recorded,
                sorted[Math.min(recorded - 1, recorded / 2)],
                sorted[Math.min(recorded - 1, (int) (recorded * 0.95))],
                sorted[recorded - 1]);
    }

    /**
     * Summarise one ring buffer. Percentiles come from a sorted copy of the
     * live window: means hide the stutter a player actually notices, and a
     * p99 well above the mean is the signature of a hitch (a GC pause, a chunk
     * rebuild) rather than of a frame that is uniformly too slow.
     */
    private Stats statsOf(String name, long[] ring) {
        long[] window = Arrays.copyOf(ring, recorded);
        Arrays.sort(window);

        long sum = 0;
        for (long v : window) sum += v;

        double mean = window.length == 0 ? 0 : (double) sum / window.length;
        return new Stats(name, window.length,
                nanosToMs(mean),
                nanosToMs(percentile(window, 0.50)),
                nanosToMs(percentile(window, 0.95)),
                nanosToMs(percentile(window, 0.99)),
                nanosToMs(window.length == 0 ? 0 : window[window.length - 1]));
    }

    /** Nearest-rank percentile over an already-sorted window. */
    private static double percentile(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double nanosToMs(double nanos) {
        return nanos / 1_000_000.0;
    }

    /** Mean of the recorded window of an int ring. */
    private double mean(int[] ring) {
        if (recorded == 0) return 0;
        long sum = 0;
        for (int i = 0; i < recorded; i++) sum += ring[i];
        return (double) sum / recorded;
    }

    /**
     * What an average frame asked the renderer to draw.
     *
     * <p>{@link #mergeRatio()} is the figure the GPU decision turns on: how
     * many operations a batching backend would fold into each draw call. One
     * means no two consecutive operations are compatible and batching buys
     * nothing; double digits means the draw order is already GPU-shaped.
     */
    public record Draws(double operations, double batches, double images, double glyphs) {

        public static final Draws NONE = new Draws(0, 0, 0, 0);

        public boolean isEmpty() { return operations <= 0; }

        /** Operations per draw call a batching backend would issue. */
        public double mergeRatio() {
            return batches <= 0 ? 0 : operations / batches;
        }
    }

    /** Mean/percentile summary of one stage or one shader pass, in milliseconds. */
    public record Stats(String name, int samples, double meanMs, double p50Ms,
                        double p95Ms, double p99Ms, double maxMs) {

        static final Stats ZERO = new Stats("", 0, 0, 0, 0, 0, 0);
    }

    /**
     * An immutable read of the profiler, safe to hand to another thread.
     *
     * <p>The interesting derived figures are {@link #workMs()} — everything
     * except the limiter's wait, i.e. what the machine actually had to do — and
     * {@link #headroomPct()}, how much of the frame budget was left over.
     */
    /**
     * How many fixed-step simulation steps a frame ran, over the window.
     *
     * <p><b>SIM_PLAN S1's second measurement, and the one that decides between
     * two completely different bugs.</b> {@link Stage#UPDATE} sums every
     * catch-up step in a frame, so a 21 ms update is either one step that took
     * 21 ms or eight ordinary ones. The first is an expensive operation; the
     * second is a feedback failure, where one slow frame leaves the accumulator
     * behind and the next frame pays for it several times over. {@link #msPerStep}
     * is the number that tells them apart at a glance.
     *
     * @param mean   steps per frame, averaged over the window
     * @param p50    the median frame's step count
     * @param p95    the 95th percentile — the catch-up tail
     * @param max    the worst frame in the window; equal to the loop's own cap
     *               when the loop has been in catch-up
     */
    public record Steps(double mean, int p50, int p95, int max) {
        public static final Steps NONE = new Steps(0, 0, 0, 0);

        public boolean isEmpty() { return max == 0; }

        /**
         * The cost of one simulation step, given the frame's total update time.
         *
         * <p>A per-frame update cost conflates "how expensive is a step" with
         * "how many did this frame run". This separates them, and it is the
         * first question S2 has to answer.
         */
        public double msPerStep(double updateMeanMs) {
            return mean <= 0 ? 0 : updateMeanMs / mean;
        }
    }

    public record Snapshot(int windowFrames, long totalFrames, int targetFps,
                           double budgetMs, List<Stats> stages, List<Stats> passes,
                           List<Stats> sections, Draws draws, boolean externallyPaced,
                           Steps steps) {

        public static final Snapshot EMPTY = new Snapshot(0, 0, 120, 1000.0 / 120,
                List.of(), List.of(), List.of(), Draws.NONE, false, Steps.NONE);

        /**
         * The sections belonging to one stage, with the owning prefix stripped.
         *
         * <p>Sections are stored under their stage so that a phase named
         * {@code terrain} in the scene and one named {@code terrain} in the
         * simulation cannot silently share a bucket. Readers ask per stage.
         */
        public List<Stats> sections(Stage owner) {
            String prefix = owner.label() + SECTION_SEPARATOR;
            List<Stats> out = new ArrayList<>();
            for (Stats s : sections) {
                if (s.name().startsWith(prefix)) {
                    out.add(new Stats(s.name().substring(prefix.length()), s.samples(),
                            s.meanMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), s.maxMs()));
                }
            }
            return out;
        }

        public boolean isEmpty() { return windowFrames == 0; }

        /** The named stage, or a zeroed entry if nothing was measured. */
        public Stats stage(Stage stage) {
            for (Stats s : stages) {
                if (s.name().equals(stage.label())) return s;
            }
            return Stats.ZERO;
        }

        /** Mean milliseconds of real work per frame — every stage but the wait. */
        public double workMs() {
            double total = 0;
            for (Stage s : STAGES) {
                if (s != Stage.IDLE) total += stage(s).meanMs();
            }
            return total;
        }

        /**
         * Mean work per frame with the profiler's own HUD taken back out — the
         * honest figure to quote, since the overlay is not part of the game.
         */
        public double workMsExcludingOverlay() {
            return workMs() - stage(Stage.OVERLAY).meanMs();
        }

        /**
         * Percentage of the frame budget still unused, measured on the work the
         * game itself does. Negative means the machine cannot hold the cap.
         */
        public double headroomPct() {
            if (budgetMs <= 0) return 0;
            return 100.0 * (1.0 - workMsExcludingOverlay() / budgetMs);
        }

        /** The highest sustainable frame rate implied by the mean frame's work. */
        public double achievableFps() {
            double work = workMsExcludingOverlay();
            return work <= 0 ? targetFps : 1000.0 / work;
        }

        /** The stage with the largest mean cost, ignoring the limiter's wait. */
        public Stats dominantStage() {
            Stats worst = Stats.ZERO;
            for (Stage s : STAGES) {
                if (s == Stage.IDLE || s == Stage.OVERLAY) continue;
                Stats candidate = stage(s);
                if (candidate.meanMs() > worst.meanMs()) worst = candidate;
            }
            return worst;
        }
    }
}
