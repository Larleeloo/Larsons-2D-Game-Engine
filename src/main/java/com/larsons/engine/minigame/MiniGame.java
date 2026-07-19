package com.larsons.engine.minigame;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.level.Level;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative mini-game referee: team assignment, per-mode objective
 * logic (flags, stockpiles, kills, the escort payload), PvP rules, scoring,
 * win conditions, and automatic round resets.
 *
 * <p>Exactly one instance runs per world, on whichever side owns the
 * simulation — the {@link com.larsons.engine.net.GameServer} tick thread
 * online, or the play scene offline — mirroring how {@link World} works, so
 * online and solo play can't drift apart. Clients render from the wire state
 * ({@link #toWireMap()} / {@link MiniGameView}).
 *
 * <p>The playing field is built in creative mode with painted markers
 * ({@link Level.EntitySpawn} kinds):
 * <ul>
 *   <li>{@link #KIND_FLAG} ({@code team1}/{@code team2}) — a CTF team's flag
 *       base; the flag stands here and enemy flags are captured here.</li>
 *   <li>{@link #KIND_STOCKPILE} ({@code team1..team4}) — where a Stockpile
 *       team deposits its collected resources.</li>
 *   <li>{@link #KIND_SPAWN} ({@code team1..team4}) — team spawn points,
 *       dealt round-robin; every mode uses them when present.</li>
 *   <li>{@link #KIND_PATH} ({@code "1"}, {@code "2"}…) — the escort payload's
 *       waypoints, in order; the payload starts at #1 and the escorting team
 *       wins when it reaches the last one.</li>
 * </ul>
 */
public final class MiniGame implements World.PvpRule {

    public static final String KIND_FLAG = "mg_flag";
    public static final String KIND_SPAWN = "mg_spawn";
    public static final String KIND_STOCKPILE = "mg_stockpile";
    public static final String KIND_PATH = "mg_path";

    public enum Phase { PLAYING, ENDED }

    // Interaction distances, in tiles.
    private static final double FLAG_PICK_TILES = 1.3;
    private static final double CAPTURE_TILES = 1.6;
    private static final double DEPOSIT_TILES = 1.8;
    private static final double ESCORT_TILES = 3.0;
    /** Dropped flags fly home on their own after this long. */
    private static final double FLAG_RETURN_SECONDS = 25;
    /** A death within this long of taking PvP damage credits the attacker. */
    private static final double KILL_CREDIT_SECONDS = 8;
    /** Ended rounds show the winner this long, then reset automatically. */
    private static final double RESET_SECONDS = 12;
    /** Escort payload speed while escorted and uncontested, tiles/sec. */
    private static final double PAYLOAD_SPEED_TILES = 1.1;

    /** The items the Battle loadout grants (magic weapons + tools + food). */
    private static final String[][] BATTLE_LOADOUT = {
            {"arcane_staff", "1"}, {"fire_staff", "1"}, {"steel_sword", "1"},
            {"iron_pickaxe", "1"}, {"iron_axe", "1"}, {"bread", "5"},
    };

    /** Where the simulation finds each player's inventory (deposits, loadouts). */
    public interface Inventories {
        Inventory of(int playerId);
    }

    /** One CTF flag: at its base, carried by a player, or dropped in the field. */
    public static final class Flag {
        public final int team;
        public final double baseX, baseY;
        public int state = AT_BASE;
        public double x, y;
        public int carrier = -1;
        public double returnTimer;

        public static final int AT_BASE = 0, CARRIED = 1, DROPPED = 2;

        Flag(int team, double baseX, double baseY) {
            this.team = team;
            this.baseX = baseX;
            this.baseY = baseY;
            this.x = baseX;
            this.y = baseY;
        }

        void reset() {
            state = AT_BASE;
            x = baseX;
            y = baseY;
            carrier = -1;
            returnTimer = 0;
        }
    }

    private record Hit(int attacker, double time) {}

    private final Level level;
    private final MiniGameConfig config;

    private final Map<Integer, Integer> teamOf = new LinkedHashMap<>();
    private final Map<Integer, String> nameOf = new HashMap<>();
    private final int[] scores;
    private Phase phase = Phase.PLAYING;
    private int winner = -1;
    private double resetTimer;
    private double clock;

    private final Flag[] flags = new Flag[2];
    private final Map<Integer, double[]> stockpiles = new HashMap<>();
    private final Map<Integer, List<double[]>> teamSpawns = new HashMap<>();
    private final List<double[]> waypoints = new ArrayList<>();
    private double payloadX, payloadY;
    private int nextWaypoint = 1;
    private double pathTraveled, pathTotal;
    private double timeLeft;

    private final Map<Integer, Hit> lastHit = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final Set<Integer> inventoryChanged = new LinkedHashSet<>();
    private Inventories inventories;

    /**
     * The level's mini game, or {@code null} when it doesn't have one — the
     * one-liner scenes and the server use to opt in.
     */
    public static MiniGame createIfConfigured(Level level) {
        MiniGameConfig cfg = level.minigame;
        if (cfg == null || cfg.mode == MiniGameConfig.Mode.NONE) return null;
        return new MiniGame(level, cfg);
    }

    public MiniGame(Level level, MiniGameConfig config) {
        this.level = level;
        this.config = config;
        config.normalize();
        this.scores = new int[config.teams];
        readMarkers();
        this.timeLeft = config.escortTimeSec;
        if (!waypoints.isEmpty()) {
            payloadX = waypoints.get(0)[0];
            payloadY = waypoints.get(0)[1];
            for (int i = 1; i < waypoints.size(); i++) {
                pathTotal += Math.hypot(waypoints.get(i)[0] - waypoints.get(i - 1)[0],
                        waypoints.get(i)[1] - waypoints.get(i - 1)[1]);
            }
        }
    }

    private void readMarkers() {
        List<Level.EntitySpawn> path = new ArrayList<>();
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case KIND_FLAG -> {
                    int t = Team.fromMarkerType(e.type);
                    if (t >= 0 && t < 2) flags[t] = new Flag(t, e.x, e.y);
                }
                case KIND_STOCKPILE -> {
                    int t = Team.fromMarkerType(e.type);
                    if (t >= 0) stockpiles.put(t, new double[]{e.x, e.y});
                }
                case KIND_SPAWN -> {
                    int t = Team.fromMarkerType(e.type);
                    if (t >= 0) {
                        teamSpawns.computeIfAbsent(t, k -> new ArrayList<>())
                                .add(new double[]{e.x, e.y});
                    }
                }
                case KIND_PATH -> path.add(e);
                default -> { /* not a mini-game marker */ }
            }
        }
        path.sort((a, b) -> Integer.compare(pathIndex(a.type), pathIndex(b.type)));
        for (Level.EntitySpawn e : path) waypoints.add(new double[]{e.x, e.y});
    }

    /** The numeric order of a waypoint marker's type, or a large fallback. */
    public static int pathIndex(String type) {
        try {
            return Integer.parseInt(type.trim());
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    public MiniGameConfig config() {
        return config;
    }

    public Phase phase() {
        return phase;
    }

    public int winner() {
        return winner;
    }

    public int score(int team) {
        return team >= 0 && team < scores.length ? scores[team] : 0;
    }

    public Flag flag(int team) {
        return team >= 0 && team < flags.length ? flags[team] : null;
    }

    public double[] payload() {
        return new double[]{payloadX, payloadY};
    }

    public double timeLeft() {
        return timeLeft;
    }

    public void setInventories(Inventories inv) {
        this.inventories = inv;
    }

    /**
     * What the map is still missing to be playable, or {@code null} when it
     * has everything. Shown in creative when configuring, and logged by the
     * server on boot — the game still runs either way (missing pieces simply
     * can't score), so building/testing incrementally stays possible.
     */
    public String validate() {
        List<String> missing = new ArrayList<>();
        switch (config.mode) {
            case CTF -> {
                for (int t = 0; t < 2; t++) {
                    if (flags[t] == null) missing.add(Team.name(t) + " flag");
                }
            }
            case STOCKPILE -> {
                for (int t = 0; t < config.teams; t++) {
                    if (!stockpiles.containsKey(t)) missing.add(Team.name(t) + " stockpile");
                }
            }
            case ESCORT -> {
                if (waypoints.size() < 2) missing.add("at least 2 escort waypoints");
            }
            default -> { /* battle needs no markers */ }
        }
        if (missing.isEmpty()) return null;
        return config.mode.displayName + " still needs: " + String.join(", ", missing);
    }

    // --- players ---------------------------------------------------------------

    /** Assign {@code playerId} to the smallest team and return its index. */
    public int assignTeam(int playerId) {
        Integer existing = teamOf.get(playerId);
        if (existing != null) return existing;
        int[] counts = new int[config.teams];
        for (int team : teamOf.values()) {
            if (team >= 0 && team < counts.length) counts[team]++;
        }
        int pick = 0;
        for (int t = 1; t < counts.length; t++) {
            if (counts[t] < counts[pick]) pick = t;
        }
        teamOf.put(playerId, pick);
        return pick;
    }

    public int teamOf(int playerId) {
        return teamOf.getOrDefault(playerId, -1);
    }

    public void removePlayer(int playerId) {
        teamOf.remove(playerId);
        nameOf.remove(playerId);
        lastHit.remove(playerId);
        for (Flag f : flags) {
            if (f != null && f.state == Flag.CARRIED && f.carrier == playerId) {
                dropFlag(f, f.x, f.y);
            }
        }
    }

    /**
     * Where {@code playerId} (re)spawns: its team's painted spawn points dealt
     * round-robin, else a sensible per-mode fallback (flag base, stockpile,
     * the payload path's ends), else the level's normal multiplayer spawns.
     */
    public double[] respawnPoint(int playerId) {
        int team = teamOf(playerId);
        if (team >= 0) {
            List<double[]> spawns = teamSpawns.get(team);
            if (spawns != null && !spawns.isEmpty()) {
                return spawns.get(Math.floorMod(playerId, spawns.size()));
            }
            switch (config.mode) {
                case CTF -> {
                    if (team < 2 && flags[team] != null) {
                        return new double[]{flags[team].baseX, flags[team].baseY};
                    }
                }
                case STOCKPILE -> {
                    double[] pile = stockpiles.get(team);
                    if (pile != null) return pile;
                }
                case ESCORT -> {
                    if (!waypoints.isEmpty()) {
                        return team == 0 ? waypoints.get(0)
                                : waypoints.get(waypoints.size() - 1);
                    }
                }
                default -> { /* fall through to the level spawns */ }
            }
        }
        return level.spawnPointFor(playerId);
    }

    /** Give a joining Battle player the mode's magic-weapon loadout. */
    public void grantLoadout(int playerId) {
        if (config.mode != MiniGameConfig.Mode.BATTLE || inventories == null) return;
        Inventory inv = inventories.of(playerId);
        if (inv == null) return;
        for (String[] entry : BATTLE_LOADOUT) {
            inv.add(entry[0], Integer.parseInt(entry[1]));
        }
        inventoryChanged.add(playerId);
    }

    // --- PvP -------------------------------------------------------------------

    /** Enemy players can be hurt while the round runs and PvP is on. */
    @Override
    public boolean canHurt(int attackerId, PlayerState victim) {
        if (phase != Phase.PLAYING || !config.pvp) return false;
        if (attackerId == victim.id) return false;
        int at = teamOf(attackerId);
        int vt = teamOf(victim.id);
        return at >= 0 && vt >= 0 && at != vt;
    }

    /** Record who hurt whom, for kill credit when the victim dies. */
    @Override
    public void damaged(int attackerId, PlayerState victim) {
        lastHit.put(victim.id, new Hit(attackerId, clock));
    }

    /**
     * Resolve a melee swing against enemy players, mirroring
     * {@link World#playerAttack}'s geometry: the nearest hittable enemy within
     * reach of the aim point. Returns the victim, or {@code null} on a whiff —
     * the caller applies damage and reports it via {@link #damaged}.
     */
    public PlayerState resolveMeleeHit(PlayerState attacker, List<PlayerState> players,
                                       double aimX, double aimY) {
        if (phase != Phase.PLAYING || !config.pvp) return null;
        double size = level.tileSize;
        double px = attacker.x, py = attacker.y;
        double dx = aimX - px, dy = aimY - py;
        double len = Math.hypot(dx, dy);
        double hitX = len > World.ATTACK_REACH ? px + dx / len * World.ATTACK_REACH : aimX;
        double hitY = len > World.ATTACK_REACH ? py + dy / len * World.ATTACK_REACH : aimY;
        PlayerState best = null;
        double bestD = Double.MAX_VALUE;
        for (PlayerState p : players) {
            if (!canHurt(attacker.id, p)) continue;
            double d = Math.hypot(p.x + size / 2 - hitX, p.y + size / 2 - hitY);
            if (d < size / 2 + 24 && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * A player just died at (x, y), before respawning: drop any carried flag
     * there, and credit the kill to whoever hurt them recently (Battle score).
     * Wired as the world's death listener so lava deaths count too.
     */
    public void onPlayerDeath(PlayerState victim, double x, double y) {
        for (Flag f : flags) {
            if (f != null && f.state == Flag.CARRIED && f.carrier == victim.id) {
                dropFlag(f, x, y);
                events.add(name(victim.id) + " dropped the " + Team.name(f.team) + " flag");
            }
        }
        Hit hit = lastHit.remove(victim.id);
        if (hit == null || clock - hit.time() > KILL_CREDIT_SECONDS) return;
        int killerTeam = teamOf(hit.attacker());
        if (killerTeam < 0 || killerTeam == teamOf(victim.id)) return;
        if (config.mode == MiniGameConfig.Mode.BATTLE && phase == Phase.PLAYING) {
            scores[killerTeam]++;
            events.add(name(victim.id) + " was defeated by " + name(hit.attacker())
                    + "  (" + Team.name(killerTeam) + " " + scores[killerTeam]
                    + "/" + config.scoreLimit + ")");
            if (scores[killerTeam] >= config.scoreLimit) win(killerTeam);
        } else {
            events.add(name(victim.id) + " was defeated by " + name(hit.attacker()));
        }
    }

    // --- simulation ------------------------------------------------------------

    /**
     * Advance the referee one tick. Runs after damage has been applied for the
     * tick and before {@link World#step} respawns the dead, so deaths observed
     * by the world's death listener see up-to-date flag/carrier state.
     */
    public void step(double dt, List<PlayerState> players) {
        clock += dt;
        for (PlayerState p : players) nameOf.put(p.id, p.name);

        if (phase == Phase.ENDED) {
            resetTimer -= dt;
            if (resetTimer <= 0) resetRound(players);
            return;
        }
        switch (config.mode) {
            case CTF -> stepCtf(players);
            case STOCKPILE -> stepStockpile(players);
            case ESCORT -> stepEscort(dt, players);
            default -> { /* BATTLE scores in onPlayerDeath */ }
        }
    }

    private void stepCtf(List<PlayerState> players) {
        double ts = level.tileSize;
        // Dropped flags time out and fly home.
        for (Flag f : flags) {
            if (f == null || f.state != Flag.DROPPED) continue;
            if (clock >= f.returnTimer) {
                f.reset();
                events.add("The " + Team.name(f.team) + " flag returned to base");
            }
        }
        // Carried flags follow their carrier (or drop if the carrier vanished).
        for (Flag f : flags) {
            if (f == null || f.state != Flag.CARRIED) continue;
            PlayerState carrier = byId(players, f.carrier);
            if (carrier == null) {
                dropFlag(f, f.x, f.y);
            } else {
                f.x = carrier.x + ts / 2;
                f.y = carrier.y;
            }
        }
        for (PlayerState p : players) {
            int team = teamOf(p.id);
            if (team < 0 || team >= 2) continue;
            double cx = p.x + ts / 2, cy = p.y + ts / 2;
            // Steal the enemy flag / rescue your own dropped one.
            for (Flag f : flags) {
                if (f == null) continue;
                double d = Math.hypot(f.x - cx, f.y - cy);
                if (f.team != team && f.state != Flag.CARRIED && d <= FLAG_PICK_TILES * ts) {
                    f.state = Flag.CARRIED;
                    f.carrier = p.id;
                    events.add(p.name + " took the " + Team.name(f.team) + " flag!");
                } else if (f.team == team && f.state == Flag.DROPPED
                        && d <= FLAG_PICK_TILES * ts) {
                    f.reset();
                    events.add(p.name + " returned the " + Team.name(team) + " flag");
                }
            }
            // Capture: carrying the enemy flag home while your own flag is home.
            Flag own = flags[team];
            Flag enemy = flags[1 - team];
            if (own != null && enemy != null && enemy.state == Flag.CARRIED
                    && enemy.carrier == p.id && own.state == Flag.AT_BASE
                    && Math.hypot(own.baseX - cx, own.baseY - cy) <= CAPTURE_TILES * ts) {
                enemy.reset();
                scores[team]++;
                events.add(p.name + " captured the " + Team.name(1 - team) + " flag!  ("
                        + Team.name(team) + " " + scores[team] + "/" + config.scoreLimit + ")");
                if (scores[team] >= config.scoreLimit) win(team);
            }
        }
    }

    private void dropFlag(Flag f, double x, double y) {
        f.state = Flag.DROPPED;
        f.x = x;
        f.y = y;
        f.carrier = -1;
        f.returnTimer = clock + FLAG_RETURN_SECONDS;
    }

    private void stepStockpile(List<PlayerState> players) {
        if (inventories == null) return;
        double ts = level.tileSize;
        for (PlayerState p : players) {
            int team = teamOf(p.id);
            double[] pile = team >= 0 ? stockpiles.get(team) : null;
            if (pile == null) continue;
            double cx = p.x + ts / 2, cy = p.y + ts / 2;
            if (Math.hypot(pile[0] - cx, pile[1] - cy) > DEPOSIT_TILES * ts) continue;
            Inventory inv = inventories.of(p.id);
            if (inv == null) continue;
            int total = 0;
            for (String key : config.resourceItems) {
                total += inv.remove(key, Integer.MAX_VALUE);
            }
            if (total <= 0) continue;
            scores[team] += total;
            inventoryChanged.add(p.id);
            events.add(p.name + " stockpiled " + total + " resource" + (total == 1 ? "" : "s")
                    + "  (" + Team.name(team) + " " + scores[team] + "/" + config.scoreLimit + ")");
            if (scores[team] >= config.scoreLimit) win(team);
        }
    }

    private void stepEscort(double dt, List<PlayerState> players) {
        if (waypoints.size() < 2) return;
        double ts = level.tileSize;
        if ((timeLeft -= dt) <= 0) {
            timeLeft = 0;
            win(1); // the blocking team ran out the clock
            return;
        }
        boolean escortNear = false, blockerNear = false;
        for (PlayerState p : players) {
            double d = Math.hypot(p.x + ts / 2 - payloadX, p.y + ts / 2 - payloadY);
            if (d > ESCORT_TILES * ts) continue;
            int team = teamOf(p.id);
            if (team == 0) escortNear = true;
            else if (team == 1) blockerNear = true;
        }
        // Overwatch-style rules: defenders in range contest the payload; it
        // only rolls while escorted and uncontested.
        if (blockerNear || !escortNear) return;
        double remaining = PAYLOAD_SPEED_TILES * ts * dt;
        while (remaining > 0 && nextWaypoint < waypoints.size()) {
            double[] target = waypoints.get(nextWaypoint);
            double dx = target[0] - payloadX, dy = target[1] - payloadY;
            double dist = Math.hypot(dx, dy);
            if (dist <= remaining) {
                payloadX = target[0];
                payloadY = target[1];
                pathTraveled += dist;
                remaining -= dist;
                nextWaypoint++;
                if (nextWaypoint >= waypoints.size()) {
                    events.add("The payload reached its destination!");
                    win(0);
                    return;
                }
                events.add("Payload checkpoint " + nextWaypoint + "/" + waypoints.size()
                        + " reached");
            } else {
                payloadX += dx / dist * remaining;
                payloadY += dy / dist * remaining;
                pathTraveled += remaining;
                remaining = 0;
            }
        }
    }

    private void win(int team) {
        phase = Phase.ENDED;
        winner = team;
        resetTimer = RESET_SECONDS;
        events.add(Team.name(team) + " team wins! New round in "
                + (int) RESET_SECONDS + " seconds…");
    }

    /** Reset for a fresh round: scores, objectives, and everyone back home. */
    private void resetRound(List<PlayerState> players) {
        phase = Phase.PLAYING;
        winner = -1;
        resetTimer = 0;
        java.util.Arrays.fill(scores, 0);
        for (Flag f : flags) {
            if (f != null) f.reset();
        }
        if (!waypoints.isEmpty()) {
            payloadX = waypoints.get(0)[0];
            payloadY = waypoints.get(0)[1];
        }
        nextWaypoint = 1;
        pathTraveled = 0;
        timeLeft = config.escortTimeSec;
        lastHit.clear();
        for (PlayerState p : players) {
            double[] spawn = respawnPoint(p.id);
            p.x = spawn[0];
            p.y = spawn[1];
            p.vy = 0;
            p.health = PlayerState.MAX_HEALTH;
            p.stamina = PlayerState.MAX_STAMINA;
            p.mana = PlayerState.MAX_MANA;
        }
        events.add("New round — go!");
    }

    private static PlayerState byId(List<PlayerState> players, int id) {
        for (PlayerState p : players) {
            if (p.id == id) return p;
        }
        return null;
    }

    private String name(int playerId) {
        String n = nameOf.get(playerId);
        return n == null || n.isEmpty() ? "Player " + playerId : n;
    }

    /** Drain announcer messages ("X took the flag!") since the last call. */
    public List<String> pollEvents() {
        if (events.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /** Drain the ids whose inventories this referee changed (deposits, loadouts). */
    public List<Integer> pollInventoryChanges() {
        if (inventoryChanged.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>(inventoryChanged);
        inventoryChanged.clear();
        return out;
    }

    // --- wire state --------------------------------------------------------------

    /** The replicated game state, broadcast as the {@code mg} message. */
    public Map<String, Object> toWireMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", config.mode.name());
        m.put("phase", phase.name());
        m.put("teams", config.teams);
        m.put("pvp", config.pvp);
        m.put("limit", config.scoreLimit);
        List<Object> sc = new ArrayList<>(scores.length);
        for (int s : scores) sc.add(s);
        m.put("scores", sc);
        Map<String, Object> pt = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : teamOf.entrySet()) {
            pt.put(Integer.toString(e.getKey()), e.getValue());
        }
        m.put("pt", pt);
        if (config.mode == MiniGameConfig.Mode.CTF) {
            List<Object> fl = new ArrayList<>(2);
            for (Flag f : flags) {
                if (f == null) continue;
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("t", f.team);
                fm.put("s", f.state);
                fm.put("x", f.x);
                fm.put("y", f.y);
                fm.put("c", f.carrier);
                fl.add(fm);
            }
            m.put("flags", fl);
        }
        if (config.mode == MiniGameConfig.Mode.ESCORT && !waypoints.isEmpty()) {
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("x", payloadX);
            pay.put("y", payloadY);
            pay.put("pr", pathTotal > 0 ? pathTraveled / pathTotal : 0);
            pay.put("tl", timeLeft);
            m.put("pay", pay);
        }
        m.put("win", winner);
        if (phase == Phase.ENDED) m.put("reset", Math.max(0, resetTimer));
        return m;
    }
}
