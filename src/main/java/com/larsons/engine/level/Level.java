package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory representation of a loaded level (requirement #6: level loading).
 *
 * <p>A level is a grid of integer tile ids ({@code 0} = empty) plus metadata:
 * the perspective it is authored for, tile size, a colour palette used to draw
 * tiles, a spawn point, and a list of entity spawns. Games are expected to
 * extend this — add tile properties, collision flags, multiple layers, etc. —
 * but this is the minimal "essentials" model.
 */
public class Level {
    public String name = "Untitled";
    public Perspective perspective = Perspective.SIDE_SCROLL;
    public int tileSize = 32;
    public int width;          // in tiles
    public int height;         // in tiles
    public int[][] tiles;      // [row][col], 0 = empty
    public Color background = new Color(24, 28, 38);
    public Color[] palette = defaultPalette();
    public double spawnX, spawnY;   // world pixels
    public final List<EntitySpawn> entities = new ArrayList<>();

    /** Colour used to draw the given tile id, or {@code null} for empty tiles. */
    public Color colorFor(int tileId) {
        if (tileId <= 0) return null;
        if (palette == null || palette.length == 0) return Color.GRAY;
        return palette[(tileId - 1) % palette.length];
    }

    public int tileAt(int col, int row) {
        if (tiles == null || row < 0 || row >= tiles.length
                || col < 0 || col >= tiles[row].length) {
            return 0;
        }
        return tiles[row][col];
    }

    private static Color[] defaultPalette() {
        return new Color[]{
                new Color(120, 90, 60),    // 1: dirt
                new Color(90, 160, 80),    // 2: grass
                new Color(110, 110, 120),  // 3: stone
                new Color(70, 120, 200),   // 4: water
                new Color(220, 200, 120),  // 5: sand
        };
    }

    /** A request to spawn an entity, as declared by the level file. */
    public static class EntitySpawn {
        public final String type;
        public final double x, y;

        public EntitySpawn(String type, double x, double y) {
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }
}
