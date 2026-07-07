package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
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
import com.larsons.engine.world.World;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Creative Mode: the Side-Scroller engine's level editor, rebuilt on this
 * engine's camera/scene/registry architecture — <em>paint objects into the
 * world</em>. A palette sidebar offers every registered block, light, mob,
 * and item plus editor tools; left-click paints (drag to keep painting,
 * grid-snapped for blocks), right-click erases, middle-click picks the
 * hovered block. Levels are saved per game type ({@link LevelStore}) and
 * played back with exactly the features the game type enables.
 *
 * <p>Because painting goes through the same {@link Level}/{@link World} the
 * play scene uses, everything here honours the active game type: perspective
 * (paint in isometric if you like), tile size, feature toggles.
 *
 * <p><b>Play-test:</b> {@code P} drops a player at the spawn and simulates
 * the painted world with the real physics/mob/item code; {@code P}/Esc
 * returns to editing with the terrain restored (test-mode mining isn't
 * destructive).
 *
 * <p><b>Online:</b> opened from a multiplayer session (pause menu), the same
 * editor paints into the <em>server's</em> world: block strokes and
 * mob/item paints become protocol requests, the authoritative results
 * broadcast to every player, and other players appear live while you paint.
 * Save/load/test are local-world features and stay disabled online.
 *
 * <p>Controls: WASD/arrows pan · wheel zoom (over canvas) / scroll palette
 * (over sidebar) · Tab category · left paint · right erase · middle pick ·
 * G grid · P test · Ctrl+S save · L load · N new · Esc menu/back.
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

    /** What the palette can paint. */
    private enum Category { BLOCKS, LIGHTS, MOBS, ITEMS, TOOLS }

    private enum Dialog { NONE, NEW_LEVEL, SAVE, LOAD, CONFIRM_EXIT }

    private record Entry(String kind, String key, String name, BufferedImage icon) {}

    private final GameContext ctx;

    private Level level;
    private Camera camera;
    private NetSession net; // non-null when editing a multiplayer world

    // Palette state.
    private final Map<Category, List<Entry>> palette = new EnumMap<>(Category.class);
    private final Map<Category, Integer> selected = new EnumMap<>(Category.class);
    private final Map<Category, Integer> scroll = new EnumMap<>(Category.class);
    private Category category = Category.BLOCKS;

    // Editing state.
    private boolean showGrid = true;
    private int lastPaintCol = Integer.MIN_VALUE, lastPaintRow = Integer.MIN_VALUE;
    private int mouseX, mouseY; // sampled in update, used by the render preview
    private String status = "";
    private double statusTime;

    // Dialogs.
    private Dialog dialog = Dialog.NONE;
    private ConfigForm dialogForm;
    private String pendingName = "";
    private int pendingWidth = 60, pendingHeight = 24;

    // Play-test state (offline only).
    private boolean testing;
    private World testWorld;
    private PlayerState testMe;
    private int[][] savedTiles; // terrain restored when the test ends
    private int inputSeq;
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
        buildPalette();

        if (net != null && net.client().level() != null) {
            level = net.client().level(); // paint straight into the shared world
        } else {
            net = null;
            level = loadInitialLevel();
        }

        camera = new Camera(profile().perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = 1.0;
        camera.centerOn(level.spawnX, level.spawnY);
        setStatus(net == null
                ? "Creative Mode — paint with the left mouse button; Tab cycles the palette"
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
        List<Entry> lights = new ArrayList<>();
        for (Block b : com.larsons.engine.world.BlockRegistry.standard().all()) {
            Entry e = new Entry("block", b.key(), b.displayName(), EntitySprites.block(b, 40));
            (b.emitsLight() ? lights : blocks).add(e);
        }
        palette.put(Category.BLOCKS, blocks);
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

        List<Entry> tools = new ArrayList<>();
        tools.add(new Entry("spawn", "spawn", "Player Spawn", spawnIcon()));
        tools.add(new Entry("eraser", "eraser", "Eraser", eraserIcon()));
        palette.put(Category.TOOLS, tools);

        for (Category c : Category.values()) {
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

        // --- mouse editing ---
        if (overSidebar) {
            if (input.isMouseJustPressed()) {
                handlePaletteClick(input.getMouseX(), input.getMouseY());
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
            case "spawn" -> {
                level.spawnX = wx;
                level.spawnY = wy;
                setStatus("Player spawn moved");
            }
            case "eraser" -> eraseAt(wx, wy, col, row);
            default -> { /* unknown palette kind */ }
        }
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
        Category target = b.emitsLight() ? Category.LIGHTS : Category.BLOCKS;
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

    private Level.EntitySpawn nearestSpawn(double wx, double wy) {
        Level.EntitySpawn best = null;
        double bestD = ERASE_RADIUS;
        for (Level.EntitySpawn e : level.entities) {
            if (!e.kind.equals("mob") && !e.kind.equals("item")) continue;
            double d = Math.hypot(e.x - wx, e.y - wy);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
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
        int gridTop = 34 + Category.values().length * 22 + 10;
        if (my < gridTop) return;
        int cellPad = (SIDEBAR_W - CELLS_PER_ROW * CELL) / (CELLS_PER_ROW + 1);
        int colIdx = (mx - cellPad) / (CELL + cellPad);
        int rowIdx = (my - gridTop) / (CELL + cellPad);
        if (colIdx < 0 || colIdx >= CELLS_PER_ROW) return;
        int idx = (rowIdx + scroll.get(category)) * CELLS_PER_ROW + colIdx;
        List<Entry> entries = palette.get(category);
        if (idx >= 0 && idx < entries.size()) {
            selected.put(category, idx);
            ctx.sfx(Sfx.CLICK);
            setStatus(entries.get(idx).name);
        }
    }

    private void clampScroll() {
        int rows = (palette.get(category).size() + CELLS_PER_ROW - 1) / CELLS_PER_ROW;
        int max = Math.max(0, rows - visiblePaletteRows());
        scroll.put(category, Math.max(0, Math.min(max, scroll.get(category))));
    }

    private int visiblePaletteRows() {
        int gridTop = 34 + Category.values().length * 22 + 10;
        return Math.max(1, (viewportHeight - gridTop - 40) / (CELL + 12));
    }

    // --- play-test ----------------------------------------------------------------

    private void enterTest() {
        // Copy terrain so test-mode mining doesn't eat the level.
        savedTiles = new int[level.tiles.length][];
        for (int r = 0; r < level.tiles.length; r++) {
            savedTiles[r] = level.tiles[r].clone();
        }
        testWorld = new World(level);
        testWorld.populateFromLevel(profile());
        testMe = new PlayerState(0, "", level.spawnX, level.spawnY);
        testWorld.setPickupListener((p, key, n) -> ctx.sfx(Sfx.PICKUP));
        testing = true;
        camera.zoom = Math.max(profile().minZoom, Math.min(profile().maxZoom, 1.0));
        setStatus("Play-test — P or Esc returns to editing");
    }

    private void exitTest() {
        testing = false;
        testWorld = null;
        if (savedTiles != null) {
            for (int r = 0; r < savedTiles.length; r++) {
                level.tiles[r] = savedTiles[r];
            }
            savedTiles = null;
        }
        setStatus("Back to editing");
    }

    private void updateTest(double dt, InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE) || input.isKeyJustPressed(KeyEvent.VK_P)) {
            exitTest();
            return;
        }
        GameProfile p = profile();
        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);
        PlayerPhysics.step(testMe, in, level, p, p.perspective, dt);
        testWorld.step(dt, List.of(testMe), p);

        if (input.isMouseJustPressed() && p.combatEnabled) {
            double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
            Mob hit = testWorld.playerAttack(testMe, aim[0], aim[1], World.FIST_DAMAGE);
            if (hit != null) ctx.sfx(Sfx.HIT);
        }
        particles.update(dt);
        camera.centerOn(testMe.x + p.playerSize / 2.0, testMe.y + p.playerSize / 2.0);
    }

    // --- dialogs -------------------------------------------------------------------

    private void openDialog(Dialog d) {
        dialog = d;
        dialogForm = new ConfigForm(switch (d) {
            case NEW_LEVEL -> "New Level";
            case SAVE -> "Save Level";
            case LOAD -> "Load Level";
            case CONFIRM_EXIT -> "Leave Creative Mode?";
            default -> "";
        }).theme(MenuTheme.dark());

        switch (d) {
            case NEW_LEVEL -> {
                pendingName = "New Level";
                pendingWidth = 60;
                pendingHeight = 24;
                dialogForm.addText("Name", () -> pendingName, v -> pendingName = v, 32);
                dialogForm.addInt("Width (tiles)", () -> pendingWidth, v -> pendingWidth = v, 8, 512, 4);
                dialogForm.addInt("Height (tiles)", () -> pendingHeight, v -> pendingHeight = v, 8, 256, 4);
                dialogForm.addAction("Create", () -> {
                    level = starterLevel(pendingName, pendingWidth, pendingHeight);
                    camera.tileSize = level.tileSize;
                    camera.centerOn(level.spawnX, level.spawnY);
                    closeDialog();
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
                        camera.tileSize = level.tileSize;
                        camera.centerOn(level.spawnX, level.spawnY);
                        closeDialog();
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
            default -> { /* NONE */ }
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

        drawTiles(g);
        if (showGrid && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawWorldBounds(g);
        drawEntities(g);
        drawSpawnMarker(g);
        if (p.particlesEnabled) particles.render(g, camera);
        if (testing && testMe != null) drawTestPlayer(g);

        if (!testing) {
            drawCursorPreview(g);
            drawSidebar(g);
        }
        drawTopBar(g);
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

    private void drawTiles(Graphics2D g) {
        int ts = level.tileSize;
        int[] b = visibleTileBounds();
        for (int r = b[1]; r <= b[3]; r++) {
            for (int c = b[0]; c <= b[2]; c++) {
                int id = level.tileAt(c, r);
                if (id <= 0) continue;
                Color col = level.colorFor(id);
                projectCell(c, r, ts);
                g.setColor(col);
                g.fillPolygon(pxs, pys, 4);
                g.setColor(col.darker());
                g.drawPolygon(pxs, pys, 4);
            }
        }
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

    /** Painted mobs/items: level spawns offline, live snapshot entities online. */
    private void drawEntities(Graphics2D g) {
        MobRegistry mobs = MobRegistry.standard();
        ItemRegistry items = ItemRegistry.standard();
        if (testing && testWorld != null) {
            for (DroppedItem item : testWorld.items()) {
                drawItemAt(g, items.get(item.key), item.x, item.y);
            }
            for (Mob m : testWorld.mobs()) {
                drawMobAt(g, m.def, m.x, m.y, m.facingLeft);
            }
            return;
        }
        if (net != null) {
            Snapshot snap = net.client().latest();
            if (snap != null) {
                for (EntityView e : snap.items()) drawItemAt(g, items.get(e.key), e.x, e.y);
                for (EntityView e : snap.mobs()) {
                    MobDef def = mobs.get(e.key);
                    if (def != null) drawMobAt(g, def, e.x, e.y, e.facingLeft);
                }
                drawNetPlayers(g, snap);
            }
            return;
        }
        for (Level.EntitySpawn e : level.entities) {
            switch (e.kind) {
                case "mob" -> {
                    MobDef def = mobs.get(e.type);
                    if (def != null) drawMobAt(g, def, e.x, e.y, false);
                }
                case "item" -> drawItemAt(g, items.get(e.type), e.x, e.y);
                default -> { /* other spawns aren't painted visuals */ }
            }
        }
    }

    private void drawMobAt(Graphics2D g, MobDef def, double x, double y, boolean facingLeft) {
        BufferedImage img = EntitySprites.mob(def, 32);
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
        BufferedImage img = EntitySprites.item(def, 16);
        int w = Math.max(5, (int) Math.round(DroppedItem.SIZE * camera.zoom));
        camera.worldToScreen(x, y, pcorner);
        g.drawImage(img, pcorner[0], pcorner[1], w, w, null);
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
                if (def != null) drawMobAt(g, def, aim[0], aim[1], false);
            }
            case "item" -> drawItemAt(g, ItemRegistry.standard().get(entry.key), aim[0], aim[1]);
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
            default -> { /* nothing to preview */ }
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

        // Swatch grid.
        int gridTop = y + 10;
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

        // Selected entry name + hints at the bottom.
        Entry sel = selectedEntry();
        g.setColor(new Color(10, 10, 16));
        g.fillRect(0, viewportHeight - 36, SIDEBAR_W, 36);
        g.setColor(new Color(255, 220, 120));
        g.setFont(HUD_FONT);
        g.drawString(sel != null ? sel.name : "", 10, viewportHeight - 20);
        g.setColor(new Color(150, 150, 165));
        g.setFont(SMALL_FONT);
        g.drawString("wheel scrolls · Tab next category", 10, viewportHeight - 6);
    }

    private void drawTopBar(Graphics2D g) {
        int x0 = testing ? 0 : SIDEBAR_W;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(x0, 0, viewportWidth - x0, 28);
        g.setColor(Color.WHITE);
        g.setFont(HUD_FONT);
        String bar;
        if (testing) {
            bar = "PLAY-TEST — " + level.name + "   ·   WASD move · click attack · [P]/[Esc] back to editor";
        } else if (net != null) {
            bar = "CREATIVE (ONLINE) — painting the server's world   ·   [Tab] category · right-click erase"
                    + " · [G] grid · [Esc] back to game";
        } else {
            bar = "CREATIVE — " + level.name + " (" + level.width + "x" + level.height + ")"
                    + "   ·   [Tab] category · right-click erase · middle pick · [G] grid"
                    + " · [P] test · [Ctrl+S] save · [L] load · [N] new · [Esc] menu";
        }
        g.drawString(bar, x0 + 12, 19);
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
        g.drawString("Enter activates · Esc cancels · type to edit the name field",
                24, viewportHeight - 16);
    }

    private void setStatus(String msg) {
        status = msg;
        statusTime = 3.5;
    }

    private static String categoryName(Category c) {
        return switch (c) {
            case BLOCKS -> "Blocks";
            case LIGHTS -> "Lights";
            case MOBS -> "Mobs";
            case ITEMS -> "Items";
            case TOOLS -> "Tools";
        };
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
}
