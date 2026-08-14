package com.larsons.engine.level;

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
 * <p>Expected JSON shape (a tile grid is required — either the legacy
 * {@code tiles} row arrays below, or the RLE form {@code Level.toMap} writes:
 * {@code "tilesRle": [id,runLength, ...]} row-major with explicit
 * {@code width}/{@code height}):
 * <pre>
 * {
 *   "name": "Sample",
 *   "format": "side_scroller" | "top_down" | "isometric",
 *   "perspective": "SIDE_SCROLL" | "TOP_DOWN" | "ISOMETRIC",
 *   "tileSize": 32,
 *   "width": 24, "height": 14,
 *   "background": "#10141e",
 *   "music": "boss",
 *   "palette": ["#785a3c", "#5aa050", "#6e6e78"],
 *   "spawn": { "x": 64, "y": 96 },
 *   "tiles": [[0,0,1,...], ...],
 *   "upperRle": [id,runLength, ...],
 *   "entities": [ { "type": "player", "x": 64, "y": 96 } ]
 * }
 * </pre>
 *
 * <p>{@code upperRle} (or {@code upperChunks} on a giant level) carries the
 * second layer of blocks the plan-view formats stack — see
 * {@link Level#walkable}. A top-down or isometric level written before that
 * layer existed has neither key, and is converted on load by
 * {@link Level#liftSolidsToUpperLayer()} so it still plays as drawn.
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
        // Format first, then the legacy "perspective" key — both name the same
        // thing, and either alone is enough to load a level of any format.
        if (root.get("format") instanceof String f) {
            lvl.setFormat(LevelFormat.of(f, lvl.format()));
        }
        if (root.get("perspective") instanceof String p) {
            lvl.setFormat(LevelFormat.of(p, lvl.format()));
        }
        if (root.get("music") instanceof String track) lvl.music = track;
        if (root.get("lightAngle") instanceof Number a) lvl.lightAngle = a.doubleValue();
        // Absent means square to the world, which is what every level written
        // before this field existed meant and still means.
        if (root.get("heading") instanceof Number h) {
            lvl.authoredHeading = Math.floorMod(h.intValue(), 8);
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

        // Whether the file describes the stacked layer at all. A plan-view
        // level that doesn't is from before blocks stacked and is converted
        // below; one that does is taken exactly as written, empty layer and all.
        boolean hasUpperLayer = root.containsKey("upperRle") || root.containsKey("upperChunks");

        if (Boolean.TRUE.equals(root.get("chunked"))) {
            // Giant chunked level: bounds + edited chunks + optional generator.
            lvl.width = intOf(root.get("width"), 1024);
            lvl.height = intOf(root.get("height"), 1024);
            ChunkedTiles floor = lvl.newChunkedLayer(Level.LAYER_GROUND);
            if (root.get("generatorSeed") instanceof Number seed) {
                floor.setGenerator("flat".equals(root.get("generator"))
                        ? Level.flatGenerator(seed.intValue())
                        : LevelGenerator.chunkGenerator(seed.longValue(),
                        lvl.width, lvl.height));
            }
            readChunks(root.get("chunks"), floor);
            if (root.get("upperChunks") instanceof Map<?, ?>) {
                readChunks(root.get("upperChunks"),
                        lvl.newChunkedLayer(Level.LAYER_UPPER));
            }
        } else if (root.get("tilesRle") instanceof List<?> rle) {
            // Run-length encoded grid (what Level.toMap writes): pairs of
            // (tileId, runLength) row-major over width x height.
            int width = intOf(root.get("width"), 0);
            int height = intOf(root.get("height"), 0);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("RLE level needs width and height");
            }
            lvl.width = width;
            lvl.height = height;
            lvl.setGrid(Level.LAYER_GROUND, readRle(rle, width, height));
            if (root.get("upperRle") instanceof List<?> upperRle) {
                lvl.setGrid(Level.LAYER_UPPER, readRle(upperRle, width, height));
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
            lvl.height = root.containsKey("height") ? intOf(root.get("height"), rows.size()) : rows.size();
            lvl.width = root.containsKey("width") ? intOf(root.get("width"), maxWidth) : maxWidth;
            lvl.setGrid(Level.LAYER_GROUND, tiles);
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

        // The character roster this level offers at its start. Profiles that
        // no longer exist are dropped when the roster is resolved, so a
        // deleted character never makes a level unplayable.
        if (root.get("characters") instanceof List<?> roster) {
            for (Object o : roster) {
                if (o instanceof String key && !key.isBlank()
                        && !lvl.characters.contains(key)) {
                    lvl.characters.add(key);
                }
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

        // A plan-view level from before blocks stacked reads its geometry the
        // other way round — its corridors are air, which is a hole now — so it
        // is re-cut into layers that mean what its author drew.
        if (!hasUpperLayer) lvl.liftSolidsToUpperLayer();
        return lvl;
    }

    /** Decode one RLE layer ({@code id, runLength, …}) into a dense grid. */
    private static int[][] readRle(List<?> rle, int width, int height) {
        int[][] cells = new int[height][width];
        int cell = 0;
        long total = (long) width * height;
        List<Object> runs = Json.asArray(rle);
        for (int k = 0; k + 1 < runs.size() && cell < total; k += 2) {
            int id = intOf(runs.get(k), 0);
            int len = intOf(runs.get(k + 1), 0);
            for (int j = 0; j < len && cell < total; j++, cell++) {
                cells[cell / width][cell % width] = id;
            }
        }
        return cells;
    }

    /** Install the saved {@code "cx,cy" -> runs} chunks of one layer. */
    private static void readChunks(Object saved, ChunkedTiles into) {
        if (!(saved instanceof Map<?, ?> chunks)) return;
        for (Map.Entry<?, ?> e : chunks.entrySet()) {
            String[] cc = String.valueOf(e.getKey()).split(",");
            if (cc.length != 2 || !(e.getValue() instanceof List<?> runs)) continue;
            try {
                into.putSavedChunk(Integer.parseInt(cc[0].trim()),
                        Integer.parseInt(cc[1].trim()), Json.asArray(runs));
            } catch (NumberFormatException ignored) {
                // skip malformed chunk keys
            }
        }
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

    /**
     * The {@link LevelFormat} a level file declares, read from its header
     * without parsing the level. Both keys {@link Level#toMap()} writes are
     * accepted, and they lead the file, so listing a folder of levels by
     * format costs a short read per file instead of decoding every tile.
     * Returns {@code def} when the text declares neither.
     */
    public static LevelFormat peekFormat(String header, LevelFormat def) {
        if (header == null) return def;
        LevelFormat found = valueAfter(header, "\"format\"", def);
        return found != def ? found : valueAfter(header, "\"perspective\"", def);
    }

    /** The string value following {@code key} in a JSON header, parsed as a format. */
    private static LevelFormat valueAfter(String text, String key, LevelFormat def) {
        int at = text.indexOf(key);
        if (at < 0) return def;
        int open = text.indexOf('"', at + key.length());
        int close = open < 0 ? -1 : text.indexOf('"', open + 1);
        if (open < 0 || close < 0) return def;
        return LevelFormat.of(text.substring(open + 1, close), def);
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
