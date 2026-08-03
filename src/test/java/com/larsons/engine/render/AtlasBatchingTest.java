package com.larsons.engine.render;

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
 * What the sprite atlas actually bought, over the same frames the pixel
 * comparison uses.
 *
 * <p><b>Why this is a test and not a one-off measurement.</b> The merge ratio
 * is the number the GPU case rests on, and it is the kind of number that rots:
 * a factory that stops registering, a painter that starts handing the target a
 * freshly-sliced sub-image every frame, and the ratio quietly returns to where
 * it was while everything still renders correctly and every other test stays
 * green. This asserts the win is still there on every build, and writes the
 * table so B6 and B10 can compare against something that was measured rather
 * than remembered.
 */
class AtlasBatchingTest {

    /** Where the measured table lands, for the plan's before-and-after. */
    private static final Path REPORT = Path.of("build", "reports", "draw-calls.md");

    @Test
    void theAtlasCollapsesRunsOfSpritesAndNeverMakesAFrameWorse() {
        List<Row> rows = DrawCallReport.measure();
        write(rows);

        StringBuilder worse = new StringBuilder();
        for (Row row : rows) {
            if (row.after().batches() > row.before().batches()) {
                worse.append("\n  ").append(row.frame())
                        .append(": ").append(row.before().batches())
                        .append(" -> ").append(row.after().batches()).append(" batches");
            }
            assertEquals(row.before().operations(), row.after().operations(),
                    row.frame() + " draws a different number of operations through the atlas — "
                            + "atlasing changes which texture a draw comes from, never how many "
                            + "draws there are");
        }
        assertEquals("", worse.toString(),
                "the atlas cost these frames batches instead of saving them:" + worse);

        long improved = rows.stream().filter(Row::moved).count();
        assertTrue(improved >= 5,
                "only " + improved + " of " + rows.size() + " frames changed at all — the "
                        + "factories have stopped registering with the atlas, or the backend "
                        + "has stopped resolving through it. " + DrawCallReport.summary(rows));
    }

    /**
     * The frame the step was aimed at. {@code auto-sprites} draws six unit
     * figures back to back with nothing between them, which is the exact shape
     * an atlas collapses: six textures and six draw calls before, one after.
     * Naming it here means a regression says which frame stopped batching
     * rather than "the average moved".
     */
    @Test
    void aRowOfDistinctSpritesIsOneDrawCall() {
        Row row = one("auto-sprites");
        assertEquals(1, countedBatchesForImages(row),
                "six unit figures in a row should be one bind, not six. " + row);
    }

    /** The generated-art frames the world draws, which have sprites in them too. */
    @Test
    void theWorldFramesBatchTheirSpritesToo() {
        for (String name : List.of("world-side-scroll", "world-top-down", "world-isometric")) {
            Row row = one(name);
            assertTrue(row.after().batches() <= row.before().batches(),
                    name + " got worse: " + row);
        }
    }

    /**
     * And the negative control the plan asked for in as many words:
     * {@code scene-board-customize} is 149 flat shapes in a board preview with
     * no sprites in it at all. An atlas that appeared to improve <em>that</em>
     * would be measuring itself.
     */
    @Test
    void aFrameWithNoSpritesIsUntouched() {
        Row row = one("scene-board-customize");
        assertEquals(row.before().batches(), row.after().batches(),
                "a frame of flat geometry cannot be helped by a sprite atlas, so a "
                        + "change here means the measurement is measuring something else: "
                        + row);
    }

    /** How many distinct texture binds the frame's image draws needed. */
    private static int countedBatchesForImages(Row row) {
        // Batches attributable to images: the whole frame's batch count minus
        // what it would be with no images at all is not something DrawStats can
        // answer, so this asks the narrower question the assertion needs — how
        // many batches the frame lost by atlasing, given every image in it is
        // atlased and they are drawn consecutively.
        return row.after().batches() - (row.before().batches() - row.before().images());
    }

    private static Row one(String frame) {
        List<Row> rows = DrawCallReport.measure(
                SceneFrames.allFrames().stream().filter(f -> f.name().equals(frame)).toList());
        assertEquals(1, rows.size(), "no frame named '" + frame + "' in the catalogue");
        return rows.get(0);
    }

    /**
     * The atlas's own state, printed with the table: a page count and an
     * occupancy is how a future step tells "the sprites are batching" from
     * "the sprites stopped being packed".
     */
    private static void write(List<Row> rows) {
        try {
            Files.createDirectories(REPORT.getParent());
            String text = "# Draw calls, with and without the sprite atlas\n\n"
                    + DrawCallReport.summary(rows) + "\n\n"
                    + DrawCallReport.markdown(rows) + "\n"
                    + "Atlas: " + SpriteAtlas.shared() + "\n";
            Files.writeString(REPORT, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
