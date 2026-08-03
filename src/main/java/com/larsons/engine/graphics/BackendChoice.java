package com.larsons.engine.graphics;

/**
 * Which renderer the engine is using, and why that one.
 *
 * <p><b>Why the reason is a field and not a log line.</b> The build stamp
 * taught this project the lesson once already: a measurement that does not
 * carry the conditions it was taken under is nearly useless, and the person who
 * needs it is never the person who took it. A frame profile from a player that
 * says {@code 11.4 ms scene} answers nothing unless it also says which backend
 * drew it — so this record goes into {@link com.larsons.engine.profile.DeviceProfile},
 * which goes into every report, rather than being printed once at startup and
 * lost.
 *
 * @param requested what {@code -Dlarsons.render.backend} asked for ({@code auto} by default)
 * @param chosen    the backend actually in use
 * @param driver    the driver behind it, blank for Java2D
 * @param why       the sentence explaining the outcome
 * @param backend   the chosen backend, or {@code null} when it is the built-in Java2D floor
 */
public record BackendChoice(String requested, String chosen, String driver, String why,
                            Backend backend) {

    public BackendChoice {
        if (requested == null || requested.isBlank()) requested = Backends.AUTO;
        if (chosen == null || chosen.isBlank()) chosen = Backends.JAVA2D;
        if (driver == null) driver = "";
        if (why == null) why = "";
    }

    /** Whether the engine is on the built-in Java2D renderer. */
    public boolean isJava2D() { return backend == null; }

    /**
     * Whether a specific GPU backend was asked for and could not be given.
     *
     * <p>Distinguished from the ordinary {@code auto} → Java2D case on purpose:
     * a player who passed a flag and silently got something else has been
     * misled, and that one deserves stderr. A player who passed nothing and got
     * the renderer that ships in their jar has not.
     */
    public boolean fellBack() {
        return isJava2D() && !requested.equals(Backends.AUTO) && !requested.equals(Backends.JAVA2D);
    }

    /** One line for a log: what is drawing, and why it is the one drawing. */
    public String describe() {
        String head = "[render] backend: " + chosen;
        if (!driver.isBlank()) head += " (" + driver + ")";
        return why.isBlank() ? head : head + " — " + why;
    }
}
