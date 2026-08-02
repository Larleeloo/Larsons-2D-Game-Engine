package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps the floor already drawn.
 *
 * <p><b>The problem.</b> Terrain is the largest thing on screen and almost the
 * least likely to change: a 1080p viewport covers roughly two thousand cells,
 * and the painter rebuilt every one of them from scratch on every frame —
 * projecting four corners, resolving a texture, issuing a fill and an outline
 * — a hundred and twenty times a second, for ground that had not moved. A
 * draw-call census of that sweep counts four to seven thousand operations per
 * frame, and the overwhelming majority of them redraw exactly what was there
 * the frame before.
 *
 * <p><b>The fix.</b> Render each chunk of floor into its own image once and
 * blit the images. A chunk is rebuilt only when something it depends on
 * changes: the blocks in it ({@link Level#terrainRevision()}), the zoom, the
 * projection, or the animation frame of a tile texture. Walking across a level
 * costs the blits and nothing else, and the per-cell work happens when the
 * level is edited rather than when it is looked at.
 *
 * <p><b>Only the floor.</b> Stacked blocks are deliberately not cached. They
 * are not a layer — they join the {@link DepthPass} that the mobs, trees and
 * players share, so an actor can stand between two of them and the order is
 * settled per frame by where everyone is standing. Baking them into an image
 * would flatten exactly the thing that pass exists to keep sorted. Shadows and
 * mining cracks stay live for the same reason.
 *
 * <p><b>Every chunk sits on one global pixel lattice, and this is not a
 * detail.</b> The first version of this cache blitted each chunk at the rounded
 * projection of its own origin. Standing still that measured as 0.02% of pixels
 * and was written off as invisible. It was not: each chunk crossed its rounding
 * boundary at a different camera position, so as the view moved a fraction of a
 * pixel the chunks slid against one another and the terrain visibly shook.
 *
 * <p>So the camera enters the terrain's position exactly once per frame, and
 * every chunk is placed from that single value by integer arithmetic. A chunk's
 * cells are baked at {@code round(worldX * zoom)}, which has no camera term in
 * it at all, so the pixels inside an image do not depend on where the view was
 * when it was baked, and neighbouring chunks land on the same lattice instead
 * of leaving a seam. The whole floor now translates as one rigid sheet.
 *
 * <p>The remaining differences from the live sweep are small and static: the
 * lattice can sit a pixel from where the live painter would have put the
 * terrain (uniformly — the sheet moves together), and chunks are baked whole so
 * cells just past the requested bounds are drawn, which fills the screen edge
 * rather than cutting it. Measured on a 1280x720 side-scrolling view: nine
 * pixels.
 *
 * <p>{@code -Dlarsons.terrain.cache=false} turns the whole thing off and
 * restores the live sweep exactly.
 */
public final class TerrainCache {

    /** Chunk edge in tiles. Small enough to cull tightly, large enough to amortise. */
    public static final int CHUNK = 8;

    /**
     * Animation is sampled at this many frames per second for cache purposes.
     * A chunk holding an animated texture rebuilds at this rate instead of at
     * the frame rate — twelve rebuilds a second rather than a hundred and
     * twenty, and the eye cannot tell the difference on a tile animation.
     */
    private static final double ANIM_FPS = 12.0;

    /** Room around a chunk's projected box for outlines drawn on its edge. */
    private static final int MARGIN = 2;

    /**
     * Chunks baked in any one frame.
     *
     * <p>Walking into unseen ground on a large level asks for a whole screen of
     * chunks at once, and baking them together turned a 0.8 ms median terrain
     * pass into a 13 ms spike — a visible hitch every time the view crossed
     * into new territory. Over the budget, a chunk is drawn the slow way for
     * that frame and baked in a later one, which spreads the same total work
     * across frames instead of landing it on one.
     */
    private static final int MAX_REBUILDS_PER_FRAME = 4;

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("larsons.terrain.cache"));

    /**
     * One chunk's baked floor, and what it was baked from.
     *
     * <p>{@code latticeX/latticeY} are the chunk's position on a global pixel
     * lattice that does not involve the camera at all — see
     * {@link #drawFloor} for why that matters.
     */
    private record Entry(BufferedImage image, int latticeX, int latticeY, Key key) {}

    /** Everything a cached chunk depends on. Any change means a rebuild. */
    private record Key(long revision, double zoom, Perspective perspective,
                       int tileSize, long animFrame) {}

    private final Map<Long, Entry> chunks = new HashMap<>();

    /** Chunks touched this frame; anything else is evicted at {@link #endFrame}. */
    private final java.util.Set<Long> live = new java.util.HashSet<>();

    private int hits;
    private int rebuilds;

    /** Whether caching is on at all. */
    public static boolean enabled() { return ENABLED; }

    /**
     * Whether a baked floor is faithful in this projection.
     *
     * <p><b>Isometric is excluded, and the reason is antialiasing.</b> Its
     * floor tiles are diamonds, so every edge between two tiles is diagonal and
     * gets antialiased. Drawn live, a tile's edge blends against the neighbour
     * already painted beside it. Baked into its own chunk image, the same edge
     * blends against transparency, and compositing two such images afterwards
     * does not reproduce the blend — it leaves a seam along every shared edge
     * in the level. Measured on a 1280x720 isometric view, that was 16.7% of
     * the frame's pixels: not a rounding difference, a visible artefact.
     *
     * <p>Painting a ring of neighbouring tiles into each chunk was tried and
     * made it worse (19.8%), because the overlapping blits then overwrite real
     * tiles with differently-composited copies of themselves.
     *
     * <p>The orthographic formats have axis-aligned tile edges with nothing to
     * antialias, and there the baked floor matches the live sweep to within
     * 0.03% of pixels — chunk-edge rounding, invisible in motion.
     *
     * <p>Fixing isometric means one shared scroll buffer rather than
     * per-chunk images, so that edges blend against their real neighbours. That
     * is a larger change and is left until the formats that already work have
     * proven the approach.
     */
    public static boolean faithfulIn(Perspective perspective) {
        return perspective != Perspective.ISOMETRIC;
    }

    /**
     * Draw the floor of every chunk overlapping {@code bounds}, building any
     * that are missing or stale.
     *
     * @param renderChunk paints one chunk's cells into a target whose origin is
     *                    the chunk's own top-left — the live painter, redirected
     */
    public void drawFloor(DrawTarget target, Level level, Camera camera, int[] bounds,
                          double animClock, ChunkRenderer renderChunk) {
        Key key = new Key(level.terrainRevision(), camera.zoom, camera.getPerspective(),
                level.tileSize, (long) (animClock * ANIM_FPS));

        // The camera enters the terrain's position exactly once, here. Every
        // chunk is then placed by integer arithmetic off this one value, so the
        // whole floor moves as one rigid sheet.
        int baseX = (int) Math.round(camera.viewportWidth / 2.0 - camera.x * camera.zoom);
        int baseY = (int) Math.round(camera.viewportHeight / 2.0 - camera.y * camera.zoom);

        int c0 = Math.max(0, bounds[0] / CHUNK);
        int r0 = Math.max(0, bounds[1] / CHUNK);
        int c1 = bounds[2] / CHUNK;
        int r1 = bounds[3] / CHUNK;

        int budget = MAX_REBUILDS_PER_FRAME;
        for (int cr = r0; cr <= r1; cr++) {
            for (int cc = c0; cc <= c1; cc++) {
                long id = chunkKey(cc, cr);
                Entry entry = cached(id, key);
                if (entry == null) {
                    if (budget <= 0) {
                        // Over the frame's baking budget: draw this chunk the
                        // slow way rather than stalling. It gets baked within
                        // the next frame or two, and the cost is spread instead
                        // of landing all at once.
                        drawChunkLive(level, camera, cc, cr, renderChunk, target);
                        continue;
                    }
                    entry = build(level, camera, cc, cr, key, renderChunk);
                    if (entry == null) continue;
                    budget--;
                    rebuilds++;
                    chunks.put(id, entry);
                } else {
                    hits++;
                }
                live.add(id);
                target.drawImage(entry.image(), baseX + entry.latticeX(),
                        baseY + entry.latticeY());
            }
        }
    }

    /** The cached chunk if it is still valid, else {@code null}. */
    private Entry cached(long id, Key key) {
        Entry existing = chunks.get(id);
        return existing != null && existing.key().equals(key) ? existing : null;
    }

    /** Paint one chunk's cells straight at the screen, skipping the cache. */
    private void drawChunkLive(Level level, Camera camera, int chunkCol, int chunkRow,
                               ChunkRenderer renderChunk, DrawTarget target) {
        int col0 = chunkCol * CHUNK;
        int row0 = chunkRow * CHUNK;
        int col1 = Math.min(level.width - 1, col0 + CHUNK - 1);
        int row1 = Math.min(level.height - 1, row0 + CHUNK - 1);
        if (col0 > col1 || row0 > row1) return;
        renderChunk.render(target, camera, col0, row0, col1, row1);
    }

    /**
     * Drop chunks that were not drawn this frame. A cache that only ever grew
     * would hold every chunk a player had walked past, which on a large level
     * is the whole level in images.
     */
    public void endFrame() {
        if (chunks.size() <= live.size()) {
            live.clear();
            return;
        }
        Iterator<Map.Entry<Long, Entry>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            if (!live.contains(it.next().getKey())) it.remove();
        }
        live.clear();
    }

    /** Throw everything away — a new level, or a format change. */
    public void clear() {
        chunks.clear();
        live.clear();
        hits = 0;
        rebuilds = 0;
    }

    /** Chunk blits served from cache since the counters were last read. */
    public int hits() { return hits; }

    /** Chunks rebuilt since the counters were last read. */
    public int rebuilds() { return rebuilds; }

    public void resetCounters() {
        hits = 0;
        rebuilds = 0;
    }

    /**
     * Bake one chunk onto the global pixel lattice.
     *
     * <p>The chunk is painted through a <em>bake camera</em> rather than the
     * real one: a camera positioned so that a cell at world {@code w} lands at
     * {@code round(w * zoom) - K}, where {@code K} is this chunk's own lattice
     * offset. That expression has no camera term in it, so the pixels inside a
     * chunk image are the same whatever the view was doing when it was baked —
     * and because every chunk uses the same {@code round(w * zoom)} lattice,
     * adjacent chunks abut exactly instead of leaving a seam.
     */
    private Entry build(Level level, Camera camera, int chunkCol, int chunkRow,
                        Key key, ChunkRenderer renderChunk) {
        int col0 = chunkCol * CHUNK;
        int row0 = chunkRow * CHUNK;
        int col1 = Math.min(level.width - 1, col0 + CHUNK - 1);
        int row1 = Math.min(level.height - 1, row0 + CHUNK - 1);
        if (col0 > col1 || row0 > row1) return null;

        int paintCol0 = col0, paintRow0 = row0, paintCol1 = col1, paintRow1 = row1;

        int ts = level.tileSize;

        // This chunk's origin on the global lattice, and a camera that makes
        // the painter draw relative to it.
        int latticeX = (int) Math.round(paintCol0 * (double) ts * camera.zoom);
        int latticeY = (int) Math.round(paintRow0 * (double) ts * camera.zoom);
        Camera bake = bakeCamera(camera, latticeX, latticeY);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int[] out = new int[2];
        // The four corners of the chunk's world box; in a diamond projection
        // all four contribute, so none can be assumed.
        double[][] corners = {
                {paintCol0 * (double) ts, paintRow0 * (double) ts},
                {(paintCol1 + 1) * (double) ts, paintRow0 * (double) ts},
                {(paintCol1 + 1) * (double) ts, (paintRow1 + 1) * (double) ts},
                {paintCol0 * (double) ts, (paintRow1 + 1) * (double) ts},
        };
        for (double[] c : corners) {
            bake.worldToScreen(c[0], c[1], out);
            minX = Math.min(minX, out[0]);
            maxX = Math.max(maxX, out[0]);
            minY = Math.min(minY, out[1]);
            maxY = Math.max(maxY, out[1]);
        }

        int originX = minX - MARGIN;
        int originY = minY - MARGIN;
        int w = (maxX - minX) + MARGIN * 2 + 1;
        int h = (maxY - minY) + MARGIN * 2 + 1;
        if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) return null;   // absurd zoom

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // The chunk paints in screen coordinates; translating by its own
            // origin is what makes the same painter fill an image instead.
            g.translate(-originX, -originY);
            renderChunk.render(Java2DTarget.unsized(g), bake,
                    paintCol0, paintRow0, paintCol1, paintRow1);
        } finally {
            g.dispose();
        }
        // Where this image belongs relative to the frame's single camera
        // rounding: its lattice position, shifted by the margin it was drawn
        // with.
        return new Entry(image, latticeX + originX, latticeY + originY, key);
    }

    /**
     * A camera that projects world point {@code w} to
     * {@code round(w * zoom) - lattice}.
     *
     * <p>Derived by solving the orthographic projection
     * {@code (w - x) * zoom + viewport/2} for the {@code x} that makes the
     * camera term vanish. Only the orthographic formats are cached
     * ({@link #faithfulIn}), so the diamond case does not arise.
     */
    private static Camera bakeCamera(Camera camera, int latticeX, int latticeY) {
        Camera bake = new Camera(camera.getPerspective(),
                camera.viewportWidth, camera.viewportHeight);
        bake.zoom = camera.zoom;
        bake.tileSize = camera.tileSize;
        bake.isoTileWidth = camera.isoTileWidth;
        bake.isoTileHeight = camera.isoTileHeight;
        bake.x = (camera.viewportWidth / 2.0 + latticeX) / camera.zoom;
        bake.y = (camera.viewportHeight / 2.0 + latticeY) / camera.zoom;
        return bake;
    }

    private static long chunkKey(int chunkCol, int chunkRow) {
        return ((long) chunkCol << 32) ^ (chunkRow & 0xFFFFFFFFL);
    }

    /**
     * Paints the floor cells of one chunk into {@code target}, projecting
     * through {@code camera}.
     *
     * <p>The camera is a parameter rather than the painter's own because a
     * chunk is baked through a <em>different</em> camera from the one the frame
     * is using — see {@link #bakeCamera}. Handing the painter the real camera
     * here was the whole of the shaking bug: the image was sized on the lattice
     * and then filled from the live view.
     */
    @FunctionalInterface
    public interface ChunkRenderer {
        void render(DrawTarget target, Camera camera, int col0, int row0, int col1, int row1);
    }
}
