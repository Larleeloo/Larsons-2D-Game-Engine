package com.larsons.engine;

import com.larsons.engine.graphics.shader.ShaderContext;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Do the GPU and CPU forms of each effect actually agree?
 *
 * <p>{@code STEAM_PLAN.md}'s Appendix A raises this as a separate risk from
 * compilation, and it is the more serious one. The two implementations of every
 * pass were written by hand from the same description; nothing had ever run
 * both; and {@code BloomPass} states in its own javadoc that its GLSL is "a
 * single-shader ring-sampled approximation" rather than a translation. A
 * mismatch anywhere else would be just as real and nobody would know, because
 * a difference in <em>look</em> is invisible until someone builds the backend
 * and notices the game seems off.
 *
 * <p>So each pass runs both ways over the same frame and the results are
 * compared channel by channel.
 *
 * <p><b>What counts as agreement.</b> Not equality. The CPU passes work in
 * 8-bit integers with lookup tables; the GPU works in floats and rounds once at
 * the end, so a channel landing a step either side of a boundary is expected
 * and meaningless. The bar is mean absolute error across every channel of every
 * pixel, which catches a wrong formula, a swapped channel or a missing uniform
 * — the failures that matter — while ignoring the last bit.
 */
class ShaderParityTest {

    private static final int W = 64, H = 64;

    /** Mean absolute channel error below which two images are the same picture. */
    private static final double CLOSE_ENOUGH = 3.0;

    private static GlShaderHarness gl;

    @BeforeAll
    static void openContext() {
        gl = new GlShaderHarness();
    }

    @AfterAll
    static void closeContext() {
        if (gl != null) gl.close();
    }

    /**
     * A frame with something for every effect to bite on: a colour sweep, hard
     * edges for the distortions, and a bright patch above the bloom threshold.
     */
    private static int[] testFrame() {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int r = x * 255 / (W - 1);
                int g = y * 255 / (H - 1);
                int b = ((x / 8) + (y / 8)) % 2 == 0 ? 40 : 200;   // checker
                if (x > 40 && x < 56 && y > 8 && y < 24) {
                    r = g = b = 250;                               // bright patch
                }
                px[y * W + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    /** Mean absolute difference per colour channel between two frames. */
    private static double meanChannelError(int[] a, int[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) {
            total += Math.abs(((a[i] >> 16) & 0xFF) - ((b[i] >> 16) & 0xFF));
            total += Math.abs(((a[i] >> 8) & 0xFF) - ((b[i] >> 8) & 0xFF));
            total += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        }
        return total / (double) (a.length * 3);
    }

    /** Run one pass on the CPU, exactly as the renderer would. */
    private static int[] onCpu(ShaderPass pass, int[] src, float strength, float time) {
        int[] dst = new int[src.length];
        pass.apply(src, dst, W, H, new ShaderContext(W, H, time, strength));
        return dst;
    }

    @Test
    void everyPassLooksTheSameOnTheGpuAsOnTheCpu() {
        assumeTrue(gl.isReady(), "no GL context (" + gl.whyUnavailable() + ")");

        int[] src = testFrame();
        Map<String, Double> errors = new LinkedHashMap<>();
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            int[] cpu = onCpu(pass, src, 1.0f, 0.0f);
            int[] gpu = gl.run(pass, src, W, H, 1.0f, 0.0f);
            errors.put(pass.name(), meanChannelError(cpu, gpu));
        }

        StringBuilder report = new StringBuilder("mean channel error, CPU vs GPU:\n");
        errors.forEach((name, error) ->
                report.append("  %-22s %6.2f%n".formatted(name, error)));

        // Bloom is a known and documented approximation, so it is reported but
        // not held to the same bar — see its own test below.
        String failing = errors.entrySet().stream()
                .filter(e -> !e.getKey().equals("bloom"))
                .filter(e -> e.getValue() > CLOSE_ENOUGH)
                .map(e -> e.getKey() + " (" + Math.round(e.getValue()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        assertTrue(failing.isEmpty(),
                "these passes do not match their CPU twin: " + failing + "\n" + report);
        System.out.print(report);
    }

    @Test
    void bloomIsTheOnePassThatKnowinglyDiffers() {
        // BloomPass's javadoc says its GLSL is a ring-sampled approximation of
        // the multi-stage CPU blur. This pins that claim down with a number, so
        // that if someone later makes them match, or makes them diverge
        // further, the change is visible rather than folklore.
        assumeTrue(gl.isReady(), "no GL context (" + gl.whyUnavailable() + ")");

        ShaderPass bloom = Shaders.bloom();
        int[] src = testFrame();
        double error = meanChannelError(onCpu(bloom, src, 1.0f, 0.0f),
                gl.run(bloom, src, W, H, 1.0f, 0.0f));

        System.out.printf("bloom CPU vs GPU mean channel error: %.2f%n", error);
        assertTrue(error < 64.0,
                "bloom is an approximation, but " + error + " is not an approximation — "
                        + "the two implementations have stopped describing the same effect");
    }

    @Test
    void strengthZeroLeavesTheFrameAloneOnBothSides() {
        // Every pass takes uStrength, and at zero the frame should come back
        // untouched. It is the cheapest possible check that the uniform is
        // reaching the shader at all: a pass that ignores it would fail here
        // while looking perfectly correct at full strength.
        assumeTrue(gl.isReady(), "no GL context (" + gl.whyUnavailable() + ")");

        int[] src = testFrame();
        StringBuilder wrong = new StringBuilder();
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            if (pass.name().equals("pixelate") || pass.name().equals("wave")) {
                // Geometric passes resample regardless of strength; strength
                // scales their displacement, not their opacity.
                continue;
            }
            double error = meanChannelError(src, gl.run(pass, src, W, H, 0.0f, 0.0f));
            if (error > CLOSE_ENOUGH) {
                wrong.append("  ").append(pass.name())
                        .append(" changed the frame at strength 0 (error ")
                        .append(Math.round(error)).append(")\n");
            }
        }
        assertTrue(wrong.isEmpty(), "uStrength is not reaching some shaders:\n" + wrong);
    }
}
