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
 * <p><b>The live sweep is now on this same lattice, and this class is where the
 * argument for it was worked out.</b> {@link Camera} used to round
 * {@code (world - camera) * zoom} in one step, which is the very mistake
 * described above — one object per rounding boundary rather than one chunk per
 * rounding boundary — so everything this cache deliberately does <em>not</em>
 * hold (stacked blocks, mobs, dropped items, decor, particles) went on shaking
 * while the floor under them held still. {@code Camera} now splits the rounding
 * the same way; see its class note. The consequence here is that a baked chunk
 * and the live sweep land in the same place at any zoom rather than only at
 * whole-pixel ones: on a 480x360 three-by-three-chunk view at {@code zoom = 1.7}
 * they disagreed on 2,010 pixels and now disagree on 105.
 *
 * <p>The differences that remain are small and static, and are this cache's own
 * rather than the projection's: a two-pixel bake margin, and chunks baked whole
 * so cells just past the requested bounds are drawn, which fills the screen edge
 * rather than cutting it.
 *
 * <p><b>They are also procedural-only, which is worth knowing before reading any
 * figure quoted for them.</b> A textured level goes through {@code TilePainter}'s
 * axis-aligned blit, whose destination rectangle is integer and camera-free, so
 * baked and live come out <b>pixel-identical</b> — measured at 0 differing pixels
 * of 921,600 on a 1280x720 view at zoom 1.7 with a 16-pixel texture on every
 * block. The residual 0.05% belongs to the procedural path, where a cell is a
 * filled polygon plus a darker outline drawn with antialiasing on: at a chunk edge
 * that outline blends against the neighbour in the live sweep and against
 * transparency in a chunk image, and compositing afterwards does not reproduce the
 * blend. Nearly half of the disagreement sits within two pixels of a chunk
 * boundary for that reason. It is the same argument {@link #faithfulIn} makes
 * about isometric, at a hundredth of the magnitude.
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
     *
     * <p><b>"A chunk holding an animated texture" is what this always said and was
     * not what it did, and the gap between the two is the vibration players kept
     * reporting in side-scrollers.</b> The frame number went into every chunk's
     * validity {@link Key} unconditionally, so a level with no animated textures
     * anywhere still had every chunk on screen invalidated twelve times a second.
     * With {@link #MAX_REBUILDS_PER_FRAME} at four against a dozen or more visible
     * chunks, the cache could not rebake them in the frame they went stale, and
     * the overflow was drawn <em>live</em> — the half-and-half frame that
     * {@link #MIN_CHURN_TO_STAND_ASIDE}'s note measures at <b>22 ms against 16 ms
     * all-live and 2 ms all-baked</b>. So the cache spent two frames in every five
     * in the most expensive rendering it has, for ever. Measured on a 1280x720
     * view at zoom 1.7, walking a static procedurally-drawn level:
     *
     * <pre>
     * frame | from cache | re-baked | drawn LIVE | terrain pass
     *     0 |          0 |        4 |  8         | 16.2 ms   &lt;- animFrame ticked
     *     1 |          4 |        4 |  4         | 11.3 ms
     *     2 |          8 |        4 |  0         | 10.3 ms
     *     3 |         12 |        0 |  0         |  2.5 ms
     *     4 |         12 |        0 |  0         |  2.4 ms
     *     5 |          0 |        4 |  8         | 15.0 ms   &lt;- and again, 83 ms later
     * </pre>
     *
     * <p><b>That is the defect, and it is a timing defect rather than a drawing
     * one.</b> The terrain pass alone oscillated between 2.4 ms and 16.2 ms on a
     * 12 Hz beat, against a whole-frame budget of 16.67 ms — so two frames in five
     * had no budget left for anything else and were delivered late while the other
     * three arrived on time. {@code RENDER_PLAN.md} D5 wrote the rule that names
     * this: <i>a world drawn rigidly and delivered unevenly looks like a world that
     * is not rigid.</i> The world was rigid (D3), the pacing had been fixed (D5)
     * and the sampling had been fixed (D6); this was the terrain pass making the
     * frame time itself vibrate at 12 Hz.
     *
     * <p>Over 600 frames of walking, the pass measured <b>mean 8.19 ms, p50 9.36,
     * p95 15.43</b> and <b>152 chunk re-bakes per second</b> — each one a 435x435
     * image re-rendered cell by cell and, on the GL backend, re-uploaded to the
     * GPU. Afterwards: <b>mean 1.84 ms, p50 1.63, p95 2.08</b> and <b>3.6 re-bakes
     * per second</b>, which are the chunks a walk genuinely reveals.
     *
     * <p><b>Why a side-scroller and not the plan views.</b> In a side-scroller
     * {@code Level.layered()} is false, so the cached floor <em>is</em> the blocks
     * — the whole visible world sits on the layer that was thrashing. A plan view
     * draws its stacked blocks and their shadows live in the depth pass on top of
     * the floor, so much of the screen is live-drawn anyway and the beat is buried
     * under it; and isometric is not cached at rest at all ({@link #faithfulIn}).
     *
     * <p><b>And why twenty tests of this class could not see it.</b> Every one of
     * them passes {@code animClock = 0}. At a stopped clock the frame number never
     * changes, so the key that was wrong was never exercised. The tests at the
     * bottom of {@code TerrainCacheTest} advance it.
     *
     * <p>The frame number now lives on the {@link Entry} rather than in the
     * {@link Key}, and is consulted only for a chunk that actually baked an
     * animated texture — which {@link ChunkRenderer} reports, because the painter
     * resolves the texture keys and is the only thing that knows.
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
     * into new territory. Over the budget, baking is deferred to a later frame,
     * which spreads the same total work across frames instead of landing it on
     * one.
     *
     * <p><b>What "over the budget" does was the second half of the vibration,
     * and it contradicted this class's own policy.</b> It used to draw the
     * overflowing chunks live and blit the rest — which is precisely the
     * half-and-half frame that {@link #MIN_CHURN_TO_STAND_ASIDE} exists to
     * prevent, and whose note records that mixing measured <b>22 ms</b> against
     * 16 ms for an all-live frame and 2 ms for an all-baked one. So the budget
     * was reaching for the most expensive rendering available, and — because
     * baked and live are not pixel-identical — was also making consecutive frames
     * differ in a shifting patch of the screen.
     *
     * <p>The rule is now the same one the churn threshold states: <b>the decision
     * belongs to the frame.</b> If the stale chunks fit in the budget they are
     * rebaked and every chunk on screen is a blit; if they do not, the whole view
     * is swept live in one uniform pass and the budget is spent baking for a later
     * frame instead. Either way a frame is all of one thing, so two consecutive
     * frames can only differ by the camera's shift.
     *
     * <p>This is the <em>floor</em> of the budget rather than the whole of it; see
     * {@link #budgetFor}.
     */
    private static final int MAX_REBUILDS_PER_FRAME = 4;

    /**
     * Cell changes per frame past which baking is not worth attempting, because
     * anything baked would be stale before it was ever blitted.
     *
     * <p><b>This used to decide whether the cache stood aside for the frame, and
     * that was the wrong question for it to answer.</b> The reason a half-cached
     * frame must not happen is real and measured: on a churning view, drawing
     * every chunk live cost 16 ms and serving every chunk from cache cost 2 ms,
     * but doing half of each cost <b>22 ms</b> — more than the slower of the two,
     * because alternating image blits with per-cell fills makes Java2D switch
     * between two quite different kinds of work all the way down the frame.
     * (Per-chunk cleverness — rebuild budgets, decaying strike counts, hysteresis
     * — was tried first and made it worse, because every one of those schemes
     * still produces a mixed frame, which is the thing that is slow.) So the
     * decision belongs to the frame, and it still does.
     *
     * <p>What changed is <em>which</em> number decides it. Counting changed cells
     * is a proxy for "how much rebaking does this frame owe", and it is a bad one:
     * a liquid tick that rewrites thirty cells inside three chunks is three cheap
     * rebakes, and this rule swept the whole view for it. Because
     * {@code LiquidSim} ticks about every 0.22 s, that produced a full-view live
     * sweep every thirteenth frame in any side-scroller with running water, with
     * all-baked frames either side of it — and baked and live are not
     * pixel-identical. Measured on a 1280x720 view over a pond pouring through a
     * gap in the floor, the frames came out
     *
     * <pre>
     * ...BBBBBBBBBBB L r BBBBBBBBBBB L r BBBBBBBBBBB L r...
     *                ^ the whole view swept live, ~4.6 times a second
     * </pre>
     *
     * <p>so the seams flickered across the whole screen on a beat set by the water.
     * {@code drawFloor} now asks the question directly — <b>do the chunks that need
     * rebaking fit in this frame's budget?</b> — which is the same policy measured
     * against the right quantity, and needs no threshold.
     *
     * <p>What is left for this number is the one thing chunk counting cannot see:
     * whether baking is <em>futile</em>. On a view being swept live because it
     * cannot keep up, the sweep also bakes a budget's worth each frame so that it
     * converges; on ground genuinely being rewritten faster than it can be baked,
     * those bakes are thrown away unused and are pure cost. Above this many cell
     * changes in a frame, the sweep stops baking and simply draws.
     */
    private static final int MIN_CHURN_TO_STAND_ASIDE = 8;

    /**
     * Chunks this frame may bake, given how many are on screen.
     *
     * <p><b>A fixed four was too few to absorb what one frame can newly reveal,
     * and the shortfall showed as a flicker on a tall display.</b> Crossing a
     * chunk boundary while walking uncovers a whole column at once — three chunks
     * on a 720p view, six on a 1440p one — and moving diagonally uncovers a column
     * and a row together. When that exceeded four, the frame took the uniform live
     * sweep above, and the frame after it went back to blits: a switch between two
     * renderings that are not pixel-identical, once per chunk crossing, which at a
     * walking pace is about once a second. Measured at 2560x1440, walking: 11
     * frames in 299 differed from their predecessor by more than the camera's
     * shift, in clusters spaced 1.16 s apart — exactly the time it takes to cross
     * 8 tiles at 220 px/s. Standing still it was 1, the cold start.
     *
     * <p>So the budget is what a frame can actually be asked for: one column plus
     * one row of chunks. That is bounded by the <em>edge</em> of the view rather
     * than by its area, which is the distinction the fixed number was reaching
     * for — the 13 ms spike it was written against came from baking a whole
     * <em>screen</em> of chunks on first entry, and that path is unchanged: first
     * entry needs far more than this, and still sweeps live while baking a
     * budget's worth per frame until it has caught up.
     *
     * <p>And at a crossing it is cheaper than what it replaces, not more
     * expensive: the alternative was a live sweep of <em>every</em> visible chunk
     * plus four bakes, and this bakes a column and blits the rest.
     *
     * <p><b>Measured off the viewport and not off {@code bounds}, which is not a
     * detail.</b> A caller may legitimately ask for a region far larger than the
     * screen — {@code TerrainCacheTest} hands over a whole 120x80 level — and a
     * budget derived from the request rather than from the display would scale
     * with it and bake exactly the screenful this cap exists to prevent. What one
     * frame can newly reveal is a property of the window, so the window is what
     * it is read from.
     *
     * <p>The ceiling is four times the floor. Past that the view is asking for
     * more baking per frame than the spike this budget was written against, and
     * the uniform live sweep is the better answer — a level zoomed far enough out
     * that fifty chunk columns fit on screen should be swept, not baked. That is
     * then what happens, and it is the right outcome rather than a fallback.
     *
     * <p><b>What a crossing now costs, stated rather than left to be found.</b>
     * Rebaking a column in one frame takes that frame's terrain pass to about
     * 8.6 ms against a 1.6 ms baseline, roughly once a second at a walking pace.
     * That stays inside a 16.67 ms budget alongside the ~7 ms the rest of the frame
     * costs, so no frame is dropped — and the alternative is not cheaper: spreading
     * it produces the mixed frame, which measures 22 ms and blows the budget
     * outright. One frame doing slightly more work beats two frames doing the
     * expensive kind.
     */
    private static int budgetFor(Camera camera) {
        double chunkPx = CHUNK * camera.tileSize * camera.zoom;
        if (chunkPx <= 0) return MAX_REBUILDS_PER_FRAME;
        // +1 on each axis because a view straddles one more chunk than it spans.
        long across = (long) Math.ceil(camera.viewportWidth / chunkPx) + 1;
        long down = (long) Math.ceil(camera.viewportHeight / chunkPx) + 1;
        long edge = across + down;
        return (int) Math.max(MAX_REBUILDS_PER_FRAME,
                Math.min(MAX_REBUILDS_PER_FRAME * 4L, edge));
    }

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("larsons.terrain.cache"));

    /**
     * One chunk's baked floor, and what it was baked from.
     *
     * <p>{@code latticeX/latticeY} are the chunk's position on a global pixel
     * lattice that does not involve the camera at all — see
     * {@link #drawFloor} for why that matters.
     */
    private record Entry(BufferedImage image, int latticeX, int latticeY, Key key,
                        boolean animated, long animFrame) {}

    /**
     * Everything a cached chunk depends on. Any change means a rebuild.
     *
     * <p>{@code revision} is the change count of <em>this chunk's own region</em>
     * rather than the level's. A global counter meant a single drop of water
     * moving anywhere threw away every chunk on screen — and liquids run every
     * tick, so in a level with water the cache spent its life rebuilding.
     * {@code generation} is the separate wholesale counter, for a grid replaced
     * rather than edited.
     *
     * <p>{@code edgeDx/edgeDy} and {@code stepDx/stepDy} are where the
     * projection sends one world tile along +x and one along +y — the camera's
     * heading <em>and</em> its tilt, in the only form the baked pixels care
     * about. A chunk baked looking north is a different picture from the same
     * chunk baked looking east, and a chunk baked from overhead is a different
     * picture from the same chunk baked from a camera brought down over it;
     * without these in the key one would be served for the other. They are the
     * projected edges rather than the angles so that a heading reached by
     * turning right eight times is the heading it started from, and because
     * between them they <em>are</em> the projection — two edges pin a linear map
     * of the plane completely, which is what determines the pixels.
     *
     * <p><b>Both edges, not just the first.</b> One edge was enough while the
     * only thing that could change under it was the heading. It is not enough
     * once the camera can tilt: at heading zero the +x edge is {@code (tile, 0)}
     * whatever the pitch, so a key holding only that one would have served a
     * chunk baked from overhead to a camera that had since been brought all the
     * way down over the floor.
     */
    private record Key(long generation, long revision, double zoom,
                       Perspective perspective, int tileSize,
                       double edgeDx, double edgeDy,
                       double stepDx, double stepDy) {}

    private final Map<Long, Entry> chunks = new HashMap<>();



    /** Chunks touched this frame; anything else is evicted at {@link #endFrame}. */
    private final java.util.Set<Long> live = new java.util.HashSet<>();

    private int hits;
    private int rebuilds;

    /**
     * Chunk images actually blitted to the screen since the counters were reset.
     *
     * <p>Separate from {@link #hits} because {@link #rebuilds} is not "chunks
     * drawn from cache": the uniform live sweep bakes a budget's worth of chunks
     * for a later frame without blitting any of them. So neither counter, nor
     * their sum, can say whether a frame was all blits or all live — and that is
     * the invariant worth asserting, since a frame that is half of each is both
     * the slowest rendering available and a picture that differs from its
     * neighbours in a shifting patch of the screen. This counter can: over one
     * frame it is either zero or the number of visible chunks, never in between.
     */
    private int blits;

    /** The level's change count at the previous frame, to measure churn. */
    private long lastRevision = -1;

    /** Whether caching is on at all. */
    public static boolean enabled() { return ENABLED; }

    /**
     * Whether a baked floor is faithful through this camera.
     *
     * <p><b>The rule is antialiasing, and it was written down as a rule about
     * formats because until the camera could turn, the format decided it.</b>
     * A tile edge that lands on a screen axis has nothing to antialias. A
     * diagonal one does: drawn live, it blends against the neighbour already
     * painted beside it; baked into its own chunk image, it blends against
     * transparency, and compositing two such images afterwards does not
     * reproduce the blend — it leaves a seam along every shared edge, with the
     * background showing through.
     *
     * <p>So the question is not which format this is. It is <b>whether the
     * projection puts both of a tile's edges on a screen axis</b>, which is
     * what this measures — through {@link Camera#planarDelta}, so the answer
     * follows the projection wherever it goes rather than being tabulated
     * against the cases that existed when it was written. It reproduces the old
     * answers exactly at rest (orthographic yes, isometric no) and gives four
     * more, measured in C3:
     *
     * <table>
     *   <caption>Seam, as a share of frame pixels, chunked against live</caption>
     *   <tr><th>heading</th><th>0°</th><th>22.5°</th><th>45°</th><th>90°</th><th>135°</th><th>180°</th></tr>
     *   <tr><td>top-down</td><td>0.055%</td><td>0.658%</td><td>0.542%</td><td>0.019%</td><td>0.511%</td><td>0.001%</td></tr>
     *   <tr><td>isometric</td><td>0.622%</td><td>0.696%</td><td><b>0.039%</b></td><td>0.612%</td><td><b>0.027%</b></td><td>0.591%</td></tr>
     * </table>
     *
     * <p>Two things fall out of that table. Rotation makes top-down behave
     * exactly like isometric at the headings where its edges go diagonal — an
     * order of magnitude worse than the same view at rest, and the same size as
     * the artefact isometric was excluded for. And <b>isometric at 45° is as
     * cacheable as top-down at 0°</b>: turning the grid an eighth of a turn puts
     * the diamond's edges back on the screen axes. The floor is cacheable at
     * four of the eight headings in either format — the other four in isometric
     * than in top-down — and at none of the angles in between, which is where a
     * snap animation spends its time.
     *
     * <p>Painting a ring of neighbouring tiles into each chunk was tried when
     * this was a format rule and made it worse, because the overlapping blits
     * then overwrite real tiles with differently-composited copies of
     * themselves. Fixing the diagonal case properly means one shared scroll
     * buffer rather than per-chunk images; that is a larger change, and it is
     * now worth less than it was, because half the headings do not need it.
     */
    public static boolean faithfulIn(Camera camera) {
        // A floor with no area on screen has nothing worth baking: a camera
        // flat on the floor draws every tile as a zero-height line, so each
        // chunk would bake a transparent sliver and the blits would land on
        // top of one another in sweep order rather than in depth order. The
        // live sweep draws exactly the same nothing, for free.
        if (camera.sliced()) return false;
        return onAScreenAxis(camera.planarDelta(camera.tileSize, 0))
                && onAScreenAxis(camera.planarDelta(0, camera.tileSize));
    }

    /**
     * Whether a projected tile edge runs along a screen axis — one of its two
     * components is zero, so the edge is exactly horizontal or exactly vertical
     * and the rasteriser has no partial coverage to blend.
     *
     * <p>Compared against the edge's own length rather than an absolute
     * epsilon, so the answer does not depend on the tile size or the zoom. At
     * the headings the camera rests at, {@link Camera#setYaw} has already made
     * the zero exact; the tolerance is for the arithmetic between them, not for
     * a heading that is nearly right.
     */
    private static boolean onAScreenAxis(double[] edge) {
        double scale = Math.abs(edge[0]) + Math.abs(edge[1]);
        if (scale == 0) return true;   // a degenerate projection has no seams
        return Math.abs(edge[0]) <= 1e-9 * scale || Math.abs(edge[1]) <= 1e-9 * scale;
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
        // Everything except the per-chunk region revision, which is added below.
        long generation = level.terrainGeneration();
        double zoom = camera.zoom;
        Perspective perspective = camera.getPerspective();
        // The projection, as the key needs it: where one tile along +x lands,
        // and where one along +y does.
        double[] edge = camera.planarDelta(level.tileSize, 0);
        double[] step = camera.planarDelta(0, level.tileSize);
        long animFrame = (long) (animClock * ANIM_FPS);

        // The camera enters the terrain's position exactly once, here. Every
        // chunk is then placed from this one value by integer arithmetic, so the
        // whole floor moves as one rigid sheet.
        //
        // Through planar(), because this has to be the same offset Camera.place
        // adds — the projected focus, not the world one. They were the same
        // number until the camera could turn (C1), and at a turned heading the
        // difference is the whole floor blitted somewhere the live sweep is not.
        double[] focus = camera.planar(camera.x, camera.y);
        int baseX = (int) Math.round(camera.viewportWidth / 2.0 - focus[0] * camera.zoom);
        int baseY = (int) Math.round(camera.viewportHeight / 2.0 - focus[1] * camera.zoom);

        int c0 = Math.max(0, bounds[0] / CHUNK);
        int r0 = Math.max(0, bounds[1] / CHUNK);
        int c1 = bounds[2] / CHUNK;
        int r1 = bounds[3] / CHUNK;

        int visible = (c1 - c0 + 1) * (r1 - r0 + 1);
        if (visible <= 0) return;

        // How fast the world is being rewritten, measured on the level rather
        // than on the cache's own state. Inferring it from stale chunks does
        // not work: once churn has emptied the cache there are no stale entries
        // left to count, so the cache concludes all is calm and starts baking
        // again — the same chicken-and-egg that made it stand aside forever on
        // its first frame when cold chunks were counted instead.
        long revision = level.terrainRevision();
        long changedSinceLastFrame = lastRevision < 0 ? 0 : revision - lastRevision;
        lastRevision = revision;
        boolean churning =
                changedSinceLastFrame > Math.max(MIN_CHURN_TO_STAND_ASIDE, visible / 2);

        // Which chunks cannot be served from cache this frame — decided before
        // anything is drawn, because the answer decides how the whole frame is
        // drawn. See the note on MAX_REBUILDS_PER_FRAME for why this cannot be a
        // per-chunk decision taken as the sweep goes along.
        int needed = 0;
        for (int cr = r0; cr <= r1; cr++) {
            for (int cc = c0; cc <= c1; cc++) {
                Key key = keyFor(level, cc, cr, generation, zoom, perspective, edge, step);
                if (cached(chunkKey(cc, cr), key, animFrame) == null) needed++;
            }
        }

        int budget = budgetFor(camera);
        if (needed > budget) {
            // One plain sweep of the whole view, with no blits mixed into it.
            renderChunk.render(target, camera, bounds[0], bounds[1], bounds[2], bounds[3]);
            // The chunks in view are still wanted; without this, endFrame()
            // evicts every one of them and the cache restarts from cold after a
            // single swept frame.
            for (int cr = r0; cr <= r1; cr++) {
                for (int cc = c0; cc <= c1; cc++) live.add(chunkKey(cc, cr));
            }
            // Bake a few anyway, unless the level itself is being rewritten (in
            // which case anything baked is stale before it is used). Without this
            // a view that needs more than a frame's budget would sweep live for
            // ever, because nothing would ever reduce the count that sent it here.
            // This is the only thing the cell-churn signal now decides; see
            // MIN_CHURN_TO_STAND_ASIDE.
            if (!churning) {
                int left = budget;
                for (int cr = r0; cr <= r1 && left > 0; cr++) {
                    for (int cc = c0; cc <= c1 && left > 0; cc++) {
                        long id = chunkKey(cc, cr);
                        Key key = keyFor(level, cc, cr, generation, zoom, perspective, edge, step);
                        if (cached(id, key, animFrame) != null) continue;
                        Entry built = build(level, camera, cc, cr, key, animFrame,
                                renderChunk, chunks.get(id));
                        if (built == null) continue;
                        chunks.put(id, built);
                        left--;
                        rebuilds++;
                    }
                }
            }
            return;
        }

        // Every chunk on screen ends up a baked blit: the few that were stale are
        // rebaked first, and the frame is then uniform.
        for (int cr = r0; cr <= r1; cr++) {
            for (int cc = c0; cc <= c1; cc++) {
                long id = chunkKey(cc, cr);
                Key key = keyFor(level, cc, cr, generation, zoom, perspective, edge, step);
                Entry entry = cached(id, key, animFrame);
                if (entry == null) {
                    entry = build(level, camera, cc, cr, key, animFrame, renderChunk,
                            chunks.get(id));
                    if (entry == null) continue;
                    rebuilds++;
                    chunks.put(id, entry);
                } else {
                    hits++;
                }
                live.add(id);
                blits++;
                target.drawImage(entry.image(), baseX + entry.latticeX(),
                        baseY + entry.latticeY());
            }
        }
    }

    /** Everything a chunk's validity depends on. */
    private static Key keyFor(Level level, int chunkCol, int chunkRow, long generation,
                              double zoom, Perspective perspective, double[] edge,
                              double[] step) {
        return new Key(generation,
                level.terrainRevisionAt(chunkCol * CHUNK, chunkRow * CHUNK),
                zoom, perspective, level.tileSize, edge[0], edge[1], step[0], step[1]);
    }

    /**
     * The cached chunk if it is still valid, else {@code null}.
     *
     * <p>The animation frame is checked <em>only</em> for a chunk that baked an
     * animated texture, which is the whole of the fix described on
     * {@link #ANIM_FPS}: a chunk of static blocks does not become wrong because
     * time passed, and treating it as though it did is what put two thirds of the
     * terrain on a live-drawn/baked flip-flop at 12 Hz.
     */
    private Entry cached(long id, Key key, long animFrame) {
        Entry existing = chunks.get(id);
        if (existing == null || !existing.key().equals(key)) return null;
        if (existing.animated() && existing.animFrame() != animFrame) return null;
        return existing;
    }

    /** Paint one chunk's cells straight at the screen, skipping the cache. */
    private void drawChunkLive(Level level, Camera camera, int chunkCol, int chunkRow,
                               int[] bounds, ChunkRenderer renderChunk, DrawTarget target) {
        // Clipped to what was actually asked for. A baked chunk is built whole
        // because it is kept, but drawing one live must cost exactly what the
        // plain sweep costs — otherwise the fallback is more expensive than the
        // thing it is falling back to.
        int col0 = Math.max(bounds[0], chunkCol * CHUNK);
        int row0 = Math.max(bounds[1], chunkRow * CHUNK);
        int col1 = Math.min(Math.min(level.width - 1, bounds[2]), chunkCol * CHUNK + CHUNK - 1);
        int row1 = Math.min(Math.min(level.height - 1, bounds[3]), chunkRow * CHUNK + CHUNK - 1);
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
            Long id = it.next().getKey();
            if (!live.contains(id)) it.remove();
        }
        live.clear();
    }

    /** Throw everything away — a new level, or a format change. */
    public void clear() {
        chunks.clear();
        live.clear();
        hits = 0;
        rebuilds = 0;
        blits = 0;
    }

    /** Chunk blits served from cache since the counters were last read. */
    public int hits() { return hits; }

    /** Chunks rebuilt since the counters were last read. */
    public int rebuilds() { return rebuilds; }

    /**
     * Chunk images blitted since the counters were last read — see {@link #blits}
     * for why this is the counter that says whether a frame was uniform.
     */
    public int blits() { return blits; }

    public void resetCounters() {
        hits = 0;
        rebuilds = 0;
        blits = 0;
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
                        Key key, long animFrame, ChunkRenderer renderChunk, Entry reusable) {
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

        // Reuse the chunk's previous image when it is the right size. A chunk
        // being rebuilt is usually being rebuilt because one cell in it
        // changed, and a level with liquids in it rebuilds constantly — at a
        // third of a megabyte per image, allocating a fresh one each time made
        // the cache a garbage generator that cost more than it saved.
        BufferedImage image = reusable != null
                && reusable.image().getWidth() == w
                && reusable.image().getHeight() == h
                ? reusable.image()
                : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        boolean animated;
        Graphics2D g = image.createGraphics();
        try {
            // Whatever was there is not this chunk any more.
            g.setComposite(java.awt.AlphaComposite.Clear);
            g.fillRect(0, 0, w, h);
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // The chunk paints in screen coordinates; translating by its own
            // origin is what makes the same painter fill an image instead.
            g.translate(-originX, -originY);
            // The painter reports whether anything it resolved in here actually
            // animates. It is the only thing that knows: the cache does not see
            // texture keys, and guessing "maybe" for every chunk is the defect
            // described on ANIM_FPS.
            animated = renderChunk.render(Java2DTarget.unsized(g), bake,
                    paintCol0, paintRow0, paintCol1, paintRow1);
        } finally {
            g.dispose();
        }
        // Say so, for any backend holding a copy of these pixels rather than
        // the pixels themselves. Java2D blits from the image and sees the
        // rebuild for free; a GPU backend uploaded it once and would go on
        // drawing the chunk this one replaced — which is what it did, and what
        // the GL parity comparison caught on `scene-play`. Cheap here: this
        // path has just re-rendered a chunk.
        com.larsons.engine.graphics.draw.ImageRevision.changed(image);
        // Where this image belongs relative to the frame's single camera
        // rounding: its lattice position, shifted by the margin it was drawn
        // with.
        return new Entry(image, latticeX + originX, latticeY + originY, key,
                animated, animFrame);
    }

    /**
     * A camera that projects world point {@code w} to
     * {@code round(w * zoom) - lattice}.
     *
     * <p>Derived by solving the projection {@code (w - x) * zoom + viewport/2}
     * for the {@code x} that makes the camera term vanish. It holds for any
     * projection {@link Camera#planar} can make, which C3 needs: the diamond
     * case now does arise, because isometric turned an eighth is cached
     * ({@link #faithfulIn}).
     *
     * <p><b>The focus that solves it is a point in <em>projected</em> space, so
     * it is carried back through the inverse projection rather than assigned
     * straight to {@code x}/{@code y}.</b> Those were the same number until the
     * camera could turn (C1); at any other heading, assigning the solution to a
     * world coordinate that is about to be rotated again bakes the chunk from a
     * position the frame is not looking from, which is the shaking bug this
     * class was written to fix, wearing a different hat. At heading zero the
     * inverse is the identity and the arithmetic is bit-for-bit what it was.
     */
    private static Camera bakeCamera(Camera camera, int latticeX, int latticeY) {
        Camera bake = new Camera(camera.getPerspective(),
                camera.viewportWidth, camera.viewportHeight);
        bake.zoom = camera.zoom;
        bake.tileSize = camera.tileSize;
        bake.isoTileWidth = camera.isoTileWidth;
        bake.isoTileHeight = camera.isoTileHeight;
        if (camera.boardDiamond()) {
            bake.useBoardDiamond(camera.isoTileWidth, camera.isoTileHeight);
        }
        bake.setYaw(camera.yaw());
        // The tilt as well as the heading: both are the projection, and a chunk
        // baked through a camera that only copied one of them is a chunk drawn
        // for a picture the frame is not showing.
        bake.setPitch(camera.pitch());
        double[] focus = camera.inversePlanar(
                (camera.viewportWidth / 2.0 + latticeX) / camera.zoom,
                (camera.viewportHeight / 2.0 + latticeY) / camera.zoom);
        bake.x = focus[0];
        bake.y = focus[1];
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
        /**
         * @return whether any texture drawn in this region actually animates —
         *         more than one frame at a positive rate. The cache uses it to
         *         decide whether the region it just baked can go stale as time
         *         passes, and a chunk of static blocks must answer {@code false}
         *         or it is thrown away twelve times a second for nothing. See
         *         the note on {@code ANIM_FPS}.
         */
        boolean render(DrawTarget target, Camera camera,
                       int col0, int row0, int col1, int row1);
    }
}
