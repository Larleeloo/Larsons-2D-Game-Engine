package com.larsons.engine.input;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Names for AWT virtual-key codes, in both directions.
 *
 * <p>Custom key binds have to survive a round trip through a JSON file, which
 * rules out both raw numbers (nobody can hand-edit {@code 192}) and the text
 * AWT shows the player ({@link KeyEvent#getKeyText} is localized, so a file
 * written on a French desktop would not load on an English one). The stable
 * middle ground is the constant's own name: {@code VK_BACK_QUOTE} is written as
 * {@code BACK_QUOTE}, and read back as the same code on any machine.
 *
 * <p>The table is built by reflecting over {@link KeyEvent}'s {@code VK_}
 * constants rather than being typed out by hand, so every key the JDK knows
 * about — function keys, numpad keys, media keys — is bindable without this
 * class having to list it. Codes AWT has no constant for still round-trip, as
 * hex ({@code 0x1234}).
 */
public final class KeyNames {

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> CODES = new HashMap<>();

    static {
        for (Field f : KeyEvent.class.getFields()) {
            if (!f.getName().startsWith("VK_")) continue;
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            try {
                int code = f.getInt(null);
                String name = f.getName().substring(3);
                CODES.putIfAbsent(name, code);
                // A handful of codes have more than one constant (aliases for
                // input-method keys). Prefer the shorter name so the choice is
                // deterministic regardless of the order reflection hands the
                // fields over in.
                String existing = NAMES.get(code);
                if (existing == null || name.length() < existing.length()) NAMES.put(code, name);
            } catch (IllegalAccessException ignored) {
                // A VK_ constant we cannot read simply stays unnamed (see token).
            }
        }
    }

    private KeyNames() {}

    /** The stable, savable name of a key code ({@code "A"}, {@code "0x1234"}). */
    public static String token(int code) {
        String name = NAMES.get(code);
        return name != null ? name : "0x" + Integer.toHexString(code);
    }

    /**
     * The key code a {@link #token} names, or {@link KeyEvent#VK_UNDEFINED} for
     * a name this JDK does not know. Accepts hex ({@code 0x1234}) and plain
     * decimal too, so a file written by a newer engine still loads.
     */
    public static int code(String token) {
        if (token == null) return KeyEvent.VK_UNDEFINED;
        String t = token.trim().toUpperCase();
        if (t.isEmpty()) return KeyEvent.VK_UNDEFINED;
        Integer known = CODES.get(t);
        if (known != null) return known;
        try {
            return t.startsWith("0X") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return KeyEvent.VK_UNDEFINED;
        }
    }

    /**
     * How a key is shown to the player. AWT's own text is used where it has
     * some ({@code "A"}, {@code "F1"}, {@code "Left"}), because that is what
     * the key is called on their keyboard, in their language.
     */
    public static String display(int code) {
        String text = KeyEvent.getKeyText(code);
        // getKeyText falls back to "Unknown keyCode: 0x…" for codes it has no
        // name for; the token is shorter and no less informative.
        if (text == null || text.isEmpty() || text.startsWith("Unknown")) return token(code);
        return text;
    }
}
