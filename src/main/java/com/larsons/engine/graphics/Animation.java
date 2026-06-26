package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Time-based frame animation. Advance it with the per-frame delta (in seconds)
 * each update; because timing is delta-driven it plays at the same speed
 * regardless of the render frame rate (e.g. 60 vs 120 FPS).
 */
public class Animation {
    private final List<BufferedImage> frames;
    private final double secondsPerFrame;
    private final boolean loop;

    private double timer;
    private int index;
    private boolean finished;

    public Animation(List<BufferedImage> frames, double fps, boolean loop) {
        this.frames = frames;
        this.secondsPerFrame = fps > 0 ? 1.0 / fps : 0.1;
        this.loop = loop;
    }

    public void update(double dt) {
        if (finished || frames.isEmpty()) return;
        timer += dt;
        while (timer >= secondsPerFrame) {
            timer -= secondsPerFrame;
            index++;
            if (index >= frames.size()) {
                if (loop) {
                    index = 0;
                } else {
                    index = frames.size() - 1;
                    finished = true;
                }
            }
        }
    }

    public BufferedImage current() {
        return frames.isEmpty() ? null : frames.get(index);
    }

    public void reset() {
        index = 0;
        timer = 0;
        finished = false;
    }

    public boolean isFinished() { return finished; }

    public int frameCount() { return frames.size(); }
}
