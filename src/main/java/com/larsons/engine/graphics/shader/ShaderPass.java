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
 *   <li>any pass-specific extras reported by {@link #uniforms()}</li>
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
}
