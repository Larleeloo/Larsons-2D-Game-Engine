package com.larsons.engine;

import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Compiles every shader the engine ships, on a real driver.
 *
 * <p><b>Why this is worth a dependency.</b> Every {@link ShaderPass} carries a
 * complete GLSL fragment shader alongside its CPU implementation, and until
 * this test existed <em>nothing had ever compiled any of them</em>. The only
 * call site for {@code glsl()} in main code writes the text to a file. The
 * existing {@code ShaderTest} checks for the substrings {@code #version 330
 * core} and {@code void main()}, which a shader with a bad swizzle, an
 * undeclared uniform or a type error passes just as happily as a correct one.
 * {@code STEAM_PLAN.md} records this accurately: treat the exported
 * {@code .frag} files as never-compiled drafts, and budget debugging time for
 * every pass.
 *
 * <p>This is that debugging time, spent once and kept. A GPU backend's first
 * job is to compile these; finding out here — in a test, against a message
 * from the driver — is enormously cheaper than finding out inside a
 * half-written renderer where a blank screen could mean anything.
 *
 * <p><b>LWJGL is test-only</b> and must stay that way: the engine ships with
 * no runtime dependencies, and this checks the shaders rather than running
 * them. Where no GL context can be created — headless CI, a machine with no
 * driver — every test here skips rather than fails, because "could not ask"
 * is not "the answer is no".
 */
class ShaderCompileTest {

    private static long window;
    private static boolean glReady;
    private static String unavailable = "";

    @BeforeAll
    static void openContext() {
        try {
            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                unavailable = "glfwInit failed";
                return;
            }
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(32, 32, "shader-check", MemoryUtil.NULL, MemoryUtil.NULL);
            if (window == MemoryUtil.NULL) {
                unavailable = "no GL 3.3 core context";
                return;
            }
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glReady = true;
        } catch (Throwable t) {
            unavailable = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    @AfterAll
    static void closeContext() {
        if (window != MemoryUtil.NULL) glfwDestroyWindow(window);
        try {
            glfwTerminate();
        } catch (Throwable ignored) {
            // Nothing useful to do while tearing down a context we may not have.
        }
    }

    private void requireGl() {
        assumeTrue(glReady, "no GL context available (" + unavailable + ")");
    }

    /** Compile one shader stage, returning the driver's log on failure. */
    private static String compile(int type, String source) {
        int id = glCreateShader(type);
        try {
            glShaderSource(id, source);
            glCompileShader(id);
            if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_TRUE) return null;
            return glGetShaderInfoLog(id);
        } finally {
            glDeleteShader(id);
        }
    }

    /** Compile and link a pass against the shared fullscreen vertex shader. */
    private static String linkPass(ShaderPass pass) {
        String vertexError = compile(GL_VERTEX_SHADER, Shaders.VERTEX_GLSL);
        if (vertexError != null) return "vertex shader: " + vertexError;

        String fragmentError = compile(GL_FRAGMENT_SHADER, pass.glsl());
        if (fragmentError != null) return "fragment shader: " + fragmentError;

        int vs = glCreateShader(GL_VERTEX_SHADER);
        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vs, Shaders.VERTEX_GLSL);
            glCompileShader(vs);
            glShaderSource(fs, pass.glsl());
            glCompileShader(fs);
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) return null;
            return "link: " + glGetProgramInfoLog(program);
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
            glDeleteProgram(program);
        }
    }

    @Test
    void theSharedVertexShaderCompiles() {
        requireGl();
        String error = compile(GL_VERTEX_SHADER, Shaders.VERTEX_GLSL);
        assertTrue(error == null, "the fullscreen vertex shader every pass links "
                + "against does not compile:\n" + error);
    }

    @Test
    void everyBuiltInPassCompilesAndLinks() {
        requireGl();
        List<String> broken = new ArrayList<>();
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            String error = linkPass(pass);
            if (error != null) broken.add("--- " + pass.name() + " ---\n" + error);
        }
        assertTrue(broken.isEmpty(),
                broken.size() + " of " + Shaders.allBuiltIns().size()
                        + " shaders do not build:\n" + String.join("\n", broken));
    }

    @Test
    void everyExtraUniformAPassAdvertisesExistsInItsShader() {
        // ShaderPass.uniforms() is the list a GPU backend is told to bind
        // beyond the standard four. A name there that does not appear in the
        // shader is a contract that cannot be honoured: the backend would set
        // a uniform the program does not have, and the effect would silently
        // read zero on the GPU while working perfectly on the CPU. That is the
        // worst kind of divergence to find later, and free to catch here.
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            String source = pass.glsl();
            for (String name : pass.uniforms().keySet()) {
                assertTrue(source.contains(name),
                        pass.name() + " advertises the uniform \"" + name
                                + "\" but its GLSL never declares it");
            }
        }
    }

    @Test
    void theLightingPassCompilesToo() {
        // Deliberately separate: LightingPass is not in allBuiltIns() because
        // it is gameplay rather than post-FX, so a loop over that list would
        // have skipped the one shader most likely to be wrong. It is the only
        // pass with array uniforms (uLightPos[], uLightColor[]) and a loop
        // bounded by a uniform, both of which have rules a simpler shader
        // never runs into — and it is the pass that actually runs in a level.
        requireGl();
        String error = linkPass(new com.larsons.engine.graphics.shader.LightingPass());
        assertTrue(error == null, "the lighting shader does not build:\n" + error);
    }

    @Test
    void aDeliberatelyBrokenShaderIsRejected() {
        // Proves the check can fail. A test that only ever passes is not
        // evidence that anything was checked.
        requireGl();
        // Note for the next person: ".qqqq" is *valid* — q is the fourth
        // component of the stpq set — and this test caught that mistake the
        // first time it ran. An undeclared identifier is unambiguous.
        String error = compile(GL_FRAGMENT_SHADER, """
                #version 330 core
                out vec4 fragColor;
                void main() {
                    fragColor = noSuchVariable;
                }
                """);
        assertTrue(error != null, "a shader referring to an undeclared variable "
                + "compiled — the compile check is not actually checking anything");
    }
}
