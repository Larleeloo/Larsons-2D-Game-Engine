package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.audio.SceneSounds;
import com.larsons.engine.audio.SoundDef;
import com.larsons.engine.audio.SoundKeys;
import com.larsons.engine.audio.SoundLoader;
import com.larsons.engine.audio.SoundPack;
import com.larsons.engine.audio.SoundSynth;
import com.larsons.engine.audio.Sounds;
import com.larsons.engine.character.CharacterPicker;
import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.character.Characters;
import com.larsons.engine.character.Ultimate;
import com.larsons.engine.character.Ultimates;
import com.larsons.engine.combat.Melee;
import com.larsons.engine.combat.MeleeAction;
import com.larsons.engine.combat.MeleeProfile;
import com.larsons.engine.combat.MeleeProfiles;
import com.larsons.engine.combat.MeleeSounds;
import com.larsons.engine.combat.MeleeSprites;
import com.larsons.engine.combat.MeleeState;
import com.larsons.engine.config.CustomContentStore;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
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
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.DecorPainter;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SpriteCanvas;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Brush;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutsceneDirector;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.EditHistory;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.level.StatRule;
import com.larsons.engine.minigame.MiniGame;
import com.larsons.engine.minigame.MiniGameConfig;
import com.larsons.engine.minigame.Team;
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
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.ui.SpriteEditorPanel;
import com.larsons.engine.ui.UiText;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
import com.larsons.engine.world.World;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creative Mode: the Side-Scroller engine's level editor, rebuilt on this
 * engine's camera/scene/registry architecture — <em>paint objects into the
 * world</em>. A palette sidebar offers every registered block (including
 * liquids), light, mob, item, decoration, and the game type's doors, plus
 * editor tools; left-click paints (drag to keep painting, grid-snapped for
 * blocks), right-click over the canvas erases, middle-click picks the hovered
 * block. Levels are saved per game type ({@link LevelStore}) and played back
 * with exactly the features the game type enables.
 *
 * <p><b>Three creative modes, one editor:</b> a level belongs to one of the
 * engine's three {@link LevelFormat}s — side-scroller, top-down, isometric —
 * and this scene <em>is</em> that format's creative mode: it opens the level's
 * camera projection, paints and play-tests under that format's movement model,
 * defaults its generator accordingly, and offers the path/wall block families
 * only while building the plan-view formats. Everything else — mobs, items,
 * decorations, block details, lights, liquids, doors, cutscenes, mini-game
 * markers — is shared by all three and behaves in each. The main menu picks
 * which mode to open; the <em>New Level</em> and <em>Generate</em> dialogs'
 * Format row switches modes in place.
 *
 * <p><b>Level size sliders &amp; giant maps:</b> the sidebar's bottom panel
 * has live width / height sliders — drag to resize the level in place
 * (content preserved). The <em>override map size</em> button unlocks them
 * past 1024&times;1024 up to 65536&times;65536; giant levels use sparse
 * chunked storage ({@link ChunkedTiles} via {@link Level}) so only visited
 * chunks cost anything, and giant <em>generated</em> levels build their
 * chunks lazily as the camera reaches them.
 *
 * <p><b>Brushes:</b> the Brush row (or {@code [}/{@code ]}) picks a stroke
 * shape and size — square/circle/diamond/line/spray footprints paint or
 * erase many block cells per stamp ({@link Brush}).
 *
 * <p><b>"+" custom content:</b> each creatable category's first palette
 * entry opens a property form for a brand-new block/liquid/light/mob/item/
 * decoration/block decor; creations register live and persist per game type
 * via {@link CustomContentStore}.
 *
 * <p><b>Surface details (block decor):</b> the SURFACE category attaches
 * per-face details (grass tufts, moss, twigs…) to blocks, with toggle rows
 * for the face (auto/up/down/left/right), the open/closed-face condition, and
 * the background/foreground layer ({@link SurfaceDecor}); its "+" entry
 * creates custom block decor (colours, silhouette style, allowed faces).
 *
 * <p><b>Stat rules:</b> Tools → Stat Rules… edits the level's programmable
 * triggers over tracked stats ("mined 50 blocks → reward…"), which run
 * during play-test/play ({@link StatRule}, {@link StatRuleEngine}).
 *
 * <p><b>Cutscenes:</b> the CUTSCENES palette scripts triggerable cutscenes —
 * each has a trigger (walk into a zone, press E at a marker, or level start),
 * a cast of sprite-sheet <em>actors</em> with named animation states (idle /
 * walk / talk / anything; per-state sheet, frame size, count, fps, loop), and
 * an ordered step script (show / say / move / anim / wait / camera / hide).
 * Painting a cutscene entry places its trigger marker; play-test and play run
 * them with letterbox bars and skippable captions ({@link Cutscene},
 * {@link CutsceneDirector}, {@link CutscenePlayer}).
 *
 * <p><b>Mini games:</b> the MINI GAME palette turns a level into an online
 * team game: Mini Game Setup… picks the mode (Capture the Flag, Stockpile,
 * Battle, Escort) and its rules (teams, PvP, score/time limits, Stockpile's
 * resource items), and the palette's markers build the arena — flag bases,
 * stockpile crates, team spawns, and the escort payload's waypoint path, all
 * placeable anywhere on the map ({@link MiniGame}, {@link MiniGameConfig}).
 * The setup saves inside the level, so hosting that level runs the game for
 * everyone who joins; offline play referees the same rules locally for solo
 * testing.
 *
 * <p><b>Doors:</b> the Doors palette is built from the game type's external
 * {@link DoorDirectory} ({@code doors.json} beside its saved levels); each
 * entry names another level of the same game type, and painted doors load it
 * when entered in play or play-test.
 *
 * <p><b>Decorations:</b> trees, rocks, and other scenery paint into either
 * the background layer (behind terrain) or the foreground (in front of
 * players) — toggle with {@code B} or the layer button.
 *
 * <p><b>Textures:</b> the palette swatches show the texture each object
 * actually renders with, so a reskin is visible in the sidebar the moment it
 * applies. Sheets come from the drop-in {@link TexturePack} folder beside the
 * jar — named after the object, no menu visit needed — or from a path picked
 * per object. Right-click any palette icon for that object's texture dialog:
 * the pack switch (on by default, built-in art as the fallback), the file
 * name the pack wants for it, the frame size/length/rate, and a "sheet
 * elsewhere" path for art outside the pack; mobs get one sheet per animation
 * state. Assignments persist in {@code skins.json} and pack exceptions in the
 * pack's own {@code texturepack.json}. The Tools palette's Player Skin… entry
 * does the same for the player character, with one animation per action state
 * (idle/walk/run/jump/fall/swim) played back in play-test and play.
 *
 * <p><b>Create texture:</b> the same dialog's "✎ Create texture" opens a
 * paint window ({@link SpriteEditorPanel}) over the editor — pencil, eraser,
 * fill, line, rectangle, eyedropper, a palette and undo, with a frame strip
 * that builds an animation frame by frame (each new frame starting as a copy
 * of the last, the previous one showing through as an onion skin) and a
 * preview playing at the chosen rate. It is offered for every skinnable
 * object, built-in or custom, and the "+ New …" form opens it straight after
 * creating one. Saving writes the sheet into the texture pack under that
 * object's own file name, so drawn art and dropped-in art are the same thing.
 *
 * <p><b>Generate:</b> the Tools palette's Generate button builds a large
 * Perlin-noise level — Minecraft-style terrain/caves/ores/liquids fused with
 * a connected Metroidvania room network ({@link LevelGenerator}).
 *
 * <p><b>Play-test:</b> {@code P} drops a player at the spawn and simulates
 * the painted world with the real physics/mob/item code — including the
 * level's own character roster (its picker opens first, exactly as it will
 * for a player), a full inventory (hold-to-mine against block durability with
 * tool speed-ups, pick up items, place from the hotbar, eat, shoot on mana,
 * sprint on stamina, craft at stations with {@code E}), {@code Space} to jump
 * or hop, {@code R} to fire the character's ultimate, and door travel;
 * {@code P}/Esc returns to editing with the terrain restored (test-mode
 * mining isn't destructive).
 *
 * <p><b>Online:</b> opened from a multiplayer session (pause menu), the same
 * editor paints into the <em>server's</em> world: block strokes and
 * mob/item paints become protocol requests, the authoritative results
 * broadcast to every player, and other players appear live while you paint.
 * Save/load/test/resize and the non-replicated markers stay disabled online.
 *
 * <p><b>Undo:</b> {@code Ctrl+Z} takes back the last thing done, {@code Ctrl+Y}
 * (or {@code Ctrl+Shift+Z}) puts it back, and the history goes as far back as
 * the session does. A step is an <em>action</em>, not a change: a whole brush
 * drag comes back at once, and so does a window's worth of editing, so nothing
 * has to be walked back a cell or a field at a time ({@link EditHistory}).
 *
 * <p>It covers every edit — the blocks and details a stroke lays down or takes
 * off, painted mobs/items/decorations/doors/markers, the spawn and cutscene
 * markers, live resizes, <em>New</em>/<em>Load</em>/<em>Generate</em> (which
 * hand the previous level back), stat rules, cutscenes with their actors and
 * scripts, mini game setup, the character roster, the sun, the level's music,
 * the door directory, the objects "+ New …" creates and the palette deletes,
 * and the textures and sounds assigned to them. Three things it deliberately
 * leaves alone: <em>looking</em> (camera, grid, palette selection, brush
 * settings, decor layer) is not an edit; <em>saving</em> is not either, so
 * Ctrl+Z never un-writes a level file or a sheet drawn in the paint window
 * (which has an undo of its own); and painting a <em>server's</em> world is
 * the server's to answer for, so undo is offline only.
 *
 * <p>Controls: WASD/arrows pan · wheel zoom (over canvas) / scroll palette
 * (over sidebar) · Tab category · left paint · right erase (canvas) /
 * texture (palette) · middle pick · B decor layer · G grid · P test ·
 * Ctrl+Z undo · Ctrl+Y redo · Ctrl+S save · L load · N new · Esc menu/back.
 */
public class CreativeScene extends AbstractScene {

    private static final int SIDEBAR_W = 192;
    private static final int CELL = 52;
    private static final int CELLS_PER_ROW = 3;
    private static final double PAN_SPEED = 640;   // world px/sec at zoom 1
    private static final double MIN_ZOOM = 0.15, MAX_ZOOM = 3.0;
    private static final double ERASE_RADIUS = 24; // world px for entity erase

    private static final Font HUD_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);

    // Level size sliders + brush controls (the sidebar's bottom panel).
    private static final int SLIDER_PANEL_H = 122;
    private static final int MIN_LEVEL_W = 16, MIN_LEVEL_H = 16;
    /** Slider cap without the "override map size" toggle. */
    private static final int STANDARD_MAX_SIZE = 1024;

    /** What the palette can paint (and, for the last few, configure). */
    private enum Category { BLOCKS, LIQUIDS, LIGHTS, MOBS, ITEMS, DECOR, SURFACE, DOORS,
        CHARACTERS, EFFECTS, SOUNDS, CUTSCENES, MINIGAME, TOOLS }

    private enum Dialog { NONE, NEW_LEVEL, SAVE, LOAD, CONFIRM_EXIT, GENERATE, DOORS, TEXTURE,
        CUSTOM, RULES, BRUSH, CUTSCENES, CUTSCENE_ACTORS, CUTSCENE_STEPS, MINIGAME,
        ROSTER, SOUNDS, SOUND, SOUND_OPTIONS, LEVEL_MUSIC, SUNLIGHT }

    /** {@code custom} marks user-created objects (badged, deletable). */
    private record Entry(String kind, String key, String name, BufferedImage icon,
                         boolean custom) {
        Entry(String kind, String key, String name, BufferedImage icon) {
            this(kind, key, name, icon, false);
        }
    }

    private final GameContext ctx;

    private Level level;
    private Camera camera;
    private NetSession net; // non-null when editing a multiplayer world
    private DoorDirectory doors;

    // Palette state.
    private final Map<Category, List<Entry>> palette = new EnumMap<>(Category.class);
    private final Map<Category, Integer> selected = new EnumMap<>(Category.class);
    private final Map<Category, Integer> scroll = new EnumMap<>(Category.class);
    private Category category = Category.BLOCKS;

    // Editing state.
    private boolean showGrid = true;
    private boolean decorForeground; // which layer decorations paint into
    private int lastPaintCol = Integer.MIN_VALUE, lastPaintRow = Integer.MIN_VALUE;
    /** The cell the held eraser last took a layer off, so it takes only one. */
    private int lastEraseCol = Integer.MIN_VALUE, lastEraseRow = Integer.MIN_VALUE;
    private int mouseX, mouseY; // sampled in update, used by the render preview
    private String status = "";
    private double statusTime;
    private double animClock; // drives skinned sprite animation

    // Undo/redo (Ctrl+Z / Ctrl+Y). One history step per action: a whole drag,
    // or a whole window session, comes back in a single keystroke.
    private final EditHistory history = new EditHistory();
    /** True while a canvas stroke's step is open — it closes on mouse-up. */
    private boolean strokeOpen;
    /** True while an open window's step is open — it closes with the window. */
    private boolean dialogEditOpen;
    /**
     * The cells the open step has already saved. A drag that comes back over a
     * cell, or an eraser that takes a stack apart a layer at a time, must undo
     * to the state before the stroke rather than to the one halfway through it,
     * so each cell is saved the first time the stroke reaches it and not again.
     */
    private final Set<Long> stepCells = new HashSet<>();
    /**
     * Which non-cell snapshots the open step already holds ({@code "doc"},
     * {@code "bounds"}…), for the same reason — and so that a stroke that
     * places one mob doesn't pay for the level's document twice.
     */
    private final Set<String> stepAspects = new HashSet<>();

    // Brush (block painting/erasing): shape + diameter in tiles, plus the
    // multi-block mix — extra block keys the stroke scatters alongside the
    // selected one (configured in the Brush Settings window).
    private Brush.Shape brushShape = Brush.Shape.SQUARE;
    private int brushSize = 1;
    private final Rectangle brushShapeBox = new Rectangle();
    private boolean brushMix;
    private String brushKey2 = "", brushKey3 = "", brushKey4 = "";

    // Surface decor paint options (the SURFACE category's toggle rows).
    private int surfaceFaceMode;    // 0 = auto, 1..4 = UP/DOWN/LEFT/RIGHT
    private SurfaceDecor.Visibility surfaceVisibility = SurfaceDecor.Visibility.OPEN_FACE;
    private boolean surfaceForeground;

    // Level size sliders (index 0/1) + brush size slider (index 2).
    private int draggingSizeSlider = -1;
    private int pendingLevelW, pendingLevelH;
    private final Rectangle[] sliderTracks = {new Rectangle(), new Rectangle(), new Rectangle()};
    /** Unlocks the size sliders beyond 1024×1024, up to 65536×65536. */
    private boolean overrideMapSize;
    private final Rectangle overrideButtonBox = new Rectangle();

    // Custom-content creation ("+ New…" palette entries).
    private CustomContentStore customContent;
    /** This game type's playable character profiles ("+ New Character"). */
    private CharacterStore characterStore;
    // "+ New Character" fields: skin colours plus the traits a profile sets.
    private double cCharSpeed = 1.0, cCharJump = 1.0;
    private boolean cCharSprint = true;
    private int cCharAirJumps = 1;
    private int cCharHp = 100, cCharMana = 100, cCharStamina = 100;
    private int cCharUltIndex;
    private boolean cCharUltEnabled = true;
    private Category customCategory = Category.BLOCKS;
    private String cName = "";
    private int cR = 150, cG = 150, cB = 150;      // primary colour
    private int cR2 = 90, cG2 = 90, cB2 = 90;      // accent colour
    private boolean cSolid = true, cFlying, cFalling;
    /**
     * Which plan-view faces a new block comes with — the question the block
     * form always asks. Both on by default, because a block a creator means to
     * build with in a top-down or isometric level shows both.
     */
    private boolean cTopTexture = true, cSideTexture = true;
    /**
     * What "+ New …" just made — its palette kind and key — so the status line
     * can name the sheets a new block wants, and "Create &amp; draw its
     * texture" knows which texture key to open the paint window on.
     */
    private String createdKind = "", createdKey = "";
    private int cLightRadius, cLightR = 255, cLightG = 220, cLightB = 160;
    private int cDamage;
    private double cHardness = 1.0, cSizeTiles = 2.0;
    private int cToolIndex;                        // none/pickaxe/axe/shovel
    private int cSize = 28, cSpeed = 60, cHp = 20, cMobDamage = 5;
    private int cTemperIndex, cDetect = 220, cAttack = 34;
    private int cCategoryIndex, cRarityIndex, cMaxStack = 64, cHeal;
    private int cShapeIndex;
    private double cToolPower = 2;
    // "+ New Block Decor" (custom surface details): style, faces, layer.
    private int cSurfStyleIndex;
    private boolean cFaceUp = true, cFaceDown, cFaceLeft, cFaceRight;
    private boolean cSurfForeground;

    // Cutscene editor state (the CUTSCENES palette's dialog suite).
    private int csEditIndex;      // 0 = new cutscene, 1.. = existing
    private String csName = "";
    private int csTriggerIndex;
    private int csRadius = 2;
    private boolean csOnce = true;
    private int csActorEditIndex; // 0 = new actor, 1.. = existing
    private String csActorName = "";
    private int csActorSize = 48;
    private int csStateEditIndex; // 0 = new state, 1.. = existing
    private String csStateName = "";
    private String csSheet = "", csFrameW = "32", csFrameH = "32", csFrames = "1", csFps = "8";
    private boolean csLoop = true;
    private int csStepEditIndex;  // 0 = append a new step, 1.. = existing
    private int csOpIndex;
    private int csStepActorIndex;
    private String csText = "";
    private int csStepX, csStepY; // tile coordinates in the editor fields
    private String csSeconds = "1.0";

    // Mini Game Setup state (the Stockpile resource key fields).
    private String mgRes1 = "", mgRes2 = "", mgRes3 = "";

    // Stat-rule editor state.
    private int ruleEditIndex; // 0 = new rule, 1.. = existing
    private int ruleStatIndex;
    private int ruleThreshold = 10;
    private String ruleReward = "", ruleConsume = "";
    private int ruleRewardCount = 1, ruleConsumeCount = 1;
    private boolean ruleRepeat, ruleShowBar = true;

    // Dialogs.
    private Dialog dialog = Dialog.NONE;
    private ConfigForm dialogForm;
    /** True while a form re-opens itself to change its own layout. */
    private boolean dialogRebuild;
    private String pendingName = "";
    private int pendingWidth = 60, pendingHeight = 24;
    /** Format picked in the New Level / Generate dialogs (the mode they build for). */
    private LevelFormat pendingFormat = LevelFormat.SIDE_SCROLLER;
    private int genWidth = 240, genHeight = 140, genSeed = 1;
    /** Generate dialog mode: Perlin terrain, or the top-down/iso maze. */
    private boolean genMaze;
    private int doorEditIndex; // 0 = new door, 1.. = existing doors
    private String doorLabel = "";
    private int doorTargetIndex, doorColorIndex;
    // Texture-override dialog (right-clicked palette entry).
    private Entry texEntry;
    private List<String> texStates = List.of("default");
    private int texStateIndex;
    private String texSheet = "", texW = "32", texH = "32", texCount = "1", texFps = "0";
    /**
     * The facing the texture dialog is assigning, for objects drawn per
     * direction (the player, character profiles, mobs). Index 0 is
     * {@link #ALL_DIRECTIONS} — one sheet used whichever way they face, which
     * is what most creators want; the rest name a single compass point.
     */
    private int texDirIndex;
    /** Per-object texture pack switch; on by default (built-in art falls back). */
    private boolean texUsePack = true;

    // --- "Create texture": the sprite-sheet editor window ----------------------------

    /** The paint window, open over everything else, or {@code null}. */
    private SpriteEditorPanel spriteEditor;
    /** The texture key the open window is drawing, and what to call it. */
    private String spriteKey = "", spriteLabel = "";

    // --- sound editor state -----------------------------------------------------
    /** Which family of sounds the list shows ("" = every sound in the game). */
    private String soundCategory = "Player";
    /** 0 = every sound, 1 = only the silent ones, 2 = only the supplied ones. */
    private int soundFilter;
    /** The rows the list is showing, rebuilt when the filter changes. */
    private List<SoundKeys.Entry> soundRows = new ArrayList<>();
    /** The sound key the per-sound dialog is editing. */
    private String soundKey = "";
    private String soundLabel = "";
    private String sndFile = "", sndVolume = "1.0", sndPitch = "1.0";
    private boolean sndUsePack = true, sndLoop, sndVary = true, sndBuiltin = true;
    /** The level-music dialog's track name. */
    private String musicTrack = "";

    // Play-test state (offline only).
    private boolean testing;
    private World testWorld;
    private PlayerState testMe;
    private Level editLevel;    // the level being edited, kept across door travel
    private Object savedTiles;  // terrain snapshot restored when the test ends
    private Map<Long, List<ItemStack>> savedContainers; // chest contents snapshot
    private ContainerPanel containerPanel; // open chest/barrel during play-test
    private int inputSeq;
    private Inventory testInv;
    private boolean showInventory;
    private int cursorSlot = -1;
    private double swingTime;
    /**
     * The play-test's melee moves, on the same {@link MeleeState} machine the
     * play scene and the authoritative server run — so a weapon's swing, its
     * parry window and its lunge feel in a play-test exactly like they will
     * when the level is played.
     */
    private final MeleeState testMelee = new MeleeState();
    /** The item key the play-test's melee machine is running on. */
    private String testMeleeItem = "";
    /** Where the running move was aimed when it started, in world px. */
    private double testMeleeAimX, testMeleeAimY;
    /** Guard hits already reported, so a caught blow rings exactly once. */
    private int testGuardHits;
    private double prevHealth = PlayerState.MAX_HEALTH;
    private final Particles particles = new Particles();
    /**
     * The play-test's frame-to-frame sounds — footsteps, the swim loop, a
     * sustained ultimate, shots still in the air, the level's music — so
     * testing a level sounds exactly like playing it.
     */
    private final SceneSounds testSounds = new SceneSounds();
    /** Time until the next mining scrape in play-test (see the play scene). */
    private double mineSoundTimer;
    // Play-test stat tracking + programmable rules + crafting.
    private PlayerStats testStats;
    private StatRuleEngine ruleEngine;
    private CutsceneDirector cutsceneDirector; // runs the level's cutscenes
    private CraftingPanel craftingPanel; // non-null while a station UI is open
    private double prevTestVy;
    // The exact same directional sprite the play scene draws, so the play-test
    // character is identical to the one "load level" play loads. The action
    // state (idle/walk/run/jump/fall/swim) picks which skin animation plays
    // and the facing picks its direction; the clock resets on state changes.
    private String testAnimState = "idle";
    private double testAnimClock;
    private double prevTestVz;
    /** The character profile the play-test is played as. */
    private CharacterProfile testCharacter = CharacterProfile.defaultProfile();
    /** The roster picker shown when a play-test starts, or {@code null}. */
    private CharacterPicker testPicker;

    public CreativeScene(GameContext ctx) {
        this.ctx = ctx;
    }

    private GameProfile profile() { return ctx.profile(); }

    /** The level being edited, exposed so tests can assert what an edit did. */
    public Level editing() { return level; }

    /** This session's undo history, exposed so tests can walk it. */
    public EditHistory history() { return history; }

    /**
     * The level format this creative session is building in — the editor
     * <em>is</em> that format's creative mode: its palette, its starter
     * canvas, its generator default, and the camera projection all follow it.
     */
    private LevelFormat format() {
        return level != null ? level.format() : LevelFormat.of(profile().perspective);
    }

    @Override
    public void onEnter() {
        net = ctx.session();
        testing = false;
        dialog = Dialog.NONE;
        spriteEditor = null;
        // A new editing session starts with nothing to take back: the levels the
        // history could hand back belong to the session that had them open.
        history.clear();
        strokeOpen = false;
        dialogEditOpen = false;
        stepCells.clear();
        stepAspects.clear();
        doors = new DoorDirectory(profile().name);
        // Custom objects created with the "+" palette entries must be
        // registered before any level referencing them loads.
        customContent = new CustomContentStore(profile().name);
        customContent.loadAndRegister();
        // Character profiles register the same way, so a level's roster can
        // name them and the Characters palette can list them.
        characterStore = new CharacterStore(profile().name);
        characterStore.loadAndRegister();

        if (net != null && net.client().level() != null) {
            level = net.client().level(); // paint straight into the shared world
        } else {
            net = null;
            // The main menu opens creative mode for one format; that choice is
            // consumed here so re-entering (from the pause menu, say) returns
            // to the level being edited instead of restarting the format.
            level = loadInitialLevel(ctx.takeCreativeFormat());
            // Edit (and play-test) with the level's own saved feature toggles.
            ctx.applyLevelSettings(level.settings);
        }
        // After the level: the palette is format-specific (see buildPalette)
        // and the CUTSCENES category lists this level's cutscenes.
        buildPalette();
        pendingLevelW = level.width;
        pendingLevelH = level.height;
        pendingFormat = level.format();

        // Each level carries its own format, so the editor becomes a
        // side-scroll / top-down / isometric creative mode to match — the
        // shared objects paint the same, but obstruct the player per format.
        camera = new Camera(level.perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = 1.0;
        camera.centerOn(level.spawnX, level.spawnY);
        // Creative mode has music of its own, so building a level doesn't
        // have to happen in silence — drop music/creative.mp3 in the pack.
        ctx.music(SoundKeys.music("creative"));
        setStatus(net == null
                ? format().displayName() + " Creative Mode — " + format().description()
                : format().displayName() + " Creative Mode (online) — painting edits"
                + " the server's world for everyone");
    }

    /**
     * The level this creative session opens with. Entering creative mode for a
     * particular format (the main menu's per-format entries) continues the
     * last level when it was built in that format and starts a fresh canvas
     * otherwise, so each format's creative mode picks up where <em>it</em> left
     * off instead of dropping the creator into another format's level.
     */
    private Level loadInitialLevel(LevelFormat requested) {
        Level last = loadLastLevel();
        if (last != null && (requested == null || last.format() == requested)) return last;
        LevelFormat format = requested != null ? requested
                : LevelFormat.of(profile().perspective);
        return starterLevel("New " + format.displayName() + " Level", 60, 24, format);
    }

    /** The game type's last saved level, or {@code null} when there isn't one. */
    private Level loadLastLevel() {
        String last = profile().lastLevelPath;
        if (last == null || last.isEmpty() || !Files.exists(Path.of(last))) return null;
        try {
            return LevelLoader.load(last);
        } catch (RuntimeException e) {
            System.err.println("CreativeScene: failed to load " + last + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * A fresh canvas in an explicit format: the side-scroller gets a ground
     * floor to stand on, the plan-view formats a wall border (see
     * {@link LevelFormat#starterLevel}).
     */
    private Level starterLevel(String name, int widthTiles, int heightTiles,
                               LevelFormat format) {
        return format.starterLevel(name, widthTiles, heightTiles, profile().tileSize);
    }

    /**
     * Fill the palette for this creative mode. Everything the engine can paint
     * — blocks, mobs, items, decorations, block details, lights, liquids,
     * doors, cutscenes, mini-game markers, tools — is offered in all three
     * formats and works in all three. Blocks used to be filtered by format,
     * back when a handful of wall and path families carried the plan views'
     * geometry on their own; the plan views say that with the
     * {@linkplain Level#walkable stack} now, so every block builds floor at one
     * layer and a wall at two, and the palette has nothing to hide.
     */
    private void buildPalette() {
        palette.clear();
        // Every creatable category leads with its "+" entry: click it to add a
        // fully customizable object of that kind to the game engine.
        List<Entry> blocks = newList("+ New Block");
        List<Entry> liquids = newList("+ New Liquid");
        List<Entry> lights = newList("+ New Light");
        for (Block b : com.larsons.engine.world.BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // the sim's hidden flow twins
            Entry e = new Entry("block", b.key(), b.displayName(),
                    swatch("block/" + b.key(), EntitySprites.block(b, 40)),
                    customContent.isCustom("block", b.key()));
            if (b.liquid()) {
                liquids.add(e);
            } else if (b.emitsLight()) {
                lights.add(e);
            } else {
                blocks.add(e);
            }
        }
        palette.put(Category.BLOCKS, blocks);
        palette.put(Category.LIQUIDS, liquids);
        palette.put(Category.LIGHTS, lights);

        List<Entry> mobs = newList("+ New Mob");
        for (MobDef d : MobRegistry.standard().all()) {
            mobs.add(new Entry("mob", d.key(), d.displayName(),
                    swatch("mob/" + d.key() + "/idle", EntitySprites.mob(d, 40)),
                    customContent.isCustom("mob", d.key())));
        }
        palette.put(Category.MOBS, mobs);

        List<Entry> items = newList("+ New Item");
        for (ItemDef d : ItemRegistry.standard().allByRarity()) {
            items.add(new Entry("item", d.key(), d.name(),
                    swatch("item/" + d.key(), EntitySprites.item(d, 40)),
                    customContent.isCustom("item", d.key())));
        }
        palette.put(Category.ITEMS, items);

        List<Entry> decor = newList("+ New Decoration");
        for (Decor d : DecorRegistry.standard().all()) {
            decor.add(new Entry("decor", d.key(), d.name(),
                    swatch("decor/" + d.key(), EntitySprites.decor(d, 40)),
                    customContent.isCustom("decor", d.key())));
        }
        palette.put(Category.DECOR, decor);

        List<Entry> surface = newList("+ New Block Decor");
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            surface.add(new Entry("surface", d.key(), d.name(),
                    swatch("surface/" + d.key(), surfaceIcon(d)),
                    customContent.isCustom("surface", d.key())));
        }
        palette.put(Category.SURFACE, surface);

        // Characters: the playable profiles this game type offers, created the
        // same way a block or mob is. The roster entry decides which of them
        // this level lets a player pick from when it starts.
        List<Entry> characterEntries = newList("+ New Character");
        for (CharacterProfile c : Characters.all()) {
            characterEntries.add(new Entry("character", c.key, c.name,
                    CharacterPicker.icon(c, SWATCH),
                    characterStore.isCustom(c.key)));
        }
        characterEntries.add(new Entry("roster", "roster", "Level Roster…", rosterIcon()));
        palette.put(Category.CHARACTERS, characterEntries);

        // Effects: the particle styles and projectiles the game throws. They
        // aren't painted — clicking one opens its texture dialog, so every
        // effect texture is editable here and falls back to built-in art.
        List<Entry> effectEntries = new ArrayList<>();
        for (Particles.Style style : Particles.Style.values()) {
            effectEntries.add(new Entry("particle", Particles.textureKind(style),
                    Particles.styleName(style),
                    swatch(Particles.textureKey(style),
                            EntitySprites.particle(style, SWATCH, particleSwatchColor(style)))));
        }
        for (ProjectileDef d : ProjectileRegistry.standard().all()) {
            effectEntries.add(new Entry("projectile", d.key(), d.name(),
                    swatch("projectile/" + d.key(), EntitySprites.projectile(d, SWATCH))));
        }
        palette.put(Category.EFFECTS, effectEntries);

        // Sounds: every place the game makes a noise, grouped the way the
        // sound editor lists them. Nothing here is painted — clicking an
        // entry opens the list of that family's action states, and each of
        // those opens the sound the creator can override.
        List<Entry> soundEntries = new ArrayList<>();
        soundEntries.add(new Entry("soundeditor", "", "Sound Editor…", soundEditorIcon()));
        soundEntries.add(new Entry("soundoptions", "soundoptions", "Sound Options…",
                soundOptionsIcon()));
        soundEntries.add(new Entry("levelmusic", "levelmusic", "Level Music…",
                levelMusicIcon()));
        for (String c : SoundKeys.categories()) {
            soundEntries.add(new Entry("soundgroup", c, c + " Sounds…", soundGroupIcon(c)));
        }
        palette.put(Category.SOUNDS, soundEntries);

        List<Entry> doorEntries = newList("+ New Door");
        for (DoorLink link : doors.all()) {
            doorEntries.add(new Entry("door", link.key(), link.label(), doorIcon(link.color())));
        }
        doorEntries.add(new Entry("managedoors", "managedoors", "Manage Doors…", manageDoorsIcon()));
        palette.put(Category.DOORS, doorEntries);

        // Cutscenes live in the level: paint one to place its trigger marker.
        List<Entry> cutsceneEntries = newList("+ New Cutscene");
        if (level != null) {
            for (Cutscene cs : level.cutscenes) {
                cutsceneEntries.add(new Entry("cutscene", cs.key, cs.name, cutsceneIcon()));
            }
        }
        cutsceneEntries.add(new Entry("managecutscenes", "managecutscenes",
                "Manage Cutscenes…", manageDoorsIcon()));
        palette.put(Category.CUTSCENES, cutsceneEntries);

        // The MINIGAME palette: the setup window plus the objective markers
        // each mode is built from (flag bases, stockpiles, team spawns, the
        // escort waypoint path).
        List<Entry> minigameEntries = new ArrayList<>();
        minigameEntries.add(new Entry("mg_settings", "mg_settings",
                "Mini Game Setup…", minigameSettingsIcon()));
        for (int t = 0; t < 2; t++) {
            minigameEntries.add(new Entry(MiniGame.KIND_FLAG, Team.markerType(t),
                    Team.name(t) + " Flag Base", flagIcon(Team.color(t))));
        }
        for (int t = 0; t < Team.MAX; t++) {
            minigameEntries.add(new Entry(MiniGame.KIND_STOCKPILE, Team.markerType(t),
                    Team.name(t) + " Stockpile", stockpileIcon(Team.color(t))));
        }
        for (int t = 0; t < Team.MAX; t++) {
            minigameEntries.add(new Entry(MiniGame.KIND_SPAWN, Team.markerType(t),
                    Team.name(t) + " Team Spawn", teamSpawnIcon(Team.color(t))));
        }
        minigameEntries.add(new Entry(MiniGame.KIND_PATH, "auto",
                "Escort Waypoint", waypointIcon()));
        palette.put(Category.MINIGAME, minigameEntries);

        List<Entry> tools = new ArrayList<>();
        tools.add(new Entry("spawn", "spawn", "Player Spawn", spawnIcon()));
        tools.add(new Entry("mp_spawn", "mp_spawn", "Multiplayer Spawn", mpSpawnIcon()));
        tools.add(new Entry("playerskin", "playerskin", "Player Skin…", playerSkinIcon()));
        tools.add(new Entry("eraser", "eraser", "Eraser", eraserIcon()));
        tools.add(new Entry("brush", "brush", "Brush Settings…", brushIcon()));
        tools.add(new Entry("generate", "generate", "Generate Level…", generateIcon()));
        tools.add(new Entry("sunlight", "sunlight", "Light Direction…", sunlightIcon()));
        tools.add(new Entry("rules", "rules", "Stat Rules…", rulesIcon()));
        tools.add(new Entry("soundeditor", "", "Sound Editor…", soundEditorIcon()));
        palette.put(Category.TOOLS, tools);

        for (Category c : Category.values()) {
            palette.putIfAbsent(c, new ArrayList<>());
            selected.putIfAbsent(c, 0);
            scroll.putIfAbsent(c, 0);
            // Switching format can shorten a category (the plan-view block
            // families leave the list), so pull the selection back inside it.
            int size = palette.get(c).size();
            selected.computeIfPresent(c, (k, i) -> Math.max(0, Math.min(i, size - 1)));
        }
        clampScroll();
    }

    /**
     * The tint a particle style's palette swatch is drawn in — the colour the
     * engine most often throws that style in, so the Effects palette reads at
     * a glance rather than showing nine grey flecks.
     */
    private static Color particleSwatchColor(Particles.Style style) {
        return switch (style) {
            case EMBERS -> new Color(255, 150, 60);
            case SHARDS -> new Color(150, 210, 255);
            case SPARKS -> new Color(255, 245, 150);
            case DRIP -> new Color(150, 210, 80);
            case RING -> new Color(140, 220, 255);
            case MOTES -> new Color(200, 170, 255);
            case FOUNTAIN -> new Color(255, 190, 80);
            case IMPLODE -> new Color(170, 140, 255);
            default -> new Color(190, 170, 140);
        };
    }

    /** A fresh palette list starting with the "+" creator entry. */
    private static List<Entry> newList(String label) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("new", "new", label, plusIcon()));
        return list;
    }

    /** Palette swatch side length, in pixels. */
    private static final int SWATCH = 40;

    /**
     * The swatch a palette entry shows: the texture actually assigned to its
     * key — from the drop-in texture pack or a sheet the creator picked — and
     * only otherwise the built-in procedural art. So the sidebar previews
     * what will land on the canvas, and a reskin is visible the moment it is
     * applied (the palette is rebuilt after every texture change).
     */
    private static BufferedImage swatch(String textureKey, BufferedImage fallback) {
        return Skins.icon(textureKey, fallback, SWATCH);
    }

    @Override
    public void onResize(int w, int h) {
        super.onResize(w, h);
        if (camera != null) camera.setViewport(w, h);
    }

    @Override
    public void update(double dt, InputManager input) {
        if (statusTime > 0) statusTime -= dt;
        animClock += dt;
        mouseX = input.getMouseX();
        mouseY = input.getMouseY();
        // Effects are authored in the space the editor is drawing in, so a
        // top-down map's shards spray across its floor while a side-scroller's
        // rain down the screen — in the editor exactly as in play.
        particles.setSpace(PerspectiveSpace.of(camera.getPerspective()));

        // The paint window is the top layer: while it is open every click and
        // keystroke is a brush stroke or a button in it, so a stroke that runs
        // off its canvas can never paint the level behind it.
        if (spriteEditor != null) {
            spriteEditor.update(dt, input, viewportWidth, viewportHeight);
            return;
        }
        if (testing) {
            updateTest(dt, input);
            return;
        }
        if (dialog != Dialog.NONE) {
            // A window that opened out of a stroke swallows the button coming
            // up, so the stroke is closed here rather than waiting for a
            // mouse-up the editor will never see.
            endStroke();
            updateDialog(dt, input);
            return;
        }

        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (net != null) {
                scenes.transitionTo("play"); // back to the running session
            } else {
                openDialog(Dialog.CONFIRM_EXIT);
            }
            return;
        }

        // --- undo / redo ---
        // Before anything else: whatever the last action was, this takes it
        // back, and it must not be read as a fresh edit on the way through.
        boolean ctrl = input.isKeyDown(KeyEvent.VK_CONTROL);
        if (ctrl && input.isKeyJustPressed(KeyEvent.VK_Z)) {
            if (input.isKeyDown(KeyEvent.VK_SHIFT)) redoEdit();
            else undoEdit();
            return;
        }
        if (ctrl && input.isKeyJustPressed(KeyEvent.VK_Y)) {
            redoEdit();
            return;
        }

        // --- pan & zoom ---
        double pan = PAN_SPEED * dt / camera.zoom;
        if (input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP)) camera.y -= pan;
        if ((input.isKeyDown(KeyEvent.VK_S) && !input.isKeyDown(KeyEvent.VK_CONTROL))
                || input.isKeyDown(KeyEvent.VK_DOWN)) camera.y += pan;
        if (input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT)) camera.x -= pan;
        if (input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT)) camera.x += pan;

        boolean overSidebar = input.getMouseX() < SIDEBAR_W;
        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            if (overSidebar) {
                scroll.merge(category, wheel, Integer::sum);
                clampScroll();
            } else {
                double factor = wheel < 0 ? 1.15 : 1 / 1.15;
                camera.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, camera.zoom * factor));
                setStatus("Zoom " + Math.round(camera.zoom * 100) + "%");
            }
        }

        // --- shortcuts ---
        if (input.isKeyJustPressed(KeyEvent.VK_TAB)) {
            category = Category.values()[(category.ordinal() + 1) % Category.values().length];
            setStatus("Palette: " + categoryName(category));
        }
        if (input.isKeyJustPressed(KeyEvent.VK_G)) {
            showGrid = !showGrid;
            setStatus("Grid " + (showGrid ? "on" : "off"));
        }
        if (input.isKeyJustPressed(KeyEvent.VK_B)) {
            decorForeground = !decorForeground;
            setStatus("Decorations paint into the "
                    + (decorForeground ? "FOREGROUND" : "BACKGROUND"));
        }
        if (input.isKeyJustPressed(KeyEvent.VK_OPEN_BRACKET)) {
            brushSize = Math.max(Brush.MIN_SIZE, brushSize - 1);
            setStatus("Brush: " + Brush.label(brushShape) + " " + brushSize);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_CLOSE_BRACKET)) {
            brushSize = Math.min(Brush.MAX_SIZE, brushSize + 1);
            setStatus("Brush: " + Brush.label(brushShape) + " " + brushSize);
        }
        if (net == null) {
            if (input.isKeyJustPressed(KeyEvent.VK_P)) {
                enterTest();
                return;
            }
            if (input.isKeyDown(KeyEvent.VK_CONTROL) && input.isKeyJustPressed(KeyEvent.VK_S)) {
                openDialog(Dialog.SAVE);
                return;
            }
            if (input.isKeyJustPressed(KeyEvent.VK_L)) {
                openDialog(Dialog.LOAD);
                return;
            }
            if (input.isKeyJustPressed(KeyEvent.VK_N)) {
                openDialog(Dialog.NEW_LEVEL);
                return;
            }
        }

        // --- level size sliders (sidebar bottom panel; local worlds only) ---
        if (net == null && updateSizeSliders(input)) {
            return; // a drag in progress owns the mouse
        }

        // --- mouse editing ---
        if (overSidebar) {
            endStroke(); // the pointer left the canvas: the stroke is over
            if (input.isMouseJustPressed()) {
                handlePaletteClick(input.getMouseX(), input.getMouseY());
            }
            if (input.isRightMouseJustPressed()) {
                handlePaletteRightClick(input.getMouseX(), input.getMouseY());
            }
            lastPaintCol = lastPaintRow = Integer.MIN_VALUE;
            lastEraseCol = lastEraseRow = Integer.MIN_VALUE;
            return;
        }

        double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
        double ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);

        if (input.isMiddleMouseJustPressed()) {
            pickBlock(col, row);
        }
        // One stroke, one undo step: the step opens on the press and closes when
        // the button comes up, so a drag across half the level comes back in a
        // single Ctrl+Z instead of a cell at a time.
        if (input.isMouseDown()) {
            Entry entry = selectedEntry();
            boolean paintable = entry != null
                    && (entry.kind.equals("block") || entry.kind.equals("eraser"));
            boolean newCell = col != lastPaintCol || row != lastPaintRow;
            if (input.isMouseJustPressed() || (paintable && newCell)) {
                // Opened here rather than on the press, because a drag that
                // began over the sidebar arrives on the canvas with the press
                // already spent — and it still paints, so it is still a stroke.
                beginStroke(entry);
                paintAt(entry, aim[0], aim[1], col, row, input.isMouseJustPressed());
                lastPaintCol = col;
                lastPaintRow = row;
            }
        } else {
            lastPaintCol = lastPaintRow = Integer.MIN_VALUE;
        }
        // Erasing takes one layer off the top per cell, so a held button must
        // not fire again on the same cell the next frame: a stack would come
        // apart in a single click, and the floor would go with the wall.
        if (input.isRightMouseDown()) {
            if (input.isRightMouseJustPressed()
                    || col != lastEraseCol || row != lastEraseRow) {
                beginStroke("erase");
                eraseAt(aim[0], aim[1], col, row);
                lastEraseCol = col;
                lastEraseRow = row;
            }
        } else {
            lastEraseCol = lastEraseRow = Integer.MIN_VALUE;
        }
        if (!input.isMouseDown() && !input.isRightMouseDown()) {
            endStroke(); // both buttons up: the stroke is one finished action
        }

        particles.update(dt);
    }

    // --- level size sliders, override button & brush controls -----------------------

    /** Slider cap: 1024 normally; 65536 with "override map size" on. */
    private int maxLevelSize() {
        return overrideMapSize ? Level.MAX_GIANT_SIZE : STANDARD_MAX_SIZE;
    }

    /**
     * Slider position → tile count. With the override on the range spans
     * 16..65536, so the mapping turns exponential to keep small sizes usable.
     */
    private int sizeSliderValue(double t, int min, int max) {
        if (overrideMapSize) {
            return (int) Math.round(min * Math.pow(max / (double) min, t));
        }
        return min + (int) Math.round(t * (max - min));
    }

    private double sizeSliderT(int value, int min, int max) {
        value = Math.max(min, Math.min(max, value));
        if (overrideMapSize) {
            return Math.log(value / (double) min) / Math.log(max / (double) min);
        }
        return (value - min) / (double) (max - min);
    }

    /**
     * Drag handling for the sidebar's width/height/brush sliders and the
     * "override map size" button. Returns true while a drag owns the mouse.
     * Level resizes apply on release; the brush slider applies live.
     */
    private boolean updateSizeSliders(InputManager input) {
        if (draggingSizeSlider >= 0) {
            if (!input.isMouseDown()) {
                if (draggingSizeSlider < 2) {
                    int w = draggingSizeSlider == 0 ? pendingLevelW : level.width;
                    int h = draggingSizeSlider == 1 ? pendingLevelH : level.height;
                    if (w != level.width || h != level.height) {
                        // A resize re-cuts both tile layers and drops whatever
                        // fell outside, so its undo saves the level whole.
                        beginEdit("resize to " + w + "x" + h);
                        recordBounds();
                        level.resize(w, h);
                        commitEdit();
                        setStatus("Level resized to " + level.width + "x" + level.height
                                + (level.isChunked() ? " (chunked storage)" : "")
                                + " · [Ctrl+Z] undo");
                    }
                    pendingLevelW = level.width;
                    pendingLevelH = level.height;
                }
                draggingSizeSlider = -1;
            } else {
                dragSizeSlider(input.getMouseX());
            }
            return true;
        }
        if (!input.isMouseJustPressed()) return false;
        if (overrideButtonBox.width > 0 && overrideButtonBox.contains(mouseX, mouseY)) {
            overrideMapSize = !overrideMapSize;
            if (!overrideMapSize) {
                pendingLevelW = Math.min(pendingLevelW, STANDARD_MAX_SIZE);
                pendingLevelH = Math.min(pendingLevelH, STANDARD_MAX_SIZE);
            }
            ctx.sfx(Sfx.CLICK);
            setStatus(overrideMapSize
                    ? "Map size override ON — sliders now reach "
                    + Level.MAX_GIANT_SIZE + "x" + Level.MAX_GIANT_SIZE
                    : "Map size override off — sliders capped at "
                    + STANDARD_MAX_SIZE + "x" + STANDARD_MAX_SIZE);
            return true;
        }
        if (brushShapeBox.width > 0 && brushShapeBox.contains(mouseX, mouseY)) {
            brushShape = Brush.next(brushShape);
            ctx.sfx(Sfx.CLICK);
            setStatus("Brush: " + Brush.label(brushShape) + " " + brushSize);
            return true;
        }
        for (int i = 0; i < sliderTracks.length; i++) {
            Rectangle track = sliderTracks[i];
            Rectangle hit = new Rectangle(track.x - 4, track.y - 6,
                    track.width + 8, track.height + 12);
            if (track.width > 0 && hit.contains(mouseX, mouseY)) {
                draggingSizeSlider = i;
                pendingLevelW = level.width;
                pendingLevelH = level.height;
                dragSizeSlider(input.getMouseX());
                return true;
            }
        }
        return false;
    }

    private void dragSizeSlider(int mx) {
        Rectangle track = sliderTracks[draggingSizeSlider];
        if (track.width <= 0) return;
        double t = Math.max(0, Math.min(1, (mx - track.x) / (double) track.width));
        switch (draggingSizeSlider) {
            case 0 -> pendingLevelW = sizeSliderValue(t, MIN_LEVEL_W, maxLevelSize());
            case 1 -> pendingLevelH = sizeSliderValue(t, MIN_LEVEL_H, maxLevelSize());
            default -> brushSize = Brush.MIN_SIZE
                    + (int) Math.round(t * (Brush.MAX_SIZE - Brush.MIN_SIZE));
        }
    }

    // --- undo / redo -------------------------------------------------------------

    /**
     * Open a history step for an action that is about to change something, so
     * {@code Ctrl+Z} takes the whole action back under {@code label}.
     *
     * <p>Editing a server's world records nothing: those edits are requests,
     * and what comes back is the server's answer for every player in it — so
     * there is nothing here that is this editor's to take back.
     */
    private void beginEdit(String label) {
        if (net != null) return;
        history.begin(label);
    }

    /** Close a step opened by {@link #beginEdit} (see {@link EditHistory#commit}). */
    private void commitEdit() {
        history.commit();
        if (!history.recording()) {
            stepCells.clear();
            stepAspects.clear();
        }
    }

    /**
     * Open the step a canvas stroke records into. The step stays open for the
     * whole drag — every cell the brush reaches, every marker the drag places —
     * and closes when the button comes up, so one stroke costs one Ctrl+Z.
     */
    private void beginStroke(String label) {
        if (net != null || strokeOpen) return;
        strokeOpen = true;
        history.begin(label);
    }

    /** {@link #beginStroke} for a paint stroke, named after what it paints. */
    private void beginStroke(Entry entry) {
        if (net != null || strokeOpen) return;
        beginStroke(strokeLabel(entry));
    }

    /** Close the stroke's step: the button came up, or something took over. */
    private void endStroke() {
        if (!strokeOpen) return;
        strokeOpen = false;
        history.flush();
        stepCells.clear();
        stepAspects.clear();
    }

    /** What Ctrl+Z will call the stroke a palette entry is about to start. */
    private String strokeLabel(Entry entry) {
        if (entry == null) return "edit";
        return switch (entry.kind) {
            case "block" -> "paint " + entry.name;
            case "eraser" -> "erase";
            case "surface" -> "surface detail";
            case "spawn" -> "move the spawn";
            case "cutscene" -> "cutscene marker";
            default -> "place " + entry.name;
        };
    }

    /**
     * Save the cell at (col,row) into the open step, once per step — call it
     * <em>before</em> writing the cell. Both block layers and everything
     * attached to them come along ({@link Level#captureCell}), because clearing
     * a floor takes the stack, the surface details and the container with it.
     */
    private void recordCell(int col, int row) {
        if (!history.recording()) return;
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return;
        if (!stepCells.add(Level.cellKey(col, row))) return;
        Level target = level;
        history.add(EditHistory.field(() -> target.captureCell(col, row),
                target::restoreCell));
    }

    /**
     * Save the level's document state — markers, spawn, rules, cutscenes, the
     * mini game, the roster, the sun, the music — into the open step, once per
     * step. Windows take one of these as they open, which is what makes every
     * button in them undoable without each one describing its own inverse.
     */
    private void recordDoc() {
        if (!history.recording() || !stepAspects.add("doc")) return;
        Level target = level;
        history.add(EditHistory.field(target::snapshotDoc, doc -> {
            target.restoreDoc(doc);
            buildPalette(); // the CUTSCENES category lists the level's cutscenes
            clampScroll();
        }));
    }

    /** Save the level's size and contents before a resize re-cuts them. */
    private void recordBounds() {
        if (!history.recording() || !stepAspects.add("bounds")) return;
        Level target = level;
        history.add(EditHistory.field(target::snapshotBounds, bounds -> {
            target.restoreBounds(bounds);
            pendingLevelW = target.width;
            pendingLevelH = target.height;
        }));
    }

    /**
     * Save which level is being edited, before one replaces it. Undoing a
     * <em>New</em>, <em>Load</em> or <em>Generate</em> hands back the level that
     * was open — the object itself, untouched, so nothing about it had to be
     * copied to make it undoable.
     */
    private void recordLevelSwap() {
        if (!history.recording() || !stepAspects.add("level")) return;
        history.add(EditHistory.field(
                () -> new OpenLevel(level, profile().lastLevelPath),
                open -> {
                    level = open.level();
                    profile().lastLevelPath = open.path();
                    afterLevelSwap();
                }));
    }

    /** The level the editor has open, and the file the game type reopens. */
    private record OpenLevel(Level level, String path) {}

    /**
     * Save what a texture key is drawn with — the assignment in
     * {@code skins.json} and the frame layout the texture pack keeps for it —
     * before the Texture window reassigns either.
     */
    private void recordSkin(String key) {
        if (!history.recording() || !stepAspects.add("skin:" + key)) return;
        history.add(EditHistory.field(
                () -> new SkinState(Skins.get(key), TexturePack.hasOverride(key),
                        TexturePack.framesFor(key)),
                state -> {
                    if (state.skin() == null) Skins.remove(key);
                    else Skins.put(state.skin());
                    persistSkins();
                    if (state.packOverride()) {
                        TexturePack.Frames f = state.frames();
                        TexturePack.setOverride(key, f.width(), f.height(), f.count(), f.fps());
                    } else {
                        TexturePack.clearOverride(key);
                    }
                    Skins.clearCache();
                    buildPalette(); // the swatches show what actually draws
                }));
    }

    /** Everything that decides how one texture key draws. */
    private record SkinState(SkinDef skin, boolean packOverride, TexturePack.Frames frames) {}

    /**
     * Save what a sound key plays — its own definition and the pack's playback
     * exception for it — before the Sound window reassigns either.
     */
    private void recordSound(String key) {
        if (!history.recording() || !stepAspects.add("sound:" + key)) return;
        history.add(EditHistory.field(
                () -> new SoundState(Sounds.get(key), SoundPack.hasOverride(key),
                        SoundPack.playbackFor(key)),
                state -> {
                    if (state.sound() == null) Sounds.remove(key);
                    else Sounds.put(state.sound());
                    Sounds.save();
                    if (state.packOverride()) {
                        SoundPack.Playback p = state.playback();
                        SoundPack.setOverride(key, p.volume(), p.pitch(), p.loop(),
                                p.varyPitch());
                    } else {
                        SoundPack.clearOverride(key);
                    }
                    refreshSoundRows();
                }));
    }

    /** Everything that decides how one sound key plays. */
    private record SoundState(SoundDef sound, boolean packOverride,
                              SoundPack.Playback playback) {}

    /**
     * Save the game type's door list before the Doors window changes it. The
     * list is shared by every level of the game type and lives in its own
     * {@code doors.json}, so undo rewrites that file the same way the window
     * does.
     */
    private void recordDoors() {
        if (!history.recording() || !stepAspects.add("doors")) return;
        history.add(EditHistory.field(() -> List.copyOf(doors.all()), saved -> {
            for (DoorLink link : List.copyOf(doors.all())) doors.remove(link.key());
            for (DoorLink link : saved) doors.put(link);
            buildPalette(); // the Doors palette is built from the directory
        }));
    }

    /**
     * Take back the newest action. Terrain, markers, level settings, the
     * objects the palette is built from and the art and audio assigned to them
     * all come back the same way — whatever the action reached, its step holds.
     */
    private void undoEdit() {
        endStroke();
        if (net != null) {
            setStatus("Undo isn't available while editing a server's world"
                    + " — the server owns those edits");
            return;
        }
        if (!history.canUndo()) {
            setStatus("Nothing left to undo");
            return;
        }
        String label = history.undoLabel();
        history.undo();
        ctx.sound(SoundKeys.ui("undo"));
        setStatus("Undid " + label + " · [Ctrl+Y] redo"
                + (history.canUndo() ? " · " + history.undoDepth() + " more to undo" : ""));
    }

    /** Put back the action {@link #undoEdit} took away. */
    private void redoEdit() {
        endStroke();
        if (net != null) {
            setStatus("Redo isn't available while editing a server's world");
            return;
        }
        if (!history.canRedo()) {
            setStatus("Nothing left to redo");
            return;
        }
        String label = history.redoLabel();
        history.redo();
        ctx.sound(SoundKeys.ui("redo"));
        setStatus("Redid " + label
                + (history.canRedo() ? " · " + history.redoDepth() + " more to redo" : ""));
    }

    // --- painting ----------------------------------------------------------------

    private Entry selectedEntry() {
        List<Entry> entries = palette.get(category);
        int i = selected.get(category);
        return i >= 0 && i < entries.size() ? entries.get(i) : null;
    }

    private void paintAt(Entry entry, double wx, double wy, int col, int row, boolean firstClick) {
        if (entry == null) return;
        // Blocks and surface details are saved per cell (see writeLayer and
        // paintSurfaceDecor); everything else the palette paints is part of the
        // level's document — a marker, the spawn, a cutscene's trigger — so the
        // step saves that once, up front, whatever this arm turns out to do. An
        // arm that only opens a window changes nothing, and a snapshot that
        // comes back equal is dropped when the step closes.
        switch (entry.kind) {
            case "block", "eraser", "surface" -> { /* saved per cell */ }
            default -> recordDoc();
        }
        switch (entry.kind) {
            case "block" -> {
                Block b = level.blocks.get(entry.key);
                if (b == null) return;
                // With the brush mix on, each cell picks (stably, by cell
                // hash) among the selected block and the extra mix slots.
                List<Integer> mix = brushBlockIds(b);
                boolean painted = false;
                for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                    if (cell[0] < 0 || cell[1] < 0
                            || cell[0] >= level.width || cell[1] >= level.height) {
                        continue;
                    }
                    int paintId = mix.get(Math.floorMod(
                            cell[0] * 31 + cell[1] * 47, mix.size()));
                    if (paintCell(cell[0], cell[1], paintId)) painted = true;
                }
                if (painted && net == null) {
                    Sounds.playFirst(1.0, SoundKeys.block(b.key(), "place"),
                            SoundKeys.ui("paint"), SoundKeys.player("place"));
                    if (profile().particlesEnabled) {
                        particles.burst((col + 0.5) * level.tileSize,
                                (row + 0.5) * level.tileSize, b.color(), 4);
                    }
                }
            }
            case "surface" -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Surface details aren't available online yet");
                    return;
                }
                paintSurfaceDecor(entry, wx, wy, col, row);
            }
            case "new" -> {
                if (firstClick) openCustomCreator();
            }
            case "rules" -> {
                if (firstClick) {
                    if (net != null) setStatus("Stat rules are edited offline");
                    else openDialog(Dialog.RULES);
                }
            }
            case "sunlight" -> {
                if (firstClick) openDialog(Dialog.SUNLIGHT);
            }
            case "mob", "item" -> {
                if (!firstClick) return; // no drag-spraying creatures
                if (net != null) {
                    net.client().sendEntityPaint(entry.kind, entry.key, wx, wy);
                } else {
                    level.entities.add(new Level.EntitySpawn(entry.kind, entry.key, wx, wy));
                    ctx.sfx(Sfx.CLICK);
                }
                setStatus("Placed " + entry.name);
            }
            case "decor" -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Decorations aren't available online yet");
                    return;
                }
                String kind = decorForeground ? "decor_fg" : "decor_bg";
                level.entities.add(new Level.EntitySpawn(kind, entry.key, wx, wy));
                ctx.sfx(Sfx.CLICK);
                setStatus("Placed " + entry.name + " ("
                        + (decorForeground ? "foreground" : "background") + ")");
            }
            case "door" -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Doors aren't available online yet");
                    return;
                }
                level.entities.add(new Level.EntitySpawn("door", entry.key, wx, wy));
                ctx.sfx(Sfx.CLICK);
                DoorLink link = doors.get(entry.key);
                setStatus("Placed door \"" + entry.name + "\""
                        + (link != null && !link.targetLevel().isEmpty()
                        ? " → " + link.targetLevel() : " (no target level yet)"));
            }
            case "mp_spawn" -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Multiplayer spawns are painted before hosting, offline");
                    return;
                }
                level.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn", wx, wy));
                ctx.sfx(Sfx.CLICK);
                setStatus("Multiplayer spawn point #" + countKind("mp_spawn") + " placed");
            }
            case "mg_settings" -> {
                if (firstClick) {
                    if (net == null) openDialog(Dialog.MINIGAME);
                    else setStatus("The mini game is configured before hosting, offline");
                }
            }
            case "playerskin" -> {
                if (firstClick) openPlayerSkinDialog();
            }
            case MiniGame.KIND_FLAG, MiniGame.KIND_STOCKPILE -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Mini game markers are painted before hosting, offline");
                    return;
                }
                // One flag base / stockpile per team: painting again moves it.
                level.entities.removeIf(e ->
                        entry.kind.equals(e.kind) && entry.key.equals(e.type));
                level.entities.add(new Level.EntitySpawn(entry.kind, entry.key, wx, wy));
                ctx.sfx(Sfx.CLICK);
                setStatus(entry.name + " placed" + minigameModeHint(entry.kind));
            }
            case MiniGame.KIND_SPAWN -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Mini game markers are painted before hosting, offline");
                    return;
                }
                level.entities.add(new Level.EntitySpawn(entry.kind, entry.key, wx, wy));
                ctx.sfx(Sfx.CLICK);
                int team = Team.fromMarkerType(entry.key);
                setStatus(Team.name(team) + " spawn #"
                        + countMarkers(MiniGame.KIND_SPAWN, entry.key) + " placed");
            }
            case MiniGame.KIND_PATH -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Mini game markers are painted before hosting, offline");
                    return;
                }
                int next = countKind(MiniGame.KIND_PATH) + 1;
                level.entities.add(new Level.EntitySpawn(MiniGame.KIND_PATH,
                        Integer.toString(next), wx, wy));
                ctx.sfx(Sfx.CLICK);
                setStatus(next == 1
                        ? "Escort waypoint #1 placed — the payload starts here"
                        : "Escort waypoint #" + next + " placed"
                        + minigameModeHint(MiniGame.KIND_PATH));
            }
            case "cutscene" -> {
                if (!firstClick) return;
                if (net != null) {
                    setStatus("Cutscenes are edited offline");
                    return;
                }
                Cutscene cs = cutsceneByKey(entry.key);
                if (cs == null) return;
                if (cs.trigger == Cutscene.Trigger.LEVEL_START) {
                    setStatus("\"" + cs.name + "\" plays at level start — no marker to place");
                    return;
                }
                // One marker per cutscene: painting moves it, like the spawn.
                cs.x = wx;
                cs.y = wy;
                ctx.sfx(Sfx.CLICK);
                setStatus("\"" + cs.name + "\" trigger placed ("
                        + (cs.trigger == Cutscene.Trigger.ZONE ? "walk within" : "press E within")
                        + " " + (int) cs.radiusTiles + " tiles)");
            }
            case "managecutscenes" -> {
                if (firstClick) {
                    if (net == null) openDialog(Dialog.CUTSCENES);
                    else setStatus("Cutscenes are edited offline");
                }
            }
            case "spawn" -> {
                level.spawnX = wx;
                level.spawnY = wy;
                setStatus("Player spawn moved");
            }
            case "eraser" -> eraseAt(wx, wy, col, row);
            case "brush" -> {
                if (firstClick) openDialog(Dialog.BRUSH);
            }
            case "generate" -> {
                if (firstClick) {
                    if (net != null) {
                        setStatus("Generation is a local-world feature");
                    } else {
                        openDialog(Dialog.GENERATE);
                    }
                }
            }
            case "managedoors" -> {
                if (firstClick) openDialog(Dialog.DOORS);
            }
            default -> { /* unknown palette kind */ }
        }
    }

    private int countKind(String kind) {
        int n = 0;
        for (Level.EntitySpawn e : level.entities) {
            if (kind.equals(e.kind)) n++;
        }
        return n;
    }

    /** Painted markers of a kind AND type (e.g. the Red team's spawns). */
    private int countMarkers(String kind, String type) {
        int n = 0;
        for (Level.EntitySpawn e : level.entities) {
            if (kind.equals(e.kind) && type.equals(e.type)) n++;
        }
        return n;
    }

    /**
     * A gentle nudge when a painted mini-game marker doesn't match the level's
     * configured mode (flags without CTF, waypoints without Escort…).
     */
    private String minigameModeHint(String kind) {
        MiniGameConfig cfg = level.minigame;
        MiniGameConfig.Mode wants = switch (kind) {
            case MiniGame.KIND_FLAG -> MiniGameConfig.Mode.CTF;
            case MiniGame.KIND_STOCKPILE -> MiniGameConfig.Mode.STOCKPILE;
            case MiniGame.KIND_PATH -> MiniGameConfig.Mode.ESCORT;
            default -> null;
        };
        if (wants == null) return "";
        if (cfg == null || cfg.mode == MiniGameConfig.Mode.NONE) {
            return " — pick " + wants.displayName + " in Mini Game Setup to use it";
        }
        return cfg.mode == wants ? "" : " (note: this level plays " + cfg.mode.displayName + ")";
    }

    /** Keep escort waypoints numbered 1..N after one is erased. */
    private void renumberWaypoints() {
        List<Level.EntitySpawn> path = new ArrayList<>();
        for (Level.EntitySpawn e : level.entities) {
            if (MiniGame.KIND_PATH.equals(e.kind)) path.add(e);
        }
        path.sort((a, b) -> Integer.compare(
                MiniGame.pathIndex(a.type), MiniGame.pathIndex(b.type)));
        for (int i = 0; i < path.size(); i++) {
            Level.EntitySpawn old = path.get(i);
            String want = Integer.toString(i + 1);
            if (want.equals(old.type)) continue;
            int at = level.entities.indexOf(old);
            level.entities.set(at, new Level.EntitySpawn(
                    MiniGame.KIND_PATH, want, old.x, old.y));
        }
    }

    /**
     * The block ids a brush stroke scatters: the primary block, plus the
     * Brush Settings window's mix slots (valid keys only) when mixing is on.
     */
    private List<Integer> brushBlockIds(Block primary) {
        List<Integer> ids = new ArrayList<>(4);
        ids.add(primary.id());
        if (!brushMix) return ids;
        for (String key : new String[]{brushKey2, brushKey3, brushKey4}) {
            Block b = level.blocks.get(key == null ? "" : key.trim());
            if (b != null && !b.isFlow()) ids.add(b.id());
        }
        return ids;
    }

    /**
     * Attach a surface decoration to the clicked block. The face comes from
     * the SURFACE panel's toggle (or, in auto mode, from where inside the tile
     * the click landed), constrained to the faces the definition allows; the
     * open/closed visibility condition and bg/fg layer come from the other two
     * toggles.
     */
    /**
     * Where the brush drops a block at (col,row): on the floor when the cell is
     * bare, and on top of what is already there when it isn't. Blocks stack by
     * themselves, so building a wall in a plan view is painting the same cell
     * twice rather than arming a mode first — and painting a full cell replaces
     * the block on top, which is what repainting a wall should do.
     */
    private int paintLayer(int col, int row) {
        if (!level.layered() || level.tileAt(col, row) == 0) return Level.LAYER_GROUND;
        return Level.LAYER_UPPER;
    }

    /** Paint one cell with {@code id}, into whichever layer it lands in. */
    private boolean paintCell(int col, int row, int id) {
        return writeLayer(col, row, paintLayer(col, row), id);
    }

    /**
     * One layer of one cell, locally or through the server. Every block a
     * stroke writes passes through here, so this is where the cell's old state
     * joins the open undo step.
     */
    private boolean writeLayer(int col, int row, int layer, int id) {
        if (level.tileAt(col, row, layer) == id) return false;
        if (net != null) {
            net.client().sendBlockEdit(col, row, id, "paint", layer);
            return true;
        }
        recordCell(col, row);
        return level.setTile(col, row, layer, id);
    }

    private void paintSurfaceDecor(Entry entry, double wx, double wy, int col, int row) {
        SurfaceDecor def = SurfaceDecorRegistry.standard().get(entry.key);
        if (def == null) return;
        if (level.tileAt(col, row) <= 0) {
            setStatus("Surface details attach to a block — click one");
            return;
        }
        SurfaceDecor.Face face;
        if (surfaceFaceMode >= 1 && surfaceFaceMode <= 4) {
            face = SurfaceDecor.Face.values()[surfaceFaceMode - 1];
            if (!def.allows(face)) {
                setStatus(def.name() + " can't attach to the "
                        + face.name().toLowerCase() + " face");
                return;
            }
        } else {
            face = autoFace(def, wx, wy, col, row);
            if (face == null) return;
        }
        // One decoration per (cell, face): repaint replaces.
        SurfaceDecor.Face f = face;
        recordCell(col, row); // the cell's details are part of the cell
        level.surfaceDecor.removeIf(sd -> sd.col() == col && sd.row() == row && sd.face() == f);
        level.surfaceDecor.add(new SurfaceDecor.Placement(col, row, face, def.key(),
                surfaceForeground, surfaceVisibility));
        Sounds.playFirst(1.0, SoundKeys.surface(def.key(), "step"), SoundKeys.ui("paint"));
        setStatus(def.name() + " on " + face.name().toLowerCase() + " face ("
                + surfaceVisibility.name().toLowerCase().replace('_', ' ') + ", "
                + (surfaceForeground ? "foreground" : "background") + ")");
    }

    /** Auto face pick: the allowed face nearest to where the click landed. */
    private SurfaceDecor.Face autoFace(SurfaceDecor def, double wx, double wy, int col, int row) {
        double ts = level.tileSize;
        double fx = wx / ts - col, fy = wy / ts - row; // [0,1) inside the tile
        SurfaceDecor.Face best = null;
        double bestD = Double.MAX_VALUE;
        for (SurfaceDecor.Face f : SurfaceDecor.Face.values()) {
            if (!def.allows(f)) continue;
            double d = switch (f) {
                case UP -> fy;
                case DOWN -> 1 - fy;
                case LEFT -> fx;
                case RIGHT -> 1 - fx;
            };
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        return best;
    }

    private void eraseAt(double wx, double wy, int col, int row) {
        // Entities first (they sit on top of blocks), then surface details,
        // then the block cells under the brush.
        if (net == null) {
            Level.EntitySpawn near = nearestSpawn(wx, wy);
            if (near != null) {
                recordDoc(); // the marker list, and any waypoint renumbering
                level.entities.remove(near);
                if (MiniGame.KIND_PATH.equals(near.kind)) renumberWaypoints();
                ctx.sfx(Sfx.CLICK);
                setStatus("Erased " + near.type);
                return;
            }
            if (level.tileAt(col, row) > 0) {
                SurfaceDecor.Placement sd = surfaceDecorAt(col, row);
                if (sd != null) {
                    recordCell(col, row);
                    level.surfaceDecor.remove(sd);
                    ctx.sfx(Sfx.CLICK);
                    setStatus("Erased surface detail");
                    return;
                }
            }
            boolean broke = false;
            for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                if (eraseCell(cell[0], cell[1])) broke = true;
            }
            if (broke) ctx.sound(SoundKeys.ui("erase"));
        } else {
            EntityView near = nearestNetEntity(wx, wy);
            if (near != null) {
                net.client().sendEntityErase(near.id);
                return;
            }
            int layer = eraseLayer(col, row);
            if (level.tileAt(col, row, layer) != 0) {
                net.client().sendBlockEdit(col, row, 0, "paint", layer);
            }
        }
    }

    /**
     * The layer the eraser bites into: a stack comes apart from the top, so
     * the first stroke takes the wall down to a path and the second takes the
     * path away, leaving the hole.
     */
    private int eraseLayer(int col, int row) {
        return level.upperAt(col, row) > 0 ? Level.LAYER_UPPER : Level.LAYER_GROUND;
    }

    /** Erase the topmost block of one cell. */
    private boolean eraseCell(int col, int row) {
        int layer = eraseLayer(col, row);
        if (level.tileAt(col, row, layer) == 0) return false;
        recordCell(col, row);
        return level.setTile(col, row, layer, 0);
    }

    private void pickBlock(int col, int row) {
        // Pick what is on top, and pick up how it was built with it: clicking a
        // wall arms a stacking brush, clicking a path arms a floor one.
        Block b = level.topBlockAt(col, row);
        if (b == null) return;
        if (b.isFlow()) b = level.blocks.sourceFor(b);
        if (b == null) return;
        Category target = b.liquid() ? Category.LIQUIDS
                : b.emitsLight() ? Category.LIGHTS : Category.BLOCKS;
        List<Entry> entries = palette.get(target);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key.equals(b.key())) {
                category = target;
                selected.put(target, i);
                setStatus("Picked " + b.displayName());
                return;
            }
        }
    }

    /** Erasable painted markers: mobs, items, decorations, doors, mp spawns. */
    private Level.EntitySpawn nearestSpawn(double wx, double wy) {
        Level.EntitySpawn best = null;
        double bestD = ERASE_RADIUS;
        for (Level.EntitySpawn e : level.entities) {
            if (!isErasableKind(e.kind)) continue;
            double d = Math.hypot(e.x - wx, e.y - wy);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean isErasableKind(String kind) {
        return switch (kind) {
            case "mob", "item", "door", "decor_bg", "decor_fg", "mp_spawn",
                 MiniGame.KIND_FLAG, MiniGame.KIND_STOCKPILE,
                 MiniGame.KIND_SPAWN, MiniGame.KIND_PATH -> true;
            default -> false;
        };
    }

    /** A surface decoration on the block at (col,row), or {@code null}. */
    private SurfaceDecor.Placement surfaceDecorAt(int col, int row) {
        for (SurfaceDecor.Placement sd : level.surfaceDecor) {
            if (sd.col() == col && sd.row() == row) return sd;
        }
        return null;
    }

    private EntityView nearestNetEntity(double wx, double wy) {
        Snapshot snap = net.client().latest();
        if (snap == null) return null;
        EntityView best = null;
        double bestD = ERASE_RADIUS * 2;
        for (EntityView e : snap.mobs()) {
            double d = Math.hypot(e.x - wx, e.y - wy);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        for (EntityView e : snap.items()) {
            double d = Math.hypot(e.x - wx, e.y - wy);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void handlePaletteClick(int mx, int my) {
        // Category tabs across the top of the sidebar.
        if (my >= 34 && my < 34 + Category.values().length * 22) {
            int idx = (my - 34) / 22;
            if (idx >= 0 && idx < Category.values().length) {
                category = Category.values()[idx];
                ctx.sfx(Sfx.CLICK);
                return;
            }
        }
        int gridTop = paletteGridTop();
        // The decor layer toggle row sits just above the swatch grid.
        if (category == Category.DECOR && my >= gridTop - 22 && my < gridTop - 2) {
            decorForeground = !decorForeground;
            ctx.sfx(Sfx.CLICK);
            setStatus("Decorations paint into the "
                    + (decorForeground ? "FOREGROUND" : "BACKGROUND"));
            return;
        }
        // The SURFACE category's three toggle rows: face / condition / layer.
        if (category == Category.SURFACE && my >= gridTop - 66 && my < gridTop - 2) {
            int rowIdx = (my - (gridTop - 66)) / 22;
            ctx.sfx(Sfx.CLICK);
            switch (rowIdx) {
                case 0 -> {
                    surfaceFaceMode = (surfaceFaceMode + 1) % 5;
                    setStatus("Surface face: " + surfaceFaceLabel());
                }
                case 1 -> {
                    SurfaceDecor.Visibility[] all = SurfaceDecor.Visibility.values();
                    surfaceVisibility = all[(surfaceVisibility.ordinal() + 1) % all.length];
                    setStatus("Surface shows: " + surfaceVisibilityLabel());
                }
                default -> {
                    surfaceForeground = !surfaceForeground;
                    setStatus("Surface details paint into the "
                            + (surfaceForeground ? "FOREGROUND" : "BACKGROUND"));
                }
            }
            return;
        }
        int idx = paletteIndexAt(mx, my);
        List<Entry> entries = palette.get(category);
        if (idx >= 0 && idx < entries.size()) {
            Entry e = entries.get(idx);
            selected.put(category, idx);
            ctx.sfx(Sfx.CLICK);
            // Button-style tools act on click instead of arming a brush.
            switch (e.kind) {
                case "generate" -> {
                    if (net == null) openDialog(Dialog.GENERATE);
                    else setStatus("Generation is a local-world feature");
                }
                case "managedoors" -> openDialog(Dialog.DOORS);
                case "managecutscenes" -> {
                    if (net == null) openDialog(Dialog.CUTSCENES);
                    else setStatus("Cutscenes are edited offline");
                }
                case "new" -> openCustomCreator();
                case "brush" -> openDialog(Dialog.BRUSH);
                case "rules" -> {
                    if (net == null) openDialog(Dialog.RULES);
                    else setStatus("Stat rules are edited offline");
                }
                case "sunlight" -> openDialog(Dialog.SUNLIGHT);
                case "mg_settings" -> {
                    if (net == null) openDialog(Dialog.MINIGAME);
                    else setStatus("The mini game is configured before hosting, offline");
                }
                case "playerskin" -> openPlayerSkinDialog();
                case "soundeditor" -> openSoundList(e.key);
                case "soundgroup" -> openSoundList(e.key);
                case "soundoptions" -> openDialog(Dialog.SOUND_OPTIONS);
                case "levelmusic" -> {
                    musicTrack = level.music == null ? "" : level.music;
                    openDialog(Dialog.LEVEL_MUSIC);
                }
                case "roster" -> {
                    if (net == null) openDialog(Dialog.ROSTER);
                    else setStatus("The character roster is edited offline");
                }
                // Effects aren't painted into the level — they belong to the
                // objects that throw them — so a click opens the texture
                // dialog that reskins them.
                case "particle", "projectile" -> openTextureDialog(e);
                case "character" -> {
                    CharacterProfile c = Characters.get(e.key);
                    setStatus(c == null ? e.name : c.name + " — " + c.summary()
                            + "  ·  right-click for its skin");
                }
                case "cutscene" -> setStatus(e.name
                        + " — click the canvas to place its trigger marker");
                default -> setStatus(e.name + (e.custom ? "  (your custom object)" : ""));
            }
        }
    }

    private String surfaceFaceLabel() {
        return surfaceFaceMode == 0 ? "AUTO (nearest to click)"
                : SurfaceDecor.Face.values()[surfaceFaceMode - 1].name();
    }

    private String surfaceVisibilityLabel() {
        return switch (surfaceVisibility) {
            case ALWAYS -> "ALWAYS";
            case OPEN_FACE -> "OPEN FACES ONLY";
            case CLOSED_FACE -> "CLOSED FACES ONLY";
        };
    }

    /** The "+" entry click for the current category (doors reuse their manager). */
    private void openCustomCreator() {
        if (net != null) {
            setStatus("Custom objects are created offline");
            return;
        }
        if (category == Category.DOORS) {
            doorEditIndex = 0;
            openDialog(Dialog.DOORS);
            return;
        }
        if (category == Category.CUTSCENES) {
            csEditIndex = 0;
            openDialog(Dialog.CUTSCENES);
            return;
        }
        customCategory = category;
        cName = "";
        if (category == Category.CHARACTERS) {
            // Start every new character from the engine's own defaults rather
            // than the last block/mob the creator built.
            CharacterProfile d = CharacterProfile.defaultProfile();
            cR = d.body.getRed();
            cG = d.body.getGreen();
            cB = d.body.getBlue();
            cR2 = d.accent.getRed();
            cG2 = d.accent.getGreen();
            cB2 = d.accent.getBlue();
            cCharSpeed = d.speed;
            cCharSprint = d.sprintEnabled;
            cCharAirJumps = d.airJumps;
            cCharJump = d.jumpHeight;
            cCharHp = (int) Math.round(d.maxHealth);
            cCharMana = (int) Math.round(d.maxMana);
            cCharStamina = (int) Math.round(d.maxStamina);
            cCharUltIndex = 0;
            cCharUltEnabled = true;
        }
        openDialog(Dialog.CUSTOM);
    }

    /** Right-clicking a palette icon opens the texture-override dialog for it. */
    private void handlePaletteRightClick(int mx, int my) {
        int idx = paletteIndexAt(mx, my);
        List<Entry> entries = palette.get(category);
        if (idx < 0 || idx >= entries.size()) return;
        Entry e = entries.get(idx);
        if ("cutscene".equals(e.kind)) {
            // Straight into the editor for this cutscene.
            if (net != null) {
                setStatus("Cutscenes are edited offline");
                return;
            }
            for (int i = 0; i < level.cutscenes.size(); i++) {
                if (level.cutscenes.get(i).key.equals(e.key)) csEditIndex = i + 1;
            }
            openDialog(Dialog.CUTSCENES);
            return;
        }
        if (isSoundEntry(e.kind)) {
            // The SOUNDS palette has no textures; both buttons open its list.
            handlePaletteClick(mx, my);
            return;
        }
        if (!skinnable(e.kind)) {
            setStatus("No texture override for " + e.name);
            return;
        }
        if ("playerskin".equals(e.kind)) {
            openPlayerSkinDialog(); // per-action-state, with legacy migration
            return;
        }
        openTextureDialog(e);
    }

    /**
     * Open the texture dialog for a palette entry. Objects drawn per action
     * state (mobs, characters) get the state row; the ones drawn per facing
     * get the direction row on top of it.
     */
    private void openTextureDialog(Entry e) {
        texEntry = e;
        texStates = switch (e.kind) {
            case "mob" -> TextureKeys.MOB_STATES;
            case "character" -> PlayerSprites.ACTION_STATES;
            // An item is its icon, then one sheet per melee move (the object
            // itself sweeping through the swing), then the "wielder" sheets:
            // the whole fighter drawn holding it doing that move. Every one of
            // them falls back to the icon / to idle, so all of it is optional.
            case "item" -> ITEM_TEXTURE_STATES;
            // A block is looked at from more than one side. The flat sheet is
            // what a side-scroller draws and what both plan-view faces fall
            // back to; the top and side are the faces a top-down or isometric
            // level actually sees, and each takes a sheet of its own.
            case "block" -> BLOCK_FACES;
            default -> List.of("default");
        };
        texStateIndex = 0;
        texDirIndex = 0;
        loadTextureFields();
        openDialog(Dialog.TEXTURE);
    }

    /** The block faces the texture dialog assigns sheets to, flat sheet first. */
    private static final List<String> BLOCK_FACES = List.of("flat", "top", "side");

    /**
     * What an item's texture dialog offers: its icon, the object's own sheet
     * for each melee move, and then a "wielder" sheet per move — the fighter
     * holding it, drawn doing that. See
     * {@link com.larsons.engine.combat.MeleeSprites}.
     */
    private static final List<String> ITEM_TEXTURE_STATES = itemTextureStates();

    private static List<String> itemTextureStates() {
        List<String> out = new ArrayList<>();
        out.add("default");
        out.addAll(PlayerSprites.COMBAT_STATES);
        for (String state : MeleeSprites.wieldStates()) out.add("wielder_" + state);
        return List.copyOf(out);
    }

    /** What the face/state cycler is called for the object being reskinned. */
    private String texStateLabel() {
        return "block".equals(texEntry.kind) ? "Face (top-down / isometric)"
                : "Action state";
    }

    /** What the face being edited is used for, so the cycler isn't three words. */
    private String blockFaceNote() {
        return switch (texStates.get(Math.min(texStateIndex, texStates.size() - 1))) {
            case "top" -> "The face a top-down or isometric level looks down at: "
                    + "floors, and the lid of a stacked block.";
            case "side" -> "The face a stacked block turns toward the camera — "
                    + "what gives a wall its height.";
            default -> "The block's one sheet: what a side-scroller draws, and "
                    + "what the top and side fall back to when they have none.";
        };
    }

    /** Palette entries that belong to the sound editor rather than the canvas. */
    private static boolean isSoundEntry(String kind) {
        return switch (kind) {
            case "soundeditor", "soundgroup", "soundoptions", "levelmusic" -> true;
            default -> false;
        };
    }

    private static boolean skinnable(String kind) {
        return switch (kind) {
            case "block", "mob", "item", "decor", "surface", "playerskin",
                 "character", "particle", "projectile" -> true;
            default -> false;
        };
    }

    /** Whether an object's sheets may be split by facing (see {@link Facing}). */
    private static boolean directional(String kind) {
        return switch (kind) {
            // An item's wielder sheets are a whole character, so they split by
            // facing like one; the icon and the object's own move sheets
            // ignore the direction row (see itemTextureKey).
            case "mob", "playerskin", "character", "item" -> true;
            default -> false;
        };
    }

    /**
     * Tools &rarr; Player Skin… (click or right-click): the texture-override
     * dialog aimed at the player character, one sheet per <em>action
     * state</em> (idle/walk/run/jump/fall/swim — cycle the Action state row,
     * like mobs). Unassigned states borrow their nearest assigned relative
     * (run→walk, fall→jump, everything→idle), so a single idle sheet already
     * reskins the whole character. Assignments render the player everywhere
     * — creative play-test and "load level" play alike — and persist in
     * {@code skins.json} like any other texture.
     */
    private void openPlayerSkinDialog() {
        // A pre-action-state skins.json may carry the old single "player"
        // sheet; fold it into the idle state (every state falls back there).
        SkinDef legacy = Skins.get(PlayerSprites.SKIN_KEY);
        if (legacy != null && Skins.get(PlayerSprites.stateKey("idle")) == null) {
            Skins.put(new SkinDef(PlayerSprites.stateKey("idle"), legacy.sheet,
                    legacy.frameWidth, legacy.frameHeight, legacy.frameCount, legacy.fps));
            Skins.remove(PlayerSprites.SKIN_KEY);
            persistSkins();
        }
        texEntry = new Entry("playerskin", "playerskin", "Player Skin", playerSkinIcon());
        texStates = PlayerSprites.ACTION_STATES;
        texStateIndex = 0;
        texDirIndex = 0;
        loadTextureFields();
        openDialog(Dialog.TEXTURE);
    }

    /** The palette entry index under a sidebar point, or -1. */
    private int paletteIndexAt(int mx, int my) {
        int gridTop = paletteGridTop();
        if (my < gridTop) return -1;
        if (my > sliderPanelTop() - 4) return -1;
        int cellPad = (SIDEBAR_W - CELLS_PER_ROW * CELL) / (CELLS_PER_ROW + 1);
        int colIdx = (mx - cellPad) / (CELL + cellPad);
        int rowIdx = (my - gridTop) / (CELL + 12);
        if (colIdx < 0 || colIdx >= CELLS_PER_ROW) return -1;
        return (rowIdx + scroll.get(category)) * CELLS_PER_ROW + colIdx;
    }

    /**
     * Top of the swatch grid: tabs, plus the layer row when DECOR is active,
     * or the three option rows (face / condition / layer) for SURFACE.
     */
    private int paletteGridTop() {
        int top = 34 + Category.values().length * 22 + 10;
        if (category == Category.DECOR) top += 24;
        if (category == Category.SURFACE) top += 68;
        return top;
    }


    private int sliderPanelTop() {
        return net == null ? viewportHeight - 36 - SLIDER_PANEL_H : viewportHeight - 36;
    }

    private void clampScroll() {
        int rows = (palette.get(category).size() + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
        int max = Math.max(0, rows - visiblePaletteRows());
        scroll.put(category, Math.max(0, Math.min(max, scroll.get(category))));
    }

    private int visiblePaletteRows() {
        return Math.max(1, (sliderPanelTop() - paletteGridTop() - 4) / (CELL + 12));
    }

    // --- play-test ----------------------------------------------------------------

    /** Leaving creative mode stops its music and every sound it started. */
    @Override
    public void onExit() {
        endStroke(); // leaving mid-drag still leaves one undoable stroke behind
        testSounds.reset();
    }

    private void enterTest() {
        // Whatever was being painted is a finished edit now: a play-test is not
        // part of the stroke, and mining during one is not an edit at all.
        endStroke();
        // Snapshot terrain so test-mode mining/liquid flow doesn't eat the
        // level (works for dense and giant chunked storage alike). Container
        // contents snapshot alongside so test-mode looting isn't destructive.
        editLevel = level;
        savedTiles = level.snapshotTiles();
        savedContainers = snapshotContainers(level);
        containerPanel = null;
        startTestWorld();
        testInv = new Inventory(testWorld.itemTypes);
        bindTestPickups();
        testStats = new PlayerStats();
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        cutsceneDirector = new CutsceneDirector(level.cutscenes);
        craftingPanel = null;
        testing = true;
        showInventory = false;
        cursorSlot = -1;
        // The test starts from silence and picks up the level's own music,
        // so a play-test is heard exactly as the level will be played.
        testSounds.reset();
        testSounds.setCharacter(testCharacter.key);
        ctx.sound(SoundKeys.world("level_load"));
        camera.zoom = Math.max(profile().minZoom, Math.min(profile().maxZoom, 1.0));
        setStatus("Play-test — [Shift] sprint · hold click to mine · [E] doors/stations"
                + " · P/Esc returns to editing");
    }

    private void startTestWorld() {
        testWorld = new World(level);
        testWorld.populateFromLevel(profile());
        double[] spawn = {level.spawnX, level.spawnY};
        testMe = new PlayerState(0, "", spawn[0], spawn[1]);
        prevHealth = testMe.health;
        prevTestVy = 0;
        prevTestVz = 0;
        // Play-test as one of the characters this level offers — the same
        // roster, and the same picker, a player will meet at its start.
        List<CharacterProfile> roster = Characters.rosterFor(level.characters);
        testPicker = CharacterPicker.needed(roster)
                ? new CharacterPicker(roster, level.name, ctx.character()) : null;
        applyTestCharacter(testPicker != null ? testPicker.selected()
                : roster.isEmpty() ? CharacterProfile.defaultProfile() : roster.get(0));
        testAnimState = "idle";
        testAnimClock = 0;
    }

    /**
     * Fire the play-test character's ultimate at the cursor. The local test
     * world resolves it with the same {@code World.useUltimate} play uses, so
     * a creator tunes an ability against the behaviour players will get.
     */
    private void tryTestUltimate(GameProfile p) {
        Ultimate u = Ultimates.of(testMe);
        if (u == null) {
            setStatus(testCharacter.name + " has no ultimate ability");
            return;
        }
        if (!Ultimates.ready(testMe)) {
            setStatus(u.name() + " — " + (int) Math.round(testMe.ultCharge * 100)
                    + "% charged");
            return;
        }
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        if (testWorld.useUltimate(testMe, aim[0], aim[1], p)) {
            Sounds.actor(testCharacter.key, SoundKeys.ultimate(u.key(), "activate"),
                    "ult_activate");
            setStatus(u.name() + "!");
        } else {
            setStatus(u.name() + " can't fire here");
        }
    }

    /** Make {@code p} the character the play-test runs as (traits and skin). */
    private void applyTestCharacter(CharacterProfile p) {
        testCharacter = p == null ? CharacterProfile.defaultProfile() : p;
        testCharacter.applyTo(testMe);
        ctx.setCharacter(testCharacter.key);
        prevHealth = testMe.health;
    }

    private void bindTestPickups() {
        testWorld.setPickupListener((p, key, n) -> {
            if (profile().itemsEnabled) testInv.add(key, n);
            if (testStats != null) testStats.add("items_picked_up", n);
            Sounds.actor(testCharacter.key, SoundKeys.item(key, "pickup"), "pickup");
        });
    }

    private void exitTest() {
        testing = false;
        // Stop the level's music and every loop the test started before the
        // editor's own music takes over.
        testSounds.reset();
        testWorld = null;
        testInv = null;
        testStats = null;
        ruleEngine = null;
        cutsceneDirector = null;
        craftingPanel = null;
        containerPanel = null;
        showInventory = false;
        level = editLevel != null ? editLevel : level;
        editLevel = null;
        if (savedTiles != null) {
            level.restoreTiles(savedTiles);
            savedTiles = null;
        }
        if (savedContainers != null) {
            level.containers.clear();
            level.containers.putAll(savedContainers);
            savedContainers = null;
        }
        // A play-test that travelled through a door may have left the camera
        // (and the profile) on another level's format — put the editor back in
        // the one it is actually editing.
        ctx.applyLevelSettings(level.settings);
        camera.tileSize = level.tileSize;
        camera.setPerspective(level.perspective);
        buildPalette();
        setStatus("Back to editing — " + format().displayName() + " creative mode");
    }

    /** Deep copy of the level's container contents (test-mode snapshot). */
    private static Map<Long, List<ItemStack>> snapshotContainers(Level lvl) {
        Map<Long, List<ItemStack>> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, List<ItemStack>> e : lvl.containers.entrySet()) {
            List<ItemStack> stacks = new ArrayList<>(e.getValue().size());
            for (ItemStack s : e.getValue()) {
                ItemStack c = new ItemStack(s.key, s.count);
                c.wear = s.wear;
                stacks.add(c);
            }
            copy.put(e.getKey(), stacks);
        }
        return copy;
    }

    private void updateTest(double dt, InputManager input) {
        GameProfile p = profile();
        // The character choice comes first, exactly as it does in play: the
        // test world is built and waiting behind the cards.
        if (testPicker != null) {
            if (testPicker.update(dt, input)) {
                applyTestCharacter(testPicker.selected());
                testPicker = null;
                ctx.sfx(Sfx.CLICK);
            }
            return;
        }
        // A running cutscene owns the frame: the world holds still, the
        // director drives the camera, Enter/Esc skips to the end.
        if (cutsceneDirector != null && cutsceneDirector.active() != null) {
            if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)
                    || input.isKeyJustPressed(KeyEvent.VK_ENTER)) {
                cutsceneDirector.skip();
            } else {
                cutsceneDirector.advance(dt);
            }
            CutscenePlayer cut = cutsceneDirector.active();
            if (cut != null) {
                camera.centerOn(cut.cameraX(), cut.cameraY());
                particles.update(dt);
                return;
            }
            camera.centerOn(testMe.x + p.playerSize / 2.0, testMe.y + p.playerSize / 2.0);
            return; // resume normal play next tick
        }
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
            } else if (showInventory) {
                showInventory = false;
            } else {
                exitTest();
            }
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_P)) {
            exitTest();
            return;
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

        if (craftingPanel != null) {
            updateTestCrafting(input);
        } else if (containerPanel != null) {
            if (containerPanel.update(input, testInv, cursorSlot,
                    viewportWidth, viewportHeight)) {
                ctx.sfx(Sfx.CLICK);
                // A deposited cursor stack no longer exists in the grid.
                if (cursorSlot >= 0 && testInv.slot(cursorSlot) == null) cursorSlot = -1;
            } else if (containerPanel.interactive()) {
                // The inventory shows beside the container: keep its mouse
                // interactions and hotbar selection live so stacks can be
                // arranged and [Q]-stashed without closing the chest.
                for (int k = 0; k < Inventory.HOTBAR; k++) {
                    if (input.isKeyJustPressed(KeyEvent.VK_1 + k)) testInv.select(k);
                }
                int wheel = input.getWheelRotation();
                if (wheel != 0) testInv.scrollSelect(wheel > 0 ? 1 : -1);
                handleTestInventoryMouse(input);
            }
        } else {
            updateTestInventoryControls(input, p);
        }

        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);
        in.sprint = input.isKeyDown(KeyEvent.VK_SHIFT);
        // Space jumps in play-test too; W/Up only ever steer (see PlayScene).
        in.jump = input.isKeyJustPressed(KeyEvent.VK_SPACE);
        testInv.applyPassivesTo(testMe, p.itemsEnabled);
        double preX = testMe.x, preY = testMe.y;
        // The melee machine before the body moves: a lunge's burst and a
        // raised guard's slowed footwork are both movement.
        stepTestMelee(input, p, dt);
        // Play-test simulates in the level's own perspective, so a top-down
        // maze tests as a top-down maze even inside a side-scroll game type.
        PlayerPhysics.step(testMe, in, level, p, level.perspective, dt);
        // Stat tracking: distance in world px, jump take-offs.
        testStats.add("distance_traveled", Math.abs(testMe.x - preX) + Math.abs(testMe.y - preY));
        // A jump counts in every perspective: gravity's -vy in a side-scroller,
        // the hop's upward vz on a plane.
        if ((prevTestVy >= 0 && testMe.vy < 0) || (prevTestVz <= 0 && testMe.vz > 1)) {
            testStats.add("jumps", 1);
            Sounds.actor(testCharacter.key, "",
                    testMe.airJumpsUsed > 0 ? "double_jump" : "jump");
        }
        prevTestVy = testMe.vy;
        prevTestVz = testMe.vz;
        // Play-test classifies the action in the level's own perspective,
        // exactly like the play scene, so the same skin animations play.
        String state = PlayerSprites.actionState(testMe, level, p,
                level.perspective, in.sprint);
        // A melee move takes the drawn animation over while it runs; the
        // movement state below still drives the footsteps.
        String drawn = testMelee.animationState().isEmpty()
                ? state : testMelee.animationState();
        if (!drawn.equals(testAnimState)) {
            testAnimState = drawn;
            testAnimClock = 0;
        } else {
            testAnimClock += dt;
        }

        // Everything the play scene tracks frame to frame, tracked the same
        // way here — so a level under test sounds like the level being played.
        testSounds.setEnabled(p.audioEnabled);
        testSounds.setCharacter(testCharacter.key);
        testSounds.update(dt, testMe, level, p, state, testWorld.projectiles(),
                testWorld.mobs(),
                camera.viewportWidth / 2.0 / Math.max(0.01, camera.zoom));
        testSounds.ambience(level,
                World.darknessFor(testWorld.timeOfDay(), p) > 0.25, false);

        testWorld.step(dt, List.of(testMe), p);
        testStats.add("mobs_killed", testWorld.pollKills());
        testStats.add("deaths", testWorld.pollDeaths());
        for (World.Impact im : testWorld.pollImpacts()) {
            // The same mapping the play scene uses, so an ultimate landing
            // in a play-test sounds like it will when the level is played.
            Sounds.playFirst(1.0,
                    SoundKeys.impact(im.key(), im.explosion()).toArray(new String[0]));
            if (p.particlesEnabled) {
                particles.burst(im.x(), im.y(), new Color(255, 200, 120),
                        im.explosion() ? 18 : 6);
            }
        }
        if (testMe.health < prevHealth - 0.01) {
            testStats.add("damage_taken", prevHealth - testMe.health);
            // The cry itself comes from the tracker, which knows the character.
        }
        prevHealth = testMe.health;

        // Programmable map-maker rules ("mined 50 blocks → reward…").
        for (StatRuleEngine.Fired fired : ruleEngine.update(testStats, testInv)) {
            ctx.sound(SoundKeys.world("stat_rule"));
            setStatus(ruleFiredMessage(fired.rule()));
        }

        if (input.isKeyJustPressed(KeyEvent.VK_E)) {
            if (craftingPanel != null) {
                craftingPanel = null;
            } else if (containerPanel != null) {
                containerPanel.beginClose();
            } else if (!tryDoorTravel()) {
                tryOpenStation(p);
            }
        }

        if (!showInventory && craftingPanel == null && containerPanel == null) {
            updateTestMouseActions(input, p, dt);
            updateTestMeleeControls(input, p);
            // [R] fires the character's ultimate at the cursor, once charged —
            // the same key and the same World resolution as in play.
            if (input.isKeyJustPressed(KeyEvent.VK_R)) tryTestUltimate(p);
        } else {
            testWorld.cancelMining();
            mineSoundTimer = 0;
        }

        if (swingTime > 0) swingTime -= dt;
        particles.update(dt);
        camera.centerOn(testMe.x + p.playerSize / 2.0, testMe.y + p.playerSize / 2.0);

        // Cutscene triggers watch the player: zones fire on entry, INTERACT
        // ones on E (doors and stations already had their chance above).
        if (cutsceneDirector != null) {
            boolean interact = input.isKeyJustPressed(KeyEvent.VK_E)
                    && craftingPanel == null && containerPanel == null;
            Cutscene started = cutsceneDirector.checkTriggers(
                    testMe.x + p.playerSize / 2.0, testMe.y + p.playerSize / 2.0,
                    interact, level.tileSize, camera.x, camera.y);
            if (started != null) {
                testWorld.cancelMining();
                ctx.sfx(Sfx.CLICK);
            }
        }
    }

    private static String ruleFiredMessage(StatRule rule) {
        StringBuilder sb = new StringBuilder("Rule fired: ")
                .append(PlayerStats.label(rule.stat()))
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

    /** Crafting overlay input: wheel scrolls it, clicks craft. */
    private void updateTestCrafting(InputManager input) {
        CraftingPanel.Crafted crafted =
                craftingPanel.update(input, testInv, viewportWidth, viewportHeight);
        if (crafted != null) {
            testStats.add("crafts", 1);
            Sounds.actor(testCharacter.key,
                    SoundKeys.item(crafted.recipe().output(), "craft"), "craft");
            if (crafted.leftover() > 0) {
                DroppedItem drop = testWorld.spawnItem(crafted.recipe().output(),
                        crafted.leftover(), testMe.x, testMe.y);
                if (drop != null) drop.pickupDelay = 1.0;
            }
            ItemDef out = testWorld.itemTypes.get(crafted.recipe().output());
            setStatus("Crafted " + (out != null ? out.name() : crafted.recipe().output())
                    + (crafted.recipe().outputCount() > 1
                    ? " ×" + crafted.recipe().outputCount() : ""));
        }
    }

    /** Standing by a crafting table / alchemy station / chest, E opens its panel. */
    private void tryOpenStation(GameProfile p) {
        double ts = level.tileSize;
        int pc = (int) Math.floor((testMe.x + p.playerSize / 2.0) / ts);
        int pr = (int) Math.floor((testMe.y + p.playerSize / 2.0) / ts);
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
                            testWorld.itemTypes);
                    ctx.sfx(Sfx.CLICK);
                    return;
                }
                if (b.container() && p.itemsEnabled) {
                    // The player's inventory opens beside the container panel
                    // (side by side) so moving stacks between the two is one
                    // screen.
                    containerPanel = new ContainerPanel(level, pc + dc, pr + dr,
                            b.displayName(), testWorld.itemTypes);
                    showInventory = true;
                    cursorSlot = -1;
                    ctx.sfx(Sfx.CLICK);
                    return;
                }
            }
        }
    }

    /** The same hotbar/inventory controls the play scene has, minus netcode. */
    private void updateTestInventoryControls(InputManager input, GameProfile p) {
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
            if (input.isKeyJustPressed(KeyEvent.VK_1 + k)) testInv.select(k);
        }
        int wheel = input.getWheelRotation();
        if (wheel != 0) testInv.scrollSelect(wheel > 0 ? 1 : -1);

        if (input.isKeyJustPressed(KeyEvent.VK_Q)) {
            dropTestStack(testInv.selectedIndex(), 1);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_F)) {
            ItemDef def = testInv.selectedDef();
            if (def != null && "mana_potion".equals(def.key())
                    && testMe.mana < PlayerState.MAX_MANA && testInv.consumeSelected()) {
                testMe.mana = Math.min(PlayerState.MAX_MANA, testMe.mana + 50);
                Sounds.actor(testCharacter.key, SoundKeys.item(def.key(), "use"), "drink");
            } else if (def != null && def.heal() > 0 && testMe.health < PlayerState.MAX_HEALTH
                    && testInv.consumeSelected()) {
                // Food heals directly and restores stamina (and mana for
                // rare delicacies) — World.applyFood.
                World.applyFood(testMe, def);
                prevHealth = testMe.health;
                Sounds.actor(testCharacter.key, SoundKeys.item(def.key(), "use"), "eat");
            }
        }
        if (showInventory) handleTestInventoryMouse(input);
    }

    private void handleTestInventoryMouse(InputManager input) {
        if (input.isRightMouseJustPressed()) {
            cursorSlot = -1;
            return;
        }
        if (!input.isMouseJustPressed()) return;
        int slot = inventorySlotAt(mouseX, mouseY);
        if (slot >= 0) {
            if (cursorSlot < 0) {
                if (testInv.slot(slot) != null) cursorSlot = slot;
            } else {
                if (testInv.move(cursorSlot, slot)) ctx.sfx(Sfx.CLICK);
                cursorSlot = -1;
            }
        } else if (cursorSlot >= 0) {
            // A click on the container panel beside the inventory is panel
            // interaction, not a toss-into-the-world.
            boolean overContainer = containerPanel != null
                    && containerPanel.contains(mouseX, mouseY, viewportWidth, viewportHeight);
            ItemStack held = testInv.slot(cursorSlot);
            if (held != null && !insideInventoryPanel(mouseX, mouseY) && !overContainer) {
                dropTestStack(cursorSlot, held.count);
            }
            cursorSlot = -1;
        }
    }

    private void dropTestStack(int slot, int count) {
        ItemStack stack = testInv.slot(slot);
        if (stack == null || count <= 0) return;
        String key = stack.key;
        int removed = testInv.removeAt(slot, count);
        if (removed <= 0) return;
        DroppedItem drop = testWorld.spawnItem(key, removed, testMe.x, testMe.y);
        if (drop != null) {
            drop.tossForward(testMe.facing, level.format().gravity());
            drop.pickupDelay = 1.0;
        }
        ctx.sfx(Sfx.CLICK);
    }

    /**
     * Test-mode mouse actions. Left click shoots the held weapon or swings;
     * <em>holding</em> left over a block in reach mines it over time — block
     * durability, sped up by a matching tool (pickaxe/axe/shovel). Right
     * click places.
     */
    private void updateTestMouseActions(InputManager input, GameProfile p, double dt) {
        if (input.isRightMouseJustPressed()) handleTestRightClick(p);

        double[] aim = camera.screenToWorld(mouseX, mouseY);
        double ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (testMe.x + p.playerSize / 2.0),
                aim[1] - (testMe.y + p.playerSize / 2.0)) <= 5 * ts;
        ItemDef held = p.itemsEnabled ? testInv.selectedDef() : null;

        // Hold-to-mine: durability progress while the button stays down.
        boolean shoots = p.projectilesEnabled && held != null && held.projectile() != null;
        boolean miningNow = input.isMouseDown() && !shoots && p.blockEditingEnabled
                && inReach && level.tileAt(col, row) > 0;
        if (miningNow) {
            swingTime = Math.max(swingTime, 0.1);
            Block digging = level.topBlockAt(col, row);
            mineSoundTimer -= dt;
            if (digging != null && mineSoundTimer <= 0) {
                mineSoundTimer = 0.33;
                Sounds.actor(testCharacter.key, SoundKeys.block(digging.key(), "mine"),
                        "mine", 0.5);
            }
            Block mined = testWorld.continueMining(col, row, held, p.itemsEnabled, dt);
            if (mined != null) {
                testStats.add("blocks_mined", 1);
                Sounds.actor(testCharacter.key, SoundKeys.block(mined.key(), "break"),
                        "mine_break");
                if (p.particlesEnabled) {
                    particles.burst((col + 0.5) * ts, (row + 0.5) * ts, mined.color(), 10);
                }
                if (p.itemsEnabled && held != null && held.toolClass() != null
                        && testInv.damageSelected(1)) {
                    Sounds.actor(testCharacter.key, SoundKeys.item(held.key(), "break"),
                            "mine_break");
                    setStatus(held.name() + " broke!");
                }
            }
        } else {
            testWorld.cancelMining();
        }

        if (!input.isMouseJustPressed()) return;
        if (shoots) {
            swingTime = 0.1;
            Projectile fired = testWorld.playerShoot(testMe, testInv, aim[0], aim[1]);
            if (fired != null) {
                testStats.add("shots_fired", 1);
                Sounds.actor(testCharacter.key,
                        SoundKeys.projectile(fired.def.key(), "fire"), "shoot");
            }
            return;
        }
        if (miningNow) return; // the held stroke handles it
        // Destructible decorations (trees → logs + leaves…) before mob swings.
        if (inReach) {
            boolean axe = held != null && "axe".equals(held.toolClass());
            World.Chop chop = testWorld.chopDecor(aim[0], aim[1], axe, p.itemsEnabled);
            if (chop.hit()) {
                swingTime = 0.2;
                Sounds.actor(testCharacter.key, chop.decor() == null ? ""
                        : SoundKeys.decor(chop.decor().key(),
                        chop.broken() ? "break" : "hit"), "chop");
                if (p.particlesEnabled) {
                    particles.burst(aim[0], aim[1], new Color(110, 85, 50),
                            chop.broken() ? 14 : 5);
                }
                return;
            }
        }
        if (p.combatEnabled) {
            // The click starts the swing; it lands when the weapon's own hit
            // window opens (stepTestMelee), exactly as it does in play.
            testMeleeAimX = aim[0];
            testMeleeAimY = aim[1];
            Melee.start(testMe, testMelee, testMeleeProfile(p), MeleeAction.SWING);
        }
    }

    /**
     * The play-test's melee keys, matching the play scene's: [C] holds the
     * guard, [V] parries, [X] lunges, [Z] dashes.
     */
    private void updateTestMeleeControls(InputManager input, GameProfile p) {
        if (!p.combatEnabled) return;
        MeleeAction requested = input.isKeyJustPressed(KeyEvent.VK_V) ? MeleeAction.PARRY
                : input.isKeyJustPressed(KeyEvent.VK_X) ? MeleeAction.LUNGE
                : input.isKeyJustPressed(KeyEvent.VK_Z) ? MeleeAction.DASH
                : MeleeAction.NONE;
        if (requested == MeleeAction.NONE) return;
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        testMeleeAimX = aim[0];
        testMeleeAimY = aim[1];
        Melee.start(testMe, testMelee, testMeleeProfile(p), requested);
    }

    /**
     * Advance the play-test's melee machine and land what it says landed —
     * the play scene's {@code stepMelee}, against the local test world.
     */
    private void stepTestMelee(InputManager input, GameProfile p, double dt) {
        MeleeProfile weapon = testMeleeProfile(p);
        ItemDef held = p.itemsEnabled ? testInv.selectedDef() : null;
        testMeleeItem = held == null ? "" : held.key();
        boolean guard = p.combatEnabled && input.isKeyDown(KeyEvent.VK_C)
                && !showInventory && craftingPanel == null && containerPanel == null;
        boolean planar = level.perspective != Perspective.SIDE_SCROLL || !p.gravityEnabled;
        Melee.step(testMe, testMelee, weapon, testMeleeItem, guard, planar, dt);

        MeleeAction begun = testMelee.pollBegun();
        if (begun != MeleeAction.NONE) {
            Sounds.playFirst(1.0,
                    MeleeSounds.playerStart(testCharacter.key, testMeleeItem, begun));
        }
        if (testMelee.pollEnded() == MeleeAction.SHIELD) {
            Sounds.playFirst(0.8, MeleeSounds.playerEnd(testCharacter.key,
                    testMeleeItem, MeleeAction.SHIELD));
        }
        // Drained unconditionally: a strike is never banked for a later tick.
        boolean struck = testMelee.pollStrike();
        if (struck && p.combatEnabled) {
            double base = World.FIST_DAMAGE + (held != null ? held.damage() : 0);
            World.MeleeHit hit = testWorld.meleeStrike(testMe, weapon,
                    testMelee.action(), testMeleeAimX, testMeleeAimY,
                    Melee.damage(base, weapon, testMelee.action()));
            if (hit.parried()) {
                testMelee.stagger(MeleeState.PARRY_STAGGER);
                Sounds.playFirst(1.0, MeleeSounds.mobHit(hit.mob().def.key(),
                        hit.mob().weaponKey(), MeleeAction.PARRY));
            } else if (hit.hit()) {
                testMelee.markConnected();
                Sounds.actor(testCharacter.key, SoundKeys.mob(hit.mob().def.key(),
                        hit.mob().dead() ? "death" : "hurt"), "attack_hit");
                if (p.particlesEnabled) {
                    particles.burst(hit.mob().x + hit.mob().def.size() / 2,
                            hit.mob().y + hit.mob().def.size() / 2,
                            hit.mob().def.body(), 8);
                }
            }
        }
        if (testMelee.parrying() && testWorld.parryProjectiles(testMe, weapon) > 0) {
            testMelee.markConnected();
        }
        if (testMelee.pollConnected()) {
            Sounds.playFirst(1.0, MeleeSounds.playerHit(testCharacter.key,
                    testMeleeItem, testMelee.action()));
        }
        if (testMe.guardHits > testGuardHits) {
            testMelee.flashGuard();
            Sounds.playFirst(1.0, MeleeSounds.playerHit(testCharacter.key, testMeleeItem,
                    testMe.parrying ? MeleeAction.PARRY : MeleeAction.SHIELD));
        }
        testGuardHits = testMe.guardHits;
    }

    /** The melee timings of what the play-test player is holding. */
    private MeleeProfile testMeleeProfile(GameProfile p) {
        return MeleeProfiles.of(p.itemsEnabled ? testInv.selectedDef() : null);
    }

    /** Right click in test: place the selected hotbar block (consumes one). */
    private void handleTestRightClick(GameProfile p) {
        if (!p.blockEditingEnabled) return;
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        double ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (testMe.x + p.playerSize / 2.0),
                aim[1] - (testMe.y + p.playerSize / 2.0)) <= 5 * ts;
        if (!inReach) return;
        ItemDef def = p.itemsEnabled ? testInv.selectedDef() : null;
        if (p.itemsEnabled && (def == null || def.category() != ItemDef.Category.BLOCK)) return;
        String blockKey = def != null ? def.blockKey() : "dirt";
        Block b = level.blocks.get(blockKey);
        // A stack is built from the bottom up: a hole is floored first, and a
        // cell that already has a floor gets the block stood on it, so a player
        // builds walls in a plan view the same way a creator does. Liquids
        // accept placement either way — covering water is how it's removed.
        int layer = level.placeLayer(col, row);
        if (b == null || layer < 0) return;
        double size = p.playerSize;
        boolean overlapsMe = testMe.x + size > col * ts && testMe.x < (col + 1) * ts
                && testMe.y + size > row * ts && testMe.y < (row + 1) * ts;
        // Flooring a hole under your own feet is not walling yourself in.
        boolean wouldClose = b.solid()
                && (!level.layered() || layer == Level.LAYER_UPPER);
        if (wouldClose && overlapsMe) return;
        if (testWorld.placeBlock(col, row, b.id())) {
            if (p.itemsEnabled) testInv.consumeSelected();
            testStats.add("blocks_placed", 1);
            Sounds.actor(testCharacter.key, SoundKeys.block(b.key(), "place"), "place");
        }
    }

    /**
     * Walk into a painted door and press E: load its target level and keep
     * testing. Returns whether a door was there (used to fall through to
     * crafting stations).
     */
    private boolean tryDoorTravel() {
        double half = profile().playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(testMe.x + half, testMe.y + half,
                level.tileSize * 1.3);
        if (door == null) return false;
        DoorLink link = doors.get(door.type);
        if (link == null || link.targetLevel().isEmpty()) {
            setStatus("This door has no target level — set one in Manage Doors");
            return true;
        }
        LevelStore store = new LevelStore(profile().name);
        if (!store.exists(link.targetLevel())) {
            setStatus("Door target \"" + link.targetLevel() + "\" isn't saved yet");
            return true;
        }
        level = store.load(link.targetLevel());
        // The destination brings its own format and settings: a door from a
        // side-scrolling level into an isometric one switches the play-test's
        // camera and movement model on the spot, exactly as it does in Play.
        ctx.applyLevelSettings(level.settings);
        camera.tileSize = level.tileSize;
        camera.setPerspective(level.perspective);
        startTestWorld();
        bindTestPickups(); // inventory carries through the door
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        cutsceneDirector = new CutsceneDirector(level.cutscenes);
        testSounds.reset();
        ctx.sound(SoundKeys.door("travel"));
        ctx.sound(SoundKeys.player("door_enter"));
        setStatus("Entered \"" + link.label() + "\" → " + level.name
                + " (" + level.format().displayName() + ")");
        return true;
    }

    // --- dialogs -------------------------------------------------------------------

    private void openDialog(Dialog d) {
        // A window is one action: its rows and buttons record into a single
        // step that opens here and closes when the window does, so Ctrl+Z takes
        // back the session — a rule added, a mini game set up, a cutscene
        // scripted — rather than whichever field was touched last. Rows that
        // write straight into the level as they are dragged are covered by that
        // too, without every one of them having to know about the history.
        beginDialogEdit(d);
        dialog = d;
        dialogForm = new ConfigForm(switch (d) {
            case NEW_LEVEL -> "New Level";
            case SAVE -> "Save Level";
            case LOAD -> "Load Level";
            case CONFIRM_EXIT -> "Leave Creative Mode?";
            case GENERATE -> "Generate Level (Perlin noise)";
            case DOORS -> "Doors — " + profile().name;
            case TEXTURE -> "Texture — " + (texEntry != null ? texEntry.name : "");
            case CUSTOM -> "New Custom " + customKindName();
            case RULES -> "Stat Rules — " + level.name;
            case BRUSH -> "Brush Settings";
            case CUTSCENES -> "Cutscenes — " + level.name;
            case CUTSCENE_ACTORS -> "Actors — " + editingCutsceneName();
            case CUTSCENE_STEPS -> "Steps — " + editingCutsceneName();
            case MINIGAME -> "Mini Game — " + level.name;
            case ROSTER -> "Character Roster — " + level.name;
            case SOUNDS -> "Sounds — " + (soundCategory.isEmpty()
                    ? "every sound in the game" : soundCategory);
            case SOUND -> "Sound — " + soundLabel;
            case SOUND_OPTIONS -> "Sound Options";
            case LEVEL_MUSIC -> "Level Music — " + level.name;
            default -> "";
        }).theme(MenuTheme.dark());

        switch (d) {
            case NEW_LEVEL -> {
                if (!dialogRebuild) {
                    pendingName = "New Level";
                    pendingWidth = 60;
                    pendingHeight = 24;
                    pendingFormat = format();
                }
                dialogRebuild = false;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                // Each level keeps its own format — creating one in another
                // format switches the editor into that creative mode (palette,
                // starter canvas, camera and movement model all follow).
                dialogForm.addEnum("Format", LevelFormat.values(),
                        () -> pendingFormat, v -> pendingFormat = v);
                dialogForm.addToggle("Override map size (up to "
                                + Level.MAX_GIANT_SIZE + ")",
                        () -> overrideMapSize, v -> {
                            overrideMapSize = v;
                            dialogRebuild = true;
                            openDialog(Dialog.NEW_LEVEL); // rebuild with new caps
                        });
                if (overrideMapSize) {
                    dialogForm.addInt("Width (tiles)", () -> pendingWidth,
                            v -> pendingWidth = v, 8, Level.MAX_GIANT_SIZE, 512);
                    dialogForm.addInt("Height (tiles)", () -> pendingHeight,
                            v -> pendingHeight = v, 8, Level.MAX_GIANT_SIZE, 512);
                } else {
                    dialogForm.addSlider("Width (tiles)", () -> pendingWidth,
                            v -> pendingWidth = v, 8, STANDARD_MAX_SIZE);
                    dialogForm.addSlider("Height (tiles)", () -> pendingHeight,
                            v -> pendingHeight = v, 8, STANDARD_MAX_SIZE);
                }
                dialogForm.addAction("Create", () -> {
                    recordLevelSwap(); // Ctrl+Z hands the level back untouched
                    level = starterLevel(pendingName, pendingWidth, pendingHeight,
                            pendingFormat);
                    afterLevelSwap();
                    setStatus("Created \"" + level.name + "\" (" + level.width + "x"
                            + level.height + ") — " + format().displayName()
                            + " creative mode"
                            + (level.isChunked() ? ", chunked" : "")
                            + " · [Ctrl+Z] goes back to the last one");
                });
                dialogForm.addAction("Cancel", this::closeDialog);
            }
            case SAVE -> {
                pendingName = level.name;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                dialogForm.addAction("Save", () -> {
                    level.name = pendingName.isBlank() ? "Untitled" : pendingName.trim();
                    captureLevelSettings();
                    LevelStore store = new LevelStore(profile().name);
                    Path file = store.save(level);
                    profile().lastLevelPath = file.toString();
                    ctx.save();
                    closeDialog();
                    ctx.sound(SoundKeys.world("level_save"));
                    setStatus("Saved to " + file + " — Play/Load now loads this level with its settings");
                });
                dialogForm.addAction("Cancel", this::closeDialog);
            }
            case LOAD -> {
                LevelStore store = new LevelStore(profile().name);
                List<String> names = store.list();
                if (names.isEmpty()) {
                    dialogForm.addAction("(no saved levels for \"" + profile().name + "\")",
                            this::closeDialog);
                }
                for (String name : names) {
                    dialogForm.addAction(name, () -> {
                        recordLevelSwap(); // Ctrl+Z hands the level back untouched
                        level = store.load(name);
                        profile().lastLevelPath = store.fileFor(name).toString();
                        ctx.save();
                        afterLevelSwap();
                        setStatus("Loaded \"" + level.name + "\""
                                + " · [Ctrl+Z] goes back to the last one");
                    });
                }
                dialogForm.addAction("Cancel", this::closeDialog);
            }
            case CONFIRM_EXIT -> {
                dialogForm.addAction("Save, then exit", () -> {
                    captureLevelSettings();
                    LevelStore store = new LevelStore(profile().name);
                    Path file = store.save(level);
                    profile().lastLevelPath = file.toString();
                    ctx.save();
                    scenes.transitionTo("menu");
                });
                dialogForm.addAction("Exit without saving", () -> scenes.transitionTo("menu"));
                dialogForm.addAction("Cancel", this::closeDialog);
            }
            case GENERATE -> buildGenerateForm();
            case DOORS -> buildDoorsForm();
            case TEXTURE -> buildTextureForm();
            case CUSTOM -> buildCustomForm();
            case RULES -> buildRulesForm();
            case BRUSH -> buildBrushForm();
            case CUTSCENES -> buildCutscenesForm();
            case CUTSCENE_ACTORS -> buildCutsceneActorsForm();
            case CUTSCENE_STEPS -> buildCutsceneStepsForm();
            case MINIGAME -> buildMiniGameForm();
            case ROSTER -> buildRosterForm();
            case SOUNDS -> buildSoundListForm();
            case SOUND -> buildSoundForm();
            case SOUND_OPTIONS -> buildSoundOptionsForm();
            case LEVEL_MUSIC -> buildLevelMusicForm();
            case SUNLIGHT -> buildSunlightForm();
            default -> { /* NONE */ }
        }
    }

    /**
     * The Mini Game Setup window: pick the mode and its settings — team count
     * (Stockpile/Battle), the PvP toggle (forced on for Battle), the score to
     * win, the Escort clock, and which item keys count as Stockpile resources.
     * The mode switch rebuilds the form so only relevant rows show.
     */
    private void buildMiniGameForm() {
        if (level.minigame == null) level.minigame = new MiniGameConfig();
        MiniGameConfig cfg = level.minigame;
        if (!dialogRebuild) {
            mgRes1 = cfg.resourceItems.size() > 0 ? cfg.resourceItems.get(0) : "";
            mgRes2 = cfg.resourceItems.size() > 1 ? cfg.resourceItems.get(1) : "";
            mgRes3 = cfg.resourceItems.size() > 2 ? cfg.resourceItems.get(2) : "";
        }
        dialogRebuild = false;

        String[] modes = new String[MiniGameConfig.Mode.values().length];
        for (MiniGameConfig.Mode m : MiniGameConfig.Mode.values()) {
            modes[m.ordinal()] = m.displayName;
        }
        dialogForm.addEnum("Game mode", modes,
                () -> cfg.mode.displayName,
                v -> {
                    for (MiniGameConfig.Mode m : MiniGameConfig.Mode.values()) {
                        if (m.displayName.equals(v)) cfg.mode = m;
                    }
                    cfg.normalize();
                    dialogRebuild = true;
                    openDialog(Dialog.MINIGAME); // only relevant rows show
                });
        switch (cfg.mode) {
            case CTF -> {
                dialogForm.addNote("Two teams steal each other's flag — paint both "
                        + "Flag Bases anywhere on the level.");
                dialogForm.addInt("Captures to win", () -> cfg.scoreLimit,
                        v -> cfg.scoreLimit = v, 1, 50, 1);
                dialogForm.addToggle("PvP (players can fight)", () -> cfg.pvp,
                        v -> cfg.pvp = v);
            }
            case STOCKPILE -> {
                dialogForm.addNote("Teams race to bank resources at their "
                        + "Stockpile marker.");
                dialogForm.addInt("Teams", () -> cfg.teams, v -> cfg.teams = v, 2, Team.MAX, 1);
                dialogForm.addInt("Resources to win", () -> cfg.scoreLimit,
                        v -> cfg.scoreLimit = v, 1, 500, 1);
                dialogForm.addToggle("PvP (players can fight)", () -> cfg.pvp,
                        v -> cfg.pvp = v);
                dialogForm.addText("Resource item 1 (key)", () -> mgRes1, v -> mgRes1 = v, 24);
                dialogForm.addText("Resource item 2 (key)", () -> mgRes2, v -> mgRes2 = v, 24);
                dialogForm.addText("Resource item 3 (key)", () -> mgRes3, v -> mgRes3 = v, 24);
            }
            case BATTLE -> {
                dialogForm.addNote("Team deathmatch — everyone spawns with magic "
                        + "weapons and tools. PvP is always on in Battle.");
                dialogForm.addInt("Teams", () -> cfg.teams, v -> cfg.teams = v, 2, Team.MAX, 1);
                dialogForm.addInt("Kills to win", () -> cfg.scoreLimit,
                        v -> cfg.scoreLimit = v, 1, 100, 1);
            }
            case ESCORT -> {
                dialogForm.addNote("Red escorts the payload along the waypoint "
                        + "path; Blue stops them.");
                dialogForm.addInt("Round time (seconds)", () -> cfg.escortTimeSec,
                        v -> cfg.escortTimeSec = v, 30, 1800, 30);
                dialogForm.addToggle("PvP (players can fight)", () -> cfg.pvp,
                        v -> cfg.pvp = v);
            }
            default -> dialogForm.addNote("No mini game — this plays as a normal level.");
        }
        dialogForm.addAction("Done", () -> {
            if (cfg.mode == MiniGameConfig.Mode.STOCKPILE) {
                cfg.resourceItems = new ArrayList<>();
                StringBuilder bad = new StringBuilder();
                for (String key : new String[]{mgRes1, mgRes2, mgRes3}) {
                    if (key == null || key.isBlank()) continue;
                    if (ItemRegistry.standard().get(key.trim()) == null) {
                        if (bad.length() > 0) bad.append(", ");
                        bad.append(key.trim());
                    } else {
                        cfg.resourceItems.add(key.trim().toLowerCase());
                    }
                }
                cfg.normalize();
                if (bad.length() > 0) {
                    closeDialog();
                    setStatus("Saved — unknown item key(s) ignored: " + bad);
                    return;
                }
            }
            cfg.normalize();
            closeDialog();
            if (cfg.mode == MiniGameConfig.Mode.NONE) {
                setStatus("Mini game off — this plays as a normal level");
            } else {
                String missing = new MiniGame(level, cfg).validate();
                setStatus(missing != null ? missing
                        : cfg.mode.displayName + " is ready — save, then host or play it");
            }
        });
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /**
     * The Brush Settings window: stroke shape and size, plus the multi-block
     * mix — up to three extra block keys painted alongside the selected block,
     * scattered stably across the stroke so one drag lays down varied terrain.
     */
    private void buildBrushForm() {
        String[] shapes = new String[Brush.Shape.values().length];
        for (Brush.Shape s : Brush.Shape.values()) shapes[s.ordinal()] = Brush.label(s);
        dialogForm.addEnum("Stroke shape", shapes,
                () -> Brush.label(brushShape),
                v -> {
                    for (Brush.Shape s : Brush.Shape.values()) {
                        if (Brush.label(s).equals(v)) brushShape = s;
                    }
                });
        dialogForm.addSlider("Size (tiles)", () -> brushSize, v -> brushSize = v,
                Brush.MIN_SIZE, Brush.MAX_SIZE);
        dialogForm.addToggle("Paint with multiple blocks", () -> brushMix,
                v -> brushMix = v);
        Entry sel = selectedEntry();
        String primary = sel != null && "block".equals(sel.kind) ? sel.name : "(palette pick)";
        dialogForm.addNote("Block 1 is the palette selection: " + primary + ".");
        dialogForm.addText("Block 2 key (blank = unused)", () -> brushKey2,
                v -> brushKey2 = v, 24).enabledWhen(() -> brushMix);
        dialogForm.addText("Block 3 key (blank = unused)", () -> brushKey3,
                v -> brushKey3 = v, 24).enabledWhen(() -> brushMix);
        dialogForm.addText("Block 4 key (blank = unused)", () -> brushKey4,
                v -> brushKey4 = v, 24).enabledWhen(() -> brushMix);
        dialogForm.addAction("Done", () -> {
            StringBuilder bad = new StringBuilder();
            for (String key : new String[]{brushKey2, brushKey3, brushKey4}) {
                if (key != null && !key.isBlank()
                        && level.blocks.get(key.trim()) == null) {
                    if (bad.length() > 0) bad.append(", ");
                    bad.append(key.trim());
                }
            }
            closeDialog();
            setStatus(bad.length() == 0
                    ? "Brush: " + Brush.label(brushShape) + " " + brushSize
                    + (brushMix ? " (multi-block mix on)" : "")
                    : "Brush saved — unknown block key(s) ignored: " + bad);
        });
    }

    private String customKindName() {
        return switch (customCategory) {
            case LIQUIDS -> "Liquid";
            case LIGHTS -> "Light";
            case MOBS -> "Mob";
            case ITEMS -> "Item";
            case DECOR -> "Decoration";
            case SURFACE -> "Block Decor";
            case CHARACTERS -> "Character";
            default -> "Block";
        };
    }

    /**
     * Snapshot the active feature toggles into the level so they save with it
     * — the level is what carries settings now, not the game type. Skipped
     * online, where the server (not a saved file) owns the world.
     */
    private void captureLevelSettings() {
        if (net == null) level.captureSettings(profile());
    }

    /** Camera/slider bookkeeping after replacing the edited level. */
    private void afterLevelSwap() {
        // A loaded level brings its own feature toggles; a freshly created or
        // generated one has none yet (settings == null) and keeps the current
        // ones, which it then inherits when first saved.
        if (net == null) ctx.applyLevelSettings(level.settings);
        camera.tileSize = level.tileSize;
        if (net == null) camera.setPerspective(level.perspective);
        camera.centerOn(level.spawnX, level.spawnY);
        pendingLevelW = level.width;
        pendingLevelH = level.height;
        buildPalette(); // the CUTSCENES palette lists this level's cutscenes
        csEditIndex = 0;
        ctx.sound(SoundKeys.world("level_load"));
        closeDialog();
    }

    private void buildGenerateForm() {
        if (!dialogRebuild) {
            pendingName = "Generated " + (1 + (int) (Math.random() * 8999));
            genSeed = 1 + (int) (Math.random() * 99998);
            pendingFormat = format();
            // Maze mode fits the plan-view formats; Perlin terrain fits the
            // side-scroller — default the mode to match the format.
            genMaze = pendingFormat.defaultsToMaze();
        }
        dialogRebuild = false;
        dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
        dialogForm.addEnum("Format", LevelFormat.values(),
                () -> pendingFormat, v -> {
                    pendingFormat = v;
                    genMaze = v.defaultsToMaze();
                });
        dialogForm.addEnum("Mode", new String[]{"Perlin terrain", "Maze"},
                () -> genMaze ? "Maze" : "Perlin terrain",
                v -> genMaze = "Maze".equals(v));
        dialogForm.addToggle("Override map size (giant, chunk-loaded)",
                () -> overrideMapSize, v -> {
                    overrideMapSize = v;
                    dialogRebuild = true;
                    openDialog(Dialog.GENERATE); // rebuild with new caps
                });
        if (overrideMapSize) {
            dialogForm.addInt("Width (tiles)", () -> genWidth, v -> genWidth = v,
                    64, Level.MAX_GIANT_SIZE, 1024);
            dialogForm.addInt("Height (tiles)", () -> genHeight, v -> genHeight = v,
                    48, Level.MAX_GIANT_SIZE, 1024);
        } else {
            dialogForm.addSlider("Width (tiles)", () -> genWidth, v -> genWidth = v,
                    64, STANDARD_MAX_SIZE);
            dialogForm.addSlider("Height (tiles)", () -> genHeight, v -> genHeight = v,
                    48, STANDARD_MAX_SIZE);
        }
        dialogForm.addInt("Seed", () -> genSeed, v -> genSeed = v, 1, 99999, 1);
        dialogForm.addAction("Randomize Seed", () -> genSeed = 1 + (int) (Math.random() * 99998));
        dialogForm.addAction("Generate", () -> {
            String name = pendingName.isBlank() ? "Generated" : pendingName.trim();
            recordLevelSwap(); // Ctrl+Z hands the level back untouched
            if (genMaze) {
                level = LevelGenerator.generateMaze(name, genWidth, genHeight,
                        profile().tileSize, genSeed, pendingFormat);
                afterLevelSwap();
                ctx.sound(SoundKeys.world("level_generate"));
                setStatus("Generated maze \"" + level.name + "\" (" + level.width + "x"
                        + level.height + ", seed " + genSeed
                        + ") — chests, torches, mobs; the gold key waits at the far end");
                return;
            }
            Level generated = LevelGenerator.generate(name,
                    genWidth, genHeight, profile().tileSize, genSeed);
            generated.setFormat(pendingFormat);
            level = generated;
            afterLevelSwap();
            setStatus(level.isChunked()
                    ? "Generated GIANT \"" + level.name + "\" (" + level.width + "x"
                    + level.height + ", seed " + genSeed
                    + ") — chunks generate as you explore"
                    : "Generated \"" + level.name + "\" (" + level.width + "x" + level.height
                    + ", seed " + genSeed + ") — caves, rooms, ores, liquids, spawns");
        });
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /**
     * The door-list manager: pick an entry (or "new door"), edit its label /
     * target level / colour, save or delete. The list itself lives in the
     * game type's external {@code doors.json}.
     */
    private void buildDoorsForm() {
        List<DoorLink> links = doors.all();
        doorEditIndex = Math.min(doorEditIndex, links.size());
        List<String> options = new ArrayList<>();
        options.add("(new door)");
        for (DoorLink l : links) options.add(l.key());

        LevelStore store = new LevelStore(profile().name);
        List<String> targets = new ArrayList<>();
        targets.add("(none)");
        targets.addAll(store.list());

        DoorLink editing = doorEditIndex > 0 && doorEditIndex <= links.size()
                ? links.get(doorEditIndex - 1) : null;
        doorLabel = editing != null ? editing.label() : "New Door";
        doorTargetIndex = 0;
        if (editing != null) {
            int i = targets.indexOf(editing.targetLevel());
            doorTargetIndex = Math.max(0, i);
        }
        doorColorIndex = 0;
        if (editing != null) {
            for (int i = 0; i < DOOR_COLORS.length; i++) {
                if (DOOR_COLORS[i].equals(editing.color())) doorColorIndex = i;
            }
        }

        dialogForm.addEnum("Door", options.toArray(new String[0]),
                () -> options.get(Math.min(doorEditIndex, options.size() - 1)),
                v -> {
                    doorEditIndex = Math.max(0, options.indexOf(v));
                    // Re-open so the fields below reload for the picked door.
                    openDialog(Dialog.DOORS);
                });
        dialogForm.addText("Label", () -> doorLabel, v -> doorLabel = v, 28);
        dialogForm.addEnum("Target level", targets.toArray(new String[0]),
                () -> targets.get(Math.min(doorTargetIndex, targets.size() - 1)),
                v -> doorTargetIndex = Math.max(0, targets.indexOf(v)));
        dialogForm.addEnum("Colour", DOOR_COLOR_NAMES,
                () -> DOOR_COLOR_NAMES[doorColorIndex],
                v -> {
                    for (int i = 0; i < DOOR_COLOR_NAMES.length; i++) {
                        if (DOOR_COLOR_NAMES[i].equals(v)) doorColorIndex = i;
                    }
                });
        dialogForm.addAction(editing == null ? "Add Door" : "Save Door", () -> {
            String key = editing != null ? editing.key() : doors.freshKey();
            String target = doorTargetIndex > 0 ? targets.get(doorTargetIndex) : "";
            recordDoors();
            doors.put(new DoorLink(key, doorLabel.isBlank() ? key : doorLabel.trim(),
                    target, DOOR_COLORS[doorColorIndex]));
            buildPalette();
            closeDialog();
            setStatus("Door \"" + doorLabel + "\" saved to " + doors.file());
        });
        if (editing != null) {
            dialogForm.addAction("Delete Door", () -> {
                recordDoors();
                doors.remove(editing.key());
                buildPalette();
                doorEditIndex = 0;
                closeDialog();
                setStatus("Door \"" + editing.label() + "\" removed from the directory"
                        + " · [Ctrl+Z] puts it back");
            });
        }
        dialogForm.addAction("Close", this::closeDialog);
    }

    private static final Color[] DOOR_COLORS = {
            new Color(150, 105, 60), new Color(120, 120, 135), new Color(180, 60, 60),
            new Color(70, 130, 200), new Color(90, 160, 90), new Color(200, 170, 70),
            new Color(140, 90, 190),
    };
    private static final String[] DOOR_COLOR_NAMES =
            {"Wood", "Iron", "Red", "Blue", "Green", "Gold", "Purple"};

    // --- texture overrides -----------------------------------------------------------

    /** The Skins texture key for the dialog's entry + selected action state. */
    /** "(every direction)" — the texture dialog's non-directional choice. */
    private static final String ALL_DIRECTIONS = "(every direction)";

    /** The direction row's choices: one sheet for all, or a compass point. */
    private static String[] directionChoices() {
        String[] out = new String[Facing.values().length + 1];
        out[0] = ALL_DIRECTIONS;
        for (Facing f : Facing.values()) out[f.ordinal() + 1] = f.label();
        return out;
    }

    /** The facing the dialog is assigning, or {@code null} for "every". */
    private Facing texFacing() {
        return texDirIndex <= 0 || texDirIndex > Facing.values().length
                ? null : Facing.values()[texDirIndex - 1];
    }

    private String textureKey() {
        String state = texStates.get(Math.min(texStateIndex, texStates.size() - 1));
        Facing dir = directional(texEntry.kind) ? texFacing() : null;
        return textureKey(texEntry.kind, texEntry.key, state, dir);
    }

    /**
     * The {@link Skins} key one object's sheet is filed under, for a palette
     * kind, the object's key, the action state or block face being drawn, and
     * the facing (null = the one sheet every direction uses).
     */
    private static String textureKey(String kind, String key, String state, Facing dir) {
        String suffix = dir == null ? "" : "/" + dir.key();
        return switch (kind) {
            case "mob" -> "mob/" + key + "/" + state + suffix;
            case "item" -> itemTextureKey(key, state, dir);
            case "decor" -> "decor/" + key;
            case "surface" -> "surface/" + key;
            case "playerskin" -> PlayerSprites.stateKey(state) + suffix;
            case "character" -> PlayerSprites.characterStateKey(key, state) + suffix;
            case "particle" -> Particles.TEXTURE_NAMESPACE + "/" + key;
            case "projectile" -> "projectile/" + key;
            // "flat" is the block's one sheet; the other two are the plan-view
            // faces, which live in their own pools and fall back to it.
            default -> "flat".equals(state) ? "block/" + key : "block/" + key + "/" + state;
        };
    }

    /**
     * Where one of an item's sheets is filed: its plain icon, the object's own
     * art for a melee move ({@code item/<key>/<move>}), or a full-body sheet of
     * the fighter holding it ({@code wield/<key>/<move>}, which the dialog
     * calls "wielder …" and which may be split by facing like a character).
     */
    private static String itemTextureKey(String key, String state, Facing dir) {
        if (state == null || "default".equals(state)) return "item/" + key;
        if (state.startsWith("wielder_")) {
            return MeleeSprites.wieldKey(key, state.substring("wielder_".length()), dir);
        }
        return MeleeSprites.heldKey(key, state);
    }

    /**
     * The key an object's <em>first</em> sheet goes under — the one a creator
     * who just made the object should draw: the flat face of a block, the idle
     * pose of anything animated, the single sheet of everything else.
     */
    private static String defaultTextureKey(String kind, String key) {
        String state = switch (kind) {
            case "block" -> "flat";
            case "mob", "character", "playerskin" -> "idle";
            default -> "default";
        };
        return textureKey(kind, key, state, null);
    }

    /**
     * Fill the dialog's fields for the selected key: the texture pack switch
     * as this object has it (on unless it was turned off), the sheet path the
     * creator picked (if any), and the frame settings the texture actually
     * plays at right now — the pack's universal spec for a pack texture, this
     * object's exception when it has one.
     */
    private void loadTextureFields() {
        String key = textureKey();
        SkinDef stored = Skins.get(key);
        SkinDef showing = Skins.effective(key);
        texUsePack = stored == null || stored.usePack;
        texSheet = stored != null ? stored.sheet : "";
        SkinDef frames = showing != null ? showing : stored;
        if (frames != null) {
            texW = String.valueOf(frames.frameWidth);
            texH = String.valueOf(frames.frameHeight);
            texCount = String.valueOf(frames.frameCount);
            texFps = trimNumber(frames.fps);
        } else if (texUsePack) {
            TexturePack.Frames f = TexturePack.framesFor(key);
            texW = String.valueOf(f.width());
            texH = String.valueOf(f.height());
            texCount = String.valueOf(f.count());
            texFps = trimNumber(f.fps());
        } else {
            texW = texH = "32";
            texCount = "1";
            texFps = "0";
        }
    }

    /**
     * Assign a sprite sheet to the right-clicked palette entry. Mobs pick an
     * action state (idle/walk/attack/hurt); one sheet per state, and the
     * renderer falls back to idle for states without one.
     *
     * <p>Two ways to supply the art, and the first is the default:
     * <ul>
     *   <li><b>Texture pack folder</b> (on unless switched off) — the sheet is
     *       whatever sits at this object's file name inside the drop-in
     *       {@link TexturePack} folder. Nothing there? The built-in icon
     *       stands, so the switch is safe to leave on for everything. The
     *       frame fields set this texture's exception to the pack's universal
     *       size/length/rate and are saved into the pack itself.</li>
     *   <li><b>A sheet elsewhere</b> — switch the pack off (or just fill in a
     *       path, used as the pack's fallback) to point this one object at any
     *       image on disk.</li>
     * </ul>
     *
     * <p>Either way it applies live via {@link Skins}, persists to
     * {@code skins.json}, and the palette swatch redraws with the new look.
     */
    private void buildTextureForm() {
        String key = textureKey();
        if (texStates.size() > 1) {
            dialogForm.addEnum(texStateLabel(), texStates.toArray(new String[0]),
                    () -> texStates.get(texStateIndex),
                    v -> {
                        texStateIndex = Math.max(0, texStates.indexOf(v));
                        loadTextureFields();
                        openDialog(Dialog.TEXTURE); // the pack file row follows the state
                    });
        }
        if ("block".equals(texEntry.kind)) {
            dialogForm.addNote(blockFaceNote());
        }
        // Directional objects can have one sheet per facing. Leaving this on
        // "(every direction)" is the normal case: that sheet draws whichever
        // way the character turns, mirrored for the westward facings.
        if (directional(texEntry.kind)) {
            String[] dirs = directionChoices();
            dialogForm.addEnum("Facing", dirs,
                    () -> dirs[Math.min(texDirIndex, dirs.length - 1)],
                    v -> {
                        texDirIndex = Math.max(0, List.of(dirs).indexOf(v));
                        loadTextureFields();
                        openDialog(Dialog.TEXTURE);
                    });
        }
        dialogForm.addToggle("Use texture pack folder", () -> texUsePack, v -> {
            texUsePack = v;
            openDialog(Dialog.TEXTURE);
        });
        // Name the file this object wants, so a creator can go make it — and
        // say whether it is there yet. Clicking re-scans, for sheets dropped
        // in while the game was running.
        Path packFile = TexturePack.fileFor(key);
        dialogForm.addAction(TexturePack.fileNameFor(key)
                + (packFile != null ? "  ✓ found" : "  — rescan"),
                this::rescanTexturePack).enabledWhen(() -> texUsePack);
        // Draw the sheet here instead of going and finding a paint program.
        // Available for every object, built-in or custom — what comes out is
        // a file in the pack like any other.
        dialogForm.addAction(packFile != null
                        ? "✎ Create texture — edit this sheet…"
                        : "✎ Create texture — draw it here…",
                () -> openSpriteEditor(key, texEntry.name));
        // Where that folder is: blank = beside the jar (share/textures in the
        // IDE), which is what a pack shipped with the game uses.
        dialogForm.addNote("Leave the pack folder blank to use the one beside "
                + "the jar. A sheet path points this one object anywhere on disk.");
        dialogForm.addText("Texture pack folder",
                () -> profile().texturePackDir,
                v -> {
                    profile().texturePackDir = v;
                    TexturePack.useDir(v);
                }, 96);
        dialogForm.addText("Sheet elsewhere (PNG)", () -> texSheet, v -> texSheet = v, 96);
        dialogForm.addAction("Browse…", this::browseForSheet);
        dialogForm.addText("Frame width px", () -> texW, v -> texW = v, 4);
        dialogForm.addText("Frame height px", () -> texH, v -> texH = v, 4);
        dialogForm.addText("Frame count", () -> texCount, v -> texCount = v, 3);
        dialogForm.addText("FPS (0 = static)", () -> texFps, v -> texFps = v, 5);
        dialogForm.addAction("Apply Texture", this::applyTexture);
        if (Skins.get(key) != null || TexturePack.hasOverride(key)) {
            dialogForm.addAction("Reset to defaults", () -> {
                Skins.remove(key);
                TexturePack.clearOverride(key);
                persistSkins();
                buildPalette();
                closeDialog();
                setStatus(texEntry.name + " reset — texture pack on,"
                        + " built-in art as the fallback");
            });
        }
        // User-created objects are deletable right from their entry's dialog.
        if (texEntry != null && texEntry.custom) {
            dialogForm.addAction("DELETE this custom object", this::deleteCustomEntry);
        }
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /**
     * Save the dialog: the frame settings go to the texture pack (as this
     * key's exception to the universal spec) when the pack supplies the art,
     * and the switch plus any explicit sheet path go to {@code skins.json}.
     */
    private void applyTexture() {
        String key = textureKey();
        int w = parseInt(texW, TexturePack.DEFAULT_FRAME_SIZE);
        int h = parseInt(texH, TexturePack.DEFAULT_FRAME_SIZE);
        int count = parseInt(texCount, 1);
        double fps = parseDouble(texFps);
        String sheet = texSheet.isBlank() ? "" : resolveSheetPath(texSheet.trim());
        if (!texUsePack && sheet.isEmpty()) {
            setStatus("Turn the texture pack back on, or pick a sheet (path or Browse…)");
            return;
        }
        ctx.save(); // the texture pack folder persists with the game type
        recordSkin(key); // what this object was drawn with, before the change
        if (texUsePack) {
            try {
                TexturePack.setOverride(key, w, h, count, fps);
            } catch (RuntimeException e) {
                setStatus("Couldn't write the texture pack settings: " + e.getMessage());
                return;
            }
        }
        Skins.put(new SkinDef(key, sheet, w, h, count, fps, texUsePack));
        persistSkins();
        buildPalette(); // the palette swatch shows the texture that now applies
        closeDialog();
        SkinDef showing = Skins.effective(key);
        if (showing == null) {
            setStatus(texEntry.name + " keeps its built-in art — drop "
                    + TexturePack.fileNameFor(key) + " in " + TexturePack.root()
                    + " to reskin it");
        } else {
            setStatus(texEntry.name + " now uses " + showing.sheet + " ("
                    + showing.frameCount + " frames @ " + trimNumber(showing.fps) + " fps)");
        }
    }

    // --- "Create texture": drawing a sheet in game -----------------------------------

    /**
     * Open the sprite-sheet editor on a texture key. Whatever already draws
     * that key is what opens — the pack's sheet, or an assigned one — so
     * "Create texture" on an object that has art is an <em>edit</em>, and on
     * one that hasn't is a blank canvas at the pack's frame size.
     *
     * <p>The window floats over the dialog that opened it and owns the input
     * while it is up; saving files the sheet into the texture pack under this
     * key's own file name (see {@link #saveSpriteSheet}).
     */
    private void openSpriteEditor(String key, String label) {
        TexturePack.Frames packFrames = TexturePack.framesFor(key);
        SkinDef showing = Skins.effective(key);
        int w = showing != null ? showing.frameWidth : packFrames.width();
        int h = showing != null ? showing.frameHeight : packFrames.height();
        double fps = showing != null ? showing.fps : packFrames.fps();
        // Beyond the canvas's limit this would open the sheet sliced at the
        // wrong size, which is worse than saying so.
        if (Math.max(w, h) > SpriteCanvas.MAX_SIZE) {
            setStatus(label + "'s frames are " + w + "x" + h + " — bigger than the "
                    + SpriteCanvas.MAX_SIZE + " px the texture editor draws at."
                    + " Edit that sheet in a paint program, or lower its frame size here first.");
            return;
        }
        SpriteCanvas canvas = showing == null ? null
                : SpriteCanvas.load(showing.sheet, w, h, showing.frameCount, fps);
        // Nothing to edit: one blank frame, and the creator adds the rest.
        if (canvas == null) canvas = new SpriteCanvas(w, h, fps);

        spriteKey = key;
        spriteLabel = label;
        spriteEditor = new SpriteEditorPanel("Create Texture — " + label,
                TexturePack.fileNameFor(key), canvas);
        spriteEditor.onSave(c -> saveSpriteSheet(key, label, c));
        spriteEditor.onCancel(() -> {
            spriteEditor = null;
            setStatus(spriteLabel + " left as it was — nothing written to "
                    + TexturePack.root().resolve(TexturePack.fileNameFor(spriteKey)));
        });
        spriteEditor.setStatus(canvas.frameCount() > 1
                ? "Editing " + TexturePack.fileNameFor(key) + " — "
                + canvas.frameCount() + " frames"
                : "Draw the first frame, then \"+ Frame\" to carry it forward and animate");
    }

    /**
     * Save the drawn sheet <em>into the texture pack</em>: written under the
     * file name this key is looked up by, with the frame size/length/rate it
     * was drawn at recorded as this texture's exception to the pack's
     * universal spec. That is all it takes for the object to draw with it —
     * and because it is now a file in the pack folder, it travels with the
     * game like any hand-made sheet.
     */
    private void saveSpriteSheet(String key, String label, SpriteCanvas canvas) {
        Path file;
        try {
            file = TexturePack.writeSheet(key, canvas.toSheet());
            TexturePack.setOverride(key, canvas.width(), canvas.height(),
                    canvas.frameCount(), canvas.fps());
        } catch (IOException | RuntimeException e) {
            // The window stays open, so a failed write never loses the drawing.
            spriteEditor.setStatus("Couldn't write into " + TexturePack.root()
                    + ": " + e.getMessage());
            return;
        }
        // An object explicitly told to ignore the pack would not show what was
        // just drawn for it, so drawing a texture turns its pack switch on.
        SkinDef stored = Skins.get(key);
        if (stored != null && !stored.usePack) {
            Skins.put(new SkinDef(key, stored.sheet, canvas.width(), canvas.height(),
                    canvas.frameCount(), canvas.fps(), true));
            persistSkins();
        }
        canvas.markSaved();
        Skins.clearCache();
        buildPalette();
        spriteEditor = null;
        if (dialog == Dialog.TEXTURE) {
            loadTextureFields();
            openDialog(Dialog.TEXTURE); // the pack file row now reads "found"
        }
        setStatus(label + " now draws from " + file + " — " + canvas.frameCount()
                + " frame" + (canvas.frameCount() == 1 ? "" : "s") + " of "
                + canvas.width() + "x" + canvas.height() + " @ "
                + trimNumber(canvas.fps()) + " fps, saved into the texture pack");
    }

    /** Pick up sheets added to the texture pack folder since the last look. */
    private void rescanTexturePack() {
        AssetLoader.clearCache(); // a path cached as missing may exist now
        TexturePack.reload();
        loadTextureFields();
        buildPalette();
        openDialog(Dialog.TEXTURE); // the pack file row re-reads the folder
        boolean found = TexturePack.fileFor(textureKey()) != null;
        setStatus(found
                ? "Found " + TexturePack.fileNameFor(textureKey()) + " in " + TexturePack.root()
                : "No " + TexturePack.fileNameFor(textureKey()) + " in " + TexturePack.root()
                        + " — see " + TexturePack.KEYS_FILE + " for every file name");
    }

    // --- sound editor ---------------------------------------------------------------

    /**
     * The Sound Editor: <em>every place the game makes a noise</em>, as one
     * long list. A row per object and action state — the player jumping, Dirt
     * breaking, a Slime's death cry, a meteor's flight, the level's music —
     * saying where that sound currently comes from, and opening the dialog
     * that lets a creator supply it.
     *
     * <p>The list is driven by {@link SoundKeys}, which reads the live
     * registries, so blocks, mobs, items, decorations and characters made
     * with a "+ New …" button are in here the moment they exist, with the
     * same full set of action states as the built-ins.
     *
     * @param category one of {@link SoundKeys#categories()}, or {@code ""}
     *                 for every sound in the game at once
     */
    private void openSoundList(String category) {
        soundCategory = category == null ? "" : category;
        refreshSoundRows();
        openDialog(Dialog.SOUNDS);
    }

    /**
     * Re-read the catalogue for the current group and filter. "No audio yet"
     * means <em>nothing the creator supplied</em> — a key still riding the
     * engine's built-in voice counts, because that is exactly the list of
     * sounds a pack has left to fill.
     */
    private void refreshSoundRows() {
        List<SoundKeys.Entry> rows = new ArrayList<>();
        for (SoundKeys.Entry e : SoundKeys.inCategory(soundCategory)) {
            Sounds.Source source = Sounds.sourceOf(e.key());
            boolean supplied = source == Sounds.Source.PACK || source == Sounds.Source.FILE;
            if (soundFilter == 1 && supplied) continue;
            if (soundFilter == 2 && source == Sounds.Source.SILENT) continue;
            rows.add(e);
        }
        soundRows = rows;
    }

    /**
     * The "where is the pack, and is it there?" row plus its rescan — the
     * same pair in every sound window, so they can't drift apart.
     */
    private void addSoundPackRows(boolean withFolderField) {
        if (withFolderField) {
            dialogForm.addNote("Leave the pack folder blank to use the one "
                    + "beside the jar.");
            dialogForm.addText("Sound pack folder",
                    () -> profile().soundPackDir,
                    v -> {
                        profile().soundPackDir = v;
                        SoundPack.useDir(v);
                    }, 96);
        }
        // The folder is a path, and paths are long, so where it is wraps as a
        // note; the buttons under it stay short because they are buttons.
        dialogForm.addNote("Pack folder: " + SoundPack.root()
                + (SoundPack.exists() ? "  ✓" : "  (not there yet)"));
        dialogForm.addAction("Create / refresh the pack folder", this::createSoundPackFolder);
        dialogForm.addAction("Rescan sound pack folder", this::rescanSoundPack);
    }

    /** The fresh-pitch toggle, saved with the game type wherever it appears. */
    private void addPitchToggle() {
        dialogForm.addToggle("Fresh pitch each time (subtle drift)",
                () -> profile().soundPitchVariation,
                v -> {
                    profile().soundPitchVariation = v;
                    Sounds.setPitchVariation(v);
                    ctx.save();
                });
    }

    /** The "Pack file: …  ✓ found / not there yet" row for one sound. */
    private void addPackFileRow(String key, java.util.function.BooleanSupplier enabled) {
        ConfigForm.Option row = dialogForm.addAction(SoundPack.fileNameFor(key)
                        + (SoundPack.fileFor(key) != null ? "  ✓ found" : "  — rescan"),
                this::rescanSoundPack);
        if (enabled != null) row.enabledWhen(enabled);
    }

    private static final String[] SOUND_FILTERS =
            {"all of them", "only the ones with no audio yet", "only the ones that sound"};

    /** The one-line status a list row shows: where its audio comes from. */
    private static String soundSourceLabel(String key) {
        return switch (Sounds.sourceOf(key)) {
            case PACK -> "pack: " + SoundPack.fileNameFor(key);
            case FILE -> "file: " + Sounds.definition(key).file();
            case BUILT_IN -> "built-in";
            case SILENT -> "silent";
        };
    }

    private void buildSoundListForm() {
        // The group and filter rows sit at the top, so a creator can walk the
        // whole game or narrow to what is still silent without leaving.
        List<String> groups = new ArrayList<>();
        groups.add("(every sound in the game)");
        groups.addAll(SoundKeys.categories());
        String[] groupNames = groups.toArray(new String[0]);
        dialogForm.addEnum("Show group", groupNames,
                () -> soundCategory.isEmpty() ? groupNames[0] : soundCategory,
                v -> {
                    soundCategory = v.equals(groupNames[0]) ? "" : v;
                    refreshSoundRows();
                    openDialog(Dialog.SOUNDS);
                });
        dialogForm.addEnum("Show sounds", SOUND_FILTERS,
                () -> SOUND_FILTERS[soundFilter],
                v -> {
                    soundFilter = Math.max(0, List.of(SOUND_FILTERS).indexOf(v));
                    refreshSoundRows();
                    openDialog(Dialog.SOUNDS);
                });
        // The pitch option lives here as well as in Sound Options, because it
        // is the setting a creator most often wants while auditioning sounds.
        addPitchToggle();
        addSoundPackRows(false);
        dialogForm.addAction("Sound Options…", () -> openDialog(Dialog.SOUND_OPTIONS));

        if (soundRows.isEmpty()) {
            dialogForm.addAction("(no sounds match this filter)", () -> {
                soundFilter = 0;
                refreshSoundRows();
                openDialog(Dialog.SOUNDS);
            });
        }
        // A breakdown by where the audio comes from, because "has a sound"
        // and "you supplied a sound" are different questions: the built-in
        // ones are the engine's, and the pack ones are the creator's.
        int fromPack = 0;
        int fromFile = 0;
        int builtIn = 0;
        for (SoundKeys.Entry e : soundRows) {
            switch (Sounds.sourceOf(e.key())) {
                case PACK -> fromPack++;
                case FILE -> fromFile++;
                case BUILT_IN -> builtIn++;
                case SILENT -> { /* counted by subtraction below */ }
            }
        }
        int silent = soundRows.size() - fromPack - fromFile - builtIn;
        dialogForm.addNote(soundRows.size() + " sounds · " + (fromPack + fromFile)
                + " yours · " + builtIn + " built-in · " + silent + " silent");
        for (SoundKeys.Entry e : soundRows) {
            String key = e.key();
            String label = e.name() + (e.state().isEmpty() ? "" : " — " + e.state())
                    + "   ·   " + soundSourceLabel(key);
            dialogForm.addAction(label, () -> openSoundDialog(key, e.name()
                    + (e.state().isEmpty() ? "" : " — " + e.state())));
        }
        dialogForm.addAction("Close", this::closeDialog);
    }

    /** Open the per-sound dialog for one key, loading its current settings. */
    private void openSoundDialog(String key, String label) {
        soundKey = key;
        soundLabel = label;
        SoundDef def = Sounds.definition(key);
        sndUsePack = def.usePack();
        sndFile = def.file();
        sndVolume = trimNumber(def.volume());
        sndPitch = trimNumber(def.pitch());
        sndLoop = def.loop();
        sndVary = def.varyPitch();
        sndBuiltin = def.builtin();
        openDialog(Dialog.SOUND);
    }

    /**
     * Give one action state its audio. Same two routes as a texture, and the
     * first is the default:
     * <ul>
     *   <li><b>Sound pack folder</b> (on unless switched off) — the audio is
     *       whatever sits at this sound's file name inside the drop-in
     *       {@link SoundPack} folder. Nothing there? Silence, or the engine's
     *       built-in voice for the few actions that have one — so the switch
     *       is safe to leave on for everything.</li>
     *   <li><b>A file elsewhere</b> — switch the pack off (or just fill in a
     *       path, used as the pack's fallback) to point this one sound at any
     *       WAV or MP3 on disk.</li>
     * </ul>
     *
     * <p>Volume, pitch and looping are saved into the pack's own
     * {@code soundpack.json}, so those exceptions travel with the folder; the
     * switch and any explicit path go to {@code sounds.json}.
     */
    private void buildSoundForm() {
        String key = soundKey;
        dialogForm.addNote("Sound key: " + key);
        dialogForm.addToggle("Use sound pack folder", () -> sndUsePack, v -> {
            sndUsePack = v;
            openDialog(Dialog.SOUND);
        });
        addPackFileRow(key, () -> sndUsePack);
        dialogForm.addNote("Leave the pack folder blank to use the one beside "
                + "the jar. A file path points this one sound anywhere on disk.");
        dialogForm.addText("Sound pack folder",
                () -> profile().soundPackDir,
                v -> {
                    profile().soundPackDir = v;
                    SoundPack.useDir(v);
                }, 96);
        dialogForm.addText("Sound file (WAV/MP3)",
                () -> sndFile, v -> sndFile = v, 96);
        dialogForm.addAction("Browse…", this::browseForSound);
        dialogForm.addText("Volume (1 = as recorded)", () -> sndVolume, v -> sndVolume = v, 5);
        dialogForm.addText("Pitch (1 = as recorded)", () -> sndPitch, v -> sndPitch = v, 5);
        dialogForm.addToggle("Loop while the state holds", () -> sndLoop, v -> sndLoop = v);
        dialogForm.addToggle("Fresh pitch each time", () -> sndVary, v -> sndVary = v);
        if (SoundSynth.hasFallback(key)) {
            dialogForm.addToggle("Built-in fallback when the pack has nothing",
                    () -> sndBuiltin, v -> sndBuiltin = v);
        }
        dialogForm.addAction("▶ Preview", this::previewSound);
        dialogForm.addAction("Apply", this::applySound);
        if (Sounds.get(key) != null || SoundPack.hasOverride(key)) {
            dialogForm.addAction("Reset to default", () -> {
                Sounds.remove(key);
                SoundPack.clearOverride(key);
                Sounds.save();
                refreshSoundRows();
                closeDialog();
                setStatus(soundLabel + " reset — sound pack on, "
                        + (SoundSynth.hasFallback(key) ? "built-in voice" : "silence")
                        + " as the fallback");
            });
        }
        dialogForm.addAction("Back to the list", () -> openDialog(Dialog.SOUNDS));
        dialogForm.addAction("Close", this::closeDialog);
    }

    /** Save the per-sound dialog and say what the sound now resolves to. */
    private void applySound() {
        String key = soundKey;
        double volume = parseDouble(sndVolume);
        double pitch = parseDouble(sndPitch);
        if (pitch <= 0) pitch = 1;
        String file = sndFile.isBlank() ? "" : sndFile.trim();
        if (!sndUsePack && file.isEmpty()) {
            setStatus("Turn the sound pack back on, or pick a file (path or Browse…)");
            return;
        }
        ctx.save(); // the sound pack folder persists with the game type
        recordSound(key); // what this key played, before the change
        try {
            SoundPack.setOverride(key, volume, pitch, sndLoop, sndVary);
        } catch (RuntimeException e) {
            setStatus("Couldn't write the sound pack settings: " + e.getMessage());
            return;
        }
        Sounds.put(new SoundDef(key, file, volume, pitch, sndLoop, sndVary,
                sndUsePack, sndBuiltin));
        Sounds.save();
        refreshSoundRows();
        closeDialog();
        setStatus(switch (Sounds.sourceOf(key)) {
            case PACK -> soundLabel + " now plays " + SoundPack.fileFor(key);
            case FILE -> soundLabel + " now plays " + Sounds.resolvePath(file);
            case BUILT_IN -> soundLabel + " keeps its built-in voice — drop "
                    + SoundPack.fileNameFor(key) + " in " + SoundPack.root() + " to replace it";
            case SILENT -> soundLabel + " is silent — drop "
                    + SoundPack.fileNameFor(key) + " in " + SoundPack.root() + " to give it a sound";
        });
    }

    /** Play the sound as it stands, so a creator can hear the change. */
    private void previewSound() {
        String key = soundKey;
        // Preview what the dialog says right now, not what was last applied.
        SoundDef preview = new SoundDef(key, sndFile.trim(), parseDouble(sndVolume),
                Math.max(0.25, parseDouble(sndPitch)), false, sndVary, sndUsePack, sndBuiltin);
        SoundDef saved = Sounds.get(key);
        Sounds.put(preview);
        boolean audible = !Sounds.resolve(key).isEmpty();
        if (audible) Sounds.play(key);
        if (saved != null) Sounds.put(saved);
        else Sounds.remove(key);
        setStatus(audible ? "Playing " + soundLabel
                : soundLabel + " is silent — no " + SoundPack.fileNameFor(key)
                        + " in " + SoundPack.root());
    }

    /** Pick up audio added to the sound pack folder since the last look. */
    private void rescanSoundPack() {
        Dialog from = dialog;
        SoundPack.reload();
        refreshSoundRows();
        buildPalette();
        openDialog(from == Dialog.NONE ? Dialog.SOUNDS : from);
        if (from == Dialog.SOUND) {
            boolean found = SoundPack.fileFor(soundKey) != null;
            setStatus(found
                    ? "Found " + SoundPack.fileNameFor(soundKey) + " in " + SoundPack.root()
                    : "No " + SoundPack.fileNameFor(soundKey) + " in " + SoundPack.root()
                            + " — see " + SoundPack.KEYS_FILE + " for every file name");
        } else {
            int fromPack = 0;
            for (SoundKeys.Entry e : soundRows) {
                if (Sounds.sourceOf(e.key()) == Sounds.Source.PACK) fromPack++;
            }
            setStatus("Rescanned " + SoundPack.root() + " — " + fromPack + " of "
                    + soundRows.size() + " listed sounds have a file there");
        }
    }

    /**
     * Create the sound pack folder (with its subfolders, README and generated
     * key list) wherever the game is currently looking for it — the one-click
     * way to get from "no sounds" to "somewhere to put them".
     */
    private void createSoundPackFolder() {
        try {
            Path root = SoundPack.scaffold(SoundPack.root());
            SoundPack.reload();
            refreshSoundRows();
            openDialog(dialog);
            setStatus("Sound pack ready at " + root + " — see " + SoundPack.KEYS_FILE
                    + " for every file name, then drop WAVs or MP3s in");
        } catch (IOException | RuntimeException e) {
            setStatus("Couldn't create " + SoundPack.root() + ": " + e.getMessage());
        }
    }

    /** Rewrite SOUND_KEYS.txt so it lists the creator's newest objects too. */
    private void refreshSoundKeyList() {
        try {
            Path file = SoundPack.refreshKeyList();
            setStatus("Wrote " + file + " — " + SoundKeys.all().size()
                    + " sounds, including everything you have created");
        } catch (IOException | RuntimeException e) {
            setStatus("Couldn't write the key list: " + e.getMessage());
        }
    }

    /** A file chooser for a sound, starting in the sound pack folder. */
    private void browseForSound() {
        String picked = chooseFile("Choose a sound (WAV or MP3)",
                SoundPack.root().toString(), "Audio files", "wav", "mp3", "aiff", "aif", "au");
        if (picked != null) {
            sndFile = picked;
            openDialog(Dialog.SOUND);
            setStatus("Selected " + picked + " — press Apply to use it");
        }
    }

    /**
     * The global sound settings: the master switches, the three volumes, and
     * the fresh-pitch option with the spread it drifts by.
     */
    private void buildSoundOptionsForm() {
        dialogForm.addToggle("Sound", () -> profile().audioEnabled, v -> {
            profile().audioEnabled = v;
            ctx.applyLiveSettings();
            ctx.save();
        });
        dialogForm.addToggle("Music", () -> profile().musicEnabled, v -> {
            profile().musicEnabled = v;
            ctx.applyLiveSettings();
            ctx.save();
        });
        dialogForm.addSlider("Master volume %", () -> percent(profile().masterVolume),
                v -> {
                    profile().masterVolume = v / 100.0;
                    Sounds.setMasterVolume(profile().masterVolume);
                }, 0, 100);
        dialogForm.addSlider("Effects volume %", () -> percent(profile().sfxVolume),
                v -> {
                    profile().sfxVolume = v / 100.0;
                    Sounds.setSfxVolume(profile().sfxVolume);
                }, 0, 100);
        dialogForm.addSlider("Music volume %", () -> percent(profile().musicVolume),
                v -> {
                    profile().musicVolume = v / 100.0;
                    Sounds.setMusicVolume(profile().musicVolume);
                }, 0, 100);
        // The pitch toggle the whole system hangs off: on, every sound plays
        // a touch higher or lower each time, so repeated sounds stay fresh
        // instead of turning into a stuck record.
        addPitchToggle();
        dialogForm.addSlider("Pitch drift ± %",
                () -> (int) Math.round(SoundPack.pitchVariation() * 100),
                v -> Sounds.setPitchVariationAmount(v / 100.0),
                0, (int) Math.round(SoundPack.MAX_PITCH_VARIATION * 100))
                .enabledWhen(() -> profile().soundPitchVariation);
        addSoundPackRows(true);
        // What the sound system is actually doing right now — the quickest
        // answer to "is it even working?" on a machine with no audio device.
        dialogForm.addNote(Sounds.mixer().isAvailable()
                ? "Audio device ready · " + Sounds.mixer().activeVoices()
                + " sounds playing · " + SoundLoader.cachedCount() + " loaded"
                : "No audio device on this machine — the game runs silent.");
        dialogForm.addAction("Rewrite " + SoundPack.KEYS_FILE, this::refreshSoundKeyList);
        dialogForm.addNote("That list names every sound the game can make, your "
                + "custom objects included.");
        dialogForm.addAction("Sound Editor… (" + SoundKeys.all().size() + " sounds)",
                () -> openSoundList(soundCategory));
        dialogForm.addAction("Save & close", () -> {
            ctx.applyLiveSettings();
            ctx.save();
            closeDialog();
            setStatus("Sound settings saved with the game type");
        });
    }

    private static int percent(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 100);
    }

    /**
     * The level's music track. Music is a sound state like any other, so this
     * just picks which {@code music/…} key this level asks for; the pack
     * supplies the file.
     */
    private void buildLevelMusicForm() {
        List<String> tracks = new ArrayList<>();
        tracks.add("(the generic level track)");
        tracks.addAll(SoundKeys.MUSIC_TRACKS);
        String[] names = tracks.toArray(new String[0]);
        dialogForm.addEnum("Track", names,
                () -> musicTrack.isBlank() ? names[0] : musicTrack,
                v -> musicTrack = v.equals(names[0]) ? "" : v);
        dialogForm.addText("…or your own track name", () -> musicTrack,
                v -> musicTrack = v, 32);
        String key = musicTrack.isBlank() ? SoundKeys.music("level")
                : SoundKeys.music(musicTrack.trim());
        addPackFileRow(key, null);
        dialogForm.addAction("▶ Preview", () -> {
            Sounds.music(key);
            setStatus(Sounds.resolve(key).isEmpty()
                    ? "No " + SoundPack.fileNameFor(key) + " in " + SoundPack.root()
                    : "Playing " + key);
        });
        dialogForm.addAction("■ Stop", () -> {
            Sounds.stopMusic();
            setStatus("Music stopped");
        });
        dialogForm.addAction("Edit this track's volume and looping…",
                () -> openSoundDialog(key, "Music — "
                        + (musicTrack.isBlank() ? "level" : musicTrack)));
        dialogForm.addAction("Apply to this level", () -> {
            level.music = musicTrack.trim();
            closeDialog();
            setStatus("\"" + level.name + "\" plays " + level.musicKey()
                    + " — save the level to keep it");
        });
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /** Delete the right-clicked user-created object (custom.json + registries). */
    private void deleteCustomEntry() {
        String kind = texEntry.kind, key = texEntry.key;
        // What it was, so Ctrl+Z can register it again exactly as it stood.
        Runnable restore = customRestorer(kind, key);
        if ("character".equals(kind)) {
            if (characterStore.remove(key)) {
                level.characters.remove(key);
                recordCustomObject(kind, key, restore);
                afterCustomChange();
                closeDialog();
                setStatus("Deleted the " + texEntry.name + " character —"
                        + " levels offering it fall back to the rest of the roster"
                        + " · [Ctrl+Z] brings it back");
            } else {
                setStatus("The built-in character can't be deleted");
            }
            return;
        }
        if (customContent.remove(kind, key)) {
            recordCustomObject(kind, key, restore);
            afterCustomChange();
            closeDialog();
            setStatus("Deleted custom " + texEntry.name
                    + " — levels using it show placeholders · [Ctrl+Z] brings it back");
        } else {
            setStatus("Couldn't delete " + texEntry.name);
        }
    }

    /**
     * A one-shot "register this object again" for the object at
     * {@code kind}/{@code key} <em>as it stands now</em> — read before it is
     * deleted, so undoing the deletion is a re-registration of the very same
     * definition rather than a rebuild from the form that made it.
     */
    private Runnable customRestorer(String kind, String key) {
        switch (kind) {
            case "character" -> {
                CharacterProfile c = characterStore.isCustom(key) ? Characters.get(key) : null;
                return c == null ? null : () -> characterStore.add(c);
            }
            case "mob" -> {
                MobDef d = MobRegistry.standard().get(key);
                return d == null ? null : () -> customContent.addMob(d);
            }
            case "item" -> {
                ItemDef d = ItemRegistry.standard().get(key);
                return d == null ? null : () -> customContent.addItem(d);
            }
            case "decor" -> {
                Decor d = DecorRegistry.standard().get(key);
                return d == null ? null : () -> customContent.addDecor(d);
            }
            case "surface" -> {
                SurfaceDecor d = SurfaceDecorRegistry.standard().get(key);
                return d == null ? null : () -> customContent.addSurfaceDecor(d);
            }
            default -> {
                Block b = level.blocks.get(key);
                return b == null ? null : () -> customContent.addBlock(b);
            }
        }
    }

    /**
     * Record a user-created object appearing or disappearing, in whichever
     * direction the action went: {@code register} puts it back into its registry
     * and {@code custom.json}, and the other direction takes it out again. The
     * palette is rebuilt either way, because the object is one of the things it
     * is built from.
     *
     * @param register how to (re-)register the object; {@code null} records
     *                 nothing, which is what a built-in object being "deleted"
     *                 amounts to
     */
    private void recordCustomObject(String kind, String key, Runnable register) {
        if (!history.recording() || register == null) return;
        boolean registered = "character".equals(kind)
                ? characterStore.isCustom(key) : customContent.isCustom(kind, key);
        Runnable remove = () -> {
            if ("character".equals(kind)) characterStore.remove(key);
            else customContent.remove(kind, key);
            afterCustomChange();
        };
        Runnable add = () -> {
            register.run();
            afterCustomChange();
        };
        // Creating an object undoes by removing it; deleting one undoes by
        // putting it back. Same edit, read in the direction the action went.
        history.add(registered ? EditHistory.of(remove, add) : EditHistory.of(add, remove));
    }

    /** Palette bookkeeping after the set of paintable objects changes. */
    private void afterCustomChange() {
        buildPalette();
        for (Category c : Category.values()) {
            List<Entry> entries = palette.get(c);
            if (entries != null && selected.get(c) >= entries.size()) selected.put(c, 0);
        }
        clampScroll();
    }

    /**
     * Resolve a sheet path: as given when it exists (or is bundled), else
     * relative to the texture pack folder — so a bare {@code my_dirt.png}
     * typed into the field finds the sheet sitting in the pack.
     */
    private String resolveSheetPath(String sheet) {
        if (Files.exists(Path.of(sheet))) return sheet;
        Path inPack = TexturePack.root().resolve(sheet);
        return Files.exists(inPack) ? inPack.toString() : sheet;
    }

    private void browseForSheet() {
        String picked = chooseSheetFile();
        if (picked != null) texSheet = picked;
    }

    /** Swing image chooser (texture pack folder first), or {@code null}. */
    private String chooseSheetFile() {
        Path pack = TexturePack.root();
        Path start = Files.isDirectory(pack) ? pack : Path.of(SkinStore.DEFAULT_DIR);
        return chooseFile("Choose a sprite sheet", start.toString(), "Images",
                "png", "gif", "jpg", "jpeg");
    }

    /**
     * A file chooser opening in {@code startDir}, filtered to
     * {@code extensions}. Returns the chosen path, or {@code null} when the
     * creator cancelled — or when no chooser is available at all, in which
     * case the dialog's text field is still there to type into.
     */
    private String chooseFile(String title, String startDir, String filterName,
                              String... extensions) {
        try {
            Path start = Path.of(startDir);
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(
                    (Files.isDirectory(start) ? start : Path.of("."))
                            .toAbsolutePath().toFile());
            chooser.setDialogTitle(title);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    filterName, extensions));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                return chooser.getSelectedFile().getAbsolutePath();
            }
        } catch (RuntimeException | Error e) {
            setStatus("File chooser unavailable — type the path instead");
        }
        return null;
    }

    private void persistSkins() {
        try {
            new SkinStore().save(Skins.all());
        } catch (RuntimeException e) {
            setStatus("Texture applied (couldn't write skins.json: " + e.getMessage() + ")");
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Math.max(1, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s) {
        try {
            return SkinDef.clampFps(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 3.0 -> "3", 2.5 -> "2.5": frame rates without the trailing noise. */
    private static String trimNumber(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // --- custom object creation ("+ New…" palette entries) --------------------------

    private static final String[] TOOL_CLASSES = {"(none)", "pickaxe", "axe", "shovel"};
    private static final String[] ITEM_CATEGORIES =
            {"MATERIAL", "WEAPON", "TOOL", "FOOD", "POTION", "THROWABLE", "OTHER"};

    /**
     * The Hytale-style "add your own object" form: every property of the new
     * block/liquid/light/mob/item/decoration is editable, and Create registers
     * it with the engine and persists it in the game type's {@code custom.json}.
     */
    private void buildCustomForm() {
        dialogForm.addText("Name", () -> cName, v -> cName = v, 28);
        switch (customCategory) {
            case MOBS -> buildCustomMobFields();
            case ITEMS -> buildCustomItemFields();
            case DECOR -> buildCustomDecorFields();
            case SURFACE -> buildCustomSurfaceFields();
            case CHARACTERS -> buildCustomCharacterFields();
            default -> buildCustomBlockFields();
        }
        dialogForm.addAction("Create", this::createCustomObject);
        // The other half of "+ New …": make it, then draw what it looks like,
        // without a detour through a paint program and the file system.
        dialogForm.addAction("Create & draw its texture…", () -> createCustomObject(true));
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /**
     * "+ New Character": a playable character profile — its skin colours and
     * the traits that make it feel different to control. Every field here is
     * what {@link CharacterProfile} carries into the simulation, so the
     * numbers a creator picks are the numbers the physics runs.
     */
    private void buildCustomCharacterFields() {
        addColorSliders("Body colour", false);
        addColorSliders("Skin colour", true);
        dialogForm.addDouble("Speed (× normal)", () -> cCharSpeed,
                v -> cCharSpeed = v, 0.25, 3.0, 0.05);
        dialogForm.addToggle("Can sprint (Shift)", () -> cCharSprint, v -> cCharSprint = v);
        // "Extra" is what the number means: 1 is a double jump, 2 a triple.
        dialogForm.addSlider("Extra mid-air jumps", () -> cCharAirJumps,
                v -> cCharAirJumps = v, 0, 4);
        dialogForm.addDouble("Jump height (× normal)", () -> cCharJump,
                v -> cCharJump = v, 0.25, 3.0, 0.05);
        dialogForm.addSlider("Max health", () -> cCharHp, v -> cCharHp = v, 10, 500);
        dialogForm.addSlider("Max mana", () -> cCharMana, v -> cCharMana = v, 0, 500);
        dialogForm.addSlider("Max stamina", () -> cCharStamina,
                v -> cCharStamina = v, 0, 500);
        String[] ults = Ultimates.choices();
        dialogForm.addEnum("Ultimate ability", ults,
                () -> ults[Math.min(cCharUltIndex, ults.length - 1)],
                v -> {
                    cCharUltIndex = Math.max(0, List.of(ults).indexOf(v));
                    openDialog(Dialog.CUSTOM); // the description row follows it
                });
        Ultimate picked = Ultimates.get(Ultimates.keyForChoice(
                ults[Math.min(cCharUltIndex, ults.length - 1)]));
        if (picked != null) {
            // What the chosen ability actually does, so a creator isn't picking
            // from names alone. A note, not a row: it is a sentence, and it
            // wraps instead of running across the form.
            dialogForm.addNote(picked.description());
            dialogForm.addToggle("Ultimate switched on", () -> cCharUltEnabled,
                    v -> cCharUltEnabled = v);
        }
    }

    /**
     * Level Roster…: which character profiles this level offers when a player
     * starts it. Nothing ticked means every profile is available, so a level
     * is never unplayable and levels built before profiles existed keep
     * working.
     */
    private void buildRosterForm() {
        List<CharacterProfile> all = Characters.all();
        for (CharacterProfile c : all) {
            // The name is the row; its stat line is a note underneath, because
            // a full stat line on the row would run straight over the toggle.
            dialogForm.addToggle(c.name,
                    () -> level.characters.contains(c.key),
                    v -> {
                        if (v) {
                            if (!level.characters.contains(c.key)) level.characters.add(c.key);
                        } else {
                            level.characters.remove(c.key);
                        }
                    });
            dialogForm.addNote(c.summary());
        }
        dialogForm.addAction("Offer every character (clear the roster)", () -> {
            level.characters.clear();
            openDialog(Dialog.ROSTER);
            setStatus("This level offers every character profile");
        });
        dialogForm.addAction("Done", () -> {
            closeDialog();
            setStatus(level.characters.isEmpty()
                    ? "Roster cleared — this level offers every character"
                    : "This level offers " + level.characters.size() + " character(s)"
                    + " — save the level to keep the roster");
        });
    }

    private void addColorSliders(String label, boolean accent) {
        if (accent) {
            dialogForm.addSlider(label + " red", () -> cR2, v -> cR2 = v, 0, 255);
            dialogForm.addSlider(label + " green", () -> cG2, v -> cG2 = v, 0, 255);
            dialogForm.addSlider(label + " blue", () -> cB2, v -> cB2 = v, 0, 255);
        } else {
            dialogForm.addSlider(label + " red", () -> cR, v -> cR = v, 0, 255);
            dialogForm.addSlider(label + " green", () -> cG, v -> cG = v, 0, 255);
            dialogForm.addSlider(label + " blue", () -> cB, v -> cB = v, 0, 255);
        }
    }

    private void buildCustomBlockFields() {
        boolean liquid = customCategory == Category.LIQUIDS;
        boolean light = customCategory == Category.LIGHTS;
        if (cName.isEmpty()) {
            cSolid = !liquid && !light;
            cLightRadius = light ? 6 : 0;
            cDamage = 0;
            cHardness = 1.0;
            cToolIndex = 0;
            cFalling = false;
        }
        addColorSliders("Colour", false);
        addFaceTextureFields();
        if (!liquid) {
            dialogForm.addToggle("Solid (collides)", () -> cSolid, v -> cSolid = v);
            dialogForm.addToggle("Falls like sand/gravel", () -> cFalling,
                    v -> cFalling = v);
        }
        dialogForm.addSlider("Light radius (tiles)", () -> cLightRadius,
                v -> cLightRadius = v, 0, 12);
        dialogForm.addSlider("Light red", () -> cLightR, v -> cLightR = v, 0, 255);
        dialogForm.addSlider("Light green", () -> cLightG, v -> cLightG = v, 0, 255);
        dialogForm.addSlider("Light blue", () -> cLightB, v -> cLightB = v, 0, 255);
        dialogForm.addSlider("Contact damage /sec", () -> cDamage, v -> cDamage = v, 0, 40);
        dialogForm.addDouble("Hardness (sec to mine)", () -> cHardness,
                v -> cHardness = v, 0, 20, 0.5);
        dialogForm.addEnum("Best tool", TOOL_CLASSES,
                () -> TOOL_CLASSES[cToolIndex],
                v -> cToolIndex = Math.max(0, List.of(TOOL_CLASSES).indexOf(v)));
    }

    /**
     * The question every new block is asked: which of the plan-view faces it
     * comes with. A side-scroller only ever sees a block edge-on, but top-down
     * and isometric levels look down at its top and — once it is stacked into a
     * wall — across at its side, and those are different pictures. Each face
     * ticked here is a file the creator means to draw; a face left off falls
     * back to the block's one flat sheet, and then to its colour, so answering
     * "neither" is a real answer rather than a broken block.
     */
    private void addFaceTextureFields() {
        dialogForm.addToggle("Has a TOP texture (top-down / isometric)",
                () -> cTopTexture, v -> cTopTexture = v);
        dialogForm.addToggle("Has a SIDE texture (stacked into a wall)",
                () -> cSideTexture, v -> cSideTexture = v);
        dialogForm.addNote(faceTextureNote());
    }

    /** What the face answers mean, spelled out in the files they ask for. */
    private String faceTextureNote() {
        String key = cName.isBlank() ? "<key>"
                : CustomContentStore.keyFor(cName.trim(), k -> false);
        if (cTopTexture && cSideTexture) {
            return "Draw textures/" + TextureKeys.BLOCKS_TOP + "/" + key + ".png and "
                    + TextureKeys.BLOCKS_SIDE + "/" + key + ".png";
        }
        if (cTopTexture) {
            return "Draw textures/" + TextureKeys.BLOCKS_TOP + "/" + key
                    + ".png; stacked sides fall back to the flat sheet";
        }
        if (cSideTexture) {
            return "Draw textures/" + TextureKeys.BLOCKS_SIDE + "/" + key
                    + ".png; floors fall back to the flat sheet";
        }
        return "No plan-view faces: textures/blocks/" + key
                + ".png dresses every view, or the colour above does";
    }

    private void buildCustomMobFields() {
        addColorSliders("Body", false);
        addColorSliders("Accent", true);
        dialogForm.addSlider("Size (px)", () -> cSize, v -> cSize = v, 12, 96);
        dialogForm.addSlider("Speed (px/sec)", () -> cSpeed, v -> cSpeed = v, 10, 320);
        dialogForm.addSlider("Max health", () -> cHp, v -> cHp = v, 1, 500);
        dialogForm.addSlider("Damage", () -> cMobDamage, v -> cMobDamage = v, 0, 60);
        String[] tempers = {"HOSTILE", "NEUTRAL", "PASSIVE"};
        dialogForm.addEnum("Temperament", tempers,
                () -> tempers[cTemperIndex],
                v -> cTemperIndex = Math.max(0, List.of(tempers).indexOf(v)));
        dialogForm.addSlider("Detect range (px)", () -> cDetect, v -> cDetect = v, 40, 800);
        dialogForm.addSlider("Attack range (px)", () -> cAttack, v -> cAttack = v, 10, 200);
        dialogForm.addToggle("Flying", () -> cFlying, v -> cFlying = v);
    }

    private void buildCustomItemFields() {
        addColorSliders("Colour", false);
        dialogForm.addEnum("Category", ITEM_CATEGORIES,
                () -> ITEM_CATEGORIES[cCategoryIndex],
                v -> cCategoryIndex = Math.max(0, List.of(ITEM_CATEGORIES).indexOf(v)));
        String[] rarities = new String[ItemDef.Rarity.values().length];
        for (ItemDef.Rarity r : ItemDef.Rarity.values()) rarities[r.ordinal()] = r.name();
        dialogForm.addEnum("Rarity (sets its glow)", rarities,
                () -> rarities[cRarityIndex],
                v -> cRarityIndex = Math.max(0, List.of(rarities).indexOf(v)));
        dialogForm.addSlider("Max stack", () -> cMaxStack, v -> cMaxStack = v, 1, 99);
        dialogForm.addSlider("Damage", () -> cMobDamage, v -> cMobDamage = v, 0, 60);
        dialogForm.addSlider("Heals (food/potion)", () -> cHeal, v -> cHeal = v, 0, 100);
        dialogForm.addEnum("Tool class", TOOL_CLASSES,
                () -> TOOL_CLASSES[cToolIndex],
                v -> cToolIndex = Math.max(0, List.of(TOOL_CLASSES).indexOf(v)));
        dialogForm.addDouble("Tool power (mining ×)", () -> cToolPower,
                v -> cToolPower = v, 1, 12, 0.5)
                .enabledWhen(() -> cToolIndex > 0);
    }

    private void buildCustomDecorFields() {
        addColorSliders("Primary", false);
        addColorSliders("Secondary", true);
        String[] shapes = new String[Decor.Shape.values().length];
        for (Decor.Shape s : Decor.Shape.values()) shapes[s.ordinal()] = s.name();
        dialogForm.addEnum("Shape", shapes,
                () -> shapes[cShapeIndex],
                v -> cShapeIndex = Math.max(0, List.of(shapes).indexOf(v)));
        dialogForm.addDouble("Size (tiles tall)", () -> cSizeTiles,
                v -> cSizeTiles = v, 0.5, 8, 0.5);
    }

    /** The eight compass points the sun can be put on, and their bearings. */
    private static final String[] SUN_COMPASS = {
            "North", "North-East", "East", "South-East",
            "South", "South-West", "West", "North-West"};

    /**
     * Tools &rarr; Light Direction…: where the sun stands over this level, and
     * so which way every stacked block throws its shadow.
     *
     * <p>It is a per-level look rather than an engine constant — a town at noon
     * and a canyon in late afternoon want their shadows thrown different ways —
     * and every block agrees on one answer, because shadows that disagree stop
     * reading as light at all. The slider is live: the level redraws behind the
     * dialog as it moves, so the angle is chosen by looking at it.
     */
    private void buildSunlightForm() {
        dialogForm.addNote("Where the sun stands over this level. Stacked blocks "
                + "throw their shadows away from it, which is what makes their "
                + "height read from above.");
        dialogForm.addInt("Sun bearing (° clockwise from north)",
                () -> (int) Math.round(level.lightAngle),
                v -> level.lightAngle = Math.floorMod(v, 360), 0, 359, 5);
        dialogForm.addEnum("Or pick a compass point", SUN_COMPASS,
                () -> SUN_COMPASS[(int) Math.round(level.lightAngle / 45.0) % 8],
                v -> {
                    level.lightAngle = List.of(SUN_COMPASS).indexOf(v) * 45.0;
                    openDialog(Dialog.SUNLIGHT); // the bearing row follows it
                });
        if (!level.layered()) {
            dialogForm.addNote("This is a side-scrolling level: its blocks are "
                    + "drawn edge-on and cast no shadow, so the setting is saved "
                    + "but does nothing here.");
        }
        dialogForm.addAction("Reset (north-west)", () -> {
            level.lightAngle = Level.DEFAULT_LIGHT_ANGLE;
            openDialog(Dialog.SUNLIGHT);
        });
        dialogForm.addAction("Done", () -> {
            closeDialog();
            setStatus("Sun at " + (int) Math.round(level.lightAngle)
                    + "° — save the level to keep it");
        });
    }

    /** "+ New Block Decor": a custom face-attached surface detail. */
    private void buildCustomSurfaceFields() {
        addColorSliders("Primary", false);
        addColorSliders("Secondary", true);
        String[] styles = new String[SurfaceDecor.Style.values().length];
        for (SurfaceDecor.Style s : SurfaceDecor.Style.values()) {
            styles[s.ordinal()] = s.name();
        }
        dialogForm.addEnum("Style (silhouette)", styles,
                () -> styles[cSurfStyleIndex],
                v -> cSurfStyleIndex = Math.max(0, List.of(styles).indexOf(v)));
        // Which block faces it may attach to (none picked = all faces).
        dialogForm.addToggle("Attaches to TOP faces", () -> cFaceUp, v -> cFaceUp = v);
        dialogForm.addToggle("Attaches to BOTTOM faces", () -> cFaceDown, v -> cFaceDown = v);
        dialogForm.addToggle("Attaches to LEFT faces", () -> cFaceLeft, v -> cFaceLeft = v);
        dialogForm.addToggle("Attaches to RIGHT faces", () -> cFaceRight, v -> cFaceRight = v);
        dialogForm.addToggle("Foreground layer by default", () -> cSurfForeground,
                v -> cSurfForeground = v);
    }

    private void createCustomObject() {
        createCustomObject(false);
    }

    /**
     * Register + persist the object the "+ New …" form describes.
     *
     * @param drawTexture whether to go straight into the sprite-sheet editor
     *                    for the new object's first sheet
     */
    private void createCustomObject(boolean drawTexture) {
        String name = cName.isBlank() ? "Custom " + customKindName() : cName.trim();
        createdKind = "";
        createdKey = "";
        try {
            switch (customCategory) {
                case CHARACTERS -> {
                    CharacterProfile c = new CharacterProfile(
                            CharacterStore.keyFor(name), name);
                    c.body = new Color(cR, cG, cB);
                    c.accent = new Color(cR2, cG2, cB2);
                    c.speed = cCharSpeed;
                    c.sprintEnabled = cCharSprint;
                    c.airJumps = cCharAirJumps;
                    c.jumpHeight = cCharJump;
                    c.maxHealth = cCharHp;
                    c.maxMana = cCharMana;
                    c.maxStamina = cCharStamina;
                    String[] ults = Ultimates.choices();
                    c.ultimateKey = Ultimates.keyForChoice(
                            ults[Math.min(cCharUltIndex, ults.length - 1)]);
                    c.ultimateEnabled = cCharUltEnabled;
                    characterStore.add(c);
                    // A brand-new character joins this level's roster, unless
                    // the roster is empty (which already means "everyone").
                    if (!level.characters.isEmpty()) level.characters.add(c.key);
                    createdKind = "character";
                    createdKey = c.key;
                    recordCustomObject("character", c.key, () -> characterStore.add(c));
                }
                case MOBS -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> MobRegistry.standard().get(k) != null);
                    MobDef def = new MobDef(key, name,
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2),
                            cSize, cSpeed, cHp, cMobDamage,
                            MobDef.Temperament.values()[cTemperIndex],
                            cDetect, cAttack, cFlying);
                    customContent.addMob(def);
                    createdKind = "mob";
                    createdKey = key;
                    recordCustomObject("mob", key, () -> customContent.addMob(def));
                }
                case ITEMS -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> ItemRegistry.standard().get(k) != null);
                    boolean isTool = cToolIndex > 0
                            || "TOOL".equals(ITEM_CATEGORIES[cCategoryIndex]);
                    ItemDef def = new ItemDef(key, name,
                            ItemDef.Category.valueOf(ITEM_CATEGORIES[cCategoryIndex]),
                            ItemDef.Rarity.values()[cRarityIndex],
                            new Color(cR, cG, cB), cMaxStack, cMobDamage, cHeal,
                            null, null, null,
                            isTool && cToolIndex > 0 ? TOOL_CLASSES[cToolIndex] : null,
                            isTool && cToolIndex > 0 ? cToolPower : 0);
                    customContent.addItem(def);
                    createdKind = "item";
                    createdKey = key;
                    recordCustomObject("item", key, () -> customContent.addItem(def));
                }
                case DECOR -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> DecorRegistry.standard().get(k) != null);
                    Decor def = new Decor(key, name,
                            Decor.Shape.values()[cShapeIndex],
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2), cSizeTiles);
                    customContent.addDecor(def);
                    createdKind = "decor";
                    createdKey = key;
                    recordCustomObject("decor", key, () -> customContent.addDecor(def));
                }
                case SURFACE -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> SurfaceDecorRegistry.standard().get(k) != null);
                    java.util.EnumSet<SurfaceDecor.Face> faces =
                            java.util.EnumSet.noneOf(SurfaceDecor.Face.class);
                    if (cFaceUp) faces.add(SurfaceDecor.Face.UP);
                    if (cFaceDown) faces.add(SurfaceDecor.Face.DOWN);
                    if (cFaceLeft) faces.add(SurfaceDecor.Face.LEFT);
                    if (cFaceRight) faces.add(SurfaceDecor.Face.RIGHT);
                    SurfaceDecor def = new SurfaceDecor(key, name,
                            SurfaceDecor.Style.values()[cSurfStyleIndex],
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2),
                            faces, cSurfForeground);
                    customContent.addSurfaceDecor(def);
                    createdKind = "surface";
                    createdKey = key;
                    recordCustomObject("surface", key,
                            () -> customContent.addSurfaceDecor(def));
                }
                default -> {
                    boolean liquid = customCategory == Category.LIQUIDS;
                    String key = CustomContentStore.keyFor(name,
                            k -> level.blocks.get(k) != null);
                    boolean solid = !liquid && cSolid;
                    // The id is part of the block, so undoing and redoing its
                    // creation puts the same id back — a level already painted
                    // with it still resolves to it.
                    Block def = new Block(customContent.nextBlockId(), key,
                            name, new Color(cR, cG, cB), solid,
                            cLightRadius, new Color(cLightR, cLightG, cLightB),
                            solid ? key : null, liquid, cDamage, cHardness,
                            cToolIndex > 0 ? TOOL_CLASSES[cToolIndex] : null,
                            !liquid && cFalling, cTopTexture, cSideTexture);
                    customContent.addBlock(def);
                    createdKind = "block";
                    createdKey = key;
                    recordCustomObject("block", key, () -> customContent.addBlock(def));
                }
            }
        } catch (RuntimeException e) {
            setStatus("Couldn't create it: " + e.getMessage());
            return;
        }
        buildPalette();
        selectNewest(name);
        closeDialog();
        if (drawTexture && !createdKey.isEmpty()) {
            openSpriteEditor(defaultTextureKey(createdKind, createdKey), name);
            return; // the paint window says the rest
        }
        // A new block leaves with its homework: the exact sheets to draw for
        // the faces it just said it has.
        String faces = "block".equals(createdKind) ? faceTextureHint(createdKey) : "";
        setStatus("Added custom " + customKindName().toLowerCase() + " \"" + name
                + "\" — saved to " + customContent.file() + faces
                + " · right-click it for \"Create texture\"");
    }

    /** " · draw blocks_top/x.png, blocks_side/x.png" — or nothing to draw. */
    private String faceTextureHint(String key) {
        List<String> files = new ArrayList<>(2);
        if (cTopTexture) files.add(TextureKeys.BLOCKS_TOP + "/" + key + ".png");
        if (cSideTexture) files.add(TextureKeys.BLOCKS_SIDE + "/" + key + ".png");
        if (files.isEmpty()) return "";
        return " · drop " + String.join(" and ", files) + " into the texture pack";
    }

    /** Select the just-created object in its palette category. */
    private void selectNewest(String name) {
        List<Entry> entries = palette.get(customCategory);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equals(name)) {
                selected.put(customCategory, i);
                return;
            }
        }
    }

    // --- cutscenes (triggers + sprite-sheet actors + step scripts) --------------------

    private static final String[] CS_TRIGGER_NAMES =
            {"Walk into it (zone)", "Press E at it", "When the level starts"};
    private static final String[] CS_OP_NAMES = {
            "SHOW actor at X,Y", "SAY text (actor speaks)", "MOVE actor to X,Y",
            "ANIM: set actor's state", "WAIT", "CAMERA pan to X,Y", "HIDE actor"};

    /** The cutscene the dialog suite is editing, or {@code null} for "(new)". */
    private Cutscene editingCutscene() {
        return csEditIndex > 0 && csEditIndex <= level.cutscenes.size()
                ? level.cutscenes.get(csEditIndex - 1) : null;
    }

    private String editingCutsceneName() {
        Cutscene cs = editingCutscene();
        return cs != null ? cs.name : "Cutscene";
    }

    private Cutscene cutsceneByKey(String key) {
        for (Cutscene cs : level.cutscenes) {
            if (cs.key.equals(key)) return cs;
        }
        return null;
    }

    /** A key no cutscene in this level uses yet. */
    private String freshCutsceneKey() {
        int n = level.cutscenes.size() + 1;
        while (cutsceneByKey("cs" + n) != null) n++;
        return "cs" + n;
    }

    /**
     * The cutscene manager: pick one (or "new"), set its name, trigger kind,
     * radius, and once/repeat, then dive into its actors (sprite-sheet
     * animation states) and steps (the script). Cutscenes save with the
     * level, and painting a cutscene's palette entry places its trigger
     * marker in the world.
     */
    private void buildCutscenesForm() {
        List<Cutscene> list = level.cutscenes;
        csEditIndex = Math.min(csEditIndex, list.size());
        List<String> options = new ArrayList<>();
        options.add("(new cutscene)");
        for (Cutscene cs : list) options.add(cs.name);
        Cutscene editing = editingCutscene();
        if (!dialogRebuild) {
            csName = editing != null ? editing.name : "Cutscene " + (list.size() + 1);
            csTriggerIndex = editing != null ? editing.trigger.ordinal() : 0;
            csRadius = editing != null ? (int) Math.round(editing.radiusTiles) : 2;
            csOnce = editing == null || editing.once;
        }
        dialogRebuild = false;

        dialogForm.addEnum("Cutscene", options.toArray(new String[0]),
                () -> options.get(Math.min(csEditIndex, options.size() - 1)),
                v -> {
                    csEditIndex = Math.max(0, options.indexOf(v));
                    openDialog(Dialog.CUTSCENES); // reload the fields below
                });
        dialogForm.addText("Name", () -> csName, v -> csName = v, 28);
        dialogForm.addEnum("Trigger", CS_TRIGGER_NAMES,
                () -> CS_TRIGGER_NAMES[csTriggerIndex],
                v -> csTriggerIndex = Math.max(0, List.of(CS_TRIGGER_NAMES).indexOf(v)));
        dialogForm.addInt("Trigger radius (tiles)", () -> csRadius,
                        v -> csRadius = v, 1, 64, 1)
                .enabledWhen(() -> csTriggerIndex != Cutscene.Trigger.LEVEL_START.ordinal());
        dialogForm.addToggle("Play once per run", () -> csOnce, v -> csOnce = v);
        if (editing != null) {
            dialogForm.addAction("Edit Actors… (" + editing.actors.size() + ")", () -> {
                csActorEditIndex = 0;
                csStateEditIndex = 0;
                openDialog(Dialog.CUTSCENE_ACTORS);
            });
            dialogForm.addAction("Edit Steps… (" + editing.steps.size() + ")", () -> {
                csStepEditIndex = 0;
                openDialog(Dialog.CUTSCENE_STEPS);
            });
        }
        dialogForm.addAction(editing == null ? "Add Cutscene" : "Save Cutscene", () -> {
            if (editing == null) {
                Cutscene cs = new Cutscene(freshCutsceneKey(), csName);
                applyCutsceneFields(cs);
                // The fresh marker lands mid-view; paint its palette entry to move it.
                cs.x = camera.x;
                cs.y = camera.y;
                level.cutscenes.add(cs);
                csEditIndex = level.cutscenes.size();
                buildPalette();
                openDialog(Dialog.CUTSCENES); // reopen on it: actors/steps unlock
                setStatus("Cutscene \"" + cs.name + "\" added — give it actors and steps,"
                        + " then paint its marker from the palette");
            } else {
                applyCutsceneFields(editing);
                buildPalette();
                closeDialog();
                setStatus("Cutscene \"" + editing.name
                        + "\" saved — it runs in play-test and play (Ctrl+S keeps it)");
            }
        });
        if (editing != null) {
            dialogForm.addAction("Delete Cutscene", () -> {
                level.cutscenes.remove(editing);
                csEditIndex = 0;
                buildPalette();
                closeDialog();
                setStatus("Cutscene \"" + editing.name + "\" deleted");
            });
        }
        dialogForm.addAction("Close", this::closeDialog);
    }

    private void applyCutsceneFields(Cutscene cs) {
        cs.name = csName.isBlank() ? cs.key : csName.trim();
        cs.trigger = Cutscene.Trigger.values()[
                Math.min(csTriggerIndex, Cutscene.Trigger.values().length - 1)];
        cs.radiusTiles = Math.max(1, csRadius);
        cs.once = csOnce;
    }

    /**
     * The actor editor for the selected cutscene: pick an actor (or "new"),
     * name and size it, and define its <b>animation states</b> — each state
     * is a sprite sheet with frame width/height, frame count, fps, and a
     * loop flag. Steps refer to states by name (idle/walk/talk play
     * automatically during MOVE and SAY steps).
     */
    private void buildCutsceneActorsForm() {
        Cutscene cs = editingCutscene();
        if (cs == null) {
            openDialog(Dialog.CUTSCENES);
            return;
        }
        csActorEditIndex = Math.min(csActorEditIndex, cs.actors.size());
        List<String> options = new ArrayList<>();
        options.add("(new actor)");
        for (Cutscene.Actor a : cs.actors) options.add(a.name);
        Cutscene.Actor editing = csActorEditIndex > 0
                ? cs.actors.get(csActorEditIndex - 1) : null;
        List<String> stateNames = editing != null
                ? new ArrayList<>(editing.states.keySet()) : new ArrayList<>();
        csStateEditIndex = Math.min(csStateEditIndex, stateNames.size());
        if (!dialogRebuild) {
            csActorName = editing != null ? editing.name : "Actor " + (cs.actors.size() + 1);
            csActorSize = editing != null ? editing.sizePx
                    : Math.max(16, level.tileSize * 3 / 2);
            loadActorStateFields(editing, stateNames);
        }
        dialogRebuild = false;

        dialogForm.addEnum("Actor", options.toArray(new String[0]),
                () -> options.get(Math.min(csActorEditIndex, options.size() - 1)),
                v -> {
                    csActorEditIndex = Math.max(0, options.indexOf(v));
                    csStateEditIndex = 0;
                    openDialog(Dialog.CUTSCENE_ACTORS);
                });
        dialogForm.addText("Name", () -> csActorName, v -> csActorName = v, 24);
        dialogForm.addInt("Size (world px)", () -> csActorSize,
                v -> csActorSize = v, 8, 256, 4);
        dialogForm.addAction(editing == null ? "Add Actor" : "Save Actor", () -> {
            if (editing == null) {
                Cutscene.Actor a = new Cutscene.Actor(
                        freshActorKey(cs), csActorName, csActorSize);
                cs.actors.add(a);
                csActorEditIndex = cs.actors.size();
                openDialog(Dialog.CUTSCENE_ACTORS); // states unlock below
                setStatus("Actor \"" + a.name + "\" added — now give it animation states");
            } else {
                editing.name = csActorName.isBlank() ? editing.key : csActorName.trim();
                editing.sizePx = csActorSize;
                openDialog(Dialog.CUTSCENE_ACTORS);
                setStatus("Actor \"" + editing.name + "\" saved");
            }
        });

        if (editing != null) {
            List<String> stateOptions = new ArrayList<>();
            stateOptions.add("(new state)");
            stateOptions.addAll(stateNames);
            dialogForm.addEnum("Animation state", stateOptions.toArray(new String[0]),
                    () -> stateOptions.get(Math.min(csStateEditIndex, stateOptions.size() - 1)),
                    v -> {
                        csStateEditIndex = Math.max(0, stateOptions.indexOf(v));
                        openDialog(Dialog.CUTSCENE_ACTORS);
                    });
            dialogForm.addText("State name (idle/walk/talk/…)",
                    () -> csStateName, v -> csStateName = v, 20);
            dialogForm.addText("Sheet (PNG path)", () -> csSheet, v -> csSheet = v, 96);
            dialogForm.addAction("Browse…", () -> {
                String picked = chooseSheetFile();
                if (picked != null) csSheet = picked;
            });
            dialogForm.addText("Frame width px", () -> csFrameW, v -> csFrameW = v, 4);
            dialogForm.addText("Frame height px", () -> csFrameH, v -> csFrameH = v, 4);
            dialogForm.addText("Frame count", () -> csFrames, v -> csFrames = v, 3);
            dialogForm.addText("FPS (0 = static)", () -> csFps, v -> csFps = v, 5);
            dialogForm.addToggle("Loop (off = hold the last frame)",
                    () -> csLoop, v -> csLoop = v);
            String editingState = csStateEditIndex > 0
                    ? stateNames.get(csStateEditIndex - 1) : null;
            dialogForm.addAction("Apply State", () -> {
                String name = csStateName.trim().toLowerCase();
                if (name.isEmpty()) {
                    setStatus("Give the animation state a name (idle, walk, talk…)");
                    return;
                }
                if (csSheet.isBlank()) {
                    setStatus("Pick a sprite sheet for the state (path or Browse…)");
                    return;
                }
                Cutscene.SheetAnim anim = new Cutscene.SheetAnim(
                        resolveSheetPath(csSheet.trim()),
                        parseInt(csFrameW, 32), parseInt(csFrameH, 32),
                        parseInt(csFrames, 1), parseDouble(csFps), csLoop);
                if (editingState != null && !editingState.equals(name)) {
                    editing.states.remove(editingState); // renamed in place
                }
                editing.states.put(name, anim);
                CutscenePainter.clearCache();
                csStateEditIndex = new ArrayList<>(editing.states.keySet()).indexOf(name) + 1;
                openDialog(Dialog.CUTSCENE_ACTORS);
                setStatus("State \"" + name + "\": " + anim.frameCount()
                        + " frames @ " + anim.fps() + " fps"
                        + (anim.loop() ? " (looping)" : " (one-shot)"));
            });
            if (editingState != null) {
                dialogForm.addAction("Remove State", () -> {
                    editing.states.remove(editingState);
                    csStateEditIndex = 0;
                    openDialog(Dialog.CUTSCENE_ACTORS);
                    setStatus("State \"" + editingState + "\" removed");
                });
            }
            dialogForm.addAction("Delete Actor", () -> {
                cs.actors.remove(editing);
                csActorEditIndex = 0;
                csStateEditIndex = 0;
                openDialog(Dialog.CUTSCENE_ACTORS);
                setStatus("Actor \"" + editing.name + "\" deleted");
            });
        }
        dialogForm.addAction("Back to Cutscene…", () -> openDialog(Dialog.CUTSCENES));
        dialogForm.addAction("Close", this::closeDialog);
    }

    /** Load the state-editor fields for the picked state (or fresh defaults). */
    private void loadActorStateFields(Cutscene.Actor actor, List<String> stateNames) {
        if (actor != null && csStateEditIndex > 0 && csStateEditIndex <= stateNames.size()) {
            String name = stateNames.get(csStateEditIndex - 1);
            Cutscene.SheetAnim anim = actor.states.get(name);
            csStateName = name;
            csSheet = anim.sheet();
            csFrameW = String.valueOf(anim.frameWidth());
            csFrameH = String.valueOf(anim.frameHeight());
            csFrames = String.valueOf(anim.frameCount());
            csFps = String.valueOf(anim.fps());
            csLoop = anim.loop();
        } else {
            // Suggest the conventional states the runtime plays automatically.
            csStateName = actor == null || !actor.states.containsKey("idle") ? "idle"
                    : !actor.states.containsKey("walk") ? "walk"
                    : !actor.states.containsKey("talk") ? "talk" : "";
            csSheet = "";
            csFrameW = csFrameH = "32";
            csFrames = "1";
            csFps = "8";
            csLoop = true;
        }
    }

    private static String freshActorKey(Cutscene cs) {
        int n = cs.actors.size() + 1;
        while (cs.actor("actor" + n) != null) n++;
        return "actor" + n;
    }

    /**
     * The step editor for the selected cutscene: the script as an ordered
     * list — pick a step (or "add"), choose its action, actor, text /
     * animation-state name, target tile, and duration. Steps run in order
     * when the cutscene triggers.
     */
    private void buildCutsceneStepsForm() {
        Cutscene cs = editingCutscene();
        if (cs == null) {
            openDialog(Dialog.CUTSCENES);
            return;
        }
        csStepEditIndex = Math.min(csStepEditIndex, cs.steps.size());
        List<String> options = new ArrayList<>();
        options.add("(add a step)");
        for (int i = 0; i < cs.steps.size(); i++) {
            options.add(stepSummary(i + 1, cs.steps.get(i)));
        }
        List<String> actorKeys = new ArrayList<>();
        List<String> actorOptions = new ArrayList<>();
        actorKeys.add("");
        actorOptions.add("(none)");
        for (Cutscene.Actor a : cs.actors) {
            actorKeys.add(a.key);
            actorOptions.add(a.name);
        }
        Cutscene.Step editing = csStepEditIndex > 0
                ? cs.steps.get(csStepEditIndex - 1) : null;
        double ts = level.tileSize;
        if (!dialogRebuild) {
            if (editing != null) {
                csOpIndex = editing.op().ordinal();
                csStepActorIndex = Math.max(0, actorKeys.indexOf(editing.actor()));
                csText = editing.text();
                csStepX = (int) Math.floor(editing.x() / ts);
                csStepY = (int) Math.floor(editing.y() / ts);
                csSeconds = String.valueOf(editing.seconds());
            } else {
                csOpIndex = cs.steps.isEmpty() ? Cutscene.Op.SHOW.ordinal()
                        : Cutscene.Op.SAY.ordinal();
                csStepActorIndex = Math.min(1, actorKeys.size() - 1);
                csText = "";
                csStepX = clampTile((int) Math.floor(camera.x / ts), level.width);
                csStepY = clampTile((int) Math.floor(camera.y / ts), level.height);
                csSeconds = "1.0";
            }
        }
        dialogRebuild = false;

        dialogForm.addEnum("Step", options.toArray(new String[0]),
                () -> options.get(Math.min(csStepEditIndex, options.size() - 1)),
                v -> {
                    csStepEditIndex = Math.max(0, options.indexOf(v));
                    openDialog(Dialog.CUTSCENE_STEPS);
                });
        dialogForm.addEnum("Action", CS_OP_NAMES, () -> CS_OP_NAMES[csOpIndex],
                v -> csOpIndex = Math.max(0, List.of(CS_OP_NAMES).indexOf(v)));
        dialogForm.addEnum("Actor", actorOptions.toArray(new String[0]),
                        () -> actorOptions.get(Math.min(csStepActorIndex, actorOptions.size() - 1)),
                        v -> csStepActorIndex = Math.max(0, actorOptions.indexOf(v)))
                .enabledWhen(() -> opAt(csOpIndex) != Cutscene.Op.WAIT
                        && opAt(csOpIndex) != Cutscene.Op.CAMERA);
        dialogForm.addText("Text (dialogue / state name)", () -> csText, v -> csText = v, 120)
                .enabledWhen(() -> opAt(csOpIndex) == Cutscene.Op.SAY
                        || opAt(csOpIndex) == Cutscene.Op.ANIM
                        || opAt(csOpIndex) == Cutscene.Op.SHOW);
        dialogForm.addInt("X (tile)", () -> csStepX,
                        v -> csStepX = v, 0, Math.max(0, level.width - 1), 1)
                .enabledWhen(this::stepUsesPoint);
        dialogForm.addInt("Y (tile)", () -> csStepY,
                        v -> csStepY = v, 0, Math.max(0, level.height - 1), 1)
                .enabledWhen(this::stepUsesPoint);
        dialogForm.addAction("Set X,Y to the camera center", () -> {
            csStepX = clampTile((int) Math.floor(camera.x / ts), level.width);
            csStepY = clampTile((int) Math.floor(camera.y / ts), level.height);
        }).enabledWhen(this::stepUsesPoint);
        dialogForm.addText("Seconds (0 = default/instant)", () -> csSeconds,
                v -> csSeconds = v, 6);
        dialogForm.addAction(editing == null ? "Add Step" : "Save Step", () -> {
            Cutscene.Op op = opAt(csOpIndex);
            String actor = actorKeys.get(Math.min(csStepActorIndex, actorKeys.size() - 1));
            if (actor.isEmpty() && op != Cutscene.Op.WAIT && op != Cutscene.Op.CAMERA) {
                setStatus("This action needs an actor — add one in Edit Actors… first");
                return;
            }
            Cutscene.Step step = new Cutscene.Step(op, actor, csText,
                    (csStepX + 0.5) * ts, (csStepY + 0.5) * ts, parseSeconds(csSeconds));
            if (editing == null) {
                cs.steps.add(step);
                csStepEditIndex = cs.steps.size();
                openDialog(Dialog.CUTSCENE_STEPS);
                setStatus("Step " + cs.steps.size() + " added: " + CS_OP_NAMES[csOpIndex]);
            } else {
                cs.steps.set(csStepEditIndex - 1, step);
                openDialog(Dialog.CUTSCENE_STEPS);
                setStatus("Step " + csStepEditIndex + " saved");
            }
        });
        if (editing != null) {
            if (csStepEditIndex > 1) {
                dialogForm.addAction("Move Step Up", () -> {
                    java.util.Collections.swap(cs.steps, csStepEditIndex - 1, csStepEditIndex - 2);
                    csStepEditIndex--;
                    openDialog(Dialog.CUTSCENE_STEPS);
                });
            }
            dialogForm.addAction("Delete Step", () -> {
                cs.steps.remove(csStepEditIndex - 1);
                csStepEditIndex = 0;
                openDialog(Dialog.CUTSCENE_STEPS);
                setStatus("Step deleted");
            });
        }
        dialogForm.addAction("Back to Cutscene…", () -> openDialog(Dialog.CUTSCENES));
        dialogForm.addAction("Close", this::closeDialog);
    }

    private static Cutscene.Op opAt(int index) {
        return Cutscene.Op.values()[Math.min(index, Cutscene.Op.values().length - 1)];
    }

    private boolean stepUsesPoint() {
        Cutscene.Op op = opAt(csOpIndex);
        return op == Cutscene.Op.SHOW || op == Cutscene.Op.MOVE || op == Cutscene.Op.CAMERA;
    }

    private static int clampTile(int tile, int bound) {
        return Math.max(0, Math.min(Math.max(0, bound - 1), tile));
    }

    private static double parseSeconds(String s) {
        try {
            return Math.max(0, Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One line summarizing a step for the step cycler. */
    private static String stepSummary(int number, Cutscene.Step step) {
        StringBuilder sb = new StringBuilder().append(number).append(". ")
                .append(step.op().name());
        if (!step.actor().isEmpty()) sb.append(' ').append(step.actor());
        if (!step.text().isEmpty()) {
            String t = step.text().length() > 14
                    ? step.text().substring(0, 14) + "…" : step.text();
            sb.append(" \"").append(t).append('"');
        }
        return sb.toString();
    }

    // --- stat rules ("if stat ≥ X → consume / reward") -------------------------------

    /**
     * The map-maker rule editor: pick a rule (or "new rule"), choose the
     * tracked stat, threshold, optional consumption and reward, whether it
     * repeats every threshold step, and whether the HUD charts it. Rules save
     * with the level and run during play/play-test.
     */
    private void buildRulesForm() {
        List<StatRule> rules = level.statRules;
        ruleEditIndex = Math.min(ruleEditIndex, rules.size());
        List<String> options = new ArrayList<>();
        options.add("(new rule)");
        for (StatRule rule : rules) {
            options.add(PlayerStats.label(rule.stat()) + " ≥ " + (long) rule.threshold());
        }
        StatRule editing = ruleEditIndex > 0 && ruleEditIndex <= rules.size()
                ? rules.get(ruleEditIndex - 1) : null;
        if (!dialogRebuild) {
            ruleStatIndex = 0;
            ruleThreshold = 10;
            ruleReward = "";
            ruleConsume = "";
            ruleRewardCount = 1;
            ruleConsumeCount = 1;
            ruleRepeat = false;
            ruleShowBar = true;
            if (editing != null) {
                ruleStatIndex = Math.max(0,
                        List.of(PlayerStats.TRACKED).indexOf(editing.stat()));
                ruleThreshold = (int) editing.threshold();
                ruleReward = editing.rewardItem() == null ? "" : editing.rewardItem();
                ruleRewardCount = editing.rewardCount();
                ruleConsume = editing.consumeItem() == null ? "" : editing.consumeItem();
                ruleConsumeCount = editing.consumeCount();
                ruleRepeat = editing.repeat();
                ruleShowBar = editing.showBar();
            }
        }
        dialogRebuild = false;

        dialogForm.addEnum("Rule", options.toArray(new String[0]),
                () -> options.get(Math.min(ruleEditIndex, options.size() - 1)),
                v -> {
                    ruleEditIndex = Math.max(0, options.indexOf(v));
                    openDialog(Dialog.RULES); // reload the fields below
                });
        String[] stats = new String[PlayerStats.TRACKED.length];
        for (int i = 0; i < stats.length; i++) stats[i] = PlayerStats.label(PlayerStats.TRACKED[i]);
        dialogForm.addEnum("Tracked stat", stats,
                () -> stats[ruleStatIndex],
                v -> ruleStatIndex = Math.max(0, List.of(stats).indexOf(v)));
        dialogForm.addInt("Threshold", () -> ruleThreshold,
                v -> ruleThreshold = v, 1, 1000000, 1);
        // Item keys are typeable, but the "look up" cyclers below browse the
        // whole catalog so creators don't have to memorize keys — which is what
        // the note says, since the fields have no room to say it themselves.
        String[] itemKeys = ruleItemKeyChoices();
        dialogForm.addNote("Leave an item key blank for none, or browse the "
                + "catalogue with the \"look up\" row under it.");
        dialogForm.addText("Reward item key",
                () -> ruleReward, v -> ruleReward = v, 24);
        dialogForm.addEnum("· look up reward key", itemKeys,
                () -> keyChoiceShown(itemKeys, ruleReward),
                v -> { if (!itemKeys[0].equals(v)) ruleReward = v; });
        dialogForm.addInt("Reward count", () -> ruleRewardCount,
                v -> ruleRewardCount = v, 1, 99, 1);
        dialogForm.addText("Consume item key",
                () -> ruleConsume, v -> ruleConsume = v, 24);
        dialogForm.addEnum("· look up consume key", itemKeys,
                () -> keyChoiceShown(itemKeys, ruleConsume),
                v -> { if (!itemKeys[0].equals(v)) ruleConsume = v; });
        dialogForm.addInt("Consume count", () -> ruleConsumeCount,
                v -> ruleConsumeCount = v, 1, 99, 1);
        dialogForm.addToggle("Repeat every threshold step", () -> ruleRepeat,
                v -> ruleRepeat = v);
        dialogForm.addToggle("Show HUD progress bar", () -> ruleShowBar,
                v -> ruleShowBar = v);
        dialogForm.addAction(editing == null ? "Add Rule" : "Save Rule", () -> {
            String rewardKey = validItemKeyOrNull(ruleReward);
            if (!ruleReward.isBlank() && rewardKey == null) {
                setStatus("Unknown reward item \"" + ruleReward.trim() + "\"");
                return;
            }
            String consumeKey = validItemKeyOrNull(ruleConsume);
            if (!ruleConsume.isBlank() && consumeKey == null) {
                setStatus("Unknown consume item \"" + ruleConsume.trim() + "\"");
                return;
            }
            StatRule rule = new StatRule(PlayerStats.TRACKED[ruleStatIndex], ruleThreshold,
                    rewardKey, ruleRewardCount, consumeKey, ruleConsumeCount,
                    ruleRepeat, ruleShowBar);
            if (editing == null) {
                level.statRules.add(rule);
            } else {
                level.statRules.set(ruleEditIndex - 1, rule);
            }
            closeDialog();
            setStatus("Rule saved — it runs in play-test and play (save the level to keep it)");
        });
        if (editing != null) {
            dialogForm.addAction("Delete Rule", () -> {
                level.statRules.remove(ruleEditIndex - 1);
                ruleEditIndex = 0;
                closeDialog();
                setStatus("Rule deleted");
            });
        }
        dialogForm.addAction("Close", this::closeDialog);
    }

    /** The item catalog as sorted cycler choices, headed by a no-op entry. */
    private static String[] ruleItemKeyChoices() {
        List<String> keys = new ArrayList<>();
        for (ItemDef d : ItemRegistry.standard().all()) keys.add(d.key());
        java.util.Collections.sort(keys);
        keys.add(0, "(browse…)");
        return keys.toArray(new String[0]);
    }

    /** What the look-up cycler shows: the typed key when it's in the catalog. */
    private static String keyChoiceShown(String[] choices, String typed) {
        String t = typed == null ? "" : typed.trim();
        for (String c : choices) {
            if (c.equals(t)) return c;
        }
        return choices[0];
    }

    /** Trimmed item key when it exists in the catalog, else {@code null}. */
    private static String validItemKeyOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        ItemDef def = ItemRegistry.standard().get(raw.trim());
        return def == null ? null : def.key();
    }

    private void closeDialog() {
        dialog = Dialog.NONE;
        dialogForm = null;
        endDialogEdit();
    }

    /**
     * Open the history step a window records into.
     *
     * <p>These forms reopen themselves after anything that changes what they
     * offer — a rule added, an actor saved, a mode switched, a different row
     * selected — so closing the previous step here and starting a fresh one
     * turns each of those into its own {@code Ctrl+Z}. A reopen that changed
     * nothing (picking a different rule to look at) leaves a step with nothing
     * in it, and those are dropped rather than remembered, so browsing a window
     * never costs a keystroke to walk back through.
     *
     * <p>Windows that only look at things (Save, Load's list, the exit
     * confirmation, Brush Settings, Sound Options) get no step at all: nothing
     * they do is an edit to this level, and a step for them would put a
     * keystroke between the creator and the edit they actually want back.
     */
    private void beginDialogEdit(Dialog d) {
        endStroke();
        endDialogEdit();
        if (net != null) return;
        String label = switch (d) {
            case NEW_LEVEL -> "new level";
            case LOAD -> "load level";
            case GENERATE -> "generate level";
            case RULES -> "stat rules";
            case CUTSCENES, CUTSCENE_ACTORS, CUTSCENE_STEPS -> "cutscene edit";
            case MINIGAME -> "mini game setup";
            case ROSTER -> "character roster";
            case SUNLIGHT -> "sun bearing";
            case LEVEL_MUSIC -> "level music";
            case CUSTOM -> "new custom object";
            case TEXTURE -> "texture change";
            case DOORS -> "door list edit";
            case SOUND -> "sound change";
            default -> "";
        };
        if (label.isEmpty()) return;
        dialogEditOpen = true;
        history.begin(label);
        // Every one of these windows can reach the level itself; the ones that
        // reach further (a level swap, a created object, a reassigned sheet)
        // record that where they do it.
        recordDoc();
    }

    /** Close a window's step (see {@link #beginDialogEdit}). */
    private void endDialogEdit() {
        if (!dialogEditOpen) return;
        dialogEditOpen = false;
        history.flush();
        stepCells.clear();
        stepAspects.clear();
    }

    private void updateDialog(double dt, InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            closeDialog();
            return;
        }
        dialogForm.update(dt, input);
    }

    // --- rendering -----------------------------------------------------------------

    @Override
    public void render(Graphics2D g, float alpha) {
        GameProfile p = profile();
        feedLighting(p);

        g.setColor(level.background);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        // A side view's blocks are a wall the background layer hides behind; a
        // plan view's are the floor it stands on, so there the scenery goes on
        // after the terrain — otherwise a decoration disappears under the very
        // tile it was just painted onto.
        boolean sceneryBehind = PerspectiveSpace.of(camera.getPerspective())
                .scenerySitsBehindTerrain();
        if (sceneryBehind) drawDecorLayer(g, false);
        // Everything standing on the floor shares one queue on a plane, so
        // whether the player is in front of a tree — or of a wall — is settled
        // by where they are standing rather than by a fixed layer order. The
        // side view's layers are fixed and correct, so its pass draws straight
        // through in call order.
        DepthPass standing = DepthPass.of(camera.getPerspective());
        drawTiles(g, standing);   // queues the crack overlay with its block
        // The grid is drawn through the camera, so it lands as a diamond
        // lattice in isometric view — which is exactly where lining blocks
        // up by eye is hardest, so it is worth having there too.
        if (showGrid && !testing) drawGrid(g);
        if (!sceneryBehind) drawDecorLayer(g, false, standing);
        drawWorldBounds(g);
        drawEntities(g, standing);
        drawSpawnMarker(g);
        if (!testing) drawCutsceneMarkers(g);
        if (testing && testMe != null) {
            standing.at(footDepth(testMe.x, testMe.y, profile().playerSize),
                    () -> drawTestPlayer(g));
        }
        standing.flush();
        if (testing && cutsceneDirector != null && cutsceneDirector.active() != null) {
            CutscenePainter.drawActors(g, camera, cutsceneDirector.active());
        }
        drawDecorLayer(g, true); // foreground scenery covers players
        if (p.particlesEnabled) particles.render(g, camera);

        if (!testing) {
            drawCursorPreview(g);
            drawSidebar(g);
            if (dialog == Dialog.NONE) drawPaletteTooltip(g);
        }
        // The play-test's character choice sits over the level being tested.
        if (testing && testPicker != null) {
            testPicker.render(g, viewportWidth, viewportHeight);
        }
        drawTopBar(g);
        if (testing) {
            if (p.itemsEnabled) drawTestHotbar(g);
            drawTestHealthBar(g);
            drawTestResourceBars(g);
            drawStatRuleBars(g);
            drawDoorHint(g);
            if (showInventory) drawTestInventory(g);
            if (craftingPanel != null) {
                craftingPanel.render(g, viewportWidth, viewportHeight, testInv, animClock);
            }
            if (containerPanel != null) {
                containerPanel.render(g, viewportWidth, viewportHeight, animClock);
            }
            if (cutsceneDirector != null && cutsceneDirector.active() != null) {
                CutscenePainter.drawOverlay(g, viewportWidth, viewportHeight,
                        cutsceneDirector.active());
            }
        }
        drawStatus(g);

        if (dialog != Dialog.NONE) drawDialog(g);
        // "Create texture" floats over the dialog that opened it, so closing
        // the paint window puts the creator back in that dialog.
        if (spriteEditor != null) spriteEditor.render(g, viewportWidth, viewportHeight);
    }

    /** Crack overlay on the block being held-mined, scaled by progress. */
    /** The play-test's hold-to-mine stroke, for the crack overlay, or null. */
    private TerrainPainter.Mining miningStroke() {
        if (!testing || testWorld == null) return null;
        int[] cell = testWorld.miningCell();
        return cell == null ? null
                : new TerrainPainter.Mining(cell[0], cell[1], testWorld.miningProgress());
    }

    /** The editor is always daylit; play-test uses the game type's lighting. */
    private void feedLighting(GameProfile p) {
        var lighting = ctx.lighting();
        if (!testing || !p.lightingEnabled || testWorld == null) {
            lighting.setDarkness(0);
            return;
        }
        double darkness = testWorld.darkness(p);
        lighting.setDarkness(darkness);
        lighting.setAmbient(p.ambientLight);
        lighting.clearLights();
        if (darkness <= 0.001) return;
        double ts = level.tileSize;
        int[] b = visibleTileBounds();
        int[] corner = new int[2];
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
        // colour, brighter the rarer the item.
        if (testWorld != null) {
            for (DroppedItem item : testWorld.items()) {
                ItemDef def = testWorld.itemTypes.get(item.key);
                if (def == null || def.rarity() == ItemDef.Rarity.COMMON) continue;
                camera.worldToScreen(item.x + DroppedItem.SIZE / 2,
                        item.y + DroppedItem.SIZE / 2, corner);
                lighting.addLight(corner[0], corner[1],
                        rarityGlowRadiusTiles(def.rarity()) * ts * camera.zoom,
                        def.rarity().color);
            }
        }
        camera.worldToScreen(testMe.x + profile().playerSize / 2.0,
                testMe.y + profile().playerSize / 2.0, corner);
        lighting.addLight(corner[0], corner[1], 2.5 * ts * camera.zoom,
                new Color(255, 240, 210));
    }

    /** Rarity glow radius in tiles: uncommon small, mythic strongest. */
    private static double rarityGlowRadiusTiles(ItemDef.Rarity rarity) {
        return 1.0 + rarity.ordinal() * 0.6;
    }

    private int[] visibleTileBounds() {
        double ts = level.tileSize;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        int[][] cornersPx = {{0, 0}, {viewportWidth, 0}, {0, viewportHeight},
                {viewportWidth, viewportHeight}};
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

    private final int[] pxs = new int[4];
    private final int[] pys = new int[4];
    private final int[] pcorner = new int[2];

    /**
     * The level's terrain, painted by the same {@link TerrainPainter} the play
     * scene uses, so what a creator is looking at while building is what the
     * level plays like — including which way its walls stand and where their
     * shadows fall.
     */
    private void drawTiles(Graphics2D g, DepthPass standing) {
        TerrainPainter.draw(g, level, camera, visibleTileBounds(), animClock,
                standing, this::drawOpenLid, miningStroke());
    }

    /** The animated lid on the chest or barrel whose panel is open. */
    private void drawOpenLid(Graphics2D g, int col, int row, int[] quadX, int[] quadY,
                             Block block, Color color) {
        if (containerPanel == null || block == null || !block.container()) return;
        if (col != containerPanel.col() || row != containerPanel.row()) return;
        ContainerPanel.drawLid(g, quadX, quadY, containerPanel.openness(), color);
    }

    /** Lift the projected quad in {@link #pys} off the floor by {@code px}. */
    private void raiseQuad(int px) {
        if (px == 0) return;
        for (int i = 0; i < pys.length; i++) pys[i] -= px;
    }

    /** How high the brush's preview stands at (col,row): where the block lands. */
    private int previewLift(int col, int row) {
        return paintLayer(col, row) == Level.LAYER_UPPER
                ? TerrainPainter.liftPixels(camera, level.tileSize) : 0;
    }

    /** How high the top-most block at (col,row) is drawn — what a hover marks. */
    private int topBlockLift(int col, int row) {
        return level.upperAt(col, row) > 0
                ? TerrainPainter.liftPixels(camera, level.tileSize) : 0;
    }

    private void projectCell(int c, int r, int ts) {
        double wx = c * (double) ts, wy = r * (double) ts;
        camera.worldToScreen(wx, wy, pcorner);
        pxs[0] = pcorner[0]; pys[0] = pcorner[1];
        camera.worldToScreen(wx + ts, wy, pcorner);
        pxs[1] = pcorner[0]; pys[1] = pcorner[1];
        camera.worldToScreen(wx + ts, wy + ts, pcorner);
        pxs[2] = pcorner[0]; pys[2] = pcorner[1];
        camera.worldToScreen(wx, wy + ts, pcorner);
        pxs[3] = pcorner[0]; pys[3] = pcorner[1];
    }

    private void drawGrid(Graphics2D g) {
        int ts = level.tileSize;
        int[] b = visibleTileBounds();
        g.setColor(new Color(255, 255, 255, 28));
        for (int c = b[0]; c <= b[2] + 1; c++) {
            double wx = c * (double) ts;
            g.drawLine(camera.worldToScreenX(wx, b[1] * (double) ts),
                    camera.worldToScreenY(wx, b[1] * (double) ts),
                    camera.worldToScreenX(wx, (b[3] + 1) * (double) ts),
                    camera.worldToScreenY(wx, (b[3] + 1) * (double) ts));
        }
        for (int r = b[1]; r <= b[3] + 1; r++) {
            double wy = r * (double) ts;
            g.drawLine(camera.worldToScreenX(b[0] * (double) ts, wy),
                    camera.worldToScreenY(b[0] * (double) ts, wy),
                    camera.worldToScreenX((b[2] + 1) * (double) ts, wy),
                    camera.worldToScreenY((b[2] + 1) * (double) ts, wy));
        }
    }

    /** Outline the level so its edges are obvious while painting. */
    private void drawWorldBounds(Graphics2D g) {
        double w = level.width * (double) level.tileSize;
        double h = level.height * (double) level.tileSize;
        g.setColor(new Color(255, 200, 90, 130));
        g.setStroke(new BasicStroke(2f));
        int[] cx = new int[4];
        int[] cy = new int[4];
        double[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
        for (int i = 0; i < 4; i++) {
            camera.worldToScreen(corners[i][0], corners[i][1], pcorner);
            cx[i] = pcorner[0];
            cy[i] = pcorner[1];
        }
        g.drawPolygon(cx, cy, 4);
    }

    /**
     * One scenery layer: the free-standing decorations plus the block details
     * painted into it, drawn by the same painters the play scene uses so what
     * a creator sees while painting is what the level plays like. Which side
     * of the terrain it lands on is the format's call — see
     * {@link PerspectiveSpace#scenerySitsBehindTerrain()} in {@code render}.
     */
    private void drawDecorLayer(Graphics2D g, boolean foreground) {
        DepthPass own = DepthPass.sorted();
        drawDecorLayer(g, foreground, own);
        own.flush();
    }

    /** One scenery layer, queued into a pass it shares with something else. */
    private void drawDecorLayer(Graphics2D g, boolean foreground, DepthPass into) {
        DecorPainter.draw(g, level, camera, foreground, animClock, into);
        SurfaceDecorPainter.draw(g, level, camera, visibleTileBounds(), foreground,
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

    /** Painted mobs/items/doors/markers: level spawns offline, snapshots online. */
    private void drawEntities(Graphics2D g, DepthPass into) {
        MobRegistry mobs = MobRegistry.standard();
        ItemRegistry items = ItemRegistry.standard();
        drawDoors(g);
        if (!testing) {
            drawMpSpawnMarkers(g);
            drawMiniGameMarkers(g);
        }
        if (testing && testWorld != null) {
            for (DroppedItem item : testWorld.items()) {
                into.at(footDepth(item.x, item.y, DroppedItem.SIZE), () ->
                        drawItemAt(g, items.get(item.key), item.x, item.y));
            }
            for (Mob m : testWorld.mobs()) {
                into.at(footDepth(m.x, m.y, m.def.size()), () ->
                        drawMobAt(g, m.def, m.x, m.y, m.facing, mobStateKey(m),
                                m.weaponKey(), m.melee.action(), m.meleeProgress()));
            }
            for (Projectile pr : testWorld.projectiles()) {
                into.at(footDepth(pr.x, pr.y, 0), () -> drawProjectileAt(g, pr));
            }
            return;
        }
        if (net != null) {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                for (EntityView e : snap.items()) {
                    into.at(footDepth(e.x, e.y, DroppedItem.SIZE), () ->
                            drawItemAt(g, items.get(e.key), e.x, e.y));
                }
                for (EntityView e : snap.mobs()) {
                    MobDef def = mobs.get(e.key);
                    if (def != null) {
                        into.at(footDepth(e.x, e.y, def.size()), () ->
                                drawMobAt(g, def, e.x, e.y, e.facing, "idle"));
                    }
                }
                drawNetPlayers(g, snap);
            }
            return;
        }
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case "mob" -> {
                    MobDef def = mobs.get(e.type);
                    // A painted-but-not-yet-live spawn faces the camera, so
                    // the editor shows the species rather than a profile.
                    if (def != null) {
                        into.at(footDepth(e.x, e.y, def.size()), () ->
                                drawMobAt(g, def, e.x, e.y, Facing.SOUTH, "idle"));
                    }
                }
                case "item" -> into.at(footDepth(e.x, e.y, DroppedItem.SIZE), () ->
                        drawItemAt(g, items.get(e.type), e.x, e.y));
                default -> { /* doors/decor/markers drawn by their own passes */ }
            }
        }
    }

    /** The skin animation state a live mob is in (feeds {@code mob/<key>/<state>}). */
    private static String mobStateKey(Mob m) {
        // A melee move takes the drawn state over while it runs.
        if (!m.meleeAction().isEmpty()) return m.meleeAction();
        if (m.hurting()) return "hurt";
        return switch (m.state) {
            case CHASE, WANDER, FLEE -> "walk";
            case ATTACK -> "attack";
            default -> "idle";
        };
    }

    /** Painted doors render as tinted door shapes with their label underneath. */
    private void drawDoors(Graphics2D g) {
        double ts = level.tileSize;
        for (Level.EntitySpawn e : level.entities) {
            if (!"door".equals(e.kind)) continue;
            DoorLink link = doors.get(e.type);
            Color tint = link != null ? link.color() : new Color(150, 105, 60);
            int dw = Math.max(8, (int) Math.round(ts * 0.9 * camera.zoom));
            int dh = Math.max(12, (int) Math.round(ts * 1.6 * camera.zoom));
            camera.worldToScreen(e.x, e.y, pcorner);
            int x = pcorner[0] - dw / 2, y = pcorner[1] - dh;
            g.setColor(tint);
            g.fillRoundRect(x, y, dw, dh, dw / 3, dw / 3);
            g.setColor(tint.darker());
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(x, y, dw, dh, dw / 3, dw / 3);
            g.setColor(new Color(255, 235, 170));
            int knob = Math.max(2, dw / 6);
            g.fillOval(x + dw - knob * 2, y + dh / 2, knob, knob);
            if (!testing && camera.zoom > 0.5) {
                g.setFont(SMALL_FONT);
                g.setColor(new Color(235, 235, 245));
                String label = link != null ? link.label() : e.type;
                g.drawString(label, x + dw / 2 - g.getFontMetrics().stringWidth(label) / 2,
                        y + dh + 12);
            }
        }
    }

    /**
     * Editor-only mini-game markers: team flag bases, stockpile crates, team
     * spawns, and the numbered escort waypoint path — all in team colours so
     * the arena reads at a glance while building it.
     */
    private void drawMiniGameMarkers(Graphics2D g) {
        double ts = level.tileSize;
        // The waypoint path draws first (under the markers): dashes + numbers.
        List<Level.EntitySpawn> path = new ArrayList<>();
        for (Level.EntitySpawn e : level.entities) {
            if (MiniGame.KIND_PATH.equals(e.kind)) path.add(e);
        }
        if (!path.isEmpty()) {
            path.sort((a, b) -> Integer.compare(
                    MiniGame.pathIndex(a.type), MiniGame.pathIndex(b.type)));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{8, 8}, 0));
            g.setColor(new Color(240, 220, 130, 150));
            int px = 0, py = 0;
            for (int i = 0; i < path.size(); i++) {
                camera.worldToScreen(path.get(i).x, path.get(i).y, pcorner);
                if (i > 0) g.drawLine(px, py, pcorner[0], pcorner[1]);
                px = pcorner[0];
                py = pcorner[1];
            }
            g.setStroke(new BasicStroke(2f));
            for (int i = 0; i < path.size(); i++) {
                camera.worldToScreen(path.get(i).x, path.get(i).y, pcorner);
                int r = Math.max(6, (int) (10 * camera.zoom));
                g.setColor(new Color(50, 46, 26, 220));
                g.fillOval(pcorner[0] - r, pcorner[1] - r, r * 2, r * 2);
                g.setColor(new Color(240, 220, 130));
                g.drawOval(pcorner[0] - r, pcorner[1] - r, r * 2, r * 2);
                g.setFont(SMALL_FONT);
                String n = path.get(i).type;
                g.drawString(n, pcorner[0] - g.getFontMetrics().stringWidth(n) / 2,
                        pcorner[1] + 4);
            }
            if (camera.zoom > 0.5) {
                camera.worldToScreen(path.get(0).x, path.get(0).y, pcorner);
                g.setColor(new Color(240, 220, 130));
                g.drawString("payload start", pcorner[0] + 12, pcorner[1] - 8);
            }
        }
        for (Level.EntitySpawn e : level.entities) {
            int team = Team.fromMarkerType(e.type);
            switch (e.kind) {
                case MiniGame.KIND_FLAG -> {
                    if (team < 0) break;
                    Color c = Team.color(team);
                    camera.worldToScreen(e.x, e.y, pcorner);
                    int h = Math.max(10, (int) (ts * 0.9 * camera.zoom));
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                    g.fillOval(pcorner[0] - h / 2, pcorner[1] - h / 6, h, h / 3);
                    g.setColor(new Color(230, 230, 240));
                    g.setStroke(new BasicStroke(2.5f));
                    g.drawLine(pcorner[0], pcorner[1], pcorner[0], pcorner[1] - h);
                    g.setColor(c);
                    g.fillPolygon(
                            new int[]{pcorner[0], pcorner[0] + (int) (h * 0.65), pcorner[0]},
                            new int[]{pcorner[1] - h, pcorner[1] - h + h / 4,
                                    pcorner[1] - h + h / 2}, 3);
                    if (camera.zoom > 0.5) {
                        g.setFont(SMALL_FONT);
                        g.drawString(Team.name(team) + " flag", pcorner[0] + 6, pcorner[1] + 12);
                    }
                }
                case MiniGame.KIND_STOCKPILE -> {
                    if (team < 0) break;
                    Color c = Team.color(team);
                    camera.worldToScreen(e.x, e.y, pcorner);
                    int s = Math.max(8, (int) (ts * 0.8 * camera.zoom));
                    g.setColor(new Color(90, 65, 40));
                    g.fillRoundRect(pcorner[0] - s / 2, pcorner[1] - s, s, s, s / 5, s / 5);
                    g.setColor(c);
                    g.setStroke(new BasicStroke(Math.max(2f, s / 10f)));
                    g.drawRoundRect(pcorner[0] - s / 2, pcorner[1] - s, s, s, s / 5, s / 5);
                    g.drawLine(pcorner[0] - s / 2, pcorner[1] - s / 2,
                            pcorner[0] + s / 2, pcorner[1] - s / 2);
                    if (camera.zoom > 0.5) {
                        g.setFont(SMALL_FONT);
                        g.drawString(Team.name(team) + " stockpile",
                                pcorner[0] + s / 2 + 4, pcorner[1] - 2);
                    }
                }
                case MiniGame.KIND_SPAWN -> {
                    if (team < 0) break;
                    Color c = Team.color(team);
                    camera.worldToScreen(e.x, e.y, pcorner);
                    int s = Math.max(6, (int) (14 * camera.zoom));
                    g.setColor(c);
                    g.setStroke(new BasicStroke(2f));
                    g.drawLine(pcorner[0], pcorner[1] - s, pcorner[0], pcorner[1] + s / 2);
                    g.fillPolygon(new int[]{pcorner[0], pcorner[0] + s, pcorner[0]},
                            new int[]{pcorner[1] - s, pcorner[1] - s + s / 3,
                                    pcorner[1] - s + 2 * s / 3}, 3);
                    g.setFont(SMALL_FONT);
                    g.drawString(Team.name(team).toLowerCase() + " spawn",
                            pcorner[0] + 4, pcorner[1] + s / 2);
                }
                default -> { /* not a mini-game marker */ }
            }
        }
    }

    /** Numbered flags where multiplayer players will spawn (editor-only). */
    private void drawMpSpawnMarkers(Graphics2D g) {
        int n = 0;
        for (Level.EntitySpawn e : level.entities) {
            if (!"mp_spawn".equals(e.kind)) continue;
            n++;
            camera.worldToScreen(e.x, e.y, pcorner);
            int s = Math.max(6, (int) (14 * camera.zoom));
            g.setColor(new Color(120, 170, 240));
            g.setStroke(new BasicStroke(2f));
            g.drawLine(pcorner[0], pcorner[1] - s, pcorner[0], pcorner[1] + s / 2);
            g.fillPolygon(new int[]{pcorner[0], pcorner[0] + s, pcorner[0]},
                    new int[]{pcorner[1] - s, pcorner[1] - s + s / 3,
                            pcorner[1] - s + 2 * s / 3}, 3);
            g.setFont(SMALL_FONT);
            g.drawString("mp " + n, pcorner[0] + 4, pcorner[1] + s / 2);
        }
    }

    /**
     * A mob at a world position, drawn for the direction it faces — the same
     * resolution the play scene uses, so what a creator sees in the editor is
     * what a player sees: this direction's sheet, its mirror twin, the state's
     * sheet, the idle sheet, then the pre-generated directional art.
     */
    private void drawMobAt(Graphics2D g, MobDef def, double x, double y,
                           Facing facing, String state) {
        drawMobAt(g, def, x, y, facing, state,
                def.weapon() == null ? "" : def.weapon(), MeleeAction.NONE, 0);
    }

    /**
     * {@link #drawMobAt} for a mob mid-melee-move: the weapon it carries gets
     * first say over how its body is drawn, and the weapon itself is drawn in
     * its hands — the same two sheets the play scene resolves.
     */
    private void drawMobAt(Graphics2D g, MobDef def, double x, double y,
                           Facing facing, String state, String weapon,
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
        int w = Math.max(6, (int) Math.round(def.size() * camera.zoom));
        camera.worldToScreen(x + def.size() / 2, y + def.size(), pcorner);
        int dx = pcorner[0] - w / 2, dy = pcorner[1] - w;
        if (mirror) {
            g.drawImage(img, dx + w, dy, -w, w, null);
        } else {
            g.drawImage(img, dx, dy, w, w, null);
        }
        if (!weapon.isEmpty()) {
            MeleeSprites.Hold hold = MeleeSprites.hold(move,
                    MeleeProfiles.ofKey(weapon), moveProgress);
            BufferedImage held = MeleeSprites.heldFrame(weapon, move.key(),
                    animClock, moveProgress);
            if (held == null) {
                ItemDef item = ItemRegistry.standard().get(weapon);
                held = item == null ? null : EntitySprites.item(item, 16);
            }
            if (held != null) {
                int iw = Math.max(5, (int) Math.round(def.size() * hold.scale()
                        * camera.zoom * 0.7));
                int flip = dir.facingLeft() ? -1 : 1;
                AffineTransform old = g.getTransform();
                g.translate(pcorner[0] + flip * hold.offsetX() * def.size() * camera.zoom,
                        pcorner[1] - w / 2.0 + hold.offsetY() * def.size() * camera.zoom);
                g.rotate(flip * hold.angle());
                g.drawImage(held, flip * -iw / 2, -iw / 2, flip * iw, iw, null);
                g.setTransform(old);
            }
        }
    }

    private void drawItemAt(Graphics2D g, ItemDef def, double x, double y) {
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + def.key(), animClock);
        if (img == null) img = EntitySprites.item(def, 16);
        int w = Math.max(5, (int) Math.round(DroppedItem.SIZE * camera.zoom));
        camera.worldToScreen(x, y, pcorner);
        int dy = 0;
        if (camera.getPerspective() != Perspective.SIDE_SCROLL) {
            // Top-down / isometric drops hover with a bob over a soft shadow.
            dy = (int) Math.round(Math.sin(animClock * 3 + (x + y) * 0.05) * w * 0.18
                    - w * 0.25);
            g.setColor(new Color(0, 0, 0, 70));
            g.fillOval(pcorner[0], pcorner[1] + w - w / 4, w, w / 2);
        }
        drawRarityHalo(g, def, pcorner[0] + w / 2, pcorner[1] + dy + w / 2, w);
        g.drawImage(img, pcorner[0], pcorner[1] + dy, w, w, null);
    }

    /**
     * The coloured halo behind an uncommon+ item: a soft radial gradient in
     * the rarity's colour, gently pulsing — visible in daylight, and matched
     * by a real point light after dark (see {@link #feedLighting}).
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

    private void drawProjectileAt(Graphics2D g, Projectile pr) {
        // Skinnable like every other texture (the Effects palette edits these);
        // the procedural bolt is the fallback.
        BufferedImage img = Skins.frame("projectile/" + pr.def.key(), animClock);
        if (img == null) img = EntitySprites.projectile(pr.def, 16);
        // A plan-view shot still in the air (a meteor on its way down) draws
        // above the floor tile it is aimed at, over a shadow that marks it.
        PerspectiveSpace space = PerspectiveSpace.of(camera.getPerspective());
        int w = Math.max(8, (int) Math.round(pr.def.radius() * 3.5 * camera.zoom
                * space.heightScale(pr.z, level.tileSize)));
        camera.worldToScreen(pr.x, pr.y, pcorner);
        int lift = (int) Math.round(pr.z * space.screenLift() * camera.zoom);
        if (lift > 0) {
            double shrink = Math.max(0.3, 1 - pr.z / (level.tileSize * 8.0));
            int sw = Math.max(3, (int) (w * 0.6 * shrink));
            g.setColor(new Color(0, 0, 0, (int) (80 * shrink)));
            g.fillOval(pcorner[0] - sw / 2, pcorner[1] - sw / 4, sw, Math.max(2, sw / 2));
        }
        var old = g.getTransform();
        g.translate(pcorner[0], pcorner[1] - lift);
        if (pr.vx != 0 || pr.vy != 0) g.rotate(Math.atan2(pr.vy, pr.vx));
        g.drawImage(img, -w / 2, -w / 2, w, w, null);
        g.setTransform(old);
    }

    /** Other players painting/playing in the same online world. */
    private void drawNetPlayers(Graphics2D g, Snapshot snap) {
        int size = profile().playerSize;
        for (PlayerState ps : snap.players()) {
            if (ps.id == net.client().localId()) continue;
            Color body = Color.getHSBColor((ps.id * 0.6180339887f) % 1f, 0.6f, 0.85f);
            camera.worldToScreen(ps.x + size / 2.0, ps.y + size, pcorner);
            int w = Math.max(6, (int) Math.round(size * camera.zoom));
            g.setColor(body);
            g.fillRoundRect(pcorner[0] - w / 2, pcorner[1] - w, w, w, w / 4, w / 4);
            if (!ps.name.isEmpty()) {
                g.setFont(SMALL_FONT);
                g.setColor(Color.WHITE);
                g.drawString(ps.name, pcorner[0] - w / 2, pcorner[1] - w - 4);
            }
        }
    }

    private void drawSpawnMarker(Graphics2D g) {
        camera.worldToScreen(level.spawnX, level.spawnY, pcorner);
        int s = Math.max(6, (int) (14 * camera.zoom));
        g.setColor(new Color(120, 220, 130));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(pcorner[0], pcorner[1] - s, pcorner[0], pcorner[1] + s / 2);
        int[] fx = {pcorner[0], pcorner[0] + s, pcorner[0]};
        int[] fy = {pcorner[1] - s, pcorner[1] - s + s / 3, pcorner[1] - s + 2 * s / 3};
        g.fillPolygon(fx, fy, 3);
        g.setFont(SMALL_FONT);
        g.drawString("spawn", pcorner[0] + 4, pcorner[1] + s / 2);
    }

    /**
     * Editor-only cutscene trigger markers: a clapper diamond, the trigger
     * radius ring for zone/interact cutscenes, and the cutscene's name.
     */
    private void drawCutsceneMarkers(Graphics2D g) {
        for (Cutscene cs : level.cutscenes) {
            if (cs.trigger == Cutscene.Trigger.LEVEL_START) continue;
            camera.worldToScreen(cs.x, cs.y, pcorner);
            int s = Math.max(6, (int) (12 * camera.zoom));
            Color tint = cs.trigger == Cutscene.Trigger.ZONE
                    ? new Color(240, 170, 90) : new Color(190, 140, 240);
            // The trigger radius, in world scale.
            int r = (int) Math.round(cs.radiusTiles * level.tileSize * camera.zoom);
            g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 40));
            g.fillOval(pcorner[0] - r, pcorner[1] - r, r * 2, r * 2);
            g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 130));
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(pcorner[0] - r, pcorner[1] - r, r * 2, r * 2);
            g.setColor(tint);
            g.setStroke(new BasicStroke(2f));
            g.fillPolygon(new int[]{pcorner[0], pcorner[0] + s, pcorner[0], pcorner[0] - s},
                    new int[]{pcorner[1] - s, pcorner[1], pcorner[1] + s, pcorner[1]}, 4);
            g.setColor(new Color(20, 20, 30));
            g.fillPolygon(new int[]{pcorner[0] - s / 3, pcorner[0] + s / 2, pcorner[0] - s / 3},
                    new int[]{pcorner[1] - s / 3, pcorner[1], pcorner[1] + s / 3}, 3);
            if (camera.zoom > 0.5) {
                g.setFont(SMALL_FONT);
                g.setColor(new Color(235, 235, 245));
                g.drawString(cs.name + (cs.trigger == Cutscene.Trigger.INTERACT ? " [E]" : ""),
                        pcorner[0] + s + 4, pcorner[1] + 4);
            }
        }
    }

    /**
     * The play-test player, drawn exactly like the play scene ("load level")
     * draws its player: the shared animated walk sprite (or the assigned
     * player skin), foot-anchored, mirrored when facing left.
     */
    /**
     * The play-test character: the same directional sprite the play scene
     * draws, lifted by a plan-view hop over its own shadow — so testing a
     * top-down level shows the jump exactly as a player will see it.
     */
    private void drawTestPlayer(Graphics2D g) {
        double size = profile().playerSize;
        // Whatever is in their hands gets first say over how they are drawn
        // doing this — the same resolution the play scene uses.
        PlayerSprites.Frame sprite = MeleeSprites.playerFrame(
                testMe.characterKey, testMeleeItem, testAnimState, testMe.facing,
                testAnimClock, testMelee.progress(), (int) size, testCharacter.body);
        camera.worldToScreen(testMe.x + size / 2.0, testMe.y + size, pcorner);
        int w = (int) Math.round(size * camera.zoom);
        int h = w;
        int dx = pcorner[0] - w / 2;
        int lift = (int) Math.round(testMe.z * camera.zoom * PlayerPhysics.HOP_DRAW_SCALE);
        if (lift > 0) {
            double shrink = Math.max(0.35, 1 - testMe.z / (size * 3));
            int sw = (int) (w * 0.7 * shrink), sh = Math.max(2, (int) (w * 0.25 * shrink));
            g.setColor(new Color(0, 0, 0, (int) (90 * shrink)));
            g.fillOval(pcorner[0] - sw / 2, pcorner[1] - sh / 2, sw, sh);
        }
        int dy = pcorner[1] - h - lift;
        if (sprite.image() != null) {
            if (sprite.mirrored()) {
                g.drawImage(sprite.image(), dx + w, dy, -w, h, null);
            } else {
                g.drawImage(sprite.image(), dx, dy, w, h, null);
            }
        }
        drawTestHeldObject(g, size, w, lift);
        if (testMelee.action() != MeleeAction.NONE || swingTime > 0) {
            // While a move runs the arc is the weapon's own reach and width;
            // otherwise it is the short mining/firing stroke it always was.
            MeleeProfile weapon = MeleeProfiles.ofKey(testMeleeItem);
            boolean move = testMelee.action() != MeleeAction.NONE;
            int r = (int) ((move ? weapon.reach() : size * 0.9) * camera.zoom);
            double arc = move ? weapon.arc() : 120;
            int start = move
                    ? (int) Math.round((testMe.facingLeft ? 180 : 0) + arc / 2
                    - arc * testMelee.progress() - arc / 4)
                    : (testMe.facingLeft ? 120 : -60);
            g.setColor(new Color(255, 255, 255, (int) (150 * Math.max(0,
                    move ? (testMelee.striking() ? 1 : 0.4) : swingTime / 0.2))));
            g.setStroke(new BasicStroke(3f));
            g.drawArc(pcorner[0] - r, pcorner[1] - w / 2 - r, r * 2, r * 2,
                    start, (int) Math.round(move ? arc / 2 : arc));
        }
    }

    /**
     * The object in the play-test player's hands, swept through the move. Its
     * sheet and its placement come from the same {@link MeleeSprites} the play
     * scene draws from, so a weapon's art is tested where it is authored.
     */
    private void drawTestHeldObject(Graphics2D g, double size, int w, int lift) {
        if (testMeleeItem.isEmpty()) return;
        MeleeAction action = testMelee.action();
        double progress = testMelee.progress();
        BufferedImage img = MeleeSprites.heldFrame(testMeleeItem, action.key(),
                animClock, progress);
        if (img == null) {
            ItemDef def = testWorld != null ? testWorld.itemTypes.get(testMeleeItem)
                    : ItemRegistry.standard().get(testMeleeItem);
            if (def == null) return;
            img = EntitySprites.item(def, 16);
        }
        MeleeSprites.Hold hold = MeleeSprites.hold(action,
                MeleeProfiles.ofKey(testMeleeItem), progress);
        int iw = Math.max(6, (int) Math.round(size * hold.scale() * camera.zoom * 0.7));
        int flip = testMe.facing != null && testMe.facing.facingLeft() ? -1 : 1;
        double cx = pcorner[0] + flip * hold.offsetX() * size * camera.zoom;
        double cy = pcorner[1] - w / 2.0 - lift + hold.offsetY() * size * camera.zoom;
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(flip * hold.angle());
        g.drawImage(img, flip * -iw / 2, -iw / 2, flip * iw, iw, null);
        g.setTransform(old);
    }

    /** Ghost of what a click would paint, under the cursor. */
    private void drawCursorPreview(Graphics2D g) {
        Entry entry = selectedEntry();
        if (entry == null || dialog != Dialog.NONE || mouseX < SIDEBAR_W) return;
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        int ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);

        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        switch (entry.kind) {
            case "block" -> {
                Block b = level.blocks.get(entry.key);
                if (b != null) {
                    // The preview stands where the block would land — on the
                    // floor of a bare cell, on top of whatever is already in a
                    // full one — so a creator sees the wall they are about to
                    // build rather than a flat square that turns out to be one.
                    for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                        projectCell(cell[0], cell[1], ts);
                        raiseQuad(previewLift(cell[0], cell[1]));
                        g.setColor(b.color());
                        g.fillPolygon(pxs, pys, 4);
                    }
                    projectCell(col, row, ts);
                    raiseQuad(previewLift(col, row));
                    g.setColor(Color.WHITE);
                    g.drawPolygon(pxs, pys, 4);
                }
            }
            case "surface" -> {
                // Highlight the face the click would decorate.
                SurfaceDecor def = SurfaceDecorRegistry.standard().get(entry.key);
                if (def != null && level.tileAt(col, row) > 0) {
                    SurfaceDecor.Face face = surfaceFaceMode >= 1
                            ? SurfaceDecor.Face.values()[surfaceFaceMode - 1]
                            : autoFace(def, aim[0], aim[1], col, row);
                    if (face != null && def.allows(face)) {
                        projectCell(col, row, ts);
                        g.setColor(new Color(160, 240, 160));
                        g.setStroke(new BasicStroke(3f));
                        switch (face) {
                            case UP -> g.drawLine(pxs[0], pys[0], pxs[1], pys[1]);
                            case RIGHT -> g.drawLine(pxs[1], pys[1], pxs[2], pys[2]);
                            case DOWN -> g.drawLine(pxs[2], pys[2], pxs[3], pys[3]);
                            case LEFT -> g.drawLine(pxs[3], pys[3], pxs[0], pys[0]);
                        }
                    }
                }
            }
            case "mob" -> {
                MobDef def = MobRegistry.standard().get(entry.key);
                if (def != null) drawMobAt(g, def, aim[0], aim[1], Facing.SOUTH, "idle");
            }
            case "item" -> drawItemAt(g, ItemRegistry.standard().get(entry.key), aim[0], aim[1]);
            case "decor" -> {
                Decor def = DecorRegistry.standard().get(entry.key);
                if (def != null) {
                    BufferedImage img = EntitySprites.decor(def, 64);
                    int size = Math.max(8,
                            (int) Math.round(def.sizeTiles() * ts * camera.zoom));
                    camera.worldToScreen(aim[0], aim[1], pcorner);
                    g.drawImage(img, pcorner[0] - size / 2, pcorner[1] - size, size, size, null);
                }
            }
            case "door" -> {
                DoorLink link = doors.get(entry.key);
                Color tint = link != null ? link.color() : new Color(150, 105, 60);
                int dw = Math.max(8, (int) Math.round(ts * 0.9 * camera.zoom));
                int dh = Math.max(12, (int) Math.round(ts * 1.6 * camera.zoom));
                camera.worldToScreen(aim[0], aim[1], pcorner);
                g.setColor(tint);
                g.fillRoundRect(pcorner[0] - dw / 2, pcorner[1] - dh, dw, dh, dw / 3, dw / 3);
            }
            case "eraser" -> {
                g.setColor(new Color(230, 100, 120));
                g.setStroke(new BasicStroke(2f));
                for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                    projectCell(cell[0], cell[1], ts);
                    // Outline the block that would actually come off — the top
                    // of the stack, which is the one standing up.
                    raiseQuad(topBlockLift(cell[0], cell[1]));
                    g.drawPolygon(pxs, pys, 4);
                }
            }
            case "spawn" -> {
                camera.worldToScreen(aim[0], aim[1], pcorner);
                g.setColor(new Color(120, 220, 130));
                g.drawOval(pcorner[0] - 8, pcorner[1] - 8, 16, 16);
            }
            case "mp_spawn" -> {
                camera.worldToScreen(aim[0], aim[1], pcorner);
                g.setColor(new Color(120, 170, 240));
                g.drawOval(pcorner[0] - 8, pcorner[1] - 8, 16, 16);
            }
            case MiniGame.KIND_FLAG, MiniGame.KIND_STOCKPILE, MiniGame.KIND_SPAWN -> {
                camera.worldToScreen(aim[0], aim[1], pcorner);
                g.setColor(Team.color(Team.fromMarkerType(entry.key)));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(pcorner[0] - 8, pcorner[1] - 8, 16, 16);
            }
            case MiniGame.KIND_PATH -> {
                camera.worldToScreen(aim[0], aim[1], pcorner);
                g.setColor(new Color(240, 220, 130));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(pcorner[0] - 8, pcorner[1] - 8, 16, 16);
                g.setFont(SMALL_FONT);
                g.drawString(Integer.toString(countKind(MiniGame.KIND_PATH) + 1),
                        pcorner[0] - 3, pcorner[1] + 4);
            }
            case "cutscene" -> {
                Cutscene cs = cutsceneByKey(entry.key);
                if (cs != null && cs.trigger != Cutscene.Trigger.LEVEL_START) {
                    int r = (int) Math.round(cs.radiusTiles * ts * camera.zoom);
                    camera.worldToScreen(aim[0], aim[1], pcorner);
                    g.setColor(new Color(240, 170, 90));
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval(pcorner[0] - r, pcorner[1] - r, r * 2, r * 2);
                }
            }
            default -> { /* generate/managedoors are buttons; nothing to preview */ }
        }
        g.setComposite(old);
    }

    private void drawSidebar(Graphics2D g) {
        g.setColor(new Color(14, 14, 22, 235));
        g.fillRect(0, 0, SIDEBAR_W, viewportHeight);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawLine(SIDEBAR_W, 0, SIDEBAR_W, viewportHeight);

        g.setFont(TITLE_FONT);
        g.setColor(Color.WHITE);
        g.drawString("Palette", 12, 22);

        // Category tabs.
        g.setFont(HUD_FONT);
        int y = 34;
        for (Category c : Category.values()) {
            boolean active = c == category;
            if (active) {
                g.setColor(new Color(255, 220, 120, 40));
                g.fillRoundRect(6, y, SIDEBAR_W - 12, 20, 6, 6);
            }
            g.setColor(active ? new Color(255, 220, 120) : new Color(180, 180, 195));
            g.drawString(UiText.fit(g.getFontMetrics(),
                            categoryName(c) + "  (" + palette.get(c).size() + ")",
                            SIDEBAR_W - 26),
                    14, y + 15);
            y += 22;
        }

        int gridTop = paletteGridTop();
        // Decor layer toggle row.
        if (category == Category.DECOR) {
            g.setColor(new Color(255, 255, 255, 22));
            g.fillRoundRect(6, gridTop - 22, SIDEBAR_W - 12, 20, 6, 6);
            g.setFont(SMALL_FONT);
            g.setColor(decorForeground ? new Color(255, 190, 120) : new Color(150, 200, 255));
            g.drawString("Layer: " + (decorForeground ? "FOREGROUND" : "BACKGROUND")
                    + "  (click / B)", 14, gridTop - 8);
        }
        // SURFACE option rows: face / open-closed condition / layer.
        if (category == Category.SURFACE) {
            g.setFont(SMALL_FONT);
            String[] rows = {
                    "Face: " + surfaceFaceLabel(),
                    "Show: " + surfaceVisibilityLabel(),
                    "Layer: " + (surfaceForeground ? "FOREGROUND" : "BACKGROUND"),
            };
            Color[] tints = {
                    new Color(200, 220, 160),
                    new Color(160, 200, 235),
                    surfaceForeground ? new Color(255, 190, 120) : new Color(150, 200, 255),
            };
            for (int i = 0; i < rows.length; i++) {
                int y0 = gridTop - 66 + i * 22;
                g.setColor(new Color(255, 255, 255, 22));
                g.fillRoundRect(6, y0, SIDEBAR_W - 12, 20, 6, 6);
                g.setColor(tints[i]);
                g.drawString(rows[i] + " (click)", 14, y0 + 14);
            }
        }

        // Swatch grid.
        int cellPad = (SIDEBAR_W - CELLS_PER_ROW * CELL) / (CELLS_PER_ROW + 1);
        List<Entry> entries = palette.get(category);
        int firstRow = scroll.get(category);
        int visRows = visiblePaletteRows();
        for (int rowI = 0; rowI < visRows; rowI++) {
            for (int colI = 0; colI < CELLS_PER_ROW; colI++) {
                int idx = (rowI + firstRow) * CELLS_PER_ROW + colI;
                if (idx >= entries.size()) break;
                Entry e = entries.get(idx);
                int cx = cellPad + colI * (CELL + cellPad);
                int cy = gridTop + rowI * (CELL + 12);
                boolean sel = idx == selected.get(category);
                g.setColor(new Color(255, 255, 255, sel ? 50 : 16));
                g.fillRoundRect(cx, cy, CELL, CELL, 8, 8);
                if (sel) {
                    g.setColor(new Color(255, 220, 120));
                    g.setStroke(new BasicStroke(2f));
                    g.drawRoundRect(cx, cy, CELL, CELL, 8, 8);
                }
                g.drawImage(e.icon, cx + (CELL - 40) / 2, cy + (CELL - 40) / 2, null);
                if (e.custom) {
                    // User-created objects wear a green corner badge; their
                    // texture dialog (right-click) offers deletion.
                    g.setColor(new Color(110, 220, 140));
                    g.fillPolygon(new int[]{cx + CELL - 15, cx + CELL, cx + CELL},
                            new int[]{cy + 1, cy + 1, cy + 16}, 3);
                    g.setColor(new Color(10, 40, 20));
                    g.setFont(SMALL_FONT);
                    g.drawString("+", cx + CELL - 9, cy + 10);
                }
            }
        }

        if (net == null) drawSizeSliders(g);

        // Selected entry name + hints at the bottom.
        Entry sel = selectedEntry();
        g.setColor(new Color(10, 10, 16));
        g.fillRect(0, viewportHeight - 36, SIDEBAR_W, 36);
        g.setColor(new Color(255, 220, 120));
        g.setFont(HUD_FONT);
        // Entry names are content — a custom object is named by its creator —
        // so the name is cut to the sidebar rather than run out over the canvas.
        g.drawString(UiText.fit(g.getFontMetrics(),
                        sel != null ? sel.name + (sel.custom ? " · custom" : "") : "",
                        SIDEBAR_W - 20),
                10, viewportHeight - 20);
        g.setColor(new Color(150, 150, 165));
        g.setFont(SMALL_FONT);
        g.drawString("right-click icon = texture · Tab category", 10, viewportHeight - 6);
    }

    /** Tooltip geometry: how wide the body wraps and what it insets by. */
    private static final int TIP_WRAP_W = 320, TIP_PAD = 12, TIP_LINE_H = 15;

    /**
     * Hover tooltip beside the sidebar: the hovered palette entry's name plus
     * a description of what it does, so every palette item explains itself.
     *
     * <p>The box is sized from the text but bounded by the window — the wrap
     * width shrinks on a narrow window, the line count is capped to what fits
     * below the sidebar's top, and the box is nudged back inside if the cursor
     * is near an edge — so a wordy entry never paints off the screen.
     */
    private void drawPaletteTooltip(Graphics2D g) {
        if (mouseX >= SIDEBAR_W) return;
        List<Entry> entries = palette.get(category);
        int idx = paletteIndexAt(mouseX, mouseY);
        if (idx < 0 || idx >= entries.size()) return;
        Entry e = entries.get(idx);
        String desc = describeEntry(e);
        if (desc == null || desc.isBlank()) return;

        int x = SIDEBAR_W + 10;
        int wrapW = Math.min(TIP_WRAP_W, viewportWidth - x - 2 * TIP_PAD - 10);
        if (wrapW < 80) return; // no room for a tooltip at all
        int maxLines = Math.max(1, (viewportHeight - 96) / TIP_LINE_H);

        g.setFont(SMALL_FONT);
        FontMetrics bodyFm = g.getFontMetrics();
        List<String> lines = UiText.wrap(bodyFm, desc, wrapW, maxLines);
        int bodyW = 0;
        for (String line : lines) bodyW = Math.max(bodyW, bodyFm.stringWidth(line));

        g.setFont(HUD_FONT);
        FontMetrics titleFm = g.getFontMetrics();
        String title = UiText.fit(titleFm, e.name + (e.custom ? "  (custom)" : ""), wrapW);
        int w = Math.max(titleFm.stringWidth(title), bodyW) + 2 * TIP_PAD;
        int h = 30 + lines.size() * TIP_LINE_H + 8;
        x = Math.min(x, viewportWidth - w - 8);
        int y = Math.max(8, Math.min(mouseY - 12, viewportHeight - h - 8));

        g.setColor(new Color(12, 12, 20, 235));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(255, 220, 120));
        g.drawString(title, x + TIP_PAD, y + 19);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(205, 205, 220));
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), x + TIP_PAD, y + 36 + i * TIP_LINE_H);
        }
    }

    /**
     * What a palette entry does: definitions describe themselves from their
     * data ({@link PaletteInfo}, covering custom creations automatically),
     * and the editor-specific entries (tools, doors, markers…) explain their
     * editor behaviour.
     */
    private String describeEntry(Entry e) {
        switch (e.kind) {
            case "block" -> {
                Block b = level.blocks.get(e.key);
                return b != null ? PaletteInfo.describe(b) : "";
            }
            case "mob" -> {
                MobDef d = MobRegistry.standard().get(e.key);
                return d != null ? PaletteInfo.describe(d) : "";
            }
            case "item" -> {
                ItemDef d = ItemRegistry.standard().get(e.key);
                return d != null ? PaletteInfo.describe(d) : "";
            }
            case "decor" -> {
                Decor d = DecorRegistry.standard().get(e.key);
                return d != null ? PaletteInfo.describe(d) : "";
            }
            case "surface" -> {
                SurfaceDecor d = SurfaceDecorRegistry.standard().get(e.key);
                return d != null ? PaletteInfo.describe(d) : "";
            }
            case "door" -> {
                DoorLink link = doors.get(e.key);
                return "Door — press E at it in play to travel to "
                        + (link != null && !link.targetLevel().isEmpty()
                        ? "level \"" + link.targetLevel() + "\"."
                        : "another level (no target set yet — see Manage Doors…).");
            }
            case "cutscene" -> {
                Cutscene cs = cutsceneByKey(e.key);
                if (cs == null) return "";
                return switch (cs.trigger) {
                    case ZONE -> "Cutscene — plays when the player walks within "
                            + (int) cs.radiusTiles + " tiles of its marker.";
                    case INTERACT -> "Cutscene — plays when the player presses E within "
                            + (int) cs.radiusTiles + " tiles of its marker.";
                    default -> "Cutscene — plays automatically when the level starts.";
                };
            }
            case "new" -> {
                return "Build your own " + e.name.replace("+ New ", "").toLowerCase()
                        + " — set its properties and it joins this palette live,"
                        + " saved with the game type.";
            }
            case MiniGame.KIND_FLAG -> {
                return "Capture the Flag marker — this team's flag base. Steal the"
                        + " enemy flag and run it home to score.";
            }
            case MiniGame.KIND_STOCKPILE -> {
                return "Stockpile marker — this team's crate: deposit the configured"
                        + " resource items here to score.";
            }
            case MiniGame.KIND_SPAWN -> {
                return "Team spawn marker — players on this team appear here during"
                        + " the mini game.";
            }
            case MiniGame.KIND_PATH -> {
                return "Escort waypoint — the payload travels the waypoints in order;"
                        + " escort it to the last one to win.";
            }
            case "mg_settings" -> {
                return "Opens Mini Game Setup: pick this level's mode (Capture the"
                        + " Flag, Stockpile, Battle, Escort) and its rules.";
            }
            case "spawn" -> {
                return "Sets where the player starts — click the canvas to move the"
                        + " spawn point.";
            }
            case "mp_spawn" -> {
                return "Adds a multiplayer spawn point — hosted games scatter joining"
                        + " players across these.";
            }
            case "playerskin" -> {
                return "Reskin the player: a sprite sheet per action state"
                        + " (idle, walk, run, jump, fall, swim), and per facing"
                        + " if you want one.";
            }
            case "character" -> {
                CharacterProfile c = Characters.get(e.key);
                if (c == null) return "";
                Ultimate u = c.ultimate();
                return "Playable character — " + c.summary()
                        + (u == null ? "." : ".  Ultimate: " + u.description())
                        + "  Right-click for sprite sheets.";
            }
            case "roster" -> {
                return "Picks which characters this level offers when it starts."
                        + " Tick none and it offers every one of them.";
            }
            case "particle" -> {
                return "The " + e.name.toLowerCase() + " particle effect — click to"
                        + " give it your own sprite sheet. Without one it keeps the"
                        + " engine's built-in fleck.";
            }
            case "projectile" -> {
                return "The " + e.name + " projectile — click to give it your own"
                        + " sprite sheet. Without one it keeps its built-in art.";
            }
            case "eraser" -> {
                return "Removes what you click — entities first, then surface"
                        + " details, then blocks (uses the brush shape and size).";
            }
            case "brush" -> {
                return "Opens Brush Settings: the stroke shape, its size, and the"
                        + " multi-block mix painted per stamp.";
            }
            case "generate" -> {
                return "Opens the level generator: Perlin terrain with caves, ores"
                        + " and liquids, or a top-down maze.";
            }
            case "rules" -> {
                return "Opens Stat Rules: programmable triggers over tracked stats"
                        + " (\"mined 50 blocks → reward…\") that run in play.";
            }
            case "sunlight" -> {
                return "Sets where the sun stands over this level, and so which"
                        + " way every stacked block throws its shadow.";
            }
            case "managedoors" -> {
                return "Opens the door manager: name doors and link each to another"
                        + " level of this game type.";
            }
            case "managecutscenes" -> {
                return "Opens the cutscene manager: script triggerable scenes with"
                        + " actors, animation states, and step scripts.";
            }
            default -> {
                return "";
            }
        }
    }

    /**
     * The sidebar's bottom panel: brush shape/size controls, the live level
     * width/height sliders, and the "override map size" button that unlocks
     * them past {@value #STANDARD_MAX_SIZE} (up to 65536, exponential scale).
     */
    private void drawSizeSliders(Graphics2D g) {
        int top = sliderPanelTop();
        g.setColor(new Color(10, 10, 16, 220));
        g.fillRect(0, top, SIDEBAR_W, SLIDER_PANEL_H);
        g.setColor(new Color(255, 255, 255, 30));
        g.drawLine(0, top, SIDEBAR_W, top);
        g.setFont(SMALL_FONT);

        // Brush row: shape button + size slider ([ ] keys too).
        g.setColor(new Color(200, 200, 215));
        g.drawString("Brush", 10, top + 14);
        brushShapeBox.setBounds(48, top + 4, 64, 14);
        g.setColor(new Color(255, 255, 255, 34));
        g.fillRoundRect(brushShapeBox.x, brushShapeBox.y,
                brushShapeBox.width, brushShapeBox.height, 6, 6);
        g.setColor(new Color(255, 220, 120));
        g.drawString(Brush.label(brushShape), brushShapeBox.x + 6, brushShapeBox.y + 11);
        drawOneSlider(g, 2, top + 24, "S",
                brushSize, Brush.MIN_SIZE, Brush.MAX_SIZE, false);

        g.setColor(new Color(200, 200, 215));
        g.drawString("Level size (drag)", 10, top + 44);
        int shownW = draggingSizeSlider == 0 ? pendingLevelW : level.width;
        int shownH = draggingSizeSlider == 1 ? pendingLevelH : level.height;
        drawOneSlider(g, 0, top + 54, "W", shownW, MIN_LEVEL_W, maxLevelSize(), true);
        drawOneSlider(g, 1, top + 78, "H", shownH, MIN_LEVEL_H, maxLevelSize(), true);

        // Override button.
        overrideButtonBox.setBounds(8, top + 98, SIDEBAR_W - 16, 18);
        g.setColor(overrideMapSize ? new Color(255, 190, 100, 60)
                : new Color(255, 255, 255, 26));
        g.fillRoundRect(overrideButtonBox.x, overrideButtonBox.y,
                overrideButtonBox.width, overrideButtonBox.height, 8, 8);
        g.setColor(overrideMapSize ? new Color(255, 200, 110) : new Color(180, 180, 200));
        g.drawString(overrideMapSize
                        ? "Override map size: ON (max 65536)"
                        : "Override map size: off (max " + STANDARD_MAX_SIZE + ")",
                overrideButtonBox.x + 8, overrideButtonBox.y + 13);
    }

    private void drawOneSlider(Graphics2D g, int index, int y, String label,
                               int value, int min, int max, boolean sizeScale) {
        int trackX = 26, trackW = SIDEBAR_W - 26 - 48;
        sliderTracks[index].setBounds(trackX, y, trackW, 8);
        g.setColor(new Color(200, 200, 215));
        g.drawString(label, 10, y + 8);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRoundRect(trackX, y + 2, trackW, 4, 4, 4);
        double t = sizeScale ? sizeSliderT(value, min, max)
                : Math.max(0, Math.min(1, (value - min) / (double) (max - min)));
        g.setColor(draggingSizeSlider == index
                ? new Color(255, 220, 120) : new Color(160, 180, 220));
        g.fillRoundRect(trackX, y + 2, (int) (trackW * t), 4, 4, 4);
        g.fillOval(trackX + (int) (trackW * t) - 5, y - 1, 10, 10);
        g.setColor(new Color(220, 220, 235));
        g.drawString(String.valueOf(value), trackX + trackW + 6, y + 8);
    }

    private void drawTopBar(Graphics2D g) {
        int x0 = testing ? 0 : SIDEBAR_W;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(x0, 0, viewportWidth - x0, 28);
        g.setColor(Color.WHITE);
        g.setFont(HUD_FONT);
        String chunkInfo = level.isChunked()
                ? " · chunked: " + level.chunked.loadedCount() + " loaded / "
                + level.chunked.dirtyCount() + " edited"
                : "";
        String bar;
        // The editor is one creative mode per level format, so the bar leads
        // with which one is open.
        String mode = format().displayName().toUpperCase();
        if (testing) {
            bar = "PLAY-TEST (" + mode + ") — " + level.name + chunkInfo
                    + "   ·   WASD move · Shift sprint · hold click to mine · right-click place"
                    + " · 1-5 hotbar · [I] inventory · [E] doors/stations · [P]/[Esc] editor";
        } else if (net != null) {
            bar = mode + " CREATIVE (ONLINE) — painting the server's world   ·   [Tab] category"
                    + " · right-click erase · [G] grid · [Esc] back to game";
        } else {
            // The undo count is on the bar because it is the answer to "can I
            // try this?" — it says how far back the editor can still walk.
            String undo = history.canUndo()
                    ? " · [Ctrl+Z] undo " + history.undoLabel()
                    + (history.undoDepth() > 1 ? " (" + history.undoDepth() + ")" : "")
                    : "";
            String redo = history.canRedo() ? " · [Ctrl+Y] redo" : "";
            bar = mode + " CREATIVE — " + level.name + " (" + level.width + "x" + level.height + ")"
                    + chunkInfo
                    + "   ·   [Tab] category · right-click erase · middle pick · [B] layer"
                    + " · [ ] brush · [G] grid · [P] test · [Ctrl+S] save · [L] load · [N] new"
                    + undo + redo + " · [Esc] menu";
        }
        g.drawString(bar, x0 + 12, 19);
    }

    // --- test-mode HUD ---------------------------------------------------------------

    private void drawTestHotbar(Graphics2D g) {
        int slot = 44, pad = 5;
        int total = Inventory.HOTBAR * (slot + pad) - pad;
        int x0 = (viewportWidth - total) / 2;
        int y0 = viewportHeight - slot - 10;
        for (int i = 0; i < Inventory.HOTBAR; i++) {
            int x = x0 + i * (slot + pad);
            boolean sel = testInv.selectedIndex() == i;
            g.setColor(new Color(0, 0, 0, sel ? 200 : 140));
            g.fillRoundRect(x, y0, slot, slot, 8, 8);
            g.setColor(sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 70));
            g.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g.drawRoundRect(x, y0, slot, slot, 8, 8);
            drawStack(g, testInv.slot(i), x, y0, slot);
            g.setColor(new Color(255, 255, 255, 130));
            g.setFont(SMALL_FONT);
            g.drawString(String.valueOf(i + 1), x + 4, y0 + 12);
        }
        // The selected item's name floats above the bar in its rarity colour.
        ItemDef sel = testInv.selectedDef();
        if (sel != null) {
            g.setFont(HUD_FONT);
            int tw = g.getFontMetrics().stringWidth(sel.name());
            int nx = (viewportWidth - tw) / 2, ny = y0 - 10;
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRoundRect(nx - 8, ny - 14, tw + 16, 20, 8, 8);
            g.setColor(sel.rarity().color);
            g.drawString(sel.name(), nx, ny);
        }
    }

    private void drawTestHealthBar(Graphics2D g) {
        int w = 180, h = 14;
        int x = 12, y = viewportHeight - 28;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x - 2, y - 2, w + 4, h + 4, 6, 6);
        g.setColor(new Color(120, 30, 30));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(220, 60, 60));
        g.fillRect(x, y, (int) (w * Math.max(0, testMe.health / PlayerState.MAX_HEALTH)), h);
        g.setColor(Color.WHITE);
        g.setFont(SMALL_FONT);
        g.drawString((int) Math.ceil(testMe.health) + " / " + (int) PlayerState.MAX_HEALTH,
                x + w / 2 - 20, y + 11);
    }

    /** Stamina (green, sprint/jump) and mana (blue, magic) above the health bar. */
    private void drawTestResourceBars(Graphics2D g) {
        int w = 180, h = 8;
        int x = 12;
        drawResourceBar(g, x, viewportHeight - 40, w, h,
                testMe.stamina / PlayerState.MAX_STAMINA,
                new Color(40, 90, 40), new Color(110, 220, 110));
        drawResourceBar(g, x, viewportHeight - 52, w, h,
                testMe.mana / PlayerState.MAX_MANA,
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

    /**
     * The level's programmable stat bars (rules marked "show bar"): live
     * progress toward each rule's next firing, top-right of the play-test HUD.
     */
    private void drawStatRuleBars(Graphics2D g) {
        if (ruleEngine == null || testStats == null) return;
        int w = 170, h = 10;
        int x = viewportWidth - w - 14, y = 40;
        g.setFont(SMALL_FONT);
        for (StatRule rule : level.statRules) {
            if (!rule.showBar()) continue;
            double t = ruleEngine.progress(rule, testStats);
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x - 4, y - 13, w + 8, h + 18, 6, 6);
            g.setColor(new Color(210, 210, 225));
            String label = PlayerStats.label(rule.stat()) + "  "
                    + (long) testStats.get(rule.stat()) + " / " + (long) rule.threshold()
                    + (rule.repeat() && ruleEngine.firedCount(rule) > 0
                    ? " ×" + ruleEngine.firedCount(rule) : "");
            g.drawString(label, x, y - 3);
            g.setColor(new Color(70, 60, 30));
            g.fillRect(x, y, w, h);
            g.setColor(t >= 1 ? new Color(150, 230, 150) : new Color(240, 200, 90));
            g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, t))), h);
            y += h + 22;
        }
    }

    /** "[E] Enter …" prompt while standing at a door in play-test. */
    private void drawDoorHint(Graphics2D g) {
        double half = profile().playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(testMe.x + half, testMe.y + half,
                level.tileSize * 1.3);
        if (door == null) return;
        DoorLink link = doors.get(door.type);
        String text = link == null ? "[E] Door (unlinked)"
                : link.targetLevel().isEmpty() ? "[E] " + link.label() + " (no target)"
                : "[E] Enter " + link.label();
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(text);
        int x = (viewportWidth - tw) / 2, y = viewportHeight - 88;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8);
        g.setColor(new Color(255, 230, 160));
        g.drawString(text, x, y);
    }

    // Inventory panel geometry (mirrors the play scene's).
    private static final int INV_SLOT = 46;
    private static final int INV_PAD = 6;

    /**
     * Centred alone; shifted left of centre while a container is open so the
     * two panels sit side by side instead of overlapping.
     */
    private int[] inventoryOrigin() {
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;
        int x = containerPanel != null
                ? ContainerPanel.pairedInventoryLeft(viewportWidth) + 20
                : (viewportWidth - gw) / 2;
        return new int[]{x, (viewportHeight - gh) / 2};
    }

    private int inventorySlotAt(int sx, int sy) {
        int[] o = inventoryOrigin();
        int col = Math.floorDiv(sx - o[0], INV_SLOT + INV_PAD);
        int row = Math.floorDiv(sy - o[1], INV_SLOT + INV_PAD);
        if (col < 0 || col >= Inventory.COLS || row < 0 || row >= Inventory.ROWS) return -1;
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

    private void drawTestInventory(Graphics2D g) {
        int[] o = inventoryOrigin();
        int x0 = o[0], y0 = o[1];
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;

        g.setColor(new Color(10, 10, 16, 220));
        g.fillRoundRect(x0 - 20, y0 - 52, gw + 40, gh + 84, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("Inventory (play-test)", x0, y0 - 24);
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
            boolean sel = i == testInv.selectedIndex();
            g.setColor(new Color(255, 255, 255, hotbar ? 36 : 18));
            g.fillRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8);
            g.setColor(sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g.drawRoundRect(cx, cy, INV_SLOT, INV_SLOT, 8, 8);
            if (i == cursorSlot) continue;
            drawStack(g, testInv.slot(i), cx, cy, INV_SLOT);
        }

        if (cursorSlot >= 0) {
            ItemStack held = testInv.slot(cursorSlot);
            if (held == null) {
                cursorSlot = -1;
            } else {
                drawStack(g, held, mouseX - INV_SLOT / 2, mouseY - INV_SLOT / 2, INV_SLOT);
            }
        }
    }

    private void drawStack(Graphics2D g, ItemStack stack, int x, int y, int slot) {
        if (stack == null) return;
        ItemDef def = testWorld.itemTypes.get(stack.key);
        if (def == null) return;
        BufferedImage img = Skins.frame("item/" + def.key(), animClock);
        if (img == null) img = EntitySprites.item(def, 32);
        g.drawImage(img, x + 6, y + 6, slot - 12, slot - 12, null);
        PlayScene.drawDurabilityBar(g, def, stack, x, y, slot);
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

    private void drawStatus(Graphics2D g) {
        if (statusTime <= 0 || status.isEmpty()) return;
        g.setFont(HUD_FONT);
        int tw = g.getFontMetrics().stringWidth(status);
        int x = (viewportWidth + (testing ? 0 : SIDEBAR_W) - tw) / 2;
        int y = viewportHeight - 18;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(x - 10, y - 16, tw + 20, 24, 8, 8);
        g.setColor(new Color(235, 235, 245));
        g.drawString(status, x, y);
    }

    private void drawDialog(Graphics2D g) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
        g.setColor(new Color(8, 8, 14));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        g.setComposite(old);
        dialogForm.render(g, viewportWidth, viewportHeight);
        g.setColor(new Color(130, 130, 150));
        g.setFont(SMALL_FONT);
        g.drawString("Enter activates · Esc cancels · type to edit text fields",
                24, viewportHeight - 16);
    }

    private void setStatus(String msg) {
        status = msg;
        statusTime = 3.5;
    }

    private static String categoryName(Category c) {
        return switch (c) {
            case BLOCKS -> "Blocks";
            case LIQUIDS -> "Liquids";
            case LIGHTS -> "Lights";
            case MOBS -> "Mobs";
            case ITEMS -> "Items";
            case DECOR -> "Decor";
            case SURFACE -> "Surface";
            case DOORS -> "Doors";
            case CHARACTERS -> "Characters";
            case EFFECTS -> "Effects";
            case SOUNDS -> "Sounds";
            case CUTSCENES -> "Cutscenes";
            case MINIGAME -> "Mini Game";
            case TOOLS -> "Tools";
        };
    }

    // --- palette tool icons -----------------------------------------------------------

    /** The player character as it currently looks (assigned skin or default). */
    private static BufferedImage playerSkinIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        PlayerSprites.Frame frame = PlayerSprites.directionalFrame("", "idle",
                Facing.SOUTH_EAST, 0, 40, PlayerSprites.DEFAULT_BODY);
        if (frame.image() != null) g.drawImage(frame.image(), 0, 0, 40, 40, null);
        g.dispose();
        return img;
    }

    private static BufferedImage spawnIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 220, 130));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(14, 6, 14, 34);
        g.fillPolygon(new int[]{14, 32, 14}, new int[]{6, 12, 18}, 3);
        g.dispose();
        return img;
    }

    private static BufferedImage mpSpawnIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 170, 240));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(10, 6, 10, 34);
        g.fillPolygon(new int[]{10, 26, 10}, new int[]{6, 11, 16}, 3);
        g.setColor(new Color(170, 200, 250));
        g.drawLine(24, 14, 24, 36);
        g.fillPolygon(new int[]{24, 38, 24}, new int[]{14, 19, 24}, 3);
        g.dispose();
        return img;
    }

    /** A film clapperboard for cutscene palette entries. */
    private static BufferedImage cutsceneIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 42, 56));
        g.fillRoundRect(5, 14, 30, 20, 6, 6);
        // The striped clap bar, tilted open.
        g.rotate(-0.18, 8, 14);
        g.setColor(new Color(230, 230, 240));
        g.fillRect(5, 6, 30, 8);
        g.setColor(new Color(40, 42, 56));
        for (int i = 0; i < 4; i++) g.fillRect(7 + i * 8, 6, 4, 8);
        g.rotate(0.18, 8, 14);
        // A play triangle on the board.
        g.setColor(new Color(255, 220, 120));
        g.fillPolygon(new int[]{16, 27, 16}, new int[]{18, 24, 30}, 3);
        g.dispose();
        return img;
    }

    private static BufferedImage eraserIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(230, 130, 150));
        g.fillRoundRect(8, 14, 24, 14, 6, 6);
        g.setColor(new Color(240, 240, 245));
        g.fillRoundRect(8, 8, 24, 10, 6, 6);
        g.dispose();
        return img;
    }

    /** A paint brush for the Brush Settings window. */
    private static BufferedImage brushIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 160, 90));
        g.setStroke(new BasicStroke(4f));
        g.drawLine(26, 6, 12, 24);
        g.setColor(new Color(150, 200, 240));
        g.fillOval(6, 22, 12, 12);
        g.setColor(new Color(110, 220, 150));
        g.fillOval(20, 28, 8, 8);
        g.setColor(new Color(240, 200, 110));
        g.fillOval(28, 20, 7, 7);
        g.dispose();
        return img;
    }

    private static BufferedImage generateIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        // A little mountain-and-dice motif for "roll new terrain".
        g.setColor(new Color(140, 200, 150));
        g.fillPolygon(new int[]{4, 16, 28}, new int[]{32, 12, 32}, 3);
        g.setColor(new Color(110, 160, 220));
        g.fillPolygon(new int[]{18, 30, 40}, new int[]{32, 16, 32}, 3);
        g.setColor(new Color(255, 220, 120));
        g.fillOval(28, 4, 9, 9);
        g.dispose();
        return img;
    }

    private static BufferedImage doorIcon(Color tint) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(tint);
        g.fillRoundRect(11, 4, 18, 34, 6, 6);
        g.setColor(tint.darker());
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(11, 4, 18, 34, 6, 6);
        g.setColor(new Color(255, 235, 170));
        g.fillOval(23, 20, 4, 4);
        g.dispose();
        return img;
    }

    private static BufferedImage manageDoorsIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 200, 215));
        g.setStroke(new BasicStroke(2.5f));
        for (int i = 0; i < 3; i++) {
            g.drawLine(6, 10 + i * 10, 12, 10 + i * 10);
            g.drawLine(17, 10 + i * 10, 34, 10 + i * 10);
        }
        g.dispose();
        return img;
    }

    /** The "+" creator entry every creatable category leads with. */
    private static BufferedImage plusIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 220, 150));
        g.setStroke(new BasicStroke(4f));
        g.drawLine(20, 8, 20, 32);
        g.drawLine(8, 20, 32, 20);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(2, 2, 36, 36, 10, 10);
        g.dispose();
        return img;
    }

    /** A trophy for the Mini Game Setup window. */
    private static BufferedImage minigameSettingsIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(240, 200, 90));
        g.fillArc(8, 6, 24, 22, 180, 180);
        g.setStroke(new BasicStroke(3f));
        g.drawArc(4, 8, 10, 10, 90, 180);
        g.drawArc(26, 8, 10, 10, 270, 180);
        g.fillRect(17, 24, 6, 6);
        g.fillRoundRect(11, 30, 18, 5, 3, 3);
        g.dispose();
        return img;
    }

    /** A team-coloured flag for CTF flag-base palette entries. */
    private static BufferedImage flagIcon(Color c) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(230, 230, 240));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(12, 4, 12, 36);
        g.setColor(c);
        g.fillPolygon(new int[]{12, 34, 12}, new int[]{4, 10, 18}, 3);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 90));
        g.fillOval(4, 32, 18, 6);
        g.dispose();
        return img;
    }

    /** A team-coloured crate for Stockpile deposit palette entries. */
    private static BufferedImage stockpileIcon(Color c) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(90, 65, 40));
        g.fillRoundRect(8, 12, 24, 22, 6, 6);
        g.setColor(c);
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(8, 12, 24, 22, 6, 6);
        g.drawLine(8, 23, 32, 23);
        g.setColor(new Color(255, 235, 170));
        g.fillOval(18, 18, 4, 4);
        g.dispose();
        return img;
    }

    /** A team-coloured spawn flag for team spawn palette entries. */
    private static BufferedImage teamSpawnIcon(Color c) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(14, 6, 14, 34);
        g.fillPolygon(new int[]{14, 32, 14}, new int[]{6, 12, 18}, 3);
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
        g.drawOval(6, 30, 16, 6);
        g.dispose();
        return img;
    }

    /** A numbered waypoint dot for the escort path. */
    private static BufferedImage waypointIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(240, 220, 130));
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{5, 5}, 0));
        g.drawLine(4, 32, 16, 20);
        g.drawLine(24, 12, 36, 6);
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(new Color(50, 46, 26));
        g.fillOval(12, 10, 16, 16);
        g.setColor(new Color(240, 220, 130));
        g.drawOval(12, 10, 16, 16);
        g.drawLine(20, 14, 20, 22);
        g.dispose();
        return img;
    }

    /** A tiny bar chart for the Stat Rules tool. */

    /** Sound Editor… icon: a speaker throwing waves. */
    private static BufferedImage soundEditorIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(235, 215, 150));
        g.fillRect(6, 16, 6, 8);                        // the speaker's throat
        g.fillPolygon(new int[]{12, 20, 20, 12}, new int[]{16, 8, 32, 24}, 4);
        g.setColor(new Color(120, 210, 255));
        g.setStroke(new BasicStroke(2f));
        for (int i = 0; i < 3; i++) {                   // three widening waves
            int r = 8 + i * 7;
            g.drawArc(16 - r / 2, 20 - r, r, r * 2, -60, 120);
        }
        g.dispose();
        return img;
    }

    /** Sound Options… icon: the speaker with a level slider under it. */
    private static BufferedImage soundOptionsIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(235, 215, 150));
        g.fillRect(6, 12, 5, 7);
        g.fillPolygon(new int[]{11, 18, 18, 11}, new int[]{12, 6, 25, 19}, 4);
        g.setColor(new Color(120, 210, 255));
        g.setStroke(new BasicStroke(2f));
        g.drawArc(18, 8, 10, 15, -70, 140);
        g.setColor(new Color(150, 150, 170));           // the slider track
        g.fillRoundRect(5, 30, 30, 4, 4, 4);
        g.setColor(new Color(255, 220, 120));           // and its thumb
        g.fillOval(21, 27, 10, 10);
        g.dispose();
        return img;
    }

    /** Level Music… icon: a pair of beamed notes. */
    private static BufferedImage levelMusicIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(180, 160, 255));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(14, 8, 14, 28);
        g.drawLine(29, 5, 29, 25);
        g.drawLine(14, 8, 29, 5);                       // the beam joining them
        g.fillOval(7, 25, 11, 8);
        g.fillOval(22, 22, 11, 8);
        g.dispose();
        return img;
    }

    /**
     * A group's icon: the speaker, tinted per family so the sound palette
     * reads at a glance rather than showing twenty identical speakers.
     */
    private static BufferedImage soundGroupIcon(String category) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        Color tint = soundGroupColor(category);
        g.setColor(tint);
        g.fillRect(7, 16, 6, 8);
        g.fillPolygon(new int[]{13, 21, 21, 13}, new int[]{16, 9, 31, 24}, 4);
        g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 150));
        g.setStroke(new BasicStroke(2f));
        g.drawArc(20, 12, 10, 16, -70, 140);
        g.drawArc(24, 7, 12, 26, -70, 140);
        g.dispose();
        return img;
    }

    /** The colour a sound family is drawn in — the palette's own hues. */
    private static Color soundGroupColor(String category) {
        return switch (category) {
            case "Player" -> new Color(110, 190, 255);
            case "Characters" -> new Color(150, 200, 255);
            case "Ultimate abilities" -> new Color(255, 150, 90);
            case "Blocks" -> new Color(190, 170, 140);
            case "Liquids" -> new Color(110, 180, 235);
            case "Lights" -> new Color(255, 220, 130);
            case "Mobs" -> new Color(150, 220, 140);
            case "Items" -> new Color(230, 200, 120);
            case "Projectiles" -> new Color(255, 190, 110);
            case "Decorations" -> new Color(120, 190, 120);
            case "Block decorations" -> new Color(170, 210, 150);
            case "Vehicles" -> new Color(200, 160, 220);
            case "Particles" -> new Color(255, 245, 150);
            case "Music" -> new Color(180, 160, 255);
            case "Ambience" -> new Color(140, 200, 200);
            case "World" -> new Color(200, 200, 215);
            case "Doors" -> new Color(200, 170, 130);
            case "Cutscenes" -> new Color(230, 170, 200);
            case "Mini games" -> new Color(255, 170, 170);
            default -> new Color(210, 210, 225);
        };
    }

    /** Stat Rules… icon: bars rising over a baseline. */
    /** A sun with a block casting its shadow — the light-direction tool. */
    private static BufferedImage sunlightIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 220, 120));
        g.fillOval(4, 4, 12, 12);
        g.setStroke(new BasicStroke(1.6f));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            g.drawLine(10 + (int) (Math.cos(a) * 8), 10 + (int) (Math.sin(a) * 8),
                    10 + (int) (Math.cos(a) * 11), 10 + (int) (Math.sin(a) * 11));
        }
        g.setColor(new Color(0, 0, 0, 110));
        g.fillPolygon(new int[]{22, 36, 36, 22}, new int[]{30, 30, 36, 36}, 4);
        g.setColor(new Color(150, 160, 180));
        g.fillRect(18, 20, 12, 12);
        g.dispose();
        return img;
    }

    private static BufferedImage rulesIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(240, 200, 90));
        g.fillRect(7, 22, 7, 12);
        g.fillRect(17, 14, 7, 20);
        g.fillRect(27, 6, 7, 28);
        g.setColor(new Color(150, 230, 150));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(5, 36, 36, 36);
        g.dispose();
        return img;
    }

    /** Level Roster… icon: a line-up of characters with one picked out. */
    private static BufferedImage rosterIcon() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        Color[] tint = {new Color(90, 110, 150), new Color(110, 190, 255),
                new Color(90, 110, 150)};
        for (int i = 0; i < 3; i++) {
            int x = 4 + i * 12;
            int top = i == 1 ? 8 : 12; // the chosen one stands forward
            g.setColor(tint[i]);
            g.fillOval(x + 1, top, 8, 8);
            g.fillRoundRect(x, top + 9, 10, 15, 4, 4);
        }
        g.setColor(new Color(110, 190, 255));
        g.setStroke(new BasicStroke(2f));
        g.drawRect(15, 5, 12, 30);
        g.dispose();
        return img;
    }

    /** Palette icon for a surface decoration: a block edge wearing the detail. */
    private static BufferedImage surfaceIcon(SurfaceDecor d) {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(105, 82, 58));
        g.fillRect(4, 22, 32, 14);
        g.setColor(new Color(90, 70, 50));
        g.drawRect(4, 22, 32, 14);
        g.setColor(d.primary());
        g.setStroke(new BasicStroke(2f));
        switch (d.style()) {
            case HANGING_MOSS, ICICLES, DRIP, ROOTS -> {
                for (int i = 0; i < 4; i++) {
                    g.drawLine(9 + i * 8, 22, 9 + i * 8, 12 - (i % 2) * 4);
                }
            }
            case COBWEB -> {
                g.drawOval(12, 6, 16, 16);
                g.drawLine(20, 6, 20, 22);
                g.drawLine(12, 14, 28, 14);
            }
            case MUSHROOMS -> {
                g.fillArc(8, 12, 10, 8, 0, 180);
                g.fillArc(22, 10, 12, 10, 0, 180);
                g.setColor(d.secondary());
                g.fillRect(12, 16, 3, 6);
                g.fillRect(27, 15, 3, 7);
            }
            case CRYSTALS -> {
                g.fillPolygon(new int[]{10, 16, 13}, new int[]{22, 22, 8}, 3);
                g.setColor(d.secondary());
                g.fillPolygon(new int[]{22, 30, 26}, new int[]{22, 22, 6}, 3);
            }
            default -> { // grass/flower tufts and twigs reach upward
                for (int i = 0; i < 5; i++) {
                    g.setColor(i % 2 == 0 ? d.primary() : d.secondary());
                    g.drawLine(8 + i * 6, 22, 8 + i * 6 + (i % 3 - 1) * 3, 10 + (i % 2) * 4);
                }
            }
        }
        g.dispose();
        return img;
    }
}
