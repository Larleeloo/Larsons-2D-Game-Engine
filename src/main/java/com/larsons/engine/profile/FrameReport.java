package com.larsons.engine.profile;

import com.larsons.engine.profile.FrameProfiler;
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
        out.append("build    : ").append(BuildInfo.describe()).append('\n');
        out.append("machine  : ").append(device.summary()).append('\n');
        // The backend goes above the Java2D pipeline line, and both stay:
        // Java2D still bakes sprites and lays text out whichever renderer
        // presents the frame, so its pipeline is context even for a GL run.
        // Which renderer drew the frame is not context — it is the first thing
        // a reader needs, and B9 is where there started to be a choice.
        out.append("backend  : ").append(device.backend()).append('\n');
        if (!device.gpu().isBlank()) {
            out.append("gpu      : ").append(device.gpu()).append('\n');
        }
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
        if (snapshot.externallyPaced()) {
            // Without this line the next reader sees `idle` near zero and
            // concludes the headroom has gone. It has not: the wait moved into
            // `present`, because the display is doing the waiting. Two profiles
            // paced differently cannot be compared, so each one says which it is.
            out.append("pacing   : the display (vsync) — the frame limiter stands aside, "
                    + "so the wait is in `present` rather than in `idle`\n");
        }
        out.append('\n');

        out.append(table("Frame stages", snapshot.stages(), snapshot));

        List<Stats> updatePhases = snapshot.sections(Stage.UPDATE);
        if (!updatePhases.isEmpty()) {
            out.append('\n').append(table("Update breakdown", updatePhases, snapshot));
        }
        if (!snapshot.steps().isEmpty()) {
            // SIM_PLAN S1: a 21 ms update stage is one slow step or eight
            // ordinary ones, and until this line existed the report could not
            // say which. The per-step cost is the number that separates an
            // expensive operation from a catch-up cascade.
            FrameProfiler.Steps steps = snapshot.steps();
            out.append('\n');
            out.append("Simulation steps per frame\n");
            out.append("-".repeat(64)).append('\n');
            out.append("mean %.2f   p50 %d   p95 %d   max %d   (cap %d)%n"
                    .formatted(steps.mean(), steps.p50(), steps.p95(), steps.max(),
                            com.larsons.engine.core.GameLoop.MAX_CATCH_UP_STEPS));
            out.append("cost per step : %.3f ms   (update stage / steps)%n"
                    .formatted(steps.msPerStep(
                            snapshot.stage(Stage.UPDATE).meanMs())));
        }

        List<Stats> sceneSections = snapshot.sections(Stage.SCENE);
        if (!sceneSections.isEmpty()) {
            out.append('\n').append(table("Scene breakdown", sceneSections, snapshot));
        }

        if (!snapshot.passes().isEmpty()) {
            out.append('\n').append(table("Shader passes", snapshot.passes(), snapshot));
        }

        if (!snapshot.draws().isEmpty()) {
            out.append('\n').append(drawBlock(snapshot));
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

    /**
     * What the frame asked the renderer to draw, and what a batching backend
     * would collapse it into — the half of the GPU question the timings cannot
     * answer.
     */
    private static String drawBlock(Snapshot snapshot) {
        FrameProfiler.Draws draws = snapshot.draws();
        StringBuilder out = new StringBuilder();
        out.append("Draw calls (per frame, mean)\n");
        out.append("-".repeat(64)).append('\n');
        out.append("operations issued : %.0f%n".formatted(draws.operations()));
        out.append("batched draws     : %.0f%n".formatted(draws.batches()));
        out.append("merge ratio       : %.1fx%n".formatted(draws.mergeRatio()));
        out.append("  of which images : %.0f%n".formatted(draws.images()));
        out.append("  of which text   : %.0f%n".formatted(draws.glyphs()));
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
        appendBatchingNote(out, snapshot);
        appendDeviceNotes(out, snapshot, device);
        return out.toString();
    }

    /**
     * How much a batching backend could merge, which is what decides whether
     * a GPU renderer is the next move or whether the art has to be atlased
     * first. Only meaningful once the scene draws through a DrawTarget, so it
     * stays silent while the migration is under way.
     */
    private static void appendBatchingNote(StringBuilder out, Snapshot snapshot) {
        FrameProfiler.Draws draws = snapshot.draws();
        if (draws.isEmpty()) return;

        double ratio = draws.mergeRatio();
        if (ratio >= 10) {
            out.append("""
                    %nBatching: %.0f operations collapse into %.0f draws (%.1fx). \
                    This draw order is already GPU-shaped — a batching backend \
                    would issue very few calls for it, which is where the win \
                    would come from.%n"""
                    .formatted(draws.operations(), draws.batches(), ratio));
        } else if (ratio >= 3) {
            out.append("""
                    %nBatching: %.0f operations collapse into %.0f draws (%.1fx). \
                    Worth having, but sorting the scene by texture — or atlasing \
                    the art so more of it shares one — would raise it before any \
                    backend work.%n"""
                    .formatted(draws.operations(), draws.batches(), ratio));
        } else {
            out.append("""
                    %nBatching: %.0f operations collapse into only %.0f draws \
                    (%.1fx). Consecutive operations rarely share a texture, so a \
                    GPU backend would issue nearly one call per sprite and buy \
                    little. Atlas the art and sort by texture first; that is a \
                    prerequisite, not an optimisation.%n"""
                    .formatted(draws.operations(), draws.batches(), ratio));
        }
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
     * Write a report next to the game. Returns the path actually written, or
     * {@code null} if it could not be saved — a profiling run is never allowed
     * to take the game down with it.
     *
     * <p>An existing report is never overwritten; a run lands beside it as
     * {@code frame-profile-2.txt} and so on. Measurements are taken in sets —
     * shaders on against shaders off, one machine against another — and
     * silently clobbering the run someone just took would lose exactly the
     * half of the comparison they were in the middle of making.
     */
    public static Path write(Path path, Snapshot snapshot, DeviceProfile device,
                             String context) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path target = available(path);
            Files.writeString(target, render(snapshot, device, context));
            return target;
        } catch (IOException | RuntimeException e) {
            System.err.println("[profile] could not write report: " + e.getMessage());
            return null;
        }
    }

    /** {@code path} if free, else the first {@code name-N.ext} that is. */
    private static Path available(Path path) {
        if (!Files.exists(path)) return path;

        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot < 0 ? fileName : fileName.substring(0, dot);
        String extension = dot < 0 ? "" : fileName.substring(dot);

        Path parent = path.toAbsolutePath().getParent();
        for (int n = 2; n < 1000; n++) {
            Path candidate = (parent == null ? Path.of("") : parent)
                    .resolve(stem + "-" + n + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        return path; // a thousand reports in one folder; overwrite is fair.
    }
}
