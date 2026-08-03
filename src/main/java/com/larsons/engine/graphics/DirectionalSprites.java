package com.larsons.engine.graphics;

import com.larsons.engine.graphics.atlas.SpriteAtlas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * The pre-generated fallback art behind directional animations: a walk cycle
 * drawn <em>per facing</em>, so a character reads as turning even when nobody
 * has supplied a sprite sheet.
 *
 * <p>Every direction gets its own limb layout rather than a mirrored copy of
 * one profile:
 * <ul>
 *   <li><b>Side-on</b> ({@link Facing#EAST}/{@link Facing#WEST}) — the near
 *       arm swings <em>in front</em> of the torso in the body colour and the
 *       far arm behind it in a darker shade, which is what makes a
 *       right-facing character show their right arm in front and their left
 *       behind; facing west mirrors the whole frame, so the layering swaps
 *       with them.</li>
 *   <li><b>South</b> — seen from the front: face, both arms at the sides.</li>
 *   <li><b>North</b> — seen from behind: no face, hair/back of the head.</li>
 *   <li><b>Diagonals</b> — three-quarter views: the head sits toward the
 *       heading, one arm reads in front and the other behind.</li>
 * </ul>
 *
 * <p>Frames animate a two-beat walk (legs alternate, body bobs), matching the
 * frame count and rate of {@link PlayerSprites#WALK_FPS} so a hand-drawn sheet
 * can drop in with the same timing. Sheets are cached per
 * (size, colour, facing).
 */
public final class DirectionalSprites {

    /** Frames in every generated walk sheet. */
    public static final int FRAMES = 4;

    private static final Map<String, BufferedImage> CACHE = new HashMap<>();

    /** Single frames sliced out of {@link #CACHE}'s sheets — see {@link #frame}. */
    private static final Map<String, BufferedImage> FRAME_CACHE = new HashMap<>();

    private DirectionalSprites() {}

    /**
     * A {@link #FRAMES}-frame walk sheet for one direction, laid out
     * left-to-right at {@code size}×{@code size} per frame.
     */
    public static synchronized BufferedImage sheet(int size, Color body, Facing facing) {
        int s = Math.max(8, size);
        Facing dir = facing == null ? Facing.EAST : facing;
        String cacheKey = s + ":" + body.getRGB() + ":" + dir.key();
        BufferedImage cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        // A west-facing sheet is its eastern twin's, flipped whole — so a turn
        // swaps which arm is in front without a second painter, and the two
        // directions are exact mirrors of each other down to the pixel.
        BufferedImage img;
        if (dir.mirrored()) {
            img = flipHorizontally(sheet(s, body, dir.mirrorOf()), s);
        } else {
            img = new BufferedImage(s * FRAMES, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            for (int f = 0; f < FRAMES; f++) {
                Graphics2D fg = (Graphics2D) g.create(f * s, 0, s, s);
                paintFrame(fg, s, body, dir, f);
                fg.dispose();
            }
            g.dispose();
        }
        CACHE.put(cacheKey, img);
        return img;
    }

    /** Flip every {@code frameSize}-wide frame of a sheet in place, left to right. */
    private static BufferedImage flipHorizontally(BufferedImage sheet, int frameSize) {
        BufferedImage out = new BufferedImage(sheet.getWidth(), sheet.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        for (int f = 0; f * frameSize < sheet.getWidth(); f++) {
            int x = f * frameSize;
            g.drawImage(sheet, x + frameSize, 0, x, sheet.getHeight(),
                    x, 0, x + frameSize, sheet.getHeight(), null);
        }
        g.dispose();
        return out;
    }

    /** A looping walk animation over {@link #sheet}, at the player's walk rate. */
    public static Animation animation(int size, Color body, Facing facing) {
        int s = Math.max(8, size);
        return SpriteSheet.fromImage(sheet(s, body, facing), s, s)
                .animation(PlayerSprites.WALK_FPS, true);
    }

    /**
     * One frame of {@link #sheet} on its own (palette icons, previews).
     *
     * <p><b>Cached, and that is not a micro-optimisation.</b> This is the
     * unskinned player's sprite, so it is called for every character on screen
     * every frame, and {@code getSubimage} allocates a fresh {@code
     * BufferedImage} each time. Two things followed from that. The obvious one
     * is garbage: a few objects per character per frame, for ever. The
     * expensive one is that a batching backend keys on the image, so every
     * frame handed it a sprite it had never seen — a texture bind per character
     * per frame that no atlas could have merged, because the thing being drawn
     * was a different object each time. Caching makes the identity stable,
     * which is what lets the frame be atlased at all.
     */
    public static synchronized BufferedImage frame(int size, Color body, Facing facing, int index) {
        int s = Math.max(8, size);
        Facing dir = facing == null ? Facing.EAST : facing;
        int i = Math.floorMod(index, FRAMES);
        String key = s + ":" + body.getRGB() + ":" + dir.key() + ":" + i;
        BufferedImage cached = FRAME_CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage frame = sheet(s, body, dir).getSubimage(i * s, 0, s, s);
        FRAME_CACHE.put(key, frame);
        SpriteAtlas.shared().register("dir/" + key, frame);
        return frame;
    }

    /** Drop the generated art (a character's colours changed). */
    public static synchronized void clearCache() {
        CACHE.clear();
        FRAME_CACHE.clear();
    }

    // --- painting -------------------------------------------------------------------

    /**
     * The four-beat walk: pass, right leg leading, pass, left leg leading.
     * Alternating which limb leads is what makes the cycle read as steps
     * instead of a two-frame twitch.
     */
    private static final double[] SWING = {0, 1, 0, -1};

    private static void paintFrame(Graphics2D g, int s, Color body, Facing facing, int frame) {
        int swing = (int) Math.round(SWING[frame % FRAMES] * s / 8.0);
        // The body rides highest mid-stride, when the legs are together.
        int bob = swing == 0 ? 0 : Math.max(1, s / 16);

        // Limbs behind the torso are shaded down and limbs in front lifted, so
        // the near arm is unmistakably the near one at any sprite size.
        Color far = shade(body, 0.55);
        Color near = shade(body, 1.35);
        Color trim = shade(body, 0.42);     // boots, belt, outlines
        Color skin = new Color(245, 210, 170);
        Color hair = shade(skin, 0.45);

        switch (facing) {
            case NORTH -> paintFrontal(g, s, bob, swing, body, far, near, trim, hair, false);
            case SOUTH -> paintFrontal(g, s, bob, swing, body, far, near, trim, skin, true);
            case NORTH_EAST -> paintThreeQuarter(g, s, bob, swing, body, far, near, trim,
                    skin, hair, false);
            case SOUTH_EAST -> paintThreeQuarter(g, s, bob, swing, body, far, near, trim,
                    skin, hair, true);
            default -> paintProfile(g, s, bob, swing, body, far, near, trim, skin);
        }
    }

    /**
     * The side-on view: far arm behind the torso, near arm in front. Drawn
     * facing east; {@link #sheet} mirrors it for west.
     */
    private static void paintProfile(Graphics2D g, int s, int bob, int swing,
                                     Color body, Color far, Color near, Color trim,
                                     Color skin) {
        int cx = s / 2, torsoTop = s * 5 / 16 + bob;
        int torsoW = s * 3 / 8, torsoH = s * 3 / 8;
        int legW = Math.max(2, s / 9);
        int armW = Math.max(3, s / 7);      // arms read wider than legs
        int armTop = torsoTop + s / 14;
        int armLen = s * 5 / 16;
        int legTop = torsoTop + torsoH - s / 24;
        int legLen = s / 4;

        // Everything behind the body first, in the dark shade: the far leg
        // trails the near one and the far arm swings the opposite way.
        g.setColor(shade(far, 0.8));
        g.fillRoundRect(cx - legW / 2 - swing, legTop, legW, legLen, legW, legW);
        g.setColor(far);
        g.fillRoundRect(cx - armW / 2 - swing, armTop, armW, armLen, armW, armW);

        // Torso.
        g.setColor(body);
        g.fillRoundRect(cx - torsoW / 2, torsoTop, torsoW, torsoH, s / 8, s / 8);

        // Head in profile: skull toward +x, a nose bump so the face reads.
        int headD = s * 5 / 16;
        int headX = cx - headD / 2 + s / 24;
        int headY = torsoTop - headD + s / 32;
        g.setColor(skin);
        g.fillOval(headX, headY, headD, headD);
        g.fillPolygon(new int[]{headX + headD - 2, headX + headD + s / 16, headX + headD - 2},
                new int[]{headY + headD / 2 - s / 24, headY + headD / 2, headY + headD / 2 + s / 24}, 3);
        g.setColor(shade(skin, 0.45));
        g.fillArc(headX - 1, headY - 1, headD + 2, headD + 2, 40, 160); // hair

        // The near leg and near arm ride on top of the torso, swinging
        // opposite the far pair — the near arm lightened and outlined so it
        // is unmistakably the one in front.
        g.setColor(trim);
        g.fillRoundRect(cx - legW / 2 + swing, legTop, legW, legLen, legW, legW);
        g.setColor(near);
        g.fillRoundRect(cx - armW / 2 + swing, armTop, armW, armLen, armW, armW);
        g.setColor(shade(body, 0.35));
        g.setStroke(new BasicStroke(Math.max(1f, s / 32f)));
        g.drawRoundRect(cx - armW / 2 + swing, armTop, armW, armLen, armW, armW);
    }

    /**
     * Front ({@code face}) or back view: both arms at the sides, legs stepping
     * toward/away from the camera.
     */
    private static void paintFrontal(Graphics2D g, int s, int bob, int swing,
                                     Color body, Color far, Color near, Color trim,
                                     Color headColor, boolean face) {
        int cx = s / 2, torsoTop = s * 5 / 16 + bob;
        int torsoW = s * 7 / 16, torsoH = s * 3 / 8;
        int limbW = Math.max(2, s / 9);

        // Legs: one forward, one back — read as a stride from either side.
        g.setColor(trim);
        g.fillRoundRect(cx - torsoW / 4 - limbW / 2, torsoTop + torsoH - s / 24,
                limbW, s / 4 + swing / 2, limbW, limbW);
        g.fillRoundRect(cx + torsoW / 4 - limbW / 2, torsoTop + torsoH - s / 24,
                limbW, s / 4 - swing / 2, limbW, limbW);

        g.setColor(body);
        g.fillRoundRect(cx - torsoW / 2, torsoTop, torsoW, torsoH, s / 8, s / 8);

        // Arms hang either side, swinging in opposition.
        g.setColor(near);
        g.fillRoundRect(cx - torsoW / 2 - limbW + 1, torsoTop + s / 16 + swing / 2,
                limbW, s / 4, limbW, limbW);
        g.setColor(far);
        g.fillRoundRect(cx + torsoW / 2 - 1, torsoTop + s / 16 - swing / 2,
                limbW, s / 4, limbW, limbW);

        int headD = s * 5 / 16;
        int headX = cx - headD / 2, headY = torsoTop - headD + s / 32;
        g.setColor(headColor);
        g.fillOval(headX, headY, headD, headD);
        if (face) {
            // Front view only: eyes, so north and south never look alike.
            g.setColor(new Color(40, 40, 55));
            int eye = Math.max(1, headD / 7);
            g.fillOval(headX + headD / 4, headY + headD / 2 - eye / 2, eye, eye);
            g.fillOval(headX + headD * 3 / 5, headY + headD / 2 - eye / 2, eye, eye);
        }
    }

    /**
     * The three-quarter views: the head and shoulders turn toward +x while the
     * body still shows its width, one arm in front and one behind.
     * {@code toward} picks the southern (toward the camera) variant.
     */
    private static void paintThreeQuarter(Graphics2D g, int s, int bob, int swing,
                                          Color body, Color far, Color near, Color trim,
                                          Color skin, Color hair, boolean toward) {
        int cx = s / 2 + s / 32, torsoTop = s * 5 / 16 + bob;
        int torsoW = s * 3 / 8 + s / 16, torsoH = s * 3 / 8;
        int limbW = Math.max(2, s / 9);

        // Far arm behind the torso, offset away from the heading.
        g.setColor(far);
        g.fillRoundRect(cx - torsoW / 2 - limbW / 3, torsoTop + s / 12 - swing / 2,
                limbW, s / 4, limbW, limbW);
        g.setColor(shade(trim, 0.85));
        g.fillRoundRect(cx - torsoW / 4 - limbW / 2 - swing / 2, torsoTop + torsoH - s / 24,
                limbW, s / 4, limbW, limbW);

        g.setColor(body);
        g.fillRoundRect(cx - torsoW / 2, torsoTop, torsoW, torsoH, s / 8, s / 8);

        int headD = s * 5 / 16;
        int headX = cx - headD / 2 + s / 20;
        int headY = torsoTop - headD + s / 32;
        g.setColor(skin);
        g.fillOval(headX, headY, headD, headD);
        if (toward) {
            // Facing the camera at an angle: one eye near the turned edge.
            g.setColor(new Color(40, 40, 55));
            int eye = Math.max(1, headD / 7);
            g.fillOval(headX + headD / 2, headY + headD / 2 - eye / 2, eye, eye);
            g.fillOval(headX + headD * 3 / 4 - eye, headY + headD / 2 - eye / 2, eye, eye);
            g.setColor(hair);
            g.fillArc(headX - 1, headY - 1, headD + 2, headD + 2, 60, 150);
        } else {
            // Turned away: the back of the head, hair covering most of it.
            g.setColor(hair);
            g.fillArc(headX - 1, headY - 1, headD + 2, headD + 2, -40, 250);
        }

        // Near leg + near arm on top, on the side we are turning toward.
        g.setColor(trim);
        g.fillRoundRect(cx + torsoW / 4 - limbW / 2 + swing / 2, torsoTop + torsoH - s / 24,
                limbW, s / 4, limbW, limbW);
        g.setColor(near);
        g.fillRoundRect(cx + torsoW / 2 - limbW * 2 / 3, torsoTop + s / 12 + swing / 2,
                limbW, s / 4, limbW, limbW);
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

    /**
     * The direction indicator drawn on a character-profile palette icon: a
     * small compass wedge, so a profile's swatch shows it is directional.
     */
    public static void drawFacingHint(Graphics2D g, int size, Facing facing, Color color) {
        if (facing == null) return;
        int r = Math.max(3, size / 6);
        int cx = size - r - 2, cy = size - r - 2;
        double angle = Math.atan2(facing.dy(), facing.dx());
        g.setColor(color);
        g.setStroke(new BasicStroke(Math.max(1f, size / 24f)));
        g.drawLine(cx, cy, cx + (int) Math.round(Math.cos(angle) * r),
                cy + (int) Math.round(Math.sin(angle) * r));
    }
}
