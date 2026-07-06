package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads {@link Level}s from JSON (requirement #6: level loading).
 *
 * <p>The path is resolved from the classpath first (bundled levels, including
 * inside a jar) then the filesystem. Parsing uses the engine's own
 * {@link Json} parser, so no third-party libraries are required
 * (requirement #4).
 *
 * <p>Expected JSON shape (only {@code tiles} is required):
 * <pre>
 * {
 *   "name": "Sample",
 *   "perspective": "SIDE_SCROLL" | "TOP_DOWN" | "ISOMETRIC",
 *   "tileSize": 32,
 *   "width": 24, "height": 14,
 *   "background": "#10141e",
 *   "palette": ["#785a3c", "#5aa050", "#6e6e78"],
 *   "spawn": { "x": 64, "y": 96 },
 *   "tiles": [[0,0,1,...], ...],
 *   "entities": [ { "type": "player", "x": 64, "y": 96 } ]
 * }
 * </pre>
 */
public final class LevelLoader {

    private LevelLoader() {}

    public static Level load(String path) {
        String text = readText(path);
        if (text == null) throw new IllegalArgumentException("Level not found: " + path);
        return parse(text);
    }

    public static Level parse(String json) {
        Map<String, Object> root = Json.asObject(Json.parse(json));
        Level lvl = new Level();

        if (root.get("name") instanceof String s) lvl.name = s;
        if (root.get("perspective") instanceof String p) {
            lvl.perspective = Perspective.valueOf(p.trim().toUpperCase());
        }
        if (root.containsKey("tileSize")) lvl.tileSize = intOf(root.get("tileSize"), 32);
        if (root.get("background") instanceof String bg) lvl.background = parseColor(bg, lvl.background);

        if (root.get("palette") instanceof List<?> pal && !pal.isEmpty()) {
            Color[] colors = new Color[pal.size()];
            for (int k = 0; k < pal.size(); k++) {
                colors[k] = parseColor(String.valueOf(pal.get(k)), Color.GRAY);
            }
            lvl.palette = colors;
        }

        Object tilesObj = root.get("tiles");
        if (!(tilesObj instanceof List)) {
            throw new IllegalArgumentException("Level is missing a 'tiles' array");
        }
        List<Object> rows = Json.asArray(tilesObj);
        int[][] tiles = new int[rows.size()][];
        int maxWidth = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = Json.asArray(rows.get(r));
            tiles[r] = new int[row.size()];
            for (int c = 0; c < row.size(); c++) {
                tiles[r][c] = intOf(row.get(c), 0);
            }
            maxWidth = Math.max(maxWidth, row.size());
        }
        lvl.tiles = tiles;
        lvl.height = root.containsKey("height") ? intOf(root.get("height"), rows.size()) : rows.size();
        lvl.width = root.containsKey("width") ? intOf(root.get("width"), maxWidth) : maxWidth;

        if (root.get("spawn") instanceof Map<?, ?> sp) {
            lvl.spawnX = doubleOf(sp.get("x"), 0);
            lvl.spawnY = doubleOf(sp.get("y"), 0);
        }

        if (root.get("entities") instanceof List<?> ents) {
            for (Object o : ents) {
                if (o instanceof Map<?, ?> e) {
                    lvl.entities.add(new Level.EntitySpawn(
                            String.valueOf(e.get("type")),
                            doubleOf(e.get("x"), 0),
                            doubleOf(e.get("y"), 0)));
                }
            }
        }
        return lvl;
    }

    /**
     * Raw level JSON, resolved from the classpath first then the filesystem, or
     * {@code null} if not found. Public because a multiplayer server sends the
     * level text to joining clients (which then {@link #parse} it), so clients
     * don't need the level file locally.
     */
    public static String readText(String path) {
        String cp = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = LevelLoader.class.getResourceAsStream(cp)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // fall through to filesystem
        }
        try {
            Path p = Path.of(path);
            if (Files.exists(p)) return Files.readString(p);
        } catch (IOException ignored) {
            // fall through to null
        }
        return null;
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double doubleOf(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private static Color parseColor(String hex, Color def) {
        if (hex == null) return def;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) {
                return new Color(
                        Integer.parseInt(h.substring(0, 2), 16),
                        Integer.parseInt(h.substring(2, 4), 16),
                        Integer.parseInt(h.substring(4, 6), 16));
            }
            if (h.length() == 8) {
                return new Color(
                        Integer.parseInt(h.substring(0, 2), 16),
                        Integer.parseInt(h.substring(2, 4), 16),
                        Integer.parseInt(h.substring(4, 6), 16),
                        Integer.parseInt(h.substring(6, 8), 16));
            }
        } catch (RuntimeException ignored) {
            // fall through to default
        }
        return def;
    }
}
