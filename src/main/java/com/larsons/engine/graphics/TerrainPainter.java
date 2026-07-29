package com.larsons.engine.graphics;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.world.Block;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
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
     * <p>Drawn from the block's <em>projected quad</em> rather than from its
     * world size, for two reasons. A tile's opposite corners land on the same
     * screen column in isometric — the diamond's left and right corners are the
     * other pair — so measuring the cell as the gap between them gave a width
     * of zero there and drew nothing at all. And the block under the tool is
     * the top of the stack, which stands above its own floor tile, so the
     * cracks have to rise with it or they appear on the floor beside the wall
     * being mined.
     */
    public static void drawMiningCracks(Graphics2D g, Camera camera, Level level,
                                        int col, int row, double progress) {
        if (progress <= 0.01) return;
        int tileSize = level.tileSize;
        int[] xs = new int[4], ys = new int[4], corner = new int[2];
        double wx = col * (double) tileSize, wy = row * (double) tileSize;
        double[][] cs = {{wx, wy}, {wx + tileSize, wy},
                {wx + tileSize, wy + tileSize}, {wx, wy + tileSize}};
        int lift = level.upperAt(col, row) > 0 ? liftPixels(camera, tileSize) : 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        long sumX = 0, sumY = 0;
        for (int i = 0; i < 4; i++) {
            camera.worldToScreen(cs[i][0], cs[i][1], corner);
            xs[i] = corner[0];
            ys[i] = corner[1] - lift;
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            sumX += xs[i];
            sumY += ys[i];
        }
        int cx = (int) (sumX / 4), cy = (int) (sumY / 4);
        int span = Math.max(4, maxX - minX);

        g.setColor(new Color(20, 16, 12, 200));
        g.setStroke(new BasicStroke(Math.max(1f, span / 22f)));
        int cracks = 2 + (int) (progress * 6);
        for (int i = 0; i < cracks; i++) {
            double a = i * (Math.PI * 2 / 8) + (col * 3 + row * 7) % 7 * 0.4;
            double len = (0.2 + progress * 0.42) * span;
            int mx = cx + (int) (Math.cos(a) * len * 0.55);
            int my = cy + (int) (Math.sin(a) * len * 0.55);
            g.drawLine(cx, cy, mx, my);
            g.drawLine(mx, my, mx + (int) (Math.cos(a + 0.6) * len * 0.45),
                    my + (int) (Math.sin(a + 0.6) * len * 0.45));
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
        void afterTop(Graphics2D g, int col, int row, int[] xs, int[] ys,
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
    public static void draw(Graphics2D g, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor) {
        draw(g, level, camera, bounds, animClock, raised, decor, null);
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
    public static void draw(Graphics2D g, Level level, Camera camera, int[] bounds,
                            double animClock, DepthPass raised, CellDecorator decor,
                            Mining mining) {
        new Pass(g, level, camera, animClock, decor).run(bounds, raised, mining);
    }

    /** One frame's terrain, with the scratch state a sweep over the cells needs. */
    private static final class Pass {
        private final Graphics2D g;
        private final Level level;
        private final Camera camera;
        private final double animClock;
        private final CellDecorator decor;
        private final boolean iso;
        private final boolean layered;
        private final int tileSize;
        private final double lift;
        private final double shadowX, shadowY;

        // Projected corners of the cell being drawn: top-left, top-right,
        // bottom-right, bottom-left — the order every texture path expects.
        private final int[] xs = new int[4];
        private final int[] ys = new int[4];
        private final int[] corner = new int[2];
        private final int[] faceX = new int[4];
        private final int[] faceY = new int[4];
        /** Per-frame cache: texture key -> frame (absent value = procedural). */
        private final Map<String, BufferedImage> skins = new HashMap<>();

        Pass(Graphics2D g, Level level, Camera camera, double animClock,
             CellDecorator decor) {
            this.g = g;
            this.level = level;
            this.camera = camera;
            this.animClock = animClock;
            this.decor = decor;
            this.iso = camera.getPerspective() == Perspective.ISOMETRIC;
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
        }

        void run(int[] bounds, DepthPass raisedPass, Mining mining) {
            Path2D.Double shadows = layered ? new Path2D.Double() : null;
            for (int r = bounds[1]; r <= bounds[3]; r++) {
                for (int c = bounds[0]; c <= bounds[2]; c++) {
                    int id = level.tileAt(c, r);
                    if (id <= 0) continue;
                    project(c, r);
                    drawFloor(c, r, id);
                    if (shadows != null && level.upperAt(c, r) > 0) addShadow(shadows);
                }
            }
            if (shadows != null) {
                // One fill for every shadow in the frame: overlapping casters
                // would otherwise stack their alpha and band the floor.
                g.setColor(SHADOW);
                g.fill(shadows);
                queueRaised(bounds, raisedPass);
            }
            // The cracks go in last, so among everything queued at the mined
            // cell's depth — its own stacked block above all — they are the
            // part drawn on top. Blocks nearer the viewer still cover them,
            // which is right: they are in front of the block being mined.
            if (mining != null && mining.progress() > 0.01) {
                raisedPass.at(baseDepth(mining.col(), mining.row()), () ->
                        drawMiningCracks(g, camera, level, mining.col(), mining.row(),
                                mining.progress()));
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
                TilePainter.drawTexture(g, skin, xs, ys, !iso);
            } else {
                g.setColor(color);
                g.fillPolygon(xs, ys, 4);
                if (block != null && block.liquid()) {
                    // Liquids render translucent with a bright surface line.
                    if (level.liquidAt(col, row - 1) == null) {
                        g.setColor(new Color(255, 255, 255, 90));
                        g.drawLine(xs[0], ys[0], xs[1], ys[1]);
                    }
                } else {
                    g.setColor(color.darker());
                    g.drawPolygon(xs, ys, 4);
                }
            }
            if (decor != null) decor.afterTop(g, col, row, xs, ys, block, color);
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
            if (iso) {
                // The diamond's two lower edges face the viewer: the one from
                // the right corner down to the bottom, and its mirror on the
                // left. The upper two are turned away and never drawn.
                drawFace(1, 2, side, color);
                drawFace(3, 2, side, color);
            } else {
                // Straight down: only the southern face is ever in view.
                drawFace(3, 2, side, color);
            }
            for (int i = 0; i < 4; i++) ys[i] -= (int) Math.round(lift);
            BufferedImage top = face(block.topTextureKey(), block.textureKey());
            if (top != null) {
                TilePainter.drawTexture(g, top, xs, ys, !iso);
            } else {
                g.setColor(color);
                g.fillPolygon(xs, ys, 4);
                g.setColor(color.darker());
                g.setStroke(new BasicStroke(1f));
                g.drawPolygon(xs, ys, 4);
            }
            if (decor != null) decor.afterTop(g, col, row, xs, ys, block, color);
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
                TilePainter.drawTexture(g, skin, faceX, faceY, false);
                return;
            }
            g.setColor(shade(color, SIDE_SHADE));
            g.fillPolygon(faceX, faceY, 4);
            g.setColor(shade(color, SIDE_SHADE * 0.75));
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(faceX, faceY, 4);
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
