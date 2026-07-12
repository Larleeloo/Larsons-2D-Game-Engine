package com.larsons.engine.deckbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The complete server-side Council of Six: lobby, leader picks, rounds, the
 * turn rotation, agent placement, the shared market row, the round-end
 * conflict and territory majorities, and the win check — everything except
 * the sockets. {@link DeckServer} owns one of these on its tick thread and
 * forwards client actions in; every outbound message leaves through the
 * {@link Sink}, so the whole game is testable headlessly with a capturing
 * sink and bot players (the same seam as
 * {@link com.larsons.engine.autobattler.AutoGame}).
 *
 * <p>The round loop (Dune Imperium's rhythm, Inis's map, none of Magic's
 * stack):
 * <ol>
 *   <li>Everyone draws a hand (5 cards; Dustin draws 6) and gets 2 agents.
 *       Turn order rotates one seat per round.</li>
 *   <li>On your turn: <b>play</b> a card to send an agent to a matching,
 *       unoccupied location (resolving both effects, ending your turn), or
 *       <b>reveal</b> your remaining hand — its reveal values become market
 *       gold and conflict might — after which you may <b>buy</b> market cards
 *       until you press <b>done</b>. Buying is also allowed before your main
 *       action; bought cards go to your discard pile.</li>
 *   <li>When everyone has revealed, the round resolves: the most committed
 *       might wins the round's conflict reward (second place gets a smaller
 *       one), each territory's strict troop majority scores 1 VP, and the
 *       first player token passes left.</li>
 * </ol>
 * First to {@link Config#targetVp} at a round end wins; after
 * {@link Config#maxRounds} rounds the highest total wins.
 */
public final class DeckGame {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 6;
    public static final int AGENTS_PER_ROUND = 2;
    public static final int MARKET_SIZE = 5;

    public enum Phase { LOBBY, PLAY, POST, OVER }

    /** Tunable pacing knobs (tests shrink the timers). */
    public static final class Config {
        /** Per-turn clock; expiring auto-reveals and passes. */
        public double turnSeconds = 45;
        /** Pause after a round resolves so the results can be read. */
        public double postSeconds = 5;
        /** Seconds between bot actions, so bot turns are watchable. */
        public double botActionSeconds = 0.6;
        public int targetVp = 10;
        public int maxRounds = 8;
        public int startGold = 1;
        public long seed = new Random().nextLong();
    }

    /** Where outbound messages go; the server maps ids to connections. */
    public interface Sink {
        void toPlayer(int playerId, Map<String, Object> message);

        void toAll(Map<String, Object> message);
    }

    /** This round's conflict prize: first place and the consolation. */
    public record ConflictReward(int firstVp, int firstGold, int secondVp, int secondGold) {}

    /** Conflict rewards by round (clamped past the end): stakes grow late. */
    private static final int[][] CONFLICT_TABLE = {
            {1, 1, 0, 1},
            {1, 2, 0, 1},
            {2, 0, 0, 2},
            {2, 1, 1, 0},
            {2, 2, 1, 0},
            {3, 0, 1, 1},
            {3, 2, 1, 1},
            {3, 3, 1, 2},
    };

    private final Config cfg;
    private final Sink sink;
    private final Random rng;

    private final List<DeckPlayer> players = new ArrayList<>();
    private Phase phase = Phase.LOBBY;
    private int round;
    private int hostId = -1;
    private int nextPlayerId = 1;
    private int nextBotNumber = 1;

    /** Agents on the board: location key -> occupying player ids (usually one). */
    private final Map<String, List<Integer>> occupants = new LinkedHashMap<>();
    private final List<String> marketDeck = new ArrayList<>();
    private final List<String> market = new ArrayList<>();

    private int turnIndex = -1;
    private double turnLeft;
    private double postLeft;
    private boolean overAfterPost;
    private double clockAccum;
    private double botAccum;

    public DeckGame(Config cfg, Sink sink) {
        this.cfg = cfg;
        this.sink = sink;
        this.rng = new Random(cfg.seed);
    }

    // --- accessors ------------------------------------------------------------------

    public Phase phase() { return phase; }

    public int round() { return round; }

    public int hostId() { return hostId; }

    public List<DeckPlayer> players() { return players; }

    public List<String> market() { return market; }

    public double turnLeft() { return Math.max(0, turnLeft); }

    public DeckPlayer byId(int id) {
        for (DeckPlayer p : players) {
            if (p.id == id) return p;
        }
        return null;
    }

    public DeckPlayer byName(String name) {
        for (DeckPlayer p : players) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    /** The player whose turn it is, or {@code null} outside the PLAY phase. */
    public DeckPlayer turnPlayer() {
        return phase == Phase.PLAY && turnIndex >= 0 && turnIndex < players.size()
                ? players.get(turnIndex) : null;
    }

    public int turnPlayerId() {
        DeckPlayer p = turnPlayer();
        return p == null ? -1 : p.id;
    }

    /** Agent(s) standing on a location this round (empty when free). */
    public List<Integer> occupantsOf(String locationKey) {
        List<Integer> list = occupants.get(locationKey);
        return list == null ? List.of() : list;
    }

    public ConflictReward conflictReward() {
        int[] row = CONFLICT_TABLE[Math.max(0,
                Math.min(CONFLICT_TABLE.length - 1, round - 1))];
        return new ConflictReward(row[0], row[1], row[2], row[3]);
    }

    // --- lobby ----------------------------------------------------------------------

    /** Add a player (or bot); returns {@code null} when full or already started. */
    public DeckPlayer addPlayer(String name, boolean bot) {
        if (phase != Phase.LOBBY || players.size() >= MAX_PLAYERS) return null;
        DeckPlayer p = new DeckPlayer(nextPlayerId++, uniqueName(name), bot);
        players.add(p);
        if (hostId < 0 && !bot) hostId = p.id;
        broadcastLobby();
        return p;
    }

    /** A human left. In the lobby they vanish; mid-game their seat auto-passes. */
    public void removePlayer(int id) {
        DeckPlayer p = byId(id);
        if (p == null) return;
        if (phase == Phase.LOBBY) {
            players.remove(p);
            if (id == hostId) {
                hostId = -1;
                for (DeckPlayer other : players) {
                    if (!other.bot) {
                        hostId = other.id;
                        break;
                    }
                }
            }
            broadcastLobby();
        } else {
            p.connected = false;
            if (phase == Phase.PLAY && turnPlayer() == p) {
                // Their turn dies with them; pass immediately.
                finishTurn(p);
            }
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

    /** Claim a leader in the lobby; first come, first served. */
    public boolean pickLeader(int playerId, Leader leader) {
        DeckPlayer p = byId(playerId);
        if (p == null || phase != Phase.LOBBY || leader == null) return false;
        for (DeckPlayer other : players) {
            if (other != p && other.leader == leader) {
                sink.toPlayer(playerId, DeckProto.info(
                        leader.friendName + " is already taken"));
                return false;
            }
        }
        p.leader = leader;
        broadcastLobby();
        return true;
    }

    /** Host starts the match; needs {@value MIN_PLAYERS}+ players (bots count). */
    public boolean start(int requesterId) {
        if (phase != Phase.LOBBY || requesterId != hostId
                || players.size() < MIN_PLAYERS) {
            return false;
        }
        assignFreeLeaders();
        marketDeck.clear();
        marketDeck.addAll(Cards.marketPool());
        Collections.shuffle(marketDeck, rng);
        market.clear();
        for (int i = 0; i < MARKET_SIZE && !marketDeck.isEmpty(); i++) {
            market.add(marketDeck.remove(marketDeck.size() - 1));
        }
        for (DeckPlayer p : players) {
            p.gold = cfg.startGold;
            p.lore = 0;
            p.might = 0;
            p.vp = 0;
            p.troops.clear();
            p.setupDeck(rng);
        }
        round = 0;
        overAfterPost = false;
        beginRound();
        return true;
    }

    /** Anyone still leaderless when the host starts gets a random free one. */
    private void assignFreeLeaders() {
        List<Leader> free = new ArrayList<>(List.of(Leader.values()));
        for (DeckPlayer p : players) free.remove(p.leader);
        Collections.shuffle(free, rng);
        for (DeckPlayer p : players) {
            if (p.leader == null && !free.isEmpty()) {
                p.leader = free.remove(free.size() - 1);
            }
        }
    }

    private String uniqueName(String requested) {
        String name = requested == null || requested.isBlank() ? "Player" : requested.strip();
        String candidate = name;
        int n = 2;
        while (byName(candidate) != null) candidate = name + "-" + n++;
        return candidate;
    }

    // --- ticking --------------------------------------------------------------------

    /** Advance the game; called from the server tick thread at a fixed rate. */
    public void tick(double dt) {
        switch (phase) {
            case PLAY -> {
                turnLeft -= dt;
                broadcastClock(dt);
                DeckPlayer p = turnPlayer();
                if (p == null) return;
                if (p.bot) {
                    botAccum += dt;
                    if (botAccum >= cfg.botActionSeconds) {
                        botAccum = 0;
                        DeckBot.act(this, p);
                    }
                } else if (turnLeft <= 0 || !p.connected) {
                    // The clock ran out (or they left): reveal and pass.
                    finishTurn(p);
                }
            }
            case POST -> {
                postLeft -= dt;
                if (postLeft <= 0) {
                    if (overAfterPost) {
                        finishGame();
                    } else {
                        beginRound();
                    }
                }
            }
            default -> { /* LOBBY / OVER: nothing to advance */ }
        }
    }

    private void broadcastClock(double dt) {
        clockAccum += dt;
        if (clockAccum >= 1.0) {
            clockAccum = 0;
            sink.toAll(DeckProto.clock(turnLeft()));
        }
    }

    // --- round flow -----------------------------------------------------------------

    private void beginRound() {
        round++;
        phase = Phase.PLAY;
        occupants.clear();

        for (DeckPlayer p : players) {
            // Sweep last round's cards into the discard pile.
            p.discard.addAll(p.hand);
            p.discard.addAll(p.played);
            p.hand.clear();
            p.played.clear();

            p.might = 0;
            p.agentsLeft = AGENTS_PER_ROUND;
            p.revealed = false;
            p.envoyReady = p.leader == Leader.BELLA;
            p.tacticianUsed = false;
            p.bazaarDiscount = false;

            // Round-start passives.
            if (p.leader == Leader.LARSON) p.gold += 1;
            if (p.leader == Leader.KRIS) p.gold += Math.min(2, p.gold / 4);

            p.draw(p.handSize(), rng);
        }

        sink.toAll(DeckProto.phase(phase, round));
        // First player rotates one seat per round.
        if (!setTurnFrom((round - 1) % players.size())) return;
        pushAll();
    }

    /**
     * Seat the turn on the first non-revealed player at or after
     * {@code startIndex}, auto-revealing disconnected seats along the way.
     * Returns {@code false} when everyone is done and the round resolved.
     */
    private boolean setTurnFrom(int startIndex) {
        for (int i = 0; i < players.size(); i++) {
            int idx = (startIndex + i) % players.size();
            DeckPlayer p = players.get(idx);
            if (p.revealed) continue;
            if (!p.connected) {
                p.revealed = true; // an empty seat plays nothing
                continue;
            }
            turnIndex = idx;
            turnLeft = cfg.turnSeconds;
            botAccum = 0;
            sink.toAll(DeckProto.fx("turn", p.id, null, null, null, 0,
                    p.name + "'s turn"));
            return true;
        }
        turnIndex = -1;
        endRound();
        return false;
    }

    /** End {@code p}'s participation if needed, then pass the turn along. */
    private void finishTurn(DeckPlayer p) {
        if (!p.revealed) doReveal(p);
        int after = (indexOf(p) + 1) % players.size();
        if (setTurnFrom(after)) pushAll();
    }

    private int indexOf(DeckPlayer p) {
        int idx = players.indexOf(p);
        return Math.max(0, idx);
    }

    private void endRound() {
        resolveConflict();
        resolveTerritories();

        overAfterPost = round >= cfg.maxRounds;
        for (DeckPlayer p : players) {
            if (p.vp >= cfg.targetVp) overAfterPost = true;
        }

        phase = Phase.POST;
        postLeft = cfg.postSeconds;
        sink.toAll(DeckProto.phase(phase, round));
        pushAll();
    }

    /** Most committed might takes the round's prize; runner-up a smaller one. */
    private void resolveConflict() {
        for (DeckPlayer p : players) {
            if (p.leader == Leader.ERIC) p.might += 1; // the Warlord always shows up
        }

        int best = 0;
        int second = 0;
        for (DeckPlayer p : players) {
            if (p.might > best) {
                second = best;
                best = p.might;
            } else if (p.might > second) {
                second = p.might;
            }
        }
        if (best <= 0) {
            sink.toAll(DeckProto.fx("conflict", 0, null, null, null, 0,
                    "Nobody contested the conflict"));
            return;
        }

        ConflictReward reward = conflictReward();
        List<DeckPlayer> winners = new ArrayList<>();
        for (DeckPlayer p : players) {
            if (p.might == best) winners.add(p);
        }
        if (winners.size() == 1) {
            DeckPlayer w = winners.get(0);
            w.vp += reward.firstVp();
            w.gold += reward.firstGold();
            sink.toAll(DeckProto.fx("conflict", w.id, null, null, null,
                    reward.firstVp(), w.name + " wins the conflict ("
                            + best + " might)"));
            if (second > 0) {
                for (DeckPlayer p : players) {
                    if (p.might == second) {
                        p.vp += reward.secondVp();
                        p.gold += reward.secondGold();
                    }
                }
            }
        } else {
            // A tied top splits the consolation prize; nobody takes first.
            for (DeckPlayer p : winners) {
                p.vp += reward.secondVp();
                p.gold += reward.secondGold();
            }
            sink.toAll(DeckProto.fx("conflict", 0, null, null, null, 0,
                    "The conflict ends in a stand-off (" + best + " might)"));
        }
    }

    /** Each territory's strict troop majority scores a victory point. */
    private void resolveTerritories() {
        for (Territory t : Territory.values()) {
            DeckPlayer majority = null;
            int best = 0;
            boolean tied = false;
            for (DeckPlayer p : players) {
                int n = p.troopsIn(t);
                if (n > best) {
                    best = n;
                    majority = p;
                    tied = false;
                } else if (n == best && n > 0) {
                    tied = true;
                }
            }
            if (majority != null && !tied) {
                majority.vp += 1;
                sink.toAll(DeckProto.fx("vp", majority.id, null, null, t, 1,
                        majority.name + " holds " + t.label));
            }
        }
    }

    private void finishGame() {
        phase = Phase.OVER;
        List<DeckPlayer> order = new ArrayList<>(players);
        order.sort((a, b) -> {
            if (a.vp != b.vp) return Integer.compare(b.vp, a.vp);
            if (a.gold != b.gold) return Integer.compare(b.gold, a.gold);
            if (a.troopsTotal() != b.troopsTotal()) {
                return Integer.compare(b.troopsTotal(), a.troopsTotal());
            }
            return Integer.compare(a.id, b.id);
        });
        List<Map<String, Object>> standings = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            DeckPlayer p = order.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("place", i + 1);
            row.put("name", p.name);
            row.put("vp", p.vp);
            standings.add(row);
        }
        sink.toAll(DeckProto.gameOver(order.get(0).name, standings));
    }

    /** The eventual winner by the final-standings ordering (for tests). */
    public DeckPlayer leader() {
        DeckPlayer best = null;
        for (DeckPlayer p : players) {
            if (best == null || p.vp > best.vp
                    || (p.vp == best.vp && p.gold > best.gold)) {
                best = p;
            }
        }
        return best;
    }

    // --- actions --------------------------------------------------------------------

    /** Route one decoded client action; unknown types are ignored. */
    public void handleAction(int playerId, Map<String, Object> msg) {
        DeckPlayer p = byId(playerId);
        if (p == null) return;
        switch (DeckProto.type(msg)) {
            case "leader" -> pickLeader(playerId,
                    Leader.fromCode(msg.get("k") instanceof String s ? s : null));
            case "play" -> {
                int c = intOf(msg.get("c"));
                String loc = msg.get("loc") instanceof String s ? s : null;
                Territory terr = Territory.fromCode(
                        msg.get("terr") instanceof String s ? s : null);
                if (!playCard(p, c, loc, terr)) {
                    sink.toPlayer(playerId, DeckProto.info("You can't play that there"));
                }
            }
            case "buy" -> {
                if (!buy(p, intOf(msg.get("m")))) {
                    sink.toPlayer(playerId, DeckProto.info("You can't afford that"));
                }
            }
            case "reveal" -> reveal(p);
            case "done" -> done(p);
            default -> { /* not a deckbuilder action */ }
        }
    }

    /**
     * Play a hand card to a location on your turn: icon must match, the
     * location must be free (Bella may crash an occupied one, once per
     * round), and its entry cost paid. Resolves the location's and the
     * card's effects, spends an agent, and ends the turn.
     */
    public boolean playCard(DeckPlayer p, int handIndex, String locationKey,
                            Territory territory) {
        if (phase != Phase.PLAY || turnPlayer() != p || p.revealed) return false;
        if (p.agentsLeft <= 0) return false;
        if (handIndex < 0 || handIndex >= p.hand.size()) return false;
        CardDef card = Cards.get(p.hand.get(handIndex));
        LocationDef loc = Locations.get(locationKey);
        if (card == null || loc == null) return false;
        if (!card.icons.contains(loc.icon)) return false;

        boolean occupied = !occupantsOf(loc.key).isEmpty();
        boolean viaEnvoy = occupied && p.leader == Leader.BELLA && p.envoyReady;
        if (occupied && !viaEnvoy) return false;
        if (p.gold < loc.costGold || p.lore < loc.costLore) return false;

        // Commit.
        p.gold -= loc.costGold;
        p.lore -= loc.costLore;
        p.hand.remove(handIndex);
        p.played.add(card.key);
        p.agentsLeft--;
        if (viaEnvoy) p.envoyReady = false;
        occupants.computeIfAbsent(loc.key, k -> new ArrayList<>()).add(p.id);

        // Location + card effects stack.
        p.gold += loc.gainGold + card.playGold;
        p.lore += loc.gainLore + card.playLore;
        p.might += loc.gainMight + card.playMight;
        if (loc.bazaarDiscount) p.bazaarDiscount = true;

        int draws = loc.gainDraw + card.playDraw;
        if (draws > 0) p.draw(draws, rng);

        int vpGain = loc.gainVp + card.playVp;
        if (vpGain > 0) {
            p.vp += vpGain;
            sink.toAll(DeckProto.fx("vp", p.id, null, loc.key, null, vpGain, null));
        }

        int troopsGain = loc.gainTroops + card.playTroops;
        if (troopsGain > 0) {
            Territory target = territory != null ? territory : Territory.HIGHLANDS;
            if (p.leader == Leader.MATT && !p.tacticianUsed) {
                p.tacticianUsed = true;
                troopsGain++;
            }
            p.addTroops(target, troopsGain);
            sink.toAll(DeckProto.fx("deploy", p.id, null, null, target,
                    troopsGain, null));
        }

        sink.toAll(DeckProto.fx("play", p.id, card.key, loc.key, null, 0,
                p.name + " plays " + card.name + " → " + loc.name));

        finishAgentTurn(p);
        return true;
    }

    /** An agent turn is over: pass the turn (out of everything ⇒ auto-reveal). */
    private void finishAgentTurn(DeckPlayer p) {
        if (p.agentsLeft <= 0 && p.hand.isEmpty()) doReveal(p);
        int after = (indexOf(p) + 1) % players.size();
        if (setTurnFrom(after)) pushAll();
    }

    /**
     * Buy a market card on your turn (a free action, any number of times);
     * the card goes to your discard pile and the slot refills.
     */
    public boolean buy(DeckPlayer p, int marketIndex) {
        if (phase != Phase.PLAY || turnPlayer() != p) return false;
        if (marketIndex < 0 || marketIndex >= market.size()) return false;
        CardDef card = Cards.get(market.get(marketIndex));
        if (card == null) return false;
        int price = Math.max(0, card.cost - (p.bazaarDiscount ? 1 : 0));
        if (p.gold < price) return false;

        p.gold -= price;
        p.discard.add(card.key);
        if (marketDeck.isEmpty()) {
            market.remove(marketIndex);
        } else {
            market.set(marketIndex, marketDeck.remove(marketDeck.size() - 1));
        }
        sink.toAll(DeckProto.fx("buy", p.id, card.key, null, null, price,
                p.name + " buys " + card.name));
        pushAll();
        return true;
    }

    /**
     * Reveal your remaining hand: its reveal gold becomes spending money and
     * its reveal might joins the conflict. Your turn continues for market
     * buys until {@link #done}.
     */
    public boolean reveal(DeckPlayer p) {
        if (phase != Phase.PLAY || turnPlayer() != p || p.revealed) return false;
        doReveal(p);
        pushAll();
        return true;
    }

    private void doReveal(DeckPlayer p) {
        if (p.revealed) return;
        int gold = 0;
        int might = 0;
        for (String key : p.hand) {
            CardDef card = Cards.get(key);
            if (card == null) continue;
            gold += card.revealGold;
            might += card.revealMight;
        }
        p.gold += gold;
        p.might += might;
        p.revealed = true;
        sink.toAll(DeckProto.fx("reveal", p.id, null, null, null, might,
                p.name + " reveals (+" + gold + " gold, +" + might + " might)"));
    }

    /** End your turn. Reveals first if you hadn't yet. */
    public boolean done(DeckPlayer p) {
        if (phase != Phase.PLAY || turnPlayer() != p) return false;
        finishTurn(p);
        return true;
    }

    // --- replication helpers ----------------------------------------------------------

    /** (Re)send everything a (re)joining or state-changed player needs. */
    public void sendFullState(int playerId) {
        DeckPlayer p = byId(playerId);
        if (p == null) return;
        sink.toPlayer(playerId, DeckProto.lobby(hostId, players));
        if (phase != Phase.LOBBY) {
            sink.toPlayer(playerId, DeckProto.phase(phase, round));
            sink.toPlayer(playerId, DeckProto.table(this));
            sink.toPlayer(playerId, DeckProto.you(p));
        }
    }

    private void broadcastLobby() {
        sink.toAll(DeckProto.lobby(hostId, players));
    }

    /** Push the public table to everyone and private state to each player. */
    private void pushAll() {
        sink.toAll(DeckProto.table(this));
        for (DeckPlayer p : players) {
            if (!p.bot && p.connected) sink.toPlayer(p.id, DeckProto.you(p));
        }
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }
}
