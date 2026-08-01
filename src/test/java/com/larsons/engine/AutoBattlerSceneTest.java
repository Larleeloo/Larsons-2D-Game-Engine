package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoServer;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerLobbyScene;
import com.larsons.engine.demo.AutoBattlerScene;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-screen smoke tests for the auto-battler scenes, in the same style as
 * {@link EngineSmokeTest}: the lobby form renders headlessly, and the game
 * scene renders live planning and combat frames against a real loopback
 * server — the whole client presentation path without a display.
 */
@Timeout(60)
class AutoBattlerSceneTest {

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
    void lobbyAndGameScenesRenderHeadlessly() throws Exception {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Auto Battler Test"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(960, 540);
        AutoBattlerLobbyScene lobby = new AutoBattlerLobbyScene(ctx);
        AutoBattlerScene game = new AutoBattlerScene(ctx);
        scenes.register("autolobby", lobby);
        scenes.register("autobattler", game);

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(960, 540, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        // The lobby's host/join form renders on its own.
        scenes.setScene("autolobby");
        for (int i = 0; i < 5; i++) {
            input.newFrame();
            scenes.update(1.0 / 60.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }

        // A real loopback game: host + bot, then render planning and combat.
        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 11;
        cfg.planSeconds = 3;
        cfg.fightMaxSeconds = 5;
        cfg.postSeconds = 0.3;
        AutoServer server = new AutoServer(cfg);
        server.start(0);
        try {
            AutoClient client = AutoClient.connect(
                    "127.0.0.1", server.getPort(), "Renderer", 3000);
            client.sendAddBot();
            await("bot joins", () -> client.lobby().players().size() == 2);
            client.sendStart();
            await("planning begins", () -> client.phase() != null
                    && client.phase().phase() == AutoGame.Phase.PLAN);
            await("private state arrives", () -> client.you() != null);

            // Field a unit so round 1's creep fight actually runs (an empty
            // board loses instantly and would never produce combat frames).
            int slot = 0;
            for (int i = 0; i < client.you().shop().size(); i++) {
                if (client.you().shop().get(i) != null) {
                    slot = i;
                    break;
                }
            }
            client.sendBuy(slot);
            await("unit bought", () -> client.you() != null
                    && !client.you().bench().isEmpty());
            client.sendMoveToBoard(client.you().bench().get(0).id, 3, 4);
            await("unit fielded", () -> client.you() != null
                    && !client.you().board().isEmpty());

            game.adopt(new AutoSession(client, server));
            scenes.setScene("autobattler");
            for (int i = 0; i < 15; i++) {
                input.newFrame();
                scenes.update(1.0 / 60.0, input);
                scenes.render(Java2DTarget.unsized(g), 0f);
            }

            await("combat begins", () -> client.phase() != null
                    && client.phase().phase() == AutoGame.Phase.FIGHT
                    && client.combatLatest() != null);
            for (int i = 0; i < 15; i++) {
                input.newFrame();
                scenes.update(1.0 / 60.0, input);
                scenes.render(Java2DTarget.unsized(g), 0f);
            }

            assertNotNull(scenes.current());
            assertEquals("AutoBattlerScene", scenes.current().name());
            // Leaving the scene tears the session (and integrated server) down.
            scenes.setScene("autolobby");
            await("session closed with the scene", () -> !client.isConnected());
        } finally {
            server.stop();
            g.dispose();
        }
    }
}
