package com.larsons.engine.ui;

import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.input.KeyBinds;

import java.util.List;

/**
 * Builds the controls form: every {@link GameAction} the engine has, grouped by
 * category, each on a row with its two binding slots.
 *
 * <p>It is a plain {@link ConfigForm}, so the same screen works anywhere a form
 * fits — the standalone controls scene reached from the menus, and the pause
 * menus that show it inline without leaving the level being played or edited.
 * Building it from {@link GameAction#values()} rather than a hand-written list
 * means an action added to the engine is rebindable the day it is added.
 */
public final class KeyBindForm {

    private KeyBindForm() {}

    /** The hint line a host scene shows under the form. */
    public static final String HINT =
            "Enter or click a slot to rebind · press any key or mouse button · "
                    + "Del or right-click clears · Esc cancels";

    /**
     * The controls form over the {@linkplain KeyBinds#active() active} binds,
     * saved to the default {@link KeyBindStore} as changes are made — what a
     * pause menu wants, so rebinding mid-level neither loses the level nor the
     * rebind.
     */
    public static ConfigForm forActiveBinds(Runnable onDone) {
        KeyBindStore store = new KeyBindStore();
        return build("Controls", KeyBinds.active(),
                () -> store.trySave(KeyBinds.active()), onDone);
    }

    /**
     * Build the full controls form — every group the engine has.
     *
     * @param title      form title
     * @param binds      the set being edited (edited in place, live)
     * @param onChange   run after every rebind — where the caller saves
     * @param onDone     the "Done" row; omitted when {@code null}
     */
    public static ConfigForm build(String title, KeyBinds binds,
                                   Runnable onChange, Runnable onDone) {
        return build(title, binds, onChange, onDone,
                GameAction.Category.engineGroups());
    }

    /**
     * The controls form for {@code groups} alone — what a mini game's own
     * controls screen shows.
     *
     * <p>A mini game is a separate game with a separate keyboard, and the one
     * thing its player wants from a controls screen is <em>its</em> keys. A
     * screen listing the creative editor's brush sizes underneath them is a
     * screen nobody reads to the bottom of, and it is also how the auto
     * battler's reroll key ended up looking like a conflict with walking
     * right.
     */
    public static ConfigForm build(String title, KeyBinds binds,
                                   Runnable onChange, Runnable onDone,
                                   List<GameAction.Category> groups) {
        return fill(new ConfigForm(title).theme(MenuTheme.dark()).rowHeight(40),
                binds, onChange, onDone, groups);
    }

    /**
     * Put the controls rows into an existing form — for hosts that build their
     * windows themselves, like the creative editor's dialogs.
     */
    public static ConfigForm fill(ConfigForm form, KeyBinds binds,
                                  Runnable onChange, Runnable onDone) {
        return fill(form, binds, onChange, onDone, GameAction.Category.engineGroups());
    }

    /** {@link #fill(ConfigForm, KeyBinds, Runnable, Runnable)} for {@code groups} alone. */
    public static ConfigForm fill(ConfigForm form, KeyBinds binds,
                                  Runnable onChange, Runnable onDone,
                                  List<GameAction.Category> groups) {
        List<GameAction.Category> shown = groups == null || groups.isEmpty()
                ? GameAction.Category.engineGroups() : groups;
        form.onKeyBindChange(onChange);
        form.addNote("Any action goes on any key or mouse button, side buttons included.");
        form.addNote("Hold Ctrl/Shift/Alt while pressing to bind a combination.");
        form.addNote("Two slots per action — empty both to unbind it entirely.");

        for (GameAction.Category category : shown) {
            List<GameAction> actions = GameAction.in(category);
            if (actions.isEmpty()) continue;
            form.addNote("— " + category.label().toUpperCase() + " —");
            for (GameAction action : actions) {
                form.addKeyBind(binds, action);
            }
        }

        form.addNote("A key bound twice in one group shows red; across groups is fine.");
        // Only the groups on screen: a mini game's "reset" that also put the
        // world's movement keys back would be a reset of something the player
        // cannot see from here.
        form.addAction(shown.size() == 1
                        ? "Reset " + shown.get(0).label() + " to Defaults"
                        : "Reset All to Defaults",
                () -> {
                    for (GameAction.Category category : shown) {
                        for (GameAction action : GameAction.in(category)) binds.reset(action);
                    }
                    if (onChange != null) onChange.run();
                });
        if (onDone != null) form.addAction("Done", onDone);
        return form;
    }
}
