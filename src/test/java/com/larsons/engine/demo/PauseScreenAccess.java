package com.larsons.engine.demo;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import java.util.List;
import java.util.Map;

/**
 * A public door onto {@link PauseScreen}, which is package-private and should
 * stay that way — the same shim {@link MiniGameHudAccess} is, for the same
 * reason. The golden-frame harness lives in {@code com.larsons.engine.render}
 * and needs to draw the pause screen; widening the engine's API to suit its own
 * test suite would be backwards.
 *
 * <p>It also exposes the screen's formatters, which are small pure functions
 * with opinions worth pinning ("2h ago", "1.2k", "Dusk").
 */
public final class PauseScreenAccess {

    private PauseScreenAccess() {}

    public static int twoColumnMinWidth() { return PauseScreen.TWO_COLUMN_MIN_WIDTH; }

    public static String ago(long millis) { return PauseScreen.ago(millis); }

    public static String number(double v) { return PauseScreen.number(v); }

    public static String timeOfDayName(double t) { return PauseScreen.timeOfDayName(t); }

    /**
     * A representative paused run: mid-fight health, two goals with one already
     * settled, a handful of counters, and unsaved changes.
     *
     * <p>Everything is fixed, including the save state — the chip that would
     * normally read "saved 40s ago" is deliberately the unsaved one, because a
     * golden frame must not be a function of the clock.
     */
    public static void paintSample(DrawTarget target, int w, int h) {
        paintSample(target, w, h, false);
    }

    /**
     * The same run, online: the server owns the world, so there is no save
     * state and no play clock, and the session's own numbers take their
     * place.
     */
    public static void paintSample(DrawTarget target, int w, int h, boolean online) {
        PauseScreen.Status s = new PauseScreen.Status();
        s.gameType = "Frostmarch";
        s.levelName = "Hollow Deep";
        s.characterName = "Scout";
        s.ultimateName = "Overdrive";
        s.world = "Top-Down · up is north";
        s.viewpoint = "Third person";
        s.health = 47;
        s.maxHealth = 120;
        s.mana = 62;
        s.maxMana = 80;
        s.stamina = 90;
        s.maxStamina = 90;
        s.ultCharge = 0.65;
        s.slot = "slot1";
        s.playSeconds = 3 * 3600 + 47 * 60 + 12;
        s.unsaved = true;
        if (online) {
            s.online = true;
            s.unsaved = false;
            s.slot = null;
            s.players = 4;
            s.ping = 38;
            s.host = true;
            s.port = 25599;
        }
        s.timeOfDay = 0.62;
        s.stats = new java.util.LinkedHashMap<>(Map.of());
        s.stats.put("blocks_mined", 1840.0);
        s.stats.put("distance_traveled", 96500.0);
        s.stats.put("mobs_killed", 37.0);
        s.stats.put("deaths", 2.0);
        s.stats.put("crafts", 14.0);
        s.stats.put("parries", 9.0);
        s.objectives = List.of(
                new PauseScreen.Objective("Blocks Mined → 1× diamond", "done", 1, true),
                new PauseScreen.Objective("Mobs Killed → 2× bread", "37 / 50  ×3", 0.74, false));
        s.keyHints = List.of(
                new String[] {"Space", "Jump"},
                new String[] {"E", "Doors, chests, mounts"},
                new String[] {"Tab", "Inventory"},
                new String[] {"Q", "Ultimate"});

        // The same actions PlayScene.buildPauseForm puts up, so the golden is a
        // picture of the real menu rather than of a plausible one.
        ConfigForm form = new ConfigForm("Paused").theme(MenuTheme.dark()).rowHeight(40);
        form.addAction("Resume", () -> { });
        if (!online) {
            form.addAction("Save Run", () -> { });
            form.addAction("Save and Quit", () -> { });
        }
        form.addNote("");
        form.addAction("Options", () -> { });
        form.addAction("Controls", () -> { });
        form.addAction("Edit in Creative", () -> { });
        form.addNote("");
        form.addAction(online ? "Stop Server & Quit" : "Quit to Menu", () -> { });
        form.update(0, new com.larsons.engine.input.InputManager());

        PauseScreen.render(target, w, h, form, s);
    }
}
