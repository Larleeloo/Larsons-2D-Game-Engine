package com.larsons.engine;

import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level's blocks as a list of layers — {@code HEIGHT_PLAN.md} V1.
 *
 * <p>A level used to hold two named grids, {@code tiles} and {@code upper},
 * because the plan views stacked exactly two deep and the second one could be
 * named after what it was for. It now holds a list, indexed by height, and this
 * is the contract of that list. <b>Nothing here is about height yet</b>: V1
 * changes where blocks are kept and changes no behaviour whatsoever, so the
 * ceiling asserted below is still two and a side-scroller's is still one.
 *
 * <p>The point of pinning a ceiling that the very next step raises is that
 * raising it should be a change to {@link Level#layerLimit()} and to this test,
 * and to nothing else. If V2 turns out to need edits anywhere but those two
 * places, then the storage is still deciding something it should not.
 */
@Timeout(60)
class LevelLayersTest {

    // --- one storage, reached one way -------------------------------------------

    /**
     * The floor's grid and layer zero are the same object, not two copies kept
     * in step. That is the whole reason the fields became a list: two names for
     * one grid is two things to keep in sync, and they were kept in sync by
     * hand in {@code resize}, in the undo snapshots and in the loader.
     */
    @Test
    void theFloorIsLayerZeroAndNotACopyOfIt() {
        Level lvl = Level.empty("layers", 8, 8, 32);
        assertNotNull(lvl.tiles());
        assertSame(lvl.tiles(), lvl.grid(Level.LAYER_GROUND),
                "the floor's grid is layer zero itself");

        lvl.setTile(3, 3, lvl.blocks.get("stone").id());
        assertEquals(lvl.tileAt(3, 3), lvl.grid(Level.LAYER_GROUND)[3][3],
                "a write through the accessor lands in that one grid");
    }

    /** A dense level has no chunk storage, and a giant one has no dense grids. */
    @Test
    void aLevelIsDenseOrChunkedAndNeverBoth() {
        Level dense = Level.empty("dense", 8, 8, 32);
        assertFalse(dense.isChunked());
        assertNotNull(dense.tiles());
        assertNull(dense.chunkedTiles(), "a dense level holds no chunks");

        Level giant = Level.emptyChunked("giant", 4096, 4096, 32, null);
        assertTrue(giant.isChunked());
        assertNotNull(giant.chunkedTiles());
        assertNull(giant.tiles(), "a chunked level holds no dense grid");
    }

    // --- layers cost nothing until something is put in them ----------------------

    /**
     * A level holds as many layers as something has been built in, and not one
     * more. A plan view is allowed to stack; it does not therefore pay for a
     * second grid the size of the level before anybody has stacked anything.
     */
    @Test
    void layersAreAllocatedOnlyWhenSomethingIsPutInThem() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("lazy", 8, 8, 32);
            lvl.setFormat(format);
            lvl.fillFloor(lvl.blocks.get("stone_path").id());
            assertEquals(1, lvl.layerCount(), format + ": a flat level is one layer");
            assertNull(lvl.grid(Level.LAYER_UPPER),
                    format + ": and holds no grid for a layer nothing is in");

            boolean stacked = lvl.setTile(4, 4, Level.LAYER_UPPER,
                    lvl.blocks.get("stone").id());
            assertEquals(lvl.layered(), stacked,
                    format + ": only a layered format accepts a stacked block");
            assertEquals(lvl.layered() ? 2 : 1, lvl.layerCount(),
                    format + ": the layer arrives with the block that needed it");
        }
    }

    /**
     * Clearing a layer nothing was ever built in allocates nothing. Writing
     * zero is how the editor rubs things out, and a rubbed-out cell in a level
     * with no second layer must not conjure one.
     */
    @Test
    void clearingALayerThatWasNeverBuiltInAllocatesNothing() {
        Level lvl = Level.empty("erase", 8, 8, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());

        assertFalse(lvl.setTile(4, 4, Level.LAYER_UPPER, 0),
                "there was nothing there to clear");
        assertEquals(1, lvl.layerCount());
        assertNull(lvl.grid(Level.LAYER_UPPER));
    }

    // --- the ceiling V2 raises ---------------------------------------------------

    /**
     * How tall a level may be, today: one layer edge-on, two on a plane.
     *
     * <p>Asserted here rather than left implicit because V2's whole diff should
     * be {@link Level#layerLimit()} and this method. The side-scroller's one is
     * not a placeholder — the screen <em>is</em> the vertical plane there, and a
     * second height axis on top of it is a contradiction rather than a feature.
     */
    @Test
    void theCeilingIsOneLayerEdgeOnAndTwoOnAPlane() {
        for (LevelFormat format : LevelFormat.values()) {
            Level lvl = Level.empty("ceiling", 8, 8, 32);
            lvl.setFormat(format);
            lvl.fillFloor(lvl.blocks.get("stone_path").id());
            int stone = lvl.blocks.get("stone").id();

            assertEquals(format.layered() ? 2 : 1, lvl.layerLimit(), format + ": the ceiling");
            assertFalse(lvl.setTile(4, 4, lvl.layerLimit(), stone),
                    format + ": a block above the ceiling is refused");
            assertFalse(lvl.setTile(4, 4, -1, stone),
                    format + ": and so is one below the floor");
            assertEquals(0, lvl.tileAt(4, 4, lvl.layerLimit()),
                    format + ": nothing landed up there");
        }
    }

    // --- the column is one thing -------------------------------------------------

    /**
     * Taking the floor out from under a column takes the column with it. This
     * was already true of the one stacked layer; it has to stay true of however
     * many there turn out to be, and it is the rule that keeps
     * {@link Level#stackHeight} readable as a contiguous run.
     */
    @Test
    void clearingTheFloorClearsTheWholeColumn() {
        Level lvl = Level.empty("column", 8, 8, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        lvl.setTile(4, 4, Level.LAYER_UPPER, lvl.blocks.get("stone").id());
        assertEquals(2, lvl.stackHeight(4, 4));

        lvl.setTile(4, 4, 0);
        assertEquals(0, lvl.stackHeight(4, 4), "the hole is a hole all the way up");
        assertEquals(0, lvl.tileAt(4, 4, Level.LAYER_UPPER),
                "the stacked block went with the floor it stood on");
    }

    // --- edits that re-cut the storage -------------------------------------------

    /** Resizing re-cuts every layer, not just the floor. */
    @Test
    void resizingKeepsEveryLayer() {
        Level lvl = Level.empty("resize", 16, 16, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(2, 2, Level.LAYER_UPPER, stone);

        lvl.resize(24, 24);
        assertEquals(2, lvl.layerCount(), "both layers survived the re-cut");
        assertEquals(stone, lvl.tileAt(2, 2, Level.LAYER_UPPER),
                "and the wall is still standing where it was");
    }

    /**
     * Growing a level past the dense limit turns it into chunks — and takes
     * every layer with it.
     *
     * <p>This one is worth its runtime. The conversion used to name the two
     * grids one at a time, so a third would have been dropped silently by a
     * size slider: the level would come back looking right and be missing its
     * walls, at the one size nobody tests at.
     */
    @Test
    void growingPastTheDenseLimitConvertsEveryLayerToChunks() {
        Level lvl = Level.empty("grow", 16, 16, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(2, 2, Level.LAYER_UPPER, stone);
        assertFalse(lvl.isChunked());

        int side = (int) Math.sqrt(Level.DENSE_TILE_LIMIT) + 64;
        lvl.resize(side, side);
        assertTrue(lvl.isChunked(), "past the dense limit the storage is chunks");
        assertEquals(2, lvl.layerCount(), "and it is still two layers deep");
        assertEquals(stone, lvl.tileAt(2, 2, Level.LAYER_UPPER),
                "the wall came across with the floor");
    }

    /** A play-test snapshot puts every layer back, including one mined away. */
    @Test
    void aTerrainSnapshotRestoresEveryLayer() {
        Level lvl = Level.empty("snapshot", 8, 8, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(4, 4, Level.LAYER_UPPER, stone);

        Object saved = lvl.snapshotTiles();
        lvl.setTile(4, 4, Level.LAYER_UPPER, 0);
        lvl.setTile(5, 5, 0);
        assertEquals(1, lvl.stackHeight(4, 4));

        lvl.restoreTiles(saved);
        assertEquals(stone, lvl.tileAt(4, 4, Level.LAYER_UPPER), "the wall is back");
        assertTrue(lvl.tileAt(5, 5) > 0, "and so is the floor that was mined out");
    }

    /** A bounds snapshot — what undo puts back after a resize — does too. */
    @Test
    void aBoundsSnapshotRestoresEveryLayer() {
        Level lvl = Level.empty("bounds", 16, 16, 32);
        lvl.setFormat(LevelFormat.TOP_DOWN);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(2, 2, Level.LAYER_UPPER, stone);

        Level.Bounds saved = lvl.snapshotBounds();
        lvl.resize(8, 8);
        lvl.restoreBounds(saved);

        assertEquals(16, lvl.width);
        assertEquals(2, lvl.layerCount());
        assertEquals(stone, lvl.tileAt(2, 2, Level.LAYER_UPPER),
                "undoing a resize brings the walls back with the bounds");
    }
}
