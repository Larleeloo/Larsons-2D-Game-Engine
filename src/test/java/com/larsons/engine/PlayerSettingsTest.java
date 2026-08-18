package com.larsons.engine;

import com.larsons.engine.audio.Sounds;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.config.PlayerSettingsStore;
import com.larsons.engine.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job O — the settings that belong to the person playing.
 *
 * <p>The behaviour under test is a boundary, not a feature: volume, look
 * sensitivity, invert-Y and HUD size are the player's, and <em>nothing a level
 * loads may reach them</em>. Before this existed the three volumes lived on
 * {@link GameProfile}, were written into every level file by
 * {@code captureSettings}, and were copied back out by
 * {@link GameProfile#applyFeaturesFrom} on load and on every door transition —
 * so opening someone else's level replaced your mix with theirs.
 */
class PlayerSettingsTest {

    @AfterEach
    void restoreDefaults() {
        PlayerSettings.install(new PlayerSettings());
    }

    /** Round-trip, and a file missing half its keys is a partial answer. */
    @Test
    void settingsRoundTripAndTolerateAPartialFile() {
        PlayerSettings s = new PlayerSettings();
        s.masterVolume = 0.5;
        s.sfxVolume = 0.25;
        s.musicVolume = 0.75;
        s.lookSensitivity = 2.5;
        s.invertLook = true;
        s.hudScale = 1.5;

        PlayerSettings copy = PlayerSettings.fromMap(s.toMap());
        assertEquals(0.5, copy.masterVolume, 1e-9);
        assertEquals(0.25, copy.sfxVolume, 1e-9);
        assertEquals(0.75, copy.musicVolume, 1e-9);
        assertEquals(2.5, copy.lookSensitivity, 1e-9);
        assertTrue(copy.invertLook);
        assertEquals(1.5, copy.hudScale, 1e-9);

        // A file from an older build, carrying only what that build knew about.
        PlayerSettings partial = PlayerSettings.fromMap(Map.of("masterVolume", 0.2));
        assertEquals(0.2, partial.masterVolume, 1e-9);
        assertEquals(new PlayerSettings().lookSensitivity, partial.lookSensitivity, 1e-9,
                "a key the file does not carry should be the default, not zero");
        assertEquals(new PlayerSettings().hudScale, partial.hudScale, 1e-9);
    }

    /** Hand-edited nonsense is clamped to something the game still works at. */
    @Test
    void everythingIsClamped() {
        PlayerSettings s = PlayerSettings.fromMap(Map.of(
                "masterVolume", 4.0, "sfxVolume", -2.0, "musicVolume", 1.5,
                "lookSensitivity", 0.0, "hudScale", 99.0));
        assertEquals(1.0, s.masterVolume, 1e-9);
        assertEquals(0.0, s.sfxVolume, 1e-9);
        assertEquals(1.0, s.musicVolume, 1e-9);
        assertEquals(PlayerSettings.MIN_SENSITIVITY, s.lookSensitivity, 1e-9,
                "a sensitivity of zero is a camera that cannot turn");
        assertEquals(PlayerSettings.MAX_HUD_SCALE, s.hudScale, 1e-9);
    }

    /** The store is a plain file beside the game, and a missing one is fine. */
    @Test
    void theStoreWritesAReadableFileAndDefaultsWhenThereIsNone(@TempDir Path dir) throws Exception {
        PlayerSettingsStore store = new PlayerSettingsStore(dir.toString());
        assertEquals(new PlayerSettings().masterVolume, store.load().masterVolume, 1e-9,
                "no file at all should be the defaults, not an error");

        PlayerSettings s = new PlayerSettings();
        s.musicVolume = 0.3;
        s.invertLook = true;
        store.save(s);

        assertTrue(Files.exists(dir.resolve(PlayerSettingsStore.FILE_NAME)));
        PlayerSettings back = store.load();
        assertEquals(0.3, back.musicVolume, 1e-9);
        assertTrue(back.invertLook);

        // Readable by anything, so a run can be copied between machines.
        Map<String, Object> raw = Json.asObject(
                Json.parse(Files.readString(dir.resolve(PlayerSettingsStore.FILE_NAME))));
        assertEquals(0.3, ((Number) raw.get("musicVolume")).doubleValue(), 1e-9);
    }

    /** An unreadable file costs the player their settings, never the launch. */
    @Test
    void aCorruptFileIsTheDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(PlayerSettingsStore.FILE_NAME), "{ not json at all");
        PlayerSettings s = new PlayerSettingsStore(dir.toString()).load();
        assertEquals(new PlayerSettings().masterVolume, s.masterVolume, 1e-9);
    }

    // --- the boundary this job exists for --------------------------------------

    /**
     * <b>The regression this whole job is about.</b> A level authored by
     * somebody else must not move the player's volume.
     *
     * <p>Written as the level's own settings being applied — which is what
     * happens on entering a level and on every door transition — because that
     * is the exact path that used to overwrite the mix mid-run.
     */
    @Test
    void loadingSomebodyElsesLevelDoesNotTouchYourVolume(@TempDir Path dir) {
        PlayerSettings mine = new PlayerSettings();
        mine.masterVolume = 0.2;   // I play quietly
        mine.musicVolume = 0.0;    // and with the music off
        PlayerSettings.install(mine);

        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(new GameProfile("mine"));
        assertEquals(0.2, Sounds.masterVolume(), 1e-9);
        assertEquals(0.0, Sounds.musicVolume(), 1e-9);

        // A level whose author saved it at full blast, with keys in the file
        // from the build where volume still lived on the profile.
        GameProfile theirs = GameProfile.fromMap(new java.util.LinkedHashMap<>(Map.of(
                "name", "theirs",
                "masterVolume", 1.0,
                "musicVolume", 1.0,
                "nightMode", true)));
        ctx.applyLevelSettings(theirs);

        assertEquals(0.2, Sounds.masterVolume(), 1e-9,
                "the level's author does not get to set my master volume");
        assertEquals(0.0, Sounds.musicVolume(), 1e-9,
                "…nor to turn the music back on");
        assertEquals(0.2, PlayerSettings.active().masterVolume, 1e-9);
        assertTrue(ctx.profile().nightMode,
                "the level's own toggles should still apply — only volume is exempt");
    }

    /**
     * The profile no longer carries volume at all, so it cannot be written into
     * a level file and cannot come back out of one. Belt and braces for the
     * test above: that one proves the value survives, this one proves there is
     * no longer a channel for it to travel down.
     */
    @Test
    void aGameTypeFileCarriesNoVolume() {
        GameProfile p = new GameProfile("no volume here");
        p.musicEnabled = false;
        String json = p.toJson();
        assertFalse(json.contains("masterVolume"), json);
        assertFalse(json.contains("sfxVolume"), json);
        assertFalse(json.contains("musicVolume"), json);

        // Whether there *is* music is still the creator's call — only how loud
        // it is on this machine moved out.
        assertFalse(GameProfile.fromJson(json).musicEnabled);
    }

    /** Changing a setting reaches the mixer without a level reload. */
    @Test
    void applyingPlayerSettingsPushesTheMixLive(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(new GameProfile("live"));

        PlayerSettings.active().masterVolume = 0.4;
        assertNotEquals(0.4, Sounds.masterVolume(), 1e-9,
                "the field alone should not have reached the mixer yet");
        ctx.applyPlayerSettings();
        assertEquals(0.4, Sounds.masterVolume(), 1e-9);
    }
}
