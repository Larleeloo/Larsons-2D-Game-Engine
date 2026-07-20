package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager.Sfx;
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
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.CutscenePainter;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SurfaceDecorPainter;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Brush;
import com.larsons.engine.level.Cutscene;
import com.larsons.engine.level.CutsceneDirector;
import com.larsons.engine.level.CutscenePlayer;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
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
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
import com.larsons.engine.world.World;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p><b>Textures:</b> right-click any palette icon to assign a sprite sheet
 * to that block/item/mob/decoration (per animation state for mobs) via the
 * {@link Skins} system; assignments persist in {@code skins.json}. The Tools
 * palette's Player Skin… entry does the same for the player character, with
 * one animation per action state (idle/walk/run/jump/fall/swim) played back
 * in play-test and play.
 *
 * <p><b>Generate:</b> the Tools palette's Generate button builds a large
 * Perlin-noise level — Minecraft-style terrain/caves/ores/liquids fused with
 * a connected Metroidvania room network ({@link LevelGenerator}).
 *
 * <p><b>Play-test:</b> {@code P} drops a player at the spawn and simulates
 * the painted world with the real physics/mob/item code — including a full
 * inventory (hold-to-mine against block durability with tool speed-ups,
 * pick up items, place from the hotbar, eat, shoot on mana, sprint on
 * stamina, craft at stations with {@code E}) and door travel; {@code P}/Esc
 * returns to editing with the terrain restored (test-mode mining isn't
 * destructive).
 *
 * <p><b>Online:</b> opened from a multiplayer session (pause menu), the same
 * editor paints into the <em>server's</em> world: block strokes and
 * mob/item paints become protocol requests, the authoritative results
 * broadcast to every player, and other players appear live while you paint.
 * Save/load/test/resize and the non-replicated markers stay disabled online.
 *
 * <p>Controls: WASD/arrows pan · wheel zoom (over canvas) / scroll palette
 * (over sidebar) · Tab category · left paint · right erase (canvas) /
 * texture (palette) · middle pick · B decor layer · G grid · P test ·
 * Ctrl+S save · L load · N new · Esc menu/back.
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

    /** What the palette can paint. */
    private enum Category { BLOCKS, LIQUIDS, LIGHTS, MOBS, ITEMS, DECOR, SURFACE, DOORS,
        CUTSCENES, MINIGAME, TOOLS }

    private enum Dialog { NONE, NEW_LEVEL, SAVE, LOAD, CONFIRM_EXIT, GENERATE, DOORS, TEXTURE,
        CUSTOM, RULES, BRUSH, CUTSCENES, CUTSCENE_ACTORS, CUTSCENE_STEPS, MINIGAME }

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
    private int mouseX, mouseY; // sampled in update, used by the render preview
    private String status = "";
    private double statusTime;
    private double animClock; // drives skinned sprite animation

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
    private Category customCategory = Category.BLOCKS;
    private String cName = "";
    private int cR = 150, cG = 150, cB = 150;      // primary colour
    private int cR2 = 90, cG2 = 90, cB2 = 90;      // accent colour
    private boolean cSolid = true, cFlying, cFalling;
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
    private Perspective pendingPerspective = Perspective.SIDE_SCROLL;
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
    private double prevHealth = PlayerState.MAX_HEALTH;
    private final Particles particles = new Particles();
    // Play-test stat tracking + programmable rules + crafting.
    private PlayerStats testStats;
    private StatRuleEngine ruleEngine;
    private CutsceneDirector cutsceneDirector; // runs the level's cutscenes
    private CraftingPanel craftingPanel; // non-null while a station UI is open
    private double prevTestVy;
    // The exact same walk sprite the play scene uses, so the play-test
    // character is identical to the one "load level" play loads. The action
    // state (idle/walk/run/jump/fall/swim) picks which skin animation plays;
    // its clock resets on every state change.
    private Animation testWalkAnim;
    private String testAnimState = "idle";
    private double testAnimClock;

    public CreativeScene(GameContext ctx) {
        this.ctx = ctx;
    }

    private GameProfile profile() { return ctx.profile(); }

    @Override
    public void onEnter() {
        net = ctx.session();
        testing = false;
        dialog = Dialog.NONE;
        doors = new DoorDirectory(profile().name);
        // Custom objects created with the "+" palette entries must be
        // registered before any level referencing them loads.
        customContent = new CustomContentStore(profile().name);
        customContent.loadAndRegister();

        if (net != null && net.client().level() != null) {
            level = net.client().level(); // paint straight into the shared world
        } else {
            net = null;
            level = loadInitialLevel();
            // Edit (and play-test) with the level's own saved feature toggles.
            ctx.applyLevelSettings(level.settings);
        }
        // After the level: the CUTSCENES palette lists the level's cutscenes.
        buildPalette();
        pendingLevelW = level.width;
        pendingLevelH = level.height;
        pendingPerspective = profile().perspective;

        // Each level carries its own perspective, so the editor becomes a
        // side-scroll / top-down / isometric creative mode to match — the
        // blocks paint the same, but obstruct the player per the perspective.
        camera = new Camera(net != null ? profile().perspective : level.perspective,
                viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = 1.0;
        camera.centerOn(level.spawnX, level.spawnY);
        setStatus(net == null
                ? "Creative Mode — left-click paints; right-click a palette icon to retexture it"
                : "Creative Mode (online) — painting edits the server's world for everyone");
    }

    private Level loadInitialLevel() {
        String last = profile().lastLevelPath;
        if (last != null && !last.isEmpty() && Files.exists(Path.of(last))) {
            try {
                return LevelLoader.load(last);
            } catch (RuntimeException e) {
                System.err.println("CreativeScene: failed to load " + last + ": " + e.getMessage());
            }
        }
        return starterLevel("New Level", 60, 24);
    }

    /** A fresh canvas with a ground floor, so play-testing has somewhere to stand. */
    private Level starterLevel(String name, int widthTiles, int heightTiles) {
        return starterLevel(name, widthTiles, heightTiles, profile().perspective);
    }

    /**
     * A fresh canvas in an explicit perspective. Side-scroll gets a ground
     * floor to stand on; top-down / isometric canvases get a wall border
     * instead (there is no gravity to fall by, and walls read as the level's
     * edge in those creative modes).
     */
    private Level starterLevel(String name, int widthTiles, int heightTiles,
                               Perspective perspective) {
        Level lvl = Level.empty(name, widthTiles, heightTiles, profile().tileSize);
        lvl.perspective = perspective;
        if (perspective == Perspective.SIDE_SCROLL) {
            int dirt = lvl.blocks.get("dirt").id();
            int grass = lvl.blocks.get("grass").id();
            // Giant canvases only floor the first 2048 columns eagerly; painting
            // further out is up to the creator (a 65536-wide floor loop would
            // materialize every chunk up front).
            int floored = Math.min(lvl.width, 2048);
            for (int c = 0; c < floored; c++) {
                lvl.setTile(c, lvl.height - 1, dirt);
                lvl.setTile(c, lvl.height - 2, grass);
            }
            lvl.spawnX = lvl.tileSize * 3;
            lvl.spawnY = (lvl.height - 4) * (double) lvl.tileSize;
        } else {
            int wall = lvl.blocks.get("stone_wall").id();
            int bw = Math.min(lvl.width, 2048), bh = Math.min(lvl.height, 2048);
            for (int c = 0; c < bw; c++) {
                lvl.setTile(c, 0, wall);
                lvl.setTile(c, bh - 1, wall);
            }
            for (int r = 0; r < bh; r++) {
                lvl.setTile(0, r, wall);
                lvl.setTile(bw - 1, r, wall);
            }
            lvl.spawnX = lvl.tileSize * 2;
            lvl.spawnY = lvl.tileSize * 2;
        }
        return lvl;
    }

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
                    EntitySprites.block(b, 40), customContent.isCustom("block", b.key()));
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
            mobs.add(new Entry("mob", d.key(), d.displayName(), EntitySprites.mob(d, 40),
                    customContent.isCustom("mob", d.key())));
        }
        palette.put(Category.MOBS, mobs);

        List<Entry> items = newList("+ New Item");
        for (ItemDef d : ItemRegistry.standard().allByRarity()) {
            items.add(new Entry("item", d.key(), d.name(), EntitySprites.item(d, 40),
                    customContent.isCustom("item", d.key())));
        }
        palette.put(Category.ITEMS, items);

        List<Entry> decor = newList("+ New Decoration");
        for (Decor d : DecorRegistry.standard().all()) {
            decor.add(new Entry("decor", d.key(), d.name(), EntitySprites.decor(d, 40),
                    customContent.isCustom("decor", d.key())));
        }
        palette.put(Category.DECOR, decor);

        List<Entry> surface = newList("+ New Block Decor");
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            surface.add(new Entry("surface", d.key(), d.name(), surfaceIcon(d),
                    customContent.isCustom("surface", d.key())));
        }
        palette.put(Category.SURFACE, surface);

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
        tools.add(new Entry("rules", "rules", "Stat Rules…", rulesIcon()));
        palette.put(Category.TOOLS, tools);

        for (Category c : Category.values()) {
            palette.putIfAbsent(c, new ArrayList<>());
            selected.putIfAbsent(c, 0);
            scroll.putIfAbsent(c, 0);
        }
    }

    /** A fresh palette list starting with the "+" creator entry. */
    private static List<Entry> newList(String label) {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("new", "new", label, plusIcon()));
        return list;
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

        if (testing) {
            updateTest(dt, input);
            return;
        }
        if (dialog != Dialog.NONE) {
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
            if (input.isMouseJustPressed()) {
                handlePaletteClick(input.getMouseX(), input.getMouseY());
            }
            if (input.isRightMouseJustPressed()) {
                handlePaletteRightClick(input.getMouseX(), input.getMouseY());
            }
            lastPaintCol = lastPaintRow = Integer.MIN_VALUE;
            return;
        }

        double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
        double ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);

        if (input.isMiddleMouseJustPressed()) {
            pickBlock(col, row);
        }
        if (input.isMouseDown()) {
            Entry entry = selectedEntry();
            boolean paintable = entry != null
                    && (entry.kind.equals("block") || entry.kind.equals("eraser"));
            boolean newCell = col != lastPaintCol || row != lastPaintRow;
            if (input.isMouseJustPressed() || (paintable && newCell)) {
                paintAt(entry, aim[0], aim[1], col, row, input.isMouseJustPressed());
                lastPaintCol = col;
                lastPaintRow = row;
            }
        } else {
            lastPaintCol = lastPaintRow = Integer.MIN_VALUE;
        }
        if (input.isRightMouseDown()) {
            eraseAt(aim[0], aim[1], col, row);
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
                        level.resize(w, h);
                        setStatus("Level resized to " + level.width + "x" + level.height
                                + (level.isChunked() ? " (chunked storage)" : ""));
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

    // --- painting ----------------------------------------------------------------

    private Entry selectedEntry() {
        List<Entry> entries = palette.get(category);
        int i = selected.get(category);
        return i >= 0 && i < entries.size() ? entries.get(i) : null;
    }

    private void paintAt(Entry entry, double wx, double wy, int col, int row, boolean firstClick) {
        if (entry == null) return;
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
                    if (level.tileAt(cell[0], cell[1]) == paintId) continue;
                    if (net != null) {
                        net.client().sendBlockEdit(cell[0], cell[1], paintId, "paint");
                        painted = true;
                    } else if (level.setTile(cell[0], cell[1], paintId)) {
                        painted = true;
                    }
                }
                if (painted && net == null) {
                    ctx.sfx(Sfx.PLACE);
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
        level.surfaceDecor.removeIf(sd -> sd.col() == col && sd.row() == row && sd.face() == f);
        level.surfaceDecor.add(new SurfaceDecor.Placement(col, row, face, def.key(),
                surfaceForeground, surfaceVisibility));
        ctx.sfx(Sfx.CLICK);
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
                level.entities.remove(near);
                if (MiniGame.KIND_PATH.equals(near.kind)) renumberWaypoints();
                ctx.sfx(Sfx.CLICK);
                setStatus("Erased " + near.type);
                return;
            }
            if (level.tileAt(col, row) > 0) {
                SurfaceDecor.Placement sd = surfaceDecorAt(col, row);
                if (sd != null) {
                    level.surfaceDecor.remove(sd);
                    ctx.sfx(Sfx.CLICK);
                    setStatus("Erased surface detail");
                    return;
                }
            }
            boolean broke = false;
            for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                if (level.tileAt(cell[0], cell[1]) != 0
                        && level.setTile(cell[0], cell[1], 0)) {
                    broke = true;
                }
            }
            if (broke) ctx.sfx(Sfx.BREAK);
        } else {
            EntityView near = nearestNetEntity(wx, wy);
            if (near != null) {
                net.client().sendEntityErase(near.id);
                return;
            }
            if (level.tileAt(col, row) != 0) {
                net.client().sendBlockEdit(col, row, 0, "paint");
            }
        }
    }

    private void pickBlock(int col, int row) {
        Block b = level.blockAt(col, row);
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
                case "mg_settings" -> {
                    if (net == null) openDialog(Dialog.MINIGAME);
                    else setStatus("The mini game is configured before hosting, offline");
                }
                case "playerskin" -> openPlayerSkinDialog();
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
        if (!skinnable(e.kind)) {
            setStatus("No texture override for " + e.name);
            return;
        }
        if ("playerskin".equals(e.kind)) {
            openPlayerSkinDialog(); // per-action-state, with legacy migration
            return;
        }
        texEntry = e;
        texStates = e.kind.equals("mob")
                ? List.of("idle", "walk", "attack", "hurt") : List.of("default");
        texStateIndex = 0;
        loadTextureFields();
        openDialog(Dialog.TEXTURE);
    }

    private static boolean skinnable(String kind) {
        return switch (kind) {
            case "block", "mob", "item", "decor", "surface", "playerskin" -> true;
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

    private void enterTest() {
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
        testWalkAnim = PlayerSprites.walkAnimation(profile().playerSize,
                PlayerSprites.DEFAULT_BODY);
        testAnimState = "idle";
        testAnimClock = 0;
    }

    private void bindTestPickups() {
        testWorld.setPickupListener((p, key, n) -> {
            if (profile().itemsEnabled) testInv.add(key, n);
            if (testStats != null) testStats.add("items_picked_up", n);
            ctx.sfx(Sfx.PICKUP);
        });
    }

    private void exitTest() {
        testing = false;
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
        camera.tileSize = level.tileSize;
        setStatus("Back to editing");
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
        in.jump = input.isKeyJustPressed(KeyEvent.VK_W)
                || input.isKeyJustPressed(KeyEvent.VK_UP)
                || input.isKeyJustPressed(KeyEvent.VK_SPACE);
        testInv.applyPassivesTo(testMe, p.itemsEnabled);
        double preX = testMe.x, preY = testMe.y;
        // Play-test simulates in the level's own perspective, so a top-down
        // maze tests as a top-down maze even inside a side-scroll game type.
        PlayerPhysics.step(testMe, in, level, p, level.perspective, dt);
        // Stat tracking: distance in world px, jump take-offs.
        testStats.add("distance_traveled", Math.abs(testMe.x - preX) + Math.abs(testMe.y - preY));
        if (prevTestVy >= 0 && testMe.vy < 0) testStats.add("jumps", 1);
        prevTestVy = testMe.vy;
        testWalkAnim.update(testMe.moving ? dt : 0);
        // Play-test classifies the action in the level's own perspective,
        // exactly like the play scene, so the same skin animations play.
        String state = PlayerSprites.actionState(testMe, level, p,
                level.perspective, in.sprint);
        if (!state.equals(testAnimState)) {
            testAnimState = state;
            testAnimClock = 0;
        } else {
            testAnimClock += dt;
        }

        testWorld.step(dt, List.of(testMe), p);
        testStats.add("mobs_killed", testWorld.pollKills());
        testStats.add("deaths", testWorld.pollDeaths());
        for (World.Impact im : testWorld.pollImpacts()) {
            ctx.sfx(im.explosion() ? Sfx.BOOM : Sfx.HIT);
            if (p.particlesEnabled) {
                particles.burst(im.x(), im.y(), new Color(255, 200, 120),
                        im.explosion() ? 18 : 6);
            }
        }
        if (testMe.health < prevHealth - 0.01) {
            testStats.add("damage_taken", prevHealth - testMe.health);
            ctx.sfx(Sfx.HURT);
        }
        prevHealth = testMe.health;

        // Programmable map-maker rules ("mined 50 blocks → reward…").
        for (StatRuleEngine.Fired fired : ruleEngine.update(testStats, testInv)) {
            ctx.sfx(Sfx.PICKUP);
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
        } else {
            testWorld.cancelMining();
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
            ctx.sfx(Sfx.PICKUP);
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
                ctx.sfx(Sfx.EAT);
            } else if (def != null && def.heal() > 0 && testMe.health < PlayerState.MAX_HEALTH
                    && testInv.consumeSelected()) {
                // Food heals directly and restores stamina (and mana for
                // rare delicacies) — World.applyFood.
                World.applyFood(testMe, def);
                prevHealth = testMe.health;
                ctx.sfx(Sfx.EAT);
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
            drop.toss(testMe.facingLeft ? -170 : 170, -180);
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
            Block mined = testWorld.continueMining(col, row, held, p.itemsEnabled, dt);
            if (mined != null) {
                testStats.add("blocks_mined", 1);
                ctx.sfx(Sfx.BREAK);
                if (p.particlesEnabled) {
                    particles.burst((col + 0.5) * ts, (row + 0.5) * ts, mined.color(), 10);
                }
                if (p.itemsEnabled && held != null && held.toolClass() != null
                        && testInv.damageSelected(1)) {
                    ctx.sfx(Sfx.BREAK);
                    setStatus(held.name() + " broke!");
                }
            }
        } else {
            testWorld.cancelMining();
        }

        if (!input.isMouseJustPressed()) return;
        if (shoots) {
            swingTime = 0.1;
            if (testWorld.playerShoot(testMe, testInv, aim[0], aim[1]) != null) {
                testStats.add("shots_fired", 1);
                ctx.sfx(Sfx.SHOOT);
            }
            return;
        }
        if (miningNow) return; // the held stroke handles it
        // Destructible decorations (trees → logs + leaves…) before mob swings.
        if (inReach) {
            boolean axe = held != null && "axe".equals(held.toolClass());
            World.ChopResult res = testWorld.chopDecor(aim[0], aim[1], axe, p.itemsEnabled);
            if (res != World.ChopResult.NONE) {
                swingTime = 0.2;
                ctx.sfx(res == World.ChopResult.BROKEN ? Sfx.BREAK : Sfx.HIT);
                if (p.particlesEnabled) {
                    particles.burst(aim[0], aim[1], new Color(110, 85, 50),
                            res == World.ChopResult.BROKEN ? 14 : 5);
                }
                return;
            }
        }
        if (p.combatEnabled) {
            swingTime = 0.2;
            double damage = World.FIST_DAMAGE + (held != null ? held.damage() : 0);
            Mob hit = testWorld.playerAttack(testMe, aim[0], aim[1], damage);
            if (hit != null) {
                ctx.sfx(Sfx.HIT);
                if (p.particlesEnabled) {
                    particles.burst(hit.x + hit.def.size() / 2,
                            hit.y + hit.def.size() / 2, hit.def.body(), 8);
                }
            }
        }
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
        // Liquids accept placement — covering water is how it's removed.
        if (b == null || (level.tileAt(col, row) != 0 && level.liquidAt(col, row) == null)) {
            return;
        }
        double size = p.playerSize;
        boolean overlapsMe = testMe.x + size > col * ts && testMe.x < (col + 1) * ts
                && testMe.y + size > row * ts && testMe.y < (row + 1) * ts;
        if (b.solid() && overlapsMe) return;
        if (testWorld.placeBlock(col, row, b.id())) {
            if (p.itemsEnabled) testInv.consumeSelected();
            testStats.add("blocks_placed", 1);
            ctx.sfx(Sfx.PLACE);
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
        camera.tileSize = level.tileSize;
        startTestWorld();
        bindTestPickups(); // inventory carries through the door
        ruleEngine = new StatRuleEngine(List.copyOf(level.statRules));
        cutsceneDirector = new CutsceneDirector(level.cutscenes);
        ctx.sfx(Sfx.CLICK);
        setStatus("Entered \"" + link.label() + "\" → " + level.name);
        return true;
    }

    // --- dialogs -------------------------------------------------------------------

    private void openDialog(Dialog d) {
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
            default -> "";
        }).theme(MenuTheme.dark());

        switch (d) {
            case NEW_LEVEL -> {
                if (!dialogRebuild) {
                    pendingName = "New Level";
                    pendingWidth = 60;
                    pendingHeight = 24;
                    pendingPerspective = profile().perspective;
                }
                dialogRebuild = false;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                // Each level keeps its own perspective — the creative mode
                // (and how blocks obstruct the player) follows it.
                dialogForm.addEnum("Perspective", Perspective.values(),
                        () -> pendingPerspective, v -> pendingPerspective = v);
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
                    level = starterLevel(pendingName, pendingWidth, pendingHeight,
                            pendingPerspective);
                    afterLevelSwap();
                    setStatus("Created \"" + level.name + "\" (" + level.width + "x"
                            + level.height + ", " + level.perspective
                            + (level.isChunked() ? ", chunked" : "") + ")");
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
                        level = store.load(name);
                        profile().lastLevelPath = store.fileFor(name).toString();
                        ctx.save();
                        afterLevelSwap();
                        setStatus("Loaded \"" + level.name + "\"");
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
                dialogForm.addAction("2 teams steal each other's flag "
                        + "(paint both Flag Bases anywhere)", () -> { });
                dialogForm.addInt("Captures to win", () -> cfg.scoreLimit,
                        v -> cfg.scoreLimit = v, 1, 50, 1);
                dialogForm.addToggle("PvP (players can fight)", () -> cfg.pvp,
                        v -> cfg.pvp = v);
            }
            case STOCKPILE -> {
                dialogForm.addAction("Teams race to bank resources at their "
                        + "Stockpile marker", () -> { });
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
                dialogForm.addAction("Team deathmatch — everyone spawns with "
                        + "magic weapons and tools", () -> { });
                dialogForm.addInt("Teams", () -> cfg.teams, v -> cfg.teams = v, 2, Team.MAX, 1);
                dialogForm.addInt("Kills to win", () -> cfg.scoreLimit,
                        v -> cfg.scoreLimit = v, 1, 100, 1);
                dialogForm.addAction("PvP: always ON in Battle", () -> { });
            }
            case ESCORT -> {
                dialogForm.addAction("Red escorts the payload along the "
                        + "waypoint path; Blue stops them", () -> { });
                dialogForm.addInt("Round time (seconds)", () -> cfg.escortTimeSec,
                        v -> cfg.escortTimeSec = v, 30, 1800, 30);
                dialogForm.addToggle("PvP (players can fight)", () -> cfg.pvp,
                        v -> cfg.pvp = v);
            }
            default -> dialogForm.addAction(
                    "No mini game — this plays as a normal level", () -> { });
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
        dialogForm.addAction("Block 1: " + primary + " (the palette selection)", () -> { });
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
            default -> "Block";
        };
    }

    /**
     * Snapshot the active feature toggles into the level so they save with it
     * — the level is what carries settings now, not the game type. Skipped
     * online, where the server (not a saved file) owns the world.
     */
    private void captureLevelSettings() {
        if (net == null) level.settings = profile().copy();
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
        closeDialog();
    }

    private void buildGenerateForm() {
        if (!dialogRebuild) {
            pendingName = "Generated " + (1 + (int) (Math.random() * 8999));
            genSeed = 1 + (int) (Math.random() * 99998);
            pendingPerspective = profile().perspective;
            // Maze mode fits top-down / isometric themes; terrain fits
            // side-scrollers — default the mode to match the perspective.
            genMaze = pendingPerspective != Perspective.SIDE_SCROLL;
        }
        dialogRebuild = false;
        dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
        dialogForm.addEnum("Perspective", Perspective.values(),
                () -> pendingPerspective, v -> {
                    pendingPerspective = v;
                    genMaze = v != Perspective.SIDE_SCROLL;
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
            if (genMaze) {
                level = LevelGenerator.generateMaze(name, genWidth, genHeight,
                        profile().tileSize, genSeed, pendingPerspective);
                afterLevelSwap();
                setStatus("Generated maze \"" + level.name + "\" (" + level.width + "x"
                        + level.height + ", seed " + genSeed
                        + ") — chests, torches, mobs; the gold key waits at the far end");
                return;
            }
            Level generated = LevelGenerator.generate(name,
                    genWidth, genHeight, profile().tileSize, genSeed);
            generated.perspective = pendingPerspective;
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
            doors.put(new DoorLink(key, doorLabel.isBlank() ? key : doorLabel.trim(),
                    target, DOOR_COLORS[doorColorIndex]));
            buildPalette();
            closeDialog();
            setStatus("Door \"" + doorLabel + "\" saved to " + doors.file());
        });
        if (editing != null) {
            dialogForm.addAction("Delete Door", () -> {
                doors.remove(editing.key());
                buildPalette();
                doorEditIndex = 0;
                closeDialog();
                setStatus("Door \"" + editing.label() + "\" removed from the directory");
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
    private String textureKey() {
        String state = texStates.get(Math.min(texStateIndex, texStates.size() - 1));
        return switch (texEntry.kind) {
            case "mob" -> "mob/" + texEntry.key + "/" + state;
            case "item" -> "item/" + texEntry.key;
            case "decor" -> "decor/" + texEntry.key;
            case "surface" -> "surface/" + texEntry.key;
            case "playerskin" -> PlayerSprites.stateKey(state);
            default -> "block/" + texEntry.key;
        };
    }

    private void loadTextureFields() {
        SkinDef existing = Skins.get(textureKey());
        if (existing != null) {
            texSheet = existing.sheet;
            texW = String.valueOf(existing.frameWidth);
            texH = String.valueOf(existing.frameHeight);
            texCount = String.valueOf(existing.frameCount);
            texFps = String.valueOf(existing.fps);
        } else {
            texSheet = "";
            texW = texH = "32";
            texCount = "1";
            texFps = "0";
        }
    }

    /**
     * Assign any sprite sheet to the right-clicked palette entry. Mobs pick
     * an action state (idle/walk/attack/hurt); one sheet per state, and the
     * renderer falls back to idle for states without one. Applies live via
     * {@link Skins} and persists to {@code skins.json}.
     */
    private void buildTextureForm() {
        if (texStates.size() > 1) {
            dialogForm.addEnum("Action state", texStates.toArray(new String[0]),
                    () -> texStates.get(texStateIndex),
                    v -> {
                        texStateIndex = Math.max(0, texStates.indexOf(v));
                        loadTextureFields();
                    });
        }
        // The game type's texture pack folder: Browse… opens here, and bare
        // sheet filenames resolve against it — one folder keeps a pack's
        // sheets organized instead of scattering absolute paths around.
        dialogForm.addText("Texture pack folder (blank = default)",
                () -> profile().texturePackDir,
                v -> profile().texturePackDir = v, 96);
        dialogForm.addText("Sheet (PNG path)", () -> texSheet, v -> texSheet = v, 96);
        dialogForm.addAction("Browse…", this::browseForSheet);
        dialogForm.addText("Frame width px", () -> texW, v -> texW = v, 4);
        dialogForm.addText("Frame height px", () -> texH, v -> texH = v, 4);
        dialogForm.addText("Frame count", () -> texCount, v -> texCount = v, 3);
        dialogForm.addText("FPS (0 = static)", () -> texFps, v -> texFps = v, 5);
        dialogForm.addAction("Apply Texture", () -> {
            if (texSheet.isBlank()) {
                setStatus("Pick a sprite sheet first (path or Browse…)");
                return;
            }
            ctx.save(); // the texture pack folder persists with the game type
            SkinDef def = new SkinDef(textureKey(), resolveSheetPath(texSheet.trim()),
                    parseInt(texW, 32), parseInt(texH, 32),
                    parseInt(texCount, 1), parseDouble(texFps));
            Skins.put(def);
            persistSkins();
            // The Tools palette's Player Skin icon previews the current look.
            if ("playerskin".equals(texEntry.kind)) buildPalette();
            closeDialog();
            setStatus(texEntry.name + " now uses " + def.sheet
                    + " (" + def.frameCount + " frames @ " + def.fps + " fps)");
        });
        if (Skins.get(textureKey()) != null) {
            dialogForm.addAction("Remove Override", () -> {
                Skins.remove(textureKey());
                persistSkins();
                if ("playerskin".equals(texEntry.kind)) buildPalette();
                closeDialog();
                setStatus(texEntry.name + " back to its procedural texture");
            });
        }
        // User-created objects are deletable right from their entry's dialog.
        if (texEntry != null && texEntry.custom) {
            dialogForm.addAction("DELETE this custom object", this::deleteCustomEntry);
        }
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    /** Delete the right-clicked user-created object (custom.json + registries). */
    private void deleteCustomEntry() {
        if (customContent.remove(texEntry.kind, texEntry.key)) {
            buildPalette();
            selected.put(category, 0);
            closeDialog();
            setStatus("Deleted custom " + texEntry.name
                    + " — levels using it show placeholders");
        } else {
            setStatus("Couldn't delete " + texEntry.name);
        }
    }

    /**
     * Resolve a sheet path: as given when it exists (or is bundled), else
     * relative to the game type's texture pack folder.
     */
    private String resolveSheetPath(String sheet) {
        String dir = profile().texturePackDir;
        if (dir == null || dir.isBlank() || Files.exists(Path.of(sheet))) return sheet;
        Path inPack = Path.of(dir.trim()).resolve(sheet);
        return Files.exists(inPack) ? inPack.toString() : sheet;
    }

    private void browseForSheet() {
        String picked = chooseSheetFile();
        if (picked != null) texSheet = picked;
    }

    /** Swing image chooser (texture pack folder first), or {@code null}. */
    private String chooseSheetFile() {
        try {
            String dir = profile().texturePackDir;
            Path start = dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir.trim()))
                    ? Path.of(dir.trim())
                    : Path.of(SkinStore.DEFAULT_DIR);
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(
                    start.toAbsolutePath().toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Images", "png", "gif", "jpg", "jpeg"));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                return chooser.getSelectedFile().getAbsolutePath();
            }
        } catch (RuntimeException | Error e) {
            setStatus("File chooser unavailable — type the sheet path instead");
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
            default -> buildCustomBlockFields();
        }
        dialogForm.addAction("Create", this::createCustomObject);
        dialogForm.addAction("Cancel", this::closeDialog);
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
        String name = cName.isBlank() ? "Custom " + customKindName() : cName.trim();
        try {
            switch (customCategory) {
                case MOBS -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> MobRegistry.standard().get(k) != null);
                    customContent.addMob(new MobDef(key, name,
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2),
                            cSize, cSpeed, cHp, cMobDamage,
                            MobDef.Temperament.values()[cTemperIndex],
                            cDetect, cAttack, cFlying));
                }
                case ITEMS -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> ItemRegistry.standard().get(k) != null);
                    boolean isTool = cToolIndex > 0
                            || "TOOL".equals(ITEM_CATEGORIES[cCategoryIndex]);
                    customContent.addItem(new ItemDef(key, name,
                            ItemDef.Category.valueOf(ITEM_CATEGORIES[cCategoryIndex]),
                            ItemDef.Rarity.values()[cRarityIndex],
                            new Color(cR, cG, cB), cMaxStack, cMobDamage, cHeal,
                            null, null, null,
                            isTool && cToolIndex > 0 ? TOOL_CLASSES[cToolIndex] : null,
                            isTool && cToolIndex > 0 ? cToolPower : 0));
                }
                case DECOR -> {
                    String key = CustomContentStore.keyFor(name,
                            k -> DecorRegistry.standard().get(k) != null);
                    customContent.addDecor(new Decor(key, name,
                            Decor.Shape.values()[cShapeIndex],
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2), cSizeTiles));
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
                    customContent.addSurfaceDecor(new SurfaceDecor(key, name,
                            SurfaceDecor.Style.values()[cSurfStyleIndex],
                            new Color(cR, cG, cB), new Color(cR2, cG2, cB2),
                            faces, cSurfForeground));
                }
                default -> {
                    boolean liquid = customCategory == Category.LIQUIDS;
                    String key = CustomContentStore.keyFor(name,
                            k -> level.blocks.get(k) != null);
                    boolean solid = !liquid && cSolid;
                    customContent.addBlock(new Block(customContent.nextBlockId(), key,
                            name, new Color(cR, cG, cB), solid,
                            cLightRadius, new Color(cLightR, cLightG, cLightB),
                            solid ? key : null, liquid, cDamage, cHardness,
                            cToolIndex > 0 ? TOOL_CLASSES[cToolIndex] : null,
                            !liquid && cFalling));
                }
            }
        } catch (RuntimeException e) {
            setStatus("Couldn't create it: " + e.getMessage());
            return;
        }
        buildPalette();
        selectNewest(name);
        closeDialog();
        setStatus("Added custom " + customKindName().toLowerCase() + " \"" + name
                + "\" — saved to " + customContent.file());
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
        // whole catalog so creators don't have to memorize keys.
        String[] itemKeys = ruleItemKeyChoices();
        dialogForm.addText("Reward item key (blank = none)",
                () -> ruleReward, v -> ruleReward = v, 24);
        dialogForm.addEnum("· look up reward key", itemKeys,
                () -> keyChoiceShown(itemKeys, ruleReward),
                v -> { if (!itemKeys[0].equals(v)) ruleReward = v; });
        dialogForm.addInt("Reward count", () -> ruleRewardCount,
                v -> ruleRewardCount = v, 1, 99, 1);
        dialogForm.addText("Consume item key (blank = none)",
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

        drawDecorLayer(g, false); // background scenery sits behind the terrain
        SurfaceDecorPainter.draw(g, level, camera, visibleTileBounds(), false, animClock);
        drawTiles(g);
        if (testing) drawMiningCracks(g);
        if (showGrid && !testing && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawWorldBounds(g);
        drawEntities(g);
        drawSpawnMarker(g);
        if (!testing) drawCutsceneMarkers(g);
        if (testing && testMe != null) drawTestPlayer(g);
        if (testing && cutsceneDirector != null && cutsceneDirector.active() != null) {
            CutscenePainter.drawActors(g, camera, cutsceneDirector.active());
        }
        drawDecorLayer(g, true); // foreground scenery covers players
        SurfaceDecorPainter.draw(g, level, camera, visibleTileBounds(), true, animClock);
        if (p.particlesEnabled) particles.render(g, camera);

        if (!testing) {
            drawCursorPreview(g);
            drawSidebar(g);
            if (dialog == Dialog.NONE) drawPaletteTooltip(g);
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
    }

    /** Crack overlay on the block being held-mined, scaled by progress. */
    private void drawMiningCracks(Graphics2D g) {
        if (testWorld == null) return;
        int[] cell = testWorld.miningCell();
        double progress = testWorld.miningProgress();
        if (cell == null || progress <= 0.01) return;
        projectCell(cell[0], cell[1], level.tileSize);
        int x = Math.min(pxs[0], pxs[2]), y = Math.min(pys[0], pys[2]);
        int w = Math.abs(pxs[2] - pxs[0]), h = Math.abs(pys[2] - pys[0]);
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
    // Per-frame cache: block id -> skin frame (or null = draw procedural colour).
    private final Map<Integer, BufferedImage> tileSkins = new HashMap<>();

    private void drawTiles(Graphics2D g) {
        int ts = level.tileSize;
        int[] b = visibleTileBounds();
        boolean flat = camera.getPerspective() != Perspective.ISOMETRIC;
        tileSkins.clear();
        for (int r = b[1]; r <= b[3]; r++) {
            for (int c = b[0]; c <= b[2]; c++) {
                int id = level.tileAt(c, r);
                if (id <= 0) continue;
                Block block = level.blockAt(c, r);
                projectCell(c, r, ts);

                // The open chest/barrel gets an animated lid drawn over it.
                boolean openLid = containerPanel != null && block != null
                        && block.container()
                        && c == containerPanel.col() && r == containerPanel.row();

                if (block != null) {
                    // Isometric view warps the same texture into the diamond.
                    BufferedImage skin = tileSkinFor(id, block);
                    if (skin != null) {
                        com.larsons.engine.graphics.TilePainter.drawTexture(
                                g, skin, pxs, pys, flat);
                        if (openLid) {
                            ContainerPanel.drawLid(g, pxs, pys,
                                    containerPanel.openness(), level.colorFor(id));
                        }
                        continue;
                    }
                }

                Color col = level.colorFor(id);
                g.setColor(col);
                g.fillPolygon(pxs, pys, 4);
                if (block != null && block.liquid()) {
                    // Liquids: no hard outline; a lighter surface line where
                    // the cell above is open makes pools read at a glance.
                    if (level.liquidAt(c, r - 1) == null) {
                        g.setColor(new Color(255, 255, 255, 90));
                        g.drawLine(pxs[0], pys[0], pxs[1], pys[1]);
                    }
                } else {
                    g.setColor(col.darker());
                    g.drawPolygon(pxs, pys, 4);
                }
                if (openLid) {
                    ContainerPanel.drawLid(g, pxs, pys, containerPanel.openness(), col);
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
            int size = Math.max(8, (int) Math.round(def.sizeTiles() * level.tileSize * camera.zoom));
            camera.worldToScreen(e.x, e.y, pcorner);
            g.drawImage(img, pcorner[0] - size / 2, pcorner[1] - size, size, size, null);
        }
    }

    /** Painted mobs/items/doors/markers: level spawns offline, snapshots online. */
    private void drawEntities(Graphics2D g) {
        MobRegistry mobs = MobRegistry.standard();
        ItemRegistry items = ItemRegistry.standard();
        drawDoors(g);
        if (!testing) {
            drawMpSpawnMarkers(g);
            drawMiniGameMarkers(g);
        }
        if (testing && testWorld != null) {
            for (DroppedItem item : testWorld.items()) {
                drawItemAt(g, items.get(item.key), item.x, item.y);
            }
            for (Mob m : testWorld.mobs()) {
                drawMobAt(g, m.def, m.x, m.y, m.facingLeft, mobStateKey(m));
            }
            for (Projectile pr : testWorld.projectiles()) {
                drawProjectileAt(g, pr);
            }
            return;
        }
        if (net != null) {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                for (EntityView e : snap.items()) drawItemAt(g, items.get(e.key), e.x, e.y);
                for (EntityView e : snap.mobs()) {
                    MobDef def = mobs.get(e.key);
                    if (def != null) drawMobAt(g, def, e.x, e.y, e.facingLeft, "idle");
                }
                drawNetPlayers(g, snap);
            }
            return;
        }
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case "mob" -> {
                    MobDef def = mobs.get(e.type);
                    if (def != null) drawMobAt(g, def, e.x, e.y, false, "idle");
                }
                case "item" -> drawItemAt(g, items.get(e.type), e.x, e.y);
                default -> { /* doors/decor/markers drawn by their own passes */ }
            }
        }
    }

    /** The skin animation state a live mob is in (feeds {@code mob/<key>/<state>}). */
    private static String mobStateKey(Mob m) {
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

    private void drawMobAt(Graphics2D g, MobDef def, double x, double y,
                           boolean facingLeft, String state) {
        BufferedImage img = Skins.frame("mob/" + def.key() + "/" + state, animClock);
        if (img == null && !"idle".equals(state)) {
            img = Skins.frame("mob/" + def.key() + "/idle", animClock);
        }
        if (img == null) img = EntitySprites.mob(def, 32);
        int w = Math.max(6, (int) Math.round(def.size() * camera.zoom));
        camera.worldToScreen(x + def.size() / 2, y + def.size(), pcorner);
        int dx = pcorner[0] - w / 2, dy = pcorner[1] - w;
        if (facingLeft) {
            g.drawImage(img, dx + w, dy, -w, w, null);
        } else {
            g.drawImage(img, dx, dy, w, w, null);
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
        BufferedImage img = EntitySprites.projectile(pr.def, 16);
        int w = Math.max(8, (int) Math.round(pr.def.radius() * 3.5 * camera.zoom));
        camera.worldToScreen(pr.x, pr.y, pcorner);
        var old = g.getTransform();
        g.translate(pcorner[0], pcorner[1]);
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
    private void drawTestPlayer(Graphics2D g) {
        double size = profile().playerSize;
        if (testWalkAnim == null || testWalkAnim.frameCount() == 0) {
            testWalkAnim = PlayerSprites.walkAnimation((int) size, PlayerSprites.DEFAULT_BODY);
        }
        BufferedImage frame = PlayerSprites.frame(testAnimState, testWalkAnim, testAnimClock);
        camera.worldToScreen(testMe.x + size / 2.0, testMe.y + size, pcorner);
        int w = (int) Math.round(size * camera.zoom);
        int h = w;
        int dx = pcorner[0] - w / 2;
        int dy = pcorner[1] - h;
        if (frame != null) {
            if (testMe.facingLeft) {
                g.drawImage(frame, dx + w, dy, -w, h, null);
            } else {
                g.drawImage(frame, dx, dy, w, h, null);
            }
        }
        if (swingTime > 0) {
            g.setColor(new Color(255, 255, 255, (int) (150 * Math.max(0, swingTime / 0.2))));
            g.setStroke(new BasicStroke(3f));
            int r = (int) (size * camera.zoom * 0.9);
            int start = testMe.facingLeft ? 120 : -60;
            g.drawArc(pcorner[0] - r, pcorner[1] - w / 2 - r, r * 2, r * 2, start, 120);
        }
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
                    for (int[] cell : Brush.cells(brushShape, brushSize, col, row)) {
                        projectCell(cell[0], cell[1], ts);
                        g.setColor(b.color());
                        g.fillPolygon(pxs, pys, 4);
                    }
                    projectCell(col, row, ts);
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
                if (def != null) drawMobAt(g, def, aim[0], aim[1], false, "idle");
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
            g.drawString(categoryName(c) + "  (" + palette.get(c).size() + ")", 14, y + 15);
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
        g.drawString(sel != null ? sel.name + (sel.custom ? " · custom" : "") : "",
                10, viewportHeight - 20);
        g.setColor(new Color(150, 150, 165));
        g.setFont(SMALL_FONT);
        g.drawString("right-click icon = texture · Tab category", 10, viewportHeight - 6);
    }

    /**
     * Hover tooltip beside the sidebar: the hovered palette entry's name plus
     * a description of what it does, so every palette item explains itself.
     */
    private void drawPaletteTooltip(Graphics2D g) {
        if (mouseX >= SIDEBAR_W) return;
        List<Entry> entries = palette.get(category);
        int idx = paletteIndexAt(mouseX, mouseY);
        if (idx < 0 || idx >= entries.size()) return;
        Entry e = entries.get(idx);
        String desc = describeEntry(e);
        if (desc == null || desc.isBlank()) return;

        String title = e.name + (e.custom ? "  (custom)" : "");
        g.setFont(SMALL_FONT);
        List<String> lines = wrapText(desc, g.getFontMetrics(), 250);
        int bodyW = 0;
        for (String line : lines) {
            bodyW = Math.max(bodyW, g.getFontMetrics().stringWidth(line));
        }
        g.setFont(HUD_FONT);
        int w = Math.max(g.getFontMetrics().stringWidth(title), bodyW) + 24;
        int h = 30 + lines.size() * 15 + 8;
        int x = SIDEBAR_W + 10;
        int y = Math.max(44, Math.min(mouseY - 12, viewportHeight - h - 8));

        g.setColor(new Color(12, 12, 20, 235));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(255, 220, 120));
        g.drawString(title, x + 12, y + 19);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(205, 205, 220));
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), x + 12, y + 36 + i * 15);
        }
    }

    /** Greedy word wrap of {@code text} to lines at most {@code maxW} px wide. */
    private static List<String> wrapText(String text, java.awt.FontMetrics fm, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String probe = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(probe) > maxW && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
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
                return "Creates your own custom " + e.name.replace("+ New ", "").toLowerCase()
                        + " — set its properties in a form; it registers live, joins"
                        + " this palette, and saves with the game type.";
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
                return "Customize the player character: assign a sprite-sheet"
                        + " animation to each action state (idle, walk, run, jump,"
                        + " fall, swim), used in play and play-test alike.";
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
        if (testing) {
            bar = "PLAY-TEST — " + level.name + chunkInfo
                    + "   ·   WASD move · Shift sprint · hold click to mine · right-click place"
                    + " · 1-5 hotbar · [I] inventory · [E] doors/stations · [P]/[Esc] editor";
        } else if (net != null) {
            bar = "CREATIVE (ONLINE) — painting the server's world   ·   [Tab] category · right-click erase"
                    + " · [G] grid · [Esc] back to game";
        } else {
            bar = "CREATIVE — " + level.name + " (" + level.width + "x" + level.height + ")"
                    + chunkInfo
                    + "   ·   [Tab] category · right-click erase · middle pick · [B] layer"
                    + " · [ ] brush · [G] grid · [P] test · [Ctrl+S] save · [L] load · [N] new · [Esc] menu";
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
        BufferedImage frame = PlayerSprites.frame("idle",
                PlayerSprites.walkAnimation(40, PlayerSprites.DEFAULT_BODY), 0);
        if (frame != null) g.drawImage(frame, 0, 0, 40, 40, null);
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
