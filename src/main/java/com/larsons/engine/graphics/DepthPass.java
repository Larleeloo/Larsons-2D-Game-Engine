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
 */
public final class DepthPass {

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
     * pass is flushed.
     */
    public void at(int depth, Runnable sprite) {
        if (queue == null) {
            sprite.run();
            return;
        }
        queue.add(new Entry(depth, queue.size(), sprite));
    }

    /** Draw everything queued, back to front, and empty the queue. */
    public void flush() {
        if (queue == null || queue.isEmpty()) return;
        // Ties keep the order they arrived in, so a sprite drawn as several
        // pieces (a mob and its health bar) never comes apart.
        queue.sort(Comparator.comparingInt(Entry::depth).thenComparingInt(Entry::seq));
        for (Entry e : queue) e.sprite().run();
        queue.clear();
    }

    private record Entry(int depth, int seq, Runnable sprite) {}
}
