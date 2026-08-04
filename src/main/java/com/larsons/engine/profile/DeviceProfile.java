package com.larsons.engine.profile;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;

/**
 * The machine a measurement was taken on, captured alongside it.
 *
 * <p>A frame time is meaningless without the hardware that produced it, and
 * the engine's performance question is explicitly a cross-machine one: a
 * desktop with headroom to spare and a fanless laptop that cannot hold the cap
 * need the same build to behave. So every report carries the details that
 * actually move frame times on the low end.
 *
 * <p><b>Why these fields.</b>
 * <ul>
 *   <li>{@link #cores()} — the CPU shader chain fans out across cores via
 *       {@code ParallelRows}, so its cost scales roughly with this. A 4-core
 *       laptop pays about twice what an 8-core desktop does for the same
 *       effect.</li>
 *   <li>{@link #displayScale()} — <b>the field most often missed.</b> On a
 *       HiDPI panel a "1280×720" window is backed by 2560×1440 real pixels,
 *       and every full-screen CPU pass costs four times what the logical size
 *       suggests. A Retina laptop can therefore be four times slower at
 *       post-processing than its specification implies, which is exactly the
 *       machine most likely to be measured and misread.</li>
 *   <li>{@link #pipeline()} — which Java2D backend is live. Java2D can hand
 *       blits to the GPU (OpenGL or Metal on macOS, Direct3D on Windows), but
 *       only for images it is allowed to keep in video memory, so this is the
 *       difference between the renderer's fast and slow paths.</li>
 *   <li>{@link #refreshHz()} — a 120 FPS cap on a 60 Hz panel spends half its
 *       frames on images nobody sees.</li>
 *   <li>{@link #backend()} and {@link #gpu()} — <b>which renderer drew the
 *       frames, and on what.</b> Added by B9, when there was finally more than
 *       one possible answer. A scene stage of 11.49 ms means one thing from the
 *       Java2D renderer and something very different from the GL one, and a
 *       report that does not say which is a number nobody can act on. This
 *       project has already lost hours of profiles to a build stamp that froze
 *       and stopped saying what produced them; the same mistake is not being
 *       made twice with the renderer.</li>
 * </ul>
 *
 * <p>Every lookup is guarded: this must work under {@code java.awt.headless}
 * so tests and dedicated servers can build a report without a display.
 */
public record DeviceProfile(String os, String osVersion, String arch, int cores,
                            long maxHeapMb, String javaVersion, String javaVendor,
                            String pipeline, int displayWidth, int displayHeight,
                            int refreshHz, double displayScale,
                            String backend, String gpu) {

    /** What {@link #backend()} says before a backend has been chosen. */
    public static final String UNKNOWN_BACKEND = "unknown";

    public DeviceProfile {
        if (backend == null || backend.isBlank()) backend = UNKNOWN_BACKEND;
        if (gpu == null) gpu = "";
    }

    /**
     * A machine described without a renderer, for callers that describe
     * hardware and nothing else. The golden frames pin one of these, and
     * {@code Engine} fills the backend in through {@link #withRenderer} once it
     * has chosen one.
     */
    public DeviceProfile(String os, String osVersion, String arch, int cores,
                         long maxHeapMb, String javaVersion, String javaVendor,
                         String pipeline, int displayWidth, int displayHeight,
                         int refreshHz, double displayScale) {
        this(os, osVersion, arch, cores, maxHeapMb, javaVersion, javaVendor, pipeline,
                displayWidth, displayHeight, refreshHz, displayScale, UNKNOWN_BACKEND, "");
    }

    /** The same machine, now known to be drawing through this backend. */
    public DeviceProfile withRenderer(String backend, String gpu) {
        return new DeviceProfile(os, osVersion, arch, cores, maxHeapMb, javaVersion,
                javaVendor, pipeline, displayWidth, displayHeight, refreshHz, displayScale,
                backend, gpu);
    }

    /**
     * The same machine, with the display described by whoever owns the window
     * rather than by AWT.
     *
     * <p><b>{@link #detect()} asks {@code GraphicsEnvironment}, and the GL build
     * runs with no {@code GraphicsEnvironment} to ask.</b> The macOS GL path
     * sets {@code java.awt.headless=true} — see {@code MacGlLauncher} for the
     * main-thread reason — so every field here that came from AWT comes back
     * zero, and the frame report says "headless / unknown" about a run on a
     * real monitor. A backend that brought its own window knows better;
     * {@code Engine} asks it and calls this.
     *
     * <p>Values that are not positive are ignored rather than stored, so a
     * backend that knows the size and not the refresh rate improves the profile
     * by exactly what it knows.
     */
    public DeviceProfile withDisplay(int width, int height, int hz, double scale) {
        return new DeviceProfile(os, osVersion, arch, cores, maxHeapMb, javaVersion,
                javaVendor, pipeline,
                width > 0 ? width : displayWidth,
                height > 0 ? height : displayHeight,
                hz > 0 ? hz : refreshHz,
                scale > 0 ? scale : displayScale,
                backend, gpu);
    }

    /** Inspect the current machine. Never throws; unknown fields degrade to defaults. */
    public static DeviceProfile detect() {
        Runtime runtime = Runtime.getRuntime();

        int width = 0, height = 0, refresh = 0;
        double scale = 1.0;
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice();
                width = device.getDisplayMode().getWidth();
                height = device.getDisplayMode().getHeight();
                int rate = device.getDisplayMode().getRefreshRate();
                refresh = rate == java.awt.DisplayMode.REFRESH_RATE_UNKNOWN ? 0 : rate;

                GraphicsConfiguration gc = device.getDefaultConfiguration();
                AffineTransform tx = gc.getDefaultTransform();
                scale = tx.getScaleX();
            } catch (Throwable ignored) {
                // A locked-down or virtual display; the timings are still valid.
            }
        }

        return new DeviceProfile(
                property("os.name"), property("os.version"), property("os.arch"),
                runtime.availableProcessors(),
                runtime.maxMemory() / (1024 * 1024),
                property("java.version"), property("java.vendor"),
                detectPipeline(),
                width, height, refresh, scale);
    }

    /**
     * Which Java2D rendering pipeline is in play, as far as it can be known.
     *
     * <p>Only the opt-in system properties are observable; the JVM does not
     * expose which pipeline it actually chose. So when nothing is set this
     * reports the <em>platform default</em> rather than claiming software
     * rendering — an earlier version said "software (default)" on macOS, which
     * was doubly misleading, since the platform default there is Metal and a
     * hardware-backed destination is precisely what makes unaccelerated
     * antialiased drawing expensive.
     */
    private static String detectPipeline() {
        if (flag("sun.java2d.opengl")) return "OpenGL (forced)";
        if (flag("sun.java2d.metal")) return "Metal (forced)";
        if (flag("sun.java2d.d3d")) return "Direct3D (forced)";
        if ("false".equalsIgnoreCase(System.getProperty("sun.java2d.d3d"))
                || "false".equalsIgnoreCase(System.getProperty("sun.java2d.opengl"))
                || "false".equalsIgnoreCase(System.getProperty("sun.java2d.metal"))) {
            return "software (hardware pipeline disabled)";
        }
        return platformDefaultPipeline() + " (platform default, not forced)";
    }

    /** The pipeline a JVM uses on this OS when nothing is specified. */
    private static String platformDefaultPipeline() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "Metal";
        if (os.contains("win")) return "Direct3D";
        return "X11/software";
    }

    private static boolean flag(String key) {
        String value = System.getProperty(key);
        return value != null && !value.equalsIgnoreCase("false");
    }

    private static String property(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** Whether the display reports more physical pixels than logical ones. */
    public boolean isHiDpi() { return displayScale > 1.01; }

    /**
     * How much more pixel work a full-screen CPU pass does than the window's
     * logical size suggests — the square of the display scale.
     */
    public double pixelCostMultiplier() { return displayScale * displayScale; }

    /** A single line naming the machine, for the head of a report. */
    public String summary() {
        return "%s %s (%s), %d cores, %d MB heap, Java %s".formatted(
                os, osVersion, arch, cores, maxHeapMb, javaVersion);
    }
}
