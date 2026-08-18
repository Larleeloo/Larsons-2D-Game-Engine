package com.larsons.engine.save;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about <em>a run</em> that is not part of a level: who you are
 * playing, how you are doing, what you are carrying, what you have done, and
 * which level you were standing in when you stopped.
 *
 * <p><b>The object the engine did not have.</b> A {@code Level} could always be
 * written to disk and read back in full — terrain, entities, containers, decor,
 * cutscenes, stat rules, per-level settings. Nothing described the player, so
 * {@code PlayScene.onEnter} rebuilt one from nothing on every entry: a fresh
 * {@link PlayerStats}, a fresh {@link Inventory}, a body at the level's spawn,
 * full health from the character profile, and a world clock back at the same
 * morning. That is what this class is for.
 *
 * <p>It is also the single owner of two things that used to have two owners,
 * which is where a live exploit came from. {@link PlayerStats} survived a door
 * transition and {@link com.larsons.engine.sim.StatRuleEngine}'s fire counts
 * did not, so every one-shot reward in a level could be collected again by
 * walking out of it and back in. Both live here now, with one lifetime, and
 * {@link #firedCounts} is keyed by level because the rules are.
 *
 * <p>Written as plain JSON beside the game, like every other store in the
 * engine, so a run can be copied to another machine or handed to a friend.
 *
 * @see SaveStore for where it is written and what travels with it
 */
public final class RunRecord {

    /** Which game type this run belongs to (a slot never spans two). */
    public String gameType = "";

    /** The level the player was standing in — a name within the game type. */
    public String levelName = "";

    /** The character profile being played ({@code ""} = the default). */
    public String characterKey = "";

    // Where the body is and which way it is pointing.
    public double x, y;
    /** Height above the ground in world px — the plan views' third axis. */
    public double z;
    public String facing = Facing.EAST.name();
    public boolean facingLeft;

    // The pools. Maxima are saved alongside the current values so a run
    // restores correctly even if the character's profile was edited since.
    public double health = -1, maxHealth = -1;
    public double mana = -1, maxMana = -1;
    public double stamina = -1, maxStamina = -1;
    public double ultCharge;

    /** Inventory contents, in {@link Inventory#toList()} form. */
    public List<Object> inventory = new ArrayList<>();
    /** Which hotbar slot was selected. */
    public int selectedSlot;

    /** Every tracked counter, by key (see {@link PlayerStats#TRACKED}). */
    public Map<String, Double> stats = new LinkedHashMap<>();

    /**
     * Stat-rule fire counts per level: level name → counts in rule order. Kept
     * for every level the run has entered, not just the current one, because
     * the whole point is that leaving a level does not forget them.
     */
    public Map<String, int[]> firedCounts = new LinkedHashMap<>();

    /** The world clock, so a run resumes at the hour it stopped at. */
    public double timeOfDay = -1;

    /** Seconds actually played, for the slot list to show. */
    public double playSeconds;

    /** When this was written, epoch millis — the slot list sorts on it. */
    public long savedAt;

    // --- capture ---------------------------------------------------------------

    /**
     * Snapshot the live run.
     *
     * <p>Takes its pieces rather than a scene so the save path has no
     * dependency on the play scene, and so a test can build one without a
     * window.
     */
    public static RunRecord capture(String gameType, String levelName, PlayerState player,
                                    Inventory inventory, PlayerStats stats, double timeOfDay,
                                    double playSeconds) {
        RunRecord r = new RunRecord();
        r.gameType = gameType == null ? "" : gameType;
        r.levelName = levelName == null ? "" : levelName;
        if (player != null) {
            r.characterKey = player.characterKey == null ? "" : player.characterKey;
            r.x = player.x;
            r.y = player.y;
            r.z = player.z;
            r.facing = player.facing == null ? Facing.EAST.name() : player.facing.name();
            r.facingLeft = player.facingLeft;
            r.health = player.health;
            r.maxHealth = player.maxHealth;
            r.mana = player.mana;
            r.maxMana = player.maxMana;
            r.stamina = player.stamina;
            r.maxStamina = player.maxStamina;
            r.ultCharge = player.ultCharge;
        }
        if (inventory != null) {
            r.inventory = inventory.toList();
            r.selectedSlot = inventory.selectedIndex();
        }
        if (stats != null) r.stats = new LinkedHashMap<>(stats.all());
        r.timeOfDay = timeOfDay;
        r.playSeconds = playSeconds;
        r.savedAt = System.currentTimeMillis();
        return r;
    }

    /** Remember a level's stat-rule fire counts (see {@link #firedCounts}). */
    public void putFiredCounts(String level, int[] counts) {
        if (level == null || level.isBlank() || counts == null) return;
        firedCounts.put(level, counts.clone());
    }

    /** A level's saved fire counts, or {@code null} when it has none yet. */
    public int[] firedCountsFor(String level) {
        int[] counts = level == null ? null : firedCounts.get(level);
        return counts == null ? null : counts.clone();
    }

    // --- restore ---------------------------------------------------------------

    /**
     * Put this run's player state back onto a body.
     *
     * <p><b>Call order matters and is the caller's job.</b> The character
     * profile must be applied <em>first</em> — {@code CharacterProfile.applyTo}
     * sets health, mana and stamina to their maxima, so restoring before it
     * would hand the player a full bar every time they loaded. This method
     * runs second and overwrites those three with what was actually saved.
     *
     * <p>Pools are clamped to the character's current maxima rather than
     * trusted, because a creator may have edited the profile since the save was
     * written, and a body with 300 health out of 100 is a broken HUD.
     */
    public void applyTo(PlayerState player) {
        if (player == null) return;
        player.x = x;
        player.y = y;
        player.z = z;
        player.facingLeft = facingLeft;
        try {
            player.facing = Facing.valueOf(facing);
        } catch (IllegalArgumentException | NullPointerException e) {
            player.facing = Facing.EAST; // a save from a build with other names
        }
        if (health >= 0) player.health = Math.min(health, player.maxHealth);
        if (mana >= 0) player.mana = Math.min(mana, player.maxMana);
        if (stamina >= 0) player.stamina = Math.min(stamina, player.maxStamina);
        player.ultCharge = Math.max(0, Math.min(1, ultCharge));
        // A body restored mid-air must not be credited with the fall it was
        // already in: fallPeakZ describes a fall in progress and is not saved.
        player.fallPeakZ = player.z;
    }

    /** Put this run's carried items back into {@code inv}. */
    public void applyTo(Inventory inv) {
        if (inv == null) return;
        inv.fromList(inventory);
        inv.select(selectedSlot);
    }

    /** Put this run's counters back into {@code s}. */
    public void applyTo(PlayerStats s) {
        if (s != null) s.restore(stats);
    }

    // --- serialization ---------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gameType", gameType);
        m.put("levelName", levelName);
        m.put("characterKey", characterKey);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("facing", facing);
        m.put("facingLeft", facingLeft);
        m.put("health", health);
        m.put("maxHealth", maxHealth);
        m.put("mana", mana);
        m.put("maxMana", maxMana);
        m.put("stamina", stamina);
        m.put("maxStamina", maxStamina);
        m.put("ultCharge", ultCharge);
        m.put("selectedSlot", selectedSlot);
        m.put("inventory", inventory);
        m.put("stats", new LinkedHashMap<String, Object>(stats));
        Map<String, Object> fired = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : firedCounts.entrySet()) {
            List<Object> counts = new ArrayList<>(e.getValue().length);
            for (int c : e.getValue()) counts.add(c);
            fired.put(e.getKey(), counts);
        }
        m.put("firedCounts", fired);
        m.put("timeOfDay", timeOfDay);
        m.put("playSeconds", playSeconds);
        m.put("savedAt", savedAt);
        return m;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    public static RunRecord fromJson(String json) {
        return fromMap(Json.asObject(Json.parse(json)));
    }

    /**
     * Read a run back. Every field falls back to its default and unknown keys
     * are ignored, so a save written by another build is a partial answer
     * rather than an error — the same rule the level loader and the key-bind
     * store follow, and the reason a save file never becomes a reason the game
     * will not start.
     */
    public static RunRecord fromMap(Map<String, Object> m) {
        RunRecord r = new RunRecord();
        if (m == null) return r;
        r.gameType = str(m, "gameType", r.gameType);
        r.levelName = str(m, "levelName", r.levelName);
        r.characterKey = str(m, "characterKey", r.characterKey);
        r.x = dbl(m, "x", r.x);
        r.y = dbl(m, "y", r.y);
        r.z = dbl(m, "z", r.z);
        r.facing = str(m, "facing", r.facing);
        r.facingLeft = bool(m, "facingLeft", r.facingLeft);
        r.health = dbl(m, "health", r.health);
        r.maxHealth = dbl(m, "maxHealth", r.maxHealth);
        r.mana = dbl(m, "mana", r.mana);
        r.maxMana = dbl(m, "maxMana", r.maxMana);
        r.stamina = dbl(m, "stamina", r.stamina);
        r.maxStamina = dbl(m, "maxStamina", r.maxStamina);
        r.ultCharge = dbl(m, "ultCharge", r.ultCharge);
        r.selectedSlot = (int) dbl(m, "selectedSlot", r.selectedSlot);
        if (m.get("inventory") instanceof List<?> list) r.inventory = new ArrayList<>(list);
        if (m.get("stats") instanceof Map<?, ?> stats) {
            for (Map.Entry<?, ?> e : stats.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Number n) {
                    r.stats.put(k, n.doubleValue());
                }
            }
        }
        if (m.get("firedCounts") instanceof Map<?, ?> fired) {
            for (Map.Entry<?, ?> e : fired.entrySet()) {
                if (!(e.getKey() instanceof String level) || !(e.getValue() instanceof List<?> l)) {
                    continue;
                }
                int[] counts = new int[l.size()];
                for (int i = 0; i < counts.length; i++) {
                    counts[i] = l.get(i) instanceof Number n ? Math.max(0, n.intValue()) : 0;
                }
                r.firedCounts.put(level, counts);
            }
        }
        r.timeOfDay = dbl(m, "timeOfDay", r.timeOfDay);
        r.playSeconds = dbl(m, "playSeconds", r.playSeconds);
        r.savedAt = (long) dbl(m, "savedAt", r.savedAt);
        return r;
    }

    // --- describing a run to a player ------------------------------------------

    /**
     * A one-line description for a menu: where the run is and how long it has
     * been going. "Cave · 1h 04m".
     */
    public String describe() {
        String where = levelName.isBlank() ? "a new run" : levelName;
        return where + " · " + formatPlayTime(playSeconds);
    }

    /** "1h 04m" / "4m 12s" — a duration a player can read at a glance. */
    public static String formatPlayTime(double seconds) {
        int total = (int) Math.max(0, seconds);
        int h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%dh %02dm", h, m) : String.format("%dm %02ds", m, s);
    }

    private static String str(Map<String, Object> m, String k, String fallback) {
        return m.get(k) instanceof String s ? s : fallback;
    }

    private static double dbl(Map<String, Object> m, String k, double fallback) {
        return m.get(k) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean fallback) {
        return m.get(k) instanceof Boolean b ? b : fallback;
    }
}
