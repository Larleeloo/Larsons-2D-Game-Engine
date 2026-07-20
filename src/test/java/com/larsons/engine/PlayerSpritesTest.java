package com.larsons.engine;

import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shared player-character sprite: creative play-test and the play scene
 * draw the same walk animation, the player's action states classify from the
 * simulation, and each state's skin animation resolves through the
 * {@code player/<state>} keys (creative's Player Skin… tool) with graceful
 * fallbacks — including the legacy single {@code player} key.
 */
@Timeout(30)
class PlayerSpritesTest {

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        AssetLoader.clearCache();
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

    @Test
    void walkAnimationIsTheSharedFourFrameWalkCycle() {
        Animation anim = PlayerSprites.walkAnimation(32, PlayerSprites.DEFAULT_BODY);
        assertEquals(4, anim.frameCount(), "the character sheet has 4 walk frames");
        BufferedImage frame = anim.current();
        assertNotNull(frame);
        assertEquals(32, frame.getWidth());
        assertEquals(32, frame.getHeight());
    }

    @Test
    void frameFallsBackToProceduralArtWithoutASkin() {
        Animation anim = PlayerSprites.walkAnimation(32, PlayerSprites.DEFAULT_BODY);
        for (String state : PlayerSprites.ACTION_STATES) {
            assertSame(anim.current(), PlayerSprites.frame(state, anim, 0),
                    "no installed skin -> the procedural walk frame draws for " + state);
        }
    }

    @Test
    void actionStatesClassifyTheSimulation() {
        // Swimming beats everything; airborne splits by vertical velocity;
        // grounded splits by movement and sprint.
        assertEquals("swim", PlayerSprites.actionState(false, true, true, true, -50));
        assertEquals("jump", PlayerSprites.actionState(false, false, true, false, -50));
        assertEquals("fall", PlayerSprites.actionState(false, false, true, false, 50));
        assertEquals("run", PlayerSprites.actionState(true, false, true, true, 0));
        assertEquals("walk", PlayerSprites.actionState(true, false, true, false, 0));
        assertEquals("idle", PlayerSprites.actionState(true, false, false, false, 0));
    }

    @Test
    void eachActionStateResolvesItsOwnSkin(@TempDir Path dir) throws IOException {
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk"),
                solidSheet(dir, "walk.png", Color.GREEN).toString(), 32, 32, 1, 0));
        Skins.put(new SkinDef(PlayerSprites.stateKey("jump"),
                solidSheet(dir, "jump.png", Color.ORANGE).toString(), 32, 32, 1, 0));

        assertEquals(Color.GREEN.getRGB(),
                PlayerSprites.frame("walk", null, 0).getRGB(16, 16),
                "walk plays the walk sheet");
        assertEquals(Color.ORANGE.getRGB(),
                PlayerSprites.frame("jump", null, 0).getRGB(16, 16),
                "jump plays the jump sheet");
        // Unassigned states borrow the nearest assigned relative: run and
        // swim fall back to walk, fall to jump.
        assertEquals(Color.GREEN.getRGB(),
                PlayerSprites.frame("run", null, 0).getRGB(16, 16));
        assertEquals(Color.GREEN.getRGB(),
                PlayerSprites.frame("swim", null, 0).getRGB(16, 16));
        assertEquals(Color.ORANGE.getRGB(),
                PlayerSprites.frame("fall", null, 0).getRGB(16, 16));
    }

    @Test
    void idleBorrowingAMovingSheetFreezesOnFrameZero(@TempDir Path dir) throws IOException {
        // A 2-frame walk sheet (red then blue) at 10 fps.
        BufferedImage sheet = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 32, 32);
        g.setColor(Color.BLUE);
        g.fillRect(32, 0, 32, 32);
        g.dispose();
        Path file = dir.resolve("walk2.png");
        ImageIO.write(sheet, "png", file.toFile());
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk"), file.toString(), 32, 32, 2, 10));

        assertEquals(Color.BLUE.getRGB(),
                PlayerSprites.frame("walk", null, 0.15).getRGB(16, 16),
                "walking plays the sheet (frame 1 at 0.15s of 10 fps)");
        assertEquals(Color.RED.getRGB(),
                PlayerSprites.frame("idle", null, 0.15).getRGB(16, 16),
                "standing still borrows the walk sheet frozen on frame 0");
    }

    @Test
    void legacySinglePlayerKeyStillSkinsEveryState(@TempDir Path dir) throws IOException {
        Skins.put(new SkinDef(PlayerSprites.SKIN_KEY,
                solidSheet(dir, "player.png", Color.MAGENTA).toString(), 32, 32, 1, 0));
        Animation anim = PlayerSprites.walkAnimation(32, PlayerSprites.DEFAULT_BODY);
        for (String state : PlayerSprites.ACTION_STATES) {
            BufferedImage frame = PlayerSprites.frame(state, anim, 0);
            assertNotNull(frame);
            assertEquals(Color.MAGENTA.getRGB(), frame.getRGB(16, 16),
                    "the pre-action-state skin key covers " + state);
        }
    }
}
