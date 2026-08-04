package com.larsons.engine.gl;

import com.larsons.engine.graphics.BackendChoice;
import com.larsons.engine.graphics.Backends;
import com.larsons.engine.graphics.RendererFactory;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderChain;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where a shader thinks the top of the screen is.
 *
 * <p><b>This asks the one question `GlShaderChainTest` is built not to ask.</b>
 * That test uploads its own source texture and reads the result back with no
 * flip in either direction, on the stated grounds that GL numbers rows from the
 * bottom and the two flips cancel. They do cancel — and the cancellation is
 * exactly what makes the test unable to see a vertical flip. Inside it the frame
 * is consistently upside down, so a pass that measures screen position from the
 * wrong end of the y axis measures it from the wrong end of an upside-down
 * frame, and comes out right.
 *
 * <p>The shipping path has no such symmetry. {@link GlTarget} renders the scene
 * the right way up, which in GL's own numbering puts the <em>top</em> of the
 * picture at {@code v = 1}; the frame is then blitted to the window with no
 * flip anywhere. So a pass reading {@code vTexCoord.y} as a screen row is
 * reading it upside down, and only in the shipping path.
 *
 * <p><b>What that looks like to a player.</b> A light is mirrored about the
 * middle of the screen. The camera keeps the player near that middle, so a
 * torch on the player's own level lands more or less where it should and
 * everything looks fine — and the further the player moves vertically away from
 * a light, the further the glow slides in the opposite direction. Reported as
 * "shaders don't line up unless you're on the same vertical level as a block,
 * which causes random glowing as you move vertically", which is this and nothing
 * else.
 *
 * <p>So this test drives the real renderer — {@code GlRenderer}, its offscreen
 * surface, the production chain — rather than a texture of its own, because the
 * orientation is a property of that path and of no smaller piece of it.
 */
class GlScreenSpaceTest {

    private static final int W = 320, H = 200;

    private static final RendererFactory.Request REQUEST =
            new RendererFactory.Request(W, H, "larsons-gl-screenspace", Color.BLACK);

    /**
     * A light near the top of the frame lights the top of the frame.
     *
     * <p>Deliberately the crudest possible statement of it. The light is put a
     * quarter of the way down a fully dark frame with no ambient, so the answer
     * is not a matter of degree: either the top eighth is brighter than the
     * bottom eighth or the y axis is upside down.
     */
    @Test
    void aLightNearTheTopOfTheFrameLightsTheTopOfTheFrame() {
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        try {
            int lightY = H / 4;
            int[] pixels = litFrame(renderer, W / 2, lightY);

            long top = meanLuma(pixels, renderer, 0, H / 8);
            long bottom = meanLuma(pixels, renderer, H - H / 8, H);

            assertTrue(top > bottom * 3, () ->
                    "a light at screen y=" + lightY + " of " + H + " left the top of the "
                            + "frame at mean luma " + top + " and the bottom at " + bottom
                            + " — the lighting pass is reading vTexCoord.y as a screen row, "
                            + "and GL numbers those from the bottom, so every light is "
                            + "mirrored about the middle of the screen");
        } finally {
            renderer.dispose();
            choice.backend().window().close();
        }
    }

    /**
     * And the glow tracks a light down the screen instead of away from it.
     *
     * <p>The test above would still pass if y were merely offset rather than
     * flipped. This walks a light from top to bottom and requires the brightest
     * row to walk with it, which pins the mapping rather than one point on it —
     * and it is the shape of the bug as it was actually reported, which is
     * about *movement* rather than about one static frame.
     */
    @Test
    void theGlowFollowsTheLightDownTheScreen() {
        BackendChoice choice = Backends.select(Backends.AUTO, Backends.discover(), REQUEST);
        assumeTrue(!choice.isJava2D(), "no GL driver here: " + choice.why());

        GlRenderer renderer = (GlRenderer) choice.backend().renderer();
        try {
            for (int lightY : new int[]{40, 100, 160}) {
                int[] pixels = litFrame(renderer, W / 2, lightY);
                int brightest = brightestRow(pixels, renderer);
                assertTrue(Math.abs(brightest - lightY) <= 12, () ->
                        "a light at screen y=" + lightY + " put the frame's brightest row at "
                                + brightest + "; the two should be the same row, and "
                                + (H - lightY) + " is what a vertical flip would give");
            }
        } finally {
            renderer.dispose();
            choice.backend().window().close();
        }
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * One frame: a flat white scene under a single point light in an otherwise
     * black night, shaded by the production chain and read back top row first.
     */
    private static int[] litFrame(GlRenderer renderer, int lightX, int lightY) {
        LightingPass lighting = new LightingPass();
        lighting.setDarkness(1.0);
        lighting.setAmbient(0.0);
        lighting.setNightTint(Color.BLACK);
        lighting.clearLights();
        lighting.addLight(lightX, lightY, H / 3.0, Color.WHITE);

        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(lighting));
        chain.setStrength(1.0f);
        renderer.setShaderChain(chain);

        DrawTarget target = renderer.beginFrame();
        target.fillRect(0, 0, W, H, Color.WHITE);
        renderer.present();

        GlShaderChain ran = renderer.shaderChain();
        assertTrue(ran != null, "the renderer never built a chain to run the pass with");
        int[] pixels = ran.readPixels();
        assertTrue(pixels.length > 0, "the chain produced no frame to look at");
        return pixels;
    }

    /** Mean luma over a band of logical rows, in a device-resolution readback. */
    private static long meanLuma(int[] pixels, GlRenderer renderer, int fromRow, int toRow) {
        int dw = renderer.deviceWidth(), dh = renderer.deviceHeight();
        double scale = dh / (double) H;
        int y0 = (int) (fromRow * scale), y1 = Math.min(dh, (int) (toRow * scale));
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < dw; x++) {
                sum += luma(pixels[y * dw + x]);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** The logical row with the most light in it. */
    private static int brightestRow(int[] pixels, GlRenderer renderer) {
        int dw = renderer.deviceWidth(), dh = renderer.deviceHeight();
        double scale = dh / (double) H;
        long best = -1;
        int bestRow = 0;
        for (int y = 0; y < dh; y++) {
            long sum = 0;
            for (int x = 0; x < dw; x++) sum += luma(pixels[y * dw + x]);
            if (sum > best) {
                best = sum;
                bestRow = y;
            }
        }
        return (int) Math.round(bestRow / scale);
    }

    private static int luma(int argb) {
        return (int) (0.2126 * ((argb >> 16) & 0xFF)
                + 0.7152 * ((argb >> 8) & 0xFF)
                + 0.0722 * (argb & 0xFF));
    }
}
