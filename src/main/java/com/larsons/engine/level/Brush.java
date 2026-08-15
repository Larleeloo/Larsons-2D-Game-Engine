package com.larsons.engine.level;

import com.larsons.engine.world.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What a stroke of the creative editor's brush does — the three questions a
 * click has to answer before anything is written.
 *
 * <p><b>Which cells.</b> A shape + size expands one cursor cell into the set of
 * cells a stroke covers ({@link #cells}), so a single drag can lay down many
 * blocks at once. Shapes are deterministic except {@link Shape#SPRAY}, which
 * scatters a random ~40% of the square using an RNG seeded per cell — so a
 * spray stroke is stable while hovering one spot (the preview doesn't flicker)
 * but varies cell to cell.
 *
 * <p><b>Which layers, in each cell.</b> {@link #place} — where a painted block
 * lands in a column, on top of what is there or at the height the editor is
 * locked to.
 *
 * <p><b>Or what happens to the column's height instead.</b> {@link Height} and
 * {@link #applyHeight}: raise, lower, flatten and smooth, the verbs that make a
 * landscape rather than a tower ({@code HEIGHT_PLAN.md} E3).
 */
public final class Brush {

    /** The footprint a stroke stamps around the cursor cell. */
    public enum Shape { SQUARE, CIRCLE, DIAMOND, HLINE, VLINE, SPRAY }

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 12;

    private Brush() {}

    /**
     * What a stroke does to the <em>height</em> of the cells it covers, as
     * opposed to which block it paints in them.
     *
     * <p><b>Placing blocks one at a time builds a tower. It does not build a
     * landscape</b>, and a landscape is the thing the height axis is for — so
     * the editor needs verbs that act on a column rather than on a cell.
     * These are the four that earn their place, in order of how much they
     * change what a creator can make ({@code HEIGHT_PLAN.md} E3), beside the
     * one that leaves the height alone and paints.
     */
    public enum Height {
        /** Leave height alone; paint the selected block as always. */
        PAINT,
        /** Add a layer to every column under the brush. */
        RAISE,
        /** Take a layer off every column under the brush. */
        LOWER,
        /** Set every column under the brush to the height of the one at its centre. */
        FLATTEN,
        /** Average each column against its neighbours — turns steps into a hill. */
        SMOOTH
    }

    /** Cycle to the next height verb (the editor's tool button). */
    public static Height next(Height h) {
        Height[] all = Height.values();
        return all[(h.ordinal() + 1) % all.length];
    }

    /** Short label for the editor UI. */
    public static String label(Height h) {
        return switch (h) {
            case PAINT -> "Stack";
            case RAISE -> "Raise";
            case LOWER -> "Lower";
            case FLATTEN -> "Flatten";
            case SMOOTH -> "Smooth";
        };
    }

    /** One line saying what a verb does, for the tool window and the tooltip. */
    public static String describe(Height h) {
        return switch (h) {
            case PAINT -> "Places the selected block against the face you point at:"
                    + " a top face builds the column higher, a side face builds"
                    + " outward from it.";
            case RAISE -> "Adds a layer to every column under the brush, keeping the"
                    + " material each column is already made of.";
            case LOWER -> "Takes a layer off every column under the brush, stopping at"
                    + " the floor rather than punching a hole.";
            case FLATTEN -> "Sets every column under the brush to one height — the"
                    + " build height when it is locked, otherwise the height of the"
                    + " column under the cursor.";
            case SMOOTH -> "Averages each column against its neighbours, which turns"
                    + " the staircase a raise brush leaves into a hill.";
        };
    }

    /**
     * Where one layer of one cell is written — the editor's own
     * {@code setTile}, so a sculpting stroke joins the undo history and reaches
     * a server exactly as a painted block does.
     *
     * <p>Taken as a parameter rather than assumed, because these verbs are the
     * first thing in the engine that writes <em>columns</em> rather than cells:
     * writing straight to the level would leave the whole landscape outside
     * both the undo step and the multiplayer wire, and neither is something to
     * discover after the terrain is built.
     */
    @FunctionalInterface
    public interface LayerWriter {
        /** Write {@code id} into one layer; {@code true} when it changed something. */
        boolean set(int col, int row, int layer, int id);
    }

    /**
     * Apply a height verb to the cells of a stamp, using {@code fill} for any
     * block it has to invent. Returns whether anything changed.
     */
    public static boolean applyHeight(Level level, Height verb, List<int[]> cells,
                                      int centreCol, int centreRow, int fill) {
        return applyHeight(level, verb, cells, centreCol, centreRow, fill, -1, level::setTile);
    }

    /**
     * Apply a height verb, writing through {@code writer} and aiming
     * {@link Height#FLATTEN} at {@code targetLayer} when one is locked
     * ({@code -1} = follow the cursor's own column).
     *
     * <p>Reads the whole footprint before writing any of it, because
     * {@link Height#SMOOTH} averages against neighbours and a pass that wrote
     * as it went would smooth against cells it had already smoothed — which
     * turns a symmetric brush into one that drags terrain in whatever order the
     * cells happened to come in.
     */
    public static boolean applyHeight(Level level, Height verb, List<int[]> cells,
                                      int centreCol, int centreRow, int fill,
                                      int targetLayer, LayerWriter writer) {
        if (verb == null || verb == Height.PAINT || cells.isEmpty()) return false;
        int[] want = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            int[] cell = cells.get(i);
            want[i] = plannedHeight(level, verb, cell[0], cell[1],
                    centreCol, centreRow, targetLayer);
        }
        boolean changed = false;
        for (int i = 0; i < cells.size(); i++) {
            changed |= setHeight(level, cells.get(i)[0], cells.get(i)[1], want[i], fill, writer);
        }
        return changed;
    }

    /**
     * How deep {@code verb} would leave the column at (col,row) — in layers,
     * already clamped into what this level allows.
     *
     * <p>Public because the cursor preview draws it: a creator about to smooth
     * a hillside should see the hill they are about to get, and the only way
     * for the preview and the stroke to agree on that is for them to be the
     * same arithmetic.
     */
    public static int plannedHeight(Level level, Height verb, int col, int row,
                                    int centreCol, int centreRow, int targetLayer) {
        return clampHeight(level, targetHeight(level, verb, col, row,
                centreCol, centreRow, targetLayer));
    }

    private static int targetHeight(Level level, Height verb, int col, int row,
                                    int centreCol, int centreRow, int targetLayer) {
        int here = level.stackHeight(col, row);
        return switch (verb) {
            case RAISE -> here + 1;
            case LOWER -> here - 1;
            // A locked build height is what flatten means by "level": a plateau
            // at a height the creator chose rather than at whichever column the
            // cursor happened to be over. Unlocked it is the cursor's, which is
            // the pick-and-spread gesture every terrain editor has.
            case FLATTEN -> targetLayer >= 0 ? targetLayer + 1
                    : level.stackHeight(centreCol, centreRow);
            case SMOOTH -> {
                int sum = here, n = 1;
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int h = level.stackHeight(col + d[0], row + d[1]);
                    if (h <= 0) continue;   // a hole is not ground to average against
                    sum += h;
                    n++;
                }
                yield (int) Math.round(sum / (double) n);
            }
            default -> here;
        };
    }

    /** A column is at least a floor and at most the level's ceiling. */
    private static int clampHeight(Level level, int height) {
        return Math.max(1, Math.min(level.layerLimit(), height));
    }

    /**
     * Make the column at (col,row) exactly {@code target} layers deep, filling
     * anything new with {@code fill}. A column is never taken below its floor:
     * lowering stops at one layer rather than punching a hole, because a hole
     * is somewhere nobody can walk and a lower brush is a sculpting tool, not
     * a delete key.
     */
    private static boolean setHeight(Level level, int col, int row, int target, int fill,
                                     LayerWriter writer) {
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return false;
        target = clampHeight(level, target);
        int here = level.stackHeight(col, row);
        if (here == target) return false;
        boolean changed = false;
        if (here < target) {
            // A new column starts from whatever the floor already is, so raising
            // ground keeps the material it was made of rather than turning the
            // whole hillside into the palette's current selection.
            int material = here > 0 ? level.tileAt(col, row, here - 1) : fill;
            if (material <= 0) material = fill;
            for (int layer = Math.max(1, here); layer < target; layer++) {
                changed |= writer.set(col, row, layer, material);
            }
            if (here == 0) changed |= writer.set(col, row, 0, material);
        } else {
            for (int layer = here - 1; layer >= target; layer--) {
                changed |= writer.set(col, row, layer, 0);
            }
        }
        return changed;
    }

    // --- where a painted block lands -------------------------------------------------

    /**
     * The run of layers a painted block occupies in one cell, or a refusal
     * saying why nothing goes there.
     *
     * @param fromLayer the lowest layer written (inclusive)
     * @param toLayer   the highest layer written (inclusive)
     * @param refusal   why the cell was refused, or {@code null} when it wasn't
     */
    public record Placement(int col, int row, int fromLayer, int toLayer, String refusal) {

        /** A cell nothing can be built in, and the sentence saying why. */
        public static Placement refused(int col, int row, String why) {
            return new Placement(col, row, -1, -1, why);
        }

        public boolean refused() { return refusal != null; }

        /** How many layers this placement writes. */
        public int layers() { return refused() ? 0 : toLayer - fromLayer + 1; }
    }

    /**
     * Where a stroke's block lands in one cell — {@code HEIGHT_PLAN.md} E1 and
     * E4, as one rule.
     *
     * <p><b>Unlocked</b> ({@code targetLayer < 0}) the block goes on top of
     * whatever is there, which is what makes a column something you build by
     * clicking rather than by arming a mode first. <b>Locked</b> to a layer, it
     * goes at that height: a column short of it is built up to it — every layer
     * in between, so a placed block never floats over a gap the heightfield
     * cannot hold — and a column already taller has the block at that height
     * repainted, which is how a stripe of one material is drawn along a cliff
     * face.
     *
     * <p>The refusals are the interesting half, because they are what the
     * cursor has to be able to <em>show</em>: a column at the ceiling has
     * nowhere left to go, and a cell outside the level is not a cell.
     */
    public static Placement place(Level level, int col, int row, int targetLayer) {
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) {
            return Placement.refused(col, row, "outside the level");
        }
        // A side-scroller's grid is the picture: one layer, and painting a full
        // cell repaints it. There is no height axis here to stack along.
        if (!level.layered()) return new Placement(col, row, Level.LAYER_GROUND, 0, null);

        int ceiling = level.layerLimit();
        int height = level.stackHeight(col, row);
        if (targetLayer < 0) {
            // Covering a pool is how pools are removed, since liquids cannot be
            // mined — so a liquid on top is replaced rather than stood on.
            Block top = height > 0 ? level.blockAt(col, row, height - 1) : null;
            if (top != null && top.liquid()) {
                return new Placement(col, row, height - 1, height - 1, null);
            }
            if (height >= ceiling) {
                return Placement.refused(col, row,
                        "this column is already " + ceiling + " blocks deep — the level's ceiling");
            }
            return new Placement(col, row, height, height, null);
        }
        int target = Math.min(targetLayer, ceiling - 1);
        if (height > target) return new Placement(col, row, target, target, null);
        return new Placement(col, row, height, target, null);
    }

    /**
     * The cells a stamp covers, as {col,row} pairs centred on (col,row).
     * {@code size} is the brush diameter in tiles, clamped to
     * [{@value #MIN_SIZE}, {@value #MAX_SIZE}]; size 1 is always exactly the
     * cursor cell.
     */
    public static List<int[]> cells(Shape shape, int size, int col, int row) {
        size = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        List<int[]> out = new ArrayList<>();
        if (size == 1) {
            out.add(new int[]{col, row});
            return out;
        }
        int r0 = -(size - 1) / 2, r1 = size / 2;
        double radius = size / 2.0;
        switch (shape) {
            case SQUARE -> {
                for (int dr = r0; dr <= r1; dr++) {
                    for (int dc = r0; dc <= r1; dc++) {
                        out.add(new int[]{col + dc, row + dr});
                    }
                }
            }
            case CIRCLE -> {
                for (int dr = r0; dr <= r1; dr++) {
                    for (int dc = r0; dc <= r1; dc++) {
                        double cx = dc + (size % 2 == 0 ? 0.5 : 0);
                        double cy = dr + (size % 2 == 0 ? 0.5 : 0);
                        if (cx * cx + cy * cy <= radius * radius + 0.01) {
                            out.add(new int[]{col + dc, row + dr});
                        }
                    }
                }
            }
            case DIAMOND -> {
                for (int dr = r0; dr <= r1; dr++) {
                    for (int dc = r0; dc <= r1; dc++) {
                        if (Math.abs(dc) + Math.abs(dr) <= size / 2) {
                            out.add(new int[]{col + dc, row + dr});
                        }
                    }
                }
            }
            case HLINE -> {
                for (int dc = r0; dc <= r1; dc++) out.add(new int[]{col + dc, row});
            }
            case VLINE -> {
                for (int dr = r0; dr <= r1; dr++) out.add(new int[]{col, row + dr});
            }
            case SPRAY -> {
                for (int dr = r0; dr <= r1; dr++) {
                    for (int dc = r0; dc <= r1; dc++) {
                        long seed = (col + dc) * 0x9E3779B97F4A7C15L ^ ((long) (row + dr) << 21);
                        if (new Random(seed).nextDouble() < 0.4) {
                            out.add(new int[]{col + dc, row + dr});
                        }
                    }
                }
                if (out.isEmpty()) out.add(new int[]{col, row});
            }
        }
        return out;
    }

    /** Cycle to the next shape (the editor's shape button). */
    public static Shape next(Shape s) {
        Shape[] all = Shape.values();
        return all[(s.ordinal() + 1) % all.length];
    }

    /** Short label for the editor UI. */
    public static String label(Shape s) {
        return switch (s) {
            case SQUARE -> "Square";
            case CIRCLE -> "Circle";
            case DIAMOND -> "Diamond";
            case HLINE -> "Line —";
            case VLINE -> "Line |";
            case SPRAY -> "Spray";
        };
    }
}
