package com.larsons.engine.autobattler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The auto-battler's wire protocol: newline-delimited compact JSON over TCP,
 * exactly like the engine's world-game {@link com.larsons.engine.net.Protocol}
 * (whose encode/decode/framing helpers it reuses) but with its own message
 * vocabulary and version. Builders return plain maps; the socket layer turns
 * them into lines.
 *
 * <pre>
 *   client -> server   {"t":"join","v":1,"name":"Larson"}
 *   server -> client   {"t":"welcome","id":3,"tickRate":30}
 *   server -> all      {"t":"lobby","host":1,"players":[{"id":1,"name":"L","bot":false}...]}
 *   client -> server   {"t":"start"} | {"t":"addbot"} | {"t":"rmbot"}          (host only)
 *   server -> all      {"t":"phase","p":"PLAN","round":4,"pve":false,"left":30}
 *   server -> all      {"t":"clock","left":12}                                  (1 Hz)
 *   server -> client   {"t":"you", gold/hp/lvl/xp/bench/board/shop/items...}    (private)
 *   server -> all      {"t":"players",[{id,name,hp,lvl,alive,streak,bot}...]}   (standings)
 *   client -> server   {"t":"buy","i":slot} {"t":"sell","u":unitId} {"t":"reroll"} {"t":"xp"}
 *   client -> server   {"t":"move","u":id,"b":bench} | {"t":"move","u":id,"c":col,"r":row}
 *   client -> server   {"t":"equip","i":itemIndex,"u":unitId}
 *   client -> server   {"t":"view","p":playerId}                       (scout a board)
 *   server -> client   {"t":"board","p":id,"name",stats...,"board":[units],"bench":[units]}
 *   server -> client   {"t":"match","opp":"Kara","ghost":false,"pve":false,"home":true}
 *   server -> client   {"t":"combat","u":[units...],"e":[events...]}            (~15 Hz)
 *   server -> client   {"t":"result","w":true,"d":false,"dmg":0,"opp":"Kara"}
 *   server -> all      {"t":"over","winner":"Kara","list":[{name,place}...]}
 *   both               info / error / ping / pong, as in the world protocol
 * </pre>
 */
public final class AutoProto {

    /**
     * Auto-battler protocol version; independent of the world protocol's.
     * v2 added board scouting ({@code view}/{@code board}) and the richer
     * combat snapshots (animation states, damage tallies, event sources).
     */
    public static final int VERSION = 2;

    /** Default port for hosted auto-battler games (world servers use 7777). */
    public static final int DEFAULT_PORT = 7788;

    private AutoProto() {}

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

    public static Map<String, Object> lobby(int hostId, List<AutoPlayer> players) {
        Map<String, Object> m = msg("lobby");
        m.put("host", hostId);
        List<Object> list = new ArrayList<>(players.size());
        for (AutoPlayer p : players) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("name", p.name);
            row.put("bot", p.bot);
            list.add(row);
        }
        m.put("players", list);
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

    // --- game state ------------------------------------------------------------------

    public static Map<String, Object> phase(AutoGame.Phase phase, int round,
                                            boolean pve, double left) {
        Map<String, Object> m = msg("phase");
        m.put("p", phase.name());
        m.put("round", round);
        m.put("pve", pve);
        m.put("left", left);
        return m;
    }

    public static Map<String, Object> clock(double left) {
        Map<String, Object> m = msg("clock");
        m.put("left", left);
        return m;
    }

    public static Map<String, Object> you(AutoPlayer p) {
        Map<String, Object> m = msg("you");
        m.putAll(p.toYouMap());
        return m;
    }

    public static Map<String, Object> players(List<Map<String, Object>> rows) {
        Map<String, Object> m = msg("players");
        m.put("list", new ArrayList<Object>(rows));
        return m;
    }

    public static Map<String, Object> match(String oppName, boolean ghost,
                                            boolean pve, boolean home) {
        Map<String, Object> m = msg("match");
        m.put("opp", oppName);
        m.put("ghost", ghost);
        m.put("pve", pve);
        m.put("home", home);
        return m;
    }

    public static Map<String, Object> combat(List<Map<String, Object>> units,
                                             List<BattleSim.Event> events) {
        Map<String, Object> m = msg("combat");
        m.put("u", new ArrayList<Object>(units));
        if (!events.isEmpty()) {
            List<Object> evs = new ArrayList<>(events.size());
            for (BattleSim.Event e : events) evs.add(e.toMap());
            m.put("e", evs);
        }
        return m;
    }

    public static Map<String, Object> result(boolean won, boolean draw,
                                             int damage, String oppName) {
        Map<String, Object> m = msg("result");
        m.put("w", won);
        m.put("d", draw);
        m.put("dmg", damage);
        m.put("opp", oppName);
        return m;
    }

    public static Map<String, Object> gameOver(String winner,
                                               List<Map<String, Object>> standings) {
        Map<String, Object> m = msg("over");
        m.put("winner", winner);
        m.put("list", new ArrayList<Object>(standings));
        return m;
    }

    // --- player actions -----------------------------------------------------------

    public static Map<String, Object> buy(int shopSlot) {
        Map<String, Object> m = msg("buy");
        m.put("i", shopSlot);
        return m;
    }

    public static Map<String, Object> sell(int unitId) {
        Map<String, Object> m = msg("sell");
        m.put("u", unitId);
        return m;
    }

    public static Map<String, Object> reroll() {
        return msg("reroll");
    }

    public static Map<String, Object> buyXp() {
        return msg("xp");
    }

    public static Map<String, Object> moveToBench(int unitId, int benchSlot) {
        Map<String, Object> m = msg("move");
        m.put("u", unitId);
        m.put("b", benchSlot);
        return m;
    }

    public static Map<String, Object> moveToBoard(int unitId, int col, int row) {
        Map<String, Object> m = msg("move");
        m.put("u", unitId);
        m.put("c", col);
        m.put("r", row);
        return m;
    }

    public static Map<String, Object> equip(int itemIndex, int unitId) {
        Map<String, Object> m = msg("equip");
        m.put("i", itemIndex);
        m.put("u", unitId);
        return m;
    }

    /** Ask to scout another player's board (any player, any phase after lobby). */
    public static Map<String, Object> view(int playerId) {
        Map<String, Object> m = msg("view");
        m.put("p", playerId);
        return m;
    }

    /**
     * A scouted board: the target's public stats plus their current board and
     * bench. Everyone can request anyone's, so no private info leaks unevenly.
     */
    public static Map<String, Object> boardView(AutoPlayer p) {
        Map<String, Object> m = msg("board");
        m.put("p", p.id);
        m.put("name", p.name);
        m.put("bot", p.bot);
        m.put("hp", Math.max(0, p.hp));
        m.put("lvl", p.level);
        m.put("xp", p.xp);
        m.put("need", p.xpToNext());
        m.put("gold", p.gold);
        m.put("streak", p.streak);
        m.put("alive", p.alive);
        m.put("place", p.place);
        m.put("cap", p.boardCap());
        List<Object> board = new ArrayList<>();
        for (UnitInstance u : p.boardUnits()) board.add(u.toMap());
        m.put("board", board);
        List<Object> bench = new ArrayList<>();
        for (UnitInstance u : p.benchUnits()) bench.add(u.toMap());
        m.put("bench", bench);
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
