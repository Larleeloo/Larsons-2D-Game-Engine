package com.larsons.engine.config;

import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.level.LevelStore;
import com.larsons.engine.util.Json;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the objects a creator adds through the creative editor's
 * "+&nbsp;New…" palette entries — custom blocks (including liquids and
 * lights), mobs, items, decorations, and block decor (face-attached surface
 * details) with fully customizable properties — and re-registers them into
 * the standard registries whenever the game type loads, so saved levels that
 * use them keep working across sessions.
 *
 * <p>Custom definitions live in {@code custom.json} beside the game type's
 * saved levels. Block ids are allocated from {@value #CUSTOM_BLOCK_ID_BASE}
 * upward (well clear of the built-in set, which must never be renumbered) and
 * are stored in the file so they stay stable for the tile grids that
 * reference them. Every custom block also gets its placeable block-item, and
 * custom liquids get their hidden {@code _flow} twin, mirroring what the
 * standard registries do for built-ins.
 */
public final class CustomContentStore {

    /** First id handed to custom blocks; built-ins stay far below. */
    public static final int CUSTOM_BLOCK_ID_BASE = 1000;

    /** Filename holding a game type's custom content, beside its levels. */
    public static final String FILE_NAME = "custom.json";

    private final Path file;
    private final List<Map<String, Object>> blocks = new ArrayList<>();
    private final List<Map<String, Object>> mobs = new ArrayList<>();
    private final List<Map<String, Object>> items = new ArrayList<>();
    private final List<Map<String, Object>> decor = new ArrayList<>();
    private final List<Map<String, Object>> surface = new ArrayList<>();

    public CustomContentStore(String gameTypeName) {
        this.file = new LevelStore(gameTypeName).directory().resolve(FILE_NAME);
    }

    /** Root-dir override, mirroring {@link LevelStore}'s (used by tests). */
    public CustomContentStore(String rootDir, String gameTypeName) {
        this.file = new LevelStore(rootDir, gameTypeName).directory().resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /**
     * Load {@code custom.json} (if any) and register everything it defines
     * into the standard registries. Safe to call repeatedly — already
     * registered keys are skipped, so the editor and the play scene can both
     * ensure the content is live.
     */
    public void loadAndRegister() {
        blocks.clear();
        mobs.clear();
        items.clear();
        decor.clear();
        surface.clear();
        if (!Files.exists(file)) return;
        Map<String, Object> root;
        try {
            root = Json.asObject(Json.parse(Files.readString(file)));
        } catch (IOException | RuntimeException e) {
            System.err.println("CustomContentStore: failed to read " + file + ": " + e.getMessage());
            return;
        }
        readSection(root, "blocks", blocks);
        readSection(root, "mobs", mobs);
        readSection(root, "items", items);
        readSection(root, "decor", decor);
        readSection(root, "surface", surface);
        for (Map<String, Object> m : blocks) registerBlock(blockFrom(m));
        for (Map<String, Object> m : mobs) registerMob(mobFrom(m));
        for (Map<String, Object> m : items) registerItem(itemFrom(m));
        for (Map<String, Object> m : decor) registerDecor(decorFrom(m));
        for (Map<String, Object> m : surface) registerSurface(surfaceFrom(m));
    }

    @SuppressWarnings("unchecked")
    private static void readSection(Map<String, Object> root, String key,
                                    List<Map<String, Object>> into) {
        if (root.get(key) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) into.add((Map<String, Object>) m);
            }
        }
    }

    // --- creation (called by the editor's "+ New…" forms) -------------------------

    /** A unique, registry-safe key derived from a display name. */
    public static String keyFor(String name, java.util.function.Predicate<String> taken) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase().trim().toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        String base = sb.isEmpty() ? "custom" : sb.toString();
        String key = base;
        int n = 2;
        while (taken.test(key)) key = base + "_" + n++;
        return key;
    }

    /** The next free custom block id (stable ids stored in the file). */
    public int nextBlockId() {
        int max = CUSTOM_BLOCK_ID_BASE - 1;
        for (Block b : BlockRegistry.standard().all()) {
            if (b.id() > max) max = b.id();
        }
        return Math.max(CUSTOM_BLOCK_ID_BASE, max + 1);
    }

    /**
     * Register + persist a custom block. Liquids automatically get their
     * {@code _flow} twin (id + 1); every block gets its placeable item.
     */
    public void addBlock(Block b) {
        registerBlock(b);
        blocks.add(blockToMap(b));
        save();
    }

    public void addMob(MobDef d) {
        registerMob(d);
        mobs.add(mobToMap(d));
        save();
    }

    public void addItem(ItemDef d) {
        registerItem(d);
        items.add(itemToMap(d));
        save();
    }

    public void addDecor(Decor d) {
        registerDecor(d);
        decor.add(decorToMap(d));
        save();
    }

    /** Register + persist custom block decor (a face-attached surface detail). */
    public void addSurfaceDecor(SurfaceDecor d) {
        registerSurface(d);
        surface.add(surfaceToMap(d));
        save();
    }

    // --- identification & deletion of user-created content --------------------------

    /**
     * Whether the object with this key, in this palette kind ({@code "block"},
     * {@code "mob"}, {@code "item"}, {@code "decor"}, {@code "surface"}), was
     * created by the user — the editor badges these and offers deletion.
     */
    public boolean isCustom(String kind, String key) {
        List<Map<String, Object>> section = sectionFor(kind);
        if (section == null || key == null) return false;
        for (Map<String, Object> m : section) {
            if (key.equals(m.get("key"))) return true;
        }
        return false;
    }

    /**
     * Delete a user-created object: removed from {@code custom.json} and
     * unregistered live. Levels that still reference it degrade gracefully
     * (blocks render the magenta placeholder, spawns are skipped).
     */
    public boolean remove(String kind, String key) {
        List<Map<String, Object>> section = sectionFor(kind);
        if (section == null || key == null) return false;
        boolean removed = section.removeIf(m -> key.equals(m.get("key")));
        if (!removed) return false;
        switch (kind) {
            case "mob" -> MobRegistry.standard().unregister(key);
            case "item" -> ItemRegistry.standard().unregister(key);
            case "decor" -> DecorRegistry.standard().unregister(key);
            case "surface" -> SurfaceDecorRegistry.standard().unregister(key);
            default -> {
                Block b = BlockRegistry.standard().get(key);
                BlockRegistry.standard().unregister(key);
                if (b != null && b.liquid()) {
                    BlockRegistry.standard().unregister(key + "_flow");
                }
                ItemRegistry.standard().unregister(key); // its placeable item
            }
        }
        save();
        return true;
    }

    private List<Map<String, Object>> sectionFor(String kind) {
        return switch (kind) {
            case "block" -> blocks;
            case "mob" -> mobs;
            case "item" -> items;
            case "decor" -> decor;
            case "surface" -> surface;
            default -> null;
        };
    }

    // --- registration into the shared registries ---------------------------------

    private static void registerBlock(Block b) {
        if (b == null) return;
        BlockRegistry blocks = BlockRegistry.standard();
        if (blocks.get(b.key()) == null && blocks.get(b.id()) == null) {
            blocks.register(b);
            if (b.liquid() && !b.isFlow()) {
                // The sim needs a flow twin to spread the liquid.
                Color c = b.color();
                Color flowColor = new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        Math.max(60, c.getAlpha() - 40));
                blocks.register(new Block(b.id() + 1, b.key() + "_flow",
                        b.displayName() + " (flowing)", flowColor, false,
                        Math.max(0, b.lightRadius() - 1), b.lightColor(), null,
                        true, b.damage()));
            }
        }
        // A placeable item for the block, like the standard catalog derives.
        ItemRegistry items = ItemRegistry.standard();
        if (items.get(b.key()) == null) {
            items.register(ItemDef.block(b.key(), b.displayName(), b.color(), b.key()));
        }
    }

    private static void registerMob(MobDef d) {
        if (d != null && MobRegistry.standard().get(d.key()) == null) {
            MobRegistry.standard().register(d);
        }
    }

    private static void registerItem(ItemDef d) {
        if (d != null && ItemRegistry.standard().get(d.key()) == null) {
            ItemRegistry.standard().register(d);
        }
    }

    private static void registerDecor(Decor d) {
        if (d != null && DecorRegistry.standard().get(d.key()) == null) {
            DecorRegistry.standard().register(d);
        }
    }

    private static void registerSurface(SurfaceDecor d) {
        if (d != null && SurfaceDecorRegistry.standard().get(d.key()) == null) {
            SurfaceDecorRegistry.standard().register(d);
        }
    }

    // --- persistence ---------------------------------------------------------------

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("blocks", blocks);
        root.put("mobs", mobs);
        root.put("items", items);
        root.put("decor", decor);
        root.put("surface", surface);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.stringify(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> blockToMap(Block b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("key", b.key());
        m.put("name", b.displayName());
        m.put("color", hex(b.color()));
        m.put("solid", b.solid());
        m.put("lightRadius", b.lightRadius());
        m.put("lightColor", hex(b.lightColor()));
        if (b.drops() != null) m.put("drops", b.drops());
        m.put("liquid", b.liquid());
        m.put("damage", b.damage());
        m.put("hardness", b.hardness());
        if (b.tool() != null) m.put("tool", b.tool());
        if (b.falling()) m.put("falling", true);
        // Which plan-view faces the creator said this block has, so the key
        // list keeps asking for the same sheets across sessions.
        m.put("topTexture", b.topTexture());
        m.put("sideTexture", b.sideTexture());
        return m;
    }

    private static Block blockFrom(Map<String, Object> m) {
        try {
            return new Block(
                    num(m.get("id"), 0), str(m.get("key")), str(m.get("name")),
                    color(m.get("color"), Color.GRAY),
                    Boolean.TRUE.equals(m.get("solid")),
                    dbl(m.get("lightRadius"), 0), color(m.get("lightColor"), Color.WHITE),
                    m.get("drops") instanceof String s ? s : null,
                    Boolean.TRUE.equals(m.get("liquid")), dbl(m.get("damage"), 0),
                    dbl(m.get("hardness"), 1), m.get("tool") instanceof String t ? t : null,
                    Boolean.TRUE.equals(m.get("falling")),
                    // Blocks saved before the faces were asked about accept
                    // both, which is what they already effectively did.
                    bool(m.get("topTexture"), true), bool(m.get("sideTexture"), true));
        } catch (RuntimeException e) {
            System.err.println("CustomContentStore: bad block entry: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> mobToMap(MobDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key());
        m.put("name", d.displayName());
        m.put("body", hex(d.body()));
        m.put("accent", hex(d.accent()));
        m.put("size", d.size());
        m.put("speed", d.speed());
        m.put("maxHealth", d.maxHealth());
        m.put("damage", d.damage());
        m.put("temperament", d.temperament().name());
        m.put("detectRange", d.detectRange());
        m.put("attackRange", d.attackRange());
        m.put("flying", d.flying());
        // Combat extras, absent when default (files from older builds load fine).
        if (d.projectile() != null) m.put("projectile", d.projectile());
        if (d.ability() != MobDef.Ability.NONE) m.put("ability", d.ability().name());
        if (d.abilityArg() != null) m.put("abilityArg", d.abilityArg());
        return m;
    }

    private static MobDef mobFrom(Map<String, Object> m) {
        try {
            MobDef.Ability ability = MobDef.Ability.NONE;
            if (m.get("ability") instanceof String a) {
                try {
                    ability = MobDef.Ability.valueOf(a);
                } catch (IllegalArgumentException ignored) {
                    // An ability from a newer build: degrade to a plain mob.
                }
            }
            return new MobDef(str(m.get("key")), str(m.get("name")),
                    color(m.get("body"), Color.GRAY), color(m.get("accent"), Color.DARK_GRAY),
                    dbl(m.get("size"), 28), dbl(m.get("speed"), 60),
                    dbl(m.get("maxHealth"), 20), dbl(m.get("damage"), 5),
                    MobDef.Temperament.valueOf(str(m.get("temperament"))),
                    dbl(m.get("detectRange"), 220), dbl(m.get("attackRange"), 34),
                    Boolean.TRUE.equals(m.get("flying")),
                    m.get("projectile") instanceof String p ? p : null,
                    ability,
                    m.get("abilityArg") instanceof String arg ? arg : null);
        } catch (RuntimeException e) {
            System.err.println("CustomContentStore: bad mob entry: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> itemToMap(ItemDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key());
        m.put("name", d.name());
        m.put("category", d.category().name());
        m.put("rarity", d.rarity().name());
        m.put("color", hex(d.color()));
        m.put("maxStack", d.maxStack());
        m.put("damage", d.damage());
        m.put("heal", d.heal());
        if (d.toolClass() != null) {
            m.put("toolClass", d.toolClass());
            m.put("toolPower", d.toolPower());
        }
        if (d.maxDurability() > 0) m.put("maxDurability", d.maxDurability());
        return m;
    }

    private static ItemDef itemFrom(Map<String, Object> m) {
        try {
            String toolClass = m.get("toolClass") instanceof String t ? t : null;
            double toolPower = dbl(m.get("toolPower"), 0);
            int durability = num(m.get("maxDurability"),
                    toolClass != null ? (int) Math.round(toolPower * 80) : 0);
            return new ItemDef(str(m.get("key")), str(m.get("name")),
                    ItemDef.Category.valueOf(str(m.get("category"))),
                    ItemDef.Rarity.valueOf(str(m.get("rarity"))),
                    color(m.get("color"), Color.GRAY), num(m.get("maxStack"), 64),
                    dbl(m.get("damage"), 0), dbl(m.get("heal"), 0), null, null, null,
                    toolClass, toolPower, durability);
        } catch (RuntimeException e) {
            System.err.println("CustomContentStore: bad item entry: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> decorToMap(Decor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key());
        m.put("name", d.name());
        m.put("shape", d.shape().name());
        m.put("primary", hex(d.primary()));
        m.put("secondary", hex(d.secondary()));
        m.put("sizeTiles", d.sizeTiles());
        return m;
    }

    private static Decor decorFrom(Map<String, Object> m) {
        try {
            return new Decor(str(m.get("key")), str(m.get("name")),
                    Decor.Shape.valueOf(str(m.get("shape"))),
                    color(m.get("primary"), Color.GRAY), color(m.get("secondary"), null),
                    dbl(m.get("sizeTiles"), 1.5));
        } catch (RuntimeException e) {
            System.err.println("CustomContentStore: bad decor entry: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> surfaceToMap(SurfaceDecor d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key());
        m.put("name", d.name());
        m.put("style", d.style().name());
        m.put("primary", hex(d.primary()));
        m.put("secondary", hex(d.secondary()));
        List<String> faces = new ArrayList<>();
        for (SurfaceDecor.Face f : d.allowedFaces()) faces.add(f.name());
        m.put("faces", faces);
        m.put("foreground", d.defaultForeground());
        return m;
    }

    private static SurfaceDecor surfaceFrom(Map<String, Object> m) {
        try {
            EnumSet<SurfaceDecor.Face> faces = EnumSet.noneOf(SurfaceDecor.Face.class);
            if (m.get("faces") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) faces.add(SurfaceDecor.Face.valueOf(s));
                }
            }
            return new SurfaceDecor(str(m.get("key")), str(m.get("name")),
                    SurfaceDecor.Style.valueOf(str(m.get("style"))),
                    color(m.get("primary"), Color.GRAY), color(m.get("secondary"), null),
                    faces, Boolean.TRUE.equals(m.get("foreground")));
        } catch (RuntimeException e) {
            System.err.println("CustomContentStore: bad surface entry: " + e.getMessage());
            return null;
        }
    }

    private static String hex(Color c) {
        return c.getAlpha() == 255
                ? String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue())
                : String.format("#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(),
                c.getAlpha());
    }

    private static Color color(Object o, Color def) {
        if (!(o instanceof String hexStr)) return def;
        try {
            String h = hexStr.startsWith("#") ? hexStr.substring(1) : hexStr;
            if (h.length() == 8) {
                return new Color(Integer.parseInt(h.substring(0, 2), 16),
                        Integer.parseInt(h.substring(2, 4), 16),
                        Integer.parseInt(h.substring(4, 6), 16),
                        Integer.parseInt(h.substring(6, 8), 16));
            }
            return new Color(Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
