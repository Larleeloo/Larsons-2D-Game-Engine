package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the game-type feature configuration: JSON read/write, profile
 * persistence under a store directory, and the {@link ConfigForm} widget's
 * keyboard/mouse interaction. All headless.
 */
class ConfigFeatureTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void jsonStringifyRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "a\"b");          // exercises string escaping
        m.put("count", 3);
        m.put("ratio", 0.5);
        m.put("on", true);
        m.put("list", List.of(1, 2, 3));

        String json = Json.stringify(m);
        Map<String, Object> back = Json.asObject(Json.parse(json));
        assertEquals("a\"b", back.get("name"));
        assertEquals(3.0, (Double) back.get("count"));
        assertEquals(0.5, (Double) back.get("ratio"));
        assertEquals(Boolean.TRUE, back.get("on"));
        assertEquals(3, Json.asArray(back.get("list")).size());
    }

    @Test
    void gameProfileJsonRoundTrips() {
        GameProfile p = new GameProfile("My Type");
        p.perspective = Perspective.ISOMETRIC;
        p.perspectiveSwitchingEnabled = true;
        p.zoomEnabled = false;
        p.maxZoom = 3.5;
        p.maxFps = 144;
        p.playerSize = 48;

        GameProfile q = GameProfile.fromJson(p.toJson());
        assertEquals("My Type", q.name);
        assertEquals(Perspective.ISOMETRIC, q.perspective);
        assertTrue(q.perspectiveSwitchingEnabled);
        assertFalse(q.zoomEnabled);
        assertEquals(3.5, q.maxZoom);
        assertEquals(144, q.maxFps);
        assertEquals(48, q.playerSize);
    }

    @Test
    void gameTypeStoreSavesLoadsAndLists(@TempDir Path dir) {
        GameTypeStore store = new GameTypeStore(dir.toString());
        assertTrue(store.listProfiles().isEmpty());

        GameProfile p = new GameProfile("Cool Game!");
        p.maxFps = 90;
        store.save(p);

        assertTrue(store.exists("Cool Game!"));
        assertTrue(Files.exists(dir.resolve("cool_game.json")), "filename should be sanitized");
        assertEquals(1, store.listProfiles().size());
        assertEquals(90, store.load("Cool Game!").maxFps);
    }

    @Test
    void deleteGameTypeRemovesProfileAndLevels(@TempDir Path dir) throws IOException {
        // A game type is a profile file plus a levels folder holding levels,
        // doors, and custom content. Deleting the type must remove both — this
        // is what MainMenuScene's "Delete Game Type" confirmation carries out.
        GameTypeStore store = new GameTypeStore(dir.resolve("gametypes").toString());
        store.save(new GameProfile("My Game"));
        assertTrue(store.exists("My Game"));

        LevelStore levels = new LevelStore(dir.resolve("levels").toString(), "My Game");
        Files.createDirectories(levels.directory());
        Files.writeString(levels.directory().resolve("level1.json"), "{}");
        Files.writeString(levels.directory().resolve("doors.json"), "{}");
        Files.writeString(levels.directory().resolve("custom.json"), "{}");
        assertTrue(Files.isDirectory(levels.directory()));

        assertTrue(levels.deleteGameTypeFolder(), "levels folder existed and was removed");
        assertFalse(Files.exists(levels.directory()), "levels folder should be gone");

        assertTrue(store.delete("My Game"), "profile existed and was removed");
        assertFalse(store.exists("My Game"), "profile should be gone");

        // Idempotent: deleting an already-gone type is a harmless no-op.
        assertFalse(levels.deleteGameTypeFolder());
        assertFalse(store.delete("My Game"));
    }

    @Test
    void configFormToggleActivatesViaEnter() {
        boolean[] flag = {false};
        ConfigForm form = new ConfigForm("T");
        form.addToggle("Feature", () -> flag[0], v -> flag[0] = v);

        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(form);

        // First option is selected by default; Enter activates it.
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_ENTER, '\n'));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        assertTrue(flag[0], "Enter should toggle the feature");
    }

    @Test
    void configFormToggleActivatesViaClick() {
        boolean[] flag = {false};
        ConfigForm form = new ConfigForm("T");
        ConfigForm.Option opt = form.addToggle("Feature", () -> flag[0], v -> flag[0] = v);

        renderOnce(form);
        Rectangle main = opt.mainBounds();
        int cx = main.x + main.width / 2;
        int cy = main.y + main.height / 2;

        InputManager input = new InputManager();
        Component src = new Canvas();
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, cx, cy, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, cx, cy, 1, false));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        assertTrue(flag[0], "Clicking the toggle should activate it");
    }

    @Test
    void configFormStepperAdjustsViaArrowKeys() {
        int[] val = {50};
        ConfigForm form = new ConfigForm("T");
        form.addInt("Num", () -> val[0], v -> val[0] = v, 0, 100, 5);

        renderOnce(form);
        InputManager input = new InputManager();
        Component src = new Canvas();
        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_RIGHT, (char) 0));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        assertEquals(55, val[0]);
    }

    @Test
    void disabledRowsAreNotActivated() {
        boolean[] toggleable = {true};
        int[] val = {10};
        ConfigForm form = new ConfigForm("T");
        form.addToggle("Master", () -> toggleable[0], v -> toggleable[0] = v);
        form.addInt("Dependent", () -> val[0], v -> val[0] = v, 0, 100, 5)
                .enabledWhen(() -> toggleable[0]);

        renderOnce(form);
        // Turn master off so the dependent row becomes disabled.
        toggleable[0] = false;

        InputManager input = new InputManager();
        Component src = new Canvas();
        ConfigForm.Option dependent = form.options().get(1);
        Rectangle inc = dependent.incBounds();
        int cx = inc.x + inc.width / 2;
        int cy = inc.y + inc.height / 2;
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, cx, cy, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, cx, cy, 1, false));
        input.newFrame();
        form.update(1.0 / 120.0, input);

        assertEquals(10, val[0], "disabled rows must ignore input");
    }

    @Test
    void longFormsScrollToKeepTheSelectionVisible() {
        ConfigForm form = new ConfigForm("T");
        int n = 30; // far more rows than fit in 540px
        int[] vals = new int[n];
        for (int i = 0; i < n; i++) {
            int k = i;
            form.addInt("Row " + i, () -> vals[k], v -> vals[k] = v, 0, 9, 1);
        }

        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(form);
        assertTrue(form.options().get(0).rowBounds().height > 0, "first row starts visible");
        assertEquals(0, form.options().get(n - 1).rowBounds().height, "last row starts hidden");

        for (int i = 0; i < n - 1; i++) {
            input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
            input.keyReleased(new KeyEvent(src, KeyEvent.KEY_RELEASED, 0L, 0, KeyEvent.VK_DOWN, (char) 0));
            input.newFrame();
            form.update(1.0 / 120.0, input);
        }
        renderOnce(form);

        Rectangle last = form.options().get(n - 1).rowBounds();
        assertEquals(n - 1, form.getSelectedIndex());
        assertTrue(last.height > 0, "selected row must be scrolled into view");
        assertTrue(last.y + last.height <= 540, "selected row must fit the viewport");
        assertEquals(0, form.options().get(0).rowBounds().height, "top rows scrolled off");
    }

    @Test
    void mouseWheelScrollsWithoutMovingSelection() {
        ConfigForm form = longForm(30);

        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(form); // establishes the scroll metrics the wheel clamps against
        assertTrue(form.options().get(0).rowBounds().height > 0, "first row starts visible");

        // Roll the wheel down a few notches.
        input.mouseWheelMoved(new MouseWheelEvent(src, MouseEvent.MOUSE_WHEEL, 0L, 0,
                10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 3));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        renderOnce(form);

        assertEquals(3, form.getScroll(), "wheel scrolls the view by its rotation");
        assertEquals(0, form.getSelectedIndex(), "wheel must not move the selection");
        assertEquals(0, form.options().get(0).rowBounds().height, "top row scrolled off");
    }

    @Test
    void draggingScrollBarThumbScrollsWithoutMovingSelection() {
        ConfigForm form = longForm(30);

        InputManager input = new InputManager();
        Component src = new Canvas();
        renderOnce(form);

        Rectangle thumb = form.scrollThumbBounds();
        assertTrue(thumb.height > 0, "overflowing form shows a scroll-bar thumb");
        int grabX = thumb.x + thumb.width / 2;
        int grabY = thumb.y + thumb.height / 2;

        // Press on the thumb to begin the drag.
        input.mouseMoved(new MouseEvent(src, MouseEvent.MOUSE_MOVED, 0L, 0, grabX, grabY, 0, false));
        input.mousePressed(new MouseEvent(src, MouseEvent.MOUSE_PRESSED, 0L, 0, grabX, grabY, 1, false));
        input.newFrame();
        form.update(1.0 / 120.0, input);

        // Drag to the bottom of the track (button still held) — the view follows.
        Rectangle track = form.scrollTrackBounds();
        int dragY = track.y + track.height;
        input.mouseDragged(new MouseEvent(src, MouseEvent.MOUSE_DRAGGED, 0L, 0, grabX, dragY, 0, false));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        renderOnce(form);

        assertTrue(form.getScroll() > 0, "dragging the thumb scrolls the view");
        assertEquals(0, form.getSelectedIndex(), "dragging the thumb must not move the selection");
        assertEquals(0, form.options().get(0).rowBounds().height, "top rows scrolled off");
        assertTrue(form.options().get(29).rowBounds().height > 0, "bottom row scrolled into view");
    }

    // --- typed characters belong to the tick they were typed in ------------------

    /**
     * Keystrokes aimed at the game must never turn up in a text field opened
     * later: panning a level with {@code WASD} used to pile characters into a
     * shared buffer, and the next name field to open swallowed the lot
     * ("wasawdsds") on its first frame.
     */
    @Test
    void keystrokesTypedBeforeAFieldOpenedDoNotLandInIt() {
        InputManager input = new InputManager();
        Component src = new Canvas();

        // Several ticks of moving around the level: no form, nothing consuming.
        for (char c : "wasawdsds".toCharArray()) {
            input.keyTyped(new KeyEvent(src, KeyEvent.KEY_TYPED, 0L, 0,
                    KeyEvent.VK_UNDEFINED, c));
            input.newFrame();
        }

        // Now a name field opens and gets its first update.
        String[] name = {""};
        ConfigForm form = new ConfigForm("New Block");
        form.addText("Name", () -> name[0], v -> name[0] = v, 32);
        renderOnce(form);
        input.newFrame();
        form.update(1.0 / 120.0, input);

        assertEquals("", name[0], "the field must open empty");
    }

    /** The fix must not stop a focused field from receiving real typing. */
    @Test
    void typingIntoTheSelectedFieldStillWorks() {
        InputManager input = new InputManager();
        Component src = new Canvas();
        String[] name = {""};
        ConfigForm form = new ConfigForm("New Block");
        form.addText("Name", () -> name[0], v -> name[0] = v, 32);
        renderOnce(form);

        for (char c : "Slate".toCharArray()) {
            input.keyTyped(new KeyEvent(src, KeyEvent.KEY_TYPED, 0L, 0,
                    KeyEvent.VK_UNDEFINED, c));
            input.newFrame();
            form.update(1.0 / 120.0, input);
        }
        assertEquals("Slate", name[0]);

        input.keyPressed(new KeyEvent(src, KeyEvent.KEY_PRESSED, 0L, 0,
                KeyEvent.VK_BACK_SPACE, '\b'));
        input.newFrame();
        form.update(1.0 / 120.0, input);
        assertEquals("Slat", name[0], "backspace still edits the field");
    }

    /** The underlying rule: unread typing expires at the next tick boundary. */
    @Test
    void typedCharactersExpireAfterTheirTick() {
        InputManager input = new InputManager();
        Component src = new Canvas();

        input.keyTyped(new KeyEvent(src, KeyEvent.KEY_TYPED, 0L, 0,
                KeyEvent.VK_UNDEFINED, 'x'));
        input.newFrame();
        assertEquals("x", input.consumeTypedChars(), "readable during its own tick");

        input.keyTyped(new KeyEvent(src, KeyEvent.KEY_TYPED, 0L, 0,
                KeyEvent.VK_UNDEFINED, 'y'));
        input.newFrame(); // 'y' becomes readable here…
        input.newFrame(); // …and is dropped here, unread
        assertEquals("", input.consumeTypedChars());
    }

    private static ConfigForm longForm(int n) {
        ConfigForm form = new ConfigForm("T");
        int[] vals = new int[n];
        for (int i = 0; i < n; i++) {
            int k = i;
            form.addInt("Row " + i, () -> vals[k], v -> vals[k] = v, 0, 9, 1);
        }
        return form;
    }

    private static void renderOnce(ConfigForm form) {
        BufferedImage img = new BufferedImage(960, 540, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        form.render(g, 960, 540);
        g.dispose();
    }
}
