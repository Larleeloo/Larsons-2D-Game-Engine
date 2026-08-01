package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A game type is a folder of levels, not a configuration.
 *
 * <p>Feature settings are asked about once, on the level they apply to. The
 * game type's own copy is pinned to the engine defaults, so the template a new
 * level starts from is one predictable thing rather than whatever a previous
 * session left behind — and so a creator never sets mobs on one screen and
 * meets a level contradicting it on another.
 */
class GameTypeDefaultsTest {

    @Test
    void resettingRestoresEveryFeatureToItsDefault() {
        GameProfile p = new GameProfile("Dungeon Crawl");
        p.mobsEnabled = false;
        p.combatEnabled = false;
        p.lightingEnabled = true;
        p.shadersEnabled = true;
        p.shaderBloom = true;
        p.zoomEnabled = false;
        p.gravityEnabled = false;
        p.maxFps = 30;
        p.tileSize = 64;

        p.resetFeaturesToDefaults();

        GameProfile defaults = new GameProfile();
        assertEquals(defaults.mobsEnabled, p.mobsEnabled);
        assertEquals(defaults.combatEnabled, p.combatEnabled);
        assertEquals(defaults.lightingEnabled, p.lightingEnabled);
        assertEquals(defaults.shadersEnabled, p.shadersEnabled);
        assertEquals(defaults.shaderBloom, p.shaderBloom);
        assertEquals(defaults.zoomEnabled, p.zoomEnabled);
        assertEquals(defaults.gravityEnabled, p.gravityEnabled);
        assertEquals(defaults.maxFps, p.maxFps);
        assertEquals(defaults.tileSize, p.tileSize);
    }

    @Test
    void resettingKeepsTheGameTypeIdentity() {
        // Which folder of levels this is, and the art and audio that travel
        // with it, are not features — they must survive the reset.
        GameProfile p = new GameProfile("Dungeon Crawl");
        p.texturePackDir = "/packs/dungeon";
        p.soundPackDir = "/packs/dungeon-audio";
        p.lastLevelPath = "levels/crypt.json";
        p.mobsEnabled = false;

        p.resetFeaturesToDefaults();

        assertEquals("Dungeon Crawl", p.name);
        assertEquals("/packs/dungeon", p.texturePackDir);
        assertEquals("/packs/dungeon-audio", p.soundPackDir);
        assertEquals("levels/crypt.json", p.lastLevelPath);
        assertEquals(new GameProfile().mobsEnabled, p.mobsEnabled);
    }

    @Test
    void resettingLeavesAFinalizedGameTypePlayOnly() {
        // "Published" is identity, not a feature: resetting must not quietly
        // reopen someone else's finished game for editing.
        GameProfile p = new GameProfile("Published Game");
        p.finalized = true;

        p.resetFeaturesToDefaults();

        assertTrue(p.finalized, "a published game type stays play-only");
    }

    @Test
    void aLevelKeepsItsOwnSettingsWhenTheGameTypeIsReset() {
        // The whole point: per-level decisions are not disturbed by anything
        // happening at the game-type level.
        GameProfile gameType = new GameProfile("Sandbox");
        Level level = Level.empty("Quiet Level", 20, 12, 32);
        level.settings = gameType.copy();
        level.settings.mobsEnabled = false;
        level.settings.lightingEnabled = true;

        gameType.resetFeaturesToDefaults();

        assertFalse(level.settings.mobsEnabled, "the level's own choice stands");
        assertTrue(level.settings.lightingEnabled);
        assertNotSame(gameType, level.settings);
    }

    @Test
    void savingALevelPinsItsSettingsToItsRealFormat() {
        // Why the settings form needs no format row of its own: whatever the
        // profile carried, saving writes the level's actual format.
        Level level = Level.empty("Iso Town", 16, 16, 32);
        level.setFormat(LevelFormat.ISOMETRIC);

        GameProfile playingWith = new GameProfile("Sandbox");
        playingWith.perspective = Perspective.SIDE_SCROLL;   // contradicts the level
        level.captureSettings(playingWith);

        assertEquals(Perspective.ISOMETRIC, level.settings.perspective,
                "the level's own format wins over anything the profile carried");
        assertEquals(LevelFormat.ISOMETRIC, level.format());
    }

    @Test
    void defaultsAreAPlayableGameOutOfTheBox() {
        // A map maker who changes nothing should get a working level, since
        // that is now the only thing a new game type can hand them.
        GameProfile defaults = new GameProfile();

        assertTrue(defaults.mobsEnabled, "a fresh game should have creatures in it");
        assertTrue(defaults.itemsEnabled, "and an inventory");
        assertTrue(defaults.zoomEnabled);
        assertTrue(defaults.gravityEnabled);
        assertTrue(defaults.hudVisible);
        assertTrue(defaults.creativeEnabled, "and a way to build more levels");
        assertFalse(defaults.finalized, "a new game type is editable");
    }
}
