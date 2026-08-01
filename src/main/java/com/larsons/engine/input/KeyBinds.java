package com.larsons.engine.input;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's key binds: which physical inputs each {@link GameAction} sits
 * on (requirement #6's menu customization, carried through to the controls).
 *
 * <p>Every action gets {@value #SLOTS} slots — a primary binding and an
 * alternate — because that is how the engine's defaults are already shaped
 * ({@code A} <em>and</em> Left Arrow both walk left). A slot holds any key or
 * any mouse button, so an action can be moved onto a side button as easily as
 * onto a letter, and either slot can be left empty.
 *
 * <p>Gameplay reads binds through {@link #down} / {@link #pressed}, which query
 * the {@linkplain #active() active} set — installed once at startup from the
 * saved file and edited live by the controls menu, so a rebind takes effect on
 * the very next frame without anything having to be rebuilt.
 */
public class KeyBinds {

    /** Bindings per action: a primary and an alternate. */
    public static final int SLOTS = 2;

    /** Format marker written into the saved file. */
    public static final int VERSION = 1;

    private final Map<GameAction, InputBinding[]> binds = new EnumMap<>(GameAction.class);

    /** The set every scene reads; swapped in by {@link #install}. */
    private static KeyBinds active = defaults();

    public KeyBinds() {
        for (GameAction a : GameAction.values()) binds.put(a, emptySlots());
    }

    /** A fresh set with every action on the keys it ships with. */
    public static KeyBinds defaults() {
        KeyBinds k = new KeyBinds();
        k.resetAll();
        return k;
    }

    /** The binds gameplay and menus read. */
    public static KeyBinds active() { return active; }

    /** Make {@code binds} the set every scene reads (null restores defaults). */
    public static void install(KeyBinds binds) {
        active = binds != null ? binds : defaults();
    }

    // --- reading ---------------------------------------------------------------

    /** The binding in {@code slot} of {@code action}; never null. */
    public InputBinding binding(GameAction action, int slot) {
        if (action == null || slot < 0 || slot >= SLOTS) return InputBinding.UNBOUND;
        return binds.get(action)[slot];
    }

    /** Every bound slot of {@code action}, primary first (may be empty). */
    public List<InputBinding> bindings(GameAction action) {
        List<InputBinding> out = new ArrayList<>(SLOTS);
        if (action == null) return out;
        for (InputBinding b : binds.get(action)) {
            if (b.isBound()) out.add(b);
        }
        return out;
    }

    /** What the controls menu shows for an action: {@code "A  ·  Left"}. */
    public String describe(GameAction action) {
        List<InputBinding> bound = bindings(action);
        if (bound.isEmpty()) return "Unbound";
        StringBuilder sb = new StringBuilder();
        for (InputBinding b : bound) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(b.display());
        }
        return sb.toString();
    }

    /**
     * The primary binding's name, for on-screen prompts — {@code "E"},
     * {@code "Left Mouse"}. A prompt built with this reads correctly after a
     * rebind, instead of telling the player to press a key that no longer does
     * anything.
     */
    public String labelOf(GameAction action) {
        List<InputBinding> bound = bindings(action);
        return bound.isEmpty() ? "unbound" : bound.get(0).display();
    }

    // --- writing ---------------------------------------------------------------

    /** Put {@code binding} in a slot (null or unbound clears it). */
    public void set(GameAction action, int slot, InputBinding binding) {
        if (action == null || slot < 0 || slot >= SLOTS) return;
        binds.get(action)[slot] = binding == null ? InputBinding.UNBOUND : binding;
    }

    public void clear(GameAction action, int slot) {
        set(action, slot, InputBinding.UNBOUND);
    }

    /** Put one action back on the keys it ships with. */
    public void reset(GameAction action) {
        if (action == null) return;
        InputBinding[] slots = emptySlots();
        List<InputBinding> def = action.defaults();
        for (int i = 0; i < SLOTS && i < def.size(); i++) slots[i] = def.get(i);
        binds.put(action, slots);
    }

    /** Put every action back on the keys it ships with. */
    public void resetAll() {
        for (GameAction a : GameAction.values()) reset(a);
    }

    /** An independent copy (for editing without touching the active set). */
    public KeyBinds copy() {
        KeyBinds k = new KeyBinds();
        for (GameAction a : GameAction.values()) {
            for (int slot = 0; slot < SLOTS; slot++) k.set(a, slot, binding(a, slot));
        }
        return k;
    }

    /** Whether this set matches the shipped defaults exactly. */
    public boolean isDefault() {
        KeyBinds def = defaults();
        for (GameAction a : GameAction.values()) {
            for (int slot = 0; slot < SLOTS; slot++) {
                if (!binding(a, slot).equals(def.binding(a, slot))) return false;
            }
        }
        return true;
    }

    // --- querying against live input ------------------------------------------

    /** Whether any of {@code action}'s bindings is being held. */
    public boolean isDown(InputManager input, GameAction action) {
        if (action == null) return false;
        for (InputBinding b : binds.get(action)) {
            if (b.isDown(input)) return true;
        }
        return false;
    }

    /** Whether any of {@code action}'s bindings went down this tick. */
    public boolean isJustPressed(InputManager input, GameAction action) {
        return isJustPressed(input, action, false);
    }

    /**
     * {@link #isJustPressed(InputManager, GameAction)}, optionally ignoring
     * bindings that type a character. Text fields pass {@code true} so a menu
     * key moved onto a letter still types that letter into the field it is
     * aimed at instead of also working the menu behind it.
     */
    public boolean isJustPressed(InputManager input, GameAction action, boolean ignoreTypable) {
        if (action == null) return false;
        for (InputBinding b : binds.get(action)) {
            if (ignoreTypable && b.isTypable()) continue;
            if (b.isJustPressed(input)) return true;
        }
        return false;
    }

    // --- conflicts ---------------------------------------------------------------

    /**
     * The other actions in the same {@linkplain GameAction.Category category}
     * that share a binding with {@code action}. Two categories overlapping is
     * not a conflict — the left mouse button attacks in play and paints in the
     * editor, and never both at once.
     */
    public List<GameAction> conflicts(GameAction action) {
        List<GameAction> out = new ArrayList<>();
        if (action == null) return out;
        List<InputBinding> mine = bindings(action);
        if (mine.isEmpty()) return out;
        for (GameAction other : GameAction.values()) {
            if (other == action || other.category() != action.category()) continue;
            for (InputBinding b : bindings(other)) {
                if (mine.contains(b)) {
                    out.add(other);
                    break;
                }
            }
        }
        return out;
    }

    public boolean hasConflict(GameAction action) {
        return !conflicts(action).isEmpty();
    }

    /** Every action currently sharing a binding with another in its category. */
    public List<GameAction> allConflicts() {
        List<GameAction> out = new ArrayList<>();
        for (GameAction a : GameAction.values()) {
            if (hasConflict(a)) out.add(a);
        }
        return out;
    }

    // --- persistence ---------------------------------------------------------------

    /** This set as JSON-ready maps/lists (see {@link KeyBindStore}). */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        Map<String, Object> out = new LinkedHashMap<>();
        for (GameAction a : GameAction.values()) {
            List<Object> tokens = new ArrayList<>(SLOTS);
            for (InputBinding b : binds.get(a)) {
                if (b.isBound()) tokens.add(b.token());
            }
            out.put(a.id(), tokens);
        }
        root.put("binds", out);
        return root;
    }

    /**
     * Read a saved set back. An action the file does not mention keeps its
     * default — so binds saved by an older build still load when the engine
     * grows a new action — while one listed with no bindings stays deliberately
     * unbound. Unknown action ids and unparseable bindings are dropped.
     */
    public static KeyBinds fromMap(Map<String, Object> root) {
        KeyBinds k = defaults();
        if (root == null) return k;
        Object bindsNode = root.get("binds");
        if (!(bindsNode instanceof Map<?, ?> map)) return k;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            GameAction action = GameAction.byId(String.valueOf(e.getKey()));
            if (action == null) continue;
            k.binds.put(action, emptySlots());
            if (!(e.getValue() instanceof List<?> list)) continue;
            int slot = 0;
            for (Object token : list) {
                if (slot >= SLOTS) break;
                InputBinding b = InputBinding.parse(String.valueOf(token));
                if (b.isBound()) k.set(action, slot++, b);
            }
        }
        return k;
    }

    // --- shorthands on the active set --------------------------------------------

    /** {@link #isDown} against the {@linkplain #active() active} binds. */
    public static boolean down(InputManager input, GameAction action) {
        return active.isDown(input, action);
    }

    /** {@link #isJustPressed} against the {@linkplain #active() active} binds. */
    public static boolean pressed(InputManager input, GameAction action) {
        return active.isJustPressed(input, action);
    }

    /** {@link #isJustPressed(InputManager, GameAction, boolean)} on the active binds. */
    public static boolean pressed(InputManager input, GameAction action, boolean ignoreTypable) {
        return active.isJustPressed(input, action, ignoreTypable);
    }

    /** {@link #labelOf} against the active binds — for HUD prompts. */
    public static String label(GameAction action) {
        return active.labelOf(action);
    }

    private static InputBinding[] emptySlots() {
        InputBinding[] slots = new InputBinding[SLOTS];
        java.util.Arrays.fill(slots, InputBinding.UNBOUND);
        return slots;
    }
}
