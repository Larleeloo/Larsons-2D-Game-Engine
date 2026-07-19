package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.Team;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.World;

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
    private final Level level;
    private final World world;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread tickThread;
    private volatile boolean running;

    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Connection> pendingJoins = new ConcurrentLinkedQueue<>();
    /**
     * World-edit and inventory requests handed from reader threads to the
     * tick thread, tagged with the connection that sent them (inventory ops
     * act on that player's server-side inventory).
     */
    private final ConcurrentLinkedQueue<ClientRequest> pendingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private long tick;

    private record ClientRequest(Connection conn, Map<String, Object> msg) {}

    /** The referee when the level carries a mini game, else {@code null}. */
    private final MiniGame minigame;

    public GameServer(GameProfile profile, String levelJson) {
        this.profile = profile;
        this.level = LevelLoader.parse(levelJson); // validate up front
        this.world = new World(level);
        this.world.populateFromLevel(profile);
        this.minigame = MiniGame.createIfConfigured(level);
        if (minigame != null) {
            minigame.setInventories(this::inventoryOf);
            world.setPvpRule(minigame); // the rule itself honours the PvP toggle
            world.setDeathListener(minigame::onPlayerDeath);
            world.setRespawnProvider(minigame::respawnPoint);
        }
    }

    /** The mini game this server referees, or {@code null} (for UI / tests). */
    public MiniGame minigame() {
        return minigame;
    }

    private Inventory inventoryOf(int playerId) {
        for (Connection c : connections) {
            if (c.joined && !c.closed && c.state.id == playerId) return c.inventory;
        }
        return null;
    }

    /** The live world (level + mobs + items) this server simulates. */
    public World world() {
        return world;
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
        if (minigame != null) {
            log("Mini game: " + minigame.config().mode.displayName
                    + " (" + minigame.config().teams + " teams, PvP "
                    + (minigame.config().pvp ? "on" : "off") + ")");
            String missing = minigame.validate();
            if (missing != null) log("Warning: " + missing);
        }
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

        world.setPickupListener(this::handlePickup);

        while (running) {
            processJoins();
            processDisconnects();
            processRequests();
            stepPlayers(dt);
            // The referee runs after damage lands and before the world
            // respawns the dead, so deaths are seen with flags still carried.
            if (minigame != null) minigame.step(dt, joinedPlayers());
            world.step(dt, joinedPlayers(), profile);
            broadcastImpacts();
            if (minigame != null) {
                for (String event : minigame.pollEvents()) {
                    broadcast(Protocol.info(event));
                }
                for (int id : minigame.pollInventoryChanges()) {
                    for (Connection c : connections) {
                        if (c.joined && !c.closed && c.state.id == id) sendInventory(c);
                    }
                }
            }
            // Tiles the simulation changed on its own (liquid flow) are
            // authoritative block events like any player edit.
            for (var change : world.pollBlockChanges()) {
                broadcast(Protocol.blockSet(change.col(), change.row(), change.id()));
            }

            tick++;
            if (tick % SNAPSHOT_EVERY == 0) {
                broadcastState();
                if (minigame != null) broadcast(Protocol.minigame(minigame.toWireMap()));
            }

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
            // Mini games deal joiners onto the smallest team and spawn them at
            // their team's painted spawns; otherwise the painted multiplayer
            // spawn points deal players out round-robin.
            int team = minigame != null ? minigame.assignTeam(id) : -1;
            double[] spawn = minigame != null
                    ? minigame.respawnPoint(id) : level.spawnPointFor(id);
            conn.state = new PlayerState(id, name, spawn[0], spawn[1]);
            conn.joined = true;
            // Serialize the live level so late joiners see every edit so far.
            conn.send(Protocol.welcome(id, TICK_RATE, profile, level.toJson()));
            if (minigame != null) {
                minigame.grantLoadout(id); // Battle's magic loadout (no-op otherwise)
                broadcast(Protocol.info(name + " joined the " + Team.name(team) + " team"));
            } else {
                broadcast(Protocol.info(name + " joined"));
            }
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
                    if (minigame != null) minigame.removePlayer(c.state.id);
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
            // The input carries the hotbar selection, so "what is this player
            // holding" (melee damage, ranged shots, placements) stays current.
            c.inventory.select(in.selected);

            // Attacks are edge-triggered by sequence number: the same input is
            // re-applied every tick until the next one arrives, but a swing
            // must land only once per click. A held ranged weapon or throwable
            // fires a projectile instead of swinging; ammo comes out of this
            // player's server-side inventory, so shots can't be fabricated.
            boolean canAct = profile.combatEnabled
                    || (profile.projectilesEnabled && profile.itemsEnabled);
            if (in.attack && canAct && in.seq != c.lastAttackSeq) {
                c.lastAttackSeq = in.seq;
                Projectile shot = null;
                if (profile.projectilesEnabled && profile.itemsEnabled) {
                    shot = world.playerShoot(c.state, c.inventory, in.aimX, in.aimY);
                }
                if (shot != null) {
                    sendInventory(c);
                } else if (profile.combatEnabled) {
                    ItemDef held = profile.itemsEnabled ? c.inventory.selectedDef() : null;
                    boolean melee = held == null || held.projectile() == null;
                    double damage = World.FIST_DAMAGE
                            + (melee && held != null ? held.damage() : 0);
                    // Mini-game PvP: an enemy player in reach takes the swing;
                    // otherwise it resolves against mobs as always.
                    PlayerState victim = minigame == null ? null
                            : minigame.resolveMeleeHit(c.state, joinedPlayers(),
                            in.aimX, in.aimY);
                    if (victim != null) {
                        victim.health -= damage;
                        minigame.damaged(c.state.id, victim);
                    } else {
                        world.playerAttack(c.state, in.aimX, in.aimY, damage);
                    }
                }
            }
        }
    }

    private List<PlayerState> joinedPlayers() {
        List<PlayerState> players = new ArrayList<>();
        for (Connection c : connections) {
            if (c.joined && !c.closed) players.add(c.state);
        }
        return players;
    }

    /**
     * Apply queued client requests (block place/mine, entity paint/erase,
     * inventory move/drop/use) on the tick thread, which owns all world and
     * inventory state; broadcast authoritative results. Feature toggles gate
     * what's allowed: creative painting needs the game type's creative toggle,
     * play-mode mining needs block editing, inventory ops need items.
     */
    private void processRequests() {
        ClientRequest req;
        while ((req = pendingRequests.poll()) != null) {
            Connection conn = req.conn();
            Map<String, Object> msg = req.msg();
            if (conn.closed || !conn.joined) continue;
            switch (Protocol.type(msg)) {
                case "edit" -> {
                    int col = intOf(msg.get("c"));
                    int row = intOf(msg.get("r"));
                    int id = intOf(msg.get("b"));
                    boolean paint = "paint".equals(msg.get("m"));
                    if (paint ? !profile.creativeEnabled : !profile.blockEditingEnabled) continue;
                    boolean changed;
                    if (id == 0) {
                        // Mining in play mode pops the block's drop out.
                        boolean withDrops = !paint && profile.itemsEnabled;
                        Block mined = world.mineBlock(col, row, withDrops);
                        changed = mined != null || level.setTile(col, row, 0);
                    } else if (paint) {
                        changed = level.setTile(col, row, id);
                    } else {
                        changed = placeFromInventory(conn, col, row, id);
                    }
                    if (changed) broadcast(Protocol.blockSet(col, row, level.tileAt(col, row)));
                }
                case "paint" -> {
                    if (!profile.creativeEnabled) continue;
                    String kind = msg.get("k") instanceof String s ? s : "";
                    String type = msg.get("e") instanceof String s ? s : "";
                    double x = dblOf(msg.get("x"));
                    double y = dblOf(msg.get("y"));
                    if ("mob".equals(kind) && profile.mobsEnabled) world.spawnMob(type, x, y);
                    if ("item".equals(kind) && profile.itemsEnabled) world.spawnItem(type, 1, x, y);
                }
                case "erase" -> {
                    if (profile.creativeEnabled) world.removeEntity(intOf(msg.get("id")));
                }
                case "invmove" -> {
                    if (!profile.itemsEnabled) continue;
                    if (conn.inventory.move(intOf(msg.get("a")), intOf(msg.get("b")))) {
                        sendInventory(conn);
                    }
                }
                case "invdrop" -> {
                    if (!profile.itemsEnabled) continue;
                    dropFromInventory(conn, intOf(msg.get("i")), intOf(msg.get("n")));
                }
                case "use" -> {
                    if (!profile.itemsEnabled) continue;
                    consumeFromInventory(conn, intOf(msg.get("i")));
                }
                default -> { /* not a request we know */ }
            }
        }
    }

    /**
     * Play-mode placement spends the matching block item from the placer's
     * inventory (when items are on) — the server-side twin of the client's
     * "you can only place what you hold" rule, so placements can't be conjured.
     */
    private boolean placeFromInventory(Connection conn, int col, int row, int id) {
        if (!profile.itemsEnabled) return world.placeBlock(col, row, id);
        Block block = level.blocks.get(id);
        if (block == null || world.itemTypes.get(block.key()) == null) return false;
        if (conn.inventory.remove(block.key(), 1) < 1) return false;
        boolean placed = world.placeBlock(col, row, id);
        if (!placed) {
            conn.inventory.add(block.key(), 1); // cell was occupied: refund
        } else {
            sendInventory(conn);
        }
        return placed;
    }

    /** Drop items out of a slot into the world at the player's feet. */
    private void dropFromInventory(Connection conn, int slot, int count) {
        ItemStack stack = conn.inventory.slot(slot);
        if (stack == null) return;
        String key = stack.key;
        int removed = conn.inventory.removeAt(slot, Math.max(1, count));
        if (removed <= 0) return;
        DroppedItem drop = world.spawnItem(key, removed, conn.state.x, conn.state.y);
        if (drop != null) {
            drop.toss(conn.state.facingLeft ? -170 : 170, -180);
            drop.pickupDelay = 1.0; // don't instantly vacuum it back up
        }
        sendInventory(conn);
    }

    /** Eat/drink the item in a slot: heals and consumes server-side. */
    private void consumeFromInventory(Connection conn, int slot) {
        ItemStack stack = conn.inventory.slot(slot);
        ItemDef def = stack == null ? null : world.itemTypes.get(stack.key);
        if (def == null || def.heal() <= 0) return;
        if (conn.state.health >= PlayerState.MAX_HEALTH) return;
        if (conn.inventory.removeAt(slot, 1) < 1) return;
        // Same food effects as offline play: heal, stamina, and (for rare
        // delicacies) mana — see World.applyFood.
        World.applyFood(conn.state, def);
        sendInventory(conn);
    }

    private void handlePickup(PlayerState player, String itemKey, int count) {
        for (Connection c : connections) {
            if (c.joined && !c.closed && c.state == player) {
                c.inventory.add(itemKey, count);
                sendInventory(c);
                return;
            }
        }
    }

    /** Push a player's authoritative inventory down to them. */
    private void sendInventory(Connection c) {
        c.send(Protocol.encode(Map.of("t", "inv", "items", c.inventory.toList())));
    }

    /** Broadcast this tick's projectile impacts so every client sees the FX. */
    private void broadcastImpacts() {
        for (World.Impact im : world.pollImpacts()) {
            broadcast(Protocol.fx(im.key(), im.x(), im.y(), im.explosion()));
        }
    }

    private void broadcastState() {
        List<PlayerState> players = joinedPlayers();
        List<Map<String, Object>> mobs = new ArrayList<>(world.mobs().size());
        for (Mob m : world.mobs()) mobs.add(m.toMap());
        List<Map<String, Object>> items = new ArrayList<>(world.items().size());
        for (DroppedItem i : world.items()) items.add(i.toMap());
        List<Map<String, Object>> shots = new ArrayList<>(world.projectiles().size());
        for (Projectile p : world.projectiles()) shots.add(p.toMap());
        broadcast(Protocol.state(tick, players, mobs, items, shots, world.timeOfDay()));
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static double dblOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
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
        /** Input sequence whose attack was already applied (tick thread only). */
        int lastAttackSeq;
        /** Server-side inventory: what this player has picked up (tick thread only). */
        final Inventory inventory = new Inventory(world.itemTypes);

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
                        case "edit", "paint", "erase", "invmove", "invdrop", "use" -> {
                            // World edits and inventory ops are applied by the
                            // tick thread, which owns the level, entities, and
                            // every player's inventory.
                            if (joined) pendingRequests.add(new ClientRequest(this, msg));
                        }
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
