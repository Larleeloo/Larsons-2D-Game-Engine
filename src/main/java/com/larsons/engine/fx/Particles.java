package com.larsons.engine.fx;

import com.larsons.engine.graphics.Camera;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Random;

/**
 * A tiny pooled particle system for gameplay feedback — block-break shards,
 * hit sparks, pickup sparkles — ported in spirit from the Side-Scroller
 * engine's status/impact particles but kept asset-free (coloured squares) and
 * allocation-free (fixed pool, no per-frame garbage). Purely visual and
 * local: never simulated on the server or replicated.
 *
 * <p>{@link Style} shapes a burst's motion so effects read differently:
 * embers float up, shards rain down, sparks snap outward, rings blast in a
 * circle, motes hang in the air — the auto-battler keys these off the
 * elemental damage types.
 */
public final class Particles {

    private static final int MAX = 512;
    private static final double GRAVITY = 900;

    /** Motion profile for a burst. {@link #BURST} is the classic shard spray. */
    public enum Style {
        /** Omnidirectional shards with gravity (the original behaviour). */
        BURST,
        /** Slow upward-drifting flecks that keep rising (fire). */
        EMBERS,
        /** Fast-falling splinters (cryo). */
        SHARDS,
        /** Very fast, very short-lived crackles (electric). */
        SPARKS,
        /** Heavy droplets that arc down (corrosive). */
        DRIP,
        /** A uniform outward blast ring (explosive). */
        RING,
        /** Weightless motes that linger (radiation). */
        MOTES,
        /** An upward geyser that falls back down (harvest, revive). */
        FOUNTAIN,
        /** Spawn on a ring and rush inward — collapsing space (warp, blink). */
        IMPLODE
    }

    private final double[] x = new double[MAX];
    private final double[] y = new double[MAX];
    private final double[] vx = new double[MAX];
    private final double[] vy = new double[MAX];
    private final double[] life = new double[MAX];
    private final double[] maxLife = new double[MAX];
    private final double[] grav = new double[MAX];
    private final int[] rgb = new int[MAX];
    private final float[] size = new float[MAX];
    private int count;

    private final Random rng = new Random();

    /** Burst of shards at a world position (block break, mob hit). */
    public void burst(double wx, double wy, Color color, int n) {
        burst(wx, wy, color, n, Style.BURST);
    }

    /** A styled burst; see {@link Style} for the motion each one gets. */
    public void burst(double wx, double wy, Color color, int n, Style style) {
        for (int i = 0; i < n; i++) {
            if (count >= MAX) return;
            int p = count++;
            x[p] = wx;
            y[p] = wy;
            rgb[p] = color.getRGB() & 0xFFFFFF;
            double angle = rng.nextDouble() * Math.PI * 2;
            switch (style) {
                case EMBERS -> {
                    double speed = 20 + rng.nextDouble() * 50;
                    vx[p] = Math.cos(angle) * speed * 0.6;
                    vy[p] = -60 - rng.nextDouble() * 90;
                    grav[p] = -0.15; // embers keep floating up
                    maxLife[p] = life[p] = 0.55 + rng.nextDouble() * 0.5;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                case SHARDS -> {
                    double speed = 60 + rng.nextDouble() * 120;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.abs(Math.sin(angle)) * speed * 0.4 - 40;
                    grav[p] = 1.6;
                    maxLife[p] = life[p] = 0.3 + rng.nextDouble() * 0.3;
                    size[p] = 2f + rng.nextFloat() * 3f;
                }
                case SPARKS -> {
                    double speed = 220 + rng.nextDouble() * 240;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed;
                    grav[p] = 0.15;
                    maxLife[p] = life[p] = 0.1 + rng.nextDouble() * 0.15;
                    size[p] = 1.5f + rng.nextFloat() * 1.5f;
                }
                case DRIP -> {
                    double speed = 30 + rng.nextDouble() * 70;
                    vx[p] = Math.cos(angle) * speed * 0.8;
                    vy[p] = 20 + rng.nextDouble() * 60;
                    grav[p] = 1.2;
                    maxLife[p] = life[p] = 0.4 + rng.nextDouble() * 0.4;
                    size[p] = 2.5f + rng.nextFloat() * 2.5f;
                }
                case RING -> {
                    double speed = 190 + rng.nextDouble() * 40;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.22 + rng.nextDouble() * 0.1;
                    size[p] = 2.5f + rng.nextFloat() * 2f;
                }
                case MOTES -> {
                    double speed = 8 + rng.nextDouble() * 24;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed - 12;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.9 + rng.nextDouble() * 0.7;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                case FOUNTAIN -> {
                    vx[p] = (rng.nextDouble() * 2 - 1) * 60;
                    vy[p] = -180 - rng.nextDouble() * 160;
                    grav[p] = 1.1;
                    maxLife[p] = life[p] = 0.5 + rng.nextDouble() * 0.4;
                    size[p] = 2.5f + rng.nextFloat() * 2.5f;
                }
                case IMPLODE -> {
                    double ring = 22 + rng.nextDouble() * 16;
                    x[p] = wx + Math.cos(angle) * ring;
                    y[p] = wy + Math.sin(angle) * ring;
                    double speed = 90 + rng.nextDouble() * 60;
                    vx[p] = -Math.cos(angle) * speed;
                    vy[p] = -Math.sin(angle) * speed;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.25 + rng.nextDouble() * 0.15;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                default -> {
                    double speed = 60 + rng.nextDouble() * 160;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed - 120;
                    grav[p] = 1;
                    maxLife[p] = life[p] = 0.35 + rng.nextDouble() * 0.4;
                    size[p] = 2.5f + rng.nextFloat() * 2.5f;
                }
            }
        }
    }

    public void update(double dt) {
        for (int p = 0; p < count; ) {
            life[p] -= dt;
            if (life[p] <= 0) {
                // Swap-remove keeps the pool dense without shifting.
                count--;
                x[p] = x[count]; y[p] = y[count];
                vx[p] = vx[count]; vy[p] = vy[count];
                life[p] = life[count]; maxLife[p] = maxLife[count];
                grav[p] = grav[count];
                rgb[p] = rgb[count]; size[p] = size[count];
                continue;
            }
            vy[p] += GRAVITY * grav[p] * dt;
            x[p] += vx[p] * dt;
            y[p] += vy[p] * dt;
            p++;
        }
    }

    public void render(Graphics2D g, Camera camera) {
        for (int p = 0; p < count; p++) {
            int alpha = (int) (255 * (life[p] / maxLife[p]));
            g.setColor(new Color(rgb[p] | (Math.max(0, Math.min(255, alpha)) << 24), true));
            int sx = camera.worldToScreenX(x[p], y[p]);
            int sy = camera.worldToScreenY(x[p], y[p]);
            int s = Math.max(1, (int) (size[p] * camera.zoom));
            g.fillRect(sx - s / 2, sy - s / 2, s, s);
        }
    }

    public int count() {
        return count;
    }

    public void clear() {
        count = 0;
    }
}
