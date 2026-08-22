package com.larsons.engine.world;

import java.awt.Color;

/**
 * One block definition: the engine's unit of paintable/collidable terrain.
 *
 * <p>Ported from the Side-Scroller engine's {@code BlockType} enum +
 * {@code BlockAttributes} registry, merged into a single immutable,
 * <em>data-driven</em> record so games add blocks by registering data instead
 * of editing an enum (see {@link BlockRegistry}). Textures are procedural
 * colours here (the engine ships no image assets); a game can render richer
 * art for a block id without the simulation caring — and any block can be
 * reskinned with a sprite sheet via {@link com.larsons.engine.graphics.Skins}
 * (texture key {@code block/<key>}).
 *
 * <p>Light-emitting entities from the side-scroller's creative palette
 * (torch, campfire, lantern, crystal…) are modelled as non-solid blocks with a
 * {@link #lightRadius} — painting light means painting a block, and the
 * lighting shader pass picks it up automatically (and it replicates online
 * like any other block edit).
 *
 * <p>Liquids (water, lava, acid) are non-solid blocks with {@link #liquid}
 * set; {@link LiquidSim} makes them flow. Each liquid has a hidden
 * {@code <key>_flow} twin that the simulation spawns/drains — only source
 * blocks appear in the creative palette. Hazards (lava, spikes, cactus…)
 * carry {@link #damage} health per second of contact.
 *
 * @param id          stable numeric id stored in level tile grids and sent
 *                    over the wire; never reuse ids
 * @param key         stable string key ("grass"), used in JSON
 * @param displayName human-readable palette name
 * @param color       base render colour (procedural texture; translucent
 *                    colours render translucent, which liquids/glass use)
 * @param solid       blocks movement (feeds {@code PlayerPhysics}/mob physics)
 * @param lightRadius light emission radius in world tiles; 0 = not a light
 * @param lightColor  tint of emitted light (ignored when {@code lightRadius==0})
 * @param drops       item key dropped when mined, or {@code null} for none
 * @param liquid      simulated by {@link LiquidSim}: flows, swimmable
 * @param damage      health per second drained from players/mobs touching it
 * @param hardness    seconds of bare-handed mining to break it (durability);
 *                    {@code 0} breaks instantly, higher is tougher — matching
 *                    tools ({@link #tool}) divide the time by their power
 * @param tool        the tool class that mines this block fast ("pickaxe",
 *                    "axe", "shovel"), or {@code null} when no tool helps
 * @param falling     obeys gravity like sand/gravel: with nothing solid under
 *                    it, the block drops a cell per simulation tick
 * @param topTexture  the block has a plan-view <em>top</em> face texture
 *                    ({@code blocks_top/&lt;key&gt;.png}); without one the top
 *                    falls back to the side-scroll sheet, then to the colour
 * @param sideTexture the block has a plan-view <em>side</em> face texture
 *                    ({@code blocks_side/&lt;key&gt;.png}) — what the raised half
 *                    of a stacked block shows to the camera
 * @param step        something a body walks <em>up</em> rather than climbs: a
 *                    stair, a ramp, a slab. On a plane an ordinary block is a
 *                    wall — you get on top of it by jumping, or you do not get
 *                    up there — because a free step of one whole block would
 *                    mean walking up the side of any tower and would delete the
 *                    one thing plan-view geometry says with height. Stairs are
 *                    the exception, and they are a property of the block rather
 *                    than a global step height, because one number is either
 *                    too small for a staircase or too large for a wall
 *                    ({@code HEIGHT_PLAN.md} W2)
 * @param shape       what a view standing inside the world builds for it — a
 *                    whole cube, or a stem with a camera-facing sprite on it.
 *                    See {@link Shape}
 */
public record Block(int id, String key, String displayName, Color color,
                    boolean solid, double lightRadius, Color lightColor,
                    String drops, boolean liquid, double damage,
                    double hardness, String tool, boolean falling,
                    boolean topTexture, boolean sideTexture, boolean step,
                    Shape shape) {

    /**
     * What a block <em>looks like</em> in a view that stands inside the world —
     * the geometry the solid painter builds for it, as opposed to the volume
     * the simulation collides with.
     *
     * <p><b>Why this exists.</b> Every block in this engine was a full cube,
     * and a great many of them are flowers. A flower drawn as a cube of flower
     * colour is bad enough seen from above; seen from inside the world it is a
     * solid block you can walk through, which is two separate faults at once —
     * it looks like a wall, and because it fills its cell it hides the faces of
     * everything around it, so walking into one lets you look straight out
     * through the terrain (see {@link #covers()}).
     */
    public enum Shape {
        /** A whole block, filling its cell. What terrain is. */
        FULL,
        /**
         * A stem standing in the middle of the cell with a sprite growing out
         * of it that turns to face the camera — a flower, a tuft of grass, a
         * shrub, a mushroom.
         *
         * <p>Both halves matter and they answer different complaints. The stem
         * is geometry: it stays where it is put, so a plant has a position in
         * the world you can walk round and it does not swim as you turn. The
         * sprite is a billboard: a flower has no thickness worth modelling, and
         * a flat picture of one facing you is what every game from Doom onward
         * has drawn instead — a cross of two quads is the usual alternative and
         * it goes edge-on to the camera twice a turn, which is exactly the
         * moment a player notices the trick.
         */
        PLANT
    }

    public Block {
        if (shape == null) shape = Shape.FULL;
        if (id <= 0) throw new IllegalArgumentException("Block ids must be > 0 (0 = empty)");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Block key required");
        if (color == null) color = Color.GRAY;
        if (lightColor == null) lightColor = Color.WHITE;
        if (lightRadius < 0) lightRadius = 0;
        if (damage < 0) damage = 0;
        if (hardness < 0) hardness = 0;
        if (tool != null && tool.isBlank()) tool = null;
    }

    /**
     * Pre-face-texture constructor shape. Blocks that predate the plan-view
     * top/side pools accept both faces, because an absent sheet costs nothing:
     * the lookup falls back to the block's one side-scroll sheet, then to its
     * procedural colour.
     */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor,
                 String drops, boolean liquid, double damage,
                 double hardness, String tool, boolean falling) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops,
                liquid, damage, hardness, tool, falling, true, true, false);
    }

    /**
     * Pre-shape constructor shape. A block that does not say otherwise fills
     * its cell, which is what every block did before {@link Shape} existed.
     */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor,
                 String drops, boolean liquid, double damage,
                 double hardness, String tool, boolean falling,
                 boolean topTexture, boolean sideTexture, boolean step) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops,
                liquid, damage, hardness, tool, falling, topTexture, sideTexture,
                step, Shape.FULL);
    }

    /**
     * Pre-step constructor shape. A block that does not say otherwise is a
     * full block: you climb onto it or you do not get up there.
     */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor,
                 String drops, boolean liquid, double damage,
                 double hardness, String tool, boolean falling,
                 boolean topTexture, boolean sideTexture) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops,
                liquid, damage, hardness, tool, falling, topTexture, sideTexture, false);
    }

    /** Pre-falling constructor shape, kept so existing registrations read the same. */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor,
                 String drops, boolean liquid, double damage,
                 double hardness, String tool) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops,
                liquid, damage, hardness, tool, false);
    }

    /** Pre-durability constructor shape: solid blocks default to 1s hardness. */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor,
                 String drops, boolean liquid, double damage) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops,
                liquid, damage, solid ? 1.0 : 0, null);
    }

    /** Pre-liquid constructor shape, kept so existing registrations read the same. */
    public Block(int id, String key, String displayName, Color color,
                 boolean solid, double lightRadius, Color lightColor, String drops) {
        this(id, key, displayName, color, solid, lightRadius, lightColor, drops, false, 0);
    }

    /** Copy with the given durability tuning ({@link BlockRegistry#tune}). */
    public Block withDurability(double hardness, String tool) {
        return new Block(id, key, displayName, color, solid, lightRadius, lightColor,
                drops, liquid, damage, hardness, tool, falling, topTexture, sideTexture,
                step, shape);
    }

    /** Copy with gravity behaviour toggled ({@link BlockRegistry#setFalling}). */
    public Block withFalling(boolean falling) {
        return new Block(id, key, displayName, color, solid, lightRadius, lightColor,
                drops, liquid, damage, hardness, tool, falling, topTexture, sideTexture,
                step, shape);
    }

    /** Copy drawn as {@code shape} — see {@link Shape}. */
    public Block withShape(Shape shape) {
        return new Block(id, key, displayName, color, solid, lightRadius, lightColor,
                drops, liquid, damage, hardness, tool, falling, topTexture, sideTexture,
                step, shape);
    }

    /**
     * Copy marked as something a body walks up rather than climbs — a stair, a
     * ramp, a slab. See the {@code step} component.
     */
    public Block withStep(boolean step) {
        return new Block(id, key, displayName, color, solid, lightRadius, lightColor,
                drops, liquid, damage, hardness, tool, falling, topTexture, sideTexture,
                step, shape);
    }

    /** Copy declaring which plan-view faces this block supplies art for. */
    public Block withFaceTextures(boolean top, boolean side) {
        return new Block(id, key, displayName, color, solid, lightRadius, lightColor,
                drops, liquid, damage, hardness, tool, falling, top, side, step, shape);
    }

    /** Convenience for plain terrain: solid, no light, drops itself. */
    public static Block terrain(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, true, 0, null, key);
    }

    /** Convenience for passable decoration (leaves, vines…). */
    public static Block passable(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, false, 0, null, null);
    }

    /**
     * Convenience for a growing thing — a flower, a tuft of grass, a shrub, a
     * mushroom: passable, and drawn as a stem with a sprite on it rather than
     * as a cube of its own colour. See {@link Shape#PLANT}.
     *
     * <p>Drops itself, unlike bare {@link #passable} scenery: a plant is
     * something a player picks, and a flower that vanishes when you touch it is
     * the kind of thing that reads as the block being broken by mistake.
     */
    public static Block plant(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, false, 0, null, key, false, 0,
                0, null, false, true, true, false, Shape.PLANT);
    }

    /** Convenience for a light source: non-solid, emits light. */
    public static Block light(int id, String key, String name, Color color,
                              double radiusTiles, Color lightColor) {
        return new Block(id, key, name, color, false, radiusTiles, lightColor, key);
    }

    /** A solid block that also emits light (glowstone, ember stone…). */
    public static Block solidLight(int id, String key, String name, Color color,
                                   double radiusTiles, Color lightColor) {
        return new Block(id, key, name, color, true, radiusTiles, lightColor, key);
    }

    /** A passable hazard draining {@code damagePerSec} on contact. */
    public static Block hazard(int id, String key, String name, Color color,
                               double damagePerSec) {
        return new Block(id, key, name, color, false, 0, null, null, false, damagePerSec);
    }

    /** A liquid source block ({@link LiquidSim} spreads it via its flow twin). */
    public static Block liquidSource(int id, String key, String name, Color color,
                                     double damagePerSec, double lightRadiusTiles,
                                     Color lightColor) {
        return new Block(id, key, name, color, false, lightRadiusTiles, lightColor,
                null, true, damagePerSec);
    }

    public boolean emitsLight() {
        return lightRadius > 0;
    }

    /**
     * Whether this block hides whatever is on the far side of it — the one
     * question a renderer asks of a <em>neighbour</em>, and the reason it is a
     * method rather than a field.
     *
     * <p>Two things disqualify a block, and the engine had bugs from both. A
     * block you can see <em>through</em> — glass, water, anything whose colour
     * carries alpha — obviously cannot hide the face behind it, and treating it
     * as if it could is a wall that disappears when a pane is put in front of
     * it. A block that does not <em>fill</em> its cell cannot either: a flower
     * is a stem and a sprite with sky all round them, and the block behind it
     * has a face you can plainly see. That second one is what let a player walk
     * into a flower and look straight out through the world — standing inside
     * the cell, every face around them was culled as "covered" by a flower.
     */
    public boolean covers() {
        return shape == Shape.FULL && color.getAlpha() >= 255;
    }

    /** Whether this block is drawn as a stem with a billboard on it. */
    public boolean plant() {
        return shape == Shape.PLANT;
    }

    /**
     * This block's one texture key — the sheet a side-scrolling level draws it
     * with, and the fallback both plan-view faces resolve to.
     */
    public String textureKey() {
        return "block/" + key;
    }

    /**
     * The plan-view <em>top</em> face's texture key — what top-down and
     * isometric levels see on the floor and on the lid of a stacked block.
     *
     * <p>The key exists whatever {@link #topTexture} says, because the flag is
     * a <em>declaration</em> (it drives the pack's key list and what the
     * creation form asks for), not a gate: a creator who assigns a top sheet in
     * the texture dialog gets one, and a face with no sheet of its own falls
     * back to {@link #textureKey()} at lookup time.
     */
    public String topTextureKey() {
        return "block/" + key + "/top";
    }

    /**
     * The plan-view <em>side</em> face's texture key: the face a stacked block
     * turns toward the camera, which is what gives a barrier its height. Falls
     * back to {@link #textureKey()} exactly like {@link #topTextureKey()}.
     */
    public String sideTextureKey() {
        return "block/" + key + "/side";
    }

    /** Flow twins are simulation artifacts, hidden from palettes/item catalogs. */
    public boolean isFlow() {
        return liquid && key.endsWith("_flow");
    }

    /**
     * Storage blocks (chests, barrels) carry a second inventory that saves
     * with the level ({@code Level.containers}); stand next to one and press E
     * in play/play-test to open it.
     */
    public boolean container() {
        return "chest".equals(key) || "barrel".equals(key);
    }
}
