package com.larsons.engine.demo;

import com.larsons.engine.character.CharacterPicker;
import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.character.Characters;
import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.audio.SceneSounds;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.combat.Melee;
import com.larsons.engine.combat.MeleeAction;
import com.larsons.engine.combat.MeleeProfile;
import com.larsons.engine.combat.MeleeProfiles;
import com.larsons.engine.combat.MeleeSounds;
import com.larsons.engine.combat.MeleeSprites;
import com.larsons.engine.combat.MeleeState;
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
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.DecorPainter;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.TerrainCache;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
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
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.sim.PlayerStats;
import com.larsons.engine.sim.StatRuleEngine;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.ContainerPanel;
import com.larsons.engine.ui.CraftingPanel;
import com.larsons.engine.ui.KeyBindForm;
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.World;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
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
 * <p><b>Characters (requirement: character profiles).</b> A level offers the
 * roster its creator chose; the picker shown at its start decides who you
 * play as, and that profile's traits — speed, sprint, air jumps, jump height,
 * health/mana/stamina — ride on the simulated player state from there. Their
 * {@link Ultimate} charges with time and damage dealt and fires on [R].
 *
 * <p><b>Perspective (requirement #2).</b> The level's format decides how the
 * world is drawn <em>and</em> which axis is up in it — see
 * {@link com.larsons.engine.sim.PerspectiveSpace}. That is what the effects
 * here read: a burst on a plane spreads across the floor and rises off it
 * toward the viewer, and a shot with height on it draws above its own shadow,
 * rather than every effect replaying a side-scroller's screen-space "up".
 *
 * <p>The perspective is the level's and stays the level's for as long as it is
 * played. The three formats are not three views of one world — they differ in
 * which axis is up, in what a block means, and in how many layers of them the
 * geometry is written in — so there is nothing coherent for a mid-level switch
 * to show. Walking through a door into a level of another format is how a game
 * changes perspective.
 *
 * <p><b>Melee combat.</b> Whatever is in the player's hands brings a set of
 * moves with it ({@link com.larsons.engine.combat.MeleeAction}) — a swing, a
 * parry, a lunge, a dash, and a held guard — on timings that belong to that
 * object, so a dagger and a war hammer play completely differently out of the
 * same controls. The same machine runs for mobs and on the authoritative
 * server, and the object may bring its own art and its own voice for every one
 * of those moves (see {@link com.larsons.engine.combat.MeleeSprites} and
 * {@link com.larsons.engine.combat.MeleeSounds}).
 *
 * <p>Controls: WASD/arrows move — up is a direction (it swims, it climbs, it
 * walks north), never a jump — Space jumps in every perspective (a hop along
 * the elevation axis in top-down and isometric levels), +/- zoom (if enabled),
 * left-click mine/attack, right-click place, 1-5 + wheel hotbar, I inventory,
 * F eat, R ultimate, C hold to guard, V parry, X lunge, Z dash, Esc pause.
 */
public class PlayScene extends AbstractScene {

    /**
     * Remote players and entities are drawn this far in the past, between two
     * buffered snapshots — two snapshot intervals (at the server's 30 Hz
     * broadcast rate), enough that arrival jitter almost never leaves the
     * render time without a newer snapshot to interpolate toward.
     */
    private static final long INTERP_DELAY_NANOS = 70_000_000L; // 70 ms

    /** Prediction errors beyond this snap instantly (teleports, big lag spikes). */
    private static final double SNAP_DISTANCE = 128;

    /** How aggressively prediction errors are blended away, per second. */
    private static final double CORRECTION_PER_SEC = 8.0;

    /** Pending predicted steps kept for reconciliation (~3 s at 120 Hz). */
    private static final int MAX_PENDING_STEPS = 360;

    /** Mining / placing reach, in tiles from the player centre. */
    private static final int REACH_TILES = 5;

    /** The three-stop rarity halo, shared so no paint is built per item per frame. */
    private static final float[] HALO_STOPS = {0f, 0.55f, 1f};

    private static final Color PAUSE_SCRIM = new Color(12, 12, 18);
    private static final Color DISCONNECT_SCRIM = new Color(20, 10, 12);
    private static final Color STAT_RULE_LABEL = new Color(210, 210, 225);

    private static final Font HUD_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private final GameContext ctx;
    private final String levelPath;

    private Level level;
    private Camera camera;
    // The player's current action state (idle/walk/run/jump/fall/swim) and
    // how long it has played — picks which skin animation draws, and from
    // which frame (the clock resets whenever the state changes).
    private String animState = "idle";
    private double animStateClock;

    private PlayerState me = new PlayerState();
    private int inputSeq;

    // The character being played, and the picker shown at the level's start
    // while the player chooses from the roster its creator put together.
    private CharacterStore characterStore;
    private CharacterProfile character = CharacterProfile.defaultProfile();
    private CharacterPicker picker;

    private NetSession net; // null in single-player

    /**
     * Set when the player quits an online session, until the scene switch
     * lands. Quitting nulls {@link #net}, but the menu transition keeps
     * <em>rendering</em> this scene through the fade — and with no session
     * every {@code net == null} branch assumes an offline {@link #world},
     * which an online session never had. While leaving, update and render
     * are no-ops (the fade covers the blank frame).
     */
    private boolean leaving;

    /**
     * Online: every locally-predicted step since the last server
     * acknowledgement, oldest first. Reconciliation replays these on top of
     * the authoritative state so the corrected position is at the <em>same
     * simulation time</em> as the prediction — comparing against the raw
     * (older) server position instead used to drag the player backwards by
     * the round trip every frame, which felt like heavy lag even on a LAN.
     */
    private final java.util.ArrayDeque<PredictedStep> pendingSteps = new java.util.ArrayDeque<>();

    private record PredictedStep(int seq, PlayerInput in, double dt) {}

    /**
     * Online hold-to-mine: the locally-predicted progress on the cell being
     * mined, driving the crack overlay and break feel. The server runs the
     * identical accumulation and broadcasts the authoritative break.
     */
    private int netMineCol = Integer.MIN_VALUE, netMineRow = Integer.MIN_VALUE;
    private double netMineProgress;

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
     * The sounds that come from watching the world rather than from a single
     * event — footsteps, the swim loop, a sustained ultimate, a meteor's
     * descent, the level's music and its ambience.
     */
    private final SceneSounds sounds = new SceneSounds();
    /** Night last frame, so daybreak and nightfall are heard as they turn. */
    private boolean wasNight;
    /** Time until the next mining scrape, so holding a pick isn't a buzz. */
    private double mineSoundTimer;
    /** Time until the next liquid trickle, so a draining lake is a stream. */
    private double flowTimer;
    /** Seconds between the trickles of flowing liquid. */
    private static final double FLOW_SOUND_INTERVAL = 0.5;
    /**
     * Online only: the locally-predicted copy of the vehicle this player is
     * riding, stepped with the same deterministic physics the server runs and
     * blended toward its snapshot state — the mounted twin of the player's
     * own prediction. {@code null} while on foot (or offline, where the
     * world's own vehicle is driven directly).
     */
    private Vehicle predictedVehicle;
    private double swingTime;      // seconds left on the melee swing visual
    /**
     * The local player's melee moves — swing, parry, lunge, dash, and the held
     * guard — run on the same {@link MeleeState} machine mobs and the
     * authoritative server run. Offline it <em>is</em> the simulation; online
     * it is the prediction, and the server keeps its own copy for authority.
     */
    private final MeleeState melee = new MeleeState();
    /** The item key the melee machine is currently running on. */
    private String meleeItem = "";
    /** Where the running move was aimed when it started, in world px. */
    private double meleeAimX, meleeAimY;
    /** Guard hits the server had resolved last time we looked (for the clang). */
    private int prevGuardHits;
    private double prevVy;
    private double prevVz;         // plan-view hop velocity, for jump feedback
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

    private boolean paused;
    private ConfigForm pauseForm;
    /** The controls sheet, shown over the pause menu while it is open. */
    private ConfigForm bindsForm;

    // Scratch buffer for zero-allocation world-to-screen projection.
    private final int[] corner = new int[2];

    private static final Font SANS_BOLD_12 = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SANS_BOLD_16 = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SANS_BOLD_26 = new Font("SansSerif", Font.BOLD, 26);
    private static final Font SANS_PLAIN_11 = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font SANS_PLAIN_14 = new Font("SansSerif", Font.PLAIN, 14);

    public PlayScene(GameContext ctx, String levelPath) {
        this.ctx = ctx;
        this.levelPath = levelPath;
    }

    private GameProfile profile() { return ctx.profile(); }

    @Override
    public void onEnter() {
        paused = false;
        leaving = false;
        pauseForm = null;
        net = ctx.session();
        particles.clear();
        predictedVehicle = null;
        showInventory = false;
        cursorSlot = -1;
        swingTime = 0;
        Melee.clear(me, melee);
        meleeItem = "";
        prevGuardHits = 0;
        doors = new DoorDirectory(profile().name);
        // Objects created with the creative editor's "+" entries must be
        // registered before a level referencing them loads.
        new CustomContentStore(profile().name).loadAndRegister();
        // …and so must the game type's character profiles, since the level
        // about to load names the ones it offers.
        characterStore = new CharacterStore(profile().name);
        characterStore.loadAndRegister();
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
                itemSound(key, "pickup", "pickup");
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
        openCharacterChoice();
        prevHealth = me.health;
        setupLocalMinigame();

        parallax = null; // rebuilt lazily against the level's background
        syncCameraFromProfile();

        // A fresh level starts from silence: no landing or hurt carried over
        // from the last one, and its own music from the first frame.
        sounds.reset();
        sounds.setCharacter(character.key);
        wasNight = false;
        ctx.sound(SoundKeys.world("level_load"));
    }

    /** Leaving the scene stops the music and every loop it started. */
    @Override
    public void onExit() {
        sounds.reset();
    }

    /**
     * Open the level's character choice: the profiles its creator put on the
     * roster, offered as cards before play begins. A roster of one (or a level
     * from before character profiles existed, whose empty roster means "all of
     * them" and whose game type has only the default) needs no decision, so
     * that character is applied and play starts straight away.
     */
    private void openCharacterChoice() {
        List<CharacterProfile> roster = Characters.rosterFor(level.characters);
        picker = CharacterPicker.needed(roster)
                ? new CharacterPicker(roster, level.name, ctx.character()) : null;
        applyCharacter(picker != null ? picker.selected()
                : roster.isEmpty() ? CharacterProfile.defaultProfile() : roster.get(0));
    }

    /** Make {@code p} the character being played: traits, pools, and sprite. */
    private void applyCharacter(CharacterProfile p) {
        character = p == null ? CharacterProfile.defaultProfile() : p;
        character.applyTo(me);
        ctx.setCharacter(character.key);
        prevHealth = me.health;
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
        if (leaving) return; // session torn down; waiting out the scene fade
        if (net != null && !net.client().isConnected()) {
            if (KeyBinds.pressed(input, GameAction.MENU_SELECT)
                    || KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                leaveSession();
            }
            return;
        }
        if (paused) {
            updatePaused(dt, input);
            return;
        }
        // The character choice owns the level's first frames: the world is
        // built and waiting, but nothing simulates until a character is picked.
        if (picker != null) {
            if (picker.update(dt, input)) {
                applyCharacter(picker.selected());
                picker = null;
                ctx.sfx(Sfx.CLICK);
            }
            return;
        }
        // A running cutscene owns the frame: the world holds still, the
        // director drives the camera, Enter/Esc skips to the end.
        if (cutscenes != null && cutscenes.active() != null) {
            animClock += dt;
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                    || KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
                cutscenes.skip();
            } else {
                cutscenes.advance(dt);
            }
            CutscenePlayer cut = cutscenes.active();
            if (cut != null) camera.centerOn(cut.cameraX(), cut.cameraY());
            return;
        }
        if (KeyBinds.pressed(input, GameAction.PAUSE)
                || KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
                ctx.sound(SoundKeys.world("chest_close"));
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
                && KeyBinds.pressed(input, GameAction.INTERACT)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
                ctx.sound(SoundKeys.world("chest_close"));
            } else if (me.riding >= 0) {
                Vehicle left = world.vehicle(me.riding);
                world.dismount(me);
                vehicleSound(left, "dismount");
            } else if (!tryDoorTravel(p) && !tryOpenStation(p)) {
                Vehicle mountable = world.mountableNear(me.x + ps() / 2, me.y + ps() / 2);
                if (mountable != null && world.mount(me, mountable.id, p)) {
                    vehicleSound(mountable, "mount");
                }
            }
        }
        if (net != null && !showInventory && KeyBinds.pressed(input, GameAction.INTERACT)) {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                EntityView riding = snap.vehicleRiddenBy(me.id);
                if (riding != null) {
                    net.client().sendDismount();
                    Sounds.actor(character.key,
                            SoundKeys.vehicle(riding.key, "dismount"), "dismount");
                } else {
                    EntityView near = nearestSnapshotVehicle(snap);
                    if (near != null) {
                        net.client().sendMount(near.id);
                        Sounds.actor(character.key,
                                SoundKeys.vehicle(near.key, "mount"), "mount");
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

        // Effects are authored in the space they are drawn in — which axis is
        // up, and whether height is an axis at all. Re-read every tick so
        // walking through a door into a level of another format lands on the
        // very next burst.
        particles.setSpace(PerspectiveSpace.of(camera.getPerspective()));
        if (p.zoomEnabled) {
            if (
                    KeyBinds.down(input, GameAction.ZOOM_IN)) camera.zoom = clampZoom(camera.zoom + dt * 2,
                    p);
            if (
                    KeyBinds.down(input, GameAction.ZOOM_OUT)) camera.zoom = clampZoom(camera.zoom - dt * 2,
                    p);
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
                    if (KeyBinds.pressed(input, GameAction.hotbar(k))) inventory.select(k);
                }
                int wheel = input.getWheelRotation();
                if (wheel != 0) inventory.scrollSelect(wheel > 0 ? 1 : -1);
                handleInventoryMouse(input);
            }
        } else {
            updateInventoryControls(input, p);
        }

        PlayerInput in = new PlayerInput(
                KeyBinds.down(input, GameAction.MOVE_LEFT),
                KeyBinds.down(input, GameAction.MOVE_RIGHT),
                KeyBinds.down(input, GameAction.MOVE_UP),
                KeyBinds.down(input, GameAction.MOVE_DOWN),
                ++inputSeq);
        in.sprint = KeyBinds.down(input, GameAction.SPRINT);
        // Space is the jump key, and the only one: W/Up steer, swim and climb.
        // A fresh press is what drives mid-air jumps (double jump and beyond),
        // so holding Space doesn't burn the whole allowance in one tick.
        in.jump = KeyBinds.pressed(input, GameAction.JUMP);
        // The server resolves attacks against what this player holds.
        in.selected = inventory.selectedIndex();
        // Relic passives — extra air jumps, speed, slow fall, flight,
        // magnetism, melee power — refresh from the carried inventory.
        inventory.applyPassivesTo(me, p.itemsEnabled);

        if (!showInventory && craftingPanel == null && containerPanel == null) {
            handleMouseActions(input, p, in, dt);
            updateMeleeControls(input, p, in);
            // [R] fires the character's ultimate at the cursor, once charged.
            // (Q is already "drop one of the held stack".)
            if (KeyBinds.pressed(input, GameAction.ULTIMATE)) tryUltimate(p);
        } else {
            if (world != null) world.cancelMining();
            cancelPredictedMining();
        }

        // Online, physics must not depend on the local camera view — the server
        // simulates the level's own format, so prediction does too.
        // A mounted player drives their vehicle instead of walking.
        Perspective simPerspective = net != null ? level.perspective : camera.getPerspective();
        // The melee machine steps before the body does: a lunge's burst and a
        // raised guard's slowed footwork are both movement, and the physics
        // step below is what carries them out.
        stepMelee(p, in, simPerspective != Perspective.SIDE_SCROLL || !p.gravityEnabled, dt);
        prevVy = me.vy;
        double preX = me.x, preY = me.y;
        boolean riding = stepRiding(in, p, dt);
        if (!riding) {
            PlayerPhysics.step(me, in, level, p, simPerspective, dt);
        }
        if (net != null) {
            // Remember this predicted step for reconciliation replay. While
            // mounted the vehicle prediction blend does the job instead.
            if (riding) {
                pendingSteps.clear();
            } else {
                pendingSteps.addLast(new PredictedStep(in.seq, in, dt));
                while (pendingSteps.size() > MAX_PENDING_STEPS) pendingSteps.pollFirst();
            }
        }
        // A jump counts in every perspective: gravity's -vy in a side-scroller,
        // the hop's upward vz on a plane (see PlayerPhysics.stepHop).
        if ((me.vy < -1 && prevVy >= 0) || (me.vz > 1 && prevVz <= 0)) {
            stats.add("jumps", 1);
            playerSound(me.airJumpsUsed > 0 ? "double_jump" : "jump");
        }
        prevVz = me.vz;
        stats.add("distance_traveled", Math.abs(me.x - preX) + Math.abs(me.y - preY));

        if (net != null) {
            net.client().sendInput(in);
            reconcile(dt);
            consumeNetFeedback();
            mgView = net.client().minigame(); // replicated mini-game state
            if (p.particlesEnabled) {
                Snapshot snap = net.client().latest();
                if (snap != null) {
                    for (EntityView s : snap.shots()) emitTrail(s.key, s.x, s.y, s.z);
                }
                emitStatusParticles(dt);
            }
            // Online the server owns the meter and sends it back in snapshots;
            // charge locally too so the HUD fills smoothly between them.
            Ultimates.charge(me, dt);
        } else {
            // Same order as the server tick: the referee sees deaths before
            // the world respawns them.
            if (localMinigame != null) localMinigame.step(dt, List.of(me));
            world.step(dt, List.of(me), p);
            if (localMinigame != null) {
                for (String event : localMinigame.pollEvents()) {
                    ruleStatus = event;
                    ruleStatusTime = 3.5;
                    ctx.sound(SoundKeys.minigame("score"));
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
                // Water finding its way into a new cell: the liquid's own
                // trickle, rate-limited so a draining lake is a stream and
                // not a hundred overlapping splashes.
                Block flowed = change.id() == 0 ? null : level.blocks.get(change.id());
                if (flowed != null && flowed.liquid() && flowTimer <= 0) {
                    flowTimer = FLOW_SOUND_INTERVAL;
                    ctx.sound(SoundKeys.block(flowed.key(), "flow"), 0.4);
                }
            }
            if (flowTimer > 0) flowTimer -= dt;
            if (p.particlesEnabled) {
                for (Projectile pr : world.projectiles()) {
                    emitTrail(pr.def.key(), pr.x, pr.y, pr.z);
                }
                emitStatusParticles(dt);
            }
            // The level's programmable stat rules run against this run's stats.
            for (StatRuleEngine.Fired fired : ruleEngine.update(stats, inventory)) {
                ctx.sound(SoundKeys.world("stat_rule"));
                ruleStatus = ruleFiredMessage(fired.rule());
                ruleStatusTime = 3.5;
            }
        }

        if (me.health < prevHealth - 0.01) {
            stats.add("damage_taken", prevHealth - me.health);
            // The hurt/death cry itself comes from the tracker below, which
            // is watching the same health bar and knows the character.
        }
        prevHealth = me.health;
        // Blows the guard or the parry stopped this tick — resolved wherever
        // the simulation lives (the world offline, the server online), heard
        // and seen here.
        pollGuardFeedback(p);

        if (swingTime > 0) swingTime -= dt;
        if (p.particlesEnabled) particles.update(dt);

        double size = ps();
        camera.centerOn(me.x + size / 2.0, me.y + size / 2.0);
        // A mounted player sits (idle art); otherwise classify the action so
        // the matching skin animation plays, restarting on state changes.
        String state = riding ? "idle"
                : PlayerSprites.actionState(me, level, p, simPerspective, in.sprint);
        // A melee move takes the drawn animation over while it runs — its own
        // sheet, played once across the move rather than looping with the walk
        // cycle. The movement state itself is untouched: footsteps still land
        // while you are swinging.
        String drawn = melee.animationState().isEmpty() ? state : melee.animationState();
        if (!drawn.equals(animState)) {
            animState = drawn;
            animStateClock = 0;
        } else {
            animStateClock += dt;
        }

        // Everything that has to be tracked frame to frame — footsteps timed
        // to the gait, the splash going in and the loop while swimming, the
        // landing, a sustained ultimate, the roar of shots still in the air —
        // plus the level's music and the ambience under it.
        sounds.setEnabled(p.audioEnabled);
        sounds.setCharacter(character.key);
        sounds.update(dt, me, level, p, state,
                world != null ? world.projectiles() : List.of(),
                world != null ? world.mobs() : List.of(),
                camera.viewportWidth / 2.0 / Math.max(0.01, camera.zoom));
        boolean night = World.darknessFor(timeOfDay(), p) > 0.25;
        if (night != wasNight) {
            ctx.sound(SoundKeys.world(night ? "nightfall" : "daybreak"));
            wasNight = night;
        }
        sounds.ambience(level, night, false);

        // Cutscene triggers watch the player: zones fire on entry, INTERACT
        // ones on E (doors and stations already had their chance above).
        if (cutscenes != null) {
            boolean interact = KeyBinds.pressed(input, GameAction.INTERACT)
                    && craftingPanel == null && containerPanel == null && !showInventory;
            if (cutscenes.checkTriggers(me.x + size / 2.0, me.y + size / 2.0,
                    interact, ts(), camera.x, camera.y) != null) {
                if (world != null) world.cancelMining();
                ctx.sound(SoundKeys.cutscene("start"));
            }
        }
    }

    /**
     * Enter the door the player stands at: its {@link DoorLink} (from the game
     * type's external door directory) names another saved level, which loads
     * in place — inventory and health carry through, so a set of levels wired
     * with doors plays like one continuous world.
     *
     * <p>The destination brings its own format and settings with it: stepping
     * from a side-scrolling cave through a door into an isometric town swaps
     * the camera projection and the movement model on the spot, with no
     * reload and no menu — the three formats are authored apart and play as
     * one game.
     */
    private boolean tryDoorTravel(GameProfile p) {
        double half = p.playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(me.x + half, me.y + half, ts() * 1.3);
        if (door == null) return false;
        DoorLink link = doors.get(door.type);
        if (link == null || link.targetLevel().isEmpty()) return true;
        LevelStore store = new LevelStore(p.name);
        if (!store.exists(link.targetLevel())) return true;
        ctx.sound(SoundKeys.door("open"));
        level = store.load(link.targetLevel());
        // The destination's own toggles (and so its tile/player sizes) apply
        // before anything is built against them.
        ctx.applyLevelSettings(level.settings);
        world = new World(level);
        world.populateFromLevel(p);
        world.setPickupListener((player, key, count) -> {
            inventory.add(key, count);
            stats.add("items_picked_up", count);
            itemSound(key, "pickup", "pickup");
        });
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        cutscenes = new CutsceneDirector(level.cutscenes);
        me.x = level.spawnX;
        me.y = level.spawnY;
        me.vy = 0;
        setupLocalMinigame(); // the destination level may run its own mini game
        // Camera projection, zoom bounds and the player sprite all follow the
        // level that just loaded — this is what makes the format switch
        // seamless rather than a scene change. The projection is set outright
        // (not only when switching is locked) because arriving in an isometric
        // level with the previous level's flat camera is not that level.
        camera.setPerspective(basePerspective());
        syncCameraFromProfile();
        parallax = null;
        particles.clear();
        // The new level brings its own music and ambience; the tracker is
        // reset so the arrival isn't heard as a landing or a hurt.
        sounds.reset();
        ctx.sound(SoundKeys.door("travel"));
        ctx.sound(SoundKeys.player("door_enter"));
        ctx.sound(SoundKeys.world("level_load"));
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
                    ctx.sound(SoundKeys.world("craft_station"));
                    craftingPanel = new CraftingPanel(station, RecipeRegistry.standard(),
                            world != null ? world.itemTypes : ItemRegistry.standard());
                    ctx.sfx(Sfx.CLICK);
                    return true;
                }
                if (b.container() && p.itemsEnabled) {
                    // The chest/barrel's second inventory, stored in the level.
                    // The player's inventory opens beside it (side by side)
                    // so moving stacks between the two is one screen.
                    ctx.sound(SoundKeys.world("chest_open"));
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
        itemSound(crafted.recipe().output(), "craft", "craft");
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
        if (KeyBinds.pressed(input, GameAction.INVENTORY)) {
            showInventory = !showInventory;
            cursorSlot = -1;
        }
        for (int k = 0; k < Inventory.HOTBAR; k++) {
            if (KeyBinds.pressed(input, GameAction.hotbar(k))) inventory.select(k);
        }
        int wheel = input.getWheelRotation();
        if (wheel != 0) inventory.scrollSelect(wheel > 0 ? 1 : -1);

        // Q tosses one item from the selected stack into the world.
        if (KeyBinds.pressed(input, GameAction.DROP_ITEM)) {
            dropStack(inventory.selectedIndex(), 1);
        }

        // F uses the selected item: deploy a vehicle item, fire a relic
        // active, or consume the food/potion. Online it's a request — the
        // server owns health, mana, the world, and the inventory, and pushes
        // the results back.
        if (KeyBinds.pressed(input, GameAction.USE_ITEM)) {
            ItemDef def = inventory.selectedDef();
            boolean edible = def != null && def.heal() > 0 && me.health < me.maxHealth;
            boolean manaDrink = def != null && "mana_potion".equals(def.key())
                    && me.mana < me.maxMana;
            boolean relic = def != null && World.relicManaCost(def.key()) != null;
            VehicleDef vehDef = def == null ? null
                    : (world != null ? world.vehicleTypes : VehicleRegistry.standard())
                    .bySourceItem(def.key());
            if (net != null) {
                net.client().sendUseItem(inventory.selectedIndex());
                if (edible) itemSound(def.key(), "use", "eat");
                else if (manaDrink) itemSound(def.key(), "use", "drink");
                else if (relic) itemSound(def.key(), "use", "ult_activate");
                else if (vehDef != null) itemSound(def.key(), "use", "place");
            } else if (vehDef != null) {
                if (inventory.consumeSelected()) {
                    world.spawnVehicle(vehDef.key(),
                            me.x + (me.facingLeft ? -24 : 24), me.y);
                    ruleStatus = vehDef.name() + " deployed — ["
                            + KeyBinds.label(GameAction.INTERACT) + "] to ride";
                    ruleStatusTime = 3.0;
                    itemSound(def.key(), "use", "place");
                }
            } else if (relic) {
                if (world.useRelic(me, def.key(), p)) itemSound(def.key(), "use", "ult_activate");
            } else if (manaDrink && inventory.consumeSelected()) {
                me.mana = Math.min(me.maxMana, me.mana + 50);
                itemSound(def.key(), "use", "drink");
            } else if (edible && inventory.consumeSelected()) {
                // Food heals directly, restores stamina alongside, and rare
                // delicacies also restore mana (World.applyFood).
                World.applyFood(me, def);
                prevHealth = me.health; // don't play the hurt sound on heals
                itemSound(def.key(), "use", "eat");
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
            ctx.sound(SoundKeys.ui("click"));
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
            playerSound("drop");
            return;
        }
        String key = stack.key;
        int removed = inventory.removeAt(slot, count);
        if (removed <= 0) return;
        DroppedItem drop = world.spawnItem(key, removed, me.x, me.y);
        if (drop != null) {
            drop.tossForward(me.facing, level.format().gravity());
            drop.pickupDelay = 1.0; // don't instantly vacuum it back up
        }
        itemSound(key, "drop", "drop");
    }

    /**
     * Left click: fire the held ranged weapon / throwable (if projectiles are
     * on), else swing at mobs (if combat is on). <em>Holding</em> left over a
     * block in reach mines it over time — block durability, sped up by a
     * matching tool. Online the same hold rides the input command as mining
     * intent and the server accumulates identical progress, so durability is
     * the same in multiplayer. Right click: place the selected hotbar block.
     */
    private void handleMouseActions(InputManager input, GameProfile p, PlayerInput in,
                                    double dt) {
        boolean leftClick = KeyBinds.pressed(input, GameAction.ATTACK);
        boolean rightClick = KeyBinds.pressed(input, GameAction.PLACE);

        double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
        double ts = ts();
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (me.x + ps() / 2), aim[1] - (me.y + ps() / 2))
                <= REACH_TILES * ts;

        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        boolean shoots = p.projectilesEnabled && held != null && held.projectile() != null;

        // Hold-to-mine against block durability — everywhere. Offline the
        // local world accumulates the progress; online the mining intent
        // rides the input command and the server accumulates the identical
        // progress, so blocks are exactly as durable in multiplayer.
        boolean miningNow = KeyBinds.down(input, GameAction.ATTACK) && !shoots
                && p.blockEditingEnabled && inReach && level.tileAt(col, row) > 0;
        if (miningNow && net != null) {
            swingTime = Math.max(swingTime, 0.1);
            in.mine = true;
            in.mineCol = col;
            in.mineRow = row;
            predictMining(col, row, held, dt);
        } else if (miningNow) {
            swingTime = Math.max(swingTime, 0.1);
            // The tool bites the top of the stack: the block standing on the
            // floor where there is one, the floor itself where there isn't.
            if (level.topBlockAt(col, row) == null) {
                // Legacy palette tile with no block definition: instant break.
                if (leftClick && level.setTile(col, row, 0)) {
                    stats.add("blocks_mined", 1);
                    playerSound("mine_break");
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts, Color.GRAY, 10);
                    }
                }
            } else {
                // The scrape of the tool against the block, while it lasts.
                Block digging = level.topBlockAt(col, row);
                mineSoundTimer -= dt;
                if (digging != null && mineSoundTimer <= 0) {
                    mineSoundTimer = MINE_SOUND_INTERVAL;
                    Sounds.actor(character.key, SoundKeys.block(digging.key(), "mine"),
                            "mine", 0.5);
                }
                Block mined = world.continueMining(col, row, held, p.itemsEnabled, dt);
                if (mined != null) {
                    stats.add("blocks_mined", 1);
                    blockSound(mined, "break", "mine_break");
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts, mined.color(), 10);
                    }
                    wearHeldTool(held);
                }
            }
        } else {
            if (net == null && world != null) world.cancelMining();
            cancelPredictedMining();
            mineSoundTimer = 0;
        }

        if (leftClick) {
            if (p.projectilesEnabled && ridingArmedVehicle() != null) {
                fireVehicleAt(aim[0], aim[1], in);
            } else if (shoots) {
                shootAt(aim[0], aim[1], in);
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

    /**
     * Online: advance the local prediction of mining progress with the same
     * hardness/tool formula the server runs, for the crack overlay. The break
     * itself arrives as an authoritative {@code block} broadcast.
     */
    private void predictMining(int col, int row, ItemDef held, double dt) {
        if (col != netMineCol || row != netMineRow) {
            netMineCol = col;
            netMineRow = row;
            netMineProgress = 0;
        }
        // The client predicts against the block the server will bite into:
        // the top of the stack, not the floor beneath it.
        Block b = level.topBlockAt(col, row);
        double hardness = b == null || b.liquid() ? 0 : b.hardness();
        if (hardness <= 0) {
            netMineProgress = 1;
            return;
        }
        double power = held != null && held.toolClass() != null
                && held.toolClass().equals(b.tool()) ? held.toolPower() : 1.0;
        netMineProgress = Math.min(1, netMineProgress + dt * power / hardness);
    }

    private void cancelPredictedMining() {
        netMineCol = netMineRow = Integer.MIN_VALUE;
        netMineProgress = 0;
    }

    /** Wear the held tool one point on a finished block; report a break. */
    private void wearHeldTool(ItemDef held) {
        if (held == null || held.toolClass() == null || !profile().itemsEnabled) return;
        if (inventory.damageSelected(1)) {
            itemSound(held.key(), "break", "mine_break");
            ruleStatus = held.name() + " broke!";
            ruleStatusTime = 2.5;
        }
    }

    /** Swing at a destructible decoration (trees → logs + leaves…). */
    private boolean tryChopDecor(double aimX, double aimY, ItemDef held, GameProfile p) {
        if (world == null) return false;
        boolean axe = held != null && "axe".equals(held.toolClass());
        World.Chop chop = world.chopDecor(aimX, aimY, axe, p.itemsEnabled);
        if (!chop.hit()) return false;
        swingTime = 0.2;
        Sounds.actor(character.key, chop.decor() == null ? ""
                : SoundKeys.decor(chop.decor().key(), chop.broken() ? "break" : "hit"),
                "chop");
        if (p.particlesEnabled) {
            particles.burst(aimX, aimY, new Color(110, 85, 50), chop.broken() ? 14 : 5);
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
        // A stack is built from the bottom up: a hole is floored first, and a
        // cell that already has a floor gets the block stood on it. Liquid
        // cells accept placement either way — covering water with a block is
        // how pools are removed, since liquids can't be mined.
        int layer = level.placeLayer(col, row);
        if (b == null || layer < 0) return;
        // Don't wall yourself in. Flooring a hole under your feet is not
        // walling yourself in — it is the opposite — so only a placement that
        // would actually close the cell counts.
        double ts = ts();
        double size = ps();
        boolean overlapsMe = me.x + size > col * ts && me.x < (col + 1) * ts
                && me.y + size > row * ts && me.y < (row + 1) * ts;
        boolean wouldClose = b.solid()
                && (!level.layered() || layer == Level.LAYER_UPPER);
        if (wouldClose && overlapsMe) return;

        if (net != null) {
            net.client().sendBlockEdit(col, row, b.id(), "play");
            return;
        }
        if (world.placeBlock(col, row, b.id())) {
            if (p.itemsEnabled) inventory.consumeSelected();
            stats.add("blocks_placed", 1);
            blockSound(b, "place", "place");
        }
    }

    /**
     * Left click: throw a swing. The click only <em>starts</em> the move — the
     * blade lands when the wind-up finishes and the hit window opens
     * ({@link #stepMelee}), which is what gives every weapon its own weight
     * and what lets a mob step out of a telegraphed hammer blow.
     */
    private void swingAt(double aimX, double aimY, PlayerInput in, GameProfile p) {
        meleeAimX = aimX;
        meleeAimY = aimY;
        if (!Melee.start(me, melee, meleeProfile(p), MeleeAction.SWING)) return;
        if (net != null) in.attackAt(aimX, aimY); // the server resolves the hit
    }

    /**
     * The melee keys: [C] holds the guard up, [V] parries, [X] lunges, [Z]
     * dashes. Each is validated against what is actually held and against the
     * move's own cooldown by the same machine the server runs, so the request
     * only rides the input command when it really started here.
     */
    private void updateMeleeControls(InputManager input, GameProfile p, PlayerInput in) {
        if (!p.combatEnabled) return;
        in.shield = KeyBinds.down(input, GameAction.GUARD);
        MeleeAction requested = KeyBinds.pressed(input, GameAction.PARRY) ? MeleeAction.PARRY
                : KeyBinds.pressed(input, GameAction.LUNGE) ? MeleeAction.LUNGE
                : KeyBinds.pressed(input, GameAction.DASH) ? MeleeAction.DASH
                : MeleeAction.NONE;
        if (requested == MeleeAction.NONE) return;
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        meleeAimX = aim[0];
        meleeAimY = aim[1];
        if (Melee.start(me, melee, meleeProfile(p), requested)) {
            in.melee = requested.key();
            if (requested == MeleeAction.LUNGE && net != null) {
                // A lunge lands damage, so the server needs the aim too.
                in.attackAt(aim[0], aim[1]);
            }
        }
    }

    /**
     * Advance the melee machine and act on what it reports: the move's start
     * sound, the strike landing, a parry batting shots out of the air, and a
     * held guard being lowered.
     *
     * <p>Offline this is the whole simulation; online it is the prediction and
     * the server resolves the damage on its own copy — the moves themselves
     * play identically either way because both run this same machine.
     */
    private void stepMelee(GameProfile p, PlayerInput in, boolean planar, double dt) {
        MeleeProfile profile = meleeProfile(p);
        meleeItem = heldMeleeKey(p);
        Melee.step(me, melee, profile, meleeItem, in.shield && p.combatEnabled, planar, dt);

        MeleeAction begun = melee.pollBegun();
        if (begun != MeleeAction.NONE) {
            Sounds.playFirst(1.0, MeleeSounds.playerStart(character.key, meleeItem, begun));
        }
        if (melee.pollEnded() == MeleeAction.SHIELD) {
            Sounds.playFirst(0.8,
                    MeleeSounds.playerEnd(character.key, meleeItem, MeleeAction.SHIELD));
        }
        // The hit window opened. Drained unconditionally — a strike is never
        // banked for a later tick — and resolved here only offline; online the
        // server's copy of this machine is the one that lands it.
        boolean struck = melee.pollStrike();
        if (struck && p.combatEnabled && net == null && world != null) {
            resolveMeleeStrike(p, profile);
        }
        // An open parry catches shots as well as blades: anything in the air in
        // front of the guard is turned around and sent home.
        if (melee.parrying() && net == null && world != null
                && world.parryProjectiles(me, profile) > 0) {
            melee.markConnected();
            stats.add("parries", 1);
        }
        if (melee.pollConnected()) {
            Sounds.playFirst(1.0,
                    MeleeSounds.playerHit(character.key, meleeItem, melee.action()));
        }
    }

    /**
     * Land the swing (or lunge) whose hit window just opened, offline. A mob
     * that catches it leaves us staggered; a whiff near an empty vehicle packs
     * the vehicle back into its item, exactly as a plain swing always did.
     */
    private void resolveMeleeStrike(GameProfile p, MeleeProfile profile) {
        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        double base = World.FIST_DAMAGE + me.meleeBonus + (held != null ? held.damage() : 0);
        World.MeleeHit hit = world.meleeStrike(me, profile, melee.action(),
                meleeAimX, meleeAimY, Melee.damage(base, profile, melee.action()));
        if (hit.parried()) {
            // Caught on their guard: the swing is spent and we are off balance.
            melee.stagger(MeleeState.PARRY_STAGGER);
            Sounds.playFirst(1.0, MeleeSounds.mobHit(hit.mob().def.key(),
                    hit.mob().weaponKey(), MeleeAction.PARRY));
            if (p.particlesEnabled) {
                particles.burst(hit.mob().x + hit.mob().def.size() / 2,
                        hit.mob().y + hit.mob().def.size() / 2,
                        new Color(255, 240, 190), 10);
            }
            return;
        }
        if (hit.hit()) {
            Mob m = hit.mob();
            melee.markConnected();
            Sounds.actor(character.key,
                    SoundKeys.mob(m.def.key(), m.dead() ? "death" : "hurt"), "attack_hit");
            if (p.particlesEnabled) {
                particles.burst(m.x + m.def.size() / 2, m.y + m.def.size() / 2,
                        m.def.body(), 8);
            }
            return;
        }
        // A whiffed swing near an empty vehicle packs it back into its item.
        Vehicle packed = world.packUpVehicle(meleeAimX, meleeAimY, p.itemsEnabled);
        if (packed != null) {
            Sounds.actor(character.key,
                    SoundKeys.vehicle(packed.def.key(), "dismount"), "pickup");
            ruleStatus = packed.def.name() + " packed up";
            ruleStatusTime = 2.5;
        }
    }

    /**
     * Ring the guard when something is stopped by it. The stance itself was
     * resolved by whichever simulation is authoritative — offline the local
     * world, online the server, which replicates the running total — so this
     * only has to notice the count going up.
     */
    private void pollGuardFeedback(GameProfile p) {
        if (me.guardHits == prevGuardHits) {
            prevGuardHits = me.guardHits;
            return;
        }
        if (me.guardHits > prevGuardHits) {
            MeleeAction stance = me.parrying ? MeleeAction.PARRY : MeleeAction.SHIELD;
            melee.flashGuard();
            Sounds.playFirst(1.0, MeleeSounds.playerHit(character.key, meleeItem, stance));
            if (stance == MeleeAction.PARRY) stats.add("parries", 1);
            else stats.add("blocks", 1);
            if (p.particlesEnabled) {
                particles.burst(me.x + ps() / 2, me.y + ps() / 2,
                        new Color(230, 240, 255), 8);
            }
        }
        prevGuardHits = me.guardHits;
    }

    /** The melee timings of what is in hand right now (nothing = fists). */
    private MeleeProfile meleeProfile(GameProfile p) {
        return MeleeProfiles.of(p.itemsEnabled ? inventory.selectedDef() : null);
    }

    /** The item key in hand, which picks the wielded sheets and weapon sounds. */
    private String heldMeleeKey(GameProfile p) {
        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        return held == null ? "" : held.key();
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
            // Predicted; the server validates the cooldown.
            VehicleDef armed = ridingArmedVehicle();
            shotSound(armed != null ? armed.projectile() : "");
            return;
        }
        Vehicle v = world.vehicle(me.riding);
        Projectile fired = v == null ? null : world.vehicleShoot(v, me, aimX, aimY);
        if (fired != null) {
            stats.add("shots_fired", 1);
            shotSound(fired.def.key());
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
            // Predicted; the server validates the shot.
            if (hasAmmo) shotSound(held.projectile());
            return;
        }
        Projectile fired = world.playerShoot(me, inventory, aimX, aimY);
        if (fired != null) {
            stats.add("shots_fired", 1);
            shotSound(fired.def.key());
        }
    }

    /**
     * Particles + sound for a world impact (local or replicated): projectile
     * hits styled by their element, plus the ability/relic FX keys the World
     * emits — blinks, summons, warps, novas, tremors, chain arcs, revives.
     */
    /**
     * Fire the character's ultimate at the cursor. Offline the local world
     * resolves it; online it is a request the server validates against its own
     * copy of the meter, exactly like an attack — so nobody can cast one they
     * haven't earned.
     */
    private void tryUltimate(GameProfile p) {
        Ultimate u = Ultimates.of(me);
        if (u == null) {
            ruleStatus = character.name + " has no ultimate ability";
            ruleStatusTime = 2.5;
            return;
        }
        if (!Ultimates.ready(me)) {
            ruleStatus = u.name() + " — " + (int) Math.round(me.ultCharge * 100) + "% charged";
            ruleStatusTime = 2;
            return;
        }
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        double aimX = aim[0], aimY = aim[1];
        if (net != null) {
            net.client().sendUltimate(aimX, aimY);
            // The server's snapshot brings the spent meter back; clearing it
            // locally keeps the HUD honest in the meantime.
            me.ultCharge = 0;
        } else if (!world.useUltimate(me, aimX, aimY, p)) {
            ruleStatus = u.name() + " can't fire here";
            ruleStatusTime = 2;
            return;
        }
        // The ability's own cast sound, then the character's generic one:
        // a Meteor Volley can roar where a Nova Burst cracks.
        Sounds.actor(character.key, SoundKeys.ultimate(u.key(), "activate"), "ult_activate");
        ruleStatus = u.name() + "!";
        ruleStatusTime = 2.5;
    }

    private void impactFeedback(World.Impact im, GameProfile p) {
        boolean fx = p.particlesEnabled;
        // The sound comes from one shared mapping (so the play-test agrees);
        // the switch below only picks the particles.
        Sounds.playFirst(1.0,
                SoundKeys.impact(im.key(), im.explosion()).toArray(new String[0]));
        switch (im.key()) {
            case "blink" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(170, 140, 255), 10,
                        Particles.Style.IMPLODE);
                return;
            }
            case "warp" -> {
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
                if (fx) {
                    particles.burst(im.x(), im.y(), new Color(140, 220, 255), 30,
                            Particles.Style.RING);
                    particles.burst(im.x(), im.y(), Color.WHITE, 10, Particles.Style.SPARKS);
                }
                return;
            }
            case "tremor" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(170, 140, 95), 18,
                        Particles.Style.SHARDS);
                return;
            }
            case "revive" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(255, 190, 80), 24,
                        Particles.Style.FOUNTAIN);
                return;
            }
            case "mount" -> {
                if (fx) particles.burst(im.x(), im.y(), new Color(220, 220, 230), 6);
                return;
            }
            default -> { /* an ultimate or a projectile: styled below */ }
        }
        // An ultimate's landing, thrown in the ability's own colour — new
        // abilities are drawn and heard without a case of their own.
        String ability = im.ultimateKey();
        if (!ability.isEmpty()) {
            Ultimate cast = Ultimates.get(ability);
            if (fx) {
                Color tint = cast != null ? cast.color() : new Color(200, 190, 255);
                particles.burst(im.x(), im.y(), tint, im.explosion() ? 24 : 12,
                        im.explosion() ? Particles.Style.RING : Particles.Style.MOTES);
            }
            return;
        }
        ProjectileDef def = projectileTypes().get(im.key());
        Color color = def == null ? Color.GRAY
                : def.glows() ? def.lightColor() : def.color();
        if (im.explosion()) {
            if (fx) {
                particles.burst(im.x(), im.y(), color, 18, Particles.Style.RING);
                particles.burst(im.x(), im.y(), color, 12);
                particles.burst(im.x(), im.y(), new Color(255, 225, 130), 10);
            }
        } else {
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

    /**
     * One spark per tick behind projectiles that define a trail colour, shed
     * at the height the shot is actually at — a meteor's trail hangs in the
     * air behind it instead of lying on the floor it hasn't reached yet.
     */
    // --- sound helpers ------------------------------------------------------------

    /** Seconds between the scrapes of a tool held against a block. */
    private static final double MINE_SOUND_INTERVAL = 0.33;

    /**
     * Play one of the player's action states in this character's voice,
     * falling back to the generic player sound — so a creator can give the
     * Rogue her own jump without having to re-record everyone else's.
     */
    private void playerSound(String state) {
        Sounds.actor(character.key, "", state);
    }

    /**
     * A block's own sound for an action, falling back to this character's
     * and then the player's — {@code block/stone/break}, then
     * {@code character/rogue/mine_break}, then {@code player/mine_break}.
     */
    private void blockSound(Block block, String blockState, String playerState) {
        Sounds.actor(character.key,
                block == null ? "" : SoundKeys.block(block.key(), blockState), playerState);
    }

    /** An item's own sound for an action, falling back the same way. */
    private void itemSound(String itemKey, String itemState, String playerState) {
        Sounds.actor(character.key,
                itemKey == null ? "" : SoundKeys.item(itemKey, itemState), playerState);
    }

    /**
     * A shot leaving the weapon. Its flight and its landing are separate
     * sounds, played by {@link SceneSounds} and {@link #impactFeedback} — so
     * a meteor can be called down, heard falling, and heard crashing.
     */
    private void shotSound(String projectileKey) {
        Sounds.actor(character.key, SoundKeys.projectile(projectileKey, "fire"), "shoot");
    }

    /** A vehicle being climbed into or out of. */
    private void vehicleSound(Vehicle v, String state) {
        Sounds.actor(character.key,
                v == null ? "" : SoundKeys.vehicle(v.def.key(), state), state);
    }

    /** The time of day sounds and lighting run off: the world's, or the server's. */
    private double timeOfDay() {
        if (net == null) return world != null ? world.timeOfDay() : 0.25;
        Snapshot snap = net.client().latest();
        return snap != null ? snap.timeOfDay() : 0.25;
    }

    private void emitTrail(String key, double x, double y, double z) {
        ProjectileDef def = projectileTypes().get(key);
        if (def != null && def.trail() != null) {
            particles.burst(x, y, z, def.trail(), 1, Particles.Style.BURST);
        }
    }

    private ProjectileRegistry projectileTypes() {
        return world != null ? world.projectileTypes : ProjectileRegistry.standard();
    }

    /** Online-only: turn server broadcasts into local feedback + inventory sync. */
    private void consumeNetFeedback() {
        GameClient client = net.client();
        for (int[] e : client.pollBlockEvents()) {
            if (e[2] == 0) {
                playerSound("mine_break");
                if (profile().particlesEnabled) {
                    particles.burst((e[0] + 0.5) * ts(), (e[1] + 0.5) * ts(),
                            new Color(160, 150, 140), 8);
                }
                // The block we were chipping at broke (authoritatively) —
                // clear the predicted stroke so the cracks vanish with it.
                if (e[0] == netMineCol && e[1] == netMineRow) cancelPredictedMining();
            } else {
                blockSound(level.blocks.get(e[2]), "place", "place");
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
        boolean gravityOn = p.gravityEnabled && level.format().gravity();
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
     * Reconcile the predicted local player with the server: take the latest
     * authoritative state, replay every predicted step the server hasn't
     * acknowledged yet (snapshots echo the last applied input sequence), and
     * compare the result — which sits at the same simulation time as the
     * prediction — against where prediction actually put us. When both
     * simulations agree the error is zero and nothing tugs at the player;
     * comparing against the raw server position instead would lag the
     * comparison by a round trip and drag the player backwards while moving.
     * Small errors blend away; large ones (teleports, heavy lag) snap. While
     * mounted, the vehicle's own prediction blend does this job instead.
     */
    private void reconcile(double dt) {
        if (predictedVehicle != null) return;
        Snapshot snap = net.client().latest();
        if (snap == null) return;
        PlayerState server = snap.player(me.id);
        if (server == null) return;

        // Steps the server has already applied are no longer pending.
        while (!pendingSteps.isEmpty() && pendingSteps.peekFirst().seq() <= server.lastSeq) {
            pendingSteps.pollFirst();
        }

        GameProfile p = profile();
        PlayerState corrected = server.copy();
        // Simulation-side fields snapshots don't carry: keep the local view
        // (relic passives are re-applied from the inventory each tick anyway).
        corrected.airJumpsUsed = me.airJumpsUsed;
        corrected.bonusAirJumps = me.bonusAirJumps;
        corrected.speedFactor = me.speedFactor;
        corrected.slowFall = me.slowFall;
        corrected.canFly = me.canFly;
        for (PredictedStep step : pendingSteps) {
            PlayerPhysics.step(corrected, step.in(), level, p, p.perspective, step.dt());
        }

        double ex = corrected.x - me.x;
        double ey = corrected.y - me.y;
        if (ex * ex + ey * ey > SNAP_DISTANCE * SNAP_DISTANCE) {
            me.x = corrected.x;
            me.y = corrected.y;
            me.vy = corrected.vy;
        } else {
            double k = Math.min(1.0, CORRECTION_PER_SEC * dt);
            me.x += ex * k;
            me.y += ey * k;
            me.vy += (corrected.vy - me.vy) * k;
        }
        // Resources are server-authoritative too (shots spend mana, sprint
        // spends stamina); track them closely without HUD pops.
        double rk = Math.min(1.0, 10 * dt);
        me.stamina += (corrected.stamina - me.stamina) * rk;
        me.mana += (corrected.mana - me.mana) * rk;
        // Blows our guard stopped were resolved on the server; taking its
        // running total is what lets the clang be heard here (see
        // pollGuardFeedback). The stance itself stays locally predicted.
        me.guardHits = server.guardHits;
    }

    private void updatePaused(double dt, InputManager input) {
        // The controls sheet sits over the pause menu rather than replacing the
        // scene, so rebinding mid-level costs neither the level nor the session.
        if (bindsForm != null) {
            if (!bindsForm.isCapturing() && KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                bindsForm = null;
            } else {
                bindsForm.update(dt, input);
            }
            return;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE)) {
            resume();
            return;
        }
        pauseForm.update(dt, input);
        // Apply settings that affect the engine (e.g. FPS cap, shaders) live.
        ctx.applyLiveSettings();
    }

    // Entity overlay colours, built once rather than per mob per frame.
    private static final Color HURT_TINT = new Color(255, 60, 60, 90);
    private static final Color BURNING_TINT = new Color(255, 130, 40, 70);
    private static final Color CHILLED_TINT = new Color(120, 200, 255, 80);
    private static final Color POISONED_TINT = new Color(120, 210, 80, 65);
    private static final Color SHIELD_RING = new Color(120, 230, 255, 180);
    private static final Color HEALTH_BACK = new Color(0, 0, 0, 150);
    private static final Color HEALTH_FILL = new Color(90, 220, 90);
    private static final Color DROP_SHADOW = new Color(0, 0, 0, 70);


    /**
     * Time one named phase of this frame's drawing.
     *
     * <p>"Scene: 19 ms" is the same half-answer a frame counter gives — it
     * says the drawing is slow, not which drawing, and terrain, sprites and
     * HUD have completely different fixes. Free when profiling is off.
     */
    private void phase(String name, Runnable work) {
        long started = ctx.profiler().begin();
        try {
            work.run();
        } finally {
            ctx.profiler().recordSection(name, started);
        }
    }

    /**
     * The baked floor. Terrain is the biggest thing on screen and the least
     * likely to change, so it is drawn into chunk images and blitted rather
     * than rebuilt cell by cell every frame.
     */
    private final TerrainCache terrainCache = new TerrainCache();

    @Override
    public void render(DrawTarget target, float alpha) {
        if (leaving) {
            // Session gone; hold a blank frame while the menu fade finishes.
            ctx.lighting().setDarkness(0);
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    level != null ? level.background : Color.BLACK);
            return;
        }
        GameProfile p = profile();
        feedLighting(p);

        target.fillRect(0, 0, viewportWidth, viewportHeight, level.background);

        if (p.parallaxEnabled && camera.getPerspective() == Perspective.SIDE_SCROLL) {
            if (parallax == null) {
                parallax = new ParallaxBackground(level.background, level.name.hashCode());
            }
            parallax.render(target, camera.x, camera.y, viewportWidth, viewportHeight);
        }

        // A side view's blocks are a wall the background layer hides behind; a
        // plan view's are the floor it stands on, so there the scenery goes on
        // after the terrain — otherwise every tree is painted over by the very
        // tile it was planted on.
        boolean sceneryBehind = PerspectiveSpace.of(camera.getPerspective())
                .scenerySitsBehindTerrain();
        if (sceneryBehind) phase("decor", () -> drawDecorLayer(target, false));
        // Everything standing on the floor shares one queue on a plane, so
        // whether the player is in front of a tree — or of a wall — is settled
        // by where they are standing rather than by a fixed layer order. The
        // side view's layers are fixed and correct, so its pass draws straight
        // through in call order.
        DepthPass standing = DepthPass.of(camera.getPerspective());
        phase("terrain", () -> drawTiles(target, standing)); // queues cracks with the block
        if (p.gridVisible) drawGrid(target); // projects to a diamond lattice in isometric
        if (!sceneryBehind) phase("decor", () -> drawDecorLayer(target, false, standing));
        drawDoors(target);
        phase("entities", () -> drawWorldEntities(target, p, standing));
        if (mgView != null) MiniGameHud.drawWorld(target, camera, level, mgView, animClock);
        if (net != null) drawRemotePlayers(target, standing);
        if (mgView != null) {
            MiniGameHud.drawTeamRing(target, camera, me.x + ps() / 2, me.y + ps(),
                    ps(), mgView.teamOf(me.id), camera.zoom);
        }
        // The local player, wearing whatever the object in their hands says
        // they should look like while doing this (see MeleeSprites), with the
        // object itself drawn in hand on top.
        standing.at(footDepth(me.x, me.y), () -> {
            drawPlayer(target, me.x, me.y, me.z, MeleeSprites.playerFrame(
                    me.characterKey, meleeItem, animState, me.facing, animStateClock,
                    melee.progress(), (int) ps(), character.body), null);
            drawHeldObject(target, me.x, me.y, me.z, ps(), me.facing, meleeItem,
                    melee.action(), melee.progress(), meleeProfile(p));
        });
        // The depth queue is where the plan views actually pay: everything
        // standing on the floor was deferred to here, sorted, and drawn.
        phase("depth-flush", standing::flush);
        if (melee.action() != MeleeAction.NONE) {
            drawMeleeArc(target, meleeProfile(p));
        } else if (swingTime > 0) {
            drawSwing(target);
        }
        if (cutscenes != null && cutscenes.active() != null) {
            CutscenePainter.drawActors(target, camera, cutscenes.active());
        }
        phase("decor", () -> drawDecorLayer(target, true)); // foreground covers players
        if (p.particlesEnabled) phase("particles", () -> particles.render(target, camera));
        if (net == null) drawDoorHint(target, p);
        drawVehicleHint(target, p);
        // Everything from here down is screen-space overlay rather than world
        // drawing, and it is almost entirely text — the 334 drawString calls a
        // GPU backend would need a baked glyph atlas to serve. Worth knowing
        // separately from the world it sits over.
        phase("hud", () -> {
            if (p.hudVisible) drawHud(target);
            if (mgView != null) {
                MiniGameHud.drawHud(target, viewportWidth, viewportHeight, mgView, me.id);
            }
            if (p.itemsEnabled) drawHotbar(target);
            if (p.combatEnabled || p.mobsEnabled) drawHealthBar(target);
            drawResourceBars(target);
            drawUltimateMeter(target);
            if (net == null) drawStatRuleBars(target);
            drawRuleStatus(target);
            if (net != null) drawEvents(target);
        });
        if (showInventory) drawInventory(target);
        if (craftingPanel != null) {
            craftingPanel.render(target, viewportWidth, viewportHeight, inventory, animClock);
        }
        if (containerPanel != null) {
            containerPanel.render(target, viewportWidth, viewportHeight, animClock);
        }
        if (cutscenes != null && cutscenes.active() != null) {
            CutscenePainter.drawOverlay(target, viewportWidth, viewportHeight, cutscenes.active());
        }

        if (paused) drawPauseOverlay(target);
        // The character choice sits over the built level, so a player sees the
        // world they are about to drop into behind the cards.
        if (picker != null) picker.render(target, viewportWidth, viewportHeight);
        if (net != null && !net.client().isConnected()) drawDisconnectOverlay(target);
    }

    /** Stamina (green) and mana (blue) bars stacked above the health bar. */
    private void drawResourceBars(DrawTarget target) {
        int w = 180, h = 8;
        int x = 12;
        drawResourceBar(target, x, viewportHeight - 40, w, h,
                me.maxStamina <= 0 ? 0 : me.stamina / me.maxStamina,
                new Color(40, 90, 40), new Color(110, 220, 110));
        drawResourceBar(target, x, viewportHeight - 52, w, h,
                me.maxMana <= 0 ? 0 : me.mana / me.maxMana,
                new Color(35, 45, 100), new Color(100, 140, 245));
    }

    /**
     * The ultimate meter, bottom-right (mirroring the health/stamina/mana
     * stack on the left, clear of the centred hotbar): a bar that fills with
     * time and damage dealt, glowing and naming its key once it is ready to
     * fire, and counting down while a sustained ability runs.
     */
    private void drawUltimateMeter(DrawTarget target) {
        Ultimate u = Ultimates.of(me);
        if (u == null) return;
        int w = 220, h = 16;
        int x = viewportWidth - w - 12, y = viewportHeight - 30;
        boolean ready = Ultimates.ready(me);
        boolean running = me.ultActive > 0;
        double fill = running ? me.ultActive / Math.max(0.001, u.duration()) : me.ultCharge;

        target.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 8, 8, new Color(0, 0, 0, 165));
        Color c = u.color();
        // A ready meter pulses so it catches the eye without a sound cue.
        int alpha = ready ? (int) (190 + 60 * Math.sin(animClock * 6)) : 190;
        target.fillRoundRect(x, y, (int) (w * Math.max(0, Math.min(1, fill))), h, 6, 6, new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.max(0, Math.min(255, alpha))));
        target.drawRoundRect(x, y, w, h, 6, 6, new Color(255, 255, 255, ready ? 220 : 90),
                ready ? 2f : 1f);

        String label = running
                ? u.name() + "  " + String.format("%.1fs", me.ultActive)
                : ready ? u.name() + "  [" + KeyBinds.label(GameAction.ULTIMATE) + "] READY"
                : u.name() + "  " + (int) (me.ultCharge * 100) + "%";
        target.drawText(label, x + (w - target.textWidth(label, SMALL_FONT)) / 2, y + h - 4,
                SMALL_FONT, Color.WHITE);
    }

    private void drawResourceBar(DrawTarget target, int x, int y, int w, int h,
                                 double t, Color back, Color front) {
        target.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 5, 5, new Color(0, 0, 0, 150));
        target.fillRect(x, y, w, h, back);
        target.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, t))), h, front);
    }

    /** The level's programmable stat bars (rules marked "show bar"), top-right. */
    private void drawStatRuleBars(DrawTarget target) {
        if (ruleEngine == null || stats == null || level.statRules.isEmpty()) return;
        int w = 170, h = 10;
        int x = viewportWidth - w - 14, y = 56;
        for (StatRule rule : level.statRules) {
            if (!rule.showBar()) continue;
            double t = ruleEngine.progress(rule, stats);
            target.fillRoundRect(x - 4, y - 13, w + 8, h + 18, 6, 6, new Color(0, 0, 0, 150));
            target.drawText(PlayerStats.label(rule.stat()) + "  "
                            + (long) stats.get(rule.stat()) + " / " + (long) rule.threshold(),
                    x, y - 3, SMALL_FONT, STAT_RULE_LABEL);
            target.fillRect(x, y, w, h, new Color(70, 60, 30));
            target.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, t))), h,
                    t >= 1 ? new Color(150, 230, 150) : new Color(240, 200, 90));
            y += h + 22;
        }
    }

    /** Transient "rule fired / crafted" toast above the hotbar. */
    private void drawRuleStatus(DrawTarget target) {
        if (ruleStatusTime <= 0 || ruleStatus.isEmpty()) return;
        int tw = target.textWidth(ruleStatus, HUD_FONT);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 110;
        target.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8, new Color(0, 0, 0, 170));
        target.drawText(ruleStatus, x, y, HUD_FONT, new Color(200, 240, 200));
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
        // Online, the server keeps applying the held input command — send an
        // idle one so the player doesn't keep walking (or mining) while the
        // menu is open.
        if (net != null) {
            net.client().sendInput(new PlayerInput(false, false, false, false, ++inputSeq));
            cancelPredictedMining();
        }
    }

    private void resume() {
        paused = false;
        bindsForm = null;
        syncCameraFromProfile();
    }

    /**
     * Quit an online session (host stop or client disconnect): tear the
     * session down and head for the menu. The scene keeps receiving render
     * calls through the fade transition, so {@link #leaving} silences it —
     * without that, the post-quit frames took the offline code paths against
     * the {@code world} an online session never had and crashed the loop.
     */
    private void leaveSession() {
        leaving = true;
        ctx.closeSession();
        net = null;
        scenes.transitionTo("menu");
    }

    private void buildPauseForm() {
        GameProfile p = profile();
        pauseForm = new ConfigForm("Paused — " + p.name).theme(MenuTheme.dark());
        if (net == null) {
            // A level's feature toggles are edited in Load Level → Edit
            // Settings, not here — the pause menu stays simple.
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Controls (Key Binds)", this::openKeyBinds);
            pauseForm.addAction("Save Level", this::saveLevel);
            pauseForm.addAction("Edit in Creative",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction("Quit to Menu", () -> scenes.transitionTo("menu"));
        } else {
            // Online the server owns the world.
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Controls (Key Binds)", this::openKeyBinds);
            pauseForm.addAction("Edit in Creative",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction(net.isHost() ? "Stop Server & Quit" : "Disconnect & Quit",
                    this::leaveSession);
        }
    }

    /** Open the controls sheet over the pause menu (see {@link #updatePaused}). */
    private void openKeyBinds() {
        bindsForm = KeyBindForm.forActiveBinds(() -> bindsForm = null);
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
        level.captureSettings(p);
        LevelStore store = new LevelStore(p.name);
        Path file = store.save(level);
        p.lastLevelPath = file.toString();
        ctx.save();
        ruleStatus = "Saved level \"" + level.name + "\"";
        ruleStatusTime = 3.0;
    }

    private void drawPauseOverlay(DrawTarget target) {
        target.pushAlpha(0.82f);
        target.fillRect(0, 0, viewportWidth, viewportHeight, PAUSE_SCRIM);
        target.popAlpha();

        if (bindsForm != null) {
            bindsForm.render(target, viewportWidth, viewportHeight);
            if (bindsForm.isCapturing()) {
                target.drawText("Press any key or mouse button · Esc to cancel", 24,
                        viewportHeight - 44, HUD_FONT, new Color(255, 210, 90));
            }
            target.drawText(KeyBindForm.HINT, 24, viewportHeight - 24, HUD_FONT,
                    new Color(120, 120, 140));
            return;
        }
        pauseForm.render(target, viewportWidth, viewportHeight);
        target.drawText(net == null
                        ? "Back to resume · Save Level keeps this world; edit toggles in "
                                + "Load Level → Edit Settings"
                        : "Back to resume · game keeps running on the server while paused",
                24, viewportHeight - 24, HUD_FONT, SceneChrome.HINT);
    }

    private void drawDisconnectOverlay(DrawTarget target) {
        target.pushAlpha(0.85f);
        target.fillRect(0, 0, viewportWidth, viewportHeight, DISCONNECT_SCRIM);
        target.popAlpha();

        String title = "Disconnected";
        target.drawText(title, (viewportWidth - target.textWidth(title, SANS_BOLD_26)) / 2,
                viewportHeight / 2 - 20, SANS_BOLD_26, new Color(235, 120, 110));

        String reason = net.client().disconnectReason();
        if (reason != null) {
            target.drawText(reason, (viewportWidth - target.textWidth(reason, HUD_FONT)) / 2,
                    viewportHeight / 2 + 8, HUD_FONT, new Color(200, 190, 190));
        }
        String hint = "Press Enter to return to the menu";
        target.drawText(hint, (viewportWidth - target.textWidth(hint, HUD_FONT)) / 2,
                viewportHeight / 2 + 40, HUD_FONT, new Color(150, 150, 160));
    }

    // --- profile-driven constraints ---

    /**
     * The perspective this session simulates and renders in: the loaded
     * level's own, always. The level carries its format, so playing a
     * side-scroller, a top-down map, or an isometric one is the same act — and
     * online the server simulates that same level's format, so client
     * prediction and the authoritative step agree.
     */
    private Perspective basePerspective() {
        return level.perspective;
    }

    private void enforceProfileConstraints(GameProfile p) {
        camera.setPerspective(basePerspective());
        camera.zoom = p.zoomEnabled ? clampZoom(camera.zoom, p) : clampZoom(p.defaultZoom, p);
    }

    private void syncCameraFromProfile() {
        GameProfile p = profile();
        camera.tileSize = level.tileSize;
        camera.setPerspective(basePerspective());
        camera.zoom = clampZoom(p.zoomEnabled ? camera.zoom : p.defaultZoom, p);
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

    /**
     * The level's terrain. In a side-scroller that is one flat pass; in the
     * plan views the floor is drawn now and the blocks stacked on it join
     * {@code standing}, so a wall sorts against the players and scenery around
     * it instead of being painted over them (see {@link TerrainPainter}).
     */
    private void drawTiles(DrawTarget target, DepthPass standing) {
        // The decorator is passed only when there is actually something to
        // decorate. A decorator that does nothing still forces the floor to be
        // repainted cell by cell, because the painter cannot know it is a
        // no-op — so "no open container" has to mean "no decorator".
        TerrainPainter.draw(target, level, camera, visibleTileBounds(), animClock,
                standing, containerPanel == null ? null : this::drawOpenLid,
                miningStroke(), terrainCache);
    }

    /**
     * The hold-to-mine stroke in progress, for the crack overlay, or
     * {@code null}. Offline it reads the world's stroke; online, the local
     * prediction.
     */
    private TerrainPainter.Mining miningStroke() {
        if (net != null) {
            return netMineCol == Integer.MIN_VALUE ? null
                    : new TerrainPainter.Mining(netMineCol, netMineRow, netMineProgress);
        }
        if (world == null) return null;
        int[] cell = world.miningCell();
        return cell == null ? null
                : new TerrainPainter.Mining(cell[0], cell[1], world.miningProgress());
    }

    /** The animated lid on the chest or barrel whose panel is open. */
    private void drawOpenLid(DrawTarget target, int col, int row, int[] quadX, int[] quadY,
                             Block block, Color color) {
        if (containerPanel == null || block == null || !block.container()) return;
        if (col != containerPanel.col() || row != containerPanel.row()) return;
        ContainerPanel.drawLid(target, quadX, quadY, containerPanel.openness(), color);
    }

    /**
     * One scenery layer: the free-standing decorations plus the block details
     * painted into it. Where it lands relative to the terrain is the level
     * format's call — behind the blocks in a side view, where they are a wall
     * standing between the camera and the distance; on top of them in the plan
     * views, where the same blocks are the floor the scenery is planted on and
     * "behind" would mean buried. {@code render} asks
     * {@link PerspectiveSpace#scenerySitsBehindTerrain()} which it is.
     */
    private void drawDecorLayer(DrawTarget target, boolean foreground) {
        DepthPass own = DepthPass.sorted();
        drawDecorLayer(target, foreground, own);
        own.flush();
    }

    /** One scenery layer, queued into a pass it shares with something else. */
    private void drawDecorLayer(DrawTarget target, boolean foreground, DepthPass into) {
        DecorPainter.draw(target, level, camera, foreground, animClock, into);
        SurfaceDecorPainter.draw(target, level, camera, visibleTileBounds(), foreground,
                animClock, into);
    }

    /**
     * The screen row a body standing at this world point puts its feet on —
     * what everything sharing a {@link DepthPass} is ordered by. {@code x,y}
     * is a sprite's top-left corner and {@code size} its world extent, the
     * way the level stores entities.
     */
    private int footDepth(double x, double y, double size) {
        return camera.worldToScreenY(x + size / 2, y + size);
    }

    /** {@link #footDepth} for a player-sized body. */
    private int footDepth(double x, double y) {
        return footDepth(x, y, ps());
    }

    /** Painted doors: tinted door shapes anchored at their base. */
    private void drawDoors(DrawTarget target) {
        double ts = ts();
        for (Level.EntitySpawn e : level.entities) {
            if (!"door".equals(e.kind)) continue;
            DoorLink link = doors.get(e.type);
            Color tint = link != null ? link.color() : new Color(150, 105, 60);
            int dw = Math.max(8, (int) Math.round(ts * 0.9 * camera.zoom));
            int dh = Math.max(12, (int) Math.round(ts * 1.6 * camera.zoom));
            camera.worldToScreen(e.x, e.y, corner);
            int x = corner[0] - dw / 2, y = corner[1] - dh;
            target.fillRoundRect(x, y, dw, dh, dw / 3, dw / 3, tint);
            target.drawRoundRect(x, y, dw, dh, dw / 3, dw / 3, tint.darker(), 2f);
            int knob = Math.max(2, dw / 6);
            target.fillOval(x + dw - knob * 2, y + dh / 2, knob, knob, new Color(255, 235, 170));
        }
    }

    /** "[E] Enter …" prompt while standing at a linked door. */
    private void drawDoorHint(DrawTarget target, GameProfile p) {
        double half = p.playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(me.x + half, me.y + half, ts() * 1.3);
        if (door == null) return;
        DoorLink link = doors.get(door.type);
        String text = link == null || link.targetLevel().isEmpty()
                ? "This door leads nowhere (yet)"
                : "[" + KeyBinds.label(GameAction.INTERACT) + "] Enter " + link.label();
        int tw = target.textWidth(text, HUD_FONT);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 88;
        target.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8, new Color(0, 0, 0, 170));
        target.drawText(text, x, y, HUD_FONT, new Color(255, 230, 160));
    }

    /** "[E] Ride …" / "[E] Dismount" prompt near vehicles, above the door hint. */
    private void drawVehicleHint(DrawTarget target, GameProfile p) {
        String text = null;
        if (net == null) {
            if (world == null) return;
            if (me.riding >= 0) {
                Vehicle v = world.vehicle(me.riding);
                if (v != null) {
                    text = "[" + KeyBinds.label(GameAction.INTERACT) + "] Dismount " + v.def.name()
                            + (v.def.projectile() != null ? " · click fires" : "");
                }
            } else {
                Vehicle near = world.mountableNear(me.x + ps() / 2, me.y + ps() / 2);
                if (near != null) {
                    text = "[" + KeyBinds.label(GameAction.INTERACT) + "] Ride "
                            + near.def.name();
                }
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap == null) return;
            if (predictedVehicle != null) {
                text = "[" + KeyBinds.label(GameAction.INTERACT) + "] Dismount "
                        + predictedVehicle.def.name()
                        + (predictedVehicle.def.projectile() != null ? " · click fires" : "");
            } else {
                EntityView near = nearestSnapshotVehicle(snap);
                VehicleDef def = near == null ? null
                        : VehicleRegistry.standard().get(near.key);
                if (def != null) {
                    text = "[" + KeyBinds.label(GameAction.INTERACT) + "] Ride " + def.name();
                }
            }
        }
        if (text == null) return;
        int tw = target.textWidth(text, HUD_FONT);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 116;
        target.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8, new Color(0, 0, 0, 170));
        target.drawText(text, x, y, HUD_FONT, new Color(170, 225, 255));
    }

    private void drawGrid(DrawTarget target) {
        int ts = (int) ts();
        int[] b = visibleTileBounds();
        for (int c = b[0]; c <= b[2] + 1; c++) {
            double wx = c * ts;
            target.drawLine(camera.worldToScreenX(wx, b[1] * ts),
                    camera.worldToScreenY(wx, b[1] * ts),
                    camera.worldToScreenX(wx, (b[3] + 1) * ts),
                    camera.worldToScreenY(wx, (b[3] + 1) * ts), new Color(255, 255, 255, 30));
        }
        for (int r = b[1]; r <= b[3] + 1; r++) {
            double wy = r * ts;
            target.drawLine(camera.worldToScreenX(b[0] * ts, wy),
                    camera.worldToScreenY(b[0] * ts, wy),
                    camera.worldToScreenX((b[2] + 1) * ts, wy),
                    camera.worldToScreenY((b[2] + 1) * ts, wy), new Color(255, 255, 255, 30));
        }
    }

    /** Mobs + items + projectiles + vehicles: the offline world's, or the snapshot's. */
    private void drawWorldEntities(DrawTarget target, GameProfile p, DepthPass into) {
        if (net == null) {
            for (Vehicle v : world.vehicles()) {
                into.at(footDepth(v.x, v.y, v.def.size()), () ->
                        drawVehicleSprite(target, v.def, v.x, v.y, v.facingLeft));
            }
            for (DroppedItem item : world.items()) {
                into.at(footDepth(item.x, item.y, DroppedItem.SIZE), () ->
                        drawItemSprite(target, item.key, item.x, item.y, item.count));
            }
            for (Mob m : world.mobs()) {
                // A mob mid-move draws that move, on its weapon's own sheets.
                String state = m.meleeAction().isEmpty()
                        ? stateKeyFor(m.state.ordinal(), m.hurting()) : m.meleeAction();
                into.at(footDepth(m.x, m.y, m.def.size()), () ->
                        drawMobSprite(target, m.def, m.x, m.y, m.facing, m.health, m.hurting(),
                                state, m.statusBits(), m.weaponKey(),
                                m.melee.action(), m.meleeProgress()));
            }
            for (Projectile pr : world.projectiles()) {
                into.at(footDepth(pr.x, pr.y, 0), () ->
                        drawProjectileSprite(target, pr.def.key(), pr.x, pr.y, pr.z, pr.vx, pr.vy));
            }
        } else {
            // Replicated entities interpolate between the two buffered
            // snapshots straddling the render time — drawing the raw latest
            // snapshot stepped everything at the 30 Hz broadcast rate, which
            // read as constant stutter next to the 120 fps local player.
            long renderTime = System.nanoTime() - INTERP_DELAY_NANOS;
            Snapshot[] pair = net.client().snapshotPair(renderTime);
            if (pair == null) return;
            Snapshot from = pair[0], to = pair[1];
            double t = interpFactor(from, to, renderTime);
            VehicleRegistry vehicles = VehicleRegistry.standard();
            Map<Integer, EntityView> old = viewsById(from.vehicles());
            for (EntityView v : to.vehicles()) {
                // The vehicle we're riding renders from our own prediction so
                // it never lags behind the player glued to its saddle.
                if (predictedVehicle != null && v.id == predictedVehicle.id) continue;
                VehicleDef def = vehicles.get(v.key);
                if (def != null) {
                    double vx = lerpX(old.get(v.id), v, t), vy = lerpY(old.get(v.id), v, t);
                    into.at(footDepth(vx, vy, def.size()), () ->
                            drawVehicleSprite(target, def, vx, vy, v.facingLeft));
                }
            }
            if (predictedVehicle != null) {
                into.at(footDepth(predictedVehicle.x, predictedVehicle.y,
                        predictedVehicle.def.size()), () ->
                        drawVehicleSprite(target, predictedVehicle.def, predictedVehicle.x,
                                predictedVehicle.y, predictedVehicle.facingLeft));
            }
            old = viewsById(from.items());
            for (EntityView item : to.items()) {
                double ix = lerpX(old.get(item.id), item, t);
                double iy = lerpY(old.get(item.id), item, t);
                into.at(footDepth(ix, iy, DroppedItem.SIZE), () ->
                        drawItemSprite(target, item.key, ix, iy, item.count));
            }
            MobRegistry mobs = MobRegistry.standard();
            old = viewsById(from.mobs());
            for (EntityView mv : to.mobs()) {
                MobDef def = mobs.get(mv.key);
                if (def != null) {
                    double mx = lerpX(old.get(mv.id), mv, t);
                    double my = lerpY(old.get(mv.id), mv, t);
                    // The move the server says it is mid-way through, drawn on
                    // the weapon the server says it carries.
                    String state = mv.meleeAction.isEmpty()
                            ? stateKeyFor(mv.aiState, false) : mv.meleeAction;
                    MeleeAction move = MeleeAction.byKey(mv.meleeAction);
                    into.at(footDepth(mx, my, def.size()), () ->
                            drawMobSprite(target, def, mx, my, mv.facing, mv.health, false,
                                    state, mv.status, mv.weapon, move, mv.meleeProgress));
                }
            }
            old = viewsById(from.shots());
            for (EntityView s : to.shots()) {
                double sx = lerpX(old.get(s.id), s, t), sy = lerpY(old.get(s.id), s, t);
                into.at(footDepth(sx, sy, 0), () ->
                        drawProjectileSprite(target, s.key, sx, sy, s.z, s.vx, s.vy));
            }
        }
    }

    private static Map<Integer, EntityView> viewsById(List<EntityView> views) {
        if (views.isEmpty()) return Map.of();
        Map<Integer, EntityView> byId = new HashMap<>(views.size() * 2);
        for (EntityView v : views) byId.put(v.id, v);
        return byId;
    }

    private static double lerpX(EntityView from, EntityView to, double t) {
        return from == null ? to.x : from.x + (to.x - from.x) * t;
    }

    private static double lerpY(EntityView from, EntityView to, double t) {
        return from == null ? to.y : from.y + (to.y - from.y) * t;
    }

    /** A vehicle, flipped to its facing like mobs are. */
    private void drawVehicleSprite(DrawTarget target, VehicleDef def, double x, double y,
                                   boolean facingLeft) {
        BufferedImage img = Skins.frame("vehicle/" + def.key(), animClock);
        if (img == null) img = EntitySprites.vehicle(def, 48);
        int w = (int) Math.round(def.size() * camera.zoom);
        camera.worldToScreen(x + def.size() / 2, y + def.size(), corner);
        int dx = corner[0] - w / 2;
        int dy = corner[1] - w;
        // Negative width mirrors; see DrawTarget.drawImage.
        if (facingLeft) target.drawImage(img, dx + w, dy, -w, w);
        else target.drawImage(img, dx, dy, w, w);
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

    /**
     * A projectile, rotated to its flight direction. Its texture is skinnable
     * like everything else ({@code projectile/<key>} — the drop-in pack or the
     * creative Effects palette); the procedural bolt is the fallback.
     *
     * <p>It is drawn where its level's space says it is: a side-scrolling shot
     * is simply at (x, y), while a plan-view shot with height on it — a meteor
     * still falling — draws above the floor tile it will hit, over a shrinking
     * shadow that marks the target, and grows as it rises in a top-down level,
     * where up points at the viewer.
     */
    private void drawProjectileSprite(DrawTarget target, String key, double x, double y,
                                      double z, double vx, double vy) {
        ProjectileDef def = projectileTypes().get(key);
        if (def == null) return;
        BufferedImage img = Skins.frame("projectile/" + key, animClock);
        if (img == null) img = EntitySprites.projectile(def, 16);
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        int w = Math.max(8, (int) Math.round(def.radius() * 3.5 * camera.zoom
                * space.heightScale(z, ts())));
        camera.worldToScreen(x, y, corner);
        int lift = (int) Math.round(z * space.screenLift() * camera.zoom);
        if (lift > 0) {
            double shrink = Math.max(0.3, 1 - z / (ts() * 8));
            int sw = Math.max(3, (int) (w * 0.6 * shrink));
            target.fillOval(corner[0] - sw / 2, corner[1] - sw / 4, sw,
                    Math.max(2, sw / 2), new Color(0, 0, 0, (int) (80 * shrink)));
        }
        AffineTransform spin = AffineTransform.getTranslateInstance(
                corner[0], corner[1] - lift);
        if (vx != 0 || vy != 0) spin.rotate(Math.atan2(vy, vx));
        target.pushTransform(spin);
        target.drawImage(img, -w / 2, -w / 2, w, w);
        target.popTransform();
    }

    /**
     * A mob, drawn for the direction it faces. The texture resolves from the
     * most specific sheet outward — {@code mob/<key>/<state>/<dir>}, this
     * direction's mirror twin (drawn flipped), the state's own sheet, the
     * mob's idle sheet — and falls back to the pre-generated directional art,
     * which is already drawn facing the right way and so is never flipped.
     */
    private void drawMobSprite(DrawTarget target, MobDef def, double x, double y,
                               Facing facing, double health, boolean hurt,
                               String state, int statusBits) {
        drawMobSprite(target, def, x, y, facing, health, hurt, state, statusBits,
                def.weapon() == null ? "" : def.weapon(), MeleeAction.NONE, 0);
    }

    /**
     * {@link #drawMobSprite} for a mob mid-melee-move: the weapon it carries
     * gets first say over how its body is drawn ({@code wield/<item>/<move>}),
     * and the weapon itself is drawn in its hands — the same two sheets a
     * player holding the same thing resolves.
     */
    private void drawMobSprite(DrawTarget target, MobDef def, double x, double y,
                               Facing facing, double health, boolean hurt,
                               String state, int statusBits, String weapon,
                               MeleeAction move, double moveProgress) {
        Facing dir = facing == null ? Facing.EAST : facing;
        PlayerSprites.Frame resolved = MeleeSprites.mobFrame(def.key(), weapon, state,
                dir, animClock, moveProgress);
        BufferedImage img = resolved == null ? null : resolved.image();
        boolean mirror = resolved != null && resolved.mirrored();
        if (img == null) {
            img = EntitySprites.mob(def, 32, dir);
            mirror = false;
        }
        int w = (int) Math.round(def.size() * camera.zoom);
        camera.worldToScreen(x + def.size() / 2, y + def.size(), corner);
        int dx = corner[0] - w / 2;
        int dy = corner[1] - w;
        if (mirror) target.drawImage(img, dx + w, dy, -w, w);
        else target.drawImage(img, dx, dy, w, w);
        // Whatever it fights with, drawn in its hands and swept by the move.
        drawHeldObject(target, x, y, 0, def.size(), dir, weapon,
                move, moveProgress, MeleeProfiles.ofKey(weapon));
        if (hurt) target.fillRect(dx, dy, w, w, HURT_TINT);
        // Elemental status tints (replicated bits, so online matches offline).
        if ((statusBits & Mob.STATUS_BURNING) != 0) target.fillRect(dx, dy, w, w, BURNING_TINT);
        if ((statusBits & Mob.STATUS_CHILLED) != 0) target.fillRect(dx, dy, w, w, CHILLED_TINT);
        if ((statusBits & Mob.STATUS_POISONED) != 0) target.fillRect(dx, dy, w, w, POISONED_TINT);
        if ((statusBits & Mob.STATUS_SHIELDED) != 0) {
            target.drawOval(dx - 3, dy - 3, w + 6, w + 6, SHIELD_RING.getRGB(), 2f);
        }
        if (health < def.maxHealth() - 0.01) {
            int bw = Math.max(14, w);
            target.fillRect(dx + w / 2 - bw / 2, dy - 7, bw, 4, HEALTH_BACK);
            target.fillRect(dx + w / 2 - bw / 2, dy - 7,
                    (int) (bw * Math.max(0, health / def.maxHealth())), 4, HEALTH_FILL);
        }
    }

    private void drawItemSprite(DrawTarget target, String key, double x, double y, int count) {
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
            target.fillOval(corner[0], corner[1] + w - w / 4, w, w / 2, DROP_SHADOW);
        }
        drawRarityHalo(target, def, corner[0] + w / 2, corner[1] + dy + w / 2, w);
        target.drawImage(img, corner[0], corner[1] + dy, w, w);
        if (count > 1) {
            target.drawText("x" + count, corner[0] + w, corner[1] + dy + w,
                    SMALL_FONT, Color.WHITE);
        }
    }

    /**
     * The coloured halo behind an uncommon+ dropped item: a soft radial
     * gradient in the rarity tier's colour, gently pulsing — visible in
     * daylight, and matched by a real point light after dark (see
     * {@link #feedLighting}).
     */
    private void drawRarityHalo(DrawTarget target, ItemDef def, int cx, int cy, int itemPx) {
        if (def.rarity() == ItemDef.Rarity.COMMON) return;
        float pulse = 0.82f + 0.18f * (float) Math.sin(animClock * 3
                + def.key().hashCode() % 7);
        float radius = Math.max(4f, itemPx * (1.1f + 0.35f * def.rarity().ordinal()) * pulse);
        Color c = def.rarity().color;
        target.fillRadialGradient((int) cx, (int) cy, (int) radius, HALO_STOPS, new int[]{
                new Color(c.getRed(), c.getGreen(), c.getBlue(), 110).getRGB(),
                new Color(c.getRed(), c.getGreen(), c.getBlue(), 46).getRGB(),
                new Color(c.getRed(), c.getGreen(), c.getBlue(), 0).getRGB()});
    }

    /** A short arc in front of the player while a mining or firing stroke plays. */
    private void drawSwing(DrawTarget target) {
        double size = ps();
        camera.worldToScreen(me.x + size / 2, me.y + size / 2, corner);
        int r = (int) (size * camera.zoom * 0.9);
        int start = me.facingLeft ? 120 : -60;
        target.drawArc(corner[0] - r, corner[1] - r, r * 2, r * 2, start, 120,
                new Color(255, 255, 255, (int) (150 * Math.max(0, swingTime / 0.2))), 3f);
    }

    /**
     * The melee move itself, drawn at the weapon's own reach and arc: a bright
     * sweep tracking the strike window, a narrow thrust for a lunge, a bracing
     * shield in front of a raised guard, and a ring when a parry catches
     * something.
     */
    private void drawMeleeArc(DrawTarget target, MeleeProfile profile) {
        double size = ps();
        camera.worldToScreen(me.x + size / 2, me.y + size / 2, corner);
        int r = (int) Math.round(profile.reach() * camera.zoom);
        MeleeAction action = melee.action();
        double t = melee.progress();
        // The sweep is brightest through the hit window and fades out with the
        // recovery, so what you see is what is actually dangerous.
        int alpha = (int) (200 * (melee.striking() ? 1 : 0.35));
        double facingDeg = -Math.toDegrees(Math.atan2(me.facing.dy(), me.facing.dx()));
        switch (action) {
            case SWING, LUNGE -> {
                double arc = action == MeleeAction.LUNGE
                        ? Math.min(40, profile.arc() * 0.4) : profile.arc();
                // The arc travels through its own width across the move.
                double lead = facingDeg + arc / 2 - arc * t;
                target.drawArc(corner[0] - r, corner[1] - r, r * 2, r * 2,
                        (int) Math.round(lead - arc / 4), (int) Math.round(arc / 2),
                        new Color(255, 255, 255, Math.max(0, alpha)), 3f);
            }
            case PARRY -> {
                boolean caught = melee.parryFlash() > 0;
                int pr = (int) (r * 0.8);
                target.drawArc(corner[0] - pr, corner[1] - pr, pr * 2, pr * 2, (int) Math.round(facingDeg - 45), 90, caught ? new Color(255, 245, 200, 220)
                        : new Color(200, 225, 255, alpha), caught ? 4f : 2.5f);
            }
            case SHIELD -> {
                int sr = (int) (r * 0.75);
                target.drawArc(corner[0] - sr, corner[1] - sr, sr * 2, sr * 2, (int) Math.round(facingDeg - 55), 110, new Color(190, 215, 255,
                        melee.parryFlash() > 0 ? 220 : 120), 4f);
            }
            case DASH -> {
                // A motion streak behind the roll rather than a weapon arc.
                int dr = (int) (r * 0.6);
                target.drawOval(corner[0] - dr, corner[1] - dr / 2, dr * 2, dr,
                        new Color(235, 240, 255, (int) (120 * (1 - t))), 2f);
            }
            default -> { /* nothing running */ }
        }
    }

    /**
     * The object in a fighter's hands, swept through the move. Its sheet comes
     * from {@code item/<key>/<move>} and falls back to the plain icon, so an
     * un-animated item still shows up in hand; where it sits and how it is
     * angled comes from {@link MeleeSprites#hold}, shared with every other
     * scene that draws a fighter.
     */
    private void drawHeldObject(DrawTarget target, double x, double y, double z, double size,
                                Facing facing, String itemKey, MeleeAction action,
                                double progress, MeleeProfile profile) {
        if (itemKey == null || itemKey.isEmpty()) return;
        BufferedImage img = MeleeSprites.heldFrame(itemKey, action.key(), animClock, progress);
        if (img == null) {
            ItemDef def = (world != null ? world.itemTypes : ItemRegistry.standard())
                    .get(itemKey);
            if (def == null) return;
            img = EntitySprites.item(def, 16);
        }
        MeleeSprites.Hold hold = MeleeSprites.hold(action, profile, progress);
        int w = Math.max(6, (int) Math.round(size * hold.scale() * camera.zoom * 0.7));
        Facing dir = facing == null ? Facing.EAST : facing;
        int flip = dir.facingLeft() ? -1 : 1;
        int footX = camera.worldToScreenX(x + size / 2.0, y + size);
        int footY = camera.worldToScreenY(x + size / 2.0, y + size);
        int lift = (int) Math.round(z * camera.zoom * PlayerPhysics.HOP_DRAW_SCALE);
        double cx = footX + flip * hold.offsetX() * size * camera.zoom;
        double cy = footY - size * camera.zoom * 0.5 - lift
                + hold.offsetY() * size * camera.zoom;

        // Rotate about the grip rather than the image's corner: the held item
        // swings with the hand, so the pivot is the hand.
        AffineTransform swing = AffineTransform.getTranslateInstance(cx, cy);
        swing.rotate(flip * hold.angle());
        target.pushTransform(swing);
        target.drawImage(img, flip * -w / 2, -w / 2, flip * w, w);
        target.popTransform();
    }

    /**
     * Draw every other player, interpolated at a fixed delay behind real time
     * between the two buffered snapshots that straddle it.
     */
    private void drawRemotePlayers(DrawTarget target, DepthPass into) {
        long renderTime = System.nanoTime() - INTERP_DELAY_NANOS;
        Snapshot[] pair = net.client().snapshotPair(renderTime);
        if (pair == null) return;
        Snapshot from = pair[0], to = pair[1];
        double t = interpFactor(from, to, renderTime);

        double size = ps();
        for (PlayerState ps : to.players()) {
            if (ps.id == me.id) continue;
            PlayerState old = from.player(ps.id);
            double x = old != null ? old.x + (ps.x - old.x) * t : ps.x;
            double y = old != null ? old.y + (ps.y - old.y) * t : ps.y;
            if (mgView != null) {
                MiniGameHud.drawTeamRing(target, camera, x + size / 2, y + size,
                        size, mgView.teamOf(ps.id), camera.zoom);
            }
            // Remote players wear their own character's skin, hold their own
            // weapon, and face their own direction — all of it rides along in
            // the snapshot, so a swing looks like a swing from across the map.
            CharacterProfile theirs = Characters.getOrDefault(ps.characterKey);
            Color body = remoteBody(ps.id, theirs);
            String state = ps.meleeAction.isEmpty()
                    ? (ps.moving ? "walk" : "idle") : ps.meleeAction;
            PlayerSprites.Frame sprite = MeleeSprites.playerFrame(
                    ps.characterKey, ps.heldKey, state, ps.facing,
                    animClock, ps.meleeProgress, (int) size, body);
            MeleeAction move = MeleeAction.byKey(ps.meleeAction);
            into.at(footDepth(x, y), () -> {
                drawPlayer(target, x, y, ps.z, sprite, ps.name);
                drawHeldObject(target, x, y, ps.z, size, ps.facing, ps.heldKey, move,
                        ps.meleeProgress, MeleeProfiles.ofKey(ps.heldKey));
            });
        }
    }

    /**
     * The body colour a remote player is drawn in: their character profile's,
     * or — for the default character, where everyone would look alike — a
     * stable per-id hue, replaced by the team colour in a mini game.
     */
    private Color remoteBody(int id, CharacterProfile theirs) {
        MiniGameView v = mgView;
        if (v != null && v.teamOf(id) >= 0) return Team.color(v.teamOf(id));
        if (theirs != null && !CharacterProfile.DEFAULT_KEY.equals(theirs.key)) {
            return theirs.body;
        }
        // Golden-ratio hue spacing gives each player a distinct, stable colour.
        return Color.getHSBColor((id * 0.6180339887f) % 1f, 0.6f, 0.85f);
    }

    /** Interpolation fraction of {@code renderTime} between two snapshots. */
    private static double interpFactor(Snapshot from, Snapshot to, long renderTime) {
        if (to == from || to.receivedNanos() <= from.receivedNanos()) return 1.0;
        double t = (renderTime - from.receivedNanos())
                / (double) (to.receivedNanos() - from.receivedNanos());
        return Math.max(0.0, Math.min(1.0, t));
    }

    /**
     * Draw a player: their directional sprite, lifted by any plan-view hop
     * (over a shadow that stays on the ground, so the height reads), and
     * mirrored only when the sprite that resolved is east-facing art standing
     * in for a westward facing.
     */
    private void drawPlayer(DrawTarget target, double x, double y, double z,
                            PlayerSprites.Frame sprite, String nameTag) {
        if (sprite == null || sprite.image() == null) return;
        double size = ps();
        int w = (int) Math.round(size * camera.zoom);
        int h = w;
        int footX = camera.worldToScreenX(x + size / 2.0, y + size);
        int footY = camera.worldToScreenY(x + size / 2.0, y + size);
        int dx = footX - w / 2;
        int lift = (int) Math.round(z * camera.zoom * PlayerPhysics.HOP_DRAW_SCALE);
        if (lift > 0) {
            // The shadow marks where they will land, shrinking with height.
            double shrink = Math.max(0.35, 1 - z / (size * 3));
            int sw = (int) (w * 0.7 * shrink), sh = Math.max(2, (int) (w * 0.25 * shrink));
            target.fillOval(footX - sw / 2, footY - sh / 2, sw, sh,
                    new Color(0, 0, 0, (int) (90 * shrink)));
        }
        int dy = footY - h - lift;
        if (sprite.mirrored()) {
            target.drawImage(sprite.image(), dx + w, dy, -w, h);
        } else {
            target.drawImage(sprite.image(), dx, dy, w, h);
        }
        if (nameTag != null && !nameTag.isEmpty()) {
            int tw = target.textWidth(nameTag, NAME_FONT);
            int tx = footX - tw / 2;
            int ty = dy - 6;
            target.fillRoundRect(tx - 4, ty - 12, tw + 8, 16, 6, 6, new Color(0, 0, 0, 140));
            target.drawText(nameTag, tx, ty, NAME_FONT, Color.WHITE);
        }
    }

    private void drawHud(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, 38, new Color(0, 0, 0, 150));
        StringBuilder hud = new StringBuilder();
        // Naming where up points says which physics this level is running —
        // the formats differ in more than how they are drawn.
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        hud.append(profile().name)
                .append("    |    ").append(camera.getPerspective())
                .append(" · up is ").append(space.upLabel());
        if (net != null) {
            Snapshot snap = net.client().latest();
            int online = snap != null ? snap.players().size() : 1;
            hud.append("    |    online ").append(online);
            int ping = net.client().pingMillis();
            if (ping >= 0) hud.append(" · ").append(ping).append(" ms");
            if (net.isHost()) hud.append(" · hosting :").append(net.hostedServer().getPort());
        }
        if (profile().zoomEnabled) hud.append("    |    zoom ").append(String.format("%.2f", camera.zoom));
        hud.append("    |    [").append(KeyBinds.label(GameAction.PAUSE)).append("] pause");
        if (profile().zoomEnabled) {
            hud.append("  [").append(KeyBinds.label(GameAction.ZOOM_IN)).append("/")
                    .append(KeyBinds.label(GameAction.ZOOM_OUT)).append("] zoom");
        }
        if (profile().itemsEnabled) {
            hud.append("  [").append(KeyBinds.label(GameAction.INVENTORY)).append("] inventory");
        }
        if (Ultimates.of(me) != null) {
            hud.append("  [").append(KeyBinds.label(GameAction.ULTIMATE)).append("] ultimate");
        }
        target.drawText(hud.toString(), 12, 24, HUD_FONT, Color.WHITE);
    }

    private void drawHealthBar(DrawTarget target) {
        int w = 180, h = 14;
        int x = 12, y = viewportHeight - 28;
        target.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 6, 6, new Color(0, 0, 0, 160));
        target.fillRect(x, y, w, h, new Color(120, 30, 30));
        target.fillRect(x, y, (int) (w * Math.max(0, me.health / me.maxHealth)), h,
                new Color(220, 60, 60));
        target.drawText((int) Math.ceil(me.health) + " / " + (int) me.maxHealth, x + w / 2 - 20,
                y + 11, SMALL_FONT, Color.WHITE);
    }

    private void drawHotbar(DrawTarget target) {
        int slot = 44, pad = 5;
        int total = Inventory.HOTBAR * (slot + pad) - pad;
        int x0 = (viewportWidth - total) / 2;
        int y0 = viewportHeight - slot - 10;
        for (int i = 0; i < Inventory.HOTBAR; i++) {
            int x = x0 + i * (slot + pad);
            boolean sel = inventory.selectedIndex() == i;
            target.fillRoundRect(x, y0, slot, slot, 8, 8, new Color(0, 0, 0, sel ? 200 : 140));
            target.drawRoundRect(x, y0, slot, slot, 8, 8,
                    sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 70), sel ? 2.5f : 1f);
            drawStack(target, inventory.slot(i), x, y0, slot);
            target.drawText(String.valueOf(i + 1), x + 4, y0 + 12, SMALL_FONT,
                    new Color(255, 255, 255, 130));
        }
        drawSelectedItemName(target, inventory.selectedDef(), y0);
    }

    /** The selected hotbar item's name, floated above the bar in its rarity colour. */
    private void drawSelectedItemName(DrawTarget target, ItemDef def, int hotbarTop) {
        if (def == null) return;
        int tw = target.textWidth(def.name(), HUD_FONT);
        int x = (viewportWidth - tw) / 2, y = hotbarTop - 10;
        target.fillRoundRect(x - 8, y - 14, tw + 16, 20, 8, 8, new Color(0, 0, 0, 160));
        target.drawText(def.name(), x, y, HUD_FONT, def.rarity().color);
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

    private void drawInventory(DrawTarget target) {
        int[] o = inventoryOrigin();
        int x0 = o[0], y0 = o[1];
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;

        target.fillRoundRect(x0 - 20, y0 - 52, gw + 40, gh + 84, 14, 14, new Color(10, 10, 16, 220));
        target.drawText("Inventory", x0, y0 - 24, SANS_BOLD_16, Color.WHITE);
        String drop = KeyBinds.label(GameAction.DROP_ITEM);
        String close = KeyBinds.label(GameAction.MENU_BACK);
        target.drawText(containerPanel != null
                ? "Click to pick up / place stacks · [" + drop + "] stash · ["
                        + KeyBinds.label(GameAction.INTERACT) + "]/[" + close + "] close"
                : "Click to pick up / place stacks · click outside to drop"
                        + " · [" + drop + "] drop one · ["
                        + KeyBinds.label(GameAction.USE_ITEM) + "] eat · ["
                        + KeyBinds.label(
                                GameAction.INVENTORY) + "]/[" + close + "] close", x0, y0 - 8, SMALL_FONT, new Color(170,
                                170, 190));

        for (int i = 0; i < Inventory.SIZE; i++) {
            int cx = x0 + (i % Inventory.COLS) * (INV_SLOT + INV_PAD);
            int cy = y0 + (i / Inventory.COLS) * (INV_SLOT + INV_PAD);
            boolean hotbar = i < Inventory.HOTBAR;
            boolean sel = i == inventory.selectedIndex();
            target.fillRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8,
                    new Color(255, 255, 255, hotbar ? 36 : 18));
            target.drawRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8,
                    sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 60), sel ? 2.5f : 1f);
            if (i == cursorSlot) continue; // it's on the cursor, not in the grid
            drawStack(target, inventory.slot(i), cx, cy, INV_SLOT);
        }

        // The picked-up stack follows the mouse until it's placed or dropped.
        if (cursorSlot >= 0) {
            ItemStack held = inventory.slot(cursorSlot);
            if (held == null) {
                cursorSlot = -1; // e.g. a server inv push emptied it
            } else {
                drawStack(target, held, mouseX - INV_SLOT / 2, mouseY - INV_SLOT / 2, INV_SLOT);
            }
        }
    }

    private void drawStack(DrawTarget target, ItemStack stack, int x, int y, int slot) {
        if (stack == null) return;
        ItemDef def = (world != null ? world.itemTypes : ItemRegistry.standard()).get(stack.key);
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + stack.key, animClock);
        if (img == null) img = EntitySprites.item(def, 32);
        target.drawImage(img, x + 6, y + 6, slot - 12, slot - 12);
        drawDurabilityBar(target, def, stack, x, y, slot);
        if (stack.count > 1) {
            String n = String.valueOf(stack.count);
            int tw = target.textWidth(n, SMALL_FONT);
            target.drawText(n, x + slot - tw - 3, y + slot - 3, SMALL_FONT, Color.BLACK);
            target.drawText(n, x + slot - tw - 4, y + slot - 4, SMALL_FONT, Color.WHITE);
        }
    }

    /** Green-to-red wear bar under a worn tool's icon. */
    static void drawDurabilityBar(DrawTarget target, ItemDef def, ItemStack stack,
                                  int x, int y, int slot) {
        if (def.maxDurability() <= 0 || stack.wear <= 0) return;
        double t = 1.0 - stack.wear / (double) def.maxDurability();
        target.fillRect(x + 6, y + slot - 8, slot - 12, 4, new Color(0, 0, 0, 170));
        target.fillRect(x + 6, y + slot - 8, (int) ((slot - 12) * Math.max(0, t)), 4,
                new Color((int) (220 * (1 - t) + 60 * t), (int) (60 * (1 - t) + 210 * t), 50));
    }

    /** Server chat-style event feed ("X joined"), bottom-left. */
    private void drawEvents(DrawTarget target) {
        List<String> events = net.client().recentEvents();
        if (events.isEmpty()) return;
        int y = viewportHeight - 48;
        for (int i = events.size() - 1; i >= 0; i--) {
            int tw = target.textWidth(events.get(i), HUD_FONT);
            target.fillRoundRect(8, y - 14, tw + 12, 19, 6, 6, new Color(0, 0, 0, 120));
            target.drawText(events.get(i), 14, y, HUD_FONT, new Color(220, 220, 230));
            y -= 22;
        }
    }

    // --- world helpers ---

    private double ts() { return level.tileSize; }

    private double ps() { return profile().playerSize; }

}
