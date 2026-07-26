package com.larsons.engine.audio;

import java.util.EnumMap;
import java.util.Map;

/**
 * The engine's built-in voices: a handful of little PCM clips synthesized at
 * startup, so a game with no sound assets at all still gives jumps, block
 * edits, pickups and combat audible feedback.
 *
 * <p>This is the original {@code AudioManager} synthesizer, lifted out so
 * {@link Sounds} can treat a generated clip and a file from the
 * {@link SoundPack} identically — both are a {@link PcmClip}, both go through
 * the same mixer, and both get the same subtle pitch variation.
 *
 * <p><b>Only these keys have a built-in voice.</b> Every other sound in
 * {@link SoundKeys} is silent until a file is dropped into the pack, which is
 * what makes the pack an override rather than a mute switch: the actions the
 * engine has always made a noise for keep making it, and the hundreds of new
 * ones wait for their audio.
 */
public final class SoundSynth {

    /** The engine's built-in effect vocabulary. */
    public enum Voice { CLICK, JUMP, PLACE, BREAK, PICKUP, HIT, HURT, EAT, SHOOT, BOOM }

    private static final float SAMPLE_RATE = 22050f;

    private static final Map<Voice, PcmClip> CLIPS = new EnumMap<>(Voice.class);

    private SoundSynth() {}

    /** The generated clip for a voice (built once, then shared). */
    public static synchronized PcmClip clip(Voice voice) {
        PcmClip c = CLIPS.get(voice);
        if (c == null) {
            c = new PcmClip(generate(voice), 1, (int) SAMPLE_RATE);
            CLIPS.put(voice, c);
        }
        return c;
    }

    /**
     * The built-in voice a sound key falls back to, or {@code null} when the
     * key's default is silence — which is every key but these.
     *
     * <p>The mapping is by <em>action state</em>, not by object, so a custom
     * block a creator just invented breaks with the same built-in crunch as
     * dirt does, and their new mob is hurt with the same thud as a slime.
     */
    public static Voice fallbackFor(String key) {
        if (key == null || key.isEmpty()) return null;
        int slash = key.indexOf('/');
        String namespace = slash < 0 ? key : key.substring(0, slash);
        String state = key.substring(key.lastIndexOf('/') + 1);
        return switch (namespace) {
            case "ui" -> switch (state) {
                case "click", "select", "tab", "back", "open", "close" -> Voice.CLICK;
                case "error" -> Voice.HURT;
                default -> null;
            };
            case "player", "character" -> switch (state) {
                case "jump", "double_jump" -> Voice.JUMP;
                case "place" -> Voice.PLACE;
                case "mine_break", "chop" -> Voice.BREAK;
                case "pickup" -> Voice.PICKUP;
                case "attack_hit" -> Voice.HIT;
                case "hurt", "die" -> Voice.HURT;
                case "eat", "drink" -> Voice.EAT;
                case "shoot" -> Voice.SHOOT;
                case "ult_activate" -> Voice.BOOM;
                default -> null;
            };
            case "block" -> switch (state) {
                case "place" -> Voice.PLACE;
                case "break" -> Voice.BREAK;
                case "hit" -> Voice.HIT;
                default -> null;
            };
            case "mob" -> switch (state) {
                case "hurt" -> Voice.HIT;
                case "death" -> Voice.HURT;
                default -> null;
            };
            case "item" -> "pickup".equals(state) ? Voice.PICKUP : null;
            case "decor" -> switch (state) {
                case "hit" -> Voice.HIT;
                case "break" -> Voice.BREAK;
                default -> null;
            };
            case "projectile" -> switch (state) {
                case "fire" -> Voice.SHOOT;
                case "impact" -> Voice.HIT;
                case "explode" -> Voice.BOOM;
                default -> null;
            };
            case "world" -> switch (state) {
                case "explosion" -> Voice.BOOM;
                case "chest_open", "chest_close", "craft_station" -> Voice.CLICK;
                default -> null;
            };
            default -> null;
        };
    }

    /** Whether {@code key} makes a noise with no sound pack at all. */
    public static boolean hasFallback(String key) {
        return fallbackFor(key) != null;
    }

    private static short[] generate(Voice voice) {
        return switch (voice) {
            case CLICK -> sweep(900, 900, 0.03, 0.4);
            case JUMP -> sweep(280, 660, 0.12, 0.5);
            case PLACE -> sweep(200, 160, 0.06, 0.6);
            case BREAK -> noise(0.09, 0.55);
            case PICKUP -> concat(sweep(660, 660, 0.05, 0.4), sweep(990, 990, 0.07, 0.4));
            // HIT and HURT are deliberately soft: sine thuds instead of loud
            // square-wave shrieks, which read as alarming rather than
            // informative when particles collide or the player is hurt.
            case HIT -> mix(noise(0.04, 0.22), tone(220, 140, 0.07, 0.4));
            case HURT -> tone(310, 170, 0.11, 0.42);
            case EAT -> concat(noise(0.03, 0.3), sweep(320, 260, 0.06, 0.35));
            case SHOOT -> mix(noise(0.04, 0.25), sweep(880, 320, 0.09, 0.45));
            case BOOM -> mix(noise(0.28, 0.6), sweep(140, 45, 0.28, 0.65));
        };
    }

    // --- tiny synthesizer -------------------------------------------------------

    /** A square-wave frequency sweep with an exponential decay envelope. */
    private static short[] sweep(double fromHz, double toHz, double seconds, double volume) {
        int n = (int) (SAMPLE_RATE * seconds);
        short[] pcm = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double freq = fromHz + (toHz - fromHz) * t;
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            double env = Math.pow(1 - t, 1.8);
            pcm[i] = sample((Math.sin(phase) >= 0 ? 1 : -1) * volume * env);
        }
        return pcm;
    }

    /** A pure sine sweep with a soft decay — gentler than {@link #sweep}. */
    private static short[] tone(double fromHz, double toHz, double seconds, double volume) {
        int n = (int) (SAMPLE_RATE * seconds);
        short[] pcm = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            double freq = fromHz + (toHz - fromHz) * t;
            phase += 2 * Math.PI * freq / SAMPLE_RATE;
            double env = Math.pow(1 - t, 1.5);
            pcm[i] = sample(Math.sin(phase) * volume * env);
        }
        return pcm;
    }

    /** Decaying white noise (block break / impact). */
    private static short[] noise(double seconds, double volume) {
        int n = (int) (SAMPLE_RATE * seconds);
        short[] pcm = new short[n];
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < n; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double white = ((seed >> 24) & 0xFFFF) / 32768.0 - 1.0;
            double env = Math.pow(1 - i / (double) n, 2.0);
            pcm[i] = sample(white * volume * env);
        }
        return pcm;
    }

    private static short[] concat(short[] a, short[] b) {
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static short[] mix(short[] a, short[] b) {
        short[] out = new short[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) {
            int s = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
        return out;
    }

    private static short sample(double v) {
        return (short) (Math.max(-1, Math.min(1, v)) * Short.MAX_VALUE);
    }
}
