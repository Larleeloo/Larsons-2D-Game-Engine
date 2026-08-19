package com.larsons.engine.ui;

import com.larsons.engine.graphics.MiniGameSprites;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.BiFunction;

/**
 * A button whose face is a picture rather than a row of text: three states out
 * of one sprite sheet — resting, pointed at, held down — hit-tested against its
 * own rectangle.
 *
 * <p><b>Three states, because two of them are the feedback.</b> A picture
 * button with one frame gives a player no way to tell the pointer is on it, and
 * no way to tell a press registered; both are things a text menu says with its
 * caret and its highlight and a picture cannot say at all. So the sheet holds
 * all three and the button picks by index rather than by clock
 * ({@link com.larsons.engine.graphics.Skins#stateFrame}), which is what lets a
 * texture pack replace the art without knowing anything about buttons.
 *
 * <p><b>It activates on release, not on press</b>, unlike {@link Menu}'s rows.
 * That is the difference the pressed state buys: a button that fired the moment
 * it was touched would never be seen held down, and a player who pressed the
 * wrong one could not slide off it to take it back.
 */
public final class SpriteButton {

    /** Where a face comes from: the state and the size, to an image. */
    @FunctionalInterface
    public interface Faces extends BiFunction<MiniGameSprites.State, Integer, BufferedImage> {}

    private final Faces faces;
    private final Runnable action;
    private final String label;
    private final String tooltip;
    private final Rectangle bounds = new Rectangle();

    private MiniGameSprites.State state = MiniGameSprites.State.STATIC;
    /** Whether the press that is under way started on this button. */
    private boolean armed;

    public SpriteButton(String label, String tooltip, Faces faces, Runnable action) {
        this.label = label == null ? "" : label;
        this.tooltip = tooltip == null ? "" : tooltip;
        this.faces = faces;
        this.action = action;
    }

    public String label() { return label; }

    public String tooltip() { return tooltip; }

    /** Where the button is, in viewport pixels. */
    public Rectangle bounds() { return bounds; }

    public void setBounds(int x, int y, int w, int h) {
        bounds.setBounds(x, y, w, h);
    }

    /** Which face is being drawn right now. */
    public MiniGameSprites.State state() { return state; }

    /** Whether the pointer is over the button (its hover or pressed face). */
    public boolean hovered() { return state != MiniGameSprites.State.STATIC; }

    /**
     * Track the pointer and fire on a release that lands inside after a press
     * that started inside.
     *
     * @return whether the button was activated this tick
     */
    public boolean update(InputManager input) {
        if (input == null || bounds.isEmpty()) {
            state = MiniGameSprites.State.STATIC;
            armed = false;
            return false;
        }
        boolean over = bounds.contains(input.getMouseX(), input.getMouseY());
        boolean down = input.isMouseDown();
        if (over && input.isMouseJustPressed()) armed = true;
        // A press that wandered off the button is a press the player took back,
        // and letting go anywhere else cancels it.
        if (!down && armed) {
            armed = false;
            state = over ? MiniGameSprites.State.HOVER : MiniGameSprites.State.STATIC;
            if (over) {
                if (action != null) action.run();
                return true;
            }
            return false;
        }
        state = armed && over ? MiniGameSprites.State.PRESSED
                : over ? MiniGameSprites.State.HOVER : MiniGameSprites.State.STATIC;
        return false;
    }

    /** Fire the button's action without a pointer — what a keyboard would do. */
    public void activate() {
        if (action != null) action.run();
    }

    /** Draw the face for the state the button is in. */
    public void render(DrawTarget target) {
        if (bounds.isEmpty() || faces == null) return;
        BufferedImage face = faces.apply(state, Math.min(bounds.width, bounds.height));
        if (face == null) return;
        target.drawImage(face, bounds.x, bounds.y, bounds.width, bounds.height);
    }
}
