package com.larsons.engine.level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * How a level's JSON gets onto disk and back: deflated on the way out, and
 * either way on the way in.
 *
 * <p><b>Why compress at all.</b> A level file is the most repetitive text this
 * engine writes. Its geometry is already run-length encoded, so what is left is
 * the encoding of the runs themselves — {@code [3,412,7,96,3,388, …]} — which
 * is thousands of short decimal numbers separated by commas, out of an alphabet
 * of twelve characters. Deflate is very good at exactly that: a generated
 * world's save comes down by a factor of six to ten, and a hand-built level by
 * three or four. A player with fifty levels and a run in each of them notices
 * the difference, and nothing about the format changed to get it.
 *
 * <p><b>Lossless, and the same JSON.</b> This is a transport, not a format:
 * what is written is exactly what {@link Level#toJson()} produced, and what is
 * read back is exactly that string. There is nothing here that can round a
 * number, drop a key or reorder a map — which is the property
 * {@code LevelBytesTest} pins by saving twice and comparing, and which a real
 * compression <em>format</em> for levels would have had to earn.
 *
 * <p><b>Both shapes load, forever.</b> A level file is recognised by its first
 * two bytes: {@code 1f 8b} is deflated, anything else is text. So every level
 * written before this existed goes on loading untouched, a file a creator
 * hand-edits in a text editor goes on loading, and a level lifted out of a
 * package or off the classpath does too. Nothing has to be migrated, and there
 * is no version number to get wrong.
 *
 * <p><b>Byte-for-byte reproducible.</b> {@link GZIPOutputStream} writes a fixed
 * ten-byte header — no timestamp, no filename, no OS — so saving the same level
 * twice writes the same bytes twice. That is not a nicety: it is what lets a
 * test assert that a save is a fixed point, and what keeps a level out of a
 * version-control diff when nothing about it changed.
 */
public final class LevelFile {

    /** The two bytes every gzip member starts with. */
    private static final int MAGIC_0 = 0x1f;
    private static final int MAGIC_1 = 0x8b;

    private LevelFile() {}

    /**
     * Write {@code json} to {@code file}, deflated.
     *
     * <p>Through a temporary file and a move, so a save interrupted halfway
     * leaves the previous level rather than half of the new one — a truncated
     * deflate stream is not a level that half-loads, it is a level that does
     * not load at all, which makes atomicity matter more here than it did for
     * text.
     */
    public static void write(Path file, String json) throws IOException {
        byte[] packed = pack(json);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, packed);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code json} as the bytes a level file holds. */
    public static byte[] pack(String json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                Math.max(64, json.length() / 4));
        try (OutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // An in-memory stream cannot fail on write; treating it as fatal
            // rather than returning the text uncompressed keeps "a level file
            // is either gzip or UTF-8" a fact rather than usually a fact.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The JSON in {@code bytes}, whichever of the two shapes they are in.
     *
     * <p>Bytes that begin like gzip and then fail to inflate are read as text,
     * because a level that starts with those two characters and is not gzip is
     * a stranger thing than a corrupt one and the reader should not decide
     * which it has by guessing.
     */
    public static String unpack(byte[] bytes) {
        if (bytes == null) return null;
        if (!packed(bytes)) return new String(bytes, StandardCharsets.UTF_8);
        try (InputStream in = new GZIPInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Whether these bytes are a deflated level rather than plain text. */
    public static boolean packed(byte[] bytes) {
        return bytes != null && bytes.length >= 2
                && (bytes[0] & 0xFF) == MAGIC_0 && (bytes[1] & 0xFF) == MAGIC_1;
    }

    /** The JSON in {@code file}, or {@code null} when there is no such file. */
    public static String read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        return unpack(Files.readAllBytes(file));
    }

    /** The JSON on a stream — the classpath's shape of the same question. */
    public static String read(InputStream in) throws IOException {
        return unpack(in.readAllBytes());
    }

    /**
     * The first {@code chars} characters of a level file, without reading the
     * rest of it — what listing a folder by format needs.
     *
     * <p>Cheap on both shapes, which is the point of doing it this way rather
     * than reading the file and taking a prefix: a deflate stream inflates
     * block by block, so a reader that stops after five hundred characters has
     * decompressed about that much and touched a kilobyte of the file.
     */
    public static String head(Path file, int chars) throws IOException {
        try (InputStream raw = Files.newInputStream(file)) {
            java.io.PushbackInputStream peek = new java.io.PushbackInputStream(raw, 2);
            int b0 = peek.read();
            int b1 = peek.read();
            if (b1 >= 0) peek.unread(b1);
            if (b0 >= 0) peek.unread(b0);
            InputStream body = b0 == MAGIC_0 && b1 == MAGIC_1
                    ? new GZIPInputStream(peek) : peek;
            try (Reader in = new java.io.InputStreamReader(body, StandardCharsets.UTF_8)) {
                char[] head = new char[Math.max(1, chars)];
                int read = in.read(head);
                return read <= 0 ? "" : new String(head, 0, read);
            }
        }
    }
}
