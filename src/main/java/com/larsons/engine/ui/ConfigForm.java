package com.larsons.engine.ui;

import com.larsons.engine.input.InputManager;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
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
 */
public class ConfigForm {

    /** What kind of control a row renders as. */
    public enum Control { TOGGLE, STEPPER, CYCLER, TEXT, ACTION, SLIDER }

    /** Base class for a form row. */
    public abstract static class Option {
        final String label;
        BooleanSupplier enabledWhen;          // null => always enabled
        boolean enabled = true;               // recomputed each frame

        final Rectangle rowBox = new Rectangle();
        final Rectangle decBox = new Rectangle();   // [-] or '<'
        final Rectangle incBox = new Rectangle();   // [+] or '>'
        final Rectangle mainBox = new Rectangle();  // toggle pill / text field / button

        Option(String label) { this.label = label; }

        public Option enabledWhen(BooleanSupplier cond) { this.enabledWhen = cond; return this; }

        public boolean isEnabled() { return enabled; }
        public String label() { return label; }

        /** Row hit box (computed during render). */
        public Rectangle rowBounds() { return rowBox; }
        /** Activate hit box: toggle pill / text field / action button (0-size if n/a). */
        public Rectangle mainBounds() { return mainBox; }
        /** Decrement / previous hit box for steppers & cyclers (0-size if n/a). */
        public Rectangle decBounds() { return decBox; }
        /** Increment / next hit box for steppers & cyclers (0-size if n/a). */
        public Rectangle incBounds() { return incBox; }

        abstract Control control();
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
        @Override Control control() { return Control.TOGGLE; }
        @Override String valueText() { return get.getAsBoolean() ? "ON" : "OFF"; }
        @Override void adjust(int dir) { set.accept(!get.getAsBoolean()); }
        @Override void activate() { set.accept(!get.getAsBoolean()); }
    }

    static final class IntOption extends Option {
        final IntSupplier get; final IntConsumer set; final int min, max, step;
        IntOption(String label, IntSupplier get, IntConsumer set, int min, int max, int step) {
            super(label); this.get = get; this.set = set; this.min = min; this.max = max; this.step = step;
        }
        @Override Control control() { return Control.STEPPER; }
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
        @Override Control control() { return Control.STEPPER; }
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
        @Override Control control() { return Control.CYCLER; }
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
        @Override Control control() { return Control.TEXT; }
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
        @Override Control control() { return Control.ACTION; }
        @Override String valueText() { return ""; }
        @Override void activate() { if (action != null) action.run(); }
    }

    /** A draggable horizontal slider over an int range (drag, click, or arrow keys). */
    static final class SliderOption extends Option {
        final IntSupplier get; final IntConsumer set; final int min, max;
        SliderOption(String label, IntSupplier get, IntConsumer set, int min, int max) {
            super(label); this.get = get; this.set = set; this.min = min; this.max = Math.max(min + 1, max);
        }
        @Override Control control() { return Control.SLIDER; }
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
    public Option addSlider(String label, IntSupplier get, IntConsumer set, int min, int max) {
        return add(new SliderOption(label, get, set, min, max));
    }

    private Option add(Option o) { options.add(o); return o; }

    public void update(double dt, InputManager input) {
        if (options.isEmpty()) return;

        for (Option o : options) {
            o.enabled = o.enabledWhen == null || o.enabledWhen.getAsBoolean();
        }
        ensureSelectedEnabled(1);

        if (input.isKeyJustPressed(KeyEvent.VK_DOWN)) move(1);
        if (input.isKeyJustPressed(KeyEvent.VK_UP)) move(-1);
        // Mouse wheel scrolls the view directly, leaving the selection put.
        int wheel = input.getWheelRotation();
        if (wheel != 0) scroll = clampScroll(scroll + wheel);

        Option sel = options.get(selected);
        boolean selText = sel.enabled && sel.isText();

        if (sel.enabled && !selText) {
            if (input.isKeyJustPressed(KeyEvent.VK_LEFT)) sel.adjust(-1);
            if (input.isKeyJustPressed(KeyEvent.VK_RIGHT)) sel.adjust(1);
            if (input.isKeyJustPressed(KeyEvent.VK_ENTER) || input.isKeyJustPressed(KeyEvent.VK_SPACE)) {
                sel.activate();
            }
        }

        // Text editing for the selected text field; otherwise drain the buffer
        // so stray keystrokes don't accumulate.
        if (selText) {
            String typed = input.consumeTypedChars();
            if (!typed.isEmpty()) sel.typeChars(typed);
            if (input.isKeyJustPressed(KeyEvent.VK_BACK_SPACE)) sel.backspace();
        } else {
            input.consumeTypedChars();
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

        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (!o.enabled) continue;
            if (o.rowBox.contains(mx, my)) {
                selected = i;
                if (click) {
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

    private void move(int dir) {
        int n = options.size();
        for (int step = 0; step < n; step++) {
            selected = (selected + dir + n) % n;
            if (options.get(selected).enabled) { followSelection = true; return; }
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
        if (!options.get(selected).enabled) move(dir);
    }

    public void render(Graphics2D g, int viewportW, int viewportH) {
        int contentW = Math.min(640, viewportW - 80);
        int contentX = (viewportW - contentW) / 2;

        g.setFont(theme.titleFont);
        g.setColor(theme.title);
        FontMetrics tfm = g.getFontMetrics();
        int titleY = Math.max(tfm.getAscent() + 24, viewportH / 8);
        if (title != null) g.drawString(title, contentX, titleY);

        g.setFont(theme.itemFont);
        FontMetrics fm = g.getFontMetrics();
        int startY = titleY + 50 + fm.getAscent();

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

            if (i < scroll || i >= scroll + visibleCount) continue; // off-screen

            int baseY = startY + (i - scroll) * rowHeight;
            int boxTop = baseY - fm.getAscent() - 2;
            int boxH = fm.getHeight() + 6;
            o.rowBox.setBounds(contentX - 8, boxTop, contentW + 16, boxH);

            Color labelColor = !o.enabled ? theme.itemDisabled
                    : (i == selected ? theme.itemSelected : theme.item);

            if (i == selected && o.enabled) {
                g.setColor(new Color(255, 255, 255, 18));
                g.fillRect(o.rowBox.x, o.rowBox.y, o.rowBox.width, o.rowBox.height);
            }

            if (o.control() == Control.ACTION) {
                renderActionRow(g, fm, o, contentX, contentW, baseY, boxTop, boxH, labelColor);
                continue;
            }

            g.setColor(labelColor);
            g.drawString(o.label, contentX, baseY);
            renderValue(g, fm, o, contentX, contentW, baseY, boxTop, boxH, labelColor);
        }

        drawScrollBar(g, fm, contentX, contentW, startY, viewportH);
    }

    /**
     * Draw a scroll bar down the right margin when the form overflows, sizing the
     * thumb to the visible fraction and positioning it by {@link #scroll}. Records
     * the track/thumb boxes for {@link #handleScrollBar} to hit-test next frame.
     */
    private void drawScrollBar(Graphics2D g, FontMetrics fm,
                               int contentX, int contentW, int startY, int viewportH) {
        scrollTrack.setBounds(0, 0, 0, 0);
        scrollThumb.setBounds(0, 0, 0, 0);
        if (maxScroll <= 0) return; // everything fits; no bar needed

        int barW = 10;
        int barX = contentX + contentW + 14;
        int barTop = startY - fm.getAscent() - 2;
        int barBottom = Math.min(viewportH - 24, barTop + visibleCount * rowHeight);
        int barH = Math.max(rowHeight, barBottom - barTop);
        scrollTrack.setBounds(barX, barTop, barW, barH);

        int rows = options.size();
        int thumbH = Math.min(barH, Math.max(28, Math.round((float) visibleCount / rows * barH)));
        int travel = barH - thumbH;
        int thumbY = barTop + Math.round((float) scroll / maxScroll * travel);
        scrollThumb.setBounds(barX, thumbY, barW, thumbH);

        g.setColor(new Color(255, 255, 255, 28));
        g.fillRoundRect(barX, barTop, barW, barH, barW, barW);
        g.setColor(draggingThumb ? theme.accent : theme.item);
        g.fillRoundRect(barX, thumbY, barW, thumbH, barW, barW);
    }

    private void renderActionRow(Graphics2D g, FontMetrics fm, Option o,
                                 int contentX, int contentW, int baseY, int boxTop, int boxH, Color color) {
        int tw = fm.stringWidth(o.label);
        int bw = tw + 40;
        int bx = contentX + (contentW - bw) / 2;
        o.mainBox.setBounds(bx, boxTop, bw, boxH);
        g.setColor(color);
        g.drawRoundRect(bx, boxTop, bw, boxH, 10, 10);
        g.drawString(o.label, bx + (bw - tw) / 2, baseY);
    }

    private void renderValue(Graphics2D g, FontMetrics fm, Option o,
                             int contentX, int contentW, int baseY, int boxTop, int boxH, Color color) {
        int rightEdge = contentX + contentW;
        String value = o.valueText();

        switch (o.control()) {
            case TOGGLE -> {
                String text = value;
                int pw = Math.max(64, fm.stringWidth(text) + 28);
                int px = rightEdge - pw;
                o.mainBox.setBounds(px, boxTop, pw, boxH);
                boolean on = "ON".equals(text);
                g.setColor(o.enabled ? (on ? theme.accent : theme.itemDisabled) : theme.itemDisabled);
                g.drawRoundRect(px, boxTop, pw, boxH, boxH, boxH);
                g.drawString(text, px + (pw - fm.stringWidth(text)) / 2, baseY);
            }
            case STEPPER, CYCLER -> {
                boolean cycler = o.control() == Control.CYCLER;
                String dec = cycler ? "<" : "-";
                String inc = cycler ? ">" : "+";
                int boxW = 30;
                int gap = 10;
                int incX = rightEdge - boxW;
                int valW = Math.max(60, fm.stringWidth(value) + 16);
                int valRight = incX - gap;
                int valX = valRight - valW;
                int decX = valX - gap - boxW;

                o.decBox.setBounds(decX, boxTop, boxW, boxH);
                o.incBox.setBounds(incX, boxTop, boxW, boxH);

                g.setColor(color);
                g.drawRoundRect(decX, boxTop, boxW, boxH, 8, 8);
                g.drawString(dec, decX + (boxW - fm.stringWidth(dec)) / 2, baseY);
                g.drawRoundRect(incX, boxTop, boxW, boxH, 8, 8);
                g.drawString(inc, incX + (boxW - fm.stringWidth(inc)) / 2, baseY);
                g.drawString(value, valX + (valW - fm.stringWidth(value)) / 2, baseY);
            }
            case TEXT -> {
                int fw = 240;
                int fx = rightEdge - fw;
                o.mainBox.setBounds(fx, boxTop, fw, boxH);
                g.setColor(o.enabled ? theme.item : theme.itemDisabled);
                g.drawRoundRect(fx, boxTop, fw, boxH, 8, 8);
                boolean editing = options.get(selected) == o;
                String shown = value + (editing ? "_" : "");
                g.setColor(o.enabled ? theme.title : theme.itemDisabled);
                g.drawString(shown, fx + 8, baseY);
            }
            case SLIDER -> {
                SliderOption s = (SliderOption) o;
                int valW = Math.max(48, fm.stringWidth(String.valueOf(s.max)) + 12);
                int trackW = 200;
                int trackX = rightEdge - valW - trackW;
                o.mainBox.setBounds(trackX, boxTop, trackW, boxH);
                double t = (s.get.getAsInt() - s.min) / (double) (s.max - s.min);
                t = Math.max(0, Math.min(1, t));
                int cy = boxTop + boxH / 2;
                g.setColor(o.enabled ? theme.itemDisabled : new Color(80, 80, 90));
                g.fillRoundRect(trackX, cy - 2, trackW, 4, 4, 4);
                g.setColor(o.enabled ? theme.accent : theme.itemDisabled);
                g.fillRoundRect(trackX, cy - 2, (int) (trackW * t), 4, 4, 4);
                int thumbX = trackX + (int) (trackW * t);
                g.fillOval(thumbX - 6, cy - 7, 14, 14);
                g.setColor(color);
                g.drawString(value, rightEdge - valW + 8, baseY);
            }
            default -> { }
        }
    }

    public int getSelectedIndex() { return selected; }

    /** Index of the first visible row (top of the scrolled window). */
    public int getScroll() { return scroll; }

    /** Scroll-bar track hit box (computed during render; 0-size when the form fits). */
    public Rectangle scrollTrackBounds() { return scrollTrack; }

    /** Scroll-bar thumb hit box (computed during render; 0-size when the form fits). */
    public Rectangle scrollThumbBounds() { return scrollThumb; }
}
