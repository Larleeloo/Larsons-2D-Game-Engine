package com.larsons.engine.graphics.shader;

/**
 * Per-frame values every shader pass can read — the CPU-side mirror of the
 * standard GLSL uniforms ({@code uResolution}, {@code uTime}, {@code uStrength})
 * declared by {@link ShaderPass#glsl()}.
 *
 * @param width    framebuffer width in pixels ({@code uResolution.x})
 * @param height   framebuffer height in pixels ({@code uResolution.y})
 * @param time     seconds since the chain was created ({@code uTime})
 * @param strength global effect strength in [0,1] ({@code uStrength})
 */
public record ShaderContext(int width, int height, float time, float strength) {
}
