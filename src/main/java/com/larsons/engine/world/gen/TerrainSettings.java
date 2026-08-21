package com.larsons.engine.world.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A level's procedural-terrain settings: whether the world beyond the level's
 * own bounds generates itself, what it generates from, and how far the player
 * can see it.
 *
 * <p>These live on the level's {@link com.larsons.engine.config.GameProfile},
 * which is to say <b>per level</b> and edited in the same settings menu as
 * everything else the level plays with. A game type can hold a hand-built maze
 * beside an endless overworld, and neither one has to know about the other.
 *
 * <p><b>What "infinite" means here.</b> Turning {@link #enabled} on expands the
 * level into a world {@link #worldSize} tiles on a side — 16&nbsp;384 by
 * default, half a million blocks across at the standard tile size — and hands
 * everything outside the level's authored bounds to
 * {@link WorldGenerator}. Nothing is built up front: a column of the world
 * exists the first time something looks at it, and columns nobody has looked at
 * cost nothing at all (see {@link WorldTerrain}). It is not literally
 * unbounded, because a coordinate has to fit in an {@code int} and a save file
 * has to name a cell; it is unbounded in the sense that matters, which is that
 * no player will reach the edge.
 *
 * <p><b>What turning it off does.</b> Generation stops and the world stops
 * growing, but what has already been walked through is kept: explored chunks
 * are promoted to saved chunks, so the level keeps exactly the terrain that has
 * been seen and nothing else. That is the difference between a setting and a
 * demolition — a creator who explores a valley they like can freeze it and
 * carry on building by hand.
 */
public final class TerrainSettings {

    /** The lowest layer of open sky: terrain above ground starts here. */
    public static final int DEFAULT_GROUND_LEVEL = 150;

    /** The water line: sea level and everything under it. */
    public static final int DEFAULT_SEA_LEVEL = 149;

    /** The top of the height axis a biome may target. */
    public static final int MAX_LEVEL = 512;

    /** The world an expanded level grows into, in tiles per side. */
    public static final int DEFAULT_WORLD_SIZE = 16384;

    /** Smallest world the settings menu offers, in tiles per side. */
    public static final int MIN_WORLD_SIZE = 1024;

    /** Largest world the settings menu offers — the engine's level ceiling. */
    public static final int MAX_WORLD_SIZE = 65536;

    /** How far the detailed sweep may be pushed, in blocks. */
    public static final int MAX_RENDER_DISTANCE = 192;

    /** How far the coarse horizon may be pushed, in blocks. */
    public static final int MAX_DISTANT_DISTANCE = 2048;

    /** The render distance a level starts at — what the engine sweeps comfortably. */
    public static final int DEFAULT_RENDER_DISTANCE = 24;

    /** The horizon a generated world starts at: far enough to read as landscape. */
    public static final int DEFAULT_DISTANT_DISTANCE = 320;

    /**
     * Whether this level generates terrain beyond its own bounds. Off is the
     * default and is what every level saved before this existed means, so
     * nothing that was hand-built ever grows a coastline it did not ask for.
     */
    public boolean enabled = false;

    /**
     * The number every feature of the world derives from. Two levels with the
     * same seed and the same biome list are the same world, block for block.
     *
     * <p>Zero means "pick one when the world is first built", which is what a
     * new level carries so that two levels created on the same afternoon are
     * not the same place. Once picked it is written down.
     */
    public long seed = 0;

    /** How big the expanded world is, in tiles per side. */
    public int worldSize = DEFAULT_WORLD_SIZE;

    /** The water line, in layers. Everything at or below it floods. */
    public int seaLevel = DEFAULT_SEA_LEVEL;

    /** The lowest layer of dry land: terrain above ground starts here. */
    public int groundLevel = DEFAULT_GROUND_LEVEL;

    /** How far the detailed terrain sweep reaches, in blocks (2–192). */
    public int renderDistance = DEFAULT_RENDER_DISTANCE;

    /**
     * How far the coarse horizon reaches, in blocks (0 = off, up to 2048).
     * Beyond {@link #renderDistance} the world is drawn as boxed landforms
     * rather than blocks — see
     * {@link com.larsons.engine.graphics.SolidPainter#distant()}.
     */
    public int distantDistance = DEFAULT_DISTANT_DISTANCE;

    /**
     * How large the landforms are, as a percentage. Below 100 packs mountains
     * and coasts closer together; above 100 stretches them out into continents
     * you walk for a long time to cross.
     */
    public int terrainScale = 100;

    /** How large one biome's patch of the world is, as a percentage. */
    public int biomeScale = 100;

    /** How cave-riddled the rock is overall, 0–100, on top of each biome's own. */
    public int caveDensity = 50;

    /** How much ore the rock carries overall, 0–100. */
    public int oreRichness = 50;

    /** How thickly decorations are scattered overall, as a percentage of each biome's. */
    public int decorDensity = 100;

    /**
     * How many chunks of world stay resident before the oldest untouched ones
     * are dropped. They regenerate identically when revisited, so this is a
     * memory dial and not a correctness one.
     */
    public int loadedChunkBudget = 512;

    /**
     * How far ahead of the player chunks are generated, in chunks. This is what
     * keeps generation off the frame: a chunk the player is about to walk into
     * was built on a background thread several seconds ago.
     */
    public int prefetchRadius = 6;

    /** How many background threads build chunks. */
    public int workerThreads = 2;

    /**
     * Whether every chunk the player has walked through is written into the
     * save file, rather than only the ones they changed.
     *
     * <p>Off by default, and that default is the whole reason an endless world
     * is affordable: a pristine chunk is a function of the seed, so writing it
     * down stores a megabyte to say what six bytes already said. Turning
     * {@link #enabled} off promotes explored chunks regardless — see this
     * class's note — so this is for the creator who wants a growing world
     * <em>and</em> a save that remembers every field they have crossed.
     */
    public boolean saveExplored = false;

    /** The biomes this world may be built out of. */
    public List<Biome> biomes = Biomes.standard();

    public TerrainSettings() {}

    /** A deep, independent copy. */
    public TerrainSettings copy() {
        return fromMap(toMap());
    }

    /** Clamp every value into the range the settings menu offers. */
    public void normalize() {
        worldSize = clamp(worldSize, MIN_WORLD_SIZE, MAX_WORLD_SIZE);
        // Chunk-aligned, so the authored level lands on a chunk boundary and
        // no chunk of the world straddles the edge of it.
        worldSize = Math.max(MIN_WORLD_SIZE, worldSize - (worldSize % WorldTerrain.CHUNK));
        seaLevel = clamp(seaLevel, 1, MAX_LEVEL - 2);
        groundLevel = clamp(groundLevel, seaLevel + 1, MAX_LEVEL - 1);
        renderDistance = clamp(renderDistance, 2, MAX_RENDER_DISTANCE);
        distantDistance = distantDistance <= 0 ? 0
                : clamp(distantDistance, renderDistance, MAX_DISTANT_DISTANCE);
        terrainScale = clamp(terrainScale, 10, 400);
        biomeScale = clamp(biomeScale, 10, 400);
        caveDensity = clamp(caveDensity, 0, 100);
        oreRichness = clamp(oreRichness, 0, 100);
        decorDensity = clamp(decorDensity, 0, 400);
        loadedChunkBudget = clamp(loadedChunkBudget, 64, 8192);
        prefetchRadius = clamp(prefetchRadius, 0, 32);
        workerThreads = clamp(workerThreads, 1, 8);
        if (biomes == null) biomes = new ArrayList<>();
        for (Biome b : biomes) b.normalize();
        dedupeKeys();
    }

    /** Keys have to be unique: they are what a saved world names its regions by. */
    private void dedupeKeys() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Biome b : biomes) {
            String base = b.key;
            int n = 2;
            while (!seen.add(b.key)) b.key = base + "_" + n++;
        }
    }

    /** The biomes of one realm that may actually be placed. */
    public List<Biome> active(Biome.Realm realm) {
        List<Biome> out = new ArrayList<>();
        for (Biome b : biomes) {
            if (b.enabled && b.realm == realm && b.weight > 0) out.add(b);
        }
        return out;
    }

    /**
     * Add a biome of the given realm with a name that is free in this list —
     * the <em>+ New biome</em> button. The new biome starts as a copy of the
     * realm's defaults rather than as an empty row, so it generates something
     * recognizable the moment it is created and every field is a change from a
     * working state rather than a blank to fill in.
     */
    public Biome addBiome(Biome.Realm realm) {
        Biome b = Biomes.blank(realm);
        String base = b.key;
        int n = 2;
        while (hasKey(b.key)) {
            b.key = base + "_" + n;
            b.name = b.name.replaceAll(" \\d+$", "") + " " + n;
            n++;
        }
        biomes.add(b);
        return b;
    }

    private boolean hasKey(String key) {
        for (Biome b : biomes) {
            if (b.key.equals(key)) return true;
        }
        return false;
    }

    /**
     * Everything that decides what the world <em>is</em>, as one string.
     *
     * <p>Two settings with the same signature build the same terrain, so a
     * level whose world no longer matches its settings can regenerate itself:
     * retune a biome, change the sea level, type a different seed, and the
     * ground you have not built on grows back to the new recipe while the
     * ground you have built on stays exactly as you left it
     * ({@link WorldTerrain#rebuiltWith}).
     *
     * <p>Deliberately not everything: the render distance, the chunk budget and
     * the number of worker threads are about the machine drawing the world
     * rather than about the world, and changing one of them must not move a
     * mountain.
     */
    public String generationSignature() {
        StringBuilder sb = new StringBuilder(256);
        sb.append(seed).append('|').append(worldSize).append('|')
                .append(seaLevel).append('|').append(groundLevel).append('|')
                .append(terrainScale).append('|').append(biomeScale).append('|')
                .append(caveDensity).append('|').append(oreRichness).append('|')
                .append(decorDensity).append('|');
        for (Biome b : biomes) {
            if (!b.enabled) {
                sb.append('-').append(b.key).append(';');
                continue;
            }
            sb.append(b.toMap()).append(';');
        }
        return sb.toString();
    }

    /** Serialize to the shape a level file carries. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("seed", seed);
        m.put("worldSize", worldSize);
        m.put("seaLevel", seaLevel);
        m.put("groundLevel", groundLevel);
        m.put("renderDistance", renderDistance);
        m.put("distantDistance", distantDistance);
        m.put("terrainScale", terrainScale);
        m.put("biomeScale", biomeScale);
        m.put("caveDensity", caveDensity);
        m.put("oreRichness", oreRichness);
        m.put("decorDensity", decorDensity);
        m.put("loadedChunkBudget", loadedChunkBudget);
        m.put("prefetchRadius", prefetchRadius);
        m.put("workerThreads", workerThreads);
        m.put("saveExplored", saveExplored);
        List<Object> list = new ArrayList<>(biomes.size());
        for (Biome b : biomes) list.add(b.toMap());
        m.put("biomes", list);
        return m;
    }

    /** Read the settings back, defaulting every key a file does not carry. */
    public static TerrainSettings fromMap(Map<String, Object> m) {
        TerrainSettings t = new TerrainSettings();
        if (m == null) return t;
        t.enabled = bool(m, "enabled", t.enabled);
        t.seed = m.get("seed") instanceof Number n ? n.longValue() : t.seed;
        t.worldSize = intg(m, "worldSize", t.worldSize);
        t.seaLevel = intg(m, "seaLevel", t.seaLevel);
        t.groundLevel = intg(m, "groundLevel", t.groundLevel);
        t.renderDistance = intg(m, "renderDistance", t.renderDistance);
        t.distantDistance = intg(m, "distantDistance", t.distantDistance);
        t.terrainScale = intg(m, "terrainScale", t.terrainScale);
        t.biomeScale = intg(m, "biomeScale", t.biomeScale);
        t.caveDensity = intg(m, "caveDensity", t.caveDensity);
        t.oreRichness = intg(m, "oreRichness", t.oreRichness);
        t.decorDensity = intg(m, "decorDensity", t.decorDensity);
        t.loadedChunkBudget = intg(m, "loadedChunkBudget", t.loadedChunkBudget);
        t.prefetchRadius = intg(m, "prefetchRadius", t.prefetchRadius);
        t.workerThreads = intg(m, "workerThreads", t.workerThreads);
        t.saveExplored = bool(m, "saveExplored", t.saveExplored);
        if (m.get("biomes") instanceof List<?> list && !list.isEmpty()) {
            List<Biome> read = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> bm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) bm;
                    read.add(Biome.fromMap(cast));
                }
            }
            if (!read.isEmpty()) t.biomes = read;
        }
        t.normalize();
        return t;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m.get(k) instanceof Boolean b ? b : def;
    }

    private static int intg(Map<String, Object> m, String k, int def) {
        return m.get(k) instanceof Number n ? n.intValue() : def;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
