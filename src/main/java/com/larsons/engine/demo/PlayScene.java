package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.SpriteSheet;
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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gameplay scene that honours the active {@link GameProfile}: it only enables
 * the features the creator turned on (perspective, zoom + bounds, gravity, HUD,
 * grid, entity sizes) and exposes the same toggles live via a pause menu
 * (Esc), so features can be enabled/disabled both on launch and in-game.
 *
 * <p><b>Online play (requirement #3).</b> When the {@link GameContext} carries
 * a {@link NetSession}, this same scene becomes the multiplayer client: the
 * level comes from the server, every tick's input command is sent up, the
 * local player is <em>predicted</em> with the identical
 * {@link PlayerPhysics} the server runs (then smoothly corrected toward the
 * authoritative snapshots), and remote players are interpolated between the
 * two most recent snapshots. Movement code is shared, so single-player and
 * online play can't drift apart.
 *
 * <p>Controls: WASD/arrows move, P cycles perspective (if enabled), +/- zoom
 * (if enabled), Esc pause.
 */
public class PlayScene extends AbstractScene {

    /** Remote players are drawn this far in the past, between two snapshots. */
    private static final long INTERP_DELAY_NANOS = 100_000_000L; // 100 ms

    /** Prediction errors beyond this snap instantly (teleports, big lag spikes). */
    private static final double SNAP_DISTANCE = 128;

    /** How aggressively prediction errors are blended away, per second. */
    private static final double CORRECTION_PER_SEC = 8.0;

    private static final Font HUD_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 12);

    private final GameContext ctx;
    private final String levelPath;

    private Level level;
    private Camera camera;
    private Animation walkAnim;

    private PlayerState me = new PlayerState();
    private int inputSeq;

    private NetSession net; // null in single-player
    private final Map<Integer, Animation> remoteAnims = new HashMap<>();

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

        // Online, the world is whatever the server sent; offline, load from disk.
        if (net != null && net.client().levelJson() != null) {
            level = LevelLoader.parse(net.client().levelJson());
        } else {
            level = LevelLoader.load(levelPath);
        }

        GameProfile p = profile();
        camera = new Camera(p.perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = p.defaultZoom;

        me = new PlayerState(net != null ? net.client().localId() : 0, "",
                level.spawnX, level.spawnY);

        rebuildSprite();
        syncCameraFromProfile();
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
            openPause();
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

        PlayerInput in = new PlayerInput(
                input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT),
                input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT),
                input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP),
                input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN),
                ++inputSeq);

        // Online, physics must not depend on the local camera view — the server
        // simulates with the profile's perspective, so prediction does too.
        Perspective simPerspective = net != null ? p.perspective : camera.getPerspective();
        PlayerPhysics.step(me, in, level, p, simPerspective, dt);

        if (net != null) {
            net.client().sendInput(in);
            reconcile(dt);
            advanceRemoteAnimations(dt);
        }

        double size = ps();
        camera.centerOn(me.x + size / 2.0, me.y + size / 2.0);
        walkAnim.update(me.moving ? dt : 0);
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
        g.setColor(level.background);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        drawTiles(g);
        if (p.gridVisible && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        if (net != null) drawRemotePlayers(g);
        drawPlayer(g, me.x, me.y, me.facingLeft, walkAnim.current(), null);
        if (p.hudVisible) drawHud(g);
        if (net != null) drawEvents(g);

        if (paused) drawPauseOverlay(g);
        if (net != null && !net.client().isConnected()) drawDisconnectOverlay(g);
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
            pauseForm.addAction("Save Game Type", () -> { p.normalize(); ctx.save(); });
            pauseForm.addAction("Quit to Menu", () -> scenes.transitionTo("menu"));
        } else {
            // Online the server owns the rules: no live feature editing, or the
            // local simulation would no longer match the authoritative one.
            pauseForm.addAction("Resume", this::resume);
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
        g.drawString(hud.toString(), 12, 24);
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
