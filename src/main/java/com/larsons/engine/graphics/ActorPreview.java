package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.sim.ActorSize;
import com.larsons.engine.world.Block;

import java.awt.Color;
import java.awt.Font;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * What an actor will actually look like in the level, drawn beside the form
 * that is deciding it.
 *
 * <p><b>Why a picture and not two numbers.</b> "Sprite 3.4 blocks, hitbox 0.6
 * blocks" is not something anyone can see. What a creator wants to know is
 * whether the character they drew reads at the size they set — against the
 * blocks they will be walking between, in the projection they will be seen
 * through, with the ground they occupy marked out under their feet. Every one
 * of those is a property of the level rather than of the number, which is why
 * this draws a scrap of level rather than a sprite on a swatch.
 *
 * <p>The scene is deliberately the awkward case: floor, a wall the actor is
 * standing against, and a one-block gap beside them. A creator sizing a
 * character can see at a glance that they are drawn in front of the wall they
 * are touching and that their footprint still fits the gap — the two things
 * sizes can break, shown rather than described.
 *
 * <p>It renders through the same {@link TerrainPainter} and {@link DepthPass}
 * the game does, at the same {@link com.larsons.engine.sim.ActorSize} the
 * simulation will use, so it cannot drift from what play looks like: a preview
 * that draws its own approximation is a preview that lies the moment either
 * side changes.
 */
public final class ActorPreview {

    /** How many blocks of level the panel frames. Enough to show a gap. */
    private static final double TILES_ACROSS = 5.0;

    private static final Font LABEL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private static final Color FRAME = new Color(70, 76, 96);
    private static final Color BACKDROP = new Color(14, 16, 24);
    private static final Color HITBOX = new Color(120, 235, 150);
    private static final Color LABEL = new Color(150, 156, 178);
    private static final Color WARN = new Color(240, 190, 90);

    private ActorPreview() {}

    /** The sprite to draw, and the two sizes it is drawn and stands at. */
    public record Actor(BufferedImage sprite, boolean mirrored, Color body,
                        double spriteTiles, double hitboxTiles) {}

    /**
     * Draw the preview into the box at (x,y).
     *
     * @param format     the level format to show it in — a character looks
     *                   different edge-on and on a plane, and a creator is
     *                   sizing them for one of them
     * @param tileSize   the game type's grid, which the sizes are blocks of
     * @param blocks     the block registry the wall is built from
     * @param label      a caption under the picture, or {@code null}
     */
    public static void draw(DrawTarget target, int x, int y, int w, int h,
                            LevelFormat format, int tileSize, Level blocks,
                            Actor actor, String label) {
        target.fillRoundRect(x, y, w, h, 10, 10, BACKDROP);
        target.drawRoundRect(x, y, w, h, 10, 10, FRAME, 1.5f);
        if (w < 60 || h < 60 || actor == null) return;

        int inset = 8;
        int viewW = w - inset * 2;
        int viewH = h - inset * 2 - (label == null ? 0 : 16);
        if (viewW < 40 || viewH < 40) return;

        Level scene = scene(format, tileSize, blocks);
        Camera cam = new Camera(scene.perspective, viewW, viewH);
        cam.tileSize = tileSize;
        // Frame a fixed number of blocks whatever the grid is, so the preview
        // reads the same in a 16-pixel game type and a 96-pixel one — but pull
        // back far enough to hold the actor whole. A creator raising the size
        // slider wants to watch the character grow against the blocks, and a
        // preview that crops their head off at three blocks is showing them
        // nothing at exactly the point they started caring.
        double across = Math.max(TILES_ACROSS, actor.spriteTiles() + 1.4);
        cam.zoom = Math.max(0.05, Math.min(viewW, viewH) / (across * tileSize));

        double footX = (CENTRE_COL + 0.5) * tileSize;
        double footY = (CENTRE_ROW + 1) * tileSize;
        // Frame the sprite rather than the floor: a billboard stands up the
        // screen from its feet, so centring the camera on where an eight-block
        // character is standing puts three-quarters of them above the panel.
        cam.centerOn(footX, footY);
        double half = ActorSize.pixels(actor.spriteTiles(), tileSize) * cam.zoom / 2;
        double[] look = lookUp(cam, footX, footY, half);
        cam.centerOn(look[0], look[1]);

        target.pushClip(x + inset, y + inset, viewW, viewH);
        target.pushTransform(AffineTransform.getTranslateInstance(x + inset, y + inset));

        DepthPass standing = DepthPass.of(scene.perspective);
        TerrainPainter.draw(target, scene, cam,
                new int[]{0, 0, scene.width - 1, scene.height - 1}, 0.5, standing, null);

        // The actor stands on the open cell south of the wall, pressed against
        // it — the position a player reaches by walking into it, and the one
        // that used to show the sort going wrong.
        double hit = ActorSize.pixels(actor.hitboxTiles(), tileSize);
        double draw = ActorSize.pixels(actor.spriteTiles(), tileSize);
        drawActor(target, cam, standing, actor, footX, footY, hit, draw);
        standing.flush();

        target.popTransform();
        target.popClip();

        if (label != null) {
            target.drawText(label, x + inset, y + h - inset,
                    LABEL_FONT, oversized(actor) ? WARN : LABEL);
        }
    }

    /** The cell the preview is built around. */
    private static final int CENTRE_COL = 4;
    private static final int CENTRE_ROW = 4;

    /**
     * The point to look at so that (x,y) sits {@code rise} screen pixels below
     * the middle of the view — "raise the camera", in a projection where that
     * is not a world axis.
     *
     * <p>Moving north is not moving up the screen once the grid is a diamond:
     * a step along world y there slides the view sideways as well, and simply
     * subtracting from y sent the isometric preview off to the right. So the
     * projection is asked what it does to each world axis and the answer is
     * solved rather than assumed — which is also what makes this hold at a
     * turned heading, where neither world axis is a screen one.
     */
    private static double[] lookUp(Camera cam, double x, double y, double rise) {
        double t = Math.max(1, cam.tileSize);
        double x0 = cam.worldToScreenX(x, y), y0 = cam.worldToScreenY(x, y);
        double ax = (cam.worldToScreenX(x + t, y) - x0) / t;
        double ay = (cam.worldToScreenX(x, y + t) - x0) / t;
        double bx = (cam.worldToScreenY(x + t, y) - y0) / t;
        double by = (cam.worldToScreenY(x, y + t) - y0) / t;
        double det = ax * by - ay * bx;
        if (Math.abs(det) < 1e-9) return new double[]{x, y};
        // The world step that moves the view up `rise` and sideways not at all.
        return new double[]{x + ay * rise / det, y - ax * rise / det};
    }

    /**
     * Whether the sprite is large enough that a creator ought to be looking at
     * it rather than at the number — not an error, just worth saying.
     */
    private static boolean oversized(Actor a) {
        return a.spriteTiles() > 3.0 || a.spriteTiles() > a.hitboxTiles() * 6;
    }

    /** The scrap of level the preview is set in: floor, a wall, and a gap. */
    private static Level scene(LevelFormat format, int tileSize, Level blocks) {
        Level lvl = Level.empty("preview", 9, 9, tileSize);
        lvl.setFormat(format);
        Block floor = pick(blocks, "grass", "stone_path", "dirt");
        Block wall = pick(blocks, "stone", "dirt", "grass");
        if (floor == null || wall == null) return lvl;
        for (int r = 0; r < lvl.height; r++) {
            for (int c = 0; c < lvl.width; c++) lvl.setTile(c, r, floor.id());
        }
        // A wall to stand against, with a one-block doorway through it, so the
        // footprint has something to be measured against as well as the sprite.
        int layer = lvl.layered() ? Level.LAYER_UPPER : Level.LAYER_GROUND;
        for (int c = 0; c < lvl.width; c++) {
            if (c == CENTRE_COL + 2) continue; // the gap
            lvl.setTile(c, CENTRE_ROW, layer, wall.id());
        }
        return lvl;
    }

    private static Block pick(Level blocks, String... keys) {
        if (blocks == null) return null;
        for (String k : keys) {
            Block b = blocks.blocks.get(k);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * The actor: its footprint outlined on the floor, then the sprite standing
     * on it — queued into the same pass the terrain is, so the preview shows
     * the real answer to "is it in front of the wall" rather than asserting it.
     */
    private static void drawActor(DrawTarget target, Camera cam, DepthPass into,
                                  Actor actor, double footX, double footY,
                                  double hit, double draw) {
        int[] corner = new int[2];
        int depth = TerrainPainter.standingDepth(cam, (int) cam.tileSize, footX, footY);
        int within = cam.worldToScreenY(footX, footY);
        into.at(depth, within, () -> {
            // The ground they cover, drawn as the quad it is in this
            // projection: a square in top-down, a diamond in isometric.
            double half = hit / 2;
            double[][] cs = {{footX - half, footY - half}, {footX + half, footY - half},
                    {footX + half, footY + half}, {footX - half, footY + half}};
            int[] xs = new int[4], ys = new int[4];
            for (int i = 0; i < 4; i++) {
                cam.worldToScreen(cs[i][0], cs[i][1], corner);
                xs[i] = corner[0];
                ys[i] = corner[1];
            }
            target.fillPolygon(xs, ys, 4, new Color(HITBOX.getRed(), HITBOX.getGreen(),
                    HITBOX.getBlue(), 55));
            target.drawPolygon(xs, ys, 4, HITBOX, 1.5f);

            if (actor.sprite() == null) return;
            int w = Math.max(2, (int) Math.round(draw * cam.zoom));
            int fx = cam.worldToScreenX(footX, footY);
            int fy = cam.worldToScreenY(footX, footY);
            if (actor.mirrored()) {
                target.drawImage(actor.sprite(), fx - w / 2 + w, fy - w, -w, w);
            } else {
                target.drawImage(actor.sprite(), fx - w / 2, fy - w, w, w);
            }
        });
    }
}
