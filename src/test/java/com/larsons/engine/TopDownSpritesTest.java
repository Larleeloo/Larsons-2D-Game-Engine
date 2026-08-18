package com.larsons.engine;

import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DirectionalSprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.TopDownSprites;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player's second sprite pool: the overhead art a 3D level draws from once
 * its camera has been raised past {@link Camera#OVERHEAD_PITCH}.
 *
 * <p>Two things are under test and they are separable. One is the
 * <em>switch</em> — that the camera's tilt decides which pool is consulted, at
 * the angle it is supposed to. The other is the <em>pool</em> — that overhead
 * art resolves through its own keys and, when nobody has drawn any, falls back
 * to generated art that is genuinely a different picture rather than the
 * standing figure under another name.
 */
@Timeout(30)
class TopDownSpritesTest {

    private static final int SIZE = 40;
    private static final Color BODY = PlayerSprites.DEFAULT_BODY;

    /** No drop-in texture pack on this machine — the built-in art is the baseline. */
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

    private static Camera at(double degrees) {
        Camera cam = new Camera(Perspective.THREE_D, 480, 360);
        cam.tileSize = 32;
        cam.setPitch(Math.toRadians(degrees));
        return cam;
    }

    private static PlayerSprites.Frame frame(boolean overhead, Facing facing) {
        return PlayerSprites.directionalFrame("", "walk", facing, 0.2, 0, SIZE, BODY, overhead);
    }

    /** A one-frame 32x32 sheet of a solid colour, written to disk. */
    private static Path solidSheet(Path dir, String name, Color color) throws IOException {
        BufferedImage sheet = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        Path file = dir.resolve(name);
        ImageIO.write(sheet, "png", file.toFile());
        return file;
    }

    // --- the switch -----------------------------------------------------------------

    /**
     * The camera decides, and it decides at 75°. Asserted through
     * {@link PlayerSprites#overhead(Camera)} rather than by comparing images,
     * because this is the rule and the pictures are what follows from it.
     */
    @Test
    void theCameraTiltChoosesThePoolAtSeventyFiveDegrees() {
        assertFalse(PlayerSprites.overhead(at(60)), "the default camera is not overhead");
        assertFalse(PlayerSprites.overhead(at(74.9)));
        assertTrue(PlayerSprites.overhead(at(75)), "'75 degrees or greater' includes 75");
        assertTrue(PlayerSprites.overhead(at(90)));
        assertEquals(75, PlayerSprites.OVERHEAD_DEGREES, 1e-9);
    }

    @Test
    void aMissingCameraIsNotOverhead() {
        // Palette icons and previews draw a character with no camera at all,
        // and the answer for those has to be the standing view rather than a
        // crash.
        assertFalse(PlayerSprites.overhead(null));
        assertFalse(PlayerSprites.overhead(new Camera(Perspective.SIDE_SCROLL, 480, 360)));
    }

    // --- the pool -------------------------------------------------------------------

    @Test
    void theTwoPoolsHaveSeparateKeys() {
        assertEquals("player/walk", PlayerSprites.stateKey("walk"));
        assertEquals("player/topdown/walk", PlayerSprites.topDownStateKey("walk"));
        assertEquals("player/topdown/walk/ne",
                PlayerSprites.topDownStateKey("walk", Facing.NORTH_EAST));
        assertEquals("character/rogue/topdown/idle",
                PlayerSprites.characterStateKey("rogue", "idle", true));
        assertEquals("character/rogue/idle",
                PlayerSprites.characterStateKey("rogue", "idle", false));
    }

    /**
     * With nothing assigned, each pool draws its own generated art — and the
     * two are different pictures. This is the claim the whole feature rests on:
     * if the overhead fallback were the standing figure, raising the camera
     * would change nothing and the pool would be decoration.
     */
    @Test
    void eachPoolFallsBackToItsOwnGeneratedArt() {
        for (Facing facing : Facing.values()) {
            PlayerSprites.Frame standing = frame(false, facing);
            PlayerSprites.Frame overhead = frame(true, facing);

            assertNotNull(standing.image(), facing + ": no standing art");
            assertNotNull(overhead.image(), facing + ": no overhead art");
            assertSame(DirectionalSprites.frame(SIZE, BODY, facing, 2), standing.image(),
                    facing + ": the standing pool draws the standing figure");
            assertSame(TopDownSprites.frame(SIZE, BODY, facing, 2), overhead.image(),
                    facing + ": the overhead pool draws the overhead figure");
            assertNotEquals(standing.image(), overhead.image(),
                    facing + ": the two pools handed back the same picture");
            assertFalse(overhead.mirrored(),
                    facing + ": overhead art is drawn per direction, never flipped");
        }
    }

    /** Every direction is drawn, and no two of them are the same image. */
    @Test
    void allEightDirectionsAreDrawnAndDistinct() {
        BufferedImage[] seen = new BufferedImage[Facing.values().length];
        for (Facing facing : Facing.values()) {
            BufferedImage img = TopDownSprites.frame(SIZE, BODY, facing, 0);
            assertNotNull(img);
            assertEquals(SIZE, img.getWidth());
            assertEquals(SIZE, img.getHeight());
            assertTrue(inked(img) > 0, facing + " drew nothing at all");
            seen[facing.ordinal()] = img;
        }
        for (int a = 0; a < seen.length; a++) {
            for (int b = a + 1; b < seen.length; b++) {
                assertFalse(identical(seen[a], seen[b]),
                        Facing.values()[a] + " and " + Facing.values()[b]
                                + " are drawn identically, so the sprite does not turn");
            }
        }
    }

    /** It animates, and the frames are not all the same. */
    @Test
    void theOverheadWalkCycleActuallyMoves() {
        BufferedImage first = TopDownSprites.frame(SIZE, BODY, Facing.EAST, 0);
        boolean moved = false;
        for (int f = 1; f < TopDownSprites.FRAMES; f++) {
            if (!identical(first, TopDownSprites.frame(SIZE, BODY, Facing.EAST, f))) moved = true;
        }
        assertTrue(moved, "four identical frames are not a walk cycle");
        assertEquals(DirectionalSprites.FRAMES, TopDownSprites.FRAMES,
                "the two pools must share a frame count, or a hand-drawn overhead sheet "
                        + "cannot reuse the standing one's timing");
    }

    /** Frames are cached, so a character on screen is a stable texture identity. */
    @Test
    void framesAreCachedSoTheyCanBeAtlased() {
        assertSame(TopDownSprites.frame(SIZE, BODY, Facing.NORTH, 1),
                TopDownSprites.frame(SIZE, BODY, Facing.NORTH, 1));
    }

    // --- assigned art ----------------------------------------------------------------

    @Test
    void assignedOverheadArtIsUsedWhenTheCameraIsRaised(@TempDir Path dir) throws IOException {
        Path sheet = solidSheet(dir, "topdown_walk.png", Color.MAGENTA);
        Skins.put(new SkinDef(PlayerSprites.topDownStateKey("walk"),
                sheet.toString(), 32, 32, 1, 0));

        PlayerSprites.Frame overhead = frame(true, Facing.SOUTH);
        assertEquals(Color.MAGENTA.getRGB(), overhead.image().getRGB(16, 16),
                "the overhead pool ignored the sheet assigned to it");

        PlayerSprites.Frame standing = frame(false, Facing.SOUTH);
        assertNotEquals(Color.MAGENTA.getRGB(), standing.image().getRGB(16, 16),
                "an overhead sheet must not leak into the standing view");
    }

    /**
     * And the other direction: standing art does <em>not</em> stand in for
     * overhead art.
     *
     * <p>The deliberate omission, pinned here because it is the kind of thing a
     * later "sensible default" would restore without noticing. Serving the
     * standing sheet from above is exactly the picture the second pool exists to
     * stop, and it would do it silently.
     */
    @Test
    void standingArtDoesNotStandInForOverheadArt(@TempDir Path dir) throws IOException {
        Path sheet = solidSheet(dir, "walk.png", Color.MAGENTA);
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk"), sheet.toString(), 32, 32, 1, 0));
        Skins.put(new SkinDef(PlayerSprites.SKIN_KEY, sheet.toString(), 32, 32, 1, 0));

        assertEquals(Color.MAGENTA.getRGB(), frame(false, Facing.SOUTH).image().getRGB(16, 16),
                "the standing pool should be wearing the assigned sheet");
        assertSame(TopDownSprites.frame(SIZE, BODY, Facing.SOUTH, 2),
                frame(true, Facing.SOUTH).image(),
                "raised over the world, a character with no overhead art of its own is "
                        + "drawn from above rather than in its standing sheet");
    }

    /** A character profile's own overhead art wins over the shared player's. */
    @Test
    void aCharactersOwnOverheadArtWinsOverTheSharedOne(@TempDir Path dir) throws IOException {
        Skins.put(new SkinDef(PlayerSprites.topDownStateKey("walk"),
                solidSheet(dir, "shared.png", Color.MAGENTA).toString(), 32, 32, 1, 0));
        Skins.put(new SkinDef(PlayerSprites.characterStateKey("rogue", "walk", true),
                solidSheet(dir, "rogue.png", Color.GREEN).toString(), 32, 32, 1, 0));

        BufferedImage rogue = PlayerSprites.directionalFrame("rogue", "walk", Facing.SOUTH,
                0.2, 0, SIZE, BODY, true).image();
        assertEquals(Color.GREEN.getRGB(), rogue.getRGB(16, 16));

        BufferedImage anyone = frame(true, Facing.SOUTH).image();
        assertEquals(Color.MAGENTA.getRGB(), anyone.getRGB(16, 16),
                "a character with no overhead art of its own wears the shared one");
    }

    /** The overhead pool is listed for assignment, or nobody can draw into it. */
    @Test
    void theOverheadPoolIsListedInTheTextureKeys() {
        List<String> keys = TextureKeys.all().stream().map(TextureKeys.Entry::key).toList();
        for (String state : PlayerSprites.ACTION_STATES) {
            assertTrue(keys.contains(PlayerSprites.topDownStateKey(state)),
                    "no texture-key entry for the overhead " + state
                            + " sheet, so a creator cannot assign one");
        }
    }

    // --- helpers ---------------------------------------------------------------------

    private static int inked(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) n++;
            }
        }
        return n;
    }

    private static boolean identical(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }
}
