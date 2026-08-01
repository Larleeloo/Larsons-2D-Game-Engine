package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.BoardTheme;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The board customization menu, reached from the auto-battler lobby: pick a
 * colour scheme for your personal board, set a background image, and place
 * decorative props (plants, statues, lanterns...) anywhere around the board —
 * click a decoration in the preview to select it, drag it to move it (or
 * nudge with the arrow keys), and import a custom image per decoration slot.
 * Everything is cosmetic — none of it touches gameplay — and everything
 * applies live to {@link BoardTheme#active()} with a board preview beside the
 * form, persisting to {@code board_theme.json} in your game files.
 *
 * <p>Background and decoration images import automatically: click the import
 * action, pick a file in the browser, and it's copied into
 * {@code resources/skins/boards/} and assigned — no paths to type.
 */
public class BoardCustomizeScene extends AbstractScene {

    private final GameContext ctx;

    private ConfigForm form;
    private BoardTheme theme;
    private double animClock;

    private String status = "";
    private Color statusColor = new Color(140, 200, 150);

    // The preview's decoration editor: the selected slot, live drag state, and
    // the preview's screen geometry (recorded while rendering, like the HUD's
    // deferred hit-rects, so update-time hit tests agree with what's drawn).
    private int selectedSlot;
    private boolean draggingProp;
    private final Rectangle previewRect = new Rectangle();
    private double previewOx, previewOy, previewTw, previewTh;

    public BoardCustomizeScene(GameContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void onEnter() {
        theme = BoardTheme.active();
        status = "";
        selectedSlot = 0;
        draggingProp = false;
        buildForm();
    }

    /** Rebuilt on every change so the cycler labels stay current. */
    private void buildForm() {
        form = new ConfigForm("Board Customization").theme(MenuTheme.dark());
        form.addAction("Color scheme:  " + theme.scheme().name, () -> {
            theme.cycleScheme();
            persist("Scheme: " + theme.scheme().name);
        });
        form.addAction("Import background image…  (opens a file browser)",
                this::importBackground);
        if (!theme.background().isEmpty()) {
            form.addAction("Clear background image", () -> {
                theme.setBackground("");
                persist("Background cleared");
            });
        }
        for (int i = 0; i < BoardTheme.PROP_SLOTS; i++) {
            final int slot = i;
            String custom = theme.propImage(i).isEmpty() ? "" : " [custom image]";
            form.addAction((i == selectedSlot ? "▶ " : "") + "Prop — "
                    + BoardTheme.SLOT_LABELS[i] + ":  "
                    + pretty(theme.prop(i).name()) + custom, () -> {
                selectedSlot = slot;
                theme.cycleProp(slot);
                persist(BoardTheme.SLOT_LABELS[slot] + ": "
                        + pretty(theme.prop(slot).name()));
            });
        }
        form.addAction("Import image for selected prop…  (opens a file browser)",
                this::importPropImage);
        if (!theme.propImage(selectedSlot).isEmpty()) {
            form.addAction("Clear selected prop's image", () -> {
                theme.setPropImage(selectedSlot, "");
                persist("Prop image cleared");
            });
        }
        form.addAction("Reset to defaults", () -> {
            theme.reset();
            persist("Back to the default board");
        });
        form.addAction("Back", () -> scenes.transitionTo("autolobby"));
    }

    /** Save the live theme and refresh the form labels. */
    private void persist(String message) {
        try {
            theme.save();
            setStatus(message + "  ✓ saved", false);
        } catch (UncheckedIOException e) {
            setStatus("Could not save board theme: " + e.getMessage(), true);
        }
        buildForm();
        ctx.sfx(AudioManager.Sfx.CLICK);
    }

    /**
     * The automatic import flow: browse for an image, copy it into the skins
     * folder, and assign it — the path is filled in for you.
     */
    private void importBackground() {
        File picked = chooseImage();
        if (picked == null) return;
        try {
            Path dest = Path.of(BoardTheme.DEFAULT_DIR, "boards",
                    sanitizeFileName(picked.getName()));
            Files.createDirectories(dest.getParent());
            Files.copy(picked.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            AssetLoader.clearCache(); // the path may have been cached as missing
            theme.setBackground(dest.toString().replace('\\', '/'));
            persist("Imported " + picked.getName());
            ctx.sfx(AudioManager.Sfx.PICKUP);
        } catch (IOException e) {
            setStatus("Could not import '" + picked.getName() + "': " + e.getMessage(), true);
        }
    }

    /** Import an image for the selected decoration slot, copied like backgrounds. */
    private void importPropImage() {
        File picked = chooseImage();
        if (picked == null) return;
        try {
            Path dest = Path.of(BoardTheme.DEFAULT_DIR, "boards",
                    sanitizeFileName(picked.getName()));
            Files.createDirectories(dest.getParent());
            Files.copy(picked.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            AssetLoader.clearCache();
            theme.setPropImage(selectedSlot, dest.toString().replace('\\', '/'));
            persist(BoardTheme.SLOT_LABELS[selectedSlot] + " now shows "
                    + picked.getName());
            ctx.sfx(AudioManager.Sfx.PICKUP);
        } catch (IOException e) {
            setStatus("Could not import '" + picked.getName() + "': " + e.getMessage(), true);
        }
    }

    /** A native file browser for images; null when cancelled or unavailable. */
    private File chooseImage() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a board background image");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                return chooser.getSelectedFile();
            }
        } catch (RuntimeException e) {
            setStatus("File browser unavailable: " + e.getMessage(), true);
        }
        return null;
    }

    static String sanitizeFileName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    ? c : '_');
        }
        return sb.length() == 0 ? "import.png" : sb.toString();
    }

    private void setStatus(String text, boolean warn) {
        status = text;
        statusColor = warn ? new Color(235, 180, 110) : new Color(140, 200, 150);
    }

    private static String pretty(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }

    // --- loop ------------------------------------------------------------------------

    @Override
    public void update(double dt, InputManager input) {
        animClock += dt;
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            scenes.transitionTo("autolobby");
            return;
        }
        updatePropEditor(input);
        form.update(dt, input);
    }

    /**
     * The preview's decoration editor: click a decoration (or an empty slot
     * marker) to select it, drag it to reposition, arrow keys nudge by a
     * quarter cell. Placement persists when the drag or nudge ends.
     */
    private void updatePropEditor(InputManager input) {
        int mx = input.getMouseX();
        int my = input.getMouseY();
        boolean down = input.isMouseDown();

        if (input.isMouseJustPressed() && previewRect.contains(mx, my)
                && previewTw > 0) {
            int hit = slotAt(mx, my);
            if (hit >= 0) {
                if (hit != selectedSlot) {
                    selectedSlot = hit;
                    buildForm(); // move the ▶ marker
                }
                draggingProp = true;
            }
        }
        if (draggingProp) {
            if (down) {
                double[] cell = screenToCell(mx, my);
                theme.placeProp(selectedSlot, cell[0] - 0.5, cell[1] - 0.5);
            } else {
                draggingProp = false;
                persist(BoardTheme.SLOT_LABELS[selectedSlot] + " placed");
            }
            return;
        }
        if (previewTw > 0) {
            double step = 0.25;
            double dc = 0, dr = 0;
            if (KeyBinds.pressed(input, GameAction.MENU_LEFT)) dc -= step;
            if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)) dc += step;
            if (KeyBinds.pressed(input, GameAction.MENU_UP)) dr -= step;
            if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) dr += step;
            if (dc != 0 || dr != 0) {
                theme.nudgeProp(selectedSlot, dc, dr);
                persist(BoardTheme.SLOT_LABELS[selectedSlot] + " nudged");
            }
        }
    }

    /** The decoration slot whose marker sits under the pointer, or -1. */
    private int slotAt(int mx, int my) {
        int best = -1;
        double bestDist = 16 * 16; // pixels of grab slack
        for (int i = 0; i < BoardTheme.PROP_SLOTS; i++) {
            int[] p = slotScreen(i);
            double dx = mx - p[0], dy = my - p[1];
            double d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** A slot's screen position inside the preview. */
    private int[] slotScreen(int slot) {
        double col = theme.propCol(slot) + 0.5;
        double row = theme.propRow(slot) + 0.5;
        int cx = (int) (previewOx + (col - row) * previewTw / 2);
        int cy = (int) (previewOy + (col + row) * previewTh / 2);
        return new int[]{cx, cy};
    }

    /** Invert the preview's isometric transform: screen point -> board cell. */
    private double[] screenToCell(int mx, int my) {
        double a = (mx - previewOx) / (previewTw / 2);
        double b = (my - previewOy) / (previewTh / 2);
        return new double[]{(b + a) / 2, (b - a) / 2};
    }

    @Override
    public void render(Graphics2D g, float alpha) {
        form.render(g, viewportWidth, viewportHeight);
        drawPreview(g);
        if (!status.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.setColor(statusColor);
            g.drawString(status, 24, viewportHeight - 46);
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(120, 125, 145));
        g.drawString("Cosmetic only — your opponents' boards and combat are unaffected. "
                + "Saved to " + theme.file(), 24, viewportHeight - 22);
    }

    /** A live miniature of the themed board: backdrop, tiles, and props. */
    private void drawPreview(Graphics2D g) {
        BoardTheme.Scheme scheme = theme.scheme();
        int pw = 280, ph = 210;
        int px = viewportWidth - pw - 30;
        int py = Math.max(60, viewportHeight / 8);
        if (px < viewportWidth / 2 + 40) px = viewportWidth / 2 + 40;
        previewRect.setBounds(px, py, pw, ph);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Preview  —  click & drag a decoration, arrows nudge", px, py - 6);

        Shape oldClip = g.getClip();
        g.clip(new Rectangle(px, py, pw, ph));
        g.setPaint(new GradientPaint(px, py, scheme.bgTop, px, py + ph, scheme.bgBottom));
        g.fillRect(px, py, pw, ph);
        if (!theme.background().isEmpty()) {
            BufferedImage bg = AssetLoader.loadImageOrNull(theme.background());
            if (bg != null) {
                double scale = Math.max(pw / (double) bg.getWidth(),
                        ph / (double) bg.getHeight());
                int bw = (int) (bg.getWidth() * scale);
                int bh = (int) (bg.getHeight() * scale);
                g.drawImage(bg, px + (pw - bw) / 2, py + (ph - bh) / 2, bw, bh, null);
                g.setColor(new Color(8, 9, 16, 150));
                g.fillRect(px, py, pw, ph);
            }
        }

        // Miniature isometric board.
        double tw = 22, th = 11;
        double ox = px + pw / 2.0, oy = py + ph / 2.0 - 4.5 * th;
        previewOx = ox;
        previewOy = oy;
        previewTw = tw;
        previewTh = th;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                boolean own = r >= 4;
                Color base = (c + r) % 2 == 0
                        ? (own ? scheme.ownA : scheme.tileA)
                        : (own ? scheme.ownB : scheme.tileB);
                g.setColor(base);
                g.fillPolygon(miniTile(ox, oy, tw, th, c, r));
                g.setColor(new Color(20, 22, 34));
                g.drawPolygon(miniTile(ox, oy, tw, th, c, r));
            }
        }

        // Decorations at their (dragged) positions; the selected slot is
        // ringed even while empty so it can always be found and moved.
        for (int i = 0; i < BoardTheme.PROP_SLOTS; i++) {
            int[] p = slotScreen(i);
            int size = 22;
            if (i == selectedSlot) {
                g.setColor(new Color(255, 220, 110));
                g.drawOval(p[0] - size / 2 - 3, p[1] - size / 2 - 3, size + 6, size + 6);
            }
            BufferedImage custom = theme.propImage(i).isEmpty() ? null
                    : AssetLoader.loadImageOrNull(theme.propImage(i));
            if (custom != null) {
                g.drawImage(custom, p[0] - size / 2, p[1] - size + size / 4,
                        size, size, null);
            } else if (theme.prop(i) != BoardTheme.Prop.NONE) {
                g.drawImage(AutoSprites.prop(theme.prop(i), size),
                        p[0] - size / 2, p[1] - size + size / 4, size, size, null);
            } else {
                // Empty slot: a faint marker so it stays clickable.
                g.setColor(new Color(200, 206, 226, i == selectedSlot ? 160 : 70));
                g.drawOval(p[0] - 4, p[1] - 4, 8, 8);
            }
        }
        g.setClip(oldClip);
        g.setColor(new Color(90, 100, 140));
        g.drawRect(px, py, pw, ph);
    }

    private static Polygon miniTile(double ox, double oy, double tw, double th,
                                    int c, int r) {
        Polygon p = new Polygon();
        double[][] corners = {{c, r}, {c + 1, r}, {c + 1, r + 1}, {c, r + 1}};
        for (double[] corner : corners) {
            p.addPoint((int) (ox + (corner[0] - corner[1]) * tw / 2),
                    (int) (oy + (corner[0] + corner[1]) * th / 2));
        }
        return p;
    }
}
