package com.larsons.engine.gl;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A growable vertex buffer of {@code (x, y, u, v, rgba)}, flushed when
 * something makes the next triangle incompatible with the ones already in it.
 *
 * <p><b>What forces a flush, and nothing else does.</b> A different texture, a
 * full buffer, and a state change the GPU cannot express per-vertex — clip and
 * stencil. Alpha and transform are deliberately <em>not</em> on that list:
 * alpha multiplies into the vertex colour and the transform is applied on the
 * CPU as vertices are appended, so a scene that pushes and pops either of them
 * around every entity — which this one does — keeps one batch open across the
 * lot. That is the whole reason {@code DrawTarget} scopes state as a stack
 * rather than a setting.
 *
 * <p><b>Bytes, not floats.</b> The colour rides in the same interleaved buffer
 * as four normalised unsigned bytes. Packing it into a {@code float} via
 * {@code Float.intBitsToFloat} is the usual trick and it is not safe here: the
 * language permits a NaN to be canonicalised on its way through a
 * {@code float}, and a colour whose high bits happen to spell one comes out the
 * other side as a different colour on some JVMs and not others. A
 * {@link ByteBuffer} in native order has no such clause.
 */
final class GlBatch implements AutoCloseable {

    /** {@code x, y, u, v} as floats plus four colour bytes. */
    static final int STRIDE = 4 * 4 + 4;

    /**
     * Vertices held before a flush is forced.
     *
     * <p>Sized from the measurement rather than by feel: the busiest frame in
     * the golden catalogue (`world-crowd`) issues 374 operations, and the
     * heaviest of them — a flattened rounded rectangle — is a few hundred
     * vertices. 64k vertices is 1.25 MB and holds any frame this engine draws
     * without a capacity flush, so the flush count in a report is a count of
     * real state changes and not of arithmetic about buffer sizes.
     */
    private static final int MAX_VERTICES = 65536;

    private final int vbo;
    private ByteBuffer vertices;
    private int count;
    private int capacity;

    private int texture = -1;
    private int draws;

    GlBatch() {
        capacity = 4096;
        vertices = MemoryUtil.memAlloc(capacity * STRIDE);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_STREAM_DRAW);

        glEnableVertexAttribArray(GlProgram.ATTRIB_POSITION);
        glVertexAttribPointer(GlProgram.ATTRIB_POSITION, 2, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(GlProgram.ATTRIB_UV);
        glVertexAttribPointer(GlProgram.ATTRIB_UV, 2, GL_FLOAT, false, STRIDE, 8);
        glEnableVertexAttribArray(GlProgram.ATTRIB_COLOR);
        glVertexAttribPointer(GlProgram.ATTRIB_COLOR, 4, GL_UNSIGNED_BYTE, true, STRIDE, 16);
    }

    /**
     * Bind {@code id} for the triangles that follow, flushing first if that is
     * a different texture from the one already open.
     */
    void useTexture(int id) {
        if (id == texture) return;
        flush();
        texture = id;
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    /** The texture currently bound, or -1 if none. */
    int texture() { return texture; }

    /** Append one vertex. {@code argb} is {@code 0xAARRGGBB}. */
    void vertex(float x, float y, float u, float v, int argb) {
        if (count == capacity) {
            if (capacity < MAX_VERTICES) {
                grow();
            } else {
                flush();
            }
        }
        int at = count * STRIDE;
        vertices.putFloat(at, x);
        vertices.putFloat(at + 4, y);
        vertices.putFloat(at + 8, u);
        vertices.putFloat(at + 12, v);
        vertices.put(at + 16, (byte) (argb >> 16));
        vertices.put(at + 17, (byte) (argb >> 8));
        vertices.put(at + 18, (byte) argb);
        vertices.put(at + 19, (byte) (argb >>> 24));
        count++;
    }

    /**
     * Draw everything appended so far.
     *
     * <p>Flushing mid-triangle would be a corrupt draw, so callers append whole
     * triangles and nothing here has to reason about partial ones. The one
     * place that could break the rule is the capacity flush above, which is why
     * the buffer holds a multiple of three and callers never straddle it — a
     * triangle is three {@link #vertex} calls with nothing between them.
     */
    void flush() {
        if (count == 0) return;
        vertices.position(0).limit(count * STRIDE);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Orphan first: handing the driver a fresh store means it never has to
        // stall waiting for the previous draw to finish reading the old one.
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glDrawArrays(GL_TRIANGLES, 0, count);
        vertices.clear();
        count = 0;
        draws++;
    }

    /** How many {@code glDrawArrays} calls this batch has issued. */
    int draws() { return draws; }

    /** Start a fresh frame's draw tally. */
    void resetDraws() { draws = 0; }

    /**
     * Forget the bound texture without flushing — for when something outside
     * this class has rebound one, which the stencil passes do.
     */
    void forgetTexture() { texture = -1; }

    private void grow() {
        flush();
        capacity = Math.min(MAX_VERTICES, capacity * 2);
        vertices = MemoryUtil.memRealloc(vertices, capacity * STRIDE);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * STRIDE, GL_STREAM_DRAW);
        // The pointers are offsets into a stride, not into the store, so they
        // survive a reallocation — but the binding they were set against does
        // not, and re-stating them costs nothing here.
        glVertexAttribPointer(GlProgram.ATTRIB_POSITION, 2, GL_FLOAT, false, STRIDE, 0);
        glVertexAttribPointer(GlProgram.ATTRIB_UV, 2, GL_FLOAT, false, STRIDE, 8);
        glVertexAttribPointer(GlProgram.ATTRIB_COLOR, 4, GL_UNSIGNED_BYTE, true, STRIDE, 16);
    }

    @Override
    public void close() {
        glDeleteBuffers(vbo);
        MemoryUtil.memFree(vertices);
        vertices = null;
    }
}
