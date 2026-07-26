package com.larsons.engine.audio;

/**
 * A self-contained MPEG-1/2/2.5 Layer III (MP3) decoder, so a creator can
 * drop an {@code .mp3} into the {@link SoundPack} folder and hear it — the
 * JDK's {@code javax.sound.sampled} reads WAV/AIFF/AU but has no MP3 reader,
 * and the engine ships with no third-party jars by design.
 *
 * <p><b>Provenance.</b> This is a port of <b>minimp3</b>
 * (https://github.com/lieff/minimp3), which its authors dedicated to the
 * public domain under CC0 1.0 — "To the extent possible under law, the
 * author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide." The structure, the
 * packed Huffman code books ({@link Mp3Tables}) and the numeric constants
 * follow it closely; the port is Java-idiomatic where C pointer arithmetic
 * doesn't translate (array + offset pairs instead of moving pointers), and
 * adds bounds checks so a corrupt file throws rather than reading wild.
 *
 * <p>Layer III only — that is what an {@code .mp3} file contains. Layer I
 * and II streams are rejected with a clear message rather than decoded to
 * noise. ID3v2 tags at the front and ID3v1 tags at the end are skipped, and
 * decoding resynchronises across damaged frames instead of giving up, so a
 * file with a junk byte in the middle still plays.
 *
 * <p>Decoding is a pure function of the byte array: no files, no threads, no
 * audio device. {@link #decode} hands back a {@link PcmClip} the mixer can
 * play at any pitch.
 */
public final class Mp3Decoder {

    /** Bytes in a frame header. */
    private static final int HDR_SIZE = 4;
    /** The bit reservoir a frame may borrow from earlier frames. */
    private static final int MAX_BITRESERVOIR_BYTES = 511;
    /** Generous cap on one frame's payload (above the ISO maximum). */
    private static final int MAX_FRAME_PAYLOAD_BYTES = 2304;
    /** Consecutive header matches that confirm a frame start. */
    private static final int MAX_FRAME_SYNC_MATCHES = 10;

    private static final int SHORT_BLOCK_TYPE = 2;
    private static final int STOP_BLOCK_TYPE = 3;

    private static final int BITS_DEQUANTIZER_OUT = -1;
    private static final int MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210;
    private static final int MAX_SCFI = (MAX_SCF + 3) & ~3;

    /** Samples one Layer III granule produces per channel. */
    private static final int GRANULE_SAMPLES = 576;

    /** Thrown when a byte array isn't a Layer III stream this can read. */
    public static class Mp3FormatException extends RuntimeException {
        public Mp3FormatException(String message) {
            super(message);
        }
    }

    // --- decoder state (one frame's history: overlap, filter bank, reservoir) ---

    private final float[] mdctOverlap = new float[2 * 9 * 32];
    private final float[] qmfState = new float[15 * 2 * 32];
    private final byte[] reservoir = new byte[MAX_BITRESERVOIR_BYTES];
    private int reservoirLength;
    private int freeFormatBytes;
    private final byte[] header = new byte[HDR_SIZE];

    // Per-frame scratch, allocated once so decoding a long file doesn't churn.
    private final BitReader bits = new BitReader();
    /**
     * The frame's main data, gathered from the bit reservoir plus this
     * frame's payload. The Huffman reader keeps a four-byte look-ahead, so
     * the buffer carries slack past the largest legal frame rather than
     * bounds-checking the innermost loop.
     */
    private final byte[] mainData =
            new byte[MAX_BITRESERVOIR_BYTES + MAX_FRAME_PAYLOAD_BYTES + 8];
    private final GranuleInfo[] granules = {
            new GranuleInfo(), new GranuleInfo(), new GranuleInfo(), new GranuleInfo()};
    /** Both channels' spectra, channel 1 at {@link #GRANULE_SAMPLES}. */
    private final float[] grbuf = new float[2 * GRANULE_SAMPLES + 64];
    private final float[] scf = new float[40];
    private final float[] syn = new float[(18 + 15) * 64];
    private final int[][] istPos = new int[2][39];
    private final int[] iscf = new int[44];

    /**
     * Decode a whole MP3 file held in memory.
     *
     * @throws Mp3FormatException when no Layer III frame can be found, or the
     *                            stream is a Layer I/II one this can't read
     */
    public static PcmClip decode(byte[] mp3) {
        return new Mp3Decoder().decodeAll(mp3);
    }

    /**
     * Whether {@code data} starts like an MP3 — an ID3v2 tag or a frame sync.
     * Used to pick a decoder when a file's extension can't be trusted.
     */
    public static boolean looksLikeMp3(byte[] data) {
        if (data == null || data.length < HDR_SIZE) return false;
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        for (int i = 0; i < Math.min(data.length - HDR_SIZE, 8192); i++) {
            if (headerValid(data, i)) return true;
        }
        return false;
    }

    private PcmClip decodeAll(byte[] mp3) {
        if (mp3 == null || mp3.length < HDR_SIZE) {
            throw new Mp3FormatException("not an MP3: fewer than " + HDR_SIZE + " bytes");
        }
        int pos = skipId3v2(mp3);
        int end = trimId3v1(mp3);

        // 1152 samples per frame at 8 kHz mono is the sparsest a stream can
        // be; growing geometrically from a frame-count estimate keeps a long
        // track to a couple of copies.
        int estimate = Math.max(4096, (end - pos) * 4);
        short[] out = new short[estimate];
        int written = 0;
        int channels = 0, sampleRate = 0, frames = 0;
        short[] frameBuf = new short[1152 * 2];
        FrameInfo info = new FrameInfo();

        while (pos < end - HDR_SIZE) {
            int samples = decodeFrame(mp3, pos, end - pos, frameBuf, info);
            pos += info.frameBytes;
            if (info.frameBytes == 0) break; // no further frame in the input
            if (info.layer != 0 && info.layer != 3) {
                throw new Mp3FormatException(
                        "MPEG Layer " + info.layer + " audio is not supported —"
                                + " save the file as MP3 (Layer III) or WAV");
            }
            if (samples == 0) continue;      // a skipped/undecodable frame
            if (frames == 0) {
                channels = info.channels;
                sampleRate = info.hz;
            }
            frames++;
            int count = samples * info.channels;
            if (written + count > out.length) {
                short[] bigger = new short[Math.max(out.length * 2, written + count)];
                System.arraycopy(out, 0, bigger, 0, written);
                out = bigger;
            }
            System.arraycopy(frameBuf, 0, out, written, count);
            written += count;
        }
        if (frames == 0 || written == 0) {
            throw new Mp3FormatException("no MP3 (MPEG Layer III) frames found");
        }
        short[] exact = new short[written];
        System.arraycopy(out, 0, exact, 0, written);
        return new PcmClip(exact, channels, sampleRate);
    }

    /** Byte offset of the audio, past any ID3v2 tag at the front. */
    private static int skipId3v2(byte[] data) {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        // A syncsafe 28-bit size: seven bits per byte, high bit always clear.
        int size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14)
                | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F);
        int total = 10 + size;
        if ((data[5] & 0x10) != 0) total += 10; // a footer, when flagged
        return total > 0 && total < data.length ? total : 0;
    }

    /** End of the audio, before any 128-byte ID3v1 tag at the back. */
    private static int trimId3v1(byte[] data) {
        int end = data.length;
        if (end >= 128 && data[end - 128] == 'T' && data[end - 127] == 'A'
                && data[end - 126] == 'G') {
            end -= 128;
        }
        return end;
    }

    // --- frame headers ------------------------------------------------------------

    private static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    /**
     * A main-data byte, or 0 past the end. The Huffman reader keeps a
     * four-byte look-ahead and a damaged frame can push that past the last
     * byte gathered; reading zeros there decodes to silence instead of
     * throwing out of the innermost loop.
     */
    private int md(int i) {
        return i >= 0 && i < mainData.length ? mainData[i] & 0xFF : 0;
    }

    private static boolean headerValid(byte[] h, int o) {
        if (o + HDR_SIZE > h.length) return false;
        return u(h, o) == 0xFF
                && ((u(h, o + 1) & 0xF0) == 0xF0 || (u(h, o + 1) & 0xFE) == 0xE2)
                && layerOf(h, o) != 0
                && bitrateIndex(h, o) != 15
                && sampleRateIndex(h, o) != 3;
    }

    /** Whether two headers describe the same stream (the resync test). */
    private static boolean headerCompare(byte[] h, int a, int b) {
        return headerValid(h, b)
                && ((u(h, a + 1) ^ u(h, b + 1)) & 0xFE) == 0
                && ((u(h, a + 2) ^ u(h, b + 2)) & 0x0C) == 0
                && isFreeFormat(h, a) == isFreeFormat(h, b);
    }

    private static int layerOf(byte[] h, int o) { return (u(h, o + 1) >> 1) & 3; }
    private static int bitrateIndex(byte[] h, int o) { return u(h, o + 2) >> 4; }
    private static int sampleRateIndex(byte[] h, int o) { return (u(h, o + 2) >> 2) & 3; }
    private static boolean isMono(byte[] h, int o) { return (u(h, o + 3) & 0xC0) == 0xC0; }
    private static boolean isMsStereo(byte[] h, int o) { return (u(h, o + 3) & 0xE0) == 0x60; }
    private static boolean testMsStereo(byte[] h, int o) { return (u(h, o + 3) & 0x20) != 0; }
    private static boolean testIStereo(byte[] h, int o) { return (u(h, o + 3) & 0x10) != 0; }
    private static boolean isFreeFormat(byte[] h, int o) { return (u(h, o + 2) & 0xF0) == 0; }
    private static boolean hasCrc(byte[] h, int o) { return (u(h, o + 1) & 1) == 0; }
    private static boolean testPadding(byte[] h, int o) { return (u(h, o + 2) & 2) != 0; }
    private static boolean isMpeg1(byte[] h, int o) { return (u(h, o + 1) & 8) != 0; }
    private static boolean notMpeg25(byte[] h, int o) { return (u(h, o + 1) & 0x10) != 0; }
    private static boolean isLayer1(byte[] h, int o) { return (u(h, o + 1) & 6) == 6; }
    private static boolean isFrame576(byte[] h, int o) { return (u(h, o + 1) & 14) == 2; }

    /** Sample-rate row 0..8: MPEG-1, then MPEG-2, then MPEG-2.5. */
    private static int mySampleRate(byte[] h, int o) {
        return sampleRateIndex(h, o)
                + (((u(h, o + 1) >> 3) & 1) + ((u(h, o + 1) >> 4) & 1)) * 3;
    }

    private static final int[][][] HALF_BITRATE = {
            {{0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80},
             {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 56, 64, 72, 80},
             {0, 16, 24, 28, 32, 40, 48, 56, 64, 72, 80, 88, 96, 112, 128}},
            {{0, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160},
             {0, 16, 24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192},
             {0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224}},
    };

    private static int bitrateKbps(byte[] h, int o) {
        return 2 * HALF_BITRATE[isMpeg1(h, o) ? 1 : 0][layerOf(h, o) - 1][bitrateIndex(h, o)];
    }

    private static int sampleRateHz(byte[] h, int o) {
        int base = switch (sampleRateIndex(h, o)) {
            case 0 -> 44100;
            case 1 -> 48000;
            default -> 32000;
        };
        if (!isMpeg1(h, o)) base >>= 1;   // MPEG-2 halves it
        if (!notMpeg25(h, o)) base >>= 1; // MPEG-2.5 halves it again
        return base;
    }

    private static int frameSamples(byte[] h, int o) {
        return isLayer1(h, o) ? 384 : (1152 >> (isFrame576(h, o) ? 1 : 0));
    }

    private static int frameBytes(byte[] h, int o, int freeFormatSize) {
        int bytes = frameSamples(h, o) * bitrateKbps(h, o) * 125 / sampleRateHz(h, o);
        if (isLayer1(h, o)) bytes &= ~3; // Layer I counts in four-byte slots
        return bytes != 0 ? bytes : freeFormatSize;
    }

    private static int padding(byte[] h, int o) {
        return testPadding(h, o) ? (isLayer1(h, o) ? 4 : 1) : 0;
    }

    /** Details of the frame {@link #decodeFrame} just looked at. */
    private static final class FrameInfo {
        int frameBytes;   // consumed from the input, including any skipped junk
        int channels;
        int hz;
        int layer;
    }

    /**
     * Whether several frames chain from {@code offset} at the size the header
     * claims — what tells a real frame start from an 0xFF byte inside data.
     */
    private static boolean matchFrame(byte[] mp3, int offset, int available, int size) {
        int i = 0;
        for (int matches = 0; matches < MAX_FRAME_SYNC_MATCHES; matches++) {
            i += frameBytes(mp3, offset + i, size) + padding(mp3, offset + i);
            if (i + HDR_SIZE > available) return matches > 0;
            if (!headerCompare(mp3, offset, offset + i)) return false;
        }
        return true;
    }

    /** Offset of the next frame from {@code start}; sets {@code out[0]} to its size. */
    private int findFrame(byte[] mp3, int start, int available, int[] out) {
        for (int i = 0; i < available - HDR_SIZE; i++) {
            int o = start + i;
            if (!headerValid(mp3, o)) continue;
            int size = frameBytes(mp3, o, freeFormatBytes);
            int sizeAndPadding = size + padding(mp3, o);
            if (size != 0 && i + sizeAndPadding <= available
                    && matchFrame(mp3, o, available - i, size)) {
                out[0] = sizeAndPadding;
                return i;
            }
            freeFormatBytes = 0;
        }
        out[0] = 0;
        return available;
    }

    private final int[] frameSizeOut = new int[1];

    /**
     * Decode one frame from {@code mp3} at {@code offset} into {@code pcm}
     * (interleaved 16-bit), returning the samples written per channel.
     * {@code info.frameBytes} says how much input was consumed, including any
     * junk skipped to resynchronise.
     */
    private int decodeFrame(byte[] mp3, int offset, int available,
                            short[] pcm, FrameInfo info) {
        int skipped = 0;
        int size = 0;
        info.layer = 0; // "no frame here", so the caller can tell that apart
                        // from a Layer I/II frame it must refuse
        // The common case: this frame follows the last one at the size its
        // header implies, so no scan is needed.
        if (available > 4 && (header[0] & 0xFF) == 0xFF && headerCompare(
                concatHeader(mp3, offset), 0, HDR_SIZE)) {
            size = frameBytes(mp3, offset, freeFormatBytes) + padding(mp3, offset);
            if (size != available && (size + HDR_SIZE > available
                    || !headerCompare(mp3, offset, offset + size))) {
                size = 0;
            }
        }
        if (size == 0) {
            reset();
            skipped = findFrame(mp3, offset, available, frameSizeOut);
            size = frameSizeOut[0];
            if (size == 0 || skipped + size > available) {
                info.frameBytes = skipped;
                return 0;
            }
        }
        int hdr = offset + skipped;
        System.arraycopy(mp3, hdr, header, 0, HDR_SIZE);
        info.frameBytes = skipped + size;
        info.channels = isMono(mp3, hdr) ? 1 : 2;
        info.hz = sampleRateHz(mp3, hdr);
        info.layer = 4 - layerOf(mp3, hdr);

        if (info.layer != 3) return 0; // reported to the caller, which stops

        BitReader frame = bits;
        frame.init(mp3, hdr + HDR_SIZE, size - HDR_SIZE);
        if (hasCrc(mp3, hdr)) frame.read(16);

        int mainDataBegin = readSideInfo(frame, mp3, hdr);
        if (mainDataBegin < 0 || frame.pos > frame.limit) {
            reset();
            return 0;
        }
        boolean haveReservoir = restoreReservoir(frame, mainDataBegin);
        int granuleCount = isMpeg1(mp3, hdr) ? 2 : 1;
        if (haveReservoir) {
            for (int g = 0; g < granuleCount; g++) {
                java.util.Arrays.fill(grbuf, 0, 2 * GRANULE_SAMPLES, 0f);
                decodeGranule(mp3, hdr, g * info.channels, info.channels);
                synthGranule(18, info.channels, pcm,
                        g * GRANULE_SAMPLES * info.channels);
            }
        }
        saveReservoir();
        return haveReservoir ? frameSamples(mp3, hdr) : 0;
    }

    /** A four-byte window over the previous header followed by this one. */
    private final byte[] headerPair = new byte[2 * HDR_SIZE];

    private byte[] concatHeader(byte[] mp3, int offset) {
        System.arraycopy(header, 0, headerPair, 0, HDR_SIZE);
        System.arraycopy(mp3, offset, headerPair, HDR_SIZE,
                Math.min(HDR_SIZE, mp3.length - offset));
        return headerPair;
    }

    private void reset() {
        java.util.Arrays.fill(mdctOverlap, 0f);
        java.util.Arrays.fill(qmfState, 0f);
        java.util.Arrays.fill(header, (byte) 0);
        reservoirLength = 0;
        freeFormatBytes = 0;
    }

    // --- bit reader ----------------------------------------------------------------

    /** The straightforward MSB-first reader used for headers and side info. */
    private static final class BitReader {
        byte[] buf;
        int base;   // byte offset of bit 0 within buf
        int pos;    // bits consumed
        int limit;  // bits available

        void init(byte[] data, int offset, int bytes) {
            buf = data;
            base = offset;
            pos = 0;
            limit = bytes * 8;
        }

        int read(int n) {
            int s = pos & 7;
            int shl = n + s;
            int p = base + (pos >> 3);
            pos += n;
            if (pos > limit) return 0;
            int cache = 0;
            int next = (buf[p++] & 0xFF) & (255 >> s);
            while ((shl -= 8) > 0) {
                cache |= next << shl;
                next = buf[p++] & 0xFF;
            }
            return cache | (next >>> -shl);
        }
    }

    // --- side info -----------------------------------------------------------------

    /** One granule's coding parameters, read from the frame's side info. */
    private static final class GranuleInfo {
        int[] sfbTab;
        int part23Length, bigValues, scalefacCompress;
        int globalGain, blockType, mixedBlockFlag, nLongSfb, nShortSfb;
        final int[] tableSelect = new int[3];
        final int[] regionCount = new int[3];
        final int[] subblockGain = new int[3];
        int preflag, scalefacScale, count1Table, scfsi;
    }

    /**
     * Read the frame's side info into {@link #granules}, returning how many
     * bytes of earlier frames' bit reservoir this frame's main data starts
     * in ({@code -1} when the side info is inconsistent).
     */
    private int readSideInfo(BitReader bs, byte[] hdr, int o) {
        int scfsi = 0;
        int mainDataBegin;
        int srIdx = mySampleRate(hdr, o);
        srIdx -= (srIdx != 0) ? 1 : 0;
        int grCount = isMono(hdr, o) ? 1 : 2;
        boolean mpeg1 = isMpeg1(hdr, o);

        if (mpeg1) {
            grCount *= 2;
            mainDataBegin = bs.read(9);
            scfsi = bs.read(7 + grCount);
        } else {
            mainDataBegin = bs.read(8 + grCount) >>> grCount;
        }

        int part23Sum = 0;
        for (int g = 0; g < grCount; g++) {
            GranuleInfo gr = granules[g];
            if (isMono(hdr, o)) scfsi <<= 4;
            gr.part23Length = bs.read(12);
            part23Sum += gr.part23Length;
            gr.bigValues = bs.read(9);
            if (gr.bigValues > 288) return -1;
            gr.globalGain = bs.read(8);
            gr.scalefacCompress = bs.read(mpeg1 ? 4 : 9);
            gr.sfbTab = Mp3Tables.SCF_LONG[srIdx];
            gr.nLongSfb = 22;
            gr.nShortSfb = 0;
            int tables;
            if (bs.read(1) != 0) {
                gr.blockType = bs.read(2);
                if (gr.blockType == 0) return -1;
                gr.mixedBlockFlag = bs.read(1);
                gr.regionCount[0] = 7;
                gr.regionCount[1] = 255;
                if (gr.blockType == SHORT_BLOCK_TYPE) {
                    scfsi &= 0x0F0F;
                    if (gr.mixedBlockFlag == 0) {
                        gr.regionCount[0] = 8;
                        gr.sfbTab = Mp3Tables.SCF_SHORT[srIdx];
                        gr.nLongSfb = 0;
                        gr.nShortSfb = 39;
                    } else {
                        gr.sfbTab = Mp3Tables.SCF_MIXED[srIdx];
                        gr.nLongSfb = mpeg1 ? 8 : 6;
                        gr.nShortSfb = 30;
                    }
                }
                tables = bs.read(10) << 5;
                gr.subblockGain[0] = bs.read(3);
                gr.subblockGain[1] = bs.read(3);
                gr.subblockGain[2] = bs.read(3);
            } else {
                gr.blockType = 0;
                gr.mixedBlockFlag = 0;
                tables = bs.read(15);
                gr.regionCount[0] = bs.read(4);
                gr.regionCount[1] = bs.read(3);
                gr.regionCount[2] = 255;
            }
            gr.tableSelect[0] = (tables >>> 10) & 0xFF;
            gr.tableSelect[1] = (tables >>> 5) & 31;
            gr.tableSelect[2] = tables & 31;
            gr.preflag = mpeg1 ? bs.read(1) : (gr.scalefacCompress >= 500 ? 1 : 0);
            gr.scalefacScale = bs.read(1);
            gr.count1Table = bs.read(1);
            gr.scfsi = (scfsi >>> 12) & 15;
            scfsi <<= 4;
        }
        if (part23Sum + bs.pos > bs.limit + mainDataBegin * 8) return -1;
        return mainDataBegin;
    }

    // --- bit reservoir -------------------------------------------------------------

    /**
     * Gather this frame's main data: the tail of earlier frames the encoder
     * borrowed against, then this frame's own payload. Returns false when the
     * borrowed part isn't there (the first frames after a seek or a resync),
     * whose granules are skipped rather than decoded from nothing.
     */
    private boolean restoreReservoir(BitReader bs, int mainDataBegin) {
        int frameBytes = (bs.limit - bs.pos) / 8;
        int have = Math.min(reservoirLength, mainDataBegin);
        System.arraycopy(reservoir, Math.max(0, reservoirLength - mainDataBegin),
                mainData, 0, have);
        frameBytes = Math.min(frameBytes, mainData.length - have - 8);
        System.arraycopy(bs.buf, bs.base + bs.pos / 8, mainData, have, frameBytes);
        // Zero the look-ahead slack so the Huffman reader's four-byte window
        // never carries bytes from the previous frame into this one.
        java.util.Arrays.fill(mainData, have + frameBytes,
                Math.min(mainData.length, have + frameBytes + 8), (byte) 0);
        BitReader main = mainReader;
        main.init(mainData, 0, have + frameBytes);
        return reservoirLength >= mainDataBegin;
    }

    private final BitReader mainReader = new BitReader();

    private void saveReservoir() {
        int pos = (mainReader.pos + 7) / 8;
        int remains = mainReader.limit / 8 - pos;
        if (remains > MAX_BITRESERVOIR_BYTES) {
            pos += remains - MAX_BITRESERVOIR_BYTES;
            remains = MAX_BITRESERVOIR_BYTES;
        }
        if (remains > 0) System.arraycopy(mainData, pos, reservoir, 0, remains);
        reservoirLength = Math.max(0, remains);
    }

    // --- scale factors -------------------------------------------------------------

    /** {@code y * 2^(-expQ2/4)}, in steps small enough to stay in float range. */
    private static float ldexpQ2(float y, int expQ2) {
        int e;
        do {
            e = Math.min(30 * 4, expQ2);
            y *= Mp3Tables.EXPFRAC[e & 3] * (1 << 30 >> (e >> 2));
        } while ((expQ2 -= e) > 0);
        return y;
    }

    /**
     * Read one granule's raw scale factors. {@code scfsi} selects the bands
     * this granule reuses from the previous one (MPEG-1); a negative value
     * means MPEG-2's intensity-position bookkeeping instead.
     */
    private void readScaleFactors(int[] scfOut, int[] ist, int[] scfSize,
                                  int[] scfCount, int countOffset, BitReader bs, int scfsi) {
        int scfAt = 0, istAt = 0;
        for (int i = 0; i < 4 && scfCount[countOffset + i] != 0; i++, scfsi *= 2) {
            int cnt = scfCount[countOffset + i];
            if ((scfsi & 8) != 0) {
                System.arraycopy(ist, istAt, scfOut, scfAt, cnt);
            } else {
                int nbits = scfSize[i];
                if (nbits == 0) {
                    java.util.Arrays.fill(scfOut, scfAt, scfAt + cnt, 0);
                    java.util.Arrays.fill(ist, istAt, istAt + cnt, 0);
                } else {
                    int maxScf = (scfsi < 0) ? (1 << nbits) - 1 : -1;
                    for (int k = 0; k < cnt; k++) {
                        int s = bs.read(nbits);
                        ist[istAt + k] = (s == maxScf) ? 255 : s; // 255 = "not intensity"
                        scfOut[scfAt + k] = s;
                    }
                }
            }
            istAt += cnt;
            scfAt += cnt;
        }
        scfOut[scfAt] = scfOut[scfAt + 1] = scfOut[scfAt + 2] = 0;
    }

    /** Turn the granule's scale factors into the per-band requantization gains. */
    private void decodeScaleFactors(byte[] hdr, int o, int[] ist, BitReader bs,
                                    GranuleInfo gr, int ch) {
        int[] partition = Mp3Tables.SCF_PARTITIONS[
                (gr.nShortSfb != 0 ? 1 : 0) + (gr.nLongSfb == 0 ? 1 : 0)];
        int partitionOffset = 0;
        int[] scfSize = new int[4];
        int scfShift = gr.scalefacScale + 1;
        int scfsi = gr.scfsi;

        if (isMpeg1(hdr, o)) {
            int part = Mp3Tables.SCFC_DECODE[gr.scalefacCompress];
            scfSize[1] = scfSize[0] = part >> 2;
            scfSize[3] = scfSize[2] = part & 3;
        } else {
            int ist2 = (testIStereo(hdr, o) && ch != 0) ? 1 : 0;
            int sfc = gr.scalefacCompress >>> ist2;
            int k = ist2 * 3 * 4;
            for (; sfc >= 0; k += 4) {
                int modprod = 1;
                for (int i = 3; i >= 0; i--) {
                    scfSize[i] = sfc / modprod % Mp3Tables.MOD[k + i];
                    modprod *= Mp3Tables.MOD[k + i];
                }
                sfc -= modprod;
            }
            partitionOffset = k;
            scfsi = -16;
        }
        readScaleFactors(iscf, ist, scfSize, partition, partitionOffset, bs, scfsi);

        if (gr.nShortSfb != 0) {
            int sh = 3 - scfShift;
            for (int i = 0; i < gr.nShortSfb; i += 3) {
                iscf[gr.nLongSfb + i] = (iscf[gr.nLongSfb + i] + (gr.subblockGain[0] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 1] = (iscf[gr.nLongSfb + i + 1] + (gr.subblockGain[1] << sh)) & 0xFF;
                iscf[gr.nLongSfb + i + 2] = (iscf[gr.nLongSfb + i + 2] + (gr.subblockGain[2] << sh)) & 0xFF;
            }
        } else if (gr.preflag != 0) {
            for (int i = 0; i < 10; i++) {
                iscf[11 + i] = (iscf[11 + i] + Mp3Tables.PREAMP[i]) & 0xFF;
            }
        }

        int gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210
                - (isMsStereo(hdr, o) ? 2 : 0);
        float gain = ldexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExp);
        int bands = gr.nLongSfb + gr.nShortSfb;
        for (int i = 0; i < bands; i++) {
            scf[i] = ldexpQ2(gain, iscf[i] << scfShift);
        }
    }

    /** {@code x^(4/3)}, from a table below 129 and interpolated above it. */
    private static float pow43(int x) {
        if (x < 129) return Mp3Tables.POW43[16 + x];
        int mult = 256;
        if (x < 1024) {
            mult = 16;
            x <<= 3;
        }
        int sign = 2 * x & 64;
        float frac = (float) ((x & 63) - sign) / ((x & ~63) + sign);
        return Mp3Tables.POW43[16 + ((x + sign) >> 6)]
                * (1f + frac * ((4f / 3) + frac * (2f / 9))) * mult;
    }

    // --- Huffman-coded spectrum ----------------------------------------------------

    /**
     * Decode and requantize one granule's spectrum into {@code grbuf} from
     * {@code dstOffset}. The big-values region reads pairs from the code book
     * each region selects; the count1 region that follows codes quadruples of
     * -1/0/+1 until the granule's bit budget runs out.
     *
     * <p>Bits are pulled through a 32-bit cache rather than the byte-at-a-time
     * reader: this is the decoder's innermost loop.
     */
    private void huffman(int dstOffset, BitReader bs, GranuleInfo gr, int limitBits) {
        float one = 0f;
        int ireg = 0;
        int bigValCnt = gr.bigValues;
        int[] sfbTab = gr.sfbTab;
        int sfbAt = 0;
        int scfAt = 0;
        int dst = dstOffset;
        int dstEnd = dstOffset + GRANULE_SAMPLES;

        int next = bs.base + bs.pos / 8;
        int cache = (((md(next) * 256 + md(next + 1)) * 256
                + md(next + 2)) * 256 + md(next + 3)) << (bs.pos & 7);
        int sh = (bs.pos & 7) - 8;
        next += 4;

        while (bigValCnt > 0 && ireg < 3) {
            int tabNum = gr.tableSelect[ireg];
            int sfbCnt = gr.regionCount[ireg++];
            int codebook = Mp3Tables.TAB_INDEX[tabNum];
            int linbits = Mp3Tables.LINBITS[tabNum];
            int np;
            do {
                if (sfbAt >= sfbTab.length) { // truncated band table
                    bs.pos = limitBits;
                    return;
                }
                np = sfbTab[sfbAt++] / 2;
                int pairs = Math.min(bigValCnt, np);
                one = scf[scfAt++];
                do {
                    int w = 5;
                    int leaf = Mp3Tables.TABS[codebook + (cache >>> (32 - w))];
                    while (leaf < 0) {
                        cache <<= w;
                        sh += w;
                        w = leaf & 7;
                        leaf = Mp3Tables.TABS[codebook + (cache >>> (32 - w)) - (leaf >> 3)];
                    }
                    cache <<= (leaf >> 8);
                    sh += (leaf >> 8);

                    for (int j = 0; j < 2; j++, dst++, leaf >>= 4) {
                        int lsb = leaf & 0x0F;
                        if (linbits != 0 && lsb == 15) {
                            lsb += cache >>> (32 - linbits);
                            cache <<= linbits;
                            sh += linbits;
                            while (sh >= 0) {
                                cache |= md(next++) << sh;
                                sh -= 8;
                            }
                            grbuf[dst] = one * pow43(lsb) * (cache < 0 ? -1 : 1);
                        } else {
                            grbuf[dst] = Mp3Tables.POW43[16 + lsb - 16 * (cache >>> 31)] * one;
                        }
                        if (lsb != 0) {
                            cache <<= 1;
                            sh += 1;
                        }
                    }
                    while (sh >= 0) {
                        cache |= md(next++) << sh;
                        sh -= 8;
                    }
                } while (--pairs > 0);
            } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
        }

        int[] count1 = gr.count1Table != 0 ? Mp3Tables.TAB33 : Mp3Tables.TAB32;
        for (int np = 1 - bigValCnt; ; dst += 4) {
            int leaf = count1[cache >>> 28];
            if ((leaf & 8) == 0) {
                leaf = count1[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
            }
            cache <<= (leaf & 7);
            sh += (leaf & 7);
            int bsPos = (next - bs.base) * 8 - 24 + sh;
            if (bsPos > limitBits) break;
            // A truncated or hostile stream can claim more quadruples than a
            // granule holds; stop at the granule instead of running past it.
            if (dst + 4 > dstEnd) break;
            if (--np == 0) {
                if (sfbAt >= sfbTab.length) break;
                np = sfbTab[sfbAt++] / 2;
                if (np == 0) break;
                one = scf[scfAt++];
            }
            if ((leaf & 128) != 0) {
                grbuf[dst] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if ((leaf & 64) != 0) {
                grbuf[dst + 1] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if (--np == 0) {
                if (sfbAt >= sfbTab.length) break;
                np = sfbTab[sfbAt++] / 2;
                if (np == 0) break;
                one = scf[scfAt++];
            }
            if ((leaf & 32) != 0) {
                grbuf[dst + 2] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if ((leaf & 16) != 0) {
                grbuf[dst + 3] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            while (sh >= 0) {
                cache |= md(next++) << sh;
                sh -= 8;
            }
        }
        bs.pos = limitBits;
    }

    // --- stereo --------------------------------------------------------------------

    /** Undo mid/side coding in place: L = M + S, R = M - S. */
    private void midSideStereo(int leftOffset, int n) {
        int right = leftOffset + GRANULE_SAMPLES;
        for (int i = 0; i < n; i++) {
            float a = grbuf[leftOffset + i];
            float b = grbuf[right + i];
            grbuf[leftOffset + i] = a + b;
            grbuf[right + i] = a - b;
        }
    }

    private void intensityStereoBand(int leftOffset, int n, float kl, float kr) {
        for (int i = 0; i < n; i++) {
            grbuf[leftOffset + i + GRANULE_SAMPLES] = grbuf[leftOffset + i] * kr;
            grbuf[leftOffset + i] = grbuf[leftOffset + i] * kl;
        }
    }

    /** The highest band per short-block window that carries any right channel. */
    private void stereoTopBand(int rightOffset, int[] sfb, int nbands, int[] maxBand) {
        maxBand[0] = maxBand[1] = maxBand[2] = -1;
        int at = rightOffset;
        for (int i = 0; i < nbands; i++) {
            for (int k = 0; k < sfb[i]; k += 2) {
                if (grbuf[at + k] != 0 || grbuf[at + k + 1] != 0) {
                    maxBand[i % 3] = i;
                    break;
                }
            }
            at += sfb[i];
        }
    }

    private void stereoProcess(int leftOffset, int[] ist, int[] sfb, byte[] hdr, int o,
                               int[] maxBand, int mpeg2Sh) {
        int maxPos = isMpeg1(hdr, o) ? 7 : 64;
        int at = leftOffset;
        for (int i = 0; sfb[i] != 0; i++) {
            int ipos = ist[i];
            if (i > maxBand[i % 3] && ipos < maxPos) {
                float kl, kr;
                float s = testMsStereo(hdr, o) ? 1.41421356f : 1f;
                if (isMpeg1(hdr, o)) {
                    kl = Mp3Tables.PAN[2 * ipos];
                    kr = Mp3Tables.PAN[2 * ipos + 1];
                } else {
                    kl = 1;
                    kr = ldexpQ2(1, ((ipos + 1) >> 1) << mpeg2Sh);
                    if ((ipos & 1) != 0) {
                        kl = kr;
                        kr = 1;
                    }
                }
                intensityStereoBand(at, sfb[i], kl * s, kr * s);
            } else if (testMsStereo(hdr, o)) {
                midSideStereo(at, sfb[i]);
            }
            at += sfb[i];
        }
    }

    private void intensityStereo(int leftOffset, int[] ist, int granuleIndex, byte[] hdr, int o) {
        GranuleInfo gr = granules[granuleIndex];
        int[] maxBand = new int[3];
        int nSfb = gr.nLongSfb + gr.nShortSfb;
        int maxBlocks = gr.nShortSfb != 0 ? 3 : 1;

        stereoTopBand(leftOffset + GRANULE_SAMPLES, gr.sfbTab, nSfb, maxBand);
        if (gr.nLongSfb != 0) {
            maxBand[0] = maxBand[1] = maxBand[2] =
                    Math.max(Math.max(maxBand[0], maxBand[1]), maxBand[2]);
        }
        for (int i = 0; i < maxBlocks; i++) {
            int defaultPos = isMpeg1(hdr, o) ? 3 : 0;
            int itop = nSfb - maxBlocks + i;
            int prev = itop - maxBlocks;
            ist[itop] = maxBand[i] >= prev ? defaultPos : ist[prev];
        }
        stereoProcess(leftOffset, ist, gr.sfbTab, hdr, o, maxBand,
                granules[granuleIndex + 1].scalefacCompress & 1);
    }

    // --- hybrid synthesis ----------------------------------------------------------

    /** Short blocks are coded window-major; put them back in frequency order. */
    private void reorder(int grOffset, int[] sfb, int sfbOffset) {
        int src = grOffset;
        int dst = 0;
        int len;
        int at = sfbOffset;
        while ((len = sfb[at]) != 0) {
            for (int i = 0; i < len; i++, src++) {
                syn[dst++] = grbuf[src];
                syn[dst++] = grbuf[src + len];
                syn[dst++] = grbuf[src + 2 * len];
            }
            src += 2 * len;
            at += 3;
        }
        System.arraycopy(syn, 0, grbuf, grOffset, dst);
    }

    /** Butterfly the band boundaries to cancel the filter bank's aliasing. */
    private void antialias(int grOffset, int nbands) {
        for (int at = grOffset; nbands > 0; nbands--, at += 18) {
            for (int i = 0; i < 8; i++) {
                float u = grbuf[at + 18 + i];
                float d = grbuf[at + 17 - i];
                grbuf[at + 18 + i] = u * Mp3Tables.ANTIALIAS[0][i] - d * Mp3Tables.ANTIALIAS[1][i];
                grbuf[at + 17 - i] = u * Mp3Tables.ANTIALIAS[1][i] + d * Mp3Tables.ANTIALIAS[0][i];
            }
        }
    }

    private static void dct3n9(float[] y, int o) {
        float s0 = y[o], s2 = y[o + 2], s4 = y[o + 4], s6 = y[o + 6], s8 = y[o + 8];
        float t0 = s0 + s6 * 0.5f;
        s0 -= s6;
        float t4 = (s4 + s2) * 0.93969262f;
        float t2 = (s8 + s2) * 0.76604444f;
        s6 = (s4 - s8) * 0.17364818f;
        s4 += s8 - s2;

        s2 = s0 - s4 * 0.5f;
        y[o + 4] = s4 + s0;
        s8 = t0 - t2 + s6;
        s0 = t0 - t4 + t2;
        s4 = t0 + t4 - s6;

        float s1 = y[o + 1], s3 = y[o + 3], s5 = y[o + 5], s7 = y[o + 7];
        s3 *= 0.86602540f;
        t0 = (s5 + s1) * 0.98480775f;
        t4 = (s5 - s7) * 0.34202014f;
        t2 = (s1 + s7) * 0.64278761f;
        s1 = (s1 - s5 - s7) * 0.86602540f;

        s5 = t0 - s3 - t2;
        s7 = t4 - s3 - t0;
        s3 = t4 + s3 - t2;

        y[o] = s4 - s7;
        y[o + 1] = s2 + s1;
        y[o + 2] = s0 - s3;
        y[o + 3] = s8 + s5;
        y[o + 5] = s8 - s5;
        y[o + 6] = s0 + s3;
        y[o + 7] = s2 - s1;
        y[o + 8] = s4 + s7;
    }

    private final float[] imdctCo = new float[9];
    private final float[] imdctSi = new float[9];

    /** The 36-point IMDCT (long blocks), overlap-added with the last granule. */
    private void imdct36(int grOffset, int overlapOffset, float[] window, int nbands) {
        float[] co = imdctCo;
        float[] si = imdctSi;
        for (int j = 0; j < nbands; j++, grOffset += 18, overlapOffset += 9) {
            co[0] = -grbuf[grOffset];
            si[0] = grbuf[grOffset + 17];
            for (int i = 0; i < 4; i++) {
                si[8 - 2 * i] = grbuf[grOffset + 4 * i + 1] - grbuf[grOffset + 4 * i + 2];
                co[1 + 2 * i] = grbuf[grOffset + 4 * i + 1] + grbuf[grOffset + 4 * i + 2];
                si[7 - 2 * i] = grbuf[grOffset + 4 * i + 4] - grbuf[grOffset + 4 * i + 3];
                co[2 + 2 * i] = -(grbuf[grOffset + 4 * i + 3] + grbuf[grOffset + 4 * i + 4]);
            }
            dct3n9(co, 0);
            dct3n9(si, 0);
            si[1] = -si[1];
            si[3] = -si[3];
            si[5] = -si[5];
            si[7] = -si[7];

            for (int i = 0; i < 9; i++) {
                float ovl = mdctOverlap[overlapOffset + i];
                float sum = co[i] * Mp3Tables.TWID9[9 + i] + si[i] * Mp3Tables.TWID9[i];
                mdctOverlap[overlapOffset + i] =
                        co[i] * Mp3Tables.TWID9[i] - si[i] * Mp3Tables.TWID9[9 + i];
                grbuf[grOffset + i] = ovl * window[i] - sum * window[9 + i];
                grbuf[grOffset + 17 - i] = ovl * window[9 + i] + sum * window[i];
            }
        }
    }

    private static void idct3(float x0, float x1, float x2, float[] dst) {
        float m1 = x1 * 0.86602540f;
        float a1 = x0 - x2 * 0.5f;
        dst[1] = x0 + x2;
        dst[0] = a1 + m1;
        dst[2] = a1 - m1;
    }

    private final float[] short3Co = new float[3];
    private final float[] short3Si = new float[3];
    private final float[] shortTmp = new float[18];

    /** One 12-point IMDCT of a short block's three windows. */
    private void imdct12(float[] x, int xo, float[] dst, int dsto, int overlapOffset) {
        float[] co = short3Co;
        float[] si = short3Si;
        idct3(-x[xo], x[xo + 6] + x[xo + 3], x[xo + 12] + x[xo + 9], co);
        idct3(x[xo + 15], x[xo + 12] - x[xo + 9], x[xo + 6] - x[xo + 3], si);
        si[1] = -si[1];

        for (int i = 0; i < 3; i++) {
            float ovl = mdctOverlap[overlapOffset + i];
            float sum = co[i] * Mp3Tables.TWID3[3 + i] + si[i] * Mp3Tables.TWID3[i];
            mdctOverlap[overlapOffset + i] =
                    co[i] * Mp3Tables.TWID3[i] - si[i] * Mp3Tables.TWID3[3 + i];
            dst[dsto + i] = ovl * Mp3Tables.TWID3[2 - i] - sum * Mp3Tables.TWID3[5 - i];
            dst[dsto + 5 - i] = ovl * Mp3Tables.TWID3[5 - i] + sum * Mp3Tables.TWID3[2 - i];
        }
    }

    private void imdctShort(int grOffset, int overlapOffset, int nbands) {
        for (; nbands > 0; nbands--, overlapOffset += 9, grOffset += 18) {
            System.arraycopy(grbuf, grOffset, shortTmp, 0, 18);
            System.arraycopy(mdctOverlap, overlapOffset, grbuf, grOffset, 6);
            imdct12(shortTmp, 0, grbuf, grOffset + 6, overlapOffset + 6);
            imdct12(shortTmp, 1, grbuf, grOffset + 12, overlapOffset + 6);
            imdct12(shortTmp, 2, mdctOverlap, overlapOffset, overlapOffset + 6);
        }
    }

    /** Every other sub-band is frequency-inverted before the filter bank. */
    private void changeSign(int grOffset) {
        for (int b = 0, at = grOffset + 18; b < 32; b += 2, at += 36) {
            for (int i = 1; i < 18; i += 2) grbuf[at + i] = -grbuf[at + i];
        }
    }

    private void imdctGranule(int grOffset, int overlapOffset, int blockType, int nLongBands) {
        if (nLongBands != 0) {
            imdct36(grOffset, overlapOffset, Mp3Tables.MDCT_WINDOW[0], nLongBands);
            grOffset += 18 * nLongBands;
            overlapOffset += 9 * nLongBands;
        }
        if (blockType == SHORT_BLOCK_TYPE) {
            imdctShort(grOffset, overlapOffset, 32 - nLongBands);
        } else {
            imdct36(grOffset, overlapOffset,
                    Mp3Tables.MDCT_WINDOW[blockType == STOP_BLOCK_TYPE ? 1 : 0],
                    32 - nLongBands);
        }
    }

    /** Decode both channels of one granule up to the sub-band samples. */
    private void decodeGranule(byte[] hdr, int o, int firstGranule, int nch) {
        for (int ch = 0; ch < nch; ch++) {
            GranuleInfo gr = granules[firstGranule + ch];
            int limit = mainReader.pos + gr.part23Length;
            decodeScaleFactors(hdr, o, istPos[ch], mainReader, gr, ch);
            huffman(ch * GRANULE_SAMPLES, mainReader, gr, limit);
        }

        if (testIStereo(hdr, o)) {
            intensityStereo(0, istPos[1], firstGranule, hdr, o);
        } else if (isMsStereo(hdr, o)) {
            midSideStereo(0, GRANULE_SAMPLES);
        }

        for (int ch = 0; ch < nch; ch++) {
            GranuleInfo gr = granules[firstGranule + ch];
            int aaBands = 31;
            int nLongBands = (gr.mixedBlockFlag != 0 ? 2 : 0)
                    << (mySampleRate(hdr, o) == 2 ? 1 : 0);
            if (gr.nShortSfb != 0) {
                aaBands = nLongBands - 1;
                reorder(ch * GRANULE_SAMPLES + nLongBands * 18, gr.sfbTab, gr.nLongSfb);
            }
            antialias(ch * GRANULE_SAMPLES, aaBands);
            imdctGranule(ch * GRANULE_SAMPLES, ch * 9 * 32, gr.blockType, nLongBands);
            changeSign(ch * GRANULE_SAMPLES);
        }
    }

    // --- polyphase synthesis filter bank -------------------------------------------

    private final float[][] dctT = new float[4][8];

    /** The 32-point DCT that feeds the filter bank, one sub-band column at a time. */
    private void dct2(int grOffset, int nbands) {
        for (int k = 0; k < nbands; k++) {
            int y = grOffset + k;
            for (int i = 0; i < 8; i++) {
                float x0 = grbuf[y + i * 18];
                float x1 = grbuf[y + (15 - i) * 18];
                float x2 = grbuf[y + (16 + i) * 18];
                float x3 = grbuf[y + (31 - i) * 18];
                float t0 = x0 + x3;
                float t1 = x1 + x2;
                float t2 = (x1 - x2) * Mp3Tables.DCT_SEC[3 * i];
                float t3 = (x0 - x3) * Mp3Tables.DCT_SEC[3 * i + 1];
                dctT[0][i] = t0 + t1;
                dctT[1][i] = (t0 - t1) * Mp3Tables.DCT_SEC[3 * i + 2];
                dctT[2][i] = t3 + t2;
                dctT[3][i] = (t3 - t2) * Mp3Tables.DCT_SEC[3 * i + 2];
            }
            for (int i = 0; i < 4; i++) {
                float[] x = dctT[i];
                float x0 = x[0], x1 = x[1], x2 = x[2], x3 = x[3];
                float x4 = x[4], x5 = x[5], x6 = x[6], x7 = x[7], xt;
                xt = x0 - x7; x0 += x7;
                x7 = x1 - x6; x1 += x6;
                x6 = x2 - x5; x2 += x5;
                x5 = x3 - x4; x3 += x4;
                x4 = x0 - x3; x0 += x3;
                x3 = x1 - x2; x1 += x2;
                x[0] = x0 + x1;
                x[4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                x5 -= x7 * 0.198912367f; // rotate by PI/8
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6; xt += x6;
                x[1] = (xt + x7) * 0.50979561f;
                x[2] = (x4 + x3) * 0.54119611f;
                x[3] = (x0 - x5) * 0.60134488f;
                x[5] = (x0 + x5) * 0.89997619f;
                x[6] = (x4 - x3) * 1.30656302f;
                x[7] = (xt - x7) * 2.56291556f;
            }
            for (int i = 0; i < 7; i++, y += 4 * 18) {
                grbuf[y] = dctT[0][i];
                grbuf[y + 18] = dctT[2][i] + dctT[3][i] + dctT[3][i + 1];
                grbuf[y + 2 * 18] = dctT[1][i] + dctT[1][i + 1];
                grbuf[y + 3 * 18] = dctT[2][i + 1] + dctT[3][i] + dctT[3][i + 1];
            }
            grbuf[y] = dctT[0][7];
            grbuf[y + 18] = dctT[2][7] + dctT[3][7];
            grbuf[y + 2 * 18] = dctT[1][7];
            grbuf[y + 3 * 18] = dctT[3][7];
        }
    }

    private static short scalePcm(float sample) {
        if (sample >= 32766.5f) return 32767;
        if (sample <= -32767.5f) return -32768;
        short s = (short) (sample + 0.5f);
        if (s < 0) s--; // round away from zero, as the standard asks
        return s;
    }

    /** The two-tap pair the window's centre and edge contribute directly. */
    private void synthPair(short[] pcm, int pcmAt, int nch, float[] z, int zo) {
        float a;
        a = (z[zo + 14 * 64] - z[zo]) * 29;
        a += (z[zo + 64] + z[zo + 13 * 64]) * 213;
        a += (z[zo + 12 * 64] - z[zo + 2 * 64]) * 459;
        a += (z[zo + 3 * 64] + z[zo + 11 * 64]) * 2037;
        a += (z[zo + 10 * 64] - z[zo + 4 * 64]) * 5153;
        a += (z[zo + 5 * 64] + z[zo + 9 * 64]) * 6574;
        a += (z[zo + 8 * 64] - z[zo + 6 * 64]) * 37489;
        a += z[zo + 7 * 64] * 75038;
        pcm[pcmAt] = scalePcm(a);

        zo += 2;
        a = z[zo + 14 * 64] * 104;
        a += z[zo + 12 * 64] * 1567;
        a += z[zo + 10 * 64] * 9727;
        a += z[zo + 8 * 64] * 64019;
        a += z[zo + 6 * 64] * -9975;
        a += z[zo + 4 * 64] * -45;
        a += z[zo + 2 * 64] * 146;
        a += z[zo] * -5;
        pcm[pcmAt + 16 * nch] = scalePcm(a);
    }

    private final float[] synthA = new float[4];
    private final float[] synthB = new float[4];

    /** Window and fold 32 sub-bands into 32 PCM samples per channel. */
    private void synth(int xlOffset, short[] pcm, int dstl, int nch, float[] lins, int linsOffset) {
        int xl = xlOffset;
        int xr = xl + GRANULE_SAMPLES * (nch - 1);
        int dstr = dstl + (nch - 1);
        int zlin = linsOffset + 15 * 64;
        int w = 0;

        lins[zlin + 4 * 15] = grbuf[xl + 18 * 16];
        lins[zlin + 4 * 15 + 1] = grbuf[xr + 18 * 16];
        lins[zlin + 4 * 15 + 2] = grbuf[xl];
        lins[zlin + 4 * 15 + 3] = grbuf[xr];

        lins[zlin + 4 * 31] = grbuf[xl + 1 + 18 * 16];
        lins[zlin + 4 * 31 + 1] = grbuf[xr + 1 + 18 * 16];
        lins[zlin + 4 * 31 + 2] = grbuf[xl + 1];
        lins[zlin + 4 * 31 + 3] = grbuf[xr + 1];

        synthPair(pcm, dstr, nch, lins, linsOffset + 4 * 15 + 1);
        synthPair(pcm, dstr + 32 * nch, nch, lins, linsOffset + 4 * 15 + 64 + 1);
        synthPair(pcm, dstl, nch, lins, linsOffset + 4 * 15);
        synthPair(pcm, dstl + 32 * nch, nch, lins, linsOffset + 4 * 15 + 64);

        float[] a = synthA;
        float[] b = synthB;
        for (int i = 14; i >= 0; i--) {
            lins[zlin + 4 * i] = grbuf[xl + 18 * (31 - i)];
            lins[zlin + 4 * i + 1] = grbuf[xr + 18 * (31 - i)];
            lins[zlin + 4 * i + 2] = grbuf[xl + 1 + 18 * (31 - i)];
            lins[zlin + 4 * i + 3] = grbuf[xr + 1 + 18 * (31 - i)];
            lins[zlin + 4 * (i + 16)] = grbuf[xl + 1 + 18 * (1 + i)];
            lins[zlin + 4 * (i + 16) + 1] = grbuf[xr + 1 + 18 * (1 + i)];
            lins[zlin + 4 * (i - 16) + 2] = grbuf[xl + 18 * (1 + i)];
            lins[zlin + 4 * (i - 16) + 3] = grbuf[xr + 18 * (1 + i)];

            // Eight window taps: the first seeds the accumulators, then they
            // alternate sign on the "a" side (S1 / S2 in the C original).
            for (int k = 0; k < 8; k++) {
                float w0 = Mp3Tables.SYNTH_WIN[w++];
                float w1 = Mp3Tables.SYNTH_WIN[w++];
                int vz = zlin + 4 * i - k * 64;
                int vy = zlin + 4 * i - (15 - k) * 64;
                boolean negate = (k & 1) != 0;
                for (int j = 0; j < 4; j++) {
                    float z = lins[vz + j];
                    float y = lins[vy + j];
                    if (k == 0) {
                        b[j] = z * w1 + y * w0;
                        a[j] = z * w0 - y * w1;
                    } else {
                        b[j] += z * w1 + y * w0;
                        a[j] += negate ? (y * w1 - z * w0) : (z * w0 - y * w1);
                    }
                }
            }

            pcm[dstr + (15 - i) * nch] = scalePcm(a[1]);
            pcm[dstr + (17 + i) * nch] = scalePcm(b[1]);
            pcm[dstl + (15 - i) * nch] = scalePcm(a[0]);
            pcm[dstl + (17 + i) * nch] = scalePcm(b[0]);
            pcm[dstr + (47 - i) * nch] = scalePcm(a[3]);
            pcm[dstr + (49 + i) * nch] = scalePcm(b[3]);
            pcm[dstl + (47 - i) * nch] = scalePcm(a[2]);
            pcm[dstl + (49 + i) * nch] = scalePcm(b[2]);
        }
    }

    private void synthGranule(int nbands, int nch, short[] pcm, int pcmOffset) {
        for (int i = 0; i < nch; i++) {
            dct2(GRANULE_SAMPLES * i, nbands);
        }
        System.arraycopy(qmfState, 0, syn, 0, 15 * 64);
        for (int i = 0; i < nbands; i += 2) {
            synth(i, pcm, pcmOffset + 32 * nch * i, nch, syn, i * 64);
        }
        if (nch == 1) {
            // Mono only fills the even lanes; copying the odd ones back would
            // carry the previous granule's right channel into the next.
            for (int i = 0; i < 15 * 64; i += 2) {
                qmfState[i] = syn[nbands * 64 + i];
            }
        } else {
            System.arraycopy(syn, nbands * 64, qmfState, 0, 15 * 64);
        }
    }
}
