package com.larsons.engine.core;

import com.larsons.engine.graphics.Java2DRenderer;
import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;

import java.awt.Graphics2D;

/**
 * Top-level engine: wires together the window, renderer, input, scene manager,
 * and game loop.
 *
 * <p>Typical use:
 * <pre>
 *   Engine engine = new Engine(new EngineConfig());
 *   engine.scenes().register("menu", new MainMenuScene());
 *   engine.scenes().setScene("menu");
 *   engine.start();
 * </pre>
 */
public class Engine {
    private final EngineConfig config;
    private final GameWindow window;
    private final InputManager input;
    private final Renderer renderer;
    private final SceneManager scenes;
    private final GameLoop loop;

    // Last-seen viewport size, to detect window resizes.
    private int lastWidth, lastHeight;

    public Engine(EngineConfig config) {
        this.config = config;
        this.window = new GameWindow(config);
        this.input = new InputManager();
        window.attachInput(input);

        this.renderer = new Java2DRenderer(window.getCanvas(), config.backgroundColor);
        this.scenes = new SceneManager();
        this.scenes.setViewport(config.width, config.height);
        this.lastWidth = config.width;
        this.lastHeight = config.height;

        this.loop = new GameLoop(config.updateRate, config.targetFps, this::tick, this::draw);
    }

    public SceneManager scenes() { return scenes; }

    public EngineConfig config() { return config; }

    public InputManager input() { return input; }

    public double fps() { return loop.getFps(); }

    public void start() {
        window.show();
        loop.start();
    }

    public void stop() { loop.stop(); }

    private void tick(double dt) {
        // Keep the scene viewport in sync with a (possibly) resizable window.
        int w = window.getWidth();
        int h = window.getHeight();
        if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
            lastWidth = w;
            lastHeight = h;
            scenes.setViewport(w, h);
        }

        input.newFrame();
        scenes.update(dt, input);
    }

    private void draw(double alpha) {
        Graphics2D g = renderer.beginFrame();
        try {
            scenes.render(g, (float) alpha);
        } finally {
            renderer.present();
        }
    }
}
