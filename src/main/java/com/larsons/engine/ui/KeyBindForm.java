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
     * Build the full controls form.
     *
     * @param title      form title
     * @param binds      the set being edited (edited in place, live)
     * @param onChange   run after every rebind — where the caller saves
     * @param onDone     the "Done" row; omitted when {@code null}
     */
    public static ConfigForm build(String title, KeyBinds binds,
                                   Runnable onChange, Runnable onDone) {
        return fill(new ConfigForm(title).theme(MenuTheme.dark()).rowHeight(40),
                binds, onChange, onDone);
    }

    /**
     * Put the controls rows into an existing form — for hosts that build their
     * windows themselves, like the creative editor's dialogs.
     */
    public static ConfigForm fill(ConfigForm form, KeyBinds binds,
                                  Runnable onChange, Runnable onDone) {
        form.onKeyBindChange(onChange);
        form.addNote("Any action goes on any key or mouse button, side buttons included.");
        form.addNote("Hold Ctrl/Shift/Alt while pressing to bind a combination.");
        form.addNote("Two slots per action — empty both to unbind it entirely.");

        for (GameAction.Category category : GameAction.Category.values()) {
            List<GameAction> actions = GameAction.in(category);
            if (actions.isEmpty()) continue;
            form.addNote("— " + category.label().toUpperCase() + " —");
            for (GameAction action : actions) {
                form.addKeyBind(binds, action);
            }
        }

        form.addNote("A key bound twice in one group shows red; across groups is fine.");
        form.addAction("Reset All to Defaults", () -> {
            binds.resetAll();
            if (onChange != null) onChange.run();
        });
        if (onDone != null) form.addAction("Done", onDone);
        return form;
    }
}
