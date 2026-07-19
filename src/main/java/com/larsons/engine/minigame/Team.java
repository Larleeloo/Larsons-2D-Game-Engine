package com.larsons.engine.minigame;

import java.awt.Color;

/**
 * The fixed team palette mini games play with: up to {@value #MAX} teams,
 * each with a stable index, display name, and colour used everywhere a team
 * shows up (creative markers, flags, HUD score pills, player tints).
 *
 * <p>Teams are identified by index ({@code 0..3}) in game logic and on the
 * wire, and by the marker type string {@code "team1".."team4"} inside painted
 * {@link com.larsons.engine.level.Level.EntitySpawn} markers, so level JSON
 * stays human-readable.
 */
public final class Team {

    /** Most teams any mini game supports. */
    public static final int MAX = 4;

    private static final String[] NAMES = {"Red", "Blue", "Green", "Yellow"};
    private static final Color[] COLORS = {
            new Color(225, 80, 80),
            new Color(80, 140, 235),
            new Color(90, 200, 110),
            new Color(235, 200, 70),
    };

    private Team() {}

    /** Display name of team {@code index} ("Red", "Blue"…). */
    public static String name(int index) {
        return index >= 0 && index < MAX ? NAMES[index] : "Team " + (index + 1);
    }

    /** The team's colour (markers, flags, HUD). */
    public static Color color(int index) {
        return index >= 0 && index < MAX ? COLORS[index] : Color.GRAY;
    }

    /** The marker type string for team {@code index}: {@code "team1".."team4"}. */
    public static String markerType(int index) {
        return "team" + (index + 1);
    }

    /** Parse a {@code "teamN"} marker type back to its index, or -1. */
    public static int fromMarkerType(String type) {
        if (type == null || !type.startsWith("team")) return -1;
        try {
            int n = Integer.parseInt(type.substring(4)) - 1;
            return n >= 0 && n < MAX ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
