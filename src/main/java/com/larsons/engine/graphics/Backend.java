package com.larsons.engine.graphics;

/**
 * What a {@link RendererFactory} hands back: a renderer and the window it draws
 * into, or the reason there is neither.
 *
 * <p>One record for both outcomes, because the caller's next question is the
 * same either way — {@link #available()} decides whether the engine takes this
 * backend or moves on, and {@link #why()} is what it prints when it moves on. A
 * separate failure type would make the "no driver on this laptop" path look
 * exceptional, and it is not exceptional at all: it is what every machine
 * without a GPU does, every time, on purpose.
 *
 * @param id        the factory's id, echoed so a caller holding several can tell them apart
 * @param renderer  the backend, or {@code null} when unavailable
 * @param window    the window it brought, or {@code null} if it draws into the engine's
 * @param driver    a human-readable driver line for the frame report, e.g. vendor / renderer / version
 * @param why       why it is unavailable; blank when it is not
 */
public record Backend(String id, Renderer renderer, BackendWindow window,
                      String driver, String why) {

    public Backend {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a backend needs an id");
        if (driver == null) driver = "";
        if (why == null) why = "";
    }

    /** A working backend, with the window it owns and the driver behind it. */
    public static Backend of(String id, Renderer renderer, BackendWindow window, String driver) {
        if (renderer == null) throw new IllegalArgumentException("an available backend needs a renderer");
        return new Backend(id, renderer, window, driver, "");
    }

    /** No backend, and the reason — which is a log line, not an error. */
    public static Backend unavailable(String id, String why) {
        return new Backend(id, null, null, "", why == null || why.isBlank() ? "unknown" : why);
    }

    /** Whether there is a renderer here to use. */
    public boolean available() { return renderer != null; }
}
