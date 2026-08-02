package com.larsons.engine.level;

import com.larsons.engine.config.GameProfile;
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
    /**
     * The camera projection this level is drawn through. It is the storage
     * form of the level's {@link LevelFormat} — use {@link #format()} /
     * {@link #setFormat} to talk about the level's kind (which creative mode
     * builds it, whether it simulates gravity, which blocks its palette
     * offers) and this field when a {@code Camera} is what needs feeding.
     */
    public Perspective perspective = Perspective.SIDE_SCROLL;
    /**
     * This level's own feature settings (the toggles that used to live on the
     * game type). Game types are just a folder grouping now, so each level
     * carries the configuration it plays with — perspective switching, gravity,
     * mobs/items/combat, lighting, shaders, and so on. {@code null} means the
     * level has no saved settings of its own (legacy levels and the bundled
     * sample), in which case the active game type's profile is used as-is.
     */
    public GameProfile settings;
    /**
     * The music track this level plays, as a {@link com.larsons.engine.audio.SoundKeys}
     * music key ({@code "level"}, {@code "boss"}, a name of the creator's own).
     * Blank means the generic {@code music/level} track, so a level that was
     * never given one still plays whatever the sound pack has for levels.
     * Set in creative mode's sound editor and saved with the level.
     */
    public String music = "";
    /**
     * Where the sun stands over a plan-view level, as a compass bearing in
     * degrees clockwise from north — so {@code 315} (the default) is the
     * north-west shoulder, and the shadows stacked blocks cast fall away from
     * it to the south-east.
     *
     * <p>It is the level's, not the engine's, because it is a look: a town at
     * noon and a canyon in late afternoon want their shadows thrown different
     * ways, and every stacked block in the level agrees on one answer or the
     * scene stops reading as lit at all. Set in creative mode and saved with
     * the level; a side-scroller carries it harmlessly and ignores it.
     */
    public double lightAngle = DEFAULT_LIGHT_ANGLE;

    /** The sun's default bearing: over the north-west shoulder. */
    public static final double DEFAULT_LIGHT_ANGLE = 315;

    public int tileSize = 32;
    public int width;          // in tiles
    public int height;         // in tiles
    public int[][] tiles;      // [row][col], 0 = empty; null in chunked mode
    /** Sparse chunk storage for giant levels; {@code null} in dense mode. */
    public ChunkedTiles chunked;
    /**
     * The second layer of blocks stacked on the ground layer, in the plan-view
     * formats only ({@link LevelFormat#layered()}); {@code null} until the
     * level has one. Same storage shape as {@link #tiles}/{@link #chunked}, so
     * a layered level carries either two dense grids or two chunk maps.
     *
     * <p>This layer is what a top-down or isometric level says <em>height</em>
     * with, and height is the whole of its geometry: see {@link #walkable}.
     */
    public int[][] upper;
    /** Sparse chunk storage for the upper layer of a giant layered level. */
    public ChunkedTiles upperChunked;
    public Color background = new Color(24, 28, 38);
    public Color[] palette = defaultPalette();
    public double spawnX, spawnY;   // world pixels
    public final List<EntitySpawn> entities = new ArrayList<>();
    /** Surface decorations attached to block faces (tall grass, moss…). */
    public final List<SurfaceDecor.Placement> surfaceDecor = new ArrayList<>();
    /** Map-maker stat triggers evaluated while the level is played. */
    public final List<StatRule> statRules = new ArrayList<>();
    /**
     * The level's mini-game setup (Capture the Flag, Stockpile, Battle,
     * Escort), configured in creative mode; {@code null} (or mode NONE) means
     * a normal level. Saved with the level so the game ships with the map.
     */
    public com.larsons.engine.minigame.MiniGameConfig minigame;
    /** Map-maker cutscenes (triggers + sprite-sheet actors + step scripts). */
    public final List<Cutscene> cutscenes = new ArrayList<>();
    /**
     * The character profiles a player may pick from when this level starts —
     * the level's roster, chosen by its creator in the Characters palette.
     * Keys refer to {@link com.larsons.engine.character.Characters}. An empty
     * roster means "all of them", so a level is never unplayable and levels
     * built before character profiles existed keep working.
     */
    public final List<String> characters = new ArrayList<>();
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

    /**
     * Bumped whenever this level's terrain changes anywhere. Kept as the cheap
     * "has anything at all moved" question.
     *
     * <p>Deliberately not saved — it describes this session's edits, not the
     * level, and a loaded level starts over at zero.
     */
    private transient long terrainRevision;

    /**
     * Per-region change counters, keyed by {@code (col >> REGION_SHIFT,
     * row >> REGION_SHIFT)}.
     *
     * <p><b>Why a whole map instead of one counter.</b> Anything caching a
     * picture of the terrain has to know when its copy went stale, and a single
     * global counter answers that far too broadly: one block breaking marks the
     * entire level dirty. That is not a rare case — liquids run every tick, and
     * mining, meteors and block placement all rewrite cells during ordinary
     * play — so a global counter meant the terrain cache threw away every
     * visible chunk whenever a single drop of water moved, and quietly did the
     * full per-cell sweep it exists to avoid.
     *
     * <p>Regions are the granularity a cache actually invalidates at, so a
     * change tells only the chunk containing it.
     */
    private transient java.util.Map<Long, Long> regionRevisions;

    /**
     * Bumped when the grid is replaced wholesale rather than edited — loading,
     * resizing, changing format. Everything cached is stale at once, and the
     * per-region counters start over.
     */
    private transient long terrainGeneration;

    /** Region edge in tiles, as a power of two. */
    private static final int REGION_SHIFT = 3;

    /** The current global terrain revision — any change to any cell. */
    public long terrainRevision() { return terrainRevision; }

    /** The wholesale-replacement counter; see {@link #terrainGeneration}. */
    public long terrainGeneration() { return terrainGeneration; }

    /**
     * How many times the region containing {@code (col, row)} has changed.
     * A cache holding a picture of that region compares this against what it
     * built with.
     */
    public long terrainRevisionAt(int col, int row) {
        if (regionRevisions == null) return 0L;
        return regionRevisions.getOrDefault(regionKey(col, row), 0L);
    }

    private static long regionKey(int col, int row) {
        return ((long) (col >> REGION_SHIFT) << 32) ^ ((row >> REGION_SHIFT) & 0xFFFFFFFFL);
    }

    /** Record that the cell at {@code (col, row)} changed. */
    private void markTerrainChanged(int col, int row) {
        terrainRevision++;
        if (regionRevisions == null) regionRevisions = new java.util.HashMap<>();
        regionRevisions.merge(regionKey(col, row), 1L, Long::sum);
    }

    /**
     * Mark the whole terrain as replaced — for anything that rewrites the grid
     * rather than editing cells in it.
     */
    public void bumpTerrainRevision() {
        terrainRevision++;
        terrainGeneration++;
        if (regionRevisions != null) regionRevisions.clear();
    }
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

    // --- level format ----------------------------------------------------------

    /**
     * Which of the three level formats this is (side-scroller, top-down,
     * isometric) — the level's kind, as opposed to the raw camera projection
     * {@link #perspective} stores it as.
     */
    public LevelFormat format() {
        return LevelFormat.of(perspective);
    }

    /** Retarget this level at another format (also sets {@link #perspective}). */
    public void setFormat(LevelFormat format) {
        if (format != null) perspective = format.perspective();
    }

    /**
     * Whether this level simulates on a plane (top-down / isometric) rather
     * than under gravity. Entity simulation reads this to decide between the
     * platformer model and the plan-view one.
     */
    public boolean planar() {
        return format().planar();
    }

    /**
     * Whether blocks stack two deep here, and so whether {@link #walkable}
     * rather than the block's own {@code solid} flag decides what stops a
     * body. True in the plan views, and only for registry-mode levels —
     * legacy palette levels have no block definitions to stack.
     */
    public boolean layered() {
        return registryTiles && format().layered();
    }

    /**
     * Snapshot {@code profile}'s feature toggles as this level's own
     * {@link #settings} — what saving a level does.
     *
     * <p>The saved copy's perspective is forced to this level's, because the
     * profile only carries the format <em>new</em> levels start in: without
     * this, saving an isometric level from a game type whose default is
     * side-scroll would bury a contradicting format inside it and re-open it
     * flat.
     */
    public void captureSettings(GameProfile profile) {
        if (profile == null) return;
        settings = profile.copy();
        settings.perspective = perspective;
    }

    /** Colour used to draw the given tile id, or {@code null} for empty tiles. */
    /** The generic track a level with no music of its own asks for. */
    private static final String DEFAULT_MUSIC_KEY = "music/level";
    /** {@link #musicKey()}'s answer, remembered because it is asked every frame. */
    private transient String musicKeyCache;
    private transient String musicKeyFor;

    /**
     * The full sound key of this level's music — its own track when it names
     * one, else the generic level track.
     *
     * <p>Asked once a frame by the scene that plays it, so the answer is kept
     * until {@link #music} changes rather than rebuilt each time.
     */
    public String musicKey() {
        String track = music == null ? "" : music;
        if (!track.equals(musicKeyFor) || musicKeyCache == null) {
            musicKeyFor = track;
            musicKeyCache = track.isBlank() ? DEFAULT_MUSIC_KEY : "music/" + track.trim();
        }
        return musicKeyCache;
    }

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

    /** The layer index of the floor — the only layer a side-scroller has. */
    public static final int LAYER_GROUND = 0;
    /** The layer index of the blocks stacked on the floor (plan views only). */
    public static final int LAYER_UPPER = 1;

    /**
     * The layer things placed <em>on</em> the floor go into: the stacked layer
     * where the level has one, the single layer otherwise. Liquids pool here,
     * blocks are mined from here down, and it is the layer a block edit means
     * when it doesn't say.
     */
    public int surfaceLayer() {
        return layered() ? LAYER_UPPER : LAYER_GROUND;
    }

    /** The tile at (col,row) in one layer — {@link #tileAt}/{@link #upperAt}. */
    public int tileAt(int col, int row, int layer) {
        return layer == LAYER_UPPER ? upperAt(col, row) : tileAt(col, row);
    }

    /** Set the tile at (col,row) in one layer; returns whether it changed. */
    public boolean setTile(int col, int row, int layer, int id) {
        return layer == LAYER_UPPER ? setUpper(col, row, id) : setTile(col, row, id);
    }

    /** The block stacked on the ground layer at (col,row); 0 = nothing there. */
    public int upperAt(int col, int row) {
        if (upperChunked != null) return upperChunked.get(col, row);
        if (upper == null || row < 0 || row >= upper.length
                || col < 0 || col >= upper[row].length) {
            return 0;
        }
        return upper[row][col];
    }

    /**
     * How many blocks deep the stack at (col,row) is: {@code 0} bare ground,
     * {@code 1} a floor to walk on, {@code 2} a wall. Only meaningful in a
     * {@link #layered()} level; a side-scroller answers 0 or 1.
     */
    public int stackHeight(int col, int row) {
        int floor = tileAt(col, row);
        if (floor <= 0) return 0;
        return upperAt(col, row) > 0 ? 2 : 1;
    }

    /**
     * Whether a body may stand at (col,row) in a {@link #layered()} level.
     *
     * <p>On a plane the block grid <em>is</em> the floor, so what stops you is
     * the shape of that floor rather than any property of one block:
     * <ul>
     *   <li><b>Bare ground</b> — nothing painted, or outside the level — is a
     *       hole. There is no "down" to fall along in a plan view, so a gap in
     *       the floor is simply somewhere you cannot go.</li>
     *   <li><b>One layer</b> is the pathway: a floor tile you walk across.</li>
     *   <li><b>Two layers</b> is a barrier — the stacked block stands up out of
     *       the floor and reads as a wall, which is what its cast shadow shows.
     *       A non-solid block stacked on a path (a torch, a flower) is dressing
     *       rather than a wall, so it leaves the path open.</li>
     * </ul>
     */
    public boolean walkable(int col, int row) {
        if (tileAt(col, row) <= 0) return false;
        Block up = upperBlockAt(col, row);
        return up == null || !up.solid();
    }

    /**
     * Whether the tile at (col,row) blocks movement. Layered plan-view levels
     * ask the stack ({@link #walkable}); the side-scroller asks the block
     * definition, and palette mode keeps the legacy "any tile is solid".
     */
    public boolean solidAt(int col, int row) {
        if (layered()) return !walkable(col, row);
        int id = tileAt(col, row);
        if (id <= 0) return false;
        return !registryTiles || blocks.isSolid(id);
    }

    /** The block definition at (col,row), or {@code null} (empty / palette mode). */
    public Block blockAt(int col, int row) {
        if (!registryTiles) return null;
        return blocks.get(tileAt(col, row));
    }

    /** The stacked block at (col,row), or {@code null} when nothing is stacked. */
    public Block upperBlockAt(int col, int row) {
        if (!registryTiles) return null;
        return blocks.get(upperAt(col, row));
    }

    /**
     * The block a player interacts with at (col,row) — the stacked one when
     * there is one, else the floor. Mining takes the stack apart from the top
     * down, which is what makes a wall become a path and then a hole.
     */
    public Block topBlockAt(int col, int row) {
        Block up = upperBlockAt(col, row);
        return up != null ? up : blockAt(col, row);
    }

    /**
     * The layer a block placed at (col,row) would land in, or {@code -1} when
     * the cell has no room for one.
     *
     * <p>A stack is built from the bottom up, so a hole is floored before
     * anything is stood on it and a placed block never buries the one already
     * there. A liquid is the exception in both layers: covering a pool is how
     * pools are removed, since liquids cannot be mined.
     */
    public int placeLayer(int col, int row) {
        if (col < 0 || row < 0 || col >= width || row >= height) return -1;
        if (tileAt(col, row) == 0) return LAYER_GROUND;
        if (!layered()) {
            return liquidAt(col, row) != null ? LAYER_GROUND : -1;
        }
        Block up = upperBlockAt(col, row);
        if (up == null) return LAYER_UPPER;
        return up.liquid() ? LAYER_UPPER : -1;
    }

    /**
     * The liquid occupying (col,row), or {@code null} (swim/damage checks).
     * A pool in a layered level sits on the floor rather than replacing it, so
     * the stacked layer is looked at first and the floor is the fallback.
     */
    public Block liquidAt(int col, int row) {
        Block up = upperBlockAt(col, row);
        if (up != null && up.liquid()) return up;
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
            if (upperChunked != null) upperChunked.resize(newWidth, newHeight);
        } else if ((long) newWidth * newHeight > DENSE_TILE_LIMIT) {
            // Growing past the dense limit converts to chunked storage.
            chunked = toChunked(tiles, newWidth, newHeight);
            upperChunked = upper == null ? null : toChunked(upper, newWidth, newHeight);
            tiles = null;
            upper = null;
        } else {
            int[][] nextTiles = resized(tiles, newWidth, newHeight);
            int[][] nextUpper = upper == null ? null : resized(upper, newWidth, newHeight);
            tiles = nextTiles;
            upper = nextUpper;
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

    /** One layer, re-cut to new bounds, keeping the overlapping region. */
    private int[][] resized(int[][] layer, int newWidth, int newHeight) {
        int[][] next = new int[newHeight][newWidth];
        if (layer != null) {
            for (int r = 0; r < Math.min(height, newHeight); r++) {
                System.arraycopy(layer[r], 0, next[r], 0, Math.min(width, newWidth));
            }
        }
        return next;
    }

    /** One dense layer converted to chunked storage at the new bounds. */
    private ChunkedTiles toChunked(int[][] layer, int newWidth, int newHeight) {
        ChunkedTiles next = new ChunkedTiles(newWidth, newHeight);
        if (layer != null) {
            for (int r = 0; r < Math.min(height, newHeight); r++) {
                for (int c = 0; c < Math.min(width, newWidth); c++) {
                    if (layer[r][c] != 0) next.set(c, r, layer[r][c]);
                }
            }
        }
        return next;
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
            if (changed) {
                markTerrainChanged(col, row);
                if (id == 0) clearCellAttachments(col, row);
            }
            return changed;
        }
        if (tiles == null || row < 0 || row >= tiles.length
                || col < 0 || col >= tiles[row].length) {
            return false;
        }
        if (tiles[row][col] == id) return false;
        tiles[row][col] = id;
        markTerrainChanged(col, row);
        if (id == 0) clearCellAttachments(col, row);
        return true;
    }

    /**
     * Set the stacked block at (col,row), returns {@code true} if it changed.
     * Only layered levels have somewhere to put it, and the storage for it is
     * allocated the first time something is actually stacked — a plan-view
     * level with no walls in it costs no second grid.
     */
    public boolean setUpper(int col, int row, int id) {
        if (id < 0 || !layered()) return false;
        if (id != 0 && blocks.get(id) == null) return false;
        if (col < 0 || row < 0 || col >= width || row >= height) return false;
        if (id != 0) ensureUpperStorage();
        if (upperChunked != null) {
            boolean changed = upperChunked.set(col, row, id);
            if (changed) markTerrainChanged(col, row);
            return changed;
        }
        if (upper == null) return false;  // clearing a layer that never existed
        if (upper[row][col] == id) return false;
        upper[row][col] = id;
        markTerrainChanged(col, row);
        return true;
    }

    /**
     * Paint a whole stack at (col,row): the block as floor <em>and</em> stacked
     * on itself, which is what a wall is. In an unlayered level it just sets
     * the tile.
     */
    public boolean stackTile(int col, int row, int id) {
        boolean changed = setTile(col, row, id);
        return setUpper(col, row, id) || changed;
    }

    /**
     * Lay {@code id} across the ground layer, so a plan-view canvas is floor
     * rather than holes. Dense levels are filled outright; a giant level takes
     * a generator instead, which fills each chunk as the camera reaches it.
     */
    public void fillFloor(int id) {
        if (id <= 0) return;
        if (chunked != null) {
            chunked.setGenerator(flatGenerator(id));
            return;
        }
        if (tiles == null) return;
        for (int[] row : tiles) java.util.Arrays.fill(row, id);
    }

    /** A {@link ChunkGenerator} that lays one block id everywhere. */
    public static ChunkGenerator flatGenerator(int id) {
        return new FlatChunks(id);
    }

    /**
     * The floor under a giant plan-view level. Its "seed" is the block id it
     * lays, so a save carries everything needed to rebuild it — and it is a
     * named type rather than a lambda so {@link #toMap()} can tell it apart
     * from the terrain generator and write down which one to restore.
     */
    public record FlatChunks(int blockId) implements ChunkGenerator {
        @Override
        public void generate(int cx, int cy, int[] out) {
            java.util.Arrays.fill(out, blockId);
        }

        @Override
        public long seed() {
            return blockId;
        }
    }

    /** Allocate the upper layer's storage, matching the ground layer's shape. */
    private void ensureUpperStorage() {
        if (chunked != null) {
            if (upperChunked == null) upperChunked = new ChunkedTiles(width, height);
        } else if (upper == null && tiles != null) {
            upper = new int[height][width];
        }
    }

    /** Cell data that follows its block: clearing the cell drops it too. */
    private void clearCellAttachments(int col, int row) {
        // The floor going means the stack goes with it — there is nothing left
        // to hold a block up, and a stacked block over a hole reads as neither.
        setUpper(col, row, 0);
        removeSurfaceDecorAt(col, row);
        if (!containers.isEmpty()) containers.remove(cellKey(col, row));
    }

    /** Surface decorations follow their host block: clearing the cell drops them. */
    private void removeSurfaceDecorAt(int col, int row) {
        if (surfaceDecor.isEmpty()) return;
        surfaceDecor.removeIf(sd -> sd.col() == col && sd.row() == row);
    }

    // --- legacy plan-view levels -----------------------------------------------

    /**
     * Rebuild a plan-view level written before blocks stacked, so it plays the
     * way its creator drew it.
     *
     * <p>The old rule was the side view's — a solid block stopped you and
     * everything else, air included, was open floor. The new rule reads the
     * <em>stack</em>, which inverts exactly the case those levels are mostly
     * made of: their corridors are air, and air is now a hole. So each cell is
     * re-cut into the layers that mean what it used to:
     *
     * <ul>
     *   <li>a solid block becomes a stack of itself — floor with the same block
     *       standing on it, which is the barrier it always was;</li>
     *   <li>a passable block (a path marking, a torch, a flower) stays one
     *       layer, the pathway it always was;</li>
     *   <li>air becomes plain floor, because it was somewhere you could walk.</li>
     * </ul>
     *
     * <p>Giant chunked levels are laid with a floor generator instead of being
     * walked cell by cell; their saved chunks are converted in place.
     */
    public void liftSolidsToUpperLayer() {
        if (!layered()) return;
        Block floor = LevelFormat.floorBlock(blocks);
        int floorId = floor != null ? floor.id() : 0;
        if (floorId <= 0) return;
        if (chunked != null) {
            chunked.setGenerator(flatGenerator(floorId));
            chunked.forEachLoadedCell((col, row, id) -> {
                if (blocks.isSolid(id)) {
                    setUpper(col, row, id);
                } else if (id == 0) {
                    chunked.set(col, row, floorId);
                }
            });
            return;
        }
        if (tiles == null) return;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int id = tileAt(c, r);
                if (id == 0) {
                    setTile(c, r, floorId);
                } else if (blocks.isSolid(id)) {
                    setUpper(c, r, id);
                }
            }
        }
    }

    // --- play-test terrain snapshots -------------------------------------------

    /**
     * Deep copy of the terrain, storage-agnostic — the creative editor grabs
     * one before a play-test so mining/liquid flow can't eat the level, and
     * {@link #restoreTiles} puts it back afterwards.
     */
    public Object snapshotTiles() {
        return new TerrainSnapshot(snapshotLayer(chunked, tiles),
                snapshotLayer(upperChunked, upper));
    }

    /** Restore terrain saved by {@link #snapshotTiles} (no-op on mismatch). */
    public void restoreTiles(Object snapshot) {
        if (snapshot instanceof TerrainSnapshot both) {
            restoreLayer(chunked, tiles, both.ground());
            if (both.stacked() != null) ensureUpperStorage();
            restoreLayer(upperChunked, upper, both.stacked());
        } else {
            // A snapshot from before the second layer existed: ground only.
            restoreLayer(chunked, tiles, snapshot);
        }
    }

    /** Both layers of a play-test terrain snapshot; either half may be null. */
    private record TerrainSnapshot(Object ground, Object stacked) {}

    /** Deep copy of one layer, whichever storage it uses. */
    private static Object snapshotLayer(ChunkedTiles sparse, int[][] dense) {
        if (sparse != null) return sparse.snapshot();
        if (dense == null) return null;
        int[][] copy = new int[dense.length][];
        for (int r = 0; r < dense.length; r++) copy[r] = dense[r].clone();
        return copy;
    }

    /** Put one layer back; a snapshot that doesn't match the storage is ignored. */
    private static void restoreLayer(ChunkedTiles sparse, int[][] dense, Object snapshot) {
        if (sparse != null && snapshot instanceof ChunkedTiles.Snapshot s) {
            sparse.restore(s);
        } else if (dense != null && snapshot instanceof int[][] saved) {
            for (int r = 0; r < saved.length && r < dense.length; r++) {
                System.arraycopy(saved[r], 0, dense[r], 0,
                        Math.min(saved[r].length, dense[r].length));
            }
        }
    }

    // --- editor undo snapshots -------------------------------------------------

    /**
     * Everything one cell holds: both block layers, plus the data that hangs
     * off them. This is the unit the creative editor's undo saves terrain in
     * ({@link EditHistory}) — one of these per cell a stroke is about to touch,
     * because a level has millions of cells and a stroke touches a handful.
     *
     * <p>It is the whole cell rather than a tile id because clearing a cell's
     * floor takes the block stacked on it, its surface details and its
     * container with it ({@link #setTile}): put back only the id and a mined
     * wall comes back as a path with its moss gone.
     */
    public record CellState(int col, int row, int ground, int stacked,
                            List<SurfaceDecor.Placement> decor, List<ItemStack> container) {}

    /** Save everything at (col,row) — see {@link CellState}. */
    public CellState captureCell(int col, int row) {
        List<SurfaceDecor.Placement> decor = List.of();
        if (!surfaceDecor.isEmpty()) {
            List<SurfaceDecor.Placement> found = new ArrayList<>(2);
            for (SurfaceDecor.Placement sd : surfaceDecor) {
                if (sd.col() == col && sd.row() == row) found.add(sd);
            }
            if (!found.isEmpty()) decor = List.copyOf(found);
        }
        List<ItemStack> held = containerAt(col, row);
        return new CellState(col, row, tileAt(col, row), upperAt(col, row),
                decor, held == null ? null : List.copyOf(held));
    }

    /** Put a cell back exactly as {@link #captureCell} found it. */
    public void restoreCell(CellState state) {
        if (state == null) return;
        int col = state.col(), row = state.row();
        // The floor goes back first: writing it can clear the whole cell
        // (see clearCellAttachments), so everything else has to be laid on top
        // of that cascade rather than under it.
        setTile(col, row, LAYER_GROUND, state.ground());
        setTile(col, row, LAYER_UPPER, state.stacked());
        if (!surfaceDecor.isEmpty()) {
            surfaceDecor.removeIf(sd -> sd.col() == col && sd.row() == row);
        }
        surfaceDecor.addAll(state.decor());
        long cell = cellKey(col, row);
        if (state.container() == null) containers.remove(cell);
        else containers.put(cell, new ArrayList<>(state.container()));
    }

    /**
     * The level as its editor's dialogs see it: the fields and lists they
     * change, in a form that can be compared and put back. Terrain is not in
     * here — {@link CellState} carries that.
     *
     * <p>Cutscenes and the mini-game setup are held in their serialized form
     * rather than as objects, which does two jobs: it makes the snapshot a deep
     * copy (the editor edits cutscenes in place, so a list of references would
     * change underneath the history), and it makes two snapshots comparable, so
     * a window that was opened and cancelled leaves no undo step behind.
     */
    public record Doc(String name, String music, double lightAngle,
                      double spawnX, double spawnY,
                      List<EntitySpawn> entities,
                      List<SurfaceDecor.Placement> surfaceDecor,
                      List<StatRule> statRules, List<String> characters,
                      List<Map<String, Object>> cutscenes,
                      Map<String, Object> minigame) {}

    /** Save the level's document state — see {@link Doc}. */
    public Doc snapshotDoc() {
        List<Map<String, Object>> scenes = new ArrayList<>(cutscenes.size());
        for (Cutscene cs : cutscenes) scenes.add(cs.toMap());
        return new Doc(name, music, lightAngle, spawnX, spawnY,
                List.copyOf(entities), List.copyOf(surfaceDecor),
                List.copyOf(statRules), List.copyOf(characters),
                List.copyOf(scenes), minigame == null ? null : minigame.toMap());
    }

    /** Put back a {@link #snapshotDoc()} (the snapshot stays reusable). */
    public void restoreDoc(Doc doc) {
        if (doc == null) return;
        name = doc.name();
        music = doc.music();
        lightAngle = doc.lightAngle();
        spawnX = doc.spawnX();
        spawnY = doc.spawnY();
        refill(entities, doc.entities());
        refill(surfaceDecor, doc.surfaceDecor());
        refill(statRules, doc.statRules());
        refill(characters, doc.characters());
        cutscenes.clear();
        for (Map<String, Object> m : doc.cutscenes()) cutscenes.add(Cutscene.fromMap(m));
        minigame = doc.minigame() == null ? null
                : com.larsons.engine.minigame.MiniGameConfig.fromMap(
                        new LinkedHashMap<>(doc.minigame()));
    }

    private static <T> void refill(List<T> list, List<T> saved) {
        list.clear();
        list.addAll(saved);
    }

    /**
     * A whole-level snapshot, for the one edit that cannot be described cell by
     * cell: a {@link #resize}, which re-cuts both tile layers and drops
     * whatever fell outside the new bounds.
     *
     * <p>Which storage the level used is part of it, because growing past
     * {@link #DENSE_TILE_LIMIT} turns a dense grid into chunks and resizing
     * back down does not turn it back — undo has to reinstate the grid itself,
     * not just its bounds.
     */
    public record Bounds(int width, int height, Object ground, Object stacked,
                         boolean chunkedStorage, ChunkGenerator generator,
                         List<EntitySpawn> entities,
                         List<SurfaceDecor.Placement> surfaceDecor,
                         Map<Long, List<ItemStack>> containers,
                         double spawnX, double spawnY) {}

    /** Save the level's size and everything a resize would re-cut or drop. */
    public Bounds snapshotBounds() {
        return new Bounds(width, height,
                snapshotLayer(chunked, tiles), snapshotLayer(upperChunked, upper),
                chunked != null, chunked != null ? chunked.generator() : null,
                List.copyOf(entities), List.copyOf(surfaceDecor),
                copyContainers(containers), spawnX, spawnY);
    }

    /** Put back a {@link #snapshotBounds()} (the snapshot stays reusable). */
    public void restoreBounds(Bounds saved) {
        if (saved == null) return;
        width = saved.width();
        height = saved.height();
        if (saved.chunkedStorage()) {
            tiles = null;
            upper = null;
            chunked = new ChunkedTiles(width, height);
            chunked.setGenerator(saved.generator());
            if (saved.ground() instanceof ChunkedTiles.Snapshot s) chunked.restore(s);
            upperChunked = null;
            if (saved.stacked() instanceof ChunkedTiles.Snapshot s) {
                upperChunked = new ChunkedTiles(width, height);
                upperChunked.restore(s);
            }
        } else {
            chunked = null;
            upperChunked = null;
            tiles = saved.ground() instanceof int[][] g ? denseCopy(g) : new int[height][width];
            upper = saved.stacked() instanceof int[][] u ? denseCopy(u) : null;
        }
        refill(entities, saved.entities());
        refill(surfaceDecor, saved.surfaceDecor());
        containers.clear();
        containers.putAll(copyContainers(saved.containers()));
        spawnX = saved.spawnX();
        spawnY = saved.spawnY();
    }

    private static int[][] denseCopy(int[][] layer) {
        int[][] copy = new int[layer.length][];
        for (int r = 0; r < layer.length; r++) copy[r] = layer[r].clone();
        return copy;
    }

    /**
     * Container map copy: the lists are copied so adding a chest cannot reach
     * into a snapshot, the stacks in them are not, because a level being edited
     * replaces stacks rather than changing them.
     */
    private static Map<Long, List<ItemStack>> copyContainers(Map<Long, List<ItemStack>> from) {
        Map<Long, List<ItemStack>> copy = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ItemStack>> e : from.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
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
        // The level's format is what decides which creative mode builds it and
        // how it simulates; "perspective" stays alongside it so levels written
        // here still load in engine versions that only knew the projection.
        m.put("format", format().id());
        m.put("perspective", perspective.name());
        if (music != null && !music.isBlank()) m.put("music", music);
        if (lightAngle != DEFAULT_LIGHT_ANGLE) m.put("lightAngle", lightAngle);
        m.put("tileSize", tileSize);
        m.put("width", width);
        m.put("height", height);
        m.put("background", hex(background));
        // Each level stores its own feature toggles so game types can hold a
        // diverse mix of levels; loading a level loads its settings.
        if (settings != null) {
            m.put("settings", settings.toMap());
        }
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
                // Which generator, not just its seed: a plan view's chunks are
                // floor, and rebuilding them as side-scrolling terrain would
                // hand the level back full of holes and hills.
                if (chunked.generator() instanceof FlatChunks) m.put("generator", "flat");
            }
            Map<String, Object> chunkMap = new LinkedHashMap<>();
            chunkMap.putAll(chunked.dirtyChunksRle());
            m.put("chunks", chunkMap);
            if (upperChunked != null) {
                Map<String, Object> upperMap = new LinkedHashMap<>();
                upperMap.putAll(upperChunked.dirtyChunksRle());
                if (!upperMap.isEmpty()) m.put("upperChunks", upperMap);
            }
        } else {
            // Run-length encoded, row-major over the whole grid: pairs of
            // (tileId, runLength). Levels are mostly runs of air and terrain,
            // so this is dramatically smaller (and faster to write) than the
            // old row-of-arrays form — which matters both for saves and for
            // the multiplayer welcome message that carries the level as one
            // line. LevelLoader still reads the legacy "tiles" shape.
            m.put("tilesRle", rleOf(tiles));
            // The stacked layer rides alongside in the same encoding. It is
            // absent in a side-scroller and in a plan-view level nobody has
            // built anything up in yet, which is also how a reader tells a
            // pre-layer level apart from a deliberately flat one.
            if (upper != null) m.put("upperRle", rleOf(upper));
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
        if (minigame != null
                && minigame.mode != com.larsons.engine.minigame.MiniGameConfig.Mode.NONE) {
            m.put("minigame", minigame.toMap());
        }
        if (!cutscenes.isEmpty()) {
            List<Object> scenes = new ArrayList<>(cutscenes.size());
            for (Cutscene cs : cutscenes) scenes.add(cs.toMap());
            m.put("cutscenes", scenes);
        }
        // The level's character roster; absent means "offer every profile".
        if (!characters.isEmpty()) m.put("characters", new ArrayList<>(characters));
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

    /**
     * RLE runs (id, length, id, length, …) over one dense layer, row-major.
     * Emitted against the level bounds (ragged legacy rows pad with air) so
     * the decoder can rebuild rows from {@code width} alone.
     */
    private List<Object> rleOf(int[][] layer) {
        List<Object> runs = new ArrayList<>();
        if (layer == null) return runs;
        int runId = 0, runLen = 0;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int id = r < layer.length && c < layer[r].length ? layer[r][c] : 0;
                if (runLen > 0 && id == runId) {
                    runLen++;
                } else {
                    if (runLen > 0) {
                        runs.add(runId);
                        runs.add(runLen);
                    }
                    runId = id;
                    runLen = 1;
                }
            }
        }
        if (runLen > 0) {
            runs.add(runId);
            runs.add(runLen);
        }
        return runs;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    /**
     * Single-line JSON for the wire. The pretty form puts every value on its
     * own indented line — harmless in a save file, ruinous inside a one-line
     * protocol message — so the multiplayer welcome sends this instead.
     */
    public String toJsonCompact() {
        return Json.stringifyCompact(toMap());
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
