package com.larsons.engine.world.gen;

import com.larsons.engine.world.BlockRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The storage a procedurally generated world lives in: run-encoded columns,
 * held in chunks, generated on background threads as the player approaches
 * them and dropped again behind them.
 *
 * <p><b>Columns, run-encoded.</b> A column is {@code int[]} of
 * {@code (endExclusive, blockId)} pairs covering layer 0 upward, with the sky
 * trimmed off the top — so a mountain three hundred blocks tall is eight pairs
 * and a patch of ocean floor is four. That is what makes a 512-layer world
 * possible: storing the same world as one grid per layer would be three
 * hundred megabytes for what fits in twelve, and answering "how tall is the
 * ground here" would mean reading three hundred cells instead of looking at the
 * end of an array.
 *
 * <p><b>Chunks, streamed.</b> Columns are grouped into {@value #CHUNK}&sup2;
 * chunks. A chunk materializes the first time something reads inside it, and
 * {@link #prefetch} keeps a ring of them being built on worker threads well
 * ahead of where the player is walking — which is the difference between a
 * world that appears as you reach it and a world that stops for a fifth of a
 * second every time you cross a chunk line. Chunks past the budget are
 * dropped, furthest first, and rebuild identically from the seed when
 * revisited.
 *
 * <p><b>What is saved.</b> Chunks the player has <em>changed</em> are written
 * into the level file. Chunks they have only walked through are not — they are
 * a function of the seed, so writing them down would store a megabyte to say
 * what six bytes already said. What <em>is</em> written down is which chunks
 * have been visited, and that list is what makes turning generation off
 * meaningful: a frozen world generates the ground that has been explored and
 * nothing beyond it, so the level keeps the valley you walked through and does
 * not keep growing a continent you never saw. See {@link #freeze()}.
 */
public final class WorldTerrain {

    /** Columns per chunk side. */
    public static final int CHUNK = 32;

    /** The column of a cell nothing has ever been put in. */
    public static final int[] EMPTY_COLUMN = new int[0];

    /** How many visited-chunk keys are remembered before the list stops growing. */
    private static final int MAX_EXPLORED = 262144;

    /** Cells per side of one coarse-height tile — see {@link #groupTop}. */
    private static final int COARSE_TILE = 256;

    /** Cells per side of one coarse-height sample. */
    private static final int COARSE_STEP = 4;

    /** Most samples a side of one coarse box is measured from; see {@link #groupTop}. */
    private static final int SAMPLES_PER_GROUP = 8;

    /** Coarse tiles held before the oldest are dropped (~4 MB). */
    private static final int MAX_COARSE_TILES = 256;

    private final TerrainSettings settings;
    private final BlockRegistry blocks;
    private final WorldGenerator generator;
    private final long seed;
    private final int worldWidth, worldHeight;

    /** The authored level's own rectangle, which the generator leaves alone. */
    private final int authoredCol, authoredRow, authoredWidth, authoredHeight;

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> explored = ConcurrentHashMap.newKeySet();
    private final Map<Long, int[]> coarse = new ConcurrentHashMap<>();
    private final Set<Long> coarsePending = ConcurrentHashMap.newKeySet();

    private ExecutorService workers;
    private volatile boolean frozen;
    private volatile long centreKey = Long.MIN_VALUE;

    /** One chunk: {@value #CHUNK}&sup2; run-encoded columns. */
    private static final class Chunk {
        final int[][] columns = new int[CHUNK * CHUNK][];
        /**
         * Each column's visible faces, worked out on first use and kept until
         * something writes near it — see {@link #columnFaces}.
         */
        final int[][] faces = new int[CHUNK * CHUNK][];
        /** Edited (or explicitly kept), so it is written into the save file. */
        volatile boolean dirty;
        volatile int cx, cy;
    }

    public WorldTerrain(TerrainSettings settings, BlockRegistry blocks,
                        int worldWidth, int worldHeight,
                        int authoredCol, int authoredRow,
                        int authoredWidth, int authoredHeight, long seed) {
        this.settings = settings;
        this.blocks = blocks != null ? blocks : BlockRegistry.standard();
        this.seed = seed;
        this.worldWidth = Math.max(CHUNK, worldWidth);
        this.worldHeight = Math.max(CHUNK, worldHeight);
        this.authoredCol = authoredCol;
        this.authoredRow = authoredRow;
        this.authoredWidth = Math.max(0, authoredWidth);
        this.authoredHeight = Math.max(0, authoredHeight);
        this.generator = new WorldGenerator(settings, this.blocks, seed);
        this.lod = new WorldLod(this.generator, this.worldWidth, this.worldHeight, this::pool);
        TerrainSettings recipe = settings.copy();
        recipe.normalize();
        recipe.seed = seed;
        this.signature = recipe.generationSignature();
    }

    public long seed() { return seed; }

    public TerrainSettings settings() { return settings; }

    public WorldGenerator generator() { return generator; }

    /**
     * The cached level-of-detail terrain this world's far field is drawn from.
     * See {@link WorldLod}.
     */
    public WorldLod lod() { return lod; }

    private final WorldLod lod;

    public int worldWidth() { return worldWidth; }

    public int worldHeight() { return worldHeight; }

    /** The authored level's rectangle: {@code {col, row, width, height}}. */
    public int[] authoredBounds() {
        return new int[]{authoredCol, authoredRow, authoredWidth, authoredHeight};
    }

    /** Whether new ground is still being generated (see {@link #freeze()}). */
    public boolean generating() { return !frozen; }

    /** Chunks resident right now (HUD/diagnostics). */
    public int loadedChunks() { return chunks.size(); }

    /** Chunks the player has ever been near — what a frozen world keeps. */
    public int exploredChunks() { return explored.size(); }

    /** Chunks carrying edits, which are the ones written into the save file. */
    public int editedChunks() {
        int n = 0;
        for (Chunk c : chunks.values()) {
            if (c.dirty) n++;
        }
        return n;
    }

    /**
     * Stop generating new ground, keeping everything already explored.
     *
     * <p>What the world <em>is</em> stops changing here: the chunks that have
     * been visited go on loading (they are still a function of the seed, so
     * they come back identical), and a chunk nobody has ever been to is now
     * empty rather than a continent waiting to happen. That is what "only the
     * areas that have been explored are saved" means — the explored list is the
     * world's new boundary.
     */
    public void freeze() {
        frozen = true;
    }

    /** Resume generating, which is what turning the setting back on does. */
    public void thaw() {
        frozen = false;
    }

    // --- reads -----------------------------------------------------------------

    /** The block at a cell, or 0 for sky, for a hole, and for outside the world. */
    public int blockAt(int col, int row, int layer) {
        if (layer < 0 || col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) {
            return 0;
        }
        int[] column = column(col, row);
        return at(column, layer);
    }

    /** One layer of a run-encoded column. */
    public static int at(int[] runs, int layer) {
        if (runs == null || layer < 0) return 0;
        for (int i = 0; i < runs.length; i += 2) {
            if (layer < runs[i]) return runs[i + 1];
        }
        return 0;
    }

    /** One past the highest filled layer of a column — its {@code columnDepth}. */
    public int columnDepth(int col, int row) {
        int[] runs = column(col, row);
        return runs.length == 0 ? 0 : runs[runs.length - 2];
    }

    /**
     * The layers of a column that could possibly be seen, written into
     * {@code out}; returns how many.
     *
     * <p><b>This is what makes a 512-layer world drawable.</b> The renderer's
     * column sweep used to walk every layer from the floor to the top, which on
     * ground a hundred and fifty blocks deep is a hundred and fifty reads per
     * visible cell to discover that all of them are buried. A cell draws no
     * face at all when every one of its six neighbours is filled, so the
     * question is not "how tall is this column" but "where does it or anything
     * beside it have a gap" — and a run-encoded column answers that by looking
     * at the ends of runs rather than at cells. The middle of a mountain is one
     * step; a cave mouth or a cliff is every layer, which is exactly where the
     * work belongs.
     *
     * <p><b>Hidden rather than merely <em>filled</em>, and that distinction is
     * the ground under a flower.</b> This used to skip a layer whose six
     * neighbours were all non-empty, which is not the same question the painter
     * asks: it draws a face against anything that does not {@linkplain
     * com.larsons.engine.world.Block#covers() cover} — a flower, a pane of
     * glass, the surface of a lake — because you can plainly see the block
     * behind one. A column of grass with a flower growing on it therefore had
     * its topmost grass block counted as buried, so the ground the flower was
     * standing on was never drawn and the player looked straight through the
     * world wherever anything was growing. A wall behind glass was the same
     * fault with a different decoration.
     *
     * <p>What still counts as hidden, so a lake does not cost a face per layer
     * of its depth, is a neighbour of the <em>same block</em>: water against
     * water has no surface between it, whatever its alpha says. The painter
     * agrees ({@code SolidPainter.open}), which is what keeps the two passes
     * from disagreeing about which faces exist.
     *
     * <p><b>It walks runs rather than layers, and it walks each of them once.</b>
     * Every question here is asked of the run encoding — where does the cell
     * above this one change, how far up does the column beside it go on being
     * rock — so the loop keeps a cursor into each of the five columns and
     * advances it, instead of scanning each array from its start for every
     * layer it looks at. That is not a micro-optimisation: this method is the
     * inner loop of the whole renderer (it is asked of every cell of the sweep,
     * a hundred thousand of them at a long render distance) and the scans made
     * it cost two microseconds a cell, which was most of the frame.
     */
    public int visibleLayers(int col, int row, int depth, int[] out) {
        int[] faces = columnFaces(col, row);
        int n = 0;
        for (int packed : faces) {
            int layer = (packed >>> FACE_BITS) & LAYER_MASK;
            if (layer >= depth || n >= out.length) break;
            out[n++] = layer;
        }
        return n;
    }

    // --- the column mesh -------------------------------------------------------

    /**
     * How a face entry is packed: the mask in the low {@value #FACE_BITS} bits,
     * the layer in the {@value #LAYER_BITS} above it, and the block id above
     * that — thirty-one bits in all, so a column's whole mesh is one
     * {@code int[]} and reading a face costs no lookup at all.
     */
    public static final int FACE_BITS = 6;
    /** Bits of a packed face entry that hold the layer; see {@link #FACE_BITS}. */
    public static final int LAYER_BITS = 9;
    /** Mask for the layer once it has been shifted down. */
    public static final int LAYER_MASK = (1 << LAYER_BITS) - 1;

    /** The face toward the sky. */
    public static final int FACE_UP = 1;
    /** The face toward the floor. */
    public static final int FACE_DOWN = 2;
    /** The face toward row&nbsp;&minus;&nbsp;1. */
    public static final int FACE_NORTH = 4;
    /** The face toward row&nbsp;+&nbsp;1. */
    public static final int FACE_SOUTH = 8;
    /** The face toward col&nbsp;+&nbsp;1. */
    public static final int FACE_EAST = 16;
    /** The face toward col&nbsp;&minus;&nbsp;1. */
    public static final int FACE_WEST = 32;

    /**
     * Every face of one column that could be seen, as
     * {@code (layer << FACE_BITS) | mask}, ascending — <b>worked out once and
     * kept</b>.
     *
     * <p><b>This is the chunk mesh, in the shape this engine's storage takes.</b>
     * Minecraft does not decide what a chunk looks like while it is drawing a
     * frame; it builds the geometry when the chunk changes and then spends
     * every frame until the next change simply drawing it. That is the single
     * biggest thing a voxel renderer can do, and the reason is arithmetic: the
     * question "is this face exposed" is answered from five columns of
     * neighbours and cannot change unless one of them does, while the frame
     * asks it again for every cell of the view distance, sixty or a hundred and
     * twenty times a second.
     *
     * <p>So the answer is computed on the first ask and kept on the chunk, and
     * a frame reads an array. What that removes from the sweep is all of
     * {@code visibleLayers}, all six neighbour lookups per block, and the run
     * scans under them — measured at about two fifths of the whole detailed
     * pass.
     *
     * <p><b>What invalidates it</b> is any write to this column or the four
     * beside it, which is exactly what {@link #set} clears. Nothing else can
     * change the answer: exposure is a fact about six cells.
     *
     * <p>The mask is deliberately <em>not</em> view-dependent. Which faces
     * point at the eye is a comparison the painter makes per frame and cannot
     * be cached; which faces exist at all is a property of the world.
     */
    public int[] columnFaces(int col, int row) {
        if (col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) return NO_FACES;
        Chunk chunk = chunk(col >> 5, row >> 5, true);
        if (chunk == null) return NO_FACES;
        int index = (row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1));
        int[] cached = chunk.faces[index];
        if (cached != null) return cached;
        int[] built = buildColumnFaces(col, row);
        chunk.faces[index] = built;
        return built;
    }

    /** A column with nothing in it to draw. */
    private static final int[] NO_FACES = new int[0];

    /**
     * Work out {@link #columnFaces} for one column.
     *
     * <p>The walk is the one {@code visibleLayers} used to do — runs rather than
     * layers, with a cursor into each of the five columns so none of them is
     * scanned twice — with one difference: where that stopped at the first
     * neighbour which left the layer exposed, this asks all six, because the
     * answer it is storing is <em>which</em> faces rather than <em>whether</em>
     * any. That costs a little more per column and it is paid once.
     */
    private int[] buildColumnFaces(int col, int row) {
        int[] self = column(col, row);
        if (self.length == 0) return NO_FACES;
        int limit = self[self.length - 2];
        if (limit <= 1) return NO_FACES;
        boolean[] covers = blocks.coversTable();
        int[] west = column(col - 1, row);
        int[] east = column(col + 1, row);
        int[] north = column(col, row - 1);
        int[] south = column(col, row + 1);
        int[] out = new int[16];
        int n = 0;
        int y = 1;
        int ci = 0, wi = 0, ei = 0, ni = 0, si = 0;
        while (y < limit) {
            while (ci < self.length && y >= self[ci]) ci += 2;
            if (ci >= self.length) break;          // open sky above the column
            int id = self[ci + 1];
            if (id == 0) {
                // Empty: nothing to draw in the gap. Skipping past it does not
                // hide the underside of whatever sits on top, because that
                // block finds an empty cell below it and is drawn for it.
                y = self[ci];
                continue;
            }
            // A block that covers is hidden by anything else that covers; one
            // you can see past is hidden only by its own kind, and by cover.
            int like = hides(id, 0, covers) ? 0 : id;
            int entryEnd = self[ci];
            int under = ci == 0 ? 0 : self[ci - 2];
            int below = y - 1 >= under ? id : (ci >= 2 ? self[ci - 1] : 0);
            int above = y + 1 < entryEnd ? id : (ci + 2 < self.length ? self[ci + 3] : 0);

            int mask = 0;
            if (!hides(above, like, covers)) mask |= FACE_UP;
            if (!hides(below, like, covers)) mask |= FACE_DOWN;

            // Never past the run this layer's own block occupies: what follows
            // is an argument about a cell of this id, and the cell above may be
            // something else that its neighbours do not hide.
            int stop = entryEnd - 1;
            long side = hidesFrom(west, y, like, covers, wi);
            wi = (int) (side >>> 32);
            if ((int) side < 0) mask |= FACE_WEST; else stop = Math.min(stop, (int) side);
            side = hidesFrom(east, y, like, covers, ei);
            ei = (int) (side >>> 32);
            if ((int) side < 0) mask |= FACE_EAST; else stop = Math.min(stop, (int) side);
            side = hidesFrom(north, y, like, covers, ni);
            ni = (int) (side >>> 32);
            if ((int) side < 0) mask |= FACE_NORTH; else stop = Math.min(stop, (int) side);
            side = hidesFrom(south, y, like, covers, si);
            si = (int) (side >>> 32);
            if ((int) side < 0) mask |= FACE_SOUTH; else stop = Math.min(stop, (int) side);

            if (mask != 0) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                out[n++] = (id << (FACE_BITS + LAYER_BITS)) | (y << FACE_BITS) | mask;
                y++;
                continue;
            }
            // Walled in on all six sides, from here to wherever the first of
            // those walls runs out.
            y = Math.max(y + 1, stop);
        }
        return n == 0 ? NO_FACES : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Forget the cached faces of a column and of the four beside it — the four
     * because a face is a fact about two cells, so a block put down here is a
     * face that stopped existing next door.
     */
    private void forgetFaces(int col, int row) {
        forgetFace(col, row);
        forgetFace(col - 1, row);
        forgetFace(col + 1, row);
        forgetFace(col, row - 1);
        forgetFace(col, row + 1);
    }

    private void forgetFace(int col, int row) {
        if (col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) return;
        Chunk chunk = chunk(col >> 5, row >> 5, false);
        if (chunk == null) return;
        chunk.faces[(row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1))] = null;
    }

    /**
     * Whether a cell of block {@code id} hides the face of a neighbouring cell
     * of block {@code like}.
     *
     * <p>Cover does it — an opaque, whole block. So does being the same block:
     * water against water has no surface between it however see-through it is,
     * and drawing one would lay the same translucent blue down once per layer
     * of the lake's depth. Pass {@code like} as {@code 0} to ask only about
     * cover. An id past the end of the table is one the registry does not know,
     * and an unknown id covers — see {@code BlockRegistry.covers}.
     */
    private static boolean hides(int id, int like, boolean[] covers) {
        if (id == 0) return false;
        if (id == like) return true;
        return id >= covers.length || covers[id];
    }

    /**
     * How far a neighbouring column goes on hiding a column of {@code like}
     * from layer {@code y} upward, as
     * {@code (scanPosition << 32) | endExclusive} — with an end of {@code -1}
     * when it does not hide {@code y} at all.
     *
     * <p>The scan position comes back so the caller can hand it in again: the
     * layers a column asks about only ever increase, so each neighbour's runs
     * are walked once for the whole column rather than once per layer.
     *
     * @param from where to resume scanning; must name an entry starting at or
     *             below {@code y}, which the returned position always does
     */
    private static long hidesFrom(int[] runs, int y, int like, boolean[] covers, int from) {
        int i = from;
        while (i < runs.length && y >= runs[i]) i += 2;
        if (i >= runs.length) return ((long) i << 32) | 0xFFFFFFFFL;  // open sky
        if (!hides(runs[i + 1], like, covers)) return ((long) i << 32) | 0xFFFFFFFFL;
        int end = runs[i];
        for (int j = i + 2; j < runs.length; j += 2) {
            if (!hides(runs[j + 1], like, covers)) break;
            end = runs[j];
        }
        return ((long) i << 32) | (end & 0xFFFFFFFFL);
    }

    /**
     * How many cells stand in an unbroken run on this column's floor — the
     * height a line-of-sight cull may treat the column as solid to. Zero when
     * layer 1 is empty, so a column with a hole at its foot occludes nothing.
     *
     * <p>Free here, which is the point of asking the terrain rather than the
     * level: a run-encoded column already knows where its first gap is, and the
     * alternative is a hundred and fifty reads per cell per frame.
     */
    public int groundedDepth(int col, int row) {
        long span = filledSpan(column(col, row), 1);
        if (span < 0) return 0;
        if ((int) (span >>> 32) > 1) return 0;   // the run does not reach the floor
        return Math.max(0, (int) span - 1);
    }

    /**
     * The unbroken run of filled cells containing a layer, as
     * {@code (start << 32) | endExclusive}, or {@code -1} when the cell is
     * empty. Runs of different blocks that touch are one span: what matters
     * here is where the gaps are, not where the material changes.
     */
    private static long filledSpan(int[] runs, int y) {
        if (runs == null || y < 0) return -1;
        int pos = 0;
        int spanStart = -1;
        for (int i = 0; i < runs.length; i += 2) {
            int end = runs[i];
            boolean filled = runs[i + 1] != 0;
            if (filled) {
                if (spanStart < 0) spanStart = pos;
            } else {
                spanStart = -1;
            }
            if (y < end) {
                if (!filled) return -1;
                int spanEnd = end;
                for (int j = i + 2; j < runs.length; j += 2) {
                    if (runs[j + 1] == 0) break;
                    spanEnd = runs[j];
                }
                return ((long) spanStart << 32) | (spanEnd & 0xFFFFFFFFL);
            }
            pos = end;
        }
        return -1; // above the top of the column: open sky
    }

    /** The run-encoded column at a cell, generating or loading it as needed. */
    public int[] column(int col, int row) {
        if (col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) {
            return EMPTY_COLUMN;
        }
        Chunk chunk = chunk(col >> 5, row >> 5, true);
        if (chunk == null) return EMPTY_COLUMN;
        int[] runs = chunk.columns[(row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1))];
        return runs != null ? runs : EMPTY_COLUMN;
    }

    // --- writes ----------------------------------------------------------------

    /**
     * Put a block into the world. The column is re-encoded around the change
     * and its chunk is marked as carrying edits, which is what gets it written
     * into the save file.
     */
    public boolean set(int col, int row, int layer, int id) {
        if (layer < 0 || layer >= TerrainSettings.MAX_LEVEL) return false;
        if (col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) return false;
        Chunk chunk = chunk(col >> 5, row >> 5, true);
        if (chunk == null) return false;
        int index = (row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1));
        int[] runs = chunk.columns[index];
        if (runs == null) runs = EMPTY_COLUMN;
        if (at(runs, layer) == id) return false;
        chunk.columns[index] = withLayer(runs, layer, id);
        chunk.dirty = true;
        forgetFaces(col, row);
        // The far field is a picture of the ground, so changing the ground
        // makes it a picture of what used to be there.
        lod.invalidate();
        return true;
    }

    /**
     * A column with one layer replaced, re-encoded.
     *
     * <p>Expanded to cells and recompressed rather than spliced, because the
     * splice has six cases (inside a run, at either end of one, merging with a
     * neighbour, extending the top, and trimming it) and this has none. A block
     * placement is a keystroke, not a frame budget.
     */
    static int[] withLayer(int[] runs, int layer, int id) {
        int top = runs.length == 0 ? 0 : runs[runs.length - 2];
        int size = Math.max(top, layer + 1);
        int[] cells = new int[size];
        int at = 0;
        for (int i = 0; i < runs.length; i += 2) {
            int end = runs[i];
            for (; at < end && at < size; at++) cells[at] = runs[i + 1];
        }
        cells[layer] = id;
        while (size > 0 && cells[size - 1] == 0) size--;
        if (size == 0) return EMPTY_COLUMN;
        int count = 1;
        for (int i = 1; i < size; i++) {
            if (cells[i] != cells[i - 1]) count++;
        }
        int[] out = new int[count * 2];
        int w = 0;
        for (int i = 1; i <= size; i++) {
            if (i == size || cells[i] != cells[i - 1]) {
                out[w++] = i;
                out[w++] = cells[i - 1];
            }
        }
        return out;
    }

    /** Install a whole column, marking its chunk as carrying edits (level import). */
    public void putColumn(int col, int row, int[] runs) {
        if (col < 0 || row < 0 || col >= worldWidth || row >= worldHeight) return;
        Chunk chunk = chunk(col >> 5, row >> 5, true);
        if (chunk == null) return;
        chunk.columns[(row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1))] =
                runs != null ? runs : EMPTY_COLUMN;
        chunk.dirty = true;
    }

    // --- chunk loading ---------------------------------------------------------

    public static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }

    private static int keyX(long k) { return (int) (k >> 32); }

    private static int keyY(long k) { return (int) (long) k; }

    /**
     * The chunk at chunk coordinates, built on the spot if it is missing and
     * {@code materialize} is set.
     *
     * <p>Synchronous generation here is the fallback, not the plan:
     * {@link #prefetch} normally has the chunk built seconds before anything
     * reads it. It stays synchronous rather than returning empty because the
     * alternative is a player falling through ground that had not finished
     * arriving.
     */
    private Chunk chunk(int cx, int cy, boolean materialize) {
        long k = key(cx, cy);
        // A one-entry memo in front of the map, because the two readers that
        // matter are both spatially coherent: a sweep walks a chunk's worth of
        // cells before it moves on, and each of those cells asks for its own
        // column and its four neighbours' — nine lookups a cell, a million a
        // frame at a long render distance, all but a thirty-secondth of them
        // for the chunk the last one was in. Held as one object so a reader on
        // another thread sees a key and a chunk that belong together.
        Recent memo = recent;
        if (memo != null && memo.key == k) return memo.chunk;
        Chunk chunk = chunks.get(k);
        if (chunk == null) {
            if (!materialize || !buildOnRead) return null;
            if (frozen && !explored.contains(k)) return null;
            chunk = build(k, cx, cy);
            if (chunk == null) return null;
        }
        recent = new Recent(k, chunk);
        return chunk;
    }

    /** The chunk the last read landed in; see {@link #chunk}. */
    private record Recent(long key, Chunk chunk) {}

    private volatile Recent recent;

    /**
     * Drop the memo, because the chunk it names may no longer be the chunk at
     * that key — anything that removes or replaces an entry of {@link #chunks}
     * has to say so here. Adding one never does: the memo only ever holds a
     * chunk that was found, so a key that had none cannot be stale.
     */
    private void forgetRecent() {
        recent = null;
    }

    /**
     * Whether a read may generate the chunk it lands in, on the calling thread.
     *
     * <p>See {@link com.larsons.engine.level.Level#setBuildOnRead(boolean)} for
     * why a frame turns this off and the simulation leaves it on. Not per
     * thread: the renderer and the simulation are both the game loop, and the
     * worker pool goes through {@link #prefetch} rather than through a read.
     *
     * @return what it was, so a caller can put it back
     */
    public boolean setBuildOnRead(boolean build) {
        boolean was = buildOnRead;
        buildOnRead = build;
        return was;
    }

    private volatile boolean buildOnRead = true;

    /** Generate one chunk and install it. Safe to call from any thread. */
    private Chunk build(long k, int cx, int cy) {
        // The synchronous fallback: something is reading this chunk right now,
        // which is the strongest evidence there is that the player is in it.
        return build(k, cx, cy, true);
    }

    /**
     * {@link #build(long, int, int)}, saying whether this counts as the player
     * having been <em>in</em> the chunk rather than having had it drawn for
     * them — see {@link #prefetch(int, int, int, int)}.
     */
    private Chunk build(long k, int cx, int cy, boolean visited) {
        Chunk existing = chunks.get(k);
        if (existing != null) return existing;
        Chunk chunk = new Chunk();
        chunk.cx = cx;
        chunk.cy = cy;
        if (!insideAuthored(cx, cy)) {
            generator.generateChunk(cx, cy, chunk.columns);
        }
        Chunk raced = chunks.putIfAbsent(k, chunk);
        if (raced != null) return raced;
        // Explored is the world's boundary — what a frozen world still has —
        // so anything built at all belongs in it: ground the player has seen
        // from a hilltop should not vanish the moment generation stops.
        if (explored.size() < MAX_EXPLORED) explored.add(k);
        // Whether its *contents* are written down is a different question, and
        // it is the one distance answers. A chunk drawn at the horizon is a
        // chunk the player has not touched a block of, and a pristine chunk is
        // a function of the seed — so storing it is storing a megabyte to say
        // what six bytes already said.
        if (visited && settings.saveExplored) chunk.dirty = true;
        trim();
        return chunk;
    }

    /**
     * Whether a chunk lies inside the authored level, which the generator never
     * touches — that is what "infinite terrain starts at the bounds of a given
     * level" means in code. The authored rectangle is chunk-aligned when the
     * level is expanded, so a chunk is either wholly inside it or wholly out.
     */
    private boolean insideAuthored(int cx, int cy) {
        if (authoredWidth <= 0 || authoredHeight <= 0) return false;
        int c = cx * CHUNK, r = cy * CHUNK;
        return c >= authoredCol && r >= authoredRow
                && c + CHUNK <= authoredCol + authoredWidth
                && r + CHUNK <= authoredRow + authoredHeight;
    }

    /**
     * Keep the chunks around a point built and ready.
     *
     * <p>Called once a frame with wherever the camera is. Everything inside
     * {@code radius} chunks that is not already resident is queued on the
     * worker pool nearest-first, so the ground the player is walking toward is
     * built before the ground behind them.
     */
    public void prefetch(int col, int row, int radiusChunks) {
        prefetch(col, row, radiusChunks, radiusChunks);
    }

    /**
     * {@link #prefetch(int, int, int)} with the two radii told apart: how far
     * ground is <em>built</em>, and how far of it counts as having been
     * <em>visited</em>.
     *
     * <p><b>They were the same number and should never have been.</b> A chunk
     * gets built because something is about to draw it, which at a long render
     * distance is a disc six chunks across. With
     * {@link TerrainSettings#saveExplored} on, every one of those was written
     * into the save file in full — four thousand columns of ground the player
     * had seen at the horizon and not touched a block of. A pristine chunk is a
     * function of the seed, so that is a megabyte spent saying what the seed
     * already said.
     *
     * <p>What the far ring still does is join {@code explored}, because that is
     * the world's <em>boundary</em> rather than a record of interference:
     * ground seen from a hilltop should not vanish when generation stops.
     *
     * @param exploreChunks how near the player has to be for a chunk's contents
     *                      to be worth storing; further out they are built,
     *                      drawn, and left to the seed
     */
    public void prefetch(int col, int row, int radiusChunks, int exploreChunks) {
        if (frozen && explored.isEmpty()) return;
        int cx = Math.floorDiv(col, CHUNK), cy = Math.floorDiv(row, CHUNK);
        centreKey = key(cx, cy);
        lod.centreOn(col, row);
        if (radiusChunks <= 0) return;
        ExecutorService pool = pool();
        int queued = 0;
        for (int ring = 0; ring <= radiusChunks && queued < 64; ring++) {
            for (int dy = -ring; dy <= ring && queued < 64; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue;
                    int gx = cx + dx, gy = cy + dy;
                    if (gx < 0 || gy < 0) continue;
                    if (gx * CHUNK >= worldWidth || gy * CHUNK >= worldHeight) continue;
                    long k = key(gx, gy);
                    if (chunks.containsKey(k)) continue;
                    if (frozen && !explored.contains(k)) continue;
                    if (!pending.add(k)) continue;
                    final int fx = gx, fy = gy;
                    final boolean visited = ring <= exploreChunks;
                    pool.execute(() -> {
                        try {
                            build(k, fx, fy, visited);
                        } catch (RuntimeException e) {
                            System.err.println("WorldTerrain: chunk " + fx + "," + fy
                                    + " failed to generate: " + e);
                        } finally {
                            pending.remove(k);
                        }
                    });
                    if (++queued >= 64) break;
                }
            }
        }
    }

    private ExecutorService pool() {
        ExecutorService pool = workers;
        if (pool != null) return pool;
        synchronized (this) {
            if (workers == null) {
                AtomicInteger n = new AtomicInteger();
                ThreadFactory factory = r -> {
                    Thread t = new Thread(r, "terrain-gen-" + n.incrementAndGet());
                    t.setDaemon(true);
                    // Below the game loop on purpose: a chunk arriving a frame
                    // later is invisible, a dropped frame is not.
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                };
                // Threads that go away when there is nothing to build. A world
                // that is loaded, walked across and left behind would otherwise
                // hold its workers alive for the rest of the session, and a
                // player who opens six levels would be holding twelve.
                java.util.concurrent.ThreadPoolExecutor built =
                        new java.util.concurrent.ThreadPoolExecutor(
                                Math.max(1, settings.workerThreads),
                                Math.max(1, settings.workerThreads),
                                20, java.util.concurrent.TimeUnit.SECONDS,
                                new java.util.concurrent.LinkedBlockingQueue<>(), factory);
                built.allowCoreThreadTimeOut(true);
                workers = built;
            }
            return workers;
        }
    }

    /** Drop the furthest pristine chunks once the budget is passed. */
    private void trim() {
        int budget = Math.max(64, settings.loadedChunkBudget);
        if (chunks.size() <= budget) return;
        int centreX = keyX(centreKey), centreY = keyY(centreKey);
        List<Long> droppable = new ArrayList<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            if (!e.getValue().dirty) droppable.add(e.getKey());
        }
        if (droppable.isEmpty()) return;
        droppable.sort(Comparator.comparingLong(k ->
                -distanceSq(keyX(k), keyY(k), centreX, centreY)));
        int drop = Math.min(droppable.size(), chunks.size() - budget);
        for (int i = 0; i < drop; i++) chunks.remove(droppable.get(i));
        forgetRecent();
    }

    private static long distanceSq(int ax, int ay, int bx, int by) {
        long dx = ax - (long) bx, dy = ay - (long) by;
        return dx * dx + dy * dy;
    }

    /** Let the worker threads go — the level this world belongs to is closing. */
    public void close() {
        ExecutorService pool = workers;
        workers = null;
        lod.clear();
        if (pool != null) pool.shutdownNow();
    }

    // --- the coarse horizon ----------------------------------------------------

    /**
     * The tallest ground in a block of cells and what it is made of, for the
     * coarse pass that draws the horizon.
     *
     * <p>Answered from a cache of sampled heights rather than from the columns
     * themselves, because the horizon reaches two thousand blocks and there are
     * sixteen million columns inside that — generating them to find out how
     * tall they are would cost more than drawing the whole world in detail.
     * {@link WorldGenerator#surfaceHeight} is a handful of noise samples and no
     * storage at all, so the distance is affordable at the resolution the
     * distance is drawn at: one sample per {@value #COARSE_STEP} cells.
     *
     * @param out {@code {tallest layer, its block id, lowest layer}} — three
     *            numbers, the last of which is what a line-of-sight cull may
     *            treat the whole group as solid up to
     * @return whether an answer was available; a tile still being sampled says
     *         no, and the horizon fills in over the next few frames
     */
    public boolean groupTop(int c0, int r0, int c1, int r1, int[] out) {
        int best = -1, bestId = 0, worst = Integer.MAX_VALUE;
        boolean any = false;
        // At most a handful of samples per side, however wide the group is.
        // The outermost ring of the horizon draws boxes four hundred cells
        // across, and reading one sample per four cells of those is ten
        // thousand reads to decide the height of a box eight pixels tall —
        // which measured as most of what the whole horizon pass cost. The
        // answer a box wants is "how high is the ground around here", and
        // sixty-four samples say that as well as ten thousand do.
        int step = Math.max(COARSE_STEP,
                Math.max(c1 - c0, r1 - r0) / SAMPLES_PER_GROUP);
        for (int r = r0; r < r1; r += step) {
            for (int c = c0; c < c1; c += step) {
                int[] tile = coarseTile(Math.floorDiv(c, COARSE_TILE),
                        Math.floorDiv(r, COARSE_TILE));
                if (tile == null) continue;
                int lx = Math.floorMod(c, COARSE_TILE) / COARSE_STEP;
                int ly = Math.floorMod(r, COARSE_TILE) / COARSE_STEP;
                int packed = tile[ly * (COARSE_TILE / COARSE_STEP) + lx];
                int height = packed >>> 16;
                if (height > best) {
                    best = height;
                    bestId = packed & 0xFFFF;
                }
                if (height < worst) worst = height;
                any = true;
            }
        }
        if (!any || best < 0) return false;
        out[0] = best;
        out[1] = bestId;
        out[2] = Math.max(0, Math.min(best, worst));
        return true;
    }

    /** One tile of sampled surface heights, queued for sampling if it is missing. */
    private int[] coarseTile(int tx, int ty) {
        long k = key(tx, ty);
        int[] tile = coarse.get(k);
        if (tile != null) return tile;
        if (frozen && explored.isEmpty()) return null;
        if (coarsePending.add(k)) {
            pool().execute(() -> {
                try {
                    coarse.put(k, sampleCoarse(tx, ty));
                    if (coarse.size() > MAX_COARSE_TILES) trimCoarse();
                } catch (RuntimeException e) {
                    System.err.println("WorldTerrain: horizon tile " + tx + "," + ty
                            + " failed: " + e);
                } finally {
                    coarsePending.remove(k);
                }
            });
        }
        return null;
    }

    private int[] sampleCoarse(int tx, int ty) {
        int side = COARSE_TILE / COARSE_STEP;
        int[] tile = new int[side * side];
        int c0 = tx * COARSE_TILE, r0 = ty * COARSE_TILE;
        for (int ly = 0; ly < side; ly++) {
            int row = r0 + ly * COARSE_STEP + COARSE_STEP / 2;
            for (int lx = 0; lx < side; lx++) {
                int col = c0 + lx * COARSE_STEP + COARSE_STEP / 2;
                int h = Math.max(0, Math.min(0xFFFF, generator.surfaceHeight(col, row)));
                int id = generator.surfaceBlockId(col, row) & 0xFFFF;
                tile[ly * side + lx] = (h << 16) | id;
            }
        }
        return tile;
    }

    private void trimCoarse() {
        int centreX = keyX(centreKey) * CHUNK / COARSE_TILE;
        int centreY = keyY(centreKey) * CHUNK / COARSE_TILE;
        List<Long> keys = new ArrayList<>(coarse.keySet());
        keys.sort(Comparator.comparingLong(k ->
                -distanceSq(keyX(k), keyY(k), centreX, centreY)));
        for (int i = 0; i < keys.size() - MAX_COARSE_TILES; i++) coarse.remove(keys.get(i));
    }

    // --- regenerating ----------------------------------------------------------

    /** The recipe this world was built from — see {@link TerrainSettings#generationSignature}. */
    private final String signature;

    /** Whether this world is still the world {@code next} describes. */
    public boolean matches(TerrainSettings next) {
        if (next == null) return true;
        TerrainSettings normalized = next.copy();
        normalized.normalize();
        if (normalized.seed == 0) normalized.seed = seed;
        return signature.equals(normalized.generationSignature());
    }

    /**
     * This world rebuilt from a new recipe, keeping everything that was built
     * in it.
     *
     * <p><b>The ground grows back; the buildings do not move.</b> Retuning a
     * biome or typing a different seed changes what the untouched world is made
     * of, and there is no honest way to keep the old terrain and call it the
     * new settings. What <em>can</em> be kept is everything the creator put
     * there themselves: edited chunks are carried across cell for cell, and the
     * visited list with them, so a frozen world stays exactly as big as it was.
     *
     * <p>The caller installs the result ({@link com.larsons.engine.level.Level#setTerrain}),
     * which closes this one.
     */
    public WorldTerrain rebuiltWith(TerrainSettings next) {
        TerrainSettings settings = next.copy();
        settings.normalize();
        long nextSeed = settings.seed != 0 ? settings.seed : seed;
        settings.seed = nextSeed;
        WorldTerrain rebuilt = new WorldTerrain(settings, blocks,
                settings.worldSize == worldWidth ? worldWidth : settings.worldSize,
                settings.worldSize == worldHeight ? worldHeight : settings.worldSize,
                authoredCol, authoredRow, authoredWidth, authoredHeight, nextSeed);
        rebuilt.explored.addAll(explored);
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk chunk = e.getValue();
            if (!chunk.dirty) continue;
            Chunk copy = new Chunk();
            copy.cx = chunk.cx;
            copy.cy = chunk.cy;
            copy.dirty = true;
            for (int i = 0; i < copy.columns.length; i++) {
                int[] runs = chunk.columns[i];
                copy.columns[i] = runs == null ? null : runs.clone();
            }
            rebuilt.chunks.put(e.getKey(), copy);
        }
        rebuilt.frozen = frozen;
        return rebuilt;
    }

    // --- play-test snapshots ---------------------------------------------------

    /**
     * A copy of everything about this world that is not a function of its seed:
     * the chunks that carry edits, and which chunks have been visited.
     *
     * <p>That is the whole of it, and it is why a snapshot of an endless world
     * is small. Pristine ground does not need saving because it rebuilds
     * identically — so a play-test that mines a tunnel through a mountain is
     * undone by throwing the mountain away and letting it grow back.
     */
    public Snapshot snapshot() {
        Map<Long, int[][]> copy = new LinkedHashMap<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            Chunk chunk = e.getValue();
            if (!chunk.dirty) continue;
            int[][] columns = new int[CHUNK * CHUNK][];
            for (int i = 0; i < columns.length; i++) {
                int[] runs = chunk.columns[i];
                columns[i] = runs == null ? null : runs.clone();
            }
            copy.put(e.getKey(), columns);
        }
        return new Snapshot(copy, new ArrayList<>(explored), frozen);
    }

    /** Put a {@link #snapshot()} back, discarding every change made since. */
    public void restore(Snapshot saved) {
        if (saved == null) return;
        chunks.clear();
        forgetRecent();
        explored.clear();
        explored.addAll(saved.explored());
        frozen = saved.frozen();
        for (Map.Entry<Long, int[][]> e : saved.chunks().entrySet()) {
            Chunk chunk = new Chunk();
            chunk.cx = keyX(e.getKey());
            chunk.cy = keyY(e.getKey());
            chunk.dirty = true;
            int[][] columns = e.getValue();
            for (int i = 0; i < columns.length; i++) {
                chunk.columns[i] = columns[i] == null ? null : columns[i].clone();
            }
            chunks.put(e.getKey(), chunk);
        }
        forgetRecent();
    }

    /** Opaque saved state for {@link #snapshot()}/{@link #restore}. */
    public record Snapshot(Map<Long, int[][]> chunks, List<Long> explored, boolean frozen) {}

    // --- persistence -----------------------------------------------------------

    /**
     * The world as a level file carries it: the seed and shape it rebuilds
     * from, which chunks have been visited, and the contents of the chunks that
     * have been changed. Everything else is a function of the seed.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", seed);
        m.put("worldWidth", worldWidth);
        m.put("worldHeight", worldHeight);
        m.put("authored", List.of(authoredCol, authoredRow, authoredWidth, authoredHeight));
        m.put("frozen", frozen);
        List<Object> visited = new ArrayList<>(explored.size() * 2);
        List<Long> keys = new ArrayList<>(explored);
        keys.sort(Long::compare);
        for (long k : keys) {
            visited.add(keyX(k));
            visited.add(keyY(k));
        }
        m.put("explored", visited);
        Map<String, Object> edited = new LinkedHashMap<>();
        List<Long> dirtyKeys = new ArrayList<>();
        for (Map.Entry<Long, Chunk> e : chunks.entrySet()) {
            if (e.getValue().dirty) dirtyKeys.add(e.getKey());
        }
        dirtyKeys.sort(Long::compare);
        for (long k : dirtyKeys) {
            Chunk chunk = chunks.get(k);
            if (chunk == null) continue;
            edited.put(keyX(k) + "," + keyY(k), encode(chunk));
        }
        m.put("chunks", edited);
        return m;
    }

    /** One chunk as a flat number list: per column, a run count then its runs. */
    private static List<Object> encode(Chunk chunk) {
        List<Object> out = new ArrayList<>();
        for (int[] runs : chunk.columns) {
            int n = runs == null ? 0 : runs.length;
            out.add(n);
            for (int i = 0; i < n; i++) out.add(runs[i]);
        }
        return out;
    }

    /**
     * Rebuild a world from a saved level. The generator is reconstructed from
     * the seed rather than stored, so a save is the seed, the visited list and
     * the edits — and never the terrain itself.
     */
    public static WorldTerrain fromMap(Map<String, Object> m, TerrainSettings settings,
                                       BlockRegistry blocks) {
        if (m == null) return null;
        long seed = m.get("seed") instanceof Number n ? n.longValue() : settings.seed;
        int ww = intOf(m.get("worldWidth"), settings.worldSize);
        int wh = intOf(m.get("worldHeight"), settings.worldSize);
        int ac = 0, ar = 0, aw = 0, ah = 0;
        if (m.get("authored") instanceof List<?> a && a.size() >= 4) {
            ac = intOf(a.get(0), 0);
            ar = intOf(a.get(1), 0);
            aw = intOf(a.get(2), 0);
            ah = intOf(a.get(3), 0);
        }
        WorldTerrain world = new WorldTerrain(settings, blocks, ww, wh, ac, ar, aw, ah, seed);
        if (m.get("explored") instanceof List<?> visited) {
            for (int i = 0; i + 1 < visited.size(); i += 2) {
                world.explored.add(key(intOf(visited.get(i), 0), intOf(visited.get(i + 1), 0)));
            }
        }
        if (m.get("chunks") instanceof Map<?, ?> edited) {
            for (Map.Entry<?, ?> e : edited.entrySet()) {
                String[] parts = String.valueOf(e.getKey()).split(",");
                if (parts.length != 2) continue;
                try {
                    world.decode(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()), e.getValue());
                } catch (NumberFormatException ignored) {
                    // A hand-edited file can carry anything; a chunk that does
                    // not name a cell is skipped rather than failing the load.
                }
            }
        }
        world.frozen = Boolean.TRUE.equals(m.get("frozen")) || !settings.enabled;
        return world;
    }

    private void decode(int cx, int cy, Object data) {
        if (!(data instanceof List<?> flat)) return;
        Chunk chunk = new Chunk();
        chunk.cx = cx;
        chunk.cy = cy;
        chunk.dirty = true;
        int at = 0;
        for (int i = 0; i < CHUNK * CHUNK && at < flat.size(); i++) {
            int n = intOf(flat.get(at++), 0);
            if (n <= 0 || at + n > flat.size()) {
                chunk.columns[i] = EMPTY_COLUMN;
                at += Math.max(0, n);
                continue;
            }
            int[] runs = new int[n];
            for (int j = 0; j < n; j++) runs[j] = intOf(flat.get(at++), 0);
            chunk.columns[i] = runs;
        }
        long k = key(cx, cy);
        chunks.put(k, chunk);
        forgetRecent();
        explored.add(k);
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
