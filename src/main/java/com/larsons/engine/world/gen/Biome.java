package com.larsons.engine.world.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One biome: the recipe a region of the procedural world is built from.
 *
 * <p>Biomes are <b>data</b>, not code. Every field here is something the
 * settings menu can edit and something the level file carries, so a creator
 * adds a biome with the <em>+ New biome</em> button rather than by editing the
 * engine — the same decision {@link com.larsons.engine.world.Block} made for
 * blocks and {@link com.larsons.engine.world.Decor} made for decorations. The
 * built-in set in {@link Biomes} is written through this class and has no
 * privileges a creator's biome lacks.
 *
 * <p>A biome answers four questions the generator asks in order:
 * <ol>
 *   <li><b>Where does it belong?</b> — {@link #realm} (over the ground or
 *       under it), {@link #temperature}/{@link #humidity} (the climate it
 *       claims) and {@link #weight} (how often it wins where it fits).</li>
 *   <li><b>What shape is the ground?</b> — {@link #targetLevel}, the height
 *       the terrain aims for inside it, and {@link #relief}, how far it wanders
 *       from that. Both are in the level's own layer units, so
 *       {@value TerrainSettings#DEFAULT_GROUND_LEVEL} is the shoreline and
 *       512 is the ceiling of the world.</li>
 *   <li><b>What is it made of?</b> — {@link #surfaceBlock} over
 *       {@link #subsurfaceBlock} over {@link #stoneBlock}, with
 *       {@link #beachBlock} where it meets water and {@link #liquidBlock} in
 *       whatever pools.</li>
 *   <li><b>What grows on it?</b> — trees ({@link #treeDensity} and the
 *       log/leaf pair), ground cover ({@link #grassDensity}), scattered
 *       decorations ({@link #decorBlocks}) and light sources
 *       ({@link #lightBlock}), which is what makes one stretch of stone read as
 *       a cave and another as a temple.</li>
 * </ol>
 *
 * <p>Block fields are <em>keys</em> ({@code "grass"}, {@code "pine_log"}),
 * resolved against the level's {@link com.larsons.engine.world.BlockRegistry}
 * when the generator starts. An unknown key falls back rather than throwing, so
 * a biome that names a block from a texture pack you no longer have still
 * generates.
 */
public final class Biome {

    /** Whether a biome describes the surface of the world or the rock under it. */
    public enum Realm {
        /** Above ground: everything from the sea floor up into the sky. */
        SURFACE("Above ground"),
        /** Below ground: the caverns carved out of the rock. */
        UNDERGROUND("Below ground");

        private final String label;

        Realm(String label) { this.label = label; }

        /** What a player-facing menu calls this. */
        public String label() { return label; }

        @Override public String toString() { return label; }

        /** The realm a saved name means, tolerating anything unknown. */
        public static Realm of(String text, Realm def) {
            if (text == null || text.isBlank()) return def;
            return switch (text.trim().toUpperCase()) {
                case "SURFACE", "ABOVE", "ABOVE_GROUND" -> SURFACE;
                case "UNDERGROUND", "BELOW", "BELOW_GROUND", "CAVE" -> UNDERGROUND;
                default -> def;
            };
        }
    }

    /** Stable key, unique within one level's biome list ({@code "pine"}). */
    public String key = "biome";

    /** What the settings menu calls it ({@code "Pine Forest"}). */
    public String name = "New Biome";

    /**
     * Whether the generator may place this biome at all. Off is not the same
     * as {@link #weight} zero: a creator turning a biome off keeps its recipe
     * for later, where deleting it does not.
     */
    public boolean enabled = true;

    /** Over the ground or under it. */
    public Realm realm = Realm.SURFACE;

    /**
     * How likely this biome is to win a region it fits, 0–100. Relative to the
     * other biomes of the same {@link #realm} — a world of one biome at 3 and
     * one at 7 is 30% / 70%, not 10% of anything.
     */
    public int weight = 50;

    /**
     * The layer this biome's ground aims for, 0–512. The world's shoreline is
     * {@value TerrainSettings#DEFAULT_SEA_LEVEL}, so a biome below that is
     * ocean floor and one at 300 is a mountain range.
     *
     * <p>For an {@link Realm#UNDERGROUND} biome this is the depth its caverns
     * centre on instead, which is what keeps lava at the bottom of the world
     * and ice caves near the top of the rock.
     */
    public int targetLevel = TerrainSettings.DEFAULT_GROUND_LEVEL + 8;

    /** How far the ground wanders above and below {@link #targetLevel}, in layers. */
    public int relief = 16;

    /** The climate band this biome claims, 0 (frozen) – 100 (scorching). */
    public int temperature = 50;

    /** The moisture band this biome claims, 0 (arid) – 100 (soaking). */
    public int humidity = 50;

    /** The block the ground is capped with. */
    public String surfaceBlock = "grass";

    /** What lies under the cap, for {@link #soilDepth} layers. */
    public String subsurfaceBlock = "dirt";

    /** The rock everything below the soil is cut from. */
    public String stoneBlock = "stone";

    /** What fills pools, seas and flooded caverns here. */
    public String liquidBlock = "water";

    /** The block laid where this biome meets the water line. */
    public String beachBlock = "sand";

    /** How many layers of {@link #subsurfaceBlock} sit under the cap. */
    public int soilDepth = 4;

    /** Trees per hundred columns, 0–100. */
    public int treeDensity = 0;

    /** The trunk block of this biome's tree. */
    public String logBlock = "oak_log";

    /** The canopy block of this biome's tree. */
    public String leafBlock = "leaves";

    /** Shortest trunk this biome grows, in layers. */
    public int treeMinHeight = 4;

    /** Tallest trunk this biome grows, in layers. */
    public int treeMaxHeight = 7;

    /** Ground cover (tall grass, ferns) per hundred columns, 0–100. */
    public int grassDensity = 0;

    /** Scattered {@link #decorBlocks} per hundred columns, 0–100. */
    public int decorDensity = 0;

    /**
     * The one-block decorations scattered across this biome — flowers, bushes,
     * mushrooms, crystals, coral. Block keys, picked between at random.
     */
    public List<String> decorBlocks = new ArrayList<>();

    /**
     * How much of this biome's rock is hollow, 0–100. On the surface it is how
     * cave-riddled the ground under it is; underground it is how open the
     * caverns are.
     */
    public int caveDensity = 50;

    /** How much ore this biome's rock carries, 0–100. */
    public int oreRichness = 50;

    /** Whether the cap is dusted with snow and the water freezes. */
    public boolean snowy = false;

    /**
     * How high this biome's own water sits relative to the world's sea level,
     * in layers. Negative sinks a lava sea to the bottom of a cave; positive
     * lifts a marsh above the shoreline.
     */
    public int liquidLevelOffset = 0;

    /** The glowing block scattered through this biome, or blank for none. */
    public String lightBlock = "";

    /** Light sources per hundred columns, 0–100. */
    public int lightDensity = 0;

    /** Buildings per thousand columns, 0–100 — villages, temples, ruins. */
    public int structureDensity = 0;

    /** What this biome's buildings are made of, or blank for none. */
    public String structureBlock = "";

    /** What this biome's buildings are roofed and floored with. */
    public String structureAccentBlock = "";

    public Biome() {}

    public Biome(String key, String name, Realm realm) {
        this.key = key;
        this.name = name;
        this.realm = realm;
    }

    /** A deep, independent copy (the map round-trip keeps it in sync with the fields). */
    public Biome copy() {
        return fromMap(toMap());
    }

    /** Clamp every value into the range the settings menu offers. */
    public void normalize() {
        if (key == null || key.isBlank()) key = "biome";
        if (name == null || name.isBlank()) name = key;
        if (realm == null) realm = Realm.SURFACE;
        weight = clamp(weight, 0, 100);
        targetLevel = clamp(targetLevel, 0, TerrainSettings.MAX_LEVEL);
        relief = clamp(relief, 0, 200);
        temperature = clamp(temperature, 0, 100);
        humidity = clamp(humidity, 0, 100);
        soilDepth = clamp(soilDepth, 0, 32);
        treeDensity = clamp(treeDensity, 0, 100);
        treeMinHeight = clamp(treeMinHeight, 1, 40);
        treeMaxHeight = clamp(treeMaxHeight, treeMinHeight, 40);
        grassDensity = clamp(grassDensity, 0, 100);
        decorDensity = clamp(decorDensity, 0, 100);
        caveDensity = clamp(caveDensity, 0, 100);
        oreRichness = clamp(oreRichness, 0, 100);
        lightDensity = clamp(lightDensity, 0, 100);
        structureDensity = clamp(structureDensity, 0, 100);
        liquidLevelOffset = clamp(liquidLevelOffset, -256, 256);
        if (surfaceBlock == null || surfaceBlock.isBlank()) surfaceBlock = "stone";
        if (subsurfaceBlock == null || subsurfaceBlock.isBlank()) subsurfaceBlock = surfaceBlock;
        if (stoneBlock == null || stoneBlock.isBlank()) stoneBlock = "stone";
        if (liquidBlock == null) liquidBlock = "";
        if (beachBlock == null || beachBlock.isBlank()) beachBlock = surfaceBlock;
        if (logBlock == null) logBlock = "";
        if (leafBlock == null) leafBlock = "";
        if (lightBlock == null) lightBlock = "";
        if (structureBlock == null) structureBlock = "";
        if (structureAccentBlock == null) structureAccentBlock = "";
        if (decorBlocks == null) decorBlocks = new ArrayList<>();
        decorBlocks.removeIf(s -> s == null || s.isBlank());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Serialize to the shape a level file carries. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("enabled", enabled);
        m.put("realm", realm.name());
        m.put("weight", weight);
        m.put("targetLevel", targetLevel);
        m.put("relief", relief);
        m.put("temperature", temperature);
        m.put("humidity", humidity);
        m.put("surfaceBlock", surfaceBlock);
        m.put("subsurfaceBlock", subsurfaceBlock);
        m.put("stoneBlock", stoneBlock);
        m.put("liquidBlock", liquidBlock);
        m.put("beachBlock", beachBlock);
        m.put("soilDepth", soilDepth);
        m.put("treeDensity", treeDensity);
        m.put("logBlock", logBlock);
        m.put("leafBlock", leafBlock);
        m.put("treeMinHeight", treeMinHeight);
        m.put("treeMaxHeight", treeMaxHeight);
        m.put("grassDensity", grassDensity);
        m.put("decorDensity", decorDensity);
        m.put("decorBlocks", new ArrayList<Object>(decorBlocks));
        m.put("caveDensity", caveDensity);
        m.put("oreRichness", oreRichness);
        m.put("snowy", snowy);
        m.put("liquidLevelOffset", liquidLevelOffset);
        m.put("lightBlock", lightBlock);
        m.put("lightDensity", lightDensity);
        m.put("structureDensity", structureDensity);
        m.put("structureBlock", structureBlock);
        m.put("structureAccentBlock", structureAccentBlock);
        return m;
    }

    /** Read a biome back, defaulting every key a file does not carry. */
    public static Biome fromMap(Map<String, Object> m) {
        Biome b = new Biome();
        if (m == null) return b;
        b.key = str(m, "key", b.key);
        b.name = str(m, "name", b.name);
        b.enabled = bool(m, "enabled", b.enabled);
        b.realm = Realm.of(str(m, "realm", b.realm.name()), b.realm);
        b.weight = intg(m, "weight", b.weight);
        b.targetLevel = intg(m, "targetLevel", b.targetLevel);
        b.relief = intg(m, "relief", b.relief);
        b.temperature = intg(m, "temperature", b.temperature);
        b.humidity = intg(m, "humidity", b.humidity);
        b.surfaceBlock = str(m, "surfaceBlock", b.surfaceBlock);
        b.subsurfaceBlock = str(m, "subsurfaceBlock", b.subsurfaceBlock);
        b.stoneBlock = str(m, "stoneBlock", b.stoneBlock);
        b.liquidBlock = str(m, "liquidBlock", b.liquidBlock);
        b.beachBlock = str(m, "beachBlock", b.beachBlock);
        b.soilDepth = intg(m, "soilDepth", b.soilDepth);
        b.treeDensity = intg(m, "treeDensity", b.treeDensity);
        b.logBlock = str(m, "logBlock", b.logBlock);
        b.leafBlock = str(m, "leafBlock", b.leafBlock);
        b.treeMinHeight = intg(m, "treeMinHeight", b.treeMinHeight);
        b.treeMaxHeight = intg(m, "treeMaxHeight", b.treeMaxHeight);
        b.grassDensity = intg(m, "grassDensity", b.grassDensity);
        b.decorDensity = intg(m, "decorDensity", b.decorDensity);
        if (m.get("decorBlocks") instanceof List<?> list) {
            b.decorBlocks = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) b.decorBlocks.add(s);
            }
        }
        b.caveDensity = intg(m, "caveDensity", b.caveDensity);
        b.oreRichness = intg(m, "oreRichness", b.oreRichness);
        b.snowy = bool(m, "snowy", b.snowy);
        b.liquidLevelOffset = intg(m, "liquidLevelOffset", b.liquidLevelOffset);
        b.lightBlock = str(m, "lightBlock", b.lightBlock);
        b.lightDensity = intg(m, "lightDensity", b.lightDensity);
        b.structureDensity = intg(m, "structureDensity", b.structureDensity);
        b.structureBlock = str(m, "structureBlock", b.structureBlock);
        b.structureAccentBlock = str(m, "structureAccentBlock", b.structureAccentBlock);
        b.normalize();
        return b;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        return m.get(k) instanceof String s ? s : def;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m.get(k) instanceof Boolean v ? v : def;
    }

    private static int intg(Map<String, Object> m, String k, int def) {
        return m.get(k) instanceof Number n ? n.intValue() : def;
    }

    @Override
    public String toString() {
        return name;
    }
}
