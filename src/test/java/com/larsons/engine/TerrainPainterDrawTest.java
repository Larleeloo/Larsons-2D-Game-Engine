package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain painter, asserted on as a list of drawing operations rather than
 * as pixels — the thing {@link RecordingTarget} exists to make possible.
 *
 * <p>These are the properties that break during a port and that pixel tests
 * catch only by accident: that one tile is one draw, that the shadows of a
 * frame are filled as a single region, that a plan view queues its walls into
 * the depth pass instead of painting them flat.
 */
class TerrainPainterDrawTest {

    private static final int TILE = 32;

    /** A small level of one solid block type, optionally stacked in one cell. */
    private static Level level(LevelFormat format, boolean stackOne) {
        Level lvl = Level.empty("t", 4, 4, TILE);
        lvl.setFormat(format);
        Block solid = lvl.blocks.all().stream()
                .filter(b -> !b.liquid())
                .findFirst().orElseThrow();
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, solid.id());
        }
        if (stackOne) lvl.setTile(1, 1, Level.LAYER_UPPER, solid.id());
        return lvl;
    }

    private static Camera camera(Level lvl) {
        Camera cam = new Camera(lvl.perspective, 400, 300);
        cam.tileSize = lvl.tileSize;
        cam.centerOn(lvl.width * TILE / 2.0, lvl.height * TILE / 2.0);
        return cam;
    }

    /** Paint a whole level into a recorder, flushing the depth pass. */
    private static RecordingTarget paint(Level lvl, TerrainPainter.Mining mining) {
        return paint(lvl, mining, 0);
    }

    private static RecordingTarget paint(Level lvl, TerrainPainter.Mining mining, double yaw) {
        RecordingTarget target = new RecordingTarget(400, 300);
        DepthPass pass = DepthPass.of(lvl.perspective);
        Camera cam = camera(lvl);
        cam.setYaw(yaw);
        TerrainPainter.draw(target, lvl, cam, new int[]{0, 0, lvl.width - 1, lvl.height - 1},
                0.0, pass, null, mining);
        pass.flush();
        return target;
    }

    @Test
    void everyFloorTileIsOneFillAndOneOutline() {
        // Sixteen untextured cells: a filled quad and its darker edge each.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        RecordingTarget target = paint(lvl, null);

        assertEquals(16, target.count("fillPolygon"), "one fill per cell");
        assertEquals(16, target.count("drawPolygon"), "one outline per cell");
    }

    @Test
    void aSideScrollerDrawsNoShadowsAndNoWalls() {
        // Only the plan views stack blocks, so a side view has nothing to lift
        // and nothing to cast.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        RecordingTarget target = paint(lvl, null);

        assertEquals(0, target.count("fillShape"), "no shadow pass in a side view");
    }

    @Test
    void everyShadowInAFrameIsFilledAsOneRegion() {
        // Filling them separately would stack translucent black where two
        // overlap and band the floor — the reason fillShape exists at all.
        Level lvl = level(LevelFormat.THREE_D, false);
        Block solid = lvl.blocks.all().stream().filter(b -> !b.liquid()).findFirst().orElseThrow();
        lvl.setTile(1, 1, Level.LAYER_UPPER, solid.id());
        lvl.setTile(2, 1, Level.LAYER_UPPER, solid.id());
        lvl.setTile(1, 2, Level.LAYER_UPPER, solid.id());

        RecordingTarget target = paint(lvl, null);

        assertEquals(1, target.count("fillShape"),
                "three casters, one fill — not three overlapping fills");
    }

    @Test
    void aStackedBlockDrawsItsSideFaceAndItsLiftedTop() {
        Level lvl = level(LevelFormat.THREE_D, true);
        RecordingTarget flat = paint(level(LevelFormat.THREE_D, false), null);
        RecordingTarget stacked = paint(lvl, null);

        // The raised block adds a side face and a top, each a fill plus an
        // outline, on top of what the bare floor drew.
        assertEquals(flat.count("fillPolygon") + 2, stacked.count("fillPolygon"),
                "one side face and one top");
        assertEquals(flat.count("drawPolygon") + 2, stacked.count("drawPolygon"));
    }

    @Test
    void aTurnedStackShowsTwoSideFacesNotOne() {
        // Square to the world a block shows one face, its southern one. Turned
        // an eighth, two of its lower edges come round toward the viewer and
        // both have to be drawn — which is the case the face-selection code
        // exists for, and the one an unturned camera never exercises.
        RecordingTarget square = paint(level(LevelFormat.THREE_D, true), null);
        RecordingTarget squareFlat = paint(level(LevelFormat.THREE_D, false), null);
        RecordingTarget turned = paint(level(LevelFormat.THREE_D, true), null,
                Camera.EIGHTH_TURN);
        RecordingTarget turnedFlat = paint(level(LevelFormat.THREE_D, false), null,
                Camera.EIGHTH_TURN);

        assertEquals(2, square.count("fillPolygon") - squareFlat.count("fillPolygon"),
                "one side face plus the top");
        assertEquals(3, turned.count("fillPolygon") - turnedFlat.count("fillPolygon"),
                "two side faces plus the top");
    }

    @Test
    void miningCracksAreDrawnAsLinesAtTheMinedCell() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        RecordingTarget without = paint(lvl, null);
        RecordingTarget with = paint(lvl, new TerrainPainter.Mining(1, 1, 0.9));

        assertTrue(with.count("drawLine") > without.count("drawLine"),
                "a stroke in progress adds crack lines");
    }

    @Test
    void aStrokeThatHasBarelyStartedDrawsNothing() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        RecordingTarget none = paint(lvl, null);
        RecordingTarget barely = paint(lvl, new TerrainPainter.Mining(1, 1, 0.001));

        assertEquals(none.ops(), barely.ops(), "below the threshold, nothing is drawn");
    }

    @Test
    void emptyCellsAreSkippedEntirely() {
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        lvl.setTile(0, 0, 0);
        lvl.setTile(1, 0, 0);

        assertEquals(14, paint(lvl, null).count("fillPolygon"),
                "two cleared cells draw nothing at all");
    }

    @Test
    void theTerrainPassBatchesWell() {
        // Untextured terrain is solid geometry throughout, which is the best
        // case for a batching backend: it should collapse to very few draws.
        Level lvl = level(LevelFormat.SIDE_SCROLLER, false);
        RecordingTarget target = paint(lvl, null);

        assertTrue(target.stats().mergeRatio() > 4,
                "solid terrain should merge heavily, got " + target.stats());
        assertEquals(0, target.stats().stateChanges(),
                "the terrain pass pushes no scoped state");
    }

    @Test
    void aWrappedGraphics2DStillReachesTheSamePainter() {
        // B4 deleted the Graphics2D overload; a caller that has one — a bake,
        // or a test like this — wraps it and gets the same full terrain pass.
        Level lvl = level(LevelFormat.THREE_D, true);
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(400, 300,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        DepthPass pass = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(com.larsons.engine.graphics.draw.Java2DTarget.unsized(g),
                lvl, camera(lvl),
                new int[]{0, 0, lvl.width - 1, lvl.height - 1}, 0.0, pass, null, null);
        pass.flush();
        g.dispose();

        boolean drewSomething = false;
        for (int y = 0; y < 300 && !drewSomething; y++) {
            for (int x = 0; x < 400; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) { drewSomething = true; break; }
            }
        }
        assertTrue(drewSomething, "the Graphics2D overload must still draw terrain");
    }

    @Test
    void theRecordedOrderPutsFloorBeforeWalls() {
        // The floor is the ground everything stands on; a wall queued into the
        // depth pass must land after it, never under it.
        Level lvl = level(LevelFormat.THREE_D, true);
        RecordingTarget target = paint(lvl, null);
        List<String> ops = target.ops();

        int firstFill = ops.indexOf("fillPolygon");
        int shadow = ops.indexOf("fillShape");
        assertTrue(firstFill >= 0 && shadow > firstFill,
                "floor tiles are laid before the shadows the walls cast");
    }
}
