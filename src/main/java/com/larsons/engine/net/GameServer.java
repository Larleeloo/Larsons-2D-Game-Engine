package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The authoritative game server (requirement #3). Host one like a Minecraft
 * Java server: bind a port, players connect to {@code your-ip:port}. Runs
 * headless (see {@link ServerMain}) or embedded in the game when a player
 * chooses "Host" (an integrated server, like opening a single-player world to
 * others).
 *
 * <p>Model: the server owns the simulation. It ticks {@link PlayerPhysics} at
 * a fixed {@value #TICK_RATE} Hz — the same deterministic step clients use for
 * prediction — applying each player's most recent {@link PlayerInput} and
 * broadcasting {@code state} snapshots. Clients never send positions, so they
 * can't teleport, and a laggy client degrades only itself.
 *
 * <p>Threading: one accept thread, two lightweight threads per connection
 * (blocking read; queued write so a slow client can't stall the tick), and one
 * tick thread that owns all player state. Cross-thread handoff is confined to
 * a join queue, per-connection input references, and outbound queues.
 */
public final class GameServer {

    /** Simulation tick rate (Hz); {@code dt} is fixed at {@code 1/TICK_RATE}. */
    public static final int TICK_RATE = 60;

    /** Snapshots are broadcast every Nth tick (30 Hz — plenty for interpolation). */
    private static final int SNAPSHOT_EVERY = 2;

    /** Clients silent for this long are kicked (they ping every 2 s). */
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);

    private static final int OUTBOX_CAPACITY = 256;

    private final GameProfile profile;
    private final String levelJson;
    private final Level level;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread tickThread;
    private volatile boolean running;

    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Connection> pendingJoins = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private long tick;

    public GameServer(GameProfile profile, String levelJson) {
        this.profile = profile;
        this.levelJson = levelJson;
        this.level = LevelLoader.parse(levelJson); // validate up front
    }

    /** Bind and start serving. Port {@code 0} picks a free port (see {@link #getPort()}). */
    public synchronized void start(int port) throws IOException {
        if (running) throw new IllegalStateException("Server already running");
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = daemon("server-accept", this::acceptLoop);
        tickThread = daemon("server-tick", this::tickLoop);
        acceptThread.start();
        tickThread.start();
        log("Hosting '" + profile.name + "' / level '" + level.name + "' on port " + getPort());
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running;
    }

    /** Number of joined players (for UI / tests). */
    public int playerCount() {
        int n = 0;
        for (Connection c : connections) if (c.joined && !c.closed) n++;
        return n;
    }

    /** Stop serving and disconnect everyone. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        closeQuietly(serverSocket);
        for (Connection c : connections) c.markClosed();
        Connection pending;
        while ((pending = pendingJoins.poll()) != null) pending.markClosed();
        joinQuietly(acceptThread);
        joinQuietly(tickThread);
        log("Server stopped");
    }

    // --- accept ----------------------------------------------------------------

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
                // Socket closed during stop(): fall out of the loop.
            }
        }
    }

    // --- fixed-rate tick ---------------------------------------------------------

    private void tickLoop() {
        final double dt = 1.0 / TICK_RATE;
        final long nsPerTick = 1_000_000_000L / TICK_RATE;
        long next = System.nanoTime();

        while (running) {
            processJoins();
            processDisconnects();
            stepPlayers(dt);

            tick++;
            if (tick % SNAPSHOT_EVERY == 0) broadcastState();

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
                next = System.nanoTime(); // fell behind; don't try to catch up
            }
        }
    }

    private void processJoins() {
        Connection conn;
        while ((conn = pendingJoins.poll()) != null) {
            if (conn.closed) continue;
            int id = nextId.getAndIncrement();
            String name = uniqueName(conn.requestedName, id);
            conn.state = new PlayerState(id, name, level.spawnX, level.spawnY);
            conn.joined = true;
            conn.send(Protocol.welcome(id, TICK_RATE, profile, levelJson));
            broadcast(Protocol.info(name + " joined"));
            log(name + " joined from " + conn.socket.getRemoteSocketAddress()
                    + " (" + playerCount() + " online)");
        }
    }

    private void processDisconnects() {
        long now = System.nanoTime();
        for (Connection c : connections) {
            if (!c.closed && now - c.lastHeardNanos > TIMEOUT_NANOS) {
                log((c.joined ? c.state.name : "A connection") + " timed out");
                c.markClosed();
            }
            if (c.closed) {
                connections.remove(c);
                if (c.joined) {
                    broadcast(Protocol.info(c.state.name + " left"));
                    log(c.state.name + " left (" + playerCount() + " online)");
                }
            }
        }
    }

    private void stepPlayers(double dt) {
        for (Connection c : connections) {
            if (!c.joined || c.closed) continue;
            PlayerInput in = c.latestInput.get();
            // Physics always uses the profile's perspective: a client switching
            // its local camera view must not change how it moves on the server.
            PlayerPhysics.step(c.state, in, level, profile, profile.perspective, dt);
            c.state.lastSeq = in.seq;
        }
    }

    private void broadcastState() {
        List<PlayerState> players = new ArrayList<>();
        for (Connection c : connections) {
            if (c.joined && !c.closed) players.add(c.state);
        }
        broadcast(Protocol.state(tick, players));
    }

    private void broadcast(String message) {
        for (Connection c : connections) {
            if (c.joined && !c.closed) c.send(message);
        }
    }

    private String uniqueName(String requested, int id) {
        String name = Protocol.sanitizeName(requested);
        for (Connection c : connections) {
            if (c.joined && !c.closed && c.state.name.equalsIgnoreCase(name)) {
                return name + "-" + id;
            }
        }
        return name;
    }

    // --- per-client connection -----------------------------------------------

    private final class Connection {
        final Socket socket;
        final BufferedReader in;
        final BufferedWriter out;
        final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
        final AtomicReference<PlayerInput> latestInput = new AtomicReference<>(new PlayerInput());

        volatile String requestedName = "Player";
        volatile PlayerState state;      // assigned by the tick thread on join
        volatile boolean joined;
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
            daemon("server-read-" + socket.getPort(), this::readLoop).start();
            daemon("server-write-" + socket.getPort(), this::writeLoop).start();
        }

        void readLoop() {
            try {
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    lastHeardNanos = System.nanoTime();
                    Map<String, Object> msg = Protocol.decode(line);
                    if (msg == null) continue;
                    switch (Protocol.type(msg)) {
                        case "join" -> handleJoin(msg);
                        case "input" -> latestInput.set(PlayerInput.fromMap(msg));
                        case "ping" -> send(Protocol.pong(
                                msg.get("p") instanceof Number n ? n.longValue() : 0));
                        default -> { /* unknown message types are ignored */ }
                    }
                }
            } catch (IOException e) {
                // Disconnect; the tick thread announces it.
            } finally {
                markClosed();
            }
        }

        void handleJoin(Map<String, Object> msg) {
            if (joined) return;
            int version = msg.get("v") instanceof Number n ? n.intValue() : -1;
            if (version != Protocol.VERSION) {
                sendNow(Protocol.error("Incompatible protocol version " + version
                        + " (server is " + Protocol.VERSION + ")"));
                markClosed();
                return;
            }
            requestedName = msg.get("name") instanceof String s ? s : "Player";
            pendingJoins.add(this);
        }

        void writeLoop() {
            try {
                while (!closed) {
                    String msg = outbox.poll(250, TimeUnit.MILLISECONDS);
                    if (msg == null) continue;
                    out.write(msg);
                    out.write('\n');
                    // Drain whatever else queued up before flushing once.
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

        /** Queue a message; a client too slow to keep up gets disconnected. */
        void send(String message) {
            if (closed) return;
            if (!outbox.offer(message)) {
                log((joined ? state.name : "A connection") + " is not keeping up; disconnecting");
                markClosed();
            }
        }

        /** Blocking write used before the writer thread matters (join errors). */
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

    // --- small helpers ---------------------------------------------------------

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
        System.out.println("[server] " + msg);
    }
}
