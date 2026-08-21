package com.larsons.engine.config;

import com.larsons.engine.graphics.CameraLock;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;

import com.larsons.engine.sim.ActorSize;

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
     * The player size as a fraction of a tile: slightly smaller than one
     * block, so the player fits through one-tile gaps without pixel-perfect
     * alignment. {@link #normalize()} locks {@link #playerSize} to this.
     *
     * <p>This is the game type's <em>default</em>, and only that: a character
     * profile says how large it is drawn and how much floor it occupies, each
     * on its own ({@link com.larsons.engine.sim.ActorSize}), and a body that
     * never chose falls back here.
     */
    public static final double PLAYER_TILE_FRACTION = ActorSize.DEFAULT_TILES;

    public String name = "New Game Type";

    /**
     * The format new levels start in (requirement #2). A level owns its
     * perspective from the moment it is created and keeps it — the two formats
     * differ in which axis is up, in what a block means, and in how many layers
     * of them a level is written in, so there is no coherent "switch view" for
     * one to offer. Changing perspective is what walking through a door into a
     * level of the other format does. (Moving the <em>camera</em> within a 3D
     * level is a different thing entirely, and free: see
     * {@link com.larsons.engine.graphics.Camera#tilt}.)
     */
    public Perspective perspective = Perspective.SIDE_SCROLL;

    /**
     * Where this level lets its camera stand — headings, tilt range, and which
     * stops of the first/third-person cycle are reachable. Free by default and
     * in every level saved before it existed.
     *
     * <p>Beside the zoom bounds because it is the same kind of setting: the
     * creator saying what the player may do with the view, per level, on the
     * level it applies to. See {@link CameraLock}.
     */
    public CameraLock cameraLock = CameraLock.free();

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

    /**
     * Whether a plan-view level's height axis is somewhere a body can <em>be</em>
     * — climb a stack, stand on its top, walk off and fall — rather than only
     * something that blocks them.
     *
     * <p><b>Off by default, and the default is load-bearing.</b> A plan-view
     * hop rises about 57 world units against a block of 17.6, so the moment
     * landing on a column becomes possible every wall in every level already
     * saved is climbable, and every maze becomes traversable over its own
     * walls. That is not a bug in the feature, it is the feature — so it is the
     * level's decision, and a level that predates the decision has not made it.
     *
     * <p>An absent key in a saved settings block therefore has to mean "off",
     * which is why this defaults {@code false} rather than defaulting to what a
     * newly authored level would want ({@code HEIGHT_PLAN.md} W0).
     */
    public boolean verticality = false;

    /**
     * Whether a long fall hurts. Off by default: whether height is dangerous
     * is a game-type question rather than a physics one, and a level that
     * turned the height axis on did not thereby ask for its players to die of
     * it ({@code HEIGHT_PLAN.md} W3).
     */
    public boolean fallDamageEnabled = false;

    /**
     * This level's procedural-terrain settings: whether the world generates
     * itself beyond the level's own bounds, the seed it generates from, the
     * biomes it is built out of, and how far the player can see it.
     *
     * <p>Never {@code null} — a level that has never been asked carries the
     * defaults, which have generation off. See
     * {@link com.larsons.engine.world.gen.TerrainSettings}.
     */
    public com.larsons.engine.world.gen.TerrainSettings terrain =
            new com.larsons.engine.world.gen.TerrainSettings();

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
    // Volume is NOT here. It is a property of the person playing, so it lives
    // in PlayerSettings (config/player.json) beside the key binds, and no level
    // file carries it. Whether a level has music at all is the creator's call
    // (musicEnabled, above); how loud it is on your machine is yours.
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
        // Written only when it restricts something, so a level that lets the
        // camera go anywhere carries no camera block at all.
        Map<String, Object> camera = cameraLock.toMap();
        if (!camera.isEmpty()) m.put("camera", camera);
        m.put("zoomEnabled", zoomEnabled);
        m.put("minZoom", minZoom);
        m.put("maxZoom", maxZoom);
        m.put("defaultZoom", defaultZoom);
        m.put("minFps", minFps);
        m.put("maxFps", maxFps);
        m.put("gravityEnabled", gravityEnabled);
        m.put("verticality", verticality);
        m.put("fallDamageEnabled", fallDamageEnabled);
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
        m.put("terrain", terrain.toMap());
        m.put("parallaxEnabled", parallaxEnabled);
        m.put("particlesEnabled", particlesEnabled);
        m.put("audioEnabled", audioEnabled);
        m.put("musicEnabled", musicEnabled);
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
        cameraLock = s.cameraLock;
        zoomEnabled = s.zoomEnabled;
        minZoom = s.minZoom;
        maxZoom = s.maxZoom;
        defaultZoom = s.defaultZoom;
        minFps = s.minFps;
        maxFps = s.maxFps;
        gravityEnabled = s.gravityEnabled;
        verticality = s.verticality;
        fallDamageEnabled = s.fallDamageEnabled;
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
        terrain = s.terrain;
        parallaxEnabled = s.parallaxEnabled;
        particlesEnabled = s.particlesEnabled;
        audioEnabled = s.audioEnabled;
        musicEnabled = s.musicEnabled;
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
        p.cameraLock = m.get("camera") instanceof Map<?, ?> camera
                ? CameraLock.fromMap(Json.asObject(camera))
                : CameraLock.free();
        p.zoomEnabled = bool(m, "zoomEnabled", p.zoomEnabled);
        p.minZoom = dbl(m, "minZoom", p.minZoom);
        p.maxZoom = dbl(m, "maxZoom", p.maxZoom);
        p.defaultZoom = dbl(m, "defaultZoom", p.defaultZoom);
        p.minFps = intg(m, "minFps", p.minFps);
        p.maxFps = intg(m, "maxFps", p.maxFps);
        p.gravityEnabled = bool(m, "gravityEnabled", p.gravityEnabled);
        p.verticality = bool(m, "verticality", p.verticality);
        p.fallDamageEnabled = bool(m, "fallDamageEnabled", p.fallDamageEnabled);
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
        if (m.get("terrain") instanceof Map<?, ?> terrainMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tm = (Map<String, Object>) terrainMap;
            p.terrain = com.larsons.engine.world.gen.TerrainSettings.fromMap(tm);
        }
        p.parallaxEnabled = bool(m, "parallaxEnabled", p.parallaxEnabled);
        p.particlesEnabled = bool(m, "particlesEnabled", p.particlesEnabled);
        p.audioEnabled = bool(m, "audioEnabled", p.audioEnabled);
        p.musicEnabled = bool(m, "musicEnabled", p.musicEnabled);
        // "masterVolume"/"sfxVolume"/"musicVolume" are deliberately not read.
        // Level and game-type files written before volume moved to
        // PlayerSettings still carry those keys; ignoring them is what stops an
        // old file from reaching into a player's mix. Unknown keys have always
        // been skipped here, so no migration is needed and no file breaks.
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
        if (cameraLock == null) cameraLock = CameraLock.free();
        cameraLock.normalize();
        if (terrain == null) terrain = new com.larsons.engine.world.gen.TerrainSettings();
        terrain.normalize();
        if (lastLevelPath == null) lastLevelPath = "";
        if (texturePackDir == null) texturePackDir = "";
        if (soundPackDir == null) soundPackDir = "";
    }

    /** The default player size for a tile size: {@link #PLAYER_TILE_FRACTION} of it. */
    public static int playerSizeFor(int tileSize) {
        return (int) ActorSize.pixels(PLAYER_TILE_FRACTION, tileSize);
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
        // Through Perspective's own parser rather than valueOf, so a game type
        // saved when the plan views were two formats still names a world this
        // engine has.
        return Perspective.of(s, def);
    }
}
