package com.larsons.engine.input;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Objects;

/**
 * One physical input a player can put an action on: a keyboard key or a mouse
 * button, optionally with Ctrl/Shift/Alt held alongside it.
 *
 * <p>Keys and mouse buttons are the same kind of thing here on purpose — a
 * binding is asked "are you down?" and answers, whichever it is, so nothing
 * that reads input has to care whether "attack" ended up on the left mouse
 * button or on {@code F}. That is what lets <em>any</em> action be moved onto
 * <em>any</em> input from the controls menu.
 *
 * <p><b>Modifiers.</b> A binding that names modifiers ({@code Ctrl+Z}) only
 * matches while they are held. A binding that names none matches whether or not
 * anything else happens to be held, so walking left with {@code A} is not
 * interrupted by the Shift being held down to sprint. Where a plain key and a
 * modified one would otherwise both fire, the modified one is checked first
 * (see the creative editor's undo).
 *
 * <p>Instances are immutable and are compared by value, which is what conflict
 * detection in {@link KeyBinds} rests on.
 */
public final class InputBinding {

    /** Whether the binding sits on the keyboard or the mouse. */
    public enum Kind { KEY, MOUSE }

    public static final int CTRL = 1;
    public static final int SHIFT = 1 << 1;
    public static final int ALT = 1 << 2;

    /** An empty slot: matches nothing, and is what a cleared binding becomes. */
    public static final InputBinding UNBOUND = new InputBinding(null, 0, 0);

    private final Kind kind;   // null only for UNBOUND
    private final int code;    // key code, or mouse button number
    private final int mods;

    private InputBinding(Kind kind, int code, int mods) {
        this.kind = kind;
        this.code = code;
        this.mods = mods;
    }

    public static InputBinding key(int keyCode) {
        return key(keyCode, 0);
    }

    public static InputBinding key(int keyCode, int modifiers) {
        if (keyCode == KeyEvent.VK_UNDEFINED) return UNBOUND;
        return new InputBinding(Kind.KEY, keyCode, modifiers & (CTRL | SHIFT | ALT));
    }

    public static InputBinding mouse(int button) {
        return mouse(button, 0);
    }

    public static InputBinding mouse(int button, int modifiers) {
        if (button <= 0) return UNBOUND;
        return new InputBinding(Kind.MOUSE, button, modifiers & (CTRL | SHIFT | ALT));
    }

    public boolean isBound() { return kind != null; }

    public Kind kind() { return kind; }

    /** The key code (keyboard bindings) or button number (mouse bindings). */
    public int code() { return code; }

    public int modifiers() { return mods; }

    /** Whether this binding names {@code modifier} (one of {@link #CTRL} etc). */
    public boolean has(int modifier) { return (mods & modifier) != 0; }

    // --- matching ---------------------------------------------------------------

    /** Whether the input this binding names is being held right now. */
    public boolean isDown(InputManager input) {
        if (!isBound() || input == null) return false;
        if (!modifiersHeld(input)) return false;
        return kind == Kind.KEY ? input.isKeyDown(code) : input.isMouseButtonDown(code);
    }

    /** Whether the input this binding names went down during this tick. */
    public boolean isJustPressed(InputManager input) {
        if (!isBound() || input == null) return false;
        if (!modifiersHeld(input)) return false;
        return kind == Kind.KEY
                ? input.isKeyJustPressed(code)
                : input.isMouseButtonJustPressed(code);
    }

    private boolean modifiersHeld(InputManager input) {
        if (mods == 0) return true;
        if (has(CTRL) && !input.isKeyDown(KeyEvent.VK_CONTROL)) return false;
        if (has(SHIFT) && !input.isKeyDown(KeyEvent.VK_SHIFT)) return false;
        return !has(ALT) || input.isKeyDown(KeyEvent.VK_ALT);
    }

    /**
     * Whether pressing this also types a character into a text field. Bindings
     * that do are skipped while a field is being edited, so a menu whose "down"
     * was moved onto {@code J} does not jump a row every time a level is named.
     */
    public boolean isTypable() {
        if (kind != Kind.KEY || has(CTRL) || has(ALT)) return false;
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return true;
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return true;
        if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_DIVIDE) return true;
        return switch (code) {
            case KeyEvent.VK_SPACE, KeyEvent.VK_COMMA, KeyEvent.VK_MINUS, KeyEvent.VK_PERIOD,
                 KeyEvent.VK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_EQUALS,
                 KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_CLOSE_BRACKET,
                 KeyEvent.VK_QUOTE, KeyEvent.VK_BACK_QUOTE -> true;
            default -> false;
        };
    }

    // --- text -------------------------------------------------------------------

    /** What the controls menu shows: {@code "A"}, {@code "Ctrl+Z"}, {@code "Left Mouse"}. */
    public String display() {
        if (!isBound()) return "—";
        StringBuilder sb = new StringBuilder();
        if (has(CTRL)) sb.append("Ctrl+");
        if (has(SHIFT)) sb.append("Shift+");
        if (has(ALT)) sb.append("Alt+");
        sb.append(kind == Kind.KEY ? KeyNames.display(code) : mouseName(code));
        return sb.toString();
    }

    /** The savable form: {@code "key:A"}, {@code "ctrl+key:Z"}, {@code "mouse:1"}. */
    public String token() {
        if (!isBound()) return "";
        StringBuilder sb = new StringBuilder();
        if (has(CTRL)) sb.append("ctrl+");
        if (has(SHIFT)) sb.append("shift+");
        if (has(ALT)) sb.append("alt+");
        sb.append(kind == Kind.KEY ? "key:" + KeyNames.token(code) : "mouse:" + code);
        return sb.toString();
    }

    /** Read a {@link #token} back; anything unparseable reads as {@link #UNBOUND}. */
    public static InputBinding parse(String token) {
        if (token == null) return UNBOUND;
        String t = token.trim().toLowerCase();
        if (t.isEmpty()) return UNBOUND;
        int mods = 0;
        while (true) {
            if (t.startsWith("ctrl+")) { mods |= CTRL; t = t.substring(5); }
            else if (t.startsWith("shift+")) { mods |= SHIFT; t = t.substring(6); }
            else if (t.startsWith("alt+")) { mods |= ALT; t = t.substring(4); }
            else break;
        }
        if (t.startsWith("mouse:")) {
            try {
                return mouse(Integer.parseInt(t.substring(6).trim()), mods);
            } catch (NumberFormatException e) {
                return UNBOUND;
            }
        }
        if (t.startsWith("key:")) t = t.substring(4);
        return key(KeyNames.code(t), mods);
    }

    private static String mouseName(int button) {
        return switch (button) {
            case MouseEvent.BUTTON1 -> "Left Mouse";
            case MouseEvent.BUTTON2 -> "Middle Mouse";
            case MouseEvent.BUTTON3 -> "Right Mouse";
            default -> "Mouse " + button;
        };
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InputBinding other)) return false;
        return code == other.code && mods == other.mods && kind == other.kind;
    }

    @Override public int hashCode() { return Objects.hash(kind, code, mods); }

    @Override public String toString() { return isBound() ? token() : "unbound"; }
}
