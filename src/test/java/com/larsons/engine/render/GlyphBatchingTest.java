package com.larsons.engine.render;

import com.larsons.engine.graphics.atlas.GlyphAtlas;
import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.render.DrawCallReport.Row;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the glyph atlas bought, over the frames the pixel comparison uses.
 *
 * <p>{@code AtlasBatchingTest} is the same instrument pointed at B5's switch;
 * this one points it at B6's, with the sprite atlas on throughout — which is
 * the configuration that ships, and the only one in which the question "does
 * putting glyphs on the sprites' pages help" has a meaning.
 *
 * <p><b>Why the assertions are named frames rather than an average.</b> B3
 * predicted a win in two frames and B5 measured none in either, because the
 * prediction was about images being atlased and the reality was about images
 * being adjacent. An average would have hidden that in both directions. So the
 * frames this step is supposed to move are named, the frame it must not move is
 * named, and a regression says which one stopped rather than that the mean
 * drifted.
 */
class GlyphBatchingTest {

    /** Where the measured table lands, for the plan's before-and-after. */
    private static final Path REPORT = Path.of("build", "reports", "glyph-batching.md");

    @Test
    void theGlyphAtlasCollapsesTextIntoTheSpriteBatchesAndNeverMakesAFrameWorse() {
        List<Row> rows = DrawCallReport.measureGlyphs(SceneFrames.allFrames());
        write(rows);

        StringBuilder worse = new StringBuilder();
        for (Row row : rows) {
            if (row.after().batches() > row.before().batches()) {
                worse.append("\n  ").append(row.frame())
                        .append(": ").append(row.before().batches())
                        .append(" -> ").append(row.after().batches()).append(" batches");
            }
            assertEquals(row.before().operations(), row.after().operations(),
                    row.frame() + " draws a different number of operations through the glyph "
                            + "atlas — a run of text is one operation whether it is rasterised "
                            + "by the font system or blitted from a page, and counting the "
                            + "glyphs individually would inflate every merge ratio in the table");
        }
        assertEquals("", worse.toString(),
                "the glyph atlas cost these frames batches instead of saving them:" + worse);

        long improved = rows.stream().filter(Row::moved).count();
        assertTrue(improved >= 20,
                "only " + improved + " of " + rows.size() + " frames changed at all — text has "
                        + "stopped resolving through the atlas. " + DrawCallReport.summary(rows));
    }

    /**
     * The frame B5 named as the one its own step could not reach, and the
     * reason this step exists in the shape it does.
     *
     * <p>{@code crafting-panel} is twelve icons and fifteen labels interleaved
     * one for one. Atlasing the sprites left it at 1.09× because an atlas
     * merges neighbours and every icon's neighbour is a label. It only moves if
     * the glyphs are on the icons' own page, which is what B6 does — so if this
     * assertion ever fails, the two atlases have drifted apart and the step has
     * silently undone itself.
     */
    @Test
    void theInterleavedIconAndLabelFrameFinallyMerges() {
        Row row = one("crafting-panel");
        assertTrue(row.after().batches() < row.before().batches() / 2,
                "icons and labels share a page now, so an icon-label row is one batch: " + row);
    }

    /** The volume case: seventy operations, thirty-nine of them text runs. */
    @Test
    void theTextHeaviestSceneBatches() {
        Row row = one("scene-auto-battler-guide");
        assertTrue(row.after().mergeRatio() > row.before().mergeRatio() * 1.5,
                "the frame with the most text in the catalogue barely moved: " + row);
    }

    /**
     * The negative control, and it is a different frame from B5's on purpose:
     * {@code particles} is sixty-two flat shapes with no text and no sprites at
     * all. A glyph atlas that appeared to improve <em>that</em> would be
     * measuring itself rather than the change.
     */
    @Test
    void aFrameWithNoTextIsUntouched() {
        Row row = one("particles");
        assertEquals(0, row.after().glyphs(), "the control frame has grown some text");
        assertEquals(row.before().batches(), row.after().batches(),
                "a frame of flat geometry cannot be helped by a glyph atlas: " + row);
    }

    /**
     * The atlas has to stay on one page, or the step undoes itself quietly.
     *
     * <p>Everything above rests on glyphs and sprites sharing a texture. They
     * only do while they fit: spill onto a second page and an icon-label row is
     * two binds again, every assertion here still passes because each half is
     * internally consistent, and the merge ratio silently returns to where B5
     * left it. Colour-keyed cells are what make that plausible — a UI that grew
     * forty text colours would multiply the glyph count by forty — so the page
     * count is asserted rather than assumed.
     */
    @Test
    void everythingTheEngineDrawsStillFitsOnOnePage() {
        DrawCallReport.measureGlyphs(SceneFrames.allFrames());
        assertEquals(1, SpriteAtlas.shared().pages().size(),
                "sprites and glyphs have spilled onto a second page, so an icon "
                        + "followed by a label is two texture binds again: "
                        + SpriteAtlas.shared() + "; " + GlyphAtlas.shared());
    }

    private static Row one(String frame) {
        List<Row> rows = DrawCallReport.measureGlyphs(
                SceneFrames.allFrames().stream().filter(f -> f.name().equals(frame)).toList());
        assertEquals(1, rows.size(), "no frame named '" + frame + "' in the catalogue");
        return rows.get(0);
    }

    private static void write(List<Row> rows) {
        try {
            Files.createDirectories(REPORT.getParent());
            String text = "# Draw calls, with and without the glyph atlas\n\n"
                    + "Sprite atlas on throughout; the only variable is "
                    + "`-Dlarsons.render.glyphs`.\n\n"
                    + DrawCallReport.summary(rows) + "\n\n"
                    + DrawCallReport.markdown(rows) + "\n"
                    + "Sprites: " + SpriteAtlas.shared() + "\n\n"
                    + "Glyphs: " + GlyphAtlas.shared() + "\n";
            Files.writeString(REPORT, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
