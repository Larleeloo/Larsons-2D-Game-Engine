package com.larsons.engine.graphics;

import com.larsons.engine.level.Level;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.world.Block;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a level's terrain, in whichever number of layers its format has.
 *
 * <p>A side-scroller has one layer and is painted the way it always was: every
 * non-empty cell is a quad, and the block itself says whether you can walk
 * through it. The plan-view formats have two, and there the <em>stack</em> is
 * the geometry — one layer is floor, two is a wall (see {@link Level#walkable})
 * — which is a distinction the camera cannot show by colour alone, because from
 * above a wall and the floor beside it are both just squares. So a stacked
 * block is drawn as a block: lifted off its own floor tile, showing the side
 * face that lift exposes, and casting a shadow onto the floor behind it. Height
 * is what the player reads, and the shadow is what makes the height legible.
 *
 * <p><b>Layers and depth.</b> The floor goes down first, in one flat pass —
 * it is the ground everything else stands on and can never be in front of
 * anything. Raised blocks are not a layer at all: they join the {@link DepthPass}
 * that the trees, mobs, dropped items and players share, queued at the depth of
 * the cell they stand on ({@link #tileDepth}), so who is in front of whom is
 * settled by where each thing stands. Walking north behind a wall puts the wall
 * in front of you; walking south past it puts you in front of the wall — the
 * same rule that already decided whether you pass in front of a tree. A block
 * is queued {@link DepthPass#TERRAIN behind} everything else at its own depth,
 * because a wall is something to stand against and not something to be inside.
 *
 * <p><b>Faces.</b> A plan view looks at faces a side-scroller never shows, so
 * blocks have their own texture pools for them ({@link TextureKeys#BLOCKS_TOP},
 * {@link TextureKeys#BLOCKS_SIDE}). Both are optional: a block that supplies
 * neither falls back to its one side-scroll sheet, and failing that to the
 * shaded procedural colour that has always drawn it.
 */
public final class TerrainPainter {

    /**
     * How tall a stacked block stands, as a fraction of a tile in world units.
     *
     * <p>Lives on {@link Level} rather than here, because it stopped being a
     * drawing constant the moment a body could stand on a block: the physics
     * needs the same number to know how high that is, and two copies of it
     * would come apart as soon as one was tuned. Kept as a name here because
     * this is where it is used most.
     */
    public static final double BLOCK_HEIGHT = Level.BLOCK_HEIGHT;

    /** How far a shadow reaches from its caster, as a fraction of a tile. */
    private static final double SHADOW_REACH = 0.34;

    /** The shadow itself — one flat translucent black, cast toward the south-east. */
    private static final Color SHADOW = new Color(0, 0, 0, 96);

    /** Darkening applied to a side face with no texture of its own. */
    private static final double SIDE_SHADE = 0.62;

    /** The bright line along the top of an exposed liquid surface. */
    private static final Color LIQUID_SURFACE = new Color(255, 255, 255, 90);

    /**
     * How many layers of screen a standing body covers, for the cutaway's
     * reach. A character is drawn about a tile tall, and one layer of slack
     * covers what they carry over their head.
     */
    private static final int BODY_LAYERS = 2;

    private TerrainPainter() {}

    /**
     * Screen pixels a stacked block stands above its own floor tile, at this
     * camera's zoom and projection — zero where the format has no second layer
     * to lift. The editor's cursor preview borrows it so a brush about to build
     * a wall is drawn standing up, like the wall it is about to make.
     */
    public static int liftPixels(Camera camera, int tileSize) {
        return (int) Math.round(tileSize * BLOCK_HEIGHT * camera.zoom
                * camera.liftScale());
    }

    /**
     * The depth a cell is sorted at: the screen row of its centre.
     *
     * <p>The primary key of everything sharing a plan view's {@link DepthPass}
     * — a raised block's own, and the cell an actor is standing on. It is
     * public because those two have to be the <em>same number</em> for the same
     * cell: what orders a plan view is which cell a thing is on, and a second
     * copy of this line elsewhere is a bug waiting for a projection to change
     * under it. {@link DepthPass} carries the argument for comparing cells at
     * all.
     *
     * <p><b>Why the centre.</b> Between one cell and another the offset inside
     * the cell cancels — any point taken the same way for every cell puts them
     * in the same order — so this is not what fixes the ordering. What it fixes
     * is the comparison against a caller that hands {@link DepthPass#at(int,
     * Runnable)} a plain screen row, knowing nothing about cells: that number
     * lands somewhere inside a cell, and the stand-in wants to be as far from
     * both edges as it can. Write {@code f} for the screen row of a floor point
     * — linear, whatever the projection or heading. A cell's key has to sit
     * above {@code f} of every point on the cells behind it and below {@code f}
     * of every point on the cells in front, and those two bounds are symmetric
     * about {@code f} of the cell's centre. In top-down the window between them
     * is a whole tile deep, so the middle of the cell's southern edge — what
     * this used to be — sat exactly on its rim and survived anyway. In
     * isometric, which folds both world axes into the screen row, the window
     * closes to a single value, and the centre is it.
     */
    public static int tileDepth(Camera camera, int tileSize, int col, int row) {
        return camera.worldToScreenY((col + 0.5) * tileSize, (row + 0.5) * tileSize);
    }

    /**
     * The depth of a body whose feet are at ({@code footX}, {@code footY}) —
     * {@link #tileDepth} of the cell it is standing on.
     */
    public static int standingDepth(Camera camera, int tileSize, double footX, double footY) {
        return tileDepth(camera, tileSize,
                (int) Math.floor(footX / tileSize), (int) Math.floor(footY / tileSize));
    }

    /**
     * The height key a body at {@code z} sorts on — how many layers above the
     * floor it is standing, rounded down.
     *
     * <p>The companion to {@link #standingDepth}, and the same rule a column
     * queues itself with: a body standing on a column of height {@code h} is at
     * layer {@code h - 1}, which is the number that column passes, so the body
     * and the block it stands on tie on this key and the tie-break puts the
     * body in front. Anything lower in the same cell sorts behind both.
     */
    public static int standingLayer(Level level, double z) {
        double block = level.blockHeight();
        return block <= 0 ? 0 : (int) Math.floor(z / block + 1e-6);
    }

    /**
     * The cells between the camera and a body — what has to be seen through
     * for that body to be visible at all.
     *
     * <p><b>Without this, walking indoors is walking into nothing.</b> A roof
     * is geometry nearer the camera than the player under it, so the painter
     * draws it over them and correctly: they <em>are</em> behind it. That is
     * the whole reason overhangs are a job of their own rather than a flag —
     * the moment a column may have a gap in it, the engine can hide the player
     * from the player.
     *
     * <p>The march is {@link #pick}'s, run backwards: from the body's own cell,
     * step toward the camera one layer at a time and collect every column that
     * reaches the ray. Bounded by the level's height rather than by its size,
     * and it costs one pass per body rather than a search.
     *
     * @return cell keys ({@code col | row << 32}) to draw see-through this
     *         frame, empty when nothing is in the way
     */
    public static java.util.Set<Long> cutaway(Camera camera, Level level,
                                              double footX, double footY, double z) {
        if (!level.layered() || !level.verticality()) return java.util.Set.of();
        int ts = level.tileSize;
        double[] step = camera.inversePlanar(0,
                BLOCK_HEIGHT * ts * camera.liftScale());
        int bodyCol = (int) Math.floor(footX / ts), bodyRow = (int) Math.floor(footY / ts);
        int standing = (int) Math.floor(z / Math.max(1e-9, level.blockHeight()) + 1e-6);
        java.util.Set<Long> hidden = null;
        // A block at cell T and layer L draws over the floor point T − L·step,
        // so the cells drawn over a body at cell B and layer s are B + m·step
        // for m = 1 upward. Along the step, not against it — the sign that
        // decides whether a cutaway finds the roof over your head or the empty
        // ground behind you.
        //
        // <b>And a band of layers at each one, not a single layer.</b> A body
        // is a billboard taller than the tile it stands on, so it reaches into
        // the screen space of the cells north of it; clearing only what the ray
        // through its feet meets leaves everything above the ankles still
        // behind roof. A block at (T, L) overlaps a body {@code BODY_LAYERS}
        // tall exactly when L runs from m + s to m + s + BODY_LAYERS.
        for (int m = 1; m <= level.tallestColumn(); m++) {
            int col = bodyCol + (int) Math.round(step[0] * m / ts);
            int row = bodyRow + (int) Math.round(step[1] * m / ts);
            if (!inside(level, col, row)) break;
            int from = standing + m;
            int to = Math.min(level.layerCount() - 1, from + BODY_LAYERS);
            for (int layer = from; layer <= to; layer++) {
                if (!level.solidAt(col, row, layer)) continue;
                if (hidden == null) hidden = new java.util.HashSet<>(16);
                // The cell and the ring around it. A body's billboard is
                // narrower than an isometric diamond but not confined to one:
                // it laps into the screen space of the cells beside and above,
                // so a cutaway that clears only the cells the ray passes
                // through leaves a player visible in top-down and still buried
                // in isometric. Clearing the ring is also what a cutaway looks
                // like in practice — a hole in the roof that moves with you,
                // rather than a single tile winking out.
                for (int dc = -1; dc <= 1; dc++) {
                    for (int dr = -1; dr <= 1; dr++) {
                        hidden.add(((col + dc) & 0xFFFFFFFFL) | ((long) (row + dr) << 32));
                    }
                }
                break;
            }
        }
        return hidden == null ? java.util.Set.of() : hidden;
    }

    /**
     * What the cursor is pointing at: a cell, the layer of it under the
     * pointer, and which face of that block the ray struck.
     *
     * @param col   the column hit
     * @param row   the row hit
     * @param layer the layer of the block hit — {@code 0} for a bare floor tile
     * @param top   whether the ray landed on the block's top face rather than
     *              on one of its sides
     * @param placeCol the column a block placed against this aim goes in
     * @param placeRow the row it goes in — the aimed cell for a top face, the
     *              cell on the near side of the face for a side. This is the
     *              rule every block game uses and the only one that is not
     *              maddening: point at the top of a wall and you build higher,
     *              point at its face and you build outward from it
     */
    public record Aim(int col, int row, int layer, boolean top,
                      int placeCol, int placeRow) {

        /** The layer a block placed against this aim would occupy. */
        public int placeLayer(Level level) {
            return level.stackHeight(placeCol, placeRow);
        }
    }

    /**
     * The cell under a screen point, accounting for how tall the terrain is —
     * {@code HEIGHT_PLAN.md} R7.
     *
     * <p><b>Why a march and not an inverse.</b> {@link Camera#screenToWorld}
     * inverts the ground projection, which answers "which point of the
     * <em>floor</em> is under this pixel". That was the whole question while
     * the floor was all there was. With columns standing on it the answer is
     * wrong in the way players notice first: the cursor over the <em>side</em>
     * of a tower resolves to the floor cell behind the tower, so mining hits a
     * block one or more cells from the one under the mouse, and the taller the
     * tower the further off it is.
     *
     * <p>So the ray is walked instead. A screen point plus the projection's
     * view direction is a line through the world; stepping it cell by cell and
     * asking each column whether it reaches the ray gives the first thing hit.
     * The march is exact rather than approximate because the projection is
     * parallel and the geometry is unit boxes: a cell's column occupies a known
     * height, and the ray's height over that cell is a linear function of how
     * far along it we are.
     *
     * <p>Walking <em>toward</em> the camera from the floor point is what makes
     * this cheap. The floor point is where the ray leaves the world, so
     * everything the ray could have hit is between there and the viewer — a
     * bounded walk of at most the level's height in cells, not a search.
     *
     * @return what is under the point, or {@code null} when the ray leaves the
     *         level without meeting anything
     */
    public static Aim pick(Camera camera, Level level, int screenX, int screenY) {
        double[] floor = camera.screenToWorld(screenX, screenY);
        int ts = level.tileSize;
        double lift = BLOCK_HEIGHT * ts;
        if (lift <= 0 || !level.layered()) {
            int col = (int) Math.floor(floor[0] / ts), row = (int) Math.floor(floor[1] / ts);
            return inside(level, col, row) ? new Aim(col, row, 0, true, col, row) : null;
        }
        // Where a block one layer up has to sit to draw over a given floor
        // point. A block at cell T whose top is n layers up projects to the
        // planar point of T lifted by n lifts, so the floor point under the
        // cursor is T shifted back by n of these — and T is the cursor's floor
        // point shifted forward by n. Taken through the projection's own
        // inverse, so it turns with the camera without this method knowing a
        // camera can turn.
        double[] step = camera.inversePlanar(0, lift * camera.liftScale());
        // From the top down: the ray meets the highest thing first, and the
        // first column it meets is what the cursor is on. Marching up from the
        // floor instead would return the floor every time, since the floor
        // point under the cursor is itself a floored cell.
        int lastCol = Integer.MIN_VALUE, lastRow = Integer.MIN_VALUE;
        for (int n = level.tallestColumn(); n >= 0; n--) {
            int col = (int) Math.floor((floor[0] + step[0] * n) / ts);
            int row = (int) Math.floor((floor[1] + step[1] * n) / ts);
            if (!inside(level, col, row)) continue;
            int height = level.stackHeight(col, row);
            // Over this cell the ray is n blocks above the floor, so it strikes
            // the column exactly when the column stands at least that tall.
            if (height >= 1 && height - 1 >= n) {
                boolean top = height - 1 == n;
                // A top face builds upward in the same cell. A side face builds
                // outward, into the cell the ray was passing through when it
                // met that face — which the march is already holding, because
                // it looked at it one step ago.
                int placeCol = top || lastCol == Integer.MIN_VALUE ? col : lastCol;
                int placeRow = top || lastRow == Integer.MIN_VALUE ? row : lastRow;
                return new Aim(col, row, height - 1, top, placeCol, placeRow);
            }
            lastCol = col;
            lastRow = row;
        }
        return null;
    }

    private static boolean inside(Level level, int col, int row) {
        return col >= 0 && row >= 0 && col < level.width && row < level.height;
    }

    /**
     * The crack overlay on the block being held-mined at (col,row), spreading
     * with {@code progress} in [0,1].
     *
     * <p>Sized and placed from the block's <em>projected quad</em> rather than
     * from its world size, because a block on screen is neither the shape nor
     * the size its tile is in the world. A tile's opposite corners land on the
     * same screen column in isometric — the diamond's left and right corners
     * are the other pair — so measuring the cell as the gap between them gave
     * a width of zero there; and the diamond is twice as wide as it is tall, so
     * measuring it along one axis and spreading the cracks evenly gave a
     * pattern at twice the block's height. Both extents are taken separately.
     * The block under the tool is also the top of the stack, which stands above
     * its own floor tile, so the cracks rise with it rather than appearing on
     * the floor beside the wall being mined.
     */
    public static void drawMiningCracks(DrawTarget target, Camera camera, Level level,
                                        int col, int row, double progress) {
        if (progress <= 0.01) return;
        int tileSize = level.tileSize;
        int[] xs = new int[4], ys = new int[4], corner = new int[2];
        double wx = col * (double) tileSize, wy = row * (double) tileSize;
        double[][] cs = {{wx, wy}, {wx + tileSize, wy},
                {wx + tileSize, wy + tileSize}, {wx, wy + tileSize}};
        int lift = liftPixels(camera, tileSize)
                * Math.max(0, level.stackHeight(col, row) - 1);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long sumX = 0, sumY = 0;
        for (int i = 0; i < 4; i++) {
            camera.worldToScreen(cs[i][0], cs[i][1], corner);
            xs[i] = corner[0];
            ys[i] = corner[1] - lift;
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
            sumX += xs[i];
            sumY += ys[i];
        }
        int cx = (int) (sumX / 4), cy = (int) (sumY / 4);
        // The cracks are measured along each screen axis separately, because a
        // block is not the same size along both: an isometric tile projects to
        // a diamond twice as wide as it is tall, and a pattern scaled to that
        // width alone came out at twice the block's height. Spreading it over
        // the quad's own half-extents keeps it inside whatever shape the
        // projection made of the block — a circle on a square tile, a diamond's
        // ellipse on a diamond — at the same proportions in both.
        double halfW = Math.max(2, (maxX - minX) / 2.0);
        double halfH = Math.max(2, (maxY - minY) / 2.0);

        int crackArgb = new Color(20, 16, 12, 200).getRGB();
        float thickness = (float) Math.max(1, Math.min(halfW, halfH) / 11);
        int cracks = 2 + (int) (progress * 6);
        for (int i = 0; i < cracks; i++) {
            double a = i * (Math.PI * 2 / 8) + (col * 3 + row * 7) % 7 * 0.4;
            double reach = 0.2 + progress * 0.42;
            int mx = cx + (int) (Math.cos(a) * reach * halfW * 1.1);
            int my = cy + (int) (Math.sin(a) * reach * halfH * 1.1);
            target.drawLine(cx, cy, mx, my, crackArgb, thickness);
            target.drawLine(mx, my, mx + (int) (Math.cos(a + 0.6) * reach * halfW * 0.9),
                    my + (int) (Math.sin(a + 0.6) * reach * halfH * 0.9),
                    crackArgb, thickness);
        }
    }

    /** Lets a caller draw over a finished top face (the open container lid). */
    public interface CellDecorator {
        /**
         * Draw over the top face just painted at (col,row). {@code block} is
         * the block that face belongs to — the floor's or, on a stacked cell,
         * the raised one, so a caller can tell which of the two it is looking
         * at. {@code xs}/{@code ys} hold the face's projected corners in
         * top-left, top-right, bottom-right, bottom-left order.
         */
        void afterTop(DrawTarget target, int col, int row, int[] xs, int[] ys,
                      Block block, Color color);
    }

    /** A hold-to-mine stroke in progress: the cell, and how far along it is. */
    public record Mining(int col, int row, double progress) {}

    /**
     * Paint the terrain inside {@code bounds} ({@code {col0, row0, col1, row1}}).
     *
     * <p>The floor is drawn immediately; stacked blocks are queued into
     * {@code raised} so they sort against everything else standing on the
     * floor. The caller flushes that pass once it has added its own sprites.
     */
    public static void draw(DrawTarget target, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor) {
        draw(target, level, camera, bounds, animClock, raised, decor, null);
    }

    /**
     * Paint the terrain, with the crack overlay of a hold-to-mine stroke.
     *
     * <p>The cracks go into the same queue as the blocks, at the depth of the
     * cell being mined, because they belong to a block rather than to the
     * screen. Drawn outside the queue they were painted before it was flushed,
     * and every stacked block then landed on top of them: in a top-down level
     * the mined block covered its own cracks, and in an isometric one the walls
     * to the south stood up over the cell to their north-west and covered even
     * a floor tile's.
     */
    public static void draw(DrawTarget target, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor,
                            Mining mining) {
        draw(target, level, camera, bounds, animClock, raised, decor, mining, null);
    }

    /**
     * Paint the terrain, reusing {@code cache}'s baked floor chunks where it
     * can.
     *
     * <p>The cache is skipped outright when a {@link CellDecorator} is in play.
     * A decorator draws something animated over a finished top face — the
     * swinging lid of an open container — and baking that into a chunk image
     * would freeze it mid-swing until the level was edited. A caller that has
     * nothing to decorate should pass {@code null} rather than a decorator
     * that does nothing, which is what lets the floor be cached at all.
     */
    public static void draw(DrawTarget target, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor,
                            Mining mining, TerrainCache cache) {
        draw(target, level, camera, bounds, animClock, raised, decor, mining, cache,
                java.util.Set.of());
    }

    /**
     * One frame's terrain with {@code seeThrough} cells drawn as ghosts — the
     * columns {@link #cutaway} found between the camera and a body.
     */
    public static void draw(DrawTarget target, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor,
                            Mining mining, TerrainCache cache,
                            java.util.Set<Long> seeThrough) {
        Pass pass = new Pass(target, level, camera, animClock, decor);
        pass.seeThrough = seeThrough == null ? java.util.Set.of() : seeThrough;
        pass.run(bounds, raised, mining, decor == null ? cache : null);
    }

    /** One frame's terrain, with the scratch state a sweep over the cells needs. */
    private static final class Pass {
        /**
         * The pass draws through this and nothing else — there is no
         * Graphics2D left in here. One target for the whole sweep, because the
         * loop runs over every visible cell (around two thousand at 1080p) and
         * anything allocated inside it would cost more than the drawing.
         */
        private DrawTarget target;
        private final Level level;
        /**
         * Not final: baking a chunk projects through a lattice-aligned camera
         * rather than the frame's, so the sweep runs with that one swapped in.
         */
        private Camera camera;
        private final double animClock;
        private final CellDecorator decor;
        /**
         * Whether a tile's texture can be blitted as an upright rectangle
         * instead of warped through its own edge vectors.
         *
         * <p>This was {@code !iso} — a proxy for "the projection is
         * orthographic", which was the same thing for as long as the only
         * non-upright projection was the diamond. A turned top-down view is
         * orthographic and not upright, and the proxy would have blitted its
         * ground textures unrotated: the geometry turns and the texture painted
         * on it does not, so a road drawn on the floor would point the wrong
         * way after a quarter turn while its tile turned correctly underneath
         * it. Measured from the projection instead, and exact at rest, because
         * {@link Camera#setYaw} makes the zeros exact.
         */
        private final boolean flatBlit;
        private final boolean layered;
        private final int tileSize;
        private final double lift;
        private final double shadowX, shadowY;
        /**
         * How far outside the viewport a cell may still reach into it.
         *
         * <p>A cell is rejected on its own projected corners, not on its centre,
         * so this only has to cover what a cell draws <em>beyond</em> those
         * corners: the lift a stacked block rises by, the offset its shadow
         * falls at, and a tile's worth of slack for a decorator drawing over a
         * top face. Being generous costs a few cells at the edge of the screen;
         * being mean costs a wall that pops in when its base leaves the view.
         */
        private final int cullMarginSide;
        private final int cullMarginUp;
        private final int cullMarginDown;
        /**
         * Whether cells outside the viewport are skipped.
         *
         * <p>Off while baking a chunk, and that is not a detail: a bake projects
         * through a camera positioned so the chunk lands on its own lattice
         * rather than on the screen, so "outside the viewport" means nothing
         * there. A chunk is also deliberately baked whole — see
         * {@link TerrainCache} — which is the opposite of what culling is for.
         */
        private boolean culling = true;
        /** Cells to draw see-through this frame; see {@link #cutaway}. */
        private java.util.Set<Long> seeThrough = java.util.Set.of();

        // Projected corners of the cell being drawn: top-left, top-right,
        // bottom-right, bottom-left — the order every texture path expects.
        private final int[] xs = new int[4];
        private final int[] ys = new int[4];
        private final int[] corner = new int[2];
        private final int[] faceX = new int[4];
        private final int[] faceY = new int[4];
        /** Per-frame cache: texture key -> frame (absent value = procedural). */
        private final Map<String, BufferedImage> skins = new HashMap<>();
        /**
         * Per-frame cache of {@link Skins#animated}, and the flag the current
         * sweep sets when it resolves one.
         *
         * <p>{@link TerrainCache} needs to know whether a region it just baked can
         * go stale as time passes, and the painter is the only thing that knows —
         * the cache never sees a texture key. Assuming "maybe" for every chunk is
         * what had every chunk in every level invalidated twelve times a second;
         * see that class's note on {@code ANIM_FPS}.
         */
        private final Map<String, Boolean> animatedKeys = new HashMap<>();
        private boolean sawAnimated;

        Pass(DrawTarget target, Level level, Camera camera, double animClock,
             CellDecorator decor) {
            this.target = target;
            this.level = level;
            this.camera = camera;
            this.animClock = animClock;
            this.decor = decor;
            double[] alongX = camera.planarDelta(level.tileSize, 0);
            double[] alongY = camera.planarDelta(0, level.tileSize);
            this.flatBlit = alongX[1] == 0 && alongY[0] == 0
                    && alongX[0] > 0 && alongY[1] > 0;
            this.layered = level.layered();
            this.tileSize = level.tileSize;
            // Height is drawn along whichever axis this space lifts things
            // along, so a raised block rises the same way a jumping player does.
            this.lift = liftPixels(camera, tileSize);
            // Shadows fall away from wherever the level put its sun. The
            // bearing is a compass direction on the world plane, so it is
            // projected like anything else on that plane — which is what keeps
            // a shadow pointing the same way on the ground when the same level
            // is drawn as a diamond instead of a square.
            double bearing = Math.toRadians(level.lightAngle);
            double reach = tileSize * SHADOW_REACH;
            // North is -y and east is +x on the world plane; the shadow runs
            // opposite the sun.
            double awayX = -Math.sin(bearing) * reach;
            double awayY = Math.cos(bearing) * reach;
            double[] offset = camera.planarDelta(awayX, awayY);
            this.shadowX = offset[0] * camera.zoom;
            this.shadowY = offset[1] * camera.zoom;
            // How far a cell's drawing reaches beyond its own quad, per side.
            //
            // <b>A column reaches up the screen and nowhere else</b>, so only
            // the margin below the viewport grows with height — cells whose
            // quads are off the bottom whose columns still paint into view. A
            // uniform margin would have cost the same slack on all four sides,
            // which is most of what C-series culling buys back at a turned
            // heading. The shadow reaches in whichever direction the level's
            // sun throws it, and both scale with how tall the level is known to
            // get (Level.tallestColumn).
            int tallest = level.tallestColumn();
            double stories = Math.max(1, tallest - 1);
            double slack = tileSize * camera.zoom;
            this.cullMarginSide = (int) Math.ceil(Math.abs(shadowX) * stories + slack);
            this.cullMarginDown = (int) Math.ceil(Math.abs(shadowY) * stories + slack);
            this.cullMarginUp = (int) Math.ceil(lift * stories
                    + Math.abs(shadowY) * stories + slack);
        }

        void run(int[] bounds, DepthPass raisedPass, Mining mining, TerrainCache cache) {
            Path2D.Double shadows = layered ? new Path2D.Double() : null;
            if (cache != null && TerrainCache.enabled()
                    && TerrainCache.faithfulIn(camera)) {
                // The floor comes out of the cache as a handful of blits. The
                // shadows still have to be gathered live, because they are cast
                // by blocks that are not in the cache and must land under the
                // actors rather than inside a chunk image.
                cache.drawFloor(target, level, camera, bounds, animClock, this::renderChunk);
                cache.endFrame();
                if (shadows != null) gatherShadows(bounds, shadows);
            } else {
                sweepFloor(bounds, shadows);
            }
            if (shadows != null) {
                // One fill for every shadow in the frame: overlapping casters
                // would otherwise stack their alpha and band the floor.
                target.fillShape(shadows, SHADOW);
                queueRaised(bounds, raisedPass);
            }
            // The cracks go in last, so among everything queued at the mined
            // cell's depth — its own stacked block above all — they are the
            // part drawn on top. Blocks nearer the viewer still cover them,
            // which is right: they are in front of the block being mined.
            if (mining != null && mining.progress() > 0.01) {
                // Queued at the mined column's own height as well as its depth,
                // so the block it is breaking cannot sort above its own cracks
                // — the two tie on every key and arrival order, which puts the
                // cracks last, decides.
                int height = Math.max(1, level.stackHeight(mining.col(), mining.row()));
                raisedPass.at(baseDepth(mining.col(), mining.row()), height - 1,
                        DepthPass.TERRAIN, () ->
                        drawMiningCracks(target, camera, level, mining.col(), mining.row(),
                                mining.progress()));
            }
        }

        /** Draw every floor cell in the bounds, gathering shadows as it goes. */
        private void sweepFloor(int[] bounds, Path2D.Double shadows) {
            for (int r = bounds[1]; r <= bounds[3]; r++) {
                for (int c = bounds[0]; c <= bounds[2]; c++) {
                    int id = level.tileAt(c, r);
                    if (id <= 0) continue;
                    project(c, r);
                    if (offScreen()) continue;
                    drawFloor(c, r, id);
                    if (shadows != null) {
                        int height = level.stackHeight(c, r);
                        if (height > 1) addShadow(shadows, height);
                    }
                }
            }
        }

        /** The shadow shapes alone, for when the floor came from the cache. */
        private void gatherShadows(int[] bounds, Path2D.Double shadows) {
            for (int r = bounds[1]; r <= bounds[3]; r++) {
                for (int c = bounds[0]; c <= bounds[2]; c++) {
                    int height = level.stackHeight(c, r);
                    if (height <= 1) continue;
                    project(c, r);
                    if (offScreen()) continue;
                    addShadow(shadows, height);
                }
            }
        }

        /**
         * Whether the cell just projected into {@link #xs}/{@link #ys} is far
         * enough outside the viewport to draw nothing.
         *
         * <p><b>Why a per-cell test earns its keep only now.</b> The bounds a
         * scene hands this painter are the axis-aligned box around the four
         * viewport corners carried back into the world. Unturned, that box is
         * the view: it holds 1.31× the cells actually on screen, which is the
         * one-cell margin and not worth a test to save. Turned, the view is a
         * rotated rectangle inside its own bounding box, and the box holds up to
         * <b>2.47×</b> — measured at 45°, where nearly half of every terrain
         * sweep was cells behind the player's shoulder. Isometric pays 2.28× of
         * it at rest and always did.
         *
         * <p>It is also cheapest exactly where it is worth most: the headings
         * with the worst ratio are the ones {@link TerrainCache#faithfulIn}
         * refuses to bake, so the sweep it halves is the live one.
         */
        private boolean offScreen() {
            if (!culling) return false;
            int minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
            for (int i = 1; i < 4; i++) {
                if (xs[i] < minX) minX = xs[i];
                if (xs[i] > maxX) maxX = xs[i];
                if (ys[i] < minY) minY = ys[i];
                if (ys[i] > maxY) maxY = ys[i];
            }
            return maxX < -cullMarginSide || minX > camera.viewportWidth + cullMarginSide
                    || maxY < -cullMarginDown
                    || minY > camera.viewportHeight + cullMarginUp;
        }

        /**
         * Paint one chunk's floor into a target of its own — the same per-cell
         * work the live sweep does, aimed at a chunk image instead of the
         * screen. Swapping the target for the duration is what lets one
         * implementation serve both.
         */
        private boolean renderChunk(DrawTarget into, Camera with,
                                    int col0, int row0, int col1, int row1) {
            DrawTarget previousTarget = target;
            Camera previousCamera = camera;
            boolean previouslySaw = sawAnimated;
            target = into;
            camera = with;
            culling = false;
            sawAnimated = false;
            try {
                sweepFloor(new int[]{col0, row0, col1, row1}, null);
                return sawAnimated;
            } finally {
                target = previousTarget;
                camera = previousCamera;
                culling = true;
                // Restore rather than clear: a sweep nested inside another one
                // must not tell the outer sweep it saw nothing.
                sawAnimated = previouslySaw || sawAnimated;
            }
        }

        /** {@link TerrainPainter#tileDepth} against this pass's camera. */
        private int baseDepth(int col, int row) {
            return tileDepth(camera, tileSize, col, row);
        }

        /** The flat floor tile — the top face of the block lying in the ground layer. */
        private void drawFloor(int col, int row, int id) {
            Block block = level.blockAt(col, row);
            Color color = level.colorFor(id);
            BufferedImage skin = block == null ? null
                    : layered ? face(block.topTextureKey(), block.textureKey())
                    : skin(block.textureKey());
            if (skin != null) {
                TilePainter.drawTexture(target, skin, xs, ys, flatBlit);
            } else {
                target.fillPolygon(xs, ys, 4, color);
                if (block != null && block.liquid()) {
                    // Liquids render translucent with a bright surface line.
                    if (level.liquidAt(col, row - 1) == null) {
                        target.drawLine(xs[0], ys[0], xs[1], ys[1], LIQUID_SURFACE);
                    }
                } else {
                    target.drawPolygon(xs, ys, 4, color.darker());
                }
            }
            if (decor != null) decor.afterTop(target, col, row, xs, ys, block, color);
        }

        /**
         * Add this cell's cast shadow to the frame's single shadow shape,
         * thrown as far as the column is tall.
         *
         * <p><b>Not raycast, and that is a decision rather than an oversight.</b>
         * The offset scales with the caster's height and stops there: a tower's
         * shadow falls on the floor even where a taller neighbour should have
         * caught it, and two towers' shadows merge rather than one falling
         * across the other. A real cast is affordable on a heightfield — march
         * each column along the light bearing until the accumulated drop meets
         * a taller column, which is O(reach) per column with no allocation —
         * but it is a shadow system, and this step's job is only that height
         * reads. Recorded in {@code HEIGHT_PLAN.md} Appendix B.
         */
        private void addShadow(Path2D.Double into, int height) {
            double reach = Math.max(1, height - 1);
            double dx = shadowX * reach, dy = shadowY * reach;
            into.moveTo(xs[0] + dx, ys[0] + dy);
            for (int i = 1; i < 4; i++) into.lineTo(xs[i] + dx, ys[i] + dy);
            into.closePath();
        }

        /**
         * Queue every stacked block in view into the pass it shares with the
         * actors, at the screen row of its base — the same measure a sprite's
         * feet are sorted by, so a wall and a player standing beside it agree
         * on which is nearer.
         */
        private void queueRaised(int[] bounds, DepthPass raisedPass) {
            for (int r = bounds[1]; r <= bounds[3]; r++) {
                for (int c = bounds[0]; c <= bounds[2]; c++) {
                    int layers = level.layerCount();
                    if (layers <= 1) continue;
                    project(c, r);
                    if (offScreen()) continue;
                    int col = c, row = r;
                    // Every occupied run in the column, wherever it sits —
                    // walking the layers rather than the run from the ground,
                    // because a bridge deck is precisely the geometry that run
                    // does not reach (O1).
                    //
                    // One queue entry per run, banded by the layer it stands
                    // on. A column with no gaps is one band and sorts exactly
                    // as it did; a deck is a band of its own, four layers up,
                    // and sorts against an actor walking under it rather than
                    // with the ground beneath them (O4).
                    for (int base = 1; base < layers; ) {
                        int id = level.tileAt(col, row, base);
                        if (id <= 0) {
                            base++;
                            continue;
                        }
                        int top = base + 1;
                        while (top < layers && level.tileAt(col, row, top) == id) top++;
                        Block block = level.blocks.get(id);
                        int from = base, to = top;
                        if (block != null) {
                            raisedPass.at(baseDepth(col, row), from - 1, DepthPass.TERRAIN,
                                    () -> drawRun(col, row, block, from, to));
                        }
                        base = top;
                    }
                }
            }
        }

        /**
         * One column of raised blocks: the side faces its lift exposes, then
         * the top face of whatever is on top.
         *
         * <p><b>Drawn as runs of one material rather than block by block.</b> A
         * column of eight stones is one extrusion eight blocks tall, not eight
         * extrusions of one — which is a correctness matter and not only a
         * speed one. Each face is placed by rounding its own lift to whole
         * pixels, and rounding eight times accumulates: at a lift of 17.6 px,
         * eight blocks drawn one at a time land at {@code 8 × round(17.6) =
         * 144} while the column's true top is {@code round(8 × 17.6) = 141}, and
         * the three pixels of difference open as seams down the side. Rounding
         * happens once per run, against the run's own top.
         *
         * <p>Runs also do the culling. Only the topmost block's <em>top</em> is
         * visible — the others have a block sitting on them — so a column that
         * is all one material is one quad and one set of side faces however
         * deep it is.
         */
        private void drawColumn(int col, int row, int height) {
            int runTop = height;
            while (runTop > 1) {
                int id = level.tileAt(col, row, runTop - 1);
                int runBase = runTop - 1;
                while (runBase > 1 && level.tileAt(col, row, runBase - 1) == id) runBase--;
                Block block = level.blocks.get(id);
                if (block != null) drawRun(col, row, block, runBase, runTop);
                runTop = runBase;
            }
        }

        /**
         * One run of identical blocks in a column, occupying layers
         * {@code [base, top)} — its exposed side faces and, when it is the
         * column's own top, its top face.
         */
        private void drawRun(int col, int row, Block block, int base, int top) {
            project(col, row);
            Color color = block.color();
            if (!seeThrough.isEmpty()
                    && seeThrough.contains((col & 0xFFFFFFFFL) | ((long) row << 32))) {
                // Ghosted: the shape stays legible so a creator can still read
                // the building they are inside, and the body under it shows
                // through. Drawn as an outline rather than at reduced alpha
                // because a translucent fill still hides a sprite behind two
                // of them, and a player indoors is behind several.
                drawGhost(base, top, color);
                return;
            }
            // Layer 0 is the floor tile itself, drawn flat by the floor pass,
            // so the block in layer 1 stands ON it at a lift of zero. A run
            // covering layers [base, top) therefore spans screen heights
            // [base - 1, top - 1) — the off-by-one that draws a whole column
            // floating one block above its own floor.
            int lower = (int) Math.round(lift * (base - 1));
            int upper = (int) Math.round(lift * (top - 1));
            // The face spans from this run's floor to its ceiling. Both ends are
            // rounded from the column's own base, so neighbouring runs share an
            // edge exactly and no seam can open between them.
            BufferedImage side = face(block.sideTextureKey(), block.textureKey());
            drawVisibleFaces(col, row, base, top, side, color);
            for (int i = 0; i < 4; i++) ys[i] -= upper;
            if (top >= level.layerCount() || level.tileAt(col, row, top) <= 0) {
                BufferedImage skin = face(block.topTextureKey(), block.textureKey());
                if (skin != null) {
                    TilePainter.drawTexture(target, skin, xs, ys, flatBlit);
                } else {
                    target.fillPolygon(xs, ys, 4, color);
                    target.drawPolygon(xs, ys, 4, color.darker());
                }
                if (decor != null) decor.afterTop(target, col, row, xs, ys, block, color);
            }
        }

        /**
         * The side faces of the block just projected into {@link #xs}/{@link #ys}
         * that the camera can see, in whichever direction it is looking.
         *
         * <p><b>Derived from the projected quad, not from the heading.</b> The
         * old code asked the perspective and named the faces: the diamond's two
         * lower edges in isometric, the southern one straight down. Both answers
         * are correct at rest and neither survives a turn, and the obvious
         * repair — a table of which faces each of the eight headings shows —
         * would need a row per heading per projection and would be wrong at
         * every angle in between, which is where a snap animation lives.
         *
         * <p>What decides it is not the heading but where the extruded face
         * ends up pointing, and the projected corners already know. A block
         * stands <em>up</em> the screen, so a side face is turned toward the
         * viewer exactly when its edge's outward normal points <em>down</em> the
         * screen — an ordinary back-face cull, done in two dimensions because
         * the extrusion is along a screen axis. It reproduces both old answers
         * exactly: one face in an unturned plan view, two in a diamond, and two
         * for a square tile turned an eighth, which is the same shape by then.
         *
         * <p><b>The quad's winding is assumed, and that assumption is the one
         * thing here worth a test of its own.</b> The outward normal of an edge
         * depends on which way round the quad reads on screen, and a normal
         * derived from the wrong winding points into the block: it draws the
         * faces turned away and hides the ones turned toward, which is the
         * "world is inside out" failure C4 warns about. It was written the
         * careful way first, measuring the winding per cell — and then measured
         * to be unreachable. A rotation has determinant +1 and the isometric
         * transform's is positive too, so no projection this camera can make
         * turns the corners round the other way; forcing the winding to a
         * constant passes every test in the suite. What guards it is therefore
         * an assertion rather than a branch — {@code TurnedTerrainTest} checks
         * every heading of both plan views winds the same way, so a projection
         * that ever mirrors fails there and names this method.
         */
        /**
         * Which world neighbour each edge of the projected quad faces.
         *
         * <p>The corners are projected in a fixed world order — {@code (c,r)},
         * {@code (c+1,r)}, {@code (c+1,r+1)}, {@code (c,r+1)} — so edge
         * {@code i}&rarr;{@code i+1} always separates this cell from the same
         * neighbour whatever the camera is doing. Where those edges <em>appear</em>
         * turns with the yaw; which cells they divide does not.
         */
        private static final int[] EDGE_DC = {0, 1, 0, -1};
        private static final int[] EDGE_DR = {-1, 0, 1, 0};

        private void drawVisibleFaces(int col, int row, int base, int top,
                                      BufferedImage side, Color color) {
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                // The y component of the edge's outward normal, (dy, -dx) for a
                // quad that reads clockwise on a screen whose y grows downward.
                if (-(xs[j] - xs[i]) <= 0) continue;
                // …and the neighbour on the far side of this edge hides
                // everything up to its own height. A plateau of identical
                // columns therefore draws its tops and the side faces of its
                // rim, and nothing in between — which is the difference between
                // a landscape costing its silhouette and costing its area.
                int neighbour = level.stackHeight(col + EDGE_DC[i], row + EDGE_DR[i]);
                int from = Math.max(base, neighbour);
                if (from >= top) continue;
                int lower = (int) Math.round(lift * (from - 1));
                int upper = (int) Math.round(lift * (top - 1));
                for (int k = 0; k < 4; k++) ys[k] -= lower;
                // Drawn from its higher end to its lower one, so the texture on
                // it keeps one orientation across the faces of a block and
                // across headings. This is the order the isometric and top-down
                // cases were written with by hand, and it reproduces both.
                boolean iFirst = ys[i] < ys[j] || (ys[i] == ys[j] && xs[i] < xs[j]);
                if (iFirst) {
                    drawFace(i, j, side, color, upper - lower);
                } else {
                    drawFace(j, i, side, color, upper - lower);
                }
                for (int k = 0; k < 4; k++) ys[k] += lower;
            }
        }

        /**
         * The wall between two floor corners and their lifted twins.
         * {@code from}/{@code to} index {@link #xs}/{@link #ys}; the face is
         * handed to the texture painter in the corner order it expects, with
         * the lifted edge on top.
         */
        private void drawFace(int from, int to, BufferedImage skin, Color color, int riseUp) {
            faceX[0] = xs[from]; faceY[0] = ys[from] - riseUp;
            faceX[1] = xs[to];   faceY[1] = ys[to] - riseUp;
            faceX[2] = xs[to];   faceY[2] = ys[to];
            faceX[3] = xs[from]; faceY[3] = ys[from];
            if (skin != null) {
                // Never a screen-aligned rectangle: a side is a parallelogram
                // in isometric and a plain band straight down, and the warping
                // path draws both from the quad's own edges.
                TilePainter.drawTexture(target, skin, faceX, faceY, false);
                drawLayerSeams(from, to, riseUp, shade(color, SIDE_SHADE * 0.7));
                return;
            }
            target.fillPolygon(faceX, faceY, 4, shade(color, SIDE_SHADE));
            target.drawPolygon(faceX, faceY, 4, shade(color, SIDE_SHADE * 0.75));
            drawLayerSeams(from, to, riseUp, shade(color, SIDE_SHADE * 0.72));
        }

        /**
         * A run drawn see-through: its silhouette and nothing else.
         */
        private void drawGhost(int base, int top, Color color) {
            int lower = (int) Math.round(lift * (base - 1));
            int upper = (int) Math.round(lift * (top - 1));
            Color ink = new Color(color.getRed(), color.getGreen(), color.getBlue(), 70);
            for (int i = 0; i < 4; i++) ys[i] -= upper;
            target.drawPolygon(xs, ys, 4, ink);
            for (int i = 0; i < 4; i++) ys[i] += upper - lower;
            target.drawPolygon(xs, ys, 4, ink);
            for (int i = 0; i < 4; i++) ys[i] -= lower;
        }

        /**
         * The lines where one block of a run meets the next.
         *
         * <p><b>What makes a tall column readable.</b> R1 draws a run of eight
         * identical stones as one extrusion, which is right for seams and for
         * speed and leaves a flat slab of colour eight blocks tall with nothing
         * in it to say so. A player cannot count what they cannot see the edges
         * of, and "how many blocks up is that ledge" is a question they ask
         * before every jump.
         *
         * <p>A line per boundary rather than a fill per block: the extrusion
         * stays one quad, the seams cost a stroke each, and — because every
         * boundary is measured from the column's own base with the same
         * rounding the run's ends use — a seam lands exactly where the block
         * edge is rather than a pixel off it.
         */
        private void drawLayerSeams(int from, int to, int riseUp, Color ink) {
            if (lift < 2) return;   // zoomed out far enough that seams are noise
            int blocks = (int) Math.round(riseUp / lift);
            for (int k = 1; k < blocks; k++) {
                int up = (int) Math.round(lift * k);
                target.drawLine(xs[from], ys[from] - up, xs[to], ys[to] - up, ink);
            }
        }

        /** Project cell (col,row) into {@link #xs}/{@link #ys}. */
        private void project(int col, int row) {
            double wx = col * (double) tileSize, wy = row * (double) tileSize;
            camera.worldToScreen(wx, wy, corner);
            xs[0] = corner[0]; ys[0] = corner[1];
            camera.worldToScreen(wx + tileSize, wy, corner);
            xs[1] = corner[0]; ys[1] = corner[1];
            camera.worldToScreen(wx + tileSize, wy + tileSize, corner);
            xs[2] = corner[0]; ys[2] = corner[1];
            camera.worldToScreen(wx, wy + tileSize, corner);
            xs[3] = corner[0]; ys[3] = corner[1];
        }

        /** The frame of a texture key, resolved at most once per frame. */
        private BufferedImage skin(String key) {
            Boolean animated = animatedKeys.get(key);
            if (animated == null) {
                animated = Skins.animated(key);
                animatedKeys.put(key, animated);
            }
            if (animated) sawAnimated = true;
            if (skins.containsKey(key)) return skins.get(key);
            BufferedImage img = Skins.frame(key, animClock);
            skins.put(key, img);
            return img;
        }

        /**
         * A plan-view face's frame, falling back to the block's one flat sheet.
         * Both faces are optional, and the fallback is here rather than in the
         * key so it catches a sheet assigned by hand as well as one found in
         * the pack — a block reskinned in the texture dialog looks reskinned
         * from above too, whether or not it was given faces of its own.
         */
        private BufferedImage face(String faceKey, String flatKey) {
            BufferedImage img = skin(faceKey);
            return img != null ? img : skin(flatKey);
        }

        /** {@code color} scaled toward black, keeping its alpha. */
        private static Color shade(Color color, double factor) {
            return new Color((int) (color.getRed() * factor),
                    (int) (color.getGreen() * factor),
                    (int) (color.getBlue() * factor), color.getAlpha());
        }
    }
}
