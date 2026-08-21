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

    /**
     * Single-octave gradient noise at (x, y, z) — the same construction one
     * dimension up, sharing this instance's permutation table.
     *
     * <p>Caves are the reason this exists. A tunnel is a three-dimensional
     * thing: sampling a 2D field against depth gives vertical curtains rather
     * than passages, and a world whose caves are all the same shape at every
     * depth is a world with one cave in it. Everything that only needs a
     * heightmap goes on using {@link #noise(double, double)}, which is half the
     * work.
     */
    public double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double zf = z - Math.floor(z);
        double u = fade(xf), v = fade(yf), w = fade(zf);

        int a = perm[xi] + yi, aa = perm[a] + zi, ab = perm[a + 1] + zi;
        int b = perm[xi + 1] + yi, ba = perm[b] + zi, bb = perm[b + 1] + zi;

        double x1 = lerp(grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf), u);
        double x2 = lerp(grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf), u);
        double y1 = lerp(x1, x2, v);

        double x3 = lerp(grad(perm[aa + 1], xf, yf, zf - 1),
                grad(perm[ba + 1], xf - 1, yf, zf - 1), u);
        double x4 = lerp(grad(perm[ab + 1], xf, yf - 1, zf - 1),
                grad(perm[bb + 1], xf - 1, yf - 1, zf - 1), u);
        double y2 = lerp(x3, x4, v);

        return lerp(y1, y2, w);
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

    /** Fractal 3D noise, the {@link #fbm(double, double, int, double, double)} of {@link #noise(double, double, double)}. */
    public double fbm(double x, double y, double z, int octaves, double gain, double lacunarity) {
        double sum = 0, amp = 1, freq = 1, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += noise(x * freq, y * freq, z * freq) * amp;
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
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

    /** The 12 edge gradients of Perlin's improved 3D noise. */
    private static double grad(int hash, double x, double y, double z) {
        return switch (hash & 15) {
            case 0, 12 -> x + y;
            case 1, 14 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x + z;
            case 5 -> -x + z;
            case 6 -> x - z;
            case 7 -> -x - z;
            case 8 -> y + z;
            case 9, 13 -> -y + z;
            case 10 -> y - z;
            default -> -y - z;
        };
    }
}
