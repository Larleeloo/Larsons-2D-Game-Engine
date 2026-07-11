package com.larsons.engine.level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sparse, chunked tile storage for <em>giant</em> levels (up to
 * 65536&times;65536 tiles), where a dense {@code int[65536][65536]} grid would
 * need 16&nbsp;GB. Tiles live in {@value #CHUNK}&times;{@value #CHUNK} chunks
 * held in a hash map keyed by chunk coordinates, so memory scales with the
 * area actually <em>visited</em>, not the level bounds.
 *
 * <p><b>Chunk loading.</b> Chunks materialize lazily on first access. When a
 * {@link ChunkGenerator} is attached (auto-generated giant worlds), a missing
 * chunk is generated on demand — and since the renderer and simulation only
 * touch tiles near the camera/players, only the <em>visible</em> chunks ever
 * load. Pristine generated chunks (never painted/mined) are evicted once the
 * loaded count passes {@link #MAX_LOADED_CHUNKS}; they regenerate identically
 * from the seed when revisited, so eviction is invisible. Edited chunks are
 * marked dirty, are never evicted, and are the only chunks serialized.
 *
 * <p>Reads are row-major almost everywhere (rendering, sims), so a one-entry
 * cache of the last chunk touched turns the hash lookup into a field read for
 * ~{@value #CHUNK} consecutive accesses.
 */
public final class ChunkedTiles {

    /** Tiles per chunk side. */
    public static final int CHUNK = 64;

    /** Loaded-chunk budget before pristine chunks are evicted (~64 MB). */
    public static final int MAX_LOADED_CHUNKS = 4096;

    private final Map<Long, int[]> chunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private ChunkGenerator generator;

    private int width, height; // level bounds, tiles

    // Last-chunk cache (row-major access pattern).
    private long cachedKey = Long.MIN_VALUE;
    private int[] cachedChunk;

    public ChunkedTiles(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public static long key(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Attach the on-demand generator for missing chunks ({@code null} = empty). */
    public void setGenerator(ChunkGenerator g) {
        this.generator = g;
    }

    public ChunkGenerator generator() {
        return generator;
    }

    /** Tile id at (col,row); out-of-bounds reads are empty. */
    public int get(int col, int row) {
        if (col < 0 || row < 0 || col >= width || row >= height) return 0;
        int[] chunk = chunkFor(col >> 6, row >> 6, generator != null);
        if (chunk == null) return 0;
        return chunk[(row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1))];
    }

    /** Set a tile; returns whether the cell changed. Marks the chunk dirty. */
    public boolean set(int col, int row, int id) {
        if (col < 0 || row < 0 || col >= width || row >= height) return false;
        long k = key(col >> 6, row >> 6);
        int[] chunk = chunkFor(col >> 6, row >> 6, true);
        int idx = (row & (CHUNK - 1)) * CHUNK + (col & (CHUNK - 1));
        if (chunk[idx] == id) return false;
        chunk[idx] = id;
        dirty.add(k);
        return true;
    }

    /**
     * The chunk at (cx,cy). With {@code materialize} a missing chunk is
     * created (generated when a generator is attached, empty otherwise);
     * without it, unloaded chunks read as {@code null} (= all empty).
     */
    private int[] chunkFor(int cx, int cy, boolean materialize) {
        long k = key(cx, cy);
        if (k == cachedKey) return cachedChunk;
        int[] chunk = chunks.get(k);
        if (chunk == null && materialize) {
            chunk = new int[CHUNK * CHUNK];
            if (generator != null) generator.generate(cx, cy, chunk);
            if (chunks.size() >= MAX_LOADED_CHUNKS) evictPristine();
            chunks.put(k, chunk);
        }
        if (chunk != null) {
            cachedKey = k;
            cachedChunk = chunk;
        }
        return chunk;
    }

    /** Drop ~half the pristine (never-edited) chunks; they regenerate on demand. */
    private void evictPristine() {
        int toDrop = Math.max(1, (chunks.size() - dirty.size()) / 2);
        Iterator<Map.Entry<Long, int[]>> it = chunks.entrySet().iterator();
        while (it.hasNext() && toDrop > 0) {
            Map.Entry<Long, int[]> e = it.next();
            if (!dirty.contains(e.getKey())) {
                it.remove();
                toDrop--;
            }
        }
        cachedKey = Long.MIN_VALUE;
        cachedChunk = null;
    }

    /** Number of chunks currently resident (HUD/diagnostics). */
    public int loadedCount() {
        return chunks.size();
    }

    /** Number of edited (persisted-on-save) chunks. */
    public int dirtyCount() {
        return dirty.size();
    }

    /** Shrink/grow the bounds; chunks fully outside the new bounds unload. */
    public void resize(int newWidth, int newHeight) {
        width = Math.max(1, newWidth);
        height = Math.max(1, newHeight);
        int maxCx = (width - 1) >> 6, maxCy = (height - 1) >> 6;
        chunks.keySet().removeIf(k -> (int) (k >> 32) > maxCx || (int) (long) k > maxCy);
        dirty.removeIf(k -> (int) (k >> 32) > maxCx || (int) (long) k > maxCy);
        cachedKey = Long.MIN_VALUE;
        cachedChunk = null;
    }

    // --- persistence -----------------------------------------------------------

    /**
     * The edited chunks as {@code "cx,cy" -> RLE run list} — pairs of
     * (tileId, runLength) over the chunk's row-major cells. Pristine generated
     * chunks are omitted; they rebuild from the generator's seed.
     */
    public Map<String, List<Object>> dirtyChunksRle() {
        Map<String, List<Object>> out = new java.util.LinkedHashMap<>();
        List<Long> keys = new ArrayList<>(dirty);
        keys.sort(Long::compare);
        for (long k : keys) {
            int[] chunk = chunks.get(k);
            if (chunk == null) continue;
            out.put(((int) (k >> 32)) + "," + ((int) (long) k), rle(chunk));
        }
        return out;
    }

    /** Install a saved chunk (from {@link #dirtyChunksRle} data) and mark it dirty. */
    public void putSavedChunk(int cx, int cy, List<Object> rleRuns) {
        int[] chunk = new int[CHUNK * CHUNK];
        int i = 0;
        for (int r = 0; r + 1 < rleRuns.size() && i < chunk.length; r += 2) {
            int id = rleRuns.get(r) instanceof Number n ? n.intValue() : 0;
            int len = rleRuns.get(r + 1) instanceof Number n ? n.intValue() : 0;
            for (int j = 0; j < len && i < chunk.length; j++) chunk[i++] = id;
        }
        long k = key(cx, cy);
        chunks.put(k, chunk);
        dirty.add(k);
        cachedKey = Long.MIN_VALUE;
        cachedChunk = null;
    }

    private static List<Object> rle(int[] cells) {
        List<Object> runs = new ArrayList<>();
        int i = 0;
        while (i < cells.length) {
            int id = cells[i];
            int j = i;
            while (j < cells.length && cells[j] == id) j++;
            runs.add(id);
            runs.add(j - i);
            i = j;
        }
        return runs;
    }

    // --- snapshot / restore (creative play-test terrain protection) -------------

    /** Deep copy of the current storage state (loaded chunks + dirty set). */
    public Snapshot snapshot() {
        Map<Long, int[]> copy = new HashMap<>();
        for (Map.Entry<Long, int[]> e : chunks.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }
        return new Snapshot(copy, new HashSet<>(dirty));
    }

    /** Restore a {@link #snapshot}, discarding every change made since. */
    public void restore(Snapshot s) {
        chunks.clear();
        dirty.clear();
        for (Map.Entry<Long, int[]> e : s.chunks.entrySet()) {
            chunks.put(e.getKey(), e.getValue().clone());
        }
        dirty.addAll(s.dirty);
        cachedKey = Long.MIN_VALUE;
        cachedChunk = null;
    }

    /** Opaque saved state for {@link #snapshot}/{@link #restore}. */
    public record Snapshot(Map<Long, int[]> chunks, Set<Long> dirty) {}
}
