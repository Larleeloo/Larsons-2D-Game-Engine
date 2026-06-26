package com.larsons.engine.core;

import com.larsons.engine.graphics.Perspective;

import java.awt.Color;

/**
 * Engine configuration. Construct one, tweak via the fluent setters, and hand
 * it to {@link Engine}.
 *
 * <p>Defaults reflect the project's requirements: a 120 FPS render cap
 * (requirement #1) and a side-scroll default perspective (requirement #2,
 * which can be changed per game/level).
 */
public class EngineConfig {
    public String title = "Larson's 2D Game Engine";
    public int width = 1280;
    public int height = 720;

    /** Render frame cap. Requirement #1: support 120 FPS. */
    public int targetFps = 120;

    /**
     * Fixed simulation tick rate (Hz), decoupled from rendering. A fixed update
     * rate keeps the simulation deterministic, which is the foundation for the
     * networked play planned later (requirement #3).
     */
    public int updateRate = 120;

    public boolean resizable = true;
    public Color backgroundColor = new Color(18, 18, 24);
    public Perspective defaultPerspective = Perspective.SIDE_SCROLL;

    public EngineConfig() {}

    public EngineConfig title(String t) { this.title = t; return this; }

    public EngineConfig size(int w, int h) { this.width = w; this.height = h; return this; }

    public EngineConfig targetFps(int fps) { this.targetFps = fps; return this; }

    public EngineConfig updateRate(int rate) { this.updateRate = rate; return this; }

    public EngineConfig resizable(boolean b) { this.resizable = b; return this; }

    public EngineConfig background(Color c) { this.backgroundColor = c; return this; }

    public EngineConfig perspective(Perspective p) { this.defaultPerspective = p; return this; }
}
