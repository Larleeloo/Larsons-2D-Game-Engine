package com.larsons.engine.config;

import com.larsons.engine.core.Engine;

/**
 * Shared, app-wide state passed to scenes: the active {@link GameProfile} (game
 * type) and the {@link GameTypeStore} used to persist it. Also applies live
 * settings — currently the render frame cap — to the running {@link Engine}.
 *
 * <p>The {@code Engine} reference is optional so this can be used in headless
 * tests without a window/loop.
 */
public class GameContext {

    private final GameTypeStore store;
    private final Engine engine; // may be null (tests)
    private GameProfile profile;

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

    /** Push profile settings that affect the running engine (e.g. FPS cap). */
    public void applyLiveSettings() {
        if (engine != null && profile != null) {
            profile.normalize();
            engine.setTargetFps(profile.maxFps);
        }
    }

    /** Save the active profile to the store. */
    public void save() {
        if (profile != null) store.save(profile);
    }
}
