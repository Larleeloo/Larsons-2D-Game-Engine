package com.larsons.engine.ui;

/**
 * A selectable entry in a {@link Menu}: a label plus an action to run when
 * activated. The label is supplied via a {@link Label} functional interface so
 * it can be dynamic (e.g. "Perspective: ISOMETRIC" that updates as state
 * changes).
 */
public class MenuItem {

    /** Supplies the item's current display text. */
    @FunctionalInterface
    public interface Label {
        String text();
    }

    private final Label label;
    private final Runnable action;
    public boolean enabled = true;

    // Hit box, recomputed by Menu during render for mouse interaction.
    public int x, y, width, height;

    public MenuItem(String label, Runnable action) {
        this(() -> label, action);
    }

    public MenuItem(Label label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    public String text() { return label.text(); }

    public void activate() {
        if (enabled && action != null) action.run();
    }

    public boolean contains(int px, int py) {
        return width > 0 && px >= x && px <= x + width && py >= y && py <= y + height;
    }
}
