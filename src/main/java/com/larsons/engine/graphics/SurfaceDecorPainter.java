package com.larsons.engine.graphics;

import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws a level's painted {@link SurfaceDecor.Placement}s — the per-face
 * block details (grass tufts, hanging moss, twigs…). Shared by the creative
 * editor and the play scene so surfaces look identical while editing and
 * playing.
 *
 * <p>Each placement is culled against the visible tile bounds, checked
 * against its visibility condition (open/closed face) and its host block
 * (mined host = nothing to sit on), then drawn anchored to the face, with a
 * light {@code animClock} sway for organic styles.
 *
 * <p><b>Details are drawn in the face's own frame, not in screen space.</b>
 * A block face is an edge of the world grid, and where that edge lands on
 * screen is the camera's business: the top of a tile runs left-to-right in
 * the orthographic views but along the upper-right side of a diamond in
 * isometric, where "away from the block" is up <em>and to the right</em>.
 * Painting blades straight up the screen therefore tore every tuft off the
 * block it belongs to as soon as the level was seen isometrically. So each
 * placement is measured in tiles along its face and out of it
 * ({@link FaceFrame}), and the camera decides where that lands — which
 * collapses to exactly the old screen-space arithmetic in the side view, and
 * keeps the detail welded to its face in the plan views.
 *
 * <p>Which way a detail hangs or stands up is
 * {@code com.larsons.engine.sim.PerspectiveSpace}'s answer rather than the
 * face's: icicles point down the screen in a side view whatever they cling
 * to, while on a floor seen from above there is no down in the picture at
 * all, so they simply trail out of their face.
 */
public final class SurfaceDecorPainter {

    private SurfaceDecorPainter() {}

    /**
     * Draw one layer of a level's surface decor.
     *
     * @param bounds visible tile bounds as {col0, row0, col1, row1}
     * @param foreground which layer to draw this pass
     */
    public static void draw(Graphics2D g, Level level, Camera camera, int[] bounds,
                            boolean foreground, double animClock) {
        if (level.surfaceDecor.isEmpty()) return;
        SurfaceDecorRegistry registry = SurfaceDecorRegistry.standard();
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        List<Detail> batch = new ArrayList<>();
        for (SurfaceDecor.Placement p : level.surfaceDecor) {
            if (p.foreground() != foreground) continue;
            if (p.col() < bounds[0] - 1 || p.col() > bounds[2] + 1
                    || p.row() < bounds[1] - 1 || p.row() > bounds[3] + 1) continue;
            if (!visible(level, p)) continue;
            SurfaceDecor def = registry.get(p.key());
            if (def == null) continue;
            FaceFrame frame = FaceFrame.of(level, camera, space, p);
            if (frame == null) continue; // sub-pixel at extreme zoom-out
            batch.add(new Detail(frame, p, def));
        }
        if (batch.isEmpty()) return;
        // Nearer details cover farther ones: down the screen is toward the
        // viewer in every perspective the camera offers.
        batch.sort(Comparator.comparingInt(d -> d.frame().py));
        for (Detail d : batch) drawOne(g, d.frame(), d.placement(), d.def(), animClock);
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

    private static void drawOne(Graphics2D g, FaceFrame f, SurfaceDecor.Placement p,
                                SurfaceDecor def, double animClock) {
        double tile = f.tile;

        // Sprite-sheet override (texture key surface/<key>): a creator's own
        // grass/spikes/etc. art replaces the procedural painter, drawn one
        // tile in size centred half a tile out from the face it decorates.
        java.awt.image.BufferedImage skin = Skins.frame("surface/" + def.key(), animClock);
        if (skin != null) {
            int size = Math.max(2, (int) Math.round(tile));
            int cx = f.xOut(0, 0.5), cy = f.yOut(0, 0.5);
            g.drawImage(skin, cx - size / 2, cy - size / 2, size, size, null);
            return;
        }

        double sway = Math.sin(animClock * 2.1 + p.col() * 1.7 + p.row() * 0.9);
        // Thinnest sliver that still shows at this zoom, in tiles.
        double thin = Math.max(0.045, 1.0 / tile);

        switch (def.style()) {
            case GRASS_TUFT -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (tile / 16))));
                int blades = 5;
                for (int i = 0; i < blades; i++) {
                    double u = (i - blades / 2.0 + 0.5) / (blades + 1);
                    double h = 0.28 + 0.14 * ((i * 7 + p.col()) % 3);
                    double lean = sway * 0.06 + (i - blades / 2.0) * 0.02;
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine(f.xOut(u, 0), f.yOut(u, 0),
                            f.xOut(u + lean, h), f.yOut(u + lean, h));
                }
            }
            case FLOWERS -> {
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.24;
                    double h = 0.2 + 0.1 * i;
                    int tipX = f.xOut(u + sway * 0.04, h), tipY = f.yOut(u + sway * 0.04, h);
                    g.setColor(new Color(70, 130, 60));
                    g.drawLine(f.xOut(u, 0), f.yOut(u, 0), tipX, tipY);
                    g.setColor(i == 1 ? def.secondary() : def.primary());
                    int fs = Math.max(2, (int) (tile * 0.14));
                    g.fillOval(tipX - fs / 2, tipY - fs / 2, fs, fs);
                }
            }
            case HANGING_MOSS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (tile / 14))));
                for (int i = 0; i < 4; i++) {
                    double u = (i - 1.5) * 0.22;
                    double len = 0.3 + 0.18 * ((i + p.col()) % 3);
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine(f.xHang(u, 0), f.yHang(u, 0),
                            f.xHang(u + sway * 0.08, len), f.yHang(u + sway * 0.08, len));
                }
            }
            case ICICLES -> {
                g.setColor(def.primary());
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.26;
                    double len = 0.2 + 0.16 * ((i + p.row()) % 3);
                    g.fillPolygon(
                            new int[]{f.xHang(u - thin, 0), f.xHang(u + thin, 0),
                                    f.xHang(u, len)},
                            new int[]{f.yHang(u - thin, 0), f.yHang(u + thin, 0),
                                    f.yHang(u, len)}, 3);
                }
                g.setColor(def.secondary());
                fillBand(g, f, f.hangX, f.hangY, 0.4, Math.max(0.06, thin));
            }
            case TWIGS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (tile / 14))));
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.22;
                    double len = 0.2 + 0.12 * ((i + p.row()) % 3);
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine(f.xOut(u, 0), f.yOut(u, 0),
                            f.xOut(u - len * 0.4, len), f.yOut(u - len * 0.4, len));
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
                    int bx = f.xOut(u, stand), by = f.yOut(u, stand);
                    double cap = Math.max(3.0 / tile, 0.14 + 0.05 * (i % 2));
                    g.setColor(def.primary());
                    fillDome(g, f, bx, by, cap);
                    g.setColor(def.secondary());
                    fillStem(g, f, bx, by, cap);
                }
            }
            case COBWEB -> {
                g.setColor(new Color(def.primary().getRed(), def.primary().getGreen(),
                        def.primary().getBlue(), 170));
                g.setStroke(new BasicStroke(1f));
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
                    g.drawLine(f.xOut(0, 0), f.yOut(0, 0),
                            f.xOut(su[i], sv[i]), f.yOut(su[i], sv[i]));
                }
                for (double ring : new double[]{0.55, 1.0}) {
                    for (int i = 0; i + 1 < strands; i++) {
                        g.drawLine(f.xOut(su[i] * ring, sv[i] * ring),
                                f.yOut(su[i] * ring, sv[i] * ring),
                                f.xOut(su[i + 1] * ring, sv[i + 1] * ring),
                                f.yOut(su[i + 1] * ring, sv[i + 1] * ring));
                    }
                }
            }
            case ROOTS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (tile / 12))));
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.24;
                    double len = 0.24 + 0.1 * ((i + p.col()) % 2);
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine(f.xOut(u, 0), f.yOut(u, 0),
                            f.xOut(u + (i - 1) * 0.1, len), f.yOut(u + (i - 1) * 0.1, len));
                }
            }
            case CRYSTALS -> {
                double half = Math.max(thin, 0.1);
                for (int i = 0; i < 3; i++) {
                    double u = (i - 1) * 0.22;
                    double len = 0.18 + 0.12 * ((i + p.row()) % 3);
                    g.setColor(i == 1 ? def.secondary() : def.primary());
                    // Crystals grow away from the face they sprouted on.
                    g.fillPolygon(
                            new int[]{f.xOut(u - half, 0), f.xOut(u + half, 0), f.xOut(u, len)},
                            new int[]{f.yOut(u - half, 0), f.yOut(u + half, 0), f.yOut(u, len)},
                            3);
                }
            }
            case DRIP -> {
                g.setColor(def.secondary());
                fillBand(g, f, f.hangX, f.hangY, 0.3, Math.max(0.05, thin));
                // The falling droplet loops on the anim clock.
                double t = (animClock * 0.7 + p.col() * 0.37 + p.row() * 0.11) % 1.0;
                g.setColor(def.primary());
                int ds = Math.max(2, (int) (tile * 0.08));
                g.fillOval(f.xHang(0, t * 0.9) - ds / 2, f.yHang(0, t * 0.9) - ds / 2, ds, ds);
            }
        }
    }

    /** A band lying along the face, {@code half} tiles either side of centre. */
    private static void fillBand(Graphics2D g, FaceFrame f, double vx, double vy,
                                 double half, double thickness) {
        g.fillPolygon(
                new int[]{f.x(-half, 0, vx, vy), f.x(half, 0, vx, vy),
                        f.x(half, thickness, vx, vy), f.x(-half, thickness, vx, vy)},
                new int[]{f.y(-half, 0, vx, vy), f.y(half, 0, vx, vy),
                        f.y(half, thickness, vx, vy), f.y(-half, thickness, vx, vy)}, 4);
    }

    /**
     * A mushroom cap on the face point {@code (bx, by)}: a half-disc of radius
     * {@code cap} tiles lying across the space's level plane and doming up it.
     */
    private static void fillDome(Graphics2D g, FaceFrame f, int bx, int by, double cap) {
        int steps = 8;
        int[] xs = new int[steps + 1], ys = new int[steps + 1];
        for (int k = 0; k <= steps; k++) {
            double a = Math.PI * k / steps;
            xs[k] = bx + f.capX(Math.cos(a) * cap, Math.sin(a) * cap / 2);
            ys[k] = by + f.capY(Math.cos(a) * cap, Math.sin(a) * cap / 2);
        }
        g.fillPolygon(xs, ys, steps + 1);
    }

    /** The stalk under a cap: a short bar hanging back from the cap's centre. */
    private static void fillStem(Graphics2D g, FaceFrame f, int bx, int by, double cap) {
        double w = cap / 6, len = cap / 2;
        g.fillPolygon(
                new int[]{bx + f.capX(-w, 0), bx + f.capX(w, 0),
                        bx + f.capX(w, -len), bx + f.capX(-w, -len)},
                new int[]{by + f.capY(-w, 0), by + f.capY(w, 0),
                        by + f.capY(w, -len), by + f.capY(-w, -len)}, 4);
    }

    /** One visible placement, projected and ready to draw. */
    private record Detail(FaceFrame frame, SurfaceDecor.Placement placement,
                          SurfaceDecor def) {}

    /**
     * Where one decorated block face landed on screen, and the directions a
     * detail attached to it can run in — all in <em>tiles</em>, so a style is
     * written once and drawn correctly through any of the camera's
     * projections.
     *
     * <p>{@code tan} runs along the face and {@code out} away from the block
     * across it; both come straight from the camera, so in isometric they are
     * the two diamond edges rather than the screen's axes. {@code hang},
     * {@code up} and {@code across} are the space's, not the face's: in a side
     * view gravity is in the picture, so a dangling detail falls down the
     * screen and a cap lies level across it whatever face it grips; on a floor
     * seen from above gravity points at the viewer and shows as nothing at
     * all, so both simply lean out of the face.
     */
    private static final class FaceFrame {
        final int px, py;
        /** One world tile, in screen pixels, at this face. */
        final double tile;
        final double tanX, tanY;
        final double outX, outY;
        final double hangX, hangY;
        final double upX, upY;
        final double acrossX, acrossY;

        /**
         * @param gravityInView whether the space's "down" is a direction on
         *                      screen — true only in the side view, and the
         *                      one thing the last three axes turn on
         */
        private FaceFrame(int px, int py, double tile, double tanX, double tanY,
                          double outX, double outY, boolean gravityInView) {
            this.px = px;
            this.py = py;
            this.tile = tile;
            this.tanX = tanX;
            this.tanY = tanY;
            this.outX = outX;
            this.outY = outY;
            this.hangX = gravityInView ? 0 : outX;
            this.hangY = gravityInView ? 1 : outY;
            this.upX = gravityInView ? 0 : outX;
            this.upY = gravityInView ? -1 : outY;
            this.acrossX = gravityInView ? 1 : tanX;
            this.acrossY = gravityInView ? 0 : tanY;
        }

        /** Project a face, or {@code null} when a tile is too small to detail. */
        static FaceFrame of(Level level, Camera camera, PerspectiveSpace space,
                            SurfaceDecor.Placement p) {
            double ts = level.tileSize;
            SurfaceDecor.Face face = p.face();
            int[] c = new int[2];
            // Anchor: the middle of the decorated face, world coordinates.
            double ax = (p.col() + 0.5 + face.dc * 0.5) * ts;
            double ay = (p.row() + 0.5 + face.dr * 0.5) * ts;
            camera.worldToScreen(ax, ay, c);
            int px = c[0], py = c[1];

            // One world tile straight out of the block: both the direction a
            // detail grows in and how big a tile is on screen here.
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

            // A side view's gravity pulls down the screen; a floor's pulls
            // straight at the viewer, where it draws as nothing at all.
            return new FaceFrame(px, py, tile, tanX, tanY, outX, outY,
                    space.gravityOnPlane());
        }

        int x(double u, double v, double vx, double vy) {
            return (int) Math.round(px + (u * tanX + v * vx) * tile);
        }

        int y(double u, double v, double vx, double vy) {
            return (int) Math.round(py + (u * tanY + v * vy) * tile);
        }

        int xOut(double u, double v) { return x(u, v, outX, outY); }

        int yOut(double u, double v) { return y(u, v, outX, outY); }

        int xHang(double u, double v) { return x(u, v, hangX, hangY); }

        int yHang(double u, double v) { return y(u, v, hangX, hangY); }

        /**
         * A cap-shaped offset in tiles, to add to a point already on the face:
         * {@code u} runs across the cap and {@code v} up it.
         */
        int capX(double u, double v) {
            return (int) Math.round((u * acrossX + v * upX) * tile);
        }

        int capY(double u, double v) {
            return (int) Math.round((u * acrossY + v * upY) * tile);
        }
    }
}
