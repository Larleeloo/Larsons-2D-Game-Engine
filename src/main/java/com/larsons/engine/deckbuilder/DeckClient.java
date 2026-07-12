package com.larsons.engine.deckbuilder;

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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client side of online Council of Six: dial a host by {@code ip:port}, join
 * the table, then read the server's replicated state — your private hand and
 * economy, the public table (turn, locations, market, territories, standings),
 * and the fx event feed the scene turns into particles. All action senders
 * are fire-and-forget; the server answers with fresh state. Mirrors
 * {@link com.larsons.engine.autobattler.AutoClient}.
 */
public final class DeckClient implements Closeable {

    private static final long PING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int SO_TIMEOUT_MS = 15_000;
    private static final int MAX_TOASTS = 6;
    private static final int MAX_EVENTS = 128;

    // --- typed views of replicated state ----------------------------------------

    public record LobbyPlayer(int id, String name, boolean bot, Leader leader) {}

    public record LobbyState(int hostId, List<LobbyPlayer> players) {}

    public record PhaseState(DeckGame.Phase phase, int round) {}

    /** Your private state: resources, hand, piles, and turn flags. */
    public record You(int gold, int lore, int might, int vp, List<String> hand,
                      int deckCount, int discardCount, int agentsLeft,
                      boolean revealed, boolean envoyReady, boolean bazaarDiscount,
                      Leader leader) {}

    /** One location's occupancy on the public table. */
    public record LocationState(String key, List<Integer> occupants) {}

    /** One player's public standing row. */
    public record Standing(int id, String name, boolean bot, Leader leader,
                           int vp, int gold, int lore, int might, int troops,
                           int agentsLeft, boolean revealed, boolean connected) {}

    /** The whole public table, replicated on every change. */
    public record Table(int round, int turnPlayerId, double turnLeft, long atNanos,
                        List<LocationState> locations, List<String> market,
                        Map<Territory, Map<Integer, Integer>> territories,
                        DeckGame.ConflictReward conflict, List<Standing> standings) {
        /** Seconds left on the turn clock, extrapolated locally. */
        public double turnLeftNow() {
            return Math.max(0, turnLeft - (System.nanoTime() - atNanos) / 1e9);
        }
    }

    /** A presentation event (particles/sounds); fields depend on {@code kind}. */
    public record FxEvent(String kind, int playerId, String card, String location,
                          Territory territory, int amount, String text) {}

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
    private volatile Table table;
    private volatile GameOver gameOver;

    private final ConcurrentLinkedDeque<FxEvent> events = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<String> toasts = new ConcurrentLinkedDeque<>();

    /**
     * Connect and complete the join handshake, or throw with a reason
     * (unreachable host, full table, game already started, version mismatch).
     */
    public static DeckClient connect(String host, int port, String playerName, int timeoutMs)
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

            out.write(Protocol.encode(DeckProto.join(playerName)));
            out.write('\n');
            out.flush();

            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("Server closed the connection");
                Map<String, Object> msg = Protocol.decode(line);
                if (msg == null) continue;
                switch (DeckProto.type(msg)) {
                    case "welcome" -> {
                        socket.setSoTimeout(SO_TIMEOUT_MS);
                        return new DeckClient(socket, in, out, msg);
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

    private DeckClient(Socket socket, BufferedReader in, BufferedWriter out,
                       Map<String, Object> welcome) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.localId = intOf(welcome.get("id"));
        daemon("deck-client-read", this::readLoop).start();
        daemon("deck-client-write", this::writeLoop).start();
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

    /** The public table; null until the first push. */
    public Table table() { return table; }

    public GameOver gameOver() { return gameOver; }

    /** True when it's this client's turn to act. */
    public boolean myTurn() {
        Table t = table;
        return t != null && t.turnPlayerId() == localId;
    }

    /** Drain fx events (plays, buys, deploys, conflicts) since the last call. */
    public List<FxEvent> pollEvents() {
        List<FxEvent> out = new ArrayList<>();
        FxEvent e;
        while ((e = events.pollFirst()) != null) out.add(e);
        return out;
    }

    /** Drain server info toasts ("X joined", "You can't afford that"). */
    public List<String> pollToasts() {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = toasts.pollFirst()) != null) out.add(s);
        return out;
    }

    // --- action senders -----------------------------------------------------------------

    public void sendPickLeader(Leader leader) { send(DeckProto.pickLeader(leader)); }

    public void sendStart() { send(DeckProto.start()); }

    public void sendAddBot() { send(DeckProto.addBot()); }

    public void sendRemoveBot() { send(DeckProto.removeBot()); }

    /** Play a hand card to a location (territory only matters for troop plays). */
    public void sendPlay(int handIndex, String locationKey, Territory territory) {
        send(DeckProto.play(handIndex, locationKey, territory));
    }

    public void sendBuy(int marketIndex) { send(DeckProto.buy(marketIndex)); }

    public void sendReveal() { send(DeckProto.reveal()); }

    public void sendDone() { send(DeckProto.done()); }

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
        switch (DeckProto.type(msg)) {
            case "lobby" -> {
                List<LobbyPlayer> players = new ArrayList<>();
                if (msg.get("players") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Map<String, Object> row = Json.asObject(pm);
                            players.add(new LobbyPlayer(intOf(row.get("id")),
                                    str(row.get("name")),
                                    Boolean.TRUE.equals(row.get("bot")),
                                    Leader.fromCode(str(row.get("leader")))));
                        }
                    }
                }
                lobby = new LobbyState(intOf(msg.get("host")), players);
            }
            case "phase" -> {
                DeckGame.Phase p;
                try {
                    p = DeckGame.Phase.valueOf(str(msg.get("p")));
                } catch (IllegalArgumentException e) {
                    return;
                }
                phase = new PhaseState(p, intOf(msg.get("round")));
            }
            case "you" -> you = parseYou(msg);
            case "table" -> table = parseTable(msg);
            case "clock" -> {
                Table t = table;
                if (t != null) {
                    table = new Table(t.round(), t.turnPlayerId(),
                            dblOf(msg.get("left")), System.nanoTime(),
                            t.locations(), t.market(), t.territories(),
                            t.conflict(), t.standings());
                }
            }
            case "fx" -> {
                events.addLast(new FxEvent(str(msg.get("k")), intOf(msg.get("p")),
                        msg.get("card") instanceof String s ? s : null,
                        msg.get("loc") instanceof String s ? s : null,
                        Territory.fromCode(str(msg.get("terr"))),
                        intOf(msg.get("n")),
                        msg.get("msg") instanceof String s ? s : null));
                while (events.size() > MAX_EVENTS) events.pollFirst();
            }
            case "over" -> {
                List<String> rows = new ArrayList<>();
                if (msg.get("list") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Map<String, Object> row = Json.asObject(pm);
                            rows.add("#" + intOf(row.get("place")) + "  "
                                    + str(row.get("name")) + "  —  "
                                    + intOf(row.get("vp")) + " VP");
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
        List<String> hand = new ArrayList<>();
        if (msg.get("hand") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) hand.add(s);
            }
        }
        return new You(intOf(msg.get("gold")), intOf(msg.get("lore")),
                intOf(msg.get("might")), intOf(msg.get("vp")), hand,
                intOf(msg.get("deck")), intOf(msg.get("disc")),
                intOf(msg.get("agents")), Boolean.TRUE.equals(msg.get("rev")),
                Boolean.TRUE.equals(msg.get("envoy")),
                Boolean.TRUE.equals(msg.get("disco")),
                Leader.fromCode(str(msg.get("leader"))));
    }

    private static Table parseTable(Map<String, Object> msg) {
        List<LocationState> locations = new ArrayList<>();
        if (msg.get("locs") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> lm) {
                    Map<String, Object> row = Json.asObject(lm);
                    List<Integer> occ = new ArrayList<>();
                    if (row.get("occ") instanceof List<?> ol) {
                        for (Object oo : ol) {
                            if (oo instanceof Number n) occ.add(n.intValue());
                        }
                    }
                    locations.add(new LocationState(str(row.get("k")), occ));
                }
            }
        }

        List<String> market = new ArrayList<>();
        if (msg.get("market") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) market.add(s);
            }
        }

        Map<Territory, Map<Integer, Integer>> territories = new EnumMap<>(Territory.class);
        if (msg.get("terr") instanceof Map<?, ?> tm) {
            for (Map.Entry<String, Object> e : Json.asObject(tm).entrySet()) {
                Territory t = Territory.fromCode(e.getKey());
                if (t == null || !(e.getValue() instanceof Map<?, ?> cm)) continue;
                Map<Integer, Integer> counts = new LinkedHashMap<>();
                for (Map.Entry<String, Object> ce : Json.asObject(cm).entrySet()) {
                    try {
                        counts.put(Integer.parseInt(ce.getKey()), intOf(ce.getValue()));
                    } catch (NumberFormatException ignored) {
                        // malformed key; skip
                    }
                }
                territories.put(t, counts);
            }
        }

        DeckGame.ConflictReward conflict = new DeckGame.ConflictReward(0, 0, 0, 0);
        if (msg.get("conflict") instanceof Map<?, ?> cm) {
            Map<String, Object> c = Json.asObject(cm);
            conflict = new DeckGame.ConflictReward(intOf(c.get("fv")), intOf(c.get("fg")),
                    intOf(c.get("sv")), intOf(c.get("sg")));
        }

        List<Standing> standings = new ArrayList<>();
        if (msg.get("players") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> pm) {
                    Map<String, Object> row = Json.asObject(pm);
                    standings.add(new Standing(intOf(row.get("id")), str(row.get("name")),
                            Boolean.TRUE.equals(row.get("bot")),
                            Leader.fromCode(str(row.get("leader"))),
                            intOf(row.get("vp")), intOf(row.get("gold")),
                            intOf(row.get("lore")), intOf(row.get("might")),
                            intOf(row.get("troops")), intOf(row.get("agents")),
                            Boolean.TRUE.equals(row.get("rev")),
                            Boolean.TRUE.equals(row.get("conn"))));
                }
            }
        }

        return new Table(intOf(msg.get("round")), intOf(msg.get("turn")),
                dblOf(msg.get("left")), System.nanoTime(),
                locations, market, territories, conflict, standings);
    }

    private void writeLoop() {
        long lastPing = System.nanoTime() - PING_INTERVAL_NANOS; // ping immediately
        try {
            while (connected) {
                long now = System.nanoTime();
                if (now - lastPing >= PING_INTERVAL_NANOS) {
                    lastPing = now;
                    out.write(Protocol.encode(DeckProto.ping(nowMillis())));
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
