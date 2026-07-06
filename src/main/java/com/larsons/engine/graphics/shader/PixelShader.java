package com.larsons.engine.graphics.shader;

/**
 * Convenience base for the common case: a pass whose output pixel depends only
 * on reading the source frame — the direct analogue of a fragment shader's
 * {@code main()}. Subclasses implement {@link #shade} for one pixel; this base
 * runs it across the frame in parallel stripes.
 *
 * <p>Built-in passes with hot inner loops implement {@link ShaderPass} directly
 * so the per-row loop stays monomorphic; this base trades a virtual call per
 * pixel for brevity, which is fine for user-defined effects.
 */
public abstract class PixelShader implements ShaderPass {

    private final String name;
    private final String glsl;

    protected PixelShader(String name, String glsl) {
        this.name = name;
        this.glsl = glsl;
    }

    @Override public String name() { return name; }

    @Override public String glsl() { return glsl; }

    /** Compute the output pixel at (x, y). {@code src} is the full source frame. */
    protected abstract int shade(int x, int y, int[] src, int width, int height, ShaderContext ctx);

    @Override
    public void apply(int[] src, int[] dst, int width, int height, ShaderContext ctx) {
        ParallelRows.run(height, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    dst[row + x] = shade(x, y, src, width, height, ctx);
                }
            }
        });
    }
}
