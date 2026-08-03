package com.larsons.engine.render;

import com.larsons.engine.graphics.atlas.GlyphAtlas;
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

    /**
     * What the sprite atlas alone buys: sprite routing off then on, with glyph
     * routing off throughout.
     *
     * <p>The second half of that sentence is the point. Both atlases pack into
     * the same pages by B6, so leaving glyphs on would move both halves of this
     * comparison and the number would stop being a statement about B5 — it
     * would be a statement about whichever step happened to run last. One
     * variable at a time is the only way a table published in one step survives
     * being read in another.
     */
    public static List<Row> measure(List<Frame> frames) {
        return measure(frames, false, false, true, false);
    }

    /**
     * What the glyph atlas buys on top of it: glyph routing off then on, with
     * the sprite atlas on throughout, which is the configuration that ships.
     */
    public static List<Row> measureGlyphs(List<Frame> frames) {
        return measure(frames, true, false, true, true);
    }

    /** Measure the given frames, leaving both routing switches as they were found. */
    private static List<Row> measure(List<Frame> frames,
                                     boolean spritesBefore, boolean glyphsBefore,
                                     boolean spritesAfter, boolean glyphsAfter) {
        boolean sprites = SpriteAtlas.routing();
        boolean glyphs = GlyphAtlas.routing();
        List<Row> rows = new ArrayList<>();
        try {
            for (Frame frame : frames) {
                // Bake, register and rasterise, then throw the result away: a
                // frame that populated an atlas during measurement would be
                // measuring the load rather than the render.
                SpriteAtlas.setRouting(true);
                GlyphAtlas.setRouting(true);
                GoldenFrames.record(frame);

                SpriteAtlas.setRouting(spritesBefore);
                GlyphAtlas.setRouting(glyphsBefore);
                DrawStats before = GoldenFrames.record(frame).stats().copy();

                SpriteAtlas.setRouting(spritesAfter);
                GlyphAtlas.setRouting(glyphsAfter);
                DrawStats after = GoldenFrames.record(frame).stats().copy();

                rows.add(new Row(frame.name(), before, after));
            }
        } finally {
            SpriteAtlas.setRouting(sprites);
            GlyphAtlas.setRouting(glyphs);
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
                .append("merge after | images | text |\n");
        sb.append("|-------|----:|---------------:|--------------:|-------------:|")
                .append("-----------:|-------:|-----:|\n");
        for (Row row : sorted) {
            sb.append("| %s | %d | %d | %d | %.2f× | %.2f× | %d | %d |%n".formatted(
                    row.frame(), row.after().operations(),
                    row.before().batches(), row.after().batches(),
                    row.before().mergeRatio(), row.after().mergeRatio(),
                    row.after().images(), row.after().glyphs()));
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
