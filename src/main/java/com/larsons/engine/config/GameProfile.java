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

    public String name = "New Game Type";

    // Perspective the creator builds levels in (requirement #2).
    public Perspective perspective = Perspective.SIDE_SCROLL;
    public boolean perspectiveSwitchingEnabled = false;

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
    public boolean audioEnabled = true;       // synthesized sound effects

    // The level last saved/played in this game type ("" = bundled sample).
    public String lastLevelPath = "";

    /**
     * The creator's texture pack folder ("" = none): sprite-sheet browsing in
     * the creative editor's texture dialog starts here, and bare sheet
     * filenames resolve against it.
     */
    public String texturePackDir = "";

    // Sizes of various entities (world pixels). The player is always exactly
    // one block (1x1 tiles) so it fits one-tile gaps — normalize() keeps
    // playerSize locked to tileSize.
    public int tileSize = 32;
    public int playerSize = 32;
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
        m.put("perspectiveSwitchingEnabled", perspectiveSwitchingEnabled);
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
        m.put("lastLevelPath", lastLevelPath);
        m.put("texturePackDir", texturePackDir);
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

    public static GameProfile fromJson(String json) {
        return fromMap(Json.asObject(Json.parse(json)));
    }

    public static GameProfile fromMap(Map<String, Object> m) {
        GameProfile p = new GameProfile();
        p.name = str(m, "name", p.name);
        p.perspective = perspectiveOf(str(m, "perspective", p.perspective.name()), p.perspective);
        p.perspectiveSwitchingEnabled = bool(m, "perspectiveSwitchingEnabled", p.perspectiveSwitchingEnabled);
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
        p.lastLevelPath = str(m, "lastLevelPath", p.lastLevelPath);
        p.texturePackDir = str(m, "texturePackDir", p.texturePackDir);
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
        playerSize = tileSize; // the player is exactly one block, 1x1 tiles
        defaultEntitySize = Math.max(1, defaultEntitySize);
        shaderStrength = Math.max(0.0, Math.min(1.0, shaderStrength));
        shaderPixelSize = Math.max(1, Math.min(64, shaderPixelSize));
        nightDarkness = Math.max(0.0, Math.min(1.0, nightDarkness));
        ambientLight = Math.max(0.0, Math.min(1.0, ambientLight));
        if (lastLevelPath == null) lastLevelPath = "";
        if (texturePackDir == null) texturePackDir = "";
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
