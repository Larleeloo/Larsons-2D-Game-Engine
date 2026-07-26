package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.DirectionalSprites;
import com.larsons.engine.graphics.Facing;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directional animations: which way a character faces picks the sprite that
 * draws them — two directions in a side-scroller (so the near arm is in front
 * whichever way you turn), eight in the plan-view formats — with pre-generated
 * art behind every direction and per-direction sheets a texture pack can
 * supply.
 */
@Timeout(60)
class DirectionalAnimationTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** No drop-in texture pack on this machine — the built-in art is the baseline. */
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

    /** A one-frame 32x32 sheet of a solid colour, written to disk. */
    private static Path solidSheet(Path dir, String name, Color color) throws IOException {
        BufferedImage sheet = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        ImageIO.write(sheet, "png", file.toFile());
        return file;
    }

    private static Level flatLevel(LevelFormat format) {
        Level lvl = format.starterLevel("Directions", 40, 20, 32);
        lvl.spawnX = 5 * 32;
        lvl.spawnY = 5 * 32;
        return lvl;
    }

    private static GameProfile profileFor(Perspective p) {
        GameProfile profile = new GameProfile("dirs");
        profile.perspective = p;
        profile.gravityEnabled = p == Perspective.SIDE_SCROLL;
        profile.normalize();
        return profile;
    }

    // --- the compass ------------------------------------------------------------

    @Test
    void movementVectorsSnapToTheNearestCompassPoint() {
        // Screen space: +x east, +y south.
        assertEquals(Facing.EAST, Facing.of(1, 0, Facing.NORTH));
        assertEquals(Facing.WEST, Facing.of(-1, 0, Facing.NORTH));
        assertEquals(Facing.NORTH, Facing.of(0, -1, Facing.EAST));
        assertEquals(Facing.SOUTH, Facing.of(0, 1, Facing.EAST));
        assertEquals(Facing.NORTH_EAST, Facing.of(1, -1, Facing.EAST));
        assertEquals(Facing.NORTH_WEST, Facing.of(-1, -1, Facing.EAST));
        assertEquals(Facing.SOUTH_EAST, Facing.of(1, 1, Facing.EAST));
        assertEquals(Facing.SOUTH_WEST, Facing.of(-1, 1, Facing.EAST));
        // Standing still keeps the heading rather than snapping to a default.
        assertEquals(Facing.NORTH_WEST, Facing.of(0, 0, Facing.NORTH_WEST));
    }

    @Test
    void westFacingsMirrorTheirEasternTwin() {
        assertEquals(Facing.EAST, Facing.WEST.mirrorOf());
        assertEquals(Facing.NORTH_EAST, Facing.NORTH_WEST.mirrorOf());
        assertEquals(Facing.SOUTH_EAST, Facing.SOUTH_WEST.mirrorOf());
        assertTrue(Facing.WEST.mirrored());
        // The ones art is actually drawn for are their own answer.
        for (Facing f : Facing.canonical()) {
            assertFalse(f.mirrored(), f + " is drawn directly, not mirrored");
            assertSame(f, f.mirrorOf());
        }
    }

    @Test
    void aSideScrollerOnlyEverFacesLeftOrRight() {
        // Vertical movement in a side-scroller is jumping, not turning.
        assertEquals(Facing.EAST,
                Facing.of(1, -5, Perspective.SIDE_SCROLL, Facing.WEST));
        assertEquals(Facing.WEST,
                Facing.of(-1, 3, Perspective.SIDE_SCROLL, Facing.EAST));
        assertEquals(Facing.WEST,
                Facing.of(0, -9, Perspective.SIDE_SCROLL, Facing.WEST),
                "jumping straight up keeps the last heading");
        // A plan view uses the whole compass.
        assertEquals(Facing.NORTH,
                Facing.of(0, -1, Perspective.TOP_DOWN, Facing.EAST));
    }

    // --- the simulation records it ----------------------------------------------

    @Test
    void walkingSetsTheFacingInEveryPerspective() {
        Level side = flatLevel(LevelFormat.SIDE_SCROLLER);
        GameProfile sideProfile = profileFor(Perspective.SIDE_SCROLL);
        PlayerState s = new PlayerState(0, "", side.spawnX, side.spawnY);

        PlayerInput right = new PlayerInput(false, true, false, false, 1);
        PlayerPhysics.step(s, right, side, sideProfile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertEquals(Facing.EAST, s.facing);
        assertFalse(s.facingLeft);

        PlayerInput left = new PlayerInput(true, false, false, false, 2);
        PlayerPhysics.step(s, left, side, sideProfile, Perspective.SIDE_SCROLL, 1 / 60.0);
        assertEquals(Facing.WEST, s.facing);
        assertTrue(s.facingLeft);

        // Top-down: eight directions, and facingLeft stays consistent with them.
        Level plan = flatLevel(LevelFormat.TOP_DOWN);
        GameProfile planProfile = profileFor(Perspective.TOP_DOWN);
        PlayerState t = new PlayerState(0, "", plan.spawnX, plan.spawnY);
        PlayerInput upRight = new PlayerInput(false, true, true, false, 1);
        PlayerPhysics.step(t, upRight, plan, planProfile, Perspective.TOP_DOWN, 1 / 60.0);
        assertEquals(Facing.NORTH_EAST, t.facing);
        assertFalse(t.facingLeft);

        PlayerInput upLeft = new PlayerInput(true, false, true, false, 2);
        PlayerPhysics.step(t, upLeft, plan, planProfile, Perspective.TOP_DOWN, 1 / 60.0);
        assertEquals(Facing.NORTH_WEST, t.facing);
        assertTrue(t.facingLeft, "a north-west facing reads as facing left");
    }

    @Test
    void mobsFaceTheWayTheyWalkAndReplicateIt() {
        Level plan = flatLevel(LevelFormat.TOP_DOWN);
        Mob m = new Mob(1, MobRegistry.standard().get("slime"), 200, 200);
        m.facing = Facing.NORTH_WEST;
        // The wire form carries the compass point alongside the left/right flag.
        assertEquals("nw", m.toMap().get("d"));
        m.facing = Facing.EAST;
        assertFalse(m.toMap().containsKey("d"),
                "plain east/west is already covered by the facingLeft flag");
        assertNotNull(plan);
    }

    // --- the art ----------------------------------------------------------------

    @Test
    void everyDirectionHasPreGeneratedArtAndTheyDiffer() {
        BufferedImage east = DirectionalSprites.frame(32, Color.BLUE, Facing.EAST, 0);
        BufferedImage north = DirectionalSprites.frame(32, Color.BLUE, Facing.NORTH, 0);
        BufferedImage south = DirectionalSprites.frame(32, Color.BLUE, Facing.SOUTH, 0);
        assertEquals(32, east.getWidth());
        assertEquals(32, east.getHeight());
        // Front, back and profile are genuinely different pictures — the whole
        // point of directional art rather than one mirrored sprite.
        assertFalse(sameImage(east, north), "the side view differs from the back");
        assertFalse(sameImage(north, south), "the back differs from the front");

        // The walk cycle animates: a later frame differs from the first.
        BufferedImage stride = DirectionalSprites.frame(32, Color.BLUE, Facing.EAST, 1);
        assertFalse(sameImage(east, stride), "the walk cycle moves between frames");

        // Facing west is the eastern art flipped, so the near arm swaps sides.
        BufferedImage west = DirectionalSprites.frame(32, Color.BLUE, Facing.WEST, 0);
        assertTrue(sameImage(west, mirror(east)), "west is east, mirrored");
        assertFalse(sameImage(west, east));
    }

    @Test
    void unskinnedCharactersFallBackToDirectionalArtAndAreNeverFlipped() {
        for (Facing f : Facing.values()) {
            PlayerSprites.Frame frame = PlayerSprites.directionalFrame(
                    "", "walk", f, 0, 32, Color.BLUE);
            assertNotNull(frame.image(), "pre-generated art backs " + f);
            assertFalse(frame.mirrored(),
                    "generated art is already drawn for " + f + ", so it must not be flipped");
            assertTrue(sameImage(frame.image(),
                            DirectionalSprites.frame(32, Color.BLUE, f, 0)),
                    "the fallback is this direction's generated frame");
        }
    }

    @Test
    void aPerDirectionSheetWinsAndItsMirrorCoversTheOppositeFacing(@TempDir Path dir)
            throws IOException {
        // One sheet for east only.
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk", Facing.EAST),
                solidSheet(dir, "east.png", Color.GREEN).toString(), 32, 32, 1, 0));

        PlayerSprites.Frame east = PlayerSprites.directionalFrame(
                "", "walk", Facing.EAST, 0, 32, Color.BLUE);
        assertEquals(Color.GREEN.getRGB(), east.image().getRGB(16, 16));
        assertFalse(east.mirrored());

        // West has no sheet of its own, so it draws the eastern one flipped.
        PlayerSprites.Frame west = PlayerSprites.directionalFrame(
                "", "walk", Facing.WEST, 0, 32, Color.BLUE);
        assertEquals(Color.GREEN.getRGB(), west.image().getRGB(16, 16));
        assertTrue(west.mirrored(), "west borrows the east sheet, mirrored");

        // Give west its own art and it stops borrowing.
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk", Facing.WEST),
                solidSheet(dir, "west.png", Color.ORANGE).toString(), 32, 32, 1, 0));
        PlayerSprites.Frame own = PlayerSprites.directionalFrame(
                "", "walk", Facing.WEST, 0, 32, Color.BLUE);
        assertEquals(Color.ORANGE.getRGB(), own.image().getRGB(16, 16));
        assertFalse(own.mirrored(), "west now has art of its own");

        // A direction with no sheet at all still falls back to the plain
        // state sheet, mirrored when it points west.
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk"),
                solidSheet(dir, "any.png", Color.CYAN).toString(), 32, 32, 1, 0));
        PlayerSprites.Frame northWest = PlayerSprites.directionalFrame(
                "", "walk", Facing.NORTH_WEST, 0, 32, Color.BLUE);
        assertEquals(Color.CYAN.getRGB(), northWest.image().getRGB(16, 16));
        assertTrue(northWest.mirrored());
    }

    @Test
    void aCharacterProfileWearsItsOwnSheetsAndFallsBackToThePlayers(@TempDir Path dir)
            throws IOException {
        Skins.put(new SkinDef(PlayerSprites.characterStateKey("rogue", "walk"),
                solidSheet(dir, "rogue.png", Color.MAGENTA).toString(), 32, 32, 1, 0));
        Skins.put(new SkinDef(PlayerSprites.stateKey("walk"),
                solidSheet(dir, "shared.png", Color.CYAN).toString(), 32, 32, 1, 0));

        assertEquals(Color.MAGENTA.getRGB(), PlayerSprites.directionalFrame(
                "rogue", "walk", Facing.EAST, 0, 32, Color.BLUE).image().getRGB(16, 16),
                "a profile's own sheet wins");
        assertEquals(Color.CYAN.getRGB(), PlayerSprites.directionalFrame(
                "knight", "walk", Facing.EAST, 0, 32, Color.BLUE).image().getRGB(16, 16),
                "a profile with no art of its own wears the shared player sheet");
    }

    // --- the texture pack --------------------------------------------------------

    @Test
    void directionalKeysNameProgressivelyMoreGeneralPackFiles() {
        List<String> playerPaths = TextureKeys.paths("player/walk/ne");
        assertEquals(List.of("player/walk_ne", "player/walk"), playerPaths,
                "a direction's sheet, then the state's own");

        List<String> mobPaths = TextureKeys.paths("mob/slime/walk/e");
        assertEquals(List.of("mobs/slime_walk_e", "mobs/slime_walk", "mobs/slime"),
                mobPaths, "direction, then state, then the whole mob");

        assertEquals(List.of("player/rogue_walk_se", "player/rogue_walk", "player/rogue"),
                TextureKeys.paths("character/rogue/walk/se"));

        // Particles and projectiles get folders of their own.
        assertEquals("particles/embers.png", TexturePack.fileNameFor("particle/embers"));
        assertEquals("projectiles/arrow.png", TexturePack.fileNameFor("projectile/arrow"));

        // The old, non-directional names still resolve exactly as before.
        assertEquals("mobs/slime_walk.png", TexturePack.fileNameFor("mob/slime/walk"));
        assertEquals("player/idle.png",
                TexturePack.fileNameFor(PlayerSprites.stateKey("idle")));
    }

    @Test
    void aPackSheetForOneDirectionIsFoundByName(@TempDir Path tmp) throws IOException {
        Path root = TexturePack.scaffold(tmp.resolve("textures"));
        solidSheet(root.resolve("player"), "walk_ne.png", Color.RED);
        TexturePack.useDir(root.toString());

        SkinDef def = TexturePack.lookup(PlayerSprites.stateKey("walk", Facing.NORTH_EAST));
        assertNotNull(def, "player/walk_ne.png backs the north-east walk");
        assertNotEquals(null, TexturePack.fileFor("player/walk/ne"));
        // The key list tells a creator the directions exist.
        String keys = Files.readString(root.resolve(TexturePack.KEYS_FILE));
        assertTrue(keys.contains("_<dir>"), "the key list documents per-direction sheets");
    }

    // --- helpers ------------------------------------------------------------------

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static BufferedImage mirror(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, src.getWidth(), 0, -src.getWidth(), src.getHeight(), null);
        g.dispose();
        return out;
    }
}
