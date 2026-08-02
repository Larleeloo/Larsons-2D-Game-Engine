package com.larsons.engine.ui;

import com.larsons.engine.graphics.SpriteCanvas;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Create texture" window: a small paint program — pencil, eraser, flood
 * fill, line, rectangle, eyedropper, a palette and an undo stack — laid over
 * whatever scene opened it, with a frame strip along the bottom for animating
 * the result and a live preview playing at the frame rate being set.
 *
 * <p>It is the popup half of the sprite-sheet editor; the pixels themselves
 * live in {@link SpriteCanvas}, and what happens to the finished sheet is the
 * caller's business ({@link #onSave}) — in the creative editor that means
 * writing it into the drop-in texture pack under the object's own file name,
 * so a texture drawn in game is indistinguishable from one dropped into the
 * folder by hand.
 *
 * <p>Frames work forward: <b>+ Frame</b> copies the frame you are on so the
 * next one starts as the last one and you draw only what moves, with the
 * previous frame showing through as an onion skin while you do. <b>+ Blank</b>
 * is there for a fresh start.
 *
 * <p>The window owns the mouse and keyboard while it is open — the scene that
 * opened it hands input straight here and does nothing else with it, so a
 * stroke that runs off the canvas never paints the level underneath.
 */
public final class SpriteEditorPanel {

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 19);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.PLAIN, 13);
    private static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private static final Color WINDOW_BG = new Color(24, 25, 34);
    private static final Color WINDOW_EDGE = new Color(90, 94, 120);
    private static final Color PANEL_BG = new Color(32, 34, 46);
    private static final Color TEXT = new Color(214, 218, 232);
    private static final Color TEXT_DIM = new Color(132, 138, 160);
    private static final Color ACCENT = new Color(255, 210, 90);

    // Hoisted out of the draw calls. The checkerboard alone is hundreds of
    // cells a frame and allocated a Color for every one of them; the palette
    // is 48 more.
    private static final int SCRIM = new Color(6, 6, 12).getRGB();
    private static final int CHECK_LIGHT = new Color(58, 60, 72).getRGB();
    private static final int CHECK_DARK = new Color(44, 46, 58).getRGB();
    private static final int GRID_FINE = new Color(255, 255, 255, 34).getRGB();
    private static final int GRID_COARSE = new Color(255, 255, 255, 20).getRGB();
    private static final int ERASE_PREVIEW = new Color(255, 255, 255, 120).getRGB();
    private static final int BAR_TRACK = new Color(58, 60, 76).getRGB();
    private static final int BUTTON_ACTIVE = new Color(70, 62, 30).getRGB();
    private static final int BUTTON_BG = new Color(44, 46, 60).getRGB();
    private static final int BUTTON_OFF_EDGE = new Color(70, 72, 86).getRGB();
    private static final int BUTTON_OFF_TEXT = new Color(96, 98, 112).getRGB();

    /** Column widths either side of the canvas. */
    private static final int TOOL_W = 104, PALETTE_W = 168;
    private static final int TITLE_H = 40, BOTTOM_H = 158, PAD = 10;
    private static final int ROW_H = 26;

    /** The starting palette: greys, then a ramp per hue family. */
    private static final int[] PALETTE = {
            0xff000000, 0xff1a1c2c, 0xff333c57, 0xff566c86, 0xff94b0c2, 0xffc2d3e0,
            0xffe8eef2, 0xffffffff,
            0xff4a1010, 0xff8f2727, 0xffb13e53, 0xffef7d57, 0xffff9a5c, 0xffffcd75,
            0xfff4d29c, 0xfffff6d3,
            0xff14281a, 0xff0f4c3a, 0xff255c3a, 0xff38b764, 0xff5ddb7f, 0xffa7f070,
            0xffd6ffa0, 0xff1c6b4a,
            0xff101c40, 0xff2b2f77, 0xff1a3a8f, 0xff3b5dc9, 0xff5b6ee1, 0xff41a6f6,
            0xff73eff7, 0xffa0e8ff,
            0xff2a1a3a, 0xff5d275d, 0xff8a4fa0, 0xffc96bd8, 0xff5a3921, 0xff8b5a2b,
            0xffc08552, 0xffe0b087,
    };
    private static final int PALETTE_COLS = 8;

    /** A clickable box: label, what it does, and whether it reads as "on". */
    private record Button(Rectangle box, String label, Runnable action,
                          boolean enabled, boolean active) {}

    private final String title;
    private final String targetFile;
    private final SpriteCanvas canvas;

    private Consumer<SpriteCanvas> onSave = c -> {};
    private Runnable onCancel = () -> {};

    private SpriteCanvas.Tool tool = SpriteCanvas.Tool.PENCIL;
    private int color = 0xff1a1c2c;
    private int brush = 1;
    private boolean onion = true;
    private boolean fillShapes;
    private boolean playing = true;
    private double previewClock;
    private String status = "";

    // Window geometry, recomputed every frame from the viewport (see layout).
    private final Rectangle window = new Rectangle();
    private final Rectangle canvasArea = new Rectangle();
    private final Rectangle canvasBox = new Rectangle();
    private final Rectangle previewBox = new Rectangle();
    private final Rectangle colorBox = new Rectangle();
    /** Where the brush-size and frame-rate readouts are written. */
    private final Rectangle brushLabel = new Rectangle();
    private final Rectangle fpsLabel = new Rectangle();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Rectangle> swatchBoxes = new ArrayList<>();
    private final List<Rectangle> frameBoxes = new ArrayList<>();
    private final Rectangle[] channelBars = {new Rectangle(), new Rectangle(), new Rectangle()};
    private int zoom = 0;               // pixels per canvas pixel; 0 = fit the area

    // Stroke state. A stroke that starts on the canvas keeps drawing while the
    // button is held, even when the pointer leaves the canvas, so a fast
    // scribble across the edge doesn't come back as a click on a tool button.
    private boolean drawing;
    private boolean erasingStroke;      // right-drag paints transparent
    private int lastX = -1, lastY = -1;
    private int anchorX = -1, anchorY = -1;   // line/rect origin
    private int draggingChannel = -1;

    public SpriteEditorPanel(String title, String targetFile, SpriteCanvas canvas) {
        this.title = title;
        this.targetFile = targetFile;
        this.canvas = canvas;
    }

    /**
     * Where the drawing area lands on screen at this viewport size — one
     * canvas pixel is {@code width / canvas.width()} screen pixels, which is
     * what turns a click into the pixel it painted.
     */
    public Rectangle canvasBounds(int viewportW, int viewportH) {
        layout(viewportW, viewportH);
        return new Rectangle(canvasBox);
    }

    /** What to do with the finished sheet; the handler closes the window. */
    public void onSave(Consumer<SpriteCanvas> handler) {
        this.onSave = handler == null ? c -> {} : handler;
    }

    /** What to do when the creator backs out (Esc / Cancel). */
    public void onCancel(Runnable handler) {
        this.onCancel = handler == null ? () -> {} : handler;
    }

    /** A line of feedback along the bottom of the window. */
    public void setStatus(String message) {
        this.status = message == null ? "" : message;
    }

    // --- input ----------------------------------------------------------------------

    public void update(double dt, InputManager input, int viewportW, int viewportH) {
        layout(viewportW, viewportH);
        previewClock += dt;

        // Saving or cancelling hands the window back to the scene, which drops
        // it — nothing after that keystroke belongs to this panel any more.
        if (handleKeys(input)) return;

        int mx = input.getMouseX(), my = input.getMouseY();
        int wheel = input.getWheelRotation();
        if (wheel != 0 && canvasArea.contains(mx, my)) {
            zoom = clampZoom(effectiveZoom() - wheel);
        }

        // A slider drag owns the pointer until the button comes back up.
        if (draggingChannel >= 0) {
            if (input.isMouseDown()) {
                setChannel(draggingChannel, valueFromBar(channelBars[draggingChannel], mx));
                return;
            }
            draggingChannel = -1;
        }

        boolean held = input.isMouseDown() || input.isRightMouseDown();
        boolean pressed = input.isMouseJustPressed();
        boolean rightPressed = input.isRightMouseJustPressed();

        if (drawing) {
            continueStroke(mx, my, held);
        } else if (pressed || rightPressed) {
            if (canvasBox.contains(mx, my)) {
                beginStroke(mx, my, rightPressed && !pressed);
            } else {
                handleClick(mx, my);
            }
        }
    }

    /** @return true when the key closed the window (save/cancel) */
    private boolean handleKeys(InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            onCancel.run();
            return true;
        }
        boolean ctrl = input.isKeyDown(KeyEvent.VK_CONTROL);
        if (ctrl && input.isKeyJustPressed(KeyEvent.VK_S)) {
            onSave.accept(canvas);
            return true;
        }
        if (ctrl && input.isKeyJustPressed(KeyEvent.VK_Z)) {
            setStatus(canvas.undo() ? "Undone" : "Nothing left to undo");
            return false;
        }
        if (ctrl && input.isKeyJustPressed(KeyEvent.VK_Y)) {
            setStatus(canvas.redo() ? "Redone" : "Nothing to redo");
            return false;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_B)) tool = SpriteCanvas.Tool.PENCIL;
        if (input.isKeyJustPressed(KeyEvent.VK_E)) tool = SpriteCanvas.Tool.ERASER;
        if (input.isKeyJustPressed(KeyEvent.VK_G)) tool = SpriteCanvas.Tool.FILL;
        if (input.isKeyJustPressed(KeyEvent.VK_L)) tool = SpriteCanvas.Tool.LINE;
        if (input.isKeyJustPressed(KeyEvent.VK_R)) tool = SpriteCanvas.Tool.RECT;
        if (input.isKeyJustPressed(KeyEvent.VK_I)) tool = SpriteCanvas.Tool.PICKER;
        if (input.isKeyJustPressed(KeyEvent.VK_O)) onion = !onion;
        if (input.isKeyJustPressed(KeyEvent.VK_SPACE)) playing = !playing;
        if (input.isKeyJustPressed(KeyEvent.VK_OPEN_BRACKET)) {
            brush = Math.max(1, brush - 1);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_CLOSE_BRACKET)) {
            brush = Math.min(SpriteCanvas.MAX_BRUSH, brush + 1);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_COMMA)) canvas.stepFrame(-1);
        if (input.isKeyJustPressed(KeyEvent.VK_PERIOD)) canvas.stepFrame(1);
        if (input.isKeyJustPressed(KeyEvent.VK_N)) addFrame(true);
        return false;
    }

    private void handleClick(int mx, int my) {
        for (Button b : buttons) {
            if (b.enabled() && b.box().contains(mx, my)) {
                b.action().run();
                return;
            }
        }
        for (int i = 0; i < swatchBoxes.size(); i++) {
            if (swatchBoxes.get(i).contains(mx, my)) {
                color = PALETTE[i];
                if (tool == SpriteCanvas.Tool.ERASER) tool = SpriteCanvas.Tool.PENCIL;
                return;
            }
        }
        for (int i = 0; i < frameBoxes.size(); i++) {
            if (frameBoxes.get(i).contains(mx, my)) {
                canvas.selectFrame(i);
                return;
            }
        }
        for (int c = 0; c < channelBars.length; c++) {
            if (channelBars[c].contains(mx, my)) {
                draggingChannel = c;
                setChannel(c, valueFromBar(channelBars[c], mx));
                return;
            }
        }
    }

    // --- drawing strokes ------------------------------------------------------------

    private void beginStroke(int mx, int my, boolean rightButton) {
        int px = pixelX(mx), py = pixelY(my);
        if (!canvas.inBounds(px, py)) return;
        if (tool == SpriteCanvas.Tool.PICKER && !rightButton) {
            int picked = canvas.pixel(px, py);
            if ((picked >>> 24) == 0) {
                setStatus("That pixel is empty — pick a colour with something in it");
            } else {
                color = picked;
                tool = SpriteCanvas.Tool.PENCIL;
            }
            return;
        }
        canvas.pushUndo();
        drawing = true;
        erasingStroke = rightButton || tool == SpriteCanvas.Tool.ERASER;
        lastX = anchorX = px;
        lastY = anchorY = py;
        int paint = erasingStroke ? SpriteCanvas.TRANSPARENT : color;
        switch (tool) {
            case FILL -> {
                canvas.fill(px, py, paint);
                endStroke();
            }
            case LINE, RECT -> { /* committed on release; previewed until then */ }
            default -> canvas.plot(px, py, paint, brush);
        }
    }

    private void continueStroke(int mx, int my, boolean stillDown) {
        int px = clamp(pixelX(mx), 0, canvas.width() - 1);
        int py = clamp(pixelY(my), 0, canvas.height() - 1);
        int paint = erasingStroke ? SpriteCanvas.TRANSPARENT : color;
        if (stillDown) {
            if (tool == SpriteCanvas.Tool.PENCIL || tool == SpriteCanvas.Tool.ERASER
                    || erasingStroke) {
                // Interpolate: a fast drag between two ticks would otherwise
                // leave a dotted line instead of a stroke.
                canvas.stroke(lastX, lastY, px, py, paint, brush);
            }
            lastX = px;
            lastY = py;
            return;
        }
        switch (tool) {
            case LINE -> canvas.line(anchorX, anchorY, px, py, paint, brush);
            case RECT -> canvas.rect(anchorX, anchorY, px, py, paint, brush, fillShapes);
            default -> { /* already painted as it was dragged */ }
        }
        endStroke();
    }

    private void endStroke() {
        drawing = false;
        erasingStroke = false;
        lastX = lastY = anchorX = anchorY = -1;
    }

    private void addFrame(boolean copyPrevious) {
        boolean added = copyPrevious ? canvas.addFrame() : canvas.addBlankFrame();
        setStatus(added
                ? (copyPrevious
                ? "Frame " + (canvas.frame() + 1) + " — a copy of the one before it, draw the change"
                : "Frame " + (canvas.frame() + 1) + " — blank")
                : "That's the most frames one animation can have ("
                + SpriteCanvas.MAX_FRAMES + ")");
    }

    // --- layout ---------------------------------------------------------------------

    /**
     * Recompute every hit box from the viewport. Called at the top of both
     * {@code update} and {@code render} so a click lands on the button that
     * was actually drawn under it, even on the frame the window is resized.
     */
    private void layout(int viewportW, int viewportH) {
        buttons.clear();
        swatchBoxes.clear();
        frameBoxes.clear();

        int w = Math.min(viewportW - 40, 1000);
        int h = Math.min(viewportH - 40, 680);
        window.setBounds((viewportW - w) / 2, (viewportH - h) / 2, w, h);

        int bodyTop = window.y + TITLE_H;
        int bodyBottom = window.y + h - BOTTOM_H;
        canvasArea.setBounds(window.x + TOOL_W + PAD, bodyTop + PAD,
                w - TOOL_W - PALETTE_W - 2 * PAD, bodyBottom - bodyTop - 2 * PAD);
        layoutCanvasBox();
        layoutTools(window.x + PAD, bodyTop + PAD, bodyBottom);
        layoutPalette(window.x + w - PALETTE_W, bodyTop + PAD);
        layoutBottom(bodyBottom);
    }

    private void layoutCanvasBox() {
        int z = effectiveZoom();
        int cw = canvas.width() * z, ch = canvas.height() * z;
        canvasBox.setBounds(canvasArea.x + (canvasArea.width - cw) / 2,
                canvasArea.y + (canvasArea.height - ch) / 2, cw, ch);
    }

    /**
     * The tool column. Its eleven rows are pitched to the height they have
     * rather than to a fixed row height, so a short window squeezes the
     * buttons instead of running them down into the frame strip.
     */
    private void layoutTools(int x, int y, int bottom) {
        int bw = TOOL_W - 2 * PAD;
        int rows = SpriteCanvas.Tool.values().length + 5;   // + brush, shape, undo, redo, clear
        int caption = 18;                                   // the "brush N px" readout
        int pitch = Math.max(18, Math.min(ROW_H + 4, (bottom - y - caption) / rows));
        int bh = Math.max(15, pitch - 4);

        for (SpriteCanvas.Tool t : SpriteCanvas.Tool.values()) {
            // The shortcut rides on the button, which keeps the footer hint
            // short enough to read in one line.
            addButton(new Rectangle(x, y, bw, bh), toolLabel(t) + "  " + toolKey(t),
                    () -> tool = t, true, tool == t);
            y += pitch;
        }
        y += caption;
        brushLabel.setBounds(x, y - caption + 2, bw, 12);
        int half = (bw - 4) / 2;
        addButton(new Rectangle(x, y, half, bh), "-", () -> brush = Math.max(1, brush - 1),
                brush > 1, false);
        addButton(new Rectangle(x + half + 4, y, half, bh), "+",
                () -> brush = Math.min(SpriteCanvas.MAX_BRUSH, brush + 1),
                brush < SpriteCanvas.MAX_BRUSH, false);
        y += pitch + 6;
        addButton(new Rectangle(x, y, bw, bh), fillShapes ? "Solid □" : "Outline □",
                () -> fillShapes = !fillShapes, true, fillShapes);
        y += pitch + 6;
        addButton(new Rectangle(x, y, bw, bh), "Undo",
                () -> setStatus(canvas.undo() ? "Undone" : "Nothing left to undo"),
                canvas.canUndo(), false);
        y += pitch;
        addButton(new Rectangle(x, y, bw, bh), "Redo",
                () -> setStatus(canvas.redo() ? "Redone" : "Nothing to redo"),
                canvas.canRedo(), false);
        y += pitch;
        addButton(new Rectangle(x, y, bw, bh), "Clear", () -> {
            canvas.clearFrame();
            setStatus("Frame " + (canvas.frame() + 1) + " wiped");
        }, true, false);
    }

    private void layoutPalette(int x, int y) {
        int inner = PALETTE_W - 2 * PAD;
        int cell = inner / PALETTE_COLS;
        x += PAD;
        colorBox.setBounds(x, y, inner, 30);
        y += 38;
        for (int i = 0; i < PALETTE.length; i++) {
            swatchBoxes.add(new Rectangle(x + (i % PALETTE_COLS) * cell,
                    y + (i / PALETTE_COLS) * cell, cell - 2, cell - 2));
        }
        y += (PALETTE.length / PALETTE_COLS + 1) * cell + 6;
        for (int c = 0; c < channelBars.length; c++) {
            channelBars[c].setBounds(x + 18, y + 6, inner - 18, 10);
            y += 22;
        }
        y += 6;
        // The canvas size lives here rather than in a settings form: it is the
        // one number that changes what the sheet *is*, and a creator who
        // realises 32 is too small wants it a click away, not a dialog away.
        int half = (inner - 6) / 2;
        addButton(new Rectangle(x, y, half, ROW_H), "− size", () -> resizeBy(-8),
                Math.min(canvas.width(), canvas.height()) > 8, false);
        addButton(new Rectangle(x + half + 6, y, half, ROW_H), "+ size", () -> resizeBy(8),
                Math.max(canvas.width(), canvas.height()) < 128, false);
    }

    private void layoutBottom(int top) {
        int x = window.x + PAD;
        int right = window.x + window.width - PAD;
        int y = top + 6;

        // Frame thumbnails, sized so the whole animation fits the strip.
        int strip = right - x - 74;
        int count = canvas.frameCount();
        int size = Math.max(18, Math.min(52, strip / Math.max(1, count) - 4));
        for (int i = 0; i < count; i++) {
            frameBoxes.add(new Rectangle(x + i * (size + 4), y, size, size));
        }
        previewBox.setBounds(right - 62, y, 62, 62);

        // Save and Cancel are anchored to the right edge; the frame controls
        // get the room that is left, squeezed to fit it rather than allowed to
        // run on underneath them.
        y = top + 76;
        int cancelW = 90, saveW = 156;
        addButton(new Rectangle(right - cancelW, y, cancelW, ROW_H), "Cancel",
                onCancel, true, false);
        addButton(new Rectangle(right - cancelW - saveW - 8, y, saveW, ROW_H),
                "Save to texture pack", () -> onSave.accept(canvas), true, true);

        // A readout (null action) is spacing with a number written in it.
        record Control(int width, String label, Runnable action, boolean enabled,
                       boolean active) {}
        List<Control> row = List.of(
                new Control(28, "◀", () -> canvas.stepFrame(-1), count > 1, false),
                new Control(28, "▶", () -> canvas.stepFrame(1), count > 1, false),
                new Control(86, "+ Frame", () -> addFrame(true), true, false),
                new Control(76, "+ Blank", () -> addFrame(false), true, false),
                new Control(68, "Delete", () -> {
                    canvas.deleteFrame();
                    setStatus("Frame deleted — " + canvas.frameCount() + " left");
                }, true, false),
                new Control(88, onion ? "Onion ON" : "Onion OFF",
                        () -> onion = !onion, true, onion),
                new Control(26, "-", () -> canvas.setFps(canvas.fps() - 1),
                        canvas.fps() > 0, false),
                new Control(56, "", null, true, false),          // the fps readout
                new Control(26, "+", () -> canvas.setFps(canvas.fps() + 1), true, false),
                new Control(86, playing ? "❚❚ Pause" : "▶ Play",
                        () -> playing = !playing, true, playing));

        int gap = 6;
        int wanted = gap * (row.size() - 1);
        for (Control c : row) wanted += c.width();
        int room = right - cancelW - saveW - 24 - x;
        double squeeze = Math.min(1.0, room / (double) wanted);
        int cx = x;
        for (Control c : row) {
            int cw = Math.max(16, (int) Math.round(c.width() * squeeze));
            if (c.action() == null) {
                fpsLabel.setBounds(cx, y, cw, ROW_H);
            } else {
                addButton(new Rectangle(cx, y, cw, ROW_H), c.label(), c.action(),
                        c.enabled(), c.active());
            }
            cx += cw + Math.max(2, (int) Math.round(gap * squeeze));
        }
    }

    private void addButton(Rectangle box, String label, Runnable action,
                           boolean enabled, boolean active) {
        buttons.add(new Button(box, label, action, enabled, active));
    }

    /** Grow or shrink the frame, keeping a non-square sheet's proportions. */
    private void resizeBy(int delta) {
        canvas.resize(clamp(canvas.width() + delta, 8, 128),
                clamp(canvas.height() + delta, 8, 128));
        zoom = 0; // refit: the frame just changed size under the view
        setStatus("Frame size " + canvas.width() + "×" + canvas.height() + " px");
    }

    // --- rendering ------------------------------------------------------------------

    public void render(DrawTarget target, int viewportW, int viewportH) {
        layout(viewportW, viewportH);

        target.pushAlpha(0.72f);
        target.fillRect(0, 0, viewportW, viewportH, SCRIM);
        target.popAlpha();

        target.fillRoundRect(window.x, window.y, window.width, window.height,
                14, 14, WINDOW_BG);
        target.drawRoundRect(window.x, window.y, window.width, window.height,
                14, 14, WINDOW_EDGE);

        drawTitleBar(target);
        drawCanvas(target);
        drawToolColumn(target);
        drawPalette(target);
        drawFrameStrip(target);
        drawButtons(target);
        drawFooter(target);
    }

    private void drawTitleBar(DrawTarget target) {
        int titleRight = window.x + window.width - 16;
        String head = title + (canvas.modified() ? " *" : "");
        target.drawText(UiText.fit(target, TITLE_FONT, head, window.width - 340),
                window.x + 16, window.y + 27, TITLE_FONT, TEXT);
        // The file this becomes is the whole point of the window, so it is on
        // screen the entire time rather than only in the save message.
        String file = "saves as  " + targetFile;
        String shown = UiText.fitTail(target, SMALL_FONT, file, 320);
        target.drawText(shown, titleRight - target.textWidth(shown, SMALL_FONT),
                window.y + 26, SMALL_FONT, TEXT_DIM);
        target.drawLine(window.x + 8, window.y + TITLE_H - 4,
                window.x + window.width - 8, window.y + TITLE_H - 4, WINDOW_EDGE);
    }

    private void drawCanvas(DrawTarget target) {
        target.fillRect(canvasArea.x, canvasArea.y, canvasArea.width, canvasArea.height,
                PANEL_BG);

        target.pushClip(canvasArea.x, canvasArea.y, canvasArea.width, canvasArea.height);
        drawTransparencyChecks(target, canvasBox, 8);

        // The previous frame shows through underneath, which is what makes
        // frame-by-frame animation possible: you draw the next pose over the
        // last one instead of from memory.
        if (onion && canvas.frame() > 0) {
            target.pushAlpha(0.3f);
            target.drawImage(canvas.frameImage(canvas.frame() - 1), canvasBox.x, canvasBox.y,
                    canvasBox.width, canvasBox.height);
            target.popAlpha();
        }
        target.drawImage(canvas.frameImage(canvas.frame()), canvasBox.x, canvasBox.y,
                canvasBox.width, canvasBox.height);

        drawShapePreview(target);
        drawPixelGrid(target);

        target.drawRect(canvasBox.x, canvasBox.y, canvasBox.width, canvasBox.height,
                WINDOW_EDGE);
        target.popClip();
    }

    /** The line/rectangle being dragged, before the button comes up. */
    private void drawShapePreview(DrawTarget target) {
        if (!drawing || anchorX < 0 || lastX < 0) return;
        if (tool != SpriteCanvas.Tool.LINE && tool != SpriteCanvas.Tool.RECT) return;
        int z = effectiveZoom();
        // An erase preview has no colour of its own, so it is shown as the
        // hole it is about to make.
        int argb = erasingStroke ? ERASE_PREVIEW : color;
        SpriteCanvas.PixelSink sink = (x, y) -> {
            if (canvas.inBounds(x, y)) {
                target.fillRect(canvasBox.x + x * z, canvasBox.y + y * z, z, z, argb);
            }
        };
        if (tool == SpriteCanvas.Tool.LINE) {
            SpriteCanvas.forEachLine(anchorX, anchorY, lastX, lastY, sink);
        } else {
            SpriteCanvas.forEachRect(anchorX, anchorY, lastX, lastY, fillShapes, sink);
        }
    }

    private void drawPixelGrid(DrawTarget target) {
        int z = effectiveZoom();
        if (z < 6) return;
        int line = z >= 12 ? GRID_FINE : GRID_COARSE;
        for (int x = 0; x <= canvas.width(); x++) {
            target.drawLine(canvasBox.x + x * z, canvasBox.y,
                    canvasBox.x + x * z, canvasBox.y + canvasBox.height, line, 1f);
        }
        for (int y = 0; y <= canvas.height(); y++) {
            target.drawLine(canvasBox.x, canvasBox.y + y * z,
                    canvasBox.x + canvasBox.width, canvasBox.y + y * z, line, 1f);
        }
    }

    private void drawToolColumn(DrawTarget target) {
        target.drawText("brush " + brush + " px", brushLabel.x + 2,
                brushLabel.y + brushLabel.height, SMALL_FONT, TEXT_DIM);
    }

    private void drawPalette(DrawTarget target) {
        drawTransparencyChecks(target, colorBox, 6);
        target.fillRect(colorBox.x, colorBox.y, colorBox.width, colorBox.height, color);
        target.drawRect(colorBox.x, colorBox.y, colorBox.width, colorBox.height, WINDOW_EDGE);

        for (int i = 0; i < swatchBoxes.size(); i++) {
            Rectangle box = swatchBoxes.get(i);
            target.fillRect(box.x, box.y, box.width, box.height, PALETTE[i]);
            if (PALETTE[i] == color) {
                target.drawRect(box.x - 1, box.y - 1, box.width + 1, box.height + 1, ACCENT);
            }
        }

        String[] names = {"R", "G", "B"};
        for (int c = 0; c < channelBars.length; c++) {
            Rectangle bar = channelBars[c];
            int value = channel(c);
            target.drawText(names[c], bar.x - 14, bar.y + 10, SMALL_FONT, TEXT_DIM);
            target.fillRoundRect(bar.x, bar.y, bar.width, bar.height, 6, 6, BAR_TRACK);
            target.fillRoundRect(bar.x, bar.y, bar.width * value / 255, bar.height, 6, 6,
                    new Color(c == 0 ? 220 : 90, c == 1 ? 220 : 90, c == 2 ? 220 : 90));
            target.fillOval(bar.x + bar.width * value / 255 - 4, bar.y - 2, 9, 14, TEXT);
        }
    }

    private void drawFrameStrip(DrawTarget target) {
        for (int i = 0; i < frameBoxes.size(); i++) {
            Rectangle box = frameBoxes.get(i);
            drawTransparencyChecks(target, box, 6);
            target.drawImage(canvas.frameImage(i), box.x, box.y, box.width, box.height);
            boolean here = i == canvas.frame();
            target.drawRect(box.x, box.y, box.width, box.height,
                    (here ? ACCENT : WINDOW_EDGE).getRGB(), here ? 2f : 1f);
            target.drawText(String.valueOf(i + 1), box.x + 2, box.y + box.height - 2,
                    SMALL_FONT, here ? ACCENT : TEXT_DIM);
        }

        // The animation as it will actually play, at the rate being set.
        drawTransparencyChecks(target, previewBox, 6);
        int frame = canvas.fps() > 0 && playing
                ? (int) Math.floor(previewClock * canvas.fps()) % canvas.frameCount()
                : canvas.frame();
        target.drawImage(canvas.frameImage(frame), previewBox.x, previewBox.y,
                previewBox.width, previewBox.height);
        // 1px: the loop above restored the previous stroke after each box, so
        // this border was always drawn at the ambient width, which for this
        // panel is the default. Stating it is the point of the port — there is
        // no ambient stroke to inherit any more.
        target.drawRect(previewBox.x, previewBox.y, previewBox.width, previewBox.height,
                WINDOW_EDGE);
    }

    private void drawButtons(DrawTarget target) {
        for (Button b : buttons) {
            Rectangle box = b.box();
            target.fillRoundRect(box.x, box.y, box.width, box.height, 8, 8,
                    b.active() ? BUTTON_ACTIVE : BUTTON_BG);
            target.drawRoundRect(box.x, box.y, box.width, box.height, 8, 8,
                    !b.enabled() ? BUTTON_OFF_EDGE
                            : (b.active() ? ACCENT : WINDOW_EDGE).getRGB(), 1f);
            String label = UiText.fit(target, LABEL_FONT, b.label(), box.width - 10);
            target.drawText(label,
                    box.x + (box.width - target.textWidth(label, LABEL_FONT)) / 2,
                    box.y + (box.height + target.textAscent(LABEL_FONT)) / 2 - 2,
                    LABEL_FONT, !b.enabled() ? BUTTON_OFF_TEXT
                            : (b.active() ? ACCENT : TEXT).getRGB());
        }
        // The frame rate reads between its steppers, where the number belongs.
        String fps = trim(canvas.fps()) + " fps";
        target.drawText(fps,
                fpsLabel.x + (fpsLabel.width - target.textWidth(fps, LABEL_FONT)) / 2,
                fpsLabel.y + (fpsLabel.height + target.textAscent(LABEL_FONT)) / 2 - 2,
                LABEL_FONT, canvas.fps() > 0 ? TEXT : TEXT_DIM);
    }

    private void drawFooter(DrawTarget target) {
        int y = window.y + window.height - 12;
        String hint = "left-click paints · right-click erases · wheel zooms · "
                + "[ ] brush size · , . step frame · N new frame · O onion skin · "
                + "Ctrl+Z undo · Ctrl+S save · Esc close";
        target.drawText(UiText.fit(target, SMALL_FONT, hint, window.width - 32),
                window.x + 16, y, SMALL_FONT, TEXT_DIM);
        if (!status.isEmpty()) {
            target.drawText(UiText.fit(target, SMALL_FONT, status, window.width - 32),
                    window.x + 16, y - 16, SMALL_FONT, ACCENT);
        }
        String spec = canvas.width() + "×" + canvas.height() + " px · "
                + canvas.frameCount() + " frame" + (canvas.frameCount() == 1 ? "" : "s")
                + " · frame " + (canvas.frame() + 1) + " · zoom " + effectiveZoom() + "×";
        target.drawText(spec,
                window.x + window.width - 16 - target.textWidth(spec, SMALL_FONT),
                y - 16, SMALL_FONT, TEXT_DIM);
    }

    /** A checkerboard under a box, so transparent pixels read as empty. */
    private static void drawTransparencyChecks(DrawTarget target, Rectangle box, int cell) {
        target.pushClip(box.x, box.y, box.width, box.height);
        for (int y = 0; y < box.height; y += cell) {
            for (int x = 0; x < box.width; x += cell) {
                boolean even = ((x / cell) + (y / cell)) % 2 == 0;
                target.fillRect(box.x + x, box.y + y, cell, cell,
                        even ? CHECK_LIGHT : CHECK_DARK);
            }
        }
        target.popClip();
    }

    // --- helpers --------------------------------------------------------------------

    private static String toolLabel(SpriteCanvas.Tool t) {
        return switch (t) {
            case PENCIL -> "Pencil";
            case ERASER -> "Eraser";
            case FILL -> "Fill";
            case LINE -> "Line";
            case RECT -> "Rect";
            case PICKER -> "Pick";
        };
    }

    /** The key that selects a tool, shown on its button (see {@code handleKeys}). */
    private static String toolKey(SpriteCanvas.Tool t) {
        return switch (t) {
            case PENCIL -> "B";
            case ERASER -> "E";
            case FILL -> "G";
            case LINE -> "L";
            case RECT -> "R";
            case PICKER -> "I";
        };
    }

    private int effectiveZoom() {
        if (zoom > 0) return zoom;
        int fit = Math.min(canvasArea.width / Math.max(1, canvas.width()),
                canvasArea.height / Math.max(1, canvas.height()));
        return clampZoom(fit);
    }

    private static int clampZoom(int z) {
        return Math.max(1, Math.min(40, z));
    }

    private int pixelX(int mx) {
        return Math.floorDiv(mx - canvasBox.x, effectiveZoom());
    }

    private int pixelY(int my) {
        return Math.floorDiv(my - canvasBox.y, effectiveZoom());
    }

    private int channel(int index) {
        return switch (index) {
            case 0 -> (color >> 16) & 0xff;
            case 1 -> (color >> 8) & 0xff;
            default -> color & 0xff;
        };
    }

    private void setChannel(int index, int value) {
        int v = clamp(value, 0, 255);
        int shift = switch (index) {
            case 0 -> 16;
            case 1 -> 8;
            default -> 0;
        };
        color = 0xff000000 | (color & ~(0xff << shift) & 0x00ffffff) | (v << shift);
        if (tool == SpriteCanvas.Tool.ERASER) tool = SpriteCanvas.Tool.PENCIL;
    }

    private static int valueFromBar(Rectangle bar, int mx) {
        if (bar.width <= 0) return 0;
        return (int) Math.round((mx - bar.x) / (double) bar.width * 255);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
