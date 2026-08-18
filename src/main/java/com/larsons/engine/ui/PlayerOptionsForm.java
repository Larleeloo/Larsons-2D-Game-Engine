package com.larsons.engine.ui;

import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.config.PlayerSettingsStore;

/**
 * Builds the options form: the settings that belong to the person playing
 * rather than to the game type they are playing — volume, mouse-look feel, HUD
 * size.
 *
 * <p>Written as the exact counterpart of {@link KeyBindForm}, because it is the
 * exact counterpart: one form over one global object, saved to its own file as
 * changes are made, so the same screen works in a pause menu without leaving
 * the level. Before this existed the volume sliders lived only in the creative
 * editor's sound dialog and wrote to the <em>game type</em> — which meant a
 * player who was not also the level's author had nowhere to turn the music
 * down, and an author who did turn it down shipped that decision to everyone
 * who opened their level.
 */
public final class PlayerOptionsForm {

    private PlayerOptionsForm() {}

    /** The hint line a host scene shows under the form. */
    public static final String HINT =
            "These are yours, not the level's — they follow you into every game type";

    /**
     * The options form over the {@linkplain PlayerSettings#active() active}
     * settings, saved to the default store as changes are made.
     *
     * @param onApply run after every change — where the caller pushes the new
     *                values into the running engine (the mixer, mainly)
     * @param onDone  the "Back" row; omitted when {@code null}
     */
    public static ConfigForm forActiveSettings(Runnable onApply, Runnable onDone) {
        PlayerSettingsStore store = new PlayerSettingsStore();
        return build("Options", PlayerSettings.active(), () -> {
            if (onApply != null) onApply.run();
            store.trySave(PlayerSettings.active());
        }, onDone);
    }

    /**
     * Build the full options form.
     *
     * @param title    form title
     * @param settings the settings being edited (edited in place, live)
     * @param onChange run after every change — where the caller applies + saves
     * @param onDone   the "Back" row; omitted when {@code null}
     */
    public static ConfigForm build(String title, PlayerSettings settings,
                                   Runnable onChange, Runnable onDone) {
        return fill(new ConfigForm(title).theme(MenuTheme.dark()), settings, onChange, onDone);
    }

    /**
     * Put the options rows into an existing form — for hosts that build their
     * windows themselves, like the creative editor's dialogs.
     */
    public static ConfigForm fill(ConfigForm form, PlayerSettings settings,
                                  Runnable onChange, Runnable onDone) {
        Runnable changed = onChange == null ? () -> {} : onChange;

        form.addNote("— AUDIO —");
        form.addSlider("Master volume %", () -> percent(settings.masterVolume), v -> {
            settings.masterVolume = v / 100.0;
            changed.run();
        }, 0, 100);
        form.addSlider("Effects volume %", () -> percent(settings.sfxVolume), v -> {
            settings.sfxVolume = v / 100.0;
            changed.run();
        }, 0, 100);
        form.addSlider("Music volume %", () -> percent(settings.musicVolume), v -> {
            settings.musicVolume = v / 100.0;
            changed.run();
        }, 0, 100);

        form.addNote("— LOOKING AROUND ([F5] first and third person) —");
        // As a percentage rather than a raw multiplier: "150%" is a sentence a
        // player can act on and "1.5" is a number they have to interpret.
        form.addSlider("Mouse sensitivity %", () -> percent(settings.lookSensitivity), v -> {
            settings.lookSensitivity = v / 100.0;
            changed.run();
        }, (int) Math.round(PlayerSettings.MIN_SENSITIVITY * 100),
                (int) Math.round(PlayerSettings.MAX_SENSITIVITY * 100));
        form.addToggle("Invert look (Y axis)", () -> settings.invertLook, v -> {
            settings.invertLook = v;
            changed.run();
        });

        form.addNote("— DISPLAY —");
        form.addSlider("HUD size %", () -> percent(settings.hudScale), v -> {
            settings.hudScale = v / 100.0;
            changed.run();
        }, (int) Math.round(PlayerSettings.MIN_HUD_SCALE * 100),
                (int) Math.round(PlayerSettings.MAX_HUD_SCALE * 100));

        form.addAction("Reset to defaults", () -> {
            PlayerSettings defaults = new PlayerSettings();
            settings.masterVolume = defaults.masterVolume;
            settings.sfxVolume = defaults.sfxVolume;
            settings.musicVolume = defaults.musicVolume;
            settings.lookSensitivity = defaults.lookSensitivity;
            settings.invertLook = defaults.invertLook;
            settings.hudScale = defaults.hudScale;
            changed.run();
        });
        if (onDone != null) form.addAction("Back", onDone);
        return form;
    }

    private static int percent(double v) {
        return (int) Math.round(v * 100);
    }
}
