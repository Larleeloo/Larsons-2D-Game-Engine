package com.larsons.engine.graphics;

import com.larsons.engine.world.Block;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Procedural art for blocks that are <em>not</em> cubes — the sprite half of a
 * {@link Block.Shape#PLANT}.
 *
 * <p><b>Why the engine has to draw this itself.</b> It ships no image assets on
 * purpose (requirement #4): a block's art is its colour, and a texture pack
 * replaces it. That answer works perfectly for a cube, whose shape carries the
 * meaning and whose colour only has to say what it is made of. It does not work
 * at all for a flower, whose whole meaning <em>is</em> its shape — a
 * flower-coloured cube is a pink wall. So a plant with no sheet of its own gets
 * a silhouette drawn from its colour here, and a pack that supplies
 * {@code block/<key>} replaces it exactly as it replaces any other block's art.
 *
 * <p><b>The silhouette is chosen from the block's key</b>, which is a heuristic
 * and is a good one: a registry of a hundred and sixty blocks names a flower
 * "flower_red" and a shrub "desert_shrub" because that is what they are, and
 * five shapes cover every plant in it. A key that matches nothing gets the tuft,
 * which is the shape that reads as "something growing" without claiming to be
 * anything in particular.
 *
 * <p>Sprites are cached by key and size — a level has a handful of plants, each
 * drawn thousands of times a frame — and registered with the shared
 * {@link com.larsons.engine.graphics.atlas.SpriteAtlas} like every other piece
 * of procedural art, so a batching backend can treat them as one sheet.
 */
public final class BlockSprites {

    /** How many of these to keep: a level's plants, at the sizes in play. */
    private static final int MAX_ENTRIES = 64;

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /** The green every stem and stalk is drawn in, whatever the petals are. */
    private static final Color STEM = new Color(72, 128, 62);

    private BlockSprites() {}

    /** The five shapes a plant key can resolve to. */
    private enum Silhouette { FLOWER, TUFT, BUSH, MUSHROOM, STALK }

    /**
     * The billboard a plant block is drawn with: the pack's sheet where there
     * is one, else a procedural silhouette in the block's own colour.
     *
     * @param animClock where in an animated sheet's cycle this frame is
     * @param size      how many pixels across to draw a procedural sprite at;
     *                  a pack's own sheet is returned at its own resolution
     */
    public static BufferedImage plant(Block block, int size, double animClock) {
        if (block == null) return null;
        BufferedImage skin = Skins.frame(block.textureKey(), animClock);
        if (skin != null) return skin;
        return procedural(block, size);
    }

    /** The procedural silhouette alone, ignoring any pack sheet. */
    public static synchronized BufferedImage procedural(Block block, int size) {
        int px = Math.max(8, Math.min(64, size));
        String key = block.key() + ":" + px;
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paint(g, silhouetteOf(block.key()), block.color(), px);
        g.dispose();
        CACHE.put(key, img);
        com.larsons.engine.graphics.atlas.SpriteAtlas.shared().register("plant/" + key, img);
        return img;
    }

    /** Which shape a block key reads as; see the class note. */
    private static Silhouette silhouetteOf(String key) {
        String k = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (k.contains("mushroom")) return Silhouette.MUSHROOM;
        if (k.contains("flower") || k.contains("tulip") || k.contains("orchid")
                || k.contains("lavender") || k.contains("lily")) {
            return Silhouette.FLOWER;
        }
        if (k.contains("bush") || k.contains("shrub") || k.contains("berry")
                || k.contains("coral") || k.contains("roots") || k.contains("vines")) {
            return Silhouette.BUSH;
        }
        if (k.contains("cane") || k.contains("bamboo") || k.contains("cattail")
                || k.contains("stalagmite") || k.contains("icicle")) {
            return Silhouette.STALK;
        }
        return Silhouette.TUFT;
    }

    private static void paint(Graphics2D g, Silhouette shape, Color colour, int s) {
        Color dark = colour.darker();
        float stroke = Math.max(1.2f, s / 14f);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (shape) {
            case FLOWER -> {
                g.setColor(STEM);
                g.drawLine(s / 2, s, s / 2, s * 2 / 5);
                g.drawLine(s / 2, s * 7 / 10, s / 3, s * 3 / 5);
                int r = Math.max(3, s / 3);
                // Five petals round a centre: the shape a child draws when
                // asked for a flower, which is exactly the recognisability a
                // sprite this small needs.
                for (int i = 0; i < 5; i++) {
                    double a = Math.PI * 2 * i / 5 - Math.PI / 2;
                    int px = (int) Math.round(s / 2 + Math.cos(a) * r * 0.42);
                    int py = (int) Math.round(s * 2 / 5 + Math.sin(a) * r * 0.42);
                    g.setColor(colour);
                    g.fillOval(px - r / 2, py - r / 2, r, r);
                }
                g.setColor(brighten(colour, 0.35));
                g.fillOval(s / 2 - r / 4, s * 2 / 5 - r / 4, r / 2, r / 2);
            }
            case TUFT -> {
                for (int i = 0; i < 5; i++) {
                    double lean = (i - 2) * 0.16;
                    int top = (int) Math.round(s * (0.18 + 0.10 * Math.abs(i - 2)));
                    g.setColor(i % 2 == 0 ? colour : dark);
                    g.drawLine(s / 2, s, (int) Math.round(s / 2 + lean * s), top);
                }
            }
            case BUSH -> {
                g.setColor(dark);
                g.fillOval(s / 8, s / 3, s * 3 / 4, s * 2 / 3);
                g.setColor(colour);
                g.fillOval(s / 5, s / 4, s * 3 / 5, s / 2);
                g.setColor(brighten(colour, 0.22));
                g.fillOval(s / 3, s / 3, s / 4, s / 5);
            }
            case MUSHROOM -> {
                g.setColor(new Color(235, 226, 208));
                g.fillRect(s * 7 / 16, s / 2, s / 8, s / 2);
                g.setColor(colour);
                g.fillArc(s / 6, s / 4, s * 2 / 3, s / 2, 0, 180);
                g.setColor(brighten(colour, 0.3));
                g.fillOval(s * 3 / 8, s * 5 / 16, s / 8, s / 12);
            }
            case STALK -> {
                g.setColor(colour);
                g.fillRect(s * 7 / 16, s / 6, s / 8, s * 5 / 6);
                g.setColor(dark);
                for (int i = 1; i < 4; i++) {
                    int y = s / 6 + i * s / 5;
                    g.drawLine(s * 7 / 16, y, s * 9 / 16, y);
                }
            }
        }
    }

    private static Color brighten(Color c, double by) {
        return new Color(
                Math.min(255, (int) Math.round(c.getRed() + (255 - c.getRed()) * by)),
                Math.min(255, (int) Math.round(c.getGreen() + (255 - c.getGreen()) * by)),
                Math.min(255, (int) Math.round(c.getBlue() + (255 - c.getBlue()) * by)),
                c.getAlpha());
    }
}
