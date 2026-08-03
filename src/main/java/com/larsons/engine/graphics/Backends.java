package com.larsons.engine.graphics;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Picks the rendering backend at startup, and never strands a player.
 *
 * <p><b>Java2D is the floor, not a legacy path.</b> It is what a bare JRE with
 * no GL module gets, what a machine with no usable driver gets, what headless
 * CI gets, and what {@code -Dlarsons.render.backend=java2d} gets on any machine
 * at all. Every route through this class that does not end in a GPU backend
 * ends there, with a sentence saying why — which is the difference between a
 * fallback and a failure.
 *
 * <p><b>The flag.</b> {@code -Dlarsons.render.backend=auto|java2d|<id>},
 * defaulting to {@code auto}:
 *
 * <ul>
 *   <li>{@code auto} — use the first GPU backend on the classpath that can
 *       actually create a context; Java2D if there is none or none works.</li>
 *   <li>{@code java2d} — Java2D, without probing for anything. A player who
 *       hits a driver bug has a one-flag workaround, and this is it.</li>
 *   <li>anything else — that backend by id, and <em>only</em> that one. If it
 *       is not on the classpath or cannot start, the engine still runs, on
 *       Java2D, and says on stderr that the flag was not honoured. A bug report
 *       can then name a backend and be believed.</li>
 * </ul>
 *
 * <p><b>Nothing here names a backend.</b> The ids come from the factories
 * themselves, which arrive over {@link ServiceLoader} from whatever is on the
 * classpath. See {@link RendererFactory} for why that indirection is
 * structural rather than stylistic.
 */
public final class Backends {

    /** The system property that chooses a backend. */
    public static final String PROPERTY = "larsons.render.backend";

    /** Probe for a GPU backend and use it if one works. The default. */
    public static final String AUTO = "auto";

    /** The built-in renderer, always available, never probed for. */
    public static final String JAVA2D = "java2d";

    private Backends() {}

    /**
     * Choose a backend for a window of this size, reading {@link #PROPERTY}.
     *
     * <p>Never throws and never returns {@code null}: the worst case is a
     * {@link BackendChoice} naming Java2D and explaining itself.
     */
    public static BackendChoice select(RendererFactory.Request request) {
        return select(requested(), discover(), request);
    }

    /** What {@link #PROPERTY} asks for, normalised; {@code auto} when unset. */
    public static String requested() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return AUTO;
        return raw.strip().toLowerCase();
    }

    /**
     * Every backend on the classpath, in the order the service loader finds
     * them.
     *
     * <p>Failure to load a service is not fatal here. A malformed provider, or
     * one whose class is present but whose natives are not, would otherwise
     * take down a game that has a perfectly good software renderer sitting
     * behind it.
     */
    public static List<RendererFactory> discover() {
        List<RendererFactory> found = new ArrayList<>();
        try {
            for (RendererFactory factory : ServiceLoader.load(RendererFactory.class)) {
                if (factory != null && factory.id() != null && !factory.id().isBlank()) {
                    found.add(factory);
                }
            }
        } catch (Throwable t) {
            // ServiceConfigurationError, or a provider constructor that threw.
            System.err.println("[render] could not scan for GPU backends: " + describe(t));
        }
        return found;
    }

    /**
     * The decision itself, as a pure function of the request, the flag and the
     * factories available — so it can be tested without a classpath, a display
     * or a driver.
     */
    public static BackendChoice select(String requested, List<RendererFactory> factories,
                                       RendererFactory.Request request) {
        String want = requested == null || requested.isBlank()
                ? AUTO : requested.strip().toLowerCase();
        List<RendererFactory> available = factories == null ? List.of() : factories;

        if (want.equals(JAVA2D)) {
            return java2d(want, "requested by -D" + PROPERTY + "=" + JAVA2D);
        }

        if (want.equals(AUTO) && available.isEmpty()) {
            return java2d(want, "no GPU backend on the classpath");
        }

        if (!want.equals(AUTO) && ids(available).stream().noneMatch(want::equals)) {
            return java2d(want, "-D" + PROPERTY + "=" + want + " names a backend that is not "
                    + "on the classpath (" + (available.isEmpty()
                    ? "none is" : "available: " + String.join(", ", ids(available))) + ")");
        }

        List<String> refused = new ArrayList<>();
        for (RendererFactory factory : available) {
            if (!want.equals(AUTO) && !want.equals(factory.id())) continue;
            Backend backend = build(factory, request);
            if (backend.available()) {
                return new BackendChoice(want, backend.id(), backend.driver(),
                        want.equals(AUTO) ? "probed and selected automatically"
                                : "requested by -D" + PROPERTY + "=" + want,
                        backend);
            }
            refused.add(factory.id() + ": " + backend.why());
        }
        return java2d(want, "no usable GPU context — " + String.join("; ", refused));
    }

    /**
     * Ask one factory, treating anything it throws as an unavailable backend.
     *
     * <p>{@link UnsatisfiedLinkError} is the case this exists for and it is not
     * an {@link Exception}: a GL distribution built for another OS has the
     * classes but not the natives, and the player who downloaded the wrong one
     * should get their game on Java2D with an explanation, not a stack trace
     * and no window.
     */
    private static Backend build(RendererFactory factory, RendererFactory.Request request) {
        try {
            Backend backend = factory.create(request);
            if (backend == null) {
                return Backend.unavailable(factory.id(), "the factory returned nothing");
            }
            if (backend.available() && backend.window() == null) {
                // Checked here so the engine never has to hold half a backend.
                // The engine's own window is an AWT canvas that only Java2D can
                // draw into; a backend from outside the core therefore has to
                // arrive with a window of its own or it cannot be presented.
                return Backend.unavailable(factory.id(),
                        "produced a renderer but no window — a backend outside the core "
                                + "has to bring its own");
            }
            return backend;
        } catch (Throwable t) {
            return Backend.unavailable(factory.id(), describe(t));
        }
    }

    private static BackendChoice java2d(String requested, String why) {
        return new BackendChoice(requested, JAVA2D, "", why, null);
    }

    private static List<String> ids(List<RendererFactory> factories) {
        return factories.stream().map(RendererFactory::id).toList();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
