package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a loaded level (requirement #6: level loading).
 *
 * <p>A level is a grid of integer tile ids ({@code 0} = empty) plus metadata.
 * Tiles resolve in one of two modes:
 * <ul>
 *   <li><b>Registry mode</b> (levels written by the creative editor, marked
 *       {@code "tileset": "registry"}): ids are {@link Block} ids from a
 *       {@link BlockRegistry}, which supplies colour, solidity, light
 *       emission, and drops. This is the block system ported from the
 *       Side-Scroller engine.</li>
 *   <li><b>Palette mode</b> (legacy levels): ids index a colour palette and
 *       every non-empty tile is solid — exactly the original minimal
 *       behaviour, so old levels load unchanged.</li>
 * </ul>
 *
 * <p>Levels are mutable ({@link #setTile}) because the creative editor paints
 * into them and multiplayer block edits apply to them, and they serialize back
 * to JSON ({@link #toJson}) so edited worlds can be saved and sent to joining
 * players.
 */
public class Level {
    public String name = "Untitled";
    public Perspective perspective = Perspective.SIDE_SCROLL;
    public int tileSize = 32;
    public int width;          // in tiles
    public int height;         // in tiles
    public int[][] tiles;      // [row][col], 0 = empty
    public Color background = new Color(24, 28, 38);
    public Color[] palette = defaultPalette();
    public double spawnX, spawnY;   // world pixels
    public final List<EntitySpawn> entities = new ArrayList<>();

    /** True when tile ids are {@link BlockRegistry} block ids. */
    public boolean registryTiles;
    /** Resolves block ids in registry mode. */
    public BlockRegistry blocks = BlockRegistry.standard();

    /** Create an empty registry-mode level of the given size (creative editor). */
    public static Level empty(String name, int widthTiles, int heightTiles, int tileSize) {
        Level lvl = new Level();
        lvl.name = name;
        lvl.width = Math.max(1, widthTiles);
        lvl.height = Math.max(1, heightTiles);
        lvl.tileSize = tileSize;
        lvl.tiles = new int[lvl.height][lvl.width];
        lvl.registryTiles = true;
        lvl.spawnX = tileSize * 2;
        lvl.spawnY = tileSize * 2;
        return lvl;
    }

    /** Colour used to draw the given tile id, or {@code null} for empty tiles. */
    public Color colorFor(int tileId) {
        if (tileId <= 0) return null;
        if (registryTiles) {
            Color c = blocks.colorOf(tileId);
            return c != null ? c : Color.MAGENTA; // unknown id: loud placeholder
        }
        if (palette == null || palette.length == 0) return Color.GRAY;
        return palette[(tileId - 1) % palette.length];
    }

    public int tileAt(int col, int row) {
        if (tiles == null || row < 0 || row >= tiles.length
                || col < 0 || col >= tiles[row].length) {
            return 0;
        }
        return tiles[row][col];
    }

    /**
     * Whether the tile at (col,row) blocks movement. Registry mode asks the
     * block definition; palette mode keeps the legacy "any tile is solid".
     */
    public boolean solidAt(int col, int row) {
        int id = tileAt(col, row);
        if (id <= 0) return false;
        return !registryTiles || blocks.isSolid(id);
    }

    /** The block definition at (col,row), or {@code null} (empty / palette mode). */
    public Block blockAt(int col, int row) {
        if (!registryTiles) return null;
        return blocks.get(tileAt(col, row));
    }

    /**
     * Set a tile, returns {@code true} if it changed. Out-of-bounds writes and
     * unknown block ids are ignored (the wire can carry garbage; the level
     * can't). Id {@code 0} always clears.
     */
    public boolean setTile(int col, int row, int id) {
        if (tiles == null || row < 0 || row >= tiles.length
                || col < 0 || col >= tiles[row].length) {
            return false;
        }
        if (id != 0 && registryTiles && blocks.get(id) == null) return false;
        if (id < 0) return false;
        if (tiles[row][col] == id) return false;
        tiles[row][col] = id;
        return true;
    }

    private static Color[] defaultPalette() {
        return new Color[]{
                new Color(120, 90, 60),    // 1: dirt
                new Color(90, 160, 80),    // 2: grass
                new Color(110, 110, 120),  // 3: stone
                new Color(70, 120, 200),   // 4: water
                new Color(220, 200, 120),  // 5: sand
        };
    }

    // --- serialization --------------------------------------------------------

    /** Serialize to the same JSON shape {@link LevelLoader} reads. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("perspective", perspective.name());
        m.put("tileSize", tileSize);
        m.put("width", width);
        m.put("height", height);
        m.put("background", hex(background));
        if (registryTiles) {
            m.put("tileset", "registry");
        } else if (palette != null) {
            List<Object> pal = new ArrayList<>(palette.length);
            for (Color c : palette) pal.add(hex(c));
            m.put("palette", pal);
        }
        Map<String, Object> spawn = new LinkedHashMap<>();
        spawn.put("x", spawnX);
        spawn.put("y", spawnY);
        m.put("spawn", spawn);
        List<Object> rows = new ArrayList<>(tiles == null ? 0 : tiles.length);
        if (tiles != null) {
            for (int[] row : tiles) {
                List<Object> cols = new ArrayList<>(row.length);
                for (int id : row) cols.add(id);
                rows.add(cols);
            }
        }
        m.put("tiles", rows);
        if (!entities.isEmpty()) {
            List<Object> ents = new ArrayList<>(entities.size());
            for (EntitySpawn e : entities) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("kind", e.kind);
                em.put("type", e.type);
                em.put("x", e.x);
                em.put("y", e.y);
                ents.add(em);
            }
            m.put("entities", ents);
        }
        return m;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * A request to spawn an entity, as declared by the level file. {@code kind}
     * says which registry resolves {@code type}: {@code "mob"} / {@code "item"}
     * (from the creative palette), or the legacy {@code "entity"} for untyped
     * spawns like {@code "player"}.
     */
    public static class EntitySpawn {
        public final String kind;
        public final String type;
        public final double x, y;

        public EntitySpawn(String kind, String type, double x, double y) {
            this.kind = kind == null || kind.isBlank() ? "entity" : kind;
            this.type = type;
            this.x = x;
            this.y = y;
        }

        public EntitySpawn(String type, double x, double y) {
            this("entity", type, x, y);
        }
    }
}
