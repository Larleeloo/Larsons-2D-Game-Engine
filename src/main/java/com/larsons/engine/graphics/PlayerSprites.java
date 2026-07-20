package com.larsons.engine.graphics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The player character's sprite, shared by every place a player renders —
 * the play scene ("load level" play), creative mode's play-test, and remote
 * players online — so the character looks identical everywhere.
 *
 * <p>The default look is the procedural 4-frame walk sheet (body, head,
 * alternating legs). Like blocks/mobs/items, the player is reskinnable via
 * the {@link Skins} system under texture key {@value #SKIN_KEY}: assign a
 * sprite sheet (creative mode's Tools &rarr; Player Skin…) and
 * {@link #frame} returns its frames instead of the procedural art.
 */
public final class PlayerSprites {

    /** {@link Skins} texture key of the player-character override. */
    public static final String SKIN_KEY = "player";

    /** The default (unskinned) body colour of the local player. */
    public static final Color DEFAULT_BODY = new Color(70, 130, 220);

    /** Playback rate of the procedural walk cycle, sprite frames/sec. */
    public static final double WALK_FPS = 10;

    private PlayerSprites() {}

    /**
     * The frame to draw right now: the assigned player skin's frame at
     * {@code walkSeconds} when one is installed, else the procedural walk
     * animation's current frame. {@code walkSeconds} should only advance
     * while the player moves, so both art styles stand still when idle.
     */
    public static BufferedImage frame(Animation walkAnim, double walkSeconds) {
        BufferedImage skin = Skins.frame(SKIN_KEY, walkSeconds);
        if (skin != null) return skin;
        return walkAnim != null ? walkAnim.current() : null;
    }

    /** A looping walk animation over {@link #sheet}, at {@link #WALK_FPS}. */
    public static Animation walkAnimation(int size, Color body) {
        int s = Math.max(8, size);
        return SpriteSheet.fromImage(sheet(s, body), s, s).animation(WALK_FPS, true);
    }

    /**
     * The procedural 4-frame walk sheet: rounded body in {@code body}, skin
     * tone head, dark legs alternating spread, with a small bob on odd frames.
     */
    public static BufferedImage sheet(int size, Color body) {
        int frames = 4;
        BufferedImage img = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int f = 0; f < frames; f++) {
            int ox = f * size;
            int bob = (f % 2 == 0) ? 0 : Math.max(1, size / 16);
            g.setColor(body);
            g.fillRoundRect(ox + size / 4, size / 4 + bob, size / 2, size / 2, size / 6, size / 6);
            g.setColor(new Color(245, 210, 170));
            g.fillOval(ox + size / 3, size / 8 + bob, size / 3, size / 3);
            g.setColor(new Color(40, 40, 60));
            int legW = Math.max(2, size / 10);
            int legY = size * 3 / 4 + bob;
            int spread = (f % 2 == 0) ? size / 12 : size / 6;
            g.fillRect(ox + size / 2 - spread - legW, legY, legW, size / 5);
            g.fillRect(ox + size / 2 + spread, legY, legW, size / 5);
        }
        g.dispose();
        return img;
    }
}
