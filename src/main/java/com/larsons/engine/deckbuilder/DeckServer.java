package com.larsons.engine.deckbuilder;

import com.larsons.engine.net.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The authoritative Council of Six server, hosted exactly like the engine's
 * world server and the auto-battler's: bind a port, players connect to
 * {@code ip:port} — 2 to 6 of them, plus host-added bots. All game state
 * lives in a {@link DeckGame} owned by the tick thread; connections only
 * decode requests onto a queue and write queued replies, so a slow client
 * can never stall the turn clock.
 */
public final class DeckServer implements DeckGame.Sink {

    /** Game logic tick rate (Hz); plenty for a turn-based table. */
    public static final int TICK_RATE = 30;

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final int OUTBOX_CAPACITY = 512;

    private final DeckGame game;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread tickThread;
    private volatile boolean running;

    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Connection> pendingJoins = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ClientRequest> pendingRequests = new ConcurrentLinkedQueue<>();

    private record ClientRequest(Connection conn, Map<String, Object> msg) {}

    public DeckServer(DeckGame.Config config) {
        this.game = new DeckGame(config, this);
    }

    /** The game state machine (owned by the tick thread once started). */
    public DeckGame game() {
        return game;
    }

    /** Bind and start serving. Port {@code 0} picks a free port. */
    public synchronized void start(int port) throws IOException {
        if (running) throw new IllegalStateException("Server already running");
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = daemon("deck-accept", this::acceptLoop);
        tickThread = daemon("deck-tick", this::tickLoop);
        acceptThread.start();
        tickThread.start();
        log("Hosting Council of Six on port " + getPort());
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    public int playerCount() {
        int n = 0;
        for (Connection c : connections) if (c.playerId > 0 && !c.closed) n++;
        return n;
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        closeQuietly(serverSocket);
        for (Connection c : connections) c.markClosed();
        Connection pending;
        while ((pending = pendingJoins.poll()) != null) pending.markClosed();
        joinQuietly(acceptThread);
        joinQuietly(tickThread);
        log("Council of Six server stopped");
    }

    // --- Sink: routing game output to sockets --------------------------------------

    @Override
    public void toPlayer(int playerId, Map<String, Object> message) {
        for (Connection c : connections) {
            if (c.playerId == playerId && !c.closed) {
                c.send(Protocol.encode(message));
                return;
            }
        }
    }

    @Override
    public void toAll(Map<String, Object> message) {
        String line = Protocol.encode(message);
        for (Connection c : connections) {
            if (c.playerId > 0 && !c.closed) c.send(line);
        }
    }

    // --- threads --------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Connection conn = new Connection(socket);
                connections.add(conn);
                conn.startThreads();
            } catch (IOException e) {
                if (running) log("Accept failed: " + e.getMessage());
            }
        }
    }

    private void tickLoop() {
        final double dt = 1.0 / TICK_RATE;
        final long nsPerTick = 1_000_000_000L / TICK_RATE;
        long next = System.nanoTime();

        while (running) {
            processJoins();
            processDisconnects();
            processRequests();
            game.tick(dt);

            next += nsPerTick;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                next = System.nanoTime();
            }
        }
    }

    private void processJoins() {
        Connection conn;
        while ((conn = pendingJoins.poll()) != null) {
            if (conn.closed) continue;
            DeckPlayer p = game.addPlayer(conn.requestedName, false);
            if (p == null) {
                String reason = game.phase() != DeckGame.Phase.LOBBY
                        ? "Game already started"
                        : "Game is full (" + DeckGame.MAX_PLAYERS + " players max)";
                conn.sendNow(Protocol.encode(DeckProto.error(reason)));
                conn.markClosed();
                continue;
            }
            conn.playerId = p.id;
            conn.send(Protocol.encode(DeckProto.welcome(p.id, TICK_RATE)));
            game.sendFullState(p.id);
            toAll(DeckProto.info(p.name + " joined the table"));
            log(p.name + " joined from " + conn.socket.getRemoteSocketAddress()
                    + " (" + playerCount() + " online)");
        }
    }

    private void processDisconnects() {
        long now = System.nanoTime();
        for (Connection c : connections) {
            if (!c.closed && now - c.lastHeardNanos > TIMEOUT_NANOS) {
                log("Connection " + c.playerId + " timed out");
                c.markClosed();
            }
            if (c.closed) {
                connections.remove(c);
                if (c.playerId > 0) {
                    DeckPlayer p = game.byId(c.playerId);
                    game.removePlayer(c.playerId);
                    if (p != null) {
                        toAll(DeckProto.info(p.name + " left"));
                        log(p.name + " left (" + playerCount() + " online)");
                    }
                }
            }
        }
    }

    private void processRequests() {
        ClientRequest req;
        while ((req = pendingRequests.poll()) != null) {
            Connection conn = req.conn();
            if (conn.closed || conn.playerId <= 0) continue;
            Map<String, Object> msg = req.msg();
            switch (DeckProto.type(msg)) {
                case "start" -> {
                    if (!game.start(conn.playerId) && game.phase() == DeckGame.Phase.LOBBY) {
                        toPlayer(conn.playerId, DeckProto.info(
                                "Need at least " + DeckGame.MIN_PLAYERS
                                        + " players (add a bot?)"));
                    }
                }
                case "addbot" -> {
                    if (!game.addBot(conn.playerId)) {
                        toPlayer(conn.playerId, DeckProto.info("Can't add a bot now"));
                    }
                }
                case "rmbot" -> game.removeBot(conn.playerId);
                default -> game.handleAction(conn.playerId, msg);
            }
        }
    }

    // --- per-client connection -------------------------------------------------------

    private final class Connection {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
        final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);

        volatile String requestedName = "Player";
        /** Assigned by the tick thread once the join is accepted. */
        volatile int playerId;
        volatile boolean closed;
        volatile long lastHeardNanos = System.nanoTime();

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void startThreads() {
            daemon("deck-read-" + socket.getPort(), this::readLoop).start();
            daemon("deck-write-" + socket.getPort(), this::writeLoop).start();
        }

        void readLoop() {
            try {
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    lastHeardNanos = System.nanoTime();
                    Map<String, Object> msg = Protocol.decode(line);
                    if (msg == null) continue;
                    switch (DeckProto.type(msg)) {
                        case "join" -> handleJoin(msg);
                        case "ping" -> send(Protocol.encode(DeckProto.pong(
                                msg.get("p") instanceof Number n ? n.longValue() : 0)));
                        default -> {
                            if (playerId > 0) pendingRequests.add(new ClientRequest(this, msg));
                        }
                    }
                }
            } catch (IOException e) {
                // Disconnect; the tick thread announces it.
            } finally {
                markClosed();
            }
        }

        void handleJoin(Map<String, Object> msg) {
            if (playerId > 0) return;
            int version = msg.get("v") instanceof Number n ? n.intValue() : -1;
            if (version != DeckProto.VERSION) {
                sendNow(Protocol.encode(DeckProto.error(
                        "Incompatible Council of Six version " + version
                                + " (server is " + DeckProto.VERSION + ")")));
                markClosed();
                return;
            }
            requestedName = Protocol.sanitizeName(
                    msg.get("name") instanceof String s ? s : "Player");
            pendingJoins.add(this);
        }

        void writeLoop() {
            try {
                while (!closed) {
                    String msg = outbox.poll(250, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    out.write(msg);
                    out.write('\n');
                    while ((msg = outbox.poll()) != null) {
                        out.write(msg);
                        out.write('\n');
                    }
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // Disconnect; the tick thread announces it.
            } finally {
                markClosed();
            }
        }

        void send(String message) {
            if (closed) return;
            if (!outbox.offer(message)) {
                log("Player " + playerId + " is not keeping up; disconnecting");
                markClosed();
            }
        }

        void sendNow(String message) {
            try {
                out.write(message);
                out.write('\n');
                out.flush();
            } catch (IOException ignored) {
                // Connection is going away regardless.
            }
        }

        void markClosed() {
            closed = true;
            closeQuietly(socket);
        }
    }

    // --- small helpers -----------------------------------------------------------------

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        System.out.println("[deck-server] " + msg);
    }
}
