package com.larsons.engine.demo;

import com.larsons.engine.graphics.MiniGameSprites;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.minigame.StandaloneGame;
import com.larsons.engine.ui.SpriteButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mini games' corner buttons: one per game, drawn from a three-state
 * sheet, opened by a click that lands where it started.
 *
 * <p>The three states are the point. A picture button with one frame cannot say
 * that the pointer is on it or that a press registered — both of which a text
 * menu says with its caret and its highlight — so a sheet that does not differ
 * between them is a button nobody can tell they are pressing.
 */
class MiniGameButtonsTest {

    private static final int W = 960, H = 540;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static InputManager pointerAt(double x, double y, boolean down) {
        InputManager input = new InputManager();
        input.moveMouse((int) x, (int) y);
        if (down) input.pressMouse(MouseEvent.BUTTON1, 0);
        input.newFrame();
        return input;
    }

    @Test
    void thereIsOneButtonPerMiniGameAndTheyAreInTheCorner() {
        MiniGameButtons strip = new MiniGameButtons(g -> { });
        strip.layout(W, H);

        assertEquals(StandaloneGame.all().size(), strip.buttons().size());
        for (StandaloneGame game : StandaloneGame.all()) {
            SpriteButton button = strip.button(game);
            assertNotNull(button, game + " has a button");
            Rectangle b = button.bounds();
            assertTrue(b.width > 0 && b.height > 0, game + " has a size");
            assertTrue(b.x + b.width <= W && b.y + b.height <= H,
                    game + " is inside the window");
            assertTrue(b.x > W / 2 && b.y > H / 2,
                    game + " is in the bottom-right corner, not in the menu's way");
        }
    }

    /** A press and a release inside opens the game; the states change on the way. */
    @Test
    void aClickOpensTheGameAndTheButtonShowsItsThreeStates() {
        List<StandaloneGame> opened = new ArrayList<>();
        MiniGameButtons strip = new MiniGameButtons(opened::add);
        strip.layout(W, H);
        SpriteButton button = strip.button(StandaloneGame.AUTO_BATTLER);
        Rectangle b = button.bounds();

        assertEquals(MiniGameSprites.State.STATIC, button.state(), "at rest");

        button.update(pointerAt(b.getCenterX(), b.getCenterY(), false));
        assertEquals(MiniGameSprites.State.HOVER, button.state(), "pointed at");
        assertTrue(opened.isEmpty(), "hovering opens nothing");

        button.update(pointerAt(b.getCenterX(), b.getCenterY(), true));
        assertEquals(MiniGameSprites.State.PRESSED, button.state(), "held down");
        assertTrue(opened.isEmpty(), "a press alone opens nothing — it is the release that does");

        assertTrue(button.update(pointerAt(b.getCenterX(), b.getCenterY(), false)),
                "letting go inside fires it");
        assertEquals(List.of(StandaloneGame.AUTO_BATTLER), opened);
    }

    /** A press that wanders off the button is a press the player took back. */
    @Test
    void lettingGoOutsideTheButtonCancelsThePress() {
        List<StandaloneGame> opened = new ArrayList<>();
        MiniGameButtons strip = new MiniGameButtons(opened::add);
        strip.layout(W, H);
        SpriteButton button = strip.button(StandaloneGame.EVOLUTION);
        Rectangle b = button.bounds();

        button.update(pointerAt(b.getCenterX(), b.getCenterY(), true));
        assertFalse(button.update(pointerAt(4, 4, false)), "released somewhere else");
        assertTrue(opened.isEmpty(), "so nothing opened");
        assertEquals(MiniGameSprites.State.STATIC, button.state());
    }

    /** Each state is a different picture, and the sheet holds all three. */
    @Test
    void theThreeStatesAreThreeDifferentFrames() {
        for (StandaloneGame game : StandaloneGame.all()) {
            BufferedImage stat = MiniGameSprites.button(game, MiniGameSprites.State.STATIC, 64);
            BufferedImage hover = MiniGameSprites.button(game, MiniGameSprites.State.HOVER, 64);
            BufferedImage down = MiniGameSprites.button(game, MiniGameSprites.State.PRESSED, 64);
            assertNotSame(stat, hover, game + ": hover is its own frame");
            assertNotSame(hover, down, game + ": pressed is its own frame");
            assertTrue(differs(stat, hover), game + ": hover looks different");
            assertTrue(differs(hover, down), game + ": pressed looks different");

            BufferedImage sheet = MiniGameSprites.sheet(game, 64);
            assertEquals(64 * StandaloneGame.BUTTON_STATES, sheet.getWidth(),
                    game + ": the sheet is one frame per state, left to right");
            assertEquals(64, sheet.getHeight());
        }
    }

    /** The strip draws its buttons — one image each — and nothing else moves. */
    @Test
    void theStripDrawsOneImagePerButton() {
        MiniGameButtons strip = new MiniGameButtons(g -> { });
        RecordingTarget target = new RecordingTarget(W, H);
        strip.render(target, W, H);
        long images = target.ofType(RecordingTarget.Cmd.Image.class).size();
        assertEquals(StandaloneGame.all().size(), images, "one face per button");
    }

    private static boolean differs(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y += 3) {
            for (int x = 0; x < a.getWidth(); x += 3) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return true;
            }
        }
        return false;
    }
}
