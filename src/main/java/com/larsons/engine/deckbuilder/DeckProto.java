package com.larsons.engine.deckbuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Council of Six's wire protocol: newline-delimited compact JSON over TCP,
 * the same framing as the world game's {@link com.larsons.engine.net.Protocol}
 * (whose encode/decode helpers it reuses) and the auto-battler's
 * {@link com.larsons.engine.autobattler.AutoProto}, with its own message
 * vocabulary, version, and default port.
 *
 * <pre>
 *   client -> server   {"t":"join","v":1,"name":"Bella"}
 *   server -> client   {"t":"welcome","id":2,"tickRate":30}
 *   server -> all      {"t":"lobby","host":1,"players":[{"id","name","bot","leader"}...]}
 *   client -> server   {"t":"leader","k":"BELLA"}                       (pick a leader)
 *   client -> server   {"t":"start"} | {"t":"addbot"} | {"t":"rmbot"}   (host only)
 *   server -> all      {"t":"phase","p":"PLAY","round":3}
 *   server -> all      {"t":"table", turn/left/locs/market/terr/conflict/players}
 *   server -> client   {"t":"you", gold/lore/might/vp/hand/deck/agents...}  (private)
 *   server -> all      {"t":"clock","left":12}                              (1 Hz)
 *   client -> server   {"t":"play","c":handIndex,"loc":"war_camp","terr":"HIGHLANDS"}
 *   client -> server   {"t":"buy","m":marketIndex} | {"t":"reveal"} | {"t":"done"}
 *   server -> all      {"t":"fx","k":"play|buy|deploy|vp|conflict|turn",...}
 *   server -> all      {"t":"over","winner":"Kris","list":[{name,place,vp}...]}
 *   both               info / error / ping / pong, as in the world protocol
 * </pre>
 */
public final class DeckProto {

    /** Council of Six protocol version; independent of the other protocols'. */
    public static final int VERSION = 1;

    /** Default port for hosted games (world: 7777, auto-battler: 7788). */
    public static final int DEFAULT_PORT = 7799;

    private DeckProto() {}

    public static String type(Map<String, Object> message) {
        return message.get("t") instanceof String s ? s : "";
    }

    // --- handshake -----------------------------------------------------------------

    public static Map<String, Object> join(String name) {
        Map<String, Object> m = msg("join");
        m.put("v", VERSION);
        m.put("name", name);
        return m;
    }

    public static Map<String, Object> welcome(int id, int tickRate) {
        Map<String, Object> m = msg("welcome");
        m.put("id", id);
        m.put("tickRate", tickRate);
        return m;
    }

    public static Map<String, Object> error(String reason) {
        Map<String, Object> m = msg("error");
        m.put("msg", reason);
        return m;
    }

    public static Map<String, Object> info(String text) {
        Map<String, Object> m = msg("info");
        m.put("msg", text);
        return m;
    }

    // --- lobby ---------------------------------------------------------------------

    public static Map<String, Object> lobby(int hostId, List<DeckPlayer> players) {
        Map<String, Object> m = msg("lobby");
        m.put("host", hostId);
        List<Object> list = new ArrayList<>(players.size());
        for (DeckPlayer p : players) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("name", p.name);
            row.put("bot", p.bot);
            if (p.leader != null) row.put("leader", p.leader.name());
            list.add(row);
        }
        m.put("players", list);
        return m;
    }

    public static Map<String, Object> pickLeader(Leader leader) {
        Map<String, Object> m = msg("leader");
        m.put("k", leader.name());
        return m;
    }

    public static Map<String, Object> start() {
        return msg("start");
    }

    public static Map<String, Object> addBot() {
        return msg("addbot");
    }

    public static Map<String, Object> removeBot() {
        return msg("rmbot");
    }

    // --- replicated state ------------------------------------------------------------

    public static Map<String, Object> phase(DeckGame.Phase phase, int round) {
        Map<String, Object> m = msg("phase");
        m.put("p", phase.name());
        m.put("round", round);
        return m;
    }

    public static Map<String, Object> clock(double left) {
        Map<String, Object> m = msg("clock");
        m.put("left", left);
        return m;
    }

    public static Map<String, Object> you(DeckPlayer p) {
        Map<String, Object> m = msg("you");
        m.putAll(p.toYouMap());
        return m;
    }

    /**
     * The whole public table in one message: whose turn it is, location
     * occupancy, the market row, troops per territory, this round's conflict
     * reward, and every player's public standing. Small enough to resend on
     * every change — the deckbuilder is turn-based, not 15 Hz combat.
     */
    public static Map<String, Object> table(DeckGame game) {
        Map<String, Object> m = msg("table");
        m.put("round", game.round());
        m.put("turn", game.turnPlayerId());
        m.put("left", game.turnLeft());

        List<Object> locs = new ArrayList<>();
        for (LocationDef def : Locations.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("k", def.key);
            // A list: Bella's passive lets a second agent share a location.
            List<Integer> occupants = game.occupantsOf(def.key);
            if (!occupants.isEmpty()) row.put("occ", new ArrayList<Object>(occupants));
            locs.add(row);
        }
        m.put("locs", locs);

        m.put("market", new ArrayList<Object>(game.market()));

        Map<String, Object> terr = new LinkedHashMap<>();
        for (Territory t : Territory.values()) {
            Map<String, Object> counts = new LinkedHashMap<>();
            for (DeckPlayer p : game.players()) {
                int n = p.troopsIn(t);
                if (n > 0) counts.put(Integer.toString(p.id), n);
            }
            terr.put(t.name(), counts);
        }
        m.put("terr", terr);

        DeckGame.ConflictReward reward = game.conflictReward();
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("fv", reward.firstVp());
        conflict.put("fg", reward.firstGold());
        conflict.put("sv", reward.secondVp());
        conflict.put("sg", reward.secondGold());
        m.put("conflict", conflict);

        List<Object> rows = new ArrayList<>();
        for (DeckPlayer p : game.players()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("name", p.name);
            row.put("bot", p.bot);
            if (p.leader != null) row.put("leader", p.leader.name());
            row.put("vp", p.vp);
            row.put("gold", p.gold);
            row.put("lore", p.lore);
            row.put("might", p.might);
            row.put("troops", p.troopsTotal());
            row.put("agents", p.agentsLeft);
            row.put("rev", p.revealed);
            row.put("conn", p.connected);
            rows.add(row);
        }
        m.put("players", rows);
        return m;
    }

    // --- player actions -----------------------------------------------------------

    public static Map<String, Object> play(int handIndex, String locationKey,
                                           Territory territory) {
        Map<String, Object> m = msg("play");
        m.put("c", handIndex);
        m.put("loc", locationKey);
        if (territory != null) m.put("terr", territory.name());
        return m;
    }

    public static Map<String, Object> buy(int marketIndex) {
        Map<String, Object> m = msg("buy");
        m.put("m", marketIndex);
        return m;
    }

    public static Map<String, Object> reveal() {
        return msg("reveal");
    }

    public static Map<String, Object> done() {
        return msg("done");
    }

    // --- events --------------------------------------------------------------------

    /**
     * A presentation event: something happened that clients turn into
     * particles, floaters, and sounds. {@code kind} is one of
     * {@code play | buy | deploy | vp | conflict | turn}; the optional fields
     * depend on the kind.
     */
    public static Map<String, Object> fx(String kind, int playerId, String card,
                                         String location, Territory territory,
                                         int amount, String text) {
        Map<String, Object> m = msg("fx");
        m.put("k", kind);
        m.put("p", playerId);
        if (card != null) m.put("card", card);
        if (location != null) m.put("loc", location);
        if (territory != null) m.put("terr", territory.name());
        if (amount != 0) m.put("n", amount);
        if (text != null) m.put("msg", text);
        return m;
    }

    public static Map<String, Object> gameOver(String winner,
                                               List<Map<String, Object>> standings) {
        Map<String, Object> m = msg("over");
        m.put("winner", winner);
        m.put("list", new ArrayList<Object>(standings));
        return m;
    }

    public static Map<String, Object> ping(long payload) {
        Map<String, Object> m = msg("ping");
        m.put("p", payload);
        return m;
    }

    public static Map<String, Object> pong(long payload) {
        Map<String, Object> m = msg("pong");
        m.put("p", payload);
        return m;
    }

    private static Map<String, Object> msg(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);
        return m;
    }
}
