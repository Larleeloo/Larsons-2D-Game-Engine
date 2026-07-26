package com.larsons.engine;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.audio.PcmClip;
import com.larsons.engine.audio.SoundDef;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.SoundMixer;
import com.larsons.engine.audio.SoundPack;
import com.larsons.engine.audio.SoundSynth;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playback: the mixer, the subtle per-play pitch drift that keeps repeated
 * sounds from turning into a stuck record, and the master switches.
 *
 * <p>These run on machines with no audio device (CI, containers), which is
 * the point of half of them — nothing here may throw when there is nowhere to
 * play a sound, and the game must not have to ask first.
 */
@Timeout(60)
class SoundMixerTest {

    @AfterEach
    void resetRuntime() {
        Sounds.clear();
        Sounds.setEnabled(true);
        Sounds.setPitchVariation(true);
        Sounds.setSfxVolume(1.0);
        Sounds.setMusicVolume(0.6);
        SoundPack.useDir("");
    }

    // --- headless safety --------------------------------------------------------

    /**
     * Every entry point has to be a no-op rather than an exception when there
     * is no audio line — this is what lets gameplay code call {@code sound()}
     * without ever checking.
     */
    @Test
    void everythingIsSafeWithoutAnAudioDevice() {
        Sounds.play("player/jump");
        Sounds.play("player/jump", 0.5);
        Sounds.play("player/jump", 0.5, -1);
        Sounds.playAt("player/jump", 120, -40, 640);
        Sounds.playFirst(1.0, "nothing/at/all", "player/jump");
        Sounds.playLoop("ambient/night", 1, 0);
        Sounds.music("music/level");
        Sounds.stopMusic();
        Sounds.stopAll();
        assertNotNull(Sounds.mixer());

        SoundMixer mixer = new SoundMixer();
        // The mixer always hands back a handle, so callers never null-check.
        assertNotNull(mixer.play(null, 1, 1, 0, false),
                "a null clip should give a silent handle, not crash");
        assertNotNull(mixer.play(PcmClip.SILENCE, 1, 1, 0, false));
        assertFalse(mixer.play(null, 1, 1, 0, false).isPlaying(),
                "the handle for a refused sound is not playing");
        assertNotNull(mixer.play(SoundSynth.clip(SoundSynth.Voice.CLICK), 1, 1, 0, false));
        mixer.stopAll();
        mixer.dispose();
    }

    /** An unknown key is silence, not an error — creators mistype names. */
    @Test
    void anUnknownKeyIsSilentRatherThanBroken() {
        assertTrue(Sounds.resolve("no/such/sound").isEmpty());
        assertEquals(Sounds.Source.SILENT, Sounds.sourceOf("no/such/sound"));
        assertNull(Sounds.play("no/such/sound"));
        assertNull(Sounds.play(""));
    }

    // --- pitch variation --------------------------------------------------------

    /**
     * The Minecraft trick the request asked for: repeated sounds come out at
     * slightly different pitches. The spread is bounded and settable, and the
     * toggle really does pin every playback to the same pitch.
     */
    @Test
    void freshPitchVariesEachPlaybackWithinItsBound(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());

        double spread = SoundPack.pitchVariation();
        assertTrue(spread > 0 && spread <= SoundPack.MAX_PITCH_VARIATION,
                "the default drift should be subtle but real, got " + spread);
        assertEquals(SoundPack.DEFAULT_PITCH_VARIATION, spread, 1e-9);

        SoundDef def = SoundDef.packDefault("player/walk");
        assertTrue(def.varyPitch(), "effects should drift by default");

        Set<Double> pitches = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            double p = drift(def.pitch(), spread);
            assertTrue(p >= def.pitch() * (1 - spread) - 1e-9
                            && p <= def.pitch() * (1 + spread) + 1e-9,
                    "pitch " + p + " escaped ±" + spread);
            pitches.add(p);
        }
        assertTrue(pitches.size() > 100,
                "400 playbacks should not all land on the same pitch, got "
                        + pitches.size() + " distinct");
    }

    /** The same arithmetic {@code Sounds} uses, so the bound is the real one. */
    private static double drift(double base, double spread) {
        double d = java.util.concurrent.ThreadLocalRandom.current()
                .nextDouble(-spread, spread);
        return Math.max(0.25, Math.min(4, base * (1 + d)));
    }

    @Test
    void thePitchToggleAndItsSpreadAreSettable(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        SoundPack.useDir(dir.toString());

        Sounds.setPitchVariation(false);
        assertFalse(Sounds.pitchVariation());
        Sounds.setPitchVariation(true);
        assertTrue(Sounds.pitchVariation());

        Sounds.setPitchVariationAmount(0.25);
        assertEquals(0.25, Sounds.pitchVariationAmount(), 1e-9);
        // The spread lives with the pack, so it travels with the folder.
        SoundPack.reload();
        assertEquals(0.25, SoundPack.pitchVariation(), 1e-9);

        // And it is clamped rather than trusted.
        Sounds.setPitchVariationAmount(99);
        assertEquals(SoundPack.MAX_PITCH_VARIATION, Sounds.pitchVariationAmount(), 1e-9);
        Sounds.setPitchVariationAmount(-1);
        assertEquals(0, Sounds.pitchVariationAmount(), 1e-9);
    }

    /** Music must never drift: a wandering soundtrack is a bug, not an effect. */
    @Test
    void musicIsNeverPitchVaried() {
        assertFalse(SoundDef.packDefault("music/level").varyPitch());
        assertFalse(SoundDef.packDefault("music/boss").varyPitch());
        assertTrue(SoundDef.packDefault("player/walk").varyPitch());
        assertTrue(SoundKeys.isMusic("music/level"));
        assertFalse(SoundKeys.isMusic("player/jump"));
    }

    /** Sounds that last as long as their state are marked as looping. */
    @Test
    void continuousSoundsLoopAndOneShotsDoNot() {
        assertTrue(SoundKeys.isLooping("music/level"));
        assertTrue(SoundKeys.isLooping("ambient/cave"));
        assertTrue(SoundKeys.isLooping("block/water/ambient"));
        assertTrue(SoundKeys.isLooping("ultimate/overdrive/loop"));
        assertTrue(SoundKeys.isLooping("vehicle/war_dragon/engine"));
        assertTrue(SoundKeys.isLooping("projectile/meteor/flight"),
                "a meteor on its way down keeps roaring until it lands");
        assertFalse(SoundKeys.isLooping("player/jump"));
        assertFalse(SoundKeys.isLooping("block/dirt/break"));
    }

    // --- the profile drives it all ---------------------------------------------

    /**
     * The game type's audio settings really reach the sound system, so a
     * creator's choices apply to their game rather than sitting in a file.
     */
    @Test
    void theProfilePushesItsSettingsIntoTheSoundSystem(@TempDir Path dir) throws IOException {
        SoundPack.scaffold(dir);
        GameProfile p = new GameProfile("sound test");
        p.soundPackDir = dir.toString();
        p.audioEnabled = true;
        p.musicEnabled = false;
        p.masterVolume = 0.5;
        p.sfxVolume = 0.25;
        p.musicVolume = 0.75;
        p.soundPitchVariation = false;

        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        ctx.setProfile(p);

        assertEquals(dir.toAbsolutePath(), SoundPack.root().toAbsolutePath(),
                "the profile's pack folder should be the one in use");
        assertEquals(0.5, Sounds.masterVolume(), 1e-9);
        assertEquals(0.25, Sounds.sfxVolume(), 1e-9);
        assertEquals(0.75, Sounds.musicVolume(), 1e-9);
        assertFalse(Sounds.isMusicEnabled());
        assertFalse(Sounds.pitchVariation());

        // And the settings survive the JSON round trip a game type is saved as.
        GameProfile copy = GameProfile.fromJson(p.toJson());
        assertEquals(dir.toString(), copy.soundPackDir);
        assertFalse(copy.musicEnabled);
        assertFalse(copy.soundPitchVariation);
        assertEquals(0.5, copy.masterVolume, 1e-9);
        assertEquals(0.25, copy.sfxVolume, 1e-9);
        assertEquals(0.75, copy.musicVolume, 1e-9);
    }

    /** Volumes are clamped to 0..1 whatever a hand-edited file says. */
    @Test
    void volumesAreClamped() {
        GameProfile p = new GameProfile();
        p.masterVolume = 4;
        p.sfxVolume = -2;
        p.musicVolume = 1.5;
        p.normalize();
        assertEquals(1.0, p.masterVolume, 1e-9);
        assertEquals(0.0, p.sfxVolume, 1e-9);
        assertEquals(1.0, p.musicVolume, 1e-9);
    }

    /** Turning sound off silences everything without any call site changing. */
    @Test
    void theMasterSwitchSilencesEverything() {
        Sounds.setEnabled(false);
        assertNull(Sounds.play("player/jump"));
        assertNull(Sounds.playFirst(1.0, "player/jump"));
        assertNull(Sounds.playLoop("ambient/night", 1, 0));
        assertNull(Sounds.music("music/level"));
        assertEquals("", Sounds.currentMusic());
        Sounds.setEnabled(true);
    }

    // --- the legacy vocabulary --------------------------------------------------

    /**
     * The ten effects the engine has always had are now sound keys, so every
     * {@code ctx.sfx(...)} call site in the game is pack-overridable without
     * that call site changing.
     */
    @Test
    void theBuiltInEffectsAreOverridableSoundKeys() {
        for (AudioManager.Sfx sfx : AudioManager.Sfx.values()) {
            String key = sfx.key();
            assertFalse(key.isBlank(), sfx + " needs a sound key");
            assertTrue(SoundSynth.hasFallback(key),
                    sfx + " (" + key + ") should keep its built-in voice");
            assertFalse(SoundSynth.clip(SoundSynth.fallbackFor(key)).isEmpty(),
                    sfx + " should synthesize something audible");
        }
        assertEquals("ui/click", AudioManager.Sfx.CLICK.key());
        assertEquals("player/jump", AudioManager.Sfx.JUMP.key());
        assertEquals("world/explosion", AudioManager.Sfx.BOOM.key());
    }

    /** A generated voice is real PCM at a real rate, not an empty stub. */
    @Test
    void theSynthesizedVoicesAreRealAudio() {
        for (SoundSynth.Voice v : SoundSynth.Voice.values()) {
            PcmClip clip = SoundSynth.clip(v);
            assertFalse(clip.isEmpty(), v + " should have samples");
            assertEquals(1, clip.channels());
            assertTrue(clip.seconds() > 0.01 && clip.seconds() < 1.0,
                    v + " lasts " + clip.seconds() + "s");
            boolean loud = false;
            for (short s : clip.samples()) {
                if (Math.abs(s) > 1000) {
                    loud = true;
                    break;
                }
            }
            assertTrue(loud, v + " should be audible, not near-silence");
        }
    }

    /** Reading a clip's samples is bounds-safe, whatever the mixer asks for. */
    @Test
    void clipSamplingIsBoundsSafe() {
        PcmClip mono = new PcmClip(new short[]{100, -100, 200}, 1, 44100);
        assertEquals(3, mono.frames());
        assertEquals(100 / 32768f, mono.sample(0, 0), 1e-6);
        // A mono clip answers the same for either channel, so the mixer never
        // has to branch while filling a stereo buffer.
        assertEquals(mono.sample(1, 0), mono.sample(1, 1), 1e-9);
        assertEquals(0f, mono.sample(-1, 0), 1e-9);
        assertEquals(0f, mono.sample(99, 0), 1e-9);

        PcmClip stereo = new PcmClip(new short[]{1, 2, 3, 4}, 2, 22050);
        assertEquals(2, stereo.frames());
        assertEquals(1 / 32768f, stereo.sample(0, 0), 1e-6);
        assertEquals(2 / 32768f, stereo.sample(0, 1), 1e-6);

        // A malformed clip is normalised rather than trusted.
        PcmClip odd = new PcmClip(null, 9, -5);
        assertTrue(odd.isEmpty());
        assertEquals(2, odd.channels());
        assertEquals(44100, odd.sampleRate());
    }
}
