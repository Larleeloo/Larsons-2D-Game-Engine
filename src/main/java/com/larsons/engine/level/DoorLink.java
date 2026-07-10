package com.larsons.engine.level;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a game type's door list ({@link DoorDirectory}): a named,
 * tinted kind of door that leads to another level of the same game type.
 * Levels only store <em>where</em> a door stands (an {@code EntitySpawn} of
 * kind {@code "door"} whose type is this key); what the door means — its
 * label, colour, and destination level — lives in the external directory, so
 * retargeting a door kind updates every level that uses it.
 *
 * @param key         stable identifier stored in level files ("door_caves")
 * @param label       what the player sees ("To the Caves")
 * @param targetLevel level name (a {@link LevelStore} entry) the door loads
 * @param color       tint the door renders with
 */
public record DoorLink(String key, String label, String targetLevel, Color color) {

    public DoorLink {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Door key required");
        if (label == null || label.isBlank()) label = key;
        if (targetLevel == null) targetLevel = "";
        if (color == null) color = new Color(150, 105, 60);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("target", targetLevel);
        m.put("color", String.format("#%02x%02x%02x",
                color.getRed(), color.getGreen(), color.getBlue()));
        return m;
    }

    public static DoorLink fromMap(Map<String, Object> m) {
        return new DoorLink(
                str(m.get("key"), "door"),
                str(m.get("label"), ""),
                str(m.get("target"), ""),
                parseColor(str(m.get("color"), "")));
    }

    private static String str(Object o, String def) {
        return o instanceof String s && !s.isBlank() ? s : def;
    }

    private static Color parseColor(String hex) {
        try {
            String s = hex.startsWith("#") ? hex.substring(1) : hex;
            if (s.length() == 6) {
                return new Color(Integer.parseInt(s.substring(0, 2), 16),
                        Integer.parseInt(s.substring(2, 4), 16),
                        Integer.parseInt(s.substring(4, 6), 16));
            }
        } catch (RuntimeException ignored) {
            // fall through to default
        }
        return null;
    }
}
