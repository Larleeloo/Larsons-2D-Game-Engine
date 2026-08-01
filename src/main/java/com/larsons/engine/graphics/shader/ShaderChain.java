package com.larsons.engine.graphics.shader;

import com.larsons.engine.profile.FrameProfiler;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of {@link ShaderPass}es applied to a frame after the scene
 * has drawn — the engine's post-processing pipeline.
 *
 * <p>Passes ping-pong between the frame and a cached scratch buffer, so each
 * pass reads a stable source and writes a clean destination (exactly like FBO
 * ping-ponging on a GPU). The pass list is replaced atomically via
 * {@link #setPasses}, so a settings menu can reconfigure effects while the
 * render thread is mid-frame.
 */
public final class ShaderChain {

    private volatile List<ShaderPass> passes = List.of();
    private volatile float strength = 1.0f;
    private final long startNanos = System.nanoTime();
    private int[] scratch = new int[0];

    /**
     * Optional per-pass timing. Attached by the engine so a profile can say
     * which effect costs what — the difference between "post-processing is
     * expensive" and "bloom is expensive and the other six passes are free".
     */
    private FrameProfiler profiler;

    /** Attach (or clear, with {@code null}) the profiler timing each pass. */
    public void setProfiler(FrameProfiler profiler) {
        this.profiler = profiler;
    }

    /** Replace the active passes (atomic; safe to call from any thread). */
    public void setPasses(List<ShaderPass> newPasses) {
        this.passes = List.copyOf(newPasses);
    }

    /** Current passes, in application order. */
    public List<ShaderPass> passes() {
        return passes;
    }

    /** Append a pass to the end of the chain. */
    public void add(ShaderPass pass) {
        List<ShaderPass> next = new ArrayList<>(passes);
        next.add(pass);
        setPasses(next);
    }

    public void clear() {
        passes = List.of();
    }

    public boolean hasPasses() {
        return !passes.isEmpty();
    }

    /** Global effect strength in [0,1], exposed to every pass as {@code uStrength}. */
    public void setStrength(float s) {
        this.strength = Math.max(0f, Math.min(1f, s));
    }

    public float getStrength() {
        return strength;
    }

    /** Seconds since this chain was created ({@code uTime}). */
    public float time() {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0f;
    }

    /**
     * Run every pass over the frame, in place. Called by the renderer once per
     * frame (single-threaded); each pass fans out internally across cores.
     */
    public void apply(int[] pixels, int width, int height) {
        List<ShaderPass> active = passes;
        if (active.isEmpty()) return;

        int n = width * height;
        if (scratch.length < n) scratch = new int[n];

        ShaderContext ctx = new ShaderContext(width, height, time(), strength);
        FrameProfiler prof = profiler;
        int[] src = pixels;
        int[] dst = scratch;
        for (ShaderPass pass : active) {
            long started = prof == null ? 0L : prof.begin();
            try {
                pass.apply(src, dst, width, height, ctx);
            } finally {
                if (prof != null) prof.recordPass(pass.name(), started);
            }
            int[] tmp = src;
            src = dst;
            dst = tmp;
        }
        if (src != pixels) System.arraycopy(src, 0, pixels, 0, n);
    }
}
