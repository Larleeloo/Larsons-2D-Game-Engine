package com.larsons.engine;

import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.graphics.Camera;
import com.larsons.engine.graphics.DepthPass;
import com.larsons.engine.graphics.Facing;
import com.larsons.engine.graphics.PlayerSprites;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.TerrainPainter;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.ActorSize;
import com.larsons.engine.sim.PlayerInput;
import com.larsons.engine.sim.PlayerPhysics;
import com.larsons.engine.sim.PlayerState;
import com.larsons.engine.world.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An actor's two sizes: how large it is drawn, and how much floor it occupies.
 *
 * <p>They were one number, which is exactly what stopped a creator using their
 * own art. A 64&times;64 character drawn at the engine's 0.9 of a tile is a
 * postage stamp, and the only way to make them bigger was to make their body
 * bigger — which walls them out of every gap they used to fit through. So the
 * sprite says how large a character <em>looks</em> and the hitbox says how much
 * floor they <em>stand on</em>, and neither is derived from the other.
 */
@Timeout(120)
class ActorSizeTest {

    private static final int CANVAS = 360;
    private static final double DT = 1.0 / 60.0;
    private static final Color ACTOR = new Color(255, 0, 255);

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void unskinned() {
        Skins.install(List.of());
    }

    // --- the two sizes are independent -------------------------------------------

    /**
     * Drawing a character four times the size does not change a single
     * collision — which is the whole point of the split, and the thing a
     * creator is trusting when they raise the sprite slider.
     */
    @Test
    void redrawingACharacterDoesNotMoveTheirBody() {
        Level lvl = corridor(LevelFormat.TOP_DOWN);
        GameProfile p = profile();

        double[] rest = new double[2];
        for (double sprite : List.of(ActorSize.MIN_TILES, 0.9, 3.0, ActorSize.MAX_TILES)) {
            CharacterProfile c = new CharacterProfile("c", "C");
            c.spriteScale = sprite;
            c.hitboxScale = 0.9;
            PlayerState s = walkInto(lvl, p, c);
            if (rest[0] == 0 && rest[1] == 0) {
                rest[0] = s.x;
                rest[1] = s.y;
            }
            assertEquals(rest[0], s.x, 0.0, "sprite " + sprite + " moved the body sideways");
            assertEquals(rest[1], s.y, 0.0, "sprite " + sprite + " moved the body along");
            assertEquals(ActorSize.pixels(sprite, p.tileSize), s.spriteSize, 0.0,
                    "and it is drawn at the size it was given");
        }
    }

    /** Changing the footprint does move the body — the other half of the split. */
    @Test
    void resizingTheFootprintMovesWhereTheyComeToRest() {
        Level lvl = corridor(LevelFormat.TOP_DOWN);
        GameProfile p = profile();

        CharacterProfile small = new CharacterProfile("s", "S");
        small.hitboxScale = 0.3;
        CharacterProfile large = new CharacterProfile("l", "L");
        large.hitboxScale = 2.0;

        assertNotEquals(walkInto(lvl, p, small).y, walkInto(lvl, p, large).y,
                "a bigger footprint stops further out from the wall");
    }

    /**
     * A giant with a small footprint fits a one-block gap.
     *
     * <p>This is the case the split exists for: a creator who wants a boss
     * three blocks tall, walking the same corridors everything else does. With
     * one size it was a choice between drawing them large and letting them
     * through the door.
     */
    @Test
    void aGiantWithASmallFootprintFitsAOneBlockGap() {
        Level lvl = corridor(LevelFormat.TOP_DOWN);
        GameProfile p = profile();
        CharacterProfile giant = new CharacterProfile("g", "Giant");
        giant.spriteScale = ActorSize.MAX_TILES;
        giant.hitboxScale = 0.5;

        PlayerState s = new PlayerState(1, "g", GAP_COL * 32 + 8, 2 * 32);
        giant.applyTo(s, lvl.tileSize);
        PlayerInput down = new PlayerInput(false, false, false, true, 1);
        for (int i = 0; i < 400; i++) {
            PlayerPhysics.step(s, down, lvl, p, LevelFormat.TOP_DOWN.perspective(), DT);
        }
        assertTrue(s.y > WALL_ROW * 32, "the giant walked through the gap, reaching y=" + s.y);
    }

    // --- the range a creator authors in ------------------------------------------

    @Test
    void sizesAreClampedToTheAuthoringRange() {
        assertEquals(ActorSize.MIN_TILES, ActorSize.clampTiles(0.001), 1e-9);
        assertEquals(ActorSize.MAX_TILES, ActorSize.clampTiles(500), 1e-9);
        assertEquals(0.2, ActorSize.MIN_TILES, 1e-9, "a fifth of a block is the floor");
        assertTrue(ActorSize.MAX_TILES >= 8.0, "and the ceiling fills a good part of the screen");

        CharacterProfile c = new CharacterProfile("c", "C");
        c.spriteScale = 99;
        c.hitboxScale = -4;
        c.normalize();
        assertEquals(ActorSize.MAX_TILES, c.spriteScale, 1e-9);
        assertEquals(ActorSize.MIN_TILES, c.hitboxScale, 1e-9);
    }

    /**
     * The game type's own player size is the default and nothing more, and it
     * is still the pixel count it always was — a game type that never touches
     * any of this plays exactly as it did.
     */
    @Test
    void theGameTypeDefaultIsUnchanged() {
        for (int tile : List.of(8, 16, 32, 48, 64, 96)) {
            assertEquals((int) Math.floor(tile * 0.9), GameProfile.playerSizeFor(tile),
                    "tile " + tile + ": the default player size moved");
        }
        PlayerState fresh = new PlayerState(1, "p", 0, 0);
        assertEquals(28, fresh.hitSize(28), 0.0, "a body that never chose falls back");
        assertEquals(28, fresh.spriteSize(28), 0.0);
    }

    // --- what gets written down ---------------------------------------------------

    @Test
    void bothSizesSurviveASave() {
        CharacterProfile c = new CharacterProfile("hero", "Hero");
        c.spriteScale = 2.5;
        c.hitboxScale = 0.4;
        CharacterProfile back = CharacterProfile.fromMap(c.toMap());
        assertEquals(2.5, back.spriteScale, 1e-9);
        assertEquals(0.4, back.hitboxScale, 1e-9);

        // A character that never moved them says nothing, so a profile written
        // before sizes existed round-trips unchanged.
        CharacterProfile plain = new CharacterProfile("p", "P");
        assertFalse(plain.toMap().containsKey("spriteScale"));
        assertEquals(ActorSize.DEFAULT_TILES,
                CharacterProfile.fromMap(plain.toMap()).spriteScale, 1e-9);
    }

    /** A species registered before the split occupies exactly what it is drawn on. */
    @Test
    void aMobWithNoFootprintOfItsOwnStandsOnItsDrawnSize() {
        MobDef old = MobDef.hostile("z", "Z", Color.GREEN, Color.BLACK, 40, 60, 20, 5);
        assertEquals(40, old.size(), 1e-9);
        assertEquals(40, old.hitbox(), 1e-9, "one size, as it always was");

        MobDef giant = old.drawnAt(200);
        assertEquals(200, giant.size(), 1e-9);
        assertEquals(40, giant.hitbox(), 1e-9, "redrawing it does not widen it");

        MobDef wide = old.standingOn(90);
        assertEquals(40, wide.size(), 1e-9);
        assertEquals(90, wide.hitbox(), 1e-9);
    }

    // --- and none of it lets a block eat the sprite --------------------------------

    /**
     * At every size a creator can author, an actor standing against a wall is
     * never covered by it.
     *
     * <p>The sizes are what makes this worth asking twice. A plan view sorts
     * sprites on the cell their feet are on, and a sprite eight blocks tall
     * reaches across a great many cells it is not standing on — so a rule that
     * holds for a one-block character is not thereby a rule that holds. This
     * sweeps the range against a wall, in both plan views, at three tile sizes,
     * and along the wall rather than at one spot, because the defect this
     * guards lived in a band a few pixels wide.
     */
    @Test
    void noSpriteIsEatenByAWallAtAnySize() {
        for (LevelFormat format : List.of(LevelFormat.TOP_DOWN, LevelFormat.ISOMETRIC)) {
            for (int tile : List.of(16, 32, 64)) {
                Level lvl = wall(format, tile);
                for (double sprite : List.of(ActorSize.MIN_TILES, 0.9, 3.0, ActorSize.MAX_TILES)) {
                    for (double hit : List.of(ActorSize.MIN_TILES, 0.9, 2.0)) {
                        double hitPx = ActorSize.pixels(hit, tile);
                        double drawPx = ActorSize.pixels(sprite, tile);
                        for (int step = 0; step <= 8; step++) {
                            double footX = 8 * tile + step * (tile / 4.0);
                            double footY = WALL_ROW * tile + tile + hitPx / 2;
                            int eaten = onTop(lvl, tile, footX, footY, drawPx)
                                    - sorted(lvl, tile, footX, footY, hitPx, drawPx);
                            assertEquals(0, eaten, format + " tile=" + tile
                                    + " sprite=" + sprite + " hitbox=" + hit
                                    + " at x=" + footX + ": the wall ate " + eaten
                                    + " px of the character standing against it");
                        }
                    }
                }
            }
        }
    }

    // --- helpers ------------------------------------------------------------------

    private static final int WALL_ROW = 6;
    private static final int GAP_COL = 8;

    private static GameProfile profile() {
        GameProfile p = new GameProfile("size-test");
        p.tileSize = 32;
        p.normalize();
        return p;
    }

    /** A floored plan-view level with an east-west wall and a one-block gap in it. */
    private static Level corridor(LevelFormat format) {
        Level lvl = Level.empty("corridor", 20, 20, 32);
        lvl.setFormat(format);
        Block floor = lvl.blocks.get("stone_path");
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, floor.id());
        }
        int stone = lvl.blocks.get("stone").id();
        int layer = lvl.layered() ? Level.LAYER_UPPER : Level.LAYER_GROUND;
        for (int c = 0; c < lvl.width; c++) {
            if (c != GAP_COL) lvl.setTile(c, WALL_ROW, layer, stone);
        }
        return lvl;
    }

    /** The same, without the gap — a wall to walk into. */
    private static Level wall(LevelFormat format, int tile) {
        Level lvl = Level.empty("wall", 20, 20, tile);
        lvl.setFormat(format);
        Block floor = lvl.blocks.get("stone_path");
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, floor.id());
        }
        int stone = lvl.blocks.get("stone").id();
        int layer = lvl.layered() ? Level.LAYER_UPPER : Level.LAYER_GROUND;
        for (int c = 0; c < lvl.width; c++) lvl.setTile(c, WALL_ROW, layer, stone);
        return lvl;
    }

    /** Walk a character north into the wall until they stop. */
    private static PlayerState walkInto(Level lvl, GameProfile p, CharacterProfile c) {
        PlayerState s = new PlayerState(1, "t", 4 * 32 + 6, 12 * 32);
        c.applyTo(s, lvl.tileSize);
        PlayerInput up = new PlayerInput(false, false, true, false, 1);
        for (int i = 0; i < 400; i++) {
            PlayerPhysics.step(s, up, lvl, p, lvl.format().perspective(), DT);
        }
        return s;
    }

    /** Actor pixels when nothing may cover them. */
    private static int onTop(Level lvl, int tile, double footX, double footY, double draw) {
        return paint(lvl, tile, footX, footY, 0, draw, false);
    }

    /** Actor pixels once the depth pass has had its say. */
    private static int sorted(Level lvl, int tile, double footX, double footY,
                              double hit, double draw) {
        return paint(lvl, tile, footX, footY, hit, draw, true);
    }

    private static int paint(Level lvl, int tile, double footX, double footY,
                             double hit, double draw, boolean sorted) {
        Camera cam = new Camera(lvl.perspective, CANVAS, CANVAS);
        cam.tileSize = tile;
        cam.zoom = Math.max(0.2, 96.0 / Math.max(tile, draw));
        cam.centerOn(footX, WALL_ROW * tile + tile / 2.0);
        BufferedImage canvas = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        DrawTarget target = Java2DTarget.unsized(g);
        DepthPass standing = DepthPass.of(lvl.perspective);
        TerrainPainter.draw(target, lvl, cam, new int[]{0, 0, lvl.width - 1, lvl.height - 1},
                0.5, standing, null);

        int w = Math.max(2, (int) Math.round(draw * cam.zoom));
        int fx = cam.worldToScreenX(footX, footY);
        int fy = cam.worldToScreenY(footX, footY);
        // The real art, so what is counted is the silhouette a player sees
        // rather than the transparent corners of its bounding box.
        PlayerSprites.Frame f = PlayerSprites.directionalFrame("", "idle", Facing.NORTH,
                0, Math.max(8, (int) draw), ACTOR);
        Runnable actor = () -> target.drawImage(f.image(), fx - w / 2, fy - w, w, w);
        if (sorted) {
            standing.at(TerrainPainter.standingDepth(cam, tile, footX, footY), fy, actor);
            standing.flush();
        } else {
            standing.flush();
            actor.run();
        }
        g.dispose();
        int seen = 0;
        for (int y = 0; y < CANVAS; y++) {
            for (int x = 0; x < CANVAS; x++) {
                int rgb = canvas.getRGB(x, y);
                if ((rgb >>> 24) != 0 && !terrain(rgb)) seen++;
            }
        }
        return seen;
    }

    /** Whether a pixel is one the terrain painted (floor or block, never the actor). */
    private static boolean terrain(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        return (g > r + 20 && g > b + 20) || (Math.abs(r - g) < 14 && Math.abs(g - b) < 14);
    }
}
