package com.larsons.engine;

import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated terrain keeps its water features and decorations — the amplitude
 * cap that smoothed tall maps had left the sea level behind, so valleys never
 * dipped under water any more — and the SURFACE palette's "+" entry creates
 * persistent custom block decor.
 */
class TerrainDecorWaterTest {

    /** water / tall grass / flowers / mushrooms / decor entities counts. */
    private static int[] census(Level lvl) {
        int water = 0, tallGrass = 0, flowers = 0, shrooms = 0, decorEnt = 0;
        for (int c = 0; c < lvl.width; c++) {
            for (int r = 0; r < lvl.height; r++) {
                Block b = lvl.blocks.get(lvl.tileAt(c, r));
                if (b == null) continue;
                switch (b.key()) {
                    case "water" -> water++;
                    case "tall_grass" -> tallGrass++;
                    case "flower_red", "flower_yellow", "flower_blue" -> flowers++;
                    case "glow_mushroom", "mushroom" -> shrooms++;
                }
            }
        }
        for (Level.EntitySpawn e : lvl.entities) {
            if ("decor_bg".equals(e.kind) || "decor_fg".equals(e.kind)) decorEnt++;
        }
        return new int[]{water, tallGrass, flowers, shrooms, decorEnt};
    }

    // --- water features --------------------------------------------------------------

    @Test
    void generatedLevelsPoolWaterAtEverySize() {
        // The hill amplitude is capped in absolute tiles, so a sea fixed at
        // h * 0.36 drifted out of the terrain's reach as maps grew: beyond
        // ~300 tiles tall not a single water tile generated. The sea now
        // follows the capped amplitude and every size keeps its pools.
        for (int[] size : new int[][]{{240, 140}, {480, 280}, {600, 400}}) {
            int total = 0, seedsWithWater = 0;
            for (long seed = 1; seed <= 6; seed++) {
                int water = census(LevelGenerator.generate("w", size[0], size[1],
                        32, seed))[0];
                total += water;
                if (water > 0) seedsWithWater++;
            }
            assertTrue(total > 100, size[0] + "x" + size[1]
                    + " should pool real water across seeds, total was " + total);
            assertTrue(seedsWithWater >= 4, size[0] + "x" + size[1]
                    + " should have water in most seeds, had it in " + seedsWithWater + "/6");
        }
    }

    // --- decorations & block features --------------------------------------------------

    @Test
    void generatedLevelsGrowDecorationsAndBlockFeatures() {
        Level lvl = LevelGenerator.generate("d", 240, 140, 32, 2L);
        int[] counts = census(lvl);
        assertTrue(counts[1] > 0, "tall grass should generate on the surface");
        assertTrue(counts[2] > 0, "flowers should generate on the surface");
        assertTrue(counts[3] > 0, "mushrooms should generate in caves");
        assertTrue(counts[4] > 0, "trees/rocks/bushes should generate as decor entities");
        assertFalse(lvl.surfaceDecor.isEmpty(),
                "face-attached surface details should generate with the terrain");
    }

    @Test
    void giantChunkedLevelsGrowBlockFeaturesAndWater() {
        // The lazy chunk generator skips the global dressing passes, but block
        // features are per-tile hash rolls, so giant maps grow them too.
        Level lvl = LevelGenerator.generateChunked("giant", 1024, 256, 32, 7L);
        int[] counts = census(lvl);
        assertTrue(counts[0] > 0, "chunked terrain should pool water below sea level");
        assertTrue(counts[1] > 0, "chunked terrain should grow tall grass");
        assertTrue(counts[2] > 0, "chunked terrain should grow flowers");
        assertTrue(counts[3] > 0, "chunked caves should grow mushrooms");
    }

    @Test
    void chunkedBlockFeaturesAreDeterministic() {
        // Chunks must stay pure functions of (seed, col, row) — rebuilt after
        // eviction, they have to match what was there before.
        Level a = LevelGenerator.generateChunked("a", 512, 192, 32, 99L);
        Level b = LevelGenerator.generateChunked("b", 512, 192, 32, 99L);
        for (int c = 0; c < 512; c += 3) {
            for (int r = 0; r < 192; r += 3) {
                assertEquals(a.tileAt(c, r), b.tileAt(c, r),
                        "tile (" + c + "," + r + ") should regenerate identically");
            }
        }
    }

    // --- "+" custom block decor ---------------------------------------------------------

    @Test
    void customBlockDecorPersistsRegistersAndDeletes(@TempDir Path dir) {
        CustomContentStore store = new CustomContentStore(dir.toString(), "Decor Game");
        store.addSurfaceDecor(new SurfaceDecor("test_vines", "Test Vines",
                SurfaceDecor.Style.HANGING_MOSS,
                new Color(20, 130, 40), new Color(10, 100, 30),
                EnumSet.of(SurfaceDecor.Face.DOWN, SurfaceDecor.Face.LEFT), true));
        assertNotNull(SurfaceDecorRegistry.standard().get("test_vines"),
                "creation registers live");
        assertTrue(store.isCustom("surface", "test_vines"));

        // A fresh store (a new session) re-registers it from custom.json.
        SurfaceDecorRegistry.standard().unregister("test_vines");
        CustomContentStore reloaded = new CustomContentStore(dir.toString(), "Decor Game");
        reloaded.loadAndRegister();
        SurfaceDecor d = SurfaceDecorRegistry.standard().get("test_vines");
        assertNotNull(d, "custom block decor reloads from custom.json");
        assertEquals(SurfaceDecor.Style.HANGING_MOSS, d.style());
        assertEquals(EnumSet.of(SurfaceDecor.Face.DOWN, SurfaceDecor.Face.LEFT),
                d.allowedFaces());
        assertTrue(d.defaultForeground());

        assertTrue(reloaded.remove("surface", "test_vines"));
        assertNull(SurfaceDecorRegistry.standard().get("test_vines"),
                "deletion unregisters live");
        assertFalse(reloaded.isCustom("surface", "test_vines"));
    }
}
