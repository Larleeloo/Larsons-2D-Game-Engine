package com.larsons.engine.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The sound system's front door: play a sound by <em>key</em> and this
 * resolves what should actually be heard, applies the creator's settings and
 * the subtle per-playback pitch drift, and hands it to the mixer.
 *
 * <p>This is the audio counterpart of {@link com.larsons.engine.graphics.Skins}
 * and resolves a key the same way:
 *
 * <ol>
 *   <li>the creator's explicit file for this key, when the pack is switched
 *       off for it ({@link SoundDef#usePack});</li>
 *   <li>the {@link SoundPack} folder's file for the key — the normal case,
 *       and what a creator gets by dropping a WAV or MP3 in with the right
 *       name;</li>
 *   <li>the creator's explicit file as the pack's fallback;</li>
 *   <li>the engine's built-in synthesized voice, for the handful of actions
 *       that have one ({@link SoundSynth});</li>
 *   <li><b>silence</b> — the default for every other sound in the game.</li>
 * </ol>
 *
 * <p><b>Fresh pitch.</b> Every one-shot plays at a slightly different pitch,
 * drawn per playback from ±{@link SoundPack#pitchVariation()} — the trick
 * Minecraft uses so a run of footsteps or block breaks never sounds like a
 * stuck record. It is a toggle ({@link #setPitchVariation}) wired to the
 * sound editor and the game type's settings, and music is never varied.
 *
 * <p><b>Music</b> is a sound state like any other ({@code music/level},
 * {@code music/boss}…) but only one plays at a time: {@link #music} swaps the
 * track, keeps it looping, and does nothing when asked for the track already
 * playing, so a scene can call it every frame.
 *
 * <p>Everything here is safe to call from the game loop and safe on a machine
 * with no audio device at all.
 */
public final class Sounds {

    private static final Map<String, SoundDef> DEFS = new LinkedHashMap<>();
    private static final SoundMixer MIXER = new SoundMixer();

    private static boolean enabled = true;
    private static boolean musicEnabled = true;
    private static boolean pitchVariation = true;
    private static double sfxVolume = 1.0;
    private static double musicVolume = 0.6;

    private static String musicKey = "";
    private static SoundMixer.Voice musicVoice;

    private Sounds() {}

    // --- master switches ---------------------------------------------------------

    /** Master switch, wired to the game type's audio toggle. */
    public static synchronized void setEnabled(boolean on) {
        enabled = on;
        if (!on) stopAll();
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    /** Music switch, separate from effects so a creator can silence one. */
    public static synchronized void setMusicEnabled(boolean on) {
        musicEnabled = on;
        if (!on) stopMusic();
    }

    public static synchronized boolean isMusicEnabled() {
        return musicEnabled;
    }

    /**
     * Turn the per-playback pitch drift on or off. On is the default and is
     * what keeps repeated sounds from sounding mechanical.
     */
    public static synchronized void setPitchVariation(boolean on) {
        pitchVariation = on;
    }

    public static synchronized boolean pitchVariation() {
        return pitchVariation;
    }

    /** How far pitch drifts either way, as a fraction (0 = not at all). */
    public static double pitchVariationAmount() {
        return SoundPack.pitchVariation();
    }

    /** Set the drift, saved into the sound pack so it travels with it. */
    public static void setPitchVariationAmount(double amount) {
        SoundPack.setPitchVariation(amount);
    }

    /** Overall level of sound effects, 0..1. */
    public static synchronized void setSfxVolume(double v) {
        sfxVolume = clamp(v);
    }

    public static synchronized double sfxVolume() {
        return sfxVolume;
    }

    /** Overall level of music, 0..1 (applied to the playing track at once). */
    public static synchronized void setMusicVolume(double v) {
        musicVolume = clamp(v);
        if (musicVoice != null) musicVoice.setVolume(trackVolume());
    }

    public static synchronized double musicVolume() {
        return musicVolume;
    }

    /** Master level over everything, 0..1. */
    public static void setMasterVolume(double v) {
        MIXER.setMasterVolume(clamp(v));
    }

    public static double masterVolume() {
        return MIXER.masterVolume();
    }

    /** The mixer, for the sound editor's diagnostics. */
    public static SoundMixer mixer() {
        return MIXER;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    // --- playing -----------------------------------------------------------------

    /** Play {@code key} once (or start it looping, if that is what it is). */
    public static SoundMixer.Voice play(String key) {
        return play(key, 1.0, 0.0);
    }

    /**
     * Play {@code key} at a scaled volume — for effects that should be
     * quieter at a distance or softer for a glancing blow.
     */
    public static SoundMixer.Voice play(String key, double volumeScale) {
        return play(key, volumeScale, 0.0);
    }

    /**
     * Play {@code key} at a scaled volume and a stereo position.
     *
     * @param pan -1 hard left … 0 centred … +1 hard right
     */
    public static SoundMixer.Voice play(String key, double volumeScale, double pan) {
        if (!isEnabled()) return null;
        if (SoundKeys.isMusic(key)) return music(key);
        SoundDef def = definition(key);
        return start(def, resolve(key, def), volumeScale, pan, def.loop());
    }

    /**
     * Hand an already-resolved sound to the mixer. Split out so the
     * fallback-chain callers below can resolve a key <em>once</em>: they have
     * to resolve it anyway to find out whether it is silent, and re-resolving
     * it to play would double the work on every footstep.
     */
    private static SoundMixer.Voice start(SoundDef def, PcmClip clip,
                                          double volumeScale, double pan, boolean loop) {
        if (clip.isEmpty()) return null;
        double volume = def.volume() * volumeScale * sfxVolume();
        if (volume <= 0) return null;
        return MIXER.play(clip, pitchFor(def), volume, pan, loop);
    }

    /**
     * Play the first of {@code keys} that actually has audio, and nothing at
     * all if none of them do.
     *
     * <p>This is how the engine asks for the specific sound first and settles
     * for the general one: a footstep asks for {@code block/stone/step}, then
     * {@code character/rogue/walk}, then {@code player/walk}. A creator who
     * supplies only {@code player/walk.wav} hears it everywhere; one who adds
     * {@code blocks/stone_step.wav} hears stone underfoot without losing the
     * rest. Blank keys are skipped, so callers can pass a key they only
     * sometimes have.
     */
    public static SoundMixer.Voice playFirst(double volumeScale, String... keys) {
        return first(volumeScale, false, keys);
    }

    /**
     * Play the most specific sound available for something a character did:
     * <b>the object's own sound</b>, then <b>this character's voice</b> for
     * the action, then <b>the plain player's</b>.
     *
     * <p>This is the rule behind nearly every trigger in the game, in one
     * place: breaking stone asks for {@code block/stone/break}, then
     * {@code character/rogue/mine_break}, then {@code player/mine_break}.
     * Pass a blank {@code objectKey} for actions no object is involved in (a
     * jump, a landing).
     *
     * <p>Having it here rather than copied into each scene is what keeps a
     * level's play-test sounding like the level being played — both call
     * this, so neither can quietly lose a tier.
     *
     * @param characterKey the character acting; blank means the default player
     * @param objectKey    the specific object's sound key, or {@code ""}
     * @param playerState  the {@link SoundKeys#PLAYER_STATES} action to fall
     *                     back to
     */
    public static SoundMixer.Voice actor(String characterKey, String objectKey,
                                         String playerState, double volumeScale) {
        String character = SoundKeys.character(characterKey, playerState);
        String player = SoundKeys.player(playerState);
        // A blank character key makes those two the same key; skip the twin
        // so the common case resolves once instead of twice.
        return character.equals(player)
                ? playFirst(volumeScale, objectKey, player)
                : playFirst(volumeScale, objectKey, character, player);
    }

    /** {@link #actor} at full volume, for the usual case. */
    public static SoundMixer.Voice actor(String characterKey, String objectKey,
                                         String playerState) {
        return actor(characterKey, objectKey, playerState, 1.0);
    }

    /** {@link #playFirst} for a sound that repeats until its voice is stopped. */
    public static SoundMixer.Voice playLoopFirst(double volumeScale, String... keys) {
        return first(volumeScale, true, keys);
    }

    /** Walk the candidates, resolving each at most once, and play the first. */
    private static SoundMixer.Voice first(double volumeScale, boolean forceLoop,
                                          String... keys) {
        if (!isEnabled() || keys == null) return null;
        for (String key : keys) {
            if (key == null || key.isEmpty()) continue;
            if (SoundKeys.isMusic(key)) return music(key);
            SoundDef def = definition(key);
            PcmClip clip = resolve(key, def);
            if (clip.isEmpty()) continue;
            return start(def, clip, volumeScale, 0, forceLoop || def.loop());
        }
        return null;
    }

    /**
     * Start {@code key} looping regardless of its own loop setting, for a
     * sound that lasts exactly as long as the state that started it — a swim,
     * a sustained ultimate, a meteor's descent. Returns the voice to stop, or
     * {@code null} when the key is silent.
     */
    public static SoundMixer.Voice playLoop(String key, double volumeScale, double pan) {
        if (!isEnabled()) return null;
        SoundDef def = definition(key);
        return start(def, resolve(key, def), volumeScale, pan, true);
    }

    /**
     * Play {@code key} positioned by where it happened relative to the
     * listener: sounds off the side of the screen come from that side, and
     * anything far away is quieter. {@code halfWidth} is half the viewport in
     * world pixels — the distance at which a sound is fully panned.
     */
    public static SoundMixer.Voice playAt(String key, double dx, double dy, double halfWidth) {
        return playAt(key, dx, dy, halfWidth, 1.0);
    }

    /**
     * {@link #playAt(String, double, double, double)} with an extra volume
     * scale, for events that are quieter than a full-strength one even up
     * close — a mob's footfall against its death cry.
     *
     * <p>This is the only distance/pan model in the engine: everything that
     * happens somewhere in the world goes through it, so a mob and a meteor
     * fade the same way.
     */
    public static SoundMixer.Voice playAt(String key, double dx, double dy,
                                          double halfWidth, double volumeScale) {
        if (halfWidth <= 0) return play(key, volumeScale);
        double pan = Math.max(-1, Math.min(1, dx / halfWidth));
        double distance = Math.hypot(dx, dy) / (halfWidth * 2);
        double falloff = 1.0 / (1.0 + distance * distance * 3);
        return play(key, falloff * volumeScale, pan);
    }

    /**
     * Start (or keep) the level's music. Calling this with the track already
     * playing does nothing, so a scene can call it every frame; calling it
     * with a different key fades the old track out and the new one in. A
     * blank key stops the music.
     */
    public static synchronized SoundMixer.Voice music(String key) {
        String next = key == null ? "" : key;
        if (next.equals(musicKey) && musicVoice != null && musicVoice.isPlaying()) {
            return musicVoice;
        }
        stopMusic();
        // Only remember the track once it is really playing: a request made
        // while music is off, or for a track the pack has nothing for, must
        // leave currentMusic() honest — and must start when it can, which is
        // what the next call does because the key was never taken.
        if (next.isEmpty() || !enabled || !musicEnabled) return null;
        SoundDef def = definition(next);
        PcmClip clip = resolve(next, def);
        if (clip.isEmpty()) return null;
        musicKey = next;
        // Music is never pitch-varied: a drifting soundtrack is a bug, not
        // an effect. Its own pitch setting still applies.
        musicVoice = MIXER.play(clip, def.pitch(), trackVolume(next), 0, true);
        return musicVoice;
    }

    /** The track playing right now ("" when silent). */
    public static synchronized String currentMusic() {
        return musicKey;
    }

    public static synchronized void stopMusic() {
        if (musicVoice != null) musicVoice.stop();
        musicVoice = null;
        musicKey = "";
    }

    private static synchronized double trackVolume() {
        return trackVolume(musicKey);
    }

    private static synchronized double trackVolume(String key) {
        SoundDef def = key == null || key.isEmpty() ? null : definition(key);
        return (def == null ? 1 : def.volume()) * musicVolume;
    }

    /** Stop every sound, music included. */
    public static void stopAll() {
        stopMusic();
        MIXER.stopAll();
    }

    /** Release the audio device (game shutdown). */
    public static void dispose() {
        stopAll();
        MIXER.dispose();
    }

    /**
     * The pitch one playback uses: the sound's own pitch, drifted by a random
     * fraction when the fresh-pitch option is on and this sound allows it.
     */
    private static double pitchFor(SoundDef def) {
        double base = def.pitch();
        if (!pitchVariation() || !def.varyPitch()) return base;
        double spread = SoundPack.pitchVariation();
        if (spread <= 0) return base;
        double drift = ThreadLocalRandom.current().nextDouble(-spread, spread);
        return Math.max(0.25, Math.min(4, base * (1 + drift)));
    }

    // --- resolution --------------------------------------------------------------

    /**
     * What {@code key} actually sounds like right now: the creator's file,
     * the pack's file, the built-in voice, or silence. Public so the sound
     * editor can say which of those a key is currently getting.
     */
    public static PcmClip resolve(String key) {
        return resolve(key, definition(key));
    }

    private static PcmClip resolve(String key, SoundDef def) {
        if (!def.usePack() && !def.file().isEmpty()) {
            PcmClip explicit = SoundLoader.load(resolvePath(def.file()));
            if (!explicit.isEmpty()) return explicit;
        }
        if (def.usePack()) {
            Path packFile = SoundPack.fileFor(key);
            if (packFile != null) {
                PcmClip fromPack = SoundLoader.load(packFile);
                if (!fromPack.isEmpty()) return fromPack;
            }
            if (!def.file().isEmpty()) {
                PcmClip explicit = SoundLoader.load(resolvePath(def.file()));
                if (!explicit.isEmpty()) return explicit;
            }
        }
        if (def.builtin()) {
            SoundSynth.Voice voice = SoundSynth.fallbackFor(key);
            if (voice != null) return SoundSynth.clip(voice);
        }
        return PcmClip.SILENCE;
    }

    /** Where a key's audio comes from right now — what the editor displays. */
    public enum Source {
        /** A file the creator picked, outside the pack. */
        FILE,
        /** The drop-in sound pack folder. */
        PACK,
        /** The engine's own synthesized voice. */
        BUILT_IN,
        /** Nothing — the default for most of the game. */
        SILENT
    }

    /**
     * Which of the four resolution steps {@code key} currently lands on.
     * Answered from what files <em>exist</em>, not by decoding them, so the
     * sound editor can label a list of two thousand sounds without reading
     * two thousand files. (A file that exists but turns out to be unreadable
     * still falls through to the built-in voice or silence when played.)
     */
    public static Source sourceOf(String key) {
        SoundDef def = definition(key);
        boolean hasFile = !def.file().isEmpty()
                && Files.isRegularFile(resolvePath(def.file()));
        if (!def.usePack() && hasFile) return Source.FILE;
        if (def.usePack()) {
            if (SoundPack.fileFor(key) != null) return Source.PACK;
            if (hasFile) return Source.FILE;
        }
        if (def.builtin() && SoundSynth.hasFallback(key)) return Source.BUILT_IN;
        return Source.SILENT;
    }

    /**
     * Resolve a file path: as given when it exists, else relative to the
     * sound pack folder — so a bare {@code my_jump.wav} typed into the
     * editor's field finds the file sitting in the pack.
     */
    public static Path resolvePath(String file) {
        Path direct = Path.of(file);
        if (Files.isRegularFile(direct)) return direct;
        Path inPack = SoundPack.root().resolve(file);
        return Files.isRegularFile(inPack) ? inPack : direct;
    }

    // --- assignments -------------------------------------------------------------

    /** The creator's assignment for {@code key}, or {@code null} if none. */
    public static synchronized SoundDef get(String key) {
        return DEFS.get(key);
    }

    /**
     * The settings {@code key} plays at: the creator's assignment when there
     * is one, else the pack's settings for it. Never {@code null}.
     */
    public static synchronized SoundDef definition(String key) {
        SoundDef def = DEFS.get(key);
        return def != null ? def : SoundDef.packDefault(key);
    }

    public static synchronized void put(SoundDef def) {
        if (def == null || def.key().isEmpty()) return;
        DEFS.put(def.key(), def);
    }

    public static synchronized void remove(String key) {
        DEFS.remove(key);
    }

    /** Every assignment, for saving. */
    public static synchronized List<SoundDef> all() {
        return new ArrayList<>(DEFS.values());
    }

    public static synchronized void clear() {
        DEFS.clear();
    }

    /** Replace the whole assignment set (loading {@code sounds.json}). */
    public static synchronized void setAll(List<SoundDef> defs) {
        DEFS.clear();
        if (defs == null) return;
        for (SoundDef d : defs) {
            if (d != null && !d.key().isEmpty()) DEFS.put(d.key(), d);
        }
    }

    /** Load assignments from the default store — called once at startup. */
    public static void load() {
        setAll(new SoundStore().load());
    }

    /** Save assignments to the default store. */
    public static Path save() {
        return new SoundStore().save(all());
    }
}
