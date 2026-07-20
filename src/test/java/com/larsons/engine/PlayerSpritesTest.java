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
 * draw the same walk animation, and the {@code player} skin key reskins the
 * character everywhere via the Skins system (creative's Player Skin… tool).
 */
@Timeout(30)
class PlayerSpritesTest {

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        AssetLoader.clearCache();
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
        assertSame(anim.current(), PlayerSprites.frame(anim, 0),
                "no installed skin -> the procedural walk frame draws");
    }

    @Test
    void playerSkinKeyOverridesTheCharacter(@TempDir Path dir) throws IOException {
        BufferedImage sheet = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        Path file = dir.resolve("player.png");
        ImageIO.write(sheet, "png", file.toFile());

        Skins.put(new SkinDef(PlayerSprites.SKIN_KEY, file.toString(), 32, 32, 1, 0));
        Animation anim = PlayerSprites.walkAnimation(32, PlayerSprites.DEFAULT_BODY);
        BufferedImage frame = PlayerSprites.frame(anim, 0);
        assertNotNull(frame);
        assertEquals(Color.MAGENTA.getRGB(), frame.getRGB(16, 16),
                "an assigned player skin replaces the procedural character");
    }
}
