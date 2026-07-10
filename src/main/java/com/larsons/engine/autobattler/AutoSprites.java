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

    /**
     * An item gem: components are single drops, combined items twin-toned,
     * elemental relics ringed in their element's colour.
     */
    public static BufferedImage item(AutoItem item, int size) {
        return cached("item:" + item.key + ":" + size, size, g -> {
            int s = size;
            g.setColor(item.color);
            int[] xs = {s / 2, s * 5 / 6, s / 2, s / 6};
            int[] ys = {s / 8, s / 2, s * 7 / 8, s / 2};
            g.fillPolygon(xs, ys, 4);
            g.setColor(item.color.brighter());
            g.drawLine(s / 2, s / 8, s / 6, s / 2);
            if (item.isRelic()) {
                g.setColor(withAlpha(item.color.brighter(), 220));
                g.setStroke(new BasicStroke(Math.max(1.5f, s / 14f)));
                g.drawOval(s / 8, s / 8, s * 3 / 4, s * 3 / 4);
            } else if (!item.isComponent()) {
                g.setColor(new Color(255, 255, 255, 170));
                g.setStroke(new BasicStroke(Math.max(1.5f, s / 12f)));
                g.drawPolygon(xs, ys, 4);
            }
        });
    }

    /**
     * A small icon for a synergy category, drawn procedurally like everything
     * else: a sword for Damage, a cross for Healing, a coin for Economy...
     * used by the synergy panel's filter chips and per-trait role markers.
     */
    public static BufferedImage categoryIcon(SynergyCategory cat, int size) {
        return cached("cat:" + cat.name() + ":" + size, size, g -> {
            int s = size;
            int c = s / 2;
            g.setColor(cat.color);
            g.setStroke(new BasicStroke(Math.max(1.5f, s / 8f), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            switch (cat) {
                case SUPPORT -> { // upward chevron over a dot: lifting others
                    g.drawLine(s / 4, c, c, s / 5);
                    g.drawLine(c, s / 5, s * 3 / 4, c);
                    g.fillOval(c - s / 8, s * 3 / 5, s / 4, s / 4);
                }
                case DAMAGE -> { // sword
                    g.drawLine(s / 4, s * 3 / 4, s * 3 / 4, s / 4);
                    g.drawLine(s / 3, s / 2 - s / 12, s / 2 + s / 12, s * 2 / 3);
                }
                case TANK -> { // filled shield
                    int[] xs = {s / 4, s * 3 / 4, s * 3 / 4, c, s / 4};
                    int[] ys = {s / 5, s / 5, s / 2, s * 4 / 5, s / 2};
                    g.fillPolygon(xs, ys, 5);
                }
                case HEALING -> { // cross
                    g.drawLine(c, s / 5, c, s * 4 / 5);
                    g.drawLine(s / 5, c, s * 4 / 5, c);
                }
                case SHIELDING -> { // shield outline (a bubble, not a wall)
                    int[] xs = {s / 4, s * 3 / 4, s * 3 / 4, c, s / 4};
                    int[] ys = {s / 5, s / 5, s / 2, s * 4 / 5, s / 2};
                    g.drawPolygon(xs, ys, 5);
                }
                case RANGE -> { // arrow
                    g.drawLine(s / 5, s * 4 / 5, s * 3 / 4, s / 4);
                    g.drawLine(s * 3 / 4, s / 4, s * 3 / 4, s / 2);
                    g.drawLine(s * 3 / 4, s / 4, s / 2, s / 4);
                }
                case CROWD_CONTROL -> { // snowflake-ish asterisk: slows/stops
                    g.drawLine(c, s / 5, c, s * 4 / 5);
                    g.drawLine(s / 4, s / 3, s * 3 / 4, s * 2 / 3);
                    g.drawLine(s / 4, s * 2 / 3, s * 3 / 4, s / 3);
                }
                case MAGIC -> { // orb with a highlight
                    g.fillOval(s / 4, s / 4, s / 2, s / 2);
                    g.setColor(Color.WHITE);
                    g.fillOval(c - s / 10, s / 3, s / 6, s / 6);
                }
                case MOBILITY -> { // double chevron right
                    g.drawLine(s / 4, s / 4, s / 2, c);
                    g.drawLine(s / 4, s * 3 / 4, s / 2, c);
                    g.drawLine(s / 2, s / 4, s * 3 / 4, c);
                    g.drawLine(s / 2, s * 3 / 4, s * 3 / 4, c);
                }
                case ECONOMY -> { // coin
                    g.drawOval(s / 5, s / 5, s * 3 / 5, s * 3 / 5);
                    g.drawLine(c, s / 3, c, s * 2 / 3);
                }
                case UTILITY -> { // four gear studs around a hub
                    g.fillOval(c - s / 8, c - s / 8, s / 4, s / 4);
                    g.drawLine(c, s / 6, c, s / 3);
                    g.drawLine(c, s * 2 / 3, c, s * 5 / 6);
                    g.drawLine(s / 6, c, s / 3, c);
                    g.drawLine(s * 2 / 3, c, s * 5 / 6, c);
                }
            }
        });
    }

    /**
     * A decorative board prop (cosmetic only): plants, statues, lanterns and
     * friends, drawn in the same procedural style as the unit figures. Kinds
     * are the {@link BoardTheme.Prop} names.
     */
    public static BufferedImage prop(BoardTheme.Prop kind, int size) {
        return cached("prop:" + kind.name() + ":" + size, size, g -> {
            int s = size;
            int c = s / 2;
            switch (kind) {
                case PLANT -> {
                    g.setColor(new Color(120, 85, 55));
                    g.fillRect(c - s / 8, s * 5 / 8, s / 4, s / 4);
                    g.setColor(new Color(90, 170, 90));
                    for (int i = -1; i <= 1; i++) {
                        g.fillOval(c - s / 8 + i * s / 6, s / 4 + Math.abs(i) * s / 10,
                                s / 4, s * 2 / 5);
                    }
                }
                case STATUE -> {
                    Color stone = new Color(165, 170, 185);
                    g.setColor(stone.darker());
                    g.fillRect(s / 4, s * 3 / 4, s / 2, s / 6);
                    g.setColor(stone);
                    g.fillRect(c - s / 8, s * 2 / 5, s / 4, s * 2 / 5);
                    g.fillOval(c - s / 7, s / 6, s * 2 / 7, s * 2 / 7);
                }
                case LANTERN -> {
                    g.setColor(new Color(90, 80, 70));
                    g.fillRect(c - s / 24, s / 3, s / 12, s / 2);
                    g.setColor(new Color(255, 210, 110));
                    g.fillOval(c - s / 7, s / 8, s * 2 / 7, s * 2 / 7);
                    g.setColor(new Color(255, 240, 190, 120));
                    g.fillOval(c - s / 4, s / 24, s / 2, s / 2);
                }
                case BANNER -> {
                    g.setColor(new Color(120, 105, 85));
                    g.fillRect(c - s / 24, s / 8, s / 12, s * 3 / 4);
                    g.setColor(new Color(90, 140, 220));
                    int[] xs = {c, c + s * 2 / 5, c + s / 4, c + s * 2 / 5, c};
                    int[] ys = {s / 8, s / 8, s / 4, s * 3 / 8, s * 3 / 8};
                    g.fillPolygon(xs, ys, 5);
                }
                case MUSHROOM -> {
                    g.setColor(new Color(235, 230, 215));
                    g.fillRect(c - s / 10, s / 2, s / 5, s * 2 / 5);
                    g.setColor(new Color(210, 90, 80));
                    g.fillArc(s / 6, s / 5, s * 2 / 3, s * 3 / 5, 0, 180);
                    g.setColor(new Color(250, 245, 235));
                    g.fillOval(s / 3, s / 3, s / 8, s / 8);
                    g.fillOval(s * 3 / 5, s * 2 / 7, s / 9, s / 9);
                }
                case CRYSTAL -> {
                    g.setColor(new Color(140, 200, 245));
                    int[] xs = {c, c + s / 4, c + s / 8, c - s / 8, c - s / 4};
                    int[] ys = {s / 8, s / 2, s * 7 / 8, s * 7 / 8, s / 2};
                    g.fillPolygon(xs, ys, 5);
                    g.setColor(new Color(220, 245, 255));
                    g.drawLine(c, s / 8, c - s / 8, s * 7 / 8);
                }
                case FOUNTAIN -> {
                    g.setColor(new Color(150, 155, 170));
                    g.fillArc(s / 8, s / 2, s * 3 / 4, s * 2 / 5, 0, 180);
                    g.setColor(new Color(110, 180, 235));
                    g.fillOval(s / 4, s * 5 / 9, s / 2, s / 5);
                    g.setColor(new Color(170, 215, 250));
                    g.drawLine(c, s / 6, c, s * 5 / 9);
                    g.drawLine(c, s / 6, c - s / 8, s * 2 / 5);
                    g.drawLine(c, s / 6, c + s / 8, s * 2 / 5);
                }
                default -> { /* NONE renders nothing */ }
            }
        });
    }

    /** A row of small element pips (diamonds) centred on {@code cx}. */
    public static void drawElementPips(Graphics2D g, java.util.List<Element> elements,
                                       int cx, int y, int size) {
        if (elements.isEmpty()) return;
        int total = elements.size() * size + (elements.size() - 1) * 2;
        int x = cx - total / 2;
        for (Element e : elements) {
            int[] xs = {x + size / 2, x + size, x + size / 2, x};
            int[] ys = {y, y + size / 2, y + size, y + size / 2};
            g.setColor(e.color);
            g.fillPolygon(xs, ys, 4);
            g.setColor(new Color(20, 22, 32));
            g.drawPolygon(xs, ys, 4);
            x += size + 2;
        }
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
