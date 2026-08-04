package com.larsons.engine.graphics.shader;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamic 2D lighting as a post-processing pass — the Side-Scroller engine's
 * {@code LightingSystem} (day/night darkness overlay with point-light
 * cutouts) ported onto the engine's GLSL-first shader pipeline, which is what
 * makes lighting compose with every other effect and work identically online:
 * the scene feeds it screen-space lights each frame, and the pass darkens and
 * tints the frame around them.
 *
 * <p>Like the original, the CPU side computes the light field at reduced
 * resolution (the side-scroller used a 1/4-res buffer) and upsamples
 * bilinearly, so cost stays flat regardless of light count. The GLSL side
 * evaluates the same falloff per fragment.
 *
 * <p><b>Uniforms.</b> Beyond the standard set, a GPU backend binds
 * {@code uLightCount}, {@code uDarkness}, {@code uAmbient}, {@code uNightTint}
 * and the arrays {@code uLightPos[i] = (x, y, radiusPx)} /
 * {@code uLightColor[i]} from {@link #lightData()}. This pass deliberately
 * ignores {@code uStrength}: darkness <em>is</em> its strength, so gameplay
 * lighting doesn't change when the user tunes post-FX strength.
 *
 * <p>Not thread-safe by design: the game loop both updates and renders.
 */
public final class LightingPass implements ShaderPass {

    public static final int MAX_LIGHTS = 32;

    /** Light-field resolution divisor (the original's render scale). */
    private static final int SCALE = 4;

    // Per-frame scene-fed state (the CPU mirror of the uniforms).
    private float darkness;          // 0 = broad daylight (pass is a no-op)
    private float ambient = 0.25f;   // light floor so night stays readable
    private float tintR = 0.28f, tintG = 0.28f, tintB = 0.55f; // night blue

    private int lightCount;
    private final float[] lightX = new float[MAX_LIGHTS];
    private final float[] lightY = new float[MAX_LIGHTS];
    private final float[] lightR = new float[MAX_LIGHTS];
    private final float[] lightRed = new float[MAX_LIGHTS];
    private final float[] lightGreen = new float[MAX_LIGHTS];
    private final float[] lightBlue = new float[MAX_LIGHTS];

    // Low-res light-field buffers, grown on demand.
    private float[] vis = new float[0];
    private float[] warmR = new float[0], warmG = new float[0], warmB = new float[0];

    // --- per-frame API (called by scenes) --------------------------------------

    /** Global darkness [0,1] for this frame; 0 disables the pass entirely. */
    public void setDarkness(double d) {
        darkness = (float) Math.max(0, Math.min(1, d));
    }

    /** Minimum visibility in fully dark areas [0,1]. */
    public void setAmbient(double a) {
        ambient = (float) Math.max(0, Math.min(1, a));
    }

    /** Colour that unlit areas shift toward (the original's night blue). */
    public void setNightTint(Color c) {
        tintR = c.getRed() / 255f;
        tintG = c.getGreen() / 255f;
        tintB = c.getBlue() / 255f;
    }

    /** Forget last frame's lights; call before re-adding visible ones. */
    public void clearLights() {
        lightCount = 0;
    }

    /**
     * Add a point light in <em>screen</em> pixels (project world lights
     * through the camera first, so zoom and perspective are already applied).
     * Silently ignored beyond {@link #MAX_LIGHTS}.
     */
    public void addLight(double screenX, double screenY, double radiusPx, Color color) {
        if (lightCount >= MAX_LIGHTS || radiusPx <= 0) return;
        int i = lightCount++;
        lightX[i] = (float) screenX;
        lightY[i] = (float) screenY;
        lightR[i] = (float) radiusPx;
        lightRed[i] = color.getRed() / 255f;
        lightGreen[i] = color.getGreen() / 255f;
        lightBlue[i] = color.getBlue() / 255f;
    }

    public float darkness() {
        return darkness;
    }

    /** How many lights this frame carries, after {@link #MAX_LIGHTS} clipping. */
    public int lightCount() {
        return lightCount;
    }

    /**
     * Raw light array data for a GPU backend:
     * {@code [x, y, radius, r, g, b] * uLightCount}, screen pixels + [0,1] colour.
     */
    public float[] lightData() {
        float[] data = new float[lightCount * 6];
        for (int i = 0; i < lightCount; i++) {
            data[i * 6] = lightX[i];
            data[i * 6 + 1] = lightY[i];
            data[i * 6 + 2] = lightR[i];
            data[i * 6 + 3] = lightRed[i];
            data[i * 6 + 4] = lightGreen[i];
            data[i * 6 + 5] = lightBlue[i];
        }
        return data;
    }

    // --- ShaderPass -------------------------------------------------------------

    @Override
    public String name() {
        return "lighting";
    }

    @Override
    public Map<String, Float> uniforms() {
        Map<String, Float> u = new LinkedHashMap<>();
        u.put("uDarkness", darkness);
        u.put("uAmbient", ambient);
        u.put("uLightCount", (float) lightCount);
        return u;
    }

    /**
     * The vectors and arrays a GPU backend must bind, alongside
     * {@link #uniforms()}.
     *
     * <p><b>All three were unbindable before this existed</b> — a {@code Float}
     * map cannot carry a {@code vec3}, so a backend binding only
     * {@link #uniforms()} got {@code uNightTint} at the GLSL default of
     * {@code vec3(0)} and two empty light arrays. That is a frame tinted to
     * black wherever it is dark and lit nowhere, which is not obviously a
     * missing uniform when you are looking at it.
     *
     * <p>{@code uLightPos} is {@code (x, y, radius)} in the same pixel space
     * the scene projected the lights into, which is why the whole vector shares
     * one unit and a backend rendering at a different scale can scale the
     * vector rather than pick it apart.
     */
    @Override
    public Map<String, ShaderPass.Vector> vectorUniforms() {
        float[] pos = new float[lightCount * 3];
        float[] color = new float[lightCount * 3];
        for (int i = 0; i < lightCount; i++) {
            pos[i * 3] = lightX[i];
            pos[i * 3 + 1] = lightY[i];
            pos[i * 3 + 2] = lightR[i];
            color[i * 3] = lightRed[i];
            color[i * 3 + 1] = lightGreen[i];
            color[i * 3 + 2] = lightBlue[i];
        }
        Map<String, ShaderPass.Vector> v = new LinkedHashMap<>();
        v.put("uNightTint", ShaderPass.Vector.of(tintR, tintG, tintB));
        v.put("uLightPos", ShaderPass.Vector.array(3, pos));
        v.put("uLightColor", ShaderPass.Vector.array(3, color));
        return v;
    }

    @Override
    public String glsl() {
        return glsl(MAX_LIGHTS);
    }

    /**
     * The same shader with the light arrays declared at {@code maxLights}.
     *
     * <p><b>For the driver that cannot afford {@link #MAX_LIGHTS}, not for
     * tuning.</b> GL 3.3 guarantees 1,024 fragment uniform components and two
     * {@code vec3[32]} arrays need a quarter of that, so on any conforming
     * driver this is called with {@link #MAX_LIGHTS} and the source is
     * identical. It exists because the alternative on a driver that reports
     * less is a link failure at the moment a player enables lighting — the
     * worst possible time to discover a limit — and because a limit that can
     * be dialled down is a limit that can be tested on a machine that does not
     * have it.
     *
     * @param maxLights array size to declare, clamped to 1..{@link #MAX_LIGHTS}
     */
    public String glsl(int maxLights) {
        int n = Math.max(1, Math.min(MAX_LIGHTS, maxLights));
        return Shaders.fragmentShader("""
                uniform float uDarkness;
                uniform float uAmbient;
                uniform vec3  uNightTint;
                uniform int   uLightCount;
                uniform vec3  uLightPos[%d];   // x, y (pixels), radius (pixels)
                uniform vec3  uLightColor[%d];""".formatted(n, n), """
                    vec4 c = texture(uTexture, vTexCoord);
                    // Screen pixels, top-left origin — the space the scene
                    // projected these lights into. Sampling uses vTexCoord and
                    // measuring uses flipY(vTexCoord); see Shaders.FLIP_Y_GLSL
                    // for the mirrored lighting that came of confusing them.
                    vec2 px = flipY(vTexCoord) * uResolution;
                    float glow = 0.0;
                    vec3 warm = vec3(0.0);
                    for (int i = 0; i < uLightCount; i++) {
                        float att = clamp(1.0 - distance(px, uLightPos[i].xy) / uLightPos[i].z, 0.0, 1.0);
                        att *= att;
                        glow += att;
                        warm += uLightColor[i] * att;
                    }
                    float visibility = clamp(uAmbient + glow, 0.0, 1.0);
                    float f = 1.0 - uDarkness * (1.0 - visibility);
                    vec3 tint = mix(uNightTint, vec3(1.0), f);
                    vec3 col = c.rgb * f * tint + warm * (0.18 * uDarkness);
                    fragColor = vec4(min(col, 1.0), c.a);
                """);
    }

    @Override
    public void apply(int[] src, int[] dst, int w, int h, ShaderContext ctx) {
        if (darkness <= 0.001f) { // daylight: identity
            System.arraycopy(src, 0, dst, 0, w * h);
            return;
        }

        // 1) Light field at 1/SCALE resolution.
        int lw = Math.max(1, w / SCALE), lh = Math.max(1, h / SCALE);
        ensureBuffers(lw * lh);
        computeLightField(lw, lh);

        // 2) Full-res composite, bilinear-sampling the field.
        final float dark = darkness;
        final float tr = tintR, tg = tintG, tb = tintB;
        final int lwF = lw, lhF = lh;
        ParallelRows.run(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                float fy = (y + 0.5f) / SCALE - 0.5f;
                int y0i = clampInt((int) Math.floor(fy), 0, lhF - 1);
                int y1i = Math.min(y0i + 1, lhF - 1);
                float wy = fy - (float) Math.floor(fy);
                if (fy < 0) { y0i = y1i = 0; wy = 0; }
                int row0 = y0i * lwF, row1 = y1i * lwF;
                int di = y * w;
                for (int x = 0; x < w; x++, di++) {
                    float fx = (x + 0.5f) / SCALE - 0.5f;
                    int x0i = clampInt((int) Math.floor(fx), 0, lwF - 1);
                    int x1i = Math.min(x0i + 1, lwF - 1);
                    float wx = fx - (float) Math.floor(fx);
                    if (fx < 0) { x0i = x1i = 0; wx = 0; }

                    float v = bilerp(vis, row0, row1, x0i, x1i, wx, wy);
                    float wr = bilerp(warmR, row0, row1, x0i, x1i, wx, wy);
                    float wg = bilerp(warmG, row0, row1, x0i, x1i, wx, wy);
                    float wb = bilerp(warmB, row0, row1, x0i, x1i, wx, wy);

                    float f = 1f - dark * (1f - v);
                    float tintRf = tr + (1f - tr) * f;
                    float tintGf = tg + (1f - tg) * f;
                    float tintBf = tb + (1f - tb) * f;

                    int c = src[di];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    int nr = (int) (r * f * tintRf + wr * (0.18f * dark) * 255f);
                    int ng = (int) (g * f * tintGf + wg * (0.18f * dark) * 255f);
                    int nb = (int) (b * f * tintBf + wb * (0.18f * dark) * 255f);
                    dst[di] = 0xFF000000
                            | (Math.min(255, nr) << 16)
                            | (Math.min(255, ng) << 8)
                            | Math.min(255, nb);
                }
            }
        });
    }

    private void computeLightField(int lw, int lh) {
        final float amb = ambient;
        ParallelRows.run(lh, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                float py = y * SCALE + SCALE * 0.5f;
                int idx = y * lw;
                for (int x = 0; x < lw; x++, idx++) {
                    float px = x * SCALE + SCALE * 0.5f;
                    float glow = 0, r = 0, g = 0, b = 0;
                    for (int i = 0; i < lightCount; i++) {
                        float dx = px - lightX[i], dy = py - lightY[i];
                        float att = 1f - (float) Math.sqrt(dx * dx + dy * dy) / lightR[i];
                        if (att <= 0) continue;
                        att *= att;
                        glow += att;
                        r += lightRed[i] * att;
                        g += lightGreen[i] * att;
                        b += lightBlue[i] * att;
                    }
                    float v = amb + glow;
                    vis[idx] = v > 1f ? 1f : v;
                    warmR[idx] = r;
                    warmG[idx] = g;
                    warmB[idx] = b;
                }
            }
        });
    }

    private void ensureBuffers(int size) {
        if (vis.length < size) {
            vis = new float[size];
            warmR = new float[size];
            warmG = new float[size];
            warmB = new float[size];
        }
    }

    private static float bilerp(float[] a, int row0, int row1, int x0, int x1,
                                float wx, float wy) {
        float top = a[row0 + x0] + (a[row0 + x1] - a[row0 + x0]) * wx;
        float bot = a[row1 + x0] + (a[row1 + x1] - a[row1 + x0]) * wx;
        return top + (bot - top) * wy;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
