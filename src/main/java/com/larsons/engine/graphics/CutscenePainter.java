package com.larsons.engine.graphics;

import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutscenePlayer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a running {@link CutscenePlayer}: the sprite-sheet actors in world
 * space (their active animation state's frame at its own clock), and the
 * cinematic overlay — easing letterbox bars, the dialogue caption with its
 * speaker name, and the skip hint. Shared by the creative editor's play-test
 * and the play scene so both present cutscenes identically.
 *
 * <p>Sheets are sliced once and cached per (path, frame size). An actor whose
 * active state has no working sheet draws as a procedural stand-in figure
 * tinted by its key — consistent with the engine's asset-free philosophy: a
 * missing PNG never breaks playback.
 */
public final class CutscenePainter {

    private static final Font CAPTION_FONT = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font SPEAKER_FONT = new Font("SansSerif", Font.BOLD, 13);
    private static final Font HINT_FONT = new Font("SansSerif", Font.PLAIN, 11);

    /** Letterbox bar height as a fraction of the viewport height. */
    private static final double BAR_FRACTION = 0.11;

    private static final Map<String, List<BufferedImage>> SHEETS = new HashMap<>();
    private static final Map<String, BufferedImage> PLACEHOLDERS = new HashMap<>();

    private CutscenePainter() {}

    /** Draw every visible actor through the scene's camera. */
    public static void drawActors(Graphics2D g, Camera camera, CutscenePlayer player) {
        int[] corner = new int[2];
        for (CutscenePlayer.ActorView a : player.actors()) {
            if (!a.visible) continue;
            BufferedImage img = frame(a.def, a.state, a.stateTime);
            if (img == null) img = placeholder(a.def);
            int w = Math.max(8, (int) Math.round(a.def.sizePx * camera.zoom));
            camera.worldToScreen(a.x, a.y, corner);
            int dx = corner[0] - w / 2, dy = corner[1] - w;
            if (a.facingLeft) {
                g.drawImage(img, dx + w, dy, -w, w, null);
            } else {
                g.drawImage(img, dx, dy, w, w, null);
            }
        }
    }

    /** The letterbox bars, caption box, and skip hint (draw over the HUD). */
    public static void drawOverlay(Graphics2D g, int width, int height,
                                   CutscenePlayer player) {
        // Bars ease in over the first third of a second.
        int bar = (int) Math.round(height * BAR_FRACTION
                * Math.min(1, player.time() * 3));
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, bar);
        g.fillRect(0, height - bar, width, bar);

        g.setFont(HINT_FONT);
        g.setColor(new Color(210, 210, 220));
        String hint = "▶ " + player.cutscene().name + "  ·  Enter/Esc skips";
        g.drawString(hint, width - g.getFontMetrics().stringWidth(hint) - 10,
                Math.max(14, bar - 6));

        String caption = player.caption();
        if (caption.isEmpty()) return;
        g.setFont(CAPTION_FONT);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(caption, fm, (int) (width * 0.7));
        int lineH = fm.getHeight();
        int boxW = 0;
        for (String line : lines) boxW = Math.max(boxW, fm.stringWidth(line));
        boxW += 28;
        String speaker = player.speaker();
        int boxH = lines.size() * lineH + 18 + (speaker.isEmpty() ? 0 : 16);
        int bx = (width - boxW) / 2;
        int by = height - Math.max(bar, 8) - boxH - 8;
        g.setColor(new Color(10, 10, 18, 215));
        g.fillRoundRect(bx, by, boxW, boxH, 12, 12);
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(bx, by, boxW, boxH, 12, 12);
        int ty = by + 14;
        if (!speaker.isEmpty()) {
            g.setFont(SPEAKER_FONT);
            g.setColor(new Color(255, 220, 120));
            g.drawString(speaker, bx + 14, ty + 4);
            ty += 16;
            g.setFont(CAPTION_FONT);
        }
        g.setColor(new Color(235, 235, 245));
        for (String line : lines) {
            ty += lineH;
            g.drawString(line, bx + 14, ty - 4);
        }
    }

    /**
     * The frame of {@code actor}'s animation {@code state} at
     * {@code stateTime} seconds, or {@code null} when neither the state nor
     * its fallbacks have a working sheet (the caller's cue to draw the
     * procedural stand-in).
     */
    public static synchronized BufferedImage frame(Cutscene.Actor actor, String state,
                                                   double stateTime) {
        Cutscene.SheetAnim anim = actor.state(state);
        if (anim == null || anim.sheet().isEmpty()) return null;
        String cacheKey = anim.sheet() + "|" + anim.frameWidth() + "x" + anim.frameHeight();
        List<BufferedImage> frames = SHEETS.get(cacheKey);
        if (frames == null) {
            frames = slice(anim);
            SHEETS.put(cacheKey, frames);
        }
        if (frames.isEmpty()) return null;
        int idx = Math.min(frames.size() - 1, anim.frameAt(stateTime));
        return frames.get(idx);
    }

    private static List<BufferedImage> slice(Cutscene.SheetAnim anim) {
        BufferedImage sheet = AssetLoader.loadImageOrNull(anim.sheet());
        if (sheet == null || sheet.getWidth() < anim.frameWidth()
                || sheet.getHeight() < anim.frameHeight()) {
            return List.of();
        }
        return SpriteSheet.fromImage(sheet, anim.frameWidth(), anim.frameHeight()).frames();
    }

    /** A simple tinted figure for actors without a working sheet. */
    public static synchronized BufferedImage placeholder(Cutscene.Actor actor) {
        BufferedImage img = PLACEHOLDERS.get(actor.key);
        if (img != null) return img;
        int s = 48;
        img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        Color body = Color.getHSBColor(
                (Math.floorMod(actor.key.hashCode(), 360)) / 360f, 0.55f, 0.85f);
        g.setColor(body);
        g.fillRoundRect(s / 4, s * 2 / 5, s / 2, s * 11 / 20, s / 5, s / 5);
        g.setColor(new Color(245, 220, 185));
        g.fillOval(s * 3 / 10, s / 12, s * 2 / 5, s * 2 / 5);
        g.setColor(new Color(40, 40, 50));
        int eye = Math.max(2, s / 14);
        g.fillOval(s * 2 / 5, s / 5, eye, eye);
        g.fillOval(s * 11 / 20, s / 5, eye, eye);
        g.dispose();
        PLACEHOLDERS.put(actor.key, img);
        return img;
    }

    /** Drop the sheet caches (skins reloaded, tests). */
    public static synchronized void clearCache() {
        SHEETS.clear();
        PLACEHOLDERS.clear();
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && fm.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
