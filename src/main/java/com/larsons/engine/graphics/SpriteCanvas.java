package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The pixels behind the in-game sprite-sheet editor: a stack of equally sized
 * animation frames with the paint operations a basic pixel editor needs —
 * pencil, eraser, flood fill, line, rectangle and colour picking — plus undo.
 *
 * <p>This is the model half of "Create texture" (the editor window itself is
 * {@link com.larsons.engine.ui.SpriteEditorPanel}), and it is deliberately
 * free of AWT input and rendering so the drawing behaviour can be exercised
 * headlessly. Pixels are plain ARGB ints and {@value #TRANSPARENT} is empty
 * space, which is what makes an exported sheet drop straight into the
 * {@link TexturePack} beside hand-drawn art.
 *
 * <p>Frames work the way an animator expects: {@link #addFrame()} copies the
 * frame you are on, so each new frame starts as the previous one and you draw
 * the difference — a walk cycle is built by nudging what is already there
 * rather than by redrawing the character six times. {@link #addBlankFrame()}
 * is there for when you do want to start over.
 *
 * <p>{@link #toSheet()} lays the frames out left-to-right in one row, which is
 * exactly how {@link SpriteSheet} slices them back apart, so a canvas exported
 * at {@code w}×{@code h} with {@code n} frames is read back as the same
 * animation at the same {@link #fps()}.
 */
public final class SpriteCanvas {

    /** Empty space: fully transparent, what an exported sheet leaves unpainted. */
    public static final int TRANSPARENT = 0;

    /** Frame size limits — a texture pack sheet, not a painting. */
    public static final int MIN_SIZE = 1, MAX_SIZE = 256;
    /** Most frames one animation may hold. */
    public static final int MAX_FRAMES = 32;
    /** Widest square brush the pencil and eraser paint with. */
    public static final int MAX_BRUSH = 8;
    /** How many operations can be taken back. */
    private static final int MAX_UNDO = 64;

    /** What a click paints with. */
    public enum Tool { PENCIL, ERASER, FILL, LINE, RECT, PICKER }

    /** Receives the pixels a shape covers (used for both drawing and preview). */
    @FunctionalInterface
    public interface PixelSink {
        void at(int x, int y);
    }

    /**
     * One point in the undo history: every frame at the size it was then, and
     * which one was being drawn on. The size travels with it so undoing a
     * resize restores the frames <em>and</em> the canvas they fitted.
     */
    private record Snapshot(List<int[]> frames, int width, int height, int current) {}

    private int width;
    private int height;
    private final List<int[]> frames = new ArrayList<>();
    private int current;
    private double fps;
    private boolean modified;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();

    /** A blank one-frame canvas at the given frame size. */
    public SpriteCanvas(int width, int height) {
        this(width, height, TexturePack.DEFAULT_FPS);
    }

    public SpriteCanvas(int width, int height, double fps) {
        this.width = clampSize(width);
        this.height = clampSize(height);
        this.fps = SkinDef.clampFps(fps);
        frames.add(blank());
    }

    /**
     * Read an existing sheet back into an editable canvas, so "Create texture"
     * on an object that already has art opens that art rather than a blank
     * page. Frames past what the image actually holds are dropped; a
     * {@code null} image yields a blank canvas of the requested size.
     */
    public static SpriteCanvas fromSheet(BufferedImage sheet, int frameWidth,
                                         int frameHeight, int frameCount, double fps) {
        SpriteCanvas canvas = new SpriteCanvas(frameWidth, frameHeight, fps);
        if (sheet == null) return canvas;
        List<BufferedImage> sliced = SpriteSheet
                .fromImage(sheet, canvas.width, canvas.height).frames();
        if (sliced.isEmpty()) return canvas;
        int count = Math.max(1, Math.min(Math.min(frameCount, MAX_FRAMES), sliced.size()));
        canvas.frames.clear();
        for (int i = 0; i < count; i++) {
            int[] px = canvas.blank();
            BufferedImage frame = sliced.get(i);
            for (int y = 0; y < canvas.height; y++) {
                for (int x = 0; x < canvas.width; x++) {
                    if (x < frame.getWidth() && y < frame.getHeight()) {
                        px[y * canvas.width + x] = frame.getRGB(x, y);
                    }
                }
            }
            canvas.frames.add(px);
        }
        return canvas;
    }

    /**
     * The canvas for a sheet on disk (or bundled), or {@code null} when there
     * is no readable image there — the caller's cue to start from blank.
     */
    public static SpriteCanvas load(String path, int frameWidth, int frameHeight,
                                    int frameCount, double fps) {
        BufferedImage img = AssetLoader.loadImageOrNull(path);
        return img == null ? null
                : fromSheet(img, frameWidth, frameHeight, frameCount, fps);
    }

    // --- geometry & frames ----------------------------------------------------------

    public int width() { return width; }

    public int height() { return height; }

    public int frameCount() { return frames.size(); }

    /** Index of the frame being drawn on. */
    public int frame() { return current; }

    public double fps() { return fps; }

    public void setFps(double value) {
        double next = SkinDef.clampFps(value);
        if (next != fps) {
            fps = next;
            modified = true;
        }
    }

    /** Whether anything has been drawn or changed since the canvas was made. */
    public boolean modified() { return modified; }

    /** Forget the modified flag — what saving the sheet does. */
    public void markSaved() { modified = false; }

    public void selectFrame(int index) {
        current = Math.max(0, Math.min(frames.size() - 1, index));
    }

    /** Step one frame on, wrapping — how the frame strip's arrows move. */
    public void stepFrame(int dir) {
        if (frames.isEmpty()) return;
        current = Math.floorMod(current + dir, frames.size());
    }

    /**
     * Add a frame after the current one, starting as a copy of it, and move
     * onto it — the frame-by-frame flow, where each frame carries the previous
     * drawing forward and you change only what moves.
     *
     * @return false when the animation is already {@value #MAX_FRAMES} long
     */
    public boolean addFrame() {
        return insert(frames.get(current).clone());
    }

    /** Add an empty frame after the current one and move onto it. */
    public boolean addBlankFrame() {
        return insert(blank());
    }

    private boolean insert(int[] pixels) {
        if (frames.size() >= MAX_FRAMES) return false;
        pushUndo();
        frames.add(current + 1, pixels);
        current++;
        modified = true;
        return true;
    }

    /**
     * Delete the current frame, landing on the one before it. The last frame
     * is never deleted — an animation with no frames has nothing to draw on —
     * so that case clears it instead.
     */
    public void deleteFrame() {
        pushUndo();
        if (frames.size() == 1) {
            frames.set(0, blank());
        } else {
            frames.remove(current);
            current = Math.max(0, Math.min(frames.size() - 1, current));
        }
        modified = true;
    }

    /** Wipe the current frame back to transparent. */
    public void clearFrame() {
        pushUndo();
        frames.set(current, blank());
        modified = true;
    }

    /**
     * Change the frame size, keeping what is already drawn anchored at the
     * top-left (cropped when the canvas shrinks, transparent where it grows).
     */
    public void resize(int newWidth, int newHeight) {
        int w = clampSize(newWidth), h = clampSize(newHeight);
        if (w == width && h == height) return;
        pushUndo();
        List<int[]> resized = new ArrayList<>(frames.size());
        for (int[] src : frames) {
            int[] dst = new int[w * h];
            for (int y = 0; y < Math.min(h, height); y++) {
                for (int x = 0; x < Math.min(w, width); x++) {
                    dst[y * w + x] = src[y * width + x];
                }
            }
            resized.add(dst);
        }
        frames.clear();
        frames.addAll(resized);
        width = w;
        height = h;
        modified = true;
    }

    // --- pixels ---------------------------------------------------------------------

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** The ARGB pixel of a given frame, or {@value #TRANSPARENT} off-canvas. */
    public int pixel(int frameIndex, int x, int y) {
        if (!inBounds(x, y) || frameIndex < 0 || frameIndex >= frames.size()) return TRANSPARENT;
        return frames.get(frameIndex)[y * width + x];
    }

    /** The ARGB pixel of the current frame. */
    public int pixel(int x, int y) {
        return pixel(current, x, y);
    }

    /** Paint one pixel of the current frame (no undo point of its own). */
    public void set(int x, int y, int argb) {
        if (!inBounds(x, y)) return;
        int[] px = frames.get(current);
        if (px[y * width + x] == argb) return;
        px[y * width + x] = argb;
        modified = true;
    }

    /** Paint a square brush centred on a pixel. */
    public void plot(int x, int y, int argb, int brush) {
        int size = Math.max(1, Math.min(MAX_BRUSH, brush));
        int from = -(size - 1) / 2;
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                set(x + from + dx, y + from + dy, argb);
            }
        }
    }

    /** Drag support: paint the run of pixels between two brush positions. */
    public void stroke(int x0, int y0, int x1, int y1, int argb, int brush) {
        forEachLine(x0, y0, x1, y1, (x, y) -> plot(x, y, argb, brush));
    }

    /** Draw a one-pixel-wide line (thicker with a bigger brush). */
    public void line(int x0, int y0, int x1, int y1, int argb, int brush) {
        stroke(x0, y0, x1, y1, argb, brush);
    }

    /** Draw a rectangle between two corners, outlined or filled. */
    public void rect(int x0, int y0, int x1, int y1, int argb, int brush, boolean filled) {
        forEachRect(x0, y0, x1, y1, filled, (x, y) ->
                plot(x, y, argb, filled ? 1 : brush));
    }

    /**
     * Flood fill from a pixel: every pixel of the same colour reachable
     * through its four neighbours becomes {@code argb}.
     */
    public void fill(int x, int y, int argb) {
        if (!inBounds(x, y)) return;
        int[] px = frames.get(current);
        int target = px[y * width + x];
        if (target == argb) return;
        Deque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{x, y});
        while (!pending.isEmpty()) {
            int[] p = pending.pop();
            int idx = p[1] * width + p[0];
            if (px[idx] != target) continue;
            px[idx] = argb;
            if (p[0] > 0) pending.push(new int[]{p[0] - 1, p[1]});
            if (p[0] < width - 1) pending.push(new int[]{p[0] + 1, p[1]});
            if (p[1] > 0) pending.push(new int[]{p[0], p[1] - 1});
            if (p[1] < height - 1) pending.push(new int[]{p[0], p[1] + 1});
        }
        modified = true;
    }

    /** Replace every pixel of the current frame (the paint-bucket-on-nothing case). */
    public void fillFrame(int argb) {
        pushUndo();
        int[] px = frames.get(current);
        java.util.Arrays.fill(px, argb);
        modified = true;
    }

    // --- shape walks (shared by drawing and the on-canvas preview) -------------------

    /** Bresenham: hand every pixel of a line to {@code sink}. */
    public static void forEachLine(int x0, int y0, int x1, int y1, PixelSink sink) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0, y = y0;
        while (true) {
            sink.at(x, y);
            if (x == x1 && y == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    /** Hand every pixel of a rectangle (outline or solid) to {@code sink}. */
    public static void forEachRect(int x0, int y0, int x1, int y1,
                                   boolean filled, PixelSink sink) {
        int left = Math.min(x0, x1), right = Math.max(x0, x1);
        int top = Math.min(y0, y1), bottom = Math.max(y0, y1);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                boolean edge = x == left || x == right || y == top || y == bottom;
                if (filled || edge) sink.at(x, y);
            }
        }
    }

    // --- undo -----------------------------------------------------------------------

    /**
     * Record an undo point. The editor calls this once at the start of each
     * stroke, so taking back a scribble takes back the whole scribble rather
     * than one pixel of it.
     */
    public void pushUndo() {
        if (undo.size() >= MAX_UNDO) undo.removeLast();
        undo.push(snapshot());
        redo.clear();
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.push(snapshot());
        restore(undo.pop());
        modified = true;
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.push(snapshot());
        restore(redo.pop());
        modified = true;
        return true;
    }

    private Snapshot snapshot() {
        List<int[]> copy = new ArrayList<>(frames.size());
        for (int[] f : frames) copy.add(f.clone());
        return new Snapshot(copy, width, height, current);
    }

    private void restore(Snapshot s) {
        frames.clear();
        for (int[] f : s.frames()) frames.add(f.clone());
        width = s.width();
        height = s.height();
        current = Math.max(0, Math.min(frames.size() - 1, s.current()));
    }

    // --- export ---------------------------------------------------------------------

    /** One frame as an image (for previews and thumbnails). */
    public BufferedImage frameImage(int index) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (index >= 0 && index < frames.size()) {
            img.setRGB(0, 0, width, height, frames.get(index), 0, width);
        }
        return img;
    }

    /**
     * The finished sprite sheet: every frame in one row, left to right — the
     * layout {@link SpriteSheet} slices and the texture pack documents.
     */
    public BufferedImage toSheet() {
        BufferedImage sheet = new BufferedImage(width * frames.size(), height,
                BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < frames.size(); i++) {
            sheet.setRGB(i * width, 0, width, height, frames.get(i), 0, width);
        }
        return sheet;
    }

    private int[] blank() {
        return new int[width * height];
    }

    private static int clampSize(int v) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
    }
}
