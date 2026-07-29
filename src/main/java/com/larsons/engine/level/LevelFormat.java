package com.larsons.engine.level;

import com.larsons.engine.graphics.Perspective;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

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
 *   <li><b>Which way is up</b> — the format's
 *       {@code com.larsons.engine.sim.PerspectiveSpace}, loaded with the
 *       level. Gravity does not switch off on a plane, it <em>turns</em>: the
 *       world grid becomes the floor and height moves onto an elevation axis,
 *       which is what jumps hop along, what meteors fall down, and what an
 *       ember rises through. Effects ask the space which axis carries their
 *       vertical component instead of replaying a side-scroller's screen.</li>
 *   <li><b>Layers</b> — {@link #layered()} formats stack blocks two deep, and
 *       the stack is what geometry <em>means</em> there: one layer is floor to
 *       walk on, two layers is a wall, and bare ground is a hole you cannot
 *       cross. A side-scroller has one layer and reads solidity off the block
 *       itself, exactly as it always did.</li>
 *   <li><b>Starter canvas &amp; generator</b> — {@link #starterLevel} floors a
 *       side-scroller and gives a plan-view map a walkable floor inside a
 *       stacked wall border, and {@link #defaultsToMaze()} picks the generator
 *       the format expects.</li>
 * </ul>
 */
public enum LevelFormat {

    SIDE_SCROLLER(Perspective.SIDE_SCROLL, "side_scroller", "Side-Scroller",
            "Gravity world seen from the side — run, jump and climb platforms."),
    TOP_DOWN(Perspective.TOP_DOWN, "top_down", "Top-Down",
            "Plan view with no gravity — one layer of blocks is floor, two is a wall."),
    ISOMETRIC(Perspective.ISOMETRIC, "isometric", "Isometric",
            "The plan-view world projected into a diamond grid, stacked two deep.");

    /**
     * The floor a fresh plan-view canvas is laid with, and what legacy plan-view
     * levels get under their lifted walls, in order of preference.
     */
    private static final String[] FLOOR_BLOCKS = {"stone_path", "grass", "dirt", "stone"};

    /** The wall a fresh plan-view canvas is bordered with, in order of preference. */
    private static final String[] WALL_BLOCKS = {"stone_wall", "stone", "cobblestone"};

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

    /**
     * Whether blocks stack two deep here — true for the plan views, where the
     * screen is the floor and a block's <em>height</em> is the only thing that
     * can read as a wall. A side view already draws its walls edge-on, so it
     * has one layer and no use for a second.
     */
    public boolean layered() { return planar(); }

    /** Whether the Generate dialog defaults to the maze generator. */
    public boolean defaultsToMaze() { return planar(); }

    /** The floor block a fresh (or migrated) plan-view canvas is laid with. */
    public static Block floorBlock(BlockRegistry blocks) {
        return firstOf(blocks, FLOOR_BLOCKS);
    }

    /** The wall block a fresh plan-view canvas is bordered with. */
    public static Block wallBlock(BlockRegistry blocks) {
        return firstOf(blocks, WALL_BLOCKS);
    }

    /** The first of {@code keys} this registry still holds, or {@code null}. */
    private static Block firstOf(BlockRegistry blocks, String[] keys) {
        if (blocks == null) return null;
        for (String key : keys) {
            Block b = blocks.get(key);
            if (b != null) return b;
        }
        return null;
    }

    /**
     * A fresh canvas in this format. The side-scroller gets a ground floor to
     * stand on (gravity needs somewhere to land); the plan-view formats get a
     * floor everywhere — bare ground is a hole there, not open air — inside a
     * border of stacked wall, which is what reads as the edge of the world
     * when there is no "down" to fall off. Giant canvases only dress the first
     * 2048 rows / columns eagerly and lay the rest of their floor with a
     * generator, because materializing every chunk of a 65536-wide map up
     * front would defeat chunked storage.
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
            Block floor = floorBlock(lvl.blocks);
            Block wall = wallBlock(lvl.blocks);
            int floorId = floor != null ? floor.id() : 0;
            int wallId = wall != null ? wall.id() : floorId;
            lvl.fillFloor(floorId);
            int bw = Math.min(lvl.width, 2048), bh = Math.min(lvl.height, 2048);
            for (int c = 0; c < bw; c++) {
                lvl.stackTile(c, 0, wallId);
                lvl.stackTile(c, bh - 1, wallId);
            }
            for (int r = 0; r < bh; r++) {
                lvl.stackTile(0, r, wallId);
                lvl.stackTile(bw - 1, r, wallId);
            }
            lvl.spawnX = lvl.tileSize * 2;
            lvl.spawnY = lvl.tileSize * 2;
        }
        return lvl;
    }

    @Override
    public String toString() { return displayName; }
}
