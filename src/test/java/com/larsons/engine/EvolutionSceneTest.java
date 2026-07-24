package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.EvolutionCatalogScene;
import com.larsons.engine.demo.EvolutionLobbyScene;
import com.larsons.engine.demo.EvolutionScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.evolution.EnergyOrb;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Genome;
import com.larsons.engine.evolution.Nucleotide;
import com.larsons.engine.evolution.ShopItem;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-screen smoke tests for the evolution scenes, in the same style as
 * {@link EngineSmokeTest}: every screen renders headlessly against a live
 * simulation — the lobby, the microscope with its overlays, and both pages of
 * the reference book — and the tool tray actually does something when clicked.
 *
 * <p>All of it runs against a temporary store, so a test run never touches the
 * player's real save or catalog.
 */
@Timeout(120)
class EvolutionSceneTest {

    private static final int W = 960;
    private static final int H = 540;
    private static final Component SRC = new Container();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static GameContext context(Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.resolve("gametypes").toString()));
        ctx.setProfile(new GameProfile("Evolution Test"));
        return ctx;
    }

    private static void render(SceneManager scenes) {
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();
        try {
            scenes.render(g, 1f);
        } finally {
            g.dispose();
        }
    }

    private static void tick(SceneManager scenes, InputManager input, int frames) {
        for (int i = 0; i < frames; i++) {
            input.newFrame();
            scenes.update(1 / 60.0, input);
        }
    }

    private static void press(SceneManager scenes, InputManager input, int key) {
        input.keyPressed(new KeyEvent(SRC, KeyEvent.KEY_PRESSED, 0L, 0, key, (char) key));
        tick(scenes, input, 1);
        input.keyReleased(new KeyEvent(SRC, KeyEvent.KEY_RELEASED, 0L, 0, key, (char) key));
        tick(scenes, input, 1);
    }

    private static void click(SceneManager scenes, InputManager input, int x, int y) {
        input.mouseMoved(new MouseEvent(SRC, MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0, false));
        input.mousePressed(new MouseEvent(SRC, MouseEvent.MOUSE_PRESSED, 0L, 0, x, y, 1, false,
                MouseEvent.BUTTON1));
        tick(scenes, input, 1);
        input.mouseReleased(new MouseEvent(SRC, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, false,
                MouseEvent.BUTTON1));
        tick(scenes, input, 1);
    }

    /** A dish that has been fed and left to evolve, so the scene has something to draw. */
    private static EvolutionGame livingExperiment(long seed) {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, seed);
        Random rng = new Random(seed);
        game.inventory().add(ShopItem.ENERGY_SIMPLE, 200);
        for (int i = 0; i < 200; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = 340 * Math.sqrt(rng.nextDouble());
            game.feed(EnergyOrb.Kind.SIMPLE, Math.cos(a) * r, Math.sin(a) * r);
        }
        for (int i = 0; i < 600; i++) game.step(0.25);
        return game;
    }

    @Test
    void everyEvolutionScreenRendersHeadlessly(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        GameContext ctx = context(dir);

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        EvolutionLobbyScene lobby = new EvolutionLobbyScene(ctx, store);
        EvolutionScene dish = new EvolutionScene(ctx, store);
        EvolutionCatalogScene book = new EvolutionCatalogScene(ctx, store);
        scenes.register("evolutionlobby", lobby);
        scenes.register("evolution", dish);
        scenes.register("evolutioncatalog", book);
        scenes.register("startup", new StartupScene(ctx));

        InputManager input = new InputManager();

        // The lobby, and its colour choice.
        scenes.setScene("evolutionlobby");
        tick(scenes, input, 3);
        render(scenes);
        press(scenes, input, KeyEvent.VK_DOWN); // move within the menu
        render(scenes);

        // A live dish, with a real population in it.
        EvolutionGame game = livingExperiment(31L);
        assertTrue(game.catalog().speciesCount() >= 1, "something was catalogued to draw");
        dish.adopt(game);
        scenes.setScene("evolution");
        tick(scenes, input, 10);
        render(scenes);

        // Every overlay.
        press(scenes, input, KeyEvent.VK_B);   // shop
        render(scenes);
        press(scenes, input, KeyEvent.VK_B);   // close
        press(scenes, input, KeyEvent.VK_H);   // help
        render(scenes);
        press(scenes, input, KeyEvent.VK_H);
        press(scenes, input, KeyEvent.VK_ESCAPE); // pause
        render(scenes);
        press(scenes, input, KeyEvent.VK_ESCAPE);

        // Both pages of the book, against the catalog files this run wrote.
        store.save(game);
        scenes.setScene("evolutioncatalog");
        tick(scenes, input, 3);
        render(scenes);
        press(scenes, input, KeyEvent.VK_TAB); // achievements
        render(scenes);
        assertTrue(store.speciesFileCount() >= 1, "the book had files to read");
    }

    @Test
    void theToolTrayFeedsTheDish(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        GameContext ctx = context(dir);

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        EvolutionScene dish = new EvolutionScene(ctx, store);
        scenes.register("evolution", dish);

        EvolutionGame game = EvolutionGame.newGame(Nucleotide.B, 32L);
        dish.adopt(game);
        scenes.setScene("evolution");
        InputManager input = new InputManager();
        tick(scenes, input, 2);
        render(scenes); // lays out the tool tray for hit-testing

        int orbsBefore = game.activeDish().orbs().size();
        int stock = game.inventory().count(ShopItem.ENERGY_SIMPLE);

        press(scenes, input, KeyEvent.VK_1);            // the simple-energy tool
        click(scenes, input, W / 2, H / 2);             // the middle of the dish
        assertEquals(orbsBefore + 1, game.activeDish().orbs().size(),
                "clicking the gel with the feeding tool drops an orb");
        assertEquals(stock - 1, game.inventory().count(ShopItem.ENERGY_SIMPLE),
                "and spends it from the bench");
    }

    @Test
    void inspectingSelectsAnOrganismAndRendersItsReadout(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        GameContext ctx = context(dir);

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        EvolutionScene dish = new EvolutionScene(ctx, store);
        scenes.register("evolution", dish);

        EvolutionGame game = EvolutionGame.newGame(Nucleotide.R, 33L);
        // The DNA scanner turns the read-out from a guess into an exact strand;
        // both paths have to draw.
        game.inventory().grant(ShopItem.DNA_SCANNER);
        dish.adopt(game);
        scenes.setScene("evolution");
        InputManager input = new InputManager();
        tick(scenes, input, 2);

        // The starter sits at the middle of the dish, which is the middle of the
        // screen while the camera is still centred.
        press(scenes, input, KeyEvent.VK_I);
        click(scenes, input, W / 2, H / 2);
        render(scenes);
        assertEquals(1, game.activeDish().population(), "the click inspected rather than killed");
    }

    @Test
    void theMainMenuOffersEvolutionAndOpensIt(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        GameContext ctx = context(dir);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        StartupScene startup = new StartupScene(ctx);
        scenes.register("startup", startup);
        scenes.register("evolutionlobby", new EvolutionLobbyScene(ctx, store));
        scenes.setScene("startup");

        InputManager input = new InputManager();
        tick(scenes, input, 2);
        render(scenes);

        MenuItem entry = null;
        for (MenuItem item : startup.menu().items()) {
            if (item.text().startsWith("Evolution")) entry = item;
        }
        assertNotNull(entry, "the artificial life simulator is on the main menu");

        entry.activate();
        tick(scenes, input, 90); // let the fade finish
        assertEquals("EvolutionLobbyScene", scenes.current().name(),
                "and choosing it opens the game");
    }

    @Test
    void aStarterStrandDecodesToSomethingDrawable() {
        // Guards the scene's assumption that every organism has a shape and a
        // colour to draw, whichever colour the player picks.
        for (Nucleotide n : Nucleotide.values()) {
            Genome starter = Genome.starter(n);
            assertNotNull(com.larsons.engine.evolution.Phenotype.of(starter).shape());
            assertNotNull(com.larsons.engine.evolution.Phenotype.of(starter).color());
        }
    }
}
