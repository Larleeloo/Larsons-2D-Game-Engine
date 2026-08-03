package com.larsons.engine.render;

import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.render.GoldenFrames.Frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The draw-call table, measured rather than remembered.
 *
 * <p><b>Why an instrument and not a note in a commit message.</b> B5's whole
 * claim is a number — the merge ratio, before and after — and the two halves of
 * that number have to come from the same frames or the comparison means
 * nothing. Recording both here, in one process, over
 * {@link SceneFrames#allFrames()}, removes every way the two could drift: same
 * build, same seeds, same clock, same scenes, and the only difference between
 * the passes is {@link SpriteAtlas#setRouting} — which is exactly why that
 * switch exists.
 *
 * <p>Each frame is drawn three times. The first is thrown away: it is the pass
 * that bakes the sprites and registers them, and a frame that baked during
 * measurement would be measuring the load, not the render. The second and third
 * are the two halves of the answer.
 */
public final class DrawCallReport {

    private DrawCallReport() {}

    /** One frame's before and after. */
    public record Row(String frame, DrawStats before, DrawStats after) {

        /** How much better the merge ratio got, as a multiple. */
        public double gain() {
            double was = before.mergeRatio();
            return was == 0 ? 1 : after.mergeRatio() / was;
        }

        /** Did the atlas change anything at all here? */
        public boolean moved() {
            return before.batches() != after.batches();
        }
    }

    /** Measure every frame in the catalogue, painters and scenes alike. */
    public static List<Row> measure() {
        return measure(SceneFrames.allFrames());
    }

    /** Measure the given frames, leaving the atlas's routing as it was found. */
    public static List<Row> measure(List<Frame> frames) {
        boolean routing = SpriteAtlas.routing();
        List<Row> rows = new ArrayList<>();
        try {
            for (Frame frame : frames) {
                SpriteAtlas.setRouting(true);
                GoldenFrames.record(frame);            // bake and register; discarded

                SpriteAtlas.setRouting(false);
                DrawStats before = GoldenFrames.record(frame).stats().copy();
                SpriteAtlas.setRouting(true);
                DrawStats after = GoldenFrames.record(frame).stats().copy();

                rows.add(new Row(frame.name(), before, after));
            }
        } finally {
            SpriteAtlas.setRouting(routing);
        }
        return rows;
    }

    /**
     * The table as it appears in {@code RENDER_PLAN.md}, sorted by how much the
     * atlas moved each frame — so the rows that justify the step are at the top
     * and the rows it could not help are visible rather than omitted.
     */
    public static String markdown(List<Row> rows) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingDouble(Row::gain).reversed()
                .thenComparing(Row::frame));

        StringBuilder sb = new StringBuilder();
        sb.append("| frame | ops | batches before | batches after | merge before | ")
                .append("merge after | images |\n");
        sb.append("|-------|----:|---------------:|--------------:|-------------:|")
                .append("-----------:|-------:|\n");
        for (Row row : sorted) {
            sb.append("| %s | %d | %d | %d | %.2f× | %.2f× | %d |%n".formatted(
                    row.frame(), row.after().operations(),
                    row.before().batches(), row.after().batches(),
                    row.before().mergeRatio(), row.after().mergeRatio(),
                    row.after().images()));
        }
        return sb.toString();
    }

    /** One line summarising the whole catalogue, which is what B10 compares. */
    public static String summary(List<Row> rows) {
        int ops = 0, before = 0, after = 0, moved = 0;
        for (Row row : rows) {
            ops += row.after().operations();
            before += row.before().batches();
            after += row.after().batches();
            if (row.moved()) moved++;
        }
        return ("%d frames, %d operations: %d batches -> %d batches "
                + "(%.2f× -> %.2f× overall); %d frames changed")
                .formatted(rows.size(), ops, before, after,
                        ops / (double) before, ops / (double) after, moved);
    }
}
