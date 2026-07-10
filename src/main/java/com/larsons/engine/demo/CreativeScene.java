package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
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
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.DoorDirectory;
import com.larsons.engine.level.DoorLink;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelGenerator;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.net.NetSession;
import com.larsons.engine.net.Snapshot;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.ui.ConfigForm;
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
 * <p><b>Level size sliders:</b> the sidebar's bottom panel has live width /
 * height sliders — drag to resize the level in place (content preserved).
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
 * {@link Skins} system; assignments persist in {@code skins.json}.
 *
 * <p><b>Generate:</b> the Tools palette's Generate button builds a large
 * Perlin-noise level — Minecraft-style terrain/caves/ores/liquids fused with
 * a connected Metroidvania room network ({@link LevelGenerator}).
 *
 * <p><b>Play-test:</b> {@code P} drops a player at the spawn and simulates
 * the painted world with the real physics/mob/item code — including a full
 * inventory (mine drops, pick up items, place from the hotbar, eat, shoot)
 * and door travel; {@code P}/Esc returns to editing with the terrain restored
 * (test-mode mining isn't destructive).
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

    // Level size sliders (the sidebar's bottom panel).
    private static final int SLIDER_PANEL_H = 76;
    private static final int MIN_LEVEL_W = 16, MAX_LEVEL_W = 600;
    private static final int MIN_LEVEL_H = 16, MAX_LEVEL_H = 320;

    /** What the palette can paint. */
    private enum Category { BLOCKS, LIQUIDS, LIGHTS, MOBS, ITEMS, DECOR, DOORS, TOOLS }

    private enum Dialog { NONE, NEW_LEVEL, SAVE, LOAD, CONFIRM_EXIT, GENERATE, DOORS, TEXTURE }

    private record Entry(String kind, String key, String name, BufferedImage icon) {}

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

    // Level size sliders.
    private int draggingSizeSlider = -1; // 0 = width, 1 = height
    private int pendingLevelW, pendingLevelH;
    private final Rectangle[] sliderTracks = {new Rectangle(), new Rectangle()};

    // Dialogs.
    private Dialog dialog = Dialog.NONE;
    private ConfigForm dialogForm;
    private String pendingName = "";
    private int pendingWidth = 60, pendingHeight = 24;
    private int genWidth = 240, genHeight = 140, genSeed = 1;
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
    private int[][] savedTiles; // terrain restored when the test ends
    private int inputSeq;
    private Inventory testInv;
    private boolean showInventory;
    private int cursorSlot = -1;
    private double swingTime;
    private double prevHealth = PlayerState.MAX_HEALTH;
    private final Particles particles = new Particles();

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
        buildPalette();

        if (net != null && net.client().level() != null) {
            level = net.client().level(); // paint straight into the shared world
        } else {
            net = null;
            level = loadInitialLevel();
        }
        pendingLevelW = level.width;
        pendingLevelH = level.height;

        camera = new Camera(profile().perspective, viewportWidth, viewportHeight);
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
        Level lvl = Level.empty(name, widthTiles, heightTiles, profile().tileSize);
        lvl.perspective = profile().perspective;
        int dirt = lvl.blocks.get("dirt").id();
        int grass = lvl.blocks.get("grass").id();
        for (int c = 0; c < lvl.width; c++) {
            lvl.tiles[lvl.height - 1][c] = dirt;
            lvl.tiles[lvl.height - 2][c] = grass;
        }
        lvl.spawnX = lvl.tileSize * 3;
        lvl.spawnY = (lvl.height - 4) * (double) lvl.tileSize;
        return lvl;
    }

    private void buildPalette() {
        palette.clear();
        List<Entry> blocks = new ArrayList<>();
        List<Entry> liquids = new ArrayList<>();
        List<Entry> lights = new ArrayList<>();
        for (Block b : com.larsons.engine.world.BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // the sim's hidden flow twins
            Entry e = new Entry("block", b.key(), b.displayName(), EntitySprites.block(b, 40));
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

        List<Entry> mobs = new ArrayList<>();
        for (MobDef d : MobRegistry.standard().all()) {
            mobs.add(new Entry("mob", d.key(), d.displayName(), EntitySprites.mob(d, 40)));
        }
        palette.put(Category.MOBS, mobs);

        List<Entry> items = new ArrayList<>();
        for (ItemDef d : ItemRegistry.standard().allByRarity()) {
            items.add(new Entry("item", d.key(), d.name(), EntitySprites.item(d, 40)));
        }
        palette.put(Category.ITEMS, items);

        List<Entry> decor = new ArrayList<>();
        for (Decor d : DecorRegistry.standard().all()) {
            decor.add(new Entry("decor", d.key(), d.name(), EntitySprites.decor(d, 40)));
        }
        palette.put(Category.DECOR, decor);

        List<Entry> doorEntries = new ArrayList<>();
        for (DoorLink link : doors.all()) {
            doorEntries.add(new Entry("door", link.key(), link.label(), doorIcon(link.color())));
        }
        doorEntries.add(new Entry("managedoors", "managedoors", "Manage Doors…", manageDoorsIcon()));
        palette.put(Category.DOORS, doorEntries);

        List<Entry> tools = new ArrayList<>();
        tools.add(new Entry("spawn", "spawn", "Player Spawn", spawnIcon()));
        tools.add(new Entry("mp_spawn", "mp_spawn", "Multiplayer Spawn", mpSpawnIcon()));
        tools.add(new Entry("eraser", "eraser", "Eraser", eraserIcon()));
        tools.add(new Entry("generate", "generate", "Generate Level…", generateIcon()));
        palette.put(Category.TOOLS, tools);

        for (Category c : Category.values()) {
            palette.putIfAbsent(c, new ArrayList<>());
            selected.putIfAbsent(c, 0);
            scroll.putIfAbsent(c, 0);
        }
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

    // --- level size sliders --------------------------------------------------------

    /**
     * Drag handling for the sidebar's width/height sliders. Returns true while
     * a drag owns the mouse. The resize applies on release, so mid-drag values
     * just preview in the panel.
     */
    private boolean updateSizeSliders(InputManager input) {
        if (draggingSizeSlider >= 0) {
            if (!input.isMouseDown()) {
                int w = draggingSizeSlider == 0 ? pendingLevelW : level.width;
                int h = draggingSizeSlider == 1 ? pendingLevelH : level.height;
                draggingSizeSlider = -1;
                if (w != level.width || h != level.height) {
                    level.resize(w, h);
                    setStatus("Level resized to " + level.width + "x" + level.height);
                }
                pendingLevelW = level.width;
                pendingLevelH = level.height;
            } else {
                dragSizeSlider(input.getMouseX());
            }
            return true;
        }
        if (!input.isMouseJustPressed()) return false;
        for (int i = 0; i < 2; i++) {
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
        if (draggingSizeSlider == 0) {
            pendingLevelW = MIN_LEVEL_W + (int) Math.round(t * (MAX_LEVEL_W - MIN_LEVEL_W));
        } else {
            pendingLevelH = MIN_LEVEL_H + (int) Math.round(t * (MAX_LEVEL_H - MIN_LEVEL_H));
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
                if (b == null || level.tileAt(col, row) == b.id()) return;
                if (net != null) {
                    net.client().sendBlockEdit(col, row, b.id(), "paint");
                } else if (level.setTile(col, row, b.id())) {
                    ctx.sfx(Sfx.PLACE);
                    if (profile().particlesEnabled) {
                        particles.burst((col + 0.5) * level.tileSize,
                                (row + 0.5) * level.tileSize, b.color(), 4);
                    }
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
            case "spawn" -> {
                level.spawnX = wx;
                level.spawnY = wy;
                setStatus("Player spawn moved");
            }
            case "eraser" -> eraseAt(wx, wy, col, row);
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

    private void eraseAt(double wx, double wy, int col, int row) {
        // Entities first (they sit on top of blocks), then the block cell.
        if (net == null) {
            Level.EntitySpawn near = nearestSpawn(wx, wy);
            if (near != null) {
                level.entities.remove(near);
                ctx.sfx(Sfx.CLICK);
                setStatus("Erased " + near.type);
                return;
            }
            if (level.tileAt(col, row) != 0 && level.setTile(col, row, 0)) {
                ctx.sfx(Sfx.BREAK);
            }
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
            case "mob", "item", "door", "decor_bg", "decor_fg", "mp_spawn" -> true;
            default -> false;
        };
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
                default -> setStatus(e.name);
            }
        }
    }

    /** Right-clicking a palette icon opens the texture-override dialog for it. */
    private void handlePaletteRightClick(int mx, int my) {
        int idx = paletteIndexAt(mx, my);
        List<Entry> entries = palette.get(category);
        if (idx < 0 || idx >= entries.size()) return;
        Entry e = entries.get(idx);
        if (!skinnable(e.kind)) {
            setStatus("No texture override for " + e.name);
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
            case "block", "mob", "item", "decor" -> true;
            default -> false;
        };
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

    /** Top of the swatch grid: tabs, plus the layer row when DECOR is active. */
    private int paletteGridTop() {
        int top = 34 + Category.values().length * 22 + 10;
        if (category == Category.DECOR) top += 24;
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
        // Copy terrain so test-mode mining/liquid flow doesn't eat the level.
        editLevel = level;
        savedTiles = new int[level.tiles.length][];
        for (int r = 0; r < level.tiles.length; r++) {
            savedTiles[r] = level.tiles[r].clone();
        }
        startTestWorld();
        testInv = new Inventory(testWorld.itemTypes);
        bindTestPickups();
        testing = true;
        showInventory = false;
        cursorSlot = -1;
        camera.zoom = Math.max(profile().minZoom, Math.min(profile().maxZoom, 1.0));
        setStatus("Play-test — full inventory active · [E] doors · P/Esc returns to editing");
    }

    private void startTestWorld() {
        testWorld = new World(level);
        testWorld.populateFromLevel(profile());
        double[] spawn = {level.spawnX, level.spawnY};
        testMe = new PlayerState(0, "", spawn[0], spawn[1]);
        prevHealth = testMe.health;
    }

    private void bindTestPickups() {
        testWorld.setPickupListener((p, key, n) -> {
            if (profile().itemsEnabled) testInv.add(key, n);
            ctx.sfx(Sfx.PICKUP);
        });
    }

    private void exitTest() {
        testing = false;
        testWorld = null;
        testInv = null;
        showInventory = false;
        level = editLevel != null ? editLevel : level;
        editLevel = null;
        if (savedTiles != null) {
            for (int r = 0; r < savedTiles.length && r < level.tiles.length; r++) {
                level.tiles[r] = savedTiles[r];
            }
            savedTiles = null;
        }
        camera.tileSize = level.tileSize;
        setStatus("Back to editing");
    }

    private void updateTest(double dt, InputManager input) {
        GameProfile p = profile();
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (showInventory) {
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

        updateTestInventoryControls(input, p);

        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);
        PlayerPhysics.step(testMe, in, level, p, p.perspective, dt);
        testWorld.step(dt, List.of(testMe), p);
        for (World.Impact im : testWorld.pollImpacts()) {
            ctx.sfx(im.explosion() ? Sfx.BOOM : Sfx.HIT);
            if (p.particlesEnabled) {
                particles.burst(im.x(), im.y(), new Color(255, 200, 120),
                        im.explosion() ? 18 : 6);
            }
        }
        if (testMe.health < prevHealth - 0.01) ctx.sfx(Sfx.HURT);
        prevHealth = testMe.health;

        if (input.isKeyJustPressed(KeyEvent.VK_E)) {
            tryDoorTravel();
        }

        if (!showInventory) {
            if (input.isMouseJustPressed()) handleTestLeftClick(p);
            if (input.isRightMouseJustPressed()) handleTestRightClick(p);
        }

        if (swingTime > 0) swingTime -= dt;
        particles.update(dt);
        camera.centerOn(testMe.x + p.playerSize / 2.0, testMe.y + p.playerSize / 2.0);
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
            if (def != null && def.heal() > 0 && testMe.health < PlayerState.MAX_HEALTH
                    && testInv.consumeSelected()) {
                testMe.health = Math.min(PlayerState.MAX_HEALTH, testMe.health + def.heal());
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
            ItemStack held = testInv.slot(cursorSlot);
            if (held != null && !insideInventoryPanel(mouseX, mouseY)) {
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

    /** Left click in test: shoot the held weapon, else mine, else swing. */
    private void handleTestLeftClick(GameProfile p) {
        double[] aim = camera.screenToWorld(mouseX, mouseY);
        double ts = level.tileSize;
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (testMe.x + p.playerSize / 2.0),
                aim[1] - (testMe.y + p.playerSize / 2.0)) <= 5 * ts;

        ItemDef held = p.itemsEnabled ? testInv.selectedDef() : null;
        boolean shoots = p.projectilesEnabled && held != null && held.projectile() != null;
        if (shoots) {
            swingTime = 0.1;
            if (testWorld.playerShoot(testMe, testInv, aim[0], aim[1]) != null) {
                ctx.sfx(Sfx.SHOOT);
            }
            return;
        }
        if (p.blockEditingEnabled && inReach && level.tileAt(col, row) > 0) {
            Block mined = testWorld.mineBlock(col, row, p.itemsEnabled);
            if (mined != null) {
                ctx.sfx(Sfx.BREAK);
                if (p.particlesEnabled) {
                    particles.burst((col + 0.5) * ts, (row + 0.5) * ts, mined.color(), 10);
                }
            }
            return;
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
        if (b == null) return;
        double size = p.playerSize;
        boolean overlapsMe = testMe.x + size > col * ts && testMe.x < (col + 1) * ts
                && testMe.y + size > row * ts && testMe.y < (row + 1) * ts;
        if (b.solid() && overlapsMe) return;
        if (testWorld.placeBlock(col, row, b.id())) {
            if (p.itemsEnabled) testInv.consumeSelected();
            ctx.sfx(Sfx.PLACE);
        }
    }

    /** Walk into a painted door and press E: load its target level and keep testing. */
    private void tryDoorTravel() {
        double half = profile().playerSize / 2.0;
        Level.EntitySpawn door = level.doorNear(testMe.x + half, testMe.y + half,
                level.tileSize * 1.3);
        if (door == null) return;
        DoorLink link = doors.get(door.type);
        if (link == null || link.targetLevel().isEmpty()) {
            setStatus("This door has no target level — set one in Manage Doors");
            return;
        }
        LevelStore store = new LevelStore(profile().name);
        if (!store.exists(link.targetLevel())) {
            setStatus("Door target \"" + link.targetLevel() + "\" isn't saved yet");
            return;
        }
        level = store.load(link.targetLevel());
        camera.tileSize = level.tileSize;
        startTestWorld();
        bindTestPickups(); // inventory carries through the door
        ctx.sfx(Sfx.CLICK);
        setStatus("Entered \"" + link.label() + "\" → " + level.name);
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
            default -> "";
        }).theme(MenuTheme.dark());

        switch (d) {
            case NEW_LEVEL -> {
                pendingName = "New Level";
                pendingWidth = 60;
                pendingHeight = 24;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                dialogForm.addSlider("Width (tiles)", () -> pendingWidth,
                        v -> pendingWidth = v, 8, MAX_LEVEL_W);
                dialogForm.addSlider("Height (tiles)", () -> pendingHeight,
                        v -> pendingHeight = v, 8, MAX_LEVEL_H);
                dialogForm.addAction("Create", () -> {
                    level = starterLevel(pendingName, pendingWidth, pendingHeight);
                    afterLevelSwap();
                    setStatus("Created \"" + level.name + "\" (" + pendingWidth + "x" + pendingHeight + ")");
                });
                dialogForm.addAction("Cancel", this::closeDialog);
            }
            case SAVE -> {
                pendingName = level.name;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                dialogForm.addAction("Save", () -> {
                    level.name = pendingName.isBlank() ? "Untitled" : pendingName.trim();
                    LevelStore store = new LevelStore(profile().name);
                    Path file = store.save(level);
                    profile().lastLevelPath = file.toString();
                    ctx.save();
                    closeDialog();
                    setStatus("Saved to " + file + " — Play now loads this level");
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
            default -> { /* NONE */ }
        }
    }

    /** Camera/slider bookkeeping after replacing the edited level. */
    private void afterLevelSwap() {
        camera.tileSize = level.tileSize;
        camera.centerOn(level.spawnX, level.spawnY);
        pendingLevelW = level.width;
        pendingLevelH = level.height;
        closeDialog();
    }

    private void buildGenerateForm() {
        pendingName = "Generated " + (1 + (int) (Math.random() * 8999));
        genSeed = 1 + (int) (Math.random() * 99998);
        dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
        dialogForm.addSlider("Width (tiles)", () -> genWidth, v -> genWidth = v, 64, 512);
        dialogForm.addSlider("Height (tiles)", () -> genHeight, v -> genHeight = v, 48, MAX_LEVEL_H);
        dialogForm.addInt("Seed", () -> genSeed, v -> genSeed = v, 1, 99999, 1);
        dialogForm.addAction("Randomize Seed", () -> genSeed = 1 + (int) (Math.random() * 99998));
        dialogForm.addAction("Generate", () -> {
            Level generated = LevelGenerator.generate(
                    pendingName.isBlank() ? "Generated" : pendingName.trim(),
                    genWidth, genHeight, profile().tileSize, genSeed);
            generated.perspective = profile().perspective;
            level = generated;
            afterLevelSwap();
            setStatus("Generated \"" + level.name + "\" (" + level.width + "x" + level.height
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
            SkinDef def = new SkinDef(textureKey(), texSheet.trim(),
                    parseInt(texW, 32), parseInt(texH, 32),
                    parseInt(texCount, 1), parseDouble(texFps));
            Skins.put(def);
            persistSkins();
            closeDialog();
            setStatus(texEntry.name + " now uses " + def.sheet
                    + " (" + def.frameCount + " frames @ " + def.fps + " fps)");
        });
        if (Skins.get(textureKey()) != null) {
            dialogForm.addAction("Remove Override", () -> {
                Skins.remove(textureKey());
                persistSkins();
                closeDialog();
                setStatus(texEntry.name + " back to its procedural texture");
            });
        }
        dialogForm.addAction("Cancel", this::closeDialog);
    }

    private void browseForSheet() {
        try {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(
                    Path.of(SkinStore.DEFAULT_DIR).toAbsolutePath().toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Images", "png", "gif", "jpg", "jpeg"));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                texSheet = chooser.getSelectedFile().getAbsolutePath();
            }
        } catch (RuntimeException | Error e) {
            setStatus("File chooser unavailable — type the sheet path instead");
        }
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
        drawTiles(g);
        if (showGrid && !testing && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawWorldBounds(g);
        drawEntities(g);
        drawSpawnMarker(g);
        if (testing && testMe != null) drawTestPlayer(g);
        drawDecorLayer(g, true); // foreground scenery covers players
        if (p.particlesEnabled) particles.render(g, camera);

        if (!testing) {
            drawCursorPreview(g);
            drawSidebar(g);
        }
        drawTopBar(g);
        if (testing) {
            if (p.itemsEnabled) drawTestHotbar(g);
            drawTestHealthBar(g);
            drawDoorHint(g);
            if (showInventory) drawTestInventory(g);
        }
        drawStatus(g);

        if (dialog != Dialog.NONE) drawDialog(g);
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
        camera.worldToScreen(testMe.x + profile().playerSize / 2.0,
                testMe.y + profile().playerSize / 2.0, corner);
        lighting.addLight(corner[0], corner[1], 2.5 * ts * camera.zoom,
                new Color(255, 240, 210));
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

                if (flat && block != null) {
                    BufferedImage skin = tileSkinFor(id, block);
                    if (skin != null) {
                        int x = Math.min(pxs[0], pxs[2]);
                        int y = Math.min(pys[0], pys[2]);
                        g.drawImage(skin, x, y, Math.abs(pxs[2] - pxs[0]) + 1,
                                Math.abs(pys[2] - pys[0]) + 1, null);
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
        if (!testing) drawMpSpawnMarkers(g);
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
        g.drawImage(img, pcorner[0], pcorner[1], w, w, null);
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

    private void drawTestPlayer(Graphics2D g) {
        int size = profile().playerSize;
        camera.worldToScreen(testMe.x + size / 2.0, testMe.y + size, pcorner);
        int w = Math.max(6, (int) Math.round(size * camera.zoom));
        g.setColor(new Color(70, 130, 220));
        g.fillRoundRect(pcorner[0] - w / 2, pcorner[1] - w, w, w, w / 4, w / 4);
        g.setColor(new Color(245, 210, 170));
        g.fillOval(pcorner[0] - w / 4, pcorner[1] - w, w / 2, w / 2);
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
                    projectCell(col, row, ts);
                    g.setColor(b.color());
                    g.fillPolygon(pxs, pys, 4);
                    g.setColor(Color.WHITE);
                    g.drawPolygon(pxs, pys, 4);
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
                projectCell(col, row, ts);
                g.setColor(new Color(230, 100, 120));
                g.setStroke(new BasicStroke(2f));
                g.drawPolygon(pxs, pys, 4);
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
            }
        }

        if (net == null) drawSizeSliders(g);

        // Selected entry name + hints at the bottom.
        Entry sel = selectedEntry();
        g.setColor(new Color(10, 10, 16));
        g.fillRect(0, viewportHeight - 36, SIDEBAR_W, 36);
        g.setColor(new Color(255, 220, 120));
        g.setFont(HUD_FONT);
        g.drawString(sel != null ? sel.name : "", 10, viewportHeight - 20);
        g.setColor(new Color(150, 150, 165));
        g.setFont(SMALL_FONT);
        g.drawString("right-click icon = texture · Tab category", 10, viewportHeight - 6);
    }

    /** The live level width/height sliders at the bottom of the sidebar. */
    private void drawSizeSliders(Graphics2D g) {
        int top = sliderPanelTop();
        g.setColor(new Color(10, 10, 16, 220));
        g.fillRect(0, top, SIDEBAR_W, SLIDER_PANEL_H);
        g.setColor(new Color(255, 255, 255, 30));
        g.drawLine(0, top, SIDEBAR_W, top);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(200, 200, 215));
        g.drawString("Level size (drag)", 10, top + 14);

        int shownW = draggingSizeSlider == 0 ? pendingLevelW : level.width;
        int shownH = draggingSizeSlider == 1 ? pendingLevelH : level.height;
        drawOneSlider(g, 0, top + 24, "W", shownW, MIN_LEVEL_W, MAX_LEVEL_W);
        drawOneSlider(g, 1, top + 48, "H", shownH, MIN_LEVEL_H, MAX_LEVEL_H);
    }

    private void drawOneSlider(Graphics2D g, int index, int y, String label,
                               int value, int min, int max) {
        int trackX = 26, trackW = SIDEBAR_W - 26 - 44;
        sliderTracks[index].setBounds(trackX, y, trackW, 8);
        g.setColor(new Color(200, 200, 215));
        g.drawString(label, 10, y + 8);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRoundRect(trackX, y + 2, trackW, 4, 4, 4);
        double t = Math.max(0, Math.min(1, (value - min) / (double) (max - min)));
        g.setColor(draggingSizeSlider == index
                ? new Color(255, 220, 120) : new Color(160, 180, 220));
        g.fillRoundRect(trackX, y + 2, (int) (trackW * t), 4, 4, 4);
        g.fillOval(trackX + (int) (trackW * t) - 5, y - 1, 10, 10);
        g.setColor(new Color(220, 220, 235));
        g.drawString(String.valueOf(value), trackX + trackW + 8, y + 8);
    }

    private void drawTopBar(Graphics2D g) {
        int x0 = testing ? 0 : SIDEBAR_W;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(x0, 0, viewportWidth - x0, 28);
        g.setColor(Color.WHITE);
        g.setFont(HUD_FONT);
        String bar;
        if (testing) {
            bar = "PLAY-TEST — " + level.name
                    + "   ·   WASD move · click mine/attack · right-click place · 1-5 hotbar"
                    + " · [I] inventory · [E] doors · [P]/[Esc] editor";
        } else if (net != null) {
            bar = "CREATIVE (ONLINE) — painting the server's world   ·   [Tab] category · right-click erase"
                    + " · [G] grid · [Esc] back to game";
        } else {
            bar = "CREATIVE — " + level.name + " (" + level.width + "x" + level.height + ")"
                    + "   ·   [Tab] category · right-click erase · middle pick · [B] layer"
                    + " · [G] grid · [P] test · [Ctrl+S] save · [L] load · [N] new · [Esc] menu";
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

    private int[] inventoryOrigin() {
        int gw = Inventory.COLS * (INV_SLOT + INV_PAD) - INV_PAD;
        int gh = Inventory.ROWS * (INV_SLOT + INV_PAD) - INV_PAD;
        return new int[]{(viewportWidth - gw) / 2, (viewportHeight - gh) / 2};
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
        g.drawString("Click to pick up / place stacks · click outside to drop"
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
            case DOORS -> "Doors";
            case TOOLS -> "Tools";
        };
    }

    // --- palette tool icons -----------------------------------------------------------

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
}
