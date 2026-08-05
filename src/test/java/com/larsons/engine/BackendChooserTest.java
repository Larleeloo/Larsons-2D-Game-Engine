package com.larsons.engine;

import com.larsons.engine.core.BackendChooser;
import com.larsons.engine.core.BackendChooser.Decision;
import com.larsons.engine.core.MacGlLauncher;
import com.larsons.engine.graphics.Backends;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the launch chooser asks, when it stays quiet, and what it remembers.
 *
 * <p><b>The dialog itself is not tested and deliberately so.</b> Driving a
 * modal Swing dialog from a build agent measures the agent's window manager. The
 * part that can be wrong without anyone noticing is the <em>decision</em>: a
 * chooser that appears in front of a scripted run, or overrides a flag someone
 * typed, or asks again every launch, is a worse program than one with no choice
 * in it at all. So {@link BackendChooser#decide()} is a pure function returning
 * a reason, and this holds every branch of it.
 *
 * <p>Note that on this classpath {@code Backends.discover()} finds nothing — the
 * core's tests are the plain jar's world — so {@code NO_CHOICE} is the answer
 * wherever a GPU backend would have been needed to make the question meaningful.
 * That is itself worth pinning: the plain jar must never ask, because it has
 * only one renderer to offer.
 */
class BackendChooserTest {

    private final List<Runnable> restore = new ArrayList<>();

    @AfterEach
    void putPropertiesBack() {
        for (Runnable r : restore) r.run();
        restore.clear();
    }

    private void set(String key, String value) {
        String previous = System.getProperty(key);
        restore.add(() -> {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        });
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /** A chooser writing somewhere a test may safely litter. */
    private static BackendChooser chooserIn(Path dir) {
        return new BackendChooser(dir.toString());
    }

    // --- when it must not ask -----------------------------------------------------

    /**
     * A backend named on the command line has already answered the question,
     * and a dialog that overrode it would make the flag untrustworthy.
     */
    @Test
    void anExplicitFlagIsNeverSecondGuessed(@TempDir Path dir) {
        set(Backends.PROPERTY, "gl");
        set(BackendChooser.PROPERTY, "always");

        assertEquals(Decision.FLAG_GIVEN, chooserIn(dir).decide(),
                "-D" + Backends.PROPERTY + " was overridden by the chooser");
    }

    /** The scripted-run switch. */
    @Test
    void neverMeansNever(@TempDir Path dir) {
        set(Backends.PROPERTY, null);
        set(BackendChooser.PROPERTY, "never");

        assertEquals(Decision.SUPPRESSED, chooserIn(dir).decide());
    }

    /**
     * The relaunched first-thread child must not ask: its parent already did,
     * and a second dialog would appear behind the game's own window.
     */
    @Test
    void theFirstThreadChildDoesNotAskAgain(@TempDir Path dir) {
        set(Backends.PROPERTY, null);
        set(BackendChooser.PROPERTY, "");
        set(MacGlLauncher.CHILD_PROPERTY, "true");

        assertEquals(Decision.RELAUNCHED_CHILD, chooserIn(dir).decide());
    }

    /**
     * And the plain jar never asks, because it has one renderer and a menu with
     * one item on it is worse than no menu.
     *
     * <p>This classpath is the plain jar's: no GPU backend is discoverable from
     * the core's own tests, which is what makes this assertion possible here and
     * is the same reason `MacGlLauncherTest` can hold all of its negatives.
     */
    @Test
    void aClasspathWithOneRendererHasNothingToAsk(@TempDir Path dir) {
        set(Backends.PROPERTY, null);
        set(BackendChooser.PROPERTY, "");
        set(MacGlLauncher.CHILD_PROPERTY, null);

        assertEquals(Decision.NO_CHOICE, chooserIn(dir).decide(),
                "a build with only Java2D on it offered a choice between one thing");
    }

    // --- what it remembers --------------------------------------------------------

    @Test
    void aChoiceSurvivesToTheNextLaunch(@TempDir Path dir) throws IOException {
        BackendChooser chooser = chooserIn(dir);
        chooser.remember(Backends.JAVA2D);

        assertTrue(Files.exists(chooser.file()), "nothing was written");
        assertEquals(Backends.JAVA2D, chooser.remembered());
        assertTrue(Files.readString(chooser.file(), StandardCharsets.UTF_8).contains("backend"),
                "the file should be readable by a person, not just by this class");
    }

    @Test
    void forgettingMakesItAskAgain(@TempDir Path dir) {
        BackendChooser chooser = chooserIn(dir);
        chooser.remember(Backends.AUTO);
        assertEquals(Backends.AUTO, chooser.remembered());

        chooser.forget();
        assertNull(chooser.remembered());
    }

    /**
     * A preference file that cannot be read is "not chosen yet", never an
     * error. It is a preference; it must not be able to stop a game starting.
     */
    @Test
    void anUnreadableChoiceIsSimplyNoChoice(@TempDir Path dir) throws IOException {
        BackendChooser chooser = chooserIn(dir);
        Files.createDirectories(dir);
        Files.writeString(chooser.file(), "this is not json at all", StandardCharsets.UTF_8);

        assertNull(chooser.remembered(), "a corrupt preference file should read as absent");
    }

    /** And so is one naming a backend this build has never heard of. */
    @Test
    void aChoiceThisBuildDoesNotRecogniseIsIgnored(@TempDir Path dir) throws IOException {
        BackendChooser chooser = chooserIn(dir);
        Files.createDirectories(dir);
        Files.writeString(chooser.file(), "{\"backend\": \"vulkan\"}", StandardCharsets.UTF_8);

        assertNull(chooser.remembered(),
                "a remembered backend that no longer exists must not be applied");
    }

    /** Writing a backend the engine would refuse is a no-op rather than a bad file. */
    @Test
    void anUnknownBackendIsNotWrittenDown(@TempDir Path dir) {
        BackendChooser chooser = chooserIn(dir);
        chooser.remember("vulkan");

        assertNull(chooser.remembered());
    }

    /** Java2D and auto are always available to choose; a made-up id is not. */
    @Test
    void theKnownBackendsAreTheOnesThatCanBeChosen() {
        assertTrue(BackendChooser.isKnown(Backends.JAVA2D));
        assertTrue(BackendChooser.isKnown(Backends.AUTO));
        assertTrue(BackendChooser.isKnown("JAVA2D"), "the id should not be case-sensitive");
        assertTrue(!BackendChooser.isKnown("vulkan"));
        assertTrue(!BackendChooser.isKnown(null));
    }

    // --- applying it --------------------------------------------------------------

    /**
     * Applying a remembered choice sets the same property the command line
     * would have set, so everything downstream — the probe, the fallback, the
     * line in the frame report — behaves exactly as if it had been typed.
     */
    @Test
    void aRememberedChoiceIsAppliedAsIfItHadBeenTyped(@TempDir Path dir) {
        set(Backends.PROPERTY, null);
        set(BackendChooser.PROPERTY, "");
        set(MacGlLauncher.CHILD_PROPERTY, null);

        BackendChooser chooser = chooserIn(dir);
        chooser.remember(Backends.JAVA2D);

        // NO_CHOICE wins on this classpath, so applying does nothing — which is
        // the correct behaviour for the plain jar and is asserted rather than
        // worked around. The remembering half is checked above.
        assertEquals(Decision.NO_CHOICE, chooser.decide());
        assertNull(chooser.apply());
        assertNull(System.getProperty(Backends.PROPERTY),
                "a build with nothing to choose between should not set the property");
    }
}
