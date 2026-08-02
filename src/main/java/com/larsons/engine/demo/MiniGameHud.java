package com.larsons.engine.demo;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.level.Level;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.minigame.Team;

import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a running mini game: the in-world objectives (flag bases and flags,
 * stockpile deposit crates, the escort path and payload cart) and the HUD
 * (team score pills, escort progress + clock, your team, the winner banner).
 *
 * <p>Everything draws from a {@link MiniGameView}, so the identical code
 * serves online play (view parsed from server {@code mg} broadcasts) and
 * offline play (view built from the local referee each frame).
 */
final class MiniGameHud {

    private static final Font HUD_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font BANNER_FONT = new Font("SansSerif", Font.BOLD, 30);

    // Hoisted: drawn every frame the mode is running. The team colours cannot
    // be hoisted — they are looked up per team — so argb() below builds their
    // packed form without a Color allocation.
    private static final int PATH_LINE = new Color(240, 220, 130, 130).getRGB();
    private static final int CRATE_BODY = new Color(90, 65, 40).getRGB();
    private static final int CART_BODY = new Color(70, 70, 84).getRGB();
    private static final int CART_WHEEL = new Color(40, 40, 50).getRGB();
    private static final int SCRIM = new Color(0, 0, 0, 150).getRGB();
    private static final int SCRIM_LIGHT = new Color(0, 0, 0, 130).getRGB();
    private static final int SCRIM_HEAVY = new Color(0, 0, 0, 190).getRGB();
    private static final int HUD_TEXT = new Color(235, 235, 245).getRGB();
    private static final int PILL_TEXT = Color.BLACK.getRGB();
    private static final int BAR_TRACK = new Color(60, 60, 74).getRGB();
    private static final int BAR_TEXT = new Color(230, 230, 240).getRGB();
    private static final int NEXT_ROUND = new Color(220, 220, 230).getRGB();
    private static final Color POLE = new Color(230, 230, 240);

    private MiniGameHud() {}

    /** {@code c} at {@code alpha}, as a packed argb — no Color per call. */
    private static int argb(Color c, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24)
                | (c.getRGB() & 0x00FFFFFF);
    }

    // --- world-space objectives -------------------------------------------------

    /** Draw the mode's objectives into the world (call between terrain and HUD). */
    static void drawWorld(DrawTarget target, Camera camera, Level level,
                          MiniGameView view, double animClock) {
        switch (view.mode) {
            case CTF -> drawCtf(target, camera, level, view, animClock);
            case STOCKPILE -> drawStockpiles(target, camera, level, animClock);
            case ESCORT -> drawEscort(target, camera, level, view);
            default -> { /* battle has no world objectives */ }
        }
    }

    private static void drawCtf(DrawTarget target, Camera camera, Level level,
                                MiniGameView view, double animClock) {
        int[] p = new int[2];
        double ts = level.tileSize;
        // Base pedestals come from the painted markers (they never move).
        for (Level.EntitySpawn e : level.entities) {
            if (!MiniGame.KIND_FLAG.equals(e.kind)) continue;
            int team = Team.fromMarkerType(e.type);
            if (team < 0) continue;
            camera.worldToScreen(e.x, e.y, p);
            int r = Math.max(6, (int) (ts * 0.7 * camera.zoom));
            Color c = Team.color(team);
            target.fillOval(p[0] - r, p[1] - r / 3, r * 2, (int) (r / 1.5), argb(c, 60));
            target.drawOval(p[0] - r, p[1] - r / 3, r * 2, (int) (r / 1.5), argb(c, 160), 2f);
        }
        // The flags themselves live in the replicated state (base / carried /
        // dropped); dropped ones pulse so they're easy to spot.
        for (MiniGameView.FlagView f : view.flags) {
            boolean dropped = f.state() == MiniGame.Flag.DROPPED;
            float pulse = dropped
                    ? 0.7f + 0.3f * (float) Math.abs(Math.sin(animClock * 4)) : 1f;
            drawFlag(target, camera, f.x(), f.y(), Team.color(f.team()),
                    Math.max(10, (int) (ts * 0.9 * camera.zoom)), pulse);
        }
    }

    /** A flag glyph anchored at its base point: pole + waving banner. */
    private static void drawFlag(DrawTarget target, Camera camera, double x, double y,
                                 Color c, int h, float alpha) {
        int[] p = new int[2];
        camera.worldToScreen(x, y, p);
        int a = (int) (255 * alpha);
        target.drawLine(p[0], p[1], p[0], p[1] - h, argb(POLE, a), 2.5f);
        target.fillPolygon(new int[]{p[0], p[0] + (int) (h * 0.65), p[0]},
                new int[]{p[1] - h, p[1] - h + h / 4, p[1] - h + h / 2}, 3, argb(c, a));
    }

    private static void drawStockpiles(DrawTarget target, Camera camera, Level level,
                                       double animClock) {
        int[] p = new int[2];
        double ts = level.tileSize;
        for (Level.EntitySpawn e : level.entities) {
            if (!MiniGame.KIND_STOCKPILE.equals(e.kind)) continue;
            int team = Team.fromMarkerType(e.type);
            if (team < 0) continue;
            Color c = Team.color(team);
            camera.worldToScreen(e.x, e.y, p);
            // The deposit ring breathes gently so it reads as a live zone.
            int ring = (int) (1.8 * ts * camera.zoom * (1 + 0.03 * Math.sin(animClock * 2)));
            target.fillOval(p[0] - ring, p[1] - ring / 2, ring * 2, ring, argb(c, 34));
            target.drawOval(p[0] - ring, p[1] - ring / 2, ring * 2, ring, argb(c, 120), 2f);
            // The crate itself.
            int s = Math.max(8, (int) (ts * 0.8 * camera.zoom));
            float edge = Math.max(2f, s / 10f);
            target.fillRoundRect(p[0] - s / 2, p[1] - s, s, s, s / 5, s / 5, CRATE_BODY);
            target.drawRoundRect(p[0] - s / 2, p[1] - s, s, s, s / 5, s / 5,
                    c.getRGB(), edge);
            target.drawLine(p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] - s / 2,
                    c.getRGB(), edge);
        }
    }

    private static void drawEscort(DrawTarget target, Camera camera, Level level,
                                   MiniGameView view) {
        int[] p = new int[2];
        double ts = level.tileSize;
        // The painted waypoint path, in order.
        List<Level.EntitySpawn> path = new ArrayList<>();
        for (Level.EntitySpawn e : level.entities) {
            if (MiniGame.KIND_PATH.equals(e.kind)) path.add(e);
        }
        path.sort((a, b) -> Integer.compare(
                MiniGame.pathIndex(a.type), MiniGame.pathIndex(b.type)));
        int px = 0, py = 0;
        for (int i = 0; i < path.size(); i++) {
            camera.worldToScreen(path.get(i).x, path.get(i).y, p);
            if (i > 0) target.drawDashedLine(px, py, p[0], p[1], PATH_LINE, 2f, 8f, 8f);
            px = p[0];
            py = p[1];
            int r = Math.max(3, (int) (5 * camera.zoom));
            target.fillOval(p[0] - r, p[1] - r, r * 2, r * 2, PATH_LINE);
        }
        if (!view.hasPayload) return;
        // The payload cart.
        camera.worldToScreen(view.payloadX, view.payloadY, p);
        int w = Math.max(12, (int) (ts * 1.2 * camera.zoom));
        int h = Math.max(8, (int) (ts * 0.7 * camera.zoom));
        Color c = Team.color(0);
        target.fillRoundRect(p[0] - w / 2, p[1] - h, w, h, h / 3, h / 3, CART_BODY);
        target.drawRoundRect(p[0] - w / 2, p[1] - h, w, h, h / 3, h / 3, c.getRGB(), 2f);
        int wheel = Math.max(3, h / 3);
        target.fillOval(p[0] - w / 2 + wheel / 2, p[1] - wheel / 2, wheel, wheel, CART_WHEEL);
        target.fillOval(p[0] + w / 2 - wheel - wheel / 2, p[1] - wheel / 2,
                wheel, wheel, CART_WHEEL);
    }

    /** A soft team-coloured ellipse under a player's feet. */
    static void drawTeamRing(DrawTarget target, Camera camera, double footCenterX,
                             double footCenterY, double sizePx, int team, double zoom) {
        if (team < 0) return;
        int[] p = new int[2];
        camera.worldToScreen(footCenterX, footCenterY, p);
        Color c = Team.color(team);
        int rx = Math.max(6, (int) (sizePx * 0.62 * zoom));
        int ry = Math.max(3, rx / 3);
        target.drawOval(p[0] - rx, p[1] - ry, rx * 2, ry * 2, argb(c, 90), 2.5f);
    }

    // --- screen-space HUD ---------------------------------------------------------

    /** Scoreboard, escort progress/clock, your team, and the winner banner. */
    static void drawHud(DrawTarget target, int vw, int vh,
                        MiniGameView view, int localPlayerId) {
        if (view.mode == MiniGameConfig.Mode.NONE) return;
        int y = 46;

        // Mode title + team score pills, centred under the top HUD strip.
        String title = view.mode.displayName;
        int pillW = 86, pillH = 22, gap = 8;
        int titleW = target.textWidth(title, HUD_FONT);
        int total = titleW + gap + view.teams * (pillW + gap) - gap;
        int x = (vw - total) / 2;
        target.fillRoundRect(x - 10, y - 4, total + 20, pillH + 8, 10, 10, SCRIM);
        target.drawText(title, x, y + 16, HUD_FONT, HUD_TEXT);
        x += titleW + gap;
        for (int t = 0; t < view.teams; t++) {
            Color c = Team.color(t);
            target.fillRoundRect(x, y, pillW, pillH, 8, 8, argb(c, 200));
            String s = Team.name(t) + " " + view.score(t)
                    + (view.mode == MiniGameConfig.Mode.ESCORT ? "" : "/" + view.scoreLimit);
            target.drawText(s, x + (pillW - target.textWidth(s, HUD_FONT)) / 2, y + 16,
                    HUD_FONT, PILL_TEXT);
            x += pillW + gap;
        }
        y += pillH + 10;

        // Escort: payload progress bar + the defenders' clock.
        if (view.mode == MiniGameConfig.Mode.ESCORT && view.hasPayload) {
            int bw = 260, bh = 10;
            int bx = (vw - bw) / 2;
            target.fillRoundRect(bx - 6, y - 4, bw + 12, bh + 24, 8, 8, SCRIM);
            target.fillRect(bx, y, bw, bh, BAR_TRACK);
            target.fillRect(bx, y,
                    (int) (bw * Math.max(0, Math.min(1, view.payloadProgress))), bh,
                    Team.color(0));
            int secs = (int) Math.ceil(view.timeLeft);
            String clock = String.format("%d:%02d", secs / 60, secs % 60);
            target.drawText("Payload " + (int) (view.payloadProgress * 100) + "%   ·   "
                            + clock + " left",
                    bx, y + bh + 14, SMALL_FONT, BAR_TEXT);
            y += bh + 28;
        }

        // Which team you're on.
        int myTeam = view.teamOf(localPlayerId);
        if (myTeam >= 0) {
            String s = "You are on the " + Team.name(myTeam) + " team"
                    + (view.pvp ? "" : "  ·  PvP off");
            int tw = target.textWidth(s, SMALL_FONT);
            Color c = Team.color(myTeam);
            target.fillRoundRect((vw - tw) / 2 - 8, y - 2, tw + 16, 18, 8, 8, SCRIM_LIGHT);
            target.drawText(s, (vw - tw) / 2, y + 11, SMALL_FONT, argb(c, 255));
        }

        // Winner banner while the round is over.
        if (view.ended && view.winner >= 0) {
            Color c = Team.color(view.winner);
            String s = Team.name(view.winner) + " team wins!";
            int tw = target.textWidth(s, BANNER_FONT);
            int bx = (vw - tw) / 2, by = vh / 2 - 40;
            target.fillRoundRect(bx - 24, by - 36, tw + 48, 78, 16, 16, SCRIM_HEAVY);
            target.drawText(s, bx, by, BANNER_FONT, c);
            String next = "Next round in " + (int) Math.ceil(view.resetIn) + "…";
            target.drawText(next, (vw - target.textWidth(next, HUD_FONT)) / 2, by + 26,
                    HUD_FONT, NEXT_ROUND);
        }
    }
}
