package com.larsons.engine.core;

import com.larsons.engine.graphics.Backends;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lets the player pick a rendering backend when they launch the jar, and
 * remembers the answer.
 *
 * <p><b>The flag that already existed cannot be typed by the person who needs
 * it.</b> {@code -Dlarsons.render.backend=gl|java2d} is how the backend has been
 * chosen since B9, and it is exactly right for a developer, a profile run or a
 * bug report. It is unreachable for a player who double-clicks a jar: a manifest
 * cannot carry JVM arguments, which is the same wall {@link MacGlLauncher} was
 * built to get around. So the choice is offered instead of demanded.
 *
 * <p><b>Asked once, not every launch.</b> A prompt in front of a game every time
 * it starts is a worse experience than no choice at all, so the answer is
 * written to {@code config/render.json} beside the other things a player owns —
 * their key binds, their texture pack — and read back on the next launch without
 * a word. {@code -Dlarsons.render.chooser=always} brings it back for someone who
 * wants to change their mind, and {@code =never} suppresses it for a kiosk or a
 * scripted run.
 *
 * <p><b>Order matters, and it is the reason this is a class rather than three
 * lines in {@code Main}.</b> The chooser has to run:
 *
 * <ul>
 *   <li><b>before {@link MacGlLauncher#relaunchIfNeeded}</b>, because on macOS
 *       the answer decides whether a first-thread child process is spawned at
 *       all — asking afterwards would mean asking in the wrong process, or
 *       twice;</li>
 *   <li><b>before {@link MacGlLauncher#keepAwtOffTheFirstThread}</b>, because
 *       the dialog is Swing and a headless process has no dialogs. That
 *       ordering is safe in the one direction that matters: the process that
 *       asks is always the parent, which never goes headless and never carries
 *       {@code -XstartOnFirstThread}.</li>
 * </ul>
 *
 * <p><b>It never blocks a launch.</b> No display, a sandbox that forbids AWT, a
 * relaunched child, an explicit flag on the command line, a classpath with no
 * GPU backend on it at all — every one of those is a reason to skip the question
 * rather than a reason to stop, and a game that cannot start because it could
 * not ask which renderer to use would be a strictly worse program than one with
 * no choice in it.
 */
public final class BackendChooser {

    /** {@code -Dlarsons.render.chooser=always|never} — force or suppress the prompt. */
    public static final String PROPERTY = "larsons.render.chooser";

    /** Where the remembered answer lives, beside key binds and texture packs. */
    public static final String DEFAULT_DIR = "src/main/resources/config";

    public static final String FILE_NAME = "render.json";

    private final Path dir;

    public BackendChooser() {
        this(DEFAULT_DIR);
    }

    public BackendChooser(String dir) {
        this.dir = Path.of(dir);
    }

    /** The file the remembered choice is written to. */
    public Path file() {
        return dir.resolve(FILE_NAME);
    }

    // --- the decision ------------------------------------------------------------

    /**
     * Why the chooser is or is not going to ask this launch. Returned rather
     * than logged so the caller can say so, and so the decision is testable
     * without a display.
     */
    public enum Decision {
        /** Ask the player. */
        ASK,
        /** A remembered answer applies. */
        REMEMBERED,
        /** {@code -Dlarsons.render.backend} was given; it wins. */
        FLAG_GIVEN,
        /** {@code -Dlarsons.render.chooser=never}. */
        SUPPRESSED,
        /** This process is the first-thread child; the parent already asked. */
        RELAUNCHED_CHILD,
        /** There is only one backend on this classpath, so there is nothing to choose. */
        NO_CHOICE,
        /** Nothing here can show a dialog. */
        NO_DISPLAY
    }

    /**
     * What this launch should do, without doing any of it.
     *
     * <p>{@code always} outranks a remembered answer but not an explicit flag:
     * someone who typed the backend on the command line has already answered,
     * and a dialog that overrode them would make the flag untrustworthy.
     */
    public Decision decide() {
        String mode = mode();
        if (mode.equals("never")) return Decision.SUPPRESSED;
        if (System.getProperty(Backends.PROPERTY) != null
                && !System.getProperty(Backends.PROPERTY).isBlank()) {
            return Decision.FLAG_GIVEN;
        }
        if (Boolean.getBoolean(MacGlLauncher.CHILD_PROPERTY)) return Decision.RELAUNCHED_CHILD;
        // Nothing to choose between: the plain jar has only the Java2D path, and
        // offering a menu with one item on it is worse than offering none.
        if (Backends.discover().isEmpty()) return Decision.NO_CHOICE;
        if (mode.equals("always")) return Decision.ASK;
        if (remembered() != null) return Decision.REMEMBERED;
        if (headless()) return Decision.NO_DISPLAY;
        return Decision.ASK;
    }

    /**
     * Apply this launch's backend choice, asking the player if that is what
     * {@link #decide()} calls for.
     *
     * <p>Sets {@code larsons.render.backend} and returns what it set, or
     * {@code null} when the choice was left to the existing rules — which is not
     * a failure and is the common case after the first launch on a machine where
     * the player kept the default.
     */
    public String apply() {
        Decision decision = decide();
        switch (decision) {
            case REMEMBERED -> {
                String chosen = remembered();
                if (chosen != null) System.setProperty(Backends.PROPERTY, chosen);
                return chosen;
            }
            case ASK -> {
                String chosen = ask();
                if (chosen == null) return null;         // dismissed: leave the defaults alone
                System.setProperty(Backends.PROPERTY, chosen);
                remember(chosen);
                return chosen;
            }
            default -> {
                return null;
            }
        }
    }

    // --- the remembered answer ---------------------------------------------------

    /**
     * The backend chosen on a previous launch, or {@code null} if there is none
     * or the file says something this build does not recognise.
     *
     * <p>An unreadable or nonsense file is treated as "no answer yet" rather
     * than as an error: it is a preference, and a preference that cannot be read
     * must never be a reason a game will not start.
     */
    public String remembered() {
        try {
            Path path = file();
            if (!Files.exists(path)) return null;
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String value = valueOf(raw, "backend");
            if (value == null) return null;
            value = value.strip().toLowerCase();
            return isKnown(value) ? value : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Write the choice down for next time. Failure to do so is not worth a crash. */
    public void remember(String backend) {
        if (!isKnown(backend)) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(file(),
                    "{\n  \"backend\": \"" + backend + "\"\n}\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("[render] could not remember the backend choice ("
                    + e + "); it will be asked again next launch");
        }
    }

    /** Forget it, so the next launch asks again. */
    public void forget() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ignored) {
            // Then it is still remembered, which is not worth failing over.
        }
    }

    /** Whether this is a backend id the engine will accept. */
    public static boolean isKnown(String backend) {
        if (backend == null) return false;
        String id = backend.strip().toLowerCase();
        if (id.equals(Backends.JAVA2D) || id.equals(Backends.AUTO)) return true;
        return Backends.discover().stream().anyMatch(f -> f.id().equals(id));
    }

    // --- the dialog --------------------------------------------------------------

    /**
     * Ask, and return the chosen backend id — or {@code null} if the player
     * closed the dialog, or if anything at all went wrong showing it.
     *
     * <p>Swing rather than a scene of the engine's own, because the answer is
     * needed <em>before</em> a renderer exists to draw a scene with. That is the
     * whole difficulty of this question and the reason it cannot live in the
     * settings menu, where it would otherwise belong.
     */
    private String ask() {
        try {
            if (headless()) return null;
            Object[] options = {"OpenGL (recommended)", "Java2D (compatible)"};
            int picked = javax.swing.JOptionPane.showOptionDialog(null,
                    """
                    Which renderer should the game use?

                    OpenGL   — hardware accelerated, and what the frame profiles
                               are taken on. Falls back to Java2D by itself if
                               this machine's driver cannot provide a context.

                    Java2D   — the built-in software path. Slower, and works
                               everywhere a Java runtime does.

                    This is remembered. To change it later, delete
                    config/render.json or launch with
                    -Dlarsons.render.chooser=always""",
                    "Larson's Game Engine — renderer",
                    javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (picked == 0) return Backends.AUTO;
            if (picked == 1) return Backends.JAVA2D;
            return null;   // closed, escaped, or dismissed
        } catch (RuntimeException | Error e) {
            // No display, a sandbox, a broken look-and-feel. The game starts.
            System.err.println("[render] could not ask which backend to use ("
                    + e + "); continuing with the usual probe");
            return null;
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static String mode() {
        String raw = System.getProperty(PROPERTY);
        return raw == null ? "" : raw.strip().toLowerCase();
    }

    private static boolean headless() {
        if (Boolean.getBoolean(MacGlLauncher.HEADLESS_PROPERTY)) return true;
        try {
            return java.awt.GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * The string value of {@code key} in a flat JSON object, or {@code null}.
     *
     * <p>Hand-rolled rather than routed through {@code Json}, because this file
     * is read before the engine has done anything else and holds exactly one
     * string. A parser failure here must degrade to "not chosen yet", which a
     * three-line reader can guarantee and a general one cannot.
     */
    private static String valueOf(String json, String key) {
        int at = json.indexOf('"' + key + '"');
        if (at < 0) return null;
        int colon = json.indexOf(':', at);
        if (colon < 0) return null;
        int open = json.indexOf('"', colon);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }
}
