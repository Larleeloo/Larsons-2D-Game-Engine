package com.larsons.engine.graphics.shader;

/**
 * Bright-pass glow: pixels above a luma threshold bleed light into their
 * neighbourhood.
 *
 * <p>Like real GPU bloom, this is multi-stage on the CPU: bright-pass into a
 * quarter-resolution buffer, separable box blur, then bilinear upsample-add.
 * The {@link #glsl()} form is a single-shader ring-sampled approximation of the
 * same look (a GPU backend that wants exact parity can run the same
 * downsample/blur stages as separate passes — bloom is inherently multi-pass).
 */
final class BloomPass implements ShaderPass {

    private static final float THRESHOLD = 0.55f;
    private static final float INTENSITY = 0.9f;
    private static final int BLUR_RADIUS = 2; // in quarter-res pixels

    // smoothstep(THRESHOLD, 1, luma/255) as an 8-bit fixed-point factor, baked
    // once per class load: no float math in the full-resolution bright pass.
    private static final int[] BRIGHT_FACTOR = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            BRIGHT_FACTOR[i] = Math.round(Shaders.smoothstep(THRESHOLD, 1f, i / 255f) * 256f);
        }
    }

    // Quarter-resolution work buffers, cached between frames.
    private int[] bright = new int[0];
    private int[] blurred = new int[0];
    private int qw, qh;

    @Override public String name() { return "bloom"; }

    @Override public String glsl() {
        return Shaders.fragmentShader("", """
                    vec4 base = texture(uTexture, vTexCoord);
                    vec3 sum = vec3(0.0);
                    for (int i = 0; i < 16; ++i) {
                        float ang = 6.2831853 * float(i - (i / 8) * 8) / 8.0;
                        float rad = (i < 8) ? 3.0 : 7.0;
                        vec2 off = vec2(cos(ang), sin(ang)) * rad / uResolution;
                        vec3 s = texture(uTexture, clamp(vTexCoord + off, 0.0, 1.0)).rgb;
                        float l = dot(s, vec3(0.2126, 0.7152, 0.0722));
                        sum += s * smoothstep(0.55, 1.0, l);
                    }
                    vec3 glow = sum / 16.0;
                    fragColor = vec4(clamp(base.rgb + glow * uStrength * 0.9, 0.0, 1.0), base.a);
                """);
    }

    @Override
    public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
        ensureBuffers(w, h);
        brightPass(src, w, h);
        blurHorizontal();
        blurVertical();
        composite(src, dst, w, h, ctx.strength() * INTENSITY);
    }

    private void ensureBuffers(int w, int h) {
        int nqw = Math.max(1, w / 4);
        int nqh = Math.max(1, h / 4);
        if (nqw != qw || nqh != qh) {
            qw = nqw;
            qh = nqh;
            bright = new int[qw * qh];
            blurred = new int[qw * qh];
        }
    }

    /** Average each 4×4 block, keep only what exceeds the luma threshold. */
    private void brightPass(int[] src, int w, int h) {
        ParallelRows.run(qh, (y0, y1) -> {
            for (int qy = y0; qy < y1; qy++) {
                int sy0 = qy * 4;
                int sy1 = Math.min(h, sy0 + 4);
                for (int qx = 0; qx < qw; qx++) {
                    int sx0 = qx * 4;
                    int sx1 = Math.min(w, sx0 + 4);
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int y = sy0; y < sy1; y++) {
                        int row = y * w;
                        for (int x = sx0; x < sx1; x++) {
                            int c = src[row + x];
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    if (n == 0) { bright[qy * qw + qx] = 0; continue; }
                    int ar = r / n, ag = g / n, ab = b / n;
                    int l = (13933 * ar + 46871 * ag + 4732 * ab) >> 16; // Rec.709, fixed-point
                    int f = BRIGHT_FACTOR[l];
                    bright[qy * qw + qx] = Shaders.pack(
                            (ar * f) >> 8, (ag * f) >> 8, (ab * f) >> 8);
                }
            }
        });
    }

    private void blurHorizontal() {
        int radius = BLUR_RADIUS;
        ParallelRows.run(qh, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * qw;
                for (int x = 0; x < qw; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int k = -radius; k <= radius; k++) {
                        int sx = x + k;
                        if (sx < 0 || sx >= qw) continue;
                        int c = bright[row + sx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                    blurred[row + x] = Shaders.pack(r / n, g / n, b / n);
                }
            }
        });
    }

    private void blurVertical() {
        int radius = BLUR_RADIUS;
        ParallelRows.run(qh, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < qw; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int k = -radius; k <= radius; k++) {
                        int sy = y + k;
                        if (sy < 0 || sy >= qh) continue;
                        int c = blurred[sy * qw + x];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                    bright[y * qw + x] = Shaders.pack(r / n, g / n, b / n);
                }
            }
        });
    }

    /**
     * dst = src + bilinear-upsampled glow, scaled by {@code scale}. All in
     * 8-bit fixed point: at a 4× upsample the sub-pixel offset
     * {@code (x+0.5)/4 - 0.5} is exactly {@code (2x-3)/8}, so the bilinear
     * weights are eighths — no floats needed.
     */
    private void composite(int[] src, int[] dst, int w, int h, float scale) {
        int scale256 = Math.round(scale * 256f);
        ParallelRows.run(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int ey = 2 * y - 3;
                int iy = Math.floorDiv(ey, 8);
                int ty = Math.floorMod(ey, 8) << 5; // fraction in 0..255
                int y0q = Math.max(0, Math.min(qh - 1, iy));
                int y1q = Math.max(0, Math.min(qh - 1, iy + 1));
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int ex = 2 * x - 3;
                    int ix = Math.floorDiv(ex, 8);
                    int tx = Math.floorMod(ex, 8) << 5;
                    int x0q = Math.max(0, Math.min(qw - 1, ix));
                    int x1q = Math.max(0, Math.min(qw - 1, ix + 1));

                    int c00 = bright[y0q * qw + x0q];
                    int c10 = bright[y0q * qw + x1q];
                    int c01 = bright[y1q * qw + x0q];
                    int c11 = bright[y1q * qw + x1q];

                    int w00 = ((256 - tx) * (256 - ty)) >> 8, w10 = (tx * (256 - ty)) >> 8;
                    int w01 = ((256 - tx) * ty) >> 8, w11 = (tx * ty) >> 8;

                    int gr = (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10
                            + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11) >> 8;
                    int gg = (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10
                            + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11) >> 8;
                    int gb = ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10
                            + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11) >> 8;

                    int c = src[row + x];
                    dst[row + x] = Shaders.pack(
                            ((c >> 16) & 0xFF) + ((gr * scale256) >> 8),
                            ((c >> 8) & 0xFF) + ((gg * scale256) >> 8),
                            (c & 0xFF) + ((gb * scale256) >> 8));
                }
            }
        });
    }
}
