package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.ui.ConfigForm;

import java.nio.file.Path;

/**
 * Builds the shared set of feature toggles for a {@link GameProfile}, so the
 * launch-time editor and the in-game pause menu present exactly the same
 * options. Each control edits the profile in place via lambdas.
 */
final class ProfileForms {

    private ProfileForms() {}

    static void addFeatureOptions(ConfigForm form, GameProfile p) {
        // A level's own format decides how it is built and played; this is the
        // format new levels start in (see LevelFormat and the creative-mode
        // picker), which is why it reads as a default rather than a switch.
        form.addEnum("Default level format", LevelFormat.values(),
                () -> LevelFormat.of(p.perspective),
                v -> p.perspective = v.perspective());
        form.addToggle("Switch perspective in-game",
                () -> p.perspectiveSwitchingEnabled, v -> p.perspectiveSwitchingEnabled = v);

        form.addToggle("Zoom enabled", () -> p.zoomEnabled, v -> p.zoomEnabled = v);
        form.addDouble("Min zoom", () -> p.minZoom, v -> p.minZoom = v, 0.1, 4.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);
        form.addDouble("Max zoom", () -> p.maxZoom, v -> p.maxZoom = v, 0.1, 8.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);
        form.addDouble("Default zoom", () -> p.defaultZoom, v -> p.defaultZoom = v, 0.1, 8.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);

        form.addInt("Min framerate", () -> p.minFps, v -> p.minFps = v, 10, 240, 5);
        form.addInt("Max framerate", () -> p.maxFps, v -> p.maxFps = v, 10, 240, 5);

        form.addToggle("Gravity / jumping", () -> p.gravityEnabled, v -> p.gravityEnabled = v);
        form.addToggle("Show HUD", () -> p.hudVisible, v -> p.hudVisible = v);
        form.addToggle("Show grid", () -> p.gridVisible, v -> p.gridVisible = v);

        // World features merged in from the Side-Scroller engine.
        form.addToggle("Mobs (AI creatures)", () -> p.mobsEnabled, v -> p.mobsEnabled = v);
        form.addToggle("Items & inventory", () -> p.itemsEnabled, v -> p.itemsEnabled = v);
        form.addToggle("Combat", () -> p.combatEnabled, v -> p.combatEnabled = v)
                .enabledWhen(() -> p.mobsEnabled);
        form.addToggle("Projectiles & ranged weapons", () -> p.projectilesEnabled, v -> p.projectilesEnabled = v)
                .enabledWhen(() -> p.itemsEnabled);
        form.addToggle("Mine / place blocks in play", () -> p.blockEditingEnabled, v -> p.blockEditingEnabled = v);
        form.addToggle("Creative mode (paint objects)", () -> p.creativeEnabled, v -> p.creativeEnabled = v);

        // Lighting rides the shader chain but has its own toggle (gameplay,
        // not post-FX), so it works with or without the effects below.
        form.addToggle("Lighting", () -> p.lightingEnabled, v -> p.lightingEnabled = v);
        form.addToggle("· Day/night cycle", () -> p.dayNightCycle, v -> p.dayNightCycle = v)
                .enabledWhen(() -> p.lightingEnabled);
        form.addToggle("· Night (fixed)", () -> p.nightMode, v -> p.nightMode = v)
                .enabledWhen(() -> p.lightingEnabled && !p.dayNightCycle);
        form.addDouble("· Night darkness", () -> p.nightDarkness, v -> p.nightDarkness = v, 0.0, 1.0, 0.05)
                .enabledWhen(() -> p.lightingEnabled);
        form.addDouble("· Ambient light", () -> p.ambientLight, v -> p.ambientLight = v, 0.0, 1.0, 0.05)
                .enabledWhen(() -> p.lightingEnabled);

        form.addToggle("Parallax background", () -> p.parallaxEnabled, v -> p.parallaxEnabled = v);
        form.addToggle("Particles", () -> p.particlesEnabled, v -> p.particlesEnabled = v);
        form.addToggle("Sound effects", () -> p.audioEnabled, v -> p.audioEnabled = v);

        // The player hitbox stays slightly smaller than one block so it fits
        // one-tile gaps; sizing the tiles sizes the player with them.
        form.addInt("Tile size (player just under 1 block)", () -> p.tileSize,
                v -> { p.tileSize = v; p.playerSize = GameProfile.playerSizeFor(v); }, 8, 256, 4);
        form.addInt("Default entity size", () -> p.defaultEntitySize, v -> p.defaultEntitySize = v, 8, 256, 4);

        form.addToggle("Shaders (post-FX)", () -> p.shadersEnabled, v -> p.shadersEnabled = v);
        form.addDouble("Shader strength", () -> p.shaderStrength, v -> p.shaderStrength = v, 0.0, 1.0, 0.05)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Pixelate", () -> p.shaderPixelate, v -> p.shaderPixelate = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addInt("· Pixel size", () -> p.shaderPixelSize, v -> p.shaderPixelSize = v, 1, 64, 1)
                .enabledWhen(() -> p.shadersEnabled && p.shaderPixelate);
        form.addToggle("· Wave distortion", () -> p.shaderWave, v -> p.shaderWave = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Chromatic aberration", () -> p.shaderChromatic, v -> p.shaderChromatic = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Bloom", () -> p.shaderBloom, v -> p.shaderBloom = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Grayscale", () -> p.shaderGrayscale, v -> p.shaderGrayscale = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Scanlines (CRT)", () -> p.shaderScanlines, v -> p.shaderScanlines = v)
                .enabledWhen(() -> p.shadersEnabled);
        form.addToggle("· Vignette", () -> p.shaderVignette, v -> p.shaderVignette = v)
                .enabledWhen(() -> p.shadersEnabled);
        // The GPU bridge: dump this profile's passes (or the whole library if
        // none are toggled on) as ready-to-compile GLSL files.
        form.addAction("Export shaders as GLSL (shaders/)", () -> {
            var passes = GameContext.shaderPassesFor(p);
            Shaders.writeGlsl(passes.isEmpty() ? Shaders.allBuiltIns() : passes, Path.of("shaders"));
        });
    }
}
