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
 * that the trees, mobs, dropped items and players share, queued at the screen
 * row of their base, so who is in front of whom is settled by where each thing
 * stands. Walking north behind a wall puts the wall in front of you; walking
 * south past it puts you in front of the wall — the same rule that already
 * decided whether you pass in front of a tree.
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
     * Tall enough that the side face reads as a wall at a glance, short enough
     * that a wall does not swallow the row of floor behind it.
     */
    public static final double BLOCK_HEIGHT = 0.55;

    /** How far a shadow reaches from its caster, as a fraction of a tile. */
    private static final double SHADOW_REACH = 0.34;

    /** The shadow itself — one flat translucent black, cast toward the south-east. */
    private static final Color SHADOW = new Color(0, 0, 0, 96);

    /** Darkening applied to a side face with no texture of its own. */
    private static final double SIDE_SHADE = 0.62;

    /** The bright line along the top of an exposed liquid surface. */
    private static final Color LIQUID_SURFACE = new Color(255, 255, 255, 90);

    private TerrainPainter() {}

    /**
     * Screen pixels a stacked block stands above its own floor tile, at this
     * camera's zoom and projection — zero where the format has no second layer
     * to lift. The editor's cursor preview borrows it so a brush about to build
     * a wall is drawn standing up, like the wall it is about to make.
     */
    public static int liftPixels(Camera camera, int tileSize) {
        return (int) Math.round(tileSize * BLOCK_HEIGHT * camera.zoom
                * PerspectiveSpace.of(camera.getPerspective()).screenLift());
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
        int lift = level.upperAt(col, row) > 0 ? liftPixels(camera, tileSize) : 0;
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
        new Pass(target, level, camera, animClock, decor)
                .run(bounds, raised, mining, decor == null ? cache : null);
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
        private final int cullMargin;
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
            this.cullMargin = (int) Math.ceil(lift + Math.abs(shadowX) + Math.abs(shadowY)
                    + tileSize * camera.zoom);
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
                raisedPass.at(baseDepth(mining.col(), mining.row()), () ->
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
                    if (shadows != null && level.upperAt(c, r) > 0) addShadow(shadows);
                }
            }
        }

        /** The shadow shapes alone, for when the floor came from the cache. */
        private void gatherShadows(int[] bounds, Path2D.Double shadows) {
            for (int r = bounds[1]; r <= bounds[3]; r++) {
                for (int c = bounds[0]; c <= bounds[2]; c++) {
                    if (level.tileAt(c, r) <= 0 || level.upperAt(c, r) <= 0) continue;
                    project(c, r);
                    if (offScreen()) continue;
                    addShadow(shadows);
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
            return maxX < -cullMargin || minX > camera.viewportWidth + cullMargin
                    || maxY < -cullMargin || minY > camera.viewportHeight + cullMargin;
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

        /**
         * The screen row a cell's base sits on — what everything standing on
         * the floor there is sorted by, sprites included.
         */
        private int baseDepth(int col, int row) {
            return camera.worldToScreenY((col + 0.5) * tileSize, (row + 1.0) * tileSize);
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

        /** Add this cell's cast shadow to the frame's single shadow shape. */
        private void addShadow(Path2D.Double into) {
            into.moveTo(xs[0] + shadowX, ys[0] + shadowY);
            for (int i = 1; i < 4; i++) into.lineTo(xs[i] + shadowX, ys[i] + shadowY);
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
                    int id = level.upperAt(c, r);
                    if (id <= 0 || level.tileAt(c, r) <= 0) continue;
                    Block block = level.blocks.get(id);
                    if (block == null) continue;
                    project(c, r);
                    if (offScreen()) continue;
                    int col = c, row = r;
                    raisedPass.at(baseDepth(col, row), () -> drawRaised(col, row, block));
                }
            }
        }

        /** One stacked block: the faces its lift exposes, then its top. */
        private void drawRaised(int col, int row, Block block) {
            project(col, row);
            Color color = block.color();
            BufferedImage side = face(block.sideTextureKey(), block.textureKey());
            drawVisibleFaces(side, color);
            for (int i = 0; i < 4; i++) ys[i] -= (int) Math.round(lift);
            BufferedImage top = face(block.topTextureKey(), block.textureKey());
            if (top != null) {
                TilePainter.drawTexture(target, top, xs, ys, flatBlit);
            } else {
                target.fillPolygon(xs, ys, 4, color);
                target.drawPolygon(xs, ys, 4, color.darker());
            }
            if (decor != null) decor.afterTop(target, col, row, xs, ys, block, color);
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
        private void drawVisibleFaces(BufferedImage side, Color color) {
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                // The y component of the edge's outward normal, (dy, -dx) for a
                // quad that reads clockwise on a screen whose y grows downward.
                if (-(xs[j] - xs[i]) <= 0) continue;
                // Drawn from its higher end to its lower one, so the texture on
                // it keeps one orientation across the faces of a block and
                // across headings. This is the order the isometric and top-down
                // cases were written with by hand, and it reproduces both.
                boolean iFirst = ys[i] < ys[j] || (ys[i] == ys[j] && xs[i] < xs[j]);
                if (iFirst) {
                    drawFace(i, j, side, color);
                } else {
                    drawFace(j, i, side, color);
                }
            }
        }

        /**
         * The wall between two floor corners and their lifted twins.
         * {@code from}/{@code to} index {@link #xs}/{@link #ys}; the face is
         * handed to the texture painter in the corner order it expects, with
         * the lifted edge on top.
         */
        private void drawFace(int from, int to, BufferedImage skin, Color color) {
            int riseUp = (int) Math.round(lift);
            faceX[0] = xs[from]; faceY[0] = ys[from] - riseUp;
            faceX[1] = xs[to];   faceY[1] = ys[to] - riseUp;
            faceX[2] = xs[to];   faceY[2] = ys[to];
            faceX[3] = xs[from]; faceY[3] = ys[from];
            if (skin != null) {
                // Never a screen-aligned rectangle: a side is a parallelogram
                // in isometric and a plain band straight down, and the warping
                // path draws both from the quad's own edges.
                TilePainter.drawTexture(target, skin, faceX, faceY, false);
                return;
            }
            target.fillPolygon(faceX, faceY, 4, shade(color, SIDE_SHADE));
            target.drawPolygon(faceX, faceY, 4, shade(color, SIDE_SHADE * 0.75));
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
