package com.larsons.engine.config;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.core.Engine;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.net.NetSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, app-wide state passed to scenes: the active {@link GameProfile} (game
 * type) and the {@link GameTypeStore} used to persist it. Also applies live
 * settings — the render frame cap and the shader chain — to the running
 * {@link Engine}, and carries the active multiplayer {@link NetSession} (if
 * any) between the multiplayer menu and the play scene.
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

    /** The shared lighting pass; scenes set darkness + screen-space lights on it. */
    public LightingPass lighting() { return lighting; }

    /** Sound effects (no-op when the profile's audio toggle is off or headless). */
    public AudioManager audio() { return audio; }

    /** Play a sound if the active profile has audio enabled. */
    public void sfx(AudioManager.Sfx sfx) {
        if (profile().audioEnabled) audio.play(sfx);
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
        }
        if (engine != null && profile != null) {
            engine.setTargetFps(profile.maxFps);
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
