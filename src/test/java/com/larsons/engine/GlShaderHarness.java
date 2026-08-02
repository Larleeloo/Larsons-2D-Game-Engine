package com.larsons.engine;

import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Runs a {@link ShaderPass} on the GPU, for comparison against its CPU twin.
 *
 * <p><b>What this is for.</b> Compiling the shaders proved they are valid GLSL.
 * It proved nothing about what they <em>do</em>. {@code STEAM_PLAN.md}'s
 * Appendix A raises that separately and correctly: the two implementations of
 * each effect are written by hand from the same description, nothing has ever
 * run both, and {@code BloomPass} says in its own javadoc that its GLSL is an
 * approximation rather than a translation. Divergence there is invisible until
 * a GPU backend exists and the game looks subtly wrong on it.
 *
 * <p>So this executes the real thing: uploads a frame, renders the pass through
 * a framebuffer with the shipped fullscreen vertex shader, and reads the pixels
 * back. What comes out can be diffed against {@link ShaderPass#apply}.
 *
 * <p><b>Test scope only.</b> The engine ships with no runtime dependencies and
 * this does not change that — it is a measuring instrument, not a renderer. A
 * real GPU backend would live in its own Gradle module for the same reason.
 *
 * <p><b>Pixel layout.</b> Java's {@code int} pixels are {@code 0xAARRGGBB},
 * which on a little-endian machine is exactly {@code GL_BGRA} with
 * {@code GL_UNSIGNED_INT_8_8_8_8_REV} — so both upload and readback move whole
 * ints with no per-pixel repacking, and no chance of a channel-swap bug being
 * mistaken for a shader difference.
 */
final class GlShaderHarness implements AutoCloseable {

    private final long window;
    private final int vao;
    private boolean ready;
    private String unavailable = "";

    GlShaderHarness() {
        long created = MemoryUtil.NULL;
        int createdVao = 0;
        try {
            GLFWErrorCallback.createPrint(System.err).set();
            if (glfwInit()) {
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
                created = glfwCreateWindow(16, 16, "shader-parity",
                        MemoryUtil.NULL, MemoryUtil.NULL);
                if (created != MemoryUtil.NULL) {
                    glfwMakeContextCurrent(created);
                    GL.createCapabilities();
                    // Core profile draws nothing without a bound vertex array,
                    // even when the vertex shader invents its own positions.
                    createdVao = glGenVertexArrays();
                    ready = true;
                } else {
                    unavailable = "no GL 3.3 core context";
                }
            } else {
                unavailable = "glfwInit failed";
            }
        } catch (Throwable t) {
            unavailable = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        this.window = created;
        this.vao = createdVao;
    }

    /** Whether a context could be obtained; tests skip when it could not. */
    boolean isReady() { return ready; }

    String whyUnavailable() { return unavailable; }

    /**
     * Run {@code pass} over {@code src} on the GPU and return the result.
     *
     * @param strength the {@code uStrength} the CPU side would be given
     * @param time     the {@code uTime} the CPU side would be given
     */
    int[] run(ShaderPass pass, int[] src, int width, int height,
              float strength, float time) {
        int program = buildProgram(pass);
        int source = uploadTexture(src, width, height);
        int destination = emptyTexture(width, height);
        int fbo = glGenFramebuffers();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, destination, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("framebuffer incomplete");
            }

            glViewport(0, 0, width, height);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glUseProgram(program);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, source);
            bindUniforms(program, pass, width, height, strength, time);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 3);   // the fullscreen triangle
            glBindVertexArray(0);

            return readBack(width, height);
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(source);
            glDeleteTextures(destination);
            glDeleteProgram(program);
        }
    }

    private void bindUniforms(int program, ShaderPass pass, int width, int height,
                              float strength, float time) {
        setInt(program, "uTexture", 0);
        int resolution = glGetUniformLocation(program, "uResolution");
        if (resolution >= 0) glUniform2f(resolution, width, height);
        setFloat(program, "uTime", time);
        setFloat(program, "uStrength", strength);
        // Whatever else the pass says a backend must bind. A name here that the
        // shader does not declare resolves to -1 and is skipped, which is the
        // case ShaderCompileTest asserts never happens.
        for (Map.Entry<String, Float> extra : pass.uniforms().entrySet()) {
            setFloat(program, extra.getKey(), extra.getValue());
        }
    }

    private static void setInt(int program, String name, int value) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform1i(location, value);
    }

    private static void setFloat(int program, String name, float value) {
        int location = glGetUniformLocation(program, name);
        if (location >= 0) glUniform1f(location, value);
    }

    private static int buildProgram(ShaderPass pass) {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vs, Shaders.VERTEX_GLSL);
            glCompileShader(vs);
            requireCompiled(vs, "vertex");
            glShaderSource(fs, pass.glsl());
            glCompileShader(fs);
            requireCompiled(fs, pass.name());
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                throw new IllegalStateException(
                        pass.name() + " failed to link: " + glGetProgramInfoLog(program));
            }
            return program;
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
    }

    private static void requireCompiled(int shader, String what) {
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException(
                    what + " failed to compile: " + glGetShaderInfoLog(shader));
        }
    }

    private static int uploadTexture(int[] pixels, int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        // Nearest and clamped, to match what the CPU passes do at the edges.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        IntBuffer buffer = MemoryUtil.memAllocInt(pixels.length);
        try {
            buffer.put(pixels).flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
        return id;
    }

    private static int emptyTexture(int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);
        return id;
    }

    private static int[] readBack(int width, int height) {
        IntBuffer buffer = MemoryUtil.memAllocInt(width * height);
        try {
            glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            int[] out = new int[width * height];
            buffer.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Override
    public void close() {
        try {
            if (vao != 0) glDeleteVertexArrays(vao);
            if (window != MemoryUtil.NULL) glfwDestroyWindow(window);
            glfwTerminate();
        } catch (Throwable ignored) {
            // Tearing down a context that may never have existed.
        }
    }
}
