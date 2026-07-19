package com.larsons.engine.minigame;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A level's mini-game setup, configured in creative mode and saved inside the
 * level JSON (under {@code "minigame"}), so the game travels with the map —
 * including to joining multiplayer clients, who receive the level over the
 * wire and see the same rules the host built.
 *
 * <p>The four built-in modes:
 * <ul>
 *   <li><b>CTF</b> — 2 teams; steal the enemy flag (painted anywhere in
 *       creative) and carry it home while your own flag is at its base.</li>
 *   <li><b>STOCKPILE</b> — 2-4 teams race to deposit resource items (the item
 *       keys are chosen here in creative) at their stockpile; PvP optional.</li>
 *   <li><b>BATTLE</b> — 2-4 teams team-deathmatch with a magic-weapon loadout;
 *       PvP always on.</li>
 *   <li><b>ESCORT</b> — 2 teams; one escorts a payload along a painted
 *       waypoint path before time runs out, the other stops them.</li>
 * </ul>
 */
public class MiniGameConfig {

    /** Which mini game the level plays. {@link #NONE} = a normal level. */
    public enum Mode {
        NONE("None"), CTF("Capture the Flag"), STOCKPILE("Stockpile"),
        BATTLE("Battle"), ESCORT("Escort");

        public final String displayName;

        Mode(String displayName) { this.displayName = displayName; }
    }

    public Mode mode = Mode.NONE;

    /** Competing teams (2-4; CTF and Escort are always exactly 2). */
    public int teams = 2;

    /** Players can damage enemy players. Forced on for BATTLE. */
    public boolean pvp = true;

    /** Score to win: CTF captures, Stockpile deposits, Battle kills. */
    public int scoreLimit = 3;

    /** Escort round length in seconds; defenders win when it expires. */
    public int escortTimeSec = 300;

    /**
     * Item keys that count as Stockpile resources (changed in creative).
     * Deposited automatically when a carrier stands at their team's stockpile.
     */
    public List<String> resourceItems = defaultResources();

    private static List<String> defaultResources() {
        return new ArrayList<>(List.of("coal", "iron_ingot", "gold_ingot"));
    }

    /** Clamp values into a playable state (mode-specific team counts, PvP). */
    public void normalize() {
        if (mode == null) mode = Mode.NONE;
        teams = Math.max(2, Math.min(Team.MAX, teams));
        if (mode == Mode.CTF || mode == Mode.ESCORT) teams = 2;
        if (mode == Mode.BATTLE) pvp = true;
        scoreLimit = Math.max(1, Math.min(500, scoreLimit));
        escortTimeSec = Math.max(30, Math.min(3600, escortTimeSec));
        if (resourceItems == null) resourceItems = defaultResources();
        resourceItems.removeIf(k -> k == null || k.isBlank());
        if (resourceItems.isEmpty()) resourceItems = defaultResources();
    }

    public Map<String, Object> toMap() {
        normalize();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name());
        m.put("teams", teams);
        m.put("pvp", pvp);
        m.put("scoreLimit", scoreLimit);
        m.put("escortTimeSec", escortTimeSec);
        m.put("resources", new ArrayList<Object>(resourceItems));
        return m;
    }

    public static MiniGameConfig fromMap(Map<String, Object> m) {
        MiniGameConfig c = new MiniGameConfig();
        if (m.get("mode") instanceof String s) {
            try {
                c.mode = Mode.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                c.mode = Mode.NONE; // unknown mode from a newer save: play as a normal level
            }
        }
        if (m.get("teams") instanceof Number n) c.teams = n.intValue();
        if (m.get("pvp") instanceof Boolean b) c.pvp = b;
        if (m.get("scoreLimit") instanceof Number n) c.scoreLimit = n.intValue();
        if (m.get("escortTimeSec") instanceof Number n) c.escortTimeSec = n.intValue();
        if (m.get("resources") instanceof List<?> list) {
            c.resourceItems = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String key && !key.isBlank()) c.resourceItems.add(key);
            }
        }
        c.normalize();
        return c;
    }
}
