package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.autobattler.AutoClient;
import com.larsons.engine.autobattler.AutoGame;
import com.larsons.engine.autobattler.AutoServer;
import com.larsons.engine.autobattler.AutoSession;
import com.larsons.engine.autobattler.UnitInstance;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerScene;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real synthetic mouse drags through {@link AutoBattlerScene} against a
 * loopback server to prove the drag-and-drop placement path end to end: a
 * press over a benched unit, a drag across the bench, and a release lands the
 * unit in the target slot (the server applies it and reflects it back in the
 * player's {@code you} roster). A companion case confirms the plain
 * click-to-move fallback still works, so both interaction styles coexist.
 */
@Timeout(60)
class AutoBattlerDragDropTest {

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

    // Bench-slot screen geometry, mirroring AutoBattlerScene#layoutHud at 960x540.
    private static int benchCenterX(int slot) {
        int slotW = 58;
        int benchX = W / 2 - (slotW * 9 + 8 * 4) / 2;
        return benchX + slot * (slotW + 4) + slotW / 2;
    }

    private static int benchCenterY() {
        int shopH = 116;
        int slotW = 58;
        return (H - shopH) - slotW - 10 + slotW / 2;
    }

    @Test
    void draggingABenchedUnitMovesItToTheTargetSlot() throws Exception {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Auto Battler Drag Test"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        AutoBattlerScene game = new AutoBattlerScene(ctx);
        scenes.register("autobattler", game);

        InputManager input = new InputManager();
        Component source = new Canvas(); // MouseEvent needs a non-null source
        BufferedImage frame = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        AutoGame.Config cfg = new AutoGame.Config();
        cfg.seed = 7;
        cfg.planSeconds = 30; // stay in planning long enough to drag
        cfg.fightMaxSeconds = 5;
        cfg.postSeconds = 0.3;
        AutoServer server = new AutoServer(cfg);
        server.start(0);
        try {
            AutoClient client = AutoClient.connect("127.0.0.1", server.getPort(), "Dragger", 3000);
            client.sendAddBot();
            await("bot joins", () -> client.lobby().players().size() == 2);
            client.sendStart();
            await("planning begins", () -> client.phase() != null
                    && client.phase().phase() == AutoGame.Phase.PLAN);
            await("private state arrives", () -> client.you() != null);

            // Buy a unit so there is something on the bench to drag.
            int shopSlot = 0;
            for (int i = 0; i < client.you().shop().size(); i++) {
                if (client.you().shop().get(i) != null) {
                    shopSlot = i;
                    break;
                }
            }
            client.sendBuy(shopSlot);
            await("unit bought", () -> !client.you().bench().isEmpty());

            game.adopt(new AutoSession(client, server));
            scenes.setScene("autobattler");
            pump(scenes, input, g, 3);

            UnitInstance unit = client.you().bench().get(0);
            int fromSlot = unit.bench;
            int toSlot = (fromSlot + 4) % 9; // a distinct, empty bench slot
            int unitId = unit.id;

            // Press on the unit, drag across the bench, release on the target slot.
            press(input, source, benchCenterX(fromSlot), benchCenterY());
            pump(scenes, input, g, 1);
            drag(input, source, (benchCenterX(fromSlot) + benchCenterX(toSlot)) / 2, benchCenterY());
            pump(scenes, input, g, 1);
            drag(input, source, benchCenterX(toSlot), benchCenterY());
            pump(scenes, input, g, 1);
            release(input, source, benchCenterX(toSlot), benchCenterY());
            pump(scenes, input, g, 1);

            await("drag moved the unit to the target slot",
                    () -> benchSlotOf(client, unitId) == toSlot);

            // The plain click-to-move fallback: click the unit, then click a slot.
            int backSlot = (toSlot + 2) % 9;
            click(input, source, scenes, g, benchCenterX(toSlot), benchCenterY());
            click(input, source, scenes, g, benchCenterX(backSlot), benchCenterY());
            await("click-to-move fallback still works",
                    () -> benchSlotOf(client, unitId) == backSlot);

            assertNotNull(scenes.current());
        } finally {
            server.stop();
            g.dispose();
        }
    }

    private static int benchSlotOf(AutoClient client, int unitId) {
        if (client.you() == null) return -999;
        for (UnitInstance u : client.you().bench()) {
            if (u.id == unitId) return u.bench;
        }
        return -1; // fielded on the board, or gone
    }

    /** Advance the loop n ticks: promote input latches, update, render. */
    private static void pump(SceneManager scenes, InputManager input, Graphics2D g, int ticks) {
        for (int i = 0; i < ticks; i++) {
            input.newFrame();
            scenes.update(1.0 / 60.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
    }

    private static void click(InputManager input, Component source, SceneManager scenes,
                              Graphics2D g, int x, int y) {
        press(input, source, x, y);
        pump(scenes, input, g, 1);
        release(input, source, x, y);
        pump(scenes, input, g, 1);
    }

    private static void press(InputManager input, Component source, int x, int y) {
        input.mousePressed(new MouseEvent(source, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void drag(InputManager input, Component source, int x, int y) {
        input.mouseDragged(new MouseEvent(source, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, 0, false));
    }

    private static void release(InputManager input, Component source, int x, int y) {
        input.mouseReleased(new MouseEvent(source, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
    }
}
