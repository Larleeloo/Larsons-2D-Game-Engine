package com.larsons.engine.core;

import com.larsons.engine.audio.Sounds;
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
import com.larsons.engine.demo.KeyBindsScene;
import com.larsons.engine.demo.LevelSelectScene;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.MultiplayerScene;
import com.larsons.engine.demo.PlayScene;
import com.larsons.engine.demo.SkinEditorScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.input.KeyBinds;

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
        // Before the relaunch, because on macOS the answer decides whether a
        // first-thread child process is spawned at all — and before anything
        // goes headless, because the question is asked with a Swing dialog and a
        // headless process has none. Asked once and remembered; an explicit
        // -Dlarsons.render.backend always wins. See BackendChooser.
        new BackendChooser().apply();

        // Before anything else, including the share-jar build below: on macOS a
        // GPU backend can only open a window on the process's first thread, and
        // a jar's manifest cannot ask for one. This relaunches the game there
        // when it is needed, and returns true only if the whole game has
        // already been played out in that child process.
        if (MacGlLauncher.relaunchIfNeeded(args)) return;

        // Still on macOS, still heading for a GPU backend: keep AWT off the
        // first thread. It has to happen here, before any of the loading below
        // can touch a BufferedImage, because a headless property set after the
        // toolkit has started is ignored without a word. The relaunched child
        // is told the same thing on its command line; this covers the run that
        // was already on the first thread and never needed a child.
        //
        // Deliberately after the relaunch check and not before it: when the
        // child reports that it found no driver, the parent runs the game on
        // Java2D, and Java2D is AWT. MacGlLauncher.keepAwtOffTheFirstThread has
        // the crash report that made this necessary — without it the window's
        // close button does nothing.
        // Two calls, and they are separate because settling AWT's headless
        // state is irreversible: the decision stays a pure function the tests
        // can exercise, and the side effect happens here, once, in a process
        // that is about to be a game rather than a test.
        //
        // Unconditional on the second, and the first version was not — which
        // left the warning it was added to remove. The relaunched child is told
        // `-Djava.awt.headless=true` on its command line, so it never takes the
        // branch that sets the property, so it never settled it early, so AWT
        // settled it late — after the backend probe had started a Cocoa
        // application — and said so on stderr. `settleHeadlessNow` checks
        // whether headless is in force rather than who put it there.
        MacGlLauncher.keepAwtOffTheFirstThread();
        MacGlLauncher.settleHeadlessNow();

        // Development convenience only: launching from IntelliJ leaves a
        // ready-to-send copy of the game (plus empty texture and sound packs
        // to fill) in share/, built in the background. A shipped jar builds nothing —
        // it must not write a copy of itself wherever the player put it.
        ShareJar.writeAsync();

        // Install any game-type packages (.larsonsengine) dropped next to the
        // jar. Runs before the startup scene lists game types, so an imported
        // type shows up on the chooser this launch.
        GamePackage.importDropIns();

        // The player's saved texture and sound overrides apply from the first
        // frame, so a pack that is already in place is heard and seen at once.
        Skins.install(new SkinStore().load());
        Sounds.load();

        // Custom key binds, likewise: the controls a player set last time are
        // in force from the launch menu on, before any scene reads a key.
        KeyBinds.install(new KeyBindStore().load());

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
        engine.scenes().register(KeyBindsScene.NAME, new KeyBindsScene());
        engine.scenes().register("skins", new SkinEditorScene(context));
        engine.scenes().register("boardtheme", new BoardCustomizeScene(context));

        engine.scenes().setScene("startup");
        engine.start();
    }
}
