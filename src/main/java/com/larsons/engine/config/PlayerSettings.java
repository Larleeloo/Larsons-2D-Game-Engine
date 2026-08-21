package com.larsons.engine.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings that belong to <em>the person playing</em>, as opposed to the
 * ones that belong to a game type or a level.
 *
 * <p>{@link com.larsons.engine.input.KeyBinds} already drew this line and drew
 * it correctly — "binds are a property of the person playing, not of a game
 * type, so there is a single file rather than one per profile". Audio never
 * got the same treatment: volume lived on {@link GameProfile}, which
 * {@code captureSettings} writes into every level file and
 * {@link GameProfile#applyFeaturesFrom} copies back out on load. The
 * consequence was that opening a level someone else authored replaced your
 * volume with theirs, and walking through a door between two levels saved at
 * different volumes changed the mix under you mid-run. Volume lives here now,
 * beside the two look settings that were previously constants in the play
 * scene and the HUD scale that was previously nothing at all.
 *
 * <p>The test for whether something belongs in this class is simple: <b>would
 * a player be annoyed if loading someone else's level changed it?</b> Volume,
 * mouse sensitivity, an inverted Y axis and HUD size all pass. Whether the
 * game has a day/night cycle does not — that is the level's business, and it
 * stays on {@link GameProfile}.
 *
 * <p>One deliberate omission: {@link GameProfile#soundPitchVariation} stays
 * with the game type. Slight per-play pitch drift is a stylistic decision a
 * creator makes about how their game sounds, not a comfort setting a player
 * makes about their own machine.
 *
 * @see PlayerSettingsStore for the file it lives in
 */
public final class PlayerSettings {

    /** The settings in force, installed at startup by {@code Main}. */
    private static PlayerSettings active = new PlayerSettings();

    public static PlayerSettings active() { return active; }

    /**
     * Make {@code settings} the ones in force. A {@code null} argument installs
     * the defaults rather than leaving nothing behind — the game must always
     * have a volume to play at.
     */
    public static void install(PlayerSettings settings) {
        active = settings == null ? new PlayerSettings() : settings;
        active.normalize();
    }

    // --- audio -------------------------------------------------------------

    /** Everything, 0..1. */
    public double masterVolume = 1.0;
    /** Sound effects, 0..1. */
    public double sfxVolume = 1.0;
    /** Music, 0..1. */
    public double musicVolume = 0.6;

    // --- looking around ----------------------------------------------------

    /**
     * Mouse-look speed multiplier for the {@code [F5]} first- and third-person
     * views, against the engine's base of 0.22° per pixel. Bounded rather than
     * free: a sensitivity of zero is a camera that cannot turn, which is
     * indistinguishable from a broken game.
     */
    public double lookSensitivity = 1.0;

    /** Push the mouse forward to look up, the way flight sticks work. */
    public boolean invertLook = false;

    // --- presentation ------------------------------------------------------

    /**
     * HUD size multiplier. Every HUD dimension in the engine is a constant
     * against a pixel viewport, which is legible at 720p and unreadable at 4K;
     * this is the one number that fixes that without re-deriving each of them.
     */
    public double hudScale = 1.0;

    /**
     * Draw the world past the ordinary view distance, coarsely.
     *
     * <p>What Distant Horizons does for Minecraft, and for the same reason: a
     * render distance chosen so that a machine can draw every block of it is a
     * render distance that ends in fog a few dozen paces out, and a landscape
     * that ends a few dozen paces out is not a landscape. Past the detailed
     * distance the terrain is drawn as one box per group of cells, at the
     * height of the tallest column in it and in that block's colour — no
     * textures, no per-block faces, no edges. A mountain range on the horizon
     * costs a few hundred flat quads, which is what makes it affordable at all.
     *
     * <p>A player setting rather than a level one: it is a statement about the
     * machine in front of the person playing, and the same level has to be
     * playable on both. Off by default, because the machine that needs the
     * setting is the one that cannot afford it turned on.
     */
    public boolean distantTerrain = false;

    public static final double MIN_SENSITIVITY = 0.1;
    public static final double MAX_SENSITIVITY = 5.0;
    public static final double MIN_HUD_SCALE = 0.75;
    public static final double MAX_HUD_SCALE = 3.0;

    public PlayerSettings copy() {
        return fromMap(toMap());
    }

    /** Clamp everything into a range the game still works in. */
    public void normalize() {
        masterVolume = clamp01(masterVolume);
        sfxVolume = clamp01(sfxVolume);
        musicVolume = clamp01(musicVolume);
        lookSensitivity = clamp(lookSensitivity, MIN_SENSITIVITY, MAX_SENSITIVITY);
        hudScale = clamp(hudScale, MIN_HUD_SCALE, MAX_HUD_SCALE);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("masterVolume", masterVolume);
        m.put("sfxVolume", sfxVolume);
        m.put("musicVolume", musicVolume);
        m.put("lookSensitivity", lookSensitivity);
        m.put("invertLook", invertLook);
        m.put("hudScale", hudScale);
        m.put("distantTerrain", distantTerrain);
        return m;
    }

    /**
     * Read a settings file. Every field falls back to its default, so a file
     * written by an older build (or a hand-edited one missing half its keys) is
     * a partial answer rather than an error — the same rule the key-bind and
     * skin stores follow.
     */
    public static PlayerSettings fromMap(Map<String, Object> m) {
        PlayerSettings s = new PlayerSettings();
        if (m == null) return s;
        s.masterVolume = dbl(m, "masterVolume", s.masterVolume);
        s.sfxVolume = dbl(m, "sfxVolume", s.sfxVolume);
        s.musicVolume = dbl(m, "musicVolume", s.musicVolume);
        s.lookSensitivity = dbl(m, "lookSensitivity", s.lookSensitivity);
        s.invertLook = m.get("invertLook") instanceof Boolean b ? b : s.invertLook;
        s.hudScale = dbl(m, "hudScale", s.hudScale);
        s.distantTerrain = m.get("distantTerrain") instanceof Boolean d
                ? d : s.distantTerrain;
        s.normalize();
        return s;
    }

    private static double dbl(Map<String, Object> m, String key, double fallback) {
        return m.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double clamp01(double v) { return clamp(v, 0, 1); }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
