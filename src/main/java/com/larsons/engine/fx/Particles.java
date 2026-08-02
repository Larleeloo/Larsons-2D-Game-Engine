package com.larsons.engine.fx;

import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.sim.PerspectiveSpace;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * A tiny pooled particle system for gameplay feedback — block-break shards,
 * hit sparks, pickup sparkles — ported in spirit from the Side-Scroller
 * engine's status/impact particles and kept allocation-free (fixed pool, no
 * per-frame garbage). Purely visual and local: never simulated on the server
 * or replicated.
 *
 * <p>{@link Style} shapes a burst's motion so effects read differently:
 * embers float up, shards rain down, sparks snap outward, rings blast in a
 * circle, motes hang in the air — the auto-battler keys these off the
 * elemental damage types.
 *
 * <p><b>Skinnable.</b> Each style is a texture key of its own
 * ({@code particle/embers}, {@code particle/sparks}…), so a texture pack — or
 * the creative editor's Effects palette — can replace the flecks with real
 * art. The pre-generated fallback is the engine's own coloured fleck, tinted
 * by whatever colour the effect asked for, so an unskinned game looks exactly
 * as it always did.
 *
 * <p><b>Perspective.</b> A burst is authored the way it reads to a player:
 * some speed spreading away from the impact point, and some speed going
 * <em>up</em>. {@link #setSpace} says which axis "up" is
 * ({@link PerspectiveSpace}), and that is the whole difference between the
 * formats. In a side-scroller up is up the screen, exactly as it always was.
 * On a plane the spread becomes a circle on the <em>floor</em> — which the
 * isometric camera projects into a diamond for free — and the upward part
 * lifts the fleck along the elevation axis, so embers rise off the ground
 * toward the viewer instead of drifting north (top-down) or north-west
 * (isometric), which is what "up the screen" used to mean out here.
 */
public final class Particles {

    private static final int MAX = 512;
    private static final double GRAVITY = 900;
    /**
     * The elevation that counts as "one step nearer the camera" when top-down
     * grows a rising fleck. Flecks live in world pixels and never climb far,
     * so this is a tile rather than the camera's own tile size.
     */
    private static final double HEIGHT_UNIT = 32;

    /** Texture-key namespace of the particle sheets ({@code particle/<kind>}). */
    public static final String TEXTURE_NAMESPACE = "particle";

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

    /** The file-name stem a style's sheet takes in the pack ({@code embers}). */
    public static String textureKind(Style style) {
        return style.name().toLowerCase();
    }

    /** The {@link Skins} texture key of a style's sheet. */
    public static String textureKey(Style style) {
        return TEXTURE_NAMESPACE + "/" + textureKind(style);
    }

    /** A readable name for a style ("EMBERS" -> "Embers"). */
    public static String styleName(Style style) {
        String k = textureKind(style);
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    /** The {@link Style} behind a texture key, or {@code null}. */
    public static Style styleForKey(String key) {
        if (key == null) return null;
        String kind = key.startsWith(TEXTURE_NAMESPACE + "/")
                ? key.substring(TEXTURE_NAMESPACE.length() + 1) : key;
        for (Style s : Style.values()) {
            if (textureKind(s).equals(kind)) return s;
        }
        return null;
    }

    private final double[] x = new double[MAX];
    private final double[] y = new double[MAX];
    /** Height over the floor; always 0 in a side view, which has no such axis. */
    private final double[] z = new double[MAX];
    private final double[] vx = new double[MAX];
    private final double[] vy = new double[MAX];
    private final double[] vz = new double[MAX];
    private final double[] life = new double[MAX];
    private final double[] maxLife = new double[MAX];
    private final double[] grav = new double[MAX];
    private final int[] rgb = new int[MAX];
    private final float[] size = new float[MAX];
    /** {@link Style} ordinal, so each fleck can draw its own skinned texture. */
    private final int[] styleIdx = new int[MAX];
    /**
     * Whether this fleck was born on a plane, and so falls along the elevation
     * axis. Kept per fleck rather than read from {@link #space}: a burst keeps
     * the axes it launched with even if the scene switches perspective while
     * it is still in the air.
     */
    private final boolean[] elevated = new boolean[MAX];
    private int count;

    /**
     * Which axis "up" is for new bursts. Defaults to the side view, so the
     * flat scenes that own a {@link Particles} without a world behind it (the
     * auto-battler board, the deck game, the evolution dish) keep the motion
     * they have always had.
     */
    private PerspectiveSpace space = PerspectiveSpace.SIDE_VIEW;

    private final Random rng;

    /** A system whose bursts differ every run, which is what play wants. */
    public Particles() {
        this.rng = new Random();
    }

    /**
     * A system whose bursts are the same every run.
     *
     * <p>Not a tuning knob — the only caller is the golden-frame harness,
     * which cannot store a reference picture of something that scatters
     * differently each time it is drawn. Everything downstream of the seed is
     * already deterministic, so this is the whole of what stood between
     * particles and being pinned down.
     */
    public Particles(long seed) {
        this.rng = new Random(seed);
    }

    /**
     * Point new bursts at the space the scene is drawn in. Flecks already in
     * flight keep the axes they were born with — they live well under a
     * second, so a mid-air perspective switch settles immediately.
     */
    public void setSpace(PerspectiveSpace space) {
        if (space != null) this.space = space;
    }

    /** The space bursts are currently authored in. */
    public PerspectiveSpace space() {
        return space;
    }

    /** Burst of shards at a world position (block break, mob hit). */
    public void burst(double wx, double wy, Color color, int n) {
        burst(wx, wy, color, n, Style.BURST);
    }

    /** A styled burst; see {@link Style} for the motion each one gets. */
    public void burst(double wx, double wy, Color color, int n, Style style) {
        burst(wx, wy, 0, color, n, style);
    }

    /**
     * A styled burst {@code wz} above the floor — what a shot still in the air
     * trails behind it, or an impact up a wall. Ignored in a side view, whose
     * world plane already contains the vertical.
     */
    public void burst(double wx, double wy, double wz, Color color, int n, Style style) {
        boolean plane = space.hasElevation();
        for (int i = 0; i < n; i++) {
            if (count >= MAX) return;
            int p = count++;
            x[p] = wx;
            y[p] = wy;
            z[p] = plane ? Math.max(0, wz) : 0;
            vz[p] = 0;
            elevated[p] = plane;
            rgb[p] = color.getRGB() & 0xFFFFFF;
            styleIdx[p] = style.ordinal();
            double angle = rng.nextDouble() * Math.PI * 2;
            // Every style is authored twice over: the side-view velocities it
            // has always had, and the pair (spread away from the impact, rise
            // along the up axis) a plan-view level lays out on its own axes.
            double spread, rise;
            switch (style) {
                case EMBERS -> {
                    double speed = 20 + rng.nextDouble() * 50;
                    vx[p] = Math.cos(angle) * speed * 0.6;
                    vy[p] = -60 - rng.nextDouble() * 90;
                    spread = speed * 0.6;
                    rise = -vy[p];
                    grav[p] = -0.15; // embers keep floating up
                    maxLife[p] = life[p] = 0.55 + rng.nextDouble() * 0.5;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                case SHARDS -> {
                    double speed = 60 + rng.nextDouble() * 120;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.abs(Math.sin(angle)) * speed * 0.4 - 40;
                    // Splinters spray across the floor and are pulled back to
                    // it hard — the plan-view reading of "fast-falling".
                    spread = speed;
                    rise = 40;
                    grav[p] = 1.6;
                    maxLife[p] = life[p] = 0.3 + rng.nextDouble() * 0.3;
                    size[p] = 2f + rng.nextFloat() * 3f;
                }
                case SPARKS -> {
                    double speed = 220 + rng.nextDouble() * 240;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed;
                    spread = speed;
                    rise = speed * 0.35;
                    grav[p] = 0.15;
                    maxLife[p] = life[p] = 0.1 + rng.nextDouble() * 0.15;
                    size[p] = 1.5f + rng.nextFloat() * 1.5f;
                }
                case DRIP -> {
                    double speed = 30 + rng.nextDouble() * 70;
                    vx[p] = Math.cos(angle) * speed * 0.8;
                    vy[p] = 20 + rng.nextDouble() * 60;
                    // Nothing can drip "down the screen" on a floor: the same
                    // droplets arc off the impact and splat back onto it.
                    spread = speed * 0.8;
                    rise = vy[p];
                    grav[p] = 1.2;
                    maxLife[p] = life[p] = 0.4 + rng.nextDouble() * 0.4;
                    size[p] = 2.5f + rng.nextFloat() * 2.5f;
                }
                case RING -> {
                    double speed = 190 + rng.nextDouble() * 40;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed;
                    // A blast ring stays flat on the ground it blasted.
                    spread = speed;
                    rise = 0;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.22 + rng.nextDouble() * 0.1;
                    size[p] = 2.5f + rng.nextFloat() * 2f;
                }
                case MOTES -> {
                    double speed = 8 + rng.nextDouble() * 24;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed - 12;
                    spread = speed;
                    rise = 12;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.9 + rng.nextDouble() * 0.7;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                case FOUNTAIN -> {
                    vx[p] = (rng.nextDouble() * 2 - 1) * 60;
                    vy[p] = -180 - rng.nextDouble() * 160;
                    spread = rng.nextDouble() * 60;
                    rise = -vy[p];
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
                    // The ring is already a circle in world coordinates, so on
                    // a plane it collapses across the floor (a diamond, seen
                    // isometrically) instead of collapsing up the screen.
                    spread = -speed;
                    rise = 0;
                    grav[p] = 0;
                    maxLife[p] = life[p] = 0.25 + rng.nextDouble() * 0.15;
                    size[p] = 2f + rng.nextFloat() * 2f;
                }
                default -> {
                    double speed = 60 + rng.nextDouble() * 160;
                    vx[p] = Math.cos(angle) * speed;
                    vy[p] = Math.sin(angle) * speed - 120;
                    spread = speed;
                    rise = 120;
                    grav[p] = 1;
                    maxLife[p] = life[p] = 0.35 + rng.nextDouble() * 0.4;
                    size[p] = 2.5f + rng.nextFloat() * 2.5f;
                }
            }
            if (plane) {
                // The floor carries the whole spread; the vertical part of the
                // burst goes onto the elevation axis, where gravity now is.
                vx[p] = Math.cos(angle) * spread;
                vy[p] = Math.sin(angle) * spread;
                vz[p] = rise;
            }
        }
    }

    public void update(double dt) {
        for (int p = 0; p < count; ) {
            life[p] -= dt;
            if (life[p] <= 0) {
                // Swap-remove keeps the pool dense without shifting.
                count--;
                x[p] = x[count]; y[p] = y[count]; z[p] = z[count];
                vx[p] = vx[count]; vy[p] = vy[count]; vz[p] = vz[count];
                life[p] = life[count]; maxLife[p] = maxLife[count];
                grav[p] = grav[count];
                rgb[p] = rgb[count]; size[p] = size[count];
                styleIdx[p] = styleIdx[count];
                elevated[p] = elevated[count];
                continue;
            }
            if (elevated[p]) {
                // On a plane gravity pulls along the elevation axis, and the
                // floor catches what falls back rather than swallowing it.
                vz[p] -= GRAVITY * grav[p] * dt;
                z[p] += vz[p] * dt;
                if (z[p] < 0) {
                    z[p] = 0;
                    vz[p] = 0;
                }
            } else {
                vy[p] += GRAVITY * grav[p] * dt;
            }
            x[p] += vx[p] * dt;
            y[p] += vy[p] * dt;
            p++;
        }
    }

    /**
     * Draw every live fleck. A style with a texture assigned (pack folder or
     * creative Effects palette) draws that sheet, animated over the
     * particle's own lifetime and fading out with it; a style with none keeps
     * the built-in coloured fleck, which is the pre-generated fallback.
     *
     * <p>Height off the floor draws the way the perspective sees it: as a lift
     * up the screen in both plan views, and — only in top-down, where up
     * points at the viewer — as a fleck that grows a little as it rises
     * (see {@link PerspectiveSpace#heightScale}).
     */
    public void render(Graphics2D g, Camera camera) {
        Style[] styles = Style.values();
        Composite restore = null;
        double lift = space.screenLift();
        for (int p = 0; p < count; p++) {
            double fade = Math.max(0, Math.min(1, life[p] / maxLife[p]));
            int sx = camera.worldToScreenX(x[p], y[p]);
            int sy = camera.worldToScreenY(x[p], y[p])
                    - (int) Math.round(z[p] * lift * camera.zoom);
            int s = Math.max(1, (int) (size[p] * camera.zoom
                    * space.heightScale(z[p], HEIGHT_UNIT)));
            Style style = styles[styleIdx[p]];
            // Age drives the sheet, so a multi-frame particle texture plays
            // once across the fleck's life instead of flickering per frame.
            BufferedImage tex = Skins.frame(textureKey(style), maxLife[p] - life[p]);
            if (tex != null) {
                if (restore == null) restore = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, (float) fade));
                // Textured flecks read better a little larger than the bare
                // squares they replace, which are only a few pixels across.
                int w = Math.max(2, s * 2);
                g.drawImage(tex, sx - w / 2, sy - w / 2, w, w, null);
            } else {
                int alpha = (int) (255 * fade);
                g.setColor(new Color(rgb[p] | (Math.max(0, Math.min(255, alpha)) << 24), true));
                g.fillRect(sx - s / 2, sy - s / 2, s, s);
            }
        }
        if (restore != null) g.setComposite(restore);
    }

    public int count() {
        return count;
    }

    public void clear() {
        count = 0;
    }
}
