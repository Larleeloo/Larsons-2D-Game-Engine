package com.larsons.engine.profile;

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

    /** Discard the current window and start measuring afresh. */
    public void reset() {
        for (long[] row : samples) Arrays.fill(row, 0L);
        Arrays.fill(pending, 0L);
        passSamples.clear();
        passPending.clear();
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
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed > 0) pending[stage.ordinal()] += elapsed;
    }

    /**
     * Add the elapsed time since {@code startNanos} to one shader pass. Passes
     * are identified by {@link com.larsons.engine.graphics.shader.ShaderPass#name()},
     * so the breakdown follows the chain even as a settings menu reconfigures
     * it — a pass that stops running simply stops accumulating.
     */
    public void recordPass(String passName, long startNanos) {
        if (!enabled || startNanos == 0L || passName == null) return;
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed <= 0) return;
        passPending.merge(passName, elapsed, Long::sum);
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
        for (Map.Entry<String, Long> e : passPending.entrySet()) {
            passSamples.computeIfAbsent(e.getKey(), k -> new long[WINDOW])[cursor] = e.getValue();
        }
        // A pass that ran previously but not this frame must record a zero, or
        // its old samples would drift forward and overstate a disabled effect.
        for (Map.Entry<String, long[]> e : passSamples.entrySet()) {
            if (!passPending.containsKey(e.getKey())) e.getValue()[cursor] = 0L;
        }
        passPending.clear();

        cursor = (cursor + 1) % WINDOW;
        if (recorded < WINDOW) recorded++;
        totalFrames++;

        long now = System.nanoTime();
        if (now - lastPublish >= PUBLISH_INTERVAL_NANOS) {
            lastPublish = now;
            latest = buildSnapshot();
        }
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

        double budgetMs = 1000.0 / targetFps;
        return new Snapshot(recorded, totalFrames, targetFps, budgetMs,
                List.copyOf(stageStats), List.copyOf(passStats));
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
    public record Snapshot(int windowFrames, long totalFrames, int targetFps,
                           double budgetMs, List<Stats> stages, List<Stats> passes) {

        public static final Snapshot EMPTY =
                new Snapshot(0, 0, 120, 1000.0 / 120, List.of(), List.of());

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
