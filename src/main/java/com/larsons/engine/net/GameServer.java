package com.larsons.engine.net;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.core.GameLoop;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.Vehicle;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.Team;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.LiquidSim;
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
 * a join queue, per-connection input queues, and outbound queues.
 */
public final class GameServer {

    /** Simulation tick rate (Hz); {@code dt} is fixed at {@code 1/TICK_RATE}. */
    public static final int TICK_RATE = 60;

    /** Snapshots are broadcast every Nth tick (30 Hz — plenty for interpolation). */
    private static final int SNAPSHOT_EVERY = 2;

    /** Clients silent for this long are kicked (they ping every 2 s). */
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);

    /**
     * Outbound queue bound per client. Generous: a single simulation tick can
     * legitimately emit bursts (block events, FX, inventory pushes), and a
     * client that briefly stalls shouldn't be kicked over a full queue.
     */
    private static final int OUTBOX_CAPACITY = 2048;

    /**
     * Pending-input bound per client (~1-2 s of input at the client's send
     * rate). The tick thread drains the queue every tick, so it only fills if
     * the simulation stalls; then the oldest inputs drop first.
     */
    private static final int INBOX_CAPACITY = 128;

    /**
     * Server-side mining reach, in tiles from the player centre — the
     * client's five-tile rule plus slack for the small offset between a
     * predicted position and the authoritative one.
     */
    private static final double MINE_REACH_TILES = 6.5;

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
        // Simulation-owned item consumption (the Phoenix Feather's revive)
        // spends from the dying player's server-side inventory.
        this.world.setItemConsumer((player, key) -> {
            Inventory inv = inventoryOf(player.id);
            if (inv == null || inv.remove(key, 1) < 1) return false;
            for (Connection c : connections) {
                if (c.joined && !c.closed && c.state.id == player.id) sendInventory(c);
            }
            return true;
        });
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
            // authoritative block events like any player edit. Bursts go out
            // as one batched message: a liquid tick can change hundreds of
            // cells at once, and a message per cell used to overflow every
            // client's outbound queue and disconnect them.
            broadcastBlockChanges(world.pollBlockChanges());

            tick++;
            if (tick % SNAPSHOT_EVERY == 0) {
                broadcastState();
                if (minigame != null) broadcast(Protocol.minigame(minigame.toWireMap()));
            }

            next += nsPerTick;
            long now = System.nanoTime();
            if (next <= now) {
                next = now; // fell behind; don't try to catch up
            } else if (!GameLoop.waitUntil(next)) {
                return; // interrupted during shutdown
            }
        }
    }

    /** One {@code block} message for a lone change, one batch for a burst. */
    private void broadcastBlockChanges(List<LiquidSim.Change> changes) {
        if (changes.isEmpty()) return;
        if (changes.size() <= 3) {
            for (LiquidSim.Change change : changes) {
                broadcast(Protocol.blockSet(change.col(), change.row(), change.id(),
                        change.layer()));
            }
            return;
        }
        List<int[]> flat = new ArrayList<>(changes.size());
        for (LiquidSim.Change change : changes) {
            flat.add(new int[]{change.col(), change.row(), change.id(), change.layer()});
        }
        broadcast(Protocol.blockBatch(flat));
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
            // Compact + RLE: the welcome is one protocol line, and the pretty
            // form put every tile on its own indented line — big custom levels
            // overflowed the line cap and joiners timed out waiting for it.
            conn.send(Protocol.welcome(id, TICK_RATE, profile, level.toJsonCompact()));
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
            // Drain everything received since the last tick. Movement keys use
            // the most recent input, but edge-triggered intents — jump presses
            // and attack clicks — are collected from every drained input.
            // Clients send faster than the server ticks, so sampling only the
            // latest input used to overwrite (and lose) about half of all
            // clicks and jumps; and reusing a stale jump across ticks used to
            // burn every air jump at once.
            boolean jumpPressed = false;
            List<PlayerInput> attacks = null;
            PlayerInput latest = null;
            PlayerInput queued;
            while ((queued = c.inputQueue.poll()) != null) {
                if (queued.jump) jumpPressed = true;
                if (queued.attack && queued.seq != c.lastAttackSeq) {
                    if (attacks == null) attacks = new ArrayList<>(2);
                    attacks.add(queued);
                }
                latest = queued;
            }
            if (latest != null) c.heldInput = latest;
            PlayerInput in = c.heldInput;
            in.jump = jumpPressed;

            // Relic passives (extra air jumps, speed, slow fall, flight,
            // magnetism, melee power) come from the server-side inventory, so
            // they can't be conjured client-side.
            c.inventory.applyPassivesTo(c.state, profile.itemsEnabled);

            // A mounted player drives the vehicle instead of walking: the same
            // deterministic vehicle step the client predicts with. The rider
            // is then locked to the saddle, which is what snapshots carry.
            Vehicle riding = c.state.riding >= 0 ? world.vehicle(c.state.riding) : null;
            if (c.state.riding >= 0 && riding == null) c.state.riding = -1; // erased
            if (riding != null) {
                world.driveVehicle(riding, c.state, in, profile, dt);
            } else {
                // Physics always uses the served level's own format: a client
                // switching its local camera view must not change how it moves
                // on the server, and hosting an isometric level must not walk
                // everyone around as if it were the game type's side-scroller.
                PlayerPhysics.step(c.state, in, level, profile, level.perspective, dt);
            }
            in.jump = false; // consumed; must not re-fire while the input is held
            c.state.lastSeq = in.seq;
            // The input carries the hotbar selection, so "what is this player
            // holding" (melee damage, ranged shots, placements) stays current.
            c.inventory.select(in.selected);

            // Hold-to-mine progress against block durability, mirroring
            // offline play (see stepMining). Runs off the held input, so a
            // stroke keeps chipping between input arrivals.
            stepMining(c, in, dt);

            if (attacks != null) {
                for (PlayerInput atk : attacks) {
                    resolveAttack(c, atk, riding);
                }
                c.inventory.select(in.selected); // restore the live selection
            }
        }
    }

    /**
     * Resolve one attack click. A held ranged weapon or throwable fires a
     * projectile instead of swinging; ammo comes out of this player's
     * server-side inventory, so shots can't be fabricated. Each click is
     * edge-triggered by its sequence number and resolved exactly once.
     */
    private void resolveAttack(Connection c, PlayerInput atk, Vehicle riding) {
        boolean canAct = profile.combatEnabled
                || (profile.projectilesEnabled && profile.itemsEnabled);
        if (!canAct) return;
        c.lastAttackSeq = atk.seq;
        // Resolve against what the player held when they clicked.
        c.inventory.select(atk.selected);
        // An armed vehicle (war dragon) fires its own weapon first.
        if (riding != null && profile.projectilesEnabled
                && world.vehicleShoot(riding, c.state, atk.aimX, atk.aimY) != null) {
            return;
        }
        Projectile shot = null;
        if (profile.projectilesEnabled && profile.itemsEnabled) {
            shot = world.playerShoot(c.state, c.inventory, atk.aimX, atk.aimY);
        }
        if (shot != null) {
            sendInventory(c);
        } else if (profile.combatEnabled) {
            ItemDef held = profile.itemsEnabled ? c.inventory.selectedDef() : null;
            boolean melee = held == null || held.projectile() == null;
            double damage = World.FIST_DAMAGE + c.state.meleeBonus
                    + (melee && held != null ? held.damage() : 0);
            // Mini-game PvP: an enemy player in reach takes the swing;
            // otherwise it resolves against mobs as always — and a
            // whiffed swing near an empty vehicle packs it up.
            PlayerState victim = minigame == null ? null
                    : minigame.resolveMeleeHit(c.state, joinedPlayers(),
                    atk.aimX, atk.aimY);
            if (victim != null) {
                victim.hurt(damage);
                minigame.damaged(c.state.id, victim);
            } else if (world.playerAttack(c.state, atk.aimX, atk.aimY, damage) == null) {
                world.packUpVehicle(atk.aimX, atk.aimY, profile.itemsEnabled);
            }
        }
    }

    /**
     * The server-side twin of offline hold-to-mine ({@code World.continueMining}):
     * progress accumulates against the block's hardness, a matching held tool
     * multiplies speed, switching cells restarts, and the break lands — with
     * drops, tool wear, and the authoritative broadcast — only when progress
     * completes. This is what gives blocks their durability online instead of
     * breaking on a single click.
     */
    private void stepMining(Connection c, PlayerInput in, double dt) {
        if (!in.mine || !profile.blockEditingEnabled) {
            c.resetMining();
            return;
        }
        int col = in.mineCol;
        int row = in.mineRow;
        double ts = level.tileSize;
        double px = c.state.x + profile.playerSize / 2.0;
        double py = c.state.y + profile.playerSize / 2.0;
        boolean inReach = Math.hypot((col + 0.5) * ts - px, (row + 0.5) * ts - py)
                <= MINE_REACH_TILES * ts;
        if (!inReach || level.tileAt(col, row) <= 0) {
            c.resetMining();
            return;
        }
        // A stack comes apart from the top: the block under the tool is the
        // one standing on the floor, or the floor itself when nothing is.
        Block b = level.topBlockAt(col, row);
        if (b != null && b.liquid()) { // liquids aren't minable
            c.resetMining();
            return;
        }
        if (col != c.mineCol || row != c.mineRow) {
            c.mineCol = col;
            c.mineRow = row;
            c.mineProgress = 0;
        }
        ItemDef held = profile.itemsEnabled ? c.inventory.selectedDef() : null;
        // Legacy palette tiles have no block definition (hardness unknown):
        // instant, exactly like offline play.
        double hardness = b == null ? 0 : b.hardness();
        if (hardness > 0) {
            double power = held != null && held.toolClass() != null
                    && held.toolClass().equals(b.tool()) ? held.toolPower() : 1.0;
            c.mineProgress += dt * power / hardness;
            if (c.mineProgress < 1) return;
        }
        c.resetMining();
        int layer = world.mineLayer(col, row);
        Block mined = world.mineBlock(col, row, profile.itemsEnabled);
        boolean changed = mined != null || level.setTile(col, row, layer, 0);
        if (!changed) return;
        broadcast(Protocol.blockSet(col, row, level.tileAt(col, row, layer), layer));
        // Finished blocks wear the held tool, as offline (it may break).
        if (mined != null && held != null && held.toolClass() != null && profile.itemsEnabled) {
            if (c.inventory.damageSelected(1)) {
                c.send(Protocol.info(held.name() + " broke!"));
            }
            sendInventory(c);
        }
    }

    /**
     * Creative erase of one layer. Clearing a stacked block leaves its floor;
     * clearing the floor takes the whole cell with it, because a block with
     * nothing under it is neither a wall nor a path.
     */
    private boolean eraseLayer(int col, int row, int layer) {
        if (layer == Level.LAYER_UPPER) return level.setTile(col, row, layer, 0);
        Block mined = world.mineBlock(col, row, false);
        return mined != null || level.setTile(col, row, 0);
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
                    // Creative painting names the layer it means; play-mode
                    // placing lets the world choose (a hole is floored before
                    // anything is stood on it).
                    int layer = paint ? Protocol.layerOf(msg) : world.placeLayer(col, row);
                    if (id == 0) {
                        // Creative erase only. Play-mode mining is hold-to-mine
                        // via the input command (see stepMining), so blocks
                        // keep their durability online — a bare "break this"
                        // request would bypass it.
                        if (!paint) continue;
                        changed = eraseLayer(col, row, layer);
                    } else if (paint) {
                        changed = level.setTile(col, row, layer, id);
                    } else {
                        changed = layer >= 0 && placeFromInventory(conn, col, row, id);
                    }
                    if (changed) {
                        broadcast(Protocol.blockSet(col, row,
                                level.tileAt(col, row, layer), layer));
                    }
                }
                case "paint" -> {
                    if (!profile.creativeEnabled) continue;
                    String kind = msg.get("k") instanceof String s ? s : "";
                    String type = msg.get("e") instanceof String s ? s : "";
                    double x = dblOf(msg.get("x"));
                    double y = dblOf(msg.get("y"));
                    if ("mob".equals(kind) && profile.mobsEnabled) world.spawnMob(type, x, y);
                    if ("item".equals(kind) && profile.itemsEnabled) world.spawnItem(type, 1, x, y);
                    if ("vehicle".equals(kind)) world.spawnVehicle(type, x, y);
                }
                case "mount" -> world.mount(conn.state, intOf(msg.get("id")), profile);
                case "dismount" -> world.dismount(conn.state);
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
                // The meter lives on the server-side player state, so a
                // request only fires when the server agrees it is charged.
                case "ult" -> world.useUltimate(conn.state, dblOf(msg.get("x")),
                        dblOf(msg.get("y")), profile);
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
            // Thrown out in front of the player — along the way they are
            // facing, with the upward lob only where gravity can bring it back
            // down (see DroppedItem.tossForward).
            drop.tossForward(conn.state.facing, level.format().gravity());
            drop.pickupDelay = 1.0; // don't instantly vacuum it back up
        }
        sendInventory(conn);
    }

    /**
     * Use the item in a slot server-side: deploy a vehicle item, fire a relic
     * active, drink a mana potion, or eat/drink a healing consumable — the
     * same [F] menu the offline scene resolves locally.
     */
    private void consumeFromInventory(Connection conn, int slot) {
        ItemStack stack = conn.inventory.slot(slot);
        ItemDef def = stack == null ? null : world.itemTypes.get(stack.key);
        if (def == null) return;
        // Vehicle items become the vehicle, placed at the user's feet.
        VehicleDef vehicle = world.vehicleTypes.bySourceItem(def.key());
        if (vehicle != null) {
            if (conn.inventory.removeAt(slot, 1) < 1) return;
            world.spawnVehicle(vehicle.key(),
                    conn.state.x + (conn.state.facingLeft ? -20 : 20), conn.state.y);
            sendInventory(conn);
            return;
        }
        // Relic actives (Nova Crystal, Tremor Totem) fire without being spent.
        if (World.relicManaCost(def.key()) != null) {
            world.useRelic(conn.state, def.key(), profile);
            return;
        }
        if ("mana_potion".equals(def.key())) {
            if (conn.state.mana >= conn.state.maxMana) return;
            if (conn.inventory.removeAt(slot, 1) < 1) return;
            conn.state.mana = Math.min(conn.state.maxMana, conn.state.mana + 50);
            sendInventory(conn);
            return;
        }
        if (def.heal() <= 0) return;
        if (conn.state.health >= conn.state.maxHealth) return;
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
        List<Map<String, Object>> vehicles = new ArrayList<>(world.vehicles().size());
        for (Vehicle v : world.vehicles()) vehicles.add(v.toMap());
        broadcast(Protocol.state(tick, players, mobs, items, shots, vehicles,
                world.timeOfDay()));
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
        /**
         * Inputs received since the last tick, oldest first. The tick thread
         * drains all of them so edge-triggered intents (jumps, attack clicks)
         * are never lost between ticks, however fast the client sends.
         */
        final LinkedBlockingQueue<PlayerInput> inputQueue =
                new LinkedBlockingQueue<>(INBOX_CAPACITY);

        volatile String requestedName = "Player";
        volatile PlayerState state;      // assigned by the tick thread on join
        volatile boolean joined;
        volatile boolean closed;
        volatile long lastHeardNanos = System.nanoTime();
        /** The most recent input; held keys keep applying until the next one
         *  arrives (tick thread only, with edges already consumed). */
        PlayerInput heldInput = new PlayerInput();
        /** Input sequence whose attack was already applied (tick thread only). */
        int lastAttackSeq;
        /** Hold-to-mine progress on the current cell (tick thread only). */
        int mineCol = Integer.MIN_VALUE, mineRow = Integer.MIN_VALUE;
        double mineProgress;
        /** Server-side inventory: what this player has picked up (tick thread only). */
        final Inventory inventory = new Inventory(world.itemTypes);

        void resetMining() {
            mineCol = mineRow = Integer.MIN_VALUE;
            mineProgress = 0;
        }

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
                        case "input" -> {
                            // Queue full means the tick thread has stalled for
                            // seconds; shed the oldest input and keep the new.
                            PlayerInput parsed = PlayerInput.fromMap(msg);
                            while (!inputQueue.offer(parsed)) inputQueue.poll();
                        }
                        case "edit", "paint", "erase", "invmove", "invdrop", "use",
                             "mount", "dismount" -> {
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
