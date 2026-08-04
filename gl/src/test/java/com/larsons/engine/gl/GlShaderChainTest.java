package com.larsons.engine.gl;

import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.render.FrameError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The shader chain, run on the GPU, subtracted from the same chain run on the
 * CPU.
 *
 * <p><b>This is the check A2 was written around, and the numbers were taken
 * before the backend existed on purpose.</b> {@code ShaderParityTest} measured
 * every pass against its CPU twin through {@code GlShaderHarness} — five passes
 * at 0.00, {@code scanlines} 0.04, {@code vignette} 0.32, {@code grayscale}
 * 0.47, {@code bloom} 3.58 — and those figures are in §2 of
 * {@code RENDER_PLAN.md} as a property of the <em>shaders</em>. Reproducing
 * them here says the backend is right. A different number does not mean a
 * shader is wrong; it means {@link GlShaderChain} is, and that is exactly why
 * they were measured first.
 *
 * <p><b>Why not just point the harness at the production chain.</b> The harness
 * uploads a texture, renders one pass and reads it back. The production chain
 * does something the harness never has to: it hands one pass's output to the
 * next through a ping-pong, at a size that can change between frames, with
 * uniforms bound from a contract rather than by hand. Every test below that is
 * not a parity number is about one of those three.
 */
class GlShaderChainTest {

    /** The bar {@code ShaderParityTest} sets for "the same picture". */
    private static final double CLOSE_ENOUGH = 3.0;

    private static final int W = 64, H = 64;

    private static GlContext context;
    private GlShaderChain chain;

    /** Errors gathered across the run, written out as a table at the end. */
    private static final Map<String, Double> measured = new LinkedHashMap<>();

    @BeforeAll
    static void openContext() {
        context = GlContext.offscreen(0);
    }

    @BeforeEach
    void requireContext() {
        assumeTrue(context != null && context.available(),
                () -> "no GL 3.3 core context: "
                        + (context == null ? "not created" : context.whyUnavailable()));
        context.makeCurrent();
        chain = new GlShaderChain();
    }

    @AfterEach
    void closeChain() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
    }

    @AfterAll
    static void closeContext() {
        writeReport();
        if (context != null) context.close();
    }

    // --- A2: the passes, and the order they run in ------------------------------

    /**
     * Every built-in pass, both ways, on the same frame.
     *
     * <p>Reported as a table whether it passes or fails: the numbers are what
     * this step delivers, and a failing run is when they are most wanted.
     */
    @Test
    void everyPassMatchesItsCpuTwinThroughTheProductionChain() {
        int[] src = testFrame();
        List<String> wrong = new ArrayList<>();
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            double error = compare(List.of(pass), src, W, H, 1.0f);
            measured.put(pass.name(), error);
            // Bloom's GLSL is a documented approximation of its multi-stage CPU
            // form, held to its own bar in ShaderParityTest and to the same one
            // here rather than to a number invented for this test.
            double bar = pass.name().equals("bloom") ? 64.0 : CLOSE_ENOUGH;
            if (error > bar) {
                wrong.add("%s: %.2f (bar %.2f)".formatted(pass.name(), error, bar));
            }
        }
        assertEquals(List.of(), wrong,
                "these passes do not run on the GPU the way they run on the CPU:\n  "
                        + String.join("\n  ", wrong)
                        + "\n\nThe same passes matched through GlShaderHarness when §2 was "
                        + "measured, so a difference here is the backend, not the shader.");
    }

    /**
     * The ping-pong feeds each pass the previous pass's output, in the order
     * {@code ShaderChain} states.
     *
     * <p><b>Order is not decoration.</b> Vignette then invert darkens the
     * corners and then flips them to white; invert then vignette flips first
     * and darkens the result, so the corners come out black. A backend that ran
     * the list backwards, or that handed every pass the original scene instead
     * of its predecessor's output, would still produce a picture — and would
     * still pass a per-pass parity test, because each pass in isolation would
     * be right.
     *
     * <p><b>The first pair tried here was grayscale and invert, and it could
     * not have failed.</b> Rec. 709's weights sum to one, so
     * {@code luma(1 - c) == 1 - luma(c)} exactly: the two orders produce the
     * same frame to 0.00, and the control below said so on the first run. A
     * control that catches the test's own choice of fixture is worth more than
     * the assertion it guards.
     */
    @Test
    void passesRunInOrderAndEachReadsTheOneBefore() {
        int[] src = testFrame();
        List<ShaderPass> forward = List.of(Shaders.vignette(), Shaders.invert());
        List<ShaderPass> backward = List.of(Shaders.invert(), Shaders.vignette());

        double forwardError = compare(forward, src, W, H, 1.0f);
        double backwardError = compare(backward, src, W, H, 1.0f);
        measured.put("vignette→invert", forwardError);
        measured.put("invert→vignette", backwardError);

        assertTrue(forwardError <= CLOSE_ENOUGH,
                "a two-pass chain diverged from the CPU chain: " + forwardError);
        assertTrue(backwardError <= CLOSE_ENOUGH,
                "the reversed chain diverged from the CPU chain: " + backwardError);

        // And the control: if these two orders produced the same picture, the
        // test above would be satisfied by a backend that ignored order
        // entirely.
        double betweenOrders = FrameError.meanChannelError(
                shade(forward, src, W, H, 1.0f), shade(backward, src, W, H, 1.0f));
        assertTrue(betweenOrders > CLOSE_ENOUGH,
                "the two orders produced the same frame (%.2f), so this test could not "
                        .formatted(betweenOrders)
                        + "tell an ordered chain from an unordered one");
    }

    /**
     * A resize reallocates the ping-pong.
     *
     * <p>A2 named this failure specifically because of how it looks: stale
     * buffers do not crash and do not blank the screen, they produce a frame
     * shaded at the old size and stretched over the new one, which reads as
     * "the game got slightly blurry" and is miserable to attribute afterwards.
     */
    @Test
    void aResizedDrawableIsNotShadedThroughTheOldBuffers() {
        List<ShaderPass> passes = List.of(Shaders.vignette());
        compare(passes, testFrame(), W, H, 1.0f);          // establish one size

        int w2 = 96, h2 = 48;                              // and change both axes
        int[] wide = testFrame(w2, h2);
        double error = compare(passes, wide, w2, h2, 1.0f);
        measured.put("vignette after resize", error);
        assertTrue(error <= CLOSE_ENOUGH,
                "after a resize the chain shaded the frame through buffers of the "
                        + "previous size: mean error " + error);
    }

    /**
     * At 2× HiDPI the chain shades four times the pixels and produces the same
     * picture.
     *
     * <p><b>This is the one design decision in {@link GlShaderChain} that a
     * parity test at scale 1 cannot reach.</b> The passes are shaded at device
     * resolution but measure in logical pixels, because that is the space the
     * scene projected its coordinates into and the space the Java2D backend
     * composes in. Handing them the device size instead is a single plausible
     * line that produces four separate wrong pictures — scanlines at half
     * thickness, pixelate blocks at a quarter of their area, bloom at half its
     * radius, every light at half its position — none of which looks like a
     * units bug and all of which only appear on a HiDPI machine, which is the
     * machine this plan cares about.
     *
     * <p>Compared the way D0 compares: against the logical-size CPU frame
     * upscaled with nearest neighbour, which is what the window server does to
     * the Java2D backend's output and therefore what the player has to see the
     * same as.
     */
    @Test
    void theChainMeasuresInLogicalPixelsSoHiDpiLooksTheSame() {
        int scale = 2;
        int[] logical = testFrame();
        // The scene texture is device-sized, because that is what the backend
        // rendered into; its content is the same picture at 2x.
        int[] device = upscale(logical, W, H, scale);

        List<String> wrong = new ArrayList<>();
        // One pass per unit the shaders measure in: rows, blocks, and the
        // screen position of a light.
        List<ShaderPass> cases = List.of(Shaders.scanlines(), Shaders.pixelate(4), litScene(3));
        for (ShaderPass pass : cases) {
            int[] cpu = upscale(onCpu(List.of(pass), logical, W, H, 1.0f), W, H, scale);
            int[] gpu = runScaled(List.of(pass), device, W * scale, H * scale, W, H);
            double error = FrameError.meanChannelError(cpu, gpu);
            measured.put(pass.name() + " at 2× HiDPI", error);
            if (error > CLOSE_ENOUGH) {
                wrong.add("%s: %.2f".formatted(pass.name(), error));
            }
        }
        assertEquals(List.of(), wrong,
                "at 2× these passes stopped agreeing with the frame the player sees on "
                        + "Java2D, which is what shading in device units instead of logical "
                        + "units looks like:\n  " + String.join("\n  ", wrong));

        // The negative control, and it is the whole reason this test is worth
        // having: the same run with the device size passed as the measuring
        // unit — the mistake — has to be caught by this comparison. Without
        // this, three errors near zero could equally mean the metric cannot
        // see the difference at all.
        double asDeviceUnits = FrameError.meanChannelError(
                upscale(onCpu(List.of(Shaders.scanlines()), logical, W, H, 1.0f), W, H, scale),
                runScaled(List.of(Shaders.scanlines()), device,
                        W * scale, H * scale, W * scale, H * scale));
        assertTrue(asDeviceUnits > CLOSE_ENOUGH,
                "measuring in device pixels produced the same frame (%.2f) as measuring in "
                        .formatted(asDeviceUnits)
                        + "logical ones, so this test cannot tell the two apart");
    }

    // --- A3: the uniform contract ------------------------------------------------

    /**
     * {@code uStrength} reaches every shader, checked against the production
     * chain rather than the harness.
     *
     * <p>A3's stated verification. It is the cheapest possible evidence that
     * the contract is bound at all: a pass that never received the uniform
     * looks perfectly correct at full strength and wrong only here.
     */
    @Test
    void strengthZeroLeavesTheFrameAloneThroughTheProductionChain() {
        int[] src = testFrame();
        List<String> wrong = new ArrayList<>();
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            if (pass.name().equals("pixelate") || pass.name().equals("wave")) {
                // Geometric passes resample regardless: strength scales their
                // displacement, not their opacity. Same exclusion, same reason,
                // as ShaderParityTest.
                continue;
            }
            double error = FrameError.meanChannelError(
                    src, shade(List.of(pass), src, W, H, 0.0f));
            if (error > CLOSE_ENOUGH) {
                wrong.add("%s changed the frame at strength 0 (%.2f)"
                        .formatted(pass.name(), error));
            }
        }
        assertEquals(List.of(), wrong,
                "uStrength is not reaching some shaders through GlShaderChain:\n  "
                        + String.join("\n  ", wrong));
    }

    /**
     * A pass-specific scalar reaches its shader.
     *
     * <p>{@code uPixelSize} is the one built-in whose value is neither a
     * standard uniform nor derivable from the frame, so a backend that skipped
     * {@link ShaderPass#uniforms()} would quantise to blocks of zero — which
     * GLSL clamps to one, i.e. an identity pass that looks like a working
     * chain doing nothing.
     */
    @Test
    void aPassSpecificScalarReachesItsShader() {
        int[] src = testFrame();
        double error = FrameError.meanChannelError(
                src, shade(List.of(Shaders.pixelate(8)), src, W, H, 1.0f));
        assertTrue(error > CLOSE_ENOUGH,
                "pixelate at a block size of 8 left the frame alone (%.2f), which is "
                        .formatted(error) + "what an unbound uPixelSize looks like");
    }

    // --- A4: the lighting pass ---------------------------------------------------

    /**
     * The pass that actually runs during play, against its CPU twin on a fixed
     * light set.
     *
     * <p><b>The two are not the same algorithm and the bar says so.</b> The CPU
     * side computes the light field at quarter resolution and upsamples it
     * bilinearly — the original side-scroller's trick for keeping cost flat in
     * the light count — while the GPU evaluates the falloff per fragment,
     * because on a GPU that is the cheaper of the two. So the difference
     * measured here is the upsample, concentrated in a ring at each light's
     * edge where the field changes fastest, and it is a property of the two
     * implementations rather than of this backend. It is reported as a number
     * for the same reason bloom's 3.58 is: so that making them match, or making
     * them diverge further, is visible rather than folklore.
     *
     * <p><b>It is nevertheless held to the ordinary bar, because it measured
     * 1.27.</b> The upsample was expected to cost more than that and a looser
     * bar was written first; measuring it made the loose bar an unforced
     * concession, and a bar set above what the code achieves is a bar that
     * lets it get worse in silence.
     */
    @Test
    void theLightingPassMatchesItsCpuTwin() {
        int[] src = testFrame();
        double error = compare(List.of(litScene(3)), src, W, H, 1.0f);
        measured.put("lighting (3 lights)", error);
        assertTrue(error <= CLOSE_ENOUGH,
                "GPU lighting has stopped describing the same effect as the CPU "
                        + "lighting: mean channel error " + error);
    }

    /**
     * Lighting is the pass with the most to lose from an unbound uniform, so
     * the frame it produces is checked for being lit rather than only for
     * matching.
     *
     * <p>Before A4 there was no way to bind {@code uNightTint}, {@code uLightPos}
     * or {@code uLightColor} at all — a {@code Map<String, Float>} cannot carry
     * a {@code vec3} — so a GPU build ran this shader with a black tint and an
     * empty light array. That renders: a uniformly darkened frame with no
     * torches in it, and no error anywhere. This asserts the middle of the
     * frame, where the light is, is brighter than the corner, where it is not.
     */
    @Test
    void theFrameIsActuallyLitRatherThanMerelyDarkened() {
        int[] src = flat(0xFF808080);
        int[] lit = shade(List.of(litScene(1)), src, W, H, 1.0f);

        int centre = luma(lit[(H / 2) * W + W / 2]);
        int corner = luma(lit[0]);
        assertTrue(centre > corner + 40,
                "the light did not reach the frame: centre luma " + centre
                        + ", corner luma " + corner + ". An unbound uLightPos/uLightColor "
                        + "darkens everything evenly and looks exactly like night.");
        assertTrue(corner < luma(src[0]),
                "darkness never applied: corner luma " + corner);
    }

    /**
     * More lights than the driver can hold still renders, at the budget the
     * driver affords.
     *
     * <p>A4's stated verification, and it is run on a machine where the clamp
     * would otherwise never engage — the budget is forced down with
     * {@link GlShaderChain#MAX_LIGHTS_PROPERTY}, the same way B9 provokes its
     * fallback by asking for a GL version no driver has. Without that this
     * would be a test of an {@code if} nobody has ever taken.
     *
     * <p>Both halves of the clamp are checked, because they are separate
     * mistakes: the <em>shader</em> has to be compiled with arrays it can
     * declare, and the <em>loop bound</em> has to stop inside them. Getting the
     * first right and the second wrong reads past the end of a uniform array,
     * which is undefined behaviour that renders fine on the driver you tested.
     */
    @Test
    void moreLightsThanTheDriverAffordsStillRenders() {
        String previous = System.getProperty(GlShaderChain.MAX_LIGHTS_PROPERTY);
        System.setProperty(GlShaderChain.MAX_LIGHTS_PROPERTY, "4");
        try (GlShaderChain clamped = new GlShaderChain()) {
            assertEquals(4, clamped.lightBudget(), "the forced budget was not honoured");

            LightingPass lighting = litScene(12);
            assertEquals(12, lighting.lightCount(), "the scene should still hold 12 lights");

            int[] src = flat(0xFF808080);
            int[] out = run(clamped, List.of(lighting), src, W, H, 1.0f);

            assertEquals("""
                    uniform vec3  uLightPos[4];""".strip(),
                    declarationOf(clamped.compiledSource(lighting), "uLightPos"),
                    "the shader was compiled with arrays the driver cannot declare");

            // The first light of the twelve is at the centre and is inside the
            // budget, so the frame is lit there; the last is not, and is not.
            assertTrue(luma(out[(H / 2) * W + W / 2]) > luma(out[0]) + 40,
                    "nothing rendered under a clamped light budget");
            assertNotEquals(0, luma(out[(H / 2) * W + W / 2]),
                    "the frame came back black, which is what an over-long uniform "
                            + "upload looks like when the driver rejects it");
        } finally {
            if (previous == null) System.clearProperty(GlShaderChain.MAX_LIGHTS_PROPERTY);
            else System.setProperty(GlShaderChain.MAX_LIGHTS_PROPERTY, previous);
        }
    }

    // --- the instrument ----------------------------------------------------------

    /**
     * The chain reports its cost where the CPU chain reports its cost.
     *
     * <p><b>This is the number §5.0 of the plan was misled by once.</b> A GL
     * profile read {@code shaders 0.000} next to a context line saying "2
     * shader pass(es)", and that zero meant "not run" rather than "free". Now
     * that they do run, a zero would mean the opposite thing and be just as
     * wrong: GL calls return before the GPU has done the work, so the stage is
     * timed with {@code GL_TIME_ELAPSED} queries collected a frame later. Two
     * runs, therefore — the first issues the queries, the second collects them.
     */
    @Test
    void theChainReportsItsCostPerPassAndAsAStage() {
        FrameProfiler profiler = new FrameProfiler();
        profiler.setEnabled(true);
        List<ShaderPass> passes = List.of(Shaders.bloom(), Shaders.vignette());
        int[] src = testFrame();
        ShaderChain configured = new ShaderChain();
        configured.setPasses(passes);

        int source = upload(src, W, H);
        try {
            for (int frame = 0; frame < 4; frame++) {
                chain.run(source, W, H, W, H, configured, profiler);
                profiler.endFrame();
            }
        } finally {
            glDeleteTextures(source);
        }

        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        assertTrue(snapshot.stage(FrameProfiler.Stage.SHADERS).meanMs() > 0,
                "the GPU chain ran but charged the frame nothing for it, which is the "
                        + "reading that made a backend running no passes at all look fast");
        List<String> named = snapshot.passes().stream().map(FrameProfiler.Stats::name).toList();
        assertTrue(named.contains("bloom") && named.contains("vignette"),
                "the per-pass breakdown is what says which effect costs what; got " + named);
    }

    // --- running both sides ------------------------------------------------------

    /** Mean channel error between this chain on the GPU and the same one on the CPU. */
    private double compare(List<ShaderPass> passes, int[] src, int w, int h, float strength) {
        return FrameError.meanChannelError(
                onCpu(passes, src, w, h, strength), shade(passes, src, w, h, strength));
    }

    private int[] shade(List<ShaderPass> passes, int[] src, int w, int h, float strength) {
        return run(chain, passes, src, w, h, strength);
    }

    /** The same, with the device and logical sizes deliberately different. */
    private int[] runScaled(List<ShaderPass> passes, int[] src,
                            int deviceW, int deviceH, int logicalW, int logicalH) {
        ShaderChain configured = new ShaderChain();
        configured.setPasses(passes);
        configured.setStrength(1.0f);
        int source = upload(src, deviceW, deviceH);
        try {
            assertTrue(chain.run(source, deviceW, deviceH, logicalW, logicalH, configured, null),
                    "the chain reported that it ran nothing");
            return readBack(chain.resultFramebuffer(), deviceW, deviceH);
        } finally {
            glDeleteTextures(source);
        }
    }

    /** Nearest-neighbour magnification — what the window server does to Java2D. */
    private static int[] upscale(int[] src, int w, int h, int scale) {
        int[] out = new int[w * scale * h * scale];
        for (int y = 0; y < h * scale; y++) {
            int row = (y / scale) * w;
            for (int x = 0; x < w * scale; x++) {
                out[y * w * scale + x] = src[row + x / scale];
            }
        }
        return out;
    }

    /**
     * The GPU side: upload, run the production chain, read the result back.
     *
     * <p>No flip in either direction, and that is deliberate rather than lazy.
     * GL numbers rows from the bottom, so an unflipped upload followed by an
     * unflipped readback is consistently upside-down and cancels — which keeps
     * even the row-parity of {@code scanlines} comparable with the CPU. A flip
     * on one side only would make that pass fail for a reason that has nothing
     * to do with the pass.
     */
    private static int[] run(GlShaderChain chain, List<ShaderPass> passes,
                             int[] src, int w, int h, float strength) {
        ShaderChain configured = new ShaderChain();
        configured.setPasses(passes);
        configured.setStrength(strength);
        int source = upload(src, w, h);
        try {
            boolean ran = chain.run(source, w, h, w, h, configured, null);
            assertTrue(ran, "the chain reported that it ran nothing");
            return readBack(chain.resultFramebuffer(), w, h);
        } finally {
            glDeleteTextures(source);
        }
    }

    /** The CPU side, exactly as {@code Java2DRenderer} runs it. */
    private static int[] onCpu(List<ShaderPass> passes, int[] src, int w, int h, float strength) {
        int[] pixels = src.clone();
        ShaderChain cpu = new ShaderChain();
        cpu.setPasses(passes);
        cpu.setStrength(strength);
        cpu.apply(pixels, w, h);
        return pixels;
    }

    // --- fixtures ----------------------------------------------------------------

    /** {@code ShaderParityTest}'s frame: a colour sweep, hard edges, a bright patch. */
    private static int[] testFrame() {
        return testFrame(W, H);
    }

    private static int[] testFrame(int w, int h) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / (w - 1);
                int g = y * 255 / (h - 1);
                int b = ((x / 8) + (y / 8)) % 2 == 0 ? 40 : 200;
                if (x > w * 5 / 8 && x < w * 7 / 8 && y > h / 8 && y < h * 3 / 8) {
                    r = g = b = 250;
                }
                px[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    private static int[] flat(int argb) {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, argb);
        return px;
    }

    /**
     * A night scene with {@code lights} torches, the first at the centre of the
     * frame and the rest marching out toward the corner.
     */
    private static LightingPass litScene(int lights) {
        LightingPass pass = new LightingPass();
        pass.setDarkness(0.85);
        pass.setAmbient(0.10);
        pass.setNightTint(new Color(40, 40, 90));
        pass.clearLights();
        for (int i = 0; i < lights; i++) {
            double t = i / (double) Math.max(1, lights);
            pass.addLight(W / 2.0 + t * W / 2.0, H / 2.0 + t * H / 2.0,
                    18 + 2.0 * i, new Color(255, 200 - 8 * i, 120));
        }
        return pass;
    }

    private static int luma(int argb) {
        return (int) (0.2126f * ((argb >> 16) & 0xFF)
                + 0.7152f * ((argb >> 8) & 0xFF)
                + 0.0722f * (argb & 0xFF));
    }

    /** The line of GLSL declaring {@code name}, for asserting on a generated source. */
    private static String declarationOf(String source, String name) {
        if (source == null) return "<never compiled>";
        for (String line : source.split("\n")) {
            if (line.contains(name)) return line.strip().replaceAll("\\s*//.*$", "");
        }
        return "<not declared>";
    }

    // --- raw GL ------------------------------------------------------------------

    private static int upload(int[] pixels, int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        IntBuffer buffer = MemoryUtil.memAllocInt(pixels.length);
        try {
            buffer.put(pixels).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        return id;
    }

    private static int[] readBack(int framebuffer, int width, int height) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        IntBuffer buffer = MemoryUtil.memAllocInt(width * height);
        try {
            glReadPixels(0, 0, width, height,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            int[] out = new int[width * height];
            buffer.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    // --- the report ----------------------------------------------------------------

    private static void writeReport() {
        if (measured.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# GPU shader chain — `GlShaderChain` against `ShaderChain`\n\n");
        sb.append("Driver: `").append(context.describe()).append("`  \n");
        sb.append("Frame: ").append(W).append("×").append(H)
                .append(", the frame `ShaderParityTest` measured §2's numbers on.  \n");
        sb.append("Bar: mean channel error <= ").append(CLOSE_ENOUGH)
                .append(" / 255, except `bloom` (a documented approximation) and\n")
                .append("`lighting` (a quarter-resolution light field on the CPU, per-fragment "
                        + "on the GPU).\n\n");
        sb.append("| pass | mean channel error |\n|------|-------------------:|\n");
        measured.forEach((name, error) ->
                sb.append("| `").append(name).append("` | ")
                        .append("%.2f".formatted(error)).append(" |\n"));
        Path dir = Path.of(System.getProperty("larsons.reports.dir", "build/reports"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("gl-shader-chain.md"), sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.print(sb);
    }
}
