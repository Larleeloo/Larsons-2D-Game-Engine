package com.larsons.engine.graphics.shader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The built-in shader library (requirement #5). Every effect is defined twice
 * with the same semantics: once as GLSL 3.30 (the universal GPU form — see
 * {@link ShaderPass} for the uniform contract) and once as a tight CPU loop
 * that runs on any JDK. {@link #writeGlsl} exports the GLSL sources as
 * {@code .frag}/{@code .vert} files usable in any GPU tool or backend.
 *
 * <p>Effects: grayscale, invert, color grading, vignette, scanlines, pixelate,
 * chromatic aberration, wave distortion, and bloom. Custom effects can extend
 * {@link PixelShader} (per-pixel lambda-style) or implement {@link ShaderPass}
 * directly for multi-stage algorithms.
 */
public final class Shaders {

    /**
     * Shared vertex shader for every pass: a bufferless fullscreen triangle.
     * Draw 3 vertices with no attributes; {@code gl_VertexID} generates the
     * corners and {@code vTexCoord} covers the screen in [0,1].
     */
    public static final String VERTEX_GLSL = """
            #version 330 core

            // Fullscreen triangle, no vertex buffer needed: draw 3 vertices.
            out vec2 vTexCoord;

            void main() {
                vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vTexCoord = pos;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private Shaders() {}

    // --- built-in passes -----------------------------------------------------

    /** Luminance grayscale, blended in by {@code uStrength}. */
    public static ShaderPass grayscale() {
        return new Grayscale();
    }

    /** Color inversion, blended in by {@code uStrength}. */
    public static ShaderPass invert() {
        return new Invert();
    }

    /**
     * Brightness / contrast / saturation grading.
     *
     * @param brightness added to the normalized color, typically in [-0.5, 0.5]
     * @param contrast   scale around mid-gray; 1 = unchanged
     * @param saturation 0 = grayscale, 1 = unchanged, &gt;1 = boosted
     */
    public static ShaderPass colorGrade(double brightness, double contrast, double saturation) {
        return new ColorGrade((float) brightness, (float) contrast, (float) saturation);
    }

    /** Darkened corners; classic framing effect. */
    public static ShaderPass vignette() {
        return new Vignette();
    }

    /** CRT-style horizontal scanlines (every other row darkened). */
    public static ShaderPass scanlines() {
        return new Scanlines();
    }

    /** Mosaic effect: the frame quantized to {@code pixelSize} blocks. */
    public static ShaderPass pixelate(int pixelSize) {
        return new Pixelate(Math.max(1, pixelSize));
    }

    /** RGB channel separation growing toward the screen edges. */
    public static ShaderPass chromaticAberration() {
        return new Chromatic();
    }

    /** Animated sine-wave UV distortion (uses {@code uTime}). */
    public static ShaderPass wave() {
        return new Wave();
    }

    /** Bright-pass glow. */
    public static ShaderPass bloom() {
        return new BloomPass();
    }

    /** One instance of every built-in effect, in a sensible chain order. */
    public static List<ShaderPass> allBuiltIns() {
        return List.of(pixelate(4), wave(), chromaticAberration(), bloom(),
                colorGrade(0.0, 1.1, 1.1), grayscale(), invert(), scanlines(), vignette());
    }

    /**
     * Write {@code fullscreen.vert} plus one {@code <name>.frag} per pass into
     * {@code dir} (created if missing). Returns the written paths. This is the
     * bridge out of the engine: the exported sources compile unmodified in any
     * GLSL 3.30 toolchain.
     */
    public static List<Path> writeGlsl(List<ShaderPass> passes, Path dir) {
        try {
            Files.createDirectories(dir);
            List<Path> written = new ArrayList<>();
            Path vert = dir.resolve("fullscreen.vert");
            Files.writeString(vert, VERTEX_GLSL);
            written.add(vert);
            for (ShaderPass p : passes) {
                Path frag = dir.resolve(p.name() + ".frag");
                Files.writeString(frag, p.glsl());
                written.add(frag);
            }
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- shared helpers ------------------------------------------------------

    /**
     * Standard fragment shader skeleton with the engine's uniform contract —
     * use it for custom passes so they honour the same contract as built-ins:
     * {@code fragmentShader("uniform float uMyKnob;", "    fragColor = ...;")}.
     */
    public static String fragmentShader(String extraUniforms, String body) {
        return "#version 330 core\n\n"
                + "in vec2 vTexCoord;\n"
                + "out vec4 fragColor;\n\n"
                + "uniform sampler2D uTexture;\n"
                + "uniform vec2  uResolution;\n"
                + "uniform float uTime;\n"
                + "uniform float uStrength;\n"
                + (extraUniforms.isEmpty() ? "" : extraUniforms + "\n")
                + "\n" + FLIP_Y_GLSL
                + "\nvoid main() {\n" + body + "}\n";
    }

    /**
     * Converts between the two coordinate spaces a pass has to live in, and
     * every pass that measures a position rather than merely samples one must
     * use it.
     *
     * <p><b>{@code vTexCoord} is a texture coordinate, not a screen position,
     * and the difference is a whole bug.</b> The scene arrives as a GL texture
     * rendered the right way up, which in GL's numbering puts the <em>top</em>
     * of the picture at {@code v = 1}. So sampling with {@code vTexCoord} is
     * correct and reads the pixel this fragment is standing on — while treating
     * {@code vTexCoord.y} as a screen row reads it upside down. Everything a
     * pass is handed from outside is in screen space: {@link LightingPass}'
     * light positions come from {@code Camera.worldToScreen}, and every CPU
     * {@code apply} indexes its arrays with row 0 at the top.
     *
     * <p><b>It shipped as a mirrored lighting pass.</b> Lights were reflected
     * about the middle of the screen, which is invisible for anything on the
     * player's own level — the camera keeps the player near that middle — and
     * grows worse the further the view moves vertically. Reported as glowing
     * that does not line up with the block it belongs to, and that wanders as
     * you climb.
     *
     * <p><b>Its own inverse</b>, so one function converts in both directions:
     * screen space in, texture space out, or the other way round. A pass that
     * computes a position in screen space and then samples with it needs both
     * ends — see {@code pixelate} and {@code wave}.
     *
     * <p>Passes that are symmetric in y need it and are none the worse for
     * skipping it: {@code vignette} measures {@code length(uv - 0.5)} and
     * {@code bloom} walks a full ring of offsets, and a mirror leaves both
     * unchanged. They are left alone rather than decorated, so that the
     * presence of this call in a shader means something.
     */
    public static final String FLIP_Y_GLSL =
            "// Screen space <-> texture space. GL puts v = 0 at the bottom of the\n"
            + "// picture; the frame's own coordinates put y = 0 at the top. Its own\n"
            + "// inverse, so it converts either way.\n"
            + "vec2 flipY(vec2 uv) { return vec2(uv.x, 1.0 - uv.y); }\n";

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3f - 2f * t);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static int pack(int r, int g, int b) {
        return 0xFF000000 | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    /** Rec. 709 luma of a packed pixel, in [0,255]. */
    static float luma(int argb) {
        return 0.2126f * ((argb >> 16) & 0xFF)
                + 0.7152f * ((argb >> 8) & 0xFF)
                + 0.0722f * (argb & 0xFF);
    }

    // --- implementations -----------------------------------------------------

    private static final class Grayscale implements ShaderPass {
        @Override public String name() { return "grayscale"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        vec4 c = texture(uTexture, vTexCoord);
                        float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                        fragColor = vec4(mix(c.rgb, vec3(l), uStrength), c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            // 16-bit fixed-point Rec.709 (weights sum to exactly 65536) and an
            // 8-bit fixed-point mix: no float conversions in the hot loop.
            int si = Math.round(ctx.strength() * 256f);
            ParallelRows.run(h, (y0, y1) -> {
                for (int i = y0 * w, end = y1 * w; i < end; i++) {
                    int c = src[i];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    int l = (13933 * r + 46871 * g + 4732 * b) >> 16;
                    dst[i] = 0xFF000000
                            | ((r + (((l - r) * si) >> 8)) << 16)
                            | ((g + (((l - g) * si) >> 8)) << 8)
                            | (b + (((l - b) * si) >> 8));
                }
            });
        }
    }

    private static final class Invert implements ShaderPass {
        @Override public String name() { return "invert"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        vec4 c = texture(uTexture, vTexCoord);
                        fragColor = vec4(mix(c.rgb, 1.0 - c.rgb, uStrength), c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            int si = Math.round(ctx.strength() * 256f); // 8-bit fixed-point mix
            ParallelRows.run(h, (y0, y1) -> {
                for (int i = y0 * w, end = y1 * w; i < end; i++) {
                    int c = src[i];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    dst[i] = 0xFF000000
                            | ((r + (((255 - 2 * r) * si) >> 8)) << 16)
                            | ((g + (((255 - 2 * g) * si) >> 8)) << 8)
                            | (b + (((255 - 2 * b) * si) >> 8));
                }
            });
        }
    }

    private static final class ColorGrade implements ShaderPass {
        private final float brightness, contrast, saturation;

        ColorGrade(float brightness, float contrast, float saturation) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.saturation = saturation;
        }

        @Override public String name() { return "color_grade"; }

        @Override public Map<String, Float> uniforms() {
            return Map.of("uBrightness", brightness, "uContrast", contrast, "uSaturation", saturation);
        }

        @Override public String glsl() {
            return fragmentShader("""
                    uniform float uBrightness;
                    uniform float uContrast;
                    uniform float uSaturation;""", """
                        vec4 c = texture(uTexture, vTexCoord);
                        vec3 col = (c.rgb - 0.5) * uContrast + 0.5 + uBrightness;
                        float l = dot(col, vec3(0.2126, 0.7152, 0.0722));
                        col = mix(vec3(l), col, uSaturation);
                        fragColor = vec4(clamp(mix(c.rgb, col, uStrength), 0.0, 1.0), c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            float s = ctx.strength();
            ParallelRows.run(h, (y0, y1) -> {
                for (int i = y0 * w, end = y1 * w; i < end; i++) {
                    int c = src[i];
                    float r = ((c >> 16) & 0xFF) / 255f;
                    float g = ((c >> 8) & 0xFF) / 255f;
                    float b = (c & 0xFF) / 255f;
                    float gr = (r - 0.5f) * contrast + 0.5f + brightness;
                    float gg = (g - 0.5f) * contrast + 0.5f + brightness;
                    float gb = (b - 0.5f) * contrast + 0.5f + brightness;
                    float l = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb;
                    gr = l + (gr - l) * saturation;
                    gg = l + (gg - l) * saturation;
                    gb = l + (gb - l) * saturation;
                    dst[i] = pack(Math.round(clamp01(r + (gr - r) * s) * 255f),
                            Math.round(clamp01(g + (gg - g) * s) * 255f),
                            Math.round(clamp01(b + (gb - b) * s) * 255f));
                }
            });
        }
    }

    private static final class Vignette implements ShaderPass {
        // The falloff depends only on (w, h, strength), so it is baked once into
        // an 8-bit fixed-point multiplier map instead of computing a sqrt and a
        // smoothstep per pixel per frame.
        private int[] factorMap;
        private int mapW, mapH;
        private float mapStrength = Float.NaN;

        @Override public String name() { return "vignette"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        vec4 c = texture(uTexture, vTexCoord);
                        float d = length(vTexCoord - 0.5) * 1.41421356; // 0 centre -> 1 corner
                        float v = 1.0 - uStrength * 0.75 * smoothstep(0.45, 1.0, d);
                        fragColor = vec4(c.rgb * v, c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            float s = ctx.strength();
            if (factorMap == null || mapW != w || mapH != h || mapStrength != s) {
                rebuildMap(w, h, s);
            }
            int[] map = factorMap;
            ParallelRows.run(h, (y0, y1) -> {
                for (int i = y0 * w, end = y1 * w; i < end; i++) {
                    int c = src[i];
                    int v = map[i];
                    dst[i] = 0xFF000000
                            | (((((c >> 16) & 0xFF) * v) >> 8) << 16)
                            | (((((c >> 8) & 0xFF) * v) >> 8) << 8)
                            | (((c & 0xFF) * v) >> 8);
                }
            });
        }

        private void rebuildMap(int w, int h, float s) {
            int[] map = new int[w * h];
            ParallelRows.run(h, (y0, y1) -> {
                for (int y = y0; y < y1; y++) {
                    float ny = (y + 0.5f) / h - 0.5f;
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        float nx = (x + 0.5f) / w - 0.5f;
                        float d = (float) Math.sqrt(nx * nx + ny * ny) * 1.41421356f;
                        float v = 1f - s * 0.75f * smoothstep(0.45f, 1f, d);
                        map[row + x] = Math.round(v * 256f);
                    }
                }
            });
            factorMap = map;
            mapW = w;
            mapH = h;
            mapStrength = s;
        }
    }

    private static final class Scanlines implements ShaderPass {
        @Override public String name() { return "scanlines"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        vec4 c = texture(uTexture, vTexCoord);
                        // Rows counted from the top, as the CPU twin counts
                        // them — otherwise the dimmed rows are the other ones.
                        float odd = mod(floor(flipY(vTexCoord).y * uResolution.y), 2.0);
                        float v = 1.0 - uStrength * 0.35 * odd;
                        fragColor = vec4(c.rgb * v, c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            float dim = 1f - ctx.strength() * 0.35f;
            ParallelRows.run(h, (y0, y1) -> {
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    if ((y & 1) == 0) {
                        System.arraycopy(src, row, dst, row, w);
                    } else {
                        for (int x = 0; x < w; x++) {
                            int c = src[row + x];
                            dst[row + x] = pack(Math.round(((c >> 16) & 0xFF) * dim),
                                    Math.round(((c >> 8) & 0xFF) * dim),
                                    Math.round((c & 0xFF) * dim));
                        }
                    }
                }
            });
        }
    }

    private static final class Pixelate implements ShaderPass {
        private final int size;

        Pixelate(int size) { this.size = size; }

        @Override public String name() { return "pixelate"; }

        @Override public Map<String, Float> uniforms() {
            return Map.of("uPixelSize", (float) size);
        }

        @Override public String glsl() {
            return fragmentShader("uniform float uPixelSize;", """
                        // The block grid is anchored at the frame's top-left,
                        // where the CPU twin anchors it, and the arithmetic is
                        // done in the texture's own texels so it lands on a
                        // texel centre at any display scale. A block's centre
                        // expressed in UV falls exactly on a texel boundary,
                        // and a boundary flipped to the other end of the axis
                        // comes back one texel out — which is a whole row of
                        // the block picked from its neighbour.
                        vec2 ts = vec2(textureSize(uTexture, 0));
                        vec2 sp = flipY(vTexCoord) * ts;                  // screen-oriented texels
                        vec2 bs = vec2(max(uPixelSize, 1.0)) * ts / uResolution;
                        vec2 pick = floor(sp / bs) * bs + floor(bs * 0.5) + 0.5;
                        fragColor = texture(uTexture, flipY(pick / ts));
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            int ps = size;
            // The x -> anchor-x mapping is the same for every row; bake it once.
            int[] sxMap = new int[w];
            for (int x = 0; x < w; x++) sxMap[x] = Math.min(w - 1, (x / ps) * ps + ps / 2);
            ParallelRows.run(h, (y0, y1) -> {
                for (int y = y0; y < y1; y++) {
                    int srcRow = Math.min(h - 1, (y / ps) * ps + ps / 2) * w;
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        dst[row + x] = src[srcRow + sxMap[x]];
                    }
                }
            });
        }
    }

    private static final class Chromatic implements ShaderPass {
        @Override public String name() { return "chromatic_aberration"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        // The split grows outward from the frame's centre, so
                        // its magnitude is symmetric — but which channel goes
                        // which way is not, and the CPU twin decides that from
                        // a top-down y.
                        vec2 uv = flipY(vTexCoord);
                        vec2 off = (uv - 0.5) * 0.012 * uStrength;
                        vec4 c = texture(uTexture, vTexCoord);
                        float r = texture(uTexture, flipY(clamp(uv + off, 0.0, 1.0))).r;
                        float b = texture(uTexture, flipY(clamp(uv - off, 0.0, 1.0))).b;
                        fragColor = vec4(r, c.g, b, c.a);
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            float k = 0.012f * ctx.strength();
            // Offsets are separable: the x shift depends only on x, the y shift
            // only on y. Bake both sample maps up front.
            int[] rxMap = new int[w];
            int[] bxMap = new int[w];
            for (int x = 0; x < w; x++) {
                int offX = Math.round(((x + 0.5f) / w - 0.5f) * k * w);
                rxMap[x] = Math.max(0, Math.min(w - 1, x + offX));
                bxMap[x] = Math.max(0, Math.min(w - 1, x - offX));
            }
            ParallelRows.run(h, (y0, y1) -> {
                for (int y = y0; y < y1; y++) {
                    int offY = Math.round(((y + 0.5f) / h - 0.5f) * k * h);
                    int ryRow = Math.max(0, Math.min(h - 1, y + offY)) * w;
                    int byRow = Math.max(0, Math.min(h - 1, y - offY)) * w;
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        int c = src[row + x];
                        dst[row + x] = 0xFF000000
                                | (src[ryRow + rxMap[x]] & 0x00FF0000)
                                | (c & 0x0000FF00)
                                | (src[byRow + bxMap[x]] & 0x000000FF);
                    }
                }
            });
        }
    }

    private static final class Wave implements ShaderPass {
        @Override public String name() { return "wave"; }

        @Override public String glsl() {
            return fragmentShader("", """
                        // The wave's phase is measured down the frame, as the
                        // CPU twin measures it, so the two ripple the same way.
                        vec2 uv = flipY(vTexCoord);
                        uv.x += sin(uv.y * 24.0 + uTime * 2.2) * 0.006 * uStrength;
                        uv.y += cos(uv.x * 18.0 + uTime * 1.7) * 0.004 * uStrength;
                        fragColor = texture(uTexture, flipY(clamp(uv, 0.0, 1.0)));
                    """);
        }

        @Override public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
            float s = ctx.strength();
            float t = ctx.time();
            ParallelRows.run(h, (y0, y1) -> {
                for (int y = y0; y < y1; y++) {
                    float v = (y + 0.5f) / h;
                    // The horizontal wobble is constant across a row; hoist it.
                    float rowShift = (float) Math.sin(v * 24f + t * 2.2f) * 0.006f * s;
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        float su = (x + 0.5f) / w + rowShift;
                        float sv = v + (float) Math.cos(su * 18f + t * 1.7f) * 0.004f * s;
                        int sx = Math.max(0, Math.min(w - 1, (int) (clamp01(su) * w)));
                        int sy = Math.max(0, Math.min(h - 1, (int) (clamp01(sv) * h)));
                        dst[row + x] = src[sy * w + sx];
                    }
                }
            });
        }
    }
}
