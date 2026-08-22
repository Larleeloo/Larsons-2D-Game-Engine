package com.larsons.engine.world.gen;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * The world as levels of detail: a cached, greedy-meshed stand-in for terrain
 * too far away to draw a block at a time.
 *
 * <p><b>What this is for.</b> A render distance of ninety chunks is fourteen
 * hundred blocks, and the disc inside that holds six and a half million
 * columns. Nothing draws that a block at a time — not this engine, not
 * Minecraft, not anything — so what every renderer with a real view distance
 * does instead is draw the far field <em>coarsely</em>, and the whole question
 * is how coarsely and how cheaply. This answers both the way
 * <a href="https://modrinth.com/mod/distanthorizons">Distant Horizons</a> does:
 *
 * <ul>
 *   <li><b>A quadtree of tiles.</b> A tile is {@value #SAMPLES}&sup2; samples of
 *       the surface whatever level it is at; level 0 samples every
 *       {@value #BASE_STEP} cells and each level up doubles that, so one tile
 *       covers 128 cells at level 0 and 4096 at level 5. A frame picks the
 *       level whose samples subtend about the same angle wherever they are —
 *       which is what makes the far field cost the same at four thousand blocks
 *       as at four hundred.</li>
 *   <li><b>Built once, not per frame.</b> The old coarse pass re-sampled the
 *       whole horizon every frame: at two thousand blocks that was measurably
 *       most of what the horizon cost. A tile here is built on a worker thread,
 *       kept, and re-used until something edits the ground under it — the same
 *       bargain Minecraft's chunk meshes make, and for the same reason.</li>
 *   <li><b>Greedy-meshed.</b> The samples are merged into the largest
 *       rectangles that share a height and a block before anything is drawn, so
 *       a plain is a handful of quads rather than a thousand. This is the
 *       standard voxel-mesher's trick and it is worth most on exactly the
 *       terrain a long view distance is <em>for</em>: flat ground, a lake, a
 *       plateau, a desert.</li>
 * </ul>
 *
 * <p><b>Heights come from the generator, not from chunks.</b>
 * {@link WorldGenerator#surfaceHeight} is a handful of noise samples and no
 * storage, so a tile four thousand blocks away costs a thousand noise
 * evaluations on a background thread rather than sixteen million generated
 * columns. That is the same argument the coarse horizon already made; what is
 * new is that the answer is kept.
 */
public final class WorldLod {

    /** Samples per side of one tile, at every level. */
    public static final int SAMPLES = 32;

    /** Cells per sample at level 0 — so a level-0 tile covers 128 cells. */
    public static final int BASE_STEP = 4;

    /** How many levels there are; level 5 tiles are 4096 cells across. */
    public static final int MAX_LEVEL = 5;

    /** Numbers per merged box: {@code c0, r0, c1, r1, topLayer, blockId}. */
    public static final int BOX_STRIDE = 6;

    /** Tiles kept before the furthest are dropped, over all levels. */
    private static final int MAX_TILES = 1536;

    /** A tile whose ground turned out to be nothing at all. */
    private static final int[] EMPTY = new int[0];

    private final WorldGenerator generator;
    private final int worldWidth, worldHeight;
    private final java.util.function.Supplier<ExecutorService> pool;

    /** Meshed tiles by {@link #key}, and the keys currently being meshed. */
    private final Map<Long, int[]> tiles = new ConcurrentHashMap<>();
    private final java.util.Set<Long> pending = ConcurrentHashMap.newKeySet();

    /** Where the player is, in level-0 tiles — what {@link #trim} keeps around. */
    private volatile int centreX, centreY;

    /**
     * Bumped when the ground changes, so tiles built before it are stale. A
     * generation counter rather than a per-tile invalidation because an edit is
     * rare and cheap to over-react to, and because the alternative — working
     * out which of six levels of tile covers a cell — is arithmetic that can be
     * wrong in a way nobody would notice for hours.
     */
    private volatile int generation;

    /** The generation each tile was built in, so a stale one is rebuilt. */
    private final Map<Long, Integer> builtIn = new ConcurrentHashMap<>();

    WorldLod(WorldGenerator generator, int worldWidth, int worldHeight,
             java.util.function.Supplier<ExecutorService> pool) {
        this.generator = generator;
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.pool = pool;
    }

    /** Cells per side of one tile at this level. */
    public static int tileCells(int level) {
        return SAMPLES * (BASE_STEP << level);
    }

    /** Cells per sample at this level — how coarse the ground is drawn. */
    public static int stepCells(int level) {
        return BASE_STEP << level;
    }

    /**
     * The level whose samples subtend about {@code angle} at {@code distance},
     * clamped to the levels that exist.
     *
     * <p>This is the whole of the level-of-detail policy, and it is one line
     * because it is one idea: a sample should be about the same size <em>on
     * screen</em> wherever it is, so its size in the world is proportional to
     * how far away it is. Everything else — that each ring costs the same, that
     * pushing the horizon out does not multiply the work — follows from it.
     *
     * @param distance how far away the ground is, in world units
     * @param tileSize the level's tile size in world units
     * @param angle    how wide a sample should be, as a fraction of its distance
     */
    public static int levelFor(double distance, int tileSize, double angle) {
        double wantCells = Math.max(BASE_STEP, distance * angle / Math.max(1, tileSize));
        int level = 0;
        while (level < MAX_LEVEL && stepCells(level) < wantCells) level++;
        return level;
    }

    /** How many level-0 tiles across the world is, for the tile grid. */
    public int tilesAcross(int level) {
        return Math.max(1, (worldWidth + tileCells(level) - 1) / tileCells(level));
    }

    /**
     * The meshed boxes of one tile, or {@code null} while it is being built.
     *
     * <p>Never builds on the calling thread. A frame that asks for a tile it
     * has not got draws nothing there and asks again next frame, which at the
     * distances this is used at is a corner of the horizon filling in over a
     * few frames — against a stall on the thread drawing the frame, which is
     * the trade the whole renderer is built on.
     */
    public int[] tile(int level, int tx, int ty) {
        if (level < 0 || level > MAX_LEVEL) return null;
        int across = tilesAcross(level);
        if (tx < 0 || ty < 0 || tx >= across || ty >= across) return null;
        long k = key(level, tx, ty);
        int[] mesh = tiles.get(k);
        int now = generation;
        if (mesh != null) {
            Integer built = builtIn.get(k);
            if (built != null && built == now) return mesh;
        }
        if (pending.add(k)) {
            ExecutorService workers = pool.get();
            if (workers == null) {
                pending.remove(k);
                return mesh;
            }
            workers.execute(() -> {
                try {
                    int[] built = mesh(level, tx, ty);
                    tiles.put(k, built);
                    builtIn.put(k, now);
                    if (tiles.size() > MAX_TILES) trim();
                } catch (RuntimeException e) {
                    System.err.println("WorldLod: tile " + level + ":" + tx + "," + ty
                            + " failed: " + e);
                } finally {
                    pending.remove(k);
                }
            });
        }
        // The stale mesh rather than nothing while a rebuild is in flight: an
        // edit moves one block and the tile is a thousand samples of landscape,
        // so last generation's answer is a better picture than a hole.
        return mesh;
    }

    /** Remember where the player is, so {@link #trim} drops the far tiles. */
    public void centreOn(int col, int row) {
        centreX = col;
        centreY = row;
    }

    /** The ground changed: every tile is suspect. See {@link #generation}. */
    public void invalidate() {
        generation++;
    }

    /** Tiles resident right now (diagnostics). */
    public int tileCount() { return tiles.size(); }

    /** Drop everything — the world is closing, or being rebuilt from a new seed. */
    public void clear() {
        tiles.clear();
        builtIn.clear();
    }

    // --- meshing ---------------------------------------------------------------

    /**
     * Sample one tile's ground and merge it into boxes.
     *
     * <p><b>The merge is the point.</b> A tile is {@value #SAMPLES}&sup2; =
     * 1024 samples; drawn one box each that is a thousand quads for a patch of
     * hillside, and most of them are the same height as the one beside them.
     * Greedy meshing walks the grid and takes, from each cell it has not used,
     * the widest run of equal ground and then the tallest stack of equal runs —
     * so a plain becomes one box, a terrace becomes one box per step, and only
     * genuinely broken ground pays per sample.
     */
    private int[] mesh(int level, int tx, int ty) {
        int step = stepCells(level);
        int side = tileCells(level);
        int c0 = tx * side, r0 = ty * side;
        int[] height = new int[SAMPLES * SAMPLES];
        int[] block = new int[SAMPLES * SAMPLES];
        boolean any = false;
        for (int y = 0; y < SAMPLES; y++) {
            int row = r0 + y * step;
            if (row >= worldHeight) break;
            for (int x = 0; x < SAMPLES; x++) {
                int col = c0 + x * step;
                if (col >= worldWidth) continue;
                int at = y * SAMPLES + x;
                height[at] = Math.max(0, generator.surfaceHeight(col, row));
                block[at] = generator.surfaceBlockId(col, row);
                if (block[at] > 0) any = true;
            }
        }
        if (!any) return EMPTY;

        boolean[] used = new boolean[SAMPLES * SAMPLES];
        // Sized for the worst case — every sample its own box — and trimmed on
        // the way out. A tile is meshed once, so the allocation is nothing; a
        // grown array per merge would be the thing that showed up.
        int[] out = new int[SAMPLES * SAMPLES * BOX_STRIDE];
        int n = 0;
        for (int y = 0; y < SAMPLES; y++) {
            for (int x = 0; x < SAMPLES; x++) {
                int at = y * SAMPLES + x;
                if (used[at] || block[at] <= 0) continue;
                int h = height[at], id = block[at];
                // The widest run of the same ground along this row…
                int w = 1;
                while (x + w < SAMPLES) {
                    int next = at + w;
                    if (used[next] || block[next] != id || height[next] != h) break;
                    w++;
                }
                // …and then the tallest stack of rows that match it all the way.
                int d = 1;
                grow:
                while (y + d < SAMPLES) {
                    int rowAt = (y + d) * SAMPLES + x;
                    for (int i = 0; i < w; i++) {
                        if (used[rowAt + i] || block[rowAt + i] != id
                                || height[rowAt + i] != h) {
                            break grow;
                        }
                    }
                    d++;
                }
                for (int j = 0; j < d; j++) {
                    java.util.Arrays.fill(used, (y + j) * SAMPLES + x,
                            (y + j) * SAMPLES + x + w, true);
                }
                out[n] = c0 + x * step;
                out[n + 1] = r0 + y * step;
                out[n + 2] = Math.min(worldWidth, c0 + (x + w) * step);
                out[n + 3] = Math.min(worldHeight, r0 + (y + d) * step);
                out[n + 4] = h;
                out[n + 5] = id;
                n += BOX_STRIDE;
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Drop the tiles furthest from the player once the budget is passed. */
    private void trim() {
        if (tiles.size() <= MAX_TILES) return;
        int cx = centreX, cy = centreY;
        List<Long> keys = new java.util.ArrayList<>(tiles.keySet());
        keys.sort(Comparator.comparingLong(k -> -distanceSq(k, cx, cy)));
        int drop = Math.min(keys.size(), tiles.size() - MAX_TILES);
        for (int i = 0; i < drop; i++) {
            tiles.remove(keys.get(i));
            builtIn.remove(keys.get(i));
        }
    }

    private static long distanceSq(long k, int cx, int cy) {
        int level = (int) (k >>> 58);
        int side = tileCells(level);
        long x = ((k >>> 29) & 0x1FFFFFFF) * (long) side + side / 2 - cx;
        long y = (k & 0x1FFFFFFF) * (long) side + side / 2 - cy;
        return x * x + y * y;
    }

    /** {@code level}, {@code tx} and {@code ty} in one long; see {@link #distanceSq}. */
    private static long key(int level, int tx, int ty) {
        return ((long) level << 58) | ((long) (tx & 0x1FFFFFFF) << 29) | (ty & 0x1FFFFFFF);
    }
}
