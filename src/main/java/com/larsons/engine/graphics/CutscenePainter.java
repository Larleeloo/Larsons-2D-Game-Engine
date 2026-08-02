package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutscenePlayer;

import java.awt.Color;
import java.awt.Font;
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

    // Hoisted out of the draw loop: one allocation at class-load instead of
    // one per caption line per frame. (Not, as it turns out, for batching —
    // see RENDER_PLAN's B2 note: DrawStats keys flat shapes on nothing at all,
    // because a GL backend folds colour into the vertex and batches them
    // regardless. The allocation is the real cost, and it is the caller's.)
    private static final int BAR = Color.BLACK.getRGB();
    private static final int HINT = new Color(210, 210, 220).getRGB();
    private static final int CAPTION_BOX = new Color(10, 10, 18, 215).getRGB();
    private static final int CAPTION_EDGE = new Color(255, 255, 255, 60).getRGB();
    private static final int SPEAKER = new Color(255, 220, 120).getRGB();
    private static final int CAPTION = new Color(235, 235, 245).getRGB();

    private static final Map<String, List<BufferedImage>> SHEETS = new HashMap<>();
    private static final Map<String, BufferedImage> PLACEHOLDERS = new HashMap<>();

    private CutscenePainter() {}

    /** Draw every visible actor through the scene's camera. */
    public static void drawActors(DrawTarget target, Camera camera, CutscenePlayer player) {
        int[] corner = new int[2];
        for (CutscenePlayer.ActorView a : player.actors()) {
            if (!a.visible) continue;
            BufferedImage img = frame(a.def, a.state, a.stateTime);
            if (img == null) img = placeholder(a.def);
            int w = Math.max(8, (int) Math.round(a.def.sizePx * camera.zoom));
            camera.worldToScreen(a.x, a.y, corner);
            int dx = corner[0] - w / 2, dy = corner[1] - w;
            if (a.facingLeft) {
                target.drawImage(img, dx + w, dy, -w, w);
            } else {
                target.drawImage(img, dx, dy, w, w);
            }
        }
    }

    /** The letterbox bars, caption box, and skip hint (draw over the HUD). */
    public static void drawOverlay(DrawTarget target, int width, int height,
                                   CutscenePlayer player) {
        // Bars ease in over the first third of a second.
        int bar = (int) Math.round(height * BAR_FRACTION
                * Math.min(1, player.time() * 3));
        target.fillRect(0, 0, width, bar, BAR);
        target.fillRect(0, height - bar, width, bar, BAR);

        String hint = "▶ " + player.cutscene().name + "  ·  Enter/Esc skips";
        target.drawText(hint, width - target.textWidth(hint, HINT_FONT) - 10,
                Math.max(14, bar - 6), HINT_FONT, HINT);

        String caption = player.caption();
        if (caption.isEmpty()) return;
        List<String> lines = wrap(target, caption, CAPTION_FONT, (int) (width * 0.7));
        int lineH = target.textHeight(CAPTION_FONT);
        int boxW = 0;
        for (String line : lines) boxW = Math.max(boxW, target.textWidth(line, CAPTION_FONT));
        boxW += 28;
        String speaker = player.speaker();
        int boxH = lines.size() * lineH + 18 + (speaker.isEmpty() ? 0 : 16);
        int bx = (width - boxW) / 2;
        int by = height - Math.max(bar, 8) - boxH - 8;
        target.fillRoundRect(bx, by, boxW, boxH, 12, 12, CAPTION_BOX);
        target.drawRoundRect(bx, by, boxW, boxH, 12, 12, CAPTION_EDGE, 1f);
        int ty = by + 14;
        if (!speaker.isEmpty()) {
            target.drawText(speaker, bx + 14, ty + 4, SPEAKER_FONT, SPEAKER);
            ty += 16;
        }
        for (String line : lines) {
            ty += lineH;
            target.drawText(line, bx + 14, ty - 4, CAPTION_FONT, CAPTION);
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

    private static List<String> wrap(DrawTarget target, String text, Font font, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && target.textWidth(candidate, font) > maxWidth) {
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
