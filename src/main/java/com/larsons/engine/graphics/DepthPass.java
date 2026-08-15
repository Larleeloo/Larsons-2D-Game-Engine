package com.larsons.engine.graphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A painter's-algorithm queue for everything standing on a level's floor —
 * trees, block details, mobs, dropped items, mounts and the players
 * themselves. Callers {@link #at} each sprite with the screen row its feet
 * land on and {@link #flush} once; the queue draws them back to front, so
 * whatever is nearer the viewer covers whatever is farther away.
 *
 * <p><b>Why this is a queue and not a draw order.</b> A side-scroller's
 * layers are fixed: scenery is behind the terrain, the player is in front of
 * it, and no amount of walking about changes that — which is why the side
 * view uses an {@link #immediate} pass that simply draws as it is told, and
 * nothing about it changes. On a plane the layers are not fixed at all,
 * because the screen is the floor: a player walking south past a tree passes
 * <em>behind</em> it and then in front of it, and only their relative depth
 * says which. Drawing the scenery layer and then the actors, as a side view
 * does, is what left the player permanently on top of every decoration.
 *
 * <p>Depth is the projected screen row of a sprite's ground contact point,
 * which is the correct ordering in all three of the camera's projections:
 * down the screen is toward the viewer in the side view and in top-down, and
 * it is the {@code col + row} diagonal once the isometric camera has folded
 * both world axes into it.
 *
 * <p><b>Depth is two numbers, because the floor is made of tiles and the
 * actors are not.</b> The primary is the row of the <em>tile</em> a thing
 * stands on, and it is what decides the order; {@link #at(int, int, Runnable)}
 * takes a second row — where on that tile the thing actually is — which only
 * breaks ties among the things sharing a tile's depth.
 *
 * <p>One number was enough while the only question was "who is further down
 * the screen", and it stays enough in top-down, where the screen row of a
 * point depends on world y alone: a tile's own row separates the row behind it
 * from the row in front by a clear tile, and any point inside the near row
 * beats it. Isometric folds both world axes into that number, and the
 * separation closes to nothing: a tile and its diagonal neighbour sit at the
 * <em>same</em> depth, side by side on screen, and an actor standing on one of
 * them can score a pixel less than the block standing on the other. Their
 * sprites overlap — an actor's billboard is wider than the diamond it stands
 * on — so that one pixel is the difference between a player pressed against a
 * wall and a player with a fifth of themselves, and the object in their hand,
 * eaten by the block beside them.
 *
 * <p>Comparing the tiles first is what a plan view means by depth: a block
 * covers an actor when the actor's tile is behind the block's, and never
 * because of where on their own tile they happen to be standing. Ties among
 * the things on one tile still sort exactly, so a player still passes behind a
 * tree and then in front of it as they walk past its trunk.
 */
public final class DepthPass {

    /**
     * The secondary depth of the floor's own geometry — behind everything else
     * standing at the same depth.
     *
     * <p>A raised block is not <em>on</em> a tile, it <em>is</em> one, so there
     * is no "where on the tile" for it to be. Sorting it under every actor at
     * its own depth is the rule the plan views want: a wall covers you when you
     * are behind it, and an actor beside it is in front of it.
     */
    public static final int TERRAIN = Integer.MIN_VALUE;

    private final List<Entry> queue;

    private DepthPass(List<Entry> queue) {
        this.queue = queue;
    }

    /** A queue that draws back to front when flushed. */
    public static DepthPass sorted() {
        return new DepthPass(new ArrayList<>());
    }

    /** A pass-through that draws each sprite as it arrives, in call order. */
    public static DepthPass immediate() {
        return new DepthPass(null);
    }

    /**
     * The pass the actors of a level share with its scenery: sorted on a
     * plane, where depth is what decides who is in front; pass-through in a
     * side view, whose layer order is fixed and already correct.
     */
    public static DepthPass of(Perspective perspective) {
        return perspective == Perspective.SIDE_SCROLL ? immediate() : sorted();
    }

    /** Whether sprites added here wait to be ordered against each other. */
    public boolean isSorted() {
        return queue != null;
    }

    /**
     * Draw {@code sprite} at the depth its feet landed on — now, or when the
     * pass is flushed. For callers with nothing standing on a tile to
     * distinguish: the one row serves as both keys.
     */
    public void at(int depth, Runnable sprite) {
        at(depth, depth, sprite);
    }

    /**
     * Draw {@code sprite} at {@code depth} — the row of the tile its feet are
     * on — breaking ties against everything else at that depth on
     * {@code within}, the row the feet themselves landed on. Pass
     * {@link #TERRAIN} for the floor's own geometry.
     */
    public void at(int depth, int within, Runnable sprite) {
        at(depth, 0, within, sprite);
    }

    /**
     * Draw {@code sprite} at {@code depth}, {@code layers} of height above the
     * floor, breaking ties on {@code within}.
     *
     * <p><b>Height is the last key, not the second, and the difference is a
     * bug this engine already had a test for.</b> The obvious reading of "a
     * higher thing is nearer" puts height straight after the cell depth. That
     * breaks the rule C4 established and {@code StackedBlockTest} guards: in
     * isometric a tile and its <em>diagonal</em> neighbour sit at the same
     * depth, so a wall in one and an actor on the floor of the other tie on the
     * primary key — and promoting the wall on height draws it over a character
     * who is beside it rather than behind it. That is the "block ate a fifth of
     * them" defect, back again by a different route.
     *
     * <p>It is also unnecessary. {@code HEIGHT_PLAN.md} Appendix C proves that
     * for a heightfield the <em>cell depth alone</em> already orders terrain
     * against actors correctly: a nearer column can only overlap something
     * standing behind it when that column's top rises above the thing's feet on
     * screen, and when it does the column genuinely is both nearer and taller,
     * so covering it is right. Screen overlap and true occlusion are the same
     * condition. No pairwise test, no topological sort, and no depth buffer —
     * which Java2D does not have and requirement #4 says must not be needed.
     *
     * <p>So what this key actually settles is the residue the proof does not
     * reach: two things on the <em>same</em> tile at the same foot row and
     * different heights — two players sharing a tile with one of them
     * mid-hop. Last place in the sort, where it can only break ties.
     *
     * <p>The proof uses one thing only: that every column is solid from the
     * ground up, so its drawn extent starts at its own floor row. Job O lets a
     * column have a gap in it and cell depth stops being sufficient at that
     * exact moment — which is what O4 is for.
     */
    public void at(int depth, int layers, int within, Runnable sprite) {
        if (queue == null) {
            sprite.run();
            return;
        }
        queue.add(new Entry(depth, layers, within, queue.size(), sprite));
    }

    /** Draw everything queued, back to front, and empty the queue. */
    public void flush() {
        if (queue == null || queue.isEmpty()) return;
        // Ties keep the order they arrived in, so a sprite drawn as several
        // pieces (a mob and its health bar) never comes apart.
        queue.sort(Comparator.comparingInt(Entry::depth)
                .thenComparingInt(Entry::within)
                .thenComparingInt(Entry::layers)
                .thenComparingInt(Entry::seq));
        for (Entry e : queue) e.sprite().run();
        queue.clear();
    }

    private record Entry(int depth, int layers, int within, int seq, Runnable sprite) {}
}
