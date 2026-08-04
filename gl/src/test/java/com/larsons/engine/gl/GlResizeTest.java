package com.larsons.engine.gl;

import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.graphics.draw.DrawTarget;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL33C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL33C.glGetError;

/**
 * Resizing the window, which is how the GL backend died on a real machine.
 *
 * <p><b>The bug this stands guard over cannot be reproduced here, and that is
 * worth saying before the assertions rather than after.</b> The crash in
 * {@link GlDrawableLock}'s note is a race between AppKit reallocating a
 * drawable on the main thread and the render thread blitting into it, and it
 * needs a Mac, a mouse and a window edge. What <em>can</em> be checked anywhere
 * a driver exists is everything around it, and each of these was a way the
 * resize path could be wrong on its own:
 *
 * <ul>
 *   <li><b>The renderer follows the window.</b> A frame after a resize is drawn
 *       at the new size, into a surface of the new size, and blitted with the
 *       new size. Getting one of those three from the previous frame is how a
 *       resize turns into a stretched or clipped picture, and it is also how a
 *       blit ends up writing outside the buffer it was given.</li>
 *   <li><b>A drag is many resizes, not one.</b> Dragging an edge delivers a size
 *       change per mouse move, each one reallocating the offscreen surface's two
 *       framebuffers, two renderbuffers and a texture. A sweep of sizes is the
 *       shape of the real event stream; a single resize would not catch a leak
 *       or a stale binding that only shows up on the second reallocation.</li>
 *   <li><b>The drawable is given back.</b> Every frame takes the platform's lock
 *       and must release it. On macOS an unbalanced hold is not a crash — it is
 *       a window that freezes the first time it is dragged, because AppKit's
 *       update blocks on a lock nobody will release. That failure would never
 *       show up as a red test on any other platform, so
 *       {@code drawablelock=force} runs the protocol against a counter here.</li>
 * </ul>
 *
 * <p>The GL suite skips itself where there is no driver, as the rest of this
 * module does. Under a software rasteriser — {@code xvfb-run ./gradlew :gl:test}
 * is enough — it runs in full.
 */
class GlResizeTest {

    private static final RendererFactory.Request REQUEST =
            new RendererFactory.Request(320, 200, "larsons-gl-resize", new Color(24, 28, 38));

    /** A rectangle the frame draws so the readback is looking at something. */
    private static final Color MARK = new Color(220, 40, 40);

    private final List<Runnable> restore = new ArrayList<>();

    @AfterEach
    void putPropertiesBack() {
        for (Runnable r : restore) r.run();
        restore.clear();
        GlDrawableLock.resetHolds();
    }

    /**
     * A window resized between frames: the next frame is the new size all the
     * way down, and the one after it still is.
     */
    @Test
    void aFrameAfterAResizeIsDrawnAtTheNewSize() {
        set(GlRenderer.OFFSCREEN_PROPERTY, "always");
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        try {
            renderer.window().show();
            frameAt(renderer, REQUEST.width(), REQUEST.height());
            resizeTo(renderer, 480, 300);
            frameAt(renderer, 480, 300);
            // Smaller as well as larger. A surface that only ever grows would
            // pass a widening drag and blit stale pixels on a narrowing one.
            resizeTo(renderer, 240, 160);
            frameAt(renderer, 240, 160);
        } finally {
            renderer.dispose();
            choice.backend().window().close();
        }
    }

    /**
     * The shape of a real drag: a size change per frame, twenty of them, each
     * one throwing away and rebuilding the offscreen surface.
     */
    @Test
    void aDragsWorthOfResizesLeavesTheDriverWithNothingToComplainAbout() {
        set(GlRenderer.OFFSCREEN_PROPERTY, "always");
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        try {
            renderer.window().show();
            for (int step = 0; step < 20; step++) {
                // Odd widths on purpose: a drawable whose size is not a multiple
                // of anything is the case a blit rectangle computed by rounding
                // gets wrong, and it is what a mouse actually produces.
                int w = 200 + step * 13;
                int h = 140 + step * 7;
                resizeTo(renderer, w, h);
                frameAt(renderer, w, h);
            }
        } finally {
            renderer.dispose();
            choice.backend().window().close();
        }
    }

    /**
     * Every frame gives the drawable back, including a frame whose drawing threw.
     *
     * <p>See the class note for why this is forced rather than left to the
     * platform: the hold is real only on macOS, and an unbalanced one there is a
     * frozen window rather than a failure anything would report.
     */
    @Test
    void everyFrameReleasesTheDrawableItTook() {
        set(GlRenderer.OFFSCREEN_PROPERTY, "always");
        set(GlDrawableLock.PROPERTY, "force");
        GlDrawableLock.resetHolds();

        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        try {
            DrawTarget target = renderer.beginFrame();
            assertEquals(1, GlDrawableLock.outstandingHolds(),
                    "a frame in flight is supposed to be holding the drawable");

            // A second beginFrame with no present between them — a caller
            // driving this by hand, or a frame abandoned after an exception.
            // It must not stack a second hold on the first, because only one
            // release is ever going to arrive.
            renderer.beginFrame();
            assertEquals(1, GlDrawableLock.outstandingHolds(),
                    "two beginFrame()s stacked two holds; the second release never comes");

            target.fillRect(4, 4, 20, 20, MARK);
            renderer.present();
            assertEquals(0, GlDrawableLock.outstandingHolds(),
                    "present() did not give the drawable back");

            // And through a frame that fails. present() runs from Engine.draw's
            // finally whatever the scene did, and the release has to survive
            // that route as well as the tidy one.
            renderer.beginFrame();
            try {
                throw new IllegalStateException("a scene that threw");
            } catch (IllegalStateException expected) {
                renderer.present();
            }
            assertEquals(0, GlDrawableLock.outstandingHolds(),
                    "a frame that threw kept the drawable; on macOS that is a window "
                            + "that never resizes again");
        } finally {
            renderer.dispose();
            choice.backend().window().close();
        }
    }

    /**
     * Off macOS the lock is not merely harmless, it is never reached — and the
     * switch that turns it off is honoured on the machine that has one.
     */
    @Test
    void theLockIsNothingAtAllOnAPlatformWithNoDrawableToLock() {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        assertEquals(mac, GlDrawableLock.supported(),
                "the drawable lock is a macOS arrangement and should be inert elsewhere");

        set(GlDrawableLock.PROPERTY, "off");
        assertFalse(GlDrawableLock.supported(), "-D" + GlDrawableLock.PROPERTY + "=off ignored");
        assertEquals(0L, GlDrawableLock.current());
        assertFalse(GlDrawableLock.lock(0L), "there is nothing to lock and nothing to release");
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Resize the window and let GLFW deliver it. The callbacks are what the
     * renderer reads its size from — it never asks the window directly, because
     * window queries belong to this thread and drawing does not.
     *
     * <p>The delivery is asynchronous on every platform this can run on: the
     * request goes to the display server and the answer comes back as an event,
     * so the poll is a loop with a deadline rather than a single call. A window
     * manager that refuses the size outright — some do, for a window it has
     * decided is too small — skips the test instead of failing it, because that
     * is a fact about the desktop rather than about this backend.
     */
    private static void resizeTo(GlRenderer renderer, int width, int height) {
        long handle = renderer.window().context().window();
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(handle, width, height);

        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            if (renderer.window().width() == width && renderer.window().height() == height) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assumeTrue(false,
                "this window manager did not honour the resize (" + renderer.window().width()
                        + "x" + renderer.window().height() + " after asking for "
                        + width + "x" + height + ")");
    }

    /**
     * Draw one frame and check it came out at the size the window is now, in
     * logical pixels for the scene and device pixels for the surface the blit
     * reads.
     */
    private static void frameAt(GlRenderer renderer, int width, int height) {
        DrawTarget target = renderer.beginFrame();
        assertEquals(width, target.width(), "the scene was handed the previous frame's width");
        assertEquals(height, target.height(), "the scene was handed the previous frame's height");
        assertEquals(width, renderer.getWidth());
        assertEquals(height, renderer.getHeight());

        target.fillRect(0, 0, width, height, MARK);
        ((GlTarget) target).endFrame();

        int deviceW = renderer.deviceWidth(), deviceH = renderer.deviceHeight();
        assertTrue(deviceW >= width && deviceH >= height,
                "the drawable (" + deviceW + "x" + deviceH + ") is smaller than the frame ("
                        + width + "x" + height + ")");

        // The surface really is the drawable's size — not the previous one, and
        // not a size derived from a scale that moved at a different moment.
        // readPixels reads the surface's own width and height, so its length is
        // the surface's own opinion of how big it is.
        int[] pixels = renderer.surface().readPixels();
        assertEquals(deviceW * deviceH, pixels.length,
                "the offscreen surface is still sized for a previous frame");
        assertEquals(0xFF000000 | MARK.getRGB() & 0xFFFFFF, pixels[deviceH / 2 * deviceW + deviceW / 2],
                "the frame did not fill the resized surface");

        renderer.present();
        assertEquals(GL_NO_ERROR, glGetError(),
                "the driver rejected something in a frame that followed a resize");
    }

    /** Set a system property for the length of one test. */
    private void set(String key, String value) {
        String previous = System.getProperty(key);
        restore.add(() -> {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        });
        System.setProperty(key, value);
    }
}
