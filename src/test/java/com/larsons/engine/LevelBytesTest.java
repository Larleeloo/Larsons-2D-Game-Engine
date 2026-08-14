package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.ChunkedTiles;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.SurfaceDecor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a saved level is made of, asserted as bytes and as keys.
 *
 * <p><b>Why this exists, and why it exists now.</b> The engine has thirty-two
 * golden frames pinning what a level <em>looks</em> like and not one assertion
 * about what a level <em>file</em> looks like. That gap does not matter while
 * the save format only ever gains optional keys by hand; it matters a great
 * deal to {@code HEIGHT_PLAN.md} V3, which widens the format to carry a stack
 * of any depth and claims in exchange that <b>a level using two layers or fewer
 * writes exactly the bytes this build writes today</b>. A claim like that needs
 * an instrument that would fail if it were false, and the plan's rule (V0) is
 * that the instrument goes in <em>before</em> the change it guards — a guard
 * added afterwards is a guard that has never seen the failure it exists for.
 *
 * <p>So everything here passes on the commit that introduced it. That is the
 * point of it, and a reviewer should check exactly that before trusting the
 * rest of the job.
 *
 * <p><b>Byte-identity is asserted against the fixed point, not against the
 * file.</b> The bundled level is hand-written: it spells its grid out as rows
 * of numbers, names a palette, and omits half of what the writer emits. Saving
 * it necessarily rewrites it into the run-length form, so "load it and save it
 * and compare to the file" would fail today for reasons that have nothing to do
 * with anything. What can be demanded — and what actually catches a reader and
 * a writer that disagree — is that the <em>second</em> save equals the first,
 * byte for byte: one pass normalises, and every pass after it is a no-op. A
 * format that loses a field, reorders a map, or re-encodes a number differently
 * on the way through fails that, and it fails it on the first save that follows
 * the defect rather than on some later day when a player's level will not load.
 */
@Timeout(60)
class LevelBytesTest {

    // --- the fixed point ---------------------------------------------------------

    /**
     * The level that ships with the engine, which is also the oldest shape the
     * reader still has to understand — a legacy row-of-arrays grid in palette
     * mode, written before formats, settings, stacks or headings existed.
     */
    @Test
    void theBundledLevelReachesAFixedPointInOnePass() {
        assertFixedPoint(LevelLoader.load("levels/sample_level.json"),
                "levels/sample_level.json");
    }

    /** Every format, carrying one of everything the writer knows how to write. */
    @Test
    void everyFormatReachesAFixedPointInOnePass() {
        for (LevelFormat format : LevelFormat.values()) {
            assertFixedPoint(furnished(format), format.toString());
        }
    }

    /**
     * A giant level, whose grid is chunks rather than rows and whose floor is
     * rebuilt from a generator seed instead of being stored at all.
     */
    @Test
    void aChunkedLevelReachesAFixedPointInOnePass() {
        assertFixedPoint(giant(), "chunked");
    }

    /**
     * The bytes are not the point on their own — a format that dropped both
     * layers would be perfectly stable and perfectly useless. Every cell of
     * every layer survives the trip.
     */
    @Test
    void theRoundTripPreservesEveryCellOfEveryLayer() {
        for (LevelFormat format : LevelFormat.values()) {
            Level before = furnished(format);
            Level after = LevelLoader.parse(before.toJson());
            for (int r = 0; r < before.height; r++) {
                for (int c = 0; c < before.width; c++) {
                    assertEquals(before.tileAt(c, r), after.tileAt(c, r),
                            format + ": floor at (" + c + "," + r + ")");
                    assertEquals(before.tileAt(c, r, Level.LAYER_UPPER), after.tileAt(c, r, Level.LAYER_UPPER),
                            format + ": stack at (" + c + "," + r + ")");
                }
            }
            assertEquals(before.stackHeight(4, 4), after.stackHeight(4, 4),
                    format + ": the height of a stacked cell");
        }
    }

    // --- the churn guard ---------------------------------------------------------

    /**
     * The keys a two-layer level writes, listed exactly.
     *
     * <p>This is the assertion V3 is measured against. Widening the format is
     * allowed to add keys to levels that <em>use</em> the extra depth; it is not
     * allowed to add so much as an empty array to a level that does not, because
     * every level anyone has saved is a level that does not, and a format that
     * rewrites all of them on load has broken the plan's third invariant in both
     * directions at once.
     *
     * <p>The list is spelled out rather than derived so that adding a key is a
     * decision someone makes in this file, on purpose, with this comment in
     * front of them.
     */
    @Test
    void aTwoLayerLevelWritesExactlyTheKeysItWritesToday() {
        Set<String> sideScroller = keysOf(furnished(LevelFormat.SIDE_SCROLLER));
        assertEquals(new LinkedHashSet<>(List.of(
                        "name", "format", "perspective", "tileSize", "width", "height",
                        "background", "settings", "tileset", "spawn", "tilesRle",
                        "surface", "entities")),
                sideScroller,
                "the side-scroller's saved keys");

        Set<String> planView = keysOf(furnished(LevelFormat.TOP_DOWN));
        assertEquals(new LinkedHashSet<>(List.of(
                        "name", "format", "perspective", "tileSize", "width", "height",
                        "background", "settings", "tileset", "spawn", "tilesRle",
                        "upperRle", "surface", "entities")),
                planView,
                "a plan view's saved keys — the stacked layer and nothing else");
    }

    /**
     * The stacked layer is written when there is one and absent when there is
     * not, and its absence is load-bearing: {@code LevelLoader} tells a level
     * written before blocks stacked from a deliberately flat one by whether the
     * key is there at all, and converts the first kind and not the second.
     */
    @Test
    void theStackedLayerIsWrittenOnlyWhenSomethingIsStackedInIt() {
        Level flat = LevelFormat.TOP_DOWN.starterLevel("Flat", 12, 12, 32);
        for (int r = 0; r < flat.height; r++) {
            for (int c = 0; c < flat.width; c++) flat.setTile(c, r, Level.LAYER_UPPER, 0);
        }
        // A starter level stands a border of wall, so clear the storage question
        // by asking a level that never allocated the layer at all.
        Level bare = Level.empty("Bare", 12, 12, 32);
        bare.setFormat(LevelFormat.TOP_DOWN);
        bare.fillFloor(bare.blocks.get("stone_path").id());
        assertFalse(keysOf(bare).contains("upperRle"),
                "a plan-view level nobody has built in writes no stacked layer");

        bare.setTile(4, 4, Level.LAYER_UPPER, bare.blocks.get("stone").id());
        assertTrue(keysOf(bare).contains("upperRle"),
                "and writes one the moment something stands in it");

        Level side = furnished(LevelFormat.SIDE_SCROLLER);
        assertFalse(keysOf(side).contains("upperRle"),
                "a side-scroller has no second layer to write");
    }

    /**
     * No level this build writes carries a key for a third layer, under any of
     * the names V3 proposes.
     *
     * <p>Trivially true today, and that is what makes it useful: it is the
     * assertion that turns red the moment V3 writes a new key unconditionally
     * instead of only for the levels that need it.
     */
    @Test
    void nothingWritesAThirdLayerYet() {
        for (LevelFormat format : LevelFormat.values()) {
            Set<String> keys = keysOf(furnished(format));
            for (String future : List.of("layerRle", "layerChunks", "maxLayers")) {
                assertFalse(keys.contains(future),
                        format + ": writes '" + future + "' before V3 says it may");
            }
        }
        Set<String> giantKeys = keysOf(giant());
        assertTrue(giantKeys.contains("chunks"), "a giant level writes its chunks");
        for (String future : List.of("layerRle", "layerChunks", "maxLayers")) {
            assertFalse(giantKeys.contains(future), "chunked: writes '" + future + "'");
        }
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Save, reload, save again — and demand the two saves are the same bytes.
     * Reports the first line they differ on, because a diff of two eight-kilobyte
     * JSON documents is not a test failure anybody can read.
     */
    private static void assertFixedPoint(Level level, String what) {
        String first = level.toJson();
        String second = LevelLoader.parse(first).toJson();
        if (!first.equals(second)) {
            String[] a = first.split("\n", -1), b = second.split("\n", -1);
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                String la = i < a.length ? a[i] : "<end of file>";
                String lb = i < b.length ? b[i] : "<end of file>";
                if (!la.equals(lb)) {
                    throw new AssertionError(what + ": the second save differs from the "
                            + "first at line " + (i + 1) + "\n  first:  " + la
                            + "\n  second: " + lb);
                }
            }
        }
        assertEquals(first, second, what + ": saving a loaded level is not a no-op");
    }

    /** The top-level keys of a level's saved JSON, in the order it writes them. */
    private static Set<String> keysOf(Level level) {
        Object parsed = Json.parse(level.toJson());
        assertTrue(parsed instanceof Map, "a level serializes to a JSON object");
        return new LinkedHashSet<>(((Map<?, ?>) parsed).keySet().stream()
                .map(String::valueOf).toList());
    }

    /**
     * A level of {@code format} carrying one of everything the writer has an
     * optional branch for — settings, a spawn moved off the default, surface
     * decor, an entity, and (where the format has one) a stacked block. A round
     * trip that only ever sees empty lists proves nothing about the branches
     * that write them.
     */
    private static Level furnished(LevelFormat format) {
        Level lvl = format.starterLevel("Furnished " + format, 16, 12, 32);
        lvl.captureSettings(new GameProfile("bytes-test"));
        lvl.spawnX = 96.5;
        lvl.spawnY = 128.25;
        int stone = lvl.blocks.get("stone").id();
        lvl.setTile(4, 4, lvl.blocks.get("stone_path").id());
        if (lvl.layered()) lvl.setTile(4, 4, Level.LAYER_UPPER, stone);
        else lvl.setTile(4, 6, stone);
        lvl.surfaceDecor.add(new SurfaceDecor.Placement(4, 4,
                SurfaceDecor.Face.UP, "moss", true,
                SurfaceDecor.Visibility.ALWAYS));
        lvl.entities.add(new Level.EntitySpawn("entity", "player", 96.5, 128.25));
        return lvl;
    }

    /** A giant chunked level with both layers edited and a generator behind them. */
    private static Level giant() {
        int side = (int) Math.sqrt(Level.DENSE_TILE_LIMIT) + ChunkedTiles.CHUNK;
        Level lvl = Level.emptyChunked("Giant", side, side, 32,
                Level.flatGenerator(1));
        lvl.setFormat(LevelFormat.TOP_DOWN);
        int stone = lvl.blocks.get("stone").id();
        int path = lvl.blocks.get("stone_path").id();
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 6; c++) lvl.setTile(c, r, path);
        }
        lvl.setTile(2, 2, Level.LAYER_UPPER, stone);
        lvl.setTile(3, 2, Level.LAYER_UPPER, stone);
        return lvl;
    }
}
