package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.input.InputManager;
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
import java.awt.image.BufferedImage;
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

    private static void renderOnce(ConfigForm form) {
        BufferedImage img = new BufferedImage(960, 540, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        form.render(g, 960, 540);
        g.dispose();
    }
}
