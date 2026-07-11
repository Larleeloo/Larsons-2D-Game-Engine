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
