package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.World;

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
    private final Level level; // shared world view; "block" broadcasts apply here

    private volatile Snapshot latest;
    private volatile Snapshot previous;

    /**
     * Recent snapshots, oldest first, guarded by itself. Keeping a short
     * history (rather than just the last two) lets scenes interpolate at a
     * fixed delay behind real time even when snapshot arrival jitters —
     * with only two, any delay longer than one snapshot interval clamps to
     * the older snapshot and remote motion steps instead of glides.
     */
    private final java.util.ArrayDeque<Snapshot> history = new java.util.ArrayDeque<>();
    private static final int SNAPSHOT_HISTORY = 12;
    private volatile boolean connected = true;
    private volatile String disconnectReason;
    private volatile int pingMillis = -1;

    /** Recent server info messages ("X joined"), newest last, capped. */
    private final ConcurrentLinkedDeque<String> events = new ConcurrentLinkedDeque<>();

    /** Block changes since last polled (scenes spawn particles/sfx from these). */
    private final ConcurrentLinkedDeque<int[]> blockEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_BLOCK_EVENTS = 64;

    /** Projectile impacts since last polled (scenes spawn particles/sfx from these). */
    private final ConcurrentLinkedDeque<World.Impact> fxEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_FX_EVENTS = 64;

    /** Server-owned inventory contents, bumped whenever an "inv" message lands. */
    private volatile List<Object> inventoryData;
    private volatile int inventoryVersion;

    /** Latest replicated mini-game state, or {@code null} (no mini game). */
    private volatile MiniGameView minigame;

    /**
     * Connect and complete the join handshake, or throw with a reason
     * (unreachable host, incompatible version, server said no).
     *
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
            // The welcome line carries the whole level, so decode it against
            // the large server-line cap — the default cap silently dropped
            // big custom levels and the join would just time out.
            while (true) {
                String line = in.readLine();
                if (line == null) throw new IOException("Server closed the connection");
                Map<String, Object> msg = Protocol.decode(line, Protocol.MAX_SERVER_LINE_LENGTH);
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
        this.level = levelJson != null ? LevelLoader.parse(levelJson) : null;

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

    /**
     * The live client-side view of the server's world: parsed once on join,
     * then kept current by applying every {@code block} broadcast. Both the
     * play scene and the creative editor render (and edit through) this one
     * instance, so painting online is immediately visible everywhere.
     */
    public Level level() { return level; }

    /**
     * Drain block changes received since the last call, each as
     * {@code [col, row, newId, layer]} (for local feedback: particles,
     * sounds). {@code layer} is which of a plan-view level's two layers
     * changed — see {@link Level#LAYER_UPPER}.
     */
    public List<int[]> pollBlockEvents() {
        List<int[]> out = new ArrayList<>();
        int[] e;
        while ((e = blockEvents.pollFirst()) != null) out.add(e);
        return out;
    }

    /**
     * Drain projectile impacts broadcast since the last call (for local
     * feedback: particles, sounds) — the online mirror of
     * {@code World.pollImpacts()}.
     */
    public List<World.Impact> pollFxEvents() {
        List<World.Impact> out = new ArrayList<>();
        World.Impact e;
        while ((e = fxEvents.pollFirst()) != null) out.add(e);
        return out;
    }

    /** The running mini game's replicated state, or {@code null}. */
    public MiniGameView minigame() { return minigame; }

    /** Server-owned inventory contents (see {@code Inventory.fromList}), or null. */
    public List<Object> inventoryData() { return inventoryData; }

    /** Bumps every time the server sends new inventory contents. */
    public int inventoryVersion() { return inventoryVersion; }

    /** Most recent snapshot, or {@code null} before the first one arrives. */
    public Snapshot latest() { return latest; }

    /** The snapshot before {@link #latest()}, for interpolation. */
    public Snapshot previous() { return previous; }

    /**
     * The two snapshots straddling {@code renderTimeNanos} (by arrival time),
     * oldest first, for interpolated rendering at a fixed delay behind real
     * time. Clamps to the oldest/newest pair when the time falls outside the
     * kept history; {@code null} before the first snapshot arrives.
     */
    public Snapshot[] snapshotPair(long renderTimeNanos) {
        synchronized (history) {
            if (history.isEmpty()) return null;
            Snapshot before = null, after = null;
            for (Snapshot s : history) {
                if (s.receivedNanos() <= renderTimeNanos) {
                    before = s;
                } else {
                    after = s;
                    break;
                }
            }
            if (before == null) before = after;
            if (after == null) after = before;
            return new Snapshot[]{before, after};
        }
    }

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

    /** Ask the server to set a block; {@code mode} is "paint" or "play". */
    public void sendBlockEdit(int col, int row, int blockId, String mode) {
        sendBlockEdit(col, row, blockId, mode, Level.LAYER_GROUND);
    }

    /** A block edit aimed at one of a plan-view level's two layers. */
    public void sendBlockEdit(int col, int row, int blockId, String mode, int layer) {
        if (connected) outbox.offer(Protocol.blockEdit(col, row, blockId, mode, layer));
    }

    /**
     * Tell the server which character profile this player picked, so it
     * simulates the body the client is predicting — see
     * {@link Protocol#character}.
     */
    public void sendCharacter(String key) {
        if (connected) outbox.offer(Protocol.character(key));
    }

    /** Ask the server to spawn a painted entity (kind "mob" or "item"). */
    public void sendEntityPaint(String kind, String type, double x, double y) {
        if (connected) outbox.offer(Protocol.entityPaint(kind, type, x, y));
    }

    /** Ask the server to erase a painted entity. */
    public void sendEntityErase(int entityId) {
        if (connected) outbox.offer(Protocol.entityErase(entityId));
    }

    /** Ask the server to move/merge/swap an inventory stack between slots. */
    public void sendInvMove(int fromSlot, int toSlot) {
        if (connected) outbox.offer(Protocol.invMove(fromSlot, toSlot));
    }

    /** Ask the server to drop items from an inventory slot into the world. */
    public void sendInvDrop(int slot, int count) {
        if (connected) outbox.offer(Protocol.invDrop(slot, count));
    }

    /** Ask the server to consume/activate the item in a slot (eat / drink /
     *  fire a relic / deploy a vehicle). */
    public void sendUseItem(int slot) {
        if (connected) outbox.offer(Protocol.useItem(slot));
    }

    /** Ask the server to fire our charged ultimate at a world point. */
    public void sendUltimate(double aimX, double aimY) {
        if (connected) outbox.offer(Protocol.ultimate(aimX, aimY));
    }

    /** Ask the server to seat us on a vehicle (validated server-side). */
    public void sendMount(int vehicleId) {
        if (connected) outbox.offer(Protocol.mount(vehicleId));
    }

    /** Ask the server to unseat us from whatever we're riding. */
    public void sendDismount() {
        if (connected) outbox.offer(Protocol.dismount());
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
                Map<String, Object> msg = Protocol.decode(line, Protocol.MAX_SERVER_LINE_LENGTH);
                if (msg == null) continue;
                switch (Protocol.type(msg)) {
                    case "state" -> handleState(msg);
                    case "block" -> {
                        int col = msg.get("c") instanceof Number n ? n.intValue() : -1;
                        int row = msg.get("r") instanceof Number n ? n.intValue() : -1;
                        int id = msg.get("b") instanceof Number n ? n.intValue() : 0;
                        applyBlock(col, row, id, Protocol.layerOf(msg));
                    }
                    case "blocks" -> {
                        // A batched burst (liquid flow, explosions): flat
                        // col,row,id,layer quadruples in one message.
                        if (msg.get("l") instanceof List<?> flat) applyBlockBatch(flat);
                    }
                    case "inv" -> {
                        if (msg.get("items") instanceof List<?> list) {
                            inventoryData = new ArrayList<>(list);
                            inventoryVersion++;
                        }
                    }
                    case "fx" -> {
                        fxEvents.addLast(new World.Impact(
                                msg.get("k") instanceof String s ? s : "",
                                msg.get("x") instanceof Number n ? n.doubleValue() : 0,
                                msg.get("y") instanceof Number n ? n.doubleValue() : 0,
                                Boolean.TRUE.equals(msg.get("e"))));
                        while (fxEvents.size() > MAX_FX_EVENTS) fxEvents.pollFirst();
                    }
                    case "mg" -> minigame = MiniGameView.fromMap(msg);
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

    /** Apply one authoritative tile write and remember it for local feedback. */
    private void applyBlock(int col, int row, int id, int layer) {
        // Tile writes are single ints; a frame that races one simply draws it
        // a frame late, which is harmless.
        if (level != null && level.setTile(col, row, layer, id)) {
            blockEvents.addLast(new int[]{col, row, id, layer});
            while (blockEvents.size() > MAX_BLOCK_EVENTS) blockEvents.pollFirst();
        }
    }

    /** Apply a batched {@code blocks} message: flat col,row,id,layer quadruples. */
    private void applyBlockBatch(List<?> flat) {
        for (int k = 0; k + 3 < flat.size(); k += 4) {
            int col = flat.get(k) instanceof Number n ? n.intValue() : -1;
            int row = flat.get(k + 1) instanceof Number n ? n.intValue() : -1;
            int id = flat.get(k + 2) instanceof Number n ? n.intValue() : 0;
            int layer = flat.get(k + 3) instanceof Number n ? n.intValue() : 0;
            applyBlock(col, row, id, layer);
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
        List<EntityView> mobs = parseEntities(msg.get("mobs"));
        List<EntityView> items = parseEntities(msg.get("items"));
        List<EntityView> shots = parseEntities(msg.get("shots"));
        List<EntityView> vehicles = parseEntities(msg.get("veh"));
        double time = msg.get("time") instanceof Number n ? n.doubleValue() : 0.25;
        Snapshot snap = new Snapshot(tick, players, mobs, items, shots, vehicles,
                time, System.nanoTime());
        synchronized (history) {
            history.addLast(snap);
            while (history.size() > SNAPSHOT_HISTORY) history.pollFirst();
        }
        previous = latest;
        latest = snap;
    }

    private static List<EntityView> parseEntities(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) return List.of();
        List<EntityView> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e instanceof Map<?, ?> em) out.add(EntityView.fromMap(Json.asObject(em)));
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
