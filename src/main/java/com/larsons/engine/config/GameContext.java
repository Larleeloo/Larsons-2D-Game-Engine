package com.larsons.engine.config;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.SoundMixer;
import com.larsons.engine.audio.SoundPack;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.core.Engine;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.net.NetSession;
import com.larsons.engine.profile.DisplayCap;
import com.larsons.engine.profile.FrameProfiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, app-wide state passed to scenes: the active {@link GameProfile} (game
 * type) and the {@link GameTypeStore} used to persist it. Also applies live
 * settings — the render frame cap and the shader chain — to the running
 * {@link Engine}, and carries the active multiplayer {@link NetSession} (if
 * any) between the multiplayer menu and the play scene.
 *
 * <p>Also the way scenes make noise: {@link #sound(String)} plays any
 * {@link SoundKeys} key through the drop-in {@link SoundPack}, and
 * {@link #music(String)} runs the level's track. Both are gated by the
 * profile's audio toggles, which {@link #applyLiveSettings()} pushes into the
 * sound system alongside the render settings.
 *
 * <p>The {@code Engine} reference is optional so this can be used in headless
 * tests without a window/loop.
 */
public class GameContext {

    private final GameTypeStore store;
    private final Engine engine; // may be null (tests)
    private GameProfile profile;
    private NetSession session; // null when playing offline
    private String lastShaderSig;

    // Cross-cutting feature services scenes share: the lighting shader pass
    // (one persistent instance so scenes can feed it lights every frame) and
    // the synthesized sound effects, both gated by profile toggles.
    private final LightingPass lighting = new LightingPass();
    private final AudioManager audio = new AudioManager();

    public GameContext(Engine engine, GameTypeStore store) {
        this.engine = engine;
        this.store = store;
    }

    public GameTypeStore store() { return store; }

    /**
     * A profiler with nothing attached, for headless use — tests and the
     * dedicated server build a context with no engine, and a scene should not
     * have to check before timing itself.
     */
    private static final FrameProfiler NO_PROFILER = new FrameProfiler();

    /**
     * Frame instrumentation, so a scene can name the phases of its own drawing.
     * Disabled (and therefore free) unless someone has turned profiling on.
     */
    public FrameProfiler profiler() {
        return engine == null ? NO_PROFILER : engine.profiler();
    }

    /** The shared lighting pass; scenes set darkness + screen-space lights on it. */
    public LightingPass lighting() { return lighting; }

    /** Sound effects (no-op when the profile's audio toggle is off or headless). */
    public AudioManager audio() { return audio; }

    /** Play one of the engine's built-in effects if audio is enabled. */
    public void sfx(AudioManager.Sfx sfx) {
        if (profile().audioEnabled) audio.play(sfx);
    }

    /**
     * Play a sound by {@link SoundKeys} key — the general form of
     * {@link #sfx}, and what gameplay code should use so a creator can give
     * that one object and action its own audio. Unknown or unsupplied keys
     * are silent, so a caller never has to check first.
     */
    public void sound(String key) {
        if (profile().audioEnabled) Sounds.play(key);
    }

    /** {@link #sound(String)} at a scaled volume (a softer, farther event). */
    public void sound(String key, double volumeScale) {
        if (profile().audioEnabled) Sounds.play(key, volumeScale);
    }

    /**
     * Make {@code key} the music that is playing. Safe to call every frame:
     * asking for the track already playing does nothing, and a blank key
     * stops the music.
     */
    public void music(String key) {
        if (profile().audioEnabled && profile().musicEnabled) Sounds.music(key);
        else Sounds.stopMusic();
    }

    /** Stop everything the mixer is playing (leaving a level, shutting down). */
    public void stopSounds() {
        Sounds.stopAll();
    }

    public GameProfile profile() {
        if (profile == null) profile = new GameProfile();
        return profile;
    }

    public void setProfile(GameProfile profile) {
        this.profile = profile;
        applyLiveSettings();
    }

    /**
     * Make a level's own saved settings the active configuration. The feature
     * toggles come from the level; the game-type identity (name, texture pack,
     * last-level pointer) stays put, so the same game type — the folder the
     * level lives in — remains selected. A {@code null} argument (a legacy
     * level with no settings of its own) leaves the active profile untouched,
     * so it plays with the game type's profile as before.
     */
    public void applyLevelSettings(GameProfile levelSettings) {
        if (levelSettings == null) return;
        profile().applyFeaturesFrom(levelSettings);
        applyLiveSettings();
    }

    // --- creative mode selection ------------------------------------------------

    /**
     * The level format the next creative session should build in, set by the
     * main menu's per-format creative entries and consumed by the creative
     * scene. {@code null} means "carry on with the level already being
     * edited" — which is what re-entering creative mode from a paused game
     * does, so it doesn't restart the format the creator is in.
     */
    private LevelFormat creativeFormat;

    /** Ask for the next creative session to open in {@code format}. */
    public void setCreativeFormat(LevelFormat format) {
        this.creativeFormat = format;
    }

    /** The requested creative format, cleared as it is read (may be {@code null}). */
    public LevelFormat takeCreativeFormat() {
        LevelFormat requested = creativeFormat;
        creativeFormat = null;
        return requested;
    }

    // --- character selection ------------------------------------------------------

    /**
     * The character profile the player last chose at a level's start. Levels
     * offer their own roster, so this is only a preference: the picker
     * pre-selects it when the level allows it, and the creative play-test uses
     * it so testing a level plays as the character you were just playing.
     */
    private String characterKey = "";

    public String character() { return characterKey; }

    public void setCharacter(String key) {
        this.characterKey = key == null ? "" : key;
    }

    /** The active multiplayer session, or {@code null} when playing offline. */
    public NetSession session() { return session; }

    public void setSession(NetSession session) { this.session = session; }

    /** Tear down the active multiplayer session (client + hosted server), if any. */
    public void closeSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    /** Push profile settings that affect the running engine (FPS cap, shaders). */
    public void applyLiveSettings() {
        if (profile != null) {
            profile.normalize();
            audio.setEnabled(profile.audioEnabled);
            // A game type may keep its texture or sound pack somewhere of its
            // own; blank means the folder beside the jar (the default pack).
            TexturePack.useDir(profile.texturePackDir);
            SoundPack.useDir(profile.soundPackDir);
            Sounds.setMusicEnabled(profile.musicEnabled);
            Sounds.setMasterVolume(profile.masterVolume);
            Sounds.setSfxVolume(profile.sfxVolume);
            Sounds.setMusicVolume(profile.musicVolume);
            Sounds.setPitchVariation(profile.soundPitchVariation);
        }
        if (engine != null && profile != null) {
            // The level says what is allowed; the machine in front of the
            // player picks inside it. See DisplayCap for why those are two
            // decisions and only one of them belongs to the level.
            engine.setTargetFps(DisplayCap.forDisplay(
                    engine.device(), profile.minFps, profile.maxFps));
            syncShaders(engine.shaders(), profile);
        }
    }

    /** Save the active profile to the store. */
    public void save() {
        if (profile != null) store.save(profile);
    }

    /**
     * Replace the engine's shader chain with a mode-specific look — used by
     * standalone modes like the auto-battler, which always runs with its own
     * post-FX regardless of the active game type's toggles. The next
     * {@link #applyLiveSettings()} rebuilds the chain from the profile, so
     * leaving the mode restores the game type's shaders.
     */
    public void overrideShaders(List<ShaderPass> passes, double strength) {
        lastShaderSig = null; // force the next profile sync to rebuild
        if (engine != null) {
            engine.shaders().setStrength((float) strength);
            engine.shaders().setPasses(passes);
        }
    }

    /**
     * Bring the engine's {@link ShaderChain} in line with the profile's shader
     * toggles. Strength is pushed every call (cheap); the pass list is only
     * rebuilt when a toggle actually changed, so calling this every frame from
     * the pause menu costs nothing.
     */
    private void syncShaders(ShaderChain chain, GameProfile p) {
        if (chain == null) return;
        chain.setStrength((float) p.shaderStrength);
        String sig = shaderSignature(p);
        if (!sig.equals(lastShaderSig)) {
            lastShaderSig = sig;
            List<ShaderPass> passes = new ArrayList<>();
            // Lighting is gameplay, not post-FX: it rides the same chain (so it
            // composes with every effect and GPU backends get it for free) but
            // has its own toggle, independent of the post-FX master switch.
            if (p.lightingEnabled) passes.add(lighting);
            if (p.shadersEnabled) passes.addAll(shaderPassesFor(p));
            chain.setPasses(passes);
        }
    }

    private static String shaderSignature(GameProfile p) {
        return "" + p.shadersEnabled + p.shaderPixelate + p.shaderPixelSize + p.shaderWave
                + p.shaderChromatic + p.shaderBloom + p.shaderGrayscale
                + p.shaderScanlines + p.shaderVignette + p.lightingEnabled;
    }

    /**
     * The post-FX passes a profile's toggles select, in application order:
     * distortions first (pixelate, wave), then color (chromatic aberration,
     * bloom, grayscale), then screen-space overlays (scanlines, vignette).
     * (The lighting pass is separate — see {@link #lighting()}.)
     */
    public static List<ShaderPass> shaderPassesFor(GameProfile p) {
        List<ShaderPass> passes = new ArrayList<>();
        if (p.shaderPixelate) passes.add(Shaders.pixelate(p.shaderPixelSize));
        if (p.shaderWave) passes.add(Shaders.wave());
        if (p.shaderChromatic) passes.add(Shaders.chromaticAberration());
        if (p.shaderBloom) passes.add(Shaders.bloom());
        if (p.shaderGrayscale) passes.add(Shaders.grayscale());
        if (p.shaderScanlines) passes.add(Shaders.scanlines());
        if (p.shaderVignette) passes.add(Shaders.vignette());
        return passes;
    }
}
