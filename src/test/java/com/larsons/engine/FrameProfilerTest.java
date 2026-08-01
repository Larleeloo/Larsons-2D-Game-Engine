package com.larsons.engine;

import com.larsons.engine.graphics.Java2DRenderer;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.profile.FrameProfiler.Snapshot;
import com.larsons.engine.profile.FrameProfiler.Stage;
import com.larsons.engine.profile.FrameProfiler.Stats;
import com.larsons.engine.profile.FrameReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Headless tests for the frame profiler — the instrumentation that decides
 * whether GPU work is worth doing, so its arithmetic had better be right.
 *
 * <p><b>How durations are faked.</b> {@code record} measures from a caller-supplied
 * start stamp, so a test synthesises a known cost by handing it a stamp that
 * many nanoseconds in the past. The measured value is therefore <em>at least</em>
 * the injected one and a little more; assertions allow for scheduler noise
 * rather than demanding exact equality, and lean on ordering (which stage is
 * biggest) where an absolute figure would be flaky under CI load.
 */
class FrameProfilerTest {

    /** A stamp {@code ms} milliseconds in the past, to synthesise a cost. */
    private static long agoMs(double ms) {
        return System.nanoTime() - (long) (ms * 1_000_000.0);
    }

    /** Generous upper bound: a synthesised cost plus room for a descheduled thread. */
    private static void assertAbout(double expectedMs, double actualMs, String what) {
        assertTrue(actualMs >= expectedMs * 0.95,
                what + " should be at least " + expectedMs + " ms, was " + actualMs);
        assertTrue(actualMs < expectedMs + 250,
                what + " should be near " + expectedMs + " ms, was " + actualMs);
    }

    private static FrameProfiler enabled() {
        FrameProfiler profiler = new FrameProfiler();
        profiler.setEnabled(true);
        profiler.setTargetFps(120);
        return profiler;
    }

    @Test
    void disabledProfilerRecordsNothing() {
        FrameProfiler profiler = new FrameProfiler();

        assertEquals(0L, profiler.begin(), "begin() should be free when disabled");
        profiler.record(Stage.SCENE, agoMs(5));
        profiler.recordPass("bloom", agoMs(5));
        profiler.endFrame();

        assertTrue(profiler.snapshot().isEmpty(), "no frames should be recorded");
    }

    @Test
    void recordedStagesSurviveAFrame() {
        FrameProfiler profiler = enabled();

        profiler.record(Stage.UPDATE, agoMs(1));
        profiler.record(Stage.SCENE, agoMs(4));
        profiler.record(Stage.PRESENT, agoMs(2));
        profiler.endFrame();

        Snapshot snapshot = profiler.snapshot();
        assertFalse(snapshot.isEmpty());
        assertEquals(1, snapshot.windowFrames());
        assertEquals(1, snapshot.totalFrames());

        assertAbout(1, snapshot.stage(Stage.UPDATE).meanMs(), "update");
        assertAbout(4, snapshot.stage(Stage.SCENE).meanMs(), "scene");
        assertAbout(2, snapshot.stage(Stage.PRESENT).meanMs(), "present");
        assertEquals(0.0, snapshot.stage(Stage.SHADERS).meanMs(), 1e-9,
                "an unrecorded stage should stay at zero");
    }

    @Test
    void repeatedRecordsInOneFrameSumTogether() {
        // The loop runs several catch-up update steps per frame; a frame paid
        // for all of them, so the frame's update cost is their sum.
        FrameProfiler profiler = enabled();

        profiler.record(Stage.UPDATE, agoMs(2));
        profiler.record(Stage.UPDATE, agoMs(2));
        profiler.record(Stage.UPDATE, agoMs(2));
        profiler.endFrame();

        assertAbout(6, profiler.snapshot().stage(Stage.UPDATE).meanMs(), "summed update");
    }

    @Test
    void pendingCostsDoNotLeakIntoTheNextFrame() {
        FrameProfiler profiler = enabled();

        profiler.record(Stage.SCENE, agoMs(8));
        profiler.endFrame();
        profiler.endFrame();     // a second frame that drew nothing

        Snapshot snapshot = profiler.snapshot();
        assertEquals(2, snapshot.windowFrames());
        // Eight milliseconds over two frames averages four.
        assertTrue(snapshot.stage(Stage.SCENE).meanMs() < 8.0,
                "the second frame should have diluted the mean, not repeated the cost");
        assertEquals(0.0, snapshot.stage(Stage.SCENE).p50Ms(), 1e-9,
                "the median of one busy frame and one idle one is the idle one");
    }

    @Test
    void windowIsBoundedAndKeepsTheMostRecentFrames() {
        FrameProfiler profiler = enabled();

        for (int i = 0; i < FrameProfiler.WINDOW + 50; i++) {
            profiler.record(Stage.SCENE, agoMs(1));
            profiler.endFrame();
        }

        Snapshot snapshot = profiler.snapshot();
        assertEquals(FrameProfiler.WINDOW, snapshot.windowFrames(),
                "the ring should saturate rather than grow");
        assertEquals(FrameProfiler.WINDOW + 50, snapshot.totalFrames(),
                "the running total should keep counting past the window");
        assertAbout(1, snapshot.stage(Stage.SCENE).meanMs(), "scene");
    }

    @Test
    void percentilesSeparateASpikeFromTheMedian() {
        FrameProfiler profiler = enabled();

        // Ninety-nine calm frames and one hitch: the median should stay calm
        // while the maximum shows the stall. This is the shape that tells a
        // stutter apart from a uniformly slow frame.
        for (int i = 0; i < 99; i++) {
            profiler.record(Stage.SCENE, agoMs(1));
            profiler.endFrame();
        }
        profiler.record(Stage.SCENE, agoMs(40));
        profiler.endFrame();

        Stats scene = profiler.snapshot().stage(Stage.SCENE);
        assertAbout(1, scene.p50Ms(), "median");
        assertAbout(40, scene.maxMs(), "worst frame");
        assertTrue(scene.maxMs() > scene.p50Ms() * 10, "the spike should stand out");
    }

    @Test
    void shaderPassesAreBrokenOutIndividually() {
        FrameProfiler profiler = enabled();

        profiler.recordPass("bloom", agoMs(6));
        profiler.recordPass("vignette", agoMs(1));
        profiler.endFrame();

        List<Stats> passes = profiler.snapshot().passes();
        assertEquals(2, passes.size());
        assertEquals("bloom", passes.get(0).name(), "passes keep chain order");
        assertAbout(6, passes.get(0).meanMs(), "bloom");
        assertAbout(1, passes.get(1).meanMs(), "vignette");
    }

    @Test
    void aPassThatStopsRunningStopsCosting() {
        // Turning an effect off in a settings menu must show up as its cost
        // falling away, not as a stale sample dragged forward every frame.
        FrameProfiler profiler = enabled();

        profiler.recordPass("bloom", agoMs(10));
        profiler.endFrame();
        for (int i = 0; i < 9; i++) profiler.endFrame();

        Stats bloom = profiler.snapshot().passes().get(0);
        assertEquals(0.0, bloom.p50Ms(), 1e-9, "the median frame no longer runs bloom");
        assertTrue(bloom.meanMs() < 2.0,
                "one costly frame in ten should average down, was " + bloom.meanMs());
    }

    @Test
    void headroomAndSustainableFpsFollowTheBudget() {
        FrameProfiler profiler = enabled();   // 120 FPS -> 8.33 ms budget

        profiler.record(Stage.SCENE, agoMs(4));
        profiler.endFrame();

        Snapshot snapshot = profiler.snapshot();
        assertEquals(1000.0 / 120, snapshot.budgetMs(), 1e-6);
        // Four milliseconds of an 8.33 ms budget leaves a little over half.
        assertTrue(snapshot.headroomPct() > 40 && snapshot.headroomPct() < 60,
                "headroom should be about half, was " + snapshot.headroomPct());
        assertTrue(snapshot.achievableFps() > 200 && snapshot.achievableFps() < 260,
                "4 ms of work sustains ~250 FPS, got " + snapshot.achievableFps());
    }

    @Test
    void overbudgetFrameReportsNegativeHeadroom() {
        FrameProfiler profiler = enabled();

        profiler.record(Stage.SCENE, agoMs(20));   // well past an 8.33 ms budget
        profiler.endFrame();

        Snapshot snapshot = profiler.snapshot();
        assertTrue(snapshot.headroomPct() < 0,
                "an overrun frame should report negative headroom");
        assertTrue(snapshot.achievableFps() < 120);
    }

    @Test
    void idleTimeIsHeadroomAndNotWork() {
        FrameProfiler profiler = enabled();

        profiler.record(Stage.SCENE, agoMs(2));
        profiler.record(Stage.IDLE, agoMs(6));
        profiler.endFrame();

        Snapshot snapshot = profiler.snapshot();
        assertAbout(6, snapshot.stage(Stage.IDLE).meanMs(), "idle");
        assertTrue(snapshot.workMs() < 5,
                "the limiter's wait must not count as work, got " + snapshot.workMs());
    }

    @Test
    void overlayCostIsExcludedFromTheReportedFigures() {
        // The readout must not inflate the frame it is measuring.
        FrameProfiler profiler = enabled();

        profiler.record(Stage.SCENE, agoMs(3));
        profiler.record(Stage.OVERLAY, agoMs(2));
        profiler.endFrame();

        Snapshot snapshot = profiler.snapshot();
        assertTrue(snapshot.workMs() - snapshot.workMsExcludingOverlay() >= 1.9,
                "the overlay should be subtractable");
        assertAbout(3, snapshot.workMsExcludingOverlay(), "work without the HUD");
    }

    @Test
    void dominantStageIgnoresIdleAndOverlay() {
        FrameProfiler profiler = enabled();

        profiler.record(Stage.SCENE, agoMs(3));
        profiler.record(Stage.SHADERS, agoMs(1));
        profiler.record(Stage.IDLE, agoMs(30));      // biggest, but not work
        profiler.record(Stage.OVERLAY, agoMs(20));   // biggest, but not the game
        profiler.endFrame();

        assertEquals("scene", profiler.snapshot().dominantStage().name());
    }

    @Test
    void enablingResetsTheWindow() {
        FrameProfiler profiler = enabled();
        profiler.record(Stage.SCENE, agoMs(5));
        profiler.endFrame();

        profiler.setEnabled(false);
        profiler.setEnabled(true);

        assertTrue(profiler.snapshot().isEmpty(),
                "a new measurement must not blend in the previous scene's frames");
    }

    @Test
    void chainReportsEachPassThroughTheProfiler() {
        // The real wiring: ShaderChain.apply must time the passes it runs.
        FrameProfiler profiler = enabled();
        ShaderChain chain = new ShaderChain();
        chain.setProfiler(profiler);
        chain.setPasses(List.of(Shaders.grayscale(), Shaders.invert()));

        int[] pixels = new int[64 * 64];
        Arrays.fill(pixels, 0xFF3366CC);
        chain.apply(pixels, 64, 64);
        profiler.endFrame();

        List<Stats> passes = profiler.snapshot().passes();
        assertEquals(2, passes.size(), "both passes should have been timed");
        assertEquals(List.of("grayscale", "invert"),
                passes.stream().map(Stats::name).toList());
    }

    @Test
    void chainWithoutAProfilerStillRuns() {
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.invert()));

        int[] pixels = new int[16];
        Arrays.fill(pixels, 0xFF000000);
        chain.apply(pixels, 4, 4);

        assertEquals(0xFFFFFFFF, pixels[0], "profiling must be optional, not required");
    }

    @Test
    void deviceProfileWorksHeadless() {
        DeviceProfile device = DeviceProfile.detect();

        assertNotNull(device.os());
        assertTrue(device.cores() >= 1);
        assertNotNull(device.pipeline());
        assertTrue(device.displayScale() >= 1.0);
        assertTrue(device.pixelCostMultiplier() >= 1.0);
        assertTrue(device.summary().contains("cores"));
    }

    @Test
    void reportNamesSceneWorkAsTheSceneRendererJob() {
        FrameProfiler profiler = enabled();
        for (int i = 0; i < 10; i++) {
            profiler.record(Stage.SCENE, agoMs(14));   // way over an 8.33 ms budget
            profiler.record(Stage.SHADERS, agoMs(1));
            profiler.endFrame();
        }

        String verdict = FrameReport.verdict(profiler.snapshot(), DeviceProfile.detect());
        assertTrue(verdict.contains("Scene drawing dominates"),
                "verdict should point at scene rendering:\n" + verdict);
        assertTrue(verdict.contains("Job B"), "verdict should name the job:\n" + verdict);
    }

    @Test
    void reportNamesShaderWorkAsThePostProcessingJob() {
        FrameProfiler profiler = enabled();
        for (int i = 0; i < 10; i++) {
            profiler.record(Stage.SHADERS, agoMs(14));
            profiler.record(Stage.SCENE, agoMs(1));
            profiler.endFrame();
        }

        String verdict = FrameReport.verdict(profiler.snapshot(), DeviceProfile.detect());
        assertTrue(verdict.contains("Post-processing dominates"),
                "verdict should point at the shader chain:\n" + verdict);
        assertTrue(verdict.contains("Job A"), "verdict should name the job:\n" + verdict);
    }

    @Test
    void reportRefusesToRecommendWorkWhenThereIsHeadroom() {
        FrameProfiler profiler = enabled();
        for (int i = 0; i < 10; i++) {
            profiler.record(Stage.SCENE, agoMs(1));   // 1 ms of an 8.33 ms budget
            profiler.endFrame();
        }

        String verdict = FrameReport.verdict(profiler.snapshot(), DeviceProfile.detect());
        assertTrue(verdict.contains("Comfortable"),
                "a frame with room to spare justifies neither job:\n" + verdict);
    }

    @Test
    void reportBlamesSimulationWhenSimulationIsTheProblem() {
        FrameProfiler profiler = enabled();
        for (int i = 0; i < 10; i++) {
            profiler.record(Stage.UPDATE, agoMs(15));
            profiler.endFrame();
        }

        String verdict = FrameReport.verdict(profiler.snapshot(), DeviceProfile.detect());
        assertTrue(verdict.contains("Simulation dominates"),
                "a slow update is not a rendering problem:\n" + verdict);
        assertTrue(verdict.contains("Neither GPU job would help"),
                "verdict should say so plainly:\n" + verdict);
    }

    @Test
    void reportWritesAFileWithTheStagesAndTheVerdict(@TempDir Path dir) throws Exception {
        FrameProfiler profiler = enabled();
        profiler.record(Stage.SCENE, agoMs(12));
        profiler.recordPass("bloom", agoMs(3));
        profiler.endFrame();

        Path out = dir.resolve("reports").resolve("frame-profile.txt");
        Path written = FrameReport.write(out, profiler.snapshot(),
                DeviceProfile.detect(), "PlayScene, 1280x720");

        assertEquals(out, written);
        String text = Files.readString(out);
        assertTrue(text.contains("Frame stages"), text);
        assertTrue(text.contains("Shader passes"), text);
        assertTrue(text.contains("bloom"), text);
        assertTrue(text.contains("Verdict"), text);
        assertTrue(text.contains("PlayScene, 1280x720"), "context should be recorded");
    }

    @Test
    void asecondReportDoesNotClobberTheFirst(@TempDir Path dir) throws Exception {
        // Measurements are taken in sets — shaders on against shaders off —
        // so a second run must not delete the half already captured.
        FrameProfiler profiler = enabled();
        profiler.record(Stage.SCENE, agoMs(3));
        profiler.endFrame();

        Path out = dir.resolve("frame-profile.txt");
        Path first = FrameReport.write(out, profiler.snapshot(), DeviceProfile.detect(), "run one");
        Path second = FrameReport.write(out, profiler.snapshot(), DeviceProfile.detect(), "run two");

        assertEquals(out, first);
        assertEquals(dir.resolve("frame-profile-2.txt"), second);
        assertTrue(Files.readString(first).contains("run one"), "the first run should survive");
        assertTrue(Files.readString(second).contains("run two"));
    }

    @Test
    void emptySnapshotRendersWithoutBlowingUp() {
        String text = FrameReport.render(Snapshot.EMPTY, DeviceProfile.detect(), null);
        assertTrue(text.contains("No frames measured"), text);
    }

    /**
     * The renderer must charge the shader chain and the blit to different
     * stages — the whole point of the instrumentation is telling those two
     * apart, and it is the one seam a headless test cannot reach because it
     * needs a real {@code BufferStrategy}. Skipped without a display; run it
     * under a virtual framebuffer ({@code xvfb-run ./gradlew test}) to exercise
     * the real path.
     */
    @Test
    void rendererChargesShadersAndPresentToDifferentStages() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        Frame window = new Frame("frame-profiler-test");
        try {
            Canvas canvas = new Canvas();
            canvas.setSize(320, 240);
            window.add(canvas);
            window.pack();
            window.setVisible(true);
            for (int i = 0; i < 100 && !canvas.isDisplayable(); i++) Thread.sleep(10);
            assumeFalse(!canvas.isDisplayable(), "canvas never became displayable");

            FrameProfiler profiler = enabled();
            ShaderChain chain = new ShaderChain();
            chain.setProfiler(profiler);
            chain.setPasses(List.of(Shaders.grayscale(), Shaders.invert()));

            Java2DRenderer renderer = new Java2DRenderer(canvas, Color.BLACK);
            renderer.setShaderChain(chain);
            renderer.setProfiler(profiler);

            Graphics2D g = renderer.beginFrame();
            g.setColor(Color.RED);
            g.fillRect(0, 0, 200, 150);
            renderer.present();
            profiler.endFrame();

            Snapshot snapshot = profiler.snapshot();
            assertTrue(snapshot.stage(Stage.SHADERS).meanMs() > 0,
                    "the chain ran, so shader time should be recorded");
            assertTrue(snapshot.stage(Stage.PRESENT).meanMs() > 0,
                    "acquiring and flipping the buffer should be recorded");
            assertEquals(List.of("grayscale", "invert"),
                    snapshot.passes().stream().map(Stats::name).toList(),
                    "both passes should appear, in chain order");
            assertEquals(0.0, snapshot.stage(Stage.SCENE).meanMs(), 1e-9,
                    "the renderer must not charge anything to the scene");
        } finally {
            window.dispose();
        }
    }
}
