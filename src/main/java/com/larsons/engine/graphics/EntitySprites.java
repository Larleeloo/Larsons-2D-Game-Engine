package com.larsons.engine.graphics;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.world.Block;

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
                case KEY -> {
                    g.fillOval(s / 6, s / 4, s / 3, s / 3);
                    g.fillRect(s / 2, s * 3 / 8, s * 2 / 5, Math.max(2, s / 10));
                    g.fillRect(s * 3 / 4, s * 3 / 8, Math.max(2, s / 12), s / 5);
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
