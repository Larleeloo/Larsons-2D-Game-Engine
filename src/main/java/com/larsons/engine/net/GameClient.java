package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;
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
 * Client side of online play: dial a server by address and port (Minecraft
 * "direct connect" style), then pump {@link PlayerInput} commands up and read
 * state {@link Snapshot}s back.
 *
 * <p>{@link #connect} performs the handshake synchronously — on return the
 * client holds the server's game type ({@link #profile()}) and level
 * ({@link #levelJson()}), so the caller can enter the exact world the host
 * configured. After that a reader thread keeps the latest two snapshots (for
 * remote-player interpolation), a writer thread drains the outbound queue
 * (a stalled network never blocks the game loop), and pings measure RTT every
 * couple of seconds, which doubles as the keep-alive.
 */
public final class GameClient implements Closeable {

    private static final long PING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final int SO_TIMEOUT_MS = 15_000;
    private static final int MAX_EVENTS = 4;

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(128);

    private final int localId;
    private final int tickRate;
    private final GameProfile profile;
    private final String levelJson;

    private volatile Snapshot latest;
    private volatile Snapshot previous;
    private volatile boolean connected = true;
    private volatile String disconnectReason;
    private volatile int pingMillis = -1;

    /** Recent server info messages ("X joined"), newest last, capped. */
    private final ConcurrentLinkedDeque<String> events = new ConcurrentLinkedDeque<>();

    /**
     * Connect and complete the join handshake, or throw with a reason
     * (unreachable host, incompatible version, server said no).
     */
    public static GameClient connect(String host, int port, String playerName, int timeoutMs)
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

            out.write(Protocol.join(playerName));
            out.write('\n');
            out.flush();

            // Read until the welcome (or a rejection); skip anything else.
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("Server closed the connection");
                Map<String, Object> msg = Protocol.decode(line);
                if (msg == null) continue;
                switch (Protocol.type(msg)) {
                    case "welcome" -> {
                        socket.setSoTimeout(SO_TIMEOUT_MS);
                        return new GameClient(socket, in, out, msg);
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

    private GameClient(Socket socket, BufferedReader in, BufferedWriter out,
                       Map<String, Object> welcome) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.localId = welcome.get("id") instanceof Number n ? n.intValue() : 0;
        this.tickRate = welcome.get("tickRate") instanceof Number n ? n.intValue() : GameServer.TICK_RATE;
        this.profile = welcome.get("profile") instanceof Map<?, ?> m
                ? GameProfile.fromMap(Json.asObject(m)) : new GameProfile();
        this.levelJson = welcome.get("level") instanceof String s ? s : null;

        daemon("client-read", this::readLoop).start();
        daemon("client-write", this::writeLoop).start();
    }

    // --- accessors ---------------------------------------------------------------

    public int localId() { return localId; }

    /** The server's simulation tick rate (Hz). */
    public int tickRate() { return tickRate; }

    /** The host's game type; the client plays with these features. */
    public GameProfile profile() { return profile; }

    /** Raw JSON of the level the server is running, or {@code null}. */
    public String levelJson() { return levelJson; }

    /** Most recent snapshot, or {@code null} before the first one arrives. */
    public Snapshot latest() { return latest; }

    /** The snapshot before {@link #latest()}, for interpolation. */
    public Snapshot previous() { return previous; }

    public boolean isConnected() { return connected; }

    /** Why the connection ended, or {@code null} while healthy. */
    public String disconnectReason() { return disconnectReason; }

    /** Round-trip time in ms, or -1 before the first pong. */
    public int pingMillis() { return pingMillis; }

    /** Recent server messages ("X joined"), oldest first. */
    public List<String> recentEvents() { return new ArrayList<>(events); }

    // --- sending -------------------------------------------------------------------

    /** Queue this tick's input command (never blocks; drops if the queue is full). */
    public void sendInput(PlayerInput input) {
        if (connected) outbox.offer(Protocol.input(input));
    }

    @Override
    public void close() {
        markDisconnected("Left the game");
    }

    // --- worker threads --------------------------------------------------------------

    private void readLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                Map<String, Object> msg = Protocol.decode(line);
                if (msg == null) continue;
                switch (Protocol.type(msg)) {
                    case "state" -> handleState(msg);
                    case "pong" -> {
                        long sent = msg.get("p") instanceof Number n ? n.longValue() : 0;
                        pingMillis = (int) Math.max(0, nowMillis() - sent);
                    }
                    case "info" -> {
                        if (msg.get("msg") instanceof String s) {
                            events.addLast(s);
                            while (events.size() > MAX_EVENTS) events.pollFirst();
                        }
                    }
                    case "error" -> markDisconnected(
                            msg.get("msg") instanceof String s ? s : "Server error");
                    default -> { /* unknown message types are ignored */ }
                }
            }
            markDisconnected("Connection closed by server");
        } catch (IOException e) {
            markDisconnected("Connection lost: " + e.getMessage());
        }
    }

    private void handleState(Map<String, Object> msg) {
        long tick = msg.get("tick") instanceof Number n ? n.longValue() : 0;
        List<PlayerState> players = new ArrayList<>();
        if (msg.get("players") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> pm) players.add(PlayerState.fromMap(Json.asObject(pm)));
            }
        }
        previous = latest;
        latest = new Snapshot(tick, players, System.nanoTime());
    }

    private void writeLoop() {
        long lastPing = System.nanoTime() - PING_INTERVAL_NANOS; // ping immediately
        try {
            while (connected) {
                long now = System.nanoTime();
                if (now - lastPing >= PING_INTERVAL_NANOS) {
                    lastPing = now;
                    out.write(Protocol.ping(nowMillis()));
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

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
