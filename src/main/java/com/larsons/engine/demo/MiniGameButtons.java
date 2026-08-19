package com.larsons.engine.demo;

import com.larsons.engine.graphics.MiniGameSprites;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.minigame.StandaloneGame;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.SpriteButton;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The mini games, as buttons in the corner of the launch screen.
 *
 * <p><b>Why they are not menu rows.</b> They were, and it made three separate
 * games look like three more things the game type in front of you could do —
 * "Auto Battler (2-10 Online)" sitting directly under "Create New Game", in the
 * same list, in the same typeface. They are not that: they are other games, and
 * a corner of the screen with a picture on it says so in a way a row of text
 * cannot. It also keeps the list of game types a list of game types, which is
 * what a launch screen with fifteen of them needs.
 *
 * <p>Everything about a button — which games there are, what each is called,
 * where it goes and what it looks like — comes from
 * {@link StandaloneGame}, so a mini game added there appears here with art
 * already, and a texture pack that supplies its
 * {@linkplain StandaloneGame#textureKey() key} redresses it without touching
 * this file.
 */
public final class MiniGameButtons {

    /** Button edge in pixels at a comfortable window size. */
    private static final int SIZE = 72;
    /** Space between buttons, and from the window's edges. */
    private static final int GAP = 12;
    private static final int MARGIN = 20;
    /**
     * Room kept clear along the bottom for the screen's own hint line
     * ({@link SceneChrome#hint}). The strip is in the corner, and the corner is
     * already spoken for down there.
     */
    private static final int HINT_ROOM = 34;

    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font TAG_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Color LABEL = new Color(226, 230, 244);
    private static final Color TAG = new Color(150, 156, 178);
    private static final Color PLATE = new Color(0, 0, 0, 110);

    private final List<StandaloneGame> games = new ArrayList<>();
    private final List<SpriteButton> buttons = new ArrayList<>();
    /** The strip's own box, recomputed each layout — for hit tests and tests. */
    private int stripX, stripY, stripW, stripH;

    /** One button per mini game, each opening that game's lobby. */
    public MiniGameButtons(SceneManager scenes) {
        this(game -> {
            if (scenes != null) scenes.transitionTo(game.scene());
        });
    }

    /** {@link #MiniGameButtons(SceneManager)} with the opening left to the caller. */
    public MiniGameButtons(Consumer<StandaloneGame> open) {
        for (StandaloneGame game : StandaloneGame.all()) {
            games.add(game);
            buttons.add(new SpriteButton(game.title(), game.tagline(),
                    (state, size) -> MiniGameSprites.button(game, state, size),
                    () -> open.accept(game)));
        }
    }

    /** The buttons, in the order they are drawn. */
    public List<SpriteButton> buttons() { return buttons; }

    /** The button that opens {@code game}, or {@code null} if there is none. */
    public SpriteButton button(StandaloneGame game) {
        int i = games.indexOf(game);
        return i < 0 ? null : buttons.get(i);
    }

    /**
     * Lay the strip out along the bottom-right corner of a {@code vw × vh}
     * viewport, shrinking the buttons when the window is too narrow to hold
     * them at full size rather than letting them run off the edge or over the
     * menu.
     */
    public void layout(int vw, int vh) {
        int count = buttons.size();
        if (count == 0) return;
        int size = SIZE;
        // A third of the width is as much as the strip may take; below that the
        // buttons shrink together rather than one of them disappearing.
        int available = Math.max(24, vw / 3 - MARGIN);
        int wanted = count * size + (count - 1) * GAP;
        if (wanted > available) {
            size = Math.max(24, (available - (count - 1) * GAP) / count);
        }
        int labelRow = 34; // the two lines under the buttons
        stripW = count * size + (count - 1) * GAP;
        stripH = size + labelRow;
        stripX = vw - MARGIN - stripW;
        stripY = vh - MARGIN - HINT_ROOM - stripH;
        for (int i = 0; i < count; i++) {
            buttons.get(i).setBounds(stripX + i * (size + GAP), stripY, size, size);
        }
    }

    /**
     * Track the pointer; returns whether a button was activated this tick.
     * Lays the strip out first, so the very first tick of a scene hit-tests
     * against real boxes rather than against last frame's (or none at all).
     */
    public boolean update(InputManager input, int vw, int vh) {
        layout(vw, vh);
        boolean fired = false;
        for (SpriteButton b : buttons) {
            // Every button is updated even after one fires, so none is left
            // holding a stale "pressed" face when the scene comes back.
            fired |= b.update(input);
        }
        return fired;
    }

    /** Whether the pointer is resting on any of the buttons. */
    public boolean hovered() {
        for (SpriteButton b : buttons) {
            if (b.hovered()) return true;
        }
        return false;
    }

    /** Draw the strip: a heading, the buttons, and the hovered one's name. */
    public void render(DrawTarget target, int vw, int vh) {
        if (buttons.isEmpty()) return;
        layout(vw, vh);

        // A plate under the whole strip. The menu's rows are centred and a long
        // game-type name reaches into this corner at a narrow window; without
        // something behind them the buttons sit in the middle of somebody
        // else's text.
        int pad = 10;
        target.fillRoundRect(stripX - pad, stripY - 24, stripW + pad * 2,
                stripH + 24 + pad / 2, 12, 12, PLATE);

        String heading = "Mini games";
        int hw = target.textWidth(heading, TAG_FONT);
        target.drawText(heading, stripX + stripW - hw, stripY - 8, TAG_FONT, TAG);

        for (SpriteButton b : buttons) b.render(target);

        // One label at a time, under the strip: three names side by side under
        // three buttons is more text than the corner of a screen can hold at
        // any window width, and the pointer already says which one is meant.
        SpriteButton hovered = null;
        for (SpriteButton b : buttons) {
            if (b.hovered()) hovered = b;
        }
        int textY = stripY + stripH - 16;
        String name = hovered != null ? hovered.label() : "";
        String tag = hovered != null ? hovered.tooltip() : "point at one to see what it is";
        int nameW = target.textWidth(name, LABEL_FONT);
        int tagW = target.textWidth(tag, TAG_FONT);
        if (!name.isEmpty()) {
            target.drawText(name, stripX + stripW - nameW - 8, textY, LABEL_FONT, LABEL);
        }
        target.drawText(tag, stripX + stripW - tagW - 8, textY + 15, TAG_FONT, TAG);
    }
}
