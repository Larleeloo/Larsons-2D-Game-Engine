package com.larsons.engine.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Paint-brush footprints for the creative editor: a shape + size expands one
 * cursor cell into the set of cells a stroke paints or erases, so a single
 * drag can lay down many blocks of the palette's selected type at once.
 *
 * <p>Shapes are deterministic except {@link Shape#SPRAY}, which scatters a
 * random ~40% of the square using an RNG seeded per cell — so a spray stroke
 * is stable while hovering one spot (the preview doesn't flicker) but varies
 * cell to cell.
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
     * These are the three that earn their place, in order of how much they
     * change what a creator can make ({@code HEIGHT_PLAN.md} E3).
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

    /**
     * Apply a height verb to the cells of a stamp, using {@code fill} for any
     * block it has to invent. Returns whether anything changed.
     *
     * <p>Reads the whole footprint before writing any of it, because
     * {@link Height#SMOOTH} averages against neighbours and a pass that wrote
     * as it went would smooth against cells it had already smoothed — which
     * turns a symmetric brush into one that drags terrain in whatever order the
     * cells happened to come in.
     */
    public static boolean applyHeight(Level level, Height verb, List<int[]> cells,
                                      int centreCol, int centreRow, int fill) {
        if (verb == null || verb == Height.PAINT || cells.isEmpty()) return false;
        int[] want = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            int[] cell = cells.get(i);
            want[i] = targetHeight(level, verb, cell[0], cell[1], centreCol, centreRow);
        }
        boolean changed = false;
        for (int i = 0; i < cells.size(); i++) {
            changed |= setHeight(level, cells.get(i)[0], cells.get(i)[1], want[i], fill);
        }
        return changed;
    }

    private static int targetHeight(Level level, Height verb, int col, int row,
                                    int centreCol, int centreRow) {
        int here = level.stackHeight(col, row);
        return switch (verb) {
            case RAISE -> here + 1;
            case LOWER -> here - 1;
            case FLATTEN -> level.stackHeight(centreCol, centreRow);
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

    /**
     * Make the column at (col,row) exactly {@code target} layers deep, filling
     * anything new with {@code fill}. A column is never taken below its floor:
     * lowering stops at one layer rather than punching a hole, because a hole
     * is somewhere nobody can walk and a lower brush is a sculpting tool, not
     * a delete key.
     */
    private static boolean setHeight(Level level, int col, int row, int target, int fill) {
        if (col < 0 || row < 0 || col >= level.width || row >= level.height) return false;
        target = Math.max(1, Math.min(level.layerLimit(), target));
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
                changed |= level.setTile(col, row, layer, material);
            }
            if (here == 0) changed |= level.setTile(col, row, 0, material);
        } else {
            for (int layer = here - 1; layer >= target; layer--) {
                changed |= level.setTile(col, row, layer, 0);
            }
        }
        return changed;
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
