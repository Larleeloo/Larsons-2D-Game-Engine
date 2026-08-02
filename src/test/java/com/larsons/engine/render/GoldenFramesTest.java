package com.larsons.engine.render;

import com.larsons.engine.render.GoldenFrames.Frame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden comparison: every {@link GoldenFrames} scene, rendered now and
 * subtracted from the PNG that was stored when it was known good.
 *
 * <p><b>The bar is 0.00.</b> Not "close enough" — these frames are produced by
 * the same rasteriser from the same inputs, so any difference at all is a
 * change in what the engine draws. The tolerant bar in {@link FrameError}
 * exists for comparing two <em>different</em> rasterisers (CPU vs GPU shader
 * passes today, Java2D vs GL in B8); it does not apply to a refactor claiming
 * to have changed nothing.
 *
 * <p><b>Regenerating.</b> {@code ./gradlew test -Dlarsons.golden.rewrite=true}
 * rewrites every reference and fails the run afterwards, so a rewrite can
 * never be mistaken for a pass. Do it when a change to what is drawn is
 * intended, look at the resulting diff in git, and never as the first response
 * to a red test.
 */
class GoldenFramesTest {

    private static final String REWRITE_FLAG = "larsons.golden.rewrite";

    /** Beside the PNGs: which font stack drew them. See {@link GoldenFrames#fontFingerprint}. */
    private static final String FINGERPRINT_FILE = "font-fingerprint.txt";

    private static Path goldenDir() {
        return Path.of(GoldenFrames.RESOURCE_DIR);
    }

    private static boolean rewriting() {
        return Boolean.getBoolean(REWRITE_FLAG);
    }

    /**
     * One test per frame, so a failure names the picture that changed rather
     * than stopping at the first one. Twelve red rows say "the port broke
     * everything"; one red row says "the port broke {@code config-form}", and
     * those need different responses.
     */
    @TestFactory
    List<DynamicTest> everyGoldenFrameIsUnchanged() throws IOException {
        if (rewriting()) return List.of(DynamicTest.dynamicTest("rewrite", this::rewriteAll));

        Path dir = goldenDir();
        String expectedFingerprint = Files.exists(dir.resolve(FINGERPRINT_FILE))
                ? Files.readString(dir.resolve(FINGERPRINT_FILE)).trim() : "";
        String actualFingerprint = GoldenFrames.fontFingerprint();

        List<DynamicTest> tests = new ArrayList<>();
        for (Frame frame : GoldenFrames.all()) {
            tests.add(DynamicTest.dynamicTest(frame.name(), () -> {
                Path png = dir.resolve(frame.name() + ".png");
                assertTrue(Files.exists(png), () ->
                        "no reference for '" + frame.name() + "' — generate it with "
                                + "./gradlew test -D" + REWRITE_FLAG + "=true");

                // Fonts are the one input this harness cannot pin down; see
                // GoldenFrames' class note. Skip rather than fail, so a
                // developer on a different font stack is told the truth
                // instead of handed a red suite they cannot fix.
                Assumptions.assumeTrue(expectedFingerprint.equals(actualFingerprint),
                        () -> "this JVM rasterises fonts differently from the machine that "
                                + "generated the goldens (fingerprint " + actualFingerprint
                                + " vs " + expectedFingerprint + ") — regenerate with -D"
                                + REWRITE_FLAG + "=true to golden this platform");

                BufferedImage expected = ImageIO.read(png.toFile());
                BufferedImage actual = GoldenFrames.render(frame);

                double error = FrameError.meanChannelError(expected, actual);
                assertEquals(0.0, error, 0.0, () ->
                        "'" + frame.name() + "' no longer draws the same picture "
                                + "(mean channel error " + error + " of 255). "
                                + FrameError.worstPixel(expected, actual)
                                + ". If this change is intended, regenerate with -D"
                                + REWRITE_FLAG + "=true and review the image diff.");
            }));
        }
        return tests;
    }

    /**
     * The negative control. A golden suite nobody has ever seen fail is a
     * golden suite that might be comparing an image with itself — this renders
     * a frame, perturbs one channel of one pixel, and insists the metric
     * notices.
     */
    @Test
    void theComparisonCanActuallyFail() {
        Frame frame = GoldenFrames.all().get(0);
        BufferedImage rendered = GoldenFrames.render(frame);

        BufferedImage tweaked = GoldenFrames.render(frame);
        int rgb = tweaked.getRGB(3, 3);
        tweaked.setRGB(3, 3, rgb ^ 0x00010000);

        assertEquals(0.0, FrameError.meanChannelError(rendered, GoldenFrames.render(frame)), 0.0,
                "rendering the same frame twice must give the same picture");
        assertTrue(FrameError.meanChannelError(rendered, tweaked) > 0.0,
                "a one-channel difference went unnoticed — the comparison is not comparing");
    }

    /**
     * Determinism, checked directly rather than inferred. Everything in the
     * catalogue is supposed to be seeded and clocked; this is what says so.
     */
    @Test
    void everyFrameRendersIdenticallyTwice() {
        StringBuilder unstable = new StringBuilder();
        for (Frame frame : GoldenFrames.all()) {
            double error = FrameError.meanChannelError(
                    GoldenFrames.render(frame), GoldenFrames.render(frame));
            if (error != 0.0) {
                unstable.append("\n  ").append(frame.name())
                        .append(" differs from itself by ").append(error);
            }
        }
        assertEquals("", unstable.toString(),
                "these frames are not deterministic and cannot be goldens:" + unstable);
    }

    /** Rewrite every reference, loudly, and fail so it cannot pass as a green run. */
    private void rewriteAll() throws IOException {
        Path dir = goldenDir();
        Files.createDirectories(dir);

        System.out.println();
        System.out.println("  ############################################################");
        System.out.println("  ##  REWRITING GOLDEN FRAMES — references are being replaced");
        System.out.println("  ##  Review the image diff before committing.");
        System.out.println("  ############################################################");
        for (Frame frame : GoldenFrames.all()) {
            Path png = dir.resolve(frame.name() + ".png");
            ImageIO.write(GoldenFrames.render(frame), "png", png.toFile());
            System.out.printf("  ##  wrote %s (%dx%d)%n",
                    png, frame.width(), frame.height());
        }
        Files.writeString(dir.resolve(FINGERPRINT_FILE),
                GoldenFrames.fontFingerprint() + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("  ##  font fingerprint: " + GoldenFrames.fontFingerprint());
        System.out.println("  ############################################################");
        System.out.println();

        throw new UncheckedIOException(new IOException(
                "golden frames were rewritten, not verified — re-run without -"
                        + "D" + REWRITE_FLAG + "=true to check them"));
    }
}
