package com.larsons.engine.graphics;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.Decor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Procedural sprites for the merged-in content: mobs, items, and block
 * swatches. The Side-Scroller engine drew these from GIF/PNG assets; this
 * engine ships none, so each definition's colours become a small generated
 * image instead — good enough to build and play with, and trivially replaced
 * by real art (draw your own image for a key before falling back here).
 * Images are cached per (key, size).
 */
public final class EntitySprites {

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private EntitySprites() {}

    /** A critter sprite: body + head + eyes, wings when it flies. */
    public static BufferedImage mob(MobDef def, int size) {
        return cached("mob:" + def.key() + ":" + size, size, g -> {
            int s = size;
            g.setColor(def.body());
            if (def.flying()) {
                // Winged blob.
                g.fillOval(s / 4, s / 4, s / 2, s / 2);
                g.setColor(def.body().darker());
                g.fillArc(-s / 8, s / 4, s / 2, s / 2, 30, 120);
                g.fillArc(s * 5 / 8, s / 4, s / 2, s / 2, 30, 120);
            } else {
                // Rounded body with feet.
                g.fillRoundRect(s / 8, s / 3, s * 3 / 4, s / 2, s / 4, s / 4);
                g.setColor(def.body().darker());
                g.fillRect(s / 4, s * 4 / 5, s / 8, s / 5);
                g.fillRect(s * 5 / 8, s * 4 / 5, s / 8, s / 5);
                g.setColor(def.body());
                g.fillOval(s / 2, s / 12, s * 5 / 12, s * 5 / 12);
            }
            // Eye + accent marking.
            g.setColor(def.accent());
            int eye = Math.max(2, s / 8);
            g.fillOval(s * 2 / 3, s / 5, eye, eye);
            g.fillRect(s / 4, s / 2, s / 3, Math.max(2, s / 10));
        });
    }

    /** An item icon shaped by category, tinted by the item colour. */
    public static BufferedImage item(ItemDef def, int size) {
        return cached("item:" + def.key() + ":" + size, size, g -> {
            int s = size;
            g.setColor(def.color());
            switch (def.category()) {
                case WEAPON -> { // diagonal blade + hilt
                    g.setStroke(new BasicStroke(Math.max(2, s / 6f), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g.drawLine(s / 5, s * 4 / 5, s * 4 / 5, s / 5);
                    g.setColor(new Color(90, 70, 50));
                    g.drawLine(s / 5, s * 3 / 5, s * 2 / 5, s * 4 / 5);
                }
                case RANGED_WEAPON -> { // bow arc + string
                    g.setStroke(new BasicStroke(Math.max(2, s / 8f)));
                    g.drawArc(s / 5, s / 8, s * 3 / 5, s * 3 / 4, -60, 120);
                    g.setColor(new Color(220, 220, 220));
                    g.drawLine(s * 2 / 3, s / 8, s * 2 / 3, s * 7 / 8);
                }
                case FOOD -> { // round with a bite
                    g.fillOval(s / 5, s / 5, s * 3 / 5, s * 3 / 5);
                    g.setColor(new Color(0, 0, 0, 0));
                    g.setComposite(java.awt.AlphaComposite.Clear);
                    g.fillOval(s * 3 / 5, s / 6, s / 4, s / 4);
                    g.setComposite(java.awt.AlphaComposite.SrcOver);
                }
                case POTION -> { // flask
                    g.fillRoundRect(s / 3, s / 2, s / 3, s * 2 / 5, s / 6, s / 6);
                    g.fillRect(s * 5 / 12, s / 5, s / 6, s / 3);
                }
                case BLOCK -> g.fillRect(s / 5, s / 5, s * 3 / 5, s * 3 / 5);
                case THROWABLE -> { // diagonal dart (arrow / knife / rock icon)
                    g.setStroke(new BasicStroke(Math.max(2, s / 8f), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g.drawLine(s / 5, s * 4 / 5, s * 7 / 10, s * 3 / 10);
                    int[] tx = {s * 7 / 10, s * 4 / 5, s * 11 / 20};
                    int[] ty = {s / 5, s * 9 / 20, s * 3 / 10};
                    g.fillPolygon(tx, ty, 3);
                }
                case KEY -> {
                    g.fillOval(s / 6, s / 4, s / 3, s / 3);
                    g.fillRect(s / 2, s * 3 / 8, s * 2 / 5, Math.max(2, s / 10));
                    g.fillRect(s * 3 / 4, s * 3 / 8, Math.max(2, s / 12), s / 5);
                }
                case ACCESSORY -> { // amulet: a cord and a hanging gem
                    g.setStroke(new BasicStroke(Math.max(2, s / 12f)));
                    g.drawArc(s / 5, s / 8, s * 3 / 5, s * 2 / 5, 200, 140);
                    int[] gx = {s / 2, s * 5 / 8, s / 2, s * 3 / 8};
                    int[] gy = {s * 2 / 5, s * 3 / 5, s * 4 / 5, s * 3 / 5};
                    g.fillPolygon(gx, gy, 4);
                    g.setColor(new Color(255, 255, 255, 170));
                    g.fillOval(s * 7 / 16, s / 2, s / 8, s / 8);
                }
                default -> { // material lump / misc
                    int[] xs = {s / 2, s * 4 / 5, s * 3 / 5, s / 4};
                    int[] ys = {s / 6, s / 2, s * 5 / 6, s * 3 / 5};
                    g.fillPolygon(xs, ys, 4);
                }
            }
            // Rarity ring so tiers read at a glance (ported colour scheme).
            g.setStroke(new BasicStroke(Math.max(1.5f, s / 16f)));
            g.setColor(withAlpha(def.rarity().color, 200));
            g.drawRoundRect(1, 1, s - 3, s - 3, s / 4, s / 4);
        });
    }

    /**
     * A projectile sprite, drawn pointing along +X (the scene rotates it to
     * the velocity): glowing projectiles are layered orbs (ported from the
     * Side-Scroller engine's magic-bolt/fireball painters), physical ones a
     * shaft + head + fletching (its arrow/bolt painters).
     */
    public static BufferedImage projectile(ProjectileDef def, int size) {
        return cached("shot:" + def.key() + ":" + size, size, g -> {
            int s = size;
            if (def.glows()) {
                Color c = def.color();
                g.setColor(withAlpha(c, 90));
                g.fillOval(0, 0, s, s);
                g.setColor(c);
                g.fillOval(s / 4, s / 4, s / 2, s / 2);
                g.setColor(Color.WHITE);
                g.fillOval(s * 3 / 8, s * 3 / 8, s / 4, s / 4);
            } else {
                g.setColor(def.color());
                g.fillRect(0, s / 2 - Math.max(1, s / 12), s * 3 / 4, Math.max(2, s / 6));
                int[] xs = {s * 3 / 4, s, s * 3 / 4};
                int[] ys = {s / 4, s / 2, s * 3 / 4};
                g.setColor(def.color().brighter());
                g.fillPolygon(xs, ys, 3);
                g.setColor(new Color(200, 70, 60));
                g.fillRect(0, s / 3, Math.max(2, s / 8), s / 3);
            }
        });
    }

    /**
     * A vehicle sprite, facing +X (scenes flip it like mobs): each movement
     * kind gets its own silhouette — a legged mount, a winged flier over a
     * carpet plank, a hull with a sail, a tracked drill with its bit.
     */
    public static BufferedImage vehicle(VehicleDef def, int size) {
        return cached("vehicle:" + def.key() + ":" + size, size, g -> {
            int s = size;
            Color body = def.body(), accent = def.accent();
            switch (def.kind()) {
                case GROUND -> { // body on legs, neck + head forward
                    g.setColor(body);
                    g.fillRoundRect(s / 8, s * 2 / 5, s * 5 / 8, s * 3 / 10, s / 6, s / 6);
                    g.fillRect(s * 5 / 8, s / 5, s / 6, s / 3);         // neck
                    g.fillOval(s * 2 / 3, s / 10, s * 4 / 15, s / 5);   // head
                    g.setColor(body.darker());
                    int legW = Math.max(2, s / 10);
                    g.fillRect(s / 5, s * 7 / 10, legW, s / 4);
                    g.fillRect(s * 3 / 5, s * 7 / 10, legW, s / 4);
                    g.setColor(accent);
                    g.fillRect(s / 4, s * 2 / 5, s * 2 / 5, Math.max(2, s / 12)); // saddle
                }
                case FLYING -> { // a plank/carpet with upswept wings
                    g.setColor(body);
                    g.fillRoundRect(s / 10, s / 2, s * 4 / 5, s / 5, s / 6, s / 6);
                    g.setColor(accent);
                    g.fillArc(-s / 6, s / 5, s / 2, s / 2, 20, 140);
                    g.fillArc(s * 2 / 3, s / 5, s / 2, s / 2, 20, 140);
                    g.setColor(accent.brighter());
                    g.fillRect(s / 5, s * 11 / 20, s * 3 / 5, Math.max(2, s / 14));
                }
                case BOAT -> { // hull + mast + sail
                    g.setColor(body);
                    int[] hx = {s / 10, s * 9 / 10, s * 7 / 10, s * 3 / 10};
                    int[] hy = {s * 3 / 5, s * 3 / 5, s * 9 / 10, s * 9 / 10};
                    g.fillPolygon(hx, hy, 4);
                    g.setColor(body.darker());
                    g.fillRect(s / 2 - Math.max(1, s / 24), s / 8, Math.max(2, s / 12), s / 2);
                    g.setColor(accent);
                    g.fillPolygon(new int[]{s / 2, s / 2, s * 4 / 5},
                            new int[]{s / 8, s * 11 / 20, s * 2 / 5}, 3);
                }
                case DRILL -> { // tracked box with a pointed bit up front
                    g.setColor(body);
                    g.fillRoundRect(s / 10, s * 2 / 5, s * 3 / 5, s * 2 / 5, s / 8, s / 8);
                    g.setColor(accent);
                    g.fillPolygon(new int[]{s * 7 / 10, s, s * 7 / 10},
                            new int[]{s * 2 / 5, s * 3 / 5, s * 4 / 5}, 3);
                    g.setColor(body.darker());
                    g.fillOval(s / 8, s * 7 / 10, s / 4, s / 4);
                    g.fillOval(s * 2 / 5, s * 7 / 10, s / 4, s / 4);
                }
            }
        });
    }

    /** A block swatch: colour fill, bevel, glow ring for light emitters. */
    public static BufferedImage block(Block b, int size) {
        return cached("block:" + b.key() + ":" + size, size, g -> {
            int s = size;
            g.setColor(b.color());
            g.fillRect(1, 1, s - 2, s - 2);
            g.setColor(b.color().brighter());
            g.drawLine(1, 1, s - 2, 1);
            g.drawLine(1, 1, 1, s - 2);
            g.setColor(b.color().darker());
            g.drawLine(s - 2, 2, s - 2, s - 2);
            g.drawLine(2, s - 2, s - 2, s - 2);
            if (b.emitsLight()) {
                g.setColor(withAlpha(b.lightColor(), 190));
                g.setStroke(new BasicStroke(Math.max(2, s / 10f)));
                g.drawOval(s / 4, s / 4, s / 2, s / 2);
            } else if (!b.solid()) {
                // Passable blocks get a hollow corner mark.
                g.setColor(new Color(255, 255, 255, 120));
                g.drawLine(s - s / 4, 3, s - 3, s / 4);
            }
        });
    }

    /**
     * A decoration sprite (tree, rock, bush…), drawn to fill the square and
     * sit on its bottom edge — scenes anchor it bottom-centre so painting on
     * a floor line plants it.
     */
    public static BufferedImage decor(Decor d, int size) {
        return cached("decor:" + d.key() + ":" + size, size, g -> {
            int s = size;
            Color a = d.primary(), b = d.secondary();
            switch (d.shape()) {
                case TREE -> {
                    g.setColor(b);
                    g.fillRect(s * 7 / 16, s / 2, s / 8, s / 2);
                    g.setColor(a);
                    g.fillOval(s / 8, s / 12, s * 3 / 4, s * 3 / 5);
                    g.setColor(a.brighter());
                    g.fillOval(s / 4, s / 8, s / 3, s / 4);
                }
                case PINE -> {
                    g.setColor(b);
                    g.fillRect(s * 7 / 16, s * 3 / 4, s / 8, s / 4);
                    g.setColor(a);
                    for (int i = 0; i < 3; i++) {
                        int top = s / 12 + i * s / 4;
                        int half = s / 5 + i * s / 9;
                        g.fillPolygon(new int[]{s / 2 - half, s / 2, s / 2 + half},
                                new int[]{top + s / 4, top, top + s / 4}, 3);
                    }
                }
                case DEAD_TREE -> {
                    g.setColor(a);
                    g.setStroke(new BasicStroke(Math.max(2, s / 10f), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g.drawLine(s / 2, s, s / 2, s / 4);
                    g.drawLine(s / 2, s / 2, s / 4, s / 4);
                    g.drawLine(s / 2, s * 2 / 5, s * 3 / 4, s / 5);
                }
                case ROCK -> {
                    g.setColor(a);
                    g.fillOval(s / 8, s / 3, s * 3 / 4, s * 2 / 3);
                    g.setColor(b);
                    g.fillArc(s / 8, s / 3, s * 3 / 4, s * 2 / 3, 200, 120);
                }
                case STONES -> {
                    g.setColor(a);
                    g.fillOval(s / 10, s * 3 / 5, s * 2 / 5, s * 2 / 5);
                    g.fillOval(s / 2, s * 7 / 10, s / 3, s * 3 / 10);
                    g.setColor(b);
                    g.fillOval(s * 2 / 5, s / 2, s / 4, s / 4);
                }
                case BUSH -> {
                    g.setColor(a);
                    g.fillOval(s / 10, s * 2 / 5, s * 2 / 5, s * 3 / 5);
                    g.fillOval(s * 2 / 5, s / 4, s / 2, s * 3 / 4);
                    g.setColor(b);
                    int dot = Math.max(2, s / 9);
                    g.fillOval(s / 3, s / 2, dot, dot);
                    g.fillOval(s * 3 / 5, s * 2 / 5, dot, dot);
                    g.fillOval(s / 2, s * 7 / 10, dot, dot);
                }
                case MUSHROOM -> {
                    g.setColor(b);
                    g.fillRect(s / 4, s * 3 / 5, s / 8, s * 2 / 5);
                    g.fillRect(s * 5 / 8, s * 7 / 10, s / 9, s * 3 / 10);
                    g.setColor(a);
                    g.fillArc(s / 8, s * 2 / 5, s * 2 / 5, s * 2 / 5, 0, 180);
                    g.fillArc(s / 2, s * 11 / 20, s * 2 / 5, s * 2 / 5, 0, 180);
                }
                case CACTUS -> {
                    g.setColor(a);
                    g.fillRoundRect(s * 2 / 5, s / 8, s / 5, s * 7 / 8, s / 6, s / 6);
                    g.fillRoundRect(s / 8, s / 3, s / 6, s / 4, s / 8, s / 8);
                    g.fillRoundRect(s * 7 / 10, s / 4, s / 6, s / 4, s / 8, s / 8);
                    g.setColor(b);
                    g.fillRect(s / 8, s * 5 / 12, s * 2 / 5, Math.max(2, s / 16));
                }
                case STALAGMITE -> {
                    g.setColor(a);
                    g.fillPolygon(new int[]{s / 5, s * 2 / 5, s * 3 / 5},
                            new int[]{s, s / 4, s}, 3);
                    g.setColor(b);
                    g.fillPolygon(new int[]{s / 2, s * 7 / 10, s * 9 / 10},
                            new int[]{s, s / 2, s}, 3);
                }
                case CRYSTAL -> {
                    g.setColor(a);
                    g.fillPolygon(new int[]{s / 6, s * 2 / 6, s / 2},
                            new int[]{s, s / 5, s}, 3);
                    g.fillPolygon(new int[]{s * 2 / 5, s * 3 / 5, s * 4 / 5},
                            new int[]{s, s / 3, s}, 3);
                    g.setColor(withAlpha(b, 220));
                    g.fillPolygon(new int[]{s * 3 / 10, s * 2 / 6, s * 2 / 5},
                            new int[]{s, s / 3, s}, 3);
                }
                case LOG -> {
                    g.setColor(a);
                    g.fillRoundRect(s / 10, s * 3 / 5, s * 4 / 5, s * 3 / 10, s / 5, s / 5);
                    g.setColor(b);
                    g.fillOval(s * 4 / 5, s * 3 / 5, s / 7, s * 3 / 10);
                }
            }
        });
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
