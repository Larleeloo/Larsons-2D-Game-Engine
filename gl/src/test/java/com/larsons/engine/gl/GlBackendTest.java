package com.larsons.engine.gl;

import com.larsons.engine.graphics.Backend;
import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.graphics.draw.DrawStats;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B9 from the end that has a driver behind it.
 *
 * <p>{@code BackendSelectionTest} in the core proves the decision is right for
 * every combination of flag and factory, using stubs, on a machine with no GPU.
 * It cannot prove the one thing that matters most: that this module really is
 * discoverable, that the thing it hands over really draws, and that a machine
 * which cannot give a context really does end up on Java2D with the driver's
 * own explanation attached. Those need the module and are therefore here.
 *
 * <p><b>The failure case is provoked rather than waited for.</b> A fallback
 * that has never been seen to engage is a comment. Asking for a core profile no
 * driver in the world implements takes the same code path as a laptop with no
 * GPU — context creation returns nothing, the factory reports why, and the
 * engine picks Java2D — and it takes it on a machine where GL otherwise works,
 * which is the only place the difference between "fell back" and "was never
 * offered anything" can be told apart.
 */
class GlBackendTest {

    private static final RendererFactory.Request REQUEST =
            new RendererFactory.Request(320, 200, "larsons-gl-test", new Color(24, 28, 38));

    // --- discovery --------------------------------------------------------------

    /**
     * The whole coupling between the two projects, checked: with this module on
     * the classpath the core's scan finds exactly one backend, called
     * {@code gl}. The core names nothing — see {@code ModuleBoundaryTest} —
     * so if the services file were missing this would find nothing and the GL
     * distribution would silently be the plain game.
     */
    @Test
    void theCoreFindsThisBackendOnTheClasspath() {
        List<String> ids = Backends.discover().stream().map(RendererFactory::id).toList();
        assertEquals(List.of(GlRendererFactory.ID), ids,
                "the core discovers backends by ServiceLoader; check "
                        + "gl/src/main/resources/META-INF/services/");
    }

    // --- the fallback, provoked --------------------------------------------------

    @Test
    void anImpossibleContextVersionIsRefusedRatherThanFaked() {
        Backend backend = withVersion("9.9", () -> new GlRendererFactory().create(REQUEST));

        assertFalse(backend.available(),
                "a GL 9.9 core context was reported as available, which no driver can give");
        assertFalse(backend.why().isBlank(), "a refusal has to carry a reason");
    }

    /** And the engine's answer to that refusal is Java2D, saying whose refusal it was. */
    @Test
    void theEngineFallsBackToJava2DAndSaysWhy() {
        BackendChoice choice = withVersion("9.9",
                () -> Backends.select("gl", Backends.discover(), REQUEST));

        assertTrue(choice.isJava2D(), "the engine kept a backend it could not create");
        assertEquals(Backends.JAVA2D, choice.chosen());
        assertTrue(choice.fellBack(),
                "-Dlarsons.render.backend=gl was not honoured; that is worth saying out loud");
        assertTrue(choice.why().contains("gl:"),
                "the reason has to name the backend that refused: " + choice.why());
        assertTrue(choice.describe().contains("java2d"), choice.describe());
    }

    /**
     * Forcing Java2D must not touch a driver at all — that flag exists for the
     * player whose driver is what is broken.
     */
    @Test
    void forcingJava2DSkipsTheProbeEvenHere() {
        BackendChoice choice = Backends.select("java2d", Backends.discover(), REQUEST);

        assertTrue(choice.isJava2D());
        assertFalse(choice.fellBack());
        assertTrue(choice.driver().isBlank());
    }

    // --- the real thing ----------------------------------------------------------

    /**
     * On a machine with a driver: {@code auto} picks this backend, the thing it
     * hands back draws a frame, and the report gets a driver string to print.
     *
     * <p>Skipped rather than failed where there is no GL, which is the same
     * rule the seven core GL tests and the six parity ones already follow — a
     * suite that fails on a build agent without a GPU stops being run.
     */
    @Test
    void autoSelectsGlAndTheBackendDrawsAFrame() {
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        assertEquals(GlRendererFactory.ID, choice.chosen());
        assertFalse(choice.driver().isBlank(),
                "the vendor/renderer/version string is what makes a player's profile readable");
        assertNotNull(choice.backend().window(), "a backend outside the core brings its own window");

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        String savedOffscreen = System.getProperty(GlRenderer.OFFSCREEN_PROPERTY);
        // Force A1's offscreen path on: with no shader chain attached the
        // renderer would correctly draw straight at the window, and this test
        // is here to check the surface the chain will read from.
        System.setProperty(GlRenderer.OFFSCREEN_PROPERTY, "always");
        try {
            renderer.window().attachInput(new InputManager());

            DrawTarget target = renderer.beginFrame();
            assertEquals(REQUEST.width(), target.width());
            assertEquals(REQUEST.height(), target.height());
            target.fillRect(10, 10, 100, 50, Color.RED);
            target.drawText("gl", 12, 40, null, Color.WHITE);

            // Flush and look at the pixels. Since A1 the frame is drawn into
            // the renderer's offscreen surface and blitted to the window on
            // present, so this reads the surface — which is also the texture
            // A2's shader chain will sample, making this the check that the
            // scene really does end up somewhere a shader could read it.
            //
            // Two samples: one inside the rectangle and one outside it, because
            // a readback of a uniformly red screen would pass just as well if
            // the clear had been red and nothing else had happened.
            ((GlTarget) target).endFrame();
            int[] pixels = renderer.surface().readPixels();
            int deviceW = renderer.deviceWidth(), deviceH = renderer.deviceHeight();
            double scale = deviceW / (double) REQUEST.width();
            assertEquals(0xFFFF0000, at(pixels, deviceW, deviceH, 60, 35, scale),
                    "the rectangle did not reach the surface the scene renders into");
            assertEquals(0xFF181C26, at(pixels, deviceW, deviceH, 300, 190, scale),
                    "the frame was not cleared to the background colour");

            renderer.present();
            assertTrue(renderer.sceneTexture() != 0,
                    "A1: the scene has to be readable as a texture for A2 to sample it");

            DrawStats stats = target.stats();
            assertTrue(stats.operations() >= 2, "the frame recorded nothing: " + stats.operations());
            assertTrue(((GlTarget) target).drawCalls() >= 1,
                    "a frame with two operations in it issued no draw call");

            // And a second frame, because a backend that works once and leaves
            // state behind looks identical to one that works.
            renderer.beginFrame();
            renderer.present();
        } finally {
            // The order the engine tears down in: the renderer's GL objects,
            // then the window that owns the context they lived in.
            renderer.dispose();
            choice.backend().window().close();
            if (savedOffscreen == null) System.clearProperty(GlRenderer.OFFSCREEN_PROPERTY);
            else System.setProperty(GlRenderer.OFFSCREEN_PROPERTY, savedOffscreen);
        }
    }

    // --- input translation, which needs no driver --------------------------------

    /**
     * The key map is a pure function and is checked as one. Its failure mode is
     * not a crash — it is a player finding that one key does nothing on one
     * backend, which no rendering test would ever notice.
     */
    @Test
    void glfwKeysArriveAsTheCodesEveryKeyBindWasSavedAs() {
        assertEquals(KeyEvent.VK_W, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W));
        assertEquals(KeyEvent.VK_7, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_7));
        assertEquals(KeyEvent.VK_F3, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_F3));
        assertEquals(KeyEvent.VK_ESCAPE, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE));
        assertEquals(KeyEvent.VK_NUMPAD4, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_KP_4));
        // The four whose numbering does not coincide with AWT's.
        assertEquals(KeyEvent.VK_QUOTE, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE));
        assertEquals(KeyEvent.VK_BACK_QUOTE, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT));
        assertEquals(KeyEvent.VK_BACK_SPACE, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE));
        assertEquals(KeyEvent.VK_ENTER, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER));
        // Both halves of a modifier are the one code every bind was recorded as.
        assertEquals(KeyEvent.VK_SHIFT, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(KeyEvent.VK_SHIFT, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT));
        // And an unmapped key is undefined rather than a wrong guess.
        assertEquals(KeyEvent.VK_UNDEFINED, GlKeys.awtKey(org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1));
    }

    @Test
    void theMiddleAndRightMouseButtonsAreNotSwapped() {
        assertEquals(MouseEvent.BUTTON1, GlKeys.awtButton(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertEquals(MouseEvent.BUTTON2, GlKeys.awtButton(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
        assertEquals(MouseEvent.BUTTON3, GlKeys.awtButton(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT));
    }

    @Test
    void scrollingAwayFromTheUserIsNegativeLikeAwtSaysItIs() {
        assertEquals(-1, GlKeys.wheelNotches(1.0));
        assertEquals(1, GlKeys.wheelNotches(-1.0));
        assertEquals(0, GlKeys.wheelNotches(0.0));
        // A trackpad's fractional scroll still moves by a whole notch.
        assertEquals(-1, GlKeys.wheelNotches(0.2));
    }

    /** A modifier never counts as a modifier of itself; that is how it stays bindable. */
    @Test
    void aModifierPressedAloneIsTheKeyNotTheModifier() {
        int shiftHeld = org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
        assertEquals(0, GlKeys.modifiers(shiftHeld, KeyEvent.VK_SHIFT));
        assertEquals(com.larsons.engine.input.InputBinding.SHIFT,
                GlKeys.modifiers(shiftHeld, KeyEvent.VK_S));
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * One logical pixel of a device-resolution readback, addressed the way the
     * engine addresses it — from the top-left, and in logical coordinates
     * whatever the display scale happens to be.
     */
    private static int at(int[] pixels, int deviceWidth, int deviceHeight,
                          int x, int y, double scale) {
        int dx = Math.min(deviceWidth - 1, (int) (x * scale));
        int dy = Math.min(deviceHeight - 1, (int) (y * scale));
        return pixels[dy * deviceWidth + dx];
    }

    /** Run {@code body} with {@code larsons.render.gl.version} set, then restore it. */
    private static <T> T withVersion(String version, java.util.function.Supplier<T> body) {
        String previous = System.getProperty(GlRendererFactory.VERSION_PROPERTY);
        System.setProperty(GlRendererFactory.VERSION_PROPERTY, version);
        try {
            return body.get();
        } finally {
            if (previous == null) System.clearProperty(GlRendererFactory.VERSION_PROPERTY);
            else System.setProperty(GlRendererFactory.VERSION_PROPERTY, previous);
        }
    }
}
