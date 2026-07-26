package com.larsons.engine.graphics;

import com.larsons.engine.autobattler.AnimState;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalogue behind the drop-in texture pack: every skinnable object the
 * engine knows about, and the file name a {@link TexturePack} expects for it.
 *
 * <p>A texture key ({@code block/dirt}, {@code mob/slime/idle}) maps to a
 * folder named after the palette category it is painted from and a file named
 * after the object, so a pack is filled in by name alone — no config editing:
 *
 * <pre>
 *   block/dirt        -&gt; blocks/dirt.png
 *   block/water       -&gt; liquids/water.png   (a liquid; blocks/water.png also works)
 *   mob/slime/walk    -&gt; mobs/slime_walk.png (or mobs/slime.png for every state)
 *   item/iron_sword   -&gt; items/iron_sword.png
 *   surface/moss      -&gt; block_decor/moss.png
 *   player/idle       -&gt; player/idle.png
 * </pre>
 *
 * <p>{@link #paths} lists the relative paths a key accepts, most specific
 * first; {@link #all} enumerates the whole catalogue so the pack can ship a
 * {@code TEXTURE_KEYS.txt} listing every name a creator can use — including
 * the custom blocks/mobs/items they added to their own game type, since those
 * register into the same registries.
 */
public final class TextureKeys {

    /** Folder names, one per palette category the pack can reskin. */
    public static final String BLOCKS = "blocks";
    public static final String LIQUIDS = "liquids";
    public static final String LIGHTS = "lights";
    public static final String MOBS = "mobs";
    public static final String ITEMS = "items";
    public static final String DECOR = "decor";
    public static final String BLOCK_DECOR = "block_decor";
    public static final String PLAYER = "player";
    public static final String UNITS = "units";
    public static final String PROJECTILES = "projectiles";
    public static final String BOARD = "board";
    /** Where keys from an unrecognised namespace land. */
    public static final String OTHER = "other";

    /** The mob animation states the creative texture dialog assigns. */
    public static final List<String> MOB_STATES = List.of("idle", "walk", "attack", "hurt");

    /** The auto-battler projectile kinds ({@code projectile/<kind>}). */
    public static final List<String> PROJECTILE_KINDS = List.of("arrow", "orb", "bolt");

    /** The auto-battler board parts ({@code board/<part>}). */
    public static final List<String> BOARD_PARTS = List.of("tile_a", "tile_b");

    /**
     * One catalogue row: an object, the pack folder it belongs in, the base
     * file name to give its sheet (no extension), and the animation states
     * that may be split into {@code <file>_<state>} sheets ({@code idle},
     * {@code walk}…). An empty state list means the object has a single sheet.
     */
    public record Entry(String category, String folder, String key,
                        String file, String name, List<String> states) {}

    private TextureKeys() {}

    /** Every pack folder, in the order the key list documents them. */
    public static List<String> folders() {
        return List.of(BLOCKS, LIQUIDS, LIGHTS, MOBS, ITEMS, DECOR, BLOCK_DECOR,
                PLAYER, UNITS, PROJECTILES, BOARD);
    }

    /**
     * The relative pack paths (no extension) a texture key accepts, most
     * specific first — so {@code mobs/slime_walk} wins over the catch-all
     * {@code mobs/slime}, and a block can be filed under whichever of
     * blocks/liquids/lights its palette category is.
     */
    public static List<String> paths(String key) {
        if (key == null || key.isBlank()) return List.of();
        String[] parts = key.split("/");
        String rest = join(parts, 1);          // "slime_walk" / "dirt"
        String base = parts.length > 1 ? parts[1] : "";  // "slime"
        boolean stated = parts.length > 2;
        return switch (parts[0]) {
            // Liquids and lights are blocks; accept all three palette folders.
            case "block" -> List.of(BLOCKS + "/" + rest, LIQUIDS + "/" + rest,
                    LIGHTS + "/" + rest);
            case "mob" -> stated
                    ? List.of(MOBS + "/" + rest, MOBS + "/" + base)
                    : List.of(MOBS + "/" + rest);
            case "item" -> List.of(ITEMS + "/" + rest);
            case "decor" -> List.of(DECOR + "/" + rest);
            case "surface" -> List.of(BLOCK_DECOR + "/" + rest);
            case "player" -> List.of(PLAYER + "/" + rest);
            case "unit" -> stated
                    ? List.of(UNITS + "/" + rest, UNITS + "/" + base)
                    : List.of(UNITS + "/" + rest);
            case "projectile" -> List.of(PROJECTILES + "/" + rest);
            case "board" -> List.of(BOARD + "/" + rest);
            default -> List.of(OTHER + "/" + key.replace('/', '_'));
        };
    }

    /** The file a creator should drop in for {@code key} (the preferred path). */
    public static String preferredFile(String key) {
        List<String> paths = paths(key);
        return paths.isEmpty() ? "" : paths.get(0) + ".png";
    }

    /**
     * The whole skinnable catalogue, grouped by palette category. Reads the
     * live registries, so custom content a game type registered is listed
     * alongside the built-ins.
     */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // the liquid sim's hidden flow twins
            String folder = b.liquid() ? LIQUIDS : b.emitsLight() ? LIGHTS : BLOCKS;
            String category = b.liquid() ? "Liquids" : b.emitsLight() ? "Lights" : "Blocks";
            out.add(new Entry(category, folder, "block/" + b.key(), b.key(),
                    b.displayName(), List.of()));
        }
        for (MobDef d : MobRegistry.standard().all()) {
            out.add(new Entry("Mobs", MOBS, "mob/" + d.key() + "/idle", d.key(),
                    d.displayName(), MOB_STATES));
        }
        // World items and auto-battler items share the item/<key> namespace,
        // so they share one folder; the set keeps a shared key listed once.
        Set<String> itemKeys = new LinkedHashSet<>();
        for (ItemDef d : ItemRegistry.standard().all()) {
            if (itemKeys.add(d.key())) {
                out.add(new Entry("Items", ITEMS, "item/" + d.key(), d.key(),
                        d.name(), List.of()));
            }
        }
        for (Decor d : DecorRegistry.standard().all()) {
            out.add(new Entry("Decorations", DECOR, "decor/" + d.key(), d.key(),
                    d.name(), List.of()));
        }
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            out.add(new Entry("Block decorations", BLOCK_DECOR, "surface/" + d.key(),
                    d.key(), d.name(), List.of()));
        }
        for (String state : PlayerSprites.ACTION_STATES) {
            out.add(new Entry("Player", PLAYER, PlayerSprites.stateKey(state), state,
                    "Player — " + state, List.of()));
        }
        List<String> unitStates = new ArrayList<>();
        for (AnimState s : AnimState.values()) unitStates.add(s.key());
        for (UnitDef d : AutoUnits.all()) {
            out.add(new Entry("Auto-battler units", UNITS, "unit/" + d.key + "/idle",
                    d.key, d.name, unitStates));
        }
        for (AutoItem i : AutoItems.all()) {
            if (itemKeys.add(i.key)) {
                out.add(new Entry("Items", ITEMS, "item/" + i.key, i.key, i.name, List.of()));
            }
        }
        for (String kind : PROJECTILE_KINDS) {
            out.add(new Entry("Auto-battler projectiles", PROJECTILES,
                    "projectile/" + kind, kind, kind, List.of()));
        }
        for (String part : BOARD_PARTS) {
            out.add(new Entry("Auto-battler board", BOARD, "board/" + part, part,
                    part, List.of()));
        }
        return out;
    }

    /** Join key segments from {@code from} with underscores: the file name. */
    private static String join(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('_');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
