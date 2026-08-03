package com.larsons.engine.graphics.draw;

/**
 * A tally of what one frame asked a {@link DrawTarget} to do.
 *
 * <p><b>Why count.</b> The profiler says scene drawing costs 15 ms; it cannot
 * say whether that is a thousand cheap operations or twenty expensive ones,
 * and the answer decides how much a GPU backend would buy. Java2D pays per
 * call — every {@code fillRect} is its own rasterization — while a batching
 * backend pays per <em>state change</em>, merging every consecutive shape that
 * shares a texture and blend mode into one draw. So the ratio between
 * {@link #operations()} and {@link #batches()} is the speed-up available
 * before anyone writes a line of OpenGL.
 *
 * <p>A frame of ten thousand operations that collapse into thirty batches is a
 * frame a GPU renderer will transform. Ten thousand that collapse into eight
 * thousand — because every other one changes texture — is a frame that needs
 * its <em>art</em> reorganised into atlases first, and no amount of backend
 * work will rescue it. Counting is how those two are told apart, and it costs
 * an increment per call.
 *
 * <p>Batches are counted the way a real batcher would: a run of operations
 * sharing a batch key extends the current batch, and anything else starts a
 * new one. The Java2D backend does not actually batch — it reports what a
 * backend that did would achieve on the same command stream.
 *
 * <p>Single-threaded by design; the render thread owns one of these.
 */
public final class DrawStats {

    /** What makes two consecutive operations mergeable into one draw call. */
    public enum Kind {
        /** Solid-colour geometry: rectangles, ovals, arcs, polygons, lines. */
        SHAPE,
        /** Textured geometry; batches only with the same source image. */
        IMAGE,
        /**
         * Glyph quads.
         *
         * <p>Keyed by the atlas page the run's glyphs were rasterised into
         * when {@code GlyphAtlas} could serve it, and by the {@link
         * java.awt.Font} when it could not. That difference is the whole of
         * B6: see {@link #textured}.
         */
        TEXT,
        /**
         * A gradient fill — its own kind because it never merges with
         * anything, including the next gradient.
         *
         * <p>Counting these as {@link #SHAPE} would be the comfortable choice
         * and the wrong one. A flat shape is a quad in the shared batch; a
         * gradient is a distinct paint, and on a GL backend either its own
         * shader or its own generated texture. Folding them in would let a
         * screen of eighteen gradients report a merge ratio it could never
         * achieve, and the merge ratio is the single number the whole GPU case
         * rests on (B5). An instrument that flatters the thing it measures is
         * worse than no instrument.
         */
        GRADIENT,
        /** Clip, alpha or transform changes — always break a batch. */
        STATE;

        /**
         * Whether this kind draws from a texture, and so can share a batch
         * with any other textured kind that names the same one.
         *
         * <p><b>Why {@link #IMAGE} and {@link #TEXT} are one family.</b> They
         * were separate kinds because they were separate textures: a sprite
         * came from its own image and a glyph run came from its font's
         * rasteriser, and no backend could keep both bound at once. B6 makes
         * that false by rasterising glyphs into the same {@code SpriteAtlas}
         * pages the sprites are packed into, so an icon-then-label row really
         * is one bind and one draw call.
         *
         * <p>Which is exactly why the key, not the kind, decides. A text run
         * the glyph atlas could not serve is still keyed by its font, and a
         * font is not a page, so it merges with nothing — the instrument
         * credits the step only where the step actually applies. B5's
         * correction is the reason to be careful here: the plan predicted a
         * win in two frames and measured none, and the only defence against
         * repeating that is a counter that cannot report a merge the backend
         * would not get.
         */
        boolean textured() { return this == IMAGE || this == TEXT; }
    }

    private int operations;
    private int batches;
    private int shapes;
    private int images;
    private int glyphs;
    private int gradients;
    private int stateChanges;

    private Kind lastKind;
    private Object lastKey;

    /** Record one operation of {@code kind} keyed by {@code key} (image, font). */
    public void record(Kind kind, Object key) {
        operations++;
        switch (kind) {
            case SHAPE -> shapes++;
            case IMAGE -> images++;
            case TEXT -> glyphs++;
            case GRADIENT -> gradients++;
            case STATE -> stateChanges++;
        }
        // State changes break the run outright, and so does a gradient — it
        // cannot join the batch before it and nothing can join it. Otherwise a
        // matching kind and key extends the batch a real backend would already
        // have open.
        if (kind == Kind.STATE || kind == Kind.GRADIENT) {
            batches++;
            lastKind = null;
            lastKey = null;
            return;
        }
        // Two textured operations naming the same texture continue the batch
        // whichever kinds they are — that is what a shared atlas page buys.
        // Everything else needs the same kind as well.
        boolean sameBatch = java.util.Objects.equals(key, lastKey)
                && (kind == lastKind
                    || (key != null && kind.textured() && lastKind != null && lastKind.textured()));
        if (!sameBatch) {
            batches++;
        }
        lastKind = kind;
        lastKey = key;
    }

    /** Convenience for operations with no batching key of their own. */
    public void record(Kind kind) {
        record(kind, null);
    }

    /** Start a fresh frame's tally. */
    public void reset() {
        operations = 0;
        batches = 0;
        shapes = 0;
        images = 0;
        glyphs = 0;
        gradients = 0;
        stateChanges = 0;
        lastKind = null;
        lastKey = null;
    }

    /** Total drawing operations issued. */
    public int operations() { return operations; }

    /**
     * Draw calls a batching backend would issue for the same stream — the
     * number Java2D's per-call cost would collapse to.
     */
    public int batches() { return batches; }

    public int shapes() { return shapes; }

    public int images() { return images; }

    public int glyphs() { return glyphs; }

    /** Gradient fills — each one its own batch. See {@link Kind#GRADIENT}. */
    public int gradients() { return gradients; }

    public int stateChanges() { return stateChanges; }

    /**
     * Operations per batch: how much merging the current draw order allows.
     * One means no two consecutive operations are compatible and batching
     * would buy nothing; higher is better, and a scene sorted by texture
     * should reach double digits.
     */
    public double mergeRatio() {
        return batches == 0 ? 0 : (double) operations / batches;
    }

    /** A snapshot that will not change as the next frame draws. */
    public DrawStats copy() {
        DrawStats copy = new DrawStats();
        copy.operations = operations;
        copy.batches = batches;
        copy.shapes = shapes;
        copy.images = images;
        copy.glyphs = glyphs;
        copy.gradients = gradients;
        copy.stateChanges = stateChanges;
        return copy;
    }

    @Override
    public String toString() {
        return ("%d ops -> %d batches (%.1fx): %d shape, %d image, %d text, "
                + "%d gradient, %d state")
                .formatted(operations, batches, mergeRatio(), shapes, images,
                        glyphs, gradients, stateChanges);
    }
}
