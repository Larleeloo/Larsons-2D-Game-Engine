package com.larsons.engine.config;

import com.larsons.engine.core.Engine;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
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

    public GameContext(Engine engine, GameTypeStore store) {
        this.engine = engine;
        this.store = store;
    }

    public GameTypeStore store() { return store; }

    public GameProfile profile() {
        if (profile == null) profile = new GameProfile();
        return profile;
    }

    public void setProfile(GameProfile profile) {
        this.profile = profile;
        applyLiveSettings();
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
        if (engine != null && profile != null) {
            profile.normalize();
            engine.setTargetFps(profile.maxFps);
            syncShaders(engine.shaders(), profile);
        }
    }

    /** Save the active profile to the store. */
    public void save() {
        if (profile != null) store.save(profile);
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
            chain.setPasses(p.shadersEnabled ? shaderPassesFor(p) : List.of());
        }
    }

    private static String shaderSignature(GameProfile p) {
        return "" + p.shadersEnabled + p.shaderPixelate + p.shaderPixelSize + p.shaderWave
                + p.shaderChromatic + p.shaderBloom + p.shaderGrayscale
                + p.shaderScanlines + p.shaderVignette;
    }

    /**
     * The passes a profile's toggles select, in application order: distortions
     * first (pixelate, wave), then color (chromatic aberration, bloom,
     * grayscale), then screen-space overlays (scanlines, vignette).
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
