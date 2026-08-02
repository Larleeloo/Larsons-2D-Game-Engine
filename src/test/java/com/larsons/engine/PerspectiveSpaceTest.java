package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.DroppedItem;
import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.Mob;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.Projectile;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.entity.Vehicle;
import com.larsons.engine.entity.VehicleRegistry;
import com.larsons.engine.fx.Particles;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.PerspectiveSpace;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import com.larsons.engine.graphics.draw.Java2DTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each level format gets a physical space of its own instead of a warped
 * side-scroller — the thing {@link PerspectiveSpace} names, and the two bugs
 * that came from not having it:
 *
 * <ul>
 *   <li><b>Jump is Space.</b> {@code W} / Up are directions — they swim, they
 *       climb, they walk north on a plane — and no longer fire a jump as a
 *       side effect, on foot or on a mount.</li>
 *   <li><b>Up is not "up the screen".</b> On a plane the screen is the floor,
 *       so effects with a direction — meteors called down from the sky,
 *       particle trajectories, knockback, a thrown stack — resolve against the
 *       format's own axes. Only the side-scroller has "up" on its world plane,
 *       and nothing about the side-scroller changes.</li>
 * </ul>
 */
@Timeout(60)
class PerspectiveSpaceTest {

    private static final double DT = 1 / 60.0;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void unskinned() {
        // Particle assertions read the pre-generated coloured fleck, so make
        // sure no sheet another test installed is what actually draws.
        Skins.install(List.of());
    }

    private static GameProfile profileFor(Perspective p) {
        GameProfile profile = new GameProfile("space");
        profile.perspective = p;
        profile.gravityEnabled = p == Perspective.SIDE_SCROLL;
        profile.audioEnabled = false;
        profile.normalize();
        return profile;
    }

    // --- which way is up ------------------------------------------------------------

    @Test
    void eachFormatKnowsWhichAxisIsUp() {
        assertEquals(PerspectiveSpace.SIDE_VIEW,
                PerspectiveSpace.of(LevelFormat.SIDE_SCROLLER));
        assertEquals(PerspectiveSpace.TOP_DOWN, PerspectiveSpace.of(LevelFormat.TOP_DOWN));
        assertEquals(PerspectiveSpace.ISOMETRIC, PerspectiveSpace.of(LevelFormat.ISOMETRIC));
        assertEquals(PerspectiveSpace.SIDE_VIEW, PerspectiveSpace.of((LevelFormat) null));

        // Only the side-scroller carries "down" on its world plane.
        assertTrue(PerspectiveSpace.SIDE_VIEW.gravityOnPlane());
        assertFalse(PerspectiveSpace.SIDE_VIEW.hasElevation());
        for (PerspectiveSpace plane : List.of(PerspectiveSpace.TOP_DOWN,
                PerspectiveSpace.ISOMETRIC)) {
            assertTrue(plane.hasElevation(), plane + " has a height axis");
            assertFalse(plane.gravityOnPlane(), plane + " does not fall along +y");
            assertEquals(PerspectiveSpace.GRAVITY, plane.gravity(), 0.0001,
                    "gravity does not switch off on a plane, it turns");
        }

        // Height reads differently in the two plan views: top-down's up axis
        // points at the viewer, so a raised body also grows; isometric's is
        // oblique and parallel-projected, so it only moves.
        assertTrue(PerspectiveSpace.TOP_DOWN.heightScale(32, 32) > 1.2,
                "rising in top-down means coming closer to the camera");
        assertEquals(1, PerspectiveSpace.ISOMETRIC.heightScale(32, 32), 0.0001,
                "isometric height lifts a sprite without resizing it");
        assertEquals(1, PerspectiveSpace.TOP_DOWN.heightScale(0, 32), 0.0001,
                "…and something on the floor is drawn at its own size");
    }

    // --- jump is Space --------------------------------------------------------------

    @Test
    void holdingUpDoesNotJumpInASideScroller() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Run", 40, 20, 32);
        GameProfile profile = profileFor(Perspective.SIDE_SCROLL);
        PlayerState s = new PlayerState(0, "", 100, (lvl.height - 3) * 32.0);
        settle(s, lvl, profile, Perspective.SIDE_SCROLL);
        double restY = s.y;

        // W held down, for as long as anyone would hold it.
        PlayerInput up = new PlayerInput(false, false, true, false, 1);
        for (int i = 0; i < 120; i++) {
            PlayerPhysics.step(s, up, lvl, profile, Perspective.SIDE_SCROLL, DT);
            assertTrue(s.vy >= 0, "W must not launch a jump, vy was " + s.vy);
        }
        assertEquals(restY, s.y, 0.001, "the player never left the floor");
        assertEquals(s.maxStamina, s.stamina, 0.001, "and never paid a jump's stamina");

        // Space, from the same standing start, does jump.
        PlayerInput space = new PlayerInput(false, false, false, false, 2);
        space.jump = true;
        PlayerPhysics.step(s, space, lvl, profile, Perspective.SIDE_SCROLL, DT);
        assertTrue(s.vy < 0, "Space is the jump key, vy was " + s.vy);
    }

    @Test
    void upWalksNorthOnAPlaneInsteadOfHopping() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = format.starterLevel("Walk", 40, 30, 32);
            GameProfile profile = profileFor(lvl.perspective);
            PlayerState s = new PlayerState(0, "", 300, 300);
            double startY = s.y;

            PlayerInput up = new PlayerInput(false, false, true, false, 1);
            for (int i = 0; i < 30; i++) {
                PlayerPhysics.step(s, up, lvl, profile, lvl.perspective, DT);
            }
            assertTrue(s.y < startY - 20, "W walks north in " + format + ", y=" + s.y);
            assertEquals(0, s.z, 0.0001, "…and never leaves the floor doing it");
            assertEquals(Facing.NORTH, s.facing, "facing follows the walk in " + format);

            PlayerInput space = new PlayerInput(false, false, false, false, 2);
            space.jump = true;
            PlayerPhysics.step(s, space, lvl, profile, lvl.perspective, DT);
            assertTrue(s.vz > 0, "Space hops in " + format + ", vz=" + s.vz);
        }
    }

    @Test
    void upDoesNotVaultAMountEither() {
        Vehicle horse = new Vehicle(1, VehicleRegistry.standard().get("horse"), 100, 100);
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Ride", 40, 20, 32);
        horse.y = (lvl.height - 3) * 32.0;
        for (int i = 0; i < 60; i++) horse.stepIdle(lvl, true, DT); // settle on the floor

        PlayerInput up = new PlayerInput(false, false, true, false, 1);
        horse.stepDriven(lvl, up, true, DT);
        assertTrue(horse.vy >= 0, "up must not vault the mount, vy=" + horse.vy);

        PlayerInput space = new PlayerInput(false, false, false, false, 2);
        space.jump = true;
        horse.stepDriven(lvl, space, true, DT);
        assertTrue(horse.vy < -300, "Space vaults it, vy=" + horse.vy);
    }

    // --- particle trajectories ------------------------------------------------------

    @Test
    void aFountainRisesUpTheScreenIsometricallyInsteadOfSprayingNorthEast() {
        // The bug in one picture: the isometric camera turns world "north"
        // (-y, which is what a side-scrolling burst calls up) into up-and-to-
        // the-right. A geyser has to go *up*.
        double[] warped = burstCentroid(Perspective.ISOMETRIC, PerspectiveSpace.SIDE_VIEW,
                Particles.Style.FOUNTAIN);
        double[] fixed = burstCentroid(Perspective.ISOMETRIC, PerspectiveSpace.ISOMETRIC,
                Particles.Style.FOUNTAIN);

        assertTrue(warped[0] > CENTRE + 6,
                "the old screen-space 'up' drifts north-east, x=" + warped[0]);
        assertTrue(Math.abs(fixed[0] - CENTRE) < 6,
                "the elevation axis goes straight up, x=" + fixed[0]);
        assertTrue(fixed[1] < CENTRE - 6, "…and upward, y=" + fixed[1]);
    }

    @Test
    void dripsSplashOverTheirImpactInsteadOfRunningSouth() {
        // "Down the screen" in a top-down level is south along the floor, so a
        // drip used to crawl away from whatever it dripped off.
        int warpedTop = burstTopEdge(Perspective.TOP_DOWN, PerspectiveSpace.SIDE_VIEW,
                Particles.Style.DRIP);
        int fixedTop = burstTopEdge(Perspective.TOP_DOWN, PerspectiveSpace.TOP_DOWN,
                Particles.Style.DRIP);

        assertTrue(warpedTop > CENTRE + 4,
                "every warped drip ended up south of the impact, top=" + warpedTop);
        assertTrue(fixedTop < CENTRE - 4,
                "the splash spreads across the floor around it, top=" + fixedTop);
    }

    @Test
    void theSideScrollerKeepsItsOwnParticleMotion() {
        // The side view is the reference: nothing here may change.
        int top = burstTopEdge(Perspective.SIDE_SCROLL, PerspectiveSpace.SIDE_VIEW,
                Particles.Style.DRIP);
        assertTrue(top > CENTRE + 4, "drips still run down the screen, top=" + top);

        double[] fountain = burstCentroid(Perspective.SIDE_SCROLL,
                PerspectiveSpace.SIDE_VIEW, Particles.Style.FOUNTAIN);
        assertTrue(fountain[1] < CENTRE - 6, "and fountains still go up it");
        assertTrue(Math.abs(fountain[0] - CENTRE) < 6, "straight up");
    }

    // --- meteors ---------------------------------------------------------------------

    @Test
    void aMeteorSalvoFallsOutOfTheSkyOntoTheAimPointOnAPlane() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            Level lvl = format.starterLevel("Strike", 60, 60, 32);
            World world = new World(lvl);
            GameProfile p = profileFor(lvl.perspective);
            Inventory inv = new Inventory(world.itemTypes);
            inv.add("meteor_staff", 1);
            inv.select(0);
            PlayerState mage = new PlayerState(1, "mage", 200, 200);
            double aimX = 900, aimY = 900;

            assertNotNull(world.playerShoot(mage, inv, aimX, aimY));
            assertEquals(3, world.projectiles().size(), "three meteors per cast");
            for (Projectile m : world.projectiles()) {
                assertTrue(m.z > 100, format + ": meteors start above the floor, z=" + m.z);
                assertTrue(m.airborne(), format + ": …and are in the air, not on it");
                assertTrue(m.vz < 0, format + ": and fall, vz=" + m.vz);
                // They ring the target rather than sitting a screen to its
                // north, which is what "aimY - 280" used to mean out here.
                assertTrue(Math.hypot(m.x - aimX, m.y - aimY) < 64,
                        format + ": launched over the aim point, not away from it");
            }

            List<World.Impact> impacts = stepUntilNoProjectiles(world, p, mage);
            assertTrue(impacts.stream().anyMatch(im -> "meteor".equals(im.key())),
                    format + ": the salvo struck");
            for (World.Impact im : impacts) {
                if (!"meteor".equals(im.key())) continue;
                assertTrue(Math.hypot(im.x() - aimX, im.y() - aimY) < 24,
                        format + ": it landed on the tile it was aimed at, not beside it");
            }
        }
    }

    @Test
    void aMeteorSalvoStillArrivesFromUpTheScreenInASideScroller() {
        Level lvl = LevelFormat.SIDE_SCROLLER.starterLevel("Strike", 60, 40, 32);
        World world = new World(lvl);
        Inventory inv = new Inventory(world.itemTypes);
        inv.add("meteor_staff", 1);
        inv.select(0);
        PlayerState mage = new PlayerState(1, "mage", 100, 800);

        assertNotNull(world.playerShoot(mage, inv, 700, 850));
        assertEquals(3, world.projectiles().size());
        for (Projectile m : world.projectiles()) {
            assertEquals(0, m.z, 0.0001, "a side-scroller has no height axis to use");
            assertTrue(m.y < 850, "meteors start above the aim point");
            assertTrue(m.vy > 0, "and rain downward");
        }
    }

    @Test
    void aFallingShotClearsWallsAndStrikesWhenItTouchesDown() {
        Level lvl = LevelFormat.TOP_DOWN.starterLevel("Wall", 40, 40, 32);
        // A wall right where it will come down: on the way it is *above* the
        // level, so it must not clip into the wall mid-fall.
        lvl.setTile(20, 20, lvl.blocks.get("stone_wall").id());
        ProjectileDef def = ProjectileRegistry.standard().get("meteor");
        double target = 20 * 32 + 16;
        Projectile m = Projectile.fromSky(1, def, 0, target, target, target, target, 280);

        int ticks = 0;
        while (!m.step(lvl, PerspectiveSpace.TOP_DOWN, false, DT) && ticks < 600) {
            assertTrue(m.airborne(), "still in the air on tick " + ticks);
            ticks++;
        }
        assertTrue(ticks > 0 && ticks < 600, "it came down, after " + ticks + " ticks");
        assertEquals(0, m.z, 0.0001, "landing means reaching the floor");
        assertEquals(target, m.x, 1.0, "…on the tile it was aimed at");
        assertEquals(target, m.y, 1.0);
    }

    // --- knockback & thrown stacks ---------------------------------------------------

    @Test
    void knockbackFollowsTheHitVectorOnAPlane() {
        Mob north = new Mob(1, MobRegistry.standard().get("slime"), 300, 300);
        // Struck from due north: on a floor, that shoves the mob south.
        north.damage(1, north.x + north.def.size() / 2, 200, PerspectiveSpace.TOP_DOWN);
        assertTrue(north.y > 300, "knocked south, y=" + north.y);
        assertEquals(300, north.x, 0.001, "and not sideways for no reason");

        Mob side = new Mob(2, MobRegistry.standard().get("slime"), 300, 300);
        side.damage(1, 200, 200, PerspectiveSpace.SIDE_VIEW);
        assertTrue(side.x > 300, "a side-scroller still shoves along x, x=" + side.x);
        assertEquals(300, side.y, 0.001, "y is gravity's axis there, not knockback's");
        assertTrue(side.vy < 0, "…with the familiar pop up the screen");
    }

    @Test
    void aThrownStackLeavesAlongTheWayThePlayerFaces() {
        DroppedItem north = new DroppedItem(1, "dirt", 1, 0, 0)
                .tossForward(Facing.NORTH, false);
        assertTrue(north.vy < -100, "thrown north on a plane, vy=" + north.vy);
        assertEquals(0, north.vx, 0.001);

        DroppedItem east = new DroppedItem(2, "dirt", 1, 0, 0)
                .tossForward(Facing.SOUTH_EAST, false);
        assertTrue(east.vx > 0 && east.vy > 0, "and south-east when facing south-east");

        DroppedItem sideways = new DroppedItem(3, "dirt", 1, 0, 0)
                .tossForward(Facing.NORTH, true);
        assertTrue(sideways.vy < 0, "a side-scroller still lobs it up the screen");
        assertTrue(sideways.vx > 0, "…and forward, never straight north");
    }

    // --- helpers ----------------------------------------------------------------------

    /** Canvas size and the screen pixel the camera's focus lands on. */
    private static final int CANVAS = 200;
    private static final int CENTRE = CANVAS / 2;

    /** Where a burst was fired from, far enough in that nothing clamps. */
    private static final double ORIGIN = 1000;

    /**
     * Fire one burst in {@code space}, let it fly, and draw it through a
     * {@code perspective} camera focused on the burst point. Returns the
     * centroid of every pixel it painted, in screen coordinates.
     */
    private static double[] burstCentroid(Perspective perspective, PerspectiveSpace space,
                                          Particles.Style style) {
        BufferedImage canvas = paintBurst(perspective, space, style);
        long n = 0, sx = 0, sy = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if ((canvas.getRGB(x, y) >>> 24) == 0) continue;
                n++;
                sx += x;
                sy += y;
            }
        }
        assertTrue(n > 0, style + " painted nothing in " + space);
        return new double[]{sx / (double) n, sy / (double) n};
    }

    /** The topmost screen row a burst painted (smaller = higher up the screen). */
    private static int burstTopEdge(Perspective perspective, PerspectiveSpace space,
                                    Particles.Style style) {
        BufferedImage canvas = paintBurst(perspective, space, style);
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                if ((canvas.getRGB(x, y) >>> 24) != 0) return y;
            }
        }
        throw new AssertionError(style + " painted nothing in " + space);
    }

    private static BufferedImage paintBurst(Perspective perspective, PerspectiveSpace space,
                                            Particles.Style style) {
        Particles particles = new Particles();
        particles.setSpace(space);
        particles.burst(ORIGIN, ORIGIN, Color.GREEN, 200, style);
        for (int i = 0; i < 9; i++) particles.update(DT);

        Camera camera = new Camera(perspective, CANVAS, CANVAS);
        camera.tileSize = 32;
        camera.centerOn(ORIGIN, ORIGIN);
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        particles.render(new Java2DTarget(g, CANVAS, CANVAS), camera);
        g.dispose();
        return canvas;
    }

    /** Drop a player onto the floor so the next input starts from a standstill. */
    private static void settle(PlayerState s, Level lvl, GameProfile p, Perspective view) {
        PlayerInput idle = new PlayerInput();
        for (int i = 0; i < 240; i++) PlayerPhysics.step(s, idle, lvl, p, view, DT);
    }

    /** Run the world until every shot has resolved, collecting what it hit. */
    private static List<World.Impact> stepUntilNoProjectiles(World world, GameProfile p,
                                                             PlayerState player) {
        List<World.Impact> impacts = new java.util.ArrayList<>();
        for (int i = 0; i < 600 && !world.projectiles().isEmpty(); i++) {
            world.step(DT, List.of(player), p);
            impacts.addAll(world.pollImpacts());
        }
        impacts.addAll(world.pollImpacts());
        return impacts;
    }
}
