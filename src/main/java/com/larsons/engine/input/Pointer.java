package com.larsons.engine.input;

/**
 * The mouse pointer itself — whether it is drawn, and whether the game may put
 * it back where it wants it.
 *
 * <p><b>Why a scene cannot simply do this.</b> Hiding a cursor and moving it
 * are properties of a <em>window</em>, and a scene has no window: the engine
 * draws through an AWT canvas on one path and a GLFW window on another, and the
 * only thing a scene is handed is a {@code DrawTarget}. So the window installs
 * a {@link Handler} here at start-up and scenes ask this class, which is a
 * no-op when nothing was installed — a headless test, a dedicated server, a
 * backend that cannot do it.
 *
 * <p><b>What it is for.</b> A first-person view steers with the mouse, and a
 * mouse that is also a visible arrow sliding across the world is two pointers
 * at once: the crosshair the game aims with and the arrow the desktop drew.
 * Worse, the arrow runs out of window. Every 3D game answers both the same way
 * — hide the cursor and hold it in the middle of the window, reading the motion
 * rather than the position — and {@link #warpTo} is the half of that the engine
 * could not express before. Where a backend cannot warp ({@link #canWarp()} is
 * false) the caller keeps whatever it did before; the engine's look control
 * still works from raw motion, it just cannot promise the pointer never reaches
 * the edge.
 */
public final class Pointer {

    /** What a window has to be able to do for this class to mean anything. */
    public interface Handler {
        /** Show or hide the pointer over the game's own surface. */
        void setPointerVisible(boolean visible);

        /**
         * Put the pointer at ({@code x}, {@code y}) in viewport pixels.
         *
         * @return whether it moved — {@code false} when this window cannot warp
         *         the pointer at all, which is a fact about the platform and
         *         not a failure to report
         */
        default boolean warpPointer(int x, int y) { return false; }

        /** Whether {@link #warpPointer} does anything on this window. */
        default boolean canWarpPointer() { return false; }
    }

    private static Handler handler;
    private static boolean visible = true;

    private Pointer() {}

    /** Hand the pointer to a window. {@code null} detaches it. */
    public static synchronized void install(Handler h) {
        handler = h;
        visible = true;
    }

    /** Whether the pointer is currently drawn. */
    public static synchronized boolean visible() { return visible; }

    /**
     * Show or hide the pointer. Idempotent — a scene calls this every frame
     * while a view is up, and only a change reaches the window.
     */
    public static synchronized void setVisible(boolean show) {
        if (show == visible) return;
        visible = show;
        if (handler != null) handler.setPointerVisible(show);
    }

    /** Whether this window can put the pointer where the game asks. */
    public static synchronized boolean canWarp() {
        return handler != null && handler.canWarpPointer();
    }

    /** Put the pointer at ({@code x}, {@code y}) in viewport pixels. */
    public static synchronized boolean warpTo(int x, int y) {
        return handler != null && handler.warpPointer(x, y);
    }

    /**
     * Put the pointer back the way the desktop expects it — visible. Called on
     * the way out of anything that hid it, and safe to call twice.
     */
    public static synchronized void restore() {
        setVisible(true);
    }
}
