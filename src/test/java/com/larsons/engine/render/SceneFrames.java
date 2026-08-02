package com.larsons.engine.render;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerGuideScene;
import com.larsons.engine.demo.AutoBattlerLobbyScene;
import com.larsons.engine.demo.BoardCustomizeScene;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.demo.DeckLobbyScene;
import com.larsons.engine.demo.EvolutionCatalogScene;
import com.larsons.engine.demo.EvolutionLobbyScene;
import com.larsons.engine.demo.GameTypeEditorScene;
import com.larsons.engine.demo.KeyBindsScene;
import com.larsons.engine.demo.LevelSelectScene;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.MultiplayerScene;
import com.larsons.engine.demo.SkinEditorScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.render.GoldenFrames.Frame;
import com.larsons.engine.scene.Scene;
import com.larsons.engine.scene.SceneManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The same instrument as {@link GoldenFrames}, pointed at whole scenes instead
 * of individual painters.
 *
 * <p><b>Why B3 needs its own catalogue.</b> B0's frames golden the shared
 * painters, which is what B2 changed. B3 changes the eighteen scenes that
 * <em>call</em> those painters — around two thousand drawing statements that
 * were reaching past the seam through {@code Java2DTarget.graphicsOf}. None of
 * that code appears in any B0 frame, so B0 would have stayed green through a
 * port that moved every HUD element in the game by a pixel. A port claiming to
 * be a pure translation has to be checked against the thing it translated, and
 * that means rendering the scenes themselves.
 *
 * <p><b>What a scene frame is.</b> A scene, built from fixed inputs, registered
 * with a {@link SceneManager} at a fixed viewport, advanced a fixed number of
 * fixed-length ticks with no input, and rendered once. That is the whole
 * lifecycle the engine puts a scene through, minus the window.
 *
 * <p><b>Determinism is the hard part, and it decides what is in here.</b>
 * Everything on disk is redirected to a scratch directory built by
 * {@link #store()} and friends, so the pictures do not depend on what the
 * developer happens to have saved. Scenes that remain non-deterministic after
 * that — because they seed a simulation from the wall clock, or spawn from an
 * unseeded RNG — are listed in {@link #NOT_GOLDENABLE} with the reason, rather
 * than being goldened at a tolerance that would hide a real regression.
 * {@code SceneFramesTest} asserts that list is exhaustive: every scene in the
 * demo package is either goldened here or named there, so a nineteenth scene
 * cannot be added without a decision being made about it.
 */
public final class SceneFrames {

    private SceneFrames() {}

    /** Where scene references live, beside B0's. */
    public static final String RESOURCE_DIR = "src/test/resources/golden";

    /** Fixed viewport for every scene frame: 4:3, big enough for a full HUD. */
    public static final int WIDTH = 800;
    public static final int HEIGHT = 480;

    /** The fixed tick every scene is advanced by, matching the engine's sim rate. */
    private static final double DT = 1.0 / 120.0;

    /**
     * Scenes deliberately left out of the catalogue, and why.
     *
     * <p>Each of these draws from a source this harness cannot pin down. They
     * are still ported in B3 — they are just verified by the suite and by
     * review rather than by a picture, which is stated here rather than left
     * for someone to discover as an absence.
     */
    public static final List<String> NOT_GOLDENABLE = List.of(
            // Spawns mobs and drops through World.populateFromLevel on an
            // unseeded RNG, and its cast shadows follow a day/night clock.
            // The world it draws is goldened by B0's three world-* frames.
            "PlayScene",
            // A live artificial-life simulation: population, positions and
            // energy all come from an unseeded RNG at onEnter.
            "EvolutionScene",
            // Rolls a shop from an unseeded RNG on entering the first round.
            "AutoBattlerScene",
            // Shuffles its deck from an unseeded RNG.
            "DeckGameScene");

    // --- the catalogue ---------------------------------------------------------

    /**
     * Every goldenable scene, ordered smallest-first — the order B3 ports them,
     * so a failure list reads as "the port stopped being a translation here".
     */
    public static List<Frame> all() {
        List<Frame> frames = new ArrayList<>();

        frames.add(scene("scene-startup", ctx -> new StartupScene(ctx)));
        frames.add(scene("scene-multiplayer", ctx -> new MultiplayerScene(ctx, "")));
        frames.add(scene("scene-level-select", ctx -> new LevelSelectScene(ctx)));
        frames.add(scene("scene-key-binds",
                ctx -> new KeyBindsScene(new KeyBindStore(scratch("keybinds").toString()))));
        frames.add(scene("scene-game-type-editor", ctx -> new GameTypeEditorScene(ctx)));
        frames.add(scene("scene-main-menu", ctx -> new MainMenuScene(ctx)));
        frames.add(scene("scene-evolution-lobby",
                ctx -> new EvolutionLobbyScene(ctx, evolutionStore())));
        frames.add(scene("scene-board-customize", ctx -> new BoardCustomizeScene(ctx)));
        frames.add(scene("scene-skin-editor",
                ctx -> new SkinEditorScene(ctx, new SkinStore(scratch("skins").toString()))));
        frames.add(scene("scene-auto-battler-lobby", ctx -> new AutoBattlerLobbyScene(ctx)));
        frames.add(scene("scene-deck-lobby", ctx -> new DeckLobbyScene(ctx)));
        frames.add(scene("scene-evolution-catalog",
                ctx -> new EvolutionCatalogScene(ctx, evolutionStore())));
        frames.add(scene("scene-auto-battler-guide", ctx -> new AutoBattlerGuideScene(ctx)));
        frames.add(scene("scene-creative", ctx -> new CreativeScene(ctx)));

        return frames;
    }

    /**
     * All of them, painters and scenes, which is what the comparison and the
     * draw-call table both want.
     */
    public static List<Frame> allFrames() {
        List<Frame> frames = new ArrayList<>(GoldenFrames.all());
        frames.addAll(all());
        return frames;
    }

    // --- building one ----------------------------------------------------------

    /** A scene frame at the standard viewport, advanced two ticks before drawing. */
    private static Frame scene(String name, Function<GameContext, Scene> factory) {
        return scene(name, factory, 2);
    }

    /**
     * Advancing before drawing is not optional: several scenes build their menu
     * or form in {@code onEnter} and then settle it over the first ticks — a
     * {@code ContainerPanel} at zero openness draws nothing at all, and B0 hit
     * exactly that. Two ticks is past every such easing and short of any
     * animation that would make the picture a function of how many frames were
     * run.
     */
    private static Frame scene(String name, Function<GameContext, Scene> factory, int ticks) {
        return new Frame(name, WIDTH, HEIGHT, target -> {
            GameContext ctx = context();
            Scene scene = factory.apply(ctx);

            SceneManager scenes = new SceneManager();
            scenes.setViewport(WIDTH, HEIGHT);
            scenes.register(name, scene);
            scenes.setScene(name);

            InputManager input = new InputManager();
            for (int i = 0; i < ticks; i++) {
                input.newFrame();
                scenes.update(DT, input);
            }
            scenes.render(target, 0f);
        });
    }

    // --- fixed inputs ----------------------------------------------------------

    /**
     * A context over a scratch game-type directory holding two fixed profiles.
     *
     * <p>Empty would be simpler and would also golden the empty-list branch of
     * every menu that lists game types, which is not the branch players see.
     * Two entries exercise the list and its selection highlight, and being
     * written here rather than read from {@code src/main/resources} means the
     * picture does not change when someone saves a game type locally.
     */
    private static GameContext context() {
        GameContext ctx = new GameContext(null, store());
        GameProfile profile = new GameProfile("Frostmarch");
        profile.perspective = com.larsons.engine.graphics.Perspective.SIDE_SCROLL;
        ctx.setProfile(profile);
        return ctx;
    }

    private static GameTypeStore store() {
        Path dir = scratch("gametypes");
        GameTypeStore store = new GameTypeStore(dir.toString());
        if (!Files.exists(dir.resolve(store.fileFor("Frostmarch").getFileName()))) {
            store.save(new GameProfile("Frostmarch"));
            GameProfile second = new GameProfile("Sunken Halls");
            second.perspective = com.larsons.engine.graphics.Perspective.TOP_DOWN;
            store.save(second);
        }
        return store;
    }

    private static EvolutionStore evolutionStore() {
        return new EvolutionStore(scratch("evolution").toString());
    }

    /**
     * A directory under {@code build/} for a store to read and write, created
     * once per run. Not {@code java.io.tmpdir}: a golden that depends on a path
     * length depends on the username, and this way the inputs are inspectable
     * after a failure instead of swept away.
     */
    private static Path scratch(String name) {
        Path dir = Path.of("build", "golden-scenes", name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

}
