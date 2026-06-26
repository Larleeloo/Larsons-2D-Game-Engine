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
import com.larsons.engine.scene.AbstractScene;
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

/**
 * Gameplay scene that honours the active {@link GameProfile}: it only enables
 * the features the creator turned on (perspective, zoom + bounds, gravity, HUD,
 * grid, entity sizes) and exposes the same toggles live via a pause menu
 * (Esc), so features can be enabled/disabled both on launch and in-game.
 *
 * <p>Controls: WASD/arrows move, P cycles perspective (if enabled), +/- zoom
 * (if enabled), Esc pause.
 */
public class PlayScene extends AbstractScene {
    private final GameContext ctx;
    private final String levelPath;

    private Level level;
    private Camera camera;
    private Animation walkAnim;

    private double px, py;     // player world position (top-left), pixels
    private double vy;         // vertical velocity (side-scroll only)
    private boolean facingLeft;

    private boolean paused;
    private ConfigForm pauseForm;

    private static final double SPEED = 220;     // px/sec
    private static final double GRAVITY = 1500;  // px/sec^2
    private static final double JUMP = 560;      // px/sec

    public PlayScene(GameContext ctx, String levelPath) {
        this.ctx = ctx;
        this.levelPath = levelPath;
    }

    private GameProfile profile() { return ctx.profile(); }

    @Override
    public void onEnter() {
        paused = false;
        pauseForm = null;
        level = LevelLoader.load(levelPath);

        GameProfile p = profile();
        camera = new Camera(p.perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;
        camera.zoom = p.defaultZoom;

        px = level.spawnX;
        py = level.spawnY;
        vy = 0;

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

        boolean left = input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT);
        boolean right = input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT);
        boolean up = input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP);
        boolean down = input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN);

        double size = ps();
        double dx = 0;
        if (left) { dx -= SPEED * dt; facingLeft = true; }
        if (right) { dx += SPEED * dt; facingLeft = false; }
        boolean moving = dx != 0;

        boolean sideScroll = camera.getPerspective() == Perspective.SIDE_SCROLL && p.gravityEnabled;
        if (sideScroll) {
            boolean grounded = isSolid(px + size / 2.0, py + size + 1);
            if (grounded && vy >= 0) {
                vy = 0;
                if (up) vy = -JUMP;
            } else {
                vy += GRAVITY * dt;
            }
            py += vy * dt;
            if (vy > 0 && isSolid(px + size / 2.0, py + size)) {
                py = Math.floor((py + size) / ts()) * ts() - size;
                vy = 0;
            }
        } else {
            double dy = 0;
            if (up) dy -= SPEED * dt;
            if (down) dy += SPEED * dt;
            py += dy;
            moving = moving || dy != 0;
        }

        px += dx;
        clampToLevel();
        camera.centerOn(px + size / 2.0, py + size / 2.0);
        walkAnim.update(moving ? dt : 0);
    }

    private void updatePaused(double dt, InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            resume();
            return;
        }
        pauseForm.update(dt, input);
        // Apply settings that affect the engine (e.g. FPS cap) live.
        ctx.applyLiveSettings();
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        GameProfile p = profile();
        g.setColor(level.background);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        drawTiles(g);
        if (p.gridVisible && camera.getPerspective() != Perspective.ISOMETRIC) drawGrid(g);
        drawCharacter(g);
        if (p.hudVisible) drawHud(g);

        if (paused) drawPauseOverlay(g);
    }

    // --- pause ---

    private void openPause() {
        paused = true;
        if (pauseForm == null) buildPauseForm();
    }

    private void resume() {
        paused = false;
        syncCameraFromProfile();
    }

    private void buildPauseForm() {
        GameProfile p = profile();
        pauseForm = new ConfigForm("Paused — " + p.name).theme(MenuTheme.dark());
        ProfileForms.addFeatureOptions(pauseForm, p);
        pauseForm.addAction("Resume", this::resume);
        pauseForm.addAction("Save Game Type", () -> { p.normalize(); ctx.save(); });
        pauseForm.addAction("Quit to Menu", () -> scenes.transitionTo("menu"));
    }

    private void drawPauseOverlay(Graphics2D g) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.82f));
        g.setColor(new Color(12, 12, 18));
        g.fillRect(0, 0, viewportWidth, viewportHeight);
        g.setComposite(old);

        pauseForm.render(g, viewportWidth, viewportHeight);
        g.setColor(new Color(120, 120, 140));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("Esc to resume · changes apply live and can be saved to the game type",
                24, viewportHeight - 24);
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

    private void drawTiles(Graphics2D g) {
        int ts = (int) ts();
        for (int r = 0; r < level.height; r++) {
            for (int c = 0; c < level.width; c++) {
                int id = level.tileAt(c, r);
                if (id <= 0) continue;
                Color col = level.colorFor(id);
                double wx = c * ts, wy = r * ts;
                int[] xs = {
                        camera.worldToScreenX(wx, wy),
                        camera.worldToScreenX(wx + ts, wy),
                        camera.worldToScreenX(wx + ts, wy + ts),
                        camera.worldToScreenX(wx, wy + ts)
                };
                int[] ys = {
                        camera.worldToScreenY(wx, wy),
                        camera.worldToScreenY(wx + ts, wy),
                        camera.worldToScreenY(wx + ts, wy + ts),
                        camera.worldToScreenY(wx, wy + ts)
                };
                g.setColor(col);
                g.fillPolygon(xs, ys, 4);
                g.setColor(col.darker());
                g.drawPolygon(xs, ys, 4);
            }
        }
    }

    private void drawGrid(Graphics2D g) {
        int ts = (int) ts();
        g.setColor(new Color(255, 255, 255, 30));
        for (int c = 0; c <= level.width; c++) {
            double wx = c * ts;
            g.drawLine(camera.worldToScreenX(wx, 0), camera.worldToScreenY(wx, 0),
                    camera.worldToScreenX(wx, level.height * ts), camera.worldToScreenY(wx, level.height * ts));
        }
        for (int r = 0; r <= level.height; r++) {
            double wy = r * ts;
            g.drawLine(camera.worldToScreenX(0, wy), camera.worldToScreenY(0, wy),
                    camera.worldToScreenX(level.width * ts, wy), camera.worldToScreenY(level.width * ts, wy));
        }
    }

    private void drawCharacter(Graphics2D g) {
        BufferedImage frame = walkAnim.current();
        if (frame == null) return;
        double size = ps();
        int w = (int) Math.round(size * camera.zoom);
        int h = w;
        int footX = camera.worldToScreenX(px + size / 2.0, py + size);
        int footY = camera.worldToScreenY(px + size / 2.0, py + size);
        int dx = footX - w / 2;
        int dy = footY - h;
        if (facingLeft) {
            g.drawImage(frame, dx + w, dy, -w, h, null);
        } else {
            g.drawImage(frame, dx, dy, w, h, null);
        }
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, viewportWidth, 38);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        StringBuilder hud = new StringBuilder();
        hud.append(profile().name)
                .append("    |    ").append(camera.getPerspective());
        if (profile().zoomEnabled) hud.append("    |    zoom ").append(String.format("%.2f", camera.zoom));
        hud.append("    |    [Esc] pause");
        if (profile().perspectiveSwitchingEnabled) hud.append("  [P] perspective");
        if (profile().zoomEnabled) hud.append("  [+/-] zoom");
        g.drawString(hud.toString(), 12, 24);
    }

    // --- world helpers ---

    private double ts() { return level.tileSize; }

    private double ps() { return profile().playerSize; }

    private void rebuildSprite() {
        int size = Math.max(8, (int) ps());
        SpriteSheet sprites = SpriteSheet.fromImage(buildCharacterSheet(size), size, size);
        walkAnim = sprites.animation(10, true);
    }

    private boolean isSolid(double worldX, double worldY) {
        int col = (int) Math.floor(worldX / ts());
        int row = (int) Math.floor(worldY / ts());
        return level.tileAt(col, row) > 0;
    }

    private void clampToLevel() {
        double maxX = Math.max(0, level.width * ts() - ps());
        double maxY = Math.max(0, level.height * ts() - ps());
        px = Math.max(0, Math.min(px, maxX));
        py = Math.max(0, Math.min(py, maxY));
    }

    private BufferedImage buildCharacterSheet(int size) {
        int frames = 4;
        BufferedImage img = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int f = 0; f < frames; f++) {
            int ox = f * size;
            int bob = (f % 2 == 0) ? 0 : Math.max(1, size / 16);
            g.setColor(new Color(70, 130, 220));
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
