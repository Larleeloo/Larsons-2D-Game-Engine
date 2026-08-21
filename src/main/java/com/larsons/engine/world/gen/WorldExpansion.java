package com.larsons.engine.world.gen;

import com.larsons.engine.level.Level;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.SurfaceDecor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a level into a world: the one-time transform that runs when a creator
 * ticks <em>Infinite terrain</em> on a level that did not have it.
 *
 * <p><b>The level ends up in the middle of the world, not in a corner of it.</b>
 * The bounds grow from the level's own size to {@link TerrainSettings#worldSize}
 * on a side, and the authored blocks are placed at the centre of that — so
 * walking out of the level in <em>any</em> direction finds generated ground,
 * rather than three directions finding a wall. Paying for that is a coordinate
 * shift: everything the level holds that names a cell or a position (the spawn,
 * entity markers, doors, chest contents, surface decorations) moves by the same
 * offset, once, here.
 *
 * <p><b>And the level ends up at the top of the world, not the bottom of it.</b>
 * A level's own layer 0 is its floor; the world's layer 0 is bedrock 150 layers
 * below sea level. Importing one onto the other would bury the authored level
 * under the world it was joined to, so the authored blocks are lifted to the
 * shoreline ({@link TerrainSettings#groundLevel}) and the rock they now stand
 * on is filled in underneath them. That fill is what makes the level a plateau
 * you can dig into rather than a slab hanging in the sky.
 *
 * <p><b>The authored rectangle is chunk-aligned and the generator never enters
 * it.</b> That is what "infinite terrain generation starts at the bounds of a
 * given level" means once it is code: the world is generated everywhere except
 * the cells the creator built, and the join between the two is the level's own
 * edge.
 */
public final class WorldExpansion {

    private WorldExpansion() {}

    /**
     * Give {@code level} an endless world, moving its authored content into the
     * middle of it. Does nothing to a level that already has one.
     *
     * @param settings the level's terrain settings; its {@link TerrainSettings#seed}
     *                 is filled in from the level's name and size if it is unset,
     *                 so a world always has a seed written down
     * @return whether the level was expanded
     */
    public static boolean expand(Level level, TerrainSettings settings) {
        if (level == null || settings == null || level.isWorld()) return false;
        settings.normalize();
        if (settings.seed == 0) settings.seed = seedFor(level);

        int worldSize = settings.worldSize;
        int authoredW = alignUp(Math.min(level.width, worldSize / 2));
        int authoredH = alignUp(Math.min(level.height, worldSize / 2));
        int offsetCol = alignDown((worldSize - authoredW) / 2);
        int offsetRow = alignDown((worldSize - authoredH) / 2);

        WorldTerrain world = new WorldTerrain(settings, level.blocks, worldSize, worldSize,
                offsetCol, offsetRow, authoredW, authoredH, settings.seed);
        importTerrain(level, world, offsetCol, offsetRow, settings);
        shiftContents(level, offsetCol, offsetRow);
        level.setTerrain(world);
        return true;
    }

    /**
     * A seed for a level that has not been given one: stable for a given level,
     * different for the next one. Derived from the name and size rather than
     * from the clock, so creating the same level twice — in a test, in a
     * regenerated game package — gives the same world both times.
     */
    public static long seedFor(Level level) {
        long h = level.name == null ? 0 : level.name.hashCode();
        h = h * 0x9E3779B97F4A7C15L + level.width;
        h = h * 0xC2B2AE3D27D4EB4FL + level.height;
        h ^= h >>> 31;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return h == 0 ? 1 : h;
    }

    /**
     * Copy the authored blocks into the world, lifted to the shoreline, on rock
     * filled in beneath them.
     *
     * <p>Read through {@link Level#tileAt} and bounded per column by
     * {@link Level#columnDepth}, so this works the same for a dense level, a
     * layered one and a giant chunked one without asking which it is.
     */
    private static void importTerrain(Level level, WorldTerrain world,
                                      int offsetCol, int offsetRow,
                                      TerrainSettings settings) {
        BlockRegistry reg = level.blocks != null ? level.blocks : BlockRegistry.standard();
        int bedrock = idOf(reg, "basalt", "stone");
        int deep = idOf(reg, "deepslate", "stone");
        int stone = idOf(reg, "stone", "deepslate");
        int soil = idOf(reg, "dirt", "stone");
        int base = settings.groundLevel;
        int top = TerrainSettings.MAX_LEVEL;
        int[] cells = new int[top];

        for (int row = 0; row < level.height; row++) {
            for (int col = 0; col < level.width; col++) {
                java.util.Arrays.fill(cells, 0);
                // The rock the authored level now stands on. Strata rather than
                // one block all the way down, so digging under a hand-built
                // level finds the same world everything else is made of.
                cells[0] = bedrock;
                for (int y = 1; y < base; y++) {
                    cells[y] = y < 3 ? bedrock : y < 34 ? deep
                            : y >= base - 4 ? soil : stone;
                }
                int depth = Math.max(1, level.columnDepth(col, row));
                boolean any = false;
                for (int layer = 0; layer < depth; layer++) {
                    int id = level.tileAt(col, row, layer);
                    int y = base + layer;
                    if (y >= top) break;
                    if (id > 0) {
                        cells[y] = id;
                        any = true;
                    }
                }
                // An empty authored cell is a hole in the level, and a hole in
                // the level is a hole in the plateau — dug out rather than
                // capped, so the level reads as it was drawn.
                if (!any) cells[base] = 0;
                world.putColumn(offsetCol + col, offsetRow + row, encode(cells));
            }
        }
    }

    /** A scratch column compressed into the run pairs the world stores. */
    private static int[] encode(int[] cells) {
        int top = cells.length;
        while (top > 0 && cells[top - 1] == 0) top--;
        if (top == 0) return WorldTerrain.EMPTY_COLUMN;
        int runs = 1;
        for (int i = 1; i < top; i++) {
            if (cells[i] != cells[i - 1]) runs++;
        }
        int[] out = new int[runs * 2];
        int at = 0;
        for (int i = 1; i <= top; i++) {
            if (i == top || cells[i] != cells[i - 1]) {
                out[at++] = i;
                out[at++] = cells[i - 1];
            }
        }
        return out;
    }

    /**
     * Move everything that names a position: the spawn, entity and door
     * markers, chest contents and surface decorations. Missing one of these is
     * how a level comes back with its doors in the sea, so they are all done
     * here and nowhere else.
     */
    private static void shiftContents(Level level, int offsetCol, int offsetRow) {
        double dx = offsetCol * (double) level.tileSize;
        double dy = offsetRow * (double) level.tileSize;
        level.spawnX += dx;
        level.spawnY += dy;

        List<Level.EntitySpawn> moved = new ArrayList<>(level.entities.size());
        for (Level.EntitySpawn e : level.entities) {
            moved.add(new Level.EntitySpawn(e.kind, e.type, e.x + dx, e.y + dy));
        }
        level.entities.clear();
        level.entities.addAll(moved);

        List<SurfaceDecor.Placement> decor = new ArrayList<>(level.surfaceDecor.size());
        for (SurfaceDecor.Placement p : level.surfaceDecor) {
            decor.add(new SurfaceDecor.Placement(p.col() + offsetCol, p.row() + offsetRow,
                    p.face(), p.key(), p.foreground(), p.visibility()));
        }
        level.surfaceDecor.clear();
        level.surfaceDecor.addAll(decor);

        if (!level.containers.isEmpty()) {
            Map<Long, List<com.larsons.engine.entity.ItemStack>> shifted = new LinkedHashMap<>();
            for (Map.Entry<Long, List<com.larsons.engine.entity.ItemStack>> e
                    : level.containers.entrySet()) {
                long k = e.getKey();
                int col = (int) (k & 0xFFFFFFFFL) + offsetCol;
                int row = (int) (k >>> 32) + offsetRow;
                shifted.put(Level.cellKey(col, row), e.getValue());
            }
            level.containers.clear();
            level.containers.putAll(shifted);
        }
    }

    private static int alignUp(int v) {
        int chunk = WorldTerrain.CHUNK;
        return Math.max(chunk, ((v + chunk - 1) / chunk) * chunk);
    }

    private static int alignDown(int v) {
        int chunk = WorldTerrain.CHUNK;
        return Math.max(0, (v / chunk) * chunk);
    }

    private static int idOf(BlockRegistry r, String... keys) {
        for (String key : keys) {
            Block b = r.get(key);
            if (b != null) return b.id();
        }
        return 0;
    }
}
