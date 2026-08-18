package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.awt.BasicStroke;
import java.awt.Color;

/**
 * Draws a level's painted {@link SurfaceDecor.Placement}s — the per-face
 * block details (grass tufts, hanging moss, twigs…). Shared by the creative
 * editor and the play scene so surfaces look identical while editing and
 * playing.
 *
 * <p>Each placement is culled against the visible tile bounds, checked
 * against its visibility condition (open/closed face) and its host block
 * (mined host = nothing to sit on), then drawn in the face's own
 * {@link FaceFrame}, with a light {@code animClock} sway for organic styles.
 *
 * <p><b>Details are measured in tiles, not in screen pixels.</b> A block face
 * is an edge of the world grid, and where that edge lands on screen is the
 * camera's business: the top of a tile runs left-to-right in the orthographic
 * views but along the upper-right side of a diamond in isometric, where "away
 * from the block" is up <em>and to the right</em>. Painting blades straight up
 * the screen therefore tore every tuft off the block it belonged to as soon as
 * the level was seen isometrically. Styles are written once, along the face
 * and out of it, and the camera decides where that lands.
 *
 * <p><b>What a "face" is depends on how the level is seen.</b> Edge-on, in a
 * side view, a face is the line between two cells: grass clings to the top of
 * a block and moss hangs off its underside, both of them straddling that line.
 * Seen from above, the same line is a seam in the floor with nothing to cling
 * to — the block's visible surface is its <em>top</em>, so the face becomes a
 * strip of that surface and a detail is rooted inside the tile rather than out
 * on its edge. The face still says which strip: the north side of a tile in
 * top-down and isometric, rather than the north <em>edge</em> of it.
 *
 * <p>Which way a detail then hangs or stands up is
 * {@code com.larsons.engine.sim.PerspectiveSpace}'s answer rather than the
 * face's. Icicles fall down the screen in a side view whatever they cling to;
 * on a floor, gravity points at the viewer and draws as nothing, so height
 * becomes a lift up the screen — the same way this engine already draws a hop
 * in either plan view — and anything lying on the surface spreads back across
 * the block instead of off it.
 */
public final class SurfaceDecorPainter {

    private SurfaceDecorPainter() {}

    /** The green a flower's stem is drawn in, regardless of its petals. */
    private static final Color STEM_GREEN = new Color(70, 130, 60);

    /**
     * Draw one layer of a level's surface decor into {@code into} — its own
     * pass when the layer stands on its own, or the pass the level's actors
     * share when a plan view has to decide who is in front.
     *
     * @param bounds visible tile bounds as {col0, row0, col1, row1}
     * @param foreground which layer to draw this pass
     */
    public static void draw(DrawTarget target, Level level, Camera camera, int[] bounds,
                            boolean foreground, double animClock, DepthPass into) {
        if (level.surfaceDecor.isEmpty()) return;
        SurfaceDecorRegistry registry = SurfaceDecorRegistry.standard();
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        for (SurfaceDecor.Placement p : level.surfaceDecor) {
            if (p.foreground() != foreground) continue;
            if (p.col() < bounds[0] - 1 || p.col() > bounds[2] + 1
                    || p.row() < bounds[1] - 1 || p.row() > bounds[3] + 1) continue;
            if (!visible(level, p)) continue;
            SurfaceDecor def = registry.get(p.key());
            if (def == null) continue;
            FaceFrame frame = FaceFrame.of(level, camera, space, p);
            if (frame == null) continue; // sub-pixel at extreme zoom-out
            // Surface decor belongs to the block it is stuck to, so it sorts on
            // that cell and settles ties on the face's own row.
            into.at(TerrainPainter.tileDepth(camera, level.tileSize, p.col(), p.row()),
                    TerrainPainter.pointDepth(camera, frame.anchorX, frame.anchorY),
                    () -> drawOne(target, frame, p, def, animClock));
        }
    }

    /** Host block still there + the face's open/closed condition holds. */
    public static boolean visible(Level level, SurfaceDecor.Placement p) {
        if (level.tileAt(p.col(), p.row()) <= 0) return false;
        boolean neighbourSolid = level.solidAt(p.col() + p.face().dc, p.row() + p.face().dr);
        return switch (p.visibility()) {
            case ALWAYS -> true;
            case OPEN_FACE -> !neighbourSolid;
            case CLOSED_FACE -> neighbourSolid;
        };
    }

    private static void drawOne(DrawTarget target, FaceFrame f, SurfaceDecor.Placement p,
                                SurfaceDecor def, double animClock) {
        double tile = f.tile;

        // Sprite-sheet override (texture key surface/<key>): a creator's own
        // grass/spikes/etc. art replaces the procedural painter, drawn one
        // tile in size standing half a tile off the face it decorates — out
        // from the edge in a side view, up off the block in a plan view.
        java.awt.image.BufferedImage skin = Skins.frame("surface/" + def.key(), animClock);
        if (skin != null) {
            int size = Math.max(2, (int) Math.round(tile));
            int cx = f.xRise(0, 0.5), cy = f.yRise(0, 0.5);
            target.drawImage(skin, cx - size / 2, cy - size / 2, size, size);
            return;
        }

        double sway = Math.sin(animClock * 2.1 + p.col() * 1.7 + p.row() * 0.9);
        // Thinnest sliver that still shows at this zoom, in tiles.
        double thin = Math.max(0.045, 1.0 / tile);

        switch (def.style()) {
            case GRASS_TUFT -> {
                float stroke = Math.max(1f, (float) (tile / 16));
                int blades = 5;
                for (int i = 0; i < blades; i++) {
                    double u = (i - blades / 2.0 + 0.5) / (blades + 1);
                    double h = 0.28 + 0.14 * ((i * 7 + p.col()) % 3);
                    double lean = sway * 0.06 + (i - blades / 2.0) * 0.02;
                    target.drawLine(f.xRise(u, 0), f.yRise(u, 0),
                            f.xRise(u + lean, h), f.yRise(u + lean, h),
                            (i % 2 == 0 ? def.primary() : def.secondary()).getRGB(), stroke);
                }
            }
            case FLOWERS -> {
                // Explicit, where it used to inherit. This style set no stroke
                // of its own, so a flower stem was drawn at whatever width the
                // previous decoration happened to leave on the Graphics2D —
                // order-dependent, and not a width anyone chose. It matches the
                // grass blade it grows beside.
                float stroke = Math.max(1f, (float) (tile / 16));
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.24;
                    double h = 0.2 + 0.1 * i;
                    int tipX = f.xRise(u + sway * 0.04, h), tipY = f.yRise(u + sway * 0.04, h);
                    target.drawLine(f.xRise(u, 0), f.yRise(u, 0), tipX, tipY,
                            STEM_GREEN.getRGB(), stroke);
                    int fs = Math.max(2, (int) (tile * 0.14));
                    target.fillOval(tipX - fs / 2, tipY - fs / 2, fs, fs,
                            i == 1 ? def.secondary() : def.primary());
                }
            }
            case HANGING_MOSS -> {
                float stroke = Math.max(1f, (float) (tile / 14));
                for (int i = 0; i < 4; i++) {
                    double u = (i - 1.5) * 0.22;
                    double len = 0.3 + 0.18 * ((i + p.col()) % 3);
                    target.drawLine(f.xHang(u, 0), f.yHang(u, 0),
                            f.xHang(u + sway * 0.08, len), f.yHang(u + sway * 0.08, len),
                            (i % 2 == 0 ? def.primary() : def.secondary()).getRGB(), stroke);
                }
            }
            case ICICLES -> {
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.26;
                    double len = 0.2 + 0.16 * ((i + p.row()) % 3);
                    fillSpike(target, def.primary(), f, f.xHang(u, 0), f.yHang(u, 0),
                            f.hangX, f.hangY, thin, len);
                }
                fillBand(target, def.secondary(), f, f.hangX, f.hangY,
                        0.4, Math.max(0.06, thin));
            }
            case TWIGS -> {
                float stroke = Math.max(1f, (float) (tile / 14));
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.22;
                    double len = 0.2 + 0.12 * ((i + p.row()) % 3);
                    target.drawLine(f.xRise(u, 0), f.yRise(u, 0),
                            f.xRise(u - len * 0.4, len), f.yRise(u - len * 0.4, len),
                            (i % 2 == 0 ? def.primary() : def.secondary()).getRGB(), stroke);
                }
            }
            case MUSHROOMS -> {
                // Shelf fungus: a level cap on a short stem. Caps are drawn in
                // the plane the space keeps them level in — the screen where
                // gravity shows, the face's own frame on a floor.
                boolean onEdge = p.face() == SurfaceDecor.Face.LEFT
                        || p.face() == SurfaceDecor.Face.RIGHT;
                double stand = onEdge ? 0.08 : 0;
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.22;
                    int bx = f.xRise(u, stand), by = f.yRise(u, stand);
                    double cap = Math.max(3.0 / tile, 0.14 + 0.05 * (i % 2));
                    fillDome(target, def.primary(), f, bx, by, cap);
                    fillStem(target, def.secondary(), f, bx, by, cap);
                }
            }
            case COBWEB -> {
                int webArgb = new Color(def.primary().getRed(), def.primary().getGreen(),
                        def.primary().getBlue(), 170).getRGB();
                // Strands fan across the face and bulge into the open, so the
                // web spans the corner it is spun in instead of straddling the
                // block it clings to.
                int strands = 5;
                double reach = 0.34;
                double[] su = new double[strands], sv = new double[strands];
                for (int i = 0; i < strands; i++) {
                    double t = Math.PI * (i + 0.5) / strands;
                    su[i] = Math.cos(t) * reach;
                    sv[i] = Math.sin(t) * reach;
                    target.drawLine(f.xLay(0, 0), f.yLay(0, 0),
                            f.xLay(su[i], sv[i]), f.yLay(su[i], sv[i]), webArgb, 1f);
                }
                for (double ring : new double[]{0.55, 1.0}) {
                    for (int i = 0; i + 1 < strands; i++) {
                        target.drawLine(f.xLay(su[i] * ring, sv[i] * ring),
                                f.yLay(su[i] * ring, sv[i] * ring),
                                f.xLay(su[i + 1] * ring, sv[i + 1] * ring),
                                f.yLay(su[i + 1] * ring, sv[i + 1] * ring), webArgb, 1f);
                    }
                }
            }
            case ROOTS -> {
                float stroke = Math.max(1f, (float) (tile / 12));
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.24;
                    double len = 0.24 + 0.1 * ((i + p.col()) % 2);
                    target.drawLine(f.xLay(u, 0), f.yLay(u, 0),
                            f.xLay(u + (i - 1) * 0.1, len), f.yLay(u + (i - 1) * 0.1, len),
                            (i % 2 == 0 ? def.primary() : def.secondary()).getRGB(), stroke);
                }
            }
            case CRYSTALS -> {
                double half = Math.max(thin, 0.1);
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.22;
                    double len = 0.18 + 0.12 * ((i + p.row()) % 3);
                    // Crystals grow away from the face they sprouted on.
                    fillSpike(target, i == 1 ? def.secondary() : def.primary(), f,
                            f.xRise(u, 0), f.yRise(u, 0),
                            f.riseX, f.riseY, half, len * f.riseScale);
                }
            }
            case DRIP -> {
                fillBand(target, def.secondary(), f, f.hangX, f.hangY,
                        0.3, Math.max(0.05, thin));
                // The falling droplet loops on the anim clock.
                double t = (animClock * 0.7 + p.col() * 0.37 + p.row() * 0.11) % 1.0;
                int ds = Math.max(2, (int) (tile * 0.08));
                target.fillOval(f.xHang(0, t * 0.9) - ds / 2, f.yHang(0, t * 0.9) - ds / 2,
                        ds, ds, def.primary());
            }
        }
    }

    /** A band lying along the face, {@code half} tiles either side of centre. */
    private static void fillBand(DrawTarget target, Color color, FaceFrame f,
                                 double vx, double vy, double half, double thickness) {
        target.fillPolygon(
                new int[]{f.x(-half, 0, vx, vy), f.x(half, 0, vx, vy),
                        f.x(half, thickness, vx, vy), f.x(-half, thickness, vx, vy)},
                new int[]{f.y(-half, 0, vx, vy), f.y(half, 0, vx, vy),
                        f.y(half, thickness, vx, vy), f.y(-half, thickness, vx, vy)}, 4, color);
    }

    /**
     * A tapering spike rooted at {@code (bx, by)}: {@code len} tiles long
     * along {@code (ax, ay)}, {@code half} tiles wide square to it.
     */
    private static void fillSpike(DrawTarget target, Color color, FaceFrame f, int bx, int by,
                                  double ax, double ay, double half, double len) {
        target.fillPolygon(
                new int[]{bx + f.alongX(0, -half, ax, ay), bx + f.alongX(0, half, ax, ay),
                        bx + f.alongX(len, 0, ax, ay)},
                new int[]{by + f.alongY(0, -half, ax, ay), by + f.alongY(0, half, ax, ay),
                        by + f.alongY(len, 0, ax, ay)}, 3, color);
    }

    /**
     * A mushroom cap on the face point {@code (bx, by)}: a half-disc of radius
     * {@code cap} tiles lying level across the screen and doming up it —
     * gravity is what gives a cap its shape, so it is drawn in the plane
     * gravity reads in, which is the screen in every perspective.
     */
    private static void fillDome(DrawTarget target, Color color, FaceFrame f,
                                 int bx, int by, double cap) {
        int steps = 8;
        int[] xs = new int[steps + 1], ys = new int[steps + 1];
        for (int k = 0; k <= steps; k++) {
            double a = Math.PI * k / steps;
            xs[k] = bx + f.capX(Math.cos(a) * cap);
            ys[k] = by + f.capY(Math.sin(a) * cap / 2);
        }
        target.fillPolygon(xs, ys, steps + 1, color);
    }

    /** The stalk under a cap: a short bar hanging back from the cap's centre. */
    private static void fillStem(DrawTarget target, Color color, FaceFrame f,
                                 int bx, int by, double cap) {
        double w = cap / 6, len = cap / 2;
        target.fillRect(bx + f.capX(-w), by, Math.max(1, f.capX(w) - f.capX(-w)),
                Math.max(1, -f.capY(-len)), color);
    }

    /**
     * Where one decorated block face landed on screen, and the directions a
     * detail attached to it can run in — all in <em>tiles</em>, so a style is
     * written once and drawn correctly through any of the camera's
     * projections.
     *
     * <p>{@code tan} runs along the face and {@code out} away from the block
     * across it. Both come straight from the camera, so in isometric they are
     * the two diamond edges rather than the screen's axes, and they are the
     * only two axes the projection alone decides.
     *
     * <p>The three a style actually draws along are the <em>space's</em>
     * answer, not the face's, because they are about weight rather than about
     * geometry:
     * <ul>
     *   <li>{@code rise} — a detail standing up off the surface. Edge-on that
     *       is simply away from the face; on a floor it is a lift up the
     *       screen, the way this engine already draws a hop in either plan
     *       view, foreshortened by {@link #PLAN_VIEW_RISE}.</li>
     *   <li>{@code lay} — a detail lying along the surface. Edge-on there is
     *       nowhere to lie but away from the face; on a floor it runs back
     *       across the block.</li>
     *   <li>{@code hang} — a detail dangling. Down the screen where gravity
     *       is in the picture; across the block where it is not, since
     *       nothing can dangle off a floor you are looking down at.</li>
     * </ul>
     *
     * <p>Caps ({@code capX}/{@code capY}) are the exception that proves it:
     * a mushroom's shape <em>is</em> gravity, so it is drawn level on the
     * screen in every perspective.
     */
    private static final class FaceFrame {

        /**
         * How far in from the middle of a tile a plan view roots a detail, in
         * tiles. Far enough that all four faces of one block stay apart and
         * still read as the north/south/east/west side of it; near enough
         * that the detail sits on the block rather than beside it.
         */
        private static final double EDGE_LEAN = 0.2;

        /**
         * How much of a detail's height a plan view actually shows, drawn as a
         * lift up the screen. Looking down at a tuft of grass foreshortens it
         * — at full length it would tower over the tile it grows on, and with
         * {@link #EDGE_LEAN} this keeps even the tallest blade on its block.
         */
        private static final double PLAN_VIEW_RISE = 0.5;

        final int px, py;
        /**
         * Where this face is rooted in the <em>world</em>, for the depth it
         * sorts at. Kept alongside the screen point rather than derived from
         * it, because a screen point stops being invertible once the camera
         * lies flat on the floor and the depth is exactly what it loses.
         */
        final double anchorX, anchorY;
        /** One world tile, in screen pixels, at this face. */
        final double tile;
        final double tanX, tanY;
        final double outX, outY;
        /** Whether the face is seen edge-on, which is the side view alone. */
        final boolean sideOn;
        final double riseX, riseY;
        /** How much of a detail's height shows — foreshortened from above. */
        final double riseScale;
        final double layX, layY;
        final double hangX, hangY;

        /**
         * @param sideOn whether the face is seen edge-on — true only in the
         *               side view, and the one thing every axis but
         *               {@code out} turns on
         */
        private FaceFrame(int px, int py, double anchorX, double anchorY,
                          double tile, double tanX, double tanY,
                          double outX, double outY, boolean sideOn) {
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.px = px;
            this.py = py;
            this.tile = tile;
            this.tanX = tanX;
            this.tanY = tanY;
            this.outX = outX;
            this.outY = outY;
            this.sideOn = sideOn;
            // Seen edge-on, leaving the face is the only direction there is,
            // so standing and lying are the same move. Seen from above they
            // part company: height becomes a lift up the screen (the way this
            // engine draws a hop in either plan view), and anything lying on
            // the surface spreads back across the block instead of off it.
            this.riseX = sideOn ? outX : 0;
            this.riseY = sideOn ? outY : -1;
            this.riseScale = sideOn ? 1 : PLAN_VIEW_RISE;
            this.layX = sideOn ? outX : -outX;
            this.layY = sideOn ? outY : -outY;
            this.hangX = sideOn ? 0 : -outX;
            this.hangY = sideOn ? 1 : -outY;
        }

        /** Project a face, or {@code null} when a tile is too small to detail. */
        static FaceFrame of(Level level, Camera camera, PerspectiveSpace space,
                            SurfaceDecor.Placement p) {
            double ts = level.tileSize;
            SurfaceDecor.Face face = p.face();
            boolean sideOn = space.gravityOnPlane();
            int[] c = new int[2];

            // Where the detail is rooted. Seen edge-on, a face is the line
            // between two cells and a detail clings to it. Seen from above,
            // that line is a seam in the floor with nothing to cling to — the
            // face is a strip of the block's own top surface, so the detail is
            // rooted inside the tile, over toward the edge it belongs to.
            double lean = sideOn ? 0.5 : EDGE_LEAN;
            double ax = (p.col() + 0.5 + face.dc * lean) * ts;
            double ay = (p.row() + 0.5 + face.dr * lean) * ts;
            camera.worldToScreen(ax, ay, c);
            int px = c[0], py = c[1];
            // Rooted on top of whatever column it is planted on, rather than on
            // the world's floor. Moss on a plateau belongs on the plateau, and
            // decoration left at zero sinks into the terrain it decorates the
            // moment that terrain is more than one block deep.
            //
            // Derived from the column rather than stored on the placement: what
            // a stored layer would buy is decoration on the exposed face of a
            // block <em>partway up</em> a stack, which needs an editor that can
            // aim at one — E1's job, and nothing can place it until then
            // ({@code HEIGHT_PLAN.md} S5).
            int height = level.stackHeight(p.col(), p.row());
            if (height > 1) {
                py -= (int) Math.round(level.surfaceZ(height)
                        * camera.zoom * camera.liftScale());
            }

            // One world tile straight out of the block: both the direction a
            // detail leaves the face by and how big a tile is on screen here.
            camera.worldToScreen(ax + face.dc * ts, ay + face.dr * ts, c);
            double outX = c[0] - px, outY = c[1] - py;
            double tile = Math.hypot(outX, outY);
            if (tile < 3) return null;
            outX /= tile;
            outY /= tile;

            // One world tile along the face: the horizontal faces run along
            // +x, the vertical ones along +y — which is (|dr|, |dc|).
            camera.worldToScreen(ax + Math.abs(face.dr) * ts,
                    ay + Math.abs(face.dc) * ts, c);
            double tanX = c[0] - px, tanY = c[1] - py;
            double tanLen = Math.hypot(tanX, tanY);
            if (tanLen < 1e-6) return null;
            tanX /= tanLen;
            tanY /= tanLen;

            return new FaceFrame(px, py, ax, ay, tile, tanX, tanY, outX, outY, sideOn);
        }

        /**
         * The point {@code u} tiles beside the anchor and {@code v} tiles
         * along {@code (vx, vy)}.
         *
         * <p>Edge-on, "beside" runs along the face: five grass blades line up
         * across the top of a block. Seen from above there is no line to lay
         * them along — a north-south strip is a single screen column from
         * straight overhead — so they line up <em>square to whichever way the
         * detail itself runs</em>, which is the one choice that can never fold
         * them onto each other. It is the same reason a spike's base has to be
         * square to the direction it grows in.
         */
        int x(double u, double v, double vx, double vy) {
            return sideOn ? (int) Math.round(px + (u * tanX + v * vx) * tile)
                    : px + alongX(v, u, vx, vy);
        }

        int y(double u, double v, double vx, double vy) {
            return sideOn ? (int) Math.round(py + (u * tanY + v * vy) * tile)
                    : py + alongY(v, u, vx, vy);
        }

        /**
         * A screen offset in tiles, {@code lengthwise} along {@code (ax, ay)}
         * and {@code sideways} square to it. Anything with a filled shape is
         * built this way rather than against the face, because a spike's base
         * has to be square to the direction it grows in or it has no width —
         * which is exactly what happened to crystals on a north-south strip
         * seen from straight above.
         */
        int alongX(double lengthwise, double sideways, double ax, double ay) {
            return (int) Math.round((lengthwise * ax - sideways * ay) * tile);
        }

        int alongY(double lengthwise, double sideways, double ax, double ay) {
            return (int) Math.round((lengthwise * ay + sideways * ax) * tile);
        }

        int xOut(double u, double v) { return x(u, v, outX, outY); }

        int yOut(double u, double v) { return y(u, v, outX, outY); }

        /** A detail that stands up off the surface: grass, twigs, crystals. */
        int xRise(double u, double v) { return x(u, v * riseScale, riseX, riseY); }

        int yRise(double u, double v) { return y(u, v * riseScale, riseX, riseY); }

        /** A detail that lies along the surface: roots, cobweb strands. */
        int xLay(double u, double v) { return x(u, v, layX, layY); }

        int yLay(double u, double v) { return y(u, v, layX, layY); }

        /** A detail that dangles: moss, icicles, drips. */
        int xHang(double u, double v) { return x(u, v, hangX, hangY); }

        int yHang(double u, double v) { return y(u, v, hangX, hangY); }

        /** Screen offsets in tiles for a cap: across the screen, and up it. */
        int capX(double across) { return (int) Math.round(across * tile); }

        int capY(double up) { return (int) Math.round(-up * tile); }
    }
}
