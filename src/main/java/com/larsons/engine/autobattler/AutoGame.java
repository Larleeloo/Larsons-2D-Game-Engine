package com.larsons.engine.autobattler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The complete server-side auto-battler: lobby, rounds, phases, the shared
 * unit pool, shops, economy, item drops, combat pairings, player damage, and
 * elimination — everything except the sockets. {@link AutoServer} owns one of
 * these on its tick thread and forwards client actions in; every outbound
 * message leaves through the {@link Sink}, so the whole game is testable
 * headlessly with a capturing sink and bot players.
 *
 * <p>Round flow (the TFT / Auto Chess loop):
 * <ol>
 *   <li><b>PLAN</b> — buy from your shop, position units, equip items. Rounds
 *       award income (base + interest + streak) and 2 XP.</li>
 *   <li><b>FIGHT</b> — boards lock; every pairing runs a deterministic
 *       {@link BattleSim}. Round 1 and every 5th round fight PvE creeps,
 *       which drop item components. Odd player counts fight a ghost copy of
 *       another player's board.</li>
 *   <li><b>POST</b> — results: the loser takes 2 + surviving-stars damage.
 *       At 0 HP a player is eliminated (units return to the pool); the last
 *       one standing wins.</li>
 * </ol>
 */
public final class AutoGame {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 10;
    public static final int ITEM_BENCH_CAP = 12;
    public static final int REROLL_COST = 2;
    public static final int XP_COST = 4;
    public static final int XP_PER_BUY = 4;
    public static final int XP_PER_ROUND = 2;
    /** Relics are re-dealt on round 1 and every this-many rounds after. */
    public static final int RELIC_INTERVAL = 10;
    /** Chance (%) that a Bargainer shop slot is discounted. */
    public static final int BARGAIN_CHANCE = 35;
    /** Gold knocked off a Bargainer-discounted shop slot. */
    public static final int BARGAIN_DISCOUNT = 3;

    public enum Phase { LOBBY, PLAN, FIGHT, POST, OVER }

    /** Tunable pacing/economy knobs (tests shrink the timers). */
    public static final class Config {
        public double planSeconds = 30;
        /**
         * Combat cap. Generous on purpose: with the sim's global damage
         * rescale fights run longer, tanky builds get to matter, and rounds
         * rarely end in an instant.
         */
        public double fightMaxSeconds = 45;
        public double postSeconds = 4;
        /** Dota Auto Chess opening: level 1, 1 gold, one unit on the board. */
        public int startGold = 1;
        public int startLevel = 1;
        public int startHp = 100;
        public long seed = new Random().nextLong();
    }

    /** Where outbound messages go; the server maps ids to connections. */
    public interface Sink {
        void toPlayer(int playerId, Map<String, Object> message);

        void toAll(Map<String, Object> message);
    }

    /** One combat pairing this round. */
    public static final class Match {
        public final AutoPlayer home;
        /** The real opposing player, or {@code null} for PvE and ghost matches. */
        public final AutoPlayer away;
        public final boolean pve;
        /** Set when {@code away} is a ghost copy: the name of the copied player. */
        public final String ghostOf;
        public final BattleSim sim;
        boolean done;

        Match(AutoPlayer home, AutoPlayer away, boolean pve, String ghostOf, BattleSim sim) {
            this.home = home;
            this.away = away;
            this.pve = pve;
            this.ghostOf = ghostOf;
            this.sim = sim;
        }
    }

    private final Config cfg;
    private final Sink sink;
    private final Random rng;

    private final List<AutoPlayer> players = new ArrayList<>();
    private UnitPool pool;
    private Phase phase = Phase.LOBBY;
    private int round;
    private double phaseLeft;
    private double fightElapsed;
    private final List<Match> matches = new ArrayList<>();

    private int nextPlayerId = 1;
    private int nextUnitId = 1;
    private int nextBotNumber = 1;
    private int hostId = -1;
    private double clockAccum;
    private double botAccum;
    private double snapshotAccum;

    public AutoGame(Config cfg, Sink sink) {
        this.cfg = cfg;
        this.sink = sink;
        this.rng = new Random(cfg.seed);
    }

    // --- lobby --------------------------------------------------------------------

    /** Add a player (or bot); returns {@code null} when full or already started. */
    public AutoPlayer addPlayer(String name, boolean bot) {
        if (phase != Phase.LOBBY || players.size() >= MAX_PLAYERS) return null;
        AutoPlayer p = new AutoPlayer(nextPlayerId++, uniqueName(name), bot);
        players.add(p);
        if (hostId < 0 && !bot) hostId = p.id;
        broadcastLobby();
        return p;
    }

    /** A human left. In the lobby they vanish; mid-game their board plays on. */
    public void removePlayer(int id) {
        AutoPlayer p = byId(id);
        if (p == null) return;
        if (phase == Phase.LOBBY) {
            players.remove(p);
            if (id == hostId) {
                hostId = -1;
                for (AutoPlayer other : players) {
                    if (!other.bot) {
                        hostId = other.id;
                        break;
                    }
                }
            }
            broadcastLobby();
        } else {
            p.connected = false;
        }
    }

    public boolean addBot(int requesterId) {
        if (requesterId != hostId || phase != Phase.LOBBY) return false;
        return addPlayer("Bot " + nextBotNumber++, true) != null;
    }

    public boolean removeBot(int requesterId) {
        if (requesterId != hostId || phase != Phase.LOBBY) return false;
        for (int i = players.size() - 1; i >= 0; i--) {
            if (players.get(i).bot) {
                players.remove(i);
                broadcastLobby();
                return true;
            }
        }
        return false;
    }

    /** Host starts the match; needs {@value MIN_PLAYERS}+ players (bots count). */
    public boolean start(int requesterId) {
        if (phase != Phase.LOBBY || requesterId != hostId
                || players.size() < MIN_PLAYERS) {
            return false;
        }
        pool = new UnitPool();
        for (AutoPlayer p : players) {
            p.hp = cfg.startHp;
            p.gold = cfg.startGold;
            p.level = cfg.startLevel;
        }
        round = 0;
        beginPlanning();
        return true;
    }

    private String uniqueName(String requested) {
        String name = requested == null || requested.isBlank() ? "Player" : requested.strip();
        String candidate = name;
        int n = 2;
        while (byName(candidate) != null) candidate = name + "-" + n++;
        return candidate;
    }

    // --- ticking ------------------------------------------------------------------

    /** Advance the game; called from the server tick thread at a fixed rate. */
    public void tick(double dt) {
        switch (phase) {
            case PLAN -> {
                phaseLeft -= dt;
                botAccum += dt;
                if (botAccum >= 1.0) {
                    botAccum = 0;
                    runBots();
                }
                broadcastClock(dt);
                if (phaseLeft <= 0) beginFight();
            }
            case FIGHT -> {
                fightElapsed += dt;
                boolean allDone = true;
                for (Match m : matches) {
                    if (m.done) continue;
                    m.sim.step(dt);
                    if (fightElapsed >= cfg.fightMaxSeconds && !m.sim.finished()) {
                        m.sim.forceDraw();
                    }
                    if (m.sim.finished()) {
                        resolveMatch(m);
                    } else {
                        allDone = false;
                    }
                }
                broadcastCombat(dt);
                broadcastClock(dt);
                if (allDone) endFight();
            }
            case POST -> {
                phaseLeft -= dt;
                if (phaseLeft <= 0) beginPlanning();
            }
            default -> { /* LOBBY / OVER: nothing to advance */ }
        }
    }

    // --- phase transitions ---------------------------------------------------------

    private void beginPlanning() {
        round++;
        phase = Phase.PLAN;
        phaseLeft = cfg.planSeconds;
        clockAccum = 0;
        botAccum = 0;
        boolean relicRound = (round - 1) % RELIC_INTERVAL == 0;
        for (AutoPlayer p : players) {
            if (!p.alive) continue;
            p.unequipUsed = false;
            if (round > 1) {
                p.gold += p.income();
                int merchant = merchantBonus(p.boardUnits());
                if (merchant > 0) {
                    merchant = (int) Math.round(merchant
                            * p.traitBoosts.getOrDefault(Trait.MERCHANT, 1.0));
                    p.gold += merchant;
                    sink.toPlayer(p.id, AutoProto.info(
                            "Your merchants earned +" + merchant + " gold"));
                }
                p.gainXp(XP_PER_ROUND);
            }
            if (relicRound) {
                grantRelic(p, Relic.roll(rng, p.relic));
            } else if (p.relic != null && p.relic.oneShot && !p.relicFired) {
                // A one-shot relic that couldn't fire yet (e.g. Harmonizer
                // dealt before any unit was owned) retries each round.
                fireOneShot(p);
            }
            if (p.shopLocked) {
                sink.toPlayer(p.id, AutoProto.info("Shop locked — same units kept"));
            } else {
                refreshShop(p);
            }
            sendYou(p);
        }
        broadcastPlayers();
        broadcastPhase();
        runBots();
    }

    // --- relics ---------------------------------------------------------------------

    /** Hand {@code p} a relic (round 1 and every {@value RELIC_INTERVAL} rounds). */
    void grantRelic(AutoPlayer p, Relic relic) {
        p.relic = relic;
        p.relicFired = false;
        sink.toPlayer(p.id, AutoProto.info(
                "Relic: " + relic.label + " — " + relic.description));
        if (relic.oneShot) fireOneShot(p);
    }

    /** Apply a one-shot relic's effect; leaves {@code relicFired} false to retry. */
    private void fireOneShot(AutoPlayer p) {
        switch (p.relic) {
            case MENDER -> {
                int heal = 15 + rng.nextInt(6);
                int healed = Math.min(cfg.startHp, p.hp + heal) - p.hp;
                p.hp += healed;
                p.relicFired = true;
                sink.toPlayer(p.id, AutoProto.info(
                        "The Mender restored " + healed + " HP"));
            }
            case ZEALOT -> {
                Trait[] all = Trait.values();
                Trait t = all[rng.nextInt(all.length)];
                p.traitBoosts.merge(t, Relic.ZEALOT_BOOST, (a, b) -> a * b);
                p.relicFired = true;
                sink.toPlayer(p.id, AutoProto.info(
                        "Zealot: " + t.label + " is now 1.5x as effective for the rest"
                                + " of the game"));
            }
            case HARMONIZER -> {
                UnitInstance u = harmonizerTarget(p);
                if (u == null) return; // no unit yet; retried next round
                Trait t = harmonizerTrait(p, u);
                if (t == null) return;
                u.bonusTrait = t;
                p.relicFired = true;
                sink.toPlayer(p.id, AutoProto.info(
                        "The Harmonizer attuned " + u.def().name + " to " + t.label));
            }
            default -> p.relicFired = true; // passive relics have nothing to fire
        }
    }

    /** A random owned unit for the Harmonizer, preferring fielded ones. */
    private UnitInstance harmonizerTarget(AutoPlayer p) {
        List<UnitInstance> pool = p.boardUnits();
        if (pool.isEmpty()) pool = p.units;
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    /**
     * A trait for the Harmonizer: drawn from the synergies the player already
     * fields (its whole point is deepening a build), skipping ones the unit
     * already carries; any trait as a fallback.
     */
    private Trait harmonizerTrait(AutoPlayer p, UnitInstance u) {
        List<Trait> candidates = new ArrayList<>(
                BattleSim.countTraits(p.boardUnits()).keySet());
        candidates.removeIf(t -> t == u.def().origin || t == u.def().clazz
                || t == u.bonusTrait);
        if (candidates.isEmpty()) {
            for (Trait t : Trait.values()) {
                if (t != u.def().origin && t != u.def().clazz) candidates.add(t);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(rng.nextInt(candidates.size()));
    }

    /** The combat modifiers a player's relics grant their team. */
    private BattleSim.TeamMods modsOf(AutoPlayer p) {
        double amp = p.relic == Relic.VOID_BRAND ? Relic.VOID_BRAND_AMP : 1.0;
        if (amp == 1.0 && p.traitBoosts.isEmpty()) return BattleSim.TeamMods.NONE;
        return new BattleSim.TeamMods(amp, Map.copyOf(p.traitBoosts));
    }

    /** Copies needed to upgrade from {@code star}: the Apprentice relic pairs 2-stars. */
    private int combineNeed(AutoPlayer p, int star) {
        return star == 1 && p.relic == Relic.APPRENTICE ? 2 : 3;
    }

    private void beginFight() {
        phase = Phase.FIGHT;
        fightElapsed = 0;
        snapshotAccum = 0;
        matches.clear();
        List<AutoPlayer> alive = alivePlayers();

        if (AutoUnits.isCreepRound(round)) {
            for (AutoPlayer p : alive) {
                matches.add(new Match(p, null, true, null,
                        new BattleSim(p.boardUnits(), creepBoard(round, p),
                                rng.nextLong(), round, modsOf(p),
                                BattleSim.TeamMods.NONE)));
            }
        } else {
            List<AutoPlayer> order = new ArrayList<>(alive);
            Collections.shuffle(order, rng);
            for (int i = 0; i + 1 < order.size(); i += 2) {
                AutoPlayer home = order.get(i);
                AutoPlayer away = order.get(i + 1);
                matches.add(new Match(home, away, false, null,
                        new BattleSim(home.boardUnits(), away.boardUnits(),
                                rng.nextLong(), round, modsOf(home), modsOf(away))));
            }
            if (order.size() % 2 == 1) {
                AutoPlayer odd = order.get(order.size() - 1);
                AutoPlayer copied = order.get(rng.nextInt(order.size() - 1));
                List<UnitInstance> ghost = new ArrayList<>();
                for (UnitInstance u : copied.boardUnits()) ghost.add(u.copy());
                matches.add(new Match(odd, null, false, copied.name,
                        new BattleSim(odd.boardUnits(), ghost, rng.nextLong(), round,
                                modsOf(odd), modsOf(copied))));
            }
        }

        broadcastPhase();
        for (Match m : matches) {
            sendMatchInfo(m.home, m, true);
            if (m.away != null) sendMatchInfo(m.away, m, false);
        }
        // Eliminated players spectate the first match.
        if (!matches.isEmpty()) {
            for (AutoPlayer p : players) {
                if (!p.alive && p.connected) sendMatchInfo(p, matches.get(0), true);
            }
        }
    }

    private void endFight() {
        // Eliminations: everyone at 0 HP drops out; worst HP takes the worst place.
        List<AutoPlayer> dead = new ArrayList<>();
        for (AutoPlayer p : alivePlayers()) {
            if (p.hp <= 0) dead.add(p);
        }
        dead.sort(Comparator.comparingInt(p -> p.hp));
        int nextPlace = alivePlayers().size();
        for (AutoPlayer p : dead) {
            p.alive = false;
            p.place = nextPlace--;
            for (UnitInstance u : p.units) {
                pool.giveBack(u.key, copiesIn(u.star));
            }
            p.units.clear();
            sink.toAll(AutoProto.info(p.name + " was eliminated (#" + p.place + ")"));
            sendYou(p);
        }

        List<AutoPlayer> alive = alivePlayers();
        broadcastPlayers();
        if (alive.size() <= 1) {
            phase = Phase.OVER;
            AutoPlayer winner = alive.isEmpty() ? null : alive.get(0);
            if (winner != null) winner.place = 1;
            broadcastPlayers();
            sink.toAll(AutoProto.gameOver(winner == null ? "Nobody" : winner.name,
                    finalStandings()));
            broadcastPhase();
        } else {
            phase = Phase.POST;
            phaseLeft = cfg.postSeconds;
            broadcastPhase();
        }
    }

    private void resolveMatch(Match m) {
        m.done = true;
        int winner = m.sim.winner();
        List<BattleSim.Event> leftover = m.sim.drainEvents();
        if (!leftover.isEmpty()) sendCombatFrame(m, leftover);

        if (m.pve) {
            boolean won = winner == BattleSim.HOME;
            applyOutcome(m.home, won, winner == -1,
                    2 + m.sim.survivorStars(BattleSim.AWAY), "Creeps");
            if (won) {
                boolean wildHunt = m.home.relic == Relic.WILD_HUNT;
                m.home.gold += wildHunt ? 4 : 2;
                int drops = wildHunt ? 2 : 1;
                for (int i = 0; i < drops && m.home.items.size() < ITEM_BENCH_CAP; i++) {
                    // Mostly components; sometimes an elemental relic instead.
                    List<String> table = rng.nextInt(100) < 30
                            ? AutoItems.relicKeys() : AutoItems.componentKeys();
                    String drop = table.get(rng.nextInt(table.size()));
                    m.home.items.add(drop);
                    sink.toPlayer(m.home.id, AutoProto.info(
                            "The creeps dropped: " + AutoItems.get(drop).name));
                }
                sendYou(m.home);
            }
        } else {
            String awayName = m.away != null ? m.away.name : "Ghost of " + m.ghostOf;
            boolean draw = winner == -1;
            applyOutcome(m.home, winner == BattleSim.HOME, draw,
                    2 + m.sim.survivorStars(BattleSim.AWAY), awayName);
            if (m.away != null) {
                applyOutcome(m.away, winner == BattleSim.AWAY, draw,
                        2 + m.sim.survivorStars(BattleSim.HOME), m.home.name);
            }
            if (!draw && m.away != null) {
                AutoPlayer victor = winner == BattleSim.HOME ? m.home : m.away;
                AutoPlayer loser = winner == BattleSim.HOME ? m.away : m.home;
                int dealt = 2 + m.sim.survivorStars(winner);
                plunder(victor, loser, dealt);
            }
        }
        broadcastPlayers();
    }

    /** The Plunderer relic: winning steals gold equal to the damage dealt. */
    void plunder(AutoPlayer victor, AutoPlayer loser, int damage) {
        if (victor.relic != Relic.PLUNDERER) return;
        int steal = Math.min(damage, loser.gold);
        if (steal <= 0) return;
        loser.gold -= steal;
        victor.gold += steal;
        sink.toPlayer(victor.id, AutoProto.info(
                "You plundered " + steal + " gold from " + loser.name));
        sink.toPlayer(loser.id, AutoProto.info(
                victor.name + " plundered " + steal + " of your gold"));
        sendYou(victor);
        sendYou(loser);
    }

    /** Update streaks and HP for one player's round result, and tell them. */
    private void applyOutcome(AutoPlayer p, boolean won, boolean draw,
                              int lossDamage, String oppName) {
        int damage = 0;
        if (won) {
            p.streak = p.streak >= 0 ? p.streak + 1 : 1;
            p.wonLastRound = true;
        } else {
            damage = draw ? 2 : lossDamage;
            p.hp -= damage;
            p.streak = p.streak <= 0 ? p.streak - 1 : -1;
            p.wonLastRound = false;
        }
        sink.toPlayer(p.id, AutoProto.result(won, draw, damage, oppName));
        sendYou(p);
    }

    // --- player actions -------------------------------------------------------------

    /** Route one decoded client action; invalid requests get an info toast back. */
    public void handleAction(int playerId, Map<String, Object> msg) {
        AutoPlayer p = byId(playerId);
        if (p == null || phase == Phase.LOBBY) return;
        // Scouting is read-only: it works for eliminated spectators and after
        // the game ends, unlike the mutating actions below.
        if ("view".equals(AutoProto.type(msg))) {
            AutoPlayer target = byId(intOf(msg.get("p")));
            if (target != null) sink.toPlayer(p.id, AutoProto.boardView(target));
            return;
        }
        if (!p.alive || phase == Phase.OVER) return;
        switch (AutoProto.type(msg)) {
            case "buy" -> buy(p, intOf(msg.get("i")));
            case "sell" -> sell(p, intOf(msg.get("u")));
            case "reroll" -> reroll(p);
            case "xp" -> buyXp(p);
            case "move" -> {
                if (msg.containsKey("b")) {
                    moveToBench(p, intOf(msg.get("u")), intOf(msg.get("b")));
                } else {
                    moveToBoard(p, intOf(msg.get("u")), intOf(msg.get("c")), intOf(msg.get("r")));
                }
            }
            case "equip" -> equip(p, intOf(msg.get("i")), intOf(msg.get("u")));
            case "lock" -> toggleLock(p);
            case "unequip" -> unequip(p, intOf(msg.get("u")));
            case "arrange" -> arrange(p, msg.get("m") instanceof String s ? s : "");
            case "fuse" -> fuse(p, intOf(msg.get("w")), intOf(msg.get("u")));
            default -> { /* not an action */ }
        }
    }

    boolean buy(AutoPlayer p, int slot) {
        if (slot < 0 || slot >= AutoPlayer.SHOP_SIZE || p.shop[slot] == null) return false;
        UnitDef def = AutoUnits.get(p.shop[slot]);
        if (def == null) return false;
        int price = Math.max(0, def.cost - p.shopDeals[slot]);
        if (p.gold < price) {
            return reject(p, "Not enough gold");
        }
        int benchSlot = p.freeBenchSlot();
        if (benchSlot < 0 && !buyWouldCombine(p, def.key)) {
            return reject(p, "Bench is full");
        }
        p.gold -= price;
        p.shop[slot] = null;
        p.shopDeals[slot] = 0;
        UnitInstance u = new UnitInstance(nextUnitId++, def.key);
        u.paid = price;
        // A full bench still accepts a copy that completes an upgrade: it
        // waits in an overflow slot that tryCombine immediately merges away.
        u.placeBench(benchSlot >= 0 ? benchSlot : AutoPlayer.BENCH_SIZE);
        p.units.add(u);
        tryCombine(p, def.key);
        sendYou(p);
        return true;
    }

    /** Whether buying {@code key} completes an upgrade even with no bench room. */
    private boolean buyWouldCombine(AutoPlayer p, String key) {
        int owned = 0;
        for (UnitInstance u : p.units) {
            if (u.key.equals(key) && u.star == 1) owned++;
        }
        return owned >= combineNeed(p, 1) - 1;
    }

    boolean sell(AutoPlayer p, int unitId) {
        UnitInstance u = p.find(unitId);
        if (u == null) return false;
        if (u.onBoard() && phase != Phase.PLAN) {
            return reject(p, "Can't sell fielded units during combat");
        }
        p.gold += sellValue(p, u);
        pool.giveBack(u.key, copiesIn(u.star));
        for (String item : u.items) {
            if (p.items.size() < ITEM_BENCH_CAP) p.items.add(item);
        }
        p.units.remove(u);
        sendYou(p);
        return true;
    }

    /**
     * A unit's sell price: 1-stars refund what was actually paid (relic
     * discounts included); upgraded units refund their combined copy value,
     * tripled while the Fence relic is held.
     */
    int sellValue(AutoPlayer p, UnitInstance u) {
        if (u.star <= 1) return u.paid >= 0 ? u.paid : u.def().cost;
        int value = u.def().value(u.star);
        return p.relic == Relic.FENCE ? value * 3 : value;
    }

    /** Toggle the shop lock: a locked shop is kept, not re-rolled, next round. */
    boolean toggleLock(AutoPlayer p) {
        p.shopLocked = !p.shopLocked;
        sink.toPlayer(p.id, AutoProto.info(p.shopLocked
                ? "Shop locked — these units will still be here next round"
                : "Shop unlocked"));
        sendYou(p);
        return true;
    }

    /** Strip every item off a unit back to the item bench; once per round. */
    boolean unequip(AutoPlayer p, int unitId) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        UnitInstance u = p.find(unitId);
        if (u == null || u.items.isEmpty()) return reject(p, "No items to remove");
        if (p.unequipUsed) return reject(p, "Item removal already used this round");
        int moved = 0;
        for (int i = u.items.size() - 1; i >= 0; i--) {
            if (p.items.size() >= ITEM_BENCH_CAP) break;
            p.items.add(u.items.remove(i));
            moved++;
        }
        if (moved == 0) return reject(p, "Item bench is full");
        p.unequipUsed = true;
        sink.toPlayer(p.id, AutoProto.info(
                "Removed " + moved + " item" + (moved == 1 ? "" : "s")
                        + " (once per round)"));
        sendYou(p);
        return true;
    }

    /**
     * Reposition the whole fielded board with one click: {@code front} packs
     * everyone toward the front line, {@code spread} disperses them across
     * the half, {@code flip} mirrors the formation left-to-right.
     */
    boolean arrange(AutoPlayer p, String mode) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        List<UnitInstance> board = p.boardUnits();
        if (board.isEmpty()) return false;
        switch (mode) {
            case "flip" -> {
                for (UnitInstance u : board) {
                    u.placeBoard(BattleSim.COLS - 1 - u.col, u.row);
                }
            }
            case "front" -> {
                // Melee lead the charge; everyone packs centre-out from row 4.
                board.sort(Comparator.comparing(u -> !u.def().melee()));
                placeAlong(board, frontCells());
            }
            case "spread" -> {
                // Checkerboard spacing: melee fill from the front of the
                // pattern, ranged from the back — indexes never meet, so
                // every unit lands on its own cell.
                List<UnitInstance> melee = new ArrayList<>();
                List<UnitInstance> ranged = new ArrayList<>();
                for (UnitInstance u : board) {
                    (u.def().melee() ? melee : ranged).add(u);
                }
                List<int[]> cells = spreadCells();
                int front = 0, back = cells.size() - 1;
                for (UnitInstance u : melee) {
                    if (front > back) break;
                    int[] cell = cells.get(front++);
                    u.placeBoard(cell[0], cell[1]);
                }
                for (UnitInstance u : ranged) {
                    if (back < front) break;
                    int[] cell = cells.get(back--);
                    u.placeBoard(cell[0], cell[1]);
                }
            }
            default -> {
                return false;
            }
        }
        sendYou(p);
        return true;
    }

    private static void placeAlong(List<UnitInstance> units, List<int[]> cells) {
        for (int i = 0; i < units.size() && i < cells.size(); i++) {
            units.get(i).placeBoard(cells.get(i)[0], cells.get(i)[1]);
        }
    }

    /** Own-half cells row by row from the front line, centre columns first. */
    private static List<int[]> frontCells() {
        int[] cols = {3, 4, 2, 5, 1, 6, 0, 7};
        List<int[]> cells = new ArrayList<>();
        for (int r = BattleSim.ROWS / 2; r < BattleSim.ROWS; r++) {
            for (int c : cols) cells.add(new int[]{c, r});
        }
        return cells;
    }

    /** Every other own-half cell (checkerboard), front rows first. */
    private static List<int[]> spreadCells() {
        List<int[]> cells = new ArrayList<>();
        for (int r = BattleSim.ROWS / 2; r < BattleSim.ROWS; r++) {
            for (int c = (r % 2 == 0) ? 0 : 1; c < BattleSim.COLS; c += 2) {
                cells.add(new int[]{c, r});
            }
        }
        return cells;
    }

    /**
     * Fuse a Wisp into a pair: the Wisp stands in for the missing copy (Io,
     * Dota Auto Chess style), consuming itself to upgrade the pair on the
     * spot. Needs a 1-star Wisp and {@code need - 1} copies of the target.
     */
    boolean fuse(AutoPlayer p, int wispId, int targetId) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        UnitInstance wisp = p.find(wispId);
        UnitInstance target = p.find(targetId);
        if (wisp == null || target == null || !AutoUnits.WISP.equals(wisp.key)
                || wisp == target) {
            return false;
        }
        if (wisp.star > 1) return reject(p, "Only 1-star Wisps can fuse");
        if (AutoUnits.WISP.equals(target.key)) {
            return reject(p, "A Wisp can't fuse with another Wisp");
        }
        if (target.star >= 3) return reject(p, "Already at max stars");
        int copies = 0;
        for (UnitInstance u : p.units) {
            if (u.key.equals(target.key) && u.star == target.star) copies++;
        }
        if (copies < combineNeed(p, target.star) - 1) {
            return reject(p, "Fusing needs a pair of " + target.def().name);
        }
        // The wisp dissolves into a stand-in copy, and the usual merge runs.
        p.units.remove(wisp);
        pool.giveBack(wisp.key, copiesIn(wisp.star));
        for (String item : wisp.items) {
            if (p.items.size() < ITEM_BENCH_CAP) p.items.add(item);
        }
        UnitInstance standIn = new UnitInstance(nextUnitId++, target.key);
        standIn.star = target.star;
        standIn.placeBench(AutoPlayer.BENCH_SIZE); // overflow; merged immediately
        p.units.add(standIn);
        sink.toPlayer(p.id, AutoProto.info(
                "The Wisp fused into " + target.def().name + "!"));
        tryCombine(p, target.key);
        sendYou(p);
        return true;
    }

    boolean reroll(AutoPlayer p) {
        if (p.gold < REROLL_COST) return reject(p, "Not enough gold");
        p.gold -= REROLL_COST;
        refreshShop(p);
        sendYou(p);
        return true;
    }

    boolean buyXp(AutoPlayer p) {
        if (p.level >= AutoPlayer.MAX_LEVEL) return reject(p, "Already max level");
        if (p.gold < XP_COST) return reject(p, "Not enough gold");
        p.gold -= XP_COST;
        p.gainXp(XP_PER_BUY);
        sendYou(p);
        return true;
    }

    boolean moveToBench(AutoPlayer p, int unitId, int slot) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        UnitInstance u = p.find(unitId);
        if (u == null || slot < 0 || slot >= AutoPlayer.BENCH_SIZE) return false;
        UnitInstance other = p.benchAt(slot);
        if (other == u) return false;
        if (other != null) {
            // Swap: the displaced unit takes this unit's old spot.
            if (u.onBoard()) other.placeBoard(u.col, u.row);
            else other.placeBench(u.bench);
        }
        u.placeBench(slot);
        sendYou(p);
        return true;
    }

    boolean moveToBoard(AutoPlayer p, int unitId, int col, int row) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        UnitInstance u = p.find(unitId);
        if (u == null || col < 0 || col >= BattleSim.COLS
                || row < BattleSim.ROWS / 2 || row >= BattleSim.ROWS) {
            return reject(p, "Place units on your half of the board");
        }
        UnitInstance other = p.boardAt(col, row);
        if (other == u) return false;
        if (other == null && !u.onBoard() && p.boardCount() >= p.boardCap()) {
            return reject(p, "Board limit reached (level up to field more)");
        }
        if (other != null) {
            if (u.onBoard()) other.placeBoard(u.col, u.row);
            else other.placeBench(u.bench);
        }
        u.placeBoard(col, row);
        sendYou(p);
        return true;
    }

    boolean equip(AutoPlayer p, int itemIndex, int unitId) {
        if (phase != Phase.PLAN) return reject(p, "Wait for the planning phase");
        if (itemIndex < 0 || itemIndex >= p.items.size()) return false;
        UnitInstance u = p.find(unitId);
        if (u == null) return false;
        String key = p.items.get(itemIndex);
        AutoItem item = AutoItems.get(key);
        if (item == null) return false;

        // A component pairs with a held component into a combined item.
        if (item.isComponent()) {
            for (int i = 0; i < u.items.size(); i++) {
                String combined = AutoItems.combine(u.items.get(i), key);
                if (combined != null) {
                    u.items.set(i, combined);
                    p.items.remove(itemIndex);
                    sendYou(p);
                    return true;
                }
            }
        }
        if (u.items.size() >= UnitInstance.MAX_ITEMS) {
            return reject(p, "That unit's item slots are full");
        }
        u.items.add(key);
        p.items.remove(itemIndex);
        sendYou(p);
        return true;
    }

    /**
     * Merge identical units into a starred-up one, cascading (upgraded copies
     * can combine again). Normally three copies make a star; the Apprentice
     * relic pairs 1-stars. Fielded copies are preferred as the keeper so an
     * upgrade never unfields a unit.
     */
    private void tryCombine(AutoPlayer p, String key) {
        for (int star = 1; star < 3; star++) {
            int need = combineNeed(p, star);
            List<UnitInstance> same = new ArrayList<>();
            for (UnitInstance u : p.units) {
                if (u.key.equals(key) && u.star == star) same.add(u);
            }
            if (same.size() < need) continue;
            UnitInstance keeper = same.get(0);
            for (UnitInstance u : same) {
                if (u.onBoard()) {
                    keeper = u;
                    break;
                }
            }
            keeper.star = star + 1;
            List<UnitInstance> feeders = new ArrayList<>(same);
            feeders.remove(keeper);
            // Overflow copies (bench-full buys, wisp stand-ins) must merge
            // away first — they have no real slot to live in.
            feeders.sort(Comparator.comparing(u -> u.bench != AutoPlayer.BENCH_SIZE));
            int consumed = 0;
            for (UnitInstance u : feeders) {
                if (consumed >= need - 1) break;
                consumed++;
                for (String item : u.items) {
                    if (keeper.items.size() < UnitInstance.MAX_ITEMS) keeper.items.add(item);
                    else if (p.items.size() < ITEM_BENCH_CAP) p.items.add(item);
                }
                if (u.bonusTrait != null && keeper.bonusTrait == null) {
                    keeper.bonusTrait = u.bonusTrait;
                }
                p.units.remove(u);
            }
            sink.toPlayer(p.id, AutoProto.info(
                    keeper.def().name + " reached " + keeper.star + " stars!"));
        }
    }

    /** Return unbought cards to the pool and roll a fresh shop. */
    private void refreshShop(AutoPlayer p) {
        for (int i = 0; i < p.shop.length; i++) {
            if (p.shop[i] != null) {
                pool.giveBack(p.shop[i], 1);
                p.shop[i] = null;
            }
            p.shopDeals[i] = 0;
        }
        // The Collector relic over-weights units the player already owns.
        Set<String> favored = Set.of();
        if (p.relic == Relic.COLLECTOR && !p.units.isEmpty()) {
            favored = new HashSet<>();
            for (UnitInstance u : p.units) favored.add(u.key);
        }
        List<String> rolled = pool.rollShop(p.level, AutoPlayer.SHOP_SIZE, rng, favored);
        for (int i = 0; i < p.shop.length; i++) {
            p.shop[i] = rolled.get(i);
            if (p.shop[i] != null && p.relic == Relic.BARGAINER
                    && rng.nextInt(100) < BARGAIN_CHANCE) {
                p.shopDeals[i] = BARGAIN_DISCOUNT;
            }
        }
    }

    private boolean reject(AutoPlayer p, String reason) {
        sink.toPlayer(p.id, AutoProto.info(reason));
        return false;
    }

    // --- bots -----------------------------------------------------------------------

    private void runBots() {
        if (phase != Phase.PLAN) return;
        for (AutoPlayer p : players) {
            if (p.bot && p.alive) AutoBot.act(this, p, rng);
        }
    }

    // --- creeps ---------------------------------------------------------------------

    /** Build the round's creep board (positions use own-half rows like a player's). */
    private List<UnitInstance> creepBoard(int round, AutoPlayer opponent) {
        List<UnitInstance> creeps = new ArrayList<>();
        // Wild Hunt boards fight a randomized, larger wave for richer rewards.
        List<String> wave = opponent != null && opponent.relic == Relic.WILD_HUNT
                ? AutoUnits.randomCreepWave(round, rng)
                : AutoUnits.creepWave(round);
        for (int i = 0; i < wave.size(); i++) {
            UnitInstance u = new UnitInstance(nextUnitId++, wave.get(i));
            u.placeBoard(2 + (i % 4), 4 + (i / 4));
            creeps.add(u);
        }
        return creeps;
    }

    // --- messaging --------------------------------------------------------------------

    private void broadcastLobby() {
        sink.toAll(AutoProto.lobby(hostId, players));
    }

    private void broadcastPhase() {
        boolean pve = AutoUnits.isCreepRound(round);
        sink.toAll(AutoProto.phase(phase, round, pve, phaseLeft));
    }

    private void broadcastClock(double dt) {
        clockAccum += dt;
        if (clockAccum >= 1.0) {
            clockAccum = 0;
            double left = phase == Phase.FIGHT
                    ? Math.max(0, cfg.fightMaxSeconds - fightElapsed) : Math.max(0, phaseLeft);
            sink.toAll(AutoProto.clock(left));
        }
    }

    private void broadcastPlayers() {
        List<Map<String, Object>> rows = new ArrayList<>(players.size());
        for (AutoPlayer p : players) rows.add(p.toPublicMap());
        sink.toAll(AutoProto.players(rows));
    }

    private void sendYou(AutoPlayer p) {
        if (!p.bot && p.connected) sink.toPlayer(p.id, AutoProto.you(p));
    }

    private void sendMatchInfo(AutoPlayer to, Match m, boolean asHome) {
        if (to.bot || !to.connected) return;
        String oppName = m.pve ? "Creeps"
                : m.away == null ? "Ghost of " + m.ghostOf
                : (asHome ? m.away.name : m.home.name);
        boolean home = asHome || to != m.away;
        sink.toPlayer(to.id, AutoProto.match(oppName, m.ghostOf != null, m.pve, home));
    }

    /** Stream combat snapshots (~15 Hz) to each match's participants + spectators. */
    private void broadcastCombat(double dt) {
        snapshotAccum += dt;
        if (snapshotAccum < 1.0 / 15) return;
        snapshotAccum = 0;
        for (Match m : matches) {
            if (!m.done) sendCombatFrame(m, m.sim.drainEvents());
        }
    }

    private void sendCombatFrame(Match m, List<BattleSim.Event> events) {
        Map<String, Object> msg = AutoProto.combat(m.sim.snapshotUnits(), events);
        if (!m.home.bot && m.home.connected) sink.toPlayer(m.home.id, msg);
        if (m.away != null && !m.away.bot && m.away.connected) {
            sink.toPlayer(m.away.id, msg);
        }
        if (!matches.isEmpty() && m == matches.get(0)) {
            for (AutoPlayer p : players) {
                if (!p.alive && p.connected && !p.bot) sink.toPlayer(p.id, msg);
            }
        }
    }

    private List<Map<String, Object>> finalStandings() {
        List<AutoPlayer> ranked = new ArrayList<>(players);
        ranked.sort(Comparator.comparingInt(p -> p.place > 0 ? p.place : MAX_PLAYERS + 1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AutoPlayer p : ranked) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("place", p.place);
            out.add(m);
        }
        return out;
    }

    // --- queries (server + tests) ------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public int hostId() {
        return hostId;
    }

    public List<AutoPlayer> players() {
        return players;
    }

    public List<AutoPlayer> alivePlayers() {
        List<AutoPlayer> out = new ArrayList<>();
        for (AutoPlayer p : players) if (p.alive) out.add(p);
        return out;
    }

    public List<Match> matches() {
        return matches;
    }

    public UnitPool pool() {
        return pool;
    }

    public AutoPlayer byId(int id) {
        for (AutoPlayer p : players) if (p.id == id) return p;
        return null;
    }

    private AutoPlayer byName(String name) {
        for (AutoPlayer p : players) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    /** Sends the full current state to one (re)connected player. */
    public void sendFullState(int playerId) {
        AutoPlayer p = byId(playerId);
        if (p == null) return;
        broadcastLobby();
        if (phase != Phase.LOBBY) {
            sendYou(p);
            broadcastPlayers();
            broadcastPhase();
        }
    }

    static int copiesIn(int star) {
        int copies = 1;
        for (int i = 1; i < star; i++) copies *= 3;
        return copies;
    }

    /** Extra start-of-round gold from the Merchant synergy on a fielded board. */
    static int merchantBonus(List<UnitInstance> board) {
        int merchants = BattleSim.countTraits(board).getOrDefault(Trait.MERCHANT, 0);
        return (int) Trait.MERCHANT.value(merchants);
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }
}
