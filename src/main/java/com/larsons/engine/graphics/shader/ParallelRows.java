package com.larsons.engine.graphics.shader;

import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Splits a framebuffer into horizontal stripes and shades them on all cores —
 * the CPU pipeline's stand-in for the GPU's massive fragment parallelism.
 * A 1280×720 frame is under a megapixel, so simple per-pixel passes stay well
 * within a 120 FPS budget when striped across the common pool.
 */
public final class ParallelRows {

    /** A unit of work covering rows {@code [y0, y1)}. */
    @FunctionalInterface
    public interface RowRange {
        void shade(int y0, int y1);
    }

    private ParallelRows() {}

    public static void run(int height, RowRange task) {
        int stripes = Math.min(height, ForkJoinPool.getCommonPoolParallelism() * 2);
        if (stripes <= 1) {
            task.shade(0, height);
            return;
        }
        IntStream.range(0, stripes).parallel().forEach(i -> {
            int y0 = (int) ((long) height * i / stripes);
            int y1 = (int) ((long) height * (i + 1) / stripes);
            task.shade(y0, y1);
        });
    }
}
