package com.larsons.engine.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A small software mixer: any number of {@link PcmClip}s playing at once,
 * each at its own pitch, volume and stereo position, summed into one audio
 * line by a background thread.
 *
 * <p>The engine used to hand each effect to its own {@code javax.sound Clip},
 * which can only play a sound at the rate it was recorded at and restarts
 * rather than overlaps. Mixing in software instead buys the three things the
 * sound system needs: <b>pitch</b> (a voice reads its clip at whatever rate
 * you ask, which is how the subtle per-playback pitch drift works),
 * <b>overlap</b> (ten footsteps and a meteor can sound together), and
 * <b>looping</b> with a level's music running under all of it.
 *
 * <p>Resampling is linear interpolation between neighbouring frames — cheap,
 * and at the fractions-of-a-semitone drift the pitch option uses, inaudible.
 *
 * <p><b>Headless-safe.</b> If the machine has no audio line — CI, a dedicated
 * server, a container — the mixer disables itself silently at first use and
 * every {@code play} is a no-op that still returns a handle, so game code
 * never has to ask whether sound exists.
 */
public final class SoundMixer {

    /** The rate everything is mixed and played at. */
    public static final int SAMPLE_RATE = 44100;
    /** Frames per mix block: ~12 ms, short enough that effects feel immediate. */
    private static final int BLOCK_FRAMES = 512;
    /** Voices past this are dropped rather than stealing the CPU. */
    private static final int MAX_VOICES = 48;

    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 2, true, false);

    /** One sound in flight. Handed back so the caller can stop or fade it. */
    public static final class Voice {
        private final PcmClip clip;
        private double position;      // playback head, in source frames
        private final double rate;    // source frames consumed per output frame
        private volatile double volume;
        private final double panLeft, panRight;
        private final boolean loop;
        private volatile boolean stopping;
        private double fade = 1.0;    // multiplier ramped to 0 when stopping

        Voice(PcmClip clip, double rate, double volume, double pan, boolean loop) {
            this.clip = clip;
            this.rate = rate;
            this.volume = volume;
            this.loop = loop;
            // Constant-power panning, so a sound crossing the screen keeps
            // its loudness instead of dipping through the middle.
            double angle = (Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4;
            this.panLeft = Math.cos(angle);
            this.panRight = Math.sin(angle);
        }

        /** Fade this voice out over a few milliseconds and drop it. */
        public void stop() {
            stopping = true;
        }

        /** Whether the voice is still producing sound. */
        public boolean isPlaying() {
            return !stopping && (loop || position < clip.frames());
        }

        /** Change how loud this voice is while it plays (music ducking). */
        public void setVolume(double v) {
            this.volume = Math.max(0, Math.min(4, v));
        }
    }

    /** A handle for a sound that never actually started (headless, or silent). */
    private static final Voice NONE = new Voice(PcmClip.SILENCE, 1, 0, 0, false);

    private final List<Voice> voices = new ArrayList<>();
    private SourceDataLine line;
    private Thread pump;
    private boolean initialized;
    private boolean available;
    private volatile boolean running;
    private volatile double masterVolume = 1.0;

    /** Master level applied to every voice, 0..1. */
    public void setMasterVolume(double v) {
        masterVolume = Math.max(0, Math.min(1, v));
    }

    public double masterVolume() {
        return masterVolume;
    }

    /** Whether an audio device was found (false on a headless machine). */
    public synchronized boolean isAvailable() {
        return initialized && available;
    }

    /**
     * Start {@code clip} playing.
     *
     * @param pitch  playback speed, 1 = as recorded (2 = an octave up)
     * @param volume level, 1 = as recorded
     * @param pan    stereo position, -1 hard left … +1 hard right
     * @param loop   whether to repeat until stopped
     * @return a handle to stop it, never {@code null}
     */
    public Voice play(PcmClip clip, double pitch, double volume, double pan, boolean loop) {
        if (clip == null || clip.isEmpty() || volume <= 0) return NONE;
        if (!ensureStarted()) return NONE;
        double rate = clip.sampleRate() / (double) SAMPLE_RATE * Math.max(0.01, pitch);
        Voice v = new Voice(clip, rate, volume, pan, loop);
        synchronized (voices) {
            if (voices.size() >= MAX_VOICES) {
                // Drop the oldest one-shot rather than refuse the new sound:
                // the newest event is the one the player just caused.
                for (int i = 0; i < voices.size(); i++) {
                    if (!voices.get(i).loop) {
                        voices.remove(i);
                        break;
                    }
                }
                if (voices.size() >= MAX_VOICES) return NONE;
            }
            voices.add(v);
        }
        return v;
    }

    /** Stop every voice at once (leaving a level, closing the game). */
    public void stopAll() {
        synchronized (voices) {
            for (Voice v : voices) v.stop();
        }
    }

    /** Voices currently sounding — the sound editor shows this. */
    public int activeVoices() {
        synchronized (voices) {
            return voices.size();
        }
    }

    /** Shut the mixer down and release the audio device. */
    public synchronized void dispose() {
        running = false;
        Thread t = pump;
        pump = null;
        if (t != null) t.interrupt();
        synchronized (voices) {
            voices.clear();
        }
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (RuntimeException ignored) {
                // a dying device shouldn't take the game down
            }
        }
        available = false;
        initialized = false;
    }

    private synchronized boolean ensureStarted() {
        if (initialized) return available;
        initialized = true;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, BLOCK_FRAMES * 8);
            line.start();
            available = true;
            running = true;
            pump = new Thread(this::pump, "sound-mixer");
            pump.setDaemon(true);
            pump.start();
        } catch (Exception | LinkageError e) {
            available = false; // headless / no sound device: stay silent
            line = null;
        }
        return available;
    }

    /**
     * The mix loop: sum every voice into one block and hand it to the line,
     * which blocks until there is room — that is what paces this thread.
     */
    private void pump() {
        float[] mix = new float[BLOCK_FRAMES * 2];
        byte[] out = new byte[BLOCK_FRAMES * 4];
        List<Voice> snapshot = new ArrayList<>();
        while (running) {
            java.util.Arrays.fill(mix, 0f);
            snapshot.clear();
            synchronized (voices) {
                snapshot.addAll(voices);
            }
            for (Voice v : snapshot) render(v, mix);
            if (!snapshot.isEmpty()) {
                synchronized (voices) {
                    Iterator<Voice> it = voices.iterator();
                    while (it.hasNext()) {
                        Voice v = it.next();
                        if (v.fade <= 0 || (!v.loop && v.position >= v.clip.frames())) it.remove();
                    }
                }
            }
            double master = masterVolume;
            for (int i = 0; i < mix.length; i++) {
                int s = Math.round(mix[i] * (float) master * 32767f);
                s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
                out[i * 2] = (byte) s;
                out[i * 2 + 1] = (byte) (s >> 8);
            }
            SourceDataLine l = line;
            if (l == null) return;
            try {
                l.write(out, 0, out.length);
            } catch (RuntimeException e) {
                return; // the device went away; go quiet rather than spin
            }
        }
    }

    /** Add one voice's contribution to the block, advancing its playhead. */
    private void render(Voice v, float[] mix) {
        PcmClip clip = v.clip;
        int frames = clip.frames();
        if (frames == 0) {
            v.fade = 0;
            return;
        }
        double pos = v.position;
        double fade = v.fade;
        // A stopping voice ramps down across one block rather than cutting,
        // which would click.
        double fadeStep = v.stopping ? 1.0 / BLOCK_FRAMES : 0;
        float vol = (float) v.volume;
        for (int i = 0; i < BLOCK_FRAMES; i++) {
            if (fade <= 0) break;
            int i0 = (int) pos;
            if (i0 >= frames) {
                if (!v.loop) {
                    fade = 0;
                    break;
                }
                pos -= frames;
                i0 = (int) pos;
                if (i0 >= frames) i0 = 0;
            }
            double frac = pos - i0;
            int i1 = i0 + 1 < frames ? i0 + 1 : (v.loop ? 0 : i0);
            float l = lerp(clip.sample(i0, 0), clip.sample(i1, 0), frac);
            float r = lerp(clip.sample(i0, 1), clip.sample(i1, 1), frac);
            float g = vol * (float) fade;
            mix[i * 2] += l * g * (float) v.panLeft;
            mix[i * 2 + 1] += r * g * (float) v.panRight;
            pos += v.rate;
            fade -= fadeStep;
        }
        v.position = pos;
        v.fade = Math.max(0, fade);
    }

    private static float lerp(float a, float b, double t) {
        return (float) (a + (b - a) * t);
    }
}
