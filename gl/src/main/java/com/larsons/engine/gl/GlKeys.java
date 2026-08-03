package com.larsons.engine.gl;

import com.larsons.engine.input.InputBinding;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import static org.lwjgl.glfw.GLFW.*;

/**
 * GLFW's input vocabulary translated into the one the engine already speaks.
 *
 * <p><b>Why AWT key codes are the target and not something neutral.</b> Every
 * key bind the engine has ever saved is a {@code java.awt.event.KeyEvent}
 * constant — {@code KeyBindStore} writes them to disk, the controls menu
 * displays them through {@link InputBinding}, and eighteen scenes compare
 * against {@code VK_} constants directly. A neutral enum would have been the
 * tidier design in 2024 and is a migration of every saved bind today, for a
 * backend that has to agree with the other one anyway. So GLFW is translated
 * at the edge, once, here, and a player's controls work identically on both
 * renderers — including the binds they saved before this backend existed.
 *
 * <p><b>The ASCII range mostly coincides, and that is not relied on.</b> GLFW
 * names its printable keys by their unshifted ASCII value and AWT numbers most
 * of its {@code VK_} constants the same way, so {@code GLFW_KEY_A} and
 * {@code VK_A} are both 65. Four do not coincide — apostrophe and backquote
 * are the ones that bite — and a table that silently passes through anything
 * it does not recognise would map those to nothing and lose the key. Every
 * mapping below is therefore written out, and anything unlisted is reported as
 * {@link KeyEvent#VK_UNDEFINED} rather than guessed at.
 */
final class GlKeys {

    private GlKeys() {}

    /** The AWT key code for a GLFW key, or {@code VK_UNDEFINED} if there is none. */
    static int awtKey(int glfwKey) {
        // Letters and digits: GLFW numbers them by ASCII and so does AWT.
        if (glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z) return glfwKey;
        if (glfwKey >= GLFW_KEY_0 && glfwKey <= GLFW_KEY_9) return glfwKey;
        if (glfwKey >= GLFW_KEY_F1 && glfwKey <= GLFW_KEY_F24) {
            return KeyEvent.VK_F1 + (glfwKey - GLFW_KEY_F1);
        }
        if (glfwKey >= GLFW_KEY_KP_0 && glfwKey <= GLFW_KEY_KP_9) {
            return KeyEvent.VK_NUMPAD0 + (glfwKey - GLFW_KEY_KP_0);
        }
        return switch (glfwKey) {
            case GLFW_KEY_SPACE -> KeyEvent.VK_SPACE;
            case GLFW_KEY_APOSTROPHE -> KeyEvent.VK_QUOTE;
            case GLFW_KEY_COMMA -> KeyEvent.VK_COMMA;
            case GLFW_KEY_MINUS -> KeyEvent.VK_MINUS;
            case GLFW_KEY_PERIOD -> KeyEvent.VK_PERIOD;
            case GLFW_KEY_SLASH -> KeyEvent.VK_SLASH;
            case GLFW_KEY_SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case GLFW_KEY_EQUAL -> KeyEvent.VK_EQUALS;
            case GLFW_KEY_LEFT_BRACKET -> KeyEvent.VK_OPEN_BRACKET;
            case GLFW_KEY_BACKSLASH -> KeyEvent.VK_BACK_SLASH;
            case GLFW_KEY_RIGHT_BRACKET -> KeyEvent.VK_CLOSE_BRACKET;
            case GLFW_KEY_GRAVE_ACCENT -> KeyEvent.VK_BACK_QUOTE;

            case GLFW_KEY_ESCAPE -> KeyEvent.VK_ESCAPE;
            case GLFW_KEY_ENTER -> KeyEvent.VK_ENTER;
            case GLFW_KEY_TAB -> KeyEvent.VK_TAB;
            case GLFW_KEY_BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case GLFW_KEY_INSERT -> KeyEvent.VK_INSERT;
            case GLFW_KEY_DELETE -> KeyEvent.VK_DELETE;
            case GLFW_KEY_RIGHT -> KeyEvent.VK_RIGHT;
            case GLFW_KEY_LEFT -> KeyEvent.VK_LEFT;
            case GLFW_KEY_DOWN -> KeyEvent.VK_DOWN;
            case GLFW_KEY_UP -> KeyEvent.VK_UP;
            case GLFW_KEY_PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case GLFW_KEY_PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case GLFW_KEY_HOME -> KeyEvent.VK_HOME;
            case GLFW_KEY_END -> KeyEvent.VK_END;
            case GLFW_KEY_CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;
            case GLFW_KEY_SCROLL_LOCK -> KeyEvent.VK_SCROLL_LOCK;
            case GLFW_KEY_NUM_LOCK -> KeyEvent.VK_NUM_LOCK;
            case GLFW_KEY_PRINT_SCREEN -> KeyEvent.VK_PRINTSCREEN;
            case GLFW_KEY_PAUSE -> KeyEvent.VK_PAUSE;
            case GLFW_KEY_MENU -> KeyEvent.VK_CONTEXT_MENU;

            case GLFW_KEY_KP_DECIMAL -> KeyEvent.VK_DECIMAL;
            case GLFW_KEY_KP_DIVIDE -> KeyEvent.VK_DIVIDE;
            case GLFW_KEY_KP_MULTIPLY -> KeyEvent.VK_MULTIPLY;
            case GLFW_KEY_KP_SUBTRACT -> KeyEvent.VK_SUBTRACT;
            case GLFW_KEY_KP_ADD -> KeyEvent.VK_ADD;
            case GLFW_KEY_KP_ENTER -> KeyEvent.VK_ENTER;
            case GLFW_KEY_KP_EQUAL -> KeyEvent.VK_EQUALS;

            // Left and right halves collapse to one code, which is what AWT
            // reports without a key location and what every bind in the engine
            // was recorded as. A player who bound "shift" means either shift.
            case GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> KeyEvent.VK_SHIFT;
            case GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> KeyEvent.VK_CONTROL;
            case GLFW_KEY_LEFT_ALT, GLFW_KEY_RIGHT_ALT -> KeyEvent.VK_ALT;
            case GLFW_KEY_LEFT_SUPER, GLFW_KEY_RIGHT_SUPER -> KeyEvent.VK_META;

            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    /**
     * GLFW's modifier mask as {@link InputBinding}'s, minus the key being
     * pressed — so pressing Shift on its own records the Shift key rather than
     * a modified nothing, exactly as the AWT path does.
     */
    static int modifiers(int glfwMods, int awtKey) {
        int out = 0;
        if ((glfwMods & GLFW_MOD_CONTROL) != 0 && awtKey != KeyEvent.VK_CONTROL) {
            out |= InputBinding.CTRL;
        }
        if ((glfwMods & GLFW_MOD_SHIFT) != 0 && awtKey != KeyEvent.VK_SHIFT) {
            out |= InputBinding.SHIFT;
        }
        if ((glfwMods & GLFW_MOD_ALT) != 0 && awtKey != KeyEvent.VK_ALT) {
            out |= InputBinding.ALT;
        }
        return out;
    }

    /**
     * GLFW's mouse button as AWT's.
     *
     * <p>The two disagree about the middle button: GLFW numbers them
     * left/right/middle from zero, AWT left/middle/right from one. Passing the
     * number straight through would put every right-click on the middle button,
     * which in this engine means "pick block" where "erase" was meant.
     */
    static int awtButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW_MOUSE_BUTTON_LEFT -> MouseEvent.BUTTON1;
            case GLFW_MOUSE_BUTTON_MIDDLE -> MouseEvent.BUTTON2;
            case GLFW_MOUSE_BUTTON_RIGHT -> MouseEvent.BUTTON3;
            // Side buttons, which InputManager tracks by number like any other.
            default -> glfwButton + 1;
        };
    }

    /**
     * A scroll offset as AWT wheel notches.
     *
     * <p>Sign flipped: GLFW counts a wheel pushed away from the user as
     * positive, AWT counts it as negative, and every scroll handler in the
     * engine was written against AWT's. A backend that got this backwards
     * would zoom out where the player meant to zoom in — visible immediately
     * and easy to misread as a bug in the scene.
     */
    static int wheelNotches(double yOffset) {
        if (yOffset > 0) return -(int) Math.max(1, Math.round(yOffset));
        if (yOffset < 0) return (int) Math.max(1, Math.round(-yOffset));
        return 0;
    }
}
