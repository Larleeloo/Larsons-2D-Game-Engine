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
 */
public record Block(int id, String key, String displayName, Color color,
                    boolean solid, double lightRadius, Color lightColor,
                    String drops, boolean liquid, double damage,
                    double hardness, String tool) {

    public Block {
        if (id <= 0) throw new IllegalArgumentException("Block ids must be > 0 (0 = empty)");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Block key required");
        if (color == null) color = Color.GRAY;
        if (lightColor == null) lightColor = Color.WHITE;
        if (lightRadius < 0) lightRadius = 0;
        if (damage < 0) damage = 0;
        if (hardness < 0) hardness = 0;
        if (tool != null && tool.isBlank()) tool = null;
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
                drops, liquid, damage, hardness, tool);
    }

    /** Convenience for plain terrain: solid, no light, drops itself. */
    public static Block terrain(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, true, 0, null, key);
    }

    /** Convenience for passable decoration (leaves, vines, flowers…). */
    public static Block passable(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, false, 0, null, null);
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

    /** Flow twins are simulation artifacts, hidden from palettes/item catalogs. */
    public boolean isFlow() {
        return liquid && key.endsWith("_flow");
    }
}
