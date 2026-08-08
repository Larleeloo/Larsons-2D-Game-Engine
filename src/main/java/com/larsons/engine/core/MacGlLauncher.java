package com.larsons.engine.core;

import com.larsons.engine.graphics.Backends;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * On macOS, relaunches the game on the process's first thread so a GPU backend
 * can open a window at all.
 *
 * <p><b>Why a shipped jar needs this and a Gradle run did not.</b> GLFW must
 * create its window and pump its events on thread 0 of the process. The JVM does
 * not run {@code main} there on macOS unless it is launched with
 * {@code -XstartOnFirstThread}, and a GL window created off it does not fail —
 * <em>it hangs</em>, with no window, no error and nothing in the log. The Gradle
 * launch tasks pass the flag, so every GL run during B8–B10 worked; the jar a
 * player double-clicks has a manifest, and a manifest cannot carry JVM
 * arguments. The GL distribution was therefore broken on macOS in exactly the
 * configuration nobody had run it in.
 *
 * <p><b>The flag cannot simply be set for everyone.</b> AWT wants thread 0 for
 * its own run loop, so a Java2D launch <em>with</em> the flag hangs the
 * {@code JFrame} the same way a GL launch without it hangs the GLFW window. It
 * is not a macOS setting; it is a per-backend one, and the backend is not known
 * until something has probed for a driver.
 *
 * <p><b>So the fallback is a second process, not a second attempt.</b>
 *
 * <ol>
 *   <li>The original process — no flag, so AWT would work — sees macOS, a GPU
 *       backend on the classpath, and a request that is not {@code java2d}. It
 *       spawns itself again with {@code -XstartOnFirstThread}, inherits the
 *       child's console, and waits.</li>
 *   <li>The child probes for a context. If it gets one, it runs the game and the
 *       parent exits with whatever the child exits with — one window, one game,
 *       the extra process idle in a {@code waitFor}.</li>
 *   <li>If the child cannot get a context it exits {@link #NO_GL_EXIT} <em>before
 *       touching AWT</em>, and the parent runs the game itself, in a process
 *       that never had the flag and where Swing is therefore perfectly happy.</li>
 * </ol>
 *
 * <p>Falling back inside the child would mean opening a Swing window on a thread
 * AWT does not own, which is the failure this class exists to avoid, pointing
 * the other way.
 */
public final class MacGlLauncher {

    /**
     * What the relaunched child exits with when it could not get a GL context,
     * to tell the parent to run the game itself on Java2D. Chosen well outside
     * the range anything else here returns.
     */
    public static final int NO_GL_EXIT = 86;

    /** Set on the child, so a relaunch can never relaunch again. */
    public static final String CHILD_PROPERTY = "larsons.launch.relaunched";

    /** {@code -Dlarsons.launch.relaunch=false} turns the whole mechanism off. */
    public static final String ENABLED_PROPERTY = "larsons.launch.relaunch";

    /** AWT's own switch, set on the macOS GL path. See {@link #keepAwtOffTheFirstThread}. */
    public static final String HEADLESS_PROPERTY = "java.awt.headless";

    private MacGlLauncher() {}

    /**
     * Keep AWT from taking the first thread, on a macOS run that is going to
     * draw with a GPU backend.
     *
     * <p><b>This is not a tidiness measure; without it the window's close
     * button does nothing.</b> A crash report from the Air showed
     * {@code +[AWTStarter starter:headless:]} → {@code runAWTLoopWithApp:} →
     * {@code [NSApplication run]} sitting on thread 0 <em>underneath</em>
     * GLFW's {@code glfwPollEvents}. AWT's loop is {@code do { [app run]; }
     * while (YES)} — it never returns. So the first call to
     * {@code Engine.pumpUntilStopped()} entered {@code pumpEvents()} and stayed
     * there for the life of the process. Events still flowed, because AppKit
     * dispatches them to the GLFW window whoever is pumping, which is why the
     * game played normally and hid this completely: what stopped happening was
     * the <em>loop</em>. {@code closeRequested()} is never read again,
     * {@code larsons.run.seconds} never fires, and {@code shutdown()} is
     * unreachable. Clicking the red button sets a flag nobody looks at.
     *
     * <p>LWJGL's own guidance is exactly this: a JVM that uses AWT and GLFW
     * together on macOS must be headless. The engine uses AWT hard — every
     * sprite is a {@code BufferedImage}, terrain chunks bake through
     * {@code Graphics2D}, the glyph atlas rasterises real fonts — and all of
     * that is supported headless. What is not supported is opening a window,
     * which on this path is precisely what AWT must not do.
     *
     * <p><b>What it costs, stated rather than discovered later.</b> Two things
     * ask AWT for something a headless process has not got:
     *
     * <ul>
     *   <li>The three {@code JFileChooser} dialogs — importing a sprite sheet in
     *       the creative, skin and board editors. All three already catch
     *       {@code RuntimeException} and say so in the scene's status line, and
     *       {@code HeadlessException} is one, so they degrade to the typed path
     *       that has always been the fallback rather than breaking. That is the
     *       whole of the product cost, and it applies only to a GL run on
     *       macOS.</li>
     *   <li>{@code DeviceProfile}'s display size, refresh rate and scale, which
     *       would leave a frame report saying "headless / unknown" about a run
     *       on a real monitor. Not accepted: {@code BackendWindow.displayMode()}
     *       now answers it from GLFW, which knows the monitor better than AWT
     *       does anyway.</li>
     * </ul>
     *
     * <p>Called from {@link Main} before anything can touch AWT, and again as a
     * command-line flag on the relaunched child, because a property set after
     * the toolkit has initialised is ignored in silence.
     */
    public static boolean keepAwtOffTheFirstThread() {
        if (!isMac()) return false;
        if (System.getProperty(HEADLESS_PROPERTY) != null) return false;  // the operator decided
        if (Backends.JAVA2D.equals(Backends.requested())) return false;   // AWT is the renderer
        if (Backends.discover().isEmpty()) return false;                  // no GPU backend to run
        System.setProperty(HEADLESS_PROPERTY, "true");
        return true;
    }

    /**
     * Make AWT resolve its headless state here, while nothing has started a
     * Cocoa application yet.
     *
     * <p><b>Separate from the decision above on purpose.</b> This one is
     * irreversible — AWT caches the answer in a static the first time it is
     * asked, and nothing can un-ask it — so a test that exercised the decision
     * would pin the whole test JVM into headless mode as a side effect, and the
     * suite that shares that fork renders real frames through Java2D. The
     * decision is a pure function and is the part worth testing; this is the
     * part {@link Main} calls once, in a process that is about to be a game.
     *
     * <p><b>Setting the property is not the same as it having taken effect, and
     * the gap between the two prints this:</b>
     *
     * <pre>
     * [JRSAppKitAWT markAppIsDaemon]: Process manager already initialized:
     *     can't fully enable headless mode.
     * </pre>
     *
     * <p>AWT settles the property the first time anything asks it a question,
     * and on this path the first question arrives late — after the backend probe
     * has called {@code glfwInit}, which creates the {@code NSApplication} and
     * with it the process manager. AWT then finds the application already
     * standing, cannot mark the process as a daemon, and says so. Nothing is
     * broken by that — GLFW owns the application and is entitled to, and the
     * part that matters (AWT not starting a run loop of its own) still holds —
     * but a line on stderr that says "can't fully enable" is not something to
     * leave a player reading, and the fix is to ask the question earlier.
     *
     * <p><b>{@code GraphicsEnvironment.isHeadless()} was the question asked here
     * first, and it is the wrong one — which is why the warning survived the fix
     * that was supposed to remove it.</b> That call resolves and caches
     * {@code GraphicsEnvironment}'s own idea of headlessness, and it does so
     * entirely in Java: it reads the system property and returns. It never loads
     * the platform toolkit, so on macOS it never reaches
     * {@code +[AWTStarter starter:headless:]}, which is the code that calls
     * {@code markAppIsDaemon} and the only code that can print the line above.
     * Settling the property is therefore not the same as settling <em>AWT</em>,
     * and only the second one races GLFW.
     *
     * <p>{@link java.awt.Toolkit#getDefaultToolkit()} is the call that does it.
     * It is what forces {@code sun.lwawt.macosx.LWCToolkit} to load, and loading
     * it is what runs the starter — in headless mode, marking the process as a
     * daemon instead of standing up a run loop. Called here, before the backend
     * probe has had a chance to {@code glfwInit}, there is no {@code
     * NSApplication} yet, the mark succeeds, and nothing is printed. Called
     * implicitly later, by the first font metric or image the game asks for,
     * there is one, and the warning is AWT telling the truth about a race it
     * lost.
     *
     * <p>{@code isHeadless()} is still asked, first, because it also pins the
     * decision: a later change to the property cannot quietly move the engine to
     * a different mode halfway through a launch.
     */
    public static void settleHeadlessNow() {
        // Only when headless is actually in force. Asking the question at all
        // settles it, and settling it as "not headless" in the parent process
        // would start an AWT application there — a second Dock icon in front of
        // a game that has not launched yet.
        if (!Boolean.getBoolean(HEADLESS_PROPERTY)) return;
        try {
            java.awt.GraphicsEnvironment.isHeadless();
            // The one that actually loads the macOS toolkit, and so the one that
            // has to win the race against glfwInit. See the note above.
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable ignored) {
            // A JDK with no AWT at all is not a reason to fail to launch, and
            // the property is set either way. Neither is a toolkit that declines
            // to load: the game draws through Java2D's software pipeline, which
            // is what the headless mode this method exists to establish keeps
            // available.
        }
    }

    /**
     * Relaunch on the first thread if this run needs it and is not already
     * there.
     *
     * <p>Returns {@code true} when the game has already been played out in a
     * child process and the caller should return immediately; {@code false} when
     * this process should carry on and run the game itself — which covers every
     * non-Mac machine, every {@code java2d} request, every launch that already
     * has the flag, and the child's own report that there was no driver.
     */
    public static boolean relaunchIfNeeded(String[] args) {
        if (!needsRelaunch()) return false;

        List<String> command = command(args);
        try {
            Process child = new ProcessBuilder(command).inheritIO().start();
            int status = child.waitFor();
            if (status == NO_GL_EXIT) {
                // The child looked for a driver and found none. Run here
                // instead, in the process that never had the flag, and do not
                // let it waste time probing again.
                System.setProperty(Backends.PROPERTY, Backends.JAVA2D);
                return false;
            }
            System.exit(status);
            return true;
        } catch (Exception e) {
            // Could not spawn — a sandbox, a missing java binary, an odd
            // classpath. Never strand the player over it: carry on here, which
            // means Java2D, which is the floor and always works.
            System.err.println("[launch] could not relaunch on the first thread ("
                    + e + "); continuing on the Java2D backend");
            System.setProperty(Backends.PROPERTY, Backends.JAVA2D);
            return false;
        }
    }

    /**
     * Called by {@link Engine} the moment it has chosen Java2D, and before it
     * has touched AWT.
     *
     * <p>In the relaunched child that is the end of the road: this process was
     * started for the sole purpose of running GL on the first thread, and a
     * {@code JFrame} here would open on the thread AWT needs for its own run
     * loop. So it exits, and the parent — still waiting in {@code waitFor}, in a
     * process with no flag — runs the game properly.
     */
    static void abortIfThisProcessExistsOnlyForGl() {
        if (!isRelaunchedChild()) return;
        System.err.println("[launch] no GL context in the first-thread process; "
                + "handing back to the launcher for a Java2D run");
        System.exit(NO_GL_EXIT);
    }

    /** Whether this JVM is the child spawned by {@link #relaunchIfNeeded}. */
    static boolean isRelaunchedChild() {
        return Boolean.getBoolean(CHILD_PROPERTY);
    }

    // --- the decision ----------------------------------------------------------

    private static boolean needsRelaunch() {
        if (!isMac()) return false;
        if (isRelaunchedChild()) return false;
        if (onFirstThread()) return false;
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) return false;

        // Nothing to relaunch for if Java2D was asked for by name, or if this
        // classpath has no GPU backend on it at all — which is the plain jar,
        // and the overwhelmingly common case.
        String requested = Backends.requested();
        if (Backends.JAVA2D.equals(requested)) return false;
        return !Backends.discover().isEmpty();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Whether this JVM was launched with {@code -XstartOnFirstThread}.
     *
     * <p>Read from the environment variable the launcher exports rather than
     * from {@code RuntimeMXBean.getInputArguments()}: the variable is set by the
     * launcher itself and is keyed by pid, so it cannot be inherited from a
     * parent shell or spoofed by a stale export, and it is what GLFW's own
     * documentation tells callers to check.
     */
    private static boolean onFirstThread() {
        String marker = System.getenv("JAVA_STARTED_ON_FIRST_THREAD_"
                + ProcessHandle.current().pid());
        return "1".equals(marker);
    }

    // --- the command -----------------------------------------------------------

    private static List<String> command(String[] args) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-XstartOnFirstThread");
        command.add("-D" + HEADLESS_PROPERTY + "=true");
        command.add("-D" + CHILD_PROPERTY + "=true");
        command.addAll(inheritedJvmArguments());
        command.add("-cp");
        command.add(System.getProperty("java.class.path", "."));
        command.add(Main.class.getName());
        if (args != null) command.addAll(List.of(args));
        return command;
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> System.getProperty("java.home", ".")
                        + java.io.File.separator + "bin"
                        + java.io.File.separator + "java");
    }

    /**
     * The JVM arguments this process was given, minus the ones a second process
     * must not repeat.
     *
     * <p>A debugger agent is bound to a port and a second JVM asking for the
     * same one fails to start at all, which would turn "run under a debugger"
     * into "does not launch". The classpath is re-stated explicitly rather than
     * inherited, so any form of it here would be a duplicate.
     */
    private static List<String> inheritedJvmArguments() {
        List<String> kept = new ArrayList<>();
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (argument.startsWith("-agentlib:jdwp")
                        || argument.startsWith("-agentpath")
                        || argument.startsWith("-javaagent")
                        || argument.startsWith("-cp")
                        || argument.startsWith("-classpath")
                        || argument.startsWith("-XstartOnFirstThread")
                        || argument.startsWith("-D" + CHILD_PROPERTY)) {
                    continue;
                }
                kept.add(argument);
            }
        } catch (RuntimeException e) {
            // No management bean is not a reason to fail to launch; the
            // defaults are fine and the flags that matter are set above.
        }
        return kept;
    }
}
