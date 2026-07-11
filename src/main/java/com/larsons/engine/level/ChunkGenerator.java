package com.larsons.engine.level;

/**
 * Fills one {@link ChunkedTiles#CHUNK}&times;{@link ChunkedTiles#CHUNK} chunk
 * of a giant level on demand. Implementations must be <em>deterministic</em>
 * functions of (seed, cx, cy): the same chunk must generate identically every
 * time, because pristine chunks are evicted under memory pressure and rebuilt
 * when revisited (see {@link ChunkedTiles}).
 */
public interface ChunkGenerator {

    /**
     * Write tile ids into {@code out} (row-major,
     * {@code out[localRow * CHUNK + localCol]}) for the chunk at chunk
     * coordinates (cx, cy). {@code out} arrives zero-filled.
     */
    void generate(int cx, int cy, int[] out);

    /** The seed the generator derives from (persisted so saves rebuild it). */
    long seed();
}
