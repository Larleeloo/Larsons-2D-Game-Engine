package com.larsons.engine.gl;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The one shader every draw goes through.
 *
 * <p><b>One program, because that is what makes the batch a batch.</b> A shape
 * is a quad sampling a 1×1 white texture; a sprite is a quad sampling an atlas
 * page; a glyph is a quad sampling the same atlas page. Multiply the sample by
 * the vertex colour and all three are the same instruction, which means a
 * label between two icons appends to whatever is already open and a panel
 * behind them does too. A second program for flat geometry would be marginally
 * faster per triangle and would flush the batch every time the frame alternated
 * between a shape and a sprite — which, in this engine's UI, is every other
 * operation.
 *
 * <p><b>Colours are straight alpha, not premultiplied.</b> The atlas pages are
 * {@code TYPE_INT_ARGB} and the engine's colours are {@code 0xAARRGGBB}, both
 * straight; multiplying a straight texel by a straight vertex colour gives a
 * straight result, and {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} composites it the
 * way {@code AlphaComposite.SRC_OVER} does. Premultiplying would be the right
 * choice if anything here filtered texels — it is the arrangement that keeps
 * bilinear sampling from darkening edges — but everything samples
 * {@code GL_NEAREST}, because the Java2D backend renders with
 * {@code VALUE_INTERPOLATION_NEAREST_NEIGHBOR} and this one has to agree with
 * it pixel for pixel.
 */
final class GlProgram implements AutoCloseable {

    /** Vertex attribute slots, matched by {@link GlBatch}'s pointer setup. */
    static final int ATTRIB_POSITION = 0;
    static final int ATTRIB_UV = 1;
    static final int ATTRIB_COLOR = 2;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;

            uniform vec2 uViewport;

            out vec2 vUV;
            out vec4 vColor;

            void main() {
                // Pixel space to clip space, y down: (0,0) is the top-left
                // corner of the surface, which is where every coordinate in
                // DrawTarget is measured from.
                vec2 ndc = vec2(aPos.x / uViewport.x * 2.0 - 1.0,
                                1.0 - aPos.y / uViewport.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;

            uniform sampler2D uTexture;

            out vec4 fragColor;

            void main() {
                fragColor = texture(uTexture, vUV) * vColor;
            }
            """;

    private final int program;
    private final int viewportUniform;

    GlProgram() {
        int vs = compile(GL_VERTEX_SHADER, VERTEX, "vertex");
        int fs = compile(GL_FRAGMENT_SHADER, FRAGMENT, "fragment");
        program = glCreateProgram();
        try {
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                throw new IllegalStateException(
                        "the sprite program failed to link: " + glGetProgramInfoLog(program));
            }
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
        viewportUniform = glGetUniformLocation(program, "uViewport");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "uTexture"), 0);
    }

    void use(int width, int height) {
        glUseProgram(program);
        glUniform2f(viewportUniform, width, height);
    }

    private static int compile(int type, String source, String what) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(what + " shader failed to compile: " + log);
        }
        return shader;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }
}
