package com.larsons.engine.demo;

import com.larsons.engine.audio.AudioManager;
import com.larsons.engine.autobattler.AnimState;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoSprites;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.ui.UiText;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The skin customization menu, reached from the auto-battler lobby: assign a
 * sprite sheet to any game texture — every unit (per animation state), every
 * item gem, the projectiles, and the board tiles. A sheet import is defined by
 * its frame pixel width/height and frame count, and plays at 0-120 sprite
 * frames per second (0 = static). Assignments apply live and persist to
 * {@code skins.json} in the player's game files ({@link SkinStore}), so they
 * survive relaunches; drop your PNGs in {@code resources/skins/}.
 *
 * <p>The form shows one texture target at a time (category → target → state
 * cycles rebuild it); a live preview under the form plays the sheet with the
 * current settings next to the procedural default it would replace.
 */
public class SkinEditorScene extends AbstractScene {

    private enum Category { UNIT, ITEM, PROJECTILE, BOARD }

    private static final String[] PROJECTILE_KINDS = {"arrow", "orb", "bolt"};
    private static final String[] BOARD_PARTS = {"tile_a", "tile_b"};

    private final GameContext ctx;
    private final SkinStore store;

    private ConfigForm form;
    private Category category = Category.UNIT;
    private int targetIndex;
    private int stateIndex;

    // The editable fields backing the form. Numbers are text-typed so exact
    // pixel sizes can be entered directly; they parse (and clamp) on apply.
    private String sheetPath = "";
    private String frameW = "32";
    private String frameH = "32";
    private String frameCount = "1";
    private String fps = "0";

    private String status = "";
    private Color statusColor = new Color(140, 200, 150);

    private double animClock;
    private String previewSig = "";
    private List<BufferedImage> previewFrames = List.of();

    public SkinEditorScene(GameContext ctx) {
        this(ctx, new SkinStore());
    }

    public SkinEditorScene(GameContext ctx, SkinStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public void onEnter() {
        status = "";
        loadFieldsFor(currentKey());
        buildForm();
    }

    // --- the selectable texture targets -------------------------------------------

    private List<String> targetKeys() {
        List<String> keys = new ArrayList<>();
        switch (category) {
            case UNIT -> {
                for (UnitDef d : AutoUnits.all()) keys.add(d.key);
            }
            case ITEM -> {
                for (AutoItem i : AutoItems.all()) keys.add(i.key);
            }
            case PROJECTILE -> keys.addAll(List.of(PROJECTILE_KINDS));
            case BOARD -> keys.addAll(List.of(BOARD_PARTS));
        }
        return keys;
    }

    private String targetKey() {
        List<String> keys = targetKeys();
        targetIndex = Math.floorMod(targetIndex, keys.size());
        return keys.get(targetIndex);
    }

    private String targetLabel() {
        String key = targetKey();
        return switch (category) {
            case UNIT -> {
                UnitDef d = AutoUnits.get(key);
                yield d != null ? d.name : key;
            }
            case ITEM -> {
                AutoItem i = AutoItems.get(key);
                yield i != null ? i.name : key;
            }
            default -> key;
        };
    }

    private AnimState state() {
        AnimState[] states = AnimState.values();
        stateIndex = Math.floorMod(stateIndex, states.length);
        return states[stateIndex];
    }

    /** The full skin key the current selection edits. */
    private String currentKey() {
        return switch (category) {
            case UNIT -> "unit/" + targetKey() + "/" + state().key();
            case ITEM -> "item/" + targetKey();
            case PROJECTILE -> "projectile/" + targetKey();
            case BOARD -> "board/" + targetKey();
        };
    }

    // --- form ----------------------------------------------------------------------

    /** Rebuilt on every selection change so the cycler labels stay current. */
    private void buildForm() {
        form = new ConfigForm("Skin Customization").theme(MenuTheme.dark());
        form.addAction("Category:  " + pretty(category.name()), () -> {
            category = Category.values()[(category.ordinal() + 1) % Category.values().length];
            targetIndex = 0;
            reload();
        });
        form.addAction("Target:  " + targetLabel()
                + "  (" + (targetIndex + 1) + "/" + targetKeys().size() + ")", () -> {
            targetIndex++;
            reload();
        });
        if (category == Category.UNIT) {
            form.addAction("Animation state:  " + state().key(), () -> {
                stateIndex++;
                reload();
            });
        }
        form.addAction("Import sheet image…  (opens a file browser)", this::importSheet);
        form.addText("Sheet image path", () -> sheetPath, v -> sheetPath = v, 96);
        form.addText("Frame width (px)", () -> frameW, v -> frameW = digits(v, 4), 4);
        form.addText("Frame height (px)", () -> frameH, v -> frameH = digits(v, 4), 4);
        form.addText("Frame count", () -> frameCount, v -> frameCount = digits(v, 3), 3);
        form.addText("Framerate (0-120 fps)", () -> fps, v -> fps = digits(v, 3), 3);
        form.addAction("Apply + Save", this::apply);
        form.addAction("Remove This Skin", this::removeCurrent);
        form.addAction("Back", () -> scenes.transitionTo("autolobby"));
    }

    private void reload() {
        loadFieldsFor(currentKey());
        buildForm();
        status = "";
        ctx.sfx(AudioManager.Sfx.CLICK);
    }

    /** Pull the saved skin for a key into the editable fields (or defaults). */
    private void loadFieldsFor(String key) {
        SkinDef def = Skins.get(key);
        if (def != null) {
            sheetPath = def.sheet;
            frameW = Integer.toString(def.frameWidth);
            frameH = Integer.toString(def.frameHeight);
            frameCount = Integer.toString(def.frameCount);
            fps = Integer.toString((int) Math.round(def.fps));
        } else {
            sheetPath = "";
            frameW = "32";
            frameH = "32";
            frameCount = "1";
            fps = "0";
        }
    }

    /**
     * The automatic import flow: browse for a PNG, and it's copied into the
     * matching {@code resources/skins/} subfolder with the path (and, for a
     * plain image, the frame size) filled in — click Apply and it's live.
     */
    private void importSheet() {
        File picked = null;
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a sprite sheet image");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                picked = chooser.getSelectedFile();
            }
        } catch (RuntimeException e) {
            setStatus("File browser unavailable: " + e.getMessage(), true);
        }
        if (picked == null) return;
        String subfolder = switch (category) {
            case UNIT -> "units";
            case ITEM -> "items";
            case PROJECTILE -> "projectiles";
            case BOARD -> "boards";
        };
        try {
            Path dest = Path.of(store.directory().toString(), subfolder,
                    BoardCustomizeScene.sanitizeFileName(picked.getName()));
            Files.createDirectories(dest.getParent());
            Files.copy(picked.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            AssetLoader.clearCache(); // the path may have been cached as missing
            sheetPath = dest.toString().replace('\\', '/');
            BufferedImage img = AssetLoader.loadImageOrNull(sheetPath);
            if (img != null && "1".equals(frameCount)) {
                // A single-frame skin defaults to the whole image.
                frameW = Integer.toString(img.getWidth());
                frameH = Integer.toString(img.getHeight());
            }
            buildForm();
            setStatus("Imported " + picked.getName()
                    + " — set frames if it's a sheet, then Apply + Save", false);
            ctx.sfx(AudioManager.Sfx.PICKUP);
        } catch (IOException e) {
            setStatus("Could not import '" + picked.getName() + "': " + e.getMessage(), true);
        }
    }

    private void apply() {
        String key = currentKey();
        if (sheetPath.isBlank()) {
            setStatus("Enter a sheet image path (e.g. skins/units/my_unit.png)", true);
            return;
        }
        SkinDef def = new SkinDef(key, sheetPath.trim(),
                parseInt(frameW, 32), parseInt(frameH, 32),
                parseInt(frameCount, 1), SkinDef.clampFps(parseInt(fps, 0)));
        Skins.put(def);
        store.save(Skins.all());
        if (AssetLoader.loadImageOrNull(def.sheet) == null) {
            setStatus("Saved " + key + " — but no image at '" + def.sheet
                    + "' yet (procedural art stays until it exists)", true);
        } else {
            setStatus("Saved " + key + "  ✓", false);
        }
        ctx.sfx(AudioManager.Sfx.PICKUP);
    }

    private void removeCurrent() {
        String key = currentKey();
        if (Skins.get(key) == null) {
            setStatus("No skin assigned to " + key, true);
            return;
        }
        Skins.remove(key);
        store.save(Skins.all());
        loadFieldsFor(key);
        buildForm();
        setStatus("Removed " + key + " — back to procedural art", false);
        ctx.sfx(AudioManager.Sfx.CLICK);
    }

    private void setStatus(String text, boolean warn) {
        status = text;
        statusColor = warn ? new Color(235, 180, 110) : new Color(140, 200, 150);
    }

    // --- loop ------------------------------------------------------------------------

    @Override
    public void update(double dt, InputManager input) {
        animClock += dt;
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            scenes.transitionTo("autolobby");
            return;
        }
        form.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        // Not yet ported off Graphics2D; see Java2DTarget.graphicsOf.
        Graphics2D g = Java2DTarget.graphicsOf(target);
        form.render(g, viewportWidth, viewportHeight);
        drawPreview(g);
        if (!status.isEmpty()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.setColor(statusColor);
            g.drawString(trimTo(g, status, viewportWidth - 48), 24, viewportHeight - 46);
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(120, 125, 145));
        g.drawString("Sheets slice left-to-right, top-to-bottom. Saved to "
                + store.file() + " — see resources/skins/README.md", 24, viewportHeight - 22);
    }

    /** The live preview: the sheet at current settings vs the default art. */
    private void drawPreview(Graphics2D g) {
        int px = viewportWidth / 2 + 340;
        if (px + 150 > viewportWidth) px = viewportWidth - 160;
        int py = Math.max(viewportHeight / 8, 60);

        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.setColor(new Color(160, 168, 190));
        g.drawString("Preview", px, py);

        int cell = 96;
        g.setColor(new Color(30, 34, 52));
        g.fillRoundRect(px, py + 8, cell, cell, 10, 10);
        g.setColor(new Color(60, 66, 92));
        g.drawRoundRect(px, py + 8, cell, cell, 10, 10);

        List<BufferedImage> frames = previewFramesFor(sheetPath.trim(),
                parseInt(frameW, 32), parseInt(frameH, 32), parseInt(frameCount, 1));
        if (!frames.isEmpty()) {
            double rate = SkinDef.clampFps(parseInt(fps, 0));
            int idx = rate <= 0 || frames.size() <= 1 ? 0
                    : (int) Math.floor(animClock * rate) % frames.size();
            g.drawImage(frames.get(idx), px + 8, py + 16, cell - 16, cell - 16, null);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(new Color(150, 156, 178));
            g.drawString(frames.size() + " frame" + (frames.size() == 1 ? "" : "s")
                    + " · " + (int) rate + " fps", px, py + cell + 24);
        } else {
            drawDefaultArt(g, px + 8, py + 16, cell - 16);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.setColor(new Color(150, 156, 178));
            g.drawString(sheetPath.isBlank() ? "default (procedural)" : "image not found",
                    px, py + cell + 24);
        }
    }

    /** The built-in art the skin would replace, so users see what they override. */
    private void drawDefaultArt(Graphics2D g, int x, int y, int size) {
        switch (category) {
            case UNIT -> {
                UnitDef d = AutoUnits.get(targetKey());
                if (d != null) g.drawImage(AutoSprites.unit(d, size, true), x, y, null);
            }
            case ITEM -> {
                AutoItem i = AutoItems.get(targetKey());
                if (i != null) g.drawImage(AutoSprites.item(i, size), x, y, null);
            }
            case PROJECTILE -> {
                g.setColor(new Color(200, 210, 235));
                g.fillOval(x + size / 3, y + size / 3, size / 3, size / 3);
            }
            case BOARD -> {
                g.setColor("tile_a".equals(targetKey())
                        ? new Color(40, 46, 66) : new Color(46, 52, 74));
                g.fillRect(x, y, size, size);
            }
        }
    }

    private List<BufferedImage> previewFramesFor(String path, int w, int h, int count) {
        String sig = path + "|" + w + "|" + h + "|" + count;
        if (sig.equals(previewSig)) return previewFrames;
        previewSig = sig;
        BufferedImage sheet = AssetLoader.loadImageOrNull(path);
        if (sheet == null || w <= 0 || h <= 0
                || sheet.getWidth() < w || sheet.getHeight() < h) {
            previewFrames = List.of();
            return previewFrames;
        }
        List<BufferedImage> all = SpriteSheet.fromImage(sheet, w, h).frames();
        previewFrames = all.subList(0, Math.min(Math.max(1, count), all.size()));
        return previewFrames;
    }

    // --- small helpers ---------------------------------------------------------------

    private static String pretty(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }

    /** Keep a typed numeric field to digits only, capped in length. */
    private static String digits(String v, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            if (Character.isDigit(c) && sb.length() < maxLen) sb.append(c);
        }
        return sb.toString();
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trimTo(Graphics2D g, String s, int maxWidth) {
        return UiText.fit(g.getFontMetrics(), s, maxWidth);
    }
}
