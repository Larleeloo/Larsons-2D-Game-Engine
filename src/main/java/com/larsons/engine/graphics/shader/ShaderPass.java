package com.larsons.engine.graphics.shader;

import java.util.Map;

/**
 * One full-screen post-processing pass (requirement #5: shader support).
 *
 * <p><b>GLSL-first contract.</b> Every pass carries a complete GLSL 3.30
 * fragment shader ({@link #glsl()}) — the universal GPU shading language — and
 * a CPU implementation ({@link #apply}) with the same semantics. The engine's
 * default backend executes the CPU side, which is what keeps the engine running
 * out of the box on any Java machine (requirement #4: JDK only, no native
 * bindings). A GPU backend (OpenGL, or anything that consumes GLSL — Vulkan and
 * Metal via the standard SPIR-V translators, WebGL after a mechanical
 * downgrade to GLSL ES) compiles {@link #glsl()} with the shared
 * {@link Shaders#VERTEX_GLSL} fullscreen-triangle vertex shader and sets the
 * uniforms below; no per-pass porting work is needed.
 *
 * <p><b>Uniform contract</b> (what a GPU backend must bind):
 * <ul>
 *   <li>{@code sampler2D uTexture} — the frame being processed</li>
 *   <li>{@code vec2 uResolution} — framebuffer size in pixels</li>
 *   <li>{@code float uTime} — seconds since the chain started</li>
 *   <li>{@code float uStrength} — global effect strength in [0,1]</li>
 *   <li>any pass-specific scalars reported by {@link #uniforms()}</li>
 *   <li>any pass-specific vectors or arrays reported by
 *       {@link #vectorUniforms()}</li>
 * </ul>
 *
 * <p>Pixels on the CPU side are packed {@code 0xAARRGGBB} ints (the layout of a
 * {@code BufferedImage.TYPE_INT_RGB/ARGB} raster).
 */
public interface ShaderPass {

    /** Short identifier; also used as the exported {@code <name>.frag} filename. */
    String name();

    /** Complete GLSL 3.30 core fragment shader source for this pass. */
    String glsl();

    /**
     * CPU execution with the same semantics as {@link #glsl()}: read {@code src},
     * write every pixel of {@code dst}. Implementations should parallelize with
     * {@link ParallelRows#run} — see {@link PixelShader} for the common case.
     */
    void apply(int[] src, int[] dst, int width, int height, ShaderContext ctx);

    /**
     * Pass-specific scalar uniforms beyond the standard set, e.g.
     * {@code {"uPixelSize": 4.0}}. A GPU backend binds these after compiling
     * {@link #glsl()}; CPU implementations simply close over the same values.
     */
    default Map<String, Float> uniforms() {
        return Map.of();
    }

    /**
     * Pass-specific <em>vector</em> and <em>array</em> uniforms, which
     * {@link #uniforms()} cannot express because a {@code Float} is one
     * component.
     *
     * <p><b>This exists because a pass that needs one was silently unbindable.</b>
     * {@link LightingPass} declares {@code vec3 uNightTint} and the arrays
     * {@code uLightPos[]} / {@code uLightColor[]}, none of which appear in
     * {@link #uniforms()}; a backend binding only the scalar map compiles the
     * shader, links it, runs it, and gets a black tint and no lights — a
     * correct-looking picture of nothing, which is the failure mode this whole
     * plan keeps finding. The uniform contract in this interface's header is
     * what a backend is told to bind, so the thing a backend must bind belongs
     * in it.
     */
    default Map<String, Vector> vectorUniforms() {
        return Map.of();
    }

    /**
     * One vector uniform, or an array of them: {@code components} floats per
     * element, {@code values.length / components} elements.
     *
     * <p>The component count is carried rather than inferred because
     * {@code glUniform3fv} over six floats is two {@code vec3}s and
     * {@code glUniform2fv} over the same six is three {@code vec2}s, and
     * nothing in the values themselves says which the shader declared. A
     * backend that guesses is a backend that draws a plausible wrong picture.
     */
    record Vector(int components, float[] values) {

        public Vector {
            if (components < 1 || components > 4) {
                throw new IllegalArgumentException(
                        "a GLSL vector has 1 to 4 components, not " + components);
            }
            if (values.length % components != 0) {
                throw new IllegalArgumentException(
                        values.length + " floats is not a whole number of "
                                + components + "-component elements");
            }
        }

        /** A single {@code vecN}, its component count taken from the arguments. */
        public static Vector of(float... values) {
            return new Vector(values.length, values);
        }

        /** An array of {@code vecN}, flattened element after element. */
        public static Vector array(int components, float[] values) {
            return new Vector(components, values);
        }

        /** How many {@code vecN} this holds — the {@code count} of a {@code glUniform*v}. */
        public int elements() {
            return values.length / components;
        }
    }
}
