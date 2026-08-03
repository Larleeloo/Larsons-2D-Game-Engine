package com.larsons.engine.gl;

import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.render.FrameError;
import com.larsons.engine.render.GoldenFrames;
import com.larsons.engine.render.SceneFrames;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same picture, from two backends, subtracted.
 *
 * <p><b>This is the check B0 was built for.</b> The golden catalogue was frozen
 * before a single line of the migration was written, precisely so that the
 * question "does the GPU backend draw what the engine draws?" would have a
 * mechanical answer instead of an argument. Every frame here is the frame B2,
 * B3, B5 and B6 published their numbers against, rendered twice in one process
 * from one set of fixed inputs, and the difference reported per scene.
 *
 * <p><b>Zero is not the bar and pretending otherwise would be dishonest.</b>
 * Java2D antialiases in software with its own coverage rules; GL antialiases
 * with four coverage samples per pixel. Two correct rasterisers disagree along
 * every curved edge, and the disagreement is real pixels rather than rounding.
 * So the bar is stated as a number and taken from the precedent this project
 * already set: {@code bloom} was accepted as "the same picture" at 3.58 mean
 * channel error out of 255, on the same metric, in {@code ShaderParityTest}.
 * {@link #BAR} is that number.
 *
 * <p><b>And the metric is checked for having any teeth.</b> A mean over a
 * 480×320 frame forgives a lot: a whole widget could move and land under the
 * bar if the frame is mostly flat colour. {@link #theMetricNoticesAFrameThatIsWrong}
 * is the negative control — it perturbs one GL frame in a way no rasteriser
 * difference could produce and insists the number moves past the bar.
 */
class GlParityTest {

    /**
     * Mean channel error out of 255 that still counts as the same picture.
     *
     * <p>{@code ShaderParityTest} accepted `bloom` at 3.58 on this metric and
     * this document has treated that as the precedent ever since. Setting it
     * anywhere else would be picking a number to fit the result.
     */
    private static final double BAR = 3.58;

    /** Coverage samples, matching {@link GlRenderer#SAMPLES} — the shipping surface. */
    private static final int SAMPLES = GlRenderer.SAMPLES;

    private static GlContext context;
    private static GlSurface surface;
    private static GlTarget target;
    private static int clearArgb;

    /** One row of the report, and of the table in {@code RENDER_PLAN.md}. */
    private record Result(String frame, int width, int height, double error,
                          int operations, int predictedBatches, int glDrawCalls,
                          int java2dOperations) {

        double mergeRatio() {
            return glDrawCalls == 0 ? 0 : operations / (double) glDrawCalls;
        }
    }

    private static final List<Result> results = new ArrayList<>();

    @BeforeAll
    static void openContext() {
        context = GlContext.offscreen(SAMPLES);
        if (!context.available()) return;
        surface = new GlSurface(SAMPLES);
        target = new GlTarget(1, 1);
        // The colour a frame starts on, discovered rather than copied. It is
        // Java2DRenderer's clear and GoldenFrames renders every reference over
        // it; a constant here would be a second copy of it, and the day the two
        // disagreed this test would report a whole-frame error and blame the
        // backend.
        BufferedImage probe = GoldenFrames.render(
                new GoldenFrames.Frame("probe", 1, 1, t -> {}));
        clearArgb = probe.getRGB(0, 0) | 0xFF000000;
    }

    @BeforeEach
    void requireContext() {
        assumeTrue(context != null && context.available(),
                () -> "no GL 3.3 core context: "
                        + (context == null ? "not created" : context.whyUnavailable()));
        context.makeCurrent();
    }

    @AfterAll
    static void closeContext() {
        if (target != null) target.close();
        if (surface != null) surface.close();
        if (context != null) context.close();
    }

    // --- the comparison --------------------------------------------------------

    /**
     * Every frame in the catalogue, both backends, subtracted — and the table
     * written out whether it passes or fails, because the numbers are the
     * deliverable of this step and a failed run is exactly when they are wanted.
     */
    @Test
    void everyGoldenFrameRendersTheSamePictureOnGl() {
        List<String> over = new ArrayList<>();
        for (GoldenFrames.Frame frame : SceneFrames.allFrames()) {
            Result result = compare(frame);
            results.add(result);
            if (result.error() > BAR) {
                over.add("%s: %.2f".formatted(result.frame(), result.error()));
                // A mean is the wrong thing to debug from — it says how much,
                // never where. The three pictures say where in one look, and
                // writing them only for the frames that failed keeps a passing
                // run from dropping 35 PNGs into the build directory.
                dumpDifference(frame);
            }
        }
        writeReport();
        assertEquals(List.of(), over,
                "frames where the GL backend does not draw the same picture "
                        + "(bar is %.2f / 255):\n  ".formatted(BAR)
                        + String.join("\n  ", over)
                        + "\n\nfull table: build/reports/gl-parity.md");
    }

    /**
     * The negative control. A mean error over a whole frame is a forgiving
     * metric, and a check nobody has watched fail is not a check.
     */
    @Test
    void theMetricNoticesAFrameThatIsWrong() {
        GoldenFrames.Frame frame = named("main-menu");
        double honest = compare(frame).error();
        assertTrue(honest <= BAR, "the control needs a frame that passes first");

        // Every colour shifted by eight steps: far less than a moved widget,
        // and nothing any two rasterisers would disagree about.
        int[] reference = FrameError.pixels(java2d(frame).image());
        int[] shifted = new int[reference.length];
        for (int i = 0; i < reference.length; i++) {
            shifted[i] = shift(reference[i]);
        }
        double wrong = FrameError.meanChannelError(reference, shifted);
        assertTrue(wrong > BAR,
                "a frame shifted by 8 per channel measured %.2f, which is under the "
                        + "bar of %.2f — the metric would not notice a real regression"
                        .formatted(wrong, BAR));
    }

    /**
     * Both backends see the same command stream, so both must report the same
     * {@link DrawStats}.
     *
     * <p><b>Why this matters more than it looks.</b> B10 compares the two
     * backends through this counter. If GL's target recorded operations
     * differently — one per glyph instead of one per run, say, or a shape as
     * two — every ratio in that comparison would be measuring the instrument
     * rather than the renderer, and it would be measuring it in the flattering
     * direction. The stream is identical by construction; the counts have to be
     * identical too.
     */
    @Test
    void bothBackendsCountTheSameOperations() {
        List<String> mismatched = new ArrayList<>();
        for (GoldenFrames.Frame frame : SceneFrames.allFrames()) {
            Java2DResult reference = java2d(frame);
            GlResult gl = render(frame);
            if (reference.operations() != gl.stats().operations()
                    || reference.batches() != gl.stats().batches()) {
                mismatched.add("%s: Java2D %d ops / %d batches, GL %d ops / %d batches"
                        .formatted(frame.name(), reference.operations(), reference.batches(),
                                gl.stats().operations(), gl.stats().batches()));
            }
        }
        assertEquals(List.of(), mismatched,
                "the two backends disagree about what they were asked to draw:\n  "
                        + String.join("\n  ", mismatched));
    }

    /**
     * The claim the whole GPU case rests on, measured on the backend rather
     * than predicted by a counter: a frame's operations collapse into
     * materially fewer real draw calls.
     *
     * <p>Java2D issues one rasterisation per operation, so its own draw-call
     * count <em>is</em> its operation count. The ratio below is therefore the
     * honest before-and-after, not an estimate of one.
     */
    @Test
    void theBackendIssuesFarFewerDrawCallsThanOperations() {
        int operations = 0, drawCalls = 0;
        List<String> perFrame = new ArrayList<>();
        for (GoldenFrames.Frame frame : SceneFrames.allFrames()) {
            GlResult gl = render(frame);
            operations += gl.stats().operations();
            drawCalls += gl.drawCalls();
            perFrame.add("%s %d→%d".formatted(frame.name(), gl.stats().operations(),
                    gl.drawCalls()));
        }
        double ratio = operations / (double) drawCalls;
        assertTrue(ratio > 5.0,
                "the whole catalogue collapsed %d operations into %d draw calls (%.2fx), "
                        .formatted(operations, drawCalls, ratio)
                        + "which is not the order of merging B5 and B6 were done for:\n  "
                        + String.join("\n  ", perFrame));
    }

    /**
     * Uploading a texture must not repaint what is already in the batch.
     *
     * <p><b>The bug this pins down drew a plausible picture.</b> An upload
     * binds, and the batch usually has triangles waiting; bound on the unit the
     * shader samples, the upload replaced the texture those triangles were
     * about to be flushed against and they came out wearing the new image.
     * `parallax-background` rendered its third layer with the fourth layer's
     * silhouette — a perfectly reasonable-looking backdrop, wrong by one bind,
     * and invisible on every frame whose textures were already resident. A
     * whole-frame mean over the catalogue caught it at 4.64; this catches it at
     * exactly the operation that causes it.
     */
    @Test
    void uploadingATextureDoesNotRepaintWhatIsAlreadyInTheBatch() {
        BufferedImage red = solid(0xFFFF0000);
        BufferedImage green = solid(0xFF00FF00);

        surface.resize(8, 4);
        surface.bind();
        target.beginFrame(8, 4);
        target.clear(0xFF000000);
        // Two images, never seen before, drawn one after the other. The first
        // is still pending when the second is uploaded.
        target.drawImage(red, 0, 0, 4, 4);
        target.drawImage(green, 4, 0, 4, 4);
        target.endFrame();
        int[] pixels = surface.readPixels();
        GlSurface.unbind();

        assertEquals(0xFF0000, pixels[1] & 0xFFFFFF,
                "the first image was flushed against the second's texture");
        assertEquals(0x00FF00, pixels[5] & 0xFFFFFF,
                "the second image did not draw");
    }

    private static BufferedImage solid(int argb) {
        // Larger than SpriteAtlas.MAX_SPRITE would defeat the point; smaller is
        // fine because these are never registered, so they stay loose images
        // with a texture each — which is the case being tested.
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) image.setRGB(x, y, argb);
        }
        return image;
    }

    // --- rendering both ways ---------------------------------------------------

    private record Java2DResult(BufferedImage image, int operations, int batches) {}

    private record GlResult(int[] pixels, DrawStats stats, int drawCalls) {}

    private Result compare(GoldenFrames.Frame frame) {
        Java2DResult reference = java2d(frame);
        GlResult gl = render(frame);
        double error = FrameError.meanChannelError(
                FrameError.pixels(reference.image()), gl.pixels());
        return new Result(frame.name(), frame.width(), frame.height(), error,
                gl.stats().operations(), gl.stats().batches(), gl.drawCalls(),
                reference.operations());
    }

    /**
     * The reference, rendered the way {@code GoldenFrames.render} renders it —
     * reimplemented here for one reason: that method returns the image and
     * throws away the target, and this test needs the target's
     * {@link DrawStats} too. The two hints and the clear are the same, and
     * {@link #theTwoRenderersAgreeWithTheGoldenCatalogue} holds them together.
     */
    private static Java2DResult java2d(GoldenFrames.Frame frame) {
        BufferedImage image = new BufferedImage(
                frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        Java2DTarget target;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new java.awt.Color(clearArgb, true));
            g.fillRect(0, 0, frame.width(), frame.height());
            target = new Java2DTarget(g, frame.width(), frame.height());
            frame.painter().paint(target);
        } finally {
            g.dispose();
        }
        return new Java2DResult(image, target.stats().operations(), target.stats().batches());
    }

    /**
     * A local reference must not drift from the catalogue's own renderer.
     * Without this the comparison could pass by both sides being wrong in the
     * same way, which is the failure mode of every hand-rolled copy.
     */
    @Test
    void theTwoRenderersAgreeWithTheGoldenCatalogue() {
        for (GoldenFrames.Frame frame : SceneFrames.allFrames()) {
            double drift = FrameError.meanChannelError(
                    GoldenFrames.render(frame), java2d(frame).image());
            assertEquals(0.0, drift, 0.0,
                    frame.name() + ": this test's Java2D reference has drifted from "
                            + "GoldenFrames.render — " + FrameError.worstPixel(
                            GoldenFrames.render(frame), java2d(frame).image()));
        }
    }

    private static GlResult render(GoldenFrames.Frame frame) {
        surface.resize(frame.width(), frame.height());
        surface.bind();
        target.beginFrame(frame.width(), frame.height());
        target.clear(clearArgb);
        // The reference fills its background straight onto the image rather
        // than through the target, so that fill is not in its tally. Counting
        // it here would put GL one operation ahead on every frame in the
        // catalogue and make the comparison an artefact of the harness.
        target.stats().reset();
        frame.painter().paint(target);
        target.endFrame();
        int[] pixels = surface.readPixels();
        GlSurface.unbind();
        return new GlResult(pixels, target.stats().copy(), target.drawCalls());
    }

    private static GoldenFrames.Frame named(String name) {
        return SceneFrames.allFrames().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no frame named " + name));
    }

    private static int shift(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 8);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 8);
        int b = Math.min(255, (argb & 0xFF) + 8);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Reference, GL, and an amplified difference, for a frame that failed. */
    private static void dumpDifference(GoldenFrames.Frame frame) {
        BufferedImage reference = java2d(frame).image();
        int[] gl = render(frame).pixels();
        int w = frame.width(), h = frame.height();
        BufferedImage glImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] ref = FrameError.pixels(reference);
        for (int i = 0; i < gl.length; i++) {
            glImage.setRGB(i % w, i / w, gl[i]);
            int dr = Math.min(255, Math.abs(((ref[i] >> 16) & 0xFF) - ((gl[i] >> 16) & 0xFF)) * 8);
            int dg = Math.min(255, Math.abs(((ref[i] >> 8) & 0xFF) - ((gl[i] >> 8) & 0xFF)) * 8);
            int db = Math.min(255, Math.abs((ref[i] & 0xFF) - (gl[i] & 0xFF)) * 8);
            diff.setRGB(i % w, i / w, (dr << 16) | (dg << 8) | db);
        }
        Path dir = reportDir().resolve("gl-parity");
        try {
            Files.createDirectories(dir);
            javax.imageio.ImageIO.write(reference, "png",
                    dir.resolve(frame.name() + "-java2d.png").toFile());
            javax.imageio.ImageIO.write(glImage, "png",
                    dir.resolve(frame.name() + "-gl.png").toFile());
            javax.imageio.ImageIO.write(diff, "png",
                    dir.resolve(frame.name() + "-diff.png").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path reportDir() {
        return Path.of(System.getProperty("larsons.reports.dir", "build/reports"));
    }

    // --- the report ------------------------------------------------------------

    private static void writeReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("# GL parity — `GlTarget` against `Java2DTarget`\n\n");
        sb.append("Driver: `").append(context.describe()).append("`  \n");
        sb.append("Coverage samples granted: ").append(surface.samples())
                .append(" (asked for ").append(SAMPLES).append(")  \n");
        sb.append("Textures: ").append(target.textureSummary()).append("  \n");
        sb.append("Bar: mean channel error <= ").append(BAR)
                .append(" / 255, the number `bloom` was accepted at in `ShaderParityTest`.\n\n");
        sb.append("`draws` is what the GL backend really issued. Java2D issues one per\n")
                .append("operation, so the `ops` column is also its draw-call count.\n\n");
        sb.append("| frame | size | error | ops | predicted batches | GL draws | GL merge |\n");
        sb.append("|-------|------|------:|----:|------------------:|---------:|---------:|\n");

        int ops = 0, predicted = 0, draws = 0;
        double worst = 0;
        for (Result r : results) {
            sb.append("| `").append(r.frame()).append("` | ")
                    .append(r.width()).append("×").append(r.height()).append(" | ")
                    .append("%.2f".formatted(r.error())).append(" | ")
                    .append(r.operations()).append(" | ")
                    .append(r.predictedBatches()).append(" | ")
                    .append(r.glDrawCalls()).append(" | ")
                    .append("%.2f×".formatted(r.mergeRatio())).append(" |\n");
            ops += r.operations();
            predicted += r.predictedBatches();
            draws += r.glDrawCalls();
            worst = Math.max(worst, r.error());
        }
        sb.append("| **all %d frames** | | **%.2f max** | **%d** | **%d** | **%d** | **%.2f×** |\n"
                .formatted(results.size(), worst, ops, predicted, draws,
                        draws == 0 ? 0 : ops / (double) draws));

        Path dir = reportDir();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("gl-parity.md"), sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
