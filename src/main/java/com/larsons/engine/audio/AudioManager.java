package com.larsons.engine.audio;

/**
 * The engine's original ten-effect vocabulary, kept as a thin front for the
 * keyed sound system so every {@code ctx.sfx(Sfx.JUMP)} in the codebase keeps
 * working — and now goes through {@link Sounds}, which means each of these is
 * overridable from a {@link SoundPack} and gets the same fresh-pitch drift as
 * everything else.
 *
 * <p>Each {@link Sfx} is simply a well-known {@link SoundKeys} key: drop
 * {@code ui/click.wav} into the pack and every menu click in the game — the
 * level loader, the auto-battler, the deck builder — uses it, without those
 * scenes knowing the sound system grew.
 *
 * <p>New code should call {@link Sounds#play(String)} with a specific key
 * ({@code block/dirt/break} rather than {@code Sfx.BREAK}), so the creator
 * can give that one object its own sound. This exists for the call sites that
 * genuinely just want "a click".
 *
 * <p>Headless-safe: with no audio line the mixer disables itself silently and
 * every {@link #play} is a no-op.
 */
public final class AudioManager {

    /** The engine's built-in effect vocabulary, as sound keys. */
    public enum Sfx {
        CLICK(SoundKeys.ui("click")),
        JUMP(SoundKeys.player("jump")),
        PLACE(SoundKeys.player("place")),
        BREAK(SoundKeys.player("mine_break")),
        PICKUP(SoundKeys.player("pickup")),
        HIT(SoundKeys.player("attack_hit")),
        HURT(SoundKeys.player("hurt")),
        EAT(SoundKeys.player("eat")),
        SHOOT(SoundKeys.player("shoot")),
        BOOM(SoundKeys.world("explosion"));

        private final String key;

        Sfx(String key) {
            this.key = key;
        }

        /** The {@link SoundKeys} key this effect plays — what a pack overrides. */
        public String key() {
            return key;
        }
    }

    /** Master switch (wired to the game type's audio toggle). */
    public void setEnabled(boolean enabled) {
        Sounds.setEnabled(enabled);
    }

    public boolean isEnabled() {
        return Sounds.isEnabled();
    }

    /** Fire-and-forget; safe to call from the game loop every frame. */
    public void play(Sfx sfx) {
        if (sfx != null) Sounds.play(sfx.key());
    }

    /** Play any sound key directly — the general form of {@link #play(Sfx)}. */
    public void play(String key) {
        Sounds.play(key);
    }

    public void dispose() {
        Sounds.dispose();
    }
}
