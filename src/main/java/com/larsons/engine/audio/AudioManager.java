package com.larsons.engine.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.util.EnumMap;
import java.util.Map;

/**
 * Sound effects, ported from the Side-Scroller engine's {@code AudioManager}
 * + {@code SoundAction} — with the asset pipeline removed: instead of loading
 * MP3s through a bundled JLayer jar, every effect is a little synthesized PCM
 * clip generated at startup. That keeps the engine's "JDK only, no files
 * required" guarantee while still giving jumps, block edits, pickups, and
 * combat audible feedback. Games with real audio assets can keep using
 * {@code javax.sound.sampled} directly alongside this.
 *
 * <p>Headless-safe: if the machine has no audio line (CI, servers), the
 * manager disables itself silently and every {@link #play} is a no-op.
 */
public final class AudioManager {

    /** The engine's built-in effect vocabulary (the ported {@code SoundAction}s). */
    public enum Sfx { CLICK, JUMP, PLACE, BREAK, PICKUP, HIT, HURT, EAT, SHOOT, BOOM }

    private static final float SAMPLE_RATE = 22050f;

    private final Map<Sfx, Clip> clips = new EnumMap<>(Sfx.class);
    private boolean available;
    private boolean enabled = true;
    private boolean initialized;

    /** Master switch (wired to the game type's audio toggle). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Fire-and-forget; safe to call from the game loop every frame. */
    public void play(Sfx sfx) {
        if (!enabled) return;
        if (!initialized) initialize();
        if (!available) return;
        Clip clip = clips.get(sfx);
        if (clip == null) return;
        try {
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        } catch (RuntimeException ignored) {
            // A dying audio device shouldn't take the game down.
        }
    }

    private synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            clips.put(Sfx.CLICK, clip(sweep(900, 900, 0.03, 0.4)));
            clips.put(Sfx.JUMP, clip(sweep(280, 660, 0.12, 0.5)));
            clips.put(Sfx.PLACE, clip(sweep(200, 160, 0.06, 0.6)));
            clips.put(Sfx.BREAK, clip(noise(0.09, 0.55)));
            clips.put(Sfx.PICKUP, clip(concat(sweep(660, 660, 0.05, 0.4), sweep(990, 990, 0.07, 0.4))));
            clips.put(Sfx.HIT, clip(mix(noise(0.08, 0.5), sweep(150, 90, 0.08, 0.6))));
            clips.put(Sfx.HURT, clip(sweep(420, 140, 0.16, 0.55)));
            clips.put(Sfx.EAT, clip(concat(noise(0.03, 0.3), sweep(320, 260, 0.06, 0.35))));
            clips.put(Sfx.SHOOT, clip(mix(noise(0.04, 0.25), sweep(880, 320, 0.09, 0.45))));
            clips.put(Sfx.BOOM, clip(mix(noise(0.28, 0.6), sweep(140, 45, 0.28, 0.65))));
            available = true;
        } catch (Exception | UnsatisfiedLinkError e) {
            available = false; // headless / no sound device: stay silent
        }
    }

    public synchronized void dispose() {
        for (Clip c : clips.values()) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }
        clips.clear();
        available = false;
    }

    // --- tiny synthesizer -------------------------------------------------------

    /** A square-wave frequency sweep with an exponential decay envelope. */
    private static byte[] sweep(double fromHz, double toHz, double seconds, double volume) {
        int n = (int) (SAMPLE_RATE * seconds);
        byte[] pcm = new byte[n * 2];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double freq = fromHz + (toHz - fromHz) * t;
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            double env = Math.pow(1 - t, 1.8);
            double sample = (Math.sin(phase) >= 0 ? 1 : -1) * volume * env;
            writeSample(pcm, i, sample);
        }
        return pcm;
    }

    /** Decaying white noise (block break / impact). */
    private static byte[] noise(double seconds, double volume) {
        int n = (int) (SAMPLE_RATE * seconds);
        byte[] pcm = new byte[n * 2];
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < n; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double white = ((seed >> 24) & 0xFFFF) / 32768.0 - 1.0;
            double env = Math.pow(1 - i / (double) n, 2.0);
            writeSample(pcm, i, white * volume * env);
        }
        return pcm;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] mix(byte[] a, byte[] b) {
        byte[] out = new byte[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i += 2) {
            int sa = i < a.length ? (short) ((a[i] & 0xFF) | (a[i + 1] << 8)) : 0;
            int sb = i < b.length ? (short) ((b[i] & 0xFF) | (b[i + 1] << 8)) : 0;
            int s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sa + sb));
            out[i] = (byte) s;
            out[i + 1] = (byte) (s >> 8);
        }
        return out;
    }

    private static void writeSample(byte[] pcm, int index, double sample) {
        int s = (int) (Math.max(-1, Math.min(1, sample)) * Short.MAX_VALUE);
        pcm[index * 2] = (byte) s;
        pcm[index * 2 + 1] = (byte) (s >> 8);
    }

    private static Clip clip(byte[] pcm) throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        Clip clip = AudioSystem.getClip();
        clip.open(format, pcm, 0, pcm.length);
        return clip;
    }
}
