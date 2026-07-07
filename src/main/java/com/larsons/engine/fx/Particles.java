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
 */
public final class Particles {

    private static final int MAX = 512;
    private static final double GRAVITY = 900;

    private final double[] x = new double[MAX];
    private final double[] y = new double[MAX];
    private final double[] vx = new double[MAX];
    private final double[] vy = new double[MAX];
    private final double[] life = new double[MAX];
    private final double[] maxLife = new double[MAX];
    private final int[] rgb = new int[MAX];
    private final float[] size = new float[MAX];
    private int count;

    private final Random rng = new Random();

    /** Burst of shards at a world position (block break, mob hit). */
    public void burst(double wx, double wy, Color color, int n) {
        for (int i = 0; i < n; i++) {
            if (count >= MAX) return;
            int p = count++;
            double angle = rng.nextDouble() * Math.PI * 2;
            double speed = 60 + rng.nextDouble() * 160;
            x[p] = wx;
            y[p] = wy;
            vx[p] = Math.cos(angle) * speed;
            vy[p] = Math.sin(angle) * speed - 120;
            maxLife[p] = life[p] = 0.35 + rng.nextDouble() * 0.4;
            rgb[p] = color.getRGB() & 0xFFFFFF;
            size[p] = 2.5f + rng.nextFloat() * 2.5f;
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
                rgb[p] = rgb[count]; size[p] = size[count];
                continue;
            }
            vy[p] += GRAVITY * dt;
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
