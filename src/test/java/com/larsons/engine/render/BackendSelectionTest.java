package com.larsons.engine.render;

import com.larsons.engine.graphics.Backend;
import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.BackendWindow;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.Renderer;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.FrameProfiler;
import com.larsons.engine.profile.FrameReport;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B9: the engine picks a renderer, and whatever happens a player gets a game.
 *
 * <p><b>Why the decision is a pure function and is tested as one.</b> The real
 * selection depends on a classpath, a display, a driver and a system property —
 * four things a test cannot vary and CI does not have. So
 * {@link Backends#select(String, List, RendererFactory.Request)} takes the
 * factories as an argument, and the convenience overload that reads the
 * property and the classpath is a two-line wrapper over it. Every route through
 * the decision is then exercisable here, on a machine with no GPU at all,
 * including the routes that only happen on a machine whose GPU is broken.
 *
 * <p>The GL end of the same question — that a real driver produces a real
 * backend, and that an impossible one falls back and says so — is
 * {@code GlBackendTest} in {@code :gl}, because it needs the module that has a
 * driver behind it. Between them the two ends meet in the middle.
 */
class BackendSelectionTest {

    private static final RendererFactory.Request REQUEST =
            new RendererFactory.Request(1280, 720, "test", Color.BLACK);

    // --- the ordinary cases ----------------------------------------------------

    /** The plain jar: nothing to find, so Java2D, and it says that plainly. */
    @Test
    void withNothingOnTheClasspathTheAnswerIsJava2D() {
        BackendChoice choice = Backends.select(Backends.AUTO, List.of(), REQUEST);

        assertTrue(choice.isJava2D());
        assertEquals(Backends.JAVA2D, choice.chosen());
        assertTrue(choice.why().contains("no GPU backend on the classpath"), choice.why());
        assertFalse(choice.fellBack(),
                "no flag was passed and no backend was there — that is the normal case, "
                        + "not a fallback");
    }

    @Test
    void autoTakesTheFirstBackendThatWorks() {
        Stub gl = Stub.working("gl", "Acme / Rocket 9000 / 3.3");
        BackendChoice choice = Backends.select(Backends.AUTO, List.of(gl), REQUEST);

        assertEquals("gl", choice.chosen());
        assertSame(gl.renderer, choice.backend().renderer());
        assertEquals("Acme / Rocket 9000 / 3.3", choice.driver());
        assertFalse(choice.isJava2D());
    }

    /**
     * The one-flag workaround a player with a driver bug is told to use. It
     * must not probe: the probe is what crashes on the machine that needs this.
     */
    @Test
    void forcingJava2DNeverAsksTheGpuBackendAnything() {
        Stub gl = Stub.working("gl", "Acme / Rocket 9000 / 3.3");
        BackendChoice choice = Backends.select("java2d", List.of(gl), REQUEST);

        assertTrue(choice.isJava2D());
        assertFalse(gl.asked, "-Dlarsons.render.backend=java2d probed for a GL context anyway");
        assertTrue(choice.why().contains("larsons.render.backend=java2d"), choice.why());
    }

    @Test
    void theFlagIsCaseAndWhitespaceInsensitive() {
        assertTrue(Backends.select("  JAVA2D ", List.of(Stub.working("gl", "d")), REQUEST)
                .isJava2D());
        assertEquals("gl", Backends.select(" GL ", List.of(Stub.working("gl", "d")), REQUEST)
                .chosen());
    }

    // --- the failures, which are the point -------------------------------------

    /** No driver is not an error. It is Tuesday, on most laptops. */
    @Test
    void aBackendThatCannotStartFallsBackCarryingItsOwnReason() {
        Stub gl = Stub.broken("gl", "no GL 3.3 core context");
        BackendChoice choice = Backends.select(Backends.AUTO, List.of(gl), REQUEST);

        assertTrue(choice.isJava2D());
        assertTrue(gl.asked, "auto should have probed");
        assertTrue(choice.why().contains("gl: no GL 3.3 core context"),
                "the driver's own words have to survive to the log line: " + choice.why());
    }

    /**
     * A GL distribution built for another OS has the classes and not the
     * natives, and announces it as an {@link UnsatisfiedLinkError} — which is
     * not an {@link Exception} and would take the game down with it.
     */
    @Test
    void aFactoryThatThrowsAnErrorIsStillJustAFallback() {
        RendererFactory exploding = new RendererFactory() {
            @Override public String id() { return "gl"; }
            @Override public Backend create(Request request) {
                throw new UnsatisfiedLinkError("no lwjgl in java.library.path");
            }
        };
        BackendChoice choice = Backends.select(Backends.AUTO, List.of(exploding), REQUEST);

        assertTrue(choice.isJava2D());
        assertTrue(choice.why().contains("UnsatisfiedLinkError"), choice.why());
        assertTrue(choice.why().contains("no lwjgl in java.library.path"), choice.why());
    }

    @Test
    void aFactoryThatReturnsNothingIsAFallbackToo() {
        RendererFactory silent = new RendererFactory() {
            @Override public String id() { return "gl"; }
            @Override public Backend create(Request request) { return null; }
        };
        assertTrue(Backends.select(Backends.AUTO, List.of(silent), REQUEST).isJava2D());
    }

    /**
     * A backend outside the core has to bring a window; the engine's own is an
     * AWT canvas only Java2D can draw into. Caught here rather than as a null
     * pointer in the engine's constructor.
     */
    @Test
    void aRendererWithNoWindowIsNotUsable() {
        RendererFactory windowless = new RendererFactory() {
            @Override public String id() { return "gl"; }
            @Override public Backend create(Request request) {
                return new Backend("gl", new StubRenderer(), null, "Acme", "");
            }
        };
        BackendChoice choice = Backends.select(Backends.AUTO, List.of(windowless), REQUEST);

        assertTrue(choice.isJava2D());
        assertTrue(choice.why().contains("no window"), choice.why());
    }

    @Test
    void askingForABackendThatIsNotThereNamesWhatIs() {
        BackendChoice choice = Backends.select("vulkan", List.of(Stub.working("gl", "d")), REQUEST);

        assertTrue(choice.isJava2D());
        assertTrue(choice.why().contains("available: gl"),
                "a mistyped flag should say what it could have said: " + choice.why());
    }

    @Test
    void askingForABackendWithNoneOnTheClasspathSaysThat() {
        BackendChoice choice = Backends.select("gl", List.of(), REQUEST);

        assertTrue(choice.isJava2D());
        assertTrue(choice.why().contains("none is"), choice.why());
    }

    /** Named backend means that one only — auto is how you say "anything". */
    @Test
    void askingForOneBackendDoesNotSilentlyStartAnother() {
        Stub gl = Stub.working("gl", "Acme");
        Stub vulkan = Stub.broken("vulkan", "not implemented");
        BackendChoice choice = Backends.select("vulkan", List.of(vulkan, gl), REQUEST);

        assertTrue(choice.isJava2D());
        assertFalse(gl.asked, "asking for vulkan started gl instead");
    }

    /**
     * Only an unhonoured flag is a fallback. The distinction decides whether
     * the engine's startup line goes to stdout or stderr, and a game that
     * shouts on every launch on every machine without a GPU is a game whose
     * warnings stop being read.
     */
    @Test
    void onlyAnUnhonouredFlagCountsAsFallingBack() {
        assertFalse(Backends.select(Backends.AUTO, List.of(), REQUEST).fellBack());
        assertFalse(Backends.select("java2d", List.of(), REQUEST).fellBack());
        assertTrue(Backends.select("gl", List.of(), REQUEST).fellBack());
        assertFalse(Backends.select(Backends.AUTO, List.of(Stub.broken("gl", "no driver")), REQUEST)
                        .fellBack(),
                "auto on a machine whose driver said no is still the normal path");
    }

    // --- what the classpath actually holds --------------------------------------

    /**
     * The core's own test classpath has no GL module on it, and the scan says
     * so rather than throwing. This is the plain jar's experience, checked on
     * every build — the other half of {@code :verifyNoRuntimeDependencies},
     * which proves the same thing about what ships.
     */
    @Test
    void theCoreFindsNoBackendOnItsOwnClasspath() {
        assertEquals(List.of(), Backends.discover().stream().map(RendererFactory::id).toList());
        assertTrue(Backends.select(REQUEST).isJava2D());
    }

    @Test
    void anUnsetFlagMeansAuto() {
        assertEquals(Backends.AUTO, Backends.requested());
    }

    // --- the report -------------------------------------------------------------

    /**
     * A profile that does not say which renderer produced it is nearly
     * useless, and this project has already lost hours of reports to exactly
     * that mistake with the build stamp.
     */
    @Test
    void everyReportNamesTheBackendAndTheDriver() {
        DeviceProfile device = DeviceProfile.detect().withRenderer("gl", "Acme / Rocket / 3.3");
        String report = FrameReport.render(new FrameProfiler().snapshot(), device, "PlayScene");

        assertTrue(report.contains("backend  : gl"), report);
        assertTrue(report.contains("gpu      : Acme / Rocket / 3.3"), report);
    }

    @Test
    void aJava2DReportSaysSoAndCarriesNoDriverLine() {
        DeviceProfile device = DeviceProfile.detect().withRenderer("java2d", "");
        String report = FrameReport.render(new FrameProfiler().snapshot(), device, null);

        assertTrue(report.contains("backend  : java2d"), report);
        assertFalse(report.contains("gpu      :"),
                "Java2D has no GL driver behind it; an empty line would invite a guess");
    }

    @Test
    void aProfileTakenBeforeABackendWasChosenAdmitsIt() {
        assertEquals(DeviceProfile.UNKNOWN_BACKEND, DeviceProfile.detect().backend());
    }

    // --- stubs ------------------------------------------------------------------

    /** A factory that reports what it was asked and produces whatever it was told to. */
    private static final class Stub implements RendererFactory {
        private final String id;
        private final String driver;
        private final String why;
        private final Renderer renderer = new StubRenderer();
        private boolean asked;

        private Stub(String id, String driver, String why) {
            this.id = id;
            this.driver = driver;
            this.why = why;
        }

        static Stub working(String id, String driver) { return new Stub(id, driver, null); }

        static Stub broken(String id, String why) { return new Stub(id, null, why); }

        @Override public String id() { return id; }

        @Override
        public Backend create(Request request) {
            asked = true;
            assertNotNull(request, "a factory is always handed a request");
            return why != null
                    ? Backend.unavailable(id, why)
                    : Backend.of(id, renderer, new StubWindow(request), driver);
        }
    }

    private static final class StubRenderer implements Renderer {
        private final RecordingTarget target = new RecordingTarget(1280, 720);

        @Override public DrawTarget beginFrame() { return target; }
        @Override public void present() {}
        @Override public int getWidth() { return 1280; }
        @Override public int getHeight() { return 720; }
        @Override public void dispose() {}
    }

    private static final class StubWindow implements BackendWindow {
        private final RendererFactory.Request request;

        private StubWindow(RendererFactory.Request request) { this.request = request; }

        @Override public void show() {}
        @Override public int width() { return request.width(); }
        @Override public int height() { return request.height(); }
        @Override public void attachInput(InputManager input) {}
        @Override public void pumpEvents() {}
        @Override public boolean closeRequested() { return false; }
        @Override public void close() {}
    }
}
