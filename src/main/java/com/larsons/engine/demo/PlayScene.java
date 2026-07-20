package com.larsons.engine.demo;

import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.crafting.Recipe;
import com.larsons.engine.crafting.RecipeRegistry;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.entity.Vehicle;
import com.larsons.engine.entity.VehicleDef;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.CutsceneDirector;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.minigame.Team;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.NetSession;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.sim.StatRuleEngine;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.ContainerPanel;
import com.larsons.engine.ui.CraftingPanel;
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.World;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gameplay scene that honours the active {@link GameProfile}: it only enables
 * the features the creator turned on — perspective, zoom + bounds, gravity,
 * HUD, grid, entity sizes, and (merged in from the Side-Scroller engine)
 * mobs, items + inventory, combat, block mining/placing, lighting, parallax,
 * particles, and sound — and exposes the same toggles live via a pause menu
 * (Esc), so features can be enabled/disabled both on launch and in-game.
 *
 * <p><b>Online play (requirement #3).</b> When the {@link GameContext} carries
 * a {@link NetSession}, this same scene becomes the multiplayer client: the
 * level comes from the server (and stays in sync as block edits are
 * broadcast), the local player is <em>predicted</em> with the identical
 * {@link PlayerPhysics} the server runs, remote players are interpolated, and
 * mobs/dropped items are rendered from server snapshots (the server is the
 * only simulation). Mining, placing, and attacks are requests the server
 * validates and applies.
 *
 * <p>Controls: WASD/arrows move, P cycles perspective (if enabled), +/- zoom
 * (if enabled), left-click mine/attack, right-click place, 1-5 + wheel hotbar,
 * I inventory, F eat, Esc pause.
 */
public class PlayScene extends AbstractScene {

    /** Remote players are drawn this far in the past, between two snapshots. */
    private static final long INTERP_DELAY_NANOS = 100_000_000L; // 100 ms

    /** Prediction errors beyond this snap instantly (teleports, big lag spikes). */
    private static final double SNAP_DISTANCE = 128;

    /** How aggressively prediction errors are blended away, per second. */
    private static final double CORRECTION_PER_SEC = 8.0;

    /** Mining / placing reach, in tiles from the player centre. */
    private static final int REACH_TILES = 5;

    private static final Font HUD_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private final GameContext ctx;
    private final String levelPath;

    private Level level;
    private Camera camera;
    private Animation walkAnim;

    private PlayerState me = new PlayerState();
    private int inputSeq;

    private NetSession net; // null in single-player
    private final Map<Integer, Animation> remoteAnims = new HashMap<>();

    // Offline world simulation (mobs, items, drops). Online the server owns it.
    private World world;
    // The level's mini game: offline this scene referees it locally; online
    // the server does and this is null. mgView is what the HUD renders from
    // in both cases (the wire shape).
    private MiniGame localMinigame;
    private MiniGameView mgView;
    private Inventory inventory;
    private int invSyncVersion = -1;
    private boolean showInventory;
    /** Slot picked up by the inventory cursor (-1 = nothing held). */
    private int cursorSlot = -1;
    private int mouseX, mouseY; // sampled each update, for render-time UI

    private ParallaxBackground parallax;
    private final Particles particles = new Particles();
    /**
     * Online only: the locally-predicted copy of the vehicle this player is
     * riding, stepped with the same deterministic physics the server runs and
     * blended toward its snapshot state — the mounted twin of the player's
     * own prediction. {@code null} while on foot (or offline, where the
     * world's own vehicle is driven directly).
     */
    private Vehicle predictedVehicle;
    private double swingTime;      // seconds left on the melee swing visual
    private double prevVy;
    private double prevHealth = PlayerState.MAX_HEALTH;
    // Stat tracking + the level's programmable rules + station crafting
    // (offline: the local world owns all three).
    private PlayerStats stats;
    private StatRuleEngine ruleEngine;
    private CutsceneDirector cutscenes; // runs the level's cutscenes (offline)
    private CraftingPanel craftingPanel; // non-null while a station UI is open
    private ContainerPanel containerPanel; // non-null while a chest/barrel is open
    private String ruleStatus = "";
    private double ruleStatusTime;
    private double animClock;      // drives skinned (sprite-sheet) textures
    private DoorDirectory doors;   // this game type's external door list
    // Per-frame cache: block id -> skin frame (null = procedural colour).
    private final Map<Integer, BufferedImage> tileSkins = new HashMap<>();

    private boolean paused;
    private ConfigForm pauseForm;

    // Scratch buffers for zero-allocation tile projection.
    private final int[] xs = new int[4];
    private final int[] ys = new int[4];
    private final int[] corner = new int[2];

    public PlayScene(GameContext ctx, String levelPath) {
        this.ctx = ctx;
        this.levelPath = levelPath;
    }

    private GameProfile profile() { return ctx.profile(); }

    @Override
    public void onEnter() {
        paused = false;
        pauseForm = null;
        net = ctx.session();
        remoteAnims.clear();
        particles.clear();
        predictedVehicle = null;
        showInventory = false;
        cursorSlot = -1;
        swingTime = 0;
        doors = new DoorDirectory(profile().name);
        // Objects created with the creative editor's "+" entries must be
        // registered before a level referencing them loads.
        new CustomContentStore(profile().name).loadAndRegister();
        stats = new PlayerStats();
        craftingPanel = null;
        containerPanel = null;
        ruleStatus = "";
        ruleStatusTime = 0;

        // Online, the world is whatever the server runs (one shared Level
        // instance that block broadcasts keep current); offline, prefer the
        // game type's last saved creative level, falling back to the bundled
        // sample.
        if (net != null && net.client().level() != null) {
            level = net.client().level();
            world = null;
        } else {
            level = loadOfflineLevel();
            // Each level carries its own feature toggles: apply them so the
            // game type acts as a folder of diverse levels, not one fixed
            // feature set. Legacy levels (settings == null) keep the game
            // type's profile as-is.
            ctx.applyLevelSettings(level.settings);
            world = new World(level);
            world.populateFromLevel(profile());
            world.setPickupListener((player, key, count) -> {
                inventory.add(key, count);
                stats.add("items_picked_up", count);
                ctx.sfx(Sfx.PICKUP);
            });
        }
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        // Cutscenes are an offline feature, like stat-rule bars and doors.
        cutscenes = net == null ? new CutsceneDirector(level.cutscenes) : null;
        inventory = new Inventory(world != null ? world.itemTypes : ItemRegistry.standard());
        invSyncVersion = -1;

        GameProfile p = profile();
        // Offline, the camera opens in the level's own perspective (each
        // level remembers whether it's a side-scroller, top-down, or
        // isometric world); online the profile rules so everyone matches.
        camera = new Camera(basePerspective(), viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = p.defaultZoom;

        me = new PlayerState(net != null ? net.client().localId() : 0, "",
                level.spawnX, level.spawnY);
        prevHealth = me.health;
        setupLocalMinigame();

        parallax = null; // rebuilt lazily against the level's background
        rebuildSprite();
        syncCameraFromProfile();
    }

    /**
     * Offline, this scene referees the level's mini game itself (the same
     * {@link MiniGame} the server runs online), so creators can test their
     * CTF/Stockpile/Battle/Escort maps solo before hosting them.
     */
    private void setupLocalMinigame() {
        localMinigame = null;
        mgView = null;
        if (net != null || world == null) return;
        localMinigame = MiniGame.createIfConfigured(level);
        if (localMinigame == null) return;
        localMinigame.assignTeam(me.id);
        localMinigame.setInventories(id -> inventory);
        world.setPvpRule(localMinigame);
        world.setDeathListener(localMinigame::onPlayerDeath);
        world.setRespawnProvider(localMinigame::respawnPoint);
        localMinigame.grantLoadout(me.id); // Battle's magic loadout (no-op otherwise)
        localMinigame.pollInventoryChanges(); // local inventory is already live
        me.name = "You";
        double[] spawn = localMinigame.respawnPoint(me.id);
        me.x = spawn[0];
        me.y = spawn[1];
        String missing = localMinigame.validate();
        ruleStatus = missing != null ? missing
                : localMinigame.config().mode.displayName + " — you are on the "
                + Team.name(localMinigame.teamOf(me.id)) + " team";
        ruleStatusTime = 6;
        mgView = MiniGameView.fromMap(localMinigame.toWireMap());
    }

    private Level loadOfflineLevel() {
        String last = profile().lastLevelPath;
        if (last != null && !last.isEmpty() && Files.exists(Path.of(last))) {
            try {
                return LevelLoader.load(last);
            } catch (RuntimeException e) {
                System.err.println("PlayScene: failed to load " + last + ": " + e.getMessage());
            }
        }
        return LevelLoader.load(levelPath);
    }

    @Override
    public void onResize(int w, int h) {
        super.onResize(w, h);
        if (camera != null) camera.setViewport(w, h);
    }

    @Override
    public void update(double dt, InputManager input) {
        if (net != null && !net.client().isConnected()) {
            if (input.isKeyJustPressed(KeyEvent.VK_ENTER)
                    || input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
                ctx.closeSession();
                net = null;
                scenes.transitionTo("menu");
            }
            return;
        }
        if (paused) {
            updatePaused(dt, input);
            return;
        }
        // A running cutscene owns the frame: the world holds still, the
        // director drives the camera, Enter/Esc skips to the end.
        if (cutscenes != null && cutscenes.active() != null) {
            animClock += dt;
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)
                    || input.isKeyJustPressed(KeyEvent.VK_ENTER)) {
                cutscenes.skip();
            } else {
                cutscenes.advance(dt);
            }
            CutscenePlayer cut = cutscenes.active();
            if (cut != null) camera.centerOn(cut.cameraX(), cut.cameraY());
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
            } else if (showInventory) {
                showInventory = false;
            } else {
                openPause();
            }
            return;
        }

        GameProfile p = profile();
        enforceProfileConstraints(p);
        animClock += dt;
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();
        if (ruleStatusTime > 0) ruleStatusTime -= dt;

        // Walk into a painted door and press E: load its target level; with no
        // door, E opens a nearby crafting/alchemy station, and with neither,
        // it mounts (or dismounts) a nearby vehicle. Doors and stations are
        // single-player concerns (online the server owns the level), but
        // mounting works everywhere — online it's a validated server request.
        // The container panel keeps the inventory open beside it, so E still
        // closes it while the inventory shows.
        if (net == null && (!showInventory || containerPanel != null)
                && input.isKeyJustPressed(KeyEvent.VK_E)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
            } else if (me.riding >= 0) {
                world.dismount(me);
                ctx.sfx(Sfx.CLICK);
            } else if (!tryDoorTravel(p) && !tryOpenStation(p)) {
                Vehicle mountable = world.mountableNear(me.x + ps() / 2, me.y + ps() / 2);
                if (mountable != null && world.mount(me, mountable.id, p)) {
                    ctx.sfx(Sfx.CLICK);
                }
            }
        }
        if (net != null && !showInventory && input.isKeyJustPressed(KeyEvent.VK_E)) {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                if (snap.vehicleRiddenBy(me.id) != null) {
                    net.client().sendDismount();
                    ctx.sfx(Sfx.CLICK);
                } else {
                    EntityView near = nearestSnapshotVehicle(snap);
                    if (near != null) {
                        net.client().sendMount(near.id);
                        ctx.sfx(Sfx.CLICK);
                    }
                }
            }
        }
        // A mined-away chest closes its panel instantly; a finished closing
        // animation removes it (and the inventory it brought along).
        if (containerPanel != null) {
            containerPanel.tick(dt);
            if (!containerPanel.valid() || containerPanel.closed()) {
                containerPanel = null;
                showInventory = false;
                cursorSlot = -1;
            }
        }

        if (p.perspectiveSwitchingEnabled && input.isKeyJustPressed(KeyEvent.VK_P)) {
            camera.setPerspective(camera.getPerspective().next());
        }
        if (p.zoomEnabled) {
            if (input.isKeyDown(KeyEvent.VK_EQUALS)) camera.zoom = clampZoom(camera.zoom + dt * 2, p);
            if (input.isKeyDown(KeyEvent.VK_MINUS)) camera.zoom = clampZoom(camera.zoom - dt * 2, p);
        }

        if (craftingPanel != null) {
            updateCrafting(input);
        } else if (containerPanel != null) {
            if (containerPanel.update(input, inventory, cursorSlot,
                    viewportWidth, viewportHeight)) {
                ctx.sfx(Sfx.CLICK);
                // A deposited cursor stack no longer exists in the grid.
                if (cursorSlot >= 0 && inventory.slot(cursorSlot) == null) cursorSlot = -1;
            } else if (containerPanel.interactive()) {
                // The inventory shows beside the container: keep its mouse
                // interactions and hotbar selection live so stacks can be
                // arranged and [Q]-stashed without closing the chest.
                for (int k = 0; k < Inventory.HOTBAR; k++) {
                    if (input.isKeyJustPressed(KeyEvent.VK_1 + k)) inventory.select(k);
                }
                int wheel = input.getWheelRotation();
                if (wheel != 0) inventory.scrollSelect(wheel > 0 ? 1 : -1);
                handleInventoryMouse(input);
            }
        } else {
            updateInventoryControls(input, p);
        }

        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);
        in.sprint = input.isKeyDown(KeyEvent.VK_SHIFT);
        // Fresh presses drive mid-air jumps (double jump and beyond).
        in.jump = input.isKeyJustPressed(KeyEvent.VK_W)
                || input.isKeyJustPressed(KeyEvent.VK_UP)
                || input.isKeyJustPressed(KeyEvent.VK_SPACE);
        // The server resolves attacks against what this player holds.
        in.selected = inventory.selectedIndex();
        // Relic passives — extra air jumps, speed, slow fall, flight,
        // magnetism, melee power — refresh from the carried inventory.
        inventory.applyPassivesTo(me, p.itemsEnabled);

        if (!showInventory && craftingPanel == null && containerPanel == null) {
            handleMouseActions(input, p, in, dt);
        } else if (world != null) {
            world.cancelMining();
        }

        // Online, physics must not depend on the local camera view — the server
        // simulates with the profile's perspective, so prediction does too.
        // A mounted player drives their vehicle instead of walking.
        Perspective simPerspective = net != null ? p.perspective : camera.getPerspective();
        prevVy = me.vy;
        double preX = me.x, preY = me.y;
        if (!stepRiding(in, p, dt)) {
            PlayerPhysics.step(me, in, level, p, simPerspective, dt);
        }
        if (me.vy < -1 && prevVy >= 0) {
            stats.add("jumps", 1);
            ctx.sfx(Sfx.JUMP);
        }
        stats.add("distance_traveled", Math.abs(me.x - preX) + Math.abs(me.y - preY));

        if (net != null) {
            net.client().sendInput(in);
            reconcile(dt);
            advanceRemoteAnimations(dt);
            consumeNetFeedback();
            mgView = net.client().minigame(); // replicated mini-game state
            if (p.particlesEnabled) {
                Snapshot snap = net.client().latest();
                if (snap != null) {
                    for (EntityView s : snap.shots()) emitTrail(s.key, s.x, s.y);
                }
                emitStatusParticles(dt);
            }
        } else {
            // Same order as the server tick: the referee sees deaths before
            // the world respawns them.
            if (localMinigame != null) localMinigame.step(dt, List.of(me));
            world.step(dt, List.of(me), p);
            if (localMinigame != null) {
                for (String event : localMinigame.pollEvents()) {
                    ruleStatus = event;
                    ruleStatusTime = 3.5;
                    ctx.sfx(Sfx.PICKUP);
                }
                localMinigame.pollInventoryChanges(); // local inventory is already live
                mgView = MiniGameView.fromMap(localMinigame.toWireMap());
            }
            stats.add("mobs_killed", world.pollKills());
            stats.add("deaths", world.pollDeaths());
            for (World.Impact im : world.pollImpacts()) impactFeedback(im, p);
            // Tiles the simulation broke on its own (bomb craters, the drill,
            // the Tremor Totem) shower shards like hand-mined blocks do.
            for (var change : world.pollBlockChanges()) {
                if (p.particlesEnabled && change.id() == 0) {
                    particles.burst((change.col() + 0.5) * ts(),
                            (change.row() + 0.5) * ts(),
                            new Color(150, 130, 100), 5);
                }
            }
            if (p.particlesEnabled) {
                for (Projectile pr : world.projectiles()) emitTrail(pr.def.key(), pr.x, pr.y);
                emitStatusParticles(dt);
            }
            // The level's programmable stat rules run against this run's stats.
            for (StatRuleEngine.Fired fired : ruleEngine.update(stats, inventory)) {
                ctx.sfx(Sfx.PICKUP);
                ruleStatus = ruleFiredMessage(fired.rule());
                ruleStatusTime = 3.5;
            }
        }

        if (me.health < prevHealth - 0.01) {
            stats.add("damage_taken", prevHealth - me.health);
            ctx.sfx(Sfx.HURT);
        }
        prevHealth = me.health;

        if (swingTime > 0) swingTime -= dt;
        if (p.particlesEnabled) particles.update(dt);

        double size = ps();
        camera.centerOn(me.x + size / 2.0, me.y + size / 2.0);
        walkAnim.update(me.moving ? dt : 0);

        // Cutscene triggers watch the player: zones fire on entry, INTERACT
        // ones on E (doors and stations already had their chance above).
        if (cutscenes != null) {
            boolean interact = input.isKeyJustPressed(KeyEvent.VK_E)
                    && craftingPanel == null && containerPanel == null && !showInventory;
            if (cutscenes.checkTriggers(me.x + size / 2.0, me.y + size / 2.0,
                    interact, ts(), camera.x, camera.y) != null) {
                if (world != null) world.cancelMining();
                ctx.sfx(Sfx.CLICK);
            }
        }
    }

    /**
     * Enter the door the player stands at: its {@link DoorLink} (from the game
     * type's external door directory) names another saved level, which loads
     * in place — inventory and health carry through, so a set of levels wired
     * with doors plays like one continuous world.
     */
    private boolean tryDoorTravel(GameProfile p) {
        double half = p.playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(me.x + half, me.y + half, ts() * 1.3);
        if (door == null) return false;
        DoorLink link = doors.get(door.type);
        if (link == null || link.targetLevel().isEmpty()) return true;
        LevelStore store = new LevelStore(p.name);
        if (!store.exists(link.targetLevel())) return true;
        level = store.load(link.targetLevel());
        world = new World(level);
        world.populateFromLevel(p);
        world.setPickupListener((player, key, count) -> {
            inventory.add(key, count);
            stats.add("items_picked_up", count);
            ctx.sfx(Sfx.PICKUP);
        });
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        cutscenes = new CutsceneDirector(level.cutscenes);
        me.x = level.spawnX;
        me.y = level.spawnY;
        me.vy = 0;
        setupLocalMinigame(); // the destination level may run its own mini game
        camera.tileSize = level.tileSize;
        parallax = null;
        particles.clear();
        ctx.sfx(Sfx.CLICK);
        return true;
    }

    /**
     * Standing near a crafting table / alchemy station / chest, E opens its
     * panel. Returns whether one opened (so E can fall through to mounting).
     */
    private boolean tryOpenStation(GameProfile p) {
        double ts = ts();
        int pc = (int) Math.floor((me.x + p.playerSize / 2.0) / ts);
        int pr = (int) Math.floor((me.y + p.playerSize / 2.0) / ts);
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                Block b = level.blockAt(pc + dc, pr + dr);
                if (b == null) continue;
                String station = switch (b.key()) {
                    case "crafting_table" -> Recipe.STATION_CRAFTING;
                    case "alchemy_station" -> Recipe.STATION_ALCHEMY;
                    default -> null;
                };
                if (station != null) {
                    craftingPanel = new CraftingPanel(station, RecipeRegistry.standard(),
                            world != null ? world.itemTypes : ItemRegistry.standard());
                    ctx.sfx(Sfx.CLICK);
                    return true;
                }
                if (b.container() && p.itemsEnabled) {
                    // The chest/barrel's second inventory, stored in the level.
                    // The player's inventory opens beside it (side by side)
                    // so moving stacks between the two is one screen.
                    containerPanel = new ContainerPanel(level, pc + dc, pr + dr,
                            b.displayName(),
                            world != null ? world.itemTypes : ItemRegistry.standard());
                    showInventory = true;
                    cursorSlot = -1;
                    ctx.sfx(Sfx.CLICK);
                    return true;
                }
            }
        }
        return false;
    }

    /** The nearest riderless snapshot vehicle within mounting range, or null. */
    private EntityView nearestSnapshotVehicle(Snapshot snap) {
        EntityView best = null;
        double bestD = World.MOUNT_RANGE;
        double half = ps() / 2;
        for (EntityView v : snap.vehicles()) {
            if (v.rider >= 0) continue;
            VehicleDef def = VehicleRegistry.standard().get(v.key);
            double size = def != null ? def.size() : ts();
            double d = Math.hypot(v.x + size / 2 - (me.x + half),
                    v.y + size / 2 - (me.y + half));
            if (d <= bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    /** Crafting overlay input: wheel scrolls it, clicking a lit recipe crafts. */
    private void updateCrafting(InputManager input) {
        CraftingPanel.Crafted crafted =
                craftingPanel.update(input, inventory, viewportWidth, viewportHeight);
        if (crafted == null) return;
        stats.add("crafts", 1);
        ctx.sfx(Sfx.PICKUP);
        if (crafted.leftover() > 0 && world != null) {
            DroppedItem drop = world.spawnItem(crafted.recipe().output(),
                    crafted.leftover(), me.x, me.y);
            if (drop != null) drop.pickupDelay = 1.0;
        }
        ItemDef out = (world != null ? world.itemTypes : ItemRegistry.standard())
                .get(crafted.recipe().output());
        ruleStatus = "Crafted " + (out != null ? out.name() : crafted.recipe().output());
        ruleStatusTime = 2.5;
    }

    private static String ruleFiredMessage(StatRule rule) {
        StringBuilder sb = new StringBuilder(PlayerStats.label(rule.stat()))
                .append(" reached ").append((long) rule.threshold());
        if (rule.consumeItem() != null) {
            sb.append(" — consumed ").append(rule.consumeCount())
                    .append("× ").append(rule.consumeItem());
        }
        if (rule.rewardItem() != null) {
            sb.append(" → +").append(rule.rewardCount())
                    .append("× ").append(rule.rewardItem());
        }
        return sb.toString();
    }

    // --- items & block interaction ------------------------------------------------

    private void updateInventoryControls(InputManager input, GameProfile p) {
        if (!p.itemsEnabled) {
            showInventory = false;
            cursorSlot = -1;
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_I)) {
            showInventory = !showInventory;
            cursorSlot = -1;
        }
        for (int k = 0; k < Inventory.HOTBAR; k++) {
            if (input.isKeyJustPressed(KeyEvent.VK_1 + k)) inventory.select(k);
        }
        int wheel = input.getWheelRotation();
        if (wheel != 0) inventory.scrollSelect(wheel > 0 ? 1 : -1);

        // Q tosses one item from the selected stack into the world.
        if (input.isKeyJustPressed(KeyEvent.VK_Q)) {
            dropStack(inventory.selectedIndex(), 1);
        }

        // F uses the selected item: deploy a vehicle item, fire a relic
        // active, or consume the food/potion. Online it's a request — the
        // server owns health, mana, the world, and the inventory, and pushes
        // the results back.
        if (input.isKeyJustPressed(KeyEvent.VK_F)) {
            ItemDef def = inventory.selectedDef();
            boolean edible = def != null && def.heal() > 0 && me.health < PlayerState.MAX_HEALTH;
            boolean manaDrink = def != null && "mana_potion".equals(def.key())
                    && me.mana < PlayerState.MAX_MANA;
            boolean relic = def != null && World.relicManaCost(def.key()) != null;
            VehicleDef vehDef = def == null ? null
                    : (world != null ? world.vehicleTypes : VehicleRegistry.standard())
                    .bySourceItem(def.key());
            if (net != null) {
                net.client().sendUseItem(inventory.selectedIndex());
                if (edible || manaDrink) ctx.sfx(Sfx.EAT);
                else if (relic) ctx.sfx(Sfx.BOOM);
                else if (vehDef != null) ctx.sfx(Sfx.PLACE);
            } else if (vehDef != null) {
                if (inventory.consumeSelected()) {
                    world.spawnVehicle(vehDef.key(),
                            me.x + (me.facingLeft ? -24 : 24), me.y);
                    ruleStatus = vehDef.name() + " deployed — [E] to ride";
                    ruleStatusTime = 3.0;
                    ctx.sfx(Sfx.PLACE);
                }
            } else if (relic) {
                if (world.useRelic(me, def.key(), p)) ctx.sfx(Sfx.BOOM);
            } else if (manaDrink && inventory.consumeSelected()) {
                me.mana = Math.min(PlayerState.MAX_MANA, me.mana + 50);
                ctx.sfx(Sfx.EAT);
            } else if (edible && inventory.consumeSelected()) {
                // Food heals directly, restores stamina alongside, and rare
                // delicacies also restore mana (World.applyFood).
                World.applyFood(me, def);
                prevHealth = me.health; // don't play the hurt sound on heals
                ctx.sfx(Sfx.EAT);
            }
        }

        if (showInventory) handleInventoryMouse(input);
    }

    /**
     * Mouse interaction with the open inventory: click a stack to pick it up,
     * click another slot to place it (merging same items, swapping different
     * ones), click outside the panel to drop it into the world. Online each
     * completed action becomes a request the server applies to its
     * authoritative copy (the local mirror applies it too, so the UI is
     * instant; the server's {@code inv} push confirms it).
     */
    private void handleInventoryMouse(InputManager input) {
        if (input.isRightMouseJustPressed()) {
            cursorSlot = -1; // put it back
            return;
        }
        if (!input.isMouseJustPressed()) return;
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0) {
            if (cursorSlot < 0) {
                if (inventory.slot(slot) != null) cursorSlot = slot;
            } else {
                moveStack(cursorSlot, slot);
                cursorSlot = -1;
            }
        } else if (cursorSlot >= 0) {
            // A click on the container panel beside the inventory is panel
            // interaction, not a toss-into-the-world.
            boolean overContainer = containerPanel != null
                    && containerPanel.contains(mouseX, mouseY, viewportWidth, viewportHeight);
            if (!insideInventoryPanel(mouseX, mouseY) && !overContainer) {
                ItemStack held = inventory.slot(cursorSlot);
                if (held != null) dropStack(cursorSlot, held.count);
            }
            cursorSlot = -1;
        }
    }

    private void moveStack(int from, int to) {
        if (from == to) return;
        if (inventory.move(from, to)) {
            ctx.sfx(Sfx.CLICK);
            if (net != null) net.client().sendInvMove(from, to);
        }
    }

    private void dropStack(int slot, int count) {
        ItemStack stack = inventory.slot(slot);
        if (stack == null || count <= 0) return;
        if (net != null) {
            // The server removes the items, spawns the drop, and pushes the
            // inventory back down.
            net.client().sendInvDrop(slot, count);
            ctx.sfx(Sfx.CLICK);
            return;
        }
        String key = stack.key;
        int removed = inventory.removeAt(slot, count);
        if (removed <= 0) return;
        DroppedItem drop = world.spawnItem(key, removed, me.x, me.y);
        if (drop != null) {
            drop.toss(me.facingLeft ? -170 : 170, -180);
            drop.pickupDelay = 1.0; // don't instantly vacuum it back up
        }
        ctx.sfx(Sfx.CLICK);
    }

    /**
     * Left click: fire the held ranged weapon / throwable (if projectiles are
     * on), else swing at mobs (if combat is on). <em>Holding</em> left over a
     * block in reach mines it over time — block durability, sped up by a
     * matching tool (offline; online mining stays a per-click server
     * request). Right click: place the selected hotbar block.
     */
    private void handleMouseActions(InputManager input, GameProfile p, PlayerInput in,
                                    double dt) {
        boolean leftClick = input.isMouseJustPressed();
        boolean rightClick = input.isRightMouseJustPressed();

        double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
        double ts = ts();
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (me.x + ps() / 2), aim[1] - (me.y + ps() / 2))
                <= REACH_TILES * ts;

        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        boolean shoots = p.projectilesEnabled && held != null && held.projectile() != null;

        // Hold-to-mine against block durability (offline world only).
        boolean miningNow = net == null && input.isMouseDown() && !shoots
                && p.blockEditingEnabled && inReach && level.tileAt(col, row) > 0;
        if (miningNow) {
            swingTime = Math.max(swingTime, 0.1);
            if (level.blockAt(col, row) == null) {
                // Legacy palette tile with no block definition: instant break.
                if (leftClick && level.setTile(col, row, 0)) {
                    stats.add("blocks_mined", 1);
                    ctx.sfx(Sfx.BREAK);
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts, Color.GRAY, 10);
                    }
                }
            } else {
                Block mined = world.continueMining(col, row, held, p.itemsEnabled, dt);
                if (mined != null) {
                    stats.add("blocks_mined", 1);
                    ctx.sfx(Sfx.BREAK);
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts, mined.color(), 10);
                    }
                    wearHeldTool(held);
                }
            }
        } else if (net == null && world != null) {
            world.cancelMining();
        }

        if (leftClick) {
            if (p.projectilesEnabled && ridingArmedVehicle() != null) {
                fireVehicleAt(aim[0], aim[1], in);
            } else if (shoots) {
                shootAt(aim[0], aim[1], in);
            } else if (net != null && p.blockEditingEnabled && inReach
                    && level.tileAt(col, row) > 0) {
                net.client().sendBlockEdit(col, row, 0, "play");
            } else if (!miningNow && net == null && inReach
                    && tryChopDecor(aim[0], aim[1], held, p)) {
                // harvested (or chipped at) a destructible decoration
            } else if (!miningNow && p.combatEnabled) {
                swingAt(aim[0], aim[1], in, p);
            }
        }
        if (rightClick && p.blockEditingEnabled && inReach) {
            placeAt(col, row, p);
        }
    }

    /** Wear the held tool one point on a finished block; report a break. */
    private void wearHeldTool(ItemDef held) {
        if (held == null || held.toolClass() == null || !profile().itemsEnabled) return;
        if (inventory.damageSelected(1)) {
            ctx.sfx(Sfx.BREAK);
            ruleStatus = held.name() + " broke!";
            ruleStatusTime = 2.5;
        }
    }

    /** Swing at a destructible decoration (trees → logs + leaves…). */
    private boolean tryChopDecor(double aimX, double aimY, ItemDef held, GameProfile p) {
        if (world == null) return false;
        boolean axe = held != null && "axe".equals(held.toolClass());
        World.ChopResult res = world.chopDecor(aimX, aimY, axe, p.itemsEnabled);
        if (res == World.ChopResult.NONE) return false;
        swingTime = 0.2;
        ctx.sfx(res == World.ChopResult.BROKEN ? Sfx.BREAK : Sfx.HIT);
        if (p.particlesEnabled) {
            particles.burst(aimX, aimY, new Color(110, 85, 50),
                    res == World.ChopResult.BROKEN ? 14 : 5);
        }
        return true;
    }

    private void placeAt(int col, int row, GameProfile p) {
        ItemDef def = p.itemsEnabled ? inventory.selectedDef() : null;
        if (p.itemsEnabled && (def == null || def.category() != ItemDef.Category.BLOCK)) {
            return; // nothing placeable selected
        }
        String blockKey = def != null ? def.blockKey() : "dirt";
        Block b = level.blocks.get(blockKey);
        // Empty cells and liquid cells accept placement — covering water with
        // a block is how pools are removed, since liquids can't be mined.
        if (b == null || (level.tileAt(col, row) != 0 && level.liquidAt(col, row) == null)) {
            return;
        }
        // Don't wall yourself in.
        double ts = ts();
        double size = ps();
        boolean overlapsMe = me.x + size > col * ts && me.x < (col + 1) * ts
                && me.y + size > row * ts && me.y < (row + 1) * ts;
        if (b.solid() && overlapsMe) return;

        if (net != null) {
            net.client().sendBlockEdit(col, row, b.id(), "play");
            return;
        }
        if (world.placeBlock(col, row, b.id())) {
            if (p.itemsEnabled) inventory.consumeSelected();
            stats.add("blocks_placed", 1);
            ctx.sfx(Sfx.PLACE);
        }
    }

    private void swingAt(double aimX, double aimY, PlayerInput in, GameProfile p) {
        swingTime = 0.2;
        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        double damage = World.FIST_DAMAGE + me.meleeBonus
                + (held != null ? held.damage() : 0);
        if (net != null) {
            in.attackAt(aimX, aimY); // the server resolves the hit
            return;
        }
        Mob hit = world.playerAttack(me, aimX, aimY, damage);
        if (hit != null) {
            ctx.sfx(Sfx.HIT);
            if (p.particlesEnabled) {
                particles.burst(hit.x + hit.def.size() / 2, hit.y + hit.def.size() / 2,
                        hit.def.body(), 8);
            }
        } else {
            // A whiffed swing near an empty vehicle packs it back into its item.
            Vehicle packed = world.packUpVehicle(aimX, aimY, p.itemsEnabled);
            if (packed != null) {
                ctx.sfx(Sfx.PICKUP);
                ruleStatus = packed.def.name() + " packed up";
                ruleStatusTime = 2.5;
            }
        }
    }

    /**
     * Fire the ridden vehicle's armament (a war dragon's fireball). Online the
     * shot rides the attack input — the server sees we're mounted and fires
     * the vehicle's weapon; offline the local world does the same thing.
     */
    private void fireVehicleAt(double aimX, double aimY, PlayerInput in) {
        swingTime = 0.1;
        if (net != null) {
            in.attackAt(aimX, aimY);
            ctx.sfx(Sfx.SHOOT); // predicted; the server validates the cooldown
            return;
        }
        Vehicle v = world.vehicle(me.riding);
        if (v != null && world.vehicleShoot(v, me, aimX, aimY) != null) {
            stats.add("shots_fired", 1);
            ctx.sfx(Sfx.SHOOT);
        }
    }

    /**
     * Fire the held ranged weapon / throwable toward the aim point. Online the
     * shot rides the attack input — the server sees the held item and spawns
     * (and owns) the projectile; offline the local world does the same thing.
     */
    private void shootAt(double aimX, double aimY, PlayerInput in) {
        swingTime = 0.1;
        if (net != null) {
            in.attackAt(aimX, aimY);
            ItemDef held = inventory.selectedDef();
            boolean hasAmmo = held != null
                    && (held.ammo() == null || inventory.totalOf(held.ammo()) > 0);
            if (hasAmmo) ctx.sfx(Sfx.SHOOT); // predicted; the server validates
            return;
        }
        if (world.playerShoot(me, inventory, aimX, aimY) != null) {
            stats.add("shots_fired", 1);
            ctx.sfx(Sfx.SHOOT);
        }
    }

    /**
     * Particles + sound for a world impact (local or replicated): projectile
     * hits styled by their element, plus the ability/relic FX keys the World
     * emits — blinks, summons, warps, novas, tremors, chain arcs, revives.
     */
    private void impactFeedback(World.Impact im, GameProfile p) {
        boolean fx = p.particlesEnabled;
        switch (im.key()) {
            case "blink" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(170, 140, 255), 10,
                        Particles.Style.IMPLODE);
                return;
            }
            case "warp" -> {
                ctx.sfx(Sfx.PICKUP);
                if (fx) {
                    particles.burst(im.x(), im.y(), new Color(200, 150, 255), 16,
                            Particles.Style.IMPLODE);
                    particles.burst(im.x(), im.y(), new Color(240, 220, 255), 8,
                            Particles.Style.MOTES);
                }
                return;
            }
            case "summon" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(150, 230, 160), 12,
                        Particles.Style.MOTES);
                return;
            }
            case "chain" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(255, 245, 150), 14,
                        Particles.Style.SPARKS);
                return;
            }
            case "nova" -> {
                ctx.sfx(Sfx.BOOM);
                if (fx) {
                    particles.burst(im.x(), im.y(), new Color(140, 220, 255), 30,
                            Particles.Style.RING);
                    particles.burst(im.x(), im.y(), Color.WHITE, 10, Particles.Style.SPARKS);
                }
                return;
            }
            case "tremor" -> {
                ctx.sfx(Sfx.BREAK);
                if (fx) particles.burst(im.x(), im.y(), new Color(170, 140, 95), 18,
                        Particles.Style.SHARDS);
                return;
            }
            case "revive" -> {
                ctx.sfx(Sfx.PICKUP);
                if (fx) particles.burst(im.x(), im.y(), new Color(255, 190, 80), 24,
                        Particles.Style.FOUNTAIN);
                return;
            }
            case "mount" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(220, 220, 230), 6);
                return;
            }
            default -> { /* a projectile impact: styled below */ }
        }
        ProjectileDef def = projectileTypes().get(im.key());
        Color color = def == null ? Color.GRAY
                : def.glows() ? def.lightColor() : def.color();
        if (im.explosion()) {
            ctx.sfx(Sfx.BOOM);
            if (fx) {
                particles.burst(im.x(), im.y(), color, 18, Particles.Style.RING);
                particles.burst(im.x(), im.y(), color, 12);
                particles.burst(im.x(), im.y(), new Color(255, 225, 130), 10);
            }
        } else {
            ctx.sfx(Sfx.HIT);
            if (fx) {
                particles.burst(im.x(), im.y(), color, 6,
                        def == null ? Particles.Style.BURST : elementStyle(def.element()));
            }
        }
    }

    /** The particle style an elemental school's impacts read as. */
    private static Particles.Style elementStyle(ProjectileDef.Element element) {
        return switch (element) {
            case FIRE -> Particles.Style.EMBERS;
            case ICE -> Particles.Style.SHARDS;
            case LIGHTNING -> Particles.Style.SPARKS;
            case POISON -> Particles.Style.DRIP;
            case ARCANE -> Particles.Style.MOTES;
            case VOID -> Particles.Style.IMPLODE;
            case EARTH -> Particles.Style.SHARDS;
            case NONE -> Particles.Style.BURST;
        };
    }

    // Cadence for ambient status particles (embers off burning mobs…).
    private double statusEmitClock;

    /**
     * Ambient particles for status-afflicted mobs: burning mobs shed embers,
     * poisoned ones drip, chilled ones glint — sourced from the offline world
     * or the latest snapshot's status bits, so online players see the same
     * burning zombie the host does.
     */
    private void emitStatusParticles(double dt) {
        statusEmitClock += dt;
        if (statusEmitClock < 0.12) return;
        statusEmitClock = 0;
        if (net == null) {
            for (Mob m : world.mobs()) {
                emitStatusFor(m.statusBits(), m.x + m.def.size() / 2,
                        m.y + m.def.size() / 2);
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap == null) return;
            MobRegistry mobs = MobRegistry.standard();
            for (EntityView mv : snap.mobs()) {
                MobDef def = mobs.get(mv.key);
                double half = def != null ? def.size() / 2 : 14;
                emitStatusFor(mv.status, mv.x + half, mv.y + half);
            }
        }
    }

    private void emitStatusFor(int bits, double cx, double cy) {
        if ((bits & Mob.STATUS_BURNING) != 0) {
            particles.burst(cx, cy, new Color(255, 150, 60), 2, Particles.Style.EMBERS);
        }
        if ((bits & Mob.STATUS_POISONED) != 0) {
            particles.burst(cx, cy, new Color(150, 210, 80), 1, Particles.Style.DRIP);
        }
        if ((bits & Mob.STATUS_CHILLED) != 0) {
            particles.burst(cx, cy, new Color(190, 235, 255), 1, Particles.Style.MOTES);
        }
    }

    /** One spark per tick behind projectiles that define a trail colour. */
    private void emitTrail(String key, double x, double y) {
        ProjectileDef def = projectileTypes().get(key);
        if (def != null && def.trail() != null) particles.burst(x, y, def.trail(), 1);
    }

    private ProjectileRegistry projectileTypes() {
        return world != null ? world.projectileTypes : ProjectileRegistry.standard();
    }

    /** Online-only: turn server broadcasts into local feedback + inventory sync. */
    private void consumeNetFeedback() {
        GameClient client = net.client();
        for (int[] e : client.pollBlockEvents()) {
            if (e[2] == 0) {
                ctx.sfx(Sfx.BREAK);
                if (profile().particlesEnabled) {
                    particles.burst((e[0] + 0.5) * ts(), (e[1] + 0.5) * ts(),
                            new Color(160, 150, 140), 8);
                }
            } else {
                ctx.sfx(Sfx.PLACE);
            }
        }
        for (World.Impact im : client.pollFxEvents()) {
            impactFeedback(im, profile());
        }
        if (client.inventoryVersion() != invSyncVersion) {
            invSyncVersion = client.inventoryVersion();
            inventory.fromList(client.inventoryData());
        }
        // The server owns health online.
        Snapshot snap = client.latest();
        PlayerState server = snap != null ? snap.player(me.id) : null;
        if (server != null) me.health = server.health;
    }

    /**
     * Drive whatever this player is riding for one tick — instead of player
     * physics. Offline the world's own vehicle is driven directly; online a
     * predicted copy runs the same deterministic step and is blended toward
     * the snapshot, exactly like the player's own prediction. Returns whether
     * the player is mounted (and was moved by the vehicle).
     */
    private boolean stepRiding(PlayerInput in, GameProfile p, double dt) {
        if (net == null) {
            if (me.riding < 0 || world == null) return false;
            Vehicle v = world.vehicle(me.riding);
            if (v == null) {
                me.riding = -1; // erased under us (creative delete)
                return false;
            }
            world.driveVehicle(v, me, in, p, dt);
            return true;
        }
        Snapshot snap = net.client().latest();
        EntityView rv = snap == null ? null : snap.vehicleRiddenBy(me.id);
        VehicleDef def = rv == null ? null : VehicleRegistry.standard().get(rv.key);
        if (def == null) {
            predictedVehicle = null;
            return false;
        }
        if (predictedVehicle == null || predictedVehicle.id != rv.id) {
            predictedVehicle = new Vehicle(rv.id, def, rv.x, rv.y);
        }
        predictedVehicle.riderId = me.id;
        // Same gravity rule the server's world uses for vehicle physics.
        boolean gravityOn = p.gravityEnabled
                && level.perspective == Perspective.SIDE_SCROLL;
        predictedVehicle.stepDriven(level, in, gravityOn, dt);
        double ex = rv.x - predictedVehicle.x;
        double ey = rv.y - predictedVehicle.y;
        if (ex * ex + ey * ey > SNAP_DISTANCE * SNAP_DISTANCE) {
            predictedVehicle.x = rv.x;
            predictedVehicle.y = rv.y;
        } else {
            double k = Math.min(1.0, CORRECTION_PER_SEC * dt);
            predictedVehicle.x += ex * k;
            predictedVehicle.y += ey * k;
        }
        predictedVehicle.seat(me, p.playerSize);
        return true;
    }

    /** The armed vehicle under this player, or {@code null} (unarmed / on foot). */
    private VehicleDef ridingArmedVehicle() {
        if (net == null) {
            if (me.riding < 0 || world == null) return null;
            Vehicle v = world.vehicle(me.riding);
            return v != null && v.def.projectile() != null ? v.def : null;
        }
        return predictedVehicle != null && predictedVehicle.def.projectile() != null
                ? predictedVehicle.def : null;
    }

    /**
     * Blend the predicted local player toward the server's authoritative state.
     * Small errors (network jitter, sampling differences) are smoothed away;
     * large ones (teleport, heavy lag) snap. While mounted, the vehicle's own
     * prediction blend does this job instead.
     */
    private void reconcile(double dt) {
        if (predictedVehicle != null) return;
        Snapshot snap = net.client().latest();
        if (snap == null) return;
        PlayerState server = snap.player(me.id);
        if (server == null) return;

        double ex = server.x - me.x;
        double ey = server.y - me.y;
        if (ex * ex + ey * ey > SNAP_DISTANCE * SNAP_DISTANCE) {
            me.x = server.x;
            me.y = server.y;
            me.vy = server.vy;
        } else {
            double k = Math.min(1.0, CORRECTION_PER_SEC * dt);
            me.x += ex * k;
            me.y += ey * k;
        }
    }

    private void advanceRemoteAnimations(double dt) {
        Snapshot snap = net.client().latest();
        if (snap == null) return;
        for (PlayerState ps : snap.players()) {
            if (ps.id == me.id) continue;
            Animation anim = remoteAnims.computeIfAbsent(ps.id, this::buildRemoteAnimation);
            anim.update(ps.moving ? dt : 0);
        }
        remoteAnims.keySet().removeIf(id -> snap.player(id) == null && id != me.id);
    }

    private void updatePaused(double dt, InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            resume();
            return;
        }
        pauseForm.update(dt, input);
        // Apply settings that affect the engine (e.g. FPS cap, shaders) live.
        ctx.applyLiveSettings();
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        GameProfile p = profile();
        feedLighting(p);

        g.setColor(level.background);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        if (p.parallaxEnabled && camera.getPerspective() == Perspective.SIDE_SCROLL) {
            if (parallax == null) {
                parallax = new ParallaxBackground(level.background, level.name.hashCode());
            }
            parallax.render(g, camera.x, camera.y, viewportWidth, viewportHeight);
        }

        drawDecorLayer(g, false); // background scenery behind the terrain
        SurfaceDecorPainter.draw(g, level, camera, visibleTileBounds(), false, animClock);
        drawTiles(g);
        if (net == null) drawMiningCracks(g);
        if (p.gridVisible && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawDoors(g);
        drawWorldEntities(g, p);
        if (mgView != null) MiniGameHud.drawWorld(g, camera, level, mgView, animClock);
        if (net != null) drawRemotePlayers(g);
        if (mgView != null) {
            MiniGameHud.drawTeamRing(g, camera, me.x + ps() / 2, me.y + ps(),
                    ps(), mgView.teamOf(me.id), camera.zoom);
        }
        drawPlayer(g, me.x, me.y, me.facingLeft, walkAnim.current(), null);
        if (swingTime > 0) drawSwing(g);
        if (cutscenes != null && cutscenes.active() != null) {
            CutscenePainter.drawActors(g, camera, cutscenes.active());
        }
        drawDecorLayer(g, true); // foreground scenery covers players
        SurfaceDecorPainter.draw(g, level, camera, visibleTileBounds(), true, animClock);
        if (p.particlesEnabled) particles.render(g, camera);
        if (net == null) drawDoorHint(g, p);
        drawVehicleHint(g, p);
        if (p.hudVisible) drawHud(g);
        if (mgView != null) MiniGameHud.drawHud(g, viewportWidth, viewportHeight, mgView, me.id);
        if (p.itemsEnabled) drawHotbar(g);
        if (p.combatEnabled || p.mobsEnabled) drawHealthBar(g);
        drawResourceBars(g);
        if (net == null) drawStatRuleBars(g);
        drawRuleStatus(g);
        if (net != null) drawEvents(g);
        if (showInventory) drawInventory(g);
        if (craftingPanel != null) {
            craftingPanel.render(g, viewportWidth, viewportHeight, inventory, animClock);
        }
        if (containerPanel != null) {
            containerPanel.render(g, viewportWidth, viewportHeight, animClock);
        }
        if (cutscenes != null && cutscenes.active() != null) {
            CutscenePainter.drawOverlay(g, viewportWidth, viewportHeight, cutscenes.active());
        }

        if (paused) drawPauseOverlay(g);
        if (net != null && !net.client().isConnected()) drawDisconnectOverlay(g);
    }

    /** Crack overlay on the block being held-mined, scaled by progress. */
    private void drawMiningCracks(Graphics2D g) {
        if (world == null) return;
        int[] cell = world.miningCell();
        double progress = world.miningProgress();
        if (cell == null || progress <= 0.01) return;
        double ts = ts();
        camera.worldToScreen(cell[0] * ts, cell[1] * ts, corner);
        int x = corner[0], y = corner[1];
        camera.worldToScreen((cell[0] + 1) * ts, (cell[1] + 1) * ts, corner);
        int w = Math.abs(corner[0] - x), h = Math.abs(corner[1] - y);
        int cx = x + w / 2, cy = y + h / 2;
        g.setColor(new Color(20, 16, 12, 200));
        g.setStroke(new BasicStroke(Math.max(1f, w / 22f)));
        int cracks = 2 + (int) (progress * 6);
        for (int i = 0; i < cracks; i++) {
            double a = i * (Math.PI * 2 / 8) + (cell[0] * 3 + cell[1] * 7) % 7 * 0.4;
            double len = (0.2 + progress * 0.42) * w;
            int mx = cx + (int) (Math.cos(a) * len * 0.55);
            int my = cy + (int) (Math.sin(a) * len * 0.55);
            g.drawLine(cx, cy, mx, my);
            g.drawLine(mx, my, mx + (int) (Math.cos(a + 0.6) * len * 0.45),
                    my + (int) (Math.sin(a + 0.6) * len * 0.45));
        }
    }

    /** Stamina (green) and mana (blue) bars stacked above the health bar. */
    private void drawResourceBars(Graphics2D g) {
        int w = 180, h = 8;
        int x = 12;
        drawResourceBar(g, x, viewportHeight - 40, w, h,
                me.stamina / PlayerState.MAX_STAMINA,
                new Color(40, 90, 40), new Color(110, 220, 110));
        drawResourceBar(g, x, viewportHeight - 52, w, h,
                me.mana / PlayerState.MAX_MANA,
                new Color(35, 45, 100), new Color(100, 140, 245));
    }

    private void drawResourceBar(Graphics2D g, int x, int y, int w, int h,
                                 double t, Color back, Color front) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 5, 5);
        g.setColor(back);
        g.fillRect(x, y, w, h);
        g.setColor(front);
        g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, t))), h);
    }

    /** The level's programmable stat bars (rules marked "show bar"), top-right. */
    private void drawStatRuleBars(Graphics2D g) {
        if (ruleEngine == null || stats == null || level.statRules.isEmpty()) return;
        int w = 170, h = 10;
        int x = viewportWidth - w - 14, y = 56;
        g.setFont(SMALL_FONT);
        for (StatRule rule : level.statRules) {
            if (!rule.showBar()) continue;
            double t = ruleEngine.progress(rule, stats);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x - 4, y - 13, w + 8, h + 18, 6, 6);
            g.setColor(new Color(210, 210, 225));
            g.drawString(PlayerStats.label(rule.stat()) + "  "
                    + (long) stats.get(rule.stat()) + " / " + (long) rule.threshold(), x, y - 3);
            g.setColor(new Color(70, 60, 30));
            g.fillRect(x, y, w, h);
            g.setColor(t >= 1 ? new Color(150, 230, 150) : new Color(240, 200, 90));
            g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, t))), h);
            y += h + 22;
        }
    }

    /** Transient "rule fired / crafted" toast above the hotbar. */
    private void drawRuleStatus(Graphics2D g) {
        if (ruleStatusTime <= 0 || ruleStatus.isEmpty()) return;
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(ruleStatus);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 110;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8);
        g.setColor(new Color(200, 240, 200));
        g.drawString(ruleStatus, x, y);
    }

    /**
     * Feed this frame's lighting to the shared {@link LightingPass}: darkness
     * from the time of day (server time online, local world offline), plus
     * every light-emitting block on screen and a small glow around players so
     * night stays navigable. The pass runs inside the shader chain, so this
     * works with (and under) every other enabled effect.
     */
    private void feedLighting(GameProfile p) {
        LightingPass lighting = ctx.lighting();
        if (!p.lightingEnabled) {
            lighting.setDarkness(0);
            return;
        }
        double darkness;
        if (net != null) {
            Snapshot snap = net.client().latest();
            double time = snap != null ? snap.timeOfDay() : 0.25;
            darkness = World.darknessFor(time, p);
        } else {
            darkness = world.darkness(p);
        }
        // Menus stay readable: the world dims, the pause overlay doesn't.
        lighting.setDarkness(paused ? 0 : darkness);
        lighting.setAmbient(p.ambientLight);
        lighting.clearLights();
        if (darkness <= 0.001 || paused) return;

        double ts = ts();
        int[] b = visibleTileBounds();
        for (int r = b[1]; r <= b[3]; r++) {
            for (int c = b[0]; c <= b[2]; c++) {
                Block block = level.blockAt(c, r);
                if (block == null || !block.emitsLight()) continue;
                camera.worldToScreen((c + 0.5) * ts, (r + 0.5) * ts, corner);
                lighting.addLight(corner[0], corner[1],
                        block.lightRadius() * ts * camera.zoom, block.lightColor());
            }
        }
        // Coloured rarity lighting: uncommon+ drops shine with their tier's
        // colour after dark (matching their daylight halo sprite).
        if (net == null) {
            for (DroppedItem item : world.items()) {
                ItemDef def = world.itemTypes.get(item.key);
                if (def == null || def.rarity() == ItemDef.Rarity.COMMON) continue;
                camera.worldToScreen(item.x + DroppedItem.SIZE / 2,
                        item.y + DroppedItem.SIZE / 2, corner);
                lighting.addLight(corner[0], corner[1],
                        (1.0 + def.rarity().ordinal() * 0.6) * ts * camera.zoom,
                        def.rarity().color);
            }
        }
        // Glowing projectiles (fireballs, magic bolts) carry their own light —
        // they ride the same lighting pass, so at night a fireball lights the
        // terrain it flies over (and bloom, if enabled, blooms it).
        if (net == null) {
            for (Projectile pr : world.projectiles()) {
                addProjectileLight(lighting, pr.def, pr.x, pr.y, ts);
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                for (EntityView s : snap.shots()) {
                    addProjectileLight(lighting, projectileTypes().get(s.key), s.x, s.y, ts);
                }
            }
        }
        // Player glow.
        camera.worldToScreen(me.x + ps() / 2, me.y + ps() / 2, corner);
        lighting.addLight(corner[0], corner[1], 2.5 * ts * camera.zoom,
                new Color(255, 240, 210));
    }

    private void addProjectileLight(LightingPass lighting, ProjectileDef def,
                                    double x, double y, double ts) {
        if (def == null || !def.glows()) return;
        camera.worldToScreen(x, y, corner);
        lighting.addLight(corner[0], corner[1],
                def.lightRadius() * ts * camera.zoom, def.lightColor());
    }

    // --- pause ---

    private void openPause() {
        paused = true;
        if (pauseForm == null) buildPauseForm();
        // Online, the server keeps applying the latest input command — send an
        // idle one so the player doesn't keep walking while the menu is open.
        if (net != null) {
            net.client().sendInput(new PlayerInput(false, false, false, false, ++inputSeq));
        }
    }

    private void resume() {
        paused = false;
        syncCameraFromProfile();
    }

    private void buildPauseForm() {
        GameProfile p = profile();
        pauseForm = new ConfigForm("Paused — " + p.name).theme(MenuTheme.dark());
        if (net == null) {
            // A level's feature toggles are edited in Load Level → Edit
            // Settings, not here — the pause menu stays simple.
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Save Level", this::saveLevel);
            pauseForm.addAction("Edit in Creative",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction("Quit to Menu", () -> scenes.transitionTo("menu"));
        } else {
            // Online the server owns the world.
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Edit in Creative",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction(net.isHost() ? "Stop Server & Quit" : "Disconnect & Quit", () -> {
                ctx.closeSession();
                net = null;
                scenes.transitionTo("menu");
            });
        }
    }

    /**
     * Save the current level — its terrain, entities, and the settings it's
     * playing with — into the game type's folder, so this play state reloads
     * next time. Its feature toggles are edited elsewhere (Load Level → Edit
     * Settings); here we just persist them alongside the world as-is.
     */
    private void saveLevel() {
        GameProfile p = profile();
        p.normalize();
        level.settings = p.copy();
        LevelStore store = new LevelStore(p.name);
        Path file = store.save(level);
        p.lastLevelPath = file.toString();
        ctx.save();
        ruleStatus = "Saved level \"" + level.name + "\"";
        ruleStatusTime = 3.0;
    }

    private void drawPauseOverlay(Graphics2D g) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f));
        g.setColor(new Color(12, 12, 18));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        g.setComposite(old);

        pauseForm.render(g, viewportWidth, viewportHeight);
        g.setColor(new Color(120, 120, 140));
        g.setFont(HUD_FONT);
        g.drawString(net == null
                        ? "Esc to resume · Save Level keeps this world; edit toggles in Load Level → Edit Settings"
                        : "Esc to resume · game keeps running on the server while paused",
                24, viewportHeight - 24);
    }

    private void drawDisconnectOverlay(Graphics2D g) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
        g.setColor(new Color(20, 10, 12));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        g.setComposite(old);

        g.setColor(new Color(235, 120, 110));
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        String title = "Disconnected";
        g.drawString(title, (viewportWidth - g.getFontMetrics().stringWidth(title)) / 2,
                viewportHeight / 2 - 20);

        String reason = net.client().disconnectReason();
        if (reason != null) {
            g.setFont(HUD_FONT);
            g.setColor(new Color(200, 190, 190));
            g.drawString(reason, (viewportWidth - g.getFontMetrics().stringWidth(reason)) / 2,
                    viewportHeight / 2 + 8);
        }
        g.setFont(HUD_FONT);
        g.setColor(new Color(150, 150, 160));
        String hint = "Press Enter to return to the menu";
        g.drawString(hint, (viewportWidth - g.getFontMetrics().stringWidth(hint)) / 2,
                viewportHeight / 2 + 40);
    }

    // --- profile-driven constraints ---

    /**
     * The perspective this session simulates and renders in by default:
     * offline it's the loaded level's own perspective, online the profile's
     * (physics must match the server's view of the world).
     */
    private Perspective basePerspective() {
        return net == null ? level.perspective : profile().perspective;
    }

    private void enforceProfileConstraints(GameProfile p) {
        if (!p.perspectiveSwitchingEnabled) camera.setPerspective(basePerspective());
        camera.zoom = p.zoomEnabled ? clampZoom(camera.zoom, p) : clampZoom(p.defaultZoom, p);
        // Player sprite tracks the configured size.
        if (walkAnim == null || walkAnim.frameCount() == 0) rebuildSprite();
    }

    private void syncCameraFromProfile() {
        GameProfile p = profile();
        camera.tileSize = level.tileSize;
        if (!p.perspectiveSwitchingEnabled) camera.setPerspective(basePerspective());
        camera.zoom = clampZoom(p.zoomEnabled ? camera.zoom : p.defaultZoom, p);
        rebuildSprite();
    }

    private double clampZoom(double z, GameProfile p) {
        return Math.max(p.minZoom, Math.min(p.maxZoom, z));
    }

    // --- rendering helpers ---

    /**
     * The tile-index rectangle that can possibly be visible, found by
     * inverse-projecting the viewport corners into world space (for isometric,
     * the corners map to a diamond; its bounding box is a conservative cover).
     * Rendering cost then scales with the screen, not the level size.
     */
    private int[] visibleTileBounds() {
        double ts = ts();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        int[][] cornersPx = {{0, 0}, {viewportWidth, 0}, {0, viewportHeight}, {viewportWidth, viewportHeight}};
        for (int[] c : cornersPx) {
            double[] wp = camera.screenToWorld(c[0], c[1]);
            minX = Math.min(minX, wp[0]);
            maxX = Math.max(maxX, wp[0]);
            minY = Math.min(minY, wp[1]);
            maxY = Math.max(maxY, wp[1]);
        }
        int col0 = Math.max(0, (int) Math.floor(minX / ts) - 1);
        int col1 = Math.min(level.width - 1, (int) Math.floor(maxX / ts) + 1);
        int row0 = Math.max(0, (int) Math.floor(minY / ts) - 1);
        int row1 = Math.min(level.height - 1, (int) Math.floor(maxY / ts) + 1);
        return new int[]{col0, row0, col1, row1};
    }

    private void drawTiles(Graphics2D g) {
        int ts = (int) ts();
        int[] b = visibleTileBounds();
        boolean flat = camera.getPerspective() != Perspective.ISOMETRIC;
        tileSkins.clear();
        for (int r = b[1]; r <= b[3]; r++) {
            for (int c = b[0]; c <= b[2]; c++) {
                int id = level.tileAt(c, r);
                if (id <= 0) continue;
                Block block = level.blockAt(c, r);
                double wx = c * ts, wy = r * ts;
                camera.worldToScreen(wx, wy, corner);
                xs[0] = corner[0]; ys[0] = corner[1];
                camera.worldToScreen(wx + ts, wy, corner);
                xs[1] = corner[0]; ys[1] = corner[1];
                camera.worldToScreen(wx + ts, wy + ts, corner);
                xs[2] = corner[0]; ys[2] = corner[1];
                camera.worldToScreen(wx, wy + ts, corner);
                xs[3] = corner[0]; ys[3] = corner[1];

                // The open chest/barrel gets an animated lid drawn over it.
                boolean openLid = containerPanel != null && block != null
                        && block.container()
                        && c == containerPanel.col() && r == containerPanel.row();

                // Sprite-sheet texture override, when one is assigned —
                // isometric view warps the same texture into the tile diamond.
                if (block != null) {
                    BufferedImage skin = tileSkinFor(id, block);
                    if (skin != null) {
                        com.larsons.engine.graphics.TilePainter.drawTexture(
                                g, skin, xs, ys, flat);
                        if (openLid) {
                            ContainerPanel.drawLid(g, xs, ys,
                                    containerPanel.openness(), level.colorFor(id));
                        }
                        continue;
                    }
                }

                Color col = level.colorFor(id);
                g.setColor(col);
                g.fillPolygon(xs, ys, 4);
                if (block != null && block.liquid()) {
                    // Liquids render translucent with a bright surface line.
                    if (level.liquidAt(c, r - 1) == null) {
                        g.setColor(new Color(255, 255, 255, 90));
                        g.drawLine(xs[0], ys[0], xs[1], ys[1]);
                    }
                } else {
                    g.setColor(col.darker());
                    g.drawPolygon(xs, ys, 4);
                }
                if (openLid) {
                    ContainerPanel.drawLid(g, xs, ys, containerPanel.openness(), col);
                }
            }
        }
    }

    private BufferedImage tileSkinFor(int id, Block block) {
        if (tileSkins.containsKey(id)) return tileSkins.get(id);
        BufferedImage img = Skins.frame("block/" + block.key(), animClock);
        tileSkins.put(id, img);
        return img;
    }

    /** One decoration layer: background behind the terrain, foreground over players. */
    private void drawDecorLayer(Graphics2D g, boolean foreground) {
        String kind = foreground ? "decor_fg" : "decor_bg";
        DecorRegistry registry = DecorRegistry.standard();
        for (Level.EntitySpawn e : level.entities) {
            if (!kind.equals(e.kind)) continue;
            Decor def = registry.get(e.type);
            if (def == null) continue;
            BufferedImage img = Skins.frame("decor/" + e.type, animClock);
            if (img == null) img = EntitySprites.decor(def, 64);
            int size = Math.max(8, (int) Math.round(def.sizeTiles() * ts() * camera.zoom));
            camera.worldToScreen(e.x, e.y, corner);
            g.drawImage(img, corner[0] - size / 2, corner[1] - size, size, size, null);
        }
    }

    /** Painted doors: tinted door shapes anchored at their base. */
    private void drawDoors(Graphics2D g) {
        double ts = ts();
        for (Level.EntitySpawn e : level.entities) {
            if (!"door".equals(e.kind)) continue;
            DoorLink link = doors.get(e.type);
            Color tint = link != null ? link.color() : new Color(150, 105, 60);
            int dw = Math.max(8, (int) Math.round(ts * 0.9 * camera.zoom));
            int dh = Math.max(12, (int) Math.round(ts * 1.6 * camera.zoom));
            camera.worldToScreen(e.x, e.y, corner);
            int x = corner[0] - dw / 2, y = corner[1] - dh;
            g.setColor(tint);
            g.fillRoundRect(x, y, dw, dh, dw / 3, dw / 3);
            g.setColor(tint.darker());
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, y, dw, dh, dw / 3, dw / 3);
            g.setColor(new Color(255, 235, 170));
            int knob = Math.max(2, dw / 6);
            g.fillOval(x + dw - knob * 2, y + dh / 2, knob, knob);
        }
    }

    /** "[E] Enter …" prompt while standing at a linked door. */
    private void drawDoorHint(Graphics2D g, GameProfile p) {
        double half = p.playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(me.x + half, me.y + half, ts() * 1.3);
        if (door == null) return;
        DoorLink link = doors.get(door.type);
        String text = link == null || link.targetLevel().isEmpty()
                ? "This door leads nowhere (yet)"
                : "[E] Enter " + link.label();
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(text);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 88;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8);
        g.setColor(new Color(255, 230, 160));
        g.drawString(text, x, y);
    }

    /** "[E] Ride …" / "[E] Dismount" prompt near vehicles, above the door hint. */
    private void drawVehicleHint(Graphics2D g, GameProfile p) {
        String text = null;
        if (net == null) {
            if (world == null) return;
            if (me.riding >= 0) {
                Vehicle v = world.vehicle(me.riding);
                if (v != null) {
                    text = "[E] Dismount " + v.def.name()
                            + (v.def.projectile() != null ? " · click fires" : "");
                }
            } else {
                Vehicle near = world.mountableNear(me.x + ps() / 2, me.y + ps() / 2);
                if (near != null) text = "[E] Ride " + near.def.name();
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap == null) return;
            if (predictedVehicle != null) {
                text = "[E] Dismount " + predictedVehicle.def.name()
                        + (predictedVehicle.def.projectile() != null ? " · click fires" : "");
            } else {
                EntityView near = nearestSnapshotVehicle(snap);
                VehicleDef def = near == null ? null
                        : VehicleRegistry.standard().get(near.key);
                if (def != null) text = "[E] Ride " + def.name();
            }
        }
        if (text == null) return;
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(text);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 116;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8);
        g.setColor(new Color(170, 225, 255));
        g.drawString(text, x, y);
    }

    private void drawGrid(Graphics2D g) {
        int ts = (int) ts();
        int[] b = visibleTileBounds();
        g.setColor(new Color(255, 255, 255, 30));
        for (int c = b[0]; c <= b[2] + 1; c++) {
            double wx = c * ts;
            g.drawLine(camera.worldToScreenX(wx, b[1] * ts), camera.worldToScreenY(wx, b[1] * ts),
                    camera.worldToScreenX(wx, (b[3] + 1) * ts), camera.worldToScreenY(wx, (b[3] + 1) * ts));
        }
        for (int r = b[1]; r <= b[3] + 1; r++) {
            double wy = r * ts;
            g.drawLine(camera.worldToScreenX(b[0] * ts, wy), camera.worldToScreenY(b[0] * ts, wy),
                    camera.worldToScreenX((b[2] + 1) * ts, wy), camera.worldToScreenY((b[2] + 1) * ts, wy));
        }
    }

    /** Mobs + items + projectiles + vehicles: the offline world's, or the snapshot's. */
    private void drawWorldEntities(Graphics2D g, GameProfile p) {
        if (net == null) {
            for (Vehicle v : world.vehicles()) {
                drawVehicleSprite(g, v.def, v.x, v.y, v.facingLeft);
            }
            for (DroppedItem item : world.items()) {
                drawItemSprite(g, item.key, item.x, item.y, item.count);
            }
            for (Mob m : world.mobs()) {
                drawMobSprite(g, m.def, m.x, m.y, m.facingLeft, m.health, m.hurting(),
                        stateKeyFor(m.state.ordinal(), m.hurting()), m.statusBits());
            }
            for (Projectile pr : world.projectiles()) {
                drawProjectileSprite(g, pr.def.key(), pr.x, pr.y, pr.vx, pr.vy);
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap == null) return;
            VehicleRegistry vehicles = VehicleRegistry.standard();
            for (EntityView v : snap.vehicles()) {
                // The vehicle we're riding renders from our own prediction so
                // it never lags behind the player glued to its saddle.
                if (predictedVehicle != null && v.id == predictedVehicle.id) continue;
                VehicleDef def = vehicles.get(v.key);
                if (def != null) drawVehicleSprite(g, def, v.x, v.y, v.facingLeft);
            }
            if (predictedVehicle != null) {
                drawVehicleSprite(g, predictedVehicle.def, predictedVehicle.x,
                        predictedVehicle.y, predictedVehicle.facingLeft);
            }
            for (EntityView item : snap.items()) {
                drawItemSprite(g, item.key, item.x, item.y, item.count);
            }
            MobRegistry mobs = MobRegistry.standard();
            for (EntityView mv : snap.mobs()) {
                MobDef def = mobs.get(mv.key);
                if (def != null) {
                    drawMobSprite(g, def, mv.x, mv.y, mv.facingLeft, mv.health, false,
                            stateKeyFor(mv.aiState, false), mv.status);
                }
            }
            for (EntityView s : snap.shots()) {
                drawProjectileSprite(g, s.key, s.x, s.y, s.vx, s.vy);
            }
        }
    }

    /** A vehicle, flipped to its facing like mobs are. */
    private void drawVehicleSprite(Graphics2D g, VehicleDef def, double x, double y,
                                   boolean facingLeft) {
        BufferedImage img = Skins.frame("vehicle/" + def.key(), animClock);
        if (img == null) img = EntitySprites.vehicle(def, 48);
        int w = (int) Math.round(def.size() * camera.zoom);
        camera.worldToScreen(x + def.size() / 2, y + def.size(), corner);
        int dx = corner[0] - w / 2;
        int dy = corner[1] - w;
        if (facingLeft) {
            g.drawImage(img, dx + w, dy, -w, w, null);
        } else {
            g.drawImage(img, dx, dy, w, w, null);
        }
    }

    /** Skin action state for a mob AI state ordinal (feeds {@code mob/<key>/<state>}). */
    private static String stateKeyFor(int aiStateOrdinal, boolean hurting) {
        if (hurting) return "hurt";
        return switch (aiStateOrdinal) {
            case 1, 2, 4 -> "walk";   // WANDER, CHASE, FLEE
            case 3 -> "attack";       // ATTACK
            default -> "idle";
        };
    }

    /** A projectile, rotated to its flight direction. */
    private void drawProjectileSprite(Graphics2D g, String key, double x, double y,
                                      double vx, double vy) {
        ProjectileDef def = projectileTypes().get(key);
        if (def == null) return;
        BufferedImage img = EntitySprites.projectile(def, 16);
        int w = Math.max(8, (int) Math.round(def.radius() * 3.5 * camera.zoom));
        camera.worldToScreen(x, y, corner);
        AffineTransform old = g.getTransform();
        g.translate(corner[0], corner[1]);
        if (vx != 0 || vy != 0) g.rotate(Math.atan2(vy, vx));
        g.drawImage(img, -w / 2, -w / 2, w, w, null);
        g.setTransform(old);
    }

    private void drawMobSprite(Graphics2D g, MobDef def, double x, double y,
                               boolean facingLeft, double health, boolean hurt,
                               String state, int statusBits) {
        BufferedImage img = Skins.frame("mob/" + def.key() + "/" + state, animClock);
        if (img == null && !"idle".equals(state)) {
            img = Skins.frame("mob/" + def.key() + "/idle", animClock);
        }
        if (img == null) img = EntitySprites.mob(def, 32);
        int w = (int) Math.round(def.size() * camera.zoom);
        camera.worldToScreen(x + def.size() / 2, y + def.size(), corner);
        int dx = corner[0] - w / 2;
        int dy = corner[1] - w;
        if (facingLeft) {
            g.drawImage(img, dx + w, dy, -w, w, null);
        } else {
            g.drawImage(img, dx, dy, w, w, null);
        }
        if (hurt) {
            g.setColor(new Color(255, 60, 60, 90));
            g.fillRect(dx, dy, w, w);
        }
        // Elemental status tints (replicated bits, so online matches offline).
        if ((statusBits & Mob.STATUS_BURNING) != 0) {
            g.setColor(new Color(255, 130, 40, 70));
            g.fillRect(dx, dy, w, w);
        }
        if ((statusBits & Mob.STATUS_CHILLED) != 0) {
            g.setColor(new Color(120, 200, 255, 80));
            g.fillRect(dx, dy, w, w);
        }
        if ((statusBits & Mob.STATUS_POISONED) != 0) {
            g.setColor(new Color(120, 210, 80, 65));
            g.fillRect(dx, dy, w, w);
        }
        if ((statusBits & Mob.STATUS_SHIELDED) != 0) {
            g.setColor(new Color(120, 230, 255, 180));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(dx - 3, dy - 3, w + 6, w + 6);
        }
        if (health < def.maxHealth() - 0.01) {
            int bw = Math.max(14, w);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRect(dx + w / 2 - bw / 2, dy - 7, bw, 4);
            g.setColor(new Color(90, 220, 90));
            g.fillRect(dx + w / 2 - bw / 2, dy - 7,
                    (int) (bw * Math.max(0, health / def.maxHealth())), 4);
        }
    }

    private void drawItemSprite(Graphics2D g, String key, double x, double y, int count) {
        ItemDef def = (world != null ? world.itemTypes : ItemRegistry.standard()).get(key);
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + key, animClock);
        if (img == null) img = EntitySprites.item(def, 16);
        int w = Math.max(6, (int) Math.round(DroppedItem.SIZE * camera.zoom));
        camera.worldToScreen(x, y, corner);
        int dy = 0;
        if (camera.getPerspective() != Perspective.SIDE_SCROLL) {
            // Top-down / isometric drops hover with a bob over a soft shadow
            // (side-scroll drops bounce physically instead).
            dy = (int) Math.round(Math.sin(animClock * 3 + (x + y) * 0.05) * w * 0.18
                    - w * 0.25);
            g.setColor(new Color(0, 0, 0, 70));
            g.fillOval(corner[0], corner[1] + w - w / 4, w, w / 2);
        }
        drawRarityHalo(g, def, corner[0] + w / 2, corner[1] + dy + w / 2, w);
        g.drawImage(img, corner[0], corner[1] + dy, w, w, null);
        if (count > 1) {
            g.setFont(SMALL_FONT);
            g.setColor(Color.WHITE);
            g.drawString("x" + count, corner[0] + w, corner[1] + dy + w);
        }
    }

    /**
     * The coloured halo behind an uncommon+ dropped item: a soft radial
     * gradient in the rarity tier's colour, gently pulsing — visible in
     * daylight, and matched by a real point light after dark (see
     * {@link #feedLighting}).
     */
    private void drawRarityHalo(Graphics2D g, ItemDef def, int cx, int cy, int itemPx) {
        if (def.rarity() == ItemDef.Rarity.COMMON) return;
        float pulse = 0.82f + 0.18f * (float) Math.sin(animClock * 3
                + def.key().hashCode() % 7);
        float radius = Math.max(4f, itemPx * (1.1f + 0.35f * def.rarity().ordinal()) * pulse);
        Color c = def.rarity().color;
        RadialGradientPaint paint = new RadialGradientPaint(
                new java.awt.geom.Point2D.Float(cx, cy), radius,
                new float[]{0f, 0.55f, 1f},
                new Color[]{
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 110),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 46),
                        new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)});
        var old = g.getPaint();
        g.setPaint(paint);
        g.fillOval((int) (cx - radius), (int) (cy - radius),
                (int) (radius * 2), (int) (radius * 2));
        g.setPaint(old);
    }

    /** A short arc in front of the player while a melee swing plays. */
    private void drawSwing(Graphics2D g) {
        double size = ps();
        camera.worldToScreen(me.x + size / 2, me.y + size / 2, corner);
        int r = (int) (size * camera.zoom * 0.9);
        g.setColor(new Color(255, 255, 255, (int) (150 * Math.max(0, swingTime / 0.2))));
        g.setStroke(new BasicStroke(3f));
        int start = me.facingLeft ? 120 : -60;
        g.drawArc(corner[0] - r, corner[1] - r, r * 2, r * 2, start, 120);
    }

    /** Draw every other player, interpolated between the two latest snapshots. */
    private void drawRemotePlayers(Graphics2D g) {
        GameClient client = net.client();
        Snapshot latest = client.latest();
        if (latest == null) return;
        Snapshot prev = client.previous();

        double t = 1.0;
        if (prev != null && latest.receivedNanos() > prev.receivedNanos()) {
            long renderTime = System.nanoTime() - INTERP_DELAY_NANOS;
            t = (renderTime - prev.receivedNanos())
                    / (double) (latest.receivedNanos() - prev.receivedNanos());
            t = Math.max(0.0, Math.min(1.0, t));
        }

        double size = ps();
        for (PlayerState ps : latest.players()) {
            if (ps.id == me.id) continue;
            PlayerState old = prev != null ? prev.player(ps.id) : null;
            double x = old != null ? old.x + (ps.x - old.x) * t : ps.x;
            double y = old != null ? old.y + (ps.y - old.y) * t : ps.y;
            Animation anim = remoteAnims.computeIfAbsent(ps.id, this::buildRemoteAnimation);
            if (mgView != null) {
                MiniGameHud.drawTeamRing(g, camera, x + size / 2, y + size,
                        size, mgView.teamOf(ps.id), camera.zoom);
            }
            drawPlayer(g, x, y, ps.facingLeft, anim.current(), ps.name);
        }
    }

    private void drawPlayer(Graphics2D g, double x, double y, boolean facingLeft,
                            BufferedImage frame, String nameTag) {
        if (frame == null) return;
        double size = ps();
        int w = (int) Math.round(size * camera.zoom);
        int h = w;
        int footX = camera.worldToScreenX(x + size / 2.0, y + size);
        int footY = camera.worldToScreenY(x + size / 2.0, y + size);
        int dx = footX - w / 2;
        int dy = footY - h;
        if (facingLeft) {
            g.drawImage(frame, dx + w, dy, -w, h, null);
        } else {
            g.drawImage(frame, dx, dy, w, h, null);
        }
        if (nameTag != null && !nameTag.isEmpty()) {
            g.setFont(NAME_FONT);
            int tw = g.getFontMetrics().stringWidth(nameTag);
            int tx = footX - tw / 2;
            int ty = dy - 6;
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRoundRect(tx - 4, ty - 12, tw + 8, 16, 6, 6);
            g.setColor(Color.WHITE);
            g.drawString(nameTag, tx, ty);
        }
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, viewportWidth, 38);
        g.setColor(Color.WHITE);
        g.setFont(HUD_FONT);
        StringBuilder hud = new StringBuilder();
        hud.append(profile().name)
                .append("    |    ").append(camera.getPerspective());
        if (net != null) {
            Snapshot snap = net.client().latest();
            int online = snap != null ? snap.players().size() : 1;
            hud.append("    |    online ").append(online);
            int ping = net.client().pingMillis();
            if (ping >= 0) hud.append(" · ").append(ping).append(" ms");
            if (net.isHost()) hud.append(" · hosting :").append(net.hostedServer().getPort());
        }
        if (profile().zoomEnabled) hud.append("    |    zoom ").append(String.format("%.2f", camera.zoom));
        hud.append("    |    [Esc] pause");
        if (profile().perspectiveSwitchingEnabled) hud.append("  [P] perspective");
        if (profile().zoomEnabled) hud.append("  [+/-] zoom");
        if (profile().itemsEnabled) hud.append("  [I] inventory");
        g.drawString(hud.toString(), 12, 24);
    }

    private void drawHealthBar(Graphics2D g) {
        int w = 180, h = 14;
        int x = 12, y = viewportHeight - 28;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 6, 6);
        g.setColor(new Color(120, 30, 30));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(220, 60, 60));
        g.fillRect(x, y, (int) (w * Math.max(0, me.health / PlayerState.MAX_HEALTH)), h);
        g.setColor(Color.WHITE);
        g.setFont(SMALL_FONT);
        g.drawString((int) Math.ceil(me.health) + " / " + (int) PlayerState.MAX_HEALTH,
                x + w / 2 - 20, y + 11);
    }

    private void drawHotbar(Graphics2D g) {
        int slot = 44, pad = 5;
        int total = Inventory.HOTBAR * (slot + pad) - pad;
        int x0 = (viewportWidth - total) / 2;
        int y0 = viewportHeight - slot - 10;
        for (int i = 0; i < Inventory.HOTBAR; i++) {
            int x = x0 + i * (slot + pad);
            boolean sel = inventory.selectedIndex() == i;
            g.setColor(new Color(0, 0, 0, sel ? 200 : 140));
            g.fillRoundRect(x, y0, slot, slot, 8, 8);
            g.setColor(sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 70));
            g.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g.drawRoundRect(x, y0, slot, slot, 8, 8);
            drawStack(g, inventory.slot(i), x, y0, slot);
            g.setColor(new Color(255, 255, 255, 130));
            g.setFont(SMALL_FONT);
            g.drawString(String.valueOf(i + 1), x + 4, y0 + 12);
        }
        drawSelectedItemName(g, inventory.selectedDef(), y0);
    }

    /** The selected hotbar item's name, floated above the bar in its rarity colour. */
    private void drawSelectedItemName(Graphics2D g, ItemDef def, int hotbarTop) {
        if (def == null) return;
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(def.name());
        int x = (viewportWidth - tw) / 2, y = hotbarTop - 10;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x - 8, y - 14, tw + 16, 20, 8, 8);
        g.setColor(def.rarity().color);
        g.drawString(def.name(), x, y);
    }

    // Inventory panel geometry, shared by rendering and mouse hit-testing.
    private static final int INV_SLOT = 46;
    private static final int INV_PAD = 6;

    /**
     * Top-left of the inventory grid: {x0, y0}. Centred alone; shifted left
     * of centre while a container is open so the two panels sit side by side
     * instead of overlapping.
     */
    private int[] inventoryOrigin() {
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;
        int x = containerPanel != null
                ? ContainerPanel.pairedInventoryLeft(viewportWidth) + 20
                : (viewportWidth - gw) / 2;
        return new int[]{x, (viewportHeight - gh) / 2};
    }

    /** The inventory slot index under a screen point, or -1. */
    private int slotAt(int sx, int sy) {
        int[] o = inventoryOrigin();
        int col = Math.floorDiv(sx - o[0], INV_SLOT + INV_PAD);
        int row = Math.floorDiv(sy - o[1], INV_SLOT + INV_PAD);
        if (col < 0 || col >= Inventory.COLS || row < 0 || row >= Inventory.ROWS) return -1;
        // Inside the cell, not the padding between cells.
        if (sx - o[0] - col * (INV_SLOT + INV_PAD) >= INV_SLOT) return -1;
        if (sy - o[1] - row * (INV_SLOT + INV_PAD) >= INV_SLOT) return -1;
        return row * Inventory.COLS + col;
    }

    private boolean insideInventoryPanel(int sx, int sy) {
        int[] o = inventoryOrigin();
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;
        return sx >= o[0] - 20 && sx <= o[0] + gw + 20
                && sy >= o[1] - 52 && sy <= o[1] + gh + 32;
    }

    private void drawInventory(Graphics2D g) {
        int[] o = inventoryOrigin();
        int x0 = o[0], y0 = o[1];
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;

        g.setColor(new Color(10, 10, 16, 220));
        g.fillRoundRect(x0 - 20, y0 - 52, gw + 40, gh + 84, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("Inventory", x0, y0 - 24);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(170, 170, 190));
        g.drawString(containerPanel != null
                ? "Click to pick up / place stacks · [Q] stash · [E]/[Esc] close"
                : "Click to pick up / place stacks · click outside to drop"
                + " · [Q] drop one · [F] eat · [I]/[Esc] close", x0, y0 - 8);

        for (int i = 0; i < Inventory.SIZE; i++) {
            int cx = x0 + (i % Inventory.COLS) * (INV_SLOT + INV_PAD);
            int cy = y0 + (i / Inventory.COLS) * (INV_SLOT + INV_PAD);
            boolean hotbar = i < Inventory.HOTBAR;
            boolean sel = i == inventory.selectedIndex();
            g.setColor(new Color(255, 255, 255, hotbar ? 36 : 18));
            g.fillRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8);
            g.setColor(sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g.drawRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8);
            if (i == cursorSlot) continue; // it's on the cursor, not in the grid
            drawStack(g, inventory.slot(i), cx, cy, INV_SLOT);
        }

        // The picked-up stack follows the mouse until it's placed or dropped.
        if (cursorSlot >= 0) {
            ItemStack held = inventory.slot(cursorSlot);
            if (held == null) {
                cursorSlot = -1; // e.g. a server inv push emptied it
            } else {
                drawStack(g, held, mouseX - INV_SLOT / 2, mouseY - INV_SLOT / 2, INV_SLOT);
            }
        }
    }

    private void drawStack(Graphics2D g, ItemStack stack, int x, int y, int slot) {
        if (stack == null) return;
        ItemDef def = (world != null ? world.itemTypes : ItemRegistry.standard()).get(stack.key);
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + stack.key, animClock);
        if (img == null) img = EntitySprites.item(def, 32);
        g.drawImage(img, x + 6, y + 6, slot - 12, slot - 12, null);
        drawDurabilityBar(g, def, stack, x, y, slot);
        if (stack.count > 1) {
            g.setFont(SMALL_FONT);
            g.setColor(Color.BLACK);
            String n = String.valueOf(stack.count);
            int tw = g.getFontMetrics().stringWidth(n);
            g.drawString(n, x + slot - tw - 3, y + slot - 3);
            g.setColor(Color.WHITE);
            g.drawString(n, x + slot - tw - 4, y + slot - 4);
        }
    }

    /** Green-to-red wear bar under a worn tool's icon. */
    static void drawDurabilityBar(Graphics2D g, ItemDef def, ItemStack stack,
                                  int x, int y, int slot) {
        if (def.maxDurability() <= 0 || stack.wear <= 0) return;
        double t = 1.0 - stack.wear / (double) def.maxDurability();
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(x + 6, y + slot - 8, slot - 12, 4);
        g.setColor(new Color((int) (220 * (1 - t) + 60 * t), (int) (60 * (1 - t) + 210 * t), 50));
        g.fillRect(x + 6, y + slot - 8, (int) ((slot - 12) * Math.max(0, t)), 4);
    }

    /** Server chat-style event feed ("X joined"), bottom-left. */
    private void drawEvents(Graphics2D g) {
        List<String> events = net.client().recentEvents();
        if (events.isEmpty()) return;
        g.setFont(HUD_FONT);
        int y = viewportHeight - 48;
        for (int i = events.size() - 1; i >= 0; i--) {
            g.setColor(new Color(0, 0, 0, 120));
            int tw = g.getFontMetrics().stringWidth(events.get(i));
            g.fillRoundRect(8, y - 14, tw + 12, 19, 6, 6);
            g.setColor(new Color(220, 220, 230));
            g.drawString(events.get(i), 14, y);
            y -= 22;
        }
    }

    // --- world helpers ---

    private double ts() { return level.tileSize; }

    private double ps() { return profile().playerSize; }

    private void rebuildSprite() {
        int size = Math.max(8, (int) ps());
        SpriteSheet sprites = SpriteSheet.fromImage(
                buildCharacterSheet(size, new Color(70, 130, 220)), size, size);
        walkAnim = sprites.animation(10, true);
        remoteAnims.clear(); // rebuilt lazily at the (possibly new) size
    }

    private Animation buildRemoteAnimation(int id) {
        int size = Math.max(8, (int) ps());
        // Golden-ratio hue spacing gives each player a distinct, stable colour;
        // in a mini game the body wears the team colour instead.
        Color body = Color.getHSBColor((id * 0.6180339887f) % 1f, 0.6f, 0.85f);
        MiniGameView v = mgView;
        if (v != null && v.teamOf(id) >= 0) body = Team.color(v.teamOf(id));
        SpriteSheet sprites = SpriteSheet.fromImage(
                buildCharacterSheet(size, body), size, size);
        return sprites.animation(10, true);
    }

    private BufferedImage buildCharacterSheet(int size, Color body) {
        int frames = 4;
        BufferedImage img = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int f = 0; f < frames; f++) {
            int ox = f * size;
            int bob = (f % 2 == 0) ? 0 : Math.max(1, size / 16);
            g.setColor(body);
            g.fillRoundRect(ox + size / 4, size / 4 + bob, size / 2, size / 2, size / 6, size / 6);
            g.setColor(new Color(245, 210, 170));
            g.fillOval(ox + size / 3, size / 8 + bob, size / 3, size / 3);
            g.setColor(new Color(40, 40, 60));
            int legW = Math.max(2, size / 10);
            int legY = size * 3 / 4 + bob;
            int spread = (f % 2 == 0) ? size / 12 : size / 6;
            g.fillRect(ox + size / 2 - spread - legW, legY, legW, size / 5);
            g.fillRect(ox + size / 2 + spread, legY, legW, size / 5);
        }
        g.dispose();
        return img;
    }
}
