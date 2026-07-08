package com.larsons.engine.autobattler;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Procedural sprites for the auto-battler, in the same asset-free spirit as
 * the engine's {@code EntitySprites}: each unit is a little chess-piece figure
 * in its definition's colours with a class emblem, ringed in its team colour;
 * items are shaped gems. Cached per (key, size, team). A game with real art
 * draws its own images per key instead.
 */
public final class AutoSprites {

    public static final Color TEAM_FRIENDLY = new Color(90, 160, 255);
    public static final Color TEAM_ENEMY = new Color(235, 95, 85);

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private AutoSprites() {}

    /** A unit figure; {@code friendly} picks the team ring colour. */
    public static BufferedImage unit(UnitDef def, int size, boolean friendly) {
        String key = "unit:" + def.key + ":" + size + ":" + friendly;
        return cached(key, size, g -> {
            int s = size;
            Color team = friendly ? TEAM_FRIENDLY : TEAM_ENEMY;

            // Team ring on the ground.
            g.setColor(withAlpha(team, 150));
            g.setStroke(new BasicStroke(Math.max(2f, s / 14f)));
            g.drawOval(s / 6, s * 7 / 10, s * 2 / 3, s / 5);

            // Body: a tapered robe/torso with a head.
            g.setColor(def.body);
            int[] bx = {s / 4, s * 3 / 4, s * 5 / 8, s * 3 / 8};
            int[] by = {s * 4 / 5, s * 4 / 5, s * 2 / 5, s * 2 / 5};
            g.fillPolygon(bx, by, 4);
            g.fillOval(s * 5 / 16, s / 8, s * 3 / 8, s * 3 / 8);
            g.setColor(def.body.darker());
            g.drawPolygon(bx, by, 4);

            // Eyes, so the figure reads as facing the player.
            g.setColor(new Color(25, 25, 35));
            int eye = Math.max(2, s / 14);
            g.fillOval(s * 7 / 16 - eye / 2, s / 4, eye, eye);
            g.fillOval(s * 9 / 16 - eye / 2, s / 4, eye, eye);

            // Class emblem in the accent colour.
            g.setColor(def.accent);
            g.setStroke(new BasicStroke(Math.max(2f, s / 12f), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            Trait clazz = def.clazz;
            int cx = s / 2, cy = s * 3 / 5;
            if (clazz == null) { // creep: spikes
                for (int i = -1; i <= 1; i++) {
                    g.drawLine(cx + i * s / 6, cy + s / 8, cx + i * s / 6, cy - s / 8);
                }
            } else {
                switch (clazz) {
                    case WARRIOR -> { // sword
                        g.drawLine(cx - s / 6, cy + s / 6, cx + s / 6, cy - s / 6);
                        g.drawLine(cx - s / 12, cy - s / 12, cx + s / 12, cy + s / 12);
                    }
                    case ARCHER -> { // bow arc + string
                        g.drawArc(cx - s / 6, cy - s / 5, s / 3, s * 2 / 5, -60, 120);
                        g.drawLine(cx + s / 9, cy - s / 5, cx + s / 9, cy + s / 5);
                    }
                    case MAGE -> { // orb
                        g.fillOval(cx - s / 8, cy - s / 8, s / 4, s / 4);
                        g.setColor(Color.WHITE);
                        g.fillOval(cx - s / 24, cy - s / 12, s / 12, s / 12);
                    }
                    case GUARDIAN -> { // shield
                        g.drawRoundRect(cx - s / 8, cy - s / 6, s / 4, s / 3, s / 10, s / 10);
                        g.drawLine(cx, cy - s / 6, cx, cy + s / 6);
                    }
                    case ASSASSIN -> { // twin daggers
                        g.drawLine(cx - s / 7, cy + s / 7, cx, cy - s / 7);
                        g.drawLine(cx + s / 7, cy + s / 7, cx, cy - s / 7);
                    }
                    case HEALER -> { // cross
                        g.drawLine(cx, cy - s / 7, cx, cy + s / 7);
                        g.drawLine(cx - s / 7, cy, cx + s / 7, cy);
                    }
                    case BRAWLER -> { // fists
                        g.fillOval(cx - s / 5, cy - s / 10, s / 5, s / 5);
                        g.fillOval(cx + s / 30, cy - s / 10, s / 5, s / 5);
                    }
                    default -> g.fillOval(cx - s / 10, cy - s / 10, s / 5, s / 5);
                }
            }
        });
    }

    /** An item gem: components are single drops, combined items twin-toned. */
    public static BufferedImage item(AutoItem item, int size) {
        return cached("item:" + item.key + ":" + size, size, g -> {
            int s = size;
            g.setColor(item.color);
            int[] xs = {s / 2, s * 5 / 6, s / 2, s / 6};
            int[] ys = {s / 8, s / 2, s * 7 / 8, s / 2};
            g.fillPolygon(xs, ys, 4);
            g.setColor(item.color.brighter());
            g.drawLine(s / 2, s / 8, s / 6, s / 2);
            if (!item.isComponent()) {
                g.setColor(new Color(255, 255, 255, 170));
                g.setStroke(new BasicStroke(Math.max(1.5f, s / 12f)));
                g.drawPolygon(xs, ys, 4);
            }
        });
    }

    /** Star pips drawn above upgraded units. */
    public static void drawStars(Graphics2D g, int star, int cx, int y, int size) {
        if (star <= 1) return;
        Color c = star >= 3 ? new Color(255, 200, 80) : new Color(210, 215, 230);
        g.setColor(c);
        int total = star * size + (star - 1) * 2;
        int x = cx - total / 2;
        for (int i = 0; i < star; i++) {
            int[] xs = {x + size / 2, x + size, x + size / 2, x};
            int[] ys = {y, y + size / 2, y + size, y + size / 2};
            g.fillPolygon(xs, ys, 4);
            x += size + 2;
        }
    }

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static BufferedImage cached(String key, int size, Painter painter) {
        BufferedImage img = CACHE.get(key);
        if (img != null) return img;
        img = new BufferedImage(Math.max(4, size), Math.max(4, size),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        painter.paint(g);
        g.dispose();
        CACHE.put(key, img);
        return img;
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
