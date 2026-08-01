package com.larsons.engine.config;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named "game type": the set of features a creator enables for their game,
 * plus their values. Profiles are configured on launch (and editable in the
 * pause menu), saved as JSON under {@code resources/gametypes/}, and reloaded to
 * create further levels within the same game type.
 *
 * <p>This is deliberately a flat bag of toggles/values — the engine's "giant
 * custom level loader" reads it to decide which features are active. Add fields
 * here as new features are introduced; {@link #toMap()}/{@link #fromMap} keep
 * JSON in sync and tolerate missing keys (old profiles still load).
 */
public class GameProfile {

    /**
     * The player hitbox as a fraction of a tile: slightly smaller than one
     * block, so the player fits through one-tile gaps without pixel-perfect
     * alignment. {@link #normalize()} locks {@link #playerSize} to this.
     */
    public static final double PLAYER_TILE_FRACTION = 0.9;

    public String name = "New Game Type";

    /**
     * The format new levels start in (requirement #2). A level owns its
     * perspective from the moment it is created and keeps it — the three
     * formats differ in which axis is up, in what a block means, and in how
     * many layers of them a level is written in, so there is no coherent
     * "switch view" for one to offer. Changing perspective is what walking
     * through a door into a level of another format does.
     */
    public Perspective perspective = Perspective.SIDE_SCROLL;

    // Zoom feature + bounds.
    public boolean zoomEnabled = true;
    public double minZoom = 0.5;
    public double maxZoom = 2.0;
    public double defaultZoom = 1.0;

    // Framerate bounds. maxFps is applied as the render cap (requirement #1).
    public int minFps = 30;
    public int maxFps = 120;

    // Gameplay feature toggles.
    public boolean gravityEnabled = true;   // side-scroll jumping/falling
    public boolean hudVisible = true;
    public boolean gridVisible = false;

    // World features (merged in from the Side-Scroller engine).
    public boolean mobsEnabled = true;        // spawn + simulate mobs
    public boolean itemsEnabled = true;       // drops, pickup, inventory + hotbar
    public boolean combatEnabled = true;      // swings hurt mobs, mobs hurt players
    public boolean projectilesEnabled = true; // ranged weapons + throwables fire
    public boolean blockEditingEnabled = true; // mine/place blocks while playing
    public boolean creativeEnabled = true;    // creative mode (paint objects)

    // Lighting (rendered as a shader pass, so it composes with post-FX).
    public boolean lightingEnabled = false;
    public boolean dayNightCycle = false;     // time-driven darkness
    public boolean nightMode = false;         // fixed night when the cycle is off
    public double nightDarkness = 0.55;       // max darkness at night [0,1]
    public double ambientLight = 0.25;        // light floor so night stays readable

    // Atmosphere & feedback.
    public boolean parallaxEnabled = false;   // procedural side-scroll backdrop
    public boolean particlesEnabled = true;   // block-break / hit particles
    public boolean audioEnabled = true;       // sound effects (the master switch)

    // Sound. Every action state in the game can be given a WAV or MP3 from the
    // drop-in sound pack; these are the settings that apply to all of them at
    // once (see com.larsons.engine.audio.Sounds).
    public boolean musicEnabled = true;       // level music, separate from effects
    public double masterVolume = 1.0;         // everything, 0..1
    public double sfxVolume = 1.0;            // sound effects, 0..1
    public double musicVolume = 0.6;          // music, 0..1
    /**
     * Whether every sound plays at a slightly different pitch each time — the
     * trick that keeps a run of footsteps or block breaks from sounding like a
     * stuck record. On by default; the spread itself lives with the sound pack
     * so it travels with the folder.
     */
    public boolean soundPitchVariation = true;

    // The level last saved/played in this game type ("" = bundled sample).
    public String lastLevelPath = "";

    /**
     * A "finalized" (published) game type: its levels are play-only. This is set
     * when a game type is exported as a {@code .larsonsengine} package with the
     * finalize toggle on — the recipient can play the levels but not open them in
     * creative mode or edit their settings. It is a game-type identity property
     * (like {@link #name}), so it survives loading a level's own settings and is
     * never copied by {@link #applyFeaturesFrom}.
     */
    public boolean finalized = false;

    /**
     * The creator's texture pack folder. Blank — the normal case — means the
     * {@code textures/} folder beside the game's jar, so a pack travels with
     * the shared game and needs no setting at all. Point it elsewhere to keep
     * a game type's pack in its own place: that folder then supplies every
     * texture, sprite-sheet browsing starts there, and bare sheet filenames
     * resolve against it (see
     * {@link com.larsons.engine.graphics.TexturePack}).
     */
    public String texturePackDir = "";

    /**
     * The creator's sound pack folder. Blank — the normal case — means the
     * {@code sounds/} folder beside the game's jar, so a pack travels with the
     * shared game and needs no setting at all. Point it elsewhere to keep a
     * game type's sounds in its own place (see
     * {@link com.larsons.engine.audio.SoundPack}).
     */
    public String soundPackDir = "";

    // Sizes of various entities (world pixels). The player's hitbox is kept
    // slightly smaller than one block (see PLAYER_TILE_FRACTION) so it slips
    // into one-tile gaps without pixel-perfect alignment — normalize() keeps
    // playerSize locked to that fraction of tileSize.
    public int tileSize = 32;
    public int playerSize = 28;
    public int defaultEntitySize = 32;

    // Shaders (requirement #5): a master toggle, a global strength, and one
    // toggle per built-in post-processing pass. Passes are applied in a fixed
    // order (distortions -> color -> screen overlays); see GameContext.
    public boolean shadersEnabled = false;
    public double shaderStrength = 1.0;
    public boolean shaderPixelate = false;
    public int shaderPixelSize = 4;
    public boolean shaderWave = false;
    public boolean shaderChromatic = false;
    public boolean shaderBloom = false;
    public boolean shaderGrayscale = false;
    public boolean shaderScanlines = false;
    public boolean shaderVignette = false;

    public GameProfile() {}

    public GameProfile(String name) { this.name = name; }

    /** Serialize to an ordered map suitable for {@link Json#stringify}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("perspective", perspective.name());
        m.put("zoomEnabled", zoomEnabled);
        m.put("minZoom", minZoom);
        m.put("maxZoom", maxZoom);
        m.put("defaultZoom", defaultZoom);
        m.put("minFps", minFps);
        m.put("maxFps", maxFps);
        m.put("gravityEnabled", gravityEnabled);
        m.put("hudVisible", hudVisible);
        m.put("gridVisible", gridVisible);
        m.put("mobsEnabled", mobsEnabled);
        m.put("itemsEnabled", itemsEnabled);
        m.put("combatEnabled", combatEnabled);
        m.put("projectilesEnabled", projectilesEnabled);
        m.put("blockEditingEnabled", blockEditingEnabled);
        m.put("creativeEnabled", creativeEnabled);
        m.put("lightingEnabled", lightingEnabled);
        m.put("dayNightCycle", dayNightCycle);
        m.put("nightMode", nightMode);
        m.put("nightDarkness", nightDarkness);
        m.put("ambientLight", ambientLight);
        m.put("parallaxEnabled", parallaxEnabled);
        m.put("particlesEnabled", particlesEnabled);
        m.put("audioEnabled", audioEnabled);
        m.put("musicEnabled", musicEnabled);
        m.put("masterVolume", masterVolume);
        m.put("sfxVolume", sfxVolume);
        m.put("musicVolume", musicVolume);
        m.put("soundPitchVariation", soundPitchVariation);
        m.put("lastLevelPath", lastLevelPath);
        m.put("finalized", finalized);
        m.put("texturePackDir", texturePackDir);
        m.put("soundPackDir", soundPackDir);
        m.put("tileSize", tileSize);
        m.put("playerSize", playerSize);
        m.put("defaultEntitySize", defaultEntitySize);
        m.put("shadersEnabled", shadersEnabled);
        m.put("shaderStrength", shaderStrength);
        m.put("shaderPixelate", shaderPixelate);
        m.put("shaderPixelSize", shaderPixelSize);
        m.put("shaderWave", shaderWave);
        m.put("shaderChromatic", shaderChromatic);
        m.put("shaderBloom", shaderBloom);
        m.put("shaderGrayscale", shaderGrayscale);
        m.put("shaderScanlines", shaderScanlines);
        m.put("shaderVignette", shaderVignette);
        return m;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    /**
     * A deep, independent copy. Used to snapshot the active feature settings
     * into a level (each level carries its own toggles) and to derive the
     * runtime profile from a level's saved settings. The map round-trip keeps
     * this automatically in sync with the field list.
     */
    public GameProfile copy() {
        return fromMap(toMap());
    }

    /**
     * Copy the feature toggles/values from {@code src} into this profile while
     * keeping this profile's game-type identity — its {@link #name}, its shared
     * {@link #texturePackDir} and {@link #soundPackDir}, the
     * {@link #lastLevelPath} pointer, and its
     * {@link #finalized} (published) status. This is how a level's own saved
     * settings become the active configuration without losing which game type
     * (folder of levels) is in play, or whether it is play-only.
     */
    /**
     * Put every feature toggle back to the engine's standard default, keeping
     * the game-type identity {@link #applyFeaturesFrom} keeps.
     *
     * <p><b>Why a game type has no feature settings of its own to edit.</b> A
     * game type is a folder of levels, and each level carries the configuration
     * it plays with ({@link com.larsons.engine.level.Level#settings}) — so a
     * feature set stored on the game type could only ever be the template new
     * levels start from. That made it invisible at the point where it mattered:
     * a creator who turned mobs off on the game type met a level with no mobs
     * much later, in a form that had a mobs toggle of its own saying something
     * different. Two places to set one thing, one of which acts at a distance.
     *
     * <p>So the template is fixed at the defaults, and every real decision is
     * made per level, where it can be seen next to the level it applies to.
     */
    public void resetFeaturesToDefaults() {
        applyFeaturesFrom(new GameProfile());
    }

    public void applyFeaturesFrom(GameProfile src) {
        if (src == null) return;
        String keepName = name;
        String keepTexture = texturePackDir;
        String keepSounds = soundPackDir;
        String keepLast = lastLevelPath;
        GameProfile s = src.copy();
        perspective = s.perspective;
        zoomEnabled = s.zoomEnabled;
        minZoom = s.minZoom;
        maxZoom = s.maxZoom;
        defaultZoom = s.defaultZoom;
        minFps = s.minFps;
        maxFps = s.maxFps;
        gravityEnabled = s.gravityEnabled;
        hudVisible = s.hudVisible;
        gridVisible = s.gridVisible;
        mobsEnabled = s.mobsEnabled;
        itemsEnabled = s.itemsEnabled;
        combatEnabled = s.combatEnabled;
        projectilesEnabled = s.projectilesEnabled;
        blockEditingEnabled = s.blockEditingEnabled;
        creativeEnabled = s.creativeEnabled;
        lightingEnabled = s.lightingEnabled;
        dayNightCycle = s.dayNightCycle;
        nightMode = s.nightMode;
        nightDarkness = s.nightDarkness;
        ambientLight = s.ambientLight;
        parallaxEnabled = s.parallaxEnabled;
        particlesEnabled = s.particlesEnabled;
        audioEnabled = s.audioEnabled;
        musicEnabled = s.musicEnabled;
        masterVolume = s.masterVolume;
        sfxVolume = s.sfxVolume;
        musicVolume = s.musicVolume;
        soundPitchVariation = s.soundPitchVariation;
        tileSize = s.tileSize;
        playerSize = s.playerSize;
        defaultEntitySize = s.defaultEntitySize;
        shadersEnabled = s.shadersEnabled;
        shaderStrength = s.shaderStrength;
        shaderPixelate = s.shaderPixelate;
        shaderPixelSize = s.shaderPixelSize;
        shaderWave = s.shaderWave;
        shaderChromatic = s.shaderChromatic;
        shaderBloom = s.shaderBloom;
        shaderGrayscale = s.shaderGrayscale;
        shaderScanlines = s.shaderScanlines;
        shaderVignette = s.shaderVignette;
        name = keepName;
        texturePackDir = keepTexture;
        soundPackDir = keepSounds;
        lastLevelPath = keepLast;
        normalize();
    }

    public static GameProfile fromJson(String json) {
        return fromMap(Json.asObject(Json.parse(json)));
    }

    public static GameProfile fromMap(Map<String, Object> m) {
        GameProfile p = new GameProfile();
        p.name = str(m, "name", p.name);
        p.perspective = perspectiveOf(str(m, "perspective", p.perspective.name()), p.perspective);
        p.zoomEnabled = bool(m, "zoomEnabled", p.zoomEnabled);
        p.minZoom = dbl(m, "minZoom", p.minZoom);
        p.maxZoom = dbl(m, "maxZoom", p.maxZoom);
        p.defaultZoom = dbl(m, "defaultZoom", p.defaultZoom);
        p.minFps = intg(m, "minFps", p.minFps);
        p.maxFps = intg(m, "maxFps", p.maxFps);
        p.gravityEnabled = bool(m, "gravityEnabled", p.gravityEnabled);
        p.hudVisible = bool(m, "hudVisible", p.hudVisible);
        p.gridVisible = bool(m, "gridVisible", p.gridVisible);
        p.mobsEnabled = bool(m, "mobsEnabled", p.mobsEnabled);
        p.itemsEnabled = bool(m, "itemsEnabled", p.itemsEnabled);
        p.combatEnabled = bool(m, "combatEnabled", p.combatEnabled);
        p.projectilesEnabled = bool(m, "projectilesEnabled", p.projectilesEnabled);
        p.blockEditingEnabled = bool(m, "blockEditingEnabled", p.blockEditingEnabled);
        p.creativeEnabled = bool(m, "creativeEnabled", p.creativeEnabled);
        p.lightingEnabled = bool(m, "lightingEnabled", p.lightingEnabled);
        p.dayNightCycle = bool(m, "dayNightCycle", p.dayNightCycle);
        p.nightMode = bool(m, "nightMode", p.nightMode);
        p.nightDarkness = dbl(m, "nightDarkness", p.nightDarkness);
        p.ambientLight = dbl(m, "ambientLight", p.ambientLight);
        p.parallaxEnabled = bool(m, "parallaxEnabled", p.parallaxEnabled);
        p.particlesEnabled = bool(m, "particlesEnabled", p.particlesEnabled);
        p.audioEnabled = bool(m, "audioEnabled", p.audioEnabled);
        p.musicEnabled = bool(m, "musicEnabled", p.musicEnabled);
        p.masterVolume = dbl(m, "masterVolume", p.masterVolume);
        p.sfxVolume = dbl(m, "sfxVolume", p.sfxVolume);
        p.musicVolume = dbl(m, "musicVolume", p.musicVolume);
        p.soundPitchVariation = bool(m, "soundPitchVariation", p.soundPitchVariation);
        p.lastLevelPath = str(m, "lastLevelPath", p.lastLevelPath);
        p.finalized = bool(m, "finalized", p.finalized);
        p.texturePackDir = str(m, "texturePackDir", p.texturePackDir);
        p.soundPackDir = str(m, "soundPackDir", p.soundPackDir);
        p.tileSize = intg(m, "tileSize", p.tileSize);
        p.playerSize = intg(m, "playerSize", p.playerSize);
        p.defaultEntitySize = intg(m, "defaultEntitySize", p.defaultEntitySize);
        p.shadersEnabled = bool(m, "shadersEnabled", p.shadersEnabled);
        p.shaderStrength = dbl(m, "shaderStrength", p.shaderStrength);
        p.shaderPixelate = bool(m, "shaderPixelate", p.shaderPixelate);
        p.shaderPixelSize = intg(m, "shaderPixelSize", p.shaderPixelSize);
        p.shaderWave = bool(m, "shaderWave", p.shaderWave);
        p.shaderChromatic = bool(m, "shaderChromatic", p.shaderChromatic);
        p.shaderBloom = bool(m, "shaderBloom", p.shaderBloom);
        p.shaderGrayscale = bool(m, "shaderGrayscale", p.shaderGrayscale);
        p.shaderScanlines = bool(m, "shaderScanlines", p.shaderScanlines);
        p.shaderVignette = bool(m, "shaderVignette", p.shaderVignette);
        return p;
    }

    /** Clamp inter-dependent values into a sane state after edits. */
    public void normalize() {
        if (maxZoom < minZoom) maxZoom = minZoom;
        defaultZoom = Math.max(minZoom, Math.min(maxZoom, defaultZoom));
        if (maxFps < minFps) maxFps = minFps;
        minFps = Math.max(1, minFps);
        maxFps = Math.max(1, maxFps);
        tileSize = Math.max(1, tileSize);
        playerSize = playerSizeFor(tileSize); // slightly smaller than one block
        defaultEntitySize = Math.max(1, defaultEntitySize);
        shaderStrength = Math.max(0.0, Math.min(1.0, shaderStrength));
        shaderPixelSize = Math.max(1, Math.min(64, shaderPixelSize));
        nightDarkness = Math.max(0.0, Math.min(1.0, nightDarkness));
        ambientLight = Math.max(0.0, Math.min(1.0, ambientLight));
        masterVolume = Math.max(0.0, Math.min(1.0, masterVolume));
        sfxVolume = Math.max(0.0, Math.min(1.0, sfxVolume));
        musicVolume = Math.max(0.0, Math.min(1.0, musicVolume));
        if (lastLevelPath == null) lastLevelPath = "";
        if (texturePackDir == null) texturePackDir = "";
        if (soundPackDir == null) soundPackDir = "";
    }

    /** The player hitbox for a tile size: {@link #PLAYER_TILE_FRACTION} of it. */
    public static int playerSizeFor(int tileSize) {
        return Math.max(1, (int) Math.floor(tileSize * PLAYER_TILE_FRACTION));
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object o = m.get(k);
        return o instanceof String s ? s : def;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k);
        return o instanceof Boolean b ? b : def;
    }

    private static int intg(Map<String, Object> m, String k, int def) {
        Object o = m.get(k);
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        Object o = m.get(k);
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private static Perspective perspectiveOf(String s, Perspective def) {
        try {
            return Perspective.valueOf(s.trim().toUpperCase());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
