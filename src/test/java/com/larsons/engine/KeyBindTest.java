package com.larsons.engine;

import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputBinding;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.KeyBindsScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.KeyBindForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.ui.MenuTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for custom key binds: what the engine ships bound, that any
 * key or mouse button can take an action's place, that a rebind survives being
 * saved and read back, and that the controls form actually captures a press.
 */
class KeyBindTest {

    private static final int W = 960, H = 720;
    private static final Component SOURCE = new Canvas(); // events need a source

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    @AfterEach
    void freshBinds() {
        // The active set is global, like the skins and sounds it sits beside;
        // no test may leave a rebind behind for the next one.
        KeyBinds.install(KeyBinds.defaults());
    }

    // --- defaults & lookup ---------------------------------------------------------

    @Test
    void shippedDefaultsCoverMovementAndTheMouse() {
        KeyBinds binds = KeyBinds.defaults();
        assertEquals(InputBinding.key(KeyEvent.VK_A), binds.binding(GameAction.MOVE_LEFT, 0));
        assertEquals(InputBinding.key(KeyEvent.VK_LEFT), binds.binding(GameAction.MOVE_LEFT, 1));
        assertEquals(InputBinding.mouse(MouseEvent.BUTTON1), binds.binding(GameAction.ATTACK, 0));
        assertEquals(InputBinding.mouse(MouseEvent.BUTTON3), binds.binding(GameAction.PLACE, 0));
        assertEquals(InputBinding.key(KeyEvent.VK_Z, InputBinding.CTRL),
                binds.binding(GameAction.EDITOR_UNDO, 0));
        assertTrue(binds.isDefault());
    }

    @Test
    void everyActionHasAUniqueIdAndIsFoundByIt() {
        for (GameAction action : GameAction.values()) {
            assertSame(action, GameAction.byId(action.id()), action + " round-trips by id");
        }
    }

    // --- binding values ---------------------------------------------------------------

    @Test
    void bindingsRoundTripThroughTheirSavedToken() {
        InputBinding[] cases = {
                InputBinding.key(KeyEvent.VK_A),
                InputBinding.key(KeyEvent.VK_BACK_QUOTE),
                InputBinding.key(KeyEvent.VK_F12),
                InputBinding.key(KeyEvent.VK_S, InputBinding.CTRL | InputBinding.SHIFT),
                InputBinding.mouse(MouseEvent.BUTTON2),
                InputBinding.mouse(5),
        };
        for (InputBinding b : cases) {
            assertEquals(b, InputBinding.parse(b.token()), b.token());
        }
        assertFalse(InputBinding.parse("nonsense").isBound());
        assertFalse(InputBinding.parse("").isBound());
    }

    @Test
    void bindingsReadAsTheKeyOrButtonThePlayerPressed() {
        assertEquals("A", InputBinding.key(KeyEvent.VK_A).display());
        assertEquals("Ctrl+Z", InputBinding.key(KeyEvent.VK_Z, InputBinding.CTRL).display());
        assertEquals("Left Mouse", InputBinding.mouse(MouseEvent.BUTTON1).display());
        assertEquals("Mouse 4", InputBinding.mouse(4).display());
    }

    // --- input tracking ----------------------------------------------------------------

    @Test
    void keysOutsideTheOldArrayRangeAreTracked() {
        // F13 (0xF000) sat far outside the fixed 256-entry table the tracker
        // used to keep, so it could never have been bound to anything.
        InputManager input = new InputManager();
        input.keyPressed(key(KeyEvent.VK_F13, 0));
        input.newFrame();
        assertTrue(input.isKeyDown(KeyEvent.VK_F13));
        assertTrue(input.isKeyJustPressed(KeyEvent.VK_F13));
    }

    @Test
    void sideMouseButtonsAreTrackedLikeTheFamiliarThree() {
        InputManager input = new InputManager();
        input.mousePressed(mouse(4, 10, 10, 0));
        input.newFrame();
        assertTrue(input.isMouseButtonDown(4));
        assertTrue(input.isMouseButtonJustPressed(4));
        assertFalse(input.isMouseDown(), "button 4 is not the left button");
        input.mouseReleased(mouse(4, 10, 10, 0));
        assertFalse(input.isMouseButtonDown(4));
    }

    @Test
    void capturedPressCarriesTheModifiersHeldWithIt() {
        InputManager input = new InputManager();
        input.keyPressed(key(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK));
        input.keyPressed(key(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        input.newFrame();

        // The Ctrl press itself comes first, bindable on its own; then Ctrl+S.
        assertEquals(InputBinding.key(KeyEvent.VK_CONTROL), input.consumeAnyPress());
        assertEquals(InputBinding.key(KeyEvent.VK_S, InputBinding.CTRL), input.consumeAnyPress());
        assertNull(input.consumeAnyPress(), "the queue is scoped to the tick");
    }

    // --- rebinding drives gameplay -------------------------------------------------------

    @Test
    void anActionFollowsWhereverItIsBound() {
        KeyBinds binds = KeyBinds.defaults();
        binds.set(GameAction.JUMP, 0, InputBinding.mouse(4));
        binds.clear(GameAction.JUMP, 1);

        InputManager input = new InputManager();
        input.keyPressed(key(KeyEvent.VK_SPACE, 0));
        input.newFrame();
        assertFalse(binds.isJustPressed(input, GameAction.JUMP), "space no longer jumps");

        InputManager other = new InputManager();
        other.mousePressed(mouse(4, 0, 0, 0));
        other.newFrame();
        assertTrue(binds.isJustPressed(other, GameAction.JUMP), "the side button jumps now");
        assertTrue(binds.isDown(other, GameAction.JUMP));
    }

    @Test
    void modifiedBindingsOnlyFireWhileTheirModifierIsHeld() {
        KeyBinds binds = KeyBinds.defaults();

        InputManager plain = new InputManager();
        plain.keyPressed(key(KeyEvent.VK_Z, 0));
        plain.newFrame();
        assertFalse(binds.isJustPressed(plain, GameAction.EDITOR_UNDO), "Z alone is not undo");
        assertTrue(binds.isJustPressed(plain, GameAction.DASH), "Z alone is still dash");

        InputManager withCtrl = new InputManager();
        withCtrl.keyPressed(key(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK));
        withCtrl.keyPressed(key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        withCtrl.newFrame();
        assertTrue(binds.isJustPressed(withCtrl, GameAction.EDITOR_UNDO), "Ctrl+Z is undo");
    }

    @Test
    void menusFollowReboundNavigationKeys() {
        KeyBinds binds = KeyBinds.defaults();
        binds.set(GameAction.MENU_DOWN, 0, InputBinding.key(KeyEvent.VK_J));
        binds.clear(GameAction.MENU_DOWN, 1);
        KeyBinds.install(binds);

        Menu menu = new Menu("Title").theme(MenuTheme.dark());
        for (int i = 0; i < 4; i++) menu.add("Item " + i, () -> { });
        render(menu);

        InputManager input = new InputManager();
        input.keyPressed(key(KeyEvent.VK_J, 0));
        input.newFrame();
        menu.update(1 / 60.0, input);
        assertEquals(1, menu.getSelectedIndex(), "the rebound key moves the menu");
    }

    // --- conflicts ------------------------------------------------------------------------

    @Test
    void conflictsAreReportedInsideAGroupButNotAcrossThem() {
        KeyBinds binds = KeyBinds.defaults();
        assertFalse(binds.hasConflict(GameAction.ATTACK),
                "attack and the editor's paint share a button in different contexts");

        binds.set(GameAction.JUMP, 0, InputBinding.key(KeyEvent.VK_A));
        assertTrue(binds.hasConflict(GameAction.JUMP));
        assertTrue(binds.conflicts(GameAction.JUMP).contains(GameAction.MOVE_LEFT));
        assertTrue(binds.allConflicts().contains(GameAction.MOVE_LEFT));
    }

    // --- persistence -------------------------------------------------------------------------

    @Test
    void bindsSurviveSavingAndLoading(@TempDir Path dir) {
        KeyBindStore store = new KeyBindStore(dir.toString());
        assertTrue(store.load().isDefault(), "no file yet means the shipped defaults");

        KeyBinds binds = KeyBinds.defaults();
        binds.set(GameAction.JUMP, 0, InputBinding.mouse(5));
        binds.set(GameAction.EDITOR_SAVE, 0,
                InputBinding.key(KeyEvent.VK_F5, InputBinding.SHIFT));
        binds.clear(GameAction.ULTIMATE, 0); // deliberately left with no key
        store.save(binds);

        KeyBinds loaded = store.load();
        assertEquals(InputBinding.mouse(5), loaded.binding(GameAction.JUMP, 0));
        assertEquals(InputBinding.key(KeyEvent.VK_F5, InputBinding.SHIFT),
                loaded.binding(GameAction.EDITOR_SAVE, 0));
        assertTrue(loaded.bindings(GameAction.ULTIMATE).isEmpty(),
                "an action left unbound stays unbound");
        assertEquals(InputBinding.key(KeyEvent.VK_A), loaded.binding(GameAction.MOVE_LEFT, 0));
    }

    @Test
    void actionsMissingFromAnOlderFileKeepTheirDefaults() {
        KeyBinds loaded = KeyBinds.fromMap(com.larsons.engine.util.Json.asObject(
                com.larsons.engine.util.Json.parse(
                        "{\"version\":1,\"binds\":{\"jump\":[\"key:F\"],\"nonsense\":[\"key:Q\"]}}")));
        assertEquals(InputBinding.key(KeyEvent.VK_F), loaded.binding(GameAction.JUMP, 0));
        assertEquals(InputBinding.key(KeyEvent.VK_E), loaded.binding(GameAction.INTERACT, 0),
                "an action the file never mentioned keeps its default");
    }

    @Test
    void resetPutsEverythingBack() {
        KeyBinds binds = KeyBinds.defaults();
        binds.set(GameAction.JUMP, 0, InputBinding.key(KeyEvent.VK_K));
        assertFalse(binds.isDefault());
        binds.resetAll();
        assertTrue(binds.isDefault());
    }

    // --- the controls form ----------------------------------------------------------------

    @Test
    void clickingASlotCapturesTheNextPress() {
        KeyBinds binds = KeyBinds.defaults();
        int[] saves = {0};
        ConfigForm form = KeyBindForm.build("Controls", binds, () -> saves[0]++, null);
        Rectangle slot = firstSlotBounds(form, GameAction.MOVE_LEFT);

        // Click the primary slot: the form starts listening.
        InputManager click = new InputManager();
        click.mouseMoved(mouse(0, slot.x + slot.width / 2, slot.y + slot.height / 2, 0));
        click.mousePressed(mouse(MouseEvent.BUTTON1,
                slot.x + slot.width / 2, slot.y + slot.height / 2, 0));
        click.newFrame();
        form.update(1 / 60.0, click);
        assertTrue(form.isCapturing(), "the slot is listening");
        assertEquals(GameAction.MOVE_LEFT, form.capturingAction());

        // The next press lands in the slot — including a mouse button.
        InputManager press = new InputManager();
        press.mousePressed(mouse(4, 0, 0, 0));
        press.newFrame();
        form.update(1 / 60.0, press);
        assertFalse(form.isCapturing());
        assertEquals(InputBinding.mouse(4), binds.binding(GameAction.MOVE_LEFT, 0));
        assertEquals(1, saves[0], "the change was reported once, for saving");
    }

    @Test
    void escapeLeavesACaptureWithoutChangingAnything() {
        KeyBinds binds = KeyBinds.defaults();
        ConfigForm form = KeyBindForm.build("Controls", binds, null, null);
        Rectangle slot = firstSlotBounds(form, GameAction.JUMP);

        InputManager click = new InputManager();
        click.mousePressed(mouse(MouseEvent.BUTTON1,
                slot.x + slot.width / 2, slot.y + slot.height / 2, 0));
        click.newFrame();
        form.update(1 / 60.0, click);
        assertTrue(form.isCapturing());

        InputManager esc = new InputManager();
        esc.keyPressed(key(KeyEvent.VK_ESCAPE, 0));
        esc.newFrame();
        form.update(1 / 60.0, esc);
        assertFalse(form.isCapturing());
        assertEquals(InputBinding.key(KeyEvent.VK_SPACE), binds.binding(GameAction.JUMP, 0));
    }

    @Test
    void rightClickingASlotEmptiesIt() {
        KeyBinds binds = KeyBinds.defaults();
        ConfigForm form = KeyBindForm.build("Controls", binds, null, null);
        Rectangle slot = firstSlotBounds(form, GameAction.MOVE_LEFT);

        InputManager input = new InputManager();
        input.mouseMoved(mouse(0, slot.x + slot.width / 2, slot.y + slot.height / 2, 0));
        input.mousePressed(mouse(MouseEvent.BUTTON3,
                slot.x + slot.width / 2, slot.y + slot.height / 2, 0));
        input.newFrame();
        form.update(1 / 60.0, input);

        assertFalse(form.isCapturing());
        assertFalse(binds.binding(GameAction.MOVE_LEFT, 0).isBound());
        assertEquals(InputBinding.key(KeyEvent.VK_LEFT), binds.binding(GameAction.MOVE_LEFT, 1),
                "the other slot is untouched");
    }

    @Test
    void theControlsFormListsEveryAction() {
        ConfigForm form = KeyBindForm.build("Controls", KeyBinds.defaults(), null, () -> { });
        long rows = form.options().stream()
                .filter(o -> o.control() == ConfigForm.Control.KEYBIND)
                .count();
        assertEquals(GameAction.values().length, rows);
    }

    // --- reaching the screen from the menus -------------------------------------------------

    @Test
    void theLaunchMenuOpensTheControlsScreenAndComesBack(@TempDir Path dir) {
        SceneManager scenes = new SceneManager();
        scenes.setViewport(W, H);
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.resolve("types").toString()));
        StartupScene startup = new StartupScene(ctx);
        KeyBindsScene controls = new KeyBindsScene(
                new KeyBindStore(dir.resolve("config").toString()));
        scenes.register("startup", startup);
        scenes.register(KeyBindsScene.NAME, controls);
        scenes.setScene("startup");

        MenuItem entry = startup.menu().items().stream()
                .filter(i -> i.text().startsWith("Controls"))
                .findFirst().orElseThrow(() -> new AssertionError("no controls entry"));
        entry.activate();
        pump(scenes);
        assertSame(controls, scenes.current(), "the controls screen opened");
        assertFalse(controls.form().options().isEmpty());

        // The back key hands the player to where they came from, saving the
        // binds on the way out.
        InputManager back = new InputManager();
        back.keyPressed(key(KeyEvent.VK_ESCAPE, 0));
        back.newFrame();
        scenes.update(1 / 60.0, back);
        pump(scenes);
        assertSame(startup, scenes.current(), "and hands the player back");
        assertTrue(java.nio.file.Files.exists(
                        dir.resolve("config").resolve(KeyBindStore.FILE_NAME)),
                "leaving the screen wrote the binds");
    }

    /** Run the scene manager long enough for a fade transition to finish. */
    private static void pump(SceneManager scenes) {
        InputManager input = new InputManager();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        for (int i = 0; i < 40 && scenes.isTransitioning(); i++) {
            input.newFrame();
            scenes.update(1 / 30.0, input);
            scenes.render(Java2DTarget.unsized(g), 1f);
        }
        g.dispose();
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Render the form and hand back the primary slot's hit box for
     * {@code action} — the layout is captured during render, exactly as it is
     * for every other row.
     */
    private static Rectangle firstSlotBounds(ConfigForm form, GameAction action) {
        // The form is taller than the viewport; scroll until the row shows.
        for (int attempt = 0; attempt < 40; attempt++) {
            render(form);
            for (ConfigForm.Option o : form.options()) {
                if (!o.label().equals(action.label())) continue;
                Rectangle slot = o.keyBindSlotBounds(0);
                if (slot.width > 0) return slot;
            }
            scrollDown(form);
        }
        throw new AssertionError("no visible slot for " + action);
    }

    private static void scrollDown(ConfigForm form) {
        InputManager input = new InputManager();
        input.mouseWheelMoved(new java.awt.event.MouseWheelEvent(SOURCE,
                java.awt.event.MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                0, 0, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 3));
        input.newFrame();
        form.update(1 / 60.0, input);
    }

    private static void render(ConfigForm form) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        form.render(new Java2DTarget(g, W, H), W, H);
        g.dispose();
    }

    private static void render(Menu menu) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        menu.render(new Java2DTarget(g, W, H), W, H);
        g.dispose();
    }

    private static KeyEvent key(int code, int modifiers) {
        return new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    /**
     * A mouse event for any button. AWT's own constructor refuses button
     * numbers above 3 while headless (it asks the toolkit whether extra buttons
     * are enabled), so a side-button event is reported through an override
     * instead — {@link InputManager} reads the button number and nothing else.
     */
    private static MouseEvent mouse(int button, int x, int y, int modifiers) {
        int id = button == 0 ? MouseEvent.MOUSE_MOVED : MouseEvent.MOUSE_PRESSED;
        if (button <= MouseEvent.BUTTON3) {
            return new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers,
                    x, y, 1, false, button);
        }
        return new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers,
                x, y, 1, false, MouseEvent.BUTTON1) {
            @Override public int getButton() { return button; }
        };
    }
}
