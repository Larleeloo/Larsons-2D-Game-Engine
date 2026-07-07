package com.larsons.engine.core;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.demo.GameTypeEditorScene;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.MultiplayerScene;
import com.larsons.engine.demo.PlayScene;
import com.larsons.engine.demo.StartupScene;

/**
 * Application entry point.
 *
 * <p>Boots into the game-type chooser: the user creates a new game type (naming
 * it and enabling the features they want) or selects an existing one, then
 * plays/creates levels within it. Game types persist as JSON under
 * {@code resources/gametypes/}.
 *
 * <pre>
 *   ./gradlew run
 *   # or, after `./gradlew jar`:
 *   java -jar build/libs/Larsons-2D-Game-Engine-0.1.0.jar
 * </pre>
 */
public class Main {

    /** The demo level; also what an in-game host serves to joining players. */
    public static final String LEVEL = "levels/sample_level.json";

    public static void main(String[] args) {
        EngineConfig config = new EngineConfig()
                .title("Larson's 2D Game Engine")
                .size(1280, 720)
                .targetFps(120)
                .updateRate(120);

        Engine engine = new Engine(config);
        GameContext context = new GameContext(engine, new GameTypeStore());

        engine.scenes().register("startup", new StartupScene(context));
        engine.scenes().register("editor", new GameTypeEditorScene(context));
        engine.scenes().register("menu", new MainMenuScene(context));
        engine.scenes().register("play", new PlayScene(context, LEVEL));
        engine.scenes().register("creative", new CreativeScene(context));
        engine.scenes().register("multiplayer", new MultiplayerScene(context, LEVEL));

        engine.scenes().setScene("startup");
        engine.start();
    }
}
