package com.larsons.engine.gl;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lock that stops macOS resizing the drawable out from under a frame.
 *
 * <p><b>This exists because of a crash report, and the report is worth stating
 * in full.</b> Dragging the window's edge on an M1 Air killed the process:
 *
 * <pre>
 * Triggered by Thread: 23  Java: game-loop
 * Exception Type:  EXC_BAD_ACCESS (SIGABRT)
 * Exception Subtype: KERN_INVALID_ADDRESS at 0x0000000000000000
 *
 * Thread 23 Crashed:: Java: game-loop
 *   9  GLEngine        gleBlitFramebuffer + 632
 *  11  GLEngine        glBlitFramebufferEXT_Exec + 2096
 *  12  libGL.dylib     glBlitFramebuffer + 80
 *
 * Thread 0:: Dispatch queue: com.apple.main-thread
 *  14  AppKit          -[NSWindow(NSWindowResizing) _resizeWithEvent:] + 640
 *  15  AppKit          -[NSTitledFrame attemptResizeWithEvent:] + 156
 * </pre>
 *
 * <p>Two threads, one drawable. The render thread was inside
 * {@link GlSurface#blitToWindow} writing to framebuffer 0 — the window's back
 * buffer — while the main thread sat in AppKit's live-resize loop. GLFW's window
 * delegate answers {@code windowDidResize:} with {@code [nsgl.object update]},
 * which reallocates the drawable's storage, and the blit dereferenced the buffer
 * that was being replaced. Hence a null at a pointer the driver had every right
 * to expect.
 *
 * <p><b>Nothing in the engine's own code was wrong, which is why it took a crash
 * log to find.</b> The sizes were right, the framebuffer was complete, the
 * command was legal. The bug is that two threads touched one drawable without
 * agreeing whose turn it was, and that is invisible in any single-threaded read
 * of either side.
 *
 * <p><b>The fix is Apple's, not an invention.</b> {@code CGLLockContext} is the
 * mutex the platform's own OpenGL stack takes around drawable changes;
 * {@code NSOpenGLContext}'s methods — {@code update} included — acquire it
 * internally. So a render thread that holds it for the duration of a frame
 * cannot be interrupted by a resize: AppKit's update blocks on the main thread
 * until the frame is presented, and then proceeds. That is a few milliseconds of
 * a slower drag in exchange for not terminating the process, which is not a
 * close call.
 *
 * <p><b>Everywhere else this is nothing at all.</b> The lock only exists on
 * macOS. On Linux and Windows every method here returns immediately without so
 * much as loading LWJGL's {@code CGL} class — the class is named only inside
 * branches a non-Mac never takes, so its absence, or its natives being built for
 * another platform, can never be reached. And if anything about it does surprise
 * us on a Mac, the failure is caught once, reported once, and the backend
 * carries on unlocked: a renderer that draws with a known race is worse than one
 * without it and far better than one that will not start.
 *
 * <p><b>{@code -Dlarsons.render.gl.drawablelock=off|force}.</b> {@code off}
 * draws without the lock, which is how a Mac user can say whether a stall
 * during a drag is this. {@code force} runs the whole take-it-and-give-it-back
 * protocol against a counter instead of against CGL, on any platform — which is
 * the only way the balance can be checked on a machine that has no CGL to check
 * it with, and an unbalanced hold on a Mac is not a crash but a window that
 * freezes the first time it is resized. {@code GlResizeTest} is the caller.
 */
final class GlDrawableLock {

    /** {@code -Dlarsons.render.gl.drawablelock=off|force}. See the class note. */
    static final String PROPERTY = "larsons.render.gl.drawablelock";

    /**
     * The handle {@code force} mode hands out in place of a CGL context. Never
     * dereferenced by anything — the counting branch checks for it first — and
     * distinct from 0 so that "no drawable" and "the fake drawable" stay
     * different answers.
     */
    private static final long COUNTED = -1L;

    /** Outstanding holds under {@code force}. Zero between frames, or the protocol is wrong. */
    private static final AtomicInteger counted = new AtomicInteger();

    /** Set once, the first time anything here throws. Never tried again. */
    private static volatile boolean broken;

    private GlDrawableLock() {}

    /** Whether this platform has a drawable that a resize can move underneath us. */
    static boolean supported() {
        if (broken) return false;
        String mode = mode();
        if (mode.equals("off")) return false;
        if (mode.equals("force")) return true;
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * The CGL context current on this thread, or {@code 0} when there is none
     * and on every platform that has no such thing.
     *
     * <p>Must be called from the thread the GL context is current on, which is
     * the render thread — that is what "current" means to CGL as much as to GL.
     */
    static long current() {
        if (!supported()) return 0L;
        if (mode().equals("force")) return COUNTED;
        try {
            return org.lwjgl.opengl.CGL.CGLGetCurrentContext();
        } catch (Throwable t) {
            disable("could not read the CGL context", t);
            return 0L;
        }
    }

    /**
     * Take the lock. Recursive, as CGL's own is — a nested acquisition on the
     * same thread succeeds and needs a matching release, which is why callers
     * pair this with {@link #unlock} in a {@code finally} rather than trusting
     * a happy path.
     *
     * @return whether the lock was actually taken, and therefore whether the
     *         caller owes an {@link #unlock}
     */
    static boolean lock(long drawable) {
        if (drawable == 0L || broken) return false;
        if (drawable == COUNTED) {
            counted.incrementAndGet();
            return true;
        }
        try {
            org.lwjgl.opengl.CGL.CGLLockContext(drawable);
            return true;
        } catch (Throwable t) {
            disable("could not lock the drawable", t);
            return false;
        }
    }

    /** Release a lock this thread took. */
    static void unlock(long drawable) {
        if (drawable == 0L) return;
        if (drawable == COUNTED) {
            counted.decrementAndGet();
            return;
        }
        try {
            org.lwjgl.opengl.CGL.CGLUnlockContext(drawable);
        } catch (Throwable t) {
            // Deliberately not disabling on this one: a failed unlock leaves the
            // lock held, and switching the mechanism off afterwards would mean
            // never releasing it. Report, and let the next lock() decide.
            System.err.println("[gl] releasing the drawable lock failed: " + t);
        }
    }

    /** Holds taken and not yet given back under {@code force}. For the test. */
    static int outstandingHolds() { return counted.get(); }

    /** Forget any {@code force}-mode holds, so one test cannot fail the next. */
    static void resetHolds() { counted.set(0); }

    private static String mode() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null) return "";
        raw = raw.strip().toLowerCase();
        if (raw.equals("false") || raw.equals("0")) return "off";
        return raw;
    }

    private static void disable(String what, Throwable t) {
        if (broken) return;
        broken = true;
        System.err.println("[gl] " + what + " (" + t + "); frames will be drawn without "
                + "it. On macOS that leaves a live window resize able to move the "
                + "drawable mid-frame — see GlDrawableLock.");
    }
}
