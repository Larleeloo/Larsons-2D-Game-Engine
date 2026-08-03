package com.larsons.engine.gl;

import com.larsons.engine.graphics.draw.ImageRevision;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * {@link BufferedImage} to GL texture, uploaded once and kept.
 *
 * <p><b>The upload format is not a choice, it is the one that cannot be wrong.</b>
 * Java's {@code int} pixels are {@code 0xAARRGGBB}, which on a little-endian
 * machine is byte-for-byte {@code GL_BGRA} with
 * {@code GL_UNSIGNED_INT_8_8_8_8_REV}. Both directions move whole ints and
 * nothing repacks a channel, so the entire class of bug where red and blue
 * change places — and which looks exactly like an unrelated art problem — does
 * not exist here. {@code GlShaderHarness} proved the pair round-trips at zero
 * error before any of this was written.
 *
 * <p><b>Images are mutable, and that is the interesting case.</b> An atlas page
 * is packed lazily — the first frame that draws a new glyph writes it into a
 * page this class uploaded three frames ago — and {@code TerrainCache} rebuilds
 * a chunk into the image it already has rather than allocating a third of a
 * megabyte per rebuild. Java2D never noticed either, because it blits from the
 * {@link BufferedImage} itself. Here the texture is a <em>copy</em>, and a copy
 * does not update itself.
 *
 * <p>{@link ImageRevision} is the answer, and it is checked on every lookup for
 * every image rather than only for the ones this class happens to recognise.
 * The first version of it watched atlas pages alone, on the reasoning that
 * loose images were only ever baked once — and the parity comparison
 * immediately produced a `scene-play` frame drawing the terrain from a
 * different level entirely, at 14.67 mean error against a bar of 3.58. An
 * assumption about who repaints what is exactly the sort of thing that is true
 * when it is written down and false two callers later.
 *
 * <p>Growing an atlas page needs no announcement: it swaps in a new
 * {@link BufferedImage}, and a map keyed by identity misses that by itself.
 */
final class GlTextures implements AutoCloseable {

    /**
     * Identity, not equality. Two {@link BufferedImage}s are never {@code
     * equal} anyway, but saying so here is the point: the question is "is this
     * the very image I uploaded", and an identity map answers it without
     * hashing a megapixel.
     */
    private final Map<BufferedImage, Entry> textures = new IdentityHashMap<>();

    private int white = -1;
    private long uploadedBytes;
    private int uploads;

    private static final class Entry {
        final int id;
        int revision;
        int epoch;

        Entry(int id, int revision, int epoch) {
            this.id = id;
            this.revision = revision;
            this.epoch = epoch;
        }
    }

    /**
     * A 1×1 opaque white texture — the fallback for flat shapes when the atlas
     * cannot hold one.
     *
     * <p>B8's first bullet says flat shapes should be "two triangles against a
     * 1×1 white texture, so shapes and sprites share one shader and one batch".
     * Half of that is right. They share one shader, and they do <em>not</em>
     * share a batch, because a 1×1 white texture is still a different texture
     * from the atlas page and binding it is still a flush. Measured, that
     * arrangement left every frame B6 named — `profile-overlay`,
     * `minigame-hud`, `scene-play`, `scene-auto-battler-lobby` — at exactly the
     * batch count it already had.
     *
     * <p>{@link GlTarget} therefore prefers a white region packed <em>into</em>
     * the sprite pages, and falls back to this when there is none: when the
     * atlas is switched off, or full. See {@code GlTarget.whitePixel}.
     */
    int white() {
        if (white < 0) {
            white = glGenTextures();
            editing(white);
            parameters();
            IntBuffer one = MemoryUtil.memAllocInt(1);
            try {
                one.put(0, 0xFFFFFFFF).position(0);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0,
                        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, one);
            } finally {
                MemoryUtil.memFree(one);
                done();
            }
        }
        return white;
    }

    /**
     * The texture unit uploads happen on, which is deliberately not the one
     * the shader samples.
     *
     * <p><b>This is not tidiness, it is a correctness fix with a picture
     * attached.</b> Uploading means binding, and a batch is normally holding
     * triangles that have not been drawn yet — so an upload on unit 0 replaces
     * the texture those pending triangles are about to be flushed against, and
     * they come out wearing the new image. It showed up as
     * `parallax-background` rendering its third layer with the fourth layer's
     * silhouette: an entirely plausible-looking picture, off by one bind, and
     * invisible in every frame whose textures were already resident. Uploading
     * somewhere the shader never looks makes the interference impossible rather
     * than unlikely.
     */
    private static final int EDIT_UNIT = GL_TEXTURE1;

    private static void editing(int id) {
        glActiveTexture(EDIT_UNIT);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    private static void done() {
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * The texture for {@code image}, uploading or refreshing it as needed.
     *
     * <p>The staleness check is two int compares on the common path. The global
     * epoch has not moved unless <em>some</em> image somewhere was repainted
     * since this one was uploaded, and on the overwhelming majority of frames
     * nothing was — so the per-image lookup, which is a synchronized map read,
     * is skipped entirely rather than paid once per textured draw.
     */
    int of(BufferedImage image) {
        Entry entry = textures.get(image);
        if (entry != null) {
            int epoch = ImageRevision.epoch();
            if (epoch != entry.epoch) {
                entry.epoch = epoch;
                int revision = ImageRevision.of(image);
                if (revision != entry.revision) {
                    upload(entry.id, image);
                    entry.revision = revision;
                }
            }
            return entry.id;
        }
        int id = glGenTextures();
        upload(id, image);
        textures.put(image, new Entry(id, ImageRevision.of(image), ImageRevision.epoch()));
        return id;
    }

    /** How many textures are resident — the number a page count should keep small. */
    int size() { return textures.size(); }

    /** How many uploads have happened, re-uploads included. */
    int uploads() { return uploads; }

    /** Bytes pushed across the bus, for a report that wants to say. */
    long uploadedBytes() { return uploadedBytes; }

    private void upload(int id, BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        // getRGB rather than reaching into the raster: grabbing the backing
        // array un-accelerates that image permanently (invariant 6), and an
        // atlas page that a GL upload quietly de-accelerated would slow the
        // Java2D backend down as a side effect of running the other one.
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        editing(id);
        parameters();
        IntBuffer buffer = MemoryUtil.memAllocInt(pixels.length);
        try {
            buffer.put(pixels).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
            done();
        }
        uploads++;
        uploadedBytes += (long) pixels.length * 4;
    }

    /**
     * Nearest and clamped.
     *
     * <p>Nearest because the Java2D backend renders with
     * {@code VALUE_INTERPOLATION_NEAREST_NEIGHBOR} and the two have to agree —
     * and because the art is pixel art, where a filtered upscale is not a
     * softer version of the right answer but a different one.
     *
     * <p>Clamped because a quad at a non-integer scale can ask for half a texel
     * past the edge of a region. B5's one-pixel gutter is what makes that
     * transparent instead of the neighbouring sprite, and clamping is the other
     * half of the same defence — the half that stops it reading off the page
     * entirely.
     */
    private static void parameters() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    @Override
    public void close() {
        for (Entry entry : textures.values()) glDeleteTextures(entry.id);
        textures.clear();
        if (white >= 0) {
            glDeleteTextures(white);
            white = -1;
        }
    }
}
