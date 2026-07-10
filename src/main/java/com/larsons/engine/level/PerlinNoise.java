package com.larsons.engine.level;

import java.util.Random;

/**
 * Classic 2D Perlin gradient noise with a seeded permutation table, plus
 * fractal Brownian motion ({@link #fbm}) for the octave-layered look terrain
 * wants. Deterministic for a given seed, dependency-free, and cheap enough to
 * sample once per tile while generating.
 *
 * <p>Output of {@link #noise} is in roughly [-1, 1] (guaranteed inside
 * [-1.6, 1.6] worst case; practically ±1); {@code fbm} keeps the same range
 * by normalizing octave weights.
 */
public final class PerlinNoise {

    private final int[] perm = new int[512];

    public PerlinNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        Random rng = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    /** Single-octave gradient noise at (x, y). */
    public double noise(double x, double y) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double u = fade(xf);
        double v = fade(yf);

        int aa = perm[perm[xi] + yi];
        int ab = perm[perm[xi] + yi + 1];
        int ba = perm[perm[xi + 1] + yi];
        int bb = perm[perm[xi + 1] + yi + 1];

        double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
        double x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }

    /** Fractal noise: {@code octaves} layers, each {@code lacunarity}× finer at {@code gain}× weight. */
    public double fbm(double x, double y, int octaves, double gain, double lacunarity) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += noise(x * freq, y * freq) * amp;
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        // 8 gradient directions.
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> x - y;
            case 2 -> -x + y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }
}
