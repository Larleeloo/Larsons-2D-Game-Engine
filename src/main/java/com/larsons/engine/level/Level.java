package com.larsons.engine.level;

import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.SurfaceDecor;

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

    /**
     * Tile-count threshold above which a level uses sparse chunked storage
     * ({@link ChunkedTiles}) instead of a dense grid — 1024&times;1024. Giant
     * levels (up to {@value #MAX_GIANT_SIZE}&sup2;) only load the chunks that
     * are actually looked at, so they stay cheap no matter their bounds.
     */
    public static final long DENSE_TILE_LIMIT = 1024L * 1024L;

    /** Hard cap on level side length, in tiles. */
    public static final int MAX_GIANT_SIZE = 65536;

    public String name = "Untitled";
    public Perspective perspective = Perspective.SIDE_SCROLL;
    public int tileSize = 32;
    public int width;          // in tiles
    public int height;         // in tiles
    public int[][] tiles;      // [row][col], 0 = empty; null in chunked mode
    /** Sparse chunk storage for giant levels; {@code null} in dense mode. */
    public ChunkedTiles chunked;
    public Color background = new Color(24, 28, 38);
    public Color[] palette = defaultPalette();
    public double spawnX, spawnY;   // world pixels
    public final List<EntitySpawn> entities = new ArrayList<>();
    /** Surface decorations attached to block faces (tall grass, moss…). */
    public final List<SurfaceDecor.Placement> surfaceDecor = new ArrayList<>();
    /** Map-maker stat triggers evaluated while the level is played. */
    public final List<StatRule> statRules = new ArrayList<>();
    /** Map-maker cutscenes (triggers + sprite-sheet actors + step scripts). */
    public final List<Cutscene> cutscenes = new ArrayList<>();
    /**
     * Storage-block inventories (chests, barrels), keyed by
     * {@link #cellKey(int, int)} — a second inventory per container cell that
     * saves and loads with the level data.
     */
    public final Map<Long, List<ItemStack>> containers = new LinkedHashMap<>();

    /** Slots a single container offers. */
    public static final int CONTAINER_SLOTS = 12;

    /** True when tile ids are {@link BlockRegistry} block ids. */
    public boolean registryTiles;
    /** Resolves block ids in registry mode. */
    public BlockRegistry blocks = BlockRegistry.standard();

    /** Create an empty registry-mode level of the given size (creative editor). */
    public static Level empty(String name, int widthTiles, int heightTiles, int tileSize) {
        widthTiles = Math.max(1, Math.min(MAX_GIANT_SIZE, widthTiles));
        heightTiles = Math.max(1, Math.min(MAX_GIANT_SIZE, heightTiles));
        if ((long) widthTiles * heightTiles > DENSE_TILE_LIMIT) {
            return emptyChunked(name, widthTiles, heightTiles, tileSize, null);
        }
        Level lvl = new Level();
        lvl.name = name;
        lvl.width = widthTiles;
        lvl.height = heightTiles;
        lvl.tileSize = tileSize;
        lvl.tiles = new int[lvl.height][lvl.width];
        lvl.registryTiles = true;
        lvl.spawnX = tileSize * 2;
        lvl.spawnY = tileSize * 2;
        return lvl;
    }

    /**
     * Create a giant chunked level. {@code generator} (may be {@code null})
     * fills missing chunks on demand — attach one for auto-generated giant
     * worlds so terrain appears as the camera reaches it.
     */
    public static Level emptyChunked(String name, int widthTiles, int heightTiles,
                                     int tileSize, ChunkGenerator generator) {
        Level lvl = new Level();
        lvl.name = name;
        lvl.width = Math.max(1, Math.min(MAX_GIANT_SIZE, widthTiles));
        lvl.height = Math.max(1, Math.min(MAX_GIANT_SIZE, heightTiles));
        lvl.tileSize = tileSize;
        lvl.chunked = new ChunkedTiles(lvl.width, lvl.height);
        lvl.chunked.setGenerator(generator);
        lvl.registryTiles = true;
        lvl.spawnX = tileSize * 2;
        lvl.spawnY = tileSize * 2;
        return lvl;
    }

    /** True when this level uses sparse chunked storage (giant maps). */
    public boolean isChunked() {
        return chunked != null;
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
        if (chunked != null) return chunked.get(col, row);
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

    /** The liquid occupying (col,row), or {@code null} (swim/damage checks). */
    public Block liquidAt(int col, int row) {
        Block b = blockAt(col, row);
        return b != null && b.liquid() ? b : null;
    }

    // --- storage-block containers ---------------------------------------------

    /** The {@link #containers} key for a cell. */
    public static long cellKey(int col, int row) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }

    /** The container contents at (col,row), or {@code null} when never opened. */
    public List<ItemStack> containerAt(int col, int row) {
        return containers.get(cellKey(col, row));
    }

    /** The container contents at (col,row), created empty on first open. */
    public List<ItemStack> openContainer(int col, int row) {
        return containers.computeIfAbsent(cellKey(col, row), k -> new ArrayList<>());
    }

    /** Detach and return the container contents at (col,row) (block mined). */
    public List<ItemStack> removeContainer(int col, int row) {
        return containers.remove(cellKey(col, row));
    }

    /**
     * Resize the tile grid in place, preserving the overlapping region (the
     * creative editor's size sliders). Entities that fall outside the new
     * bounds are dropped and the spawn is clamped back in.
     */
    public void resize(int newWidth, int newHeight) {
        newWidth = Math.max(4, Math.min(MAX_GIANT_SIZE, newWidth));
        newHeight = Math.max(4, Math.min(MAX_GIANT_SIZE, newHeight));
        if (newWidth == width && newHeight == height) return;
        if (chunked != null) {
            // Chunked levels resize in place: chunks outside the bounds unload.
            chunked.resize(newWidth, newHeight);
        } else if ((long) newWidth * newHeight > DENSE_TILE_LIMIT) {
            // Growing past the dense limit converts to chunked storage.
            ChunkedTiles next = new ChunkedTiles(newWidth, newHeight);
            if (tiles != null) {
                for (int r = 0; r < Math.min(height, newHeight); r++) {
                    for (int c = 0; c < Math.min(width, newWidth); c++) {
                        if (tiles[r][c] != 0) next.set(c, r, tiles[r][c]);
                    }
                }
            }
            tiles = null;
            chunked = next;
        } else {
            int[][] next = new int[newHeight][newWidth];
            if (tiles != null) {
                for (int r = 0; r < Math.min(height, newHeight); r++) {
                    System.arraycopy(tiles[r], 0, next[r], 0, Math.min(width, newWidth));
                }
            }
            tiles = next;
        }
        width = newWidth;
        height = newHeight;
        double maxX = width * (double) tileSize - 1;
        double maxY = height * (double) tileSize - 1;
        spawnX = Math.max(0, Math.min(spawnX, maxX));
        spawnY = Math.max(0, Math.min(spawnY, maxY));
        entities.removeIf(e -> e.x > maxX || e.y > maxY);
        surfaceDecor.removeIf(sd -> sd.col() >= width || sd.row() >= height);
        containers.keySet().removeIf(k ->
                (k & 0xFFFFFFFFL) >= width || (k >>> 32) >= height);
    }

    /**
     * Where player {@code id} spawns: painted multiplayer spawn points are
     * dealt out round-robin by id; without any, everyone uses the single
     * spawn marker. Returns {@code {x, y}} in world pixels.
     */
    public double[] spawnPointFor(int id) {
        List<EntitySpawn> points = new ArrayList<>();
        for (EntitySpawn e : entities) {
            if ("mp_spawn".equals(e.kind)) points.add(e);
        }
        if (points.isEmpty()) return new double[]{spawnX, spawnY};
        EntitySpawn pick = points.get(Math.floorMod(id, points.size()));
        return new double[]{pick.x, pick.y};
    }

    /** The nearest door marker within {@code radius} world px, or {@code null}. */
    public EntitySpawn doorNear(double x, double y, double radius) {
        EntitySpawn best = null;
        double bestD = radius;
        for (EntitySpawn e : entities) {
            if (!"door".equals(e.kind)) continue;
            double d = Math.hypot(e.x - x, e.y - y);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Set a tile, returns {@code true} if it changed. Out-of-bounds writes and
     * unknown block ids are ignored (the wire can carry garbage; the level
     * can't). Id {@code 0} always clears.
     */
    public boolean setTile(int col, int row, int id) {
        if (id < 0) return false;
        if (id != 0 && registryTiles && blocks.get(id) == null) return false;
        if (chunked != null) {
            boolean changed = chunked.set(col, row, id);
            if (changed && id == 0) clearCellAttachments(col, row);
            return changed;
        }
        if (tiles == null || row < 0 || row >= tiles.length
                || col < 0 || col >= tiles[row].length) {
            return false;
        }
        if (tiles[row][col] == id) return false;
        tiles[row][col] = id;
        if (id == 0) clearCellAttachments(col, row);
        return true;
    }

    /** Cell data that follows its block: clearing the cell drops it too. */
    private void clearCellAttachments(int col, int row) {
        removeSurfaceDecorAt(col, row);
        if (!containers.isEmpty()) containers.remove(cellKey(col, row));
    }

    /** Surface decorations follow their host block: clearing the cell drops them. */
    private void removeSurfaceDecorAt(int col, int row) {
        if (surfaceDecor.isEmpty()) return;
        surfaceDecor.removeIf(sd -> sd.col() == col && sd.row() == row);
    }

    // --- play-test terrain snapshots -------------------------------------------

    /**
     * Deep copy of the terrain, storage-agnostic — the creative editor grabs
     * one before a play-test so mining/liquid flow can't eat the level, and
     * {@link #restoreTiles} puts it back afterwards.
     */
    public Object snapshotTiles() {
        if (chunked != null) return chunked.snapshot();
        if (tiles == null) return null;
        int[][] copy = new int[tiles.length][];
        for (int r = 0; r < tiles.length; r++) copy[r] = tiles[r].clone();
        return copy;
    }

    /** Restore terrain saved by {@link #snapshotTiles} (no-op on mismatch). */
    public void restoreTiles(Object snapshot) {
        if (chunked != null && snapshot instanceof ChunkedTiles.Snapshot s) {
            chunked.restore(s);
        } else if (tiles != null && snapshot instanceof int[][] saved) {
            for (int r = 0; r < saved.length && r < tiles.length; r++) {
                tiles[r] = saved[r].clone();
            }
        }
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
        if (chunked != null) {
            // Giant levels: only edited chunks persist (RLE-compressed); the
            // rest rebuilds from the generator seed on load.
            m.put("chunked", true);
            m.put("chunkSize", ChunkedTiles.CHUNK);
            if (chunked.generator() != null) {
                m.put("generatorSeed", chunked.generator().seed());
            }
            Map<String, Object> chunkMap = new LinkedHashMap<>();
            chunkMap.putAll(chunked.dirtyChunksRle());
            m.put("chunks", chunkMap);
        } else {
            List<Object> rows = new ArrayList<>(tiles == null ? 0 : tiles.length);
            if (tiles != null) {
                for (int[] row : tiles) {
                    List<Object> cols = new ArrayList<>(row.length);
                    for (int id : row) cols.add(id);
                    rows.add(cols);
                }
            }
            m.put("tiles", rows);
        }
        if (!surfaceDecor.isEmpty()) {
            List<Object> sds = new ArrayList<>(surfaceDecor.size());
            for (SurfaceDecor.Placement sd : surfaceDecor) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("c", sd.col());
                sm.put("r", sd.row());
                sm.put("f", sd.face().name());
                sm.put("k", sd.key());
                sm.put("fg", sd.foreground());
                sm.put("v", sd.visibility().name());
                sds.add(sm);
            }
            m.put("surface", sds);
        }
        if (!statRules.isEmpty()) {
            List<Object> rules = new ArrayList<>(statRules.size());
            for (StatRule rule : statRules) rules.add(rule.toMap());
            m.put("rules", rules);
        }
        if (!cutscenes.isEmpty()) {
            List<Object> scenes = new ArrayList<>(cutscenes.size());
            for (Cutscene cs : cutscenes) scenes.add(cs.toMap());
            m.put("cutscenes", scenes);
        }
        if (!containers.isEmpty()) {
            // Storage-block inventories ride along with the level data.
            List<Object> boxes = new ArrayList<>(containers.size());
            for (Map.Entry<Long, List<ItemStack>> e : containers.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("c", (int) (e.getKey() & 0xFFFFFFFFL));
                cm.put("r", (int) (e.getKey() >>> 32));
                List<Object> items = new ArrayList<>(e.getValue().size());
                for (ItemStack s : e.getValue()) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("k", s.key);
                    sm.put("n", s.count);
                    if (s.wear > 0) sm.put("d", s.wear);
                    items.add(sm);
                }
                cm.put("items", items);
                boxes.add(cm);
            }
            if (!boxes.isEmpty()) m.put("containers", boxes);
        }
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
