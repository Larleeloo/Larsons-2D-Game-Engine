package com.larsons.engine;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the cursor is pointing at, once the terrain has height —
 * {@code HEIGHT_PLAN.md} R7.
 *
 * <p>{@link Camera#screenToWorld} answers "which point of the <em>floor</em> is
 * under this pixel", which was the whole question while the floor was all
 * there was. A tower changes that: the pixels showing its side face are pixels
 * whose floor point is the cell <em>behind</em> the tower, so every one of the
 * sixteen scene sites that turns a mouse position into a cell — mining,
 * placing, opening a chest, aiming — would act on a block one or more cells
 * away from the one under the mouse, and the taller the tower the further off.
 *
 * <p>The tests below are written the way the defect appears: point at a known
 * face of a known column and demand that column back.
 */
@Timeout(60)
class HeightPickTest {

    private static final int TILE = 32;
    private static final int CANVAS = 480;

    /**
     * Pointing anywhere on a tower resolves to the tower — including its side,
     * which is the half that used to resolve to the cell behind it.
     */
    @Test
    void pointingAtATowerHitsTheTowerAndNotTheCellBehindIt() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            for (int eighth = 0; eighth < 8; eighth++) {
                Level lvl = floored(format);
                int stone = lvl.blocks.get("stone").id();
                for (int layer = 1; layer <= 3; layer++) lvl.setTile(10, 10, layer, stone);
                Camera cam = camera(lvl, eighth);

                int lift = TerrainPainter.liftPixels(cam, TILE);
                int[] base = project(cam, 10.5 * TILE, 10.5 * TILE);

                // The top face: the cell's centre, raised the column's height.
                TerrainPainter.Aim top = TerrainPainter.pick(cam, lvl,
                        base[0], base[1] - 3 * lift);
                assertNotNull(top, format + " @" + eighth + ": the top face hits something");
                assertEquals(10, top.col(), format + " @" + eighth + ": top face column");
                assertEquals(10, top.row(), format + " @" + eighth + ": top face row");
                assertEquals(3, top.layer(), format + " @" + eighth + ": the column's top layer");
                assertTrue(top.top(), format + " @" + eighth + ": and it is the top face");

                // Halfway down the side: still the same column, and not a top.
                TerrainPainter.Aim side = TerrainPainter.pick(cam, lvl,
                        base[0], base[1] - lift);
                assertNotNull(side, format + " @" + eighth + ": the side face hits something");
                assertEquals(10, side.col(), format + " @" + eighth
                        + ": pointing at the side resolves to the tower, not behind it");
                assertEquals(10, side.row(), format + " @" + eighth + ": side face row");
            }
        }
    }

    /** Floor beside a tower is still floor, and answers layer zero. */
    @Test
    void pointingAtBareFloorHitsTheFloor() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = floored(format);
            int stone = lvl.blocks.get("stone").id();
            for (int layer = 1; layer <= 3; layer++) lvl.setTile(10, 10, layer, stone);
            Camera cam = camera(lvl, 0);

            int[] at = project(cam, 6.5 * TILE, 6.5 * TILE);
            TerrainPainter.Aim aim = TerrainPainter.pick(cam, lvl, at[0], at[1]);
            assertNotNull(aim);
            assertEquals(6, aim.col());
            assertEquals(6, aim.row());
            assertEquals(0, aim.layer(), "a bare floor tile is layer zero");
            assertTrue(aim.top(), "and you are looking at its top");
        }
    }

    /**
     * A side-scroller picks the cell under the cursor and nothing else — it has
     * no height axis for a ray to march along.
     */
    @Test
    void aSideScrollerPicksTheCellUnderTheCursor() {
        Level lvl = Level.empty("side", 20, 20, TILE);
        lvl.setFormat(LevelFormat.SIDE_SCROLLER);
        lvl.setTile(8, 8, lvl.blocks.get("stone").id());
        Camera cam = camera(lvl, 0);

        int[] at = project(cam, 8.5 * TILE, 8.5 * TILE);
        TerrainPainter.Aim aim = TerrainPainter.pick(cam, lvl, at[0], at[1]);
        assertNotNull(aim);
        assertEquals(8, aim.col());
        assertEquals(8, aim.row());
        assertEquals(0, aim.layer());
    }

    /** Off the level is a miss rather than a cell clamped to the edge. */
    @Test
    void pointingOffTheLevelHitsNothing() {
        Level lvl = floored(LevelFormat.TOP_DOWN);
        Camera cam = camera(lvl, 0);
        // Far outside a 20x20 level, in a direction the march cannot recover.
        assertEquals(null, TerrainPainter.pick(cam, lvl, -4000, -4000));
    }

    // --- helpers -----------------------------------------------------------------

    private static Level floored(LevelFormat format) {
        Level lvl = Level.empty("pick", 20, 20, TILE);
        lvl.setFormat(format);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        return lvl;
    }

    private static Camera camera(Level lvl, int eighth) {
        Camera cam = new Camera(lvl.perspective, CANVAS, CANVAS);
        cam.tileSize = lvl.tileSize;
        cam.setYaw(eighth * Camera.EIGHTH_TURN);
        cam.centerOn(10.5 * TILE, 10.5 * TILE);
        return cam;
    }

    private static int[] project(Camera cam, double wx, double wy) {
        int[] out = new int[2];
        cam.worldToScreen(wx, wy, out);
        return out;
    }
}
