package com.larsons.engine.gl;

import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * An offscreen framebuffer with multisampling and a stencil, and a way to read
 * it back as {@code 0xAARRGGBB} pixels.
 *
 * <p><b>Why multisampling is not optional here.</b> The Java2D backend renders
 * every frame with {@code VALUE_ANTIALIAS_ON}, and the golden comparison is
 * against those frames. A GL surface without coverage sampling would differ
 * from them along the edge of every oval, arc, diamond and rounded corner in
 * the engine — not because anything is wrong, but because one of the two is
 * antialiased and the other is not, and that difference would swamp the metric
 * that is supposed to find real ones. Four samples is what closes the gap; it
 * is also what the shipping window asks GLFW for, so the comparison is made
 * against the surface the player gets rather than a nicer one.
 *
 * <p><b>The stencil is why this is a renderbuffer pair rather than a texture.</b>
 * {@link GlTarget} fills concave paths and clips to arbitrary shapes through
 * the stencil buffer, so the surface it draws into has to have one, and a
 * multisample colour attachment has to be resolved before anything can read
 * it. Hence two framebuffers: the multisample one everything draws into, and a
 * single-sample one it is blitted to.
 *
 * <p>Job A will want the resolved texture rather than the pixels — that is the
 * whole economic argument for doing post-processing after Job B — so
 * {@link #resolvedTexture()} is here and unused for now.
 */
final class GlSurface implements AutoCloseable {

    private final int samples;

    private int width;
    private int height;

    private int msaaFbo;
    private int msaaColour;
    private int msaaDepthStencil;
    private int resolveFbo;
    private int resolveTexture;
    private int granted;

    GlSurface(int samples) {
        this.samples = Math.max(1, samples);
    }

    /**
     * Coverage samples the driver actually gave this surface, which can be
     * fewer than were asked for and is worth reporting rather than assuming.
     * A parity number measured at one sample and a parity number measured at
     * four are different claims about the same code.
     */
    int samples() { return granted; }

    /** Allocate or reallocate for this size. A no-op when nothing changed. */
    void resize(int width, int height) {
        int w = Math.max(1, width), h = Math.max(1, height);
        if (w == this.width && h == this.height && msaaFbo != 0) return;
        release();
        this.width = w;
        this.height = h;

        msaaColour = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, msaaColour);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h);

        msaaDepthStencil = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, msaaDepthStencil);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, w, h);

        msaaFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_RENDERBUFFER, msaaColour);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                GL_RENDERBUFFER, msaaDepthStencil);
        require(msaaFbo, "multisample");
        // Asked of the framebuffer once it is complete, not of the hint that
        // was passed in — those are a request and an answer, and they differ on
        // software rasterisers and on old drivers.
        granted = glGetInteger(GL_SAMPLES);

        resolveTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, resolveTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);

        resolveFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, resolveTexture, 0);
        require(resolveFbo, "resolve");

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Draw here. */
    void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
    }

    /** Back to the window. */
    static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Collapse the multisample buffer into the single-sample one. */
    void resolve() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** The resolved single-sample texture, for Job A's ping-pong to sample. */
    int resolvedTexture() { return resolveTexture; }

    /**
     * Copy this surface onto the window, resolving multisampling on the way.
     *
     * <p>A1's presentation step. {@code glBlitFramebuffer} from the multisample
     * buffer straight to the back buffer is one driver-side resolve rather than
     * a resolve followed by a textured quad, and it needs no shader, no vertex
     * buffer and no state of its own — which matters because it runs after
     * {@link GlTarget} has finished a frame and must not disturb anything the
     * next frame assumes.
     *
     * @param deviceWidth  the drawable's width in device pixels
     * @param deviceHeight the drawable's height in device pixels
     */
    void blitToWindow(int deviceWidth, int deviceHeight) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        // Scissor and stencil are off by the time a frame ends, but a blit
        // obeys the scissor if one is left on, and a half-presented frame is a
        // hard thing to attribute later.
        glDisable(GL_SCISSOR_TEST);
        glBlitFramebuffer(0, 0, width, height, 0, 0, deviceWidth, deviceHeight,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * The resolved frame as {@code 0xAARRGGBB}, top row first.
     *
     * <p>Flipped on the way out, because GL numbers rows from the bottom of the
     * framebuffer and every raster in this engine starts at the top. Doing it
     * here rather than in the projection keeps the surface the right way up on
     * screen, which is the case that matters more often than this one.
     */
    int[] readPixels() {
        resolve();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resolveFbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        IntBuffer buffer = MemoryUtil.memAllocInt(width * height);
        try {
            glReadPixels(0, 0, width, height,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            int[] out = new int[width * height];
            for (int row = 0; row < height; row++) {
                buffer.position((height - 1 - row) * width);
                buffer.get(out, row * width, width);
            }
            return out;
        } finally {
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    private static void require(int fbo, String what) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(
                    what + " framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private void release() {
        if (msaaFbo != 0) glDeleteFramebuffers(msaaFbo);
        if (resolveFbo != 0) glDeleteFramebuffers(resolveFbo);
        if (msaaColour != 0) glDeleteRenderbuffers(msaaColour);
        if (msaaDepthStencil != 0) glDeleteRenderbuffers(msaaDepthStencil);
        if (resolveTexture != 0) glDeleteTextures(resolveTexture);
        msaaFbo = resolveFbo = msaaColour = msaaDepthStencil = resolveTexture = 0;
    }

    @Override
    public void close() {
        release();
        width = height = 0;
    }
}
