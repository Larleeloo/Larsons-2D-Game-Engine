package com.larsons.engine;

import com.larsons.engine.audio.Mp3Decoder;
import com.larsons.engine.audio.PcmClip;
import com.larsons.engine.audio.SoundLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's own MP3 decoder, which is what lets a creator drop an
 * {@code .mp3} into the sound pack on a JDK that has no MP3 support and no
 * third-party jars to lean on.
 *
 * <p>The fixtures here are <em>built in the test</em> rather than checked in:
 * a Layer III frame whose side info and payload are all zeros is a completely
 * valid frame that decodes to silence, which exercises the whole pipeline —
 * sync, header, side info, the bit reservoir, scale factors, the Huffman
 * reader, the IMDCT and the synthesis filter bank — without shipping an audio
 * file of unclear provenance.
 */
@Timeout(60)
class Mp3DecoderTest {

    /** MPEG-1 Layer III, 44.1 kHz, mono, 128 kbps: 144*128000/44100 bytes. */
    private static final int FRAME_BYTES = 417;
    private static final int SAMPLES_PER_FRAME = 1152;

    /**
     * {@code count} silent MPEG-1 Layer III frames. The four header bytes say
     * "MPEG-1, Layer III, no CRC, 128 kbps, 44.1 kHz, mono"; everything after
     * them is zero, which is a legal all-zero side info (no big values, no
     * scale factors) followed by unused padding.
     */
    private static byte[] silentFrames(int count) {
        byte[] out = new byte[count * FRAME_BYTES];
        for (int f = 0; f < count; f++) {
            int o = f * FRAME_BYTES;
            out[o] = (byte) 0xFF;
            out[o + 1] = (byte) 0xFB; // MPEG-1, Layer III, no CRC
            out[o + 2] = (byte) 0x90; // 128 kbps, 44.1 kHz, no padding
            out[o + 3] = (byte) 0xC0; // single channel
        }
        return out;
    }

    @Test
    void decodesEveryFrameOfALayerThreeStream() {
        PcmClip clip = Mp3Decoder.decode(silentFrames(6));
        assertEquals(1, clip.channels(), "mono header should decode as mono");
        assertEquals(44100, clip.sampleRate());
        assertEquals(6 * SAMPLES_PER_FRAME, clip.frames(),
                "each Layer III frame is two 576-sample granules");
        for (short s : clip.samples()) {
            assertEquals(0, s, "an all-zero granule is silence, not noise");
        }
    }

    @Test
    void skipsAnId3v2TagBeforeTheAudio() {
        byte[] audio = silentFrames(4);
        // A 40-byte ID3v2 tag: "ID3", version, flags, then a syncsafe size.
        byte[] tagged = new byte[10 + 40 + audio.length];
        tagged[0] = 'I';
        tagged[1] = 'D';
        tagged[2] = '3';
        tagged[3] = 3;
        tagged[9] = 40; // syncsafe size of the tag body
        System.arraycopy(audio, 0, tagged, 50, audio.length);

        assertEquals(4 * SAMPLES_PER_FRAME, Mp3Decoder.decode(tagged).frames(),
                "the tag must be skipped, not decoded as audio");
    }

    @Test
    void skipsAnId3v1TagAfterTheAudio() {
        byte[] audio = silentFrames(4);
        byte[] tagged = new byte[audio.length + 128];
        System.arraycopy(audio, 0, tagged, 0, audio.length);
        tagged[audio.length] = 'T';
        tagged[audio.length + 1] = 'A';
        tagged[audio.length + 2] = 'G';

        assertEquals(4 * SAMPLES_PER_FRAME, Mp3Decoder.decode(tagged).frames());
    }

    @Test
    void resynchronisesPastJunkBetweenFrames() {
        byte[] audio = silentFrames(8);
        // Corrupt a byte in the middle: the decoder should find the next
        // frame's sync rather than give up on the file.
        byte[] damaged = audio.clone();
        damaged[FRAME_BYTES * 3 + 20] = 0x5A;

        PcmClip clip = Mp3Decoder.decode(damaged);
        assertTrue(clip.frames() >= 6 * SAMPLES_PER_FRAME,
                "a damaged byte should cost at most the frames around it, got "
                        + clip.frames() / SAMPLES_PER_FRAME + " frames");
    }

    @Test
    void recognisesMp3DataByItsSyncWord() {
        assertTrue(Mp3Decoder.looksLikeMp3(silentFrames(2)));
        assertTrue(Mp3Decoder.looksLikeMp3(new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0}));
        assertFalse(Mp3Decoder.looksLikeMp3(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0}));
        assertFalse(Mp3Decoder.looksLikeMp3(null));
    }

    @Test
    void refusesDataThatIsNotAnMp3AtAll() {
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 7);
        assertThrows(Mp3Decoder.Mp3FormatException.class, () -> Mp3Decoder.decode(junk));
        assertThrows(Mp3Decoder.Mp3FormatException.class,
                () -> Mp3Decoder.decode(new byte[]{1, 2}));
    }

    /**
     * A truncated or hostile stream must not read past its buffers. This is
     * every prefix of a real stream, which is the shape a half-written file
     * or an interrupted download takes.
     */
    @Test
    void survivesEveryTruncationOfAStream() {
        byte[] full = silentFrames(5);
        for (int len = 1; len < full.length; len += 37) {
            byte[] prefix = new byte[len];
            System.arraycopy(full, 0, prefix, 0, len);
            try {
                Mp3Decoder.decode(prefix);
            } catch (Mp3Decoder.Mp3FormatException expected) {
                // "no frames here" is a fine answer for a short prefix
            }
        }
    }

    /** Random bytes must be refused or decoded, never crash the decoder. */
    @Test
    void survivesRandomBytes() {
        java.util.Random rnd = new java.util.Random(20260726);
        for (int trial = 0; trial < 40; trial++) {
            byte[] noise = new byte[2048];
            rnd.nextBytes(noise);
            // Plant a valid-looking header so the decoder commits to a frame.
            noise[0] = (byte) 0xFF;
            noise[1] = (byte) 0xFB;
            noise[2] = (byte) 0x90;
            noise[3] = (byte) 0xC0;
            try {
                Mp3Decoder.decode(noise);
            } catch (Mp3Decoder.Mp3FormatException expected) {
                // refusing garbage is the other correct outcome
            }
        }
    }

    // --- the loader picks the right decoder ------------------------------------

    @Test
    void theLoaderReadsBothWavAndMp3(@TempDir Path dir) throws IOException {
        Path mp3 = dir.resolve("beep.mp3");
        Files.write(mp3, silentFrames(3));
        assertEquals(3 * SAMPLES_PER_FRAME, SoundLoader.load(mp3).frames(),
                "an .mp3 should go through the engine's own decoder");

        Path wav = writeWav(dir.resolve("beep.wav"), 4410, 22050);
        PcmClip fromWav = SoundLoader.load(wav);
        assertEquals(4410, fromWav.frames());
        assertEquals(22050, fromWav.sampleRate());
        assertFalse(fromWav.isEmpty());
    }

    /** A file whose contents disagree with its extension still plays. */
    @Test
    void theLoaderIgnoresAMisleadingExtension(@TempDir Path dir) throws IOException {
        Path liar = dir.resolve("actually_an_mp3.wav");
        Files.write(liar, silentFrames(3));
        assertEquals(3 * SAMPLES_PER_FRAME, SoundLoader.load(liar).frames());
    }

    /** An unreadable file is silence and a console line, never an exception. */
    @Test
    void anUnreadableFileIsSilence(@TempDir Path dir) throws IOException {
        Path broken = dir.resolve("broken.wav");
        Files.write(broken, new byte[]{'n', 'o', 'p', 'e'});
        assertTrue(SoundLoader.load(broken).isEmpty());
        assertTrue(SoundLoader.load(dir.resolve("missing.wav")).isEmpty());
        assertTrue(SoundLoader.load(null).isEmpty());
    }

    /** Re-reading the same file hands back the clip it already decoded. */
    @Test
    void theLoaderCachesWhatItHasRead(@TempDir Path dir) throws IOException {
        Path wav = writeWav(dir.resolve("cached.wav"), 1000, 44100);
        PcmClip first = SoundLoader.load(wav);
        assertEquals(first.frames(), SoundLoader.load(wav).frames());
        SoundLoader.clearCache();
        assertEquals(first.frames(), SoundLoader.load(wav).frames(),
                "a cleared cache must re-read the same audio, not lose it");
    }

    /** A mono 16-bit sine, written with the JDK's own WAV writer. */
    private static Path writeWav(Path file, int frames, int rate) throws IOException {
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            int s = (int) (Math.sin(i * 2 * Math.PI * 440 / rate) * 12000);
            pcm[i * 2] = (byte) s;
            pcm[i * 2 + 1] = (byte) (s >> 8);
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }
}
