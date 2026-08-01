package com.larsons.engine.profile;

import com.larsons.engine.profile.FrameProfiler.Snapshot;
import com.larsons.engine.profile.FrameProfiler.Stage;
import com.larsons.engine.profile.FrameProfiler.Stats;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The profiler's on-screen readout — the frame budget drawn as a bar, with the
 * per-stage split beneath it.
 *
 * <p>Drawn by {@code Engine} after the scene, so it appears in every scene
 * without any scene knowing about it. Its own cost is timed into
 * {@link Stage#OVERLAY} and excluded from the reported figures, because a
 * readout that silently inflated the frame it was measuring would be worse
 * than no readout at all.
 *
 * <p>Kept deliberately cheap: one translucent panel, a handful of filled
 * rectangles and one line of text per stage. No per-frame allocation beyond
 * the formatted strings, and nothing that would itself become the bottleneck
 * on the weak hardware this is most useful on.
 */
public final class ProfileOverlay {

    private static final int PAD = 10;
    private static final int ROW = 15;
    private static final int WIDTH = 320;
    private static final int BAR_HEIGHT = 12;

    private static final Color PANEL = new Color(12, 14, 20, 225);
    private static final Color BORDER = new Color(90, 100, 125);
    private static final Color TEXT = new Color(226, 232, 240);
    private static final Color DIM = new Color(148, 163, 184);
    private static final Color BUDGET_BG = new Color(38, 44, 58);
    private static final Color OVER_BUDGET = new Color(239, 68, 68);

    /**
     * One colour per stage, reused by the bar and its label so the two read as
     * the same thing. Ordered to match {@link Stage}.
     */
    private static final Color[] STAGE_COLORS = {
            new Color(250, 204, 21),   // UPDATE  — simulation
            new Color(56, 189, 248),   // SCENE   — Graphics2D drawing (Job B)
            new Color(168, 85, 247),   // SHADERS — post-processing (Job A)
            new Color(52, 211, 153),   // PRESENT — blit and flip
            new Color(120, 130, 150),  // OVERLAY — this readout
            new Color(51, 65, 85),     // IDLE    — headroom
    };

    private static final Font TITLE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 12);
    private static final Font BODY_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 11);

    private ProfileOverlay() {}

    /** Draw the readout in the top-left corner. */
    public static void draw(Graphics2D g, Snapshot snapshot, DeviceProfile device,
                            double fps) {
        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int rows = snapshot.isEmpty() ? 1 : Stage.values().length + snapshot.passes().size();
        int extra = snapshot.passes().isEmpty() ? 0 : ROW; // "shader passes" heading
        int height = PAD * 2 + ROW * 2 + BAR_HEIGHT + 8 + rows * ROW + extra + ROW;

        g.setColor(PANEL);
        g.fillRoundRect(PAD, PAD, WIDTH, height, 8, 8);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(PAD, PAD, WIDTH, height, 8, 8);

        int x = PAD * 2;
        int y = PAD + ROW + 2;

        g.setFont(TITLE_FONT);
        g.setColor(TEXT);
        g.drawString("FRAME PROFILE  %.0f FPS".formatted(fps), x, y);
        y += ROW;

        g.setFont(BODY_FONT);
        if (snapshot.isEmpty()) {
            g.setColor(DIM);
            g.drawString("measuring...", x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, restore(oldAA));
            return;
        }

        // Budget bar: each stage's share of one frame's budget, in order, with
        // the unused remainder left dark. Overshoot is drawn red at the end.
        int barWidth = WIDTH - PAD * 2;
        g.setColor(BUDGET_BG);
        g.fillRect(x, y, barWidth, BAR_HEIGHT);

        double budget = snapshot.budgetMs();
        int drawn = 0;
        for (Stage stage : Stage.values()) {
            if (stage == Stage.IDLE) continue;
            double ms = snapshot.stage(stage).meanMs();
            int w = (int) Math.round(barWidth * Math.min(1.0, ms / budget));
            if (w <= 0) continue;
            w = Math.min(w, barWidth - drawn);
            if (w <= 0) break;
            g.setColor(STAGE_COLORS[stage.ordinal()]);
            g.fillRect(x + drawn, y, w, BAR_HEIGHT);
            drawn += w;
        }
        if (snapshot.workMsExcludingOverlay() > budget) {
            g.setColor(OVER_BUDGET);
            g.fillRect(x + barWidth - 3, y, 3, BAR_HEIGHT);
        }
        g.setColor(BORDER);
        g.drawRect(x, y, barWidth, BAR_HEIGHT);
        y += BAR_HEIGHT + ROW;

        g.setColor(DIM);
        g.drawString("%.2f/%.2f ms  headroom %.0f%%".formatted(
                snapshot.workMsExcludingOverlay(), budget, snapshot.headroomPct()), x, y);
        y += ROW + 4;

        for (Stage stage : Stage.values()) {
            Stats s = snapshot.stage(stage);
            g.setColor(STAGE_COLORS[stage.ordinal()]);
            g.fillRect(x, y - 8, 8, 8);
            g.setColor(stage == Stage.IDLE || stage == Stage.OVERLAY ? DIM : TEXT);
            g.drawString("%-8s %6.2f ms   p99 %6.2f".formatted(
                    s.name(), s.meanMs(), s.p99Ms()), x + 14, y);
            y += ROW;
        }

        if (!snapshot.passes().isEmpty()) {
            y += 4;
            g.setColor(DIM);
            g.drawString("shader passes", x, y);
            y += ROW;
            for (Stats pass : snapshot.passes()) {
                g.setColor(TEXT);
                g.drawString("  %-14s %6.2f ms".formatted(
                        truncate(pass.name(), 14), pass.meanMs()), x + 14, y);
                y += ROW;
            }
        }

        y += 4;
        g.setColor(DIM);
        g.drawString("%d cores  %s".formatted(device.cores(),
                device.isHiDpi() ? "HiDPI %.2gx".formatted(device.displayScale()) : "1x"),
                x, y);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, restore(oldAA));
    }

    private static Object restore(Object hint) {
        return hint == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : hint;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
