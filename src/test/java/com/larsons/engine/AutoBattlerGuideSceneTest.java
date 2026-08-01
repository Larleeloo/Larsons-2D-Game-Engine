package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerGuideScene;
import com.larsons.engine.demo.AutoBattlerLobbyScene;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Off-screen smoke test for the auto-battler field guide: it renders every tab
 * and drives synthetic clicks across the whole viewport so that the tabs' icon
 * hotspots (traits, items, odds cells, unit cards, phase nodes) get hit and
 * their detail cards render — the entire guide presentation path without a
 * display. Purely data-driven, so no server/lobby connection is needed.
 */
@Timeout(60)
class AutoBattlerGuideSceneTest {

    // A throwaway AWT component to source synthetic events from (headless-safe).
    private static final Component SRC = new Container();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void guideRendersEveryTabAndOpensDetailCards() {
        GameContext ctx = new GameContext(null, new GameTypeStore());
        ctx.setProfile(new GameProfile("Auto Battler Test"));

        SceneManager scenes = new SceneManager();
        scenes.setViewport(1024, 640);
        scenes.register("autolobby", new AutoBattlerLobbyScene(ctx));
        AutoBattlerGuideScene guide = new AutoBattlerGuideScene(ctx);
        scenes.register("autoguide", guide);

        InputManager input = new InputManager();
        BufferedImage frame = new BufferedImage(1024, 640, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = frame.createGraphics();

        scenes.setScene("autoguide");

        // Visit all six tabs. On each, render a few frames so hotspots are laid
        // out, then sweep synthetic clicks over the content to trip icon
        // hotspots and pop (then dismiss) detail cards — driving every draw path.
        for (int t = 0; t < 6; t++) {
            for (int f = 0; f < 3; f++) {
                input.newFrame();
                scenes.update(1.0 / 60.0, input);
                scenes.render(Java2DTarget.unsized(g), 0f);
            }
            for (int gy = 130; gy < 620; gy += 36) {
                for (int gx = 60; gx < 1000; gx += 110) {
                    click(input, gx, gy);              // open a card if one lives here
                    scenes.update(1.0 / 60.0, input);
                    scenes.render(Java2DTarget.unsized(g), 0f);
                    click(input, gx, gy);              // dismiss whatever opened
                    scenes.update(1.0 / 60.0, input);
                    scenes.render(Java2DTarget.unsized(g), 0f);
                }
            }
            key(input, KeyEvent.VK_RIGHT);             // next tab
            scenes.update(1.0 / 60.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }

        assertNotNull(scenes.current());
        assertEquals("AutoBattlerGuideScene", scenes.current().name());

        // Esc with no card open leaves the guide for the lobby.
        key(input, KeyEvent.VK_ESCAPE);
        scenes.update(1.0 / 60.0, input);
        scenes.render(Java2DTarget.unsized(g), 0f);
        for (int i = 0; i < 80 && !"AutoBattlerLobbyScene".equals(scenes.current().name()); i++) {
            input.newFrame();
            scenes.update(1.0 / 30.0, input);
            scenes.render(Java2DTarget.unsized(g), 0f);
        }
        assertEquals("AutoBattlerLobbyScene", scenes.current().name());

        g.dispose();
    }

    /**
     * Register one synthetic left-click at (x, y) so the very next
     * {@code update} sees it: set the position + press latch, then promote it
     * with {@link InputManager#newFrame()} (the same order the real loop uses —
     * AWT events land, then the loop promotes them before updating).
     */
    private static void click(InputManager input, int x, int y) {
        input.mouseMoved(mouse(x, y, MouseEvent.MOUSE_MOVED));
        input.mousePressed(mouse(x, y, MouseEvent.MOUSE_PRESSED));
        input.newFrame();
    }

    private static void key(InputManager input, int code) {
        input.keyPressed(new KeyEvent(SRC, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
        input.newFrame(); // promote the press so the next update sees it
        // Release so a later press of the same key re-triggers the rising edge.
        input.keyReleased(new KeyEvent(SRC, KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED));
    }

    private static MouseEvent mouse(int x, int y, int id) {
        return new MouseEvent(SRC, id, System.currentTimeMillis(), 0, x, y, 1, false,
                MouseEvent.BUTTON1);
    }
}
