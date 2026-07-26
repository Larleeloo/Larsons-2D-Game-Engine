package com.larsons.engine.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an audio file on disk into a {@link PcmClip} the mixer can play, and
 * remembers what it read so the same file isn't decoded twice.
 *
 * <p>WAV, AIFF and AU go through the JDK's {@code javax.sound.sampled}
 * readers; MP3 goes through the engine's own {@link Mp3Decoder}, because the
 * JDK has no MP3 support and the engine ships with no third-party jars. The
 * file's extension picks the route, and a file whose contents disagree with
 * its name is retried the other way — a {@code .wav} that is really an MP3
 * still plays.
 *
 * <p>A file that cannot be read is reported once and remembered as silence,
 * so a broken asset is one line of console output rather than an exception
 * every time the sound is triggered.
 */
public final class SoundLoader {

    /** Refuse to load a single file larger than this (a mistyped path, a video). */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final Map<String, PcmClip> CACHE = new ConcurrentHashMap<>();

    private SoundLoader() {}

    /**
     * The decoded clip for a file, or {@link PcmClip#SILENCE} when it is
     * missing or unreadable. Results are cached by path + last-modified time,
     * so replacing a file while the game runs is picked up by a rescan.
     */
    public static PcmClip load(Path file) {
        if (file == null) return PcmClip.SILENCE;
        String key = cacheKey(file);
        PcmClip cached = CACHE.get(key);
        if (cached != null) return cached;
        PcmClip clip = read(file);
        CACHE.put(key, clip);
        return clip;
    }

    /** Forget every decoded clip — what a "rescan sound pack folder" does. */
    public static void clearCache() {
        CACHE.clear();
    }

    /** How many clips are held in memory (the editor reports this). */
    public static int cachedCount() {
        return CACHE.size();
    }

    private static String cacheKey(Path file) {
        try {
            return file.toAbsolutePath() + "@" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            return file.toString();
        }
    }

    private static PcmClip read(Path file) {
        byte[] data;
        try {
            if (!Files.isRegularFile(file)) return PcmClip.SILENCE;
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                System.err.println("Sound: " + file + " is " + (size / (1024 * 1024))
                        + " MB — too big to load as a sound effect");
                return PcmClip.SILENCE;
            }
            data = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            System.err.println("Sound: could not read " + file + " (" + e.getMessage() + ")");
            return PcmClip.SILENCE;
        }
        String name = file.getFileName().toString().toLowerCase();
        boolean mp3First = name.endsWith(".mp3") || Mp3Decoder.looksLikeMp3(data);
        PcmClip clip = mp3First ? tryMp3(data) : trySampled(data);
        if (clip == null) clip = mp3First ? trySampled(data) : tryMp3(data);
        if (clip == null) {
            System.err.println("Sound: " + file + " is not audio this engine can read"
                    + " — use WAV or MP3");
            return PcmClip.SILENCE;
        }
        return clip;
    }

    /** Decode bytes that are already in memory, e.g. a bundled asset. */
    public static PcmClip decode(byte[] data) {
        if (data == null || data.length == 0) return PcmClip.SILENCE;
        PcmClip clip = Mp3Decoder.looksLikeMp3(data) ? tryMp3(data) : trySampled(data);
        if (clip == null) clip = trySampled(data);
        return clip == null ? PcmClip.SILENCE : clip;
    }

    private static PcmClip tryMp3(byte[] data) {
        try {
            return Mp3Decoder.decode(data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Read WAV/AIFF/AU through the JDK, converting whatever the file holds —
     * 8-bit, 24-bit, float, big-endian, unsigned — into the signed 16-bit
     * little-endian frames the mixer works in.
     */
    private static PcmClip trySampled(byte[] data) {
        try (InputStream raw = new ByteArrayInputStream(data);
             AudioInputStream in = AudioSystem.getAudioInputStream(raw)) {
            AudioFormat source = in.getFormat();
            int channels = Math.max(1, Math.min(2, source.getChannels()));
            float rate = source.getSampleRate() > 0
                    ? source.getSampleRate() : 44100f;
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    rate, 16, channels, channels * 2, rate, false);
            try (AudioInputStream pcm = AudioSystem.isConversionSupported(target, source)
                    ? AudioSystem.getAudioInputStream(target, in) : null) {
                if (pcm == null) return null;
                byte[] bytes = pcm.readAllBytes();
                short[] samples = new short[bytes.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
                }
                return new PcmClip(samples, channels, Math.round(rate));
            }
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
