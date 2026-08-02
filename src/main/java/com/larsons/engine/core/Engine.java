package com.larsons.engine.core;

import com.larsons.engine.graphics.Java2DRenderer;
import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.profile.FrameReport;
import com.larsons.engine.profile.ProfileOverlay;
import com.larsons.engine.scene.SceneManager;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

/**
 * Top-level engine: wires together the window, renderer, input, scene manager,
 * and game loop.
 *
 * <p>Typical use:
 * <pre>
 *   Engine engine = new Engine(new EngineConfig());
 *   engine.scenes().register("menu", new MainMenuScene());
 *   engine.scenes().setScene("menu");
 *   engine.start();
 * </pre>
 *
 * <p><b>Profiling.</b> {@code F3} toggles a frame-cost readout in every scene,
 * and {@code F4} writes the current measurement to a report file. Both are also
 * driveable from the command line so a run can be reproduced on another
 * machine without touching the keyboard:
 * <pre>
 *   -Dlarsons.profile=true              start with profiling on
 *   -Dlarsons.profile.overlay=false     measure, but draw no HUD
 *   -Dlarsons.profile.seconds=30        auto-write a report after 30 s and stop
 *   -Dlarsons.profile.out=frames.txt    where that report goes
 * </pre>
 * See {@link FrameProfiler} for what the stages mean and {@link FrameReport}
 * for how a result is read.
 */
public class Engine {
    private final EngineConfig config;
    private final GameWindow window;
    private final InputManager input;
    private final Renderer renderer;
    private final ShaderChain shaders;
    private final SceneManager scenes;
    private final GameLoop loop;

    private final FrameProfiler profiler = new FrameProfiler();
    private final DeviceProfile device = DeviceProfile.detect();
    private boolean overlayVisible;

    /** Seconds of measurement after which a report is written, or 0 for never. */
    private final double autoReportSeconds;
    private final Path reportPath;
    private double profiledSeconds;
    private boolean autoReportDone;

    // Last-seen viewport size, to detect window resizes.
    private int lastWidth, lastHeight;

    public Engine(EngineConfig config) {
        this.config = config;
        this.window = new GameWindow(config);
        this.input = new InputManager();
        window.attachInput(input);

        this.renderer = new Java2DRenderer(window.getCanvas(), config.backgroundColor);
        this.shaders = new ShaderChain();
        this.renderer.setShaderChain(shaders);
        this.scenes = new SceneManager();
        this.scenes.setViewport(config.width, config.height);
        this.lastWidth = config.width;
        this.lastHeight = config.height;

        this.loop = new GameLoop(config.updateRate, config.targetFps, this::tick, this::draw);

        this.renderer.setProfiler(profiler);
        this.shaders.setProfiler(profiler);
        this.loop.setProfiler(profiler);
        this.profiler.setTargetFps(config.targetFps);

        this.autoReportSeconds = numberProperty("larsons.profile.seconds", 0);
        this.reportPath = Path.of(System.getProperty("larsons.profile.out", "frame-profile.txt"));
        if (booleanProperty("larsons.profile", false)) {
            setProfilingEnabled(true);
            this.overlayVisible = booleanProperty("larsons.profile.overlay", true);
        }
    }

    private static boolean booleanProperty(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : !value.equalsIgnoreCase("false");
    }

    private static double numberProperty(String key, double fallback) {
        try {
            String value = System.getProperty(key);
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public SceneManager scenes() { return scenes; }

    public EngineConfig config() { return config; }

    public InputManager input() { return input; }

    /** The post-processing shader chain applied to every presented frame. */
    public ShaderChain shaders() { return shaders; }

    public double fps() { return loop.getFps(); }

    public int getTargetFps() { return loop.getTargetFps(); }

    /** Change the render frame cap at runtime (used by game-type settings). */
    public void setTargetFps(int fps) { loop.setTargetFps(fps); }

    /** Per-stage frame timings. See {@link FrameProfiler} for what they mean. */
    public FrameProfiler profiler() { return profiler; }

    /** The machine these measurements were taken on. */
    public DeviceProfile device() { return device; }

    /**
     * Turn measurement on or off; enabling always starts a fresh window.
     *
     * <p>Enabling also re-arms {@code larsons.profile.seconds}, so each F3
     * press starts a new timed run. That is what makes the interesting
     * workflow work: launch with a duration but <em>without</em>
     * {@code larsons.profile}, walk into whatever scene you actually want
     * measured, and press F3 there — the timer starts on the scene you care
     * about rather than on the menu the game happened to boot into.
     */
    public void setProfilingEnabled(boolean on) {
        profiler.setEnabled(on);
        if (on) {
            profiledSeconds = 0;
            autoReportDone = false;
        }
    }

    /** Whether the on-screen readout is drawn (measurement is independent). */
    public void setOverlayVisible(boolean visible) { this.overlayVisible = visible; }

    public boolean isOverlayVisible() { return overlayVisible; }

    /**
     * Write the current measurement to {@code larsons.profile.out} (default
     * {@code frame-profile.txt}) and echo the verdict to the console. Returns
     * the file written, or {@code null} if nothing was measured or the write
     * failed.
     */
    public Path writeProfileReport(String context) {
        FrameProfiler.Snapshot snapshot = profiler.snapshot();
        if (snapshot.isEmpty()) {
            System.out.println("[profile] nothing measured yet — press F3 first");
            return null;
        }
        Path written = FrameReport.write(reportPath, snapshot, device, context);
        System.out.print(FrameReport.verdict(snapshot, device));
        if (written != null) {
            System.out.println("[profile] full report: " + written.toAbsolutePath());
        }
        return written;
    }

    public void start() {
        window.show();
        loop.start();
    }

    public void stop() { loop.stop(); }

    private void tick(double dt) {
        // Keep the scene viewport in sync with a (possibly) resizable window.
        int w = window.getWidth();
        int h = window.getHeight();
        if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
            lastWidth = w;
            lastHeight = h;
            scenes.setViewport(w, h);
        }

        input.newFrame();
        handleProfilerKeys();
        scenes.update(dt, input);
        advanceAutoReport(dt);
    }

    /**
     * F3 toggles measurement and the readout together; F4 writes a report
     * without interrupting the run. Read before scene updates so a scene that
     * binds the same keys still sees them.
     */
    private void handleProfilerKeys() {
        if (input.isKeyJustPressed(KeyEvent.VK_F3)) {
            boolean on = !profiler.isEnabled();
            setProfilingEnabled(on);
            overlayVisible = on;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_F4) && profiler.isEnabled()) {
            writeProfileReport(describeContext());
        }
    }

    /**
     * Drive {@code -Dlarsons.profile.seconds}: measure for a fixed span, write
     * the report, and stop. This is what makes a run comparable across
     * machines — the same scene, the same duration, no human timing a
     * keystroke.
     */
    private void advanceAutoReport(double dt) {
        if (autoReportDone || autoReportSeconds <= 0 || !profiler.isEnabled()) return;
        profiledSeconds += dt;
        if (profiledSeconds >= autoReportSeconds) {
            autoReportDone = true;
            writeProfileReport(describeContext());
            setProfilingEnabled(false);
            overlayVisible = false;
        }
    }

    /** What was on screen while measuring — a report is useless without it. */
    private String describeContext() {
        String scene = scenes.current() == null
                ? "no scene" : scenes.current().getClass().getSimpleName();
        return "%s, %dx%d, %d shader pass(es), terrain cache %s".formatted(
                scene, renderer.getWidth(), renderer.getHeight(), shaders.passes().size(),
                com.larsons.engine.graphics.TerrainCache.enabled() ? "on" : "off");
    }

    private void draw(double alpha) {
        Graphics2D g = renderer.beginFrame();
        try {
            // One target for the whole frame. Everything the scene draws goes
            // through it, so its DrawStats are the frame's own draw-call
            // count — the number that says what a batching backend would buy.
            Java2DTarget target = new Java2DTarget(g, renderer.getWidth(), renderer.getHeight());

            // Only the scene's own drawing is timed here. The renderer reports
            // its shader and present costs itself, so the three stay separable.
            long sceneStart = profiler.begin();
            try {
                scenes.render(target, (float) alpha);
            } finally {
                profiler.record(FrameProfiler.Stage.SCENE, sceneStart);
                profiler.recordDraws(target.stats());
            }
            if (overlayVisible) {
                // Its own target over the same surface, deliberately: the
                // readout is not part of the game, and sharing the frame's
                // target would fold the overlay's own draw calls into the
                // count it is reporting. A second Java2DTarget draws the same
                // pixels and keeps a separate DrawStats, which is exactly the
                // separation the old "draw straight at the Graphics2D" got by
                // bypassing the seam altogether.
                long overlayStart = profiler.begin();
                try {
                    ProfileOverlay.draw(
                            new Java2DTarget(g, renderer.getWidth(), renderer.getHeight()),
                            profiler.latest(), device, loop.getFps());
                } finally {
                    profiler.record(FrameProfiler.Stage.OVERLAY, overlayStart);
                }
            }
        } finally {
            renderer.present();
        }
    }
}
