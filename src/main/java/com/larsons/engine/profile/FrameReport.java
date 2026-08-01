package com.larsons.engine.profile;

import com.larsons.engine.profile.FrameProfiler.Snapshot;
import com.larsons.engine.profile.FrameProfiler.Stage;
import com.larsons.engine.profile.FrameProfiler.Stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Turns a {@link Snapshot} into a report a person can act on, and — the point
 * of the exercise — into a verdict on which rendering work is worth doing.
 *
 * <p>The engine has two candidate pieces of GPU work with very different price
 * tags: moving the post-processing chain onto the GPU, and moving scene drawing
 * onto the GPU. A profile decides between them, because whichever stage owns
 * the frame is the only one whose replacement can pay for itself. The verdict
 * in {@link #verdict} says which, in those terms, rather than leaving a table
 * of numbers to be interpreted.
 *
 * <p>The report is deliberately plain text: it is meant to be run on several
 * machines — a desktop and the weakest laptop that must hold the frame rate —
 * and the outputs diffed against each other.
 */
public final class FrameReport {

    /** Below this share of the frame, a stage is not worth rewriting. */
    private static final double MATERIAL_SHARE = 0.20;

    /** Headroom above which no renderer change is justified by the numbers. */
    private static final double COMFORTABLE_HEADROOM_PCT = 40.0;

    /** p99/p50 ratio above which the frame is hitching rather than merely slow. */
    private static final double HITCH_RATIO = 2.5;

    private FrameReport() {}

    /** The full report: machine, per-stage table, shader breakdown, verdict. */
    public static String render(Snapshot snapshot, DeviceProfile device, String context) {
        StringBuilder out = new StringBuilder();

        out.append("Larson's 2D Game Engine — frame profile\n");
        out.append("=".repeat(64)).append('\n');
        out.append("taken    : ")
                .append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME))
                .append('\n');
        if (context != null && !context.isBlank()) {
            out.append("context  : ").append(context).append('\n');
        }
        out.append("machine  : ").append(device.summary()).append('\n');
        out.append("java2d   : ").append(device.pipeline()).append('\n');
        out.append("display  : ").append(displayLine(device)).append('\n');
        out.append('\n');

        if (snapshot.isEmpty()) {
            out.append("No frames measured. Enable the profiler (F3) and let it run.\n");
            return out.toString();
        }

        out.append("frames   : %d in window (%d total), target %d FPS (%.2f ms budget)%n"
                .formatted(snapshot.windowFrames(), snapshot.totalFrames(),
                        snapshot.targetFps(), snapshot.budgetMs()));
        out.append('\n');

        out.append(table("Frame stages", snapshot.stages(), snapshot));

        if (!snapshot.passes().isEmpty()) {
            out.append('\n').append(table("Shader passes", snapshot.passes(), snapshot));
        }

        out.append('\n').append(summaryBlock(snapshot, device));
        out.append('\n').append(verdict(snapshot, device));
        return out.toString();
    }

    private static String displayLine(DeviceProfile device) {
        if (device.displayWidth() <= 0) return "headless / unknown";
        String base = "%dx%d".formatted(device.displayWidth(), device.displayHeight());
        if (device.refreshHz() > 0) base += " @ %d Hz".formatted(device.refreshHz());
        base += ", scale %.2gx".formatted(device.displayScale());
        if (device.isHiDpi()) {
            base += " (HiDPI — full-screen passes cost %.1fx the logical pixel count)"
                    .formatted(device.pixelCostMultiplier());
        }
        return base;
    }

    private static String table(String title, List<Stats> rows, Snapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        out.append("-".repeat(64)).append('\n');
        out.append("%-10s %8s %8s %8s %8s %7s%n"
                .formatted("stage", "mean ms", "p50", "p95", "p99", "share"));

        double work = snapshot.workMs();
        for (Stats s : rows) {
            String share = s.name().equals(Stage.IDLE.label()) || work <= 0
                    ? "-"
                    : "%.0f%%".formatted(100.0 * s.meanMs() / work);
            out.append("%-10s %8.3f %8.3f %8.3f %8.3f %7s%n".formatted(
                    s.name(), s.meanMs(), s.p50Ms(), s.p95Ms(), s.p99Ms(), share));
        }
        return out.toString();
    }

    private static String summaryBlock(Snapshot snapshot, DeviceProfile device) {
        StringBuilder out = new StringBuilder();
        out.append("Summary\n");
        out.append("-".repeat(64)).append('\n');
        out.append("work per frame   : %.3f ms (of %.2f ms budget)%n"
                .formatted(snapshot.workMsExcludingOverlay(), snapshot.budgetMs()));
        out.append("headroom         : %.1f%%%n".formatted(snapshot.headroomPct()));
        out.append("sustainable FPS  : %.0f%n".formatted(snapshot.achievableFps()));
        out.append("dominant stage   : %s (%.3f ms)%n".formatted(
                snapshot.dominantStage().name(), snapshot.dominantStage().meanMs()));

        double overlay = snapshot.stage(Stage.OVERLAY).meanMs();
        if (overlay > 0.05) {
            out.append("profiler overlay : %.3f ms (excluded from the figures above)%n"
                    .formatted(overlay));
        }
        return out.toString();
    }

    /**
     * The recommendation the measurement supports, in terms of the two jobs
     * actually on the table.
     *
     * <p>The ordering of these checks matters. Headroom is tested first because
     * a frame that comfortably makes its budget does not justify either project
     * regardless of how its time is divided. Hitching is tested next because a
     * frame that is usually fast and occasionally terrible is not fixed by a
     * faster renderer — it is fixed by finding the stall. Only then does the
     * scene-versus-shaders split decide which job to fund.
     */
    public static String verdict(Snapshot snapshot, DeviceProfile device) {
        StringBuilder out = new StringBuilder();
        out.append("Verdict\n");
        out.append("-".repeat(64)).append('\n');

        if (snapshot.isEmpty()) {
            out.append("No data.\n");
            return out.toString();
        }

        double work = snapshot.workMsExcludingOverlay();
        double scene = snapshot.stage(Stage.SCENE).meanMs();
        double shaders = snapshot.stage(Stage.SHADERS).meanMs();
        double update = snapshot.stage(Stage.UPDATE).meanMs();
        double present = snapshot.stage(Stage.PRESENT).meanMs();

        if (snapshot.headroomPct() >= COMFORTABLE_HEADROOM_PCT) {
            out.append("""
                    Comfortable. %.1f%% of the frame budget is unused at this \
                    workload, so neither GPU job is justified by these numbers \
                    yet. Re-measure on the weakest target machine, and under the \
                    heaviest scene you intend to ship, before deciding.
                    """.formatted(snapshot.headroomPct()));
        } else {
            Stats worst = snapshot.dominantStage();
            out.append("Frame budget is tight (%.1f%% headroom, ~%.0f FPS sustainable).%n%n"
                    .formatted(snapshot.headroomPct(), snapshot.achievableFps()));

            if (worst.name().equals(Stage.SCENE.label()) && scene / work >= MATERIAL_SHARE) {
                out.append("""
                        Scene drawing dominates (%.3f ms, %.0f%% of the frame). \
                        This is Job B territory: the win is a backend-neutral \
                        draw API with batching, and GPU scene rendering behind \
                        it. Moving only the shader chain to the GPU would not \
                        touch this number.
                        """.formatted(scene, 100 * scene / work));
            } else if (worst.name().equals(Stage.SHADERS.label())
                    && shaders / work >= MATERIAL_SHARE) {
                out.append("""
                        Post-processing dominates (%.3f ms, %.0f%% of the frame). \
                        This is Job A territory. Check the per-pass table first: \
                        if one effect carries most of it, tuning or disabling \
                        that pass may recover the frame for free.
                        """.formatted(shaders, 100 * shaders / work));
            } else if (worst.name().equals(Stage.UPDATE.label())) {
                out.append("""
                        Simulation dominates (%.3f ms, %.0f%% of the frame). \
                        Neither GPU job would help — a renderer cannot make the \
                        fixed-step update faster. Profile the update path instead.
                        """.formatted(update, 100 * update / work));
            } else if (worst.name().equals(Stage.PRESENT.label())) {
                out.append("""
                        Presentation dominates (%.3f ms, %.0f%% of the frame). \
                        This is blit and buffer-flip cost, often vsync waiting \
                        inside the flip. Check the display refresh rate against \
                        the frame cap before reading it as a rendering cost.
                        """.formatted(present, 100 * present / work));
            } else {
                out.append("""
                        No single stage dominates — the cost is spread across \
                        the frame. Broad costs like this rarely justify a large \
                        rewrite; look for a workload-level win (fewer draw \
                        calls, cached terrain) first.
                        """);
            }
        }

        appendHitchNote(out, snapshot);
        appendDeviceNotes(out, snapshot, device);
        return out.toString();
    }

    private static void appendHitchNote(StringBuilder out, Snapshot snapshot) {
        for (Stage stage : Stage.values()) {
            if (stage == Stage.IDLE) continue;
            Stats s = snapshot.stage(stage);
            if (s.p50Ms() > 0.05 && s.p99Ms() / s.p50Ms() >= HITCH_RATIO) {
                out.append("%nHitching: %s spikes to %.3f ms at p99 against a %.3f ms median. %s%n"
                        .formatted(s.name(), s.p99Ms(), s.p50Ms(),
                                "That is a stall to find, not a throughput problem to engineer around."));
            }
        }
    }

    private static void appendDeviceNotes(StringBuilder out, Snapshot snapshot,
                                          DeviceProfile device) {
        if (device.isHiDpi()) {
            out.append("""
                    %nHiDPI: this display is %.2gx scaled, so full-screen CPU \
                    passes cover %.1fx the pixels the window's logical size \
                    implies. Post-processing measured here is proportionally \
                    heavier than on a 1x panel at the same window size.%n"""
                    .formatted(device.displayScale(), device.pixelCostMultiplier()));
        }
        if (device.cores() <= 4 && snapshot.stage(Stage.SHADERS).meanMs() > 0) {
            out.append("""
                    %nCores: the CPU shader chain fans out across %d cores here. \
                    On a machine with fewer cores than the development one, \
                    post-processing is the stage that degrades first.%n"""
                    .formatted(device.cores()));
        }
        if (device.refreshHz() > 0 && snapshot.targetFps() > device.refreshHz()) {
            out.append("""
                    %nRefresh: the %d FPS cap is above this display's %d Hz. \
                    Frames beyond the panel's rate are work nobody sees; capping \
                    at the refresh rate is free headroom.%n"""
                    .formatted(snapshot.targetFps(), device.refreshHz()));
        }
    }

    /**
     * Write a report next to the game. Returns the path written, or
     * {@code null} if it could not be saved — a profiling run is never allowed
     * to take the game down with it.
     */
    public static Path write(Path path, Snapshot snapshot, DeviceProfile device,
                             String context) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, render(snapshot, device, context));
            return path;
        } catch (IOException | RuntimeException e) {
            System.err.println("[profile] could not write report: " + e.getMessage());
            return null;
        }
    }
}
