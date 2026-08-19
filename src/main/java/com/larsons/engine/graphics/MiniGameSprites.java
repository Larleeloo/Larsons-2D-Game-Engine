package com.larsons.engine.graphics;

import com.larsons.engine.graphics.atlas.SpriteAtlas;
import com.larsons.engine.minigame.StandaloneGame;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The built-in art for the launch screen's mini-game buttons: one plaque per
 * game per state, drawn from the game's own accent colour with a glyph that
 * says which game it is.
 *
 * <p><b>Why generated rather than shipped.</b> The engine ships no image files
 * — every sprite in it is either drawn in code or supplied by a drop-in texture
 * pack ({@link TexturePack}) — and a button whose picture is a missing PNG is a
 * button nobody can see. So the three states are painted here, and a pack that
 * supplies {@link StandaloneGame#textureKey()} replaces all three at once; the
 * button asks {@link Skins} first and only lands here when nothing answers.
 *
 * <p>The three states differ the way a button's states have to differ to be
 * read without a label: resting is flat, hovered is lifted and brighter with a
 * ring around it, and pressed is sunk — darker, its glyph nudged down and
 * right, its highlight moved to the bottom edge. Cached per (game, state,
 * size), because a menu draws them every frame.
 */
public final class MiniGameSprites {

    /** Which of a button's three states a frame is. */
    public enum State {
        /** Not pointed at: the resting picture. */
        STATIC,
        /** The pointer is over it. */
        HOVER,
        /** Held down. */
        PRESSED;

        /** The frame index this state reads out of a three-state sheet. */
        public int index() { return ordinal(); }
    }

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    private MiniGameSprites() {}

    // No cache invalidation: what is cached here is generated from a game's own
    // accent colour and a size, neither of which can change while the engine
    // runs. A texture pack's art never enters this cache — that lookup goes
    // through Skins on every call, which is what makes a rescan take effect.

    /**
     * The button face for {@code game} in {@code state}, at {@code size}
     * pixels square: the pack's sheet frame when there is one, else the
     * generated plaque.
     */
    public static BufferedImage button(StandaloneGame game, State state, int size) {
        BufferedImage skinned = Skins.stateFrame(game.textureKey(), state.index());
        if (skinned != null) return skinned;
        return generated(game, state, size);
    }

    /** The generated plaque alone, ignoring any texture pack. */
    public static synchronized BufferedImage generated(StandaloneGame game, State state,
                                                       int size) {
        int s = Math.max(16, size);
        String key = game.key() + ":" + state + ":" + s;
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paint(g, game, state, s);
        g.dispose();
        CACHE.put(key, img);
        SpriteAtlas.shared().register("minigame-button/" + key, img);
        return img;
    }

    /**
     * The whole three-state sheet, frames laid out left to right — the layout
     * {@link SpriteSheet} slices and therefore the shape a pack's replacement
     * has to be. Exported so a creator can start from the built-in art rather
     * than from a blank canvas.
     */
    public static BufferedImage sheet(StandaloneGame game, int size) {
        int s = Math.max(16, size);
        State[] states = State.values();
        BufferedImage out = new BufferedImage(s * states.length, s,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        for (int i = 0; i < states.length; i++) {
            g.drawImage(generated(game, states[i], s), i * s, 0, null);
        }
        g.dispose();
        return out;
    }

    private static void paint(Graphics2D g, StandaloneGame game, State state, int s) {
        Color accent = game.accent();
        boolean pressed = state == State.PRESSED;
        boolean hover = state == State.HOVER;
        // The plate. Pressed sinks (darker, and the whole face slides a pixel
        // down-right below), hovered lifts.
        Color top = shade(accent, pressed ? 0.42 : hover ? 0.72 : 0.55);
        Color bottom = shade(accent, pressed ? 0.28 : hover ? 0.46 : 0.34);
        int inset = pressed ? 2 : 1;
        int arc = Math.max(6, s / 5);
        g.setPaint(new java.awt.GradientPaint(0, inset, top, 0, s - inset, bottom));
        g.fillRoundRect(inset, inset, s - inset * 2, s - inset * 2, arc, arc);

        // The rim: bright and thick under the pointer, quiet otherwise.
        g.setStroke(new BasicStroke(hover ? 3f : 2f));
        g.setColor(hover ? brighten(accent, 0.35) : shade(accent, pressed ? 0.6 : 0.85));
        g.drawRoundRect(inset, inset, s - inset * 2 - 1, s - inset * 2 - 1, arc, arc);

        // A highlight along the lit edge — the top one at rest, the bottom one
        // when the plate is pressed in, which is the whole of why a pressed
        // button looks pressed.
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(255, 255, 255, pressed ? 26 : 58));
        int hy = pressed ? s - inset - 4 : inset + 3;
        g.drawLine(inset + arc / 2, hy, s - inset - arc / 2, hy);

        int nudge = pressed ? Math.max(1, s / 40) : 0;
        g.translate(nudge, nudge);
        Color ink = hover ? Color.WHITE : new Color(238, 240, 250);
        switch (game) {
            case AUTO_BATTLER -> paintAutoBattler(g, s, accent, ink);
            case DECK_BUILDER -> paintDeck(g, s, accent, ink);
            case EVOLUTION -> paintEvolution(g, s, accent, ink);
        }
        g.translate(-nudge, -nudge);
    }

    /** Two ranks of units facing off across a board line. */
    private static void paintAutoBattler(Graphics2D g, int s, Color accent, Color ink) {
        int cx = s / 2, cy = s / 2;
        g.setColor(new Color(0, 0, 0, 60));
        g.fillOval(cx - s / 3, cy - s / 9, (s / 3) * 2, s / 5);
        // Three pawns: two of yours in front, one of theirs behind.
        drawPawn(g, cx - s / 5, cy + s / 8, s / 5, ink);
        drawPawn(g, cx + s / 6, cy + s / 10, s / 5, ink);
        drawPawn(g, cx, cy - s / 6, s / 6, brighten(accent, 0.5));
    }

    private static void drawPawn(Graphics2D g, int x, int baseY, int h, Color color) {
        int head = Math.max(2, h / 3);
        g.setColor(color);
        g.fillOval(x - head / 2, baseY - h, head, head);
        g.fillRoundRect(x - head / 2 - 1, baseY - h + head - 1, head + 2, h - head + 1,
                head / 2, head / 2);
    }

    /** Three cards fanned out of a hand. */
    private static void paintDeck(Graphics2D g, int s, Color accent, Color ink) {
        int w = Math.max(6, s / 5), h = Math.max(9, s / 3);
        int cx = s / 2, cy = s / 2 + s / 12;
        double[] angles = {-0.45, 0, 0.45};
        Color[] faces = {shade(ink, 0.78), ink, shade(ink, 0.9)};
        for (int i = 0; i < angles.length; i++) {
            java.awt.geom.AffineTransform saved = g.getTransform();
            g.translate(cx, cy);
            g.rotate(angles[i]);
            g.setColor(faces[i]);
            g.fillRoundRect(-w / 2, -h, w, h, 3, 3);
            g.setColor(shade(accent, 0.7));
            g.setStroke(new BasicStroke(1.4f));
            g.drawRoundRect(-w / 2, -h, w, h, 3, 3);
            g.fillOval(-w / 6, -h + h / 5, w / 3, w / 3);
            g.setTransform(saved);
        }
    }

    /** A petri dish with a dividing cell in it. */
    private static void paintEvolution(Graphics2D g, int s, Color accent, Color ink) {
        int r = s / 3;
        int cx = s / 2, cy = s / 2;
        g.setColor(new Color(255, 255, 255, 22));
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setStroke(new BasicStroke(Math.max(1.5f, s / 26f)));
        g.setColor(ink);
        g.drawOval(cx - r, cy - r, r * 2, r * 2);
        // The cell, mid-division: two lobes and a nucleus in each.
        int cell = Math.max(3, r / 2);
        g.setColor(brighten(accent, 0.45));
        g.fillOval(cx - cell, cy - cell / 2, cell, cell);
        g.fillOval(cx, cy - cell / 3, cell, cell);
        g.setColor(shade(accent, 0.45));
        g.fillOval(cx - cell + cell / 3, cy - cell / 2 + cell / 3, cell / 3, cell / 3);
        g.fillOval(cx + cell / 3, cy - cell / 3 + cell / 3, cell / 3, cell / 3);
    }

    private static Color shade(Color c, double by) {
        return new Color(clamp(c.getRed() * by), clamp(c.getGreen() * by),
                clamp(c.getBlue() * by), c.getAlpha());
    }

    private static Color brighten(Color c, double by) {
        return new Color(clamp(c.getRed() + (255 - c.getRed()) * by),
                clamp(c.getGreen() + (255 - c.getGreen()) * by),
                clamp(c.getBlue() + (255 - c.getBlue()) * by), c.getAlpha());
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
