package com.larsons.engine.graphics;

import com.larsons.engine.graphics.atlas.SpriteAtlas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The character seen from <em>above</em>: the default art behind
 * {@link PlayerSprites}'s overhead pool, drawn per facing, for a camera that has
 * been raised past {@link Camera#OVERHEAD_PITCH}.
 *
 * <p><b>Why a second set of art at all.</b> {@link DirectionalSprites} draws a
 * figure standing up in front of a camera at roughly its own height: a torso
 * with a head on top of it, arms down the sides, legs below. That is a picture
 * of a person <em>from the side</em>, and it stays a picture of a person from
 * the side however far the camera is raised — so a 3D camera brought up over the
 * world ends up looking straight down at the tops of the walls, the tops of the
 * blocks and the tops of everything else, and at one full-length standing figure
 * pasted flat on the floor. The figure is the only thing in the frame that did
 * not turn with the camera, which is exactly the sort of thing an eye picks out
 * instantly. Past 75° a character is drawn from this file instead: head,
 * shoulders, and the arms and feet swinging out from under them.
 *
 * <p><b>Every direction is one drawing turned, and that is the honest thing to
 * do here.</b> {@link DirectionalSprites} needs a separate layout per facing —
 * a front view has a face and a back view does not, a profile puts one arm in
 * front of the torso and one behind — because turning a person in front of a
 * side-on camera shows you genuinely different things. Turning a person under an
 * overhead camera does not: from straight above, walking north <em>is</em>
 * walking east rotated a quarter turn, and drawing eight layouts would be
 * drawing the same figure eight times and inviting them to disagree. So one
 * canonical east-facing figure is painted and the frame is rotated by the
 * facing's own angle, which also means all eight directions animate in lockstep
 * for free.
 *
 * <p>The figure is kept inside the frame's inscribed circle so the diagonals
 * have nothing to clip. Frames match {@link DirectionalSprites#FRAMES} and
 * {@link PlayerSprites#WALK_FPS}, so a hand-drawn overhead sheet drops into the
 * same timing. Sheets are cached per (size, colour, facing), for the reason
 * spelled out on {@link DirectionalSprites#frame}: this is the unskinned
 * player's sprite in the overhead view, so it is resolved for every character on
 * screen every frame, and a fresh image each time is both garbage and a texture
 * bind no atlas can merge.
 */
public final class TopDownSprites {

    /** Frames in every generated overhead walk sheet. */
    public static final int FRAMES = DirectionalSprites.FRAMES;

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    /** Single frames sliced out of {@link #CACHE}'s sheets — see {@link #frame}. */
    private static final Map<String, BufferedImage> FRAME_CACHE = new HashMap<>();

    private TopDownSprites() {}

    /**
     * A {@link #FRAMES}-frame overhead walk sheet for one direction, laid out
     * left-to-right at {@code size}×{@code size} per frame.
     */
    public static synchronized BufferedImage sheet(int size, Color body, Facing facing) {
        int s = Math.max(8, size);
        Facing dir = facing == null ? Facing.EAST : facing;
        String cacheKey = s + ":" + body.getRGB() + ":" + dir.key();
        BufferedImage cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(s * FRAMES, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // The heading, as an angle on the screen. Screen +y is south and
        // Graphics2D turns clockwise in those coordinates, so this is the
        // rotation that carries the east-facing figure onto the facing asked
        // for, with no sign to remember at the call site.
        double angle = Math.atan2(dir.dy(), dir.dx());
        for (int f = 0; f < FRAMES; f++) {
            Graphics2D fg = (Graphics2D) g.create(f * s, 0, s, s);
            fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            fg.rotate(angle, s / 2.0, s / 2.0);
            paintFrame(fg, s, body, f);
            fg.dispose();
        }
        g.dispose();
        CACHE.put(cacheKey, img);
        return img;
    }

    /** A looping overhead walk animation over {@link #sheet}, at the player's walk rate. */
    public static Animation animation(int size, Color body, Facing facing) {
        int s = Math.max(8, size);
        return SpriteSheet.fromImage(sheet(s, body, facing), s, s)
                .animation(PlayerSprites.WALK_FPS, true);
    }

    /** One frame of {@link #sheet} on its own — cached; see the class note. */
    public static synchronized BufferedImage frame(int size, Color body, Facing facing,
                                                   int index) {
        int s = Math.max(8, size);
        Facing dir = facing == null ? Facing.EAST : facing;
        int i = Math.floorMod(index, FRAMES);
        String key = s + ":" + body.getRGB() + ":" + dir.key() + ":" + i;
        BufferedImage cached = FRAME_CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage frame = sheet(s, body, dir).getSubimage(i * s, 0, s, s);
        FRAME_CACHE.put(key, frame);
        SpriteAtlas.shared().register("top/" + key, frame);
        return frame;
    }

    /** Drop the generated art (a character's colours changed). */
    public static synchronized void clearCache() {
        CACHE.clear();
        FRAME_CACHE.clear();
    }

    // --- painting -------------------------------------------------------------------

    /**
     * The four-beat walk, as the limbs on the <em>left</em> of the body lead:
     * pass, left leading, pass, right leading. The same shape of cycle
     * {@link DirectionalSprites} uses, so the two pools stay in step when a
     * player tilts the camera through the threshold mid-stride.
     */
    private static final double[] SWING = {0, 1, 0, -1};

    /**
     * One frame of a figure facing <em>east</em>, seen from directly above,
     * centred in a {@code s}×{@code s} box. {@link #sheet} turns it.
     *
     * <p>Painted back to front the way it is seen: feet on the floor, then the
     * arms, then the shoulders over both, then the head on top of everything.
     * That order is what makes the limbs read as being under a body rather than
     * beside one, and it is the whole of the difference between this and a
     * standing figure — from up here the head hides most of the person, and what
     * says "walking" is the bits swinging out from under it.
     */
    private static void paintFrame(Graphics2D g, int s, Color body, int frame) {
        double stride = SWING[frame % FRAMES] * (s * 0.11);
        double armSwing = SWING[frame % FRAMES] * (s * 0.07);
        // A small sway across the heading: from above there is no bob to see,
        // and a body that never moves off its own axis reads as a sprite being
        // dragged rather than a person walking.
        double sway = SWING[(frame + 1) % FRAMES] * (s * 0.02);

        Color limb = shade(body, 0.68);     // the arms, a shade under the body
        Color trim = shade(body, 0.40);     // boots
        Color outline = shade(body, 0.30);
        Color skin = new Color(245, 210, 170);
        Color hair = shade(skin, 0.42);

        g.setStroke(new BasicStroke(Math.max(1f, s / 40f)));
        double cx = s / 2.0, cy = s / 2.0 + sway;

        // Feet first, because they are under everything else: one steps ahead
        // of the shoulders and the other trails behind them, which is the whole
        // of what a stride looks like from directly above.
        double footW = s * 0.15, footH = s * 0.11;
        g.setColor(trim);
        fillRound(g, cx + stride - footW / 2, cy - s * 0.09 - footH / 2, footW, footH);
        fillRound(g, cx - stride - footW / 2, cy + s * 0.09 - footH / 2, footW, footH);

        // Arms, swinging opposite one another, clear of the torso's edge so
        // they are visible rather than implied.
        double armW = s * 0.21, armH = s * 0.13;
        for (int side = -1; side <= 1; side += 2) {
            double ax = cx + side * armSwing - armW / 2;
            double ay = cy + side * s * 0.28 - armH / 2;
            g.setColor(limb);
            fillRound(g, ax, ay, armW, armH);
            g.setColor(outline);
            drawRound(g, ax, ay, armW, armH);
        }

        // Shoulders: half again as wide across the heading as along it, which
        // is what says "this way round" before the head is even drawn, and what
        // carries the character's own colour at a glance.
        double shW = s * 0.34, shH = s * 0.50;
        g.setColor(body);
        fillRound(g, cx - shW / 2, cy - shH / 2, shW, shH);
        g.setColor(outline);
        drawRound(g, cx - shW / 2, cy - shH / 2, shW, shH);

        // The head, slightly forward of the shoulders' centre and smaller than
        // them, with the hair covering the back of it and a nose over the front
        // — from straight above those two are the only cues to which way
        // someone is looking, so both are drawn rather than one.
        double headD = s * 0.26;
        double hx = cx + s * 0.04 - headD / 2, hy = cy - headD / 2;
        g.setColor(skin);
        g.fill(new Ellipse2D.Double(hx, hy, headD, headD));
        g.setColor(hair);
        // The rear half: 90°..270° measured anticlockwise from east, which is
        // the half pointing away from the heading.
        g.fill(new java.awt.geom.Arc2D.Double(hx, hy, headD, headD, 90, 180,
                java.awt.geom.Arc2D.PIE));
        g.setColor(shade(skin, 0.82));
        double noseD = Math.max(2, s * 0.075);
        g.fill(new Ellipse2D.Double(hx + headD - noseD * 0.55, cy - noseD / 2, noseD, noseD));
        g.setColor(shade(hair, 0.8));
        g.draw(new Ellipse2D.Double(hx, hy, headD, headD));
    }

    private static void fillRound(Graphics2D g, double x, double y, double w, double h) {
        double r = Math.min(w, h);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r, r));
    }

    private static void drawRound(Graphics2D g, double x, double y, double w, double h) {
        double r = Math.min(w, h);
        g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
    }

    /** A shade of {@code c}: {@code <1} darkens, {@code >1} lightens. */
    private static Color shade(Color c, double factor) {
        return new Color(
                clamp(c.getRed() * factor), clamp(c.getGreen() * factor),
                clamp(c.getBlue() * factor), c.getAlpha());
    }

    private static int clamp(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }
}
