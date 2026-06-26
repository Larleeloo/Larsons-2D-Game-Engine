package com.larsons.engine.core;

import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.PlayScene;

/**
 * Application entry point.
 *
 * <p>Boots the engine with the bundled demo scenes so the project is runnable
 * out of the box (requirement #4):
 * <pre>
 *   ./gradlew run
 *   # or, after `./gradlew jar`:
 *   java -jar build/libs/Larsons-2D-Game-Engine-0.1.0.jar
 * </pre>
 */
public class Main {
    public static void main(String[] args) {
        EngineConfig config = new EngineConfig()
                .title("Larson's 2D Game Engine")
                .size(1280, 720)
                .targetFps(120)
                .updateRate(120);

        Engine engine = new Engine(config);
        engine.scenes().register("menu", new MainMenuScene());
        engine.scenes().register("play", new PlayScene("levels/sample_level.json"));
        engine.scenes().setScene("menu");
        engine.start();
    }
}
