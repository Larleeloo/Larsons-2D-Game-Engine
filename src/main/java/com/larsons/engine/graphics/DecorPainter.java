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
        for (Placed p : collect(level, camera, foreground, animClock)) {
            into.at(p.depth(), () ->
                    target.drawImage(p.sprite(), p.x(), p.y(), p.size(), p.size()));
        }
    }


    /** The visible decorations of one layer, projected and ready to draw. */
    private static List<Placed> collect(Level level, Camera camera,
                                        boolean foreground, double animClock) {
        List<Placed> batch = new ArrayList<>();
        if (level.entities.isEmpty()) return batch;
        String kind = kindFor(foreground);
        DecorRegistry registry = DecorRegistry.standard();
        int[] anchor = new int[2];
        for (Level.EntitySpawn e : level.entities) {
            if (!kind.equals(e.kind)) continue;
            Decor def = registry.get(e.type);
            if (def == null) continue;
            int size = Math.max(8, (int) Math.round(
                    def.sizeTiles() * level.tileSize * camera.zoom));
            camera.worldToScreen(e.x, e.y, anchor);
            int x = anchor[0] - size / 2, y = anchor[1] - size;
            if (x + size < 0 || x > camera.viewportWidth
                    || y + size < 0 || y > camera.viewportHeight) continue;
            BufferedImage sprite = Skins.frame("decor/" + e.type, animClock);
            if (sprite == null) sprite = EntitySprites.decor(def, SPRITE_PX);
            batch.add(new Placed(sprite, x, y, size, anchor[1]));
        }
        return batch;
    }

    /** One projected decoration; {@code depth} is where its feet landed. */
    private record Placed(BufferedImage sprite, int x, int y, int size, int depth) {}
}
