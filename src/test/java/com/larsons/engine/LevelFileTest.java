package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFile;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A level file on disk: deflated, lossless, reproducible, and still able to
 * read every level written before it was.
 *
 * <p><b>What has to hold for this to be a compression and not a format.</b>
 * The bytes that come back have to be the bytes that went in, character for
 * character — anything less is a save that quietly rounds a coordinate. Saving
 * the same level twice has to write the same file, or a level nobody touched
 * turns up in every diff. And a file from before this existed has to load
 * untouched, because a player's levels folder is full of them.
 */
class LevelFileTest {

    /** What went in comes back out, exactly. */
    @Test
    void packingIsLossless() {
        String json = sample().toJson();
        assertEquals(json, LevelFile.unpack(LevelFile.pack(json)));
    }

    /** …and the same level written twice is the same file twice. */
    @Test
    void packingIsReproducible() {
        String json = sample().toJson();
        assertArrayEquals(LevelFile.pack(json), LevelFile.pack(json),
                "a gzip header with a timestamp in it would fail this");
    }

    /**
     * A level worth compressing is compressed several times over.
     *
     * <p>Loose on purpose — what deflate achieves on a particular level is not
     * a thing to pin — but not so loose that the assertion would survive the
     * compression being switched off, which is the failure it exists to catch.
     */
    @Test
    void aLevelGetsSubstantiallySmaller() {
        Level big = sample();
        int plain = big.toJson().getBytes(StandardCharsets.UTF_8).length;
        int packed = LevelFile.pack(big.toJson()).length;
        assertTrue(packed * 3 < plain,
                "expected at least a threefold saving, got " + plain + " -> " + packed);
    }

    /** A plain-text level file still loads: the first two bytes decide. */
    @Test
    void plainTextLevelsStillLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("hand_written.json");
        Files.writeString(file, sample().toJson());
        assertFalse(LevelFile.packed(Files.readAllBytes(file)));
        Level back = LevelLoader.load(file.toString());
        assertEquals("compressible", back.name);
        assertEquals(LevelFormat.THREE_D, back.format());
    }

    /** A level saved through the store round-trips, and the file is deflated. */
    @Test
    void aSavedLevelIsDeflatedAndLoadsBack(@TempDir Path dir) throws IOException {
        LevelStore store = new LevelStore(dir.toString(), "compress type");
        Level saved = sample();
        Path file = store.save(saved);

        assertTrue(LevelFile.packed(Files.readAllBytes(file)),
                "the store writes a level deflated");
        Level back = store.load(saved.name);
        assertNotNull(back);
        assertEquals(saved.toJson(), back.toJson(), "and it is the same level coming back");
    }

    /**
     * Listing a folder by format still works without inflating whole files —
     * and, more to the point, still works at all now that the header is not the
     * first bytes of the file.
     */
    @Test
    void theFormatIsStillReadableFromTheHeader(@TempDir Path dir) {
        LevelStore store = new LevelStore(dir.toString(), "compress type");
        store.save(sample());
        assertEquals(LevelFormat.THREE_D, store.formatOf("compressible"));
        assertEquals(java.util.List.of("compressible"), store.list(LevelFormat.THREE_D));
    }

    /** A level with enough in it to be worth deflating. */
    private static Level sample() {
        Level lvl = Level.empty("compressible", 64, 64, 32);
        lvl.setFormat(LevelFormat.THREE_D);
        GameProfile settings = new GameProfile("compress type");
        settings.verticality = true;
        lvl.settings = settings;
        int stone = lvl.blocks.get("stone").id();
        lvl.fillFloor(lvl.blocks.get("grass").id());
        for (int col = 0; col < 64; col++) {
            for (int row = 0; row < 64; row++) {
                int height = 1 + ((col * 7 + row * 3) % 5);
                for (int layer = 1; layer <= height; layer++) {
                    lvl.setTile(col, row, layer, stone);
                }
            }
        }
        return lvl;
    }
}
