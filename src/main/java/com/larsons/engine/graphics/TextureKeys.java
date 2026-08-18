package com.larsons.engine.graphics;

import com.larsons.engine.autobattler.AnimState;
import com.larsons.engine.autobattler.AutoItem;
import com.larsons.engine.autobattler.AutoItems;
import com.larsons.engine.autobattler.AutoUnits;
import com.larsons.engine.autobattler.UnitDef;
import com.larsons.engine.character.CharacterProfile;
import com.larsons.engine.character.Characters;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.entity.ProjectileDef;
import com.larsons.engine.entity.ProjectileRegistry;
import com.larsons.engine.fx.Particles;
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
 *   block/dirt          -&gt; blocks/dirt.png
 *   block/water         -&gt; liquids/water.png   (a liquid; blocks/water.png also works)
 *   mob/slime/walk      -&gt; mobs/slime_walk.png (or mobs/slime.png for every state)
 *   mob/slime/walk/e    -&gt; mobs/slime_walk_e.png  (walking east; falls back to the above)
 *   item/iron_sword     -&gt; items/iron_sword.png
 *   surface/moss        -&gt; block_decor/moss.png
 *   player/idle         -&gt; player/idle.png
 *   player/walk/ne      -&gt; player/walk_ne.png   (walking north-east)
 *   character/rogue/walk-&gt; player/rogue_walk.png (a character profile's sheet)
 *   particle/embers     -&gt; particles/embers.png
 *   projectile/arrow    -&gt; projectiles/arrow.png
 * </pre>
 *
 * <p>Keys nest from general to specific, and a missing sheet falls back one
 * segment at a time: {@code mob/slime/walk/e} tries {@code mobs/slime_walk_e},
 * then {@code mobs/slime_walk}, then {@code mobs/slime}. That is what lets one
 * file reskin a whole mob while a creator who wants per-direction art just
 * adds more files.
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
    /**
     * The plan-view block pool: the face a top-down or isometric level looks
     * down at. Separate from {@link #BLOCKS} because a side-scroller and a plan
     * view see different faces of the same block — one sheet cannot be both a
     * wall seen edge-on and a floor seen from above.
     */
    public static final String BLOCKS_TOP = "blocks_top";
    /**
     * The plan-view block pool's other half: the face a stacked block turns
     * toward the camera, which is what gives a barrier its height.
     */
    public static final String BLOCKS_SIDE = "blocks_side";
    public static final String MOBS = "mobs";
    public static final String ITEMS = "items";
    /**
     * Full-body sheets of a fighter <em>holding</em> one object
     * ({@code wield/iron_sword/swing}). Separate from {@link #ITEMS}, which is
     * the object itself: one folder is the sword, the other is the person
     * swinging it. See {@code com.larsons.engine.combat.MeleeSprites}.
     */
    public static final String WIELD = "wield";
    public static final String DECOR = "decor";
    public static final String BLOCK_DECOR = "block_decor";
    public static final String PLAYER = "player";
    public static final String UNITS = "units";
    public static final String PROJECTILES = "projectiles";
    public static final String PARTICLES = "particles";
    public static final String BOARD = "board";
    /** Where keys from an unrecognised namespace land. */
    public static final String OTHER = "other";

    /**
     * The mob animation states the creative texture dialog assigns: the four
     * it always had, plus the melee moves every mob can now perform
     * ({@link PlayerSprites#COMBAT_STATES}).
     */
    public static final List<String> MOB_STATES = concat(
            List.of("idle", "walk", "attack", "hurt"), PlayerSprites.COMBAT_STATES);

    /**
     * The states a held object's own sheet may be split by: its icon (the
     * fallback for everything) and one per melee move, so a creator can
     * animate the blade itself sweeping through the swing.
     */
    public static final List<String> ITEM_STATES = PlayerSprites.COMBAT_STATES;

    /**
     * The states a wielder sheet may be split by — idle (just holding the
     * thing) and one per melee move. Every one of them falls back to idle, so
     * a single {@code wield/<item>.png} is a complete answer.
     */
    public static final List<String> WIELD_STATES =
            concat(List.of("idle"), PlayerSprites.COMBAT_STATES);

    /** The auto-battler projectile kinds ({@code projectile/<kind>}). */
    public static final List<String> PROJECTILE_KINDS = List.of("arrow", "orb", "bolt");

    /** The auto-battler board parts ({@code board/<part>}). */
    public static final List<String> BOARD_PARTS = List.of("tile_a", "tile_b");

    /**
     * The eight facing suffixes a character sheet may be split by
     * ({@code player/walk/ne}). Unsupplied directions mirror or fall back to
     * the direction-agnostic sheet, so this is always optional.
     */
    public static List<String> directions() {
        List<String> keys = new ArrayList<>(Facing.values().length);
        for (Facing f : Facing.values()) keys.add(f.key());
        return keys;
    }

    /**
     * One catalogue row: an object, the pack folder it belongs in, the base
     * file name to give its sheet (no extension), the animation states that
     * may be split into {@code <file>_<state>} sheets ({@code idle},
     * {@code walk}…), and the facings each of those may be split by again
     * ({@code <file>_<state>_<dir>}). Empty lists mean the object has a
     * single sheet.
     */
    public record Entry(String category, String folder, String key,
                        String file, String name, List<String> states,
                        List<String> directions) {

        public Entry(String category, String folder, String key,
                     String file, String name, List<String> states) {
            this(category, folder, key, file, name, states, List.of());
        }
    }

    private TextureKeys() {}

    /** The plan-view block faces, as key suffix and pack folder. */
    public static final String TOP_FACE = "top";
    public static final String SIDE_FACE = "side";

    /** Every pack folder, in the order the key list documents them. */
    public static List<String> folders() {
        return List.of(BLOCKS, BLOCKS_TOP, BLOCKS_SIDE, LIQUIDS, LIGHTS, MOBS,
                ITEMS, WIELD, DECOR, BLOCK_DECOR, PLAYER, UNITS, PROJECTILES,
                PARTICLES, BOARD);
    }

    /** Two lists, end to end, as one immutable list. */
    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /**
     * The relative pack paths (no extension) a texture key accepts, most
     * specific first — so {@code mobs/slime_walk_e} wins over
     * {@code mobs/slime_walk}, which wins over the catch-all
     * {@code mobs/slime}, and a block can be filed under whichever of
     * blocks/liquids/lights its palette category is.
     */
    public static List<String> paths(String key) {
        if (key == null || key.isBlank()) return List.of();
        String[] parts = key.split("/");
        String rest = join(parts, 1, parts.length);      // "slime_walk_e" / "dirt"
        return switch (parts[0]) {
            // Liquids and lights are blocks; accept all three palette folders.
            // A plan-view face (block/dirt/top) looks in its own pool first and
            // then falls back to the block's single side-scroll sheet, so a pack
            // that supplies only blocks/dirt.png still dresses every format.
            case "block" -> blockPaths(parts);
            // Nested namespaces drop one segment at a time, so a single sheet
            // covers every state and direction until finer ones are supplied.
            case "mob" -> progressive(MOBS, parts);
            // An item's action states drop back to its plain icon
            // ({@code item/iron_sword/swing} → items/iron_sword_swing, then
            // items/iron_sword), so an un-animated item still draws in hand.
            case "item" -> progressive(ITEMS, parts);
            // The fighter holding an object, per move and per direction.
            case "wield" -> progressive(WIELD, parts);
            case "decor" -> List.of(DECOR + "/" + rest);
            case "surface" -> List.of(BLOCK_DECOR + "/" + rest);
            case "player" -> progressive(PLAYER, parts);
            // A character profile's sheets live beside the player's, prefixed
            // with the profile key: player/rogue_walk_e.png.
            case "character" -> progressive(PLAYER, parts);
            case "unit" -> progressive(UNITS, parts);
            case "projectile" -> List.of(PROJECTILES + "/" + rest);
            case "particle" -> List.of(PARTICLES + "/" + rest);
            case "board" -> List.of(BOARD + "/" + rest);
            default -> List.of(OTHER + "/" + key.replace('/', '_'));
        };
    }

    /**
     * The pack paths a block key accepts. A bare {@code block/<key>} is the
     * one sheet a side-scroller draws it with, found in whichever of the three
     * palette folders its category is. A face key ({@code block/<key>/top},
     * {@code block/<key>/side}) leads with the matching plan-view pool and then
     * falls through to that same single sheet, so supplying a face is an
     * upgrade rather than a requirement.
     */
    private static List<String> blockPaths(String[] parts) {
        String blockKey = parts[1];
        List<String> flat = List.of(BLOCKS + "/" + blockKey, LIQUIDS + "/" + blockKey,
                LIGHTS + "/" + blockKey);
        if (parts.length < 3) return flat;
        String pool = switch (parts[2]) {
            case TOP_FACE -> BLOCKS_TOP;
            case SIDE_FACE -> BLOCKS_SIDE;
            default -> null;
        };
        if (pool == null) return flat;
        List<String> out = new ArrayList<>(flat.size() + 1);
        out.add(pool + "/" + blockKey);
        out.addAll(flat);
        return out;
    }

    /**
     * {@code folder/a_b_c}, {@code folder/a_b}, {@code folder/a} — every
     * prefix of a nested key's segments, longest first.
     */
    private static List<String> progressive(String folder, String[] parts) {
        List<String> out = new ArrayList<>(Math.max(1, parts.length - 1));
        for (int end = parts.length; end > 1; end--) {
            out.add(folder + "/" + join(parts, 1, end));
        }
        return out.isEmpty() ? List.of(folder + "/" + parts[0]) : out;
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
            // The plan-view faces this block says it has. Both are optional —
            // an absent face falls back to the sheet above — so only the ones
            // a creator asked for are listed as files to draw.
            if (b.topTexture()) {
                out.add(new Entry("Block tops (3D)", BLOCKS_TOP,
                        b.topTextureKey(), b.key(), b.displayName() + " — top face",
                        List.of()));
            }
            if (b.sideTexture()) {
                out.add(new Entry("Block sides (3D)", BLOCKS_SIDE,
                        b.sideTextureKey(), b.key(), b.displayName() + " — side face",
                        List.of()));
            }
        }
        for (MobDef d : MobRegistry.standard().all()) {
            out.add(new Entry("Mobs", MOBS, "mob/" + d.key() + "/idle", d.key(),
                    d.displayName(), MOB_STATES, directions()));
        }
        // World items and auto-battler items share the item/<key> namespace,
        // so they share one folder; the set keeps a shared key listed once.
        Set<String> itemKeys = new LinkedHashSet<>();
        for (ItemDef d : ItemRegistry.standard().all()) {
            if (itemKeys.add(d.key())) {
                out.add(new Entry("Items", ITEMS, "item/" + d.key(), d.key(),
                        d.name(), ITEM_STATES));
                // …and, for the things a fighter actually fights with, the
                // full-body sheets of them doing it. Any item key resolves a
                // wield sheet — this is only which ones are worth listing.
                if (wieldable(d)) {
                    out.add(new Entry("Wielded objects", WIELD,
                            "wield/" + d.key() + "/idle", d.key(),
                            d.name() + " — wielder", WIELD_STATES, directions()));
                }
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
                    "Player — " + state, List.of(), directions()));
        }
        // The overhead pool, listed in full beside the standing one: a 3D
        // level's camera tilts past PlayerSprites.OVERHEAD_DEGREES and draws
        // from these instead, so a creator who never sees them listed is a
        // creator who cannot draw them.
        for (String state : PlayerSprites.ACTION_STATES) {
            out.add(new Entry("Player (overhead)", PLAYER,
                    PlayerSprites.topDownStateKey(state), "topdown_" + state,
                    "Player from above — " + state, List.of(), directions()));
        }
        // Character profiles: each one's sheets sit beside the player's,
        // prefixed with the profile key, and split by state and facing too —
        // in both pools, for the same reason.
        for (CharacterProfile c : Characters.all()) {
            for (String state : PlayerSprites.ACTION_STATES) {
                out.add(new Entry("Characters", PLAYER,
                        PlayerSprites.characterStateKey(c.key, state),
                        c.key + "_" + state, c.name + " — " + state,
                        List.of(), directions()));
            }
            for (String state : PlayerSprites.ACTION_STATES) {
                out.add(new Entry("Characters (overhead)", PLAYER,
                        PlayerSprites.characterStateKey(c.key, state, true),
                        c.key + "_topdown_" + state,
                        c.name + " from above — " + state,
                        List.of(), directions()));
            }
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
        // Every projectile the world can fire, then the auto-battler's three
        // generic kinds (both live in the projectile/<key> namespace).
        Set<String> shotKeys = new LinkedHashSet<>();
        for (ProjectileDef d : ProjectileRegistry.standard().all()) {
            if (shotKeys.add(d.key())) {
                out.add(new Entry("Projectiles", PROJECTILES, "projectile/" + d.key(),
                        d.key(), d.name(), List.of()));
            }
        }
        for (String kind : PROJECTILE_KINDS) {
            if (shotKeys.add(kind)) {
                out.add(new Entry("Projectiles", PROJECTILES,
                        "projectile/" + kind, kind, kind, List.of()));
            }
        }
        // Particle textures: one per motion style, so a pack can replace the
        // shards, embers and sparks the effects system throws.
        for (Particles.Style style : Particles.Style.values()) {
            String kind = Particles.textureKind(style);
            out.add(new Entry("Particles", PARTICLES, Particles.textureKey(style),
                    kind, Particles.styleName(style), List.of()));
        }
        for (String part : BOARD_PARTS) {
            out.add(new Entry("Auto-battler board", BOARD, "board/" + part, part,
                    part, List.of()));
        }
        return out;
    }

    /**
     * Whether an item is worth listing a wielder sheet for: the things a
     * fighter fights with. Every item key <em>resolves</em> one — you can
     * animate a character swinging a loaf of bread if you want to — but the
     * catalogue would be noise if it said so for all two hundred of them.
     */
    private static boolean wieldable(ItemDef d) {
        return switch (d.category()) {
            case WEAPON, RANGED_WEAPON, TOOL, ARMOR, THROWABLE -> true;
            default -> false;
        };
    }

    /** Join key segments {@code [from, to)} with underscores: the file name. */
    private static String join(String[] parts, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(to, parts.length); i++) {
            if (sb.length() > 0) sb.append('_');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
