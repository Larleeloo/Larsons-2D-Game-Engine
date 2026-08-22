package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a level's free-standing {@link Decor} — the trees, boulders and bushes
 * painted from the creative palette and stored as entity spawns of kind
 * {@code "decor_bg"} / {@code "decor_fg"}. Shared by the creative editor and
 * the play scene so a level's scenery looks identical while editing and
 * playing.
 *
 * <p>Decorations are anchored bottom-centre, so painting one on a floor line
 * plants it there. Two things follow from that, and both are why this is a
 * painter rather than a loop in each scene:
 *
 * <ul>
 *   <li><b>Nearer scenery covers farther scenery.</b> Every decoration is
 *       handed to a {@link DepthPass} keyed on how far down the screen its
 *       <em>feet</em> land — the depth order in all three of the camera's
 *       projections. Painting in level order instead let whichever tree
 *       happened to be stored last cover the one standing in front of it. On
 *       a plane that pass is shared with the level's actors, so a player
 *       walking south past a tree passes behind it and then in front.</li>
 *   <li><b>Off-screen scenery costs nothing.</b> Each decoration is culled
 *       against the viewport by its own projected sprite box, so a generated
 *       overworld with thousands of trees only pays for the ones in view.</li>
 * </ul>
 *
 * <p>Which side of the terrain a layer is drawn on is the caller's decision,
 * because it depends on the level's format rather than on the decoration —
 * see {@code PerspectiveSpace.scenerySitsBehindTerrain()}.
 */
public final class DecorPainter {

    /** Level entity kind holding the background (behind-the-actors) layer. */
    public static final String BACKGROUND_KIND = "decor_bg";

    /** Level entity kind holding the foreground (in-front-of-actors) layer. */
    public static final String FOREGROUND_KIND = "decor_fg";

    /** Sprite resolution the procedural decoration art is rendered at. */
    private static final int SPRITE_PX = 64;

    private DecorPainter() {}

    /** The entity kind one decoration layer is stored under. */
    public static String kindFor(boolean foreground) {
        return foreground ? FOREGROUND_KIND : BACKGROUND_KIND;
    }

    /** Whether an entity spawn kind is one of the two decoration layers. */
    public static boolean isDecor(String kind) {
        return BACKGROUND_KIND.equals(kind) || FOREGROUND_KIND.equals(kind);
    }

    /**
     * Draw one decoration layer of a level into {@code into} — its own pass
     * when the layer stands on its own, or the pass the level's actors share
     * when a plan view has to decide which of a tree and a player is in front.
     *
     * @param foreground which layer to draw this pass
     * @param animClock  seconds, for animated (sprite-sheet) decoration skins
     */
    public static void draw(DrawTarget target, Level level, Camera camera,
                            boolean foreground, double animClock, DepthPass into) {
        for (Placed p : collect(level, camera, foreground, animClock, true, null)) {
            into.at(p.tile(), p.depth(), () ->
                    target.drawImage(p.sprite(), p.x(), p.y(), p.size(), p.size()));
        }
    }

    /**
     * Draw one decoration layer through an eye standing in the world — the
     * first- and third-person views' scenery.
     *
     * <p><b>Every decoration was missing from those views entirely</b>, and the
     * reason was not that scenery is hard to project: it is that this painter
     * only ever spoke to a {@link DepthPass}, which is the flat camera's
     * ordering, so a solid frame simply never called it. A tree is a flat
     * picture standing on the ground, which is exactly what
     * {@link SolidPainter#billboard} draws — so the sprite, its size and its
     * screen anchor are all the same numbers the plan view computes, handed to
     * the other camera instead of to the depth pass.
     *
     * <p>Two things are added rather than reused. A decoration is stood on the
     * <em>top of its column</em> rather than on the world's floor, because a
     * view from inside the world can see the difference between a tree on a
     * plateau and a tree sunk into one; and it gets the same patch of ground
     * shadow an actor does, for the same reason — a billboard with nothing
     * underneath it is a sticker hanging in the air.
     *
     * <p>The two layers are still separate calls, and both are queued into the
     * same painter: "behind" and "in front" are a flat picture's way of saying
     * what depth says by itself once there is an eye, so a foreground tree is
     * simply a tree that happens to be nearer.
     */
    public static void drawSolid(DrawTarget target, Level level, Camera camera,
                                 SolidPainter solid, boolean foreground,
                                 double animClock) {
        int ts = Math.max(1, level.tileSize);
        // Bounded by the eye's own reach rather than by the flat camera's
        // viewport rectangle, which is a different region of the world
        // entirely. Done inside collect rather than here, so a wood of two
        // thousand trees costs a subtraction each for the ones out of range
        // instead of a projection and a sprite lookup.
        for (Placed p : collect(level, camera, foreground, animClock, false, solid)) {
            int col = (int) Math.floor(p.worldX() / ts);
            int row = (int) Math.floor(p.worldY() / ts);
            double z = level.verticality()
                    ? level.surfaceZ(Math.max(1, level.stackHeight(col, row))) : 0;
            solid.groundShadow(p.worldX(), p.worldY(), z, ts * 0.3);
            solid.billboard(p.worldX(), p.worldY(), z, p.pivotX(), p.pivotY(), camera.zoom,
                    () -> target.drawImage(p.sprite(), p.x(), p.y(), p.size(), p.size()));
        }
    }


    /**
     * The decorations of one layer, projected and ready to draw.
     *
     * @param cull whether to drop anything off the flat camera's viewport —
     *             true for the plan view, whose screen rectangle is exactly
     *             what is visible, and false for a solid view, where the flat
     *             camera's projection is only supplying a pivot and a scale
     * @param reach the eye to bound against instead, or {@code null}
     */
    private static List<Placed> collect(Level level, Camera camera,
                                        boolean foreground, double animClock,
                                        boolean cull, SolidPainter reach) {
        List<Placed> batch = new ArrayList<>();
        if (level.entities.isEmpty()) return batch;
        String kind = kindFor(foreground);
        DecorRegistry registry = DecorRegistry.standard();
        int[] anchor = new int[2];
        for (Level.EntitySpawn e : level.entities) {
            if (!kind.equals(e.kind)) continue;
            Decor def = registry.get(e.type);
            if (def == null) continue;
            if (reach != null && !reach.inRange(e.x, e.y)) continue;
            int size = Math.max(8, (int) Math.round(
                    def.sizeTiles() * level.tileSize * camera.zoom));
            camera.worldToScreen(e.x, e.y, anchor);
            int x = anchor[0] - size / 2, y = anchor[1] - size;
            if (cull && (x + size < 0 || x > camera.viewportWidth
                    || y + size < 0 || y > camera.viewportHeight)) continue;
            BufferedImage sprite = Skins.frame("decor/" + e.type, animClock);
            if (sprite == null) sprite = EntitySprites.decor(def, SPRITE_PX);
            batch.add(new Placed(sprite, x, y, size,
                    TerrainPainter.standingDepth(camera, level.tileSize, e.x, e.y),
                    TerrainPainter.pointDepth(camera, e.x, e.y),
                    e.x, e.y, anchor[0], anchor[1]));
        }
        return batch;
    }

    /**
     * One projected decoration. A tree stands on the floor like an actor does,
     * so it is sorted the same way: {@code tile} is the cell it is planted in
     * and decides the order, {@code depth} is where its trunk meets the ground
     * and settles ties with whoever else is on that cell — which is what makes
     * a player pass behind a tree and then in front of it.
     */
    private record Placed(BufferedImage sprite, int x, int y, int size,
                          int tile, int depth,
                          double worldX, double worldY, int pivotX, int pivotY) {}
}
