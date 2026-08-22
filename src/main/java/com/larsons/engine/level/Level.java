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

    /**
     * The compass point this level was authored from, 0–7 clockwise from north
     * — where the camera starts when the level is opened.
     *
     * <p>A level is built from some direction, and a plan-view level built from
     * one and opened from another reads as a different place: the wall you put
     * on the player's left is on their right, and the shape you laid out to be
     * approached from the south is approached from the north. So the heading
     * the creator was looking from is part of the level, in the way the sun's
     * bearing already is — a look the level owns rather than a setting the
     * engine keeps.
     *
     * <p>It is <em>not</em> a constraint on the player, who may turn wherever
     * they like from there, and it is not networked: C10 makes the point that
     * two players looking at one world from different angles is correct
     * behaviour. This is only where the camera starts.
     *
     * <p>Stored as the compass point rather than as radians because that is
     * what it means and because an integer cannot arrive from a file a hair off
     * a compass point — which would cost the floor cache and the upright tile
     * blit for the whole session (C3, C4). A side-scroller carries it
     * harmlessly and ignores it, exactly as it carries {@link #lightAngle}.
     */
    public int authoredHeading;

    /**
     * How high the camera stood over the floor when the level was saved, in
     * <em>degrees</em> — where its camera starts, the way
     * {@link #authoredHeading} is where its heading starts.
     *
     * <p>The companion to that field, and it exists for the same reason: a 3D
     * level's camera tilts freely, and a maze laid out to be read from directly
     * above is a different thing seen from a camera brought down over it. What
     * the creator was looking at is part of the level.
     *
     * <p>Also not a constraint on the player, who may tilt wherever they like
     * from there, and also not networked. Zero means "not written", which is
     * every level saved before the camera could tilt and which loads at
     * {@code Camera.DEFAULT_PITCH} — the tilt has no meaningful zero of its own
     * (a camera flat on the floor sees nothing), so the absent value can stand
     * for the default without ambiguity.
     *
     * <p>Stored in degrees rather than radians because a level file is
     * something a person reads and edits, and {@code 90} says what
     * {@code 1.5707963267948966} does not.
     */
    public double authoredPitchDegrees;

    public int tileSize = 32;
    public int width;          // in tiles
    public int height;         // in tiles

    /**
     * The level's blocks, one grid per layer of height, index {@code 0} being
     * the floor. Dense storage — {@code [row][col]}, {@code 0} = empty —
     * and {@code null} when the level is {@link #isChunked() chunked}.
     *
     * <p><b>Why a list and not a field per layer.</b> This used to be two
     * fields, {@code tiles} and {@code upper}, because the plan views stacked
     * exactly two deep and the second layer could be named after what it was
     * for. A height axis that goes further has no such name for its fourth
     * layer, and a level's geometry is the whole column rather than a floor
     * with a lid on it: every sweep over terrain now walks {@code 0} to
     * {@link #layerCount()} without knowing how tall this level happens to be.
     *
     * <p>Layers allocate on demand, so a flat level holds one grid and costs
     * exactly what it always cost. See {@link #ensureLayer}.
     *
     * <p>Private, and reached through {@link #tiles()} / {@link #grid(int)},
     * because the two lists are mutually exclusive — a level is dense or it is
     * chunked — and a caller holding one of them directly is a caller that can
     * be handed the wrong one.
     */
    private int[][][] layers = NO_LAYERS;

    /** The same layers in sparse chunk storage (giant levels); else empty. */
    private ChunkedTiles[] layerChunks = NO_CHUNKS;

    private static final int[][][] NO_LAYERS = new int[0][][];
    private static final ChunkedTiles[] NO_CHUNKS = new ChunkedTiles[0];

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
        // The grid is being replaced, so the old high-water mark describes a
        // level that no longer exists. It rebuilds as the new one is written.
        tallestColumn = 1;
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
        lvl.layers = new int[][][]{new int[lvl.height][lvl.width]};
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
        ChunkedTiles floor = new ChunkedTiles(lvl.width, lvl.height);
        floor.setGenerator(generator);
        lvl.layerChunks = new ChunkedTiles[]{floor};
        lvl.registryTiles = true;
        lvl.spawnX = tileSize * 2;
        lvl.spawnY = tileSize * 2;
        return lvl;
    }

    /** True when this level uses sparse chunked storage (giant maps). */
    public boolean isChunked() {
        return layerChunks.length > 0;
    }

    // --- the procedural world ---------------------------------------------------

    /**
     * The endless procedural world this level was expanded into, or
     * {@code null} — which is every level that has not turned infinite terrain
     * on, and so every level that existed before it could be.
     *
     * <p>When it is set it is the level's <em>only</em> storage: the dense
     * grids and the chunk maps are both empty, and every read and write goes
     * through it. That is deliberate. A world with two storages behind it would
     * need every one of the dozen methods below to ask which one a cell belongs
     * to, and the one place that question is worth asking is where the level
     * was expanded — the authored blocks were imported into the world as edits
     * there, once, and the generator was told to leave their rectangle alone
     * (see {@link com.larsons.engine.world.gen.WorldTerrain}).
     */
    private transient com.larsons.engine.world.gen.WorldTerrain terrain;

    /** The world this level generates, or {@code null} for an ordinary level. */
    public com.larsons.engine.world.gen.WorldTerrain terrain() {
        return terrain;
    }

    /** Whether this level's geometry is an endless generated world. */
    public boolean isWorld() {
        return terrain != null;
    }

    /**
     * The cached level-of-detail terrain the far field is drawn from, or
     * {@code null} on a level that is not a generated world — those are small
     * enough to draw to their own edges. See
     * {@link com.larsons.engine.world.gen.WorldLod}.
     */
    public com.larsons.engine.world.gen.WorldLod lod() {
        return terrain == null ? null : terrain.lod();
    }

    /**
     * One column's visible faces, worked out once and kept — or {@code null} on
     * a level that is not a generated world, whose columns are three blocks
     * deep and have nothing to save.
     *
     * @see com.larsons.engine.world.gen.WorldTerrain#columnFaces(int, int)
     */
    public int[] columnFaces(int col, int row) {
        if (terrain == null) return null;
        if (col < 0 || row < 0 || col >= width || row >= height) return null;
        return terrain.columnFaces(col, row);
    }

    /**
     * Whether reading a cell of a generated world may <em>build</em> the chunk
     * it belongs to, on the calling thread. Returns what it was, so a caller
     * can put it back.
     *
     * <p><b>On for the simulation and off for the frame that draws.</b> The
     * two are asking different questions of the same world. Physics has to have
     * an answer: a body standing on ground that has not been generated yet
     * falls through it, so a read near the player builds what it needs and
     * takes the cost. The renderer does not: it is looking at ground up to a
     * hundred and ninety-two blocks away, and a chunk is tens of milliseconds
     * to generate — so a sweep that built what it wanted to draw paid for
     * whole chunks on the game loop's thread, which is the stutter every time
     * the view distance reached ground the streamer had not got to. What is
     * missing this frame is missing at the far edge of the view, in the fog,
     * for as long as it takes a worker thread to hand it over.
     *
     * @see com.larsons.engine.world.gen.WorldTerrain#setBuildOnRead(boolean)
     */
    public boolean setBuildOnRead(boolean build) {
        return terrain == null || terrain.setBuildOnRead(build);
    }

    /**
     * Make this level a generated world, replacing whatever storage it had.
     * Only {@link com.larsons.engine.world.gen.WorldExpansion} calls this — it
     * is what carries the authored blocks across.
     */
    public void setTerrain(com.larsons.engine.world.gen.WorldTerrain world) {
        if (terrain != null && terrain != world) terrain.close();
        this.terrain = world;
        if (world != null) {
            layers = NO_LAYERS;
            layerChunks = NO_CHUNKS;
            width = world.worldWidth();
            height = world.worldHeight();
        }
        bumpTerrainRevision();
    }

    /**
     * Bring this level into line with its own terrain settings — the one call
     * that turns infinite terrain on and off.
     *
     * <p>Called after the level is loaded and again whenever its settings are
     * applied, so the toggle in the settings menu is what takes effect rather
     * than the state the level happened to be saved in. Three cases:
     * <ul>
     *   <li><b>On, and no world yet</b> — the level is expanded into one
     *       ({@link com.larsons.engine.world.gen.WorldExpansion}), which is a
     *       one-time transform.</li>
     *   <li><b>On, with a world</b> — generation resumes where it left off.</li>
     *   <li><b>Off, with a world</b> — generation stops and the world keeps
     *       what has been explored. The level is <em>not</em> collapsed back:
     *       a creator who walked a valley and then froze it still has the
     *       valley, which is the point.</li>
     * </ul>
     */
    public void applyTerrainSettings() {
        com.larsons.engine.world.gen.TerrainSettings t =
                settings != null ? settings.terrain : null;
        if (t == null) return;
        // A world has to be somewhere a body can *be*. With the height axis off
        // a plan view reads the column rather than the layer — a floor tile is
        // a path and two stacked blocks are a wall — and every column of a
        // generated world is a hundred and fifty blocks of rock, so the player
        // would stand on a mountain range unable to take a step. This is not a
        // preference the setting overrides, it is what the setting means.
        if (t.enabled) settings.verticality = true;
        if (terrain == null) {
            if (t.enabled) com.larsons.engine.world.gen.WorldExpansion.expand(this, t);
            return;
        }
        // A world that no longer matches the settings it is supposed to be
        // built from grows back to them, keeping everything that was built in
        // it — a retuned biome, a new sea level or a different seed changes the
        // ground and leaves the buildings standing on it.
        if (!terrain.matches(t)) {
            setTerrain(terrain.rebuiltWith(t));
            if (t.seed == 0) t.seed = terrain.seed();
        }
        if (t.enabled) {
            terrain.thaw();
        } else {
            terrain.freeze();
        }
    }

    /**
     * Keep the world's chunks built ahead of a point — call it once a frame
     * with wherever the camera is. A no-op on a level that is not a world.
     *
     * @param viewTiles how far the detailed sweep reaches, so the ring of
     *                  chunks kept ready always covers what is being drawn
     */
    public void streamTerrain(double worldX, double worldY, int viewTiles) {
        streamTerrain(worldX, worldY, Double.NaN, viewTiles);
    }

    /**
     * {@link #streamTerrain(double, double, int)} told how high the player is
     * standing, so the ring of chunks kept ready is a function of what can be
     * seen rather than only of where the feet are.
     *
     * <p><b>Why height belongs in this.</b> A chunk is a column of world, and
     * walking into one does not mean the player has anything to do with all of
     * it: standing on a plateau you can see for a hundred tiles, and sixty
     * blocks down a mine shaft you can see the four walls of the shaft. The
     * radius was the render distance in both cases, so a player who dug down
     * went on generating — and, with {@code saveExplored} on, went on
     * <em>storing</em> — a disc of surface world a hundred and twenty tiles
     * across that they could not see a single block of.
     *
     * <p>So being under the local ground shrinks the ring, in proportion, down
     * to the handful of chunks the body itself needs to collide with. It is the
     * streaming half of the same argument the renderer's line-of-sight cull
     * makes ({@code SolidPainter.terrain}), and the two agree by construction:
     * what the eye cannot see is what the world need not build.
     *
     * <p>Being <em>above</em> the ground does not grow it. A player on a tower
     * can see further, but what is drawn is still bounded by the render
     * distance, so a wider ring would build ground nothing is going to ask for.
     *
     * @param worldZ how high the player is above the floor, or {@code NaN} when
     *               the caller has no height to offer (a flat camera, an
     *               editor) — which keeps the ring at its full radius
     */
    public void streamTerrain(double worldX, double worldY, double worldZ, int viewTiles) {
        if (terrain == null) return;
        int ts = Math.max(1, tileSize);
        int col = (int) Math.floor(worldX / ts);
        int row = (int) Math.floor(worldY / ts);
        int chunks = viewTiles / com.larsons.engine.world.gen.WorldTerrain.CHUNK + 2;
        int radius = Math.max(chunks, settings != null && settings.terrain != null
                ? settings.terrain.prefetchRadius : 4);
        terrain.prefetch(col, row, buriedRadius(col, row, worldZ, radius),
                EXPLORE_CHUNKS);
    }

    /**
     * How many chunks below the local surface a player has to be before the
     * ring starts closing in, and how many more before it is fully closed.
     *
     * <p>A little slack at the top so that walking through a shallow gully or
     * standing in a doorway does not thrash the streamer, and then a fall-off
     * measured in blocks rather than in chunks, because how far you can see
     * underground is about the rock over your head and not about the chunk
     * grid.
     */
    private static final int BURIED_SLACK_BLOCKS = 4;
    private static final int BURIED_FULL_BLOCKS = 24;

    /**
     * How many chunks the player is <em>near</em>, as against how many are
     * built for them to look at. Only these are recorded as explored, because
     * walking past a hillside at a hundred tiles is not visiting it — see
     * {@code WorldTerrain.prefetch}.
     */
    private static final int EXPLORE_CHUNKS = 2;

    /** {@code radius}, closed in by however far under the ground the player is. */
    private int buriedRadius(int col, int row, double worldZ, int radius) {
        if (Double.isNaN(worldZ) || terrain == null) return radius;
        int ts = Math.max(1, tileSize);
        double surface = surfaceZ(Math.max(1, groundedDepth(col, row) + 1));
        double under = (surface - worldZ) / ts;
        if (under <= BURIED_SLACK_BLOCKS) return radius;
        double t = Math.min(1, (under - BURIED_SLACK_BLOCKS)
                / (double) (BURIED_FULL_BLOCKS - BURIED_SLACK_BLOCKS));
        // Never below the body's own needs: physics reads the cells around the
        // player whether or not anything is drawn, and a chunk it reaches into
        // that has not been built is a player falling through the floor.
        return (int) Math.round(radius + (EXPLORE_CHUNKS - radius) * t);
    }

    // --- layer storage ----------------------------------------------------------

    /**
     * How many layers of block this level currently holds — at least one, and
     * only as many as something has actually been put in.
     *
     * <p>This is the bound every terrain sweep runs to. It is not the level's
     * <em>ceiling</em>: a level that could hold eight layers but has two blocks
     * in it answers 2, and iterating to the ceiling would walk six empty grids
     * that were never allocated.
     */
    public int layerCount() {
        // A generated world is as tall as the format allows everywhere: there
        // are no layers to have allocated, so there is nothing to count. What
        // bounds a sweep there is columnDepth, which is exact per column.
        if (terrain != null) return layerLimit();
        return layerChunks.length > 0 ? layerChunks.length : layers.length;
    }

    /**
     * The floor's dense grid — layer 0 — or {@code null} on a chunked level.
     * The layer every format has, named for what it is rather than indexed,
     * because a great deal of code has nothing to do with height and should not
     * have to say so.
     */
    public int[][] tiles() {
        return grid(LAYER_GROUND);
    }

    /** The floor's chunk storage on a giant level, else {@code null}. */
    public ChunkedTiles chunkedTiles() {
        return chunks(LAYER_GROUND);
    }

    /** One layer's dense grid, or {@code null} (chunked, or no such layer). */
    public int[][] grid(int layer) {
        return layer >= 0 && layer < layers.length ? layers[layer] : null;
    }

    /** One layer's chunk storage, or {@code null} (dense, or no such layer). */
    public ChunkedTiles chunks(int layer) {
        return layer >= 0 && layer < layerChunks.length ? layerChunks[layer] : null;
    }

    /**
     * Install one layer's dense grid wholesale — how {@link LevelLoader} lays
     * a level down as it reads it, and the only way in from outside.
     *
     * <p>Layers below {@code layer} that the file did not describe are left
     * missing rather than filled with empty grids. {@link #layerCount()} is
     * still a count and not a highest index — the array is grown to reach the
     * index — but a hole in it reads as empty everywhere ({@link #tileAt}),
     * which is what lets a file describe a tower without paying a full grid
     * for each of the layers it happens to be built out of.
     */
    public void setGrid(int layer, int[][] grid) {
        if (layer < 0 || layer >= layerLimit() || grid == null) return;
        layerChunks = NO_CHUNKS;
        if (layer >= layers.length) {
            layers = java.util.Arrays.copyOf(layers, layer + 1);
        }
        layers[layer] = grid;
        bumpTerrainRevision();
    }

    /**
     * Add one layer of chunk storage and hand it back for filling — the
     * chunked twin of {@link #setGrid}, used by the loader as it reads a giant
     * level's saved chunks.
     */
    public ChunkedTiles newChunkedLayer(int layer) {
        if (layer < 0 || layer >= layerLimit()) return new ChunkedTiles(width, height);
        layers = NO_LAYERS;
        if (layer >= layerChunks.length) {
            layerChunks = java.util.Arrays.copyOf(layerChunks, layer + 1);
        }
        if (layerChunks[layer] == null) layerChunks[layer] = new ChunkedTiles(width, height);
        bumpTerrainRevision();
        return layerChunks[layer];
    }

    /**
     * Allocate the storage for {@code layer}, matching the floor's storage
     * shape, so that something can be written into it.
     *
     * <p><b>This layer, and not the ones under it.</b> The array has to grow to
     * reach the index, but the layers it grows past are left {@code null} —
     * every reader already treats a missing grid as empty, because
     * {@link #tileAt}'s fast path was written that way from V6. Filling the
     * holes with real grids was affordable at a ceiling of eight and is not at
     * one of {@value #MAX_LAYERS}: measured on the way into Job T, a single
     * block placed at layer 511 of a 240&times;140 level allocated 511 empty
     * grids and <b>67.2 MB</b> to hold one block. It now allocates one.
     */
    private void ensureLayer(int layer) {
        if (layer < 0 || layer >= layerLimit()) return;
        if (isChunked()) {
            if (layer >= layerChunks.length) {
                layerChunks = java.util.Arrays.copyOf(layerChunks, layer + 1);
            }
            if (layerChunks[layer] == null) layerChunks[layer] = new ChunkedTiles(width, height);
        } else if (layers.length > 0) {
            if (layer >= layers.length) {
                layers = java.util.Arrays.copyOf(layers, layer + 1);
            }
            if (layers[layer] == null) layers[layer] = new int[height][width];
        }
    }

    /**
     * The tallest a plan-view level may be built, in layers — the format's
     * ceiling, and what a level that does not name one of its own gets.
     *
     * <p><b>Eight was a decision; 512 is the same decision taken again with the
     * bill paid.</b> The original reasoning stands and is worth keeping: a
     * block is a cube, so a column of <i>n</i> stands <i>n</i> tiles up the
     * screen and hides the <i>n</i> rows of floor behind it, and what limits a
     * tall stack is legibility rather than storage — the number that would fix
     * it is the camera's pitch, not this constant. None of that says eight in
     * particular, and a world advertised as three-dimensional whose sky is two
     * storeys up is not one.
     *
     * <p>What eight <em>was</em> load-bearing for was the arithmetic underneath
     * it, all of which was linear in this number and none of which said so:
     * {@link #ensureLayer} allocated a full grid per layer, {@code layerLimit}
     * asked nothing about what a level's footprint could afford, and the
     * terrain sweep walked every layer of every visible cell. Job T of
     * {@code HEIGHT_PLAN.md} has the measurements; this constant only moved
     * once they did.
     *
     * <p>It is also, deliberately, what {@link #maxLayers} defaults to — see
     * that field for why the two must be the same number.
     */
    public static final int MAX_LAYERS = 512;

    /**
     * How many tiles of dense layer storage a level may hold in total —
     * {@code width * height * layers}, the budget that decides how tall a
     * <em>dense</em> level is allowed to be.
     *
     * <p>32M tiles is 128 MB of {@code int[]} at the very worst, and it is a
     * volume limit for the same reason {@link #DENSE_TILE_LIMIT} is an area
     * one: dense storage costs the whole footprint per layer whether or not
     * anything is standing in it, so the two dimensions trade. At the default
     * 240&times;140 editor map it buys the full {@value #MAX_LAYERS}; at the
     * 1024&times;1024 dense limit it buys 32, and the level that wants both is
     * the level that wants chunked storage, where height is free.
     */
    public static final long DENSE_VOLUME_LIMIT = 32L * 1024 * 1024;

    /**
     * How tall <em>this</em> level may be built, in layers — its own ceiling
     * within the format's, chosen by whoever made it.
     *
     * <p>A level that wants to be flat says so here rather than relying on
     * nobody stacking, which is what makes "this is a maze, not a mountain" a
     * property of the level instead of a convention. Clamped into range by
     * {@link #layerLimit()}, so a hand-edited file cannot ask for a hundred
     * thousand.
     *
     * <p><b>Its default is the format ceiling, and that is what makes raising
     * the ceiling reach old levels.</b> This field is written to the save file
     * only when it differs from the default (V3), so the default is also what
     * "unspecified" means for every level ever saved — and every level saved
     * before Job T said nothing. Raising the ceiling therefore raises theirs
     * too, which is the intent: a level that meant to stay flat said so here,
     * and one that never said anything was never asked.
     */
    public int maxLayers = MAX_LAYERS;

    /**
     * How many layers this level allows: one in a side-scroller, whose screen
     * <em>is</em> the vertical plane and which therefore has no height axis to
     * stack along, and this level's {@link #maxLayers} on a plane — capped by
     * what its storage can afford ({@link #storageCeiling()}).
     *
     * <p>The side-scroller's one is not a placeholder. A second height axis on
     * top of a view that already draws height as the screen's vertical is a
     * contradiction rather than a feature, and it is the same scope rule Job C
     * of {@code RENDER_PLAN.md} worked under: the format with no second layer
     * is exactly the format that does not rotate.
     */
    public int layerLimit() {
        if (!layered()) return 1;
        return Math.max(1, Math.min(storageCeiling(), maxLayers));
    }

    /**
     * The tallest this level's <em>storage</em> can be asked to go, whatever
     * {@link #maxLayers} says.
     *
     * <p>{@value #MAX_LAYERS} on a chunked level, whose layers are sparse maps
     * of 64&times;64 chunks and cost only what is built in them. On a dense
     * level a layer is a full {@code int[height][width]} whether or not
     * anything stands in it, so the ceiling is what {@link #DENSE_VOLUME_LIMIT}
     * buys at this footprint — <b>a bigger floor buys fewer floors</b>, which
     * the editor says out loud where the ceiling is set.
     *
     * <p>Never below two, so no level is silently robbed of the one stacked
     * layer every plan view has always had; a footprint that large is over
     * {@link #DENSE_TILE_LIMIT} and is chunked anyway.
     */
    public int storageCeiling() {
        // A generated world stores columns rather than grids, so its height
        // costs nothing per cell that is not built in — the same reason chunked
        // storage is free of the volume budget below.
        if (isWorld() || isChunked()) return MAX_LAYERS;
        long area = Math.max(1L, (long) width * height);
        return (int) Math.max(2, Math.min(MAX_LAYERS, DENSE_VOLUME_LIMIT / area));
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

    /** The block on the floor at (col,row); {@code 0} = nothing there. */
    public int tileAt(int col, int row) {
        return tileAt(col, row, LAYER_GROUND);
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

    /**
     * The block at (col,row) in one layer of height; {@code 0} for empty, for a
     * cell outside the level, and for a layer this level has not built at.
     */
    public int tileAt(int col, int row, int layer) {
        if (layer < 0) return 0;
        if (terrain != null) return terrain.blockAt(col, row, layer);
        // Written against the fields rather than through grid()/chunks()
        // because this is the hottest read in the engine — LiquidSim surveys
        // the whole grid through it every tick, and SIM_PLAN.md already has
        // that scan as the p95 stall. The two arrays are mutually exclusive, so
        // the dense path costs one length check against an empty array
        // (HEIGHT_PLAN.md V6, which measured the difference at 2x).
        if (layer < layerChunks.length) return layerChunks[layer].get(col, row);
        if (layer >= layers.length) return 0;
        int[][] dense = layers[layer];
        if (dense == null || row < 0 || row >= dense.length
                || col < 0 || col >= dense[row].length) {
            return 0;
        }
        return dense[row][col];
    }

    /**
     * Set the block at (col,row) in one layer; returns whether it changed.
     *
     * <p>The layer's storage is allocated the first time something is actually
     * put in it, so a level with no walls in it costs no second grid — and a
     * write that only <em>clears</em> a layer nothing was ever built in is a
     * no-op rather than a reason to allocate one.
     */
    public boolean setTile(int col, int row, int layer, int id) {
        if (id < 0 || layer < 0 || layer >= layerLimit()) return false;
        if (id != 0 && registryTiles && blocks.get(id) == null) return false;
        if (terrain != null) {
            if (col < 0 || row < 0 || col >= width || row >= height) return false;
            boolean changed = terrain.set(col, row, layer, id);
            if (changed) {
                markTerrainChanged(col, row);
                noteColumnWrite(col, row, layer, id);
                // No clearCellAttachments here: in a generated world the floor
                // is bedrock rather than a lid something is standing on, and
                // mining one block is not a reason to delete the column over it.
                if (id == 0 && layer == LAYER_GROUND) removeSurfaceDecorAt(col, row);
            }
            return changed;
        }
        if (layer > LAYER_GROUND) {
            // Above the floor the bounds are checked here, because an
            // unallocated layer has no grid to check them against.
            if (col < 0 || row < 0 || col >= width || row >= height) return false;
        }
        if (id != 0) {
            ensureLayer(layer);
            if (layer + 1 > tallestColumn) tallestColumn = layer + 1;
        }
        ChunkedTiles sparse = chunks(layer);
        if (sparse != null) {
            boolean changed = sparse.set(col, row, id);
            if (changed) {
                markTerrainChanged(col, row);
                noteColumnWrite(col, row, layer, id);
                if (id == 0 && layer == LAYER_GROUND) clearCellAttachments(col, row);
            }
            return changed;
        }
        int[][] dense = grid(layer);
        if (dense == null || row < 0 || row >= dense.length
                || col < 0 || col >= dense[row].length) {
            return false;
        }
        if (dense[row][col] == id) return false;
        dense[row][col] = id;
        markTerrainChanged(col, row);
        noteColumnWrite(col, row, layer, id);
        if (id == 0 && layer == LAYER_GROUND) clearCellAttachments(col, row);
        return true;
    }

    /**
     * How tall one block stands, as a fraction of a tile in world units.
     *
     * <p><b>One: blocks are cubes.</b> This was 0.55, chosen as a trade — tall
     * enough that a side face reads as a wall at a glance, short enough that a
     * wall does not swallow the row of floor behind it. {@code HEIGHT_PLAN.md}
     * R0 resolves that trade the other way on purpose: a cube <em>does</em>
     * swallow the row behind it, because that is what a cube standing on a
     * floor does, and a world built of half-height slabs never reads as one
     * built of blocks.
     *
     * <p>It also makes a layer of height exactly a tile, so {@code z} and the
     * layer index share a unit: layer {@code n}'s surface is at
     * {@code n * tileSize}, and every conversion in the simulation is a
     * multiply by the tile size rather than by a constant nobody can hold in
     * their head.
     *
     * <p>It lives on the level rather than on the painter because it is not a
     * drawing constant: {@link #blockHeight()} is how far a body rises when it
     * climbs a block, and the renderer and the physics have to be reading the
     * same number or characters stand in the air.
     */
    public static final double BLOCK_HEIGHT = 1.0;

    /**
     * The deepest column this level is known to hold — a high-water mark, not
     * a survey.
     *
     * <p>Anything reasoning about how far a column can reach off its own cell
     * needs this: the terrain sweep culls a cell on its own projected corners,
     * and a tall column paints far above them, so the cull margin has to be
     * told how tall the level gets. Asking for the true maximum would mean
     * sweeping the level, which is what culling exists to avoid.
     *
     * <p>So it only ever rises, and it is reset when the grid is replaced
     * wholesale. Mining a level's one tower leaves this reading the tower's
     * height until something reloads it, which costs a slightly generous margin
     * and never a missing block — the direction to be wrong in.
     */
    private transient int tallestColumn = 1;

    /** The deepest column this level is known to hold; see {@link #tallestColumn}. */
    public int tallestColumn() {
        return Math.max(1, Math.min(layerLimit(), tallestColumn));
    }

    /**
     * How many layers of <em>this</em> column anything need look at: one past
     * its highest filled layer, and {@code 0} for a column with nothing in it
     * at all.
     *
     * <p><b>What this is for.</b> The terrain sweep visits every layer of every
     * visible cell, and its bound used to be {@link #layerCount()} — the number
     * of layers <em>the level</em> has. Those are the same number only while
     * every column is about as tall as the tallest one. Put one 512-block tower
     * in a level of eight-block buildings and every cell on screen walks 512
     * layers to find seven blocks: measured at <b>2.59 ms a frame</b> over 2000
     * visible cells, which is 15% of a 60 Hz budget spent discovering that the
     * sky is empty. Asked per column instead, the same sweep costs 0.06 ms.
     *
     * <p><b>Why this is a cache and {@code stackHeight} is not.</b> Appendix B
     * of {@code HEIGHT_PLAN.md} refused a per-cell height cache at V6, and was
     * right to: it measured {@code stackHeight} at 0.14 ms over a whole 256²
     * grid and judged that a structure to invalidate in step with every terrain
     * write bought nothing. That measurement is linear in the layer ceiling and
     * was taken when the ceiling was eight. At 512 the same sweep is 9 ms. The
     * decision is reversed on the arithmetic that made it, not against it.
     *
     * <p><b>Why it cannot go stale low, which is the only direction that
     * matters.</b> Too high costs a few empty layers of walking; too low drops
     * geometry off the screen. So every way the terrain can change either
     * maintains this or discards it:
     *
     * <ul>
     *   <li>{@link #setTile} <b>raises</b> the bound when it fills a layer, and
     *       <b>forgets</b> the cell when it clears the top one — a clear below
     *       the top cannot move it.</li>
     *   <li>Everything that replaces terrain wholesale — {@link #setGrid},
     *       {@link #newChunkedLayer}, {@link #restoreTiles}, {@link #resize},
     *       {@link #restoreBounds} — goes through {@link #bumpTerrainRevision},
     *       and the whole table is dropped when the generation moves.</li>
     *   <li>The one writer that does neither is {@link LevelGenerator}, which
     *       assigns into {@code tiles()} directly — and only ever into the
     *       <b>floor</b>. This bounds the walk <em>above</em> the floor, where
     *       the floor pass never looks, so a floor write cannot invalidate it.
     *       {@code TallWorldTest} pins that, rather than trusting it.</li>
     * </ul>
     *
     * <p>A cell that was never told anything computes itself exactly, once.
     */
    public int columnDepth(int col, int row) {
        if (col < 0 || row < 0 || col >= width || row >= height) return 0;
        // A generated world knows this exactly and for nothing: a column is
        // stored as runs, so its top is the last number in the array.
        if (terrain != null) return terrain.columnDepth(col, row);
        int[] bounds = columnBounds();
        if (bounds == null) return layerCount();
        int at = row * width + col;
        int known = bounds[at];
        if (known != COLUMN_UNKNOWN) return known;
        int depth = topFilledLayer(col, row) + 1;
        bounds[at] = depth;
        return depth;
    }

    /**
     * The layers of one column a terrain sweep has to look at, written into
     * {@code out} in ascending order; returns how many.
     *
     * <p>Every layer from 1 to {@link #columnDepth}, on every level whose
     * columns are a handful of blocks deep: there is nothing to save by picking
     * through a stack of three, and the sweep has to see layer 1 on the formats
     * where that is the wall you are standing next to.
     *
     * <p>On a generated world it is the layers that are not walled in on all
     * six sides, which is the difference between drawing a mountain and drawing
     * every block inside one. A hundred and fifty layers of buried rock per
     * visible cell is not a slow frame, it is no frame at all; see
     * {@link com.larsons.engine.world.gen.WorldTerrain#visibleLayers}.
     */
    public int visibleLayers(int col, int row, int[] out) {
        int depth = columnDepth(col, row);
        if (terrain != null) return terrain.visibleLayers(col, row, depth, out);
        int n = 0;
        for (int layer = 1; layer < depth && n < out.length; layer++) out[n++] = layer;
        return n;
    }

    /**
     * How many blocks stand in an unbroken run on this cell's floor — the
     * height a renderer may treat this column as solid to.
     *
     * <p><b>Not {@link #columnDepth}, and the difference is a cave.</b> That
     * answers where the column's highest block is; this answers where its first
     * gap is. Line-of-sight culling needs the second one, because a column with
     * a hole in it is a column you can see through, and a mountain with a cave
     * mouth in it is the case where assuming otherwise paints out the world.
     *
     * <p>It is also not {@link #stackHeight}, which counts from layer 0 and is
     * about what holds a body up. This counts the blocks standing <em>on</em>
     * the floor, so a bare floor answers zero — the ground plane itself is at
     * height zero and needs no counting.
     *
     * <p>Answered from the run encoding on a generated world, where it costs a
     * scan of a handful of runs rather than of a hundred and fifty layers.
     */
    public int groundedDepth(int col, int row) {
        if (col < 0 || row < 0 || col >= width || row >= height) return 0;
        if (terrain != null) return terrain.groundedDepth(col, row);
        int depth = columnDepth(col, row);
        int n = 0;
        while (n + 1 < depth && tileAt(col, row, n + 1) > 0) n++;
        return n;
    }

    /**
     * The tallest ground in a rectangle of cells and what it is made of, for
     * the coarse pass that draws the horizon — {@code out[0]} the layer,
     * {@code out[1]} the block id, {@code out[2]} the <em>lowest</em> ground in
     * the same rectangle.
     *
     * <p>That third number is what a line-of-sight cull may treat the whole
     * rectangle as solid up to. The box drawn for a group stands at the height
     * of its tallest column, but claiming a group occludes to the top of the
     * one spike in it would cull the valley behind that spike, so the low mark
     * is the honest one to occlude with.
     *
     * <p>On an ordinary level this is the obvious sweep of the cells. On a
     * generated world it is answered from sampled heights instead, because the
     * horizon reaches two thousand blocks and the sixteen million columns
     * inside that radius are not something to generate in order to find out how
     * tall they are.
     *
     * @return whether there was anything there — {@code false} for open sky,
     *         and for a generated world whose horizon has not been sampled yet
     */
    public boolean groupTop(int c0, int r0, int c1, int r1, int[] out) {
        c0 = Math.max(0, c0);
        r0 = Math.max(0, r0);
        c1 = Math.min(width, c1);
        r1 = Math.min(height, r1);
        if (c0 >= c1 || r0 >= r1) return false;
        if (terrain != null) return terrain.groupTop(c0, r0, c1, r1, out);
        int tallest = -1, tallestId = 0, lowest = Integer.MAX_VALUE;
        for (int r = r0; r < r1; r++) {
            for (int c = c0; c < c1; c++) {
                int floor = tileAt(c, r);
                if (floor <= 0) {
                    lowest = 0;   // open ground occludes nothing
                    continue;
                }
                int layer = 0, id = floor;
                int depth = columnDepth(c, r);
                for (int l = 1; l < depth; l++) {
                    int at = tileAt(c, r, l);
                    if (at > 0) {
                        layer = l;
                        id = at;
                    }
                }
                if (layer > tallest) {
                    tallest = layer;
                    tallestId = id;
                }
                if (layer < lowest) lowest = layer;
            }
        }
        if (tallest < 0) return false;
        out[0] = tallest;
        out[1] = tallestId;
        out[2] = Math.max(0, Math.min(tallest, lowest));
        return true;
    }

    /**
     * The highest layer of one column worth looking at, walking downward: the
     * top of the column on a generated world, and the top of the level
     * everywhere else.
     *
     * <p>The two are the same number on a hand-built level, whose layer count
     * is only as tall as something has been put. They are not on a world, whose
     * columns are stored as runs and whose ceiling is the format's 512 — so a
     * downward walk that started there would read three hundred and fifty
     * layers of empty sky before reaching the ground, on every call, for every
     * body, every tick.
     */
    private int ceilingOf(int col, int row) {
        if (terrain != null) return Math.max(0, terrain.columnDepth(col, row) - 1);
        return layerCount() - 1;
    }

    /** Sentinel for a column whose depth has not been worked out yet. */
    private static final int COLUMN_UNKNOWN = -1;

    /** Per-cell {@link #columnDepth}, and the generation it was built for. */
    private transient int[] columnBounds;
    private transient long columnBoundsGeneration = -1;

    /**
     * The per-cell depth table, rebuilt empty when the terrain was replaced
     * under it, or {@code null} when this level is too large to hold one.
     *
     * <p>The size guard is not defensive dressing: a giant level is up to
     * 65536² cells, and a table over that is 16 GB. Those levels are chunked,
     * which is the storage whose layers are cheap in the first place, so they
     * simply go on paying {@link #layerCount()} — the bound this had before.
     */
    private int[] columnBounds() {
        long cells = (long) width * height;
        if (cells <= 0 || cells > DENSE_TILE_LIMIT) return null;
        if (columnBounds == null || columnBounds.length != (int) cells
                || columnBoundsGeneration != terrainGeneration) {
            columnBounds = new int[(int) cells];
            java.util.Arrays.fill(columnBounds, COLUMN_UNKNOWN);
            columnBoundsGeneration = terrainGeneration;
        }
        return columnBounds;
    }

    /** Keep {@link #columnDepth} honest across one cell write; see its note. */
    private void noteColumnWrite(int col, int row, int layer, int id) {
        if (columnBounds == null || columnBoundsGeneration != terrainGeneration) return;
        if (col < 0 || row < 0 || col >= width || row >= height) return;
        int at = row * width + col;
        if (at >= columnBounds.length) return;
        int known = columnBounds[at];
        if (known == COLUMN_UNKNOWN) return;
        if (id != 0) {
            if (layer + 1 > known) columnBounds[at] = layer + 1;
        } else if (layer + 1 >= known) {
            // The top of the column just went; where the new top is takes a
            // scan, and the next reader is the one who needs the answer.
            columnBounds[at] = COLUMN_UNKNOWN;
        }
    }

    /** How tall one block stands, in this level's world units. */
    public double blockHeight() {
        return BLOCK_HEIGHT * tileSize;
    }

    /**
     * Whether this level's height axis is somewhere a body can <em>be</em> —
     * climbing stacks, standing on their tops, walking off and falling — rather
     * than only something that stops them.
     *
     * <p>False unless the level says otherwise, which is what keeps every level
     * saved before this existed playing exactly as it did: see
     * {@link GameProfile#verticality}. Never true in a side-scroller, whose
     * height axis is the screen's vertical and already has a gravity of its own.
     */
    public boolean verticality() {
        return layered() && settings != null && settings.verticality;
    }

    /** The world-space height of the top of a column {@code layers} deep. */
    public double surfaceZ(int layers) {
        return (layers - 1) * blockHeight();
    }

    /**
     * How many blocks of this column hold a body up, counted from the floor:
     * {@code 0} is a hole and there is nowhere to stand.
     *
     * <p><b>The third of the three questions a column answers, and the one
     * movement asks.</b> {@link #stackHeight} is geometry — everything that is
     * there, which is what the renderer extrudes. {@link #topSolidLayer} is
     * what would stop you walking into the column. This is what you would be
     * standing <em>on</em> if you were there, and it is none of the others:
     *
     * <ul>
     *   <li>The <b>floor</b> holds you up whatever it is made of. {@code
     *       stone_path} is registered passable — you walk across it, it does
     *       not block you — and it is still the ground.</li>
     *   <li><b>Above the floor</b>, only solid blocks hold you up. A torch
     *       standing on a path is a whole block of geometry and you walk
     *       straight through it at ground level, so it lifts nobody.</li>
     *   <li>The run is <b>contiguous</b>: a solid block floating over a gap
     *       supports nothing, because there is nothing under it to stand on.</li>
     * </ul>
     *
     * <p>So a path answers 1 and its surface is {@code z = 0}; a path with a
     * torch on it also answers 1; a wall answers 2 and its top is one block up.
     */
    public int supportHeight(int col, int row) {
        return supportUnder(col, row, Integer.MAX_VALUE);
    }

    /**
     * The support height of the column at (col,row) as seen from
     * {@code fromLayer} — the top of the run of ground at or below that layer.
     *
     * <p><b>The generalisation a gap in a column forces.</b> While every column
     * is solid from the ground up there is one surface and asking "which one"
     * is meaningless, which is why {@link #supportHeight} does not ask. Let a
     * column have a bridge over it and there are two, and which one holds you
     * up is decided by which one you are under: standing beneath the deck the
     * ground is the ground, standing on it the ground is the deck.
     *
     * <p>Walks down rather than up, so it finds the nearest surface below the
     * body rather than the lowest in the column. For a column with no gaps the
     * two are the same answer, which is why every heightfield caller can go on
     * asking {@link #supportHeight} and get what it always got.
     */
    public int supportUnder(int col, int row, int fromLayer) {
        int top = Math.min(fromLayer, ceilingOf(col, row));
        for (int layer = top; layer >= 0; layer--) {
            if (supports(col, row, layer)) return layer + 1;
        }
        return 0;
    }

    /**
     * Whether one layer of a column holds a body up.
     *
     * <p>The floor counts whatever it is made of — {@code stone_path} is
     * passable and is still the ground — and above it only solid blocks do, so
     * a torch lifts nobody. See {@link #supportHeight}.
     */
    private boolean supports(int col, int row, int layer) {
        return layer == LAYER_GROUND ? tileAt(col, row, LAYER_GROUND) > 0
                : solidAt(col, row, layer);
    }

    /**
     * Whether a body {@code height} layers tall standing on layer
     * {@code onLayer} has room — nothing solid in the layers it would occupy.
     *
     * <p>Trivially true in a heightfield, where nothing is ever above a
     * column's own top. It is what makes a tunnel a tunnel once a column may
     * have a gap in it ({@code HEIGHT_PLAN.md} O2).
     */
    public boolean headRoom(int col, int row, int onLayer, int height) {
        int limit = Math.min(layerCount(), onLayer + Math.max(1, height));
        for (int layer = Math.max(0, onLayer); layer < limit; layer++) {
            if (solidAt(col, row, layer)) return false;
        }
        return true;
    }

    /**
     * How many blocks deep the stack at (col,row) is: {@code 0} bare ground,
     * {@code 1} a floor to walk on, {@code 2} a wall. Only meaningful in a
     * {@link #layered()} level; a side-scroller answers 0 or 1.
     *
     * <p>Counted as the <em>contiguous</em> run up from the floor, which is
     * what makes it a height: a block with nothing under it is not two blocks
     * of height, it is a thing that should not exist, and reading the run
     * rather than the layer count is what will keep this honest when
     * {@code HEIGHT_PLAN.md} O1 lets a column have a gap in it.
     */
    public int stackHeight(int col, int row) {
        int layers = terrain != null ? terrain.columnDepth(col, row) : layerCount();
        for (int layer = 0; layer < layers; layer++) {
            if (tileAt(col, row, layer) <= 0) return layer;
        }
        return layers;
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
        Block up = blockAt(col, row, LAYER_UPPER);
        return up == null || !up.solid();
    }

    /**
     * Whether the tile at (col,row) blocks movement <em>for a body standing on
     * the ground</em>. Layered plan-view levels ask the stack
     * ({@link #walkable}); the side-scroller asks the block definition, and
     * palette mode keeps the legacy "any tile is solid".
     *
     * <p><b>This is a question with a missing argument, and it is kept anyway.</b>
     * Once a body can stand at a height, "is this cell solid" needs to say
     * <em>at what height</em> — which is {@link #solidAt(int, int, int)}. The
     * two-argument form is what its callers have always meant: a body walking
     * the floor, asking whether the column in front of it is a barrier. Most of
     * them go on meaning exactly that, so it keeps the short name and the
     * documented assumption rather than being renamed into something every call
     * site has to be re-read to understand.
     */
    public boolean solidAt(int col, int row) {
        if (layered()) return !walkable(col, row);
        int id = tileAt(col, row);
        if (id <= 0) return false;
        return !registryTiles || blocks.isSolid(id);
    }

    /**
     * Whether one layer of (col,row) is filled with something a body cannot
     * pass through — the question {@link #solidAt(int, int)} asks of a whole
     * column, asked of a single block.
     *
     * <p>Empty is not solid, and neither is dressing: a torch or a flower
     * standing in a layer leaves it open, which is the same rule
     * {@link #walkable} applies and the reason height and walkability are not
     * the same question.
     */
    public boolean solidAt(int col, int row, int layer) {
        int id = tileAt(col, row, layer);
        if (id <= 0) return false;
        if (!registryTiles) return true;   // palette mode: any tile is solid
        return blocks.isSolid(id);
    }

    /**
     * The highest layer at (col,row) holding something solid, or {@code -1}
     * when the column holds nothing to stand on or walk into.
     *
     * <p><b>Deliberately not {@link #stackHeight} minus one.</b> Height is
     * geometry and solidity is what stops you, and the two come apart in both
     * directions. A torch on a path is two blocks of geometry with nothing
     * solid above the floor, so the column is {@code stackHeight} 2 and this
     * answers 0 — the torch has a face to draw and no barrier to collide with.
     * A block left floating over a hole is {@code stackHeight} 0, because the
     * run from the ground never starts, and this answers whichever layer it is
     * sitting in.
     *
     * <p>That second case is the first place in this engine where the volume
     * and the heightfield disagree, and it is pinned by a test rather than left
     * to be discovered: {@code HEIGHT_PLAN.md} O1 is where a column is allowed
     * a gap on purpose, and it should find the semantics already decided.
     */
    public int topSolidLayer(int col, int row) {
        // A generated world knows where its own top is, so the walk starts
        // there rather than at the ceiling of the format — 512 layers down to
        // 150 is 360 reads of empty sky, per call, per body, per tick.
        for (int layer = ceilingOf(col, row); layer >= 0; layer--) {
            if (solidAt(col, row, layer)) return layer;
        }
        return -1;
    }

    /**
     * The highest layer at (col,row) holding <em>anything</em>, or {@code -1}
     * for an empty cell.
     *
     * <p>The geometry twin of {@link #topSolidLayer}, and what a tool that
     * takes a column apart asks: the eraser bites whatever is on top whether or
     * not it is a barrier, so a torch standing on a wall comes off before the
     * wall does. Reading down from the ceiling rather than up from the floor is
     * what makes it right over a gap — the deck of a bridge is the top of that
     * column even though the run from the ground stopped underneath it
     * ({@code HEIGHT_PLAN.md} O1).
     */
    public int topFilledLayer(int col, int row) {
        if (terrain != null) return terrain.columnDepth(col, row) - 1;
        for (int layer = layerCount() - 1; layer >= 0; layer--) {
            if (tileAt(col, row, layer) > 0) return layer;
        }
        return -1;
    }

    /** The block definition at (col,row), or {@code null} (empty / palette mode). */
    public Block blockAt(int col, int row) {
        return blockAt(col, row, LAYER_GROUND);
    }

    /** The block definition in one layer at (col,row), or {@code null}. */
    public Block blockAt(int col, int row, int layer) {
        if (!registryTiles) return null;
        return blocks.get(tileAt(col, row, layer));
    }

    /**
     * The block a player interacts with at (col,row) — the topmost one in the
     * column, else the floor. Mining takes the stack apart from the top down,
     * which is what makes a wall become a path and then a hole.
     */
    public Block topBlockAt(int col, int row) {
        int top = stackHeight(col, row) - 1;
        return blockAt(col, row, Math.max(LAYER_GROUND, top));
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
        if (!verticality()) {
            // Two layers, and the second one is the top of the world. The rule
            // every level saved before the height axis was built under.
            Block up = blockAt(col, row, LAYER_UPPER);
            if (up == null) return LAYER_UPPER;
            return up.liquid() ? LAYER_UPPER : -1;
        }
        // With the axis live a column is built as high as the level allows,
        // and the next block goes on top of whatever is there. A liquid on top
        // is covered rather than stood on — covering a pool is how pools are
        // removed, since they cannot be mined.
        int height = stackHeight(col, row);
        Block top = blockAt(col, row, height - 1);
        if (top != null && top.liquid()) return height - 1;
        return height < layerLimit() ? height : -1;
    }

    /**
     * The liquid occupying (col,row), or {@code null} (swim/damage checks).
     * A pool in a layered level sits on the floor rather than replacing it, so
     * the stacked layer is looked at first and the floor is the fallback.
     */
    public Block liquidAt(int col, int row) {
        Block up = blockAt(col, row, LAYER_UPPER);
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
        // A generated world's bounds are the world's, not a slider's: shrinking
        // them would cut off ground that has been walked on and is saved, and
        // growing them would claim ground the world was not built to hold. The
        // size that matters there is set with the terrain settings.
        if (terrain != null) return;
        if (isChunked()) {
            // Chunked levels resize in place: chunks outside the bounds unload.
            // A null layer is one nothing was ever built in (ensureLayer) and
            // has no chunks to unload, so it stays null rather than being
            // materialized by a slider.
            for (ChunkedTiles layer : layerChunks) {
                if (layer != null) layer.resize(newWidth, newHeight);
            }
        } else if ((long) newWidth * newHeight > DENSE_TILE_LIMIT) {
            // Growing past the dense limit converts to chunked storage, every
            // layer of it — a level that dropped its stack on the way past the
            // limit would lose its walls to a slider.
            ChunkedTiles[] converted = new ChunkedTiles[layers.length];
            for (int i = 0; i < layers.length; i++) {
                converted[i] = toChunked(layers[i], newWidth, newHeight);
            }
            layerChunks = converted;
            layers = NO_LAYERS;
        } else {
            int[][][] next = new int[layers.length][][];
            for (int i = 0; i < layers.length; i++) {
                next[i] = resized(layers[i], newWidth, newHeight);
            }
            layers = next;
        }
        width = newWidth;
        height = newHeight;
        // Every grid was re-cut and every cell moved, so anything holding a
        // picture of this terrain is stale — including the column bounds
        // below, which are indexed by a width that has just changed.
        bumpTerrainRevision();
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
     * One layer, re-cut to new bounds, keeping the overlapping region. A layer
     * nothing was ever built in stays unbuilt: allocating a grid for it here
     * would undo {@link #ensureLayer}'s sparsity every time a size slider moved.
     */
    private int[][] resized(int[][] layer, int newWidth, int newHeight) {
        if (layer == null) return null;
        int[][] next = new int[newHeight][newWidth];
        for (int r = 0; r < Math.min(height, newHeight); r++) {
            System.arraycopy(layer[r], 0, next[r], 0, Math.min(width, newWidth));
        }
        return next;
    }

    /** One dense layer converted to chunked storage at the new bounds. */
    private ChunkedTiles toChunked(int[][] layer, int newWidth, int newHeight) {
        if (layer == null) return null;
        ChunkedTiles next = new ChunkedTiles(newWidth, newHeight);
        for (int r = 0; r < Math.min(height, newHeight); r++) {
            for (int c = 0; c < Math.min(width, newWidth); c++) {
                if (layer[r][c] != 0) next.set(c, r, layer[r][c]);
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
        return setTile(col, row, LAYER_GROUND, id);
    }

    /**
     * Paint a whole stack at (col,row): the block as floor <em>and</em> stacked
     * on itself, which is what a wall is. In an unlayered level it just sets
     * the tile.
     */
    public boolean stackTile(int col, int row, int id) {
        boolean changed = setTile(col, row, id);
        return setTile(col, row, LAYER_UPPER, id) || changed;
    }

    /**
     * Lay {@code id} across the ground layer, so a plan-view canvas is floor
     * rather than holes. Dense levels are filled outright; a giant level takes
     * a generator instead, which fills each chunk as the camera reaches it.
     */
    public void fillFloor(int id) {
        if (id <= 0) return;
        if (isChunked()) {
            chunkedTiles().setGenerator(flatGenerator(id));
            return;
        }
        int[][] floor = tiles();
        if (floor == null) return;
        for (int[] row : floor) java.util.Arrays.fill(row, id);
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

    /** Cell data that follows its block: clearing the cell drops it too. */
    private void clearCellAttachments(int col, int row) {
        // The floor going means the whole column goes with it — there is
        // nothing left to hold a block up, and a stacked block over a hole
        // reads as neither.
        for (int layer = LAYER_GROUND + 1; layer < layerCount(); layer++) {
            setTile(col, row, layer, 0);
        }
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
        if (isChunked()) {
            ChunkedTiles floorLayer = chunkedTiles();
            floorLayer.setGenerator(flatGenerator(floorId));
            floorLayer.forEachLoadedCell((col, row, id) -> {
                if (blocks.isSolid(id)) {
                    setTile(col, row, LAYER_UPPER, id);
                } else if (id == 0) {
                    floorLayer.set(col, row, floorId);
                }
            });
            return;
        }
        if (tiles() == null) return;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int id = tileAt(c, r);
                if (id == 0) {
                    setTile(c, r, floorId);
                } else if (blocks.isSolid(id)) {
                    setTile(c, r, LAYER_UPPER, id);
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
        if (terrain != null) return terrain.snapshot();
        return new TerrainSnapshot(snapshotLayers());
    }

    /**
     * Restore terrain saved by {@link #snapshotTiles} (no-op on mismatch).
     *
     * <p><b>Bumps the generation, which it did not used to.</b> This writes
     * whole layers straight into their grids rather than going through
     * {@link #setTile}, so nothing downstream heard about it: a play-test that
     * mined a wall left {@link com.larsons.engine.graphics.TerrainCache} still
     * holding the mined picture after the level was put back, and the editor
     * drew a hole that was no longer there until something else happened to
     * dirty that chunk. Replacing the terrain wholesale is exactly what
     * {@link #bumpTerrainRevision} is for.
     */
    public void restoreTiles(Object snapshot) {
        if (terrain != null) {
            if (snapshot instanceof com.larsons.engine.world.gen.WorldTerrain.Snapshot saved) {
                terrain.restore(saved);
            }
            bumpTerrainRevision();
            return;
        }
        if (snapshot instanceof TerrainSnapshot saved) {
            restoreLayers(saved.layers());
        } else {
            // A snapshot from before the second layer existed: ground only.
            restoreLayer(LAYER_GROUND, snapshot);
        }
        bumpTerrainRevision();
    }

    /** Every layer of a play-test terrain snapshot; any entry may be null. */
    private record TerrainSnapshot(List<Object> layers) {}

    /** Deep copy of every layer, whichever storage this level uses. */
    private List<Object> snapshotLayers() {
        List<Object> copies = new ArrayList<>(layerCount());
        for (int layer = 0; layer < layerCount(); layer++) {
            copies.add(snapshotLayer(chunks(layer), grid(layer)));
        }
        return copies;
    }

    /**
     * Put every layer back, allocating any the level has since dropped — a
     * play-test that mined a level's last stacked block would otherwise have
     * nowhere to restore the walls into.
     */
    private void restoreLayers(List<Object> saved) {
        if (saved == null) return;
        for (int layer = 0; layer < saved.size(); layer++) {
            if (saved.get(layer) != null) ensureLayer(layer);
            restoreLayer(layer, saved.get(layer));
        }
    }

    /** Deep copy of one layer, whichever storage it uses. */
    private static Object snapshotLayer(ChunkedTiles sparse, int[][] dense) {
        if (sparse != null) return sparse.snapshot();
        if (dense == null) return null;
        int[][] copy = new int[dense.length][];
        for (int r = 0; r < dense.length; r++) copy[r] = dense[r].clone();
        return copy;
    }

    /** Put one layer back; a snapshot that doesn't match the storage is ignored. */
    private void restoreLayer(int layer, Object snapshot) {
        ChunkedTiles sparse = chunks(layer);
        int[][] dense = grid(layer);
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
     * Everything one cell holds: its whole column of blocks, plus the data that
     * hangs off them. This is the unit the creative editor's undo saves terrain
     * in ({@link EditHistory}) — one of these per cell a stroke is about to
     * touch, because a level has millions of cells and a stroke touches a
     * handful.
     *
     * <p>It is the whole cell rather than a tile id because clearing a cell's
     * floor takes everything standing on it, its surface details and its
     * container with it ({@link #setTile}): put back only the id and a mined
     * wall comes back as a path with its moss gone.
     *
     * <p>It is the whole <em>column</em> rather than a floor and a lid because
     * a column is eight deep now. Undo that restored two layers of a five-deep
     * tower would be a corruption bug wearing an editor bug's clothes: the
     * stroke would come back looking undone and the cell would be three blocks
     * shorter than it started.
     *
     * <p><b>{@code equals} is spelled out because the array would otherwise
     * compare by identity</b>, and {@link EditHistory}'s {@code FieldEdit}
     * decides whether an edit happened at all by comparing a fresh snapshot
     * against the one it took. Left to a record's default, every brush stroke
     * that changed nothing would still push an undo step — a defect that shows
     * up as Ctrl+Z doing nothing several times in a row.
     */
    public record CellState(int col, int row, int[] column,
                            List<SurfaceDecor.Placement> decor, List<ItemStack> container) {

        /** Copies the column, so a snapshot cannot be edited from underneath. */
        public CellState {
            column = column == null ? new int[0] : column.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CellState c && col == c.col && row == c.row
                    && java.util.Arrays.equals(column, c.column)
                    && java.util.Objects.equals(decor, c.decor)
                    && java.util.Objects.equals(container, c.container);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(col, row, java.util.Arrays.hashCode(column),
                    decor, container);
        }

        /** Spells the column out, because these turn up in test failures. */
        @Override
        public String toString() {
            return "CellState[" + col + "," + row + " column="
                    + java.util.Arrays.toString(column) + " decor=" + decor
                    + " container=" + container + "]";
        }
    }

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
        int[] column = new int[layerCount()];
        for (int layer = 0; layer < column.length; layer++) {
            column[layer] = tileAt(col, row, layer);
        }
        return new CellState(col, row, column,
                decor, held == null ? null : List.copyOf(held));
    }

    /** Put a cell back exactly as {@link #captureCell} found it. */
    public void restoreCell(CellState state) {
        if (state == null) return;
        int col = state.col(), row = state.row();
        int[] column = state.column();
        // The floor goes back first: writing it can clear the whole cell
        // (see clearCellAttachments), so everything else has to be laid on top
        // of that cascade rather than under it.
        setTile(col, row, LAYER_GROUND, column.length > 0 ? column[0] : 0);
        for (int layer = 1; layer < column.length; layer++) {
            setTile(col, row, layer, column[layer]);
        }
        // Layers the level has grown since the snapshot are cleared rather than
        // left standing: undoing the stroke that built a tower has to take the
        // whole tower down, not just the part the snapshot happened to describe.
        for (int layer = Math.max(1, column.length); layer < layerCount(); layer++) {
            setTile(col, row, layer, 0);
        }
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
                      int authoredHeading, double authoredPitchDegrees,
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
        return new Doc(name, music, lightAngle, authoredHeading, authoredPitchDegrees,
                spawnX, spawnY,
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
        authoredHeading = doc.authoredHeading();
        authoredPitchDegrees = doc.authoredPitchDegrees();
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
     * cell: a {@link #resize}, which re-cuts every tile layer and drops
     * whatever fell outside the new bounds.
     *
     * <p>Which storage the level used is part of it, because growing past
     * {@link #DENSE_TILE_LIMIT} turns a dense grid into chunks and resizing
     * back down does not turn it back — undo has to reinstate the grid itself,
     * not just its bounds.
     */
    public record Bounds(int width, int height, List<Object> layers,
                         boolean chunkedStorage, ChunkGenerator generator,
                         List<EntitySpawn> entities,
                         List<SurfaceDecor.Placement> surfaceDecor,
                         Map<Long, List<ItemStack>> containers,
                         double spawnX, double spawnY) {}

    /** Save the level's size and everything a resize would re-cut or drop. */
    public Bounds snapshotBounds() {
        ChunkedTiles floor = chunkedTiles();
        return new Bounds(width, height, snapshotLayers(),
                isChunked(), floor != null ? floor.generator() : null,
                List.copyOf(entities), List.copyOf(surfaceDecor),
                copyContainers(containers), spawnX, spawnY);
    }

    /** Put back a {@link #snapshotBounds()} (the snapshot stays reusable). */
    public void restoreBounds(Bounds saved) {
        if (saved == null) return;
        width = saved.width();
        height = saved.height();
        List<Object> savedLayers = saved.layers();
        layers = NO_LAYERS;
        layerChunks = NO_CHUNKS;
        // A snapshot always describes at least the floor, even when the level
        // it came from had nothing in it.
        int depth = Math.max(1, savedLayers.size());
        if (saved.chunkedStorage()) {
            List<ChunkedTiles> rebuilt = new ArrayList<>(depth);
            for (int layer = 0; layer < depth; layer++) {
                ChunkedTiles next = new ChunkedTiles(width, height);
                if (layer == LAYER_GROUND) next.setGenerator(saved.generator());
                Object s = layer < savedLayers.size() ? savedLayers.get(layer) : null;
                if (s instanceof ChunkedTiles.Snapshot snap) next.restore(snap);
                else if (layer > LAYER_GROUND) continue;   // no such layer to reinstate
                rebuilt.add(next);
            }
            layerChunks = rebuilt.toArray(new ChunkedTiles[0]);
        } else {
            List<int[][]> rebuilt = new ArrayList<>(depth);
            for (int layer = 0; layer < depth; layer++) {
                Object s = layer < savedLayers.size() ? savedLayers.get(layer) : null;
                if (s instanceof int[][] g) {
                    rebuilt.add(denseCopy(g));
                } else if (layer == LAYER_GROUND) {
                    rebuilt.add(new int[height][width]);
                }
            }
            layers = rebuilt.toArray(new int[0][][]);
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
        // Absent when the level was built square to the world, which is every
        // level written before headings existed and every side-scroller ever.
        if (authoredHeading != 0) m.put("heading", authoredHeading);
        if (authoredPitchDegrees > 0) m.put("pitch", authoredPitchDegrees);
        m.put("tileSize", tileSize);
        m.put("width", width);
        m.put("height", height);
        // The third dimension, written beside the other two and only when the
        // level has an opinion about it.
        if (maxLayers != MAX_LAYERS) m.put("maxLayers", maxLayers);
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
        if (terrain != null) {
            // A generated world: the seed it rebuilds from, the chunks that
            // have been visited, and the ones that have been changed. The
            // terrain itself is never written down — it is a function of the
            // seed, and the file would be gigabytes to say so.
            m.put("world", terrain.toMap());
        } else if (isChunked()) {
            // Giant levels: only edited chunks persist (RLE-compressed); the
            // rest rebuilds from the generator seed on load.
            ChunkedTiles floor = chunkedTiles();
            m.put("chunked", true);
            m.put("chunkSize", ChunkedTiles.CHUNK);
            if (floor.generator() != null) {
                m.put("generatorSeed", floor.generator().seed());
                // Which generator, not just its seed: a plan view's chunks are
                // floor, and rebuilding them as side-scrolling terrain would
                // hand the level back full of holes and hills.
                if (floor.generator() instanceof FlatChunks) m.put("generator", "flat");
            }
            // One chunk map per layer, indexed by height. Empty maps are kept
            // rather than skipped, because the index is what says which layer
            // a map belongs to — and a layer nothing was ever built in has no
            // storage at all (ensureLayer), which is the same empty map.
            List<Object> perLayer = new ArrayList<>(layerCount());
            for (int layer = 0; layer < layerCount(); layer++) {
                ChunkedTiles sparse = chunks(layer);
                perLayer.add(sparse == null ? new LinkedHashMap<String, Object>()
                        : new LinkedHashMap<>(sparse.dirtyChunksRle()));
            }
            m.put("layerChunks", perLayer);
        } else {
            // Run-length encoded, row-major over the whole grid: pairs of
            // (tileId, runLength). Levels are mostly runs of air and terrain,
            // so this is dramatically smaller (and faster to write) than the
            // old row-of-arrays form — which matters both for saves and for
            // the multiplayer welcome message that carries the level as one
            // line.
            //
            // One run list per layer, bottom first. This used to be two named
            // keys, `tilesRle` and `upperRle`, from when a column was a floor
            // with at most one thing standing on it; a column of eight has no
            // name for its fifth layer, and a format that wrote "the floor,
            // the thing on the floor, and then the rest" would have made every
            // later reader ask why. LevelLoader still reads both old shapes
            // and the legacy row-of-arrays "tiles" — writing is free, reading
            // is additive (HEIGHT_PLAN.md invariant 3).
            List<Object> perLayer = new ArrayList<>(layerCount());
            for (int layer = 0; layer < layerCount(); layer++) {
                perLayer.add(rleOf(grid(layer)));
            }
            m.put("layersRle", perLayer);
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
