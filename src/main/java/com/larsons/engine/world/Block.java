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
 * art for a block id without the simulation caring.
 *
 * <p>Light-emitting entities from the side-scroller's creative palette
 * (torch, campfire, lantern, crystal…) are modelled as non-solid blocks with a
 * {@link #lightRadius} — painting light means painting a block, and the
 * lighting shader pass picks it up automatically (and it replicates online
 * like any other block edit).
 *
 * @param id          stable numeric id stored in level tile grids and sent
 *                    over the wire; never reuse ids
 * @param key         stable string key ("grass"), used in JSON
 * @param displayName human-readable palette name
 * @param color       base render colour (procedural texture)
 * @param solid       blocks movement (feeds {@code PlayerPhysics}/mob physics)
 * @param lightRadius light emission radius in world tiles; 0 = not a light
 * @param lightColor  tint of emitted light (ignored when {@code lightRadius==0})
 * @param drops       item key dropped when mined, or {@code null} for none
 */
public record Block(int id, String key, String displayName, Color color,
                    boolean solid, double lightRadius, Color lightColor,
                    String drops) {

    public Block {
        if (id <= 0) throw new IllegalArgumentException("Block ids must be > 0 (0 = empty)");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Block key required");
        if (color == null) color = Color.GRAY;
        if (lightColor == null) lightColor = Color.WHITE;
        if (lightRadius < 0) lightRadius = 0;
    }

    /** Convenience for plain terrain: solid, no light, drops itself. */
    public static Block terrain(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, true, 0, null, key);
    }

    /** Convenience for passable decoration (leaves, water, vines…). */
    public static Block passable(int id, String key, String name, Color color) {
        return new Block(id, key, name, color, false, 0, null, null);
    }

    /** Convenience for a light source: non-solid, emits light. */
    public static Block light(int id, String key, String name, Color color,
                              double radiusTiles, Color lightColor) {
        return new Block(id, key, name, color, false, radiusTiles, lightColor, key);
    }

    public boolean emitsLight() {
        return lightRadius > 0;
    }
}
