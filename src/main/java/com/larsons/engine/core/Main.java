package com.larsons.engine.core;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GamePackage;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerGuideScene;
import com.larsons.engine.demo.AutoBattlerLobbyScene;
import com.larsons.engine.demo.AutoBattlerScene;
import com.larsons.engine.demo.BoardCustomizeScene;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.demo.DeckGameScene;
import com.larsons.engine.demo.DeckLobbyScene;
import com.larsons.engine.demo.EvolutionCatalogScene;
import com.larsons.engine.demo.EvolutionLobbyScene;
import com.larsons.engine.demo.EvolutionScene;
import com.larsons.engine.demo.GameTypeEditorScene;
import com.larsons.engine.demo.LevelSelectScene;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.MultiplayerScene;
import com.larsons.engine.demo.PlayScene;
import com.larsons.engine.demo.SkinEditorScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;

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
        // Leave a ready-to-send copy of the game in share/ (built in the
        // background), so launching from the IDE is all it takes to have
        // something to hand to friends.
        ShareJar.writeAsync();

        // Install any game-type packages (.larsonsengine) dropped next to the
        // jar. Runs before the startup scene lists game types, so an imported
        // type shows up on the chooser this launch.
        GamePackage.importDropIns();

        // The player's saved texture overrides apply from the first frame.
        Skins.install(new SkinStore().load());

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
        engine.scenes().register("levelselect", new LevelSelectScene(context));
        engine.scenes().register("play", new PlayScene(context, LEVEL));
        engine.scenes().register("creative", new CreativeScene(context));
        engine.scenes().register("multiplayer", new MultiplayerScene(context, LEVEL));
        engine.scenes().register("autolobby", new AutoBattlerLobbyScene(context));
        engine.scenes().register("autobattler", new AutoBattlerScene(context));
        engine.scenes().register("autoguide", new AutoBattlerGuideScene(context));
        engine.scenes().register("decklobby", new DeckLobbyScene(context));
        engine.scenes().register("deckgame", new DeckGameScene(context));
        engine.scenes().register("evolutionlobby", new EvolutionLobbyScene(context));
        engine.scenes().register("evolution", new EvolutionScene(context));
        engine.scenes().register("evolutioncatalog", new EvolutionCatalogScene(context));
        engine.scenes().register("skins", new SkinEditorScene(context));
        engine.scenes().register("boardtheme", new BoardCustomizeScene(context));

        engine.scenes().setScene("startup");
        engine.start();
    }
}
