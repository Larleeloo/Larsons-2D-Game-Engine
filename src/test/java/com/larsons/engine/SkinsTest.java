package com.larsons.engine;

import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TexturePack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skin (texture override) system: sprite-sheet definitions round-trip
 * through JSON, the store persists them to the player's game files, and the
 * runtime resolver slices sheets by pixel width/height/frame-count, plays
 * them at 0-120 fps, and falls back cleanly (missing images, unskinned keys,
 * unit state -> idle).
 */
@Timeout(30)
class SkinsTest {

    /**
     * These cases are about the assignment side of the resolver, so the
     * drop-in texture pack is pointed at a folder that isn't there — the same
     * state as a machine that has never made one.
     */
    @BeforeEach
    void withoutATexturePack(@TempDir Path tmp) {
        TexturePack.useDir(tmp.resolve("no-texture-pack").toString());
    }

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        TexturePack.useDir("");
        AssetLoader.clearCache();
    }

    /** A 2-frame 32x32 sheet: frame 0 solid red, frame 1 solid blue. */
    private static Path writeSheet(Path dir) throws IOException {
        BufferedImage sheet = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 32, 32);
        g.setColor(Color.BLUE);
        g.fillRect(32, 0, 32, 32);
        g.dispose();
        Path file = dir.resolve("sheet.png");
        ImageIO.write(sheet, "png", file.toFile());
        return file;
    }

    @Test
    void skinDefClampsAndRoundTrips() {
        SkinDef def = new SkinDef("unit/squire/idle", "skins/units/squire.png", 32, 48, 6, 10);
        SkinDef back = SkinDef.fromMap(def.toMap());
        assertEquals(def.key, back.key);
        assertEquals(def.sheet, back.sheet);
        assertEquals(32, back.frameWidth);
        assertEquals(48, back.frameHeight);
        assertEquals(6, back.frameCount);
        assertEquals(10, back.fps);

        // The 0-120 fps contract.
        assertEquals(120, new SkinDef("k", "s", 1, 1, 1, 500).fps);
        assertEquals(0, new SkinDef("k", "s", 1, 1, 1, -3).fps);

        // Frame timing: 4 fps over 2 frames.
        SkinDef timed = new SkinDef("k", "s", 32, 32, 2, 4);
        assertEquals(0, timed.frameAt(0));
        assertEquals(1, timed.frameAt(0.25));
        assertEquals(0, timed.frameAt(0.5));
        // fps 0 = static: always frame 0.
        SkinDef still = new SkinDef("k", "s", 32, 32, 2, 0);
        assertEquals(0, still.frameAt(123.4));
    }

    @Test
    void storeSavesAndLoadsTheSkinSet(@TempDir Path tmp) {
        SkinStore store = new SkinStore(tmp.resolve("skins").toString());
        assertTrue(store.load().isEmpty(), "no file yet means no skins");

        List<SkinDef> set = List.of(
                new SkinDef("unit/squire/idle", "skins/units/squire.png", 32, 32, 4, 8),
                new SkinDef("board/tile_a", "skins/boards/tile.png", 64, 32, 1, 0));
        Path file = store.save(set);
        assertTrue(Files.exists(file));

        List<SkinDef> back = store.load();
        assertEquals(2, back.size());
        assertEquals("unit/squire/idle", back.get(0).key);
        assertEquals(8, back.get(0).fps);
        assertEquals("board/tile_a", back.get(1).key);
    }

    @Test
    void runtimeResolvesFramesByTimeAndFallsBack(@TempDir Path tmp) throws IOException {
        Path sheet = writeSheet(tmp);
        Skins.install(List.of(
                new SkinDef("unit/squire/idle", sheet.toString(), 32, 32, 2, 4),
                new SkinDef("item/sword", "no/such/image.png", 32, 32, 1, 0)));

        // Animated: 4 fps alternates the two frames.
        BufferedImage f0 = Skins.frame("unit/squire/idle", 0);
        BufferedImage f1 = Skins.frame("unit/squire/idle", 0.25);
        assertNotNull(f0);
        assertNotNull(f1);
        assertEquals(32, f0.getWidth());
        assertEquals(Color.RED.getRGB(), f0.getRGB(16, 16));
        assertEquals(Color.BLUE.getRGB(), f1.getRGB(16, 16));

        // A unit state without its own skin uses the idle sheet.
        assertNotNull(Skins.unitFrame("squire", "attack", 0));
        // Unskinned keys and broken image paths resolve to null (procedural art).
        assertNull(Skins.frame("unit/cleric/idle", 0));
        assertNull(Skins.frame("item/sword", 0));
        assertNull(AssetLoader.loadImageOrNull("still/no/such/image.png"));

        // Removal restores the fallback.
        Skins.remove("unit/squire/idle");
        assertNull(Skins.frame("unit/squire/idle", 0));
    }
}
