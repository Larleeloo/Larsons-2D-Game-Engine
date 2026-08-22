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
import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.config.PlayerSettingsStore;
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
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SolidPainter;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.TerrainCache;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.Viewpoint;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.input.Pointer;
import com.larsons.engine.level.CutsceneDirector;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.save.RunRecord;
import com.larsons.engine.save.RunSession;
import com.larsons.engine.save.SaveStore;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameView;
import com.larsons.engine.minigame.Team;
import com.larsons.engine.net.GameClient;
import com.larsons.engine.net.NetSession;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.sim.ActorSize;
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
import com.larsons.engine.ui.PlayerOptionsForm;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.World;
import com.larsons.engine.world.gen.TerrainSettings;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // --- the view the player is looking through (F5) --------------------------
    //
    // Per client and never networked, exactly like the flat camera's heading:
    // two players in one world may be standing in entirely different views, and
    // the server neither knows nor needs to.

    /** Where the player's camera stands. Starts on the level's own view. */
    private Viewpoint viewpoint = Viewpoint.PLAN;
    /** The eye the solid views are drawn through; idle while {@link #viewpoint} is flat. */
    private final EyeCamera eye = new EyeCamera();
    /** The renderer behind the solid views. */
    private final SolidPainter solid = new SolidPainter();
    /**
     * Where the player is looking, radians clockwise from north — the solid
     * views' heading, and what movement is steered by while one is on.
     *
     * <p>Kept apart from {@link Camera#yaw()} because the two turn differently
     * and must not fight: the flat camera snaps between eight compass points,
     * because its terrain cache, its tile blits and its golden frames all
     * depend on resting exactly on one, while an eye that a mouse is steering
     * has to be free to sit anywhere. They are exchanged when the view is
     * toggled — see {@link #cycleViewpoint} — so turning around in one view and
     * switching to the other leaves you facing the same way.
     */
    private double lookYaw;
    /** How far the eye is tilted, radians, positive looking up. */
    private double lookPitch;
    /**
     * This frame's pointer motion, {@code {dx, dy}} — filled by
     * {@link InputManager#consumeMouseMotion} and reused rather than allocated,
     * because it is read every frame a mouse-look view is up.
     */
    private final int[] lookMotion = new int[2];
    /** Whether the motion banked so far predates this view and must be dropped. */
    private boolean lookMotionStale = true;
    /** Whether the pointer has moved since this view was entered; see {@link #steerLook}. */
    private boolean pointerMoved;

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
    private int netMineLayer = Integer.MIN_VALUE;
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

    /**
     * The saved run this session belongs to: the player's record plus the run's
     * own copies of every level it has changed. Offline only — online the
     * server owns the world and nobody has decided yet whose save that is (see
     * {@code SAVE_PLAN.md} §10), so this stays {@code null} in a session and
     * every save path below is a no-op.
     */
    private RunSession run;

    private boolean paused;
    private ConfigForm pauseForm;
    /** The controls sheet, shown over the pause menu while it is open. */
    private ConfigForm bindsForm;
    /** The options sheet (volume, look, HUD size), shown the same way. */
    private ConfigForm optionsForm;
    /** The "you have unsaved progress" sheet, over the pause menu. */
    private ConfigForm confirmQuitForm;
    /** Set by the pause menu when quitting would throw a run away. */
    private boolean confirmQuit;

    // Scratch buffer for zero-allocation world-to-screen projection.
    private final int[] corner = new int[2];

    private static final Font SANS_BOLD_12 = new Font("SansSerif", Font.BOLD, 12);
    private static final Font SANS_BOLD_16 = new Font("SansSerif", Font.BOLD, 16);
    private static final Font SANS_BOLD_26 = new Font("SansSerif", Font.BOLD, 26);
    private static final Font SANS_PLAIN_11 = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font SANS_PLAIN_14 = new Font("SansSerif", Font.PLAIN, 14);

    public PlayScene(GameContext ctx, String levelPath) {
        this(ctx, levelPath, LevelStore.DEFAULT_DIR, SaveStore.DEFAULT_DIR);
    }

    /**
     * A play scene reading levels and writing runs somewhere other than the
     * default folders — which is what lets a test drive a whole run through a
     * door and back inside a temporary directory, the same way
     * {@link LevelSelectScene} takes its levels root.
     *
     * @param levelsDir where levels are <em>authored</em>: read from, and after
     *                  the save system never written to (see {@link SaveStore})
     * @param savesDir  where runs are written
     */
    public PlayScene(GameContext ctx, String levelPath, String levelsDir, String savesDir) {
        this.ctx = ctx;
        this.levelPath = levelPath;
        this.levelsDir = levelsDir;
        this.savesDir = savesDir;
    }

    private final String levelsDir;
    private final String savesDir;

    /** This game type's authored levels. Read-only on the play path (I1). */
    private LevelStore authoredLevels() {
        return new LevelStore(levelsDir, profile().name);
    }

    // --- test access ------------------------------------------------------------
    // Package-private, for the tests in this package that drive a whole run
    // through doors and out again. Deliberately read-only views of what the
    // scene already owns rather than setters: a test that can only observe
    // cannot put the scene into a state the game never would.

    Level currentLevel() { return level; }

    PlayerState player() { return me; }

    /** Where the eye is looking, in a solid view; see {@link #steerLook}. */
    double lookHeading() { return lookYaw; }

    Inventory carried() { return inventory; }

    PlayerStats runStats() { return stats; }

    RunSession runSession() { return run; }

    ConfigForm pauseFormForTest() {
        if (pauseForm == null) buildPauseForm();
        return pauseForm;
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
        doors = new DoorDirectory(authoredLevels());
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

        // Offline, this session is a run: which slot it is comes from whatever
        // menu got here (Continue names one, New Run wipes one), and the run
        // decides which copy of a level is played — its own, or the authored
        // one. Opened before the level loads because it is what answers that.
        run = openRun();

        // Online, the world is whatever the server runs (one shared Level
        // instance that block broadcasts keep current); offline, the level the
        // run stopped in, else the game type's last level, else the bundled
        // sample.
        if (net != null && net.client().level() != null) {
            level = net.client().level();
            world = null;
        } else {
            level = loadOfflineLevel();
            // Whatever copy that turned out to be, it is what is on disk right
            // now — so the run only writes it once the player changes it.
            if (run != null) run.adopt(level);
            // Each level carries its own feature toggles: apply them so the
            // game type acts as a folder of diverse levels, not one fixed
            // feature set. Legacy levels (settings == null) keep the game
            // type's profile as-is.
            ctx.applyLevelSettings(level.settings);
            world = new World(level);
            world.setProfiler(ctx.profiler());
            world.populateFromLevel(profile());
            world.setPickupListener((player, key, count) -> {
                inventory.add(key, count);
                stats.add("items_picked_up", count);
                itemSound(key, "pickup", "pickup");
                if (run != null) run.markDirty();
            });
        }
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        // The run, not the level, owns how many times each rule has fired —
        // see StatRuleEngine for the exploit that came of them disagreeing.
        if (run != null) run.restoreFired(level.name, ruleEngine);
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
        // A run being continued already answered the character question and
        // already has a body to put back; a new one asks and starts at spawn.
        if (!restoreRun()) openCharacterChoice();
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

    /**
     * Leaving the scene stops the music and every loop it started — and flushes
     * the run.
     *
     * <p>Every deliberate way out of play (Save and Quit, Quit to Menu, the
     * confirmation sheet) has already decided what to do about the save by the
     * time it gets here. This covers the ways that did not ask: <em>Edit in
     * Creative</em>, a disconnect, a scene change the engine made on its own.
     * Writing rather than asking is the right default for those, because the
     * alternative is a player who edited their own level losing the run they
     * were in the middle of, having never been given the chance to say no.
     */
    @Override
    public void onExit() {
        sounds.reset();
        // Whatever this scene did to the pointer, the next screen inherits a
        // desktop pointer: menus are worked with it, and a hidden cursor left
        // behind by a first-person view is a menu nobody can click.
        Pointer.restore();
        if (run != null) {
            // Unconditionally, not only when the run looks dirty. The dirty
            // flag is an optimisation for the periodic autosave and an input to
            // the quit prompt; it is assembled from the events the scene
            // happens to notice, and leaving is the one moment where being
            // wrong about it costs the player something they cannot get back.
            // Writing anyway is cheap — an unchanged level is skipped, and what
            // is left is a few hundred bytes of run record.
            run.capture(profile().name, level, me, inventory, stats, timeOfDay());
            run.rememberFired(level.name, ruleEngine);
            run.saveNow(level);
            closeRun();
        }
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
        character.applyTo(me, level.tileSize);
        ctx.setCharacter(character.key);
        prevHealth = me.health;
        // Online, the server has to simulate the body this client is
        // predicting. It cannot know which character that is until the level's
        // roster has come down and the player has chosen from it, which is
        // here — so this is where it is told.
        if (net != null) net.client().sendCharacter(character.key);
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

    /**
     * The level to play offline: whatever "Level Select" pointed at last, falling
     * back to the bundled sample.
     *
     * <p><b>A pointer that does not load is cleared rather than retried.</b> It
     * used to be retried, so a profile that had once been aimed at something
     * that is not a level printed the same failure on every launch and every
     * return to the menu, forever, with no way for a player to clear it. (The
     * way it got aimed there was {@code LevelStore.list()} offering a sidecar as
     * a level — fixed at the source, but a profile written before that fix still
     * carries the bad path.) Forgetting it turns a permanent error into one line
     * seen once.
     */
    private Level loadOfflineLevel() {
        // A run being continued goes back to the level it stopped in, and to
        // *its* copy of that level — the hole it dug is in the slot, not in the
        // level as its author built it.
        if (run != null && run.resumed()) {
            Level saved = run.level(run.record().levelName);
            if (saved != null) return saved;
            System.err.println("PlayScene: the run's level \"" + run.record().levelName
                    + "\" is gone — starting from the game type's last level instead");
        }
        String last = profile().lastLevelPath;
        if (last != null && !last.isEmpty() && Files.exists(Path.of(last))) {
            try {
                Level authored = LevelLoader.load(last);
                // Even a level reached by "Level Select" is played through the
                // run: if this slot has already been in it, that copy is the
                // one with the player's changes in it.
                if (run != null && run.store().hasLevel(authored.name)) {
                    Level mine = run.level(authored.name);
                    if (mine != null) return mine;
                }
                return authored;
            } catch (RuntimeException e) {
                System.err.println("PlayScene: failed to load " + last + ": " + e.getMessage()
                        + " — forgetting it and loading " + levelPath);
                profile().lastLevelPath = "";
                ctx.save();
            }
        }
        return LevelLoader.load(levelPath);
    }

    /**
     * Open the run this session plays: the slot the menus selected, continued
     * or started over.
     *
     * <p>{@code null} online, where the server owns the world.
     */
    private RunSession openRun() {
        closeRun();
        if (net != null) {
            ctx.takeStartFreshRun(); // consumed either way, so it cannot leak into the next run
            return null;
        }
        SaveStore store = new SaveStore(savesDir, levelsDir, profile().name, ctx.runSlot());
        if (ctx.takeStartFreshRun()) {
            // "New Run" means this slot starts from the authored levels again.
            // Deleting is the only way to say that, because a slot's levels are
            // its memory of every level it has been in.
            store.delete();
            return RunSession.fresh(store);
        }
        return RunSession.open(store);
    }

    /** Flush and release the run, if there is one. */
    private void closeRun() {
        if (run == null) return;
        run.close();
        run = null;
    }

    /**
     * Put a continued run back onto the body: character, pools, position,
     * carried items, counters and the hour of the day.
     *
     * <p><b>The character is applied first and the pools second</b>, because
     * {@code CharacterProfile.applyTo} fills health, mana and stamina to their
     * maxima — restoring before it would hand the player a full bar on every
     * load, which is the bug this ordering exists to avoid.
     *
     * @return whether a run was restored (so the caller knows not to ask the
     *         character question again)
     */
    private boolean restoreRun() {
        if (run == null || !run.resumed()) return false;
        RunRecord saved = run.record();

        List<CharacterProfile> roster = Characters.rosterFor(level.characters);
        CharacterProfile chosen = roster.stream()
                .filter(c -> c.key.equals(saved.characterKey))
                .findFirst()
                .or(() -> Optional.ofNullable(Characters.get(saved.characterKey)))
                .orElseGet(() -> roster.isEmpty()
                        ? CharacterProfile.defaultProfile() : roster.get(0));
        picker = null;
        applyCharacter(chosen);

        saved.applyTo(me);          // …and now the pools that applyCharacter filled
        saved.applyTo(inventory);
        saved.applyTo(stats);
        if (world != null && saved.timeOfDay >= 0) world.setTimeOfDay(saved.timeOfDay);

        ruleStatus = "Continued — " + level.name;
        ruleStatusTime = 3.0;
        return true;
    }

    @Override
    public void onResize(int w, int h) {
        super.onResize(w, h);
        if (camera != null) camera.setViewport(w, h);
    }

    /**
     * Collapse the interpolation to a standstill: every body's "one step ago"
     * becomes its position now.
     *
     * <p>Called at the top of every tick. On a tick that goes on to simulate,
     * {@code PlayerPhysics.step} and {@code World.step} then move the bodies away
     * from what was just recorded and the blend spans the real step. On a tick
     * that returns early it is the whole story, and the picture stands still
     * instead of oscillating inside a step nothing took.
     */
    private void holdInterpolationStill() {
        if (me != null) me.beginStep();
        if (world != null) world.beginStep();
        // The client-predicted vehicle is not in the world's lists, so it is not
        // covered by world.beginStep(). It has to be: the rider is glued to its
        // saddle and the rider is interpolated, so a raw vehicle would shimmer
        // against the player sitting on it.
        if (predictedVehicle != null) predictedVehicle.beginStep();
    }

    @Override
    public void update(double dt, InputManager input) {
        // Unconditionally, before anything below can decide to skip the
        // simulation. render() blends from "one step ago" to "now", so a tick
        // that moves nothing has to leave those two equal — otherwise the frames
        // of a pause, a character choice or a cutscene would slide the world back
        // and forth inside a step that never happened, which is the very artefact
        // the blending exists to remove. See StepInterpolation.
        holdInterpolationStill();
        if (leaving) return; // session torn down; waiting out the scene fade
        if (net != null && !net.client().isConnected()) {
            if (KeyBinds.pressed(input, GameAction.MENU_SELECT)
                    || KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                leaveSession();
            }
            return;
        }
        if (paused) {
            // A paused game is a menu, and a menu is worked with the pointer.
            Pointer.restore();
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
            // The director's mark is a point on the ground, not a body — so the
            // lift goes back to zero rather than staying wherever the player
            // was standing when the cutscene started.
            if (cut != null) camera.frameOn(cut.cameraX(), cut.cameraY());
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
        // Keep the world building itself ahead of the player. A no-op on a
        // level that is not generated, and on a generated one it is the whole
        // of "chunk loading to prevent lag": the ground a player is walking
        // toward was built on a worker thread several seconds before they
        // arrive, so no frame ever pays for it.
        streamTerrain();

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
                Vehicle mountable = world.mountableNear(me.x + hitSize() / 2, me.y + hitSize() / 2);
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

        // [F5] cycles the view: the level's own plan projection, first person,
        // third person behind, third person in front.
        if (KeyBinds.pressed(input, GameAction.TOGGLE_VIEW)) cycleViewpoint();

        if (solidView() && viewpoint.freeLook()) {
            // The eye is steered rather than snapped; the flat camera is left
            // exactly where it was, because the pivot a billboard is placed
            // against is measured through it (SolidPainter.billboard) and
            // spinning it would thrash TerrainCache for a view it is not
            // drawing.
            //
            // Not while a panel is open: the pointer is arranging stacks in an
            // inventory then, and every pixel it travels would also be a turn
            // of the world behind it. Forgetting the last reading is what stops
            // closing the panel from banking the whole journey as one flick.
            if (showInventory || craftingPanel != null || containerPanel != null) {
                input.discardMouseMotion();
                // The pointer is arranging stacks, so it is a pointer again:
                // visible, and left where the player put it.
                Pointer.restore();
            } else {
                steerLook(input, dt);
            }
            // Placed now as well as at render time, and from the simulation's
            // own position rather than the interpolated one: everything below
            // that maps between the screen and the world — what the crosshair
            // is on, where a block goes, the aim of a shot — has to agree with
            // the authoritative step rather than with a frame drawn between two
            // of them. This is the eye's half of what camera.follow does for
            // the flat camera further down.
            // At the body's own ground contact point, which is the middle of
            // its footprint and not the middle of its box: on a plane a
            // character stands on the *southern* edge of the box it collides
            // with (PlayerPhysics.footTop), so an eye at the box's centre sat
            // half a body north of the body — far enough to put your face
            // inside the wall you were standing against, and to look through
            // it. The billboard is anchored at the same point (standingAt), so
            // the two agree about where the player is.
            placeEye(me.x + hitSize() / 2.0, me.y + hitSize(), me.z);
        } else {
            // A plan view aims with the pointer, so it has one.
            Pointer.restore();
            // The eight-point camera: a press aims it one compass point round
            // and the animation carries it there over the next fifth of a
            // second. Both are per-frame because a snap in flight has to keep
            // going while the player is doing something else — and both are
            // no-ops in a level whose projection does not turn. C8.
            if (KeyBinds.pressed(input, GameAction.ROTATE_LEFT)) camera.turn(-1);
            if (KeyBinds.pressed(input, GameAction.ROTATE_RIGHT)) camera.turn(1);
            camera.stepYaw(dt);
            // And the other axis of the same camera: held rather than pressed,
            // and free rather than snapped. Turning around the player is a
            // choice between eight things and wants to land on one of them;
            // raising the camera over them is a continuum, and stopping it
            // halfway is the whole point — a player picks the angle they want to
            // look at their world from. No-op in a side view, which has no floor
            // to stand over (Camera.tilt).
            if (KeyBinds.down(input, GameAction.LOOK_UP)) {
                camera.tilt(Camera.TILT_SPEED * dt);
            }
            if (KeyBinds.down(input, GameAction.LOOK_DOWN)) {
                camera.tilt(-Camera.TILT_SPEED * dt);
            }
            // …and the plan view stands an eye at the end of that heading and
            // tilt, because it is drawn through one now (Viewpoint). The flat
            // camera keeps owning the two angles: it is what snaps them to the
            // eight the character art has frames for, and what animates between
            // them. Placed from the simulation's own position for the reason
            // the branch above is — the aim has to agree with the step, not
            // with a frame drawn between two of them.
            if (solidView()) placeEye(me.x + hitSize() / 2.0, me.y + hitSize(), me.z);
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
        // The heading these keys were pressed at. It travels with the tick
        // because the server has no camera to ask — see PlayerInput.yaw (C7).
        // In a solid view that is where the player is *looking*, which is what
        // makes W walk into the screen the way it does in every 3D game.
        in.yaw = viewYaw();
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

        // A mounted player drives their vehicle instead of walking.
        Perspective simPerspective = simPerspective();
        // The melee machine steps before the body does: a lunge's burst and a
        // raised guard's slowed footwork are both movement, and the physics
        // step below is what carries them out.
        stepMelee(p, in, simPerspective != Perspective.SIDE_SCROLL || !p.gravityEnabled, dt);
        prevVy = me.vy;
        // No interpolation capture here: holdInterpolationStill() already took
        // this tick's, at the top, before anything in the tick could move the
        // body. Capturing again would shorten the interval the blend spans to
        // less than the one the world's bodies use, and the two have to match or
        // the player drifts against the level by the difference.
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
            int died = world.pollDeaths();
            stats.add("deaths", died);
            // A death is a chapter break, like a door: the run is written so
            // that "I died" never also means "and lost the hour before it".
            if (died > 0) autosave();
            for (World.Impact im : world.pollImpacts()) impactFeedback(im, p);
            // Tiles the simulation broke on its own (bomb craters, the drill,
            // the Tremor Totem) shower shards like hand-mined blocks do.
            for (var change : world.pollBlockChanges()) {
                if (p.particlesEnabled && change.id() == 0) {
                    double bx = (change.col() + 0.5) * ts(), by = (change.row() + 0.5) * ts();
                    particles.burst(bx, by, surfaceZ(bx, by),
                            new Color(150, 130, 100), 5, Particles.Style.BURST);
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
                if (run != null) run.markDirty(); // a reward collected is progress
            }
            tickRun(dt);
        }

        if (me.health < prevHealth - 0.01) {
            stats.add("damage_taken", prevHealth - me.health);
            // Being hurt is progress in the only sense that matters here: it is
            // a change to the run that the player would notice not having been
            // saved. Position and terrain have their own detectors; this is the
            // one that would otherwise be missed while standing still.
            if (run != null) run.markDirty();
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

        double size = hitSize();
        // On the simulation's own position, and render() moves it again onto the
        // interpolated one. Both are wanted: everything in update() that maps
        // between the screen and the world — a mining click, a placed block, the
        // aim of a shot, the audio listener's reach — has to agree with the
        // authoritative step rather than with a frame drawn between two of them,
        // and the shimmer is a property of what was *drawn*. See
        // StepInterpolation, and render() for the other half.
        // Climbing moves a character up the screen without moving them on the
        // plane, so a camera that follows only (x,y) leaves a player on a tall
        // tower above the top of its own viewport. Plane and lift are taken
        // together (Camera.follow) so neither can be updated without the other.
        camera.follow(me.x + size / 2.0, me.y + size / 2.0, me.z);
        // The height axis is followed with slack rather than rigidly: a hop is
        // the character leaving the ground, and a camera that rose with them
        // showed the ground dropping away instead. See Camera.restHeight.
        camera.stepFollow(dt);
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
        double half = hitSize() / 2.0;
        Level.EntitySpawn door = level.doorNear(me.x + half, me.y + half, ts() * 1.3);
        if (door == null) return false;
        DoorLink link = doors.get(door.type);
        if (link == null || link.targetLevel().isEmpty()) return true;

        // Read the destination through the run, so a level this run has already
        // been in comes back as it was left rather than as it was authored.
        // Falls back to a plain store when there is no run (there always is
        // one offline, and door travel is offline-only, but the null check is
        // what stops this from being the one path that assumes otherwise).
        Level destination = run != null ? run.level(link.targetLevel()) : null;
        if (destination == null) {
            LevelStore store = authoredLevels();
            if (!store.exists(link.targetLevel())) return true;
            destination = store.load(link.targetLevel());
        }

        // …and write the level being left *before* reading the next one. This
        // one ordering is the difference between a game type of linked levels
        // being one continuous world and being a set of rooms that reset: the
        // departing level's only copy is the one in memory, and the line below
        // used to overwrite it.
        saveDepartingLevel();

        ctx.sound(SoundKeys.door("open"));
        level = destination;
        if (run != null) run.adopt(level);
        // The destination's own toggles (and so its tile/player sizes) apply
        // before anything is built against them.
        ctx.applyLevelSettings(level.settings);
        world = new World(level);
        world.setProfiler(ctx.profiler());
        world.populateFromLevel(p);
        world.setPickupListener((player, key, count) -> {
            inventory.add(key, count);
            stats.add("items_picked_up", count);
            itemSound(key, "pickup", "pickup");
            if (run != null) run.markDirty();
        });
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        // The counters carried across; the fire counts have to as well, or every
        // one-shot reward in this level is armed again by the walk back into it.
        if (run != null) run.restoreFired(level.name, ruleEngine);
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
        // A door is a chapter break, and a good moment to have written: the
        // screen is changing level anyway, so the cost hides where a hitch
        // mid-fight would not.
        autosave();
        return true;
    }

    /**
     * Write the level being left into the run, together with the fire counts
     * that belong to it, so both are still true when the player comes back.
     *
     * <p>Only the record is touched here — the bytes go out with the next save,
     * which for door travel is the {@link #autosave()} a few lines later.
     */
    private void saveDepartingLevel() {
        if (run == null || level == null) return;
        run.rememberFired(level.name, ruleEngine);
        run.capture(profile().name, level, me, inventory, stats, timeOfDay());
        // The level's own copy has to go out now rather than at the next
        // autosave: in a moment `level` will be a different object and this
        // one's changes will have nowhere left to be read from.
        run.saveAsync(level);
    }

    /**
     * Standing near a crafting table / alchemy station / chest, E opens its
     * panel. Returns whether one opened (so E can fall through to mounting).
     */
    private boolean tryOpenStation(GameProfile p) {
        double ts = ts();
        int pc = (int) Math.floor((me.x + hitSize() / 2.0) / ts);
        int pr = (int) Math.floor((me.y + hitSize() / 2.0) / ts);
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
                    // A chest's contents live in level.containers, which is not
                    // terrain and so does not move the level's revision counter.
                    // Opening one is the moment to say so: the panel edits the
                    // level directly, and by the time it closes there is no
                    // record of whether anything moved.
                    if (run != null) run.markLevelDirty(level.name);
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
        double half = hitSize() / 2;
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

        double[] aim = aimPoint();
        double ts = ts();
        // What the cursor — or, in a solid view, the crosshair — is on, and
        // where a block placed against it goes. Inverting the floor answers
        // neither once the terrain has height: the pixels showing a tower's
        // side belong to the floor cell behind it, so a click on a wall used to
        // mine a block a cell or more away — further the taller the wall
        // (HEIGHT_PLAN.md R7/E1).
        TerrainPainter.Aim at = aimBlock();
        int col = at != null ? at.col() : (int) Math.floor(aim[0] / ts);
        int row = at != null ? at.row() : (int) Math.floor(aim[1] / ts);
        int placeCol = at != null ? at.placeCol() : col;
        int placeRow = at != null ? at.placeRow() : row;
        // Which block of that column, and which box of the neighbouring one.
        // A crosshair names both exactly; a pointer over a plan view names
        // neither, and the column rules stand in for it (TerrainPainter.Aim).
        int aimLayer = at != null ? at.layer()
                : Math.max(Level.LAYER_GROUND, level.stackHeight(col, row) - 1);
        int placeLayer = at != null ? at.placeLayer(level) : level.placeLayer(placeCol, placeRow);
        boolean inReach = Math.hypot(aim[0] - (me.x + hitSize() / 2), aim[1] - (me.y + hitSize() / 2))
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
            in.mineLayer = aimLayer;
            predictMining(col, row, aimLayer, held, dt);
        } else if (miningNow) {
            swingTime = Math.max(swingTime, 0.1);
            // The block the crosshair is on — which in a plan view, where the
            // pointer names a column rather than a box of one, is still the top
            // of the stack (aimLayer above).
            if (level.blockAt(col, row, aimLayer) == null) {
                // Legacy palette tile with no block definition: instant break.
                if (leftClick && level.setTile(col, row, aimLayer, 0)) {
                    stats.add("blocks_mined", 1);
                    playerSound("mine_break");
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts,
                                surfaceZ((col + 0.5) * ts, (row + 0.5) * ts),
                                Color.GRAY, 10, Particles.Style.BURST);
                    }
                }
            } else {
                // The scrape of the tool against the block, while it lasts.
                Block digging = level.blockAt(col, row, aimLayer);
                mineSoundTimer -= dt;
                if (digging != null && mineSoundTimer <= 0) {
                    mineSoundTimer = MINE_SOUND_INTERVAL;
                    Sounds.actor(character.key, SoundKeys.block(digging.key(), "mine"),
                            "mine", 0.5);
                }
                Block mined = world.continueMining(col, row, aimLayer, held,
                        p.itemsEnabled, dt);
                if (mined != null) {
                    stats.add("blocks_mined", 1);
                    blockSound(mined, "break", "mine_break");
                    if (p.particlesEnabled) {
                        particles.burst((col + 0.5) * ts, (row + 0.5) * ts,
                                surfaceZ((col + 0.5) * ts, (row + 0.5) * ts),
                                mined.color(), 10, Particles.Style.BURST);
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
            placeAt(placeCol, placeRow, placeLayer, p);
        }
    }

    /**
     * Online: advance the local prediction of mining progress with the same
     * hardness/tool formula the server runs, for the crack overlay. The break
     * itself arrives as an authoritative {@code block} broadcast.
     */
    private void predictMining(int col, int row, int layer, ItemDef held, double dt) {
        if (col != netMineCol || row != netMineRow || layer != netMineLayer) {
            netMineCol = col;
            netMineRow = row;
            netMineLayer = layer;
            netMineProgress = 0;
        }
        // The client predicts against the block the server will bite into:
        // the one the crosshair named, which the input command carries.
        Block b = level.blockAt(col, row, layer);
        if (b == null) b = level.topBlockAt(col, row);
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
        netMineLayer = Integer.MIN_VALUE;
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

    /**
     * Put the held block in one box of one cell.
     *
     * <p>{@code layer} is the aim's answer rather than the column's: a
     * crosshair on the side of a wall builds outward from that face, at that
     * height, which is what every block game does and what "blocks place at the
     * bottom of the stack" was not. A plan view's pointer names a column and
     * nothing more, so it passes {@link Level#placeLayer}'s bottom-up answer and
     * behaves exactly as it always has.
     */
    private void placeAt(int col, int row, int layer, GameProfile p) {
        ItemDef def = p.itemsEnabled ? inventory.selectedDef() : null;
        if (p.itemsEnabled && (def == null || def.category() != ItemDef.Category.BLOCK)) {
            return; // nothing placeable selected
        }
        String blockKey = def != null ? def.blockKey() : "dirt";
        Block b = level.blocks.get(blockKey);
        if (b == null || layer < 0 || layer >= level.layerLimit()) return;
        // Don't wall yourself in. Flooring a hole under your feet is not
        // walling yourself in — it is the opposite — so only a placement that
        // would actually close the cell counts. "Where I am" is the shape the
        // step above collided with, which on a plane is the ground under the
        // feet: measured on the body box instead, a plan-view player could
        // never build on the cell their sprite's head reaches into. And, once a
        // body can be at a height, at which height (W6): the layer is passed so
        // a roof four layers up is not refused for the player under it, and a
        // player standing on a tower blocks the next block on top of it —
        // which "the layer is 1" stopped being able to say the day a column
        // could be eight deep.
        boolean overlapsMe = PlayerPhysics.standingIn(level, me.x, me.y, hitSize(),
                PerspectiveSpace.of(simPerspective()), col, row, me.z, layer);
        boolean wouldClose = b.solid()
                && (!level.layered() || layer > Level.LAYER_GROUND);
        if (wouldClose && overlapsMe) return;

        if (net != null) {
            net.client().sendBlockEdit(col, row, b.id(), "play", layer);
            return;
        }
        if (world.placeBlock(col, row, layer, b.id())) {
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
        double[] aim = aimPoint();
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
                        hit.mob().y + hit.mob().def.size() / 2, hit.mob().z,
                        new Color(255, 240, 190), 10, Particles.Style.BURST);
            }
            return;
        }
        if (hit.hit()) {
            Mob m = hit.mob();
            melee.markConnected();
            Sounds.actor(character.key,
                    SoundKeys.mob(m.def.key(), m.dead() ? "death" : "hurt"), "attack_hit");
            if (p.particlesEnabled) {
                particles.burst(m.x + m.def.size() / 2, m.y + m.def.size() / 2, m.z,
                        m.def.body(), 8, Particles.Style.BURST);
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
                particles.burst(me.x + hitSize() / 2, me.y + hitSize() / 2,
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
        double[] aim = aimPoint();
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
        predictedVehicle.seat(me, hitSize());
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
            me.z = corrected.z;
            me.vz = corrected.vz;
        } else {
            double k = Math.min(1.0, CORRECTION_PER_SEC * dt);
            me.x += ex * k;
            me.y += ey * k;
            me.vy += (corrected.vy - me.vy) * k;
            // The third axis reconciles like the other two.
            //
            // W2's step-up rule reads only the level and the body, so it is
            // deterministic by construction — but "by construction" is what C7
            // said before the heading turned out to need to ride the input.
            // What actually differs across the wire is the level: a client that
            // has just placed a block under itself stands on it a tick before
            // the server agrees, which is ordinary prediction error and needs
            // the ordinary correction rather than a special case
            // ({@code HEIGHT_PLAN.md} N3).
            me.z += (corrected.z - me.z) * k;
            me.vz += (corrected.vz - me.vz) * k;
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
        // The quit confirmation goes first: while it is up it is the only thing
        // that can be answered, because everything it is asking about is
        // waiting on the answer.
        if (confirmQuit) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) confirmQuit = false;
            else confirmQuitForm().update(dt, input);
            return;
        }
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
        // The options sheet, over the pause menu for the same reason: turning
        // the music down should not cost you the level you are standing in.
        if (optionsForm != null) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                optionsForm = null;
            } else {
                optionsForm.update(dt, input);
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

    /**
     * How far past the last fixed simulation step this frame is being drawn,
     * 0..1 — the loop's {@code alpha}, held for the frame so the world-drawing
     * helpers can read it without every one of them taking a parameter, exactly
     * as {@link #animClock} is held.
     *
     * <p>See {@link com.larsons.engine.sim.StepInterpolation}: this is the number
     * that turns a world sampled at an uneven cadence — the shimmer — into one
     * that scrolls by the same amount every frame.
     */
    private double renderAlpha;

    /** The local player's drawn position this frame: interpolated, not stepped. */
    private double drawX() { return me.renderX(renderAlpha); }

    private double drawY() { return me.renderY(renderAlpha); }

    private double drawZ() { return me.renderZ(renderAlpha); }

    @Override
    public void render(DrawTarget target, float alpha) {
        if (leaving) {
            // Session gone; hold a blank frame while the menu fade finishes.
            ctx.lighting().setDarkness(0);
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    level != null ? level.background : Color.BLACK);
            return;
        }
        renderAlpha = alpha;
        GameProfile p = profile();
        // The camera is placed for this frame before anything projects through
        // it — including the lighting pass, which converts world positions to
        // screen ones. update() left it on the last simulation step; this moves
        // it to where the player is at *this frame's* moment in time, which is
        // what makes the world scroll evenly instead of in the 1/2/3-step jumps
        // the accumulator hands out. A running cutscene owns the camera and is
        // left alone: its director drives it from a timeline of its own.
        if (cutscenes == null || cutscenes.active() == null) {
            double size = hitSize();
            // Including the lift, which used to be left on whatever the last
            // simulation step set while the plane moved every frame. That is
            // the defect StepInterpolation exists to remove, applied to one
            // axis and not the other: the ground scrolled evenly and a
            // climbing player's height stepped at the sim-vs-frame beat.
            camera.follow(drawX() + size / 2.0, drawY() + size / 2.0, drawZ());
        }
        // The eye is placed from the same interpolated position the flat camera
        // was, and before the lighting pass, which projects through whichever
        // of the two this frame is drawn with.
        if (solidView()) {
            placeEye(drawX() + hitSize() / 2.0, drawY() + hitSize(), drawZ());
        }
        feedLighting(p);

        if (solidView()) {
            renderSolid(target, p);
            renderOverlay(target, p);
            return;
        }

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
        drawDoors(target, standing);
        phase("entities", () -> drawWorldEntities(target, p, standing));
        if (mgView != null) MiniGameHud.drawWorld(target, camera, level, mgView, animClock);
        if (net != null) drawRemotePlayers(target, standing);
        if (mgView != null) {
            MiniGameHud.drawTeamRing(target, camera, drawX() + hitSize() / 2, drawY() + hitSize(),
                    drawSize(), mgView.teamOf(me.id), camera.zoom);
        }
        // The local player, wearing whatever the object in their hands says
        // they should look like while doing this (see MeleeSprites), with the
        // object itself drawn in hand on top. Drawn at the same interpolated
        // position the camera was centred on, so the two cannot drift against
        // each other by the step the interpolation is spanning.
        double meX = drawX(), meY = drawY(), meZ = drawZ();
        standingAt(standing, meX, meY, hitSize(), meZ, () -> {
            drawPlayer(target, meX, meY, meZ, hitSize(), drawSize(), MeleeSprites.playerFrame(
                    me.characterKey, meleeItem, animState, seen(me.facing), animStateClock,
                    melee.progress(), (int) drawSize(), character.body, overheadView()), null);
            drawHeldObject(target, meX, meY, meZ, hitSize(), drawSize(), seen(me.facing),
                    meleeItem, melee.action(), melee.progress(), meleeProfile(p));
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
        renderOverlay(target, p);
    }

    /**
     * The world seen from inside it — the first- and third-person frame.
     *
     * <p>Short, and deliberately: the sky and the blocks are
     * {@link SolidPainter}'s, and every actor is drawn by exactly the same
     * methods the plan view draws them with, routed through
     * {@link #standingAt} into the painter's queue instead of the flat depth
     * pass. Scenery joins them, as billboards queued into the same painter —
     * see {@link DecorPainter#drawSolid} and {@link SurfaceDecorPainter#drawSolid}.
     * What is still missing rather than reimplemented is the grid, painted
     * doors, the parallax backdrop and particles, all of which project through
     * the flat camera.
     */
    private void renderSolid(DrawTarget target, GameProfile p) {
        // Per frame rather than on a change, because the setting is a global
        // the options screen edits in place and there is no event to hang a
        // listener on — and because it costs an integer compare.
        applyViewDistance(p);
        solid.begin(target, eye, level, animClock);
        // Anything standing between a long camera arm and the player is drawn
        // see-through, so walking indoors is not walking into a roof. Only the
        // plan view asks: the mouse-look views pull their camera in instead,
        // which is the right answer at three tiles and the wrong one at twelve
        // (SolidPainter.setCutaway, PlayScene.placePlanEye).
        if (!viewpoint.freeLook()) {
            solid.setCutaway(drawX() + hitSize() / 2.0, drawY() + hitSize(),
                    drawZ() + drawSize() * EYE_HEIGHT, ts() * CUTAWAY_TILES);
        }
        phase("terrain", solid::terrain);
        // After the detailed sweep and drawn behind it; see SolidPainter.distant.
        phase("distant", solid::distant);
        // Both scenery layers, queued into the same painter. "Behind the
        // actors" and "in front of them" is a flat picture's way of saying what
        // depth says for itself once there is an eye standing in the world, so
        // the two are the same call twice rather than two passes with the
        // actors between them.
        phase("decor", () -> {
            DecorPainter.drawSolid(target, level, camera, solid, false, animClock);
            DecorPainter.drawSolid(target, level, camera, solid, true, animClock);
            SurfaceDecorPainter.drawSolid(target, level, solid, false, animClock);
            SurfaceDecorPainter.drawSolid(target, level, solid, true, animClock);
        });
        // Nothing queues into this in a solid view — standingAt routes to the
        // painter instead — but it is passed and flushed all the same, so that
        // anything that ever did queue into it directly would be drawn rather
        // than silently dropped.
        DepthPass standing = DepthPass.sorted();
        drawDoors(target, standing);
        phase("entities", () -> drawWorldEntities(target, p, standing));
        if (net != null) drawRemotePlayers(target, standing);
        if (drawsOwnBody()) {
            double meX = drawX(), meY = drawY(), meZ = drawZ();
            standingAt(standing, meX, meY, hitSize(), meZ, () -> {
                drawPlayer(target, meX, meY, meZ, hitSize(), drawSize(),
                        MeleeSprites.playerFrame(me.characterKey, meleeItem, animState,
                                seen(me.facing), animStateClock, melee.progress(),
                                (int) drawSize(), character.body, overheadView()), null);
                drawHeldObject(target, meX, meY, meZ, hitSize(), drawSize(), seen(me.facing),
                        meleeItem, melee.action(), melee.progress(), meleeProfile(p));
            });
        }
        if (p.particlesEnabled) {
            phase("particles", () -> particles.renderSolid(target, camera, solid));
        }
        phase("depth-flush", () -> {
            solid.flush();
            standing.flush();
        });
        // The crosshair belongs to the views that took the pointer away. The
        // plan view still has one, and two pointers on one screen is exactly
        // the confusion the crosshair exists to prevent.
        if (viewpoint.freeLook()) drawCrosshair(target);
        if (viewpoint == Viewpoint.FIRST_PERSON) drawHandItem(target, p);
    }

    /**
     * Ask the level to keep the world built around the player.
     *
     * <p>Around the <em>player</em> rather than around the camera, because the
     * camera can be looking anywhere and the player is who is about to walk
     * into a chunk. The radius covers whatever the view distance reaches, so
     * everything being drawn is ground that is already there.
     */
    private void streamTerrain() {
        if (level == null || !level.isWorld()) return;
        GameProfile p = profile();
        int view = p != null && p.terrain != null
                ? p.terrain.renderDistance : SolidPainter.DEFAULT_VIEW_TILES;
        // Only as far as the world is drawn a block at a time. Past that it is
        // drawn from sampled heights rather than from chunks, so building the
        // chunks out there would be generating — and holding in memory — a disc
        // of world that nothing is going to read a cell of.
        view = Math.min(view, PlayerSettings.active().detailDistance);
        // With the player's height, so digging down stops streaming a disc of
        // surface world nobody can see any of (Level.streamTerrain).
        level.streamTerrain(me.x + hitSize() / 2, me.y + hitSize() / 2, me.z, view);
    }

    /**
     * How far this frame draws: the level's own render distance, and its
     * horizon behind it.
     *
     * <p><b>Two settings, and they belong to different people.</b> How far the
     * world is drawn in <em>detail</em> is the level's — a generated world is
     * meant to be seen across, and a creator who built one sets how much of it
     * the player is standing in. Whether the coarse horizon is drawn at all
     * stays the player's ({@link PlayerSettings#distantTerrain}), because it is
     * a statement about their machine; a level that asks for one is asking, and
     * a player who has turned it off is answering.
     *
     * <p>So is how much of the render distance is drawn a block at a time
     * ({@link PlayerSettings#detailDistance}). That one does nothing until the
     * render distance is set past it — see
     * {@link SolidPainter#setDetailTiles(int)} for why the two numbers are
     * separate, and what draws the world between them.
     */
    private void applyViewDistance(GameProfile p) {
        var terrain = p != null ? p.terrain : null;
        boolean world = level != null && level.isWorld();
        int view = terrain != null && world
                ? terrain.renderDistance : SolidPainter.DEFAULT_VIEW_TILES;
        solid.setViewTiles(view);
        solid.setDetailTiles(PlayerSettings.active().detailDistance);
        int horizon = terrain != null && world
                ? terrain.distantDistance : SolidPainter.DISTANT_VIEW_TILES;
        solid.setDistantTiles(PlayerSettings.active().distantTerrain ? horizon : 0);
    }

    /**
     * The crosshair: what a solid view aims with, because the mouse is steering
     * the eye rather than pointing at the world.
     *
     * <p>Drawn as a dark cross with a lighter one inside it rather than as one
     * bright shape, because a single-colour crosshair disappears against
     * whatever happens to be that colour — which on a sky-blue level is the
     * sky, and on a stone one is every wall. Two colours are always visible
     * against one background.
     */
    private void drawCrosshair(DrawTarget target) {
        int cx = viewportWidth / 2, cy = viewportHeight / 2;
        int arm = 8, gap = 2;
        int dark = new Color(0, 0, 0, 150).getRGB();
        int light = new Color(255, 255, 255, 210).getRGB();
        for (int pass = 0; pass < 2; pass++) {
            int argb = pass == 0 ? dark : light;
            float thickness = pass == 0 ? 3f : 1.4f;
            target.drawLine(cx - arm, cy, cx - gap, cy, argb, thickness);
            target.drawLine(cx + gap, cy, cx + arm, cy, argb, thickness);
            target.drawLine(cx, cy - arm, cx, cy - gap, argb, thickness);
            target.drawLine(cx, cy + gap, cx, cy + arm, argb, thickness);
        }
    }

    /**
     * The held object, drawn in the corner of the screen — the first-person
     * view's hand.
     *
     * <p>The body is not drawn in first person ({@link Viewpoint#showsSelf}),
     * so without this a player has nothing on screen that says what they are
     * holding, which is exactly the gap the view model fills in every game that
     * has one. It bobs with the walk and swings on a swing, from the clocks the
     * scene already keeps, so it reads as a hand rather than as a sticker.
     */
    private void drawHandItem(DrawTarget target, GameProfile p) {
        ItemDef def = p.itemsEnabled ? inventory.selectedDef() : null;
        String key = def != null ? def.key() : "";
        BufferedImage img = key.isEmpty() ? null : Skins.frame("item/" + key, animClock);
        if (img == null && def != null) img = EntitySprites.item(def, 32);
        if (img == null) return;
        int size = Math.max(48, Math.min(viewportWidth, viewportHeight) / 4);
        // The walk bob, and the swing: a swing pulls the hand back and down
        // and lets it come forward again over its own fifth of a second.
        boolean walking = "walk".equals(animState) || "run".equals(animState);
        double bob = walking ? Math.sin(animClock * 9) * size * 0.045 : 0;
        double swing = swingTime > 0 ? Math.sin(Math.min(1, swingTime / 0.2) * Math.PI) : 0;
        // Held clear of the right edge and running off the bottom one, which is
        // where a hand comes from. The tilt swings the corners out, so the
        // inset is measured against the sprite rather than being a constant.
        int x = viewportWidth - size - (int) Math.round(size * (0.30 - swing * 0.24));
        int y = viewportHeight - size + (int) Math.round(size * (0.05 + swing * 0.32) + bob);
        AffineTransform tilt = AffineTransform.getTranslateInstance(x + size / 2.0,
                y + size / 2.0);
        tilt.rotate(Math.toRadians(-22 + swing * 34));
        target.pushTransform(tilt);
        target.drawImage(img, -size / 2, -size / 2, size, size);
        target.popTransform();
    }

    /**
     * Everything drawn <em>over</em> the world: the door and mount prompts, the
     * whole HUD, the panels and the pause screen.
     *
     * <p>Split out of {@link #render} when the solid views arrived, because it
     * is the half of a frame that does not care how the world behind it was
     * drawn. A first-person frame paints its world through {@link SolidPainter}
     * and then runs exactly this, unchanged — which is what keeps the hotbar,
     * the health bar and the inventory from needing a second implementation for
     * the second camera.
     */
    private void renderOverlay(DrawTarget target, GameProfile p) {
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
                // The whole column, not just the floor. A torch is something
                // you stand ON the ground, so on a plane it lives in layer 1 or
                // above and this loop — which only ever read layer 0 — has been
                // walking past every one of them. Height makes that visible
                // rather than causing it: a torch on a tower is exactly the
                // case a creator will try first.
                for (int layer = level.layerCount() - 1; layer >= 0; layer--) {
                    Block block = level.blockAt(c, r, layer);
                    if (block == null || !block.emitsLight()) continue;
                    // A light further off the floor pools wider and no
                    // brighter, which is what a lamp on a pole does.
                    double spread = 1 + 0.12 * layer;
                    addWorldLight(lighting, (c + 0.5) * ts, (r + 0.5) * ts,
                            level.surfaceZ(layer + 1),
                            block.lightRadius() * ts * spread, block.lightColor());
                }
                continue;
            }
        }
        // Coloured rarity lighting: uncommon+ drops shine with their tier's
        // colour after dark (matching their daylight halo sprite).
        if (net == null) {
            for (DroppedItem item : world.items()) {
                ItemDef def = world.itemTypes.get(item.key);
                if (def == null || def.rarity() == ItemDef.Rarity.COMMON) continue;
                addWorldLight(lighting, item.x + DroppedItem.SIZE / 2,
                        item.y + DroppedItem.SIZE / 2, 0,
                        (1.0 + def.rarity().ordinal() * 0.6) * ts, def.rarity().color);
            }
        }
        // Glowing projectiles (fireballs, magic bolts) carry their own light —
        // they ride the same lighting pass, so at night a fireball lights the
        // terrain it flies over (and bloom, if enabled, blooms it).
        if (net == null) {
            for (Projectile pr : world.projectiles()) {
                // At the interpolated position, so a fireball's glow travels with
                // the fireball rather than a step ahead of it.
                addProjectileLight(lighting, pr.def, pr.renderX(renderAlpha),
                        pr.renderY(renderAlpha), ts);
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
        addWorldLight(lighting, drawX() + hitSize() / 2, drawY() + hitSize() / 2, 0,
                2.5 * ts, new Color(255, 240, 210));
    }

    private void addProjectileLight(LightingPass lighting, ProjectileDef def,
                                    double x, double y, double ts) {
        if (def == null || !def.glows()) return;
        addWorldLight(lighting, x, y, 0, def.lightRadius() * ts, def.lightColor());
    }

    /** Scratch for the eye's projection of a light; see {@link #addWorldLight}. */
    private final double[] lightPoint = new double[3];

    /**
     * A light at a world position, sized in world units, projected through
     * whichever camera this frame is being drawn with.
     *
     * <p>{@link LightingPass} works in screen pixels — it is a shader pass over
     * the finished frame — so every light has to be projected, and the four
     * places that feed it were each doing that projection by hand against the
     * flat camera. One of them even had to spell out the height lift itself. In
     * a solid view all four would have lit the wrong pixels; asking here
     * instead means the answer is right in both views and is only written once.
     *
     * @param radiusWorld the light's reach in world units, before either
     *                    camera's scaling
     */
    private void addWorldLight(LightingPass lighting, double wx, double wy, double wz,
                               double radiusWorld, Color color) {
        if (radiusWorld <= 0) return;
        if (solidView()) {
            if (!eye.project(wx, wy, wz, lightPoint)) return;
            double scale = eye.scaleAt(lightPoint[2]);
            if (scale <= 0) return;
            lighting.addLight(lightPoint[0], lightPoint[1], radiusWorld * scale, color);
            return;
        }
        camera.worldToScreen(wx, wy, corner);
        int lift = (int) Math.round(wz * camera.zoom * camera.liftScale());
        lighting.addLight(corner[0], corner[1] - lift, radiusWorld * camera.zoom, color);
    }

    // --- pause ---

    private void openPause() {
        paused = true;
        // Any sheet from the last time this menu was open starts closed: a
        // pause that opens straight into "you have unsaved progress" is asking
        // a question nobody just asked for.
        confirmQuit = false;
        bindsForm = null;
        optionsForm = null;
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
        optionsForm = null;
        confirmQuit = false;
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

    /**
     * The pause menu's actions.
     *
     * <p>Grouped rather than listed: what you came here to do (resume, save),
     * then what you came here to change (options, controls), then how you
     * leave. The title is left off because {@link PauseScreen} draws a header
     * with the level and the save state in it, and two titles is one too many.
     *
     * <p>A level's feature toggles are still edited in <em>Level Select → Edit
     * Settings</em> rather than here: those belong to the level and outlive the
     * session, and a pause menu that quietly rewrites the level being played is
     * how this engine used to lose people's work.
     *
     * <p><b>The view distances are the exception, and they earn it.</b> They
     * were on that settings screen, which is the one place a player cannot
     * reach them: how far to draw is a judgement about the machine and about
     * the moment — the vista you climbed a mountain for wants it long, the
     * fight that started stuttering wants it short — and answering it used to
     * mean leaving the level, opening the editor's settings, and coming back to
     * find out whether it had helped. Here they are three sliders you drag
     * while looking at what they change.
     */
    private void buildPauseForm() {
        GameProfile p = profile();
        pauseForm = new ConfigForm("Paused").theme(MenuTheme.dark()).rowHeight(40);
        pauseForm.addAction("Resume", this::resume);

        if (net == null) {
            // Greyed out rather than hidden when there is nothing to save: an
            // entry that appears and disappears teaches nobody where it lives.
            pauseForm.addAction("Save Run", this::saveRun)
                    .enabledWhen(() -> run != null);
            pauseForm.addAction("Save and Quit", () -> {
                saveRun();
                quitToMenu();
            }).enabledWhen(() -> run != null);
        }

        addViewDistanceRows(p);

        pauseForm.addNote("");
        pauseForm.addAction("Options", this::openOptions);
        pauseForm.addAction("Controls", this::openKeyBinds);
        pauseForm.addAction("Edit in Creative", () -> scenes.transitionTo("creative"))
                .enabledWhen(() -> p.creativeEnabled);

        pauseForm.addNote("");
        if (net == null) {
            // Quitting without saving is still allowed — it is just no longer
            // silent. A menu click that throws away an hour with no word about
            // it is the one thing this menu could do that a player could not
            // undo or even notice until it was too late.
            pauseForm.addAction("Quit to Menu", () -> {
                if (run != null && run.dirty()) confirmQuit = true;
                else quitToMenu();
            });
        } else {
            // Online the server owns the world, so there is nothing here to
            // save and no version of leaving that keeps it.
            pauseForm.addAction(net.isHost() ? "Stop Server & Quit" : "Disconnect & Quit",
                    this::leaveSession);
        }
    }

    /**
     * The three sliders that say how far this frame draws, live.
     *
     * <p><b>Render distance</b> is how far of the world you can see at all, and
     * belongs to the level: a creator who built a world to be looked across
     * says how far across. <b>Detail distance</b> is how much of that is drawn
     * a block at a time, and belongs to the player, because it is the one
     * number that decides what a frame costs — everything past it is drawn as
     * landforms, for a few thousand quads however far the render distance
     * reaches ({@link SolidPainter#setDetailTiles}). <b>Horizon</b> is the
     * coarse landscape drawn <em>beyond</em> the render distance, which is
     * scenery rather than world.
     *
     * <p>Only on a generated world, because that is the only kind of level
     * these numbers mean anything on: a hand-built level is drawn to its own
     * edges whatever they say.
     */
    private void addViewDistanceRows(GameProfile p) {
        if (level == null || !level.isWorld() || p == null || p.terrain == null) return;
        var terrain = p.terrain;
        PlayerSettings settings = PlayerSettings.active();
        PlayerSettingsStore store = new PlayerSettingsStore();

        pauseForm.addNote("");
        pauseForm.addNote("— VIEW —");
        pauseForm.addSlider("Render distance (blocks)", () -> terrain.renderDistance,
                v -> {
                    terrain.renderDistance = v;
                    // The horizon is measured from the render distance, so it
                    // cannot be inside it — the coarse pass would have nothing
                    // to draw and the setting would read as broken.
                    if (terrain.distantDistance > 0 && terrain.distantDistance < v) {
                        terrain.distantDistance = v;
                    }
                }, 2, TerrainSettings.MAX_RENDER_DISTANCE);
        pauseForm.addSlider("Detail distance (blocks)", () -> settings.detailDistance,
                v -> {
                    settings.detailDistance = v;
                    store.trySave(settings);
                }, PlayerSettings.MIN_DETAIL_DISTANCE, PlayerSettings.MAX_DETAIL_DISTANCE);
        pauseForm.addSlider("Horizon (blocks, 0 = off)", () -> terrain.distantDistance,
                        v -> terrain.distantDistance = v == 0 ? 0
                                : Math.max(v, terrain.renderDistance),
                        0, TerrainSettings.MAX_DISTANT_DISTANCE)
                .enabledWhen(() -> PlayerSettings.active().distantTerrain);
        pauseForm.addNote("Past the detail distance the world is drawn as "
                + "landforms — cheaper, and the same however far you see.");
        pauseForm.addNote("The horizon needs \"Distant terrain\" on in Options.");
    }

    /** Open the controls sheet over the pause menu (see {@link #updatePaused}). */
    private void openKeyBinds() {
        bindsForm = KeyBindForm.forActiveBinds(() -> bindsForm = null);
    }

    /**
     * Open the options sheet over the pause menu: volume, look sensitivity,
     * invert-Y and HUD size. Applied live and saved to the player's own file as
     * each row changes, so a level never carries the result — see
     * {@link PlayerSettings}.
     */
    private void openOptions() {
        optionsForm = PlayerOptionsForm.forActiveSettings(
                ctx::applyPlayerSettings, () -> optionsForm = null);
    }

    /**
     * The sheet a dirty quit puts up: save and go, go anyway, or think better
     * of it. Built lazily like the others, and named after the thing at stake
     * rather than after the button that was pressed.
     */
    private ConfigForm confirmQuitForm() {
        if (confirmQuitForm == null) {
            confirmQuitForm = new ConfigForm("Unsaved progress").theme(MenuTheme.dark());
            confirmQuitForm.addNote("This run has changes that are not on disk yet.");
            confirmQuitForm.addAction("Save and Quit", () -> {
                saveRun();
                confirmQuit = false;
                quitToMenu();
            });
            confirmQuitForm.addAction("Quit without Saving", () -> {
                confirmQuit = false;
                quitToMenu();
            });
            confirmQuitForm.addAction("Back", () -> confirmQuit = false);
        }
        return confirmQuitForm;
    }

    /** Leave for the launch menu, releasing the run's writer thread. */
    private void quitToMenu() {
        closeRun();
        scenes.transitionTo("menu");
    }

    /**
     * Save the run: the player — health, position, what they are carrying, what
     * they have done — and every level this run has changed, into its own slot.
     *
     * <p><b>This used to be <em>Save Level</em>, and it saved the wrong noun.</b>
     * It wrote the level, which is a complete and correct save of everything
     * except the person playing, and it wrote it back over the level's
     * <em>authored</em> file — so pressing it preserved the mountain you dug
     * and lost the diamonds you dug out of it, while quietly editing the level
     * for every future run. Both halves of that are fixed by writing a
     * {@link RunSession} instead: the player is in the record, and the world
     * goes in the slot, next to it.
     */
    private void saveRun() {
        if (run == null) {
            ruleStatus = "Nothing to save — the server owns this world";
            ruleStatusTime = 3.0;
            return;
        }
        GameProfile p = profile();
        p.normalize();
        // The level still carries the toggles it is being played with, as it
        // always did; what changed is which file that copy of the level is.
        level.captureSettings(p);
        run.capture(p.name, level, me, inventory, stats, timeOfDay());
        run.rememberFired(level.name, ruleEngine);
        run.markLevelDirty(level.name); // an explicit save writes, revision or not
        run.saveNow(level);             // synchronous: the player asked, and is watching
        ctx.save();
        ruleStatus = "Saved — " + level.name + " · "
                + RunRecord.formatPlayTime(run.record().playSeconds);
        ruleStatusTime = 3.0;
    }

    /**
     * Write the run in the background, if anything has happened. The quiet
     * counterpart of {@link #saveRun()}: no message, no waiting, and nothing at
     * all when the run is clean.
     */
    private void autosave() {
        if (run == null || level == null) return;
        // Profiled like every other stage, because the part of a save that
        // happens on this thread — the snapshot — is the part that can cost a
        // frame, and a cost that does not appear in the report is a cost
        // nobody can attribute. It shows up beside `update` and `scene` in the
        // same `[F3]` breakdown. The stringify and the file write are on the
        // writer thread and are deliberately *not* in this measurement, which
        // is the point of them being there.
        phase("autosave", () -> {
            run.capture(profile().name, level, me, inventory, stats, timeOfDay());
            run.rememberFired(level.name, ruleEngine);
            run.saveAsync(level);
        });
    }

    /**
     * Advance the run's clock and let the periodic autosave fire.
     *
     * <p><b>What counts as "something has happened".</b> The obvious answer —
     * mark the run dirty on every frame it is being played — makes the quit
     * prompt appear one frame after a save, which teaches a player to ignore
     * it. The events that already exist mark themselves (a block changed, a
     * reward fired, a chest was opened, a death); movement is the one that has
     * no event, so it is measured instead, against the position the last save
     * actually wrote. A tile of travel is the threshold because it is the
     * smallest move worth reloading into.
     */
    private void tickRun(double dt) {
        if (run == null) return;
        run.tick(dt);
        RunRecord saved = run.record();
        if (Math.abs(me.x - saved.x) > ts() || Math.abs(me.y - saved.y) > ts()) {
            run.markDirty();
        }
        // Every terrain edit — mined, placed, or made by the simulation itself
        // (a bomb crater, water finding a new cell) — is already counted by the
        // level, so asking it is cheaper and more complete than instrumenting
        // each of the places one can happen.
        if (run.levelNeedsWrite(level)) run.markDirty();
        if (run.autosaveDue()) autosave();
    }

    /**
     * Read this frame's run into the shape the pause screen draws.
     *
     * <p>Taken fresh every frame rather than once when the menu opens: online
     * the world keeps running while a player is paused, so ping, player count
     * and health are live numbers, and offline an autosave can land while the
     * menu is up — a save chip that still says "unsaved changes" a minute after
     * it saved is worse than no chip at all.
     */
    PauseScreen.Status pauseStatus() {
        GameProfile p = profile();
        PauseScreen.Status s = new PauseScreen.Status();
        s.gameType = p.name;
        s.levelName = level == null ? "" : level.name;
        s.characterName = character == null ? "" : character.name;
        Ultimate ult = Ultimates.of(me);
        s.ultimateName = ult == null ? "" : ult.name();
        // The format's display name rather than the enum constant: "3D", not
        // "THREE_D". A pause screen is player-facing.
        s.world = camera == null ? ""
                : LevelFormat.of(camera.getPerspective()).displayName() + " · up is "
                        + PerspectiveSpace.of(camera.getPerspective()).upLabel();
        s.viewpoint = hasElevation() ? viewpoint.label() : "";

        s.health = me.health;
        s.maxHealth = me.maxHealth;
        s.mana = me.mana;
        s.maxMana = me.maxMana;
        s.stamina = me.stamina;
        s.maxStamina = me.maxStamina;
        s.ultCharge = me.ultCharge;

        // The clock is only worth naming when the level actually runs one;
        // otherwise "Dawn" is a reading of a number nothing moves.
        s.timeOfDay = p.dayNightCycle ? timeOfDay() : -1;

        if (stats != null) s.stats = stats.all();
        s.objectives = objectives();
        s.keyHints = pauseKeyHints();

        if (net != null) {
            s.online = true;
            Snapshot snap = net.client().latest();
            s.players = snap != null ? snap.players().size() : 1;
            s.ping = net.client().pingMillis();
            s.host = net.isHost();
            if (s.host && net.hostedServer() != null) s.port = net.hostedServer().getPort();
        } else if (run != null) {
            s.slot = run.store().slot();
            s.playSeconds = run.record().playSeconds;
            s.savedAt = run.record().savedAt;
            s.unsaved = run.dirty();
            s.writing = run.writing();
        }
        return s;
    }

    /**
     * The level's stat rules as goals: where the player stands against each,
     * and whether a one-shot has already paid out.
     *
     * <p>Every rule, not only the ones whose author ticked "show bar" — that
     * flag decides what crowds the HUD during play, which is a different
     * question from what a paused player is allowed to know.
     */
    private List<PauseScreen.Objective> objectives() {
        List<PauseScreen.Objective> out = new ArrayList<>();
        if (ruleEngine == null || stats == null || level == null) return out;
        for (StatRule rule : level.statRules) {
            int fired = ruleEngine.firedCount(rule);
            boolean done = !rule.repeat() && fired > 0;
            double target = rule.threshold() * (rule.repeat() ? fired + 1 : 1);
            String detail = done ? "done"
                    : (long) stats.get(rule.stat()) + " / " + (long) target
                            + (fired > 0 ? "  ×" + fired : "");
            out.add(new PauseScreen.Objective(goalLabel(rule),
                    detail, ruleEngine.progress(rule, stats), done));
        }
        return out;
    }

    /** "Blocks Mined → 1× diamond", the rule as a sentence about its reward. */
    private static String goalLabel(StatRule rule) {
        String label = PlayerStats.label(rule.stat());
        if (rule.rewardItem() == null) return label;
        return label + " → " + rule.rewardCount() + "× " + rule.rewardItem();
    }

    /** The binds worth restating on a screen somebody opened because they were stuck. */
    private List<String[]> pauseKeyHints() {
        List<String[]> hints = new ArrayList<>();
        GameProfile p = profile();
        hints.add(new String[] {KeyBinds.label(GameAction.JUMP), "Jump"});
        hints.add(new String[] {KeyBinds.label(GameAction.INTERACT), "Doors, chests, mounts"});
        if (p.itemsEnabled) {
            hints.add(new String[] {KeyBinds.label(GameAction.INVENTORY), "Inventory"});
        }
        if (Ultimates.of(me) != null) {
            hints.add(new String[] {KeyBinds.label(GameAction.ULTIMATE), "Ultimate"});
        }
        if (hasElevation()) {
            hints.add(new String[] {KeyBinds.label(GameAction.TOGGLE_VIEW), "First/third person"});
        }
        if (p.zoomEnabled) {
            hints.add(new String[] {KeyBinds.label(GameAction.ZOOM_IN) + "/"
                    + KeyBinds.label(GameAction.ZOOM_OUT), "Zoom"});
        }
        return hints;
    }

    private void drawPauseOverlay(DrawTarget target) {
        if (confirmQuit || optionsForm != null || bindsForm != null) {
            target.pushAlpha(0.82f);
            target.fillRect(0, 0, viewportWidth, viewportHeight, PAUSE_SCRIM);
            target.popAlpha();
        }

        if (confirmQuit) {
            confirmQuitForm().render(target, viewportWidth, viewportHeight);
            return;
        }
        if (optionsForm != null) {
            optionsForm.render(target, viewportWidth, viewportHeight);
            target.drawText(PlayerOptionsForm.HINT, 24, viewportHeight - 24, HUD_FONT,
                    SceneChrome.HINT);
            return;
        }
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
        PauseScreen.render(target, viewportWidth, viewportHeight, pauseForm, pauseStatus());
        target.drawText(net == null
                        ? "Esc to resume · the run saves itself at every door, every death, "
                                + "and every couple of minutes"
                        : "Esc to resume · the game keeps running on the server while you are "
                                + "paused",
                24, viewportHeight - 20, HUD_FONT, SceneChrome.HINT);
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

    // --- the view the player is looking through ---

    /**
     * Whether this frame is drawn through the eye rather than the flat camera.
     *
     * <p>Three conditions rather than one, and they are here rather than at the
     * dozen call sites because every one of them has to agree — the renderer,
     * the aim, the lighting and the depth queue all branch on this, and two of
     * them answering differently in one frame is a picture drawn through one
     * camera and lit through the other.
     *
     * <ul>
     *   <li>The player has chosen a solid view.</li>
     *   <li>The level has a height axis to stand an eye in. Walking through a
     *       door into a side-scroller has to put the view back, and this is
     *       what makes that impossible to forget; {@link #syncCameraFromProfile}
     *       resets the choice as well, so the HUD says the same thing.</li>
     *   <li>No cutscene is running. A cutscene's director drives the flat
     *       camera along a timeline authored through it, and its actors are
     *       painted through it too — so the plan view is not a fallback here,
     *       it is the view the scene was written for.</li>
     * </ul>
     */
    private boolean solidView() {
        return viewpoint.solid() && hasElevation()
                && (cutscenes == null || cutscenes.active() == null);
    }

    /**
     * Whether this level has a height axis for an eye to stand in — the
     * question {@link Viewpoint#availableIn} asks, answered from the level's
     * own format rather than from the camera, so it is the same answer online
     * and off.
     */
    private boolean hasElevation() {
        return PerspectiveSpace.of(basePerspective()).hasElevation();
    }

    /**
     * The heading anything looking at the world should use: where the player is
     * looking in a solid view, and the flat camera's own settled heading
     * otherwise.
     *
     * <p>Asked rather than picked at each call site for the reason
     * {@link Camera#viewYaw()} exists: there are two headings in this scene
     * and only one of them is the one the picture is actually drawn at.
     */
    private double viewYaw() {
        return solidView() && viewpoint.freeLook() ? lookYaw : camera.viewYaw();
    }

    /**
     * [F5]: the next view this level can show.
     *
     * <p>The two cameras hand each other the heading as they swap. Going in,
     * the eye starts facing wherever the flat camera was pointing, so the world
     * does not spin under the player at the moment they press the key; coming
     * out, the flat camera is <em>set</em> to the nearest compass point to
     * where they were looking rather than turned toward it, because {@code
     * setYaw} is the teleport and {@code turn} is what a player does — and a
     * snap animation starting on the first frame of a new view is an animation
     * of something that never moved.
     */
    private void cycleViewpoint() {
        Viewpoint next = viewpoint.next(hasElevation(), camera.lock());
        if (next == viewpoint) {
            // One view and this is it. Say which of the two reasons it is
            // rather than swallowing the press: a key that does nothing and
            // does not explain itself reads as a broken key, and "this level
            // does not allow it" and "this kind of level cannot have it" are
            // different things to be told.
            ruleStatus = hasElevation()
                    ? "This level keeps its camera in the plan view"
                    : "This level is a side-scroller — no first-person view here";
            ruleStatusTime = 2.5;
            return;
        }
        boolean wasFree = viewpoint.freeLook();
        viewpoint = next;
        if (!wasFree && viewpoint.freeLook()) {
            lookYaw = camera.viewYaw();
            lookPitch = 0;
            forgetLookMotion();
        } else if (wasFree && !viewpoint.freeLook()) {
            camera.setYaw(Math.floorMod((int) Math.round(lookYaw / Camera.EIGHTH_TURN), 8)
                    * Camera.EIGHTH_TURN);
        }
        ctx.sfx(Sfx.CLICK);
        ruleStatus = "View: " + viewpoint.label();
        ruleStatusTime = 1.6;
    }

    /** How fast the look keys turn the eye, radians per second. */
    private static final double LOOK_KEY_SPEED = Math.toRadians(120);

    /**
     * Radians of turn per pixel of pointer movement, at 100% sensitivity. The
     * player's own multiplier rides on top of it — see {@link #lookStep()}.
     */
    private static final double LOOK_SENSITIVITY = Math.toRadians(0.22);

    /**
     * Turn per pixel for this player: the engine's base rate scaled by their
     * saved sensitivity.
     *
     * <p>Read every frame rather than cached, because the options form edits
     * {@link PlayerSettings#active()} in place and a sensitivity slider that
     * only takes effect after a level reload is a slider nobody can tune.
     */
    private static double lookStep() {
        return LOOK_SENSITIVITY * PlayerSettings.active().lookSensitivity;
    }

    /**
     * Which way a downward pointer movement takes the eye: {@code -1} looks up
     * (the flight-stick convention), {@code +1} looks down.
     *
     * <p>Worth a setting rather than a constant because for a substantial
     * number of people an uninvertible Y axis does not mean the mouse feels
     * wrong — it means the {@code [F5]} views are unusable, and a whole third
     * of the engine's cameras may as well not exist.
     */
    private static double lookPitchSign() {
        return PlayerSettings.active().invertLook ? 1 : -1;
    }

    /**
     * The fraction of the window at each edge that keeps turning the view while
     * the pointer rests in it.
     */
    private static final double LOOK_EDGE = 0.10;

    /**
     * Aim the eye: the mouse turns it, and the rotate/look keys turn it too.
     *
     * <p><b>The pointer is locked, and the motion is what is read.</b> Every 3D
     * game holds the cursor in one place and reads how far the hand moved
     * between frames, never where the arrow ended up, and that is what happens
     * here: {@link Pointer#lockTo} hides the pointer and pins it to the middle
     * of the window every frame — natively where the window can hold it (the GL
     * backend's GLFW window), through a {@code Robot} where it can only be
     * moved (AWT) — and {@link InputManager#consumeMouseMotion} hands back the
     * hand's own travel with the recentring discounted out of it. There is
     * therefore no edge to run out of and no turn that stops halfway round,
     * which is what "the mouse runs out of space" was.
     *
     * <p><b>Recentred every frame rather than at the edges.</b> Warping only
     * once the arrow neared an edge was the previous scheme and it is worse in
     * both directions: between recentres the pointer really is sliding across
     * the desk, so an alt-tab or a click lands wherever it happens to have got
     * to, and every recentre had to throw a frame of motion away because the
     * reading was a <em>position</em> difference. Motion that survives a warp
     * costs nothing to recentre, so it is done always and the pointer never
     * leaves the middle of the window at all.
     *
     * <p>Edge-steering stays for the one window that can do neither — a
     * headless canvas, a desktop that refuses a {@code Robot} — where resting
     * in the outer tenth of the window keeps turning, which is the control that
     * makes running out of desk survivable when the pointer cannot be held.
     *
     * <p>Nothing here is bound to a level or a session: it is the local
     * player's view and stays entirely on this client (C10).
     */
    private void steerLook(InputManager input, double dt) {
        int mx = input.getMouseX(), my = input.getMouseY();
        int w = Math.max(1, viewportWidth), h = Math.max(1, viewportHeight);
        // Hidden first, then held: hiding the arrow is how this view says it
        // has taken the mouse over, and Pointer.lockTo refuses to pin a pointer
        // that is still being drawn.
        Pointer.setVisible(false);
        boolean locked = Pointer.lockTo(input, w / 2, h / 2);

        input.consumeMouseMotion(lookMotion);
        if (lookMotionStale) {
            lookMotionStale = false;
            lookMotion[0] = 0;
            lookMotion[1] = 0;
        }
        double step = lookStep(), pitchSign = lookPitchSign();
        if (lookMotion[0] != 0 || lookMotion[1] != 0) pointerMoved = true;
        lookYaw += lookMotion[0] * step;
        lookPitch += lookMotion[1] * step * pitchSign;

        double marginX = w * LOOK_EDGE, marginY = h * LOOK_EDGE;
        // Steering from the edges, so a turn is never cut short by the window
        // — but only once the pointer has been moved at least once since this
        // view was entered. Without that a cursor that simply happens to be
        // resting in a corner when the key is pressed spins the world on its
        // own, which is the one way this control can behave like a fault.
        if (pointerMoved && !locked) {
            if (mx < marginX) lookYaw -= edgePush(marginX - mx, marginX) * dt;
            else if (mx > w - marginX) lookYaw += edgePush(mx - (w - marginX), marginX) * dt;
            // Inverted the same way the drag is: resting against an edge is the
            // same gesture as moving toward it, so the two must agree or the
            // view reverses direction as the pointer crosses the margin.
            double edgeSign = -pitchSign;
            if (my < marginY) lookPitch += edgeSign * edgePush(marginY - my, marginY) * dt;
            else if (my > h - marginY) {
                lookPitch -= edgeSign * edgePush(my - (h - marginY), marginY) * dt;
            }
        }

        // The keys, for anyone who would rather not steer with the mouse. Not
        // inverted with the mouse: "Look Up" is a named action, and a key that
        // says up and goes down is a bug in any convention.
        if (KeyBinds.down(input, GameAction.ROTATE_LEFT)) lookYaw -= LOOK_KEY_SPEED * dt;
        if (KeyBinds.down(input, GameAction.ROTATE_RIGHT)) lookYaw += LOOK_KEY_SPEED * dt;
        if (KeyBinds.down(input, GameAction.LOOK_UP)) lookPitch += LOOK_KEY_SPEED * dt;
        if (KeyBinds.down(input, GameAction.LOOK_DOWN)) lookPitch -= LOOK_KEY_SPEED * dt;

        // Wrapped rather than left to run on, so the number a save or a HUD
        // reads is always a heading and never an accumulated total.
        lookYaw = wrapAngle(lookYaw);
        lookPitch = Math.max(-EyeCamera.MAX_PITCH, Math.min(EyeCamera.MAX_PITCH, lookPitch));
    }

    /** Turn rate for a pointer {@code into} pixels inside an edge of {@code margin}. */
    private static double edgePush(double into, double margin) {
        double t = Math.max(0, Math.min(1, into / Math.max(1, margin)));
        return LOOK_KEY_SPEED * 1.6 * t * t;
    }

    /** An angle folded back into [0, 2π). */
    private static double wrapAngle(double radians) {
        double full = Math.PI * 2;
        double a = radians % full;
        return a < 0 ? a + full : a;
    }

    /**
     * How far above their own feet a character's eyes are, as a fraction of
     * their drawn height. Just under the top of the sprite, which is where
     * eyes are.
     */
    private static final double EYE_HEIGHT = 0.82;

    /**
     * How close the camera may end up to the player before their own body
     * stops being drawn, in tiles. Nearer than this a billboard is most of the
     * screen and the player cannot see what they are walking into, which is
     * why every third-person game hides the model when its camera is pushed
     * in this far.
     */
    private static final double BODY_FADE_TILES = 1.5;

    /** How far the camera stands from the player this frame, world units. */
    private double eyeDistance;

    /**
     * How wide the plan view's see-through channel is, in tiles.
     *
     * <p>About two, so a doorway's worth of wall goes and the room beside it
     * stays: narrower and the player's own sprite is still half behind a block
     * corner, wider and half the building disappears every time you stand near
     * it. The channel fades out toward its rim, so the clear part of it is a
     * good deal less than this.
     */
    private static final double CUTAWAY_TILES = 1.9;

    /**
     * Place the eye for this frame: at the player's own eyes in first person,
     * and pulled back along the view for the third-person stops.
     *
     * <p><b>The pull-back is shortened by whatever it would otherwise pass
     * through.</b> A camera three tiles behind you is inside the wall three
     * tiles behind you as soon as you stand with your back to one, and a camera
     * inside a block sees the inside of a block — every face around it is
     * back-facing, so the screen goes to whatever the sky happens to be. So the
     * same march the crosshair uses is run backwards along the view, and the
     * camera stops just short of the first thing it meets. This is what every
     * third-person game does and it is the reason they all pull in when you
     * back into a corner.
     *
     * @param cx     the player's centre on the world plane
     * @param cy     the same, on the other axis
     * @param bodyZ  how high their feet are above the floor
     */
    private void placeEye(double cx, double cy, double bodyZ) {
        double ts = ts();
        double eyeZ = eyeOutOfBlocks(cx, cy, bodyZ + drawSize() * EYE_HEIGHT);
        eye.setViewport(viewportWidth, viewportHeight);
        if (!viewpoint.freeLook()) {
            placePlanEye(cx, cy, eyeZ);
            return;
        }
        double yaw = viewpoint.reversed() ? wrapAngle(lookYaw + Math.PI) : lookYaw;
        double pitch = viewpoint.reversed() ? -lookPitch : lookPitch;
        eye.look(yaw, pitch);
        eyeDistance = 0;
        double back = viewpoint.distanceTiles() * ts;
        if (back <= 0) {
            eye.place(cx, cy, eyeZ);
            return;
        }
        // Over the shoulder rather than out of the eyes: the camera is lifted
        // clear of the head before it is pulled back, so on the broken ground
        // this engine's height axis makes ordinary it is looking over the next
        // step up rather than into it.
        double from = eyeZ + ts * 0.35;
        eye.place(cx, cy, from);
        // Backwards: the eye looks the other way, marches, and turns round
        // again. Reusing the forward march is what keeps "what the camera can
        // pass through" the same answer as "what the crosshair can reach".
        eye.look(wrapAngle(yaw + Math.PI), -pitch);
        double[] behind = SolidPainter.aimPoint(eye, level, back);
        double blocked = Math.hypot(Math.hypot(behind[0] - cx, behind[1] - cy),
                behind[2] - from) - ts * 0.3;
        // Never all the way in. A camera that collapses onto the player when
        // the ground behind them rises is worse than one that clips a corner
        // of it, and the body stops being drawn below BODY_FADE_TILES anyway.
        eyeDistance = Math.max(ts * 0.9, Math.min(back, blocked));
        eye.look(yaw, pitch);
        eye.place(cx - eye.dirX() * eyeDistance, cy - eye.dirY() * eyeDistance,
                Math.max(0.1, from - eye.dirZ() * eyeDistance));
    }

    /**
     * The shortest the plan view's arm may get, in tiles, however far the
     * player has zoomed in. Below this the camera is over the player's shoulder
     * rather than over the world, which is a different view and there are three
     * of those on the same key.
     */
    private static final double PLAN_MIN_TILES = 5;

    /**
     * Place the plan view's camera: back and up from the player, along the flat
     * camera's own heading and tilt.
     *
     * <p><b>Three things come from the flat camera and one does not.</b> The
     * heading is {@link Camera#viewYaw()}, which snaps to the eight compass
     * points the character sprites have frames for and animates between them;
     * the tilt is {@link Camera#pitch()}, which a player sweeps with the look
     * keys and which reads here as it always did — {@code 90°} is straight
     * down, {@code 0°} is along the ground. The distance is the {@link Camera#zoom}
     * turned inside out: zooming in is walking the camera closer, which is what
     * a zoom <em>is</em> once the projection divides by depth, and it keeps the
     * zoom keys meaning what they have always meant.
     *
     * <p>What does not come from it is the collision. The mouse-look views pull
     * their camera in until nothing is in the way; a dozen tiles of arm cannot
     * do that without becoming a first-person view every time the player walks
     * under a roof, so the arm is kept and whatever is standing in it is drawn
     * see-through instead ({@link SolidPainter#setCutaway}). The one case that
     * still moves the camera is it ending up <em>inside</em> something, where
     * there is nothing to see through because every face is turned away: then
     * it comes in until it is in open air.
     */
    private void placePlanEye(double cx, double cy, double eyeZ) {
        double ts = ts();
        // Straight down is where a heading stops meaning anything, so the eye
        // stops just short of it — the same bound every mouse-look has, applied
        // to a tilt a level may legitimately have authored at ninety degrees.
        double pitch = -Math.min(camera.pitch(), EyeCamera.MAX_PITCH);
        eye.look(camera.viewYaw(), pitch);
        double zoom = Math.max(0.05, camera.zoom);
        double back = Math.max(PLAN_MIN_TILES, viewpoint.distanceTiles() / zoom) * ts;
        double from = eyeZ + ts * 0.35;
        eyeDistance = back;
        double px = cx - eye.dirX() * back;
        double py = cy - eye.dirY() * back;
        double pz = Math.max(0.1, from - eye.dirZ() * back);
        // Inside a block, and only then: walk the arm in until it is not. The
        // march runs from the player outward, so the point it stops at is the
        // furthest the camera can stand and still be in the open.
        if (SolidPainter.opaque(level, (int) Math.floor(px / ts),
                (int) Math.floor(py / ts), (int) Math.floor(pz / ts))) {
            eye.place(cx, cy, from);
            eye.look(wrapAngle(camera.viewYaw() + Math.PI), -pitch);
            double[] behind = SolidPainter.aimPoint(eye, level, back);
            double blocked = Math.hypot(Math.hypot(behind[0] - cx, behind[1] - cy),
                    behind[2] - from) - ts * 0.3;
            eyeDistance = Math.max(ts * 1.5, Math.min(back, blocked));
            eye.look(camera.viewYaw(), pitch);
            px = cx - eye.dirX() * eyeDistance;
            py = cy - eye.dirY() * eyeDistance;
            pz = Math.max(0.1, from - eye.dirZ() * eyeDistance);
        }
        eye.place(px, py, pz);
    }

    /**
     * {@code eyeZ}, brought below whatever it is standing inside.
     *
     * <p>A character's eyes are just under the top of their sprite, and a
     * character is a block tall — so walking under a ceiling exactly one block
     * over the floor (a crawlspace, the underside of a bridge deck, the inside
     * of a doorway with a lintel) put the eye <em>in</em> the block above.
     * Every face around it is turned away from there, so the view fills with
     * whatever the sky is: you are looking at the inside of a wall. The body
     * is where the collision says it is; only the eye needs bringing back, and
     * bringing it just under the block it was in is what ducking looks like.
     */
    private double eyeOutOfBlocks(double cx, double cy, double eyeZ) {
        int ts = level.tileSize;
        if (ts <= 0 || !level.layered()) return eyeZ;
        int col = (int) Math.floor(cx / ts), row = (int) Math.floor(cy / ts);
        int box = (int) Math.floor(eyeZ / ts);
        if (!SolidPainter.opaque(level, col, row, box)) return eyeZ;
        return Math.max(0.1, box * (double) ts - ts * 0.08);
    }

    /**
     * Whether the player's own body is drawn this frame: not in first person,
     * and not when the camera has been pushed in close enough that it would
     * fill the screen. See {@link #BODY_FADE_TILES}.
     */
    private boolean drawsOwnBody() {
        return viewpoint.showsSelf() && eyeDistance >= BODY_FADE_TILES * ts();
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
        // A level opens looking the way it was built. It is a starting point
        // and not a constraint: the player may turn from there whenever they
        // like, and where they turn to is theirs alone — C10 keeps the heading
        // off the wire, so two players may face different ways in one world.
        // Walking through a door into a level authored from another heading
        // lands settled on that one rather than sliding into it. C9.
        // The level's camera rules first, so the heading and tilt below are
        // placed inside them rather than corrected a frame later.
        camera.setLock(p.cameraLock);
        camera.setYaw(level.authoredHeading * Camera.EIGHTH_TURN);
        // …and at the tilt it was built from, which is the other half of the
        // same sentence now that the camera has two axes a creator can move.
        camera.setPitch(Camera.pitchFor(level.authoredPitchDegrees));
        // A door can lead from a plan-view level into a side-scroller, which
        // has no height axis and so no solid view to be in — or into a level
        // that allows only the plan view. The choice goes back to the level's
        // own view rather than being held in reserve: a player who walks back
        // out should be told what they are looking through, and the HUD reads
        // this field.
        if (!hasElevation() || !camera.lock().allows(viewpoint)) viewpoint = Viewpoint.PLAN;
        // A played level's camera trails the player's height with slack, so a
        // jump moves the character rather than the ground (Camera.restHeight).
        // Set here rather than once at construction because a door can replace
        // the level under a camera that a cutscene left rigid.
        camera.setHeightFollow(Camera.HeightFollow.EASED);
        lookYaw = camera.viewYaw();
        lookPitch = 0;
        forgetLookMotion();
    }

    /**
     * Start the mouse-look from a standstill: whatever travel the pointer has
     * banked while it was an arrow is not a flick of the camera, and neither is
     * a cursor that happens to be resting in a corner.
     */
    private void forgetLookMotion() {
        pointerMoved = false;
        lookMotionStale = true;
    }

    private double clampZoom(double z, GameProfile p) {
        return Math.max(p.minZoom, Math.min(p.maxZoom, z));
    }

    // --- rendering helpers ---

    /**
     * The tile-index rectangle that can possibly be visible, found by
     * inverse-projecting the viewport corners into world space (for isometric,
     * the corners map to a diamond; its bounding box is a conservative cover)
     * and then reaching out along the height axis. Rendering cost then scales
     * with the screen, not the level size.
     */
    private int[] visibleTileBounds() {
        if (solidView()) {
            // The eye sees a disc of the world rather than a projected
            // rectangle of it, so the square around that disc is the bound.
            // Only the lighting sweep asks in this view; the terrain painter
            // trims the same square to the circle itself.
            double reach = solid.viewTiles() * (double) level.tileSize;
            return new int[]{
                    Math.max(0, (int) Math.floor((eye.x() - reach) / level.tileSize)),
                    Math.max(0, (int) Math.floor((eye.y() - reach) / level.tileSize)),
                    Math.min(level.width - 1,
                            (int) Math.floor((eye.x() + reach) / level.tileSize)),
                    Math.min(level.height - 1,
                            (int) Math.floor((eye.y() + reach) / level.tileSize))};
        }
        return TerrainPainter.visibleBounds(camera, level, viewportWidth, viewportHeight);
    }

    // --- what the player is aiming at ---

    /**
     * The world point being aimed at — where a shot goes, what a swing is
     * pointed at, where an ultimate is called down.
     *
     * <p>Two entirely different questions wearing one name, which is why it is
     * a method rather than a line repeated at each call site. In a plan view
     * the mouse points <em>at the world</em> and the answer is the projection
     * inverted under the cursor. In a solid view the mouse is steering the eye
     * instead, so it points at nothing; what is being aimed at is whatever the
     * crosshair — the middle of the screen — is on, found by marching the eye's
     * own ray into the terrain.
     */
    private double[] aimPoint() {
        if (solidView()) {
            double[] dir = aimRay();
            double[] hit = SolidPainter.aimPoint(eye, level, REACH_TILES * ts() * 4,
                    dir[0], dir[1], dir[2]);
            return new double[]{hit[0], hit[1]};
        }
        return camera.screenToWorld(mouseX, mouseY);
    }

    /** Scratch for the ray a solid view aims along; see {@link #aimRay}. */
    private final double[] aimDir = new double[3];

    /**
     * The direction this frame aims along, from the eye.
     *
     * <p>Two views, two rays, and the difference is who the mouse belongs to. A
     * mouse-look view has taken the pointer over to steer with, so what it is
     * aiming at is whatever the crosshair — the middle of the screen — is on.
     * The plan view still has a pointer, so it aims through the pixel that
     * pointer is on, which is the same thing the flat camera's
     * {@code screenToWorld} used to answer and a better answer than that one
     * was: a ray meets the side of a tower where an inverted floor point
     * resolves to the cell behind it.
     */
    private double[] aimRay() {
        if (viewpoint.freeLook()) {
            aimDir[0] = eye.dirX();
            aimDir[1] = eye.dirY();
            aimDir[2] = eye.dirZ();
            return aimDir;
        }
        eye.rayThrough(mouseX, mouseY, aimDir);
        return aimDir;
    }

    /**
     * The block being aimed at, and where one placed against it would go.
     *
     * <p>{@link TerrainPainter#pick} marches a parallel projection back from
     * the floor point under the cursor; {@link SolidPainter#pick} marches the
     * eye's ray forward through the volume. Both answer with the same
     * {@link TerrainPainter.Aim}, so everything downstream — mining, placing,
     * the reach test — is written once and does not know which camera asked.
     *
     * <p><b>The march is given the camera's own set-back as well as the
     * player's reach.</b> Reach is measured from the player and the ray starts
     * at the camera, and in a third-person view those are three tiles apart —
     * so a march bounded by the reach alone would stop short of blocks the
     * player can perfectly well touch, and the caller would fall back to the
     * floor cell under the aim point and place a block on top of the wall
     * instead of against it.
     */
    private TerrainPainter.Aim aimBlock() {
        if (solidView()) {
            double[] dir = aimRay();
            return SolidPainter.pick(eye, level, REACH_TILES * ts() + eyeDistance,
                    dir[0], dir[1], dir[2]);
        }
        return TerrainPainter.pick(camera, level, mouseX, mouseY);
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
        // Anything standing between the camera and the player is drawn
        // see-through, or walking indoors is walking into nothing: a roof is
        // geometry nearer the camera than the player under it, and the painter
        // is right to cover them (O3).
        TerrainPainter.draw(target, level, camera, visibleTileBounds(), animClock,
                standing, containerPanel == null ? null : this::drawOpenLid,
                miningStroke(), terrainCache,
                TerrainPainter.cutaway(camera, level,
                        drawX() + hitSize() / 2, drawY() + hitSize(), drawZ()));
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
     * the tie-breaker among everything sharing one tile's depth. {@code x,y}
     * is a sprite's top-left corner and {@code size} its world extent, the
     * way the level stores entities.
     */
    private int footDepth(double x, double y, double size) {
        return TerrainPainter.pointDepth(camera, x + size / 2, y + size);
    }

    /**
     * The depth of the tile those feet are on — what a
     * {@link DepthPass} orders by, and what a raised block is measured on
     * ({@link TerrainPainter#tileDepth}).
     */
    private int standDepth(double x, double y, double size) {
        return TerrainPainter.standingDepth(camera, level.tileSize, x + size / 2, y + size);
    }

    /**
     * Queue {@code sprite} where a body of {@code size} at (x,y) is standing.
     *
     * <p>Every actor on the floor goes through here, so the two halves of a
     * plan view's depth — which tile you are on, and where on it — are decided
     * in one place rather than at twenty call sites.
     */
    /**
     * The height something happening on the ground at (wx,wy) sits at — the top
     * of the column under it.
     *
     * <p>Every ground-anchored effect needs it. A mining burst on top of a
     * tower spawned at zero falls out of the bottom of the tower and puffs on
     * the floor eight blocks below the pick that made it, which is the sort of
     * thing nobody files a bug about and everybody notices.
     */
    private double surfaceZ(double wx, double wy) {
        return world == null ? 0 : world.restingZ(wx, wy);
    }

    private void standingAt(DepthPass into, double x, double y, double size, Runnable sprite) {
        standingAt(into, x, y, size, 0, sprite);
    }

    /**
     * {@link #standingAt} for a body standing {@code z} above the floor — the
     * third key of a plan view's depth.
     *
     * <p>Everything on the ground passes zero and sorts exactly as it did. A
     * body on a column passes its own height and sorts with that column rather
     * than with the floor beside it, which is what puts a player on a roof in
     * front of the wall below them instead of behind it.
     */
    private void standingAt(DepthPass into, double x, double y, double size,
                            double z, Runnable sprite) {
        if (solidView()) {
            // Same sprite, same code, drawn as a billboard: the anchor is the
            // ground contact point every sprite in this scene is drawn around,
            // and the pivot is where this frame's flat camera would have put
            // it — including the lift, which every one of those call sites
            // applies with the same three factors. See SolidPainter.billboard.
            double ax = x + size / 2.0, ay = y + size;
            int lift = (int) Math.round(z * camera.zoom * camera.liftScale());
            // A patch of ground under them first: a billboard is a flat picture
            // standing in the air, and without a shadow there is nothing in a
            // solid view to say whether it is standing on the floor or hanging
            // a block above it. The plan view has drawn one since it grew a
            // height axis (drawPlayer); this is the same thing said in three
            // dimensions.
            double ground = level.verticality()
                    ? PlayerPhysics.groundZ(level, x, y, size) : 0;
            solid.groundShadow(ax, ay, ground, size * 0.42);
            solid.billboard(ax, ay, z, camera.worldToScreenX(ax, ay),
                    camera.worldToScreenY(ax, ay) - lift, camera.zoom, sprite);
            return;
        }
        // Cut by the same plane the terrain is when the camera lies flat on
        // the floor: a slice that removed the wall in front of you but kept the
        // mob behind it would be showing a creature through a wall the picture
        // no longer has (TerrainPainter.cutOff).
        if (TerrainPainter.cutOff(camera, level.tileSize, x + size / 2, y + size)) return;
        into.at(standDepth(x, y, size), TerrainPainter.standingLayer(level, z),
                footDepth(x, y, size), sprite);
    }

    /** {@link #standingAt} for a player-sized body. */
    private void standingAt(DepthPass into, double x, double y, Runnable sprite) {
        standingAt(into, x, y, hitSize(), sprite);
    }

    /**
     * Painted doors: tinted door shapes anchored at their base.
     *
     * <p>Routed through {@link #standingAt} rather than drawn straight to the
     * screen, which is what puts them in the solid views as well — a door is a
     * flat shape standing on the floor, so it is a billboard there and a
     * depth-sorted sprite here, and either way it is this one piece of drawing.
     * They were missing from every view but the flat one, which is a poor thing
     * for a door: it is the way out of the level.
     */
    private void drawDoors(DrawTarget target, DepthPass into) {
        double ts = ts();
        for (Level.EntitySpawn e : level.entities) {
            if (!"door".equals(e.kind)) continue;
            DoorLink link = doors.get(e.type);
            Color tint = link != null ? link.color() : new Color(150, 105, 60);
            // Anchored at the door's own foot: standingAt takes the corner of a
            // box and reads its ground contact point off the far side, so the
            // spawn point is handed to it as the middle of a tile-sized one.
            standingAt(into, e.x - ts / 2, e.y - ts, ts, 0,
                    () -> drawDoor(target, e, tint, ts));
        }
    }

    private void drawDoor(DrawTarget target, Level.EntitySpawn e, Color tint, double ts) {
        int dw = Math.max(8, (int) Math.round(ts * 0.9 * camera.zoom));
        int dh = Math.max(12, (int) Math.round(ts * 1.6 * camera.zoom));
        camera.worldToScreen(e.x, e.y, corner);
        int x = corner[0] - dw / 2, y = corner[1] - dh;
        target.fillRoundRect(x, y, dw, dh, dw / 3, dw / 3, tint);
        target.drawRoundRect(x, y, dw, dh, dw / 3, dw / 3, tint.darker(), 2f);
        int knob = Math.max(2, dw / 6);
        target.fillOval(x + dw - knob * 2, y + dh / 2, knob, knob, new Color(255, 235, 170));
    }

    /** "[E] Enter …" prompt while standing at a linked door. */
    private void drawDoorHint(DrawTarget target, GameProfile p) {
        double half = hitSize() / 2.0;
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
                Vehicle near = world.mountableNear(me.x + hitSize() / 2, me.y + hitSize() / 2);
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

    /**
     * Mobs + items + projectiles + vehicles: the offline world's, or the
     * snapshot's.
     *
     * <p>Both branches now interpolate, against their own clock. The replicated
     * branch has always straddled the two buffered snapshots either side of
     * render time, because drawing the raw latest one "read as constant stutter"
     * — see the comment where it does it. The offline branch drew the raw latest
     * <em>simulation step</em>, which is the same mistake against a 120 Hz
     * broadcast instead of a 30 Hz one, and is now blended with the loop's own
     * {@code alpha}. See {@link com.larsons.engine.sim.StepInterpolation}.
     */
    private void drawWorldEntities(DrawTarget target, GameProfile p, DepthPass into) {
        if (net == null) {
            double a = renderAlpha;
            for (Vehicle v : world.vehicles()) {
                double vx = v.renderX(a), vy = v.renderY(a);
                standingAt(into, vx, vy, v.def.size(), () ->
                        drawVehicleSprite(target, v.def, vx, vy, v.facingLeft));
            }
            for (DroppedItem item : world.items()) {
                double ix = item.renderX(a), iy = item.renderY(a);
                standingAt(into, ix, iy, DroppedItem.SIZE, () ->
                        drawItemSprite(target, item.key, ix, iy, item.count));
            }
            for (Mob m : world.mobs()) {
                // A mob mid-move draws that move, on its weapon's own sheets.
                String state = m.meleeAction().isEmpty()
                        ? stateKeyFor(m.state.ordinal(), m.hurting()) : m.meleeAction();
                double mx = m.renderX(a), my = m.renderY(a);
                standingAt(into, mx, my, m.def.size(), () ->
                        drawMobSprite(target, m.def, mx, my, m.facing, m.health, m.hurting(),
                                state, m.statusBits(), m.weaponKey(),
                                m.melee.action(), m.meleeProgress()));
            }
            for (Projectile pr : world.projectiles()) {
                double px = pr.renderX(a), py = pr.renderY(a), pz = pr.renderZ(a);
                standingAt(into, px, py, 0, () ->
                        drawProjectileSprite(target, pr.def.key(), px, py, pz, pr.vx, pr.vy));
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
                    standingAt(into, vx, vy, def.size(), () ->
                            drawVehicleSprite(target, def, vx, vy, v.facingLeft));
                }
            }
            if (predictedVehicle != null) {
                // From our own prediction rather than the snapshot, so it never
                // lags the player glued to its saddle — and interpolated on the
                // same alpha as that player, for the same reason.
                Vehicle mount = predictedVehicle;
                double mx = mount.renderX(renderAlpha), my = mount.renderY(renderAlpha);
                standingAt(into, mx, my, mount.def.size(), () ->
                        drawVehicleSprite(target, mount.def, mx, my, mount.facingLeft));
            }
            old = viewsById(from.items());
            for (EntityView item : to.items()) {
                double ix = lerpX(old.get(item.id), item, t);
                double iy = lerpY(old.get(item.id), item, t);
                standingAt(into, ix, iy, DroppedItem.SIZE, () ->
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
                    standingAt(into, mx, my, def.size(), () ->
                            drawMobSprite(target, def, mx, my, mv.facing, mv.health, false,
                                    state, mv.status, mv.weapon, move, mv.meleeProgress));
                }
            }
            old = viewsById(from.shots());
            for (EntityView s : to.shots()) {
                double sx = lerpX(old.get(s.id), s, t), sy = lerpY(old.get(s.id), s, t);
                standingAt(into, sx, sy, 0, () ->
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
        int lift = (int) Math.round(z * camera.liftScale() * camera.zoom);
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
        Facing dir = seen(facing == null ? Facing.EAST : facing);
        PlayerSprites.Frame resolved = MeleeSprites.mobFrame(def.key(), weapon, state,
                dir, animClock, moveProgress);
        BufferedImage img = resolved == null ? null : resolved.image();
        boolean mirror = resolved != null && resolved.mirrored();
        if (img == null) {
            img = EntitySprites.mob(def, 32, dir);
            mirror = false;
        }
        // Drawn at its own size, standing on the floor its footprint covers —
        // the same two answers a player's sprite is drawn from.
        int w = (int) Math.round(def.size() * camera.zoom);
        camera.worldToScreen(x + def.hitbox() / 2, y + def.hitbox(), corner);
        int dx = corner[0] - w / 2;
        int dy = corner[1] - w;
        if (mirror) target.drawImage(img, dx + w, dy, -w, w);
        else target.drawImage(img, dx, dy, w, w);
        // Whatever it fights with, drawn in its hands and swept by the move.
        drawHeldObject(target, x, y, 0, def.hitbox(), def.size(), dir, weapon,
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

    /**
     * How far up the screen the local player is drawn by standing where they
     * are — the one place the lift is spelled out, so everything drawn on the
     * body agrees with the body.
     */
    private int bodyLift() {
        return (int) Math.round(drawZ() * camera.zoom * camera.liftScale()
                * PlayerPhysics.HOP_DRAW_SCALE);
    }

    /** A short arc in front of the player while a mining or firing stroke plays. */
    private void drawSwing(DrawTarget target) {
        camera.worldToScreen(drawX() + hitSize() / 2, drawY() + hitSize() / 2, corner);
        // Lifted with the body, like every other thing drawn on it.
        corner[1] -= bodyLift();
        int r = (int) (drawSize() * camera.zoom * 0.9);
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
    /**
     * A world direction as this scene's camera currently sees it — the one
     * conversion C5 needs, in the one place a world facing becomes art.
     *
     * <p>Every sprite here is a billboard: it faces the camera, and the
     * direction it is <em>doing</em> is told by which of the eight sheets is
     * drawn. So the sheet has to be chosen relative to the heading, or a
     * character walking north goes on being drawn from behind after the camera
     * has walked round to look at their side. The alternative — eight sets of
     * art per direction — is eight times the drawing for no gameplay, in a
     * project that ships none of it.
     *
     * <p>Deliberately one method rather than a conversion at each call site.
     * A facing reaches art through the body sheet, the object in its hands and
     * the arc its swing draws, and those three disagreeing is a player whose
     * sword points somewhere their arm does not.
     */
    private Facing seen(Facing facing) {
        return facing == null ? null : facing.asSeenFrom(viewYaw());
    }

    /**
     * Whether characters are drawn from the overhead sprite pool this frame —
     * the flat camera raised past {@link Camera#OVERHEAD_PITCH}.
     *
     * <p>The solid views are excluded, and not for want of a pitch: theirs is
     * the player's own look, which sweeps the whole way up and down several
     * times in a minute of ordinary play. Swapping every character's art each
     * time somebody glanced at the floor would be a flicker rather than a view.
     * The flat camera's tilt is a setting a player chooses and leaves, which is
     * what makes it a thing art can be chosen by.
     */
    private boolean overheadView() {
        return !viewpoint.freeLook() && PlayerSprites.overhead(camera);
    }

    private void drawMeleeArc(DrawTarget target, MeleeProfile profile) {
        camera.worldToScreen(drawX() + hitSize() / 2, drawY() + hitSize() / 2, corner);
        // <b>Up the screen with the player.</b> The swing, the parry ring and
        // the lunge are drawn around where the character is, and "where the
        // character is" has had a height axis since they could climb: without
        // this the indicators stayed on the floor while the player they belong
        // to stood on top of a tower, which reads as somebody else's attack
        // happening at the foot of the wall.
        corner[1] -= bodyLift();
        int r = (int) Math.round(profile.reach() * camera.zoom);
        MeleeAction action = melee.action();
        double t = melee.progress();
        // The sweep is brightest through the hit window and fades out with the
        // recovery, so what you see is what is actually dangerous.
        int alpha = (int) (200 * (melee.striking() ? 1 : 0.35));
        Facing aim = seen(me.facing);
        double facingDeg = -Math.toDegrees(Math.atan2(aim.dy(), aim.dx()));
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
    private void drawHeldObject(DrawTarget target, double x, double y, double z,
                                double hit, double draw,
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
        // Sized and placed against the sprite — an object is held by the
        // character you can see, not by the patch of floor they stand on — but
        // anchored where they stand, so hand and feet belong to one body.
        int w = Math.max(6, (int) Math.round(draw * hold.scale() * camera.zoom * 0.7));
        Facing dir = facing == null ? Facing.EAST : facing;
        int flip = dir.facingLeft() ? -1 : 1;
        int footX = camera.worldToScreenX(x + hit / 2.0, y + hit);
        int footY = camera.worldToScreenY(x + hit / 2.0, y + hit);
        // Through the camera's own lift scale, like drawPlayer: without it the
        // object in a climbing character's hands drifted away from the hands.
        int lift = (int) Math.round(z * camera.zoom * camera.liftScale()
                * PlayerPhysics.HOP_DRAW_SCALE);
        double cx = footX + flip * hold.offsetX() * draw * camera.zoom;
        double cy = footY - draw * camera.zoom * 0.5 - lift
                + hold.offsetY() * draw * camera.zoom;

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

        for (PlayerState ps : to.players()) {
            if (ps.id == me.id) continue;
            PlayerState old = from.player(ps.id);
            double x = old != null ? old.x + (ps.x - old.x) * t : ps.x;
            double y = old != null ? old.y + (ps.y - old.y) * t : ps.y;
            // Remote players wear their own character's skin, hold their own
            // weapon, and face their own direction — all of it rides along in
            // the snapshot, so a swing looks like a swing from across the map.
            // Their size comes the same way: the character store is part of
            // the game type both ends loaded, so a giant is a giant on every
            // screen without a byte of it going over the wire.
            CharacterProfile theirs = Characters.getOrDefault(ps.characterKey);
            double hit = ActorSize.pixels(theirs.hitboxScale, ts());
            double draw = ActorSize.pixels(theirs.spriteScale, ts());
            // The team ring is painted on the floor through the flat camera,
            // so it is left out of a solid view rather than drawn in the wrong
            // place: it is a plan-view marker, and a ring under someone's feet
            // seen from their own eye height is a line.
            if (mgView != null && !solidView()) {
                MiniGameHud.drawTeamRing(target, camera, x + hit / 2, y + hit,
                        draw, mgView.teamOf(ps.id), camera.zoom);
            }
            Color body = remoteBody(ps.id, theirs);
            String state = ps.meleeAction.isEmpty()
                    ? (ps.moving ? "walk" : "idle") : ps.meleeAction;
            PlayerSprites.Frame sprite = MeleeSprites.playerFrame(
                    ps.characterKey, ps.heldKey, state, seen(ps.facing),
                    animClock, ps.meleeProgress, (int) draw, body, overheadView());
            MeleeAction move = MeleeAction.byKey(ps.meleeAction);
            standingAt(into, x, y, hit, ps.z, () -> {
                drawPlayer(target, x, y, ps.z, hit, draw, sprite, ps.name);
                drawHeldObject(target, x, y, ps.z, hit, draw, seen(ps.facing), ps.heldKey,
                        move, ps.meleeProgress, MeleeProfiles.ofKey(ps.heldKey));
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
                            double hit, double draw,
                            PlayerSprites.Frame sprite, String nameTag) {
        if (sprite == null || sprite.image() == null) return;
        // The billboard stands on the hitbox's base line and is drawn at its
        // own size around it: where a character stands and how large they are
        // drawn are separate answers (see ActorSize), and this is the one place
        // the two meet.
        int w = (int) Math.round(draw * camera.zoom);
        int h = w;
        int footX = camera.worldToScreenX(x + hit / 2.0, y + hit);
        int footY = camera.worldToScreenY(x + hit / 2.0, y + hit);
        int dx = footX - w / 2;
        int lift = (int) Math.round(z * camera.zoom * camera.liftScale()
                * PlayerPhysics.HOP_DRAW_SCALE);
        // What they would land on if they stopped here — the top of the column
        // under their feet rather than the floor of the world. A player on a
        // roof throws their shadow onto the roof; only the part of their height
        // that is a hop reads as being in the air.
        double surface = level.verticality()
                ? PlayerPhysics.groundZ(level, x, y, hit) : 0;
        int surfaceLift = (int) Math.round(surface * camera.zoom * camera.liftScale());
        double airborne = Math.max(0, z - surface);
        // …and not in a solid view, where the ground under an actor is a real
        // quad on the real floor (SolidPainter.groundShadow). This oval is
        // drawn inside the billboard, so there it would stand upright in the
        // air beside the character rather than lying under them — two shadows,
        // one of them facing the camera.
        if (airborne > 0 && !solidView()) {
            // The shadow marks where they will land, shrinking with height. It
            // is cast by the body rather than the sprite: a shadow is the floor
            // they occupy, so a giant with small feet throws a small one.
            int foot = (int) Math.round(hit * camera.zoom);
            double shrink = Math.max(0.35, 1 - airborne / (draw * 3));
            int sw = (int) (foot * 0.7 * shrink), sh = Math.max(2, (int) (foot * 0.25 * shrink));
            target.fillOval(footX - sw / 2, footY - surfaceLift - sh / 2, sw, sh,
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

    // --- HUD scale ---------------------------------------------------------
    // Every HUD dimension below used to be a literal against a pixel viewport,
    // which is legible at 720p and unreadable at 4K. These two helpers put the
    // player's hudScale in front of the literals rather than re-deriving each
    // one, so the numbers still read as the sizes they are.
    //
    // At the default scale of 1.0 both are the identity — Math.round(n * 1.0)
    // is n — which is what lets the golden frames keep asserting the exact
    // pixels they always did.

    /** A HUD dimension in pixels, scaled by the player's HUD size setting. */
    private int hud(int px) {
        double scale = PlayerSettings.active().hudScale;
        return scale == 1.0 ? px : (int) Math.round(px * scale);
    }

    /**
     * {@code base} at the player's HUD size. Cached per (font, scale) rather
     * than derived per call: the glyph atlas keys on the font instance, so
     * handing the renderer a freshly-built-but-equal font every frame would
     * turn every string in the HUD into an atlas miss.
     */
    private Font hudFont(Font base) {
        double scale = PlayerSettings.active().hudScale;
        if (scale == 1.0) return base;
        if (scale != scaledFontScale) {
            scaledFonts.clear();
            scaledFontScale = scale;
        }
        return scaledFonts.computeIfAbsent(base, f ->
                f.deriveFont((float) Math.max(1, Math.round(f.getSize() * scale))));
    }

    private final Map<Font, Font> scaledFonts = new HashMap<>();
    private double scaledFontScale = 1.0;

    private void drawHud(DrawTarget target) {
        target.fillRect(0, 0, viewportWidth, hud(38), new Color(0, 0, 0, 150));
        StringBuilder hud = new StringBuilder();
        // Naming where up points says which physics this level is running —
        // the formats differ in more than how they are drawn.
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        hud.append(profile().name)
                .append("    |    ").append(camera.getPerspective())
                .append(" · up is ").append(space.upLabel());
        if (hasElevation()) {
            hud.append("    |    ").append(viewpoint.label())
                    .append("  [").append(KeyBinds.label(GameAction.TOGGLE_VIEW))
                    .append("] view");
        }
        // Where the camera is standing, in the units a player thinks in. It is
        // on the HUD rather than left to be felt because the tilt is the one
        // camera control with a threshold in it: at OVERHEAD_DEGREES the
        // characters change to their overhead art, and a number that says how
        // close you are to that is the difference between a feature and a
        // surprise.
        if (!solidView() && camera.tilts()) {
            hud.append("    |    camera ")
                    .append((int) Math.round(camera.pitchDegrees())).append((char) 0x00B0);
            // The two angles that change what you are looking at rather than
            // only how far you are tilted, named where a player can see them.
            if (camera.sliced()) hud.append(" sliced");
            else if (camera.overhead()) hud.append(" overhead");
            if (camera.lock().isFree()) {
                hud.append("  [").append(KeyBinds.label(GameAction.LOOK_UP))
                        .append("/").append(KeyBinds.label(GameAction.LOOK_DOWN))
                        .append("] tilt");
            } else {
                // A locked camera's keys do less than they say, so the HUD says
                // what the level allows instead of naming keys that will not
                // move it.
                hud.append("  locked: ").append(camera.lock().describe());
            }
        }
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
        target.drawText(hud.toString(), hud(12), hud(24), hudFont(HUD_FONT), Color.WHITE);
    }

    private void drawHealthBar(DrawTarget target) {
        int w = hud(180), h = hud(14);
        int x = hud(12), y = viewportHeight - hud(28);
        target.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 6, 6, new Color(0, 0, 0, 160));
        target.fillRect(x, y, w, h, new Color(120, 30, 30));
        target.fillRect(x, y, (int) (w * Math.max(0, me.health / me.maxHealth)), h,
                new Color(220, 60, 60));
        target.drawText((int) Math.ceil(me.health) + " / " + (int) me.maxHealth,
                x + w / 2 - hud(20), y + hud(11), hudFont(SMALL_FONT), Color.WHITE);
    }

    private void drawHotbar(DrawTarget target) {
        int slot = hud(44), pad = hud(5);
        int total = Inventory.HOTBAR * (slot + pad) - pad;
        int x0 = (viewportWidth - total) / 2;
        int y0 = viewportHeight - slot - hud(10);
        for (int i = 0; i < Inventory.HOTBAR; i++) {
            int x = x0 + i * (slot + pad);
            boolean sel = inventory.selectedIndex() == i;
            target.fillRoundRect(x, y0, slot, slot, 8, 8, new Color(0, 0, 0, sel ? 200 : 140));
            target.drawRoundRect(x, y0, slot, slot, 8, 8,
                    sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 70), sel ? 2.5f : 1f);
            drawStack(target, inventory.slot(i), x, y0, slot);
            target.drawText(String.valueOf(i + 1), x + hud(4), y0 + hud(12),
                    hudFont(SMALL_FONT), new Color(255, 255, 255, 130));
        }
        drawSelectedItemName(target, inventory.selectedDef(), y0);
    }

    /** The selected hotbar item's name, floated above the bar in its rarity colour. */
    private void drawSelectedItemName(DrawTarget target, ItemDef def, int hotbarTop) {
        if (def == null) return;
        Font font = hudFont(HUD_FONT);
        int tw = target.textWidth(def.name(), font);
        int x = (viewportWidth - tw) / 2, y = hotbarTop - hud(10);
        target.fillRoundRect(x - 8, y - hud(14), tw + 16, hud(20), 8, 8, new Color(0, 0, 0, 160));
        target.drawText(def.name(), x, y, font, def.rarity().color);
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

    /**
     * How much floor the local player occupies, world pixels — what collides,
     * what the camera follows, and what everything measuring <em>where they
     * are</em> anchors on.
     */
    private double hitSize() { return me.hitSize(profile().playerSize); }

    /**
     * How large the local player is <em>drawn</em>, world pixels.
     *
     * <p>Deliberately not the same accessor as {@link #hitSize()}: they were
     * one number, and one number is what a call site could quietly get wrong
     * once they came apart. Splitting them means the compiler asked about
     * every use rather than letting the old name keep answering.
     */
    private double drawSize() { return me.spriteSize(profile().playerSize); }

    /**
     * The space the player's own body is simulated in.
     *
     * <p>Online, physics must not depend on the local camera view — the server
     * simulates the level's own format, so prediction does too. Offline the
     * camera may be switched in-game and the simulation follows it. Anything
     * that has to agree with the step — the shape a placed block is tested
     * against, for one — asks this rather than guessing at one of the two.
     */
    private Perspective simPerspective() {
        return net != null ? level.perspective : camera.getPerspective();
    }

}
