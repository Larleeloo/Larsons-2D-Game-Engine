package com.larsons.engine.level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The creative editor's undo history — what {@code Ctrl+Z} takes back, and
 * {@code Ctrl+Y} (or {@code Ctrl+Shift+Z}) puts back again.
 *
 * <p><b>A step is an action, not a change.</b> One brush drag lays down
 * hundreds of block cells, and a dialog session changes a dozen fields; both
 * come back in one keystroke because the editor <em>opens</em> a step
 * ({@link #begin}), lets the action record as many {@link Edit}s into it as it
 * needs, and closes it when the action is over ({@link #commit} /
 * {@link #flush}). Steps nest, so an action built out of smaller ones — an
 * eraser stroke inside a paint stroke — still reads as the single thing the
 * creator did.
 *
 * <p><b>Edits say what they were, not what they became.</b> The usual edit is
 * {@link #field}: it reads the old value when it is recorded and only reads the
 * new one the first time it is undone. That ordering is what lets the editor
 * record an undo step <em>before</em> an action runs without knowing anything
 * about what the action is going to do — which is how the dialogs are covered
 * without every button in them having to describe its own inverse.
 *
 * <p>It also means an action that changed nothing leaves nothing behind: a step
 * drops the edits whose value came back equal ({@link Edit#changed()}) and is
 * only pushed if something survives, so a window opened and cancelled does not
 * cost a Ctrl+Z press.
 */
public final class EditHistory {

    /** How many actions Ctrl+Z can walk back before the oldest is forgotten. */
    public static final int DEFAULT_DEPTH = 128;

    /** One reversible piece of an action. */
    public interface Edit {

        /** Put the state this edit saved back. */
        void undo();

        /** Re-apply what {@link #undo()} took away. */
        void redo();

        /**
         * Whether this edit has anything to take back — checked when the step
         * closes, so an action that turned out to be a no-op (repainting a cell
         * with the block already in it, cancelling a dialog) is not remembered
         * as one.
         */
        default boolean changed() {
            return true;
        }
    }

    /** Everything one action changed, under the name Ctrl+Z reports. */
    private record Step(String label, List<Edit> edits) {}

    private final int depth;
    private final Deque<Step> past = new ArrayDeque<>();
    private final Deque<Step> future = new ArrayDeque<>();

    /** The step being recorded, or {@code null} when nothing is open. */
    private List<Edit> open;
    private String openLabel = "";
    /** How many {@link #begin} calls deep the open step is. */
    private int nesting;
    /** True while an undo/redo runs, so applying an edit cannot record one. */
    private boolean applying;

    public EditHistory() {
        this(DEFAULT_DEPTH);
    }

    public EditHistory(int depth) {
        this.depth = Math.max(1, depth);
    }

    // --- recording ---------------------------------------------------------------

    /**
     * Open a step (or nest inside the one already open, which keeps its label).
     * Every {@code begin} needs a matching {@link #commit}, or a {@link #flush}
     * to close the lot.
     */
    public void begin(String label) {
        if (applying) return;
        if (open == null) {
            open = new ArrayList<>(4);
            openLabel = label == null || label.isBlank() ? "edit" : label;
        }
        nesting++;
    }

    /** Whether a step is open — i.e. whether {@link #add} would keep an edit. */
    public boolean recording() {
        return open != null && !applying;
    }

    /** The name the open step will be remembered under. */
    public String openLabel() {
        return open == null ? "" : openLabel;
    }

    /** Record one reversible change into the open step (ignored without one). */
    public void add(Edit edit) {
        if (applying || open == null || edit == null) return;
        open.add(edit);
    }

    /**
     * Close one {@link #begin}; the step is pushed when the outermost one
     * closes and something in it actually changed.
     *
     * @return whether a step was pushed onto the undo stack
     */
    public boolean commit() {
        if (applying || open == null) return false;
        if (--nesting > 0) return false;
        return push();
    }

    /**
     * Close the open step however deeply it is nested — for the ends that
     * aren't a matching {@code commit}: a mouse button coming up, a window
     * opening over a stroke, leaving the editor.
     *
     * @return whether a step was pushed onto the undo stack
     */
    public boolean flush() {
        if (applying || open == null) return false;
        nesting = 0;
        return push();
    }

    /** Throw the open step away without recording it. */
    public void abort() {
        open = null;
        nesting = 0;
    }

    private boolean push() {
        List<Edit> kept = new ArrayList<>(open.size());
        for (Edit e : open) {
            if (e.changed()) kept.add(e);
        }
        String label = openLabel;
        open = null;
        nesting = 0;
        if (kept.isEmpty()) return false;
        past.push(new Step(label, kept));
        while (past.size() > depth) past.removeLast();
        future.clear();   // a new action is a new branch: forward is gone
        return true;
    }

    // --- undo / redo -------------------------------------------------------------

    public boolean canUndo() {
        return !past.isEmpty();
    }

    public boolean canRedo() {
        return !future.isEmpty();
    }

    /** What Ctrl+Z would take back, or {@code ""} when there is nothing. */
    public String undoLabel() {
        Step s = past.peek();
        return s == null ? "" : s.label();
    }

    /** What Ctrl+Y would put back, or {@code ""} when there is nothing. */
    public String redoLabel() {
        Step s = future.peek();
        return s == null ? "" : s.label();
    }

    public int undoDepth() {
        return past.size();
    }

    public int redoDepth() {
        return future.size();
    }

    /**
     * Take back the newest action, innermost edit first — a step's edits are
     * undone in the reverse of the order they were recorded, so overlapping
     * saves of the same thing land on the oldest one.
     *
     * @return whether anything was undone
     */
    public boolean undo() {
        Step step = past.poll();
        if (step == null) return false;
        applying = true;
        try {
            List<Edit> edits = step.edits();
            for (int i = edits.size() - 1; i >= 0; i--) edits.get(i).undo();
        } finally {
            applying = false;
        }
        future.push(step);
        return true;
    }

    /** Put the last undone action back. @return whether anything was redone */
    public boolean redo() {
        Step step = future.poll();
        if (step == null) return false;
        applying = true;
        try {
            for (Edit e : step.edits()) e.redo();
        } finally {
            applying = false;
        }
        past.push(step);
        return true;
    }

    /** Forget everything (a new editing session, a different level). */
    public void clear() {
        past.clear();
        future.clear();
        open = null;
        nesting = 0;
    }

    // --- edits -------------------------------------------------------------------

    /**
     * The general-purpose edit: read some state now, write it back on undo.
     *
     * <p>{@code read} must hand over something that will not change underneath
     * the history — a value, an immutable record, a copy of a list — because
     * that snapshot <em>is</em> the undo. The new state is read the first time
     * the edit is undone, so a caller only has to say what things were, never
     * what they are about to become.
     */
    public static <T> Edit field(Supplier<T> read, Consumer<T> write) {
        return new FieldEdit<>(read, write);
    }

    /**
     * An edit that spells out both directions itself — for the changes that
     * aren't a value being written somewhere (registering an object, deleting
     * one). Always counts as a change.
     */
    public static Edit of(Runnable undo, Runnable redo) {
        return new Edit() {
            @Override
            public void undo() {
                undo.run();
            }

            @Override
            public void redo() {
                redo.run();
            }
        };
    }

    private static final class FieldEdit<T> implements Edit {
        private final Supplier<T> read;
        private final Consumer<T> write;
        private final T before;
        private T after;
        private boolean captured;

        FieldEdit(Supplier<T> read, Consumer<T> write) {
            this.read = read;
            this.write = write;
            this.before = read.get();
        }

        @Override
        public void undo() {
            if (!captured) {
                after = read.get();
                captured = true;
            }
            write.accept(before);
        }

        @Override
        public void redo() {
            write.accept(after);
        }

        @Override
        public boolean changed() {
            return !Objects.equals(before, read.get());
        }
    }
}
