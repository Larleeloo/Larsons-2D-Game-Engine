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

    // --- the shape of the file ---------------------------------------------------

    /**
     * The keys a level writes, listed exactly.
     *
     * <p><b>These three assertions were rewritten by V3, on purpose.</b> They
     * were written to forbid the format churning, back when the plan promised
     * an append-only format so old builds kept working; that promise was
     * withdrawn by decision, and the honest response to a guard whose rule has
     * been repealed is to change it and say so — not to weaken it into
     * something that could never have caught anything. What they assert now is
     * the new shape rather than the old one's preservation.
     *
     * <p>The lists are spelled out rather than derived so that adding a key is
     * a decision someone makes in this file, on purpose, with this comment in
     * front of them.
     *
     * <p>Note what stopped varying: <b>the key set no longer depends on how
     * tall the level is.</b> Geometry is one list under one name, so a
     * side-scroller and an eight-deep isometric tower write the same keys and
     * differ only in how many entries that list has. That is the readability
     * the revision was for.
     */
    @Test
    void everyLevelWritesTheSameGeometryKeyWhateverItsHeight() {
        Set<String> expected = new LinkedHashSet<>(List.of(
                "name", "format", "perspective", "tileSize", "width", "height",
                "background", "settings", "tileset", "spawn", "layersRle",
                "surface", "entities"));
        for (LevelFormat format : LevelFormat.values()) {
            assertEquals(expected, keysOf(furnished(format)), format + ": saved keys");
        }
        assertEquals(expected, keysOf(tower()), "an eight-deep tower: saved keys");
    }

    /**
     * One run-length list per layer, bottom first — so the list's length is
     * the level's height in layers, and reading the file tells you how tall it
     * is without decoding a single run.
     */
    @Test
    void theGeometryListHasOneEntryPerLayer() {
        Level bare = Level.empty("Bare", 12, 12, 32);
        bare.setFormat(LevelFormat.TOP_DOWN);
        bare.fillFloor(bare.blocks.get("stone_path").id());
        assertEquals(1, layerRunsOf(bare).size(),
                "a plan-view level nobody has built in is one layer deep");

        bare.setTile(4, 4, Level.LAYER_UPPER, bare.blocks.get("stone").id());
        assertEquals(2, layerRunsOf(bare).size(),
                "and two the moment something stands on it");

        assertEquals(1, layerRunsOf(furnished(LevelFormat.SIDE_SCROLLER)).size(),
                "a side-scroller is one layer and can never be two");
        assertEquals(Level.MAX_LAYERS, layerRunsOf(tower()).size(),
                "and a tower is as deep as it was built");
    }

    /** A giant level writes the same list, as one chunk map per layer. */
    @Test
    void aGiantLevelWritesOneChunkMapPerLayer() {
        Set<String> keys = keysOf(giant());
        assertTrue(keys.contains("layerChunks"), "chunked geometry is a list of layers");
        assertFalse(keys.contains("chunks"), "and not the old floor-plus-lid pair");
        assertFalse(keys.contains("upperChunks"));

        Object parsed = Json.parse(giant().toJson());
        Object chunks = ((Map<?, ?>) parsed).get("layerChunks");
        assertTrue(chunks instanceof List, "layerChunks is a list");
        assertEquals(2, ((List<?>) chunks).size(), "one map per layer, at the layer's index");
    }

    // --- what the new shape carries ----------------------------------------------

    /**
     * A tower survives a save, layer for layer and cell for cell. This is the
     * property V3 exists for, and nothing before V3 could have asserted it —
     * there was nowhere in the file to put a fifth layer.
     */
    @Test
    void aTowerRoundTripsEveryLayer() {
        Level before = tower();
        Level after = LevelLoader.parse(before.toJson());
        assertEquals(before.layerCount(), after.layerCount());
        for (int layer = 0; layer < before.layerCount(); layer++) {
            for (int r = 0; r < before.height; r++) {
                for (int c = 0; c < before.width; c++) {
                    assertEquals(before.tileAt(c, r, layer), after.tileAt(c, r, layer),
                            "layer " + layer + " at (" + c + "," + r + ")");
                }
            }
        }
        assertEquals(Level.MAX_LAYERS, after.stackHeight(4, 4),
                "the column is as deep as it was saved");
        assertFixedPoint(before, "tower");
    }

    /** A level's own ceiling is saved when it has an opinion, and not otherwise. */
    @Test
    void theCeilingIsSavedOnlyWhenTheLevelHasAnOpinionAboutIt() {
        Level plain = furnished(LevelFormat.ISOMETRIC);
        assertFalse(keysOf(plain).contains("maxLayers"),
                "a level that never moved its ceiling says nothing");
        assertEquals(Level.MAX_LAYERS,
                LevelLoader.parse(plain.toJson()).maxLayers);

        Level flat = furnished(LevelFormat.ISOMETRIC);
        flat.maxLayers = 2;
        assertTrue(keysOf(flat).contains("maxLayers"));
        Level reloaded = LevelLoader.parse(flat.toJson());
        assertEquals(2, reloaded.maxLayers);
        assertEquals(2, reloaded.layerLimit(),
                "a level that says it is flat is still flat when it comes back");
    }

    // --- the shapes that are still read and no longer written ---------------------

    /**
     * Writing is free; reading is additive ({@code HEIGHT_PLAN.md} invariant
     * 3). The two-key shapes this build has stopped writing still load, and
     * they load into the same geometry — which is what lets the shipped levels
     * and anything a player saved last week keep working without a migration.
     */
    @Test
    void theShapesThisBuildNoLongerWritesStillLoad() {
        Level source = furnished(LevelFormat.TOP_DOWN);
        String legacy = twoKeyForm(source);
        assertTrue(legacy.contains("tilesRle") && legacy.contains("upperRle"),
                "the fixture really is in the old shape");

        Level loaded = LevelLoader.parse(legacy);
        for (int r = 0; r < source.height; r++) {
            for (int c = 0; c < source.width; c++) {
                assertEquals(source.tileAt(c, r), loaded.tileAt(c, r),
                        "floor at (" + c + "," + r + ")");
                assertEquals(source.tileAt(c, r, Level.LAYER_UPPER),
                        loaded.tileAt(c, r, Level.LAYER_UPPER),
                        "stack at (" + c + "," + r + ")");
            }
        }
        assertTrue(loaded.toJson().contains("layersRle"),
                "and it is written back in the shape this build writes");
    }

    /**
     * The oldest shape of all — the bundled level's row-of-arrays {@code tiles}
     * — still loads, and is still recognised as describing no layers at all,
     * which is what triggers the conversion that makes such a level playable.
     */
    @Test
    void aLevelDescribingNoLayersIsStillConvertedOnLoad() {
        String legacy = """
                {"name":"Old","perspective":"TOP_DOWN","tileset":"registry",
                 "tileSize":32,"width":3,"height":3,
                 "tiles":[[0,0,0],[0,1,0],[0,0,0]]}
                """;
        Level lvl = LevelLoader.parse(legacy);
        assertTrue(lvl.stackHeight(0, 0) >= 1,
                "air became floor, because it was somewhere you could walk");
        assertEquals(2, lvl.stackHeight(1, 1),
                "and the solid block became a stack of itself, the barrier it always was");
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

    /** The per-layer run lists a dense level writes, outermost list only. */
    private static List<?> layerRunsOf(Level level) {
        Object runs = ((Map<?, ?>) Json.parse(level.toJson())).get("layersRle");
        assertTrue(runs instanceof List, "layersRle is a list of layers");
        return (List<?>) runs;
    }

    /**
     * {@code level} re-encoded in the two-key shape this build no longer
     * writes — {@code tilesRle} plus {@code upperRle} — so the reader's legacy
     * path is exercised against a file rather than against a hope.
     */
    private static String twoKeyForm(Level level) {
        Map<String, Object> m = level.toMap();
        Object runs = m.remove("layersRle");
        List<?> perLayer = (List<?>) runs;
        m.put("tilesRle", perLayer.get(0));
        if (perLayer.size() > 1) m.put("upperRle", perLayer.get(1));
        return Json.stringify(m);
    }

    /** A plan-view level with one column built all the way to the ceiling. */
    private static Level tower() {
        Level lvl = furnished(LevelFormat.ISOMETRIC);
        int stone = lvl.blocks.get("stone").id();
        for (int layer = 1; layer < Level.MAX_LAYERS; layer++) {
            lvl.setTile(4, 4, layer, stone);
        }
        return lvl;
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
