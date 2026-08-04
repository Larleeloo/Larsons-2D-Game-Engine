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

    private MacGlLauncher() {}

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
