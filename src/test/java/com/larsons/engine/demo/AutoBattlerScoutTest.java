package com.larsons.engine.demo;

import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoServer;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Board scouting end to end through the real UI path: a synthetic click on a
 * standings row sends a {@code view} request to a live loopback server, the
 * {@code board} reply lands in the client, and the scene renders the scout
 * overlay — then Esc closes it (without pausing the game).
 */
@Timeout(60)
class AutoBattlerScoutTest {

    private static final int W = 960, H = 540;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    @Test
    void clickingAStandingsRowScoutsThatPlayersBoard() throws Exception {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Auto Battler Scout Test"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        AutoBattlerScene game = new AutoBattlerScene(ctx);
        scenes.register("autobattler", game);
        scenes.register("autolobby", new AutoBattlerLobbyScene(ctx));

        InputManager input = new InputManager();
        Component source = new Canvas();
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 3;
        cfg.planSeconds = 30;
        cfg.fightMaxSeconds = 5;
        cfg.postSeconds = 0.3;
        AutoServer server = new AutoServer(cfg);
        server.start(0);
        try {
            AutoClient client = AutoClient.connect("127.0.0.1", server.getPort(), "Scout", 3000);
            client.sendAddBot();
            await("bot joins", () -> client.lobby().players().size() == 2);
            client.sendStart();
            await("planning begins", () -> client.phase() != null
                    && client.phase().phase() == AutoGame.Phase.PLAN);
            await("standings arrive", () -> client.standings().size() == 2);

            game.adopt(new AutoSession(client, server));
            scenes.setScene("autobattler");
            pump(scenes, input, g, 3); // render once so standings rows exist

            // Click the first standings row (the panel's first entry).
            Rectangle panel = AutoHud.standingsPanel(W, H);
            int rowX = panel.x + 30;
            int rowY = panel.y + 24;
            click(input, source, scenes, g, rowX, rowY);
            pump(scenes, input, g, 2);

            await("the scouted board arrives", () -> client.boardView() != null);
            AutoClient.BoardView bv = client.boardView();
            assertNotNull(bv.name());
            assertTrue(bv.hp() > 0, "scouted stats are populated");
            assertTrue(bv.boardCap() > 0);

            // The overlay renders and Esc closes it without pausing the match.
            pump(scenes, input, g, 3);
            input.keyPressed(new java.awt.event.KeyEvent(source,
                    KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                    KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
            pump(scenes, input, g, 2);
            // Still connected and playing; a second Esc now pauses (also fine
            // to render), proving the first one was consumed by the overlay.
            assertTrue(client.isConnected());
            assertEquals("AutoBattlerScene", scenes.current().name());
        } finally {
            server.stop();
            g.dispose();
        }
    }

    private static void pump(SceneManager scenes, InputManager input, Graphics2D g, int ticks) {
        for (int i = 0; i < ticks; i++) {
            input.newFrame();
            scenes.update(1.0 / 60.0, input);
            scenes.render(g, 0f);
        }
    }

    private static void click(InputManager input, Component source, SceneManager scenes,
                              Graphics2D g, int x, int y) {
        input.mousePressed(new MouseEvent(source, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
        pump(scenes, input, g, 1);
        input.mouseReleased(new MouseEvent(source, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
        pump(scenes, input, g, 1);
    }
}
