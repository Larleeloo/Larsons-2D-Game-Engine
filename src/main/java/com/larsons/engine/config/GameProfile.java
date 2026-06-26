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

    // Sizes of various entities (world pixels).
    public int tileSize = 32;
    public int playerSize = 32;
    public int defaultEntitySize = 32;

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
        m.put("tileSize", tileSize);
        m.put("playerSize", playerSize);
        m.put("defaultEntitySize", defaultEntitySize);
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
        p.tileSize = intg(m, "tileSize", p.tileSize);
        p.playerSize = intg(m, "playerSize", p.playerSize);
        p.defaultEntitySize = intg(m, "defaultEntitySize", p.defaultEntitySize);
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
        playerSize = Math.max(1, playerSize);
        defaultEntitySize = Math.max(1, defaultEntitySize);
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
