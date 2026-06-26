package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Slices a sprite sheet image into uniform frames, left-to-right then
 * top-to-bottom (requirement #6: sprite sheet support).
 *
 * <p>Frames can be sliced from a file on disk/classpath (via
 * {@link AssetLoader}) or from an in-memory {@link BufferedImage} — the latter
 * lets the demo build a placeholder character with zero binary asset files, so
 * the project runs out of the box.
 */
public final class SpriteSheet {
    private final List<BufferedImage> frames;
    private final int frameWidth;
    private final int frameHeight;

    private SpriteSheet(List<BufferedImage> frames, int frameWidth, int frameHeight) {
        this.frames = frames;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /** Load a sheet from disk/classpath and slice it. */
    public static SpriteSheet load(String path, int frameWidth, int frameHeight) {
        return fromImage(AssetLoader.loadImage(path), frameWidth, frameHeight);
    }

    /** Slice an in-memory image into frames of the given size. */
    public static SpriteSheet fromImage(BufferedImage sheet, int frameWidth, int frameHeight) {
        List<BufferedImage> frames = new ArrayList<>();
        if (sheet == null || frameWidth <= 0 || frameHeight <= 0) {
            return new SpriteSheet(frames, Math.max(1, frameWidth), Math.max(1, frameHeight));
        }
        int cols = sheet.getWidth() / frameWidth;
        int rows = sheet.getHeight() / frameHeight;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                frames.add(sheet.getSubimage(c * frameWidth, r * frameHeight, frameWidth, frameHeight));
            }
        }
        return new SpriteSheet(frames, frameWidth, frameHeight);
    }

    /** Frame at {@code index}, wrapping out-of-range indices. */
    public BufferedImage frame(int index) {
        if (frames.isEmpty()) return null;
        return frames.get(Math.floorMod(index, frames.size()));
    }

    public int frameCount() { return frames.size(); }

    public int frameWidth() { return frameWidth; }

    public int frameHeight() { return frameHeight; }

    public List<BufferedImage> frames() { return frames; }

    /** Build an animation from a contiguous run of frames. */
    public Animation animation(double fps, int from, int count, boolean loop) {
        List<BufferedImage> sub = new ArrayList<>();
        for (int k = 0; k < count; k++) sub.add(frame(from + k));
        return new Animation(sub, fps, loop);
    }

    /** Build an animation from all frames in this sheet. */
    public Animation animation(double fps, boolean loop) {
        return new Animation(new ArrayList<>(frames), fps, loop);
    }
}
