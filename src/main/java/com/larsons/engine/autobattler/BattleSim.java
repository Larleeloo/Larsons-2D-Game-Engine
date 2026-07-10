package com.larsons.engine.autobattler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The deterministic auto-battle: two boards fight on an 8x8 grid with no
 * player input. Units acquire the nearest enemy, path one cell at a time,
 * attack on their attack-speed cadence, build mana, and cast their class
 * ability when it fills — with synergy traits and items folded into their
 * stats up front.
 *
 * <p>Runs only on the server's tick thread (clients render replicated
 * snapshots), stepped at a fixed dt, and seeded — the same boards and seed
 * always produce the same fight, the property the engine's whole
 * "deterministic fixed-step simulation" netcode model is built on.
 *
 * <p>Coordinates: the <b>home</b> player's units keep their placement cells
 * (rows 4-7); the <b>away</b> board is mirrored through the board centre onto
 * rows 0-3, so both players place units on "their" half and meet in the
 * middle.
 */
public final class BattleSim {

    public static final int COLS = 8;
    public static final int ROWS = 8;
    public static final int HOME = 0;
    public static final int AWAY = 1;

    private static final double MOVE_SPEED = 2.4;   // cells per second
    private static final double RANGE_SLACK = 0.2;  // forgiving range check
    private static final double MAX_ATTACK_SPEED = 2.5;
    private static final double CRIT_MULTIPLIER = 1.9;
    private static final double MANA_PER_ATTACK = 10;
    private static final double MANA_PER_HIT_TAKEN = 8;
    private static final int MAX_EVENTS = 128;

    // How long transient animation states hold, so 15 Hz snapshots catch them.
    private static final double ATTACK_ANIM_SECONDS = 0.3;
    private static final double CAST_ANIM_SECONDS = 0.5;
    private static final double HIT_ANIM_SECONDS = 0.25;

    /** One fighting unit, stats fully resolved from def + star + items + traits. */
    public static final class CUnit {
        public final int uid;
        public final int sourceId;   // owning UnitInstance id (for damage attribution)
        public final String key;
        public final int star;
        public final int team;
        public double x, y;          // continuous position, in cells
        int cellC, cellR;            // reserved "home" cell
        int moveC = -1, moveR = -1;  // cell being moved into, or -1
        double waitTimer;            // pause before retrying a blocked path

        public double maxHp, hp;
        double ad, attackSpeed, range, armor;
        double critChance, regen, spellPower, healPower;
        public double mana, manaMax;
        double atkTimer;
        public boolean dead;
        CUnit target;

        /** Replicated animation state, plus how long a transient state holds. */
        public AnimState anim = AnimState.IDLE;
        double animTimer;

        /** Running combat-stats tallies, replicated for the damage panel. */
        public double dealtPhysical, dealtMagic, healingDone;

        CUnit(int uid, int sourceId, String key, int star, int team) {
            this.uid = uid;
            this.sourceId = sourceId;
            this.key = key;
            this.star = star;
            this.team = team;
        }
    }

    /**
     * A combat event for client feedback (damage numbers, particles, sfx).
     * Events that come from a unit carry its uid and position, so clients can
     * animate projectiles from attacker to target, trigger attack/cast
     * animations, and identify who died; {@code sourceId} is 0 otherwise.
     */
    public record Event(String kind, double x, double y, double amount, int team,
                        int sourceId, double sx, double sy) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", kind);
            m.put("x", round2(x));
            m.put("y", round2(y));
            m.put("a", Math.rint(amount));
            m.put("tm", team);
            if (sourceId > 0) {
                m.put("su", sourceId);
                m.put("sx", round2(sx));
                m.put("sy", round2(sy));
            }
            return m;
        }
    }

    private final List<CUnit> units = new ArrayList<>();
    private final CUnit[][] occupied = new CUnit[COLS][ROWS];
    private final List<Event> events = new ArrayList<>();
    private final Random rng;
    private double time;
    private boolean finished;
    private int winner = -1; // HOME/AWAY, or -1 while running / on a draw

    public BattleSim(List<UnitInstance> homeBoard, List<UnitInstance> awayBoard, long seed) {
        this.rng = new Random(seed);
        int uid = 1;
        for (UnitInstance u : homeBoard) {
            units.add(build(uid++, u, HOME, u.col, u.row));
        }
        for (UnitInstance u : awayBoard) {
            // Mirror the away board through the centre onto rows 0-3.
            units.add(build(uid++, u, AWAY, COLS - 1 - u.col, ROWS - 1 - u.row));
        }
        applyTraits(homeBoard, HOME);
        applyTraits(awayBoard, AWAY);
        for (CUnit c : units) {
            c.hp = c.maxHp;
            place(c, clampFree(c.cellC, c.cellR));
        }
        leapAssassins();
    }

    private CUnit build(int uid, UnitInstance u, int team, int col, int row) {
        UnitDef def = u.def();
        double mult = UnitDef.starMultiplier(u.star);
        CUnit c = new CUnit(uid, u.id, u.key, u.star, team);
        c.cellC = col;
        c.cellR = row;
        c.maxHp = def.hp * mult;
        c.ad = def.ad * mult;
        c.attackSpeed = def.attackSpeed;
        c.range = def.range;
        c.armor = def.armor;
        c.manaMax = def.manaMax;
        for (String key : u.items) {
            AutoItem item = AutoItems.get(key);
            if (item == null) continue;
            c.maxHp += item.hp;
            c.ad += item.ad;
            c.attackSpeed *= 1 + item.atkSpeed;
            c.armor += item.armor;
            c.spellPower += item.spellPower;
        }
        return c;
    }

    /**
     * Fold each team's active synergies into its units' stats. Counts are of
     * <em>distinct</em> fielded species per trait, TFT-style.
     */
    private void applyTraits(List<UnitInstance> board, int team) {
        Map<Trait, Integer> counts = countTraits(board);

        double holyHp = Trait.HOLY.value(counts.getOrDefault(Trait.HOLY, 0));
        double teamArmor = Trait.GUARDIAN.value(counts.getOrDefault(Trait.GUARDIAN, 0));
        double frostSlow = Trait.FROST.value(counts.getOrDefault(Trait.FROST, 0));

        for (CUnit c : units) {
            UnitDef def = AutoUnits.get(c.key);
            if (c.team == team) {
                c.maxHp *= 1 + holyHp;
                c.armor += teamArmor;
                if (def.origin != null) {
                    double v = def.origin.value(counts.getOrDefault(def.origin, 0));
                    switch (def.origin) {
                        case FOREST -> c.regen += v;
                        case EMBER -> c.ad += v;
                        case STORM -> c.spellPower += v * 100;
                        case SHADOW -> c.critChance += v;
                        case WILD -> c.attackSpeed *= 1 + v;
                        case MECH -> c.armor += v;
                        default -> { /* HOLY & FROST are team/enemy-wide, above */ }
                    }
                }
                if (def.clazz != null) {
                    double v = def.clazz.value(counts.getOrDefault(def.clazz, 0));
                    switch (def.clazz) {
                        case WARRIOR -> c.armor += v;
                        case ARCHER -> c.attackSpeed *= 1 + v;
                        case MAGE -> c.mana += v;
                        case ASSASSIN -> c.critChance += v;
                        case HEALER -> c.healPower += v;
                        case BRAWLER -> c.maxHp += v;
                        default -> { /* GUARDIAN is team-wide, above */ }
                    }
                }
            } else {
                // Frost slows the opposing team.
                c.attackSpeed *= 1 - frostSlow;
            }
        }
    }

    /** Distinct fielded species per trait — shared with the client's synergy panel. */
    public static Map<Trait, Integer> countTraits(List<UnitInstance> board) {
        Map<Trait, List<String>> seen = new HashMap<>();
        Map<Trait, Integer> counts = new HashMap<>();
        for (UnitInstance u : board) {
            UnitDef def = u.def();
            if (def == null) continue;
            for (Trait t : new Trait[]{def.origin, def.clazz}) {
                if (t == null) continue;
                List<String> keys = seen.computeIfAbsent(t, k -> new ArrayList<>());
                if (!keys.contains(u.key)) {
                    keys.add(u.key);
                    counts.merge(t, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Assassins open the fight behind enemy lines. */
    private void leapAssassins() {
        for (CUnit c : units) {
            UnitDef def = AutoUnits.get(c.key);
            if (def.clazz != Trait.ASSASSIN || c.dead) continue;
            int[] backRows = c.team == HOME ? new int[]{0, 1} : new int[]{7, 6};
            int[] best = null;
            double bestDist = Double.MAX_VALUE;
            for (int r : backRows) {
                for (int col = 0; col < COLS; col++) {
                    if (occupied[col][r] != null) continue;
                    double d = Math.abs(col - c.cellC);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new int[]{col, r};
                    }
                }
            }
            if (best != null) {
                occupied[c.cellC][c.cellR] = null;
                place(c, best);
            }
        }
    }

    private int[] clampFree(int col, int row) {
        col = Math.max(0, Math.min(COLS - 1, col));
        row = Math.max(0, Math.min(ROWS - 1, row));
        if (occupied[col][row] == null) return new int[]{col, row};
        // Occupied (bad placement data): spiral outward for the nearest free cell.
        for (int radius = 1; radius < COLS; radius++) {
            for (int dc = -radius; dc <= radius; dc++) {
                for (int dr = -radius; dr <= radius; dr++) {
                    int c = col + dc, r = row + dr;
                    if (c >= 0 && c < COLS && r >= 0 && r < ROWS && occupied[c][r] == null) {
                        return new int[]{c, r};
                    }
                }
            }
        }
        return new int[]{col, row};
    }

    private void place(CUnit c, int[] cell) {
        c.cellC = cell[0];
        c.cellR = cell[1];
        c.x = cell[0];
        c.y = cell[1];
        occupied[cell[0]][cell[1]] = c;
    }

    // --- stepping -----------------------------------------------------------------

    public void step(double dt) {
        if (finished) return;
        time += dt;
        for (CUnit c : units) {
            if (c.dead) continue;
            if (c.waitTimer > 0) c.waitTimer -= dt;
            if (c.animTimer > 0) c.animTimer -= dt;
            if (c.regen > 0) heal(c, c, c.maxHp * c.regen * dt, false);
            acquireTarget(c);
            if (c.target == null) {
                settleAnim(c, false);
                continue; // no living enemies; end detected below
            }
            if (c.moveC >= 0) {
                advanceMove(c, dt);
                settleAnim(c, true);
            } else if (inRange(c, c.target)) {
                c.atkTimer -= dt;
                if (c.atkTimer <= 0) {
                    c.atkTimer += 1.0 / Math.min(MAX_ATTACK_SPEED, Math.max(0.1, c.attackSpeed));
                    attack(c, c.target);
                }
                settleAnim(c, false);
            } else {
                startMove(c);
                settleAnim(c, c.moveC >= 0);
            }
        }
        checkEnd();
    }

    /** Fall back to WALK/IDLE once any transient state's window has run out. */
    private static void settleAnim(CUnit c, boolean moving) {
        if (c.dead) return;
        if (c.animTimer <= 0) c.anim = moving ? AnimState.WALK : AnimState.IDLE;
    }

    /** Enter a transient animation state unless something weightier is playing. */
    private static void flashAnim(CUnit c, AnimState state, double seconds) {
        if (c.dead) return;
        if (c.animTimer > 0 && c.anim.priority > state.priority) return;
        c.anim = state;
        c.animTimer = seconds;
    }

    private void acquireTarget(CUnit c) {
        if (c.target != null && !c.target.dead) return;
        c.target = null;
        double best = Double.MAX_VALUE;
        for (CUnit other : units) {
            if (other.dead || other.team == c.team) continue;
            double d = dist(c, other);
            if (d < best) {
                best = d;
                c.target = other;
            }
        }
    }

    private boolean inRange(CUnit c, CUnit target) {
        // Chebyshev distance so melee (range 1) hits diagonals on a square grid.
        double dc = Math.abs(c.x - target.x);
        double dr = Math.abs(c.y - target.y);
        return Math.max(dc, dr) <= c.range + RANGE_SLACK;
    }

    private static double dist(CUnit a, CUnit b) {
        double dx = a.x - b.x, dy = a.y - b.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void startMove(CUnit c) {
        if (c.waitTimer > 0) return; // recently blocked; handled in advance below
        int bestC = -1, bestR = -1;
        double best = Double.MAX_VALUE;
        for (int dc = -1; dc <= 1; dc++) {
            for (int dr = -1; dr <= 1; dr++) {
                if (dc == 0 && dr == 0) continue;
                int nc = c.cellC + dc, nr = c.cellR + dr;
                if (nc < 0 || nc >= COLS || nr < 0 || nr >= ROWS) continue;
                if (occupied[nc][nr] != null) continue;
                double dx = nc - c.target.x, dy = nr - c.target.y;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d < best) {
                    best = d;
                    bestC = nc;
                    bestR = nr;
                }
            }
        }
        // Only step if it actually gets closer; otherwise wait for a gap.
        double now = dist(c, c.target);
        if (bestC >= 0 && best < now) {
            c.moveC = bestC;
            c.moveR = bestR;
            occupied[bestC][bestR] = c; // reserve; old cell stays held until arrival
        } else {
            c.waitTimer = 0.2;
        }
    }

    private void advanceMove(CUnit c, double dt) {
        double dx = c.moveC - c.x;
        double dy = c.moveR - c.y;
        double d = Math.sqrt(dx * dx + dy * dy);
        double stepLen = MOVE_SPEED * dt;
        if (d <= stepLen) {
            occupied[c.cellC][c.cellR] = null;
            c.cellC = c.moveC;
            c.cellR = c.moveR;
            c.x = c.moveC;
            c.y = c.moveR;
            c.moveC = -1;
            c.moveR = -1;
        } else {
            c.x += dx / d * stepLen;
            c.y += dy / d * stepLen;
        }
    }

    private void attack(CUnit c, CUnit target) {
        boolean crit = c.critChance > 0 && rng.nextDouble() < c.critChance;
        double raw = c.ad * (crit ? CRIT_MULTIPLIER : 1.0);
        flashAnim(c, AnimState.ATTACK, ATTACK_ANIM_SECONDS);
        dealPhysical(c, target, raw, crit ? "crit" : "hit");
        gainMana(c, MANA_PER_ATTACK);
        gainMana(target, MANA_PER_HIT_TAKEN);
    }

    private void dealPhysical(CUnit from, CUnit to, double raw, String eventKind) {
        double mitigated = raw * 100.0 / (100.0 + Math.max(0, to.armor));
        damage(from, to, mitigated, eventKind, false);
    }

    private void damage(CUnit from, CUnit to, double amount, String eventKind, boolean magic) {
        if (to.dead) return;
        double effective = Math.min(amount, to.hp); // no credit for overkill
        if (from != null) {
            if (magic) from.dealtMagic += effective;
            else from.dealtPhysical += effective;
        }
        to.hp -= amount;
        flashAnim(to, AnimState.HIT, HIT_ANIM_SECONDS);
        event(eventKind, to.x, to.y, amount, to.team, from);
        if (to.hp <= 0) {
            to.hp = 0;
            to.dead = true;
            to.anim = AnimState.DEATH;
            releaseCells(to);
            event("die", to.x, to.y, 0, to.team, to);
        }
    }

    private void releaseCells(CUnit c) {
        if (occupied[c.cellC][c.cellR] == c) occupied[c.cellC][c.cellR] = null;
        if (c.moveC >= 0 && occupied[c.moveC][c.moveR] == c) occupied[c.moveC][c.moveR] = null;
        c.moveC = -1;
        c.moveR = -1;
    }

    private void heal(CUnit from, CUnit to, double amount, boolean announce) {
        if (to.dead || amount <= 0) return;
        double healed = Math.min(amount, to.maxHp - to.hp);
        if (healed <= 0) return;
        to.hp += healed;
        if (from != null) from.healingDone += healed;
        if (announce) event("heal", to.x, to.y, healed, to.team, from);
    }

    private void gainMana(CUnit c, double amount) {
        if (c.dead || c.manaMax <= 0) return;
        c.mana = Math.min(c.manaMax, c.mana + amount);
        if (c.mana >= c.manaMax) cast(c);
    }

    /** Cast the class ability; magnitude scales with star level and spell power. */
    private void cast(CUnit c) {
        UnitDef def = AutoUnits.get(c.key);
        if (def.clazz == null || c.dead) return;
        c.mana = 0;
        double power = def.spell * UnitDef.starMultiplier(c.star) * (1 + c.spellPower / 100.0);
        CUnit target = c.target;
        flashAnim(c, AnimState.CAST, CAST_ANIM_SECONDS);
        event("cast", c.x, c.y, 0, c.team, c);
        switch (def.clazz) {
            case WARRIOR -> { // empowered slash
                if (alive(target)) dealPhysical(c, target, c.ad + power, "hit");
            }
            case ARCHER -> { // piercing volley
                if (alive(target)) dealPhysical(c, target, c.ad * 1.5 + power, "hit");
            }
            case MAGE -> { // fireball: full damage to the target, splash around it
                if (alive(target)) {
                    double tx = target.x, ty = target.y;
                    damage(c, target, power, "hit", true);
                    for (CUnit other : units) {
                        if (other.dead || other.team == c.team || other == target) continue;
                        double dx = other.x - tx, dy = other.y - ty;
                        if (dx * dx + dy * dy <= 1.6 * 1.6) {
                            damage(c, other, power * 0.6, "hit", true);
                        }
                    }
                }
            }
            case ASSASSIN -> { // shadow strike: ignores armor
                if (alive(target)) damage(c, target, c.ad + power, "hit", true);
            }
            case GUARDIAN -> heal(c, c, power * (1 + c.healPower), true); // bulwark
            case HEALER -> { // mend the weakest ally
                CUnit lowest = null;
                for (CUnit other : units) {
                    if (other.dead || other.team != c.team) continue;
                    if (lowest == null || other.hp / other.maxHp < lowest.hp / lowest.maxHp) {
                        lowest = other;
                    }
                }
                if (lowest != null) heal(c, lowest, power * (1 + c.healPower), true);
            }
            case BRAWLER -> { // slam: hurt them, shrug it off
                if (alive(target)) damage(c, target, power * 0.8, "hit", true);
                heal(c, c, power * 0.5 * (1 + c.healPower), true);
            }
            default -> { /* creeps never cast */ }
        }
    }

    private static boolean alive(CUnit c) {
        return c != null && !c.dead;
    }

    private void checkEnd() {
        boolean homeAlive = false, awayAlive = false;
        for (CUnit c : units) {
            if (!c.dead) {
                if (c.team == HOME) homeAlive = true;
                else awayAlive = true;
            }
        }
        if (!homeAlive || !awayAlive) {
            finished = true;
            winner = homeAlive == awayAlive ? -1 : (homeAlive ? HOME : AWAY);
        }
    }

    /** Ends the fight as a draw (round timer expired). */
    public void forceDraw() {
        finished = true;
        winner = -1;
    }

    // --- results & replication ---------------------------------------------------

    public boolean finished() {
        return finished;
    }

    /** {@link #HOME}, {@link #AWAY}, or -1 for a draw / still running. */
    public int winner() {
        return winner;
    }

    public double time() {
        return time;
    }

    public List<CUnit> units() {
        return units;
    }

    public int livingCount(int team) {
        int n = 0;
        for (CUnit c : units) if (!c.dead && c.team == team) n++;
        return n;
    }

    /** Total star levels of a team's survivors — the loser's HP penalty scaler. */
    public int survivorStars(int team) {
        int n = 0;
        for (CUnit c : units) if (!c.dead && c.team == team) n += c.star;
        return n;
    }

    /** Wire form of every unit, for {@code combat} snapshot messages. */
    public List<Map<String, Object>> snapshotUnits() {
        List<Map<String, Object>> out = new ArrayList<>(units.size());
        for (CUnit c : units) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.uid);
            m.put("k", c.key);
            m.put("s", c.star);
            m.put("tm", c.team);
            m.put("x", round2(c.x));
            m.put("y", round2(c.y));
            m.put("hp", Math.rint(c.hp));
            m.put("mh", Math.rint(c.maxHp));
            m.put("mn", Math.rint(c.mana));
            m.put("mx", Math.rint(c.manaMax));
            m.put("st", c.anim.code);
            if (c.dealtPhysical >= 1) m.put("dp", Math.rint(c.dealtPhysical));
            if (c.dealtMagic >= 1) m.put("dm", Math.rint(c.dealtMagic));
            if (c.healingDone >= 1) m.put("dh", Math.rint(c.healingDone));
            if (c.dead) m.put("dd", true);
            out.add(m);
        }
        return out;
    }

    /** Drain the events recorded since the last call (server broadcasts them). */
    public List<Event> drainEvents() {
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    private void event(String kind, double x, double y, double amount, int team, CUnit source) {
        if (events.size() < MAX_EVENTS) {
            events.add(source == null
                    ? new Event(kind, x, y, amount, team, 0, 0, 0)
                    : new Event(kind, x, y, amount, team, source.uid, source.x, source.y));
        }
    }

    private static double round2(double v) {
        return Math.rint(v * 100) / 100.0;
    }
}
