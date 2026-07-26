package com.larsons.engine.audio;

/**
 * One decoded sound, ready for the mixer: 16-bit signed samples, interleaved
 * when there are two channels, plus the rate they were recorded at.
 *
 * <p>This is the single currency of the audio system — WAV files, MP3 files
 * ({@link Mp3Decoder}) and the engine's own synthesized effects all become a
 * {@code PcmClip}, so {@link SoundMixer} has exactly one thing to play and
 * pitch-shifting is the same arithmetic whatever the source was.
 *
 * @param samples    interleaved 16-bit PCM ({@code L,R,L,R…} when stereo)
 * @param channels   1 (mono) or 2 (stereo)
 * @param sampleRate frames per second the clip was recorded at
 */
public record PcmClip(short[] samples, int channels, int sampleRate) {

    /** A clip with nothing in it — what a sound key with no asset resolves to. */
    public static final PcmClip SILENCE = new PcmClip(new short[0], 1, 44100);

    public PcmClip {
        if (samples == null) samples = new short[0];
        channels = Math.max(1, Math.min(2, channels));
        sampleRate = sampleRate > 0 ? sampleRate : 44100;
    }

    /** Sample frames in the clip (half the array when stereo). */
    public int frames() {
        return samples.length / channels;
    }

    /** How long the clip plays at its own rate, in seconds. */
    public double seconds() {
        return frames() / (double) sampleRate;
    }

    public boolean isEmpty() {
        return samples.length == 0;
    }

    /**
     * One channel's sample at a frame, as a float in {@code [-1, 1]}. A mono
     * clip answers the same value for either channel, so the mixer never has
     * to branch on channel count while filling a stereo buffer.
     */
    public float sample(int frame, int channel) {
        if (frame < 0 || frame >= frames()) return 0f;
        int index = channels == 1 ? frame : frame * 2 + Math.min(channel, 1);
        return samples[index] / 32768f;
    }
}
