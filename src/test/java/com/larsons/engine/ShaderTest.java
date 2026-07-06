package com.larsons.engine;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.shader.PixelShader;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the shader system (requirement #5): pixel-exact behavior
 * of built-in passes on the CPU pipeline, chain ordering/ping-ponging, the
 * GLSL contract every pass must honour, and the {@code .frag} export.
 */
class ShaderTest {

    private static int[] solidFrame(int w, int h, int argb) {
        int[] px = new int[w * h];
        Arrays.fill(px, argb);
        return px;
    }

    @Test
    void grayscaleAtFullStrengthProducesLuma() {
        // Pure red (255,0,0) -> Rec.709 luma 0.2126*255 ≈ 54.
        int[] px = solidFrame(8, 8, 0xFFFF0000);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.grayscale()));
        chain.apply(px, 8, 8);

        int c = px[0];
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        assertEquals(r, g);
        assertEquals(g, b);
        assertEquals(54, r, "Rec.709 luma of pure red should be ~54");
    }

    @Test
    void strengthZeroLeavesTheFrameUntouched() {
        int[] px = solidFrame(8, 8, 0xFF3366CC);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.grayscale(), Shaders.invert()));
        chain.setStrength(0f);
        chain.apply(px, 8, 8);
        for (int p : px) assertEquals(0xFF3366CC, p);
    }

    @Test
    void invertAtFullStrengthFlipsChannels() {
        int[] px = solidFrame(4, 4, 0xFF102030);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.invert()));
        chain.apply(px, 4, 4);
        assertEquals(0xFF000000 | ((255 - 0x10) << 16) | ((255 - 0x20) << 8) | (255 - 0x30), px[0]);
    }

    @Test
    void vignetteDarkensCornersMoreThanCenter() {
        int w = 64, h = 64;
        int[] px = solidFrame(w, h, 0xFFC8C8C8);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.vignette()));
        chain.apply(px, w, h);

        int center = px[(h / 2) * w + w / 2] & 0xFF;
        int cornerPx = px[0] & 0xFF;
        assertTrue(cornerPx < center, "corner " + cornerPx + " should be darker than center " + center);
        assertEquals(0xC8, center, "center should be (nearly) untouched");
    }

    @Test
    void scanlinesDarkenOnlyOddRows() {
        int w = 8, h = 8;
        int[] px = solidFrame(w, h, 0xFF808080);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.scanlines()));
        chain.apply(px, w, h);

        assertEquals(0xFF808080, px[0], "even rows untouched");
        int odd = px[w] & 0xFF; // row 1
        assertEquals(Math.round(0x80 * (1 - 0.35f)), odd, "odd rows dimmed by 35% at full strength");
    }

    @Test
    void pixelateQuantizesToBlocks() {
        int w = 8, h = 8;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) px[y * w + x] = 0xFF000000 | (x << 16) | (y << 8);
        }
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.pixelate(4)));
        chain.apply(px, w, h);

        // Every pixel of a 4x4 block equals the block's anchor sample (2,2).
        int expected = 0xFF000000 | (2 << 16) | (2 << 8);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(expected, px[y * w + x], "block (0,0) pixel at " + x + "," + y);
            }
        }
    }

    @Test
    void chainAppliesPassesInOrder() {
        // vignette/pixelate do not commute: pixelating after the vignette pulls
        // the (brighter) block-anchor sample into the corner, while vignetting
        // after pixelate dims the corner directly.
        int w = 64, h = 64;
        int[] a = solidFrame(w, h, 0xFFC8C8C8);
        int[] b = solidFrame(w, h, 0xFFC8C8C8);

        ShaderChain first = new ShaderChain();
        first.setPasses(List.of(Shaders.vignette(), Shaders.pixelate(4)));
        first.apply(a, w, h);

        ShaderChain second = new ShaderChain();
        second.setPasses(List.of(Shaders.pixelate(4), Shaders.vignette()));
        second.apply(b, w, h);

        assertNotEquals(a[0], b[0], "pass order must change the result");
        assertTrue((a[0] & 0xFF) > (b[0] & 0xFF),
                "vignette-then-pixelate corner should be brighter");
    }

    @Test
    void evenPassCountsCopyBackThroughThePingPongBuffers() {
        // Two passes end on the scratch buffer; the result must still land in
        // the caller's array. grayscale(red)=54 -> invert -> 201 everywhere.
        int[] px = solidFrame(4, 4, 0xFFFF0000);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.grayscale(), Shaders.invert()));
        chain.apply(px, 4, 4);
        assertEquals(0xFFC9C9C9, px[0]);
    }

    @Test
    void bloomBrightensNeighborhoodOfBrightSpots() {
        int w = 64, h = 64;
        int[] px = solidFrame(w, h, 0xFF101010);
        // A bright 8x8 block in the middle.
        for (int y = 28; y < 36; y++) {
            for (int x = 28; x < 36; x++) px[y * w + x] = 0xFFFFFFFF;
        }
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(Shaders.bloom()));
        chain.apply(px, w, h);

        // A dark pixel near the bright block should have gained light...
        assertTrue((px[32 * w + 24] & 0xFF) > 0x10, "pixel near bright block should glow");
        // ...while a far corner stays dark.
        assertEquals(0x10, px[0] & 0xFF, "far corner should be unaffected");
    }

    @Test
    void customPixelShaderRuns() {
        ShaderPass redOnly = new PixelShader("red_only",
                Shaders.fragmentShader("", "    fragColor = texture(uTexture, vTexCoord) * vec4(1.0, 0.0, 0.0, 1.0);\n")) {
            @Override
            protected int shade(int x, int y, int[] src, int w, int h, com.larsons.engine.graphics.shader.ShaderContext ctx) {
                return src[y * w + x] & 0xFFFF0000;
            }
        };
        int[] px = solidFrame(4, 4, 0xFF88AACC);
        ShaderChain chain = new ShaderChain();
        chain.setPasses(List.of(redOnly));
        chain.apply(px, 4, 4);
        assertEquals(0xFF880000, px[0] | 0xFF000000);
    }

    @Test
    void everyBuiltInHonoursTheGlslContract() {
        for (ShaderPass pass : Shaders.allBuiltIns()) {
            String glsl = pass.glsl();
            assertTrue(glsl.startsWith("#version 330 core"), pass.name() + ": version header");
            assertTrue(glsl.contains("uniform sampler2D uTexture"), pass.name() + ": uTexture");
            assertTrue(glsl.contains("uniform vec2  uResolution"), pass.name() + ": uResolution");
            assertTrue(glsl.contains("uniform float uTime"), pass.name() + ": uTime");
            assertTrue(glsl.contains("uniform float uStrength"), pass.name() + ": uStrength");
            assertTrue(glsl.contains("void main()"), pass.name() + ": main()");
            assertTrue(glsl.contains("fragColor"), pass.name() + ": writes fragColor");
            // Extra uniforms reported for the GPU backend must appear in the source.
            for (String uniform : pass.uniforms().keySet()) {
                assertTrue(glsl.contains(uniform), pass.name() + ": declares " + uniform);
            }
        }
    }

    @Test
    void glslExportWritesVertexAndFragmentFiles(@TempDir Path dir) throws Exception {
        List<Path> written = Shaders.writeGlsl(Shaders.allBuiltIns(), dir.resolve("out"));
        assertEquals(Shaders.allBuiltIns().size() + 1, written.size(), "one .frag per pass + fullscreen.vert");
        assertTrue(Files.exists(dir.resolve("out/fullscreen.vert")));
        assertTrue(Files.exists(dir.resolve("out/grayscale.frag")));
        String vert = Files.readString(dir.resolve("out/fullscreen.vert"));
        assertTrue(vert.contains("gl_VertexID"), "bufferless fullscreen triangle");
    }

    @Test
    void profileTogglesSelectPassesInFixedOrder() {
        GameProfile p = new GameProfile();
        p.shadersEnabled = true;
        p.shaderVignette = true;
        p.shaderPixelate = true;
        p.shaderGrayscale = true;
        List<ShaderPass> passes = GameContext.shaderPassesFor(p);
        assertEquals(3, passes.size());
        assertEquals("pixelate", passes.get(0).name());
        assertEquals("grayscale", passes.get(1).name());
        assertEquals("vignette", passes.get(2).name());
    }

    @Test
    void shaderSettingsRoundTripThroughJson() {
        GameProfile p = new GameProfile("FX Type");
        p.shadersEnabled = true;
        p.shaderStrength = 0.65;
        p.shaderBloom = true;
        p.shaderPixelSize = 6;
        GameProfile r = GameProfile.fromJson(p.toJson());
        assertTrue(r.shadersEnabled);
        assertEquals(0.65, r.shaderStrength, 1e-9);
        assertTrue(r.shaderBloom);
        assertEquals(6, r.shaderPixelSize);
    }
}
