package com.larsons.engine.demo;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.save.RunRecord;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.UiText;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pause screen: the actions on the left, and on the right an answer to
 * "what is going on in this run".
 *
 * <p><b>Why a pause menu is more than a list of buttons.</b> Pausing is the one
 * moment a player is deliberately not playing, and it is when the questions they
 * cannot ask mid-fight arrive: how far through this goal am I, how long have I
 * been at this, when did it last save, what is this character actually good at,
 * what was that key again. Every one of those was answerable somewhere in the
 * engine already — the stat-rule bars are on the HUD, the play clock is in the
 * run record, the binds are in the controls sheet — and none of them was
 * answerable <em>here</em>, which is where they are wanted.
 *
 * <p>The screen is a pure function of a {@link Status} the scene fills each
 * frame. It owns no game state, reaches into nothing, and can therefore be
 * rendered by a golden-frame test from a hand-built status without a world, a
 * window or a run behind it.
 *
 * <p>It degrades rather than breaking: below {@link #TWO_COLUMN_MIN_WIDTH} the
 * panels are dropped and the actions take the whole width, and the right column
 * stops drawing panels when it runs out of vertical room rather than spilling
 * off the bottom. The panel order is therefore also a priority order.
 */
final class PauseScreen {

    /** Below this width the panels are dropped and the actions get everything. */
    static final int TWO_COLUMN_MIN_WIDTH = 780;

    private static final int INSET = 28;
    private static final int GAP = 9;
    private static final int PANEL_PAD = 8;
    /** Height of a progress/vitals bar. */
    private static final int BAR_H = 6;

    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 34);
    private static final Font SUBTITLE = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font PANEL_TITLE = new Font("SansSerif", Font.BOLD, 13);
    private static final Font ROW = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font ROW_BOLD = new Font("SansSerif", Font.BOLD, 14);
    private static final Font TINY = new Font("SansSerif", Font.PLAIN, 12);

    private static final Color SCRIM = new Color(10, 10, 16);
    private static final Color PANEL = new Color(26, 27, 38, 235);
    private static final Color PANEL_EDGE = new Color(58, 60, 78);
    private static final Color TITLE_COLOR = new Color(245, 245, 255);
    private static final Color LABEL = new Color(140, 144, 162);
    private static final Color VALUE = new Color(222, 226, 240);
    private static final Color HEADING = new Color(150, 170, 220);
    private static final Color BAR_BACK = new Color(48, 50, 64);

    private static final Color HEALTH = new Color(220, 70, 70);
    private static final Color MANA = new Color(90, 150, 235);
    private static final Color STAMINA = new Color(120, 200, 110);
    private static final Color ULTIMATE = new Color(230, 190, 80);
    private static final Color GOAL = new Color(240, 200, 90);
    private static final Color GOAL_DONE = new Color(140, 215, 140);

    private static final Color SAVED = new Color(140, 200, 140);
    private static final Color UNSAVED = new Color(240, 190, 90);
    private static final Color WRITING = new Color(150, 180, 240);

    private PauseScreen() {}

    /**
     * One goal the level sets, as the pause screen shows it: what it is
     * measuring, where the player is against it, and whether it is settled.
     *
     * @param label    the stat being measured ("Blocks Mined")
     * @param detail   how far along ("57 / 50", or "done ×3")
     * @param progress 0..1 toward the next firing
     * @param done     a one-shot rule that has already paid out
     */
    record Objective(String label, String detail, double progress, boolean done) {}

    /**
     * Everything the pause screen draws, as the scene sees it.
     *
     * <p>Public mutable fields rather than a constructor with twenty
     * parameters, matching {@code GameProfile} and {@code PlayerState}: this is
     * a bag of readings taken once a frame, not an object with behaviour.
     */
    static final class Status {
        String gameType = "";
        String levelName = "";
        String characterName = "";
        String ultimateName = "";
        /** "Side-Scroller · up is …" — which physics this level runs. */
        String world = "";
        /** Third/first person, when the level has a height axis; else "". */
        String viewpoint = "";

        double health, maxHealth;
        double mana, maxMana;
        double stamina, maxStamina;
        double ultCharge;

        /** {@code null} offline-with-no-run and online, where there is no run. */
        String slot;
        double playSeconds;
        long savedAt;
        boolean unsaved;
        boolean writing;

        /** Negative when the level has no day/night clock worth naming. */
        double timeOfDay = -1;

        Map<String, Double> stats = new LinkedHashMap<>();
        List<Objective> objectives = new ArrayList<>();

        boolean online;
        int players;
        int ping = -1;
        boolean host;
        int port;

        /** The handful of binds worth restating where a player is stuck. */
        List<String[]> keyHints = new ArrayList<>();
    }

    /**
     * Draw the whole screen.
     *
     * @param form the action list, which is laid out into the left column (or
     *             the whole width when the viewport is too narrow for panels)
     */
    static void render(DrawTarget target, int vw, int vh, ConfigForm form, Status status) {
        target.pushAlpha(0.9f);
        target.fillRect(0, 0, vw, vh, SCRIM);
        target.popAlpha();

        int headerBottom = drawHeader(target, vw, status);
        int contentTop = headerBottom + GAP;
        int contentBottom = vh - 34;

        boolean twoColumn = vw >= TWO_COLUMN_MIN_WIDTH;
        int actionsW = twoColumn
                ? Math.max(300, Math.min(400, (int) (vw * 0.32)))
                : vw - INSET * 2;
        form.showTitle(false)
                .region(INSET, contentTop, actionsW, Math.max(80, contentBottom - contentTop));
        form.render(target, vw, vh);

        if (twoColumn) {
            int panelsX = INSET + actionsW + GAP;
            int panelsW = vw - panelsX - INSET;
            drawPanels(target, panelsX, contentTop, panelsW, contentBottom, status);
        }
    }

    // --- header ----------------------------------------------------------------

    /** "Paused", the game type and level, and the save state. Returns its bottom. */
    private static int drawHeader(DrawTarget target, int vw, Status status) {
        int y = INSET + target.textAscent(TITLE);
        target.drawText("Paused", INSET, y, TITLE, TITLE_COLOR);

        String where = status.gameType
                + (status.levelName.isBlank() ? "" : "  ·  " + status.levelName);
        int subY = y + target.textHeight(SUBTITLE) + 2;
        target.drawText(UiText.fit(target, SUBTITLE, where, vw - INSET * 2 - 220),
                INSET, subY, SUBTITLE, LABEL);

        drawSaveChip(target, vw, status);
        return subY + 8;
    }

    /**
     * The save state, top right — the single most useful thing a pause screen
     * can tell somebody about to walk away from the keyboard.
     */
    private static void drawSaveChip(DrawTarget target, int vw, Status status) {
        String text;
        Color tint;
        if (status.online) {
            text = "Online · the server owns this world";
            tint = LABEL;
        } else if (status.writing) {
            text = "Saving…";
            tint = WRITING;
        } else if (status.unsaved) {
            text = "Unsaved changes";
            tint = UNSAVED;
        } else if (status.savedAt <= 0) {
            text = "Not saved yet";
            tint = UNSAVED;
        } else {
            text = "Saved " + ago(System.currentTimeMillis() - status.savedAt);
            tint = SAVED;
        }
        int tw = target.textWidth(text, ROW_BOLD);
        int x = vw - INSET - tw;
        int y = INSET + target.textAscent(ROW_BOLD);
        target.fillRoundRect(x - 10, y - target.textAscent(ROW_BOLD) - 5, tw + 20,
                target.textHeight(ROW_BOLD) + 10, 8, 8, PANEL);
        target.drawText(text, x, y, ROW_BOLD, tint);
    }

    // --- panels ----------------------------------------------------------------

    /**
     * Stack the panels down the right column, in priority order, stopping when
     * the next one would not fit. A short window shows the run and the vitals;
     * a tall one shows everything.
     */
    private static void drawPanels(DrawTarget target, int x, int top, int w, int bottom,
                                   Status status) {
        int y = top;
        y = panel(target, x, y, w, bottom, "THE RUN", runRows(status));
        y = vitalsPanel(target, x, y, w, bottom, status);
        y = objectivesPanel(target, x, y, w, bottom, status);
        if (status.online) y = panel(target, x, y, w, bottom, "SESSION", sessionRows(status));
        y = statsPanel(target, x, y, w, bottom, status);
        keysPanel(target, x, y, w, bottom, status);
    }

    private static List<String[]> runRows(Status status) {
        List<String[]> rows = new ArrayList<>();
        if (!status.characterName.isBlank()) rows.add(new String[] {"Character", status.characterName});
        if (!status.ultimateName.isBlank()) rows.add(new String[] {"Ultimate", status.ultimateName});
        // World and viewpoint on one row: they are one sentence about which
        // physics this level runs, and a panel is short of lines long before it
        // is short of width.
        String world = status.world;
        if (!status.viewpoint.isBlank()) {
            world = world.isBlank() ? status.viewpoint : world + " · " + status.viewpoint;
        }
        if (!world.isBlank()) rows.add(new String[] {"World", world});
        if (status.timeOfDay >= 0) rows.add(new String[] {"Time", timeOfDayName(status.timeOfDay)});
        if (!status.online) {
            rows.add(new String[] {"Played", RunRecord.formatPlayTime(status.playSeconds)});
            if (status.slot != null) rows.add(new String[] {"Save slot", status.slot});
        }
        return rows;
    }

    private static List<String[]> sessionRows(Status status) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Players", String.valueOf(Math.max(1, status.players))});
        if (status.ping >= 0) rows.add(new String[] {"Ping", status.ping + " ms"});
        rows.add(new String[] {"Role", status.host ? "Hosting on :" + status.port : "Client"});
        return rows;
    }

    /** Health, mana, stamina and the ultimate meter, as bars with numbers. */
    private static int vitalsPanel(DrawTarget target, int x, int y, int w, int bottom,
                                   Status status) {
        List<Object[]> bars = new ArrayList<>();
        if (status.maxHealth > 0) {
            bars.add(new Object[] {"Health", status.health, status.maxHealth, HEALTH});
        }
        if (status.maxMana > 0) bars.add(new Object[] {"Mana", status.mana, status.maxMana, MANA});
        if (status.maxStamina > 0) {
            bars.add(new Object[] {"Stamina", status.stamina, status.maxStamina, STAMINA});
        }
        if (!status.ultimateName.isBlank()) {
            bars.add(new Object[] {"Ultimate", status.ultCharge * 100, 100.0, ULTIMATE});
        }
        if (bars.isEmpty()) return y;

        int rowH = 27;
        int h = PANEL_PAD * 2 + target.textHeight(PANEL_TITLE) + 6 + bars.size() * rowH;
        if (y + h > bottom) return y;

        card(target, x, y, w, h);
        int inner = x + PANEL_PAD;
        int innerW = w - PANEL_PAD * 2;
        int cy = y + PANEL_PAD + target.textAscent(PANEL_TITLE);
        target.drawText("VITALS", inner, cy, PANEL_TITLE, HEADING);
        cy += 6;

        // Laid out from the top of each row rather than by nudging offsets off
        // its bottom: a label and a bar stacked in one row is exactly the place
        // where relative offsets end up a pixel apart and look like a mistake.
        for (Object[] bar : bars) {
            String label = (String) bar[0];
            double value = (Double) bar[1], max = (Double) bar[2];
            Color tint = (Color) bar[3];
            int rowTop = cy;
            int textY = rowTop + target.textAscent(ROW);
            target.drawText(label, inner, textY, ROW, LABEL);
            String amount = "Ultimate".equals(label)
                    ? Math.round(value) + "%"
                    : (long) Math.ceil(value) + " / " + (long) max;
            target.drawText(amount, inner + innerW - target.textWidth(amount, ROW), textY,
                    ROW, VALUE);
            bar(target, inner, textY + 5, innerW, BAR_H, max <= 0 ? 0 : value / max, tint);
            cy = rowTop + rowH;
        }
        return y + h + GAP;
    }

    /**
     * The level's authored goals. This is the panel that makes a pause screen
     * worth opening in a game that has any: the HUD only shows the rules whose
     * author ticked "show bar", and only while playing.
     */
    private static int objectivesPanel(DrawTarget target, int x, int y, int w, int bottom,
                                       Status status) {
        int rowH = 27;
        int rows = Math.max(1, status.objectives.size());
        int h = PANEL_PAD * 2 + target.textHeight(PANEL_TITLE) + 6 + rows * rowH;
        if (y + h > bottom) return y;

        card(target, x, y, w, h);
        int inner = x + PANEL_PAD;
        int innerW = w - PANEL_PAD * 2;
        int cy = y + PANEL_PAD + target.textAscent(PANEL_TITLE);
        target.drawText("GOALS", inner, cy, PANEL_TITLE, HEADING);
        cy += 6;

        if (status.objectives.isEmpty()) {
            target.drawText("This level sets no goals.", inner, cy + 18, ROW, LABEL);
            return y + h + GAP;
        }
        for (Objective o : status.objectives) {
            int rowTop = cy;
            int textY = rowTop + target.textAscent(ROW);
            Color tint = o.done() ? GOAL_DONE : GOAL;
            target.drawText(UiText.fit(target, ROW, o.label(), innerW - 90), inner, textY,
                    ROW, o.done() ? GOAL_DONE : VALUE);
            target.drawText(o.detail(), inner + innerW - target.textWidth(o.detail(), TINY),
                    textY, TINY, LABEL);
            bar(target, inner, textY + 5, innerW, BAR_H, o.progress(), tint);
            cy = rowTop + rowH;
        }
        return y + h + GAP;
    }

    /** Every non-zero counter, two to a line. */
    private static int statsPanel(DrawTarget target, int x, int y, int w, int bottom,
                                  Status status) {
        List<String[]> entries = new ArrayList<>();
        for (Map.Entry<String, Double> e : status.stats.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) continue;
            entries.add(new String[] {PlayerStats.label(e.getKey()), number(e.getValue())});
        }
        if (entries.isEmpty()) return y;

        int lines = (entries.size() + 1) / 2;
        int rowH = 17;
        int h = PANEL_PAD * 2 + target.textHeight(PANEL_TITLE) + 6 + lines * rowH;
        if (y + h > bottom) return y;

        card(target, x, y, w, h);
        int inner = x + PANEL_PAD;
        int innerW = w - PANEL_PAD * 2;
        int colW = innerW / 2;
        int cy = y + PANEL_PAD + target.textAscent(PANEL_TITLE);
        target.drawText("THIS RUN", inner, cy, PANEL_TITLE, HEADING);
        cy += 8;

        for (int i = 0; i < entries.size(); i++) {
            int col = i % 2;
            int lineY = cy + (i / 2 + 1) * rowH;
            int cx = inner + col * colW;
            String label = UiText.fit(target, TINY, entries.get(i)[0], colW - 46);
            target.drawText(label, cx, lineY, TINY, LABEL);
            String value = entries.get(i)[1];
            target.drawText(value, cx + colW - 8 - target.textWidth(value, TINY), lineY,
                    TINY, VALUE);
        }
        return y + h + GAP;
    }

    /** A short reminder of the binds people forget, read live from the binds. */
    private static void keysPanel(DrawTarget target, int x, int y, int w, int bottom,
                                  Status status) {
        if (status.keyHints.isEmpty()) return;
        int rowH = 16;
        int lines = (status.keyHints.size() + 1) / 2;
        int h = PANEL_PAD * 2 + target.textHeight(PANEL_TITLE) + 4 + lines * rowH;
        if (y + h > bottom) return;

        card(target, x, y, w, h);
        int inner = x + PANEL_PAD;
        int innerW = w - PANEL_PAD * 2;
        int colW = innerW / 2;
        int cy = y + PANEL_PAD + target.textAscent(PANEL_TITLE);
        target.drawText("KEYS", inner, cy, PANEL_TITLE, HEADING);
        cy += 6;

        for (int i = 0; i < status.keyHints.size(); i++) {
            String[] hint = status.keyHints.get(i);
            int lineY = cy + (i / 2 + 1) * rowH;
            int cx = inner + (i % 2) * colW;
            String key = "[" + hint[0] + "]";
            target.drawText(key, cx, lineY, TINY, GOAL);
            target.drawText(UiText.fit(target, TINY, hint[1], colW - target.textWidth(key, TINY) - 14),
                    cx + target.textWidth(key, TINY) + 6, lineY, TINY, LABEL);
        }
    }

    // --- primitives ------------------------------------------------------------

    /** A titled panel of label→value rows; returns the y to continue from. */
    private static int panel(DrawTarget target, int x, int y, int w, int bottom,
                             String title, List<String[]> rows) {
        if (rows.isEmpty()) return y;
        int rowH = 18;
        int h = PANEL_PAD * 2 + target.textHeight(PANEL_TITLE) + 4 + rows.size() * rowH;
        if (y + h > bottom) return y;

        card(target, x, y, w, h);
        int inner = x + PANEL_PAD;
        int innerW = w - PANEL_PAD * 2;
        int cy = y + PANEL_PAD + target.textAscent(PANEL_TITLE);
        target.drawText(title, inner, cy, PANEL_TITLE, HEADING);
        cy += 6;

        for (String[] row : rows) {
            cy += rowH;
            target.drawText(row[0], inner, cy, ROW, LABEL);
            String value = UiText.fit(target, ROW, row[1], innerW - 96);
            target.drawText(value, inner + innerW - target.textWidth(value, ROW), cy, ROW, VALUE);
        }
        return y + h + GAP;
    }

    private static void card(DrawTarget target, int x, int y, int w, int h) {
        target.fillRoundRect(x, y, w, h, 10, 10, PANEL);
        target.drawRoundRect(x, y, w, h, 10, 10, PANEL_EDGE, 1f);
    }

    private static void bar(DrawTarget target, int x, int y, int w, int h, double t, Color tint) {
        target.fillRoundRect(x, y, w, h, h, h, BAR_BACK);
        int filled = (int) (w * Math.max(0, Math.min(1, t)));
        if (filled > 0) target.fillRoundRect(x, y, Math.max(h, filled), h, h, h, tint);
    }

    // --- formatting ------------------------------------------------------------

    /** "12s ago" / "4m ago" / "2h ago" — a delta a player reads, not a clock. */
    static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }

    /** Whole numbers plain, big ones abbreviated: 940, 1.2k, 3.4M. */
    static String number(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (abs >= 1_000) return String.format("%.1fk", v / 1_000);
        return String.valueOf(Math.round(v));
    }

    /**
     * The hour of the day as a word. The clock is a fraction of one cycle where
     * 0.25 is noon (see {@code World.darknessFor}), which is a true statement
     * and not one anybody wants read back to them.
     */
    static String timeOfDayName(double t) {
        double f = t - Math.floor(t);
        if (f < 0.10) return "Dawn";
        if (f < 0.20) return "Morning";
        if (f < 0.32) return "Midday";
        if (f < 0.45) return "Afternoon";
        if (f < 0.55) return "Dusk";
        if (f < 0.80) return "Night";
        return "Late night";
    }
}
