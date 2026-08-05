package com.larsons.engine.core;

import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.BackendWindow;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.Java2DRenderer;
import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.profile.FrameReport;
import com.larsons.engine.profile.ProfileOverlay;
import com.larsons.engine.scene.SceneManager;

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
 *   -Dlarsons.run.seconds=45            quit the game after 45 s, whatever else
 * </pre>
 * See {@link FrameProfiler} for what the stages mean and {@link FrameReport}
 * for how a result is read.
 *
 * <p><b>Which renderer, and which window.</b> This class is the one place that
 * knows there is more than one of either. {@link Backends} picks a backend at
 * startup — a GPU one if the classpath has a working one and
 * {@code -Dlarsons.render.backend} allows it, Java2D otherwise — and the choice
 * decides the window with it:
 *
 * <ul>
 *   <li><b>Java2D</b> opens the AWT {@link GameWindow} this engine has always
 *       opened, and AWT's event-dispatch thread delivers input as it always
 *       has. {@link #start()} shows the window and waits.</li>
 *   <li><b>A GPU backend</b> arrives with a {@link BackendWindow} of its own,
 *       and <b>no {@code JFrame} is constructed at all</b>. Two windowing
 *       systems in one process is a category of bug nobody needs — two event
 *       queues, two focus owners, two ideas of where the mouse is — so they
 *       are never both alive. {@link #start()} then pumps that window's events
 *       on the caller's thread while the game loop renders on its own, which
 *       is the arrangement GLFW-style libraries require: events on the thread
 *       that created the window, GL on the thread that draws.</li>
 * </ul>
 *
 * <p>Either way the loop, the scenes and the profiler see one
 * {@link Renderer} and one {@link InputManager} and cannot tell which is
 * behind them. The chosen backend is recorded in {@link #device()} so every
 * frame report says what drew it.
 */
public class Engine {
    private final EngineConfig config;

    /** The AWT window, or {@code null} when a backend brought one of its own. */
    private final GameWindow window;

    /** The backend's own window, or {@code null} on the Java2D path. */
    private final BackendWindow backendWindow;

    private final InputManager input;
    private final Renderer renderer;
    private final BackendChoice backend;
    private final ShaderChain shaders;
    private final SceneManager scenes;
    private final GameLoop loop;

    private final FrameProfiler profiler = new FrameProfiler();
    private final DeviceProfile device;
    private boolean overlayVisible;

    /** How often the backend's window is pumped for events while running. */
    private static final long PUMP_INTERVAL_NS = 2_000_000L;

    /** Seconds to run before quitting, or 0 to run until the player closes it. */
    private final double runSeconds;
    private double ranSeconds;
    private volatile boolean stopping;

    /** Seconds of measurement after which a report is written, or 0 for never. */
    private final double autoReportSeconds;
    private final Path reportPath;
    private double profiledSeconds;
    private boolean autoReportDone;

    // Last-seen viewport size, to detect window resizes.
    private int lastWidth, lastHeight;

    public Engine(EngineConfig config) {
        this.config = config;
        this.input = new InputManager();

        this.backend = Backends.select(new RendererFactory.Request(
                config.width, config.height, config.title, config.backgroundColor));
        // A player who asked for a backend and quietly got another one has been
        // misled; one who asked for nothing and got the renderer their jar
        // ships has not. Only the first is worth stderr.
        (backend.fellBack() ? System.err : System.out).println(backend.describe());

        if (backend.isJava2D()) {
            // A process that exists only to run GL on macOS's first thread must
            // not open a Swing window: AWT wants that thread for its own run
            // loop and would hang on it. This exits, and the launcher that is
            // still waiting runs the game in a process without the flag.
            MacGlLauncher.abortIfThisProcessExistsOnlyForGl();
            this.window = new GameWindow(config);
            this.backendWindow = null;
            this.renderer = new Java2DRenderer(window.getCanvas(), config.backgroundColor);
            window.attachInput(input);
        } else {
            this.window = null;
            this.backendWindow = backend.backend().window();
            this.renderer = backend.backend().renderer();
            backendWindow.attachInput(input);
        }
        this.device = describeDevice(backend);

        this.shaders = new ShaderChain();
        this.renderer.setShaderChain(shaders);
        this.scenes = new SceneManager();
        this.scenes.setViewport(config.width, config.height);
        this.lastWidth = config.width;
        this.lastHeight = config.height;

        this.loop = new GameLoop(config.updateRate, config.targetFps, this::tick, this::draw);
        // A backend that waits for the panel is already pacing frames, and a
        // second pacer beside it costs a third of the frame rate — measured, and
        // written up on Renderer.presentationIsPaced().
        boolean paced = renderer.presentationIsPaced();
        this.loop.setExternallyPaced(paced);
        // And the report says so, because `idle` means something different
        // under each pacer and two profiles that disagree cannot be compared.
        this.profiler.setExternallyPaced(paced);

        this.renderer.setProfiler(profiler);
        this.shaders.setProfiler(profiler);
        this.loop.setProfiler(profiler);
        this.profiler.setTargetFps(config.targetFps);

        this.autoReportSeconds = numberProperty("larsons.profile.seconds", 0);
        this.runSeconds = numberProperty("larsons.run.seconds", 0);
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

    /** The machine these measurements were taken on, and the backend that drew them. */
    public DeviceProfile device() { return device; }

    /**
     * The machine this run is on, for the frame report — asked of AWT, then of
     * the backend for anything AWT could not answer.
     *
     * <p><b>The second half is not belt-and-braces, it is the only source in a
     * GL run.</b> The macOS GL path sets {@code java.awt.headless=true} — see
     * {@link MacGlLauncher} for the main-thread reason — so
     * {@code DeviceProfile.detect()} comes back with no display size and no
     * refresh rate at all, and the report would say "headless / unknown" about
     * a run on a real monitor. A backend that brought its own window knows its
     * own monitor, so it is asked.
     */
    private DeviceProfile describeDevice(BackendChoice backend) {
        DeviceProfile detected = DeviceProfile.detect()
                .withRenderer(backend.chosen(), backend.driver());
        if (backendWindow == null) return detected;
        int[] mode = backendWindow.displayMode();
        int width = mode != null && mode.length >= 3 ? mode[0] : 0;
        int height = mode != null && mode.length >= 3 ? mode[1] : 0;
        int hz = mode != null && mode.length >= 3 ? mode[2] : 0;
        // The scale the window reports is the one the frame is really drawn at,
        // which is a better answer than AWT's even when AWT is available.
        return detected.withDisplay(width, height, hz, backendWindow.displayScale());
    }

    /** Which renderer is drawing, and why that one. */
    public BackendChoice backend() { return backend; }

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

    /**
     * Show the window, start the loop, and stay until the game ends.
     *
     * <p><b>This blocks now, and it did not before.</b> The AWT path used to
     * return immediately and leave the loop thread holding the JVM open, which
     * worked because Swing has an event thread of its own. A backend window
     * does not: its events have to be pumped from the thread that created it,
     * and that is this one. Rather than have two different lifecycles depending
     * on which renderer was picked, both wait here — for the pump on one path
     * and for the loop on the other — and both tear down through
     * {@link #shutdown()} when it ends. {@code Main} did nothing after this
     * call anyway.
     */
    public void start() {
        if (backendWindow != null) backendWindow.show(); else window.show();
        loop.start();
        if (backendWindow != null) pumpUntilStopped(); else loop.awaitStop(0);
        shutdown();
    }

    public void stop() {
        stopping = true;
        loop.stop();
    }

    /**
     * Drain the backend window's event queue until the game ends.
     *
     * <p>Runs on the caller's thread — the one that created the window — while
     * the loop renders on its own. Polling rather than blocking on the queue
     * keeps the exit condition (the loop stopping, {@code larsons.run.seconds}
     * elapsing) checkable, and 2 ms is far below anything a player can feel at
     * a 120 FPS cap. The wait parks rather than spins.
     */
    private void pumpUntilStopped() {
        while (loop.isRunning() && !backendWindow.closeRequested()) {
            backendWindow.pumpEvents();
            GameLoop.waitUntil(System.nanoTime() + PUMP_INTERVAL_NS);
        }
    }

    /**
     * Stop the loop, let the frame in flight finish, and release the backend.
     *
     * <p>Order matters. The renderer is disposed only after the loop thread has
     * actually stopped, because a GPU backend's resources belong to a context
     * that thread was using and freeing them underneath it is a crash rather
     * than a leak.
     *
     * <p>The exit is deliberate. The AWT path normally never reaches here — the
     * frame's {@code EXIT_ON_CLOSE} ends the process first — so this covers
     * every other way a run can end, including {@code larsons.run.seconds},
     * and it is the only way a run on a backend window ends at all.
     */
    private void shutdown() {
        stopping = true;
        loop.stop();
        loop.awaitStop(2000);
        try {
            renderer.dispose();
        } catch (Throwable t) {
            System.err.println("[render] backend did not shut down cleanly: " + t);
        }
        if (backendWindow != null) backendWindow.close();
        System.exit(0);
    }

    private void tick(double dt) {
        // Keep the scene viewport in sync with a (possibly) resizable window.
        int w = backendWindow != null ? backendWindow.width() : window.getWidth();
        int h = backendWindow != null ? backendWindow.height() : window.getHeight();
        if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
            lastWidth = w;
            lastHeight = h;
            scenes.setViewport(w, h);
        }

        input.newFrame();
        handleProfilerKeys();
        scenes.update(dt, input);
        advanceAutoReport(dt);
        advanceRunLimit(dt);
    }

    /**
     * Drive {@code -Dlarsons.run.seconds}: play for a fixed span and quit.
     *
     * <p>Separate from {@code larsons.profile.seconds}, which stops
     * <em>measuring</em> and leaves the game up. This one is how a launch is
     * checked without a person at the keyboard — B9's "force each backend and
     * confirm both run" is exactly this flag twice — and how a headless CI run
     * of the real game ends rather than hanging.
     */
    private void advanceRunLimit(double dt) {
        if (runSeconds <= 0 || stopping) return;
        ranSeconds += dt;
        if (ranSeconds >= runSeconds) {
            System.out.println("[engine] larsons.run.seconds=%.0f elapsed on the %s backend — quitting"
                    .formatted(runSeconds, backend.chosen()));
            stop();
        }
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
        // One target for the whole frame, and the backend makes it — the loop
        // no longer knows which backend it is talking to. Everything drawn goes
        // through it, so its DrawStats are the frame's own draw-call count: the
        // number that says what a batching backend would buy.
        DrawTarget target = renderer.beginFrame();
        try {
            // Only the scene's own drawing is timed here. The renderer reports
            // its shader and present costs itself, so the three stay separable.
            long sceneStart = profiler.begin();
            try {
                scenes.render(target, (float) alpha);
            } finally {
                profiler.record(FrameProfiler.Stage.SCENE, sceneStart);
                // Read before the overlay draws, and read by value —
                // recordDraws copies the counters out rather than keeping the
                // object — so the readout never inflates the count it is
                // reporting. This used to be arranged by giving the overlay a
                // second Java2DTarget over the same Graphics2D, which is not
                // something a caller holding a DrawTarget can do, and was
                // never the reason the numbers were right anyway.
                profiler.recordDraws(target.stats());
            }
            if (overlayVisible) {
                long overlayStart = profiler.begin();
                try {
                    ProfileOverlay.draw(target, profiler.latest(), device, loop.getFps());
                } finally {
                    profiler.record(FrameProfiler.Stage.OVERLAY, overlayStart);
                }
            }
        } finally {
            renderer.present();
        }
    }
}
