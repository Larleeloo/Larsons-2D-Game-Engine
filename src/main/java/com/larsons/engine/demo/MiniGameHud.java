package com.larsons.engine.demo;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.level.Level;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.minigame.Team;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
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

    private MiniGameHud() {}

    // --- world-space objectives -------------------------------------------------

    /** Draw the mode's objectives into the world (call between terrain and HUD). */
    static void drawWorld(Graphics2D g, Camera camera, Level level,
                          MiniGameView view, double animClock) {
        switch (view.mode) {
            case CTF -> drawCtf(g, camera, level, view, animClock);
            case STOCKPILE -> drawStockpiles(g, camera, level, animClock);
            case ESCORT -> drawEscort(g, camera, level, view);
            default -> { /* battle has no world objectives */ }
        }
    }

    private static void drawCtf(Graphics2D g, Camera camera, Level level,
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
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
            g.fillOval(p[0] - r, p[1] - r / 3, r * 2, (int) (r / 1.5));
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 160));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(p[0] - r, p[1] - r / 3, r * 2, (int) (r / 1.5));
        }
        // The flags themselves live in the replicated state (base / carried /
        // dropped); dropped ones pulse so they're easy to spot.
        for (MiniGameView.FlagView f : view.flags) {
            boolean dropped = f.state() == MiniGame.Flag.DROPPED;
            float pulse = dropped
                    ? 0.7f + 0.3f * (float) Math.abs(Math.sin(animClock * 4)) : 1f;
            drawFlag(g, camera, f.x(), f.y(), Team.color(f.team()),
                    Math.max(10, (int) (ts * 0.9 * camera.zoom)), pulse);
        }
    }

    /** A flag glyph anchored at its base point: pole + waving banner. */
    private static void drawFlag(Graphics2D g, Camera camera, double x, double y,
                                 Color c, int h, float alpha) {
        int[] p = new int[2];
        camera.worldToScreen(x, y, p);
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(230, 230, 240, (int) (255 * alpha)));
        g.drawLine(p[0], p[1], p[0], p[1] - h);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * alpha)));
        g.fillPolygon(new int[]{p[0], p[0] + (int) (h * 0.65), p[0]},
                new int[]{p[1] - h, p[1] - h + h / 4, p[1] - h + h / 2}, 3);
    }

    private static void drawStockpiles(Graphics2D g, Camera camera, Level level,
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
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 34));
            g.fillOval(p[0] - ring, p[1] - ring / 2, ring * 2, ring);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(p[0] - ring, p[1] - ring / 2, ring * 2, ring);
            // The crate itself.
            int s = Math.max(8, (int) (ts * 0.8 * camera.zoom));
            g.setColor(new Color(90, 65, 40));
            g.fillRoundRect(p[0] - s / 2, p[1] - s, s, s, s / 5, s / 5);
            g.setColor(c);
            g.setStroke(new BasicStroke(Math.max(2f, s / 10f)));
            g.drawRoundRect(p[0] - s / 2, p[1] - s, s, s, s / 5, s / 5);
            g.drawLine(p[0] - s / 2, p[1] - s / 2, p[0] + s / 2, p[1] - s / 2);
        }
    }

    private static void drawEscort(Graphics2D g, Camera camera, Level level,
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
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{8, 8}, 0));
        g.setColor(new Color(240, 220, 130, 130));
        int px = 0, py = 0;
        for (int i = 0; i < path.size(); i++) {
            camera.worldToScreen(path.get(i).x, path.get(i).y, p);
            if (i > 0) g.drawLine(px, py, p[0], p[1]);
            px = p[0];
            py = p[1];
            int r = Math.max(3, (int) (5 * camera.zoom));
            g.fillOval(p[0] - r, p[1] - r, r * 2, r * 2);
        }
        if (!view.hasPayload) return;
        // The payload cart.
        camera.worldToScreen(view.payloadX, view.payloadY, p);
        int w = Math.max(12, (int) (ts * 1.2 * camera.zoom));
        int h = Math.max(8, (int) (ts * 0.7 * camera.zoom));
        Color c = Team.color(0);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(70, 70, 84));
        g.fillRoundRect(p[0] - w / 2, p[1] - h, w, h, h / 3, h / 3);
        g.setColor(c);
        g.drawRoundRect(p[0] - w / 2, p[1] - h, w, h, h / 3, h / 3);
        int wheel = Math.max(3, h / 3);
        g.setColor(new Color(40, 40, 50));
        g.fillOval(p[0] - w / 2 + wheel / 2, p[1] - wheel / 2, wheel, wheel);
        g.fillOval(p[0] + w / 2 - wheel - wheel / 2, p[1] - wheel / 2, wheel, wheel);
    }

    /** A soft team-coloured ellipse under a player's feet. */
    static void drawTeamRing(Graphics2D g, Camera camera, double footCenterX,
                             double footCenterY, double sizePx, int team, double zoom) {
        if (team < 0) return;
        int[] p = new int[2];
        camera.worldToScreen(footCenterX, footCenterY, p);
        Color c = Team.color(team);
        int rx = Math.max(6, (int) (sizePx * 0.62 * zoom));
        int ry = Math.max(3, rx / 3);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(p[0] - rx, p[1] - ry, rx * 2, ry * 2);
    }

    // --- screen-space HUD ---------------------------------------------------------

    /** Scoreboard, escort progress/clock, your team, and the winner banner. */
    static void drawHud(Graphics2D g, int vw, int vh, MiniGameView view, int localPlayerId) {
        if (view.mode == MiniGameConfig.Mode.NONE) return;
        int y = 46;

        // Mode title + team score pills, centred under the top HUD strip.
        g.setFont(HUD_FONT);
        String title = view.mode.displayName;
        int pillW = 86, pillH = 22, gap = 8;
        int titleW = g.getFontMetrics().stringWidth(title);
        int total = titleW + gap + view.teams * (pillW + gap) - gap;
        int x = (vw - total) / 2;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x - 10, y - 4, total + 20, pillH + 8, 10, 10);
        g.setColor(new Color(235, 235, 245));
        g.drawString(title, x, y + 16);
        x += titleW + gap;
        for (int t = 0; t < view.teams; t++) {
            Color c = Team.color(t);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
            g.fillRoundRect(x, y, pillW, pillH, 8, 8);
            g.setColor(Color.BLACK);
            String s = Team.name(t) + " " + view.score(t)
                    + (view.mode == MiniGameConfig.Mode.ESCORT ? "" : "/" + view.scoreLimit);
            g.drawString(s, x + (pillW - g.getFontMetrics().stringWidth(s)) / 2, y + 16);
            x += pillW + gap;
        }
        y += pillH + 10;

        // Escort: payload progress bar + the defenders' clock.
        if (view.mode == MiniGameConfig.Mode.ESCORT && view.hasPayload) {
            int bw = 260, bh = 10;
            int bx = (vw - bw) / 2;
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(bx - 6, y - 4, bw + 12, bh + 24, 8, 8);
            g.setColor(new Color(60, 60, 74));
            g.fillRect(bx, y, bw, bh);
            g.setColor(Team.color(0));
            g.fillRect(bx, y, (int) (bw * Math.max(0, Math.min(1, view.payloadProgress))), bh);
            int secs = (int) Math.ceil(view.timeLeft);
            String clock = String.format("%d:%02d", secs / 60, secs % 60);
            g.setFont(SMALL_FONT);
            g.setColor(new Color(230, 230, 240));
            g.drawString("Payload " + (int) (view.payloadProgress * 100) + "%   ·   "
                            + clock + " left",
                    bx, y + bh + 14);
            y += bh + 28;
        }

        // Which team you're on.
        int myTeam = view.teamOf(localPlayerId);
        if (myTeam >= 0) {
            g.setFont(SMALL_FONT);
            String s = "You are on the " + Team.name(myTeam) + " team"
                    + (view.pvp ? "" : "  ·  PvP off");
            int tw = g.getFontMetrics().stringWidth(s);
            Color c = Team.color(myTeam);
            g.setColor(new Color(0, 0, 0, 130));
            g.fillRoundRect((vw - tw) / 2 - 8, y - 2, tw + 16, 18, 8, 8);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue()));
            g.drawString(s, (vw - tw) / 2, y + 11);
        }

        // Winner banner while the round is over.
        if (view.ended && view.winner >= 0) {
            Color c = Team.color(view.winner);
            g.setFont(BANNER_FONT);
            String s = Team.name(view.winner) + " team wins!";
            int tw = g.getFontMetrics().stringWidth(s);
            int bx = (vw - tw) / 2, by = vh / 2 - 40;
            g.setColor(new Color(0, 0, 0, 190));
            g.fillRoundRect(bx - 24, by - 36, tw + 48, 78, 16, 16);
            g.setColor(c);
            g.drawString(s, bx, by);
            g.setFont(HUD_FONT);
            g.setColor(new Color(220, 220, 230));
            String next = "Next round in " + (int) Math.ceil(view.resetIn) + "…";
            g.drawString(next, (vw - g.getFontMetrics().stringWidth(next)) / 2, by + 26);
        }
    }
}
