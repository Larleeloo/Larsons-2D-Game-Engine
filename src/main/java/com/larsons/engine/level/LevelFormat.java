package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The three level formats the engine builds and plays: side-scroller,
 * top-down, and isometric. A format is a level's <em>kind</em> — the camera
 * projection it is drawn through, the movement model it simulates, and the
 * palette its creative mode offers — so each one is authored in its own
 * creative mode while every format loads and plays through the same code.
 *
 * <p>The format is the level's own property (saved as {@code "format"} in the
 * level JSON, see {@link Level#toMap()}), not the game type's: one game type
 * can hold a side-scrolling dungeon, a top-down overworld, and an isometric
 * town, and walking through a door from one into another switches formats
 * mid-play without a reload.
 *
 * <p>What actually differs between the formats:
 * <ul>
 *   <li><b>Projection</b> — {@link #perspective()} drives {@code Camera}:
 *       orthographic for side-scroll/top-down, a diamond for isometric.</li>
 *   <li><b>Gravity</b> — {@link #gravity()} is true only for the
 *       side-scroller. The {@link #planar()} formats move on a plane, so
 *       players/mobs/vehicles steer both axes, drops scatter instead of
 *       falling, liquids pool outward instead of pouring down, and
 *       sand/gravel stay put.</li>
 *   <li><b>Palette</b> — the path and wall block families only exist in the
 *       plan-view formats ({@link #allowsBlock}); a side-scroller's creative
 *       palette never offers them.</li>
 *   <li><b>Starter canvas &amp; generator</b> — {@link #starterLevel} floors a
 *       side-scroller and walls a plan-view map, and
 *       {@link #defaultsToMaze()} picks the generator the format expects.</li>
 * </ul>
 */
public enum LevelFormat {

    SIDE_SCROLLER(Perspective.SIDE_SCROLL, "side_scroller", "Side-Scroller",
            "Gravity world seen from the side — run, jump and climb platforms."),
    TOP_DOWN(Perspective.TOP_DOWN, "top_down", "Top-Down",
            "Plan view with no gravity — walk the whole plane through paths and walls."),
    ISOMETRIC(Perspective.ISOMETRIC, "isometric", "Isometric",
            "The plan-view world projected into a diamond grid.");

    /**
     * Block families that only exist in the plan-view formats: floor markings
     * to walk along and walls to be stopped by. They read as level geometry
     * seen from above, so the side-scroller's creative palette leaves them
     * out (a level that already contains them still renders and collides with
     * them — hiding them is a palette rule, not a tile rule).
     */
    private static final Set<String> PLAN_VIEW_BLOCKS = Set.of(
            "stone_path", "gravel_path", "wood_path",
            "stone_wall", "brick_wall", "hedge_wall");

    private final Perspective perspective;
    private final String id;
    private final String displayName;
    private final String description;

    LevelFormat(Perspective perspective, String id, String displayName, String description) {
        this.perspective = perspective;
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    /** The camera projection this format is drawn through. */
    public Perspective perspective() { return perspective; }

    /** Stable id written to level files ({@code "format"}). */
    public String id() { return id; }

    /** Human-readable name for menus ("Top-Down"). */
    public String displayName() { return displayName; }

    /** One-line explanation for menus and creative-mode headers. */
    public String description() { return description; }

    /** The format a camera perspective belongs to. */
    public static LevelFormat of(Perspective perspective) {
        if (perspective == null) return SIDE_SCROLLER;
        for (LevelFormat f : values()) {
            if (f.perspective == perspective) return f;
        }
        return SIDE_SCROLLER;
    }

    /**
     * Parse a saved format, accepting both this enum's ids/names and the
     * {@link Perspective} names older level files wrote. Unknown text falls
     * back to {@code def}, so a level from a future version still loads.
     */
    public static LevelFormat of(String text, LevelFormat def) {
        if (text == null || text.isBlank()) return def;
        String s = text.trim().toUpperCase();
        for (LevelFormat f : values()) {
            if (f.name().equals(s) || f.id.toUpperCase().equals(s)
                    || f.perspective.name().equals(s)) {
                return f;
            }
        }
        return def;
    }

    /** True when the format simulates gravity (the side-scroller only). */
    public boolean gravity() { return this == SIDE_SCROLLER; }

    /** True for the plan-view formats (top-down, isometric): movement on a plane. */
    public boolean planar() { return !gravity(); }

    /** Whether this format's palette offers the path and wall block families. */
    public boolean usesPathsAndWalls() { return planar(); }

    /** Whether the Generate dialog defaults to the maze generator. */
    public boolean defaultsToMaze() { return planar(); }

    /** Whether a block key belongs in this format's creative palette. */
    public boolean allowsBlock(String key) {
        if (key == null) return false;
        return usesPathsAndWalls() || !PLAN_VIEW_BLOCKS.contains(key);
    }

    /** The block keys that only the plan-view formats paint with. */
    public static Set<String> planViewBlocks() {
        return new LinkedHashSet<>(PLAN_VIEW_BLOCKS);
    }

    /**
     * A fresh canvas in this format. The side-scroller gets a ground floor to
     * stand on (gravity needs somewhere to land); the plan-view formats get a
     * wall border, which is what reads as the edge of the world when there is
     * no "down" to fall off. Giant canvases only dress the first 2048 rows /
     * columns eagerly — materializing every chunk of a 65536-wide map up front
     * would defeat chunked storage.
     */
    public Level starterLevel(String name, int widthTiles, int heightTiles, int tileSize) {
        Level lvl = Level.empty(name, widthTiles, heightTiles, tileSize);
        lvl.setFormat(this);
        if (gravity()) {
            int dirt = lvl.blocks.get("dirt").id();
            int grass = lvl.blocks.get("grass").id();
            int floored = Math.min(lvl.width, 2048);
            for (int c = 0; c < floored; c++) {
                lvl.setTile(c, lvl.height - 1, dirt);
                lvl.setTile(c, lvl.height - 2, grass);
            }
            lvl.spawnX = lvl.tileSize * 3;
            lvl.spawnY = (lvl.height - 4) * (double) lvl.tileSize;
        } else {
            int wall = lvl.blocks.get("stone_wall").id();
            int bw = Math.min(lvl.width, 2048), bh = Math.min(lvl.height, 2048);
            for (int c = 0; c < bw; c++) {
                lvl.setTile(c, 0, wall);
                lvl.setTile(c, bh - 1, wall);
            }
            for (int r = 0; r < bh; r++) {
                lvl.setTile(0, r, wall);
                lvl.setTile(bw - 1, r, wall);
            }
            lvl.spawnX = lvl.tileSize * 2;
            lvl.spawnY = lvl.tileSize * 2;
        }
        return lvl;
    }

    @Override
    public String toString() { return displayName; }
}
