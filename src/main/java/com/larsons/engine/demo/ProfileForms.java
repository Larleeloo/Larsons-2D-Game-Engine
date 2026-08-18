package com.larsons.engine.demo;

import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.CharacterStore;
import com.larsons.engine.character.Characters;
import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.CameraLock;
import com.larsons.engine.graphics.Viewpoint;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.profile.DeviceProfile;
import com.larsons.engine.profile.DisplayCap;
import com.larsons.engine.ui.ConfigForm;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds the feature toggles for a level's own {@link GameProfile} — the two
 * places a level's settings are asked about, and the same rows in both:
 * <em>New Level</em> (before the level is created, see {@link NewLevelScene})
 * and <em>Load Level → Edit Settings</em> (afterwards). Each control edits the
 * profile in place via lambdas.
 *
 * <p>The launch-time game-type editor used to show this same list as a
 * template for levels created later. It no longer does: see
 * {@link GameProfile#resetFeaturesToDefaults()} for why one question asked in
 * two places, only one of which is visible where it takes effect, is worse
 * than the flexibility it bought.
 */
final class ProfileForms {

    private ProfileForms() {}

    /**
     * A level's character roster: which profiles a player may choose from when
     * the level starts. Nothing ticked means every profile is offered, which is
     * what levels built before character profiles existed do.
     *
     * <p>Two screens ask this — <em>New Level</em> before the level exists and
     * <em>Load Level → Edit Settings</em> afterwards — so the rows live beside
     * the feature toggles they are shown with rather than in one of the two.
     *
     * @param gameType the game type whose character profiles are on offer
     * @param roster   the level's roster, edited in place
     */
    static void addRosterOptions(ConfigForm form, String gameType, List<String> roster) {
        // The game type's profiles must be registered before they can be listed.
        new CharacterStore(gameType).loadAndRegister();
        for (CharacterProfile c : Characters.all()) {
            form.addToggle("Character: " + c.name,
                    () -> roster.contains(c.key),
                    v -> {
                        if (v) {
                            if (!roster.contains(c.key)) roster.add(c.key);
                        } else {
                            roster.remove(c.key);
                        }
                    });
        }
        form.addAction("Offer every character (clear the roster)", roster::clear);
    }

    /**
     * Where this level lets its camera stand — the {@link CameraLock} rows.
     *
     * <p>Its own method rather than more lines in the feature list because it
     * is its own question: everything in {@code addFeatureOptions} is "does
     * this level have X", and this is "how is this level meant to be looked
     * at". A creator composing a side-on puzzle or a fixed-angle room comes
     * here once and does not touch it again.
     *
     * <p>Every row is a permission and every default is "allowed", so a level
     * that never opens this section behaves exactly as it did before locks
     * existed.
     */
    static void addCameraOptions(ConfigForm form, GameProfile p) {
        CameraLock lock = p.cameraLock;
        form.addNote("Camera lock — where this level may be looked at from. "
                + "Everything is allowed until you say otherwise.");

        // The eight compass points, one row each. A list rather than a range
        // because the useful locks are not intervals: "north and south only"
        // is a corridor seen from either end, and no min/max can say it.
        for (int h = 0; h < CameraLock.HEADINGS; h++) {
            int heading = h;
            form.addToggle("Facing " + CameraLock.HEADING_LABELS[h],
                    () -> lock.allowsHeading(heading),
                    v -> lock.setHeadingAllowed(heading, v));
        }
        form.addNote("Untick a heading and the rotate keys step over it. "
                + "One heading left is a fixed camera; the last one cannot be "
                + "unticked, because a camera has to point somewhere.");

        // The tilt: a range, plus a step that turns the range into a list of
        // exact angles.
        form.addDouble("Lowest camera tilt (°)", lock::minPitchDegrees,
                lock::setMinPitchDegrees, 0, 90, 5);
        form.addDouble("Highest camera tilt (°)", lock::maxPitchDegrees,
                lock::setMaxPitchDegrees, 0, 90, 5);
        form.addDouble("Tilt snaps to steps of (°)", lock::pitchStepDegrees,
                lock::setPitchStepDegrees, 0, 45, 5);
        form.addNote("0° is flat on the floor — the level cut open along the "
                + "line you are looking down. 90° is straight overhead. Equal "
                + "low and high values pin the camera to one angle; a step of 0 "
                + "leaves the tilt free between them.");

        // The F5 cycle. The plan view is not on the list because it cannot be
        // turned off — it is the view every level is authored through and the
        // one the others fall back to.
        for (Viewpoint v : Viewpoint.values()) {
            if (v == Viewpoint.PLAN) continue;
            form.addToggle("View: " + v.label(), () -> lock.allows(v),
                    v2 -> lock.setAllowed(v, v2));
        }
        form.addNote("The plan view is always available. Turn the others off "
                + "and [F5] stays where it is.");

        // Reset rather than replace: the rows above hold a reference to this
        // lock, and handing the profile a different object would leave every
        // one of them editing a lock nothing reads.
        form.addAction("Unlock the camera (allow everything)", lock::reset);
    }

    static void addFeatureOptions(ConfigForm form, GameProfile p) {
        // No level-format row here. The level's format is its own property and
        // the settings screen already asks for it directly, above this list —
        // a second "default format" control beside it could only disagree with
        // the first, and saving the level overwrites it anyway
        // (Level.captureSettings pins the saved perspective to the real one).
        form.addToggle("Zoom enabled", () -> p.zoomEnabled, v -> p.zoomEnabled = v);
        form.addDouble("Min zoom", () -> p.minZoom, v -> p.minZoom = v, 0.1, 4.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);
        form.addDouble("Max zoom", () -> p.maxZoom, v -> p.maxZoom = v, 0.1, 8.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);
        form.addDouble("Default zoom", () -> p.defaultZoom, v -> p.defaultZoom = v, 0.1, 8.0, 0.1)
                .enabledWhen(() -> p.zoomEnabled);

        // The level sets the range it allows; the machine running it picks
        // inside that range from its own display refresh rate (see DisplayCap).
        // Nobody has to guess what hardware a level will be opened on.
        form.addInt("Lowest allowed FPS", () -> p.minFps, v -> p.minFps = v, 10, 240, 5);
        form.addInt("Highest allowed FPS", () -> p.maxFps, v -> p.maxFps = v, 10, 240, 5);
        form.addNote(DisplayCap.describeRule(DeviceProfile.detect()));

        form.addToggle("Gravity / jumping", () -> p.gravityEnabled, v -> p.gravityEnabled = v);

        // The height axis. Off by default and off in every level saved before
        // it existed, because turning it on is a change to how those levels
        // play: a hop clears a block, so every wall becomes something to climb
        // and every maze becomes traversable over its own walls
        // (HEIGHT_PLAN.md W0). The note says so rather than leaving a creator
        // to discover it by flipping the switch on a finished level.
        form.addToggle("Standing on blocks (height)",
                () -> p.verticality, v -> p.verticality = v);
        form.addNote("Off: stacks are walls. On: climb them, stand on top, "
                + "walk off and fall — and every wall in this level becomes "
                + "something a jump can clear.");
        form.addToggle("Falling hurts", () -> p.fallDamageEnabled,
                        v -> p.fallDamageEnabled = v)
                .enabledWhen(() -> p.verticality);
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

        // The game type's *default* player size stays slightly under one block
        // so it fits one-tile gaps, and sizing the tiles sizes it with them. A
        // character profile overrides both of its sizes independently — see
        // com.larsons.engine.sim.ActorSize — so this is the fallback for a
        // body that never chose, not a ceiling on anybody.
        form.addInt("Tile size (default player size)", () -> p.tileSize,
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
