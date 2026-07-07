package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.audio.AudioManager.Sfx;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.EntityView;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.ParallaxBackground;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.net.GameClient;
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
import java.awt.RenderingHints;
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
    private Inventory inventory;
    private int invSyncVersion = -1;
    private boolean showInventory;

    private ParallaxBackground parallax;
    private final Particles particles = new Particles();
    private double swingTime;      // seconds left on the melee swing visual
    private double prevVy;
    private double prevHealth = PlayerState.MAX_HEALTH;

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
        showInventory = false;
        swingTime = 0;

        // Online, the world is whatever the server runs (one shared Level
        // instance that block broadcasts keep current); offline, prefer the
        // game type's last saved creative level, falling back to the bundled
        // sample.
        if (net != null && net.client().level() != null) {
            level = net.client().level();
            world = null;
        } else {
            level = loadOfflineLevel();
            world = new World(level);
            world.populateFromLevel(profile());
            world.setPickupListener((player, key, count) -> {
                inventory.add(key, count);
                ctx.sfx(Sfx.PICKUP);
            });
        }
        inventory = new Inventory(world != null ? world.itemTypes : ItemRegistry.standard());
        invSyncVersion = -1;

        GameProfile p = profile();
        camera = new Camera(p.perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = p.defaultZoom;

        me = new PlayerState(net != null ? net.client().localId() : 0, "",
                level.spawnX, level.spawnY);
        prevHealth = me.health;

        parallax = null; // rebuilt lazily against the level's background
        rebuildSprite();
        syncCameraFromProfile();
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
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            if (showInventory) {
                showInventory = false;
            } else {
                openPause();
            }
            return;
        }

        GameProfile p = profile();
        enforceProfileConstraints(p);

        if (p.perspectiveSwitchingEnabled && input.isKeyJustPressed(KeyEvent.VK_P)) {
            camera.setPerspective(camera.getPerspective().next());
        }
        if (p.zoomEnabled) {
            if (input.isKeyDown(KeyEvent.VK_EQUALS)) camera.zoom = clampZoom(camera.zoom + dt * 2, p);
            if (input.isKeyDown(KeyEvent.VK_MINUS)) camera.zoom = clampZoom(camera.zoom - dt * 2, p);
        }

        updateInventoryControls(input, p);

        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);

        if (!showInventory) handleMouseActions(input, p, in);

        // Online, physics must not depend on the local camera view — the server
        // simulates with the profile's perspective, so prediction does too.
        Perspective simPerspective = net != null ? p.perspective : camera.getPerspective();
        prevVy = me.vy;
        PlayerPhysics.step(me, in, level, p, simPerspective, dt);
        if (me.vy < -1 && prevVy >= 0) ctx.sfx(Sfx.JUMP);

        if (net != null) {
            net.client().sendInput(in);
            reconcile(dt);
            advanceRemoteAnimations(dt);
            consumeNetFeedback();
        } else {
            world.step(dt, List.of(me), p);
        }

        if (me.health < prevHealth - 0.01) ctx.sfx(Sfx.HURT);
        prevHealth = me.health;

        if (swingTime > 0) swingTime -= dt;
        if (p.particlesEnabled) particles.update(dt);

        double size = ps();
        camera.centerOn(me.x + size / 2.0, me.y + size / 2.0);
        walkAnim.update(me.moving ? dt : 0);
    }

    // --- items & block interaction ------------------------------------------------

    private void updateInventoryControls(InputManager input, GameProfile p) {
        if (!p.itemsEnabled) {
            showInventory = false;
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_I)) showInventory = !showInventory;
        for (int k = 0; k < Inventory.HOTBAR; k++) {
            if (input.isKeyJustPressed(KeyEvent.VK_1 + k)) inventory.select(k);
        }
        int wheel = input.getWheelRotation();
        if (wheel != 0) inventory.scrollSelect(wheel > 0 ? 1 : -1);

        // F consumes the selected food/potion (offline only: online the server
        // owns health and inventory, and doesn't take consume requests yet).
        if (net == null && input.isKeyJustPressed(KeyEvent.VK_F)) {
            ItemDef def = inventory.selectedDef();
            if (def != null && def.heal() > 0 && me.health < PlayerState.MAX_HEALTH
                    && inventory.consumeSelected()) {
                me.health = Math.min(PlayerState.MAX_HEALTH, me.health + def.heal());
                prevHealth = me.health; // don't play the hurt sound on heals
                ctx.sfx(Sfx.EAT);
            }
        }
    }

    /**
     * Left click: mine the aimed block (if block editing is on and it's in
     * reach) or swing at mobs (if combat is on). Right click: place the
     * selected hotbar block. Online these become server requests.
     */
    private void handleMouseActions(InputManager input, GameProfile p, PlayerInput in) {
        boolean leftClick = input.isMouseJustPressed();
        boolean rightClick = input.isRightMouseJustPressed();
        if (!leftClick && !rightClick) return;

        double[] aim = camera.screenToWorld(input.getMouseX(), input.getMouseY());
        double ts = ts();
        int col = (int) Math.floor(aim[0] / ts);
        int row = (int) Math.floor(aim[1] / ts);
        boolean inReach = Math.hypot(aim[0] - (me.x + ps() / 2), aim[1] - (me.y + ps() / 2))
                <= REACH_TILES * ts;

        if (leftClick) {
            if (p.blockEditingEnabled && inReach && level.tileAt(col, row) > 0) {
                mineAt(col, row, p);
            } else if (p.combatEnabled) {
                swingAt(aim[0], aim[1], in, p);
            }
        }
        if (rightClick && p.blockEditingEnabled && inReach) {
            placeAt(col, row, p);
        }
    }

    private void mineAt(int col, int row, GameProfile p) {
        if (net != null) {
            net.client().sendBlockEdit(col, row, 0, "play");
            return; // feedback arrives with the authoritative broadcast
        }
        Block mined = world.mineBlock(col, row, p.itemsEnabled);
        boolean changed = mined != null;
        if (!changed) changed = level.setTile(col, row, 0); // legacy palette tile
        if (changed) {
            ctx.sfx(Sfx.BREAK);
            if (p.particlesEnabled) {
                Color c = mined != null ? mined.color() : Color.GRAY;
                particles.burst((col + 0.5) * ts(), (row + 0.5) * ts(), c, 10);
            }
        }
    }

    private void placeAt(int col, int row, GameProfile p) {
        ItemDef def = p.itemsEnabled ? inventory.selectedDef() : null;
        if (p.itemsEnabled && (def == null || def.category() != ItemDef.Category.BLOCK)) {
            return; // nothing placeable selected
        }
        String blockKey = def != null ? def.blockKey() : "dirt";
        Block b = level.blocks.get(blockKey);
        if (b == null || level.tileAt(col, row) != 0) return;
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
            ctx.sfx(Sfx.PLACE);
        }
    }

    private void swingAt(double aimX, double aimY, PlayerInput in, GameProfile p) {
        swingTime = 0.2;
        ItemDef held = p.itemsEnabled ? inventory.selectedDef() : null;
        double damage = World.FIST_DAMAGE + (held != null ? held.damage() : 0);
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
        }
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
     * Blend the predicted local player toward the server's authoritative state.
     * Small errors (network jitter, sampling differences) are smoothed away;
     * large ones (teleport, heavy lag) snap.
     */
    private void reconcile(double dt) {
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

        drawTiles(g);
        if (p.gridVisible && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawWorldEntities(g, p);
        if (net != null) drawRemotePlayers(g);
        drawPlayer(g, me.x, me.y, me.facingLeft, walkAnim.current(), null);
        if (swingTime > 0) drawSwing(g);
        if (p.particlesEnabled) particles.render(g, camera);
        if (p.hudVisible) drawHud(g);
        if (p.itemsEnabled) drawHotbar(g);
        if (p.combatEnabled || p.mobsEnabled) drawHealthBar(g);
        if (net != null) drawEvents(g);
        if (showInventory) drawInventory(g);

        if (paused) drawPauseOverlay(g);
        if (net != null && !net.client().isConnected()) drawDisconnectOverlay(g);
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
        // Player glow.
        camera.worldToScreen(me.x + ps() / 2, me.y + ps() / 2, corner);
        lighting.addLight(corner[0], corner[1], 2.5 * ts * camera.zoom,
                new Color(255, 240, 210));
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
            ProfileForms.addFeatureOptions(pauseForm, p);
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Creative Editor (paint this world)",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction("Save Game Type", () -> { p.normalize(); ctx.save(); });
            pauseForm.addAction("Quit to Menu", () -> scenes.transitionTo("menu"));
        } else {
            // Online the server owns the rules: no live feature editing, or the
            // local simulation would no longer match the authoritative one.
            pauseForm.addAction("Resume", this::resume);
            pauseForm.addAction("Creative Editor (paint this world)",
                            () -> scenes.transitionTo("creative"))
                    .enabledWhen(() -> p.creativeEnabled);
            pauseForm.addAction(net.isHost() ? "Stop Server & Quit" : "Disconnect & Quit", () -> {
                ctx.closeSession();
                net = null;
                scenes.transitionTo("menu");
            });
        }
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
                        ? "Esc to resume · changes apply live and can be saved to the game type"
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

    private void enforceProfileConstraints(GameProfile p) {
        if (!p.perspectiveSwitchingEnabled) camera.setPerspective(p.perspective);
        camera.zoom = p.zoomEnabled ? clampZoom(camera.zoom, p) : clampZoom(p.defaultZoom, p);
        // Player sprite tracks the configured size.
        if (walkAnim == null || walkAnim.frameCount() == 0) rebuildSprite();
    }

    private void syncCameraFromProfile() {
        GameProfile p = profile();
        camera.tileSize = level.tileSize;
        if (!p.perspectiveSwitchingEnabled) camera.setPerspective(p.perspective);
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
        for (int r = b[1]; r <= b[3]; r++) {
            for (int c = b[0]; c <= b[2]; c++) {
                int id = level.tileAt(c, r);
                if (id <= 0) continue;
                Color col = level.colorFor(id);
                double wx = c * ts, wy = r * ts;
                camera.worldToScreen(wx, wy, corner);
                xs[0] = corner[0]; ys[0] = corner[1];
                camera.worldToScreen(wx + ts, wy, corner);
                xs[1] = corner[0]; ys[1] = corner[1];
                camera.worldToScreen(wx + ts, wy + ts, corner);
                xs[2] = corner[0]; ys[2] = corner[1];
                camera.worldToScreen(wx, wy + ts, corner);
                xs[3] = corner[0]; ys[3] = corner[1];
                g.setColor(col);
                g.fillPolygon(xs, ys, 4);
                g.setColor(col.darker());
                g.drawPolygon(xs, ys, 4);
            }
        }
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

    /** Mobs + dropped items: the offline world's, or the server snapshot's. */
    private void drawWorldEntities(Graphics2D g, GameProfile p) {
        if (net == null) {
            for (DroppedItem item : world.items()) {
                drawItemSprite(g, item.key, item.x, item.y, item.count);
            }
            for (Mob m : world.mobs()) {
                drawMobSprite(g, m.def, m.x, m.y, m.facingLeft, m.health, m.hurting());
            }
        } else {
            Snapshot snap = net.client().latest();
            if (snap == null) return;
            for (EntityView item : snap.items()) {
                drawItemSprite(g, item.key, item.x, item.y, item.count);
            }
            MobRegistry mobs = MobRegistry.standard();
            for (EntityView mv : snap.mobs()) {
                MobDef def = mobs.get(mv.key);
                if (def != null) {
                    drawMobSprite(g, def, mv.x, mv.y, mv.facingLeft, mv.health, false);
                }
            }
        }
    }

    private void drawMobSprite(Graphics2D g, MobDef def, double x, double y,
                               boolean facingLeft, double health, boolean hurt) {
        BufferedImage img = EntitySprites.mob(def, 32);
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
        BufferedImage img = EntitySprites.item(def, 16);
        int w = Math.max(6, (int) Math.round(DroppedItem.SIZE * camera.zoom));
        camera.worldToScreen(x, y, corner);
        g.drawImage(img, corner[0], corner[1], w, w, null);
        if (count > 1) {
            g.setFont(SMALL_FONT);
            g.setColor(Color.WHITE);
            g.drawString("x" + count, corner[0] + w, corner[1] + w);
        }
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

        for (PlayerState ps : latest.players()) {
            if (ps.id == me.id) continue;
            PlayerState old = prev != null ? prev.player(ps.id) : null;
            double x = old != null ? old.x + (ps.x - old.x) * t : ps.x;
            double y = old != null ? old.y + (ps.y - old.y) * t : ps.y;
            Animation anim = remoteAnims.computeIfAbsent(ps.id, this::buildRemoteAnimation);
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
    }

    private void drawInventory(Graphics2D g) {
        int slot = 46, pad = 6;
        int gw = Inventory.COLS * (slot + pad) - pad;
        int gh = Inventory.ROWS * (slot + pad) - pad;
        int x0 = (viewportWidth - gw) / 2;
        int y0 = (viewportHeight - gh) / 2;

        g.setColor(new Color(10, 10, 16, 220));
        g.fillRoundRect(x0 - 20, y0 - 52, gw + 40, gh + 84, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("Inventory", x0, y0 - 24);
        g.setFont(SMALL_FONT);
        g.setColor(new Color(170, 170, 190));
        g.drawString("Slots 1-5 are the hotbar · [I]/[Esc] close · [F] eat selected", x0, y0 - 8);

        for (int i = 0; i < Inventory.SIZE; i++) {
            int cx = x0 + (i % Inventory.COLS) * (slot + pad);
            int cy = y0 + (i / Inventory.COLS) * (slot + pad);
            boolean hotbar = i < Inventory.HOTBAR;
            boolean sel = i == inventory.selectedIndex();
            g.setColor(new Color(255, 255, 255, hotbar ? 36 : 18));
            g.fillRoundRect(cx, cy, slot, slot, 8, 8);
            g.setColor(sel ? new Color(255, 220, 120) : new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(sel ? 2.5f : 1f));
            g.drawRoundRect(cx, cy, slot, slot, 8, 8);
            drawStack(g, inventory.slot(i), cx, cy, slot);
        }
    }

    private void drawStack(Graphics2D g, ItemStack stack, int x, int y, int slot) {
        if (stack == null) return;
        ItemDef def = (world != null ? world.itemTypes : ItemRegistry.standard()).get(stack.key);
        if (def == null) return;
        BufferedImage img = EntitySprites.item(def, 32);
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
        // Golden-ratio hue spacing gives each player a distinct, stable colour.
        Color body = Color.getHSBColor((id * 0.6180339887f) % 1f, 0.6f, 0.85f);
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
