package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.EntitySprites;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two features that share a theme — everything you can see should be
 * reskinnable, and every perspective should play the same:
 *
 * <ul>
 *   <li><b>Effect textures</b> — particle styles and projectiles are texture
 *       keys of their own, listed in the pack's key file and editable from the
 *       creative Effects palette, with the built-in art as the fallback.</li>
 *   <li><b>Jumping everywhere</b> — Space jumps in a side-scroller and hops
 *       along a separate Z axis in top-down and isometric levels, with the
 *       same double jump and the same stamina cost.</li>
 * </ul>
 */
@Timeout(60)
class EffectSkinsAndJumpTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void withoutATexturePack(@TempDir Path tmp) {
        TexturePack.useDir(tmp.resolve("no-texture-pack").toString());
    }

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        TexturePack.useDir("");
        AssetLoader.clearCache();
    }

    private static GameProfile profileFor(Perspective p) {
        GameProfile profile = new GameProfile("fx");
        profile.perspective = p;
        profile.gravityEnabled = p == Perspective.SIDE_SCROLL;
        profile.normalize();
        return profile;
    }

    // --- particle + projectile textures -------------------------------------------

    @Test
    void everyParticleStyleIsATextureKeyWithBuiltInArt() {
        for (Particles.Style style : Particles.Style.values()) {
            String key = Particles.textureKey(style);
            assertEquals(style, Particles.styleForKey(key), key + " round-trips");
            assertEquals("particles/" + Particles.textureKind(style) + ".png",
                    TexturePack.fileNameFor(key),
                    "a creator is told exactly which file to drop in");
            // Unskinned, the style keeps its pre-generated fleck.
            assertNull(Skins.frame(key, 0), "no sheet assigned -> the built-in art draws");
            BufferedImage builtIn = EntitySprites.particle(style, 32, Color.ORANGE);
            assertNotNull(builtIn, style + " has pre-generated art");
            assertEquals(32, builtIn.getWidth());
        }
    }

    @Test
    void everyProjectileIsATextureKeyWithBuiltInArt() {
        List<ProjectileDef> all = ProjectileRegistry.standard().all();
        assertFalse(all.isEmpty());
        for (ProjectileDef d : all) {
            String key = "projectile/" + d.key();
            assertEquals("projectiles/" + d.key() + ".png", TexturePack.fileNameFor(key));
            assertNull(Skins.frame(key, 0));
            assertNotNull(EntitySprites.projectile(d, 16), d.key() + " has built-in art");
        }
    }

    @Test
    void theKeyListNamesEveryEffectACreatorCanReskin() {
        List<TextureKeys.Entry> all = TextureKeys.all();
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("particle/")),
                "particles are in the catalogue");
        assertTrue(all.stream().anyMatch(e -> e.key().equals("projectile/arrow")),
                "world projectiles are in the catalogue");
        assertTrue(all.stream().anyMatch(e -> e.key().startsWith("character/")),
                "character profiles are in the catalogue");
        // Every advertised entry must resolve to the file the list names.
        for (TextureKeys.Entry e : all) {
            assertTrue(TextureKeys.paths(e.key()).contains(e.folder() + "/" + e.file()),
                    e.key() + " resolves to " + e.folder() + "/" + e.file());
        }
        assertTrue(TexturePack.folders().contains(TextureKeys.PARTICLES),
                "a scaffolded pack has a particles folder to fill");
    }

    @Test
    void anAssignedParticleSheetRendersInsteadOfTheBuiltInFleck(@TempDir Path dir)
            throws IOException {
        BufferedImage sheet = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = sheet.createGraphics();
        sg.setColor(Color.MAGENTA);
        sg.fillRect(0, 0, 8, 8);
        sg.dispose();
        Path file = dir.resolve("embers.png");
        ImageIO.write(sheet, "png", file.toFile());
        Skins.put(new SkinDef(Particles.textureKey(Particles.Style.EMBERS),
                file.toString(), 8, 8, 1, 0));

        assertNotNull(Skins.frame(Particles.textureKey(Particles.Style.EMBERS), 0),
                "the assigned sheet is what renders now");

        // The particle system draws it: a burst of embers over a blank canvas
        // paints magenta, where an unskinned burst would paint its own colour.
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Camera camera = new Camera(Perspective.TOP_DOWN, 64, 64);
        camera.centerOn(32, 32);
        Particles particles = new Particles();
        particles.burst(32, 32, Color.GREEN, 40, Particles.Style.EMBERS);
        particles.render(g, camera);
        g.dispose();
        assertTrue(hasColor(canvas, Color.MAGENTA),
                "the skinned particle texture is what got drawn");
        assertFalse(hasColor(canvas, Color.GREEN),
                "…rather than the untextured coloured fleck");
    }

    @Test
    void anUnskinnedBurstStillDrawsItsColouredFleck() {
        BufferedImage canvas = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        Camera camera = new Camera(Perspective.TOP_DOWN, 64, 64);
        camera.centerOn(32, 32);
        Particles particles = new Particles();
        particles.burst(32, 32, Color.GREEN, 60, Particles.Style.MOTES);
        assertTrue(particles.count() > 0);
        particles.render(g, camera);
        g.dispose();
        assertTrue(hasColor(canvas, Color.GREEN), "the pre-generated fallback still draws");
    }

    // --- jumping in every perspective ------------------------------------------------

    @Test
    void spaceJumpsFromTheGroundInASideScroller() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Jump", 40, 20, 32);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);
        PlayerState s = new PlayerState(0, "", 100, (lvl.height - 3) * 32.0);
        settle(s, lvl, profile, Perspective.SIDE_SCROLL);

        // Space alone (no up key) has to work: it is the jump key everywhere else.
        PlayerInput space = new PlayerInput(false, false, false, false, 1);
        space.jump = true;
        PlayerPhysics.step(s, space, lvl, profile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertTrue(s.vy < 0, "space leaves the ground, vy was " + s.vy);
    }

    @Test
    void spaceHopsInTopDownAndIsometricLevels() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = format.starterLevel("Hop", 40, 30, 32);
            GameProfile profile = profileFor(lvl.perspective);
            PlayerState s = new PlayerState(0, "", 300, 300);

            assertEquals(0, s.z, 0.0001, "grounded before the jump in " + format);
            PlayerInput jump = new PlayerInput(false, false, false, false, 1);
            jump.jump = true;
            PlayerPhysics.step(s, jump, lvl, profile, lvl.perspective, 1 / 60.0);
            assertTrue(s.vz > 0, "space launches a hop in " + format);
            assertTrue(s.z > 0, "…which lifts the character off the ground in " + format);
            assertTrue(s.stamina < s.maxStamina, "…and costs stamina, like any jump");

            // The hop peaks and lands back on the ground on its own.
            double peak = 0;
            for (int i = 0; i < 240 && (s.z > 0 || s.vz > 0); i++) {
                PlayerPhysics.step(s, new PlayerInput(false, false, false, false, i + 2),
                        lvl, profile, lvl.perspective, 1 / 60.0);
                peak = Math.max(peak, s.z);
            }
            assertTrue(peak > 20, "the hop gets some real height in " + format
                    + ", peaked at " + peak);
            assertEquals(0, s.z, 0.0001, "…and comes back down in " + format);
        }
    }

    @Test
    void aHopSteersMidAirAndHonoursTheDoubleJump() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Hop", 60, 40, 32);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);
        PlayerState s = new PlayerState(0, "", 300, 300);
        double startX = s.x;

        PlayerInput jump = new PlayerInput(false, true, false, false, 1);
        jump.jump = true;
        PlayerPhysics.step(s, jump, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        double firstRise = s.vz;

        // Falling back down, a second press spends the double jump.
        for (int i = 0; i < 40; i++) {
            PlayerPhysics.step(s, new PlayerInput(false, true, false, false, i + 2),
                    lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        }
        assertTrue(s.vz < 0, "past the peak and falling");
        PlayerInput second = new PlayerInput(false, true, false, false, 100);
        second.jump = true;
        PlayerPhysics.step(s, second, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        assertTrue(s.vz > 0, "the double jump fires mid-hop");
        assertTrue(s.vz < firstRise, "…a shade weaker than the first, as in side-scroll");

        // A third press has nothing left.
        PlayerInput third = new PlayerInput(false, true, false, false, 101);
        third.jump = true;
        double before = s.vz;
        PlayerPhysics.step(s, third, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        assertTrue(s.vz < before, "no third jump: gravity keeps eating the rise");
        assertTrue(s.x > startX, "steering keeps working through the whole hop");
    }

    @Test
    void aHopReadsAsAirborneSoTheJumpAndFallAnimationsPlay() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Hop", 40, 30, 32);
        GameProfile profile = profileFor(Perspective.TOP_DOWN);
        PlayerState s = new PlayerState(0, "", 300, 300);

        assertEquals("idle", PlayerSprites.actionState(s, lvl, profile,
                Perspective.TOP_DOWN, false));

        PlayerInput jump = new PlayerInput(false, false, false, false, 1);
        jump.jump = true;
        PlayerPhysics.step(s, jump, lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        assertEquals("jump", PlayerSprites.actionState(s, lvl, profile,
                        Perspective.TOP_DOWN, false),
                "rising on a hop plays the jump animation");

        for (int i = 0; i < 40; i++) {
            PlayerPhysics.step(s, new PlayerInput(false, false, false, false, i + 2),
                    lvl, profile, Perspective.TOP_DOWN, 1 / 60.0);
        }
        assertEquals("fall", PlayerSprites.actionState(s, lvl, profile,
                        Perspective.TOP_DOWN, false),
                "coming back down plays the fall animation");
    }

    @Test
    void aSideScrollLevelNeverLeavesTheSpriteFloating() {
        // A player who hopped in a top-down level and walked through a door
        // into a side-scrolling one must land, not hover.
        Level plan = LevelFormat.TOP_DOWN.starterLevel("Plan", 40, 30, 32);
        PlayerState s = new PlayerState(0, "", 300, 300);
        PlayerInput jump = new PlayerInput(false, false, false, false, 1);
        jump.jump = true;
        PlayerPhysics.step(s, jump, plan, profileFor(Perspective.TOP_DOWN),
                Perspective.TOP_DOWN, 1 / 60.0);
        assertTrue(s.z > 0);

        Level side = LevelFormat.SIDE_SCROLLER.starterLevel("Side", 40, 20, 32);
        PlayerPhysics.step(s, new PlayerInput(false, false, false, false, 2),
                side, profileFor(Perspective.SIDE_SCROLL), Perspective.SIDE_SCROLL,
                1 / 60.0);
        assertEquals(0, s.z, 0.0001, "the Z axis parks in a side-scroller");
        assertEquals(0, s.vz, 0.0001);
    }

    // --- helpers --------------------------------------------------------------------

    /** Let a player fall onto the floor so the next input jumps from the ground. */
    private static void settle(PlayerState s, Level lvl, GameProfile profile,
                               Perspective perspective) {
        for (int i = 0; i < 120; i++) {
            PlayerPhysics.step(s, new PlayerInput(false, false, false, false, i),
                    lvl, profile, perspective, 1 / 60.0);
        }
    }

    private static boolean hasColor(BufferedImage img, Color want) {
        int rgb = want.getRGB() & 0xFFFFFF;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int px = img.getRGB(x, y);
                if ((px >>> 24) > 0 && (px & 0xFFFFFF) == rgb) return true;
            }
        }
        return false;
    }
}
