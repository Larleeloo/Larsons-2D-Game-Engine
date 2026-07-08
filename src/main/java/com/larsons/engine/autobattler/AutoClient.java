package com.larsons.engine.autobattler;

import com.larsons.engine.net.Protocol;
import com.larsons.engine.util.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client side of the online auto-battler: dial a host by {@code ip:port},
 * join the lobby, then read the server's replicated state — your private
 * roster/economy, the public standings, phase changes, and ~15 Hz combat
 * snapshots (kept in pairs for interpolation, like the world game's
 * {@link com.larsons.engine.net.GameClient}). All action senders are
 * fire-and-forget; the server answers with fresh {@code you} state.
 */
public final class AutoClient implements Closeable {

    private static final long PING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int SO_TIMEOUT_MS = 15_000;
    private static final int MAX_TOASTS = 6;

    // --- typed views of replicated state ----------------------------------------

    public record LobbyPlayer(int id, String name, boolean bot) {}

    public record LobbyState(int hostId, List<LobbyPlayer> players) {}

    public record PhaseState(AutoGame.Phase phase, int round, boolean pve,
                             double left, long atNanos) {
        /** Seconds left, extrapolated from when the server last told us. */
        public double leftNow() {
            return Math.max(0, left - (System.nanoTime() - atNanos) / 1e9);
        }
    }

    /** Your private state: economy plus everything you own. */
    public record You(int gold, int hp, int level, int xp, int xpNeed, int boardCap,
                      int streak, boolean alive, List<UnitInstance> bench,
                      List<UnitInstance> board, List<String> shop, List<String> items) {}

    public record Standing(int id, String name, int hp, int level, boolean alive,
                           int streak, boolean bot, int place) {}

    public record MatchInfo(String opponent, boolean ghost, boolean pve, boolean home) {}

    public record CombatUnit(int id, String key, int star, int team, double x, double y,
                             double hp, double maxHp, double mana, double manaMax,
                             boolean dead) {}

    public record CombatFrame(List<CombatUnit> units, long atNanos) {}

    public record CombatEvent(String kind, double x, double y, double amount, int team) {}

    public record RoundResult(boolean won, boolean draw, int damage, String opponent) {}

    public record GameOver(String winner, List<String> standings) {}

    // --- connection ---------------------------------------------------------------

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(128);

    private final int localId;
    private volatile boolean connected = true;
    private volatile String disconnectReason;
    private volatile int pingMillis = -1;

    private volatile LobbyState lobby = new LobbyState(-1, List.of());
    private volatile PhaseState phase;
    private volatile You you;
    private volatile List<Standing> standings = List.of();
    private volatile MatchInfo match;
    private volatile CombatFrame combatLatest;
    private volatile CombatFrame combatPrevious;
    private volatile GameOver gameOver;

    private final ConcurrentLinkedDeque<CombatEvent> combatEvents = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RoundResult> results = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<String> toasts = new ConcurrentLinkedDeque<>();

    /**
     * Connect and complete the join handshake, or throw with a reason
     * (unreachable host, full lobby, game already started, version mismatch).
     */
    public static AutoClient connect(String host, int port, String playerName, int timeoutMs)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMs);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            out.write(Protocol.encode(AutoProto.join(playerName)));
            out.write('\n');
            out.flush();

            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("Server closed the connection");
                Map<String, Object> msg = Protocol.decode(line);
                if (msg == null) continue;
                switch (AutoProto.type(msg)) {
                    case "welcome" -> {
                        socket.setSoTimeout(SO_TIMEOUT_MS);
                        return new AutoClient(socket, in, out, msg);
                    }
                    case "error" -> throw new IOException(
                            msg.get("msg") instanceof String s ? s : "Join rejected");
                    default -> { /* not part of the handshake; keep reading */ }
                }
            }
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // connect failed; nothing else to release
            }
            throw e;
        }
    }

    private AutoClient(Socket socket, BufferedReader in, BufferedWriter out,
                       Map<String, Object> welcome) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.localId = intOf(welcome.get("id"));
        daemon("auto-client-read", this::readLoop).start();
        daemon("auto-client-write", this::writeLoop).start();
    }

    // --- accessors -------------------------------------------------------------------

    public int localId() { return localId; }

    public boolean isConnected() { return connected; }

    public String disconnectReason() { return disconnectReason; }

    public int pingMillis() { return pingMillis; }

    public LobbyState lobby() { return lobby; }

    public boolean isHost() { return lobby.hostId() == localId; }

    /** Null until the match starts. */
    public PhaseState phase() { return phase; }

    /** Your private state; null until the first push. */
    public You you() { return you; }

    public List<Standing> standings() { return standings; }

    /** Who you're fighting this round; null outside combat. */
    public MatchInfo match() { return match; }

    public CombatFrame combatLatest() { return combatLatest; }

    public CombatFrame combatPrevious() { return combatPrevious; }

    public GameOver gameOver() { return gameOver; }

    /** Drain combat events (damage numbers, deaths) since the last call. */
    public List<CombatEvent> pollCombatEvents() {
        List<CombatEvent> out = new ArrayList<>();
        CombatEvent e;
        while ((e = combatEvents.pollFirst()) != null) out.add(e);
        return out;
    }

    /** Drain round results since the last call (win/loss banners). */
    public List<RoundResult> pollResults() {
        List<RoundResult> out = new ArrayList<>();
        RoundResult r;
        while ((r = results.pollFirst()) != null) out.add(r);
        return out;
    }

    /** Drain server info toasts ("X joined", "Not enough gold"). */
    public List<String> pollToasts() {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = toasts.pollFirst()) != null) out.add(s);
        return out;
    }

    // --- action senders -----------------------------------------------------------------

    public void sendStart() { send(AutoProto.start()); }

    public void sendAddBot() { send(AutoProto.addBot()); }

    public void sendRemoveBot() { send(AutoProto.removeBot()); }

    public void sendBuy(int shopSlot) { send(AutoProto.buy(shopSlot)); }

    public void sendSell(int unitId) { send(AutoProto.sell(unitId)); }

    public void sendReroll() { send(AutoProto.reroll()); }

    public void sendBuyXp() { send(AutoProto.buyXp()); }

    public void sendMoveToBench(int unitId, int slot) { send(AutoProto.moveToBench(unitId, slot)); }

    public void sendMoveToBoard(int unitId, int col, int row) {
        send(AutoProto.moveToBoard(unitId, col, row));
    }

    public void sendEquip(int itemIndex, int unitId) { send(AutoProto.equip(itemIndex, unitId)); }

    private void send(Map<String, Object> msg) {
        if (connected) outbox.offer(Protocol.encode(msg));
    }

    @Override
    public void close() {
        markDisconnected("Left the game");
    }

    // --- worker threads --------------------------------------------------------------------

    private void readLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                Map<String, Object> msg = Protocol.decode(line);
                if (msg == null) continue;
                handle(msg);
            }
            markDisconnected("Connection closed by server");
        } catch (IOException e) {
            markDisconnected("Connection lost: " + e.getMessage());
        }
    }

    private void handle(Map<String, Object> msg) {
        switch (AutoProto.type(msg)) {
            case "lobby" -> {
                List<LobbyPlayer> players = new ArrayList<>();
                if (msg.get("players") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Map<String, Object> row = Json.asObject(pm);
                            players.add(new LobbyPlayer(intOf(row.get("id")),
                                    str(row.get("name")),
                                    Boolean.TRUE.equals(row.get("bot"))));
                        }
                    }
                }
                lobby = new LobbyState(intOf(msg.get("host")), players);
            }
            case "phase" -> {
                AutoGame.Phase p;
                try {
                    p = AutoGame.Phase.valueOf(str(msg.get("p")));
                } catch (IllegalArgumentException e) {
                    return;
                }
                phase = new PhaseState(p, intOf(msg.get("round")),
                        Boolean.TRUE.equals(msg.get("pve")),
                        dblOf(msg.get("left")), System.nanoTime());
                if (p != AutoGame.Phase.FIGHT) {
                    match = null;
                    combatLatest = null;
                    combatPrevious = null;
                }
            }
            case "clock" -> {
                PhaseState ps = phase;
                if (ps != null) {
                    phase = new PhaseState(ps.phase(), ps.round(), ps.pve(),
                            dblOf(msg.get("left")), System.nanoTime());
                }
            }
            case "you" -> you = parseYou(msg);
            case "players" -> {
                List<Standing> rows = new ArrayList<>();
                if (msg.get("list") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Map<String, Object> row = Json.asObject(pm);
                            rows.add(new Standing(intOf(row.get("id")), str(row.get("name")),
                                    intOf(row.get("hp")), intOf(row.get("lvl")),
                                    Boolean.TRUE.equals(row.get("alive")),
                                    intOf(row.get("streak")),
                                    Boolean.TRUE.equals(row.get("bot")),
                                    row.containsKey("place") ? intOf(row.get("place")) : 0));
                        }
                    }
                }
                standings = rows;
            }
            case "match" -> match = new MatchInfo(str(msg.get("opp")),
                    Boolean.TRUE.equals(msg.get("ghost")),
                    Boolean.TRUE.equals(msg.get("pve")),
                    Boolean.TRUE.equals(msg.get("home")));
            case "combat" -> {
                List<CombatUnit> units = new ArrayList<>();
                if (msg.get("u") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> um) {
                            Map<String, Object> u = Json.asObject(um);
                            units.add(new CombatUnit(intOf(u.get("id")), str(u.get("k")),
                                    intOf(u.get("s")), intOf(u.get("tm")),
                                    dblOf(u.get("x")), dblOf(u.get("y")),
                                    dblOf(u.get("hp")), dblOf(u.get("mh")),
                                    dblOf(u.get("mn")), dblOf(u.get("mx")),
                                    Boolean.TRUE.equals(u.get("dd"))));
                        }
                    }
                }
                combatPrevious = combatLatest;
                combatLatest = new CombatFrame(units, System.nanoTime());
                if (msg.get("e") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> em) {
                            Map<String, Object> e = Json.asObject(em);
                            combatEvents.addLast(new CombatEvent(str(e.get("k")),
                                    dblOf(e.get("x")), dblOf(e.get("y")),
                                    dblOf(e.get("a")), intOf(e.get("tm"))));
                        }
                    }
                    while (combatEvents.size() > 256) combatEvents.pollFirst();
                }
            }
            case "result" -> results.addLast(new RoundResult(
                    Boolean.TRUE.equals(msg.get("w")), Boolean.TRUE.equals(msg.get("d")),
                    intOf(msg.get("dmg")), str(msg.get("opp"))));
            case "over" -> {
                List<String> rows = new ArrayList<>();
                if (msg.get("list") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Map<String, Object> row = Json.asObject(pm);
                            rows.add("#" + intOf(row.get("place")) + "  " + str(row.get("name")));
                        }
                    }
                }
                gameOver = new GameOver(str(msg.get("winner")), rows);
            }
            case "info" -> {
                if (msg.get("msg") instanceof String s) {
                    toasts.addLast(s);
                    while (toasts.size() > MAX_TOASTS) toasts.pollFirst();
                }
            }
            case "pong" -> {
                long sent = msg.get("p") instanceof Number n ? n.longValue() : 0;
                pingMillis = (int) Math.max(0, nowMillis() - sent);
            }
            case "error" -> markDisconnected(
                    msg.get("msg") instanceof String s ? s : "Server error");
            default -> { /* unknown message types are ignored */ }
        }
    }

    private static You parseYou(Map<String, Object> msg) {
        List<UnitInstance> bench = parseUnits(msg.get("bench"));
        List<UnitInstance> board = parseUnits(msg.get("board"));
        List<String> shop = new ArrayList<>();
        if (msg.get("shop") instanceof List<?> list) {
            for (Object o : list) shop.add(o instanceof String s ? s : null);
        }
        List<String> items = new ArrayList<>();
        if (msg.get("items") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) items.add(s);
            }
        }
        return new You(intOf(msg.get("gold")), intOf(msg.get("hp")), intOf(msg.get("lvl")),
                intOf(msg.get("xp")), intOf(msg.get("need")), intOf(msg.get("cap")),
                intOf(msg.get("streak")), Boolean.TRUE.equals(msg.get("alive")),
                bench, board, shop, items);
    }

    private static List<UnitInstance> parseUnits(Object o) {
        List<UnitInstance> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> um) out.add(UnitInstance.fromMap(Json.asObject(um)));
            }
        }
        return out;
    }

    private void writeLoop() {
        long lastPing = System.nanoTime() - PING_INTERVAL_NANOS; // ping immediately
        try {
            while (connected) {
                long now = System.nanoTime();
                if (now - lastPing >= PING_INTERVAL_NANOS) {
                    lastPing = now;
                    out.write(Protocol.encode(AutoProto.ping(nowMillis())));
                    out.write('\n');
                    out.flush();
                }
                String msg = outbox.poll(500, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                out.write(msg);
                out.write('\n');
                while ((msg = outbox.poll()) != null) {
                    out.write(msg);
                    out.write('\n');
                }
                out.flush();
            }
        } catch (IOException e) {
            markDisconnected("Connection lost: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void markDisconnected(String reason) {
        if (connected) {
            connected = false;
            disconnectReason = reason;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closing
        }
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dblOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
