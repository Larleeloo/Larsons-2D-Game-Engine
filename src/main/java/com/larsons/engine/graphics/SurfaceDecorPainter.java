package com.larsons.engine.graphics;

import com.larsons.engine.level.Level;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;

/**
 * Draws a level's painted {@link SurfaceDecor.Placement}s — the per-face
 * block details (grass tufts, hanging moss, twigs…). Shared by the creative
 * editor and the play scene so surfaces look identical while editing and
 * playing.
 *
 * <p>Each placement is culled against the visible tile bounds, checked
 * against its visibility condition (open/closed face) and its host block
 * (mined host = nothing to sit on), then drawn procedurally anchored to the
 * face, with a light {@code animClock} sway for organic styles.
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
        int[] corner = new int[2];
        for (SurfaceDecor.Placement p : level.surfaceDecor) {
            if (p.foreground() != foreground) continue;
            if (p.col() < bounds[0] - 1 || p.col() > bounds[2] + 1
                    || p.row() < bounds[1] - 1 || p.row() > bounds[3] + 1) continue;
            if (!visible(level, p)) continue;
            SurfaceDecor def = registry.get(p.key());
            if (def == null) continue;
            drawOne(g, level, camera, p, def, corner, animClock);
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

    private static void drawOne(Graphics2D g, Level level, Camera camera,
                                SurfaceDecor.Placement p, SurfaceDecor def,
                                int[] corner, double animClock) {
        double ts = level.tileSize;
        // Anchor: the middle of the decorated face, world coordinates.
        double ax = (p.col() + 0.5 + p.face().dc * 0.5) * ts;
        double ay = (p.row() + 0.5 + p.face().dr * 0.5) * ts;
        camera.worldToScreen(ax, ay, corner);
        int px = corner[0], py = corner[1];
        double s = ts * camera.zoom; // one tile in screen px
        if (s < 3) return;           // sub-pixel at extreme zoom-out

        // Sprite-sheet override (texture key surface/<key>): a creator's own
        // grass/spikes/etc. art replaces the procedural painter, drawn one
        // tile in size growing away from the decorated face.
        java.awt.image.BufferedImage skin = Skins.frame("surface/" + def.key(), animClock);
        if (skin != null) {
            int size = Math.max(2, (int) Math.round(s));
            int dx = px - size / 2 + p.face().dc * size / 2;
            int dy = py - size / 2 - (p.face() == SurfaceDecor.Face.UP ? size / 2 : 0)
                    + (p.face() == SurfaceDecor.Face.DOWN ? size / 2 : 0);
            g.drawImage(skin, dx, dy, size, size, null);
            return;
        }

        double sway = Math.sin(animClock * 2.1 + p.col() * 1.7 + p.row() * 0.9);

        switch (def.style()) {
            case GRASS_TUFT -> {
                g.setColor(def.primary());
                g.setStroke(new BasicStroke(Math.max(1f, (float) (s / 16))));
                int blades = 5;
                for (int i = 0; i < blades; i++) {
                    double bx = px + (i - blades / 2.0 + 0.5) * s / (blades + 1);
                    double h = s * (0.28 + 0.14 * ((i * 7 + p.col()) % 3));
                    double lean = sway * s * 0.06 + (i - blades / 2.0) * s * 0.02;
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine((int) bx, py, (int) (bx + lean), (int) (py - h));
                }
            }
            case FLOWERS -> {
                for (int i = 0; i < 3; i++) {
                    double bx = px + (i - 1) * s * 0.24;
                    double h = s * (0.2 + 0.1 * i % 2);
                    g.setColor(new Color(70, 130, 60));
                    g.drawLine((int) bx, py, (int) (bx + sway * s * 0.04), (int) (py - h));
                    g.setColor(i == 1 ? def.secondary() : def.primary());
                    int fs = Math.max(2, (int) (s * 0.14));
                    g.fillOval((int) (bx + sway * s * 0.04) - fs / 2, (int) (py - h) - fs / 2, fs, fs);
                }
            }
            case HANGING_MOSS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (s / 14))));
                for (int i = 0; i < 4; i++) {
                    double bx = px + (i - 1.5) * s * 0.22;
                    double len = s * (0.3 + 0.18 * ((i + p.col()) % 3));
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine((int) bx, py, (int) (bx + sway * s * 0.08), (int) (py + len));
                }
            }
            case ICICLES -> {
                g.setColor(def.primary());
                for (int i = 0; i < 3; i++) {
                    double bx = px + (i - 1) * s * 0.26;
                    double len = s * (0.2 + 0.16 * ((i + p.row()) % 3));
                    int w = Math.max(2, (int) (s * 0.1));
                    g.fillPolygon(new int[]{(int) bx - w / 2, (int) bx + w / 2, (int) bx},
                            new int[]{py, py, (int) (py + len)}, 3);
                }
                g.setColor(def.secondary());
                g.fillRect((int) (px - s * 0.4), py - 1, (int) (s * 0.8), Math.max(1, (int) (s * 0.06)));
            }
            case TWIGS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (s / 14))));
                int dir = p.face() == SurfaceDecor.Face.LEFT ? -1 : 1;
                for (int i = 0; i < 3; i++) {
                    double by = py + (i - 1) * s * 0.22;
                    double len = s * (0.2 + 0.12 * ((i + p.row()) % 3));
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    g.drawLine(px, (int) by, (int) (px + dir * len), (int) (by - len * 0.4));
                }
            }
            case MUSHROOMS -> {
                int dir = p.face() == SurfaceDecor.Face.LEFT ? -1
                        : p.face() == SurfaceDecor.Face.RIGHT ? 1 : 0;
                for (int i = 0; i < 3; i++) {
                    double off = (i - 1) * s * 0.22;
                    double bx = dir == 0 ? px + off : px + dir * s * 0.08;
                    double by = dir == 0 ? py : py + off;
                    int cap = Math.max(3, (int) (s * (0.14 + 0.05 * (i % 2))));
                    g.setColor(def.primary());
                    g.fillArc((int) bx - cap, (int) by - cap / 2, cap * 2, cap, 0, 180);
                    g.setColor(def.secondary());
                    g.fillRect((int) bx - cap / 6, (int) by, Math.max(1, cap / 3), cap / 2);
                }
            }
            case COBWEB -> {
                g.setColor(new Color(def.primary().getRed(), def.primary().getGreen(),
                        def.primary().getBlue(), 170));
                g.setStroke(new BasicStroke(1f));
                int r = (int) (s * 0.34);
                for (int i = 0; i < 5; i++) {
                    double a = Math.PI * (0.1 + i * 0.2) + (p.face().ordinal() * Math.PI / 2);
                    g.drawLine(px, py, (int) (px + Math.cos(a) * r), (int) (py + Math.sin(a) * r));
                }
                g.drawOval(px - r / 2, py - r / 2, r, r);
            }
            case ROOTS -> {
                g.setStroke(new BasicStroke(Math.max(1f, (float) (s / 12))));
                for (int i = 0; i < 3; i++) {
                    double bx = px + (i - 1) * s * 0.24;
                    double len = s * (0.24 + 0.1 * ((i + p.col()) % 2));
                    g.setColor(i % 2 == 0 ? def.primary() : def.secondary());
                    int dy = p.face() == SurfaceDecor.Face.DOWN ? 1 : 0;
                    int dx = p.face() == SurfaceDecor.Face.LEFT ? -1
                            : p.face() == SurfaceDecor.Face.RIGHT ? 1 : 0;
                    g.drawLine((int) bx, py, (int) (bx + dx * len + (dy != 0 ? (i - 1) * s * 0.1 : 0)),
                            (int) (py + dy * len));
                }
            }
            case CRYSTALS -> {
                for (int i = 0; i < 3; i++) {
                    double bx = px + (i - 1) * s * 0.22;
                    double len = s * (0.18 + 0.12 * ((i + p.row()) % 3));
                    int w = Math.max(2, (int) (s * 0.09));
                    // Crystals grow away from the face.
                    int gx = -p.face().dc, gy = -p.face().dr;
                    g.setColor(i == 1 ? def.secondary() : def.primary());
                    g.fillPolygon(
                            new int[]{(int) bx - w, (int) bx + w, (int) (bx + gx * len)},
                            new int[]{py + w * Math.abs(gx), py + w * Math.abs(gx),
                                    (int) (py + gy * len)}, 3);
                }
            }
            case DRIP -> {
                g.setColor(def.secondary());
                int w = Math.max(2, (int) (s * 0.1));
                g.fillRect((int) (px - s * 0.3), py - 1, (int) (s * 0.6), Math.max(1, w / 2));
                // The falling droplet loops on the anim clock.
                double t = (animClock * 0.7 + p.col() * 0.37 + p.row() * 0.11) % 1.0;
                g.setColor(def.primary());
                int ds = Math.max(2, (int) (s * 0.08));
                g.fillOval(px - ds / 2, (int) (py + t * s * 0.9), ds, ds);
            }
        }
    }
}
