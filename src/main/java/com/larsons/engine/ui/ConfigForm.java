package com.larsons.engine.ui;

import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputBinding;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;

import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * A clickable settings form: a vertical list of labelled controls — toggles,
 * numeric steppers, enum cyclers, a text field, and action buttons — navigable
 * by keyboard (up/down to move, left/right to adjust, Enter to activate) and
 * mouse (hover to select, click controls/buttons).
 *
 * <p>This is the shared widget behind the launch-time game-type editor and the
 * in-game pause menu, so the same toggles work in both places. Each control
 * reads/writes its value through supplier/consumer lambdas, so the form edits a
 * {@code GameProfile} (or anything else) in place. Rows can be conditionally
 * disabled (greyed out and skipped) via {@code enabledWhen} — e.g. the zoom
 * range is disabled when zoom itself is off.
 *
 * <p>{@link #addKeyBind} adds a rebinding row: two slots per action, each
 * showing the key or mouse button it holds. Activating a slot puts the form
 * into <em>capture</em> — the next press of anything at all lands in that slot
 * — which is why a form is asked {@link #isCapturing()} before anything else
 * reads input. Because it is an ordinary row type, controls can be rebound
 * wherever a form fits: the dedicated controls screen, an in-game pause menu,
 * or beside a game type's other settings.
 *
 * <p>A row is laid out control-first: the control is right-aligned in the
 * content column, and the label gets whatever is left of the column, shortened
 * with an ellipsis if it needs to be (see {@link UiText}). Nothing a caller
 * passes in — a wordy label, a level name in a cycler, a long path typed into a
 * field — can therefore be drawn over the control next to it or outside the
 * column.
 */
public class ConfigForm {

    /** What kind of control a row renders as. */
    public enum Control { TOGGLE, STEPPER, CYCLER, TEXT, ACTION, SLIDER, NOTE, KEYBIND }

    /** Shared empty box for rows without a given sub-control. */
    private static final Rectangle EMPTY = new Rectangle();

    /** Base class for a form row. */
    public abstract static class Option {
        final String label;
        BooleanSupplier enabledWhen;          // null => always enabled
        boolean enabled = true;               // recomputed each frame
        boolean selectable = true;            // false for rows that only explain

        final Rectangle rowBox = new Rectangle();
        final Rectangle decBox = new Rectangle();   // [-] or '<'
        final Rectangle incBox = new Rectangle();   // [+] or '>'
        final Rectangle mainBox = new Rectangle();  // toggle pill / text field / button
        final Rectangle labelBox = new Rectangle(); // the label as actually drawn

        Option(String label) { this.label = label; }

        public Option enabledWhen(BooleanSupplier cond) { this.enabledWhen = cond; return this; }

        public boolean isEnabled() { return enabled; }
        /** Whether keyboard/mouse selection can land on this row. */
        public boolean isSelectable() { return selectable; }
        public String label() { return label; }

        /** Row hit box (computed during render). */
        public Rectangle rowBounds() { return rowBox; }
        /**
         * Where the label was actually drawn (computed during render) — after
         * any shortening, so this never reaches into the control's boxes.
         */
        public Rectangle labelBounds() { return labelBox; }
        /** Activate hit box: toggle pill / text field / action button (0-size if n/a). */
        public Rectangle mainBounds() { return mainBox; }
        /** Decrement / previous hit box for steppers & cyclers (0-size if n/a). */
        public Rectangle decBounds() { return decBox; }
        /** Increment / next hit box for steppers & cyclers (0-size if n/a). */
        public Rectangle incBounds() { return incBox; }
        /**
         * Hit box of binding slot {@code slot} on a key-bind row (0-size for
         * every other row, and for a slot scrolled off screen).
         */
        public Rectangle keyBindSlotBounds(int slot) { return EMPTY; }

        /** Which control this row renders as. */
        public abstract Control control();
        abstract String valueText();
        void adjust(int dir) {}
        void activate() {}
        boolean isText() { return false; }
        void typeChars(String s) {}
        void backspace() {}
    }

    static final class ToggleOption extends Option {
        final BooleanSupplier get;
        final Consumer<Boolean> set;
        ToggleOption(String label, BooleanSupplier get, Consumer<Boolean> set) {
            super(label); this.get = get; this.set = set;
        }
        @Override public Control control() { return Control.TOGGLE; }
        @Override String valueText() { return get.getAsBoolean() ? "ON" : "OFF"; }
        @Override void adjust(int dir) { set.accept(!get.getAsBoolean()); }
        @Override void activate() { set.accept(!get.getAsBoolean()); }
    }

    static final class IntOption extends Option {
        final IntSupplier get; final IntConsumer set; final int min, max, step;
        IntOption(String label, IntSupplier get, IntConsumer set, int min, int max, int step) {
            super(label); this.get = get; this.set = set; this.min = min; this.max = max; this.step = step;
        }
        @Override public Control control() { return Control.STEPPER; }
        @Override String valueText() { return Integer.toString(get.getAsInt()); }
        @Override void adjust(int dir) {
            set.accept(Math.max(min, Math.min(max, get.getAsInt() + dir * step)));
        }
        @Override void activate() { adjust(1); }
    }

    static final class DoubleOption extends Option {
        final DoubleSupplier get; final DoubleConsumer set; final double min, max, step;
        DoubleOption(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, double step) {
            super(label); this.get = get; this.set = set; this.min = min; this.max = max; this.step = step;
        }
        @Override public Control control() { return Control.STEPPER; }
        @Override String valueText() {
            double v = Math.round(get.getAsDouble() * 100.0) / 100.0;
            return (v == Math.floor(v)) ? String.format("%.1f", v) : String.valueOf(v);
        }
        @Override void adjust(int dir) {
            double v = get.getAsDouble() + dir * step;
            v = Math.round(v * 100.0) / 100.0;
            set.accept(Math.max(min, Math.min(max, v)));
        }
        @Override void activate() { adjust(1); }
    }

    static final class EnumOption<T> extends Option {
        final T[] values; final Supplier<T> get; final Consumer<T> set;
        EnumOption(String label, T[] values, Supplier<T> get, Consumer<T> set) {
            super(label); this.values = values; this.get = get; this.set = set;
        }
        @Override public Control control() { return Control.CYCLER; }
        @Override String valueText() { return String.valueOf(get.get()); }
        @Override void adjust(int dir) {
            int idx = 0;
            T cur = get.get();
            for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) { idx = i; break; }
            idx = (idx + dir + values.length) % values.length;
            set.accept(values[idx]);
        }
        @Override void activate() { adjust(1); }
    }

    static final class TextOption extends Option {
        final Supplier<String> get; final Consumer<String> set; final int maxLen;
        TextOption(String label, Supplier<String> get, Consumer<String> set, int maxLen) {
            super(label); this.get = get; this.set = set; this.maxLen = maxLen;
        }
        @Override public Control control() { return Control.TEXT; }
        @Override String valueText() { return get.get(); }
        @Override boolean isText() { return true; }
        @Override void typeChars(String s) {
            String cur = get.get();
            StringBuilder sb = new StringBuilder(cur);
            for (int i = 0; i < s.length() && sb.length() < maxLen; i++) sb.append(s.charAt(i));
            set.accept(sb.toString());
        }
        @Override void backspace() {
            String cur = get.get();
            if (!cur.isEmpty()) set.accept(cur.substring(0, cur.length() - 1));
        }
    }

    static final class ActionOption extends Option {
        final Runnable action;
        ActionOption(String label, Runnable action) { super(label); this.action = action; }
        @Override public Control control() { return Control.ACTION; }
        @Override String valueText() { return ""; }
        @Override void activate() { if (action != null) action.run(); }
    }

    /**
     * A caption: prose explaining the rows around it — what the ability you just
     * picked does, what a mini-game mode means. It wraps across the whole
     * content column at the theme's smaller note font, and the selection skips
     * over it, because there is nothing on it to activate.
     */
    static final class NoteOption extends Option {
        NoteOption(String text) { super(text); selectable = false; }
        @Override public Control control() { return Control.NOTE; }
        @Override String valueText() { return ""; }
    }

    /** A draggable horizontal slider over an int range (drag, click, or arrow keys). */
    static final class SliderOption extends Option {
        final IntSupplier get; final IntConsumer set; final int min, max;
        SliderOption(String label, IntSupplier get, IntConsumer set, int min, int max) {
            super(label); this.get = get; this.set = set; this.min = min; this.max = Math.max(min + 1, max);
        }
        @Override public Control control() { return Control.SLIDER; }
        @Override String valueText() { return Integer.toString(get.getAsInt()); }
        @Override void adjust(int dir) {
            int step = Math.max(1, (max - min) / 50);
            set.accept(Math.max(min, Math.min(max, get.getAsInt() + dir * step)));
        }
        @Override void activate() { adjust(1); }
        void setFromMouse(int mx) {
            if (mainBox.width <= 0) return;
            double t = (mx - mainBox.x) / (double) mainBox.width;
            set.accept(min + (int) Math.round(Math.max(0, Math.min(1, t)) * (max - min)));
        }
    }

    /**
     * A rebinding row: one action, and a box per binding slot showing the key
     * or mouse button in it. The row itself holds no state beyond which slot
     * has focus — the bindings live in the {@link KeyBinds} it was given, so a
     * rebind is in force the moment it is made.
     */
    static final class KeyBindOption extends Option {
        final KeyBinds binds;
        final GameAction action;
        final Rectangle[] slotBox = new Rectangle[KeyBinds.SLOTS];
        int slot; // which box the keyboard is working on

        KeyBindOption(String label, KeyBinds binds, GameAction action) {
            super(label);
            this.binds = binds;
            this.action = action;
            for (int i = 0; i < slotBox.length; i++) slotBox[i] = new Rectangle();
        }

        @Override public Control control() { return Control.KEYBIND; }
        @Override public Rectangle keyBindSlotBounds(int slot) {
            return slot >= 0 && slot < slotBox.length ? slotBox[slot] : EMPTY;
        }
        @Override String valueText() { return binds.describe(action); }
        /** Left/right move between the primary and alternate slot. */
        @Override void adjust(int dir) {
            slot = Math.floorMod(slot + dir, KeyBinds.SLOTS);
        }
    }

    /** Clear space kept between a row's label and the control it belongs to. */
    private static final int LABEL_GAP = 16;
    /** Space either side of an action button's label. */
    private static final int BUTTON_PAD = 40;
    /** Inset of a text field's value from the field's edges. */
    private static final int FIELD_PAD = 8;
    /**
     * How wide a text field may be. It takes whatever room its label leaves,
     * between these bounds, so a short-labelled field (a path, say) gets more of
     * the column to show its value in.
     */
    private static final int MIN_FIELD_W = 240, MAX_FIELD_W = 380;
    /** Widest a stepper/cycler value box grows before its text is shortened. */
    private static final int MAX_VALUE_W = 200;
    /** Width of one binding slot box on a key-bind row. */
    private static final int BIND_BOX_W = 150;
    /** Gap between the two binding slot boxes. */
    private static final int BIND_GAP = 8;

    /** The selected row's wash, and the scroll track's. Theme colours do the rest. */
    private static final int ROW_HIGHLIGHT = new Color(255, 255, 255, 18).getRGB();
    private static final int TRACK = new Color(255, 255, 255, 28).getRGB();
    private static final int SLIDER_TRACK_OFF = new Color(80, 80, 90).getRGB();

    private final List<Option> options = new ArrayList<>();
    private MenuTheme theme = MenuTheme.defaultTheme();
    private String title;
    private int selected;
    private int rowHeight = 44;

    // Scrolling state for forms taller than the viewport.
    private int scroll;              // index of the first visible row
    private int visibleCount = 1;    // rows that fit; recomputed each render
    private int maxScroll;           // options.size() - visibleCount, clamped >= 0
    // A draggable scroll bar down the right edge. Its geometry is computed during
    // render() and hit-tested a frame later in update() — the same deferred
    // pattern the row boxes use. Dragging the thumb (or the mouse wheel) scrolls
    // the view directly without moving the selection; keyboard navigation still
    // pulls the selected row back into view (see followSelection).
    private final Rectangle scrollTrack = new Rectangle();
    private final Rectangle scrollThumb = new Rectangle();
    private SliderOption draggingSlider; // slider thumb being dragged, or null
    private boolean draggingThumb;
    private int dragGrabOffset;      // cursor offset inside the thumb at grab time
    private boolean followSelection; // bring the selection into view next render

    // Key-bind capture: the row and slot waiting for a press, and who to tell
    // once one lands (the controls screen saves the file from there).
    private KeyBindOption capturing;
    private int captureSlot;
    private Runnable bindListener;

    public ConfigForm(String title) { this.title = title; }

    public ConfigForm theme(MenuTheme t) { this.theme = t; return this; }
    public ConfigForm rowHeight(int h) { this.rowHeight = h; return this; }
    public MenuTheme theme() { return theme; }
    public List<Option> options() { return options; }

    public Option addToggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
        return add(new ToggleOption(label, get, set));
    }
    public Option addInt(String label, IntSupplier get, IntConsumer set, int min, int max, int step) {
        return add(new IntOption(label, get, set, min, max, step));
    }
    public Option addDouble(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, double step) {
        return add(new DoubleOption(label, get, set, min, max, step));
    }
    public <T> Option addEnum(String label, T[] values, Supplier<T> get, Consumer<T> set) {
        return add(new EnumOption<>(label, values, get, set));
    }
    public Option addText(String label, Supplier<String> get, Consumer<String> set, int maxLen) {
        return add(new TextOption(label, get, set, maxLen));
    }
    public Option addAction(String label, Runnable action) {
        return add(new ActionOption(label, action));
    }
    /**
     * Add a caption row: explanatory prose that wraps across the column instead
     * of being squeezed onto one line. Use this rather than a do-nothing
     * {@link #addAction} for text that describes rather than does something.
     */
    public Option addNote(String text) {
        return add(new NoteOption(text));
    }
    public Option addSlider(String label, IntSupplier get, IntConsumer set, int min, int max) {
        return add(new SliderOption(label, get, set, min, max));
    }

    /**
     * Add a rebinding row for {@code action}, editing {@code binds} in place.
     * The row is labelled with the action's own name; pass a label to override
     * it (the controls screen does not need to).
     */
    public Option addKeyBind(KeyBinds binds, GameAction action) {
        return addKeyBind(action.label(), binds, action);
    }

    public Option addKeyBind(String label, KeyBinds binds, GameAction action) {
        return add(new KeyBindOption(label, binds, action));
    }

    /** Called whenever a binding is assigned or cleared (for saving). */
    public ConfigForm onKeyBindChange(Runnable listener) {
        this.bindListener = listener;
        return this;
    }

    /** Whether the form is waiting for the player to press their new binding. */
    public boolean isCapturing() { return capturing != null; }

    /** The action being rebound, or {@code null} when not capturing. */
    public GameAction capturingAction() {
        return capturing == null ? null : capturing.action;
    }

    private Option add(Option o) { options.add(o); return o; }

    public void update(double dt, InputManager input) {
        if (options.isEmpty()) return;

        // Capturing owns the frame: every press belongs to the binding being
        // set, not to the menu it is being set from.
        if (capturing != null) {
            updateCapture(input);
            return;
        }

        for (Option o : options) {
            o.enabled = o.enabledWhen == null || o.enabledWhen.getAsBoolean();
        }
        ensureSelectedEnabled(1);

        // Navigation runs on the player's own binds. While a text field is
        // being edited, bindings that type a character are ignored, so a menu
        // key moved onto a letter still spells that letter into the field.
        boolean editing = options.get(selected).enabled && options.get(selected).isText();
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN, editing)) move(1);
        if (KeyBinds.pressed(input, GameAction.MENU_UP, editing)) move(-1);
        // Mouse wheel scrolls the view directly, leaving the selection put.
        int wheel = input.getWheelRotation();
        if (wheel != 0) scroll = clampScroll(scroll + wheel);

        Option sel = options.get(selected);
        boolean selText = sel.enabled && sel.isText();

        if (sel.enabled && !selText) {
            if (KeyBinds.pressed(input, GameAction.MENU_LEFT)) sel.adjust(-1);
            if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)) sel.adjust(1);
            if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
                if (sel instanceof KeyBindOption kb) beginCapture(kb, kb.slot);
                else sel.activate();
                if (capturing != null) return; // the press that opened it is spent
            }
            // Delete/Backspace empties the focused binding slot — the way to
            // say "this action has no key" without having to press one.
            if (sel instanceof KeyBindOption kb
                    && (input.isKeyJustPressed(KeyEvent.VK_DELETE)
                    || input.isKeyJustPressed(KeyEvent.VK_BACK_SPACE))) {
                kb.binds.clear(kb.action, kb.slot);
                if (bindListener != null) bindListener.run();
            }
        }

        // Text editing for the selected text field. Keystrokes aimed anywhere
        // else need no draining here: InputManager scopes typed characters to
        // the tick they arrived in, so a field only ever receives what was
        // typed while it was the one selected.
        if (selText) {
            String typed = input.consumeTypedChars();
            if (!typed.isEmpty()) sel.typeChars(typed);
            if (input.isKeyJustPressed(KeyEvent.VK_BACK_SPACE)) sel.backspace();
        }

        // Mouse: hover selects, click hits sub-controls.
        int mx = input.getMouseX(), my = input.getMouseY();
        boolean click = input.isMouseJustPressed();

        // A slider drag in progress owns the mouse until release.
        if (draggingSlider != null) {
            if (!input.isMouseDown()) {
                draggingSlider = null;
            } else {
                draggingSlider.setFromMouse(mx);
                return;
            }
        }

        // The scroll bar takes precedence over row interaction while in use.
        if (handleScrollBar(input, mx, my, click)) return;

        boolean rightClick = input.isRightMouseJustPressed();

        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (!o.enabled || !o.selectable) continue;
            if (o.rowBox.contains(mx, my)) {
                selected = i;
                if (o instanceof KeyBindOption kb && (click || rightClick)) {
                    // Click a slot to rebind it, right-click to empty it.
                    for (int s = 0; s < KeyBinds.SLOTS; s++) {
                        if (!kb.slotBox[s].contains(mx, my)) continue;
                        kb.slot = s;
                        if (click) {
                            beginCapture(kb, s);
                        } else {
                            kb.binds.clear(kb.action, s);
                            if (bindListener != null) bindListener.run();
                        }
                        return;
                    }
                } else if (click) {
                    if (o.decBox.width > 0 && o.decBox.contains(mx, my)) o.adjust(-1);
                    else if (o instanceof SliderOption s && s.mainBox.contains(mx, my)) {
                        draggingSlider = s;
                        s.setFromMouse(mx);
                    }
                    else if (o.incBox.width > 0 && o.incBox.contains(mx, my)) o.adjust(1);
                    else if (o.mainBox.width > 0 && o.mainBox.contains(mx, my)) o.activate();
                    else if (o.control() == Control.ACTION) o.activate();
                }
            }
        }
    }

    private void beginCapture(KeyBindOption row, int slot) {
        capturing = row;
        captureSlot = slot;
        row.slot = slot;
    }

    /**
     * Wait for the press that becomes the new binding. Anything the hardware
     * reports counts — letters, function keys, the numpad, any mouse button —
     * with Ctrl/Shift/Alt folded in when they were held, which is how
     * {@code Ctrl+S} gets bound. Escape backs out, leaving the slot as it was,
     * so a capture opened by accident is never a trap.
     */
    private void updateCapture(InputManager input) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            capturing = null;
            return;
        }
        InputBinding pressed = input.consumeAnyPress();
        if (pressed == null || !pressed.isBound()) return;
        capturing.binds.set(capturing.action, captureSlot, pressed);
        capturing = null;
        if (bindListener != null) bindListener.run();
    }

    private void move(int dir) {
        int n = options.size();
        for (int step = 0; step < n; step++) {
            selected = (selected + dir + n) % n;
            Option o = options.get(selected);
            if (o.enabled && o.selectable) { followSelection = true; return; }
        }
    }

    private int clampScroll(int s) {
        return Math.max(0, Math.min(maxScroll, s));
    }

    /**
     * Start/continue/finish a scroll-bar thumb drag, or jump the view when the
     * track is clicked. Returns true while the pointer is working the bar, so the
     * caller skips row hit-testing this frame. Geometry comes from the previous
     * render (see {@link #drawScrollBar}).
     */
    private boolean handleScrollBar(InputManager input, int mx, int my, boolean click) {
        if (draggingThumb) {
            if (!input.isMouseDown()) { draggingThumb = false; return true; }
            int travel = scrollTrack.height - scrollThumb.height;
            if (travel > 0) {
                int rel = Math.max(0, Math.min(travel, my - dragGrabOffset - scrollTrack.y));
                scroll = Math.round((float) rel / travel * maxScroll);
            }
            return true;
        }
        if (!click || maxScroll <= 0 || scrollTrack.width <= 0) return false;
        if (scrollThumb.contains(mx, my)) {
            draggingThumb = true;
            dragGrabOffset = my - scrollThumb.y;
            return true;
        }
        if (scrollTrack.contains(mx, my)) {
            // Click on the track jumps so the thumb centres on the pointer.
            int travel = scrollTrack.height - scrollThumb.height;
            int rel = Math.max(0, Math.min(travel, my - scrollTrack.y - scrollThumb.height / 2));
            scroll = travel > 0 ? Math.round((float) rel / travel * maxScroll) : 0;
            return true;
        }
        return false;
    }

    private void ensureSelectedEnabled(int dir) {
        if (selected < 0 || selected >= options.size()) selected = 0;
        Option o = options.get(selected);
        if (!o.enabled || !o.selectable) move(dir);
    }

    public void render(DrawTarget target, int viewportW, int viewportH) {
        int contentW = Math.min(640, viewportW - 80);
        int contentX = (viewportW - contentW) / 2;

        Font titleFont = theme.titleFont;
        int titleY = Math.max(target.textAscent(titleFont) + 24, viewportH / 8);
        // Titles carry names the creator chose ("Settings — <level>"), so they
        // are shortened to the room right of the column rather than run off it.
        if (title != null) {
            target.drawText(UiText.fit(target, titleFont, title, viewportW - contentX - 24),
                    contentX, titleY, titleFont, theme.title);
        }

        Font itemFont = theme.itemFont;
        int ascent = target.textAscent(itemFont);
        int lineHeight = target.textHeight(itemFont);
        int startY = titleY + 50 + ascent;

        // Long forms scroll. Capture the layout as fields so update() can drive
        // the scroll bar and wheel on the next frame.
        visibleCount = Math.max(1, (viewportH - 48 - startY) / rowHeight + 1);
        maxScroll = Math.max(0, options.size() - visibleCount);
        // Keyboard navigation pulls the selected row into view; free scrolling
        // (wheel / scroll-bar drag) leaves the view where the user put it.
        if (followSelection) {
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + visibleCount) scroll = selected - visibleCount + 1;
            followSelection = false;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);

            // Reset sub-control boxes; only the relevant ones get sizes.
            o.rowBox.setBounds(0, 0, 0, 0);
            o.decBox.setBounds(0, 0, 0, 0);
            o.incBox.setBounds(0, 0, 0, 0);
            o.mainBox.setBounds(0, 0, 0, 0);
            o.labelBox.setBounds(0, 0, 0, 0);
            if (o instanceof KeyBindOption kb) {
                for (Rectangle box : kb.slotBox) box.setBounds(0, 0, 0, 0);
            }

            if (i < scroll || i >= scroll + visibleCount) continue; // off-screen

            int baseY = startY + (i - scroll) * rowHeight;
            int boxTop = baseY - ascent - 2;
            int boxH = lineHeight + 6;
            o.rowBox.setBounds(contentX - 8, boxTop, contentW + 16, boxH);

            Color labelColor = !o.enabled ? theme.itemDisabled
                    : (i == selected ? theme.itemSelected : theme.item);

            if (i == selected && o.enabled && o.selectable) {
                target.fillRect(o.rowBox.x, o.rowBox.y, o.rowBox.width, o.rowBox.height,
                        ROW_HIGHLIGHT);
            }

            if (o.control() == Control.NOTE) {
                renderNoteRow(target, o, contentX, contentW, boxTop);
                continue;
            }

            if (o.control() == Control.ACTION) {
                renderActionRow(target, o, contentX, contentW, baseY, boxTop, boxH, labelColor);
                continue;
            }

            // The control is placed first so the label knows how much room is
            // left: a long label is shortened, never drawn over the field.
            int controlLeft = renderValue(target, o, contentX, contentW,
                    baseY, boxTop, boxH, labelColor);
            String label = UiText.fit(target, itemFont, o.label,
                    controlLeft - LABEL_GAP - contentX);
            o.labelBox.setBounds(contentX, boxTop, target.textWidth(label, itemFont), boxH);
            target.drawText(label, contentX, baseY, itemFont, labelColor);
        }

        drawScrollBar(target, ascent, contentX, contentW, startY, viewportH);
    }

    /**
     * Draw a scroll bar down the right margin when the form overflows, sizing the
     * thumb to the visible fraction and positioning it by {@link #scroll}. Records
     * the track/thumb boxes for {@link #handleScrollBar} to hit-test next frame.
     */
    private void drawScrollBar(DrawTarget target, int ascent,
                               int contentX, int contentW, int startY, int viewportH) {
        scrollTrack.setBounds(0, 0, 0, 0);
        scrollThumb.setBounds(0, 0, 0, 0);
        if (maxScroll <= 0) return; // everything fits; no bar needed

        int barW = 10;
        int barX = contentX + contentW + 14;
        int barTop = startY - ascent - 2;
        int barBottom = Math.min(viewportH - 24, barTop + visibleCount * rowHeight);
        int barH = Math.max(rowHeight, barBottom - barTop);
        scrollTrack.setBounds(barX, barTop, barW, barH);

        int rows = options.size();
        int thumbH = Math.min(barH, Math.max(28, Math.round((float) visibleCount / rows * barH)));
        int travel = barH - thumbH;
        int thumbY = barTop + Math.round((float) scroll / maxScroll * travel);
        scrollThumb.setBounds(barX, thumbY, barW, thumbH);

        target.fillRoundRect(barX, barTop, barW, barH, barW, barW, TRACK);
        target.fillRoundRect(barX, thumbY, barW, thumbH, barW, barW,
                draggingThumb ? theme.accent : theme.item);
    }

    /**
     * A note row: the caption wrapped across the content column at the theme's
     * note font, as many lines as the row height holds. Prose is what these
     * rows carry, so they wrap rather than being cut down to one line — only an
     * overrun past the last line is ellipsised.
     */
    private void renderNoteRow(DrawTarget target, Option o,
                               int contentX, int contentW, int boxTop) {
        Font noteFont = theme.noteFont;
        int lineH = target.textHeight(noteFont);
        int maxLines = Math.max(1, (rowHeight - 6) / lineH);
        List<String> lines = UiText.wrap(target, noteFont, o.label, contentW, maxLines);
        if (lines.isEmpty()) return;

        // Centre the block in the row slot so a one-line note sits level with
        // the rows above and below it.
        int blockH = lines.size() * lineH;
        int y = boxTop + (rowHeight - 6 - blockH) / 2 + target.textAscent(noteFont);
        int widest = 0;
        for (String line : lines) {
            target.drawText(line, contentX, y, noteFont, theme.itemDisabled);
            widest = Math.max(widest, target.textWidth(line, noteFont));
            y += lineH;
        }
        o.labelBox.setBounds(contentX, boxTop, widest, blockH);
    }

    /**
     * An action row is a centred button. The button never grows past the
     * content column — a label too wide for it is shortened rather than left to
     * spill across the screen.
     */
    private void renderActionRow(DrawTarget target, Option o, int contentX, int contentW,
                                 int baseY, int boxTop, int boxH, Color color) {
        Font font = theme.itemFont;
        String label = UiText.fit(target, font, o.label, contentW - BUTTON_PAD);
        int tw = target.textWidth(label, font);
        int bw = tw + BUTTON_PAD;
        int bx = contentX + (contentW - bw) / 2;
        o.mainBox.setBounds(bx, boxTop, bw, boxH);
        o.labelBox.setBounds(bx + (bw - tw) / 2, boxTop, tw, boxH);
        target.drawRoundRect(bx, boxTop, bw, boxH, 10, 10, color);
        target.drawText(label, bx + (bw - tw) / 2, baseY, font, color);
    }

    /**
     * Draw the row's control, right-aligned in the content column, and return
     * the x of its leftmost box — the point the row's label has to stop before.
     */
    private int renderValue(DrawTarget target, Option o, int contentX, int contentW,
                            int baseY, int boxTop, int boxH, Color color) {
        int rightEdge = contentX + contentW;
        String value = o.valueText();
        Font font = theme.itemFont;

        return switch (o.control()) {
            case TOGGLE -> {
                String text = value;
                int pw = Math.max(64, target.textWidth(text, font) + 28);
                int px = rightEdge - pw;
                o.mainBox.setBounds(px, boxTop, pw, boxH);
                boolean on = "ON".equals(text);
                Color c = o.enabled ? (on ? theme.accent : theme.itemDisabled)
                        : theme.itemDisabled;
                target.drawRoundRect(px, boxTop, pw, boxH, boxH, boxH, c);
                target.drawText(text, px + (pw - target.textWidth(text, font)) / 2, baseY,
                        font, c);
                yield px;
            }
            case STEPPER, CYCLER -> {
                boolean cycler = o.control() == Control.CYCLER;
                String dec = cycler ? "<" : "-";
                String inc = cycler ? ">" : "+";
                int boxW = 30;
                int gap = 10;
                int incX = rightEdge - boxW;
                // A cycler's values are content too (level names, door labels),
                // so the value box is capped and its text shortened instead of
                // being allowed to push the arrows back over the label.
                String shown = UiText.fit(target, font, value, MAX_VALUE_W - 16);
                int valW = Math.max(60, target.textWidth(shown, font) + 16);
                int valRight = incX - gap;
                int valX = valRight - valW;
                int decX = valX - gap - boxW;

                o.decBox.setBounds(decX, boxTop, boxW, boxH);
                o.incBox.setBounds(incX, boxTop, boxW, boxH);

                target.drawRoundRect(decX, boxTop, boxW, boxH, 8, 8, color);
                target.drawText(dec, decX + (boxW - target.textWidth(dec, font)) / 2, baseY,
                        font, color);
                target.drawRoundRect(incX, boxTop, boxW, boxH, 8, 8, color);
                target.drawText(inc, incX + (boxW - target.textWidth(inc, font)) / 2, baseY,
                        font, color);
                target.drawText(shown, valX + (valW - target.textWidth(shown, font)) / 2,
                        baseY, font, color);
                yield decX;
            }
            case TEXT -> {
                int fw = Math.max(MIN_FIELD_W, Math.min(MAX_FIELD_W,
                        contentW - target.textWidth(o.label, font) - LABEL_GAP));
                int fx = rightEdge - fw;
                o.mainBox.setBounds(fx, boxTop, fw, boxH);
                target.drawRoundRect(fx, boxTop, fw, boxH, 8, 8,
                        o.enabled ? theme.item : theme.itemDisabled);
                boolean editing = options.get(selected) == o;
                // Show the end of the value: typing appends, so the tail is the
                // part being worked on. Anything longer is cut at the field's
                // edge instead of running on across the screen.
                String shown = UiText.fitTail(target, font, value + (editing ? "_" : ""),
                        fw - 2 * FIELD_PAD);
                target.drawText(shown, fx + FIELD_PAD, baseY, font,
                        o.enabled ? theme.title : theme.itemDisabled);
                yield fx;
            }
            case SLIDER -> {
                SliderOption s = (SliderOption) o;
                int valW = Math.max(48, target.textWidth(String.valueOf(s.max), font) + 12);
                int trackW = 200;
                int trackX = rightEdge - valW - trackW;
                o.mainBox.setBounds(trackX, boxTop, trackW, boxH);
                double t = (s.get.getAsInt() - s.min) / (double) (s.max - s.min);
                t = Math.max(0, Math.min(1, t));
                int cy = boxTop + boxH / 2;
                if (o.enabled) {
                    target.fillRoundRect(trackX, cy - 2, trackW, 4, 4, 4, theme.itemDisabled);
                } else {
                    target.fillRoundRect(trackX, cy - 2, trackW, 4, 4, 4, SLIDER_TRACK_OFF);
                }
                Color fill = o.enabled ? theme.accent : theme.itemDisabled;
                target.fillRoundRect(trackX, cy - 2, (int) (trackW * t), 4, 4, 4, fill);
                int thumbX = trackX + (int) (trackW * t);
                target.fillOval(thumbX - 6, cy - 7, 14, 14, fill);
                target.drawText(value, rightEdge - valW + 8, baseY, font, color);
                yield trackX;
            }
            case KEYBIND -> {
                KeyBindOption kb = (KeyBindOption) o;
                boolean rowSelected = options.get(selected) == o;
                boolean clash = kb.binds.hasConflict(kb.action);
                // Bindings are set a little smaller than the labels they sit
                // beside: "Middle Mouse" has to fit its box whole, and a name
                // cut to "Middle Mou…" is no use to the player reading it.
                Font bindFont = font.deriveFont(font.getSize2D() * 0.78f);
                int x = rightEdge;
                for (int s = KeyBinds.SLOTS - 1; s >= 0; s--) {
                    x -= BIND_BOX_W;
                    kb.slotBox[s].setBounds(x, boxTop, BIND_BOX_W, boxH);
                    boolean waiting = capturing == kb && captureSlot == s;
                    InputBinding b = kb.binds.binding(kb.action, s);
                    String text = waiting ? "Press…" : (b.isBound() ? b.display() : "—");
                    Color box = !o.enabled ? theme.itemDisabled
                            : waiting ? theme.accent
                            : (clash && b.isBound()) ? theme.warning
                            : (rowSelected && kb.slot == s) ? theme.itemSelected
                            : theme.itemDisabled;
                    target.drawRoundRect(x, boxTop, BIND_BOX_W, boxH, 8, 8, box);
                    String shown = UiText.fit(target, bindFont, text, BIND_BOX_W - 12);
                    target.drawText(shown,
                            x + (BIND_BOX_W - target.textWidth(shown, bindFont)) / 2, baseY,
                            bindFont, !o.enabled ? theme.itemDisabled
                                    : waiting ? theme.accent
                                    : (clash && b.isBound()) ? theme.warning : theme.title);
                    x -= BIND_GAP;
                }
                yield x + BIND_GAP;
            }
            default -> rightEdge;
        };
    }

    public int getSelectedIndex() { return selected; }

    /** Index of the first visible row (top of the scrolled window). */
    public int getScroll() { return scroll; }

    /** Scroll-bar track hit box (computed during render; 0-size when the form fits). */
    public Rectangle scrollTrackBounds() { return scrollTrack; }

    /** Scroll-bar thumb hit box (computed during render; 0-size when the form fits). */
    public Rectangle scrollThumbBounds() { return scrollThumb; }
}
