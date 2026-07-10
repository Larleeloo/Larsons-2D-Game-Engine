package com.larsons.engine.world;

import java.awt.Color;

/**
 * One decoration definition: purely visual scenery (trees, rocks, bushes…)
 * painted from the creative palette into either the <b>background</b> layer
 * (behind the terrain) or the <b>foreground</b> layer (in front of players).
 * Decorations never collide, never simulate — they exist to make levels read
 * as places. Instances live in the level's entity list as spawns of kind
 * {@code "decor_bg"} / {@code "decor_fg"}, anchored at the sprite's
 * bottom-centre so painting on a floor line "plants" them.
 *
 * <p>Like blocks and mobs, art is procedural ({@code EntitySprites.decor})
 * and reskinnable via texture key {@code decor/<key>}.
 *
 * @param key       stable string key stored in level JSON ("oak_tree")
 * @param name      palette display name
 * @param shape     which procedural painter draws it
 * @param primary   main colour (canopy, rock body…)
 * @param secondary accent colour (trunk, moss, glow…)
 * @param sizeTiles rendered height in world tiles
 */
public record Decor(String key, String name, Shape shape,
                    Color primary, Color secondary, double sizeTiles) {

    /** The procedural silhouettes decorations can take. */
    public enum Shape { TREE, PINE, DEAD_TREE, ROCK, STONES, BUSH, MUSHROOM,
        CACTUS, STALAGMITE, CRYSTAL, LOG }

    public Decor {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Decor key required");
        if (primary == null) primary = Color.GRAY;
        if (secondary == null) secondary = primary.darker();
        if (sizeTiles <= 0) sizeTiles = 1;
    }
}
