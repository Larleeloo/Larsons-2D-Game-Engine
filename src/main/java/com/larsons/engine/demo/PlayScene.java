package com.larsons.engine.demo;

import com.larsons.engine.graphics.Animation;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelLoader;
import com.larsons.engine.scene.AbstractScene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;

/**
 * Demo gameplay scene tying the "essentials" together:
 * <ul>
 *   <li>loads a level from JSON (requirement #6: level loading),</li>
 *   <li>renders its tiles through a {@link Camera} that supports multiple
 *       perspectives, switchable at runtime with [P] (requirement #2),</li>
 *   <li>drives an animated character built from an in-memory
 *       {@link SpriteSheet} (requirement #6: sprite sheet support).</li>
 * </ul>
 *
 * <p>Controls: WASD/arrows move, P cycles perspective, +/- zoom, ESC to menu.
 * In SIDE_SCROLL the character has gravity and can jump; in TOP_DOWN and
 * ISOMETRIC it moves freely on both axes — illustrating how one scene adapts to
 * different perspectives.
 */
public class PlayScene extends AbstractScene {
    private final String levelPath;

    private Level level;
    private Camera camera;
    private Animation walkAnim;

    private double px, py;        // character world position (top-left), pixels
    private double vy;            // vertical velocity (side-scroll only)
    private boolean facingLeft;

    private static final double SPEED = 220;     // px/sec
    private static final double GRAVITY = 1500;  // px/sec^2
    private static final double JUMP = 560;      // px/sec

    public PlayScene(String levelPath) {
        this.levelPath = levelPath;
    }

    @Override
    public void onEnter() {
        level = LevelLoader.load(levelPath);

        camera = new Camera(level.perspective, viewportWidth, viewportHeight);
        camera.tileSize = level.tileSize;

        px = level.spawnX;
        py = level.spawnY;
        vy = 0;

        // Build a placeholder character sprite sheet entirely in code so the
        // demo needs no binary art files yet still exercises SpriteSheet.
        BufferedImage sheet = buildCharacterSheet(level.tileSize);
        SpriteSheet sprites = SpriteSheet.fromImage(sheet, level.tileSize, level.tileSize);
        walkAnim = sprites.animation(10, true);
    }

    @Override
    public void onResize(int w, int h) {
        super.onResize(w, h);
        if (camera != null) camera.setViewport(w, h);
    }

    @Override
    public void update(double dt, InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            scenes.transitionTo("menu");
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_P)) {
            camera.setPerspective(camera.getPerspective().next());
            level.perspective = camera.getPerspective();
            vy = 0;
        }
        if (input.isKeyDown(KeyEvent.VK_EQUALS)) camera.zoom = Math.min(4.0, camera.zoom + dt * 2);
        if (input.isKeyDown(KeyEvent.VK_MINUS)) camera.zoom = Math.max(0.25, camera.zoom - dt * 2);

        boolean left = input.isKeyDown(KeyEvent.VK_A) || input.isKeyDown(KeyEvent.VK_LEFT);
        boolean right = input.isKeyDown(KeyEvent.VK_D) || input.isKeyDown(KeyEvent.VK_RIGHT);
        boolean up = input.isKeyDown(KeyEvent.VK_W) || input.isKeyDown(KeyEvent.VK_UP);
        boolean down = input.isKeyDown(KeyEvent.VK_S) || input.isKeyDown(KeyEvent.VK_DOWN);

        double ts = ts();
        double dx = 0;
        if (left) { dx -= SPEED * dt; facingLeft = true; }
        if (right) { dx += SPEED * dt; facingLeft = false; }
        boolean moving = dx != 0;

        if (camera.getPerspective() == Perspective.SIDE_SCROLL) {
            boolean grounded = isSolid(px + ts / 2.0, py + ts + 1);
            if (grounded && vy >= 0) {
                vy = 0;
                if (up) vy = -JUMP;
            } else {
                vy += GRAVITY * dt;
            }
            py += vy * dt;
            // Resolve landing on a solid tile below the feet.
            if (vy > 0 && isSolid(px + ts / 2.0, py + ts)) {
                py = Math.floor((py + ts) / ts) * ts - ts;
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
        camera.centerOn(px + ts / 2.0, py + ts / 2.0);
        walkAnim.update(moving ? dt : 0);
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        g.setColor(level.background);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        drawTiles(g);
        drawCharacter(g);
        drawHud(g);
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

                // Project the tile's four world corners. For orthographic
                // perspectives this is an axis-aligned rect; for isometric it
                // forms a diamond — the same code handles every perspective.
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

    private void drawCharacter(Graphics2D g) {
        BufferedImage frame = walkAnim.current();
        if (frame == null) return;
        double ts = ts();
        int w = (int) Math.round(ts * camera.zoom);
        int h = w;
        // Anchor the sprite's bottom-centre to the character's feet so it sits
        // correctly under every perspective.
        int footX = camera.worldToScreenX(px + ts / 2.0, py + ts);
        int footY = camera.worldToScreenY(px + ts / 2.0, py + ts);
        int dx = footX - w / 2;
        int dy = footY - h;

        if (facingLeft) {
            g.drawImage(frame, dx + w, dy, -w, h, null); // horizontal flip
        } else {
            g.drawImage(frame, dx, dy, w, h, null);
        }
    }

    private void drawHud(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, viewportWidth, 38);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString(level.name
                        + "    |    Perspective: " + camera.getPerspective()
                        + "    |    [WASD/Arrows] move   [P] perspective   [+/-] zoom   [ESC] menu",
                12, 24);
    }

    // --- world helpers ---

    private double ts() { return level.tileSize; }

    private boolean isSolid(double worldX, double worldY) {
        int col = (int) Math.floor(worldX / ts());
        int row = (int) Math.floor(worldY / ts());
        return level.tileAt(col, row) > 0;
    }

    private void clampToLevel() {
        double maxX = Math.max(0, level.width * ts() - ts());
        double maxY = Math.max(0, level.height * ts() - ts());
        px = Math.max(0, Math.min(px, maxX));
        py = Math.max(0, Math.min(py, maxY));
    }

    /**
     * Draws a tiny 4-frame placeholder character sheet (one row, frames laid
     * out left-to-right). The legs alternate between frames so the walk
     * animation is visible.
     */
    private BufferedImage buildCharacterSheet(int size) {
        int frames = 4;
        BufferedImage img = new BufferedImage(size * frames, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int f = 0; f < frames; f++) {
            int ox = f * size;
            int bob = (f % 2 == 0) ? 0 : Math.max(1, size / 16);

            // Body.
            g.setColor(new Color(70, 130, 220));
            g.fillRoundRect(ox + size / 4, size / 4 + bob, size / 2, size / 2, size / 6, size / 6);
            // Head.
            g.setColor(new Color(245, 210, 170));
            g.fillOval(ox + size / 3, size / 8 + bob, size / 3, size / 3);
            // Legs (alternating stance).
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
