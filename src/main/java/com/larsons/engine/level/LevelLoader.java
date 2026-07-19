package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.SurfaceDecor;

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

        // A level's own feature settings (the per-level toggles). Absent in
        // legacy levels, in which case the active game type's profile is used.
        if (root.get("settings") instanceof Map<?, ?> settings) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sm = (Map<String, Object>) settings;
            lvl.settings = com.larsons.engine.config.GameProfile.fromMap(sm);
        }

        // "tileset": "registry" marks tile ids as BlockRegistry block ids
        // (creative-editor levels); anything else is a legacy palette level.
        if (root.get("tileset") instanceof String ts) {
            lvl.registryTiles = "registry".equalsIgnoreCase(ts.trim());
        }

        if (root.get("palette") instanceof List<?> pal && !pal.isEmpty()) {
            Color[] colors = new Color[pal.size()];
            for (int k = 0; k < pal.size(); k++) {
                colors[k] = parseColor(String.valueOf(pal.get(k)), Color.GRAY);
            }
            lvl.palette = colors;
        }

        if (Boolean.TRUE.equals(root.get("chunked"))) {
            // Giant chunked level: bounds + edited chunks + optional generator.
            lvl.width = intOf(root.get("width"), 1024);
            lvl.height = intOf(root.get("height"), 1024);
            lvl.chunked = new ChunkedTiles(lvl.width, lvl.height);
            if (root.get("generatorSeed") instanceof Number seed) {
                lvl.chunked.setGenerator(LevelGenerator.chunkGenerator(
                        seed.longValue(), lvl.width, lvl.height));
            }
            if (root.get("chunks") instanceof Map<?, ?> chunks) {
                for (Map.Entry<?, ?> e : chunks.entrySet()) {
                    String[] cc = String.valueOf(e.getKey()).split(",");
                    if (cc.length != 2 || !(e.getValue() instanceof List<?> runs)) continue;
                    try {
                        lvl.chunked.putSavedChunk(Integer.parseInt(cc[0].trim()),
                                Integer.parseInt(cc[1].trim()), Json.asArray(runs));
                    } catch (NumberFormatException ignored) {
                        // skip malformed chunk keys
                    }
                }
            }
        } else {
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
        }

        if (root.get("surface") instanceof List<?> sds) {
            for (Object o : sds) {
                if (!(o instanceof Map<?, ?> sm)) continue;
                try {
                    lvl.surfaceDecor.add(new SurfaceDecor.Placement(
                            intOf(sm.get("c"), 0), intOf(sm.get("r"), 0),
                            SurfaceDecor.Face.valueOf(String.valueOf(sm.get("f"))),
                            String.valueOf(sm.get("k")),
                            Boolean.TRUE.equals(sm.get("fg")),
                            SurfaceDecor.Visibility.valueOf(String.valueOf(sm.get("v")))));
                } catch (IllegalArgumentException ignored) {
                    // unknown face/visibility: skip the entry
                }
            }
        }

        if (root.get("rules") instanceof List<?> rules) {
            for (Object o : rules) {
                if (o instanceof Map<?, ?> rm) {
                    try {
                        lvl.statRules.add(StatRule.fromMap(rm));
                    } catch (IllegalArgumentException ignored) {
                        // malformed rule: skip
                    }
                }
            }
        }

        if (root.get("minigame") instanceof Map<?, ?> mg) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) mg;
            lvl.minigame = com.larsons.engine.minigame.MiniGameConfig.fromMap(mm);
        }

        if (root.get("cutscenes") instanceof List<?> scenes) {
            for (Object o : scenes) {
                if (o instanceof Map<?, ?> cm) lvl.cutscenes.add(Cutscene.fromMap(cm));
            }
        }

        if (root.get("containers") instanceof List<?> boxes) {
            // Storage-block inventories (chests, barrels) saved with the level.
            for (Object o : boxes) {
                if (!(o instanceof Map<?, ?> cm) || !(cm.get("items") instanceof List<?> items)) {
                    continue;
                }
                java.util.List<com.larsons.engine.entity.ItemStack> stacks =
                        lvl.openContainer(intOf(cm.get("c"), 0), intOf(cm.get("r"), 0));
                for (Object io : items) {
                    if (!(io instanceof Map<?, ?> sm)) continue;
                    String key = sm.get("k") instanceof String s ? s : null;
                    int count = intOf(sm.get("n"), 0);
                    if (key == null || count <= 0) continue;
                    var stack = new com.larsons.engine.entity.ItemStack(key, count);
                    stack.wear = intOf(sm.get("d"), 0);
                    stacks.add(stack);
                }
            }
        }

        if (root.get("spawn") instanceof Map<?, ?> sp) {
            lvl.spawnX = doubleOf(sp.get("x"), 0);
            lvl.spawnY = doubleOf(sp.get("y"), 0);
        }

        if (root.get("entities") instanceof List<?> ents) {
            for (Object o : ents) {
                if (o instanceof Map<?, ?> e) {
                    lvl.entities.add(new Level.EntitySpawn(
                            e.get("kind") instanceof String k ? k : "entity",
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
