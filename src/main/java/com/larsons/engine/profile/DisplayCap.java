package com.larsons.engine.profile;

/**
 * The frame cap for <em>this</em> machine, inside the range a level allows.
 *
 * <p><b>Why the machine picks and the level only bounds.</b> A frame rate is
 * two different decisions wearing one name. How fast the game <em>may</em> run
 * is a design decision belonging to the level — a deliberate 30 FPS look, or a
 * floor below which the level is not worth playing — and it travels with the
 * level to every machine that opens it. How fast the game <em>should</em> run
 * is a property of the hardware in front of the player: their panel's refresh
 * rate, which the level's author cannot know and should not have to guess.
 *
 * <p>So the level supplies {@code [minFps, maxFps]} and the display supplies
 * the number, clamped into that range. A 60 Hz laptop opening a level that
 * allows up to 144 runs at 60 and stops rendering frames nobody can see; a
 * 144 Hz monitor opening a level capped at 60 honours the author's cap.
 *
 * <p><b>Frames above the refresh rate are not free — they are invisible.</b>
 * A panel shows what it shows when it refreshes, so a 120 FPS cap on a 60 Hz
 * display spends half the machine's work on images that are overwritten before
 * anyone sees them. On a fanless laptop that half is also heat, and heat is
 * throttling, so the invisible frames make the visible ones slower.
 */
public final class DisplayCap {

    /**
     * Used when the display does not report a refresh rate — a virtual
     * display, a remote session, some Linux drivers. Chosen as the common
     * panel rate rather than the level's ceiling, because guessing high on
     * unknown hardware spends a weak machine's budget on invisible frames,
     * while guessing low costs a fast one only headroom it can spare.
     */
    public static final int ASSUMED_REFRESH_HZ = 60;

    private DisplayCap() {}

    /**
     * The cap to run at: this display's refresh rate, clamped into
     * {@code [minFps, maxFps]}.
     *
     * <p>The bounds are honoured even when they disagree with each other — a
     * level whose minimum exceeds its maximum gets the minimum, so a floor set
     * for a reason is never silently undercut by a ceiling left at a default.
     */
    public static int forDisplay(DeviceProfile device, int minFps, int maxFps) {
        int refresh = device == null || device.refreshHz() <= 0
                ? ASSUMED_REFRESH_HZ
                : device.refreshHz();
        return clamp(refresh, minFps, maxFps);
    }

    /** The cap for the machine this is running on. */
    public static int forThisMachine(int minFps, int maxFps) {
        return forDisplay(DeviceProfile.detect(), minFps, maxFps);
    }

    /**
     * {@code value} inside {@code [min, max]}, with the floor winning if the
     * two bounds cross.
     */
    private static int clamp(int value, int min, int max) {
        int low = Math.max(1, min);
        int high = Math.max(1, max);
        if (high < low) high = low;
        return Math.max(low, Math.min(high, value));
    }

    /** One line for a settings screen: what this machine will actually run at. */
    public static String describe(DeviceProfile device, int minFps, int maxFps) {
        int chosen = forDisplay(device, minFps, maxFps);
        return "%d FPS on this machine (%s, level allows %d-%d)"
                .formatted(chosen, refreshSource(device), minFps, maxFps);
    }

    /**
     * The rule, without the numbers it currently produces — for a settings
     * form, whose note rows are built once and would otherwise go stale the
     * moment someone edited the range they describe.
     */
    public static String describeRule(DeviceProfile device) {
        return "The frame cap is chosen per machine: this one reports %s, "
                .formatted(refreshSource(device))
                + "clamped into the range above. Frames faster than the display "
                + "are never seen, and on a laptop they are heat that slows the "
                + "frames that are.";
    }

    private static String refreshSource(DeviceProfile device) {
        int refresh = device == null ? 0 : device.refreshHz();
        return refresh > 0
                ? refresh + " Hz"
                : "no refresh rate, assuming " + ASSUMED_REFRESH_HZ + " Hz";
    }
}
