package com.larsons.engine.world.gen;

import com.larsons.engine.level.PerlinNoise;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the columns of a procedural world: everything the player walks on
 * beyond the level's own bounds, as a pure function of (seed, column, row).
 *
 * <p><b>Columns, not cells.</b> The unit of generation is a whole column of
 * the world from bedrock to sky, returned run-length encoded (see
 * {@link WorldTerrain} for the encoding). That is not a storage detail, it is
 * what makes a 512-layer world affordable at all: a column of a mountain is
 * three hundred blocks and about eight runs, so the world costs what its
 * <em>shape</em> costs rather than what its volume does. Everything that has to
 * ask "how tall is the ground here" — the renderer's sweep bound, the physics'
 * standing height, the coarse horizon — gets an answer without touching a
 * single cell.
 *
 * <p><b>The pipeline, per column.</b>
 * <ol>
 *   <li><b>Climate</b> — three slow noise fields (temperature, humidity,
 *       elevation) say what kind of place this is.</li>
 *   <li><b>Biome</b> — every surface biome in the level's list scores itself
 *       against that climate; the best fit supplies the materials, and a
 *       <em>weighted blend</em> of all of them supplies the target height and
 *       relief. Blending the numbers and picking the materials is what gives
 *       sharp biome borders over ground that never steps.</li>
 *   <li><b>Shape</b> — ridged noise for mountain spines, fine noise for the
 *       lumps in them, a continent field that drops the ground below the water
 *       line to make seas, and a lake field that dimples the land.</li>
 *   <li><b>Strata</b> — surface cap over soil over the biome's stone over
 *       deepslate, with beaches where the ground meets the sea.</li>
 *   <li><b>Caves</b> — two 3D noise fields, tunnels and caverns, carved out of
 *       the rock and then <em>dressed</em> by whichever underground biome owns
 *       that depth: an ice cave is blue ice and frost, a lava cave is basalt
 *       and a lava lake, and the band they sit in is the biome's own target
 *       level.</li>
 *   <li><b>Dressing</b> — ore by depth, water above the ground up to sea
 *       level, ground cover, scattered decorations, trees whose canopies reach
 *       across columns, and buildings where a biome asks for them.</li>
 * </ol>
 *
 * <p>Everything is deterministic and position-addressed: no column's contents
 * depend on which chunk generated it or on what has been generated before, so
 * chunks can be built on any thread in any order and rebuilt identically after
 * being evicted.
 */
public final class WorldGenerator {

    /** Columns per chunk side — the unit chunks are generated in. */
    public static final int CHUNK = WorldTerrain.CHUNK;

    /** How far a canopy can reach off its trunk, and so how wide the halo is. */
    private static final int TREE_REACH = 3;

    /** How many cells across one candidate building plot is. */
    private static final int STRUCTURE_REGION = 26;

    /** How deep the rock turns to deepslate. */
    private static final int DEEPSLATE_TOP = 34;

    /** How many layers of a cave's depth band share one underground biome. */
    private static final int CAVE_BAND = 24;

    /** How far under the surface a biome's own stone reaches. */
    private static final int BIOME_ROCK_DEPTH = 48;

    private final TerrainSettings settings;
    private final long seed;
    private final int seaLevel;
    private final int groundLevel;
    private final int topLayer;

    private final PerlinNoise contNoise, ridgeNoise, detailNoise, lakeNoise;
    private final PerlinNoise tempNoise, humidNoise, elevNoise, weirdNoise;
    private final PerlinNoise caveA, caveB, caveC, strataNoise, presenceNoise;
    private final PerlinNoise caveTemp, caveHumid;

    private final Resolved[] surface;
    private final Resolved[] under;

    private final int bedrock, defaultStone, deepslate, water, ice, snowCap;
    private final int[] shallowOres, midOres, deepOres;

    /** How far apart features are (terrainScale as a frequency multiplier). */
    private final double shapeScale;
    /** How far apart biomes are (biomeScale as a frequency multiplier). */
    private final double climateScale;
    private final double caveDial;
    private final double oreDial;
    private final double decorDial;

    public WorldGenerator(TerrainSettings settings, BlockRegistry blocks, long seed) {
        TerrainSettings s = settings.copy();
        s.normalize();
        this.settings = s;
        this.seed = seed;
        this.seaLevel = s.seaLevel;
        this.groundLevel = s.groundLevel;
        this.topLayer = TerrainSettings.MAX_LEVEL;
        this.shapeScale = 100.0 / s.terrainScale;
        this.climateScale = 100.0 / s.biomeScale;
        this.caveDial = s.caveDensity / 50.0;
        this.oreDial = s.oreRichness / 50.0;
        this.decorDial = s.decorDensity / 100.0;

        this.contNoise = new PerlinNoise(seed);
        this.ridgeNoise = new PerlinNoise(seed ^ 0x9E3779B97F4A7C15L);
        this.detailNoise = new PerlinNoise(seed * 31 + 17);
        this.lakeNoise = new PerlinNoise(seed * 131 + 991);
        this.tempNoise = new PerlinNoise(seed * 7919 + 13);
        this.humidNoise = new PerlinNoise(seed * 6577 + 29);
        this.elevNoise = new PerlinNoise(seed * 4093 + 47);
        this.weirdNoise = new PerlinNoise(seed * 3571 + 71);
        this.caveA = new PerlinNoise(seed * 1543 + 101);
        this.caveB = new PerlinNoise(seed * 1811 + 211);
        this.caveC = new PerlinNoise(seed * 2069 + 307);
        this.strataNoise = new PerlinNoise(seed * 2333 + 401);
        this.presenceNoise = new PerlinNoise(seed * 3181 + 809);
        this.caveTemp = new PerlinNoise(seed * 2609 + 503);
        this.caveHumid = new PerlinNoise(seed * 2843 + 601);

        BlockRegistry reg = blocks != null ? blocks : BlockRegistry.standard();
        this.bedrock = idOf(reg, "basalt", "stone");
        this.defaultStone = idOf(reg, "stone");
        this.deepslate = idOf(reg, "deepslate", "stone");
        this.water = idOf(reg, "water");
        this.ice = idOf(reg, "ice", "packed_ice");
        this.snowCap = idOf(reg, "snow", "snow_layer");
        this.shallowOres = ids(reg, "coal_ore", "copper_ore", "iron_ore", "coal_ore");
        this.midOres = ids(reg, "iron_ore", "gold_ore", "silver_ore", "ruby_ore");
        this.deepOres = ids(reg, "diamond_ore", "emerald_ore", "amethyst_ore", "gold_ore");

        this.surface = resolve(reg, s.active(Biome.Realm.SURFACE), Biome.Realm.SURFACE);
        this.under = resolve(reg, s.active(Biome.Realm.UNDERGROUND), Biome.Realm.UNDERGROUND);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Resolved r : surface) {
            lo = Math.min(lo, r.b.targetLevel);
            hi = Math.max(hi, r.b.targetLevel);
        }
        this.minTarget = lo;
        this.maxTarget = hi;
    }

    /** The settings this generator was built from (already normalized). */
    public TerrainSettings settings() {
        return settings;
    }

    public long seed() {
        return seed;
    }

    // --- resolved biomes -------------------------------------------------------

    /** One biome with its block keys already turned into ids. */
    private static final class Resolved {
        final Biome b;
        final int cap, soil, stone, liquid, beach, log, leaf, light, wall, accent;
        final int[] decor;
        final double weight;

        Resolved(BlockRegistry r, Biome b, int fallbackStone) {
            this.b = b;
            this.cap = idOf(r, b.surfaceBlock, "stone");
            this.soil = idOf(r, b.subsurfaceBlock, b.surfaceBlock, "dirt");
            this.stone = idOf(r, b.stoneBlock, "stone");
            this.liquid = idOf(r, b.liquidBlock);
            this.beach = idOf(r, b.beachBlock, b.surfaceBlock, "sand");
            this.log = idOf(r, b.logBlock);
            this.leaf = idOf(r, b.leafBlock);
            this.light = idOf(r, b.lightBlock);
            this.wall = idOf(r, b.structureBlock);
            this.accent = idOf(r, b.structureAccentBlock, b.structureBlock);
            List<Integer> d = new ArrayList<>();
            for (String key : b.decorBlocks) {
                int id = idOf(r, key);
                if (id > 0) d.add(id);
            }
            this.decor = d.stream().mapToInt(Integer::intValue).toArray();
            this.weight = Math.max(0.01, b.weight / 100.0);
            if (this.stone <= 0) throw new IllegalStateException("no stone for " + b.key);
            if (fallbackStone <= 0) throw new IllegalStateException("registry has no stone");
        }
    }

    private static Resolved[] resolve(BlockRegistry r, List<Biome> list, Biome.Realm realm) {
        if (list.isEmpty()) {
            // A realm a creator turned every biome off in still has to produce
            // rock rather than a hole, so it falls back to one plain recipe.
            list = List.of(realm == Biome.Realm.SURFACE ? Biomes.plains() : Biomes.regularCave());
        }
        Resolved[] out = new Resolved[list.size()];
        int stone = idOf(r, "stone");
        for (int i = 0; i < out.length; i++) out[i] = new Resolved(r, list.get(i), stone);
        return out;
    }

    private static int idOf(BlockRegistry r, String... keys) {
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            Block b = r.get(key);
            if (b != null) return b.id();
        }
        return 0;
    }

    private static int[] ids(BlockRegistry r, String... keys) {
        int[] out = new int[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = idOf(r, keys[i]);
        return out;
    }

    // --- the cheap questions ---------------------------------------------------

    /**
     * The top layer of solid ground at a column, ignoring anything growing on
     * it. Cheap enough to ask about a whole horizon: it costs the climate and
     * shape fields and nothing else, which is what lets the coarse distant pass
     * draw landforms two thousand blocks out without generating them.
     */
    public int surfaceHeight(int col, int row) {
        return heightOf(shapeAt(col, row));
    }

    /** The block the ground is capped with at a column — the horizon's colour. */
    public int surfaceBlockId(int col, int row) {
        long packed = shapeAt(col, row);
        int h = heightOf(packed);
        Resolved b = surface[biomeOf(packed)];
        return capBlock(b, h, col, row);
    }

    /** The biome that owns a column, for HUD readouts and diagnostics. */
    public Biome biomeAt(int col, int row) {
        return surface[biomeOf(shapeAt(col, row))].b;
    }

    private static int heightOf(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    private static int biomeOf(long packed) {
        return (int) (packed >>> 32);
    }

    /**
     * A column's height and biome, packed into one long so the two never have
     * to be computed twice. The whole terrain rests on this being a pure
     * function of position.
     */
    private long shapeAt(int col, int row) {
        double cf = 0.0016 * climateScale;
        double weird = weirdNoise.fbm(col * cf * 3.7, row * cf * 3.7, 2, 0.5, 2.0);
        // The amplitude is deliberately more than the noise can supply, so the
        // climate saturates at both ends instead of hugging the middle. Perlin
        // noise is bunched around zero; scaled to fit inside [0,1] it would put
        // almost the whole world at temperate and humid, and a desert nobody
        // ever walks into is a biome that does not exist. Clamping instead
        // gives real deserts and real tundra, as broad regions rather than as
        // the thin extremes of a bell curve.
        double temp = clamp01(0.5 + 2.1 * tempNoise.fbm(col * cf, row * cf, 2, 0.5, 2.0)
                + 0.06 * weird) * 100;
        double humid = clamp01(0.5 + 2.1 * humidNoise.fbm(col * cf * 1.31 + 411,
                row * cf * 1.31 - 233, 2, 0.5, 2.0) - 0.06 * weird) * 100;
        double elev = clamp01(0.5 + 1.2 * elevNoise.fbm(col * cf * 0.83 - 907,
                row * cf * 0.83 + 617, 2, 0.5, 2.0)) * 100;
        double pf = 0.0009 * climateScale;

        int best = 0;
        double bestScore = -1, sumW = 0, sumTarget = 0, sumRelief = 0;
        for (int i = 0; i < surface.length; i++) {
            Biome b = surface[i].b;
            double dt = temp - b.temperature;
            double dh = humid - b.humidity;
            double de = elev - elevationBand(b);
            // Elevation counts for less than climate: it is what separates the
            // mountains from everything else, and at equal weight one biome
            // that aims high takes the half of the world that is above average.
            double d2 = dt * dt + dh * dh + de * de * 0.5;
            double fit = 1.0 / (1.0 + d2 / 900.0);
            // A field of its own per biome, so two biomes that want the same
            // climate share it instead of one of them never appearing. Fit is
            // deterministic: without this, a village at weight 14 standing in
            // exactly the same climate as plains at weight 70 loses every cell
            // in the world rather than one in five, because "loses on average"
            // and "loses everywhere" are the same thing to an argmax. Noise
            // rather than a hash because the blended height below has to stay
            // smooth, and a per-cell coin flip is a cliff.
            double presence = 0.06 + 2.5 * clamp01(0.5 + 1.9 * presenceNoise.noise(
                    col * pf + i * 997.0, row * pf - i * 613.0));
            double w = surface[i].weight * fit * fit * fit * presence;
            if (w > bestScore) {
                bestScore = w;
                best = i;
            }
            sumW += w;
            sumTarget += w * b.targetLevel;
            sumRelief += w * b.relief;
        }
        double target = sumW > 0 ? sumTarget / sumW : groundLevel;
        double relief = sumW > 0 ? sumRelief / sumW : 12;

        double h = target + relief * shape(col, row) + basin(col, row);
        int height = (int) Math.round(h);
        height = Math.max(2, Math.min(topLayer - 24, height));
        return ((long) best << 32) | (height & 0xFFFFFFFFL);
    }

    /**
     * Where a biome sits on the elevation axis, 0–100.
     *
     * <p>Spread across the target levels the level's own biomes actually use,
     * rather than across the whole 0–512 height axis. Almost every biome aims
     * within twenty layers of the shoreline and one aims at 238, so measured
     * against the axis they would all be crowded into its bottom tenth and the
     * elevation field would be answering "mountains or not" over the whole
     * world. Measured against each other they occupy the axis they are actually
     * spread over, which is what makes the highlands a place rather than a
     * default.
     */
    private double elevationBand(Biome b) {
        double span = Math.max(1.0, maxTarget - minTarget);
        return clamp01((b.targetLevel - minTarget) / span) * 100;
    }

    /** The lowest and highest target level among the surface biomes. */
    private final double minTarget, maxTarget;

    /** The ground's own roughness: mountain spines plus the lumps on them. */
    private double shape(int col, int row) {
        double rf = 0.0045 * shapeScale;
        // Ridged noise, recentred. The usual 1-|n| is sharp where it should be
        // — the peaks are where the noise crosses zero — but it also sits well
        // above its own midpoint, and terrain built on it comes out uniformly
        // raised: a shoreline that never floods, because every biome's ground
        // is lifted half its relief above the level it was asked for.
        double ridge = ((1 - Math.abs(ridgeNoise.fbm(col * rf, row * rf, 3, 0.5, 2.0)))
                - 0.78) * 2.0;
        double detail = detailNoise.fbm(col * 0.016 * shapeScale,
                row * 0.016 * shapeScale, 3, 0.5, 2.1);
        return 0.58 * ridge + 0.42 * detail;
    }

    /**
     * How far the ground is pulled below where its biome wanted it: the deep
     * dip of an ocean basin and the shallow dimple of an inland lake. Both are
     * continuous and both reach zero smoothly, so a coastline is a shore rather
     * than a cliff.
     */
    private double basin(int col, int row) {
        double f = 0.00055 * shapeScale;
        double cont = contNoise.fbm(col * f, row * f, 4, 0.5, 2.0);
        double dip = cont < -0.05 ? (cont + 0.05) * 110 : 0;
        double lake = lakeNoise.fbm(col * 0.006 * shapeScale, row * 0.006 * shapeScale,
                2, 0.5, 2.0);
        if (lake > 0.44) dip -= (lake - 0.44) * 70;
        return dip;
    }

    /** The block a column is capped with: snow high up, beach at the water. */
    private int capBlock(Resolved b, int height, int col, int row) {
        if (height < seaLevel) return b.beach;
        if (height <= seaLevel + 2) return b.beach;
        if (b.b.snowy && snowCap > 0) return snowCap;
        // Peaks above the tree line go white whatever the biome, which is what
        // makes a mountain read as a mountain from across a valley.
        if (height > groundLevel + 90 && snowCap > 0) return snowCap;
        return b.cap;
    }

    // --- generation ------------------------------------------------------------

    /**
     * Build every column of one chunk, writing run-encoded columns into
     * {@code out} (row-major, {@code out[localRow * CHUNK + localCol]}).
     *
     * <p>Chunk-at-a-time rather than column-at-a-time because trees and
     * buildings reach across columns: the heights and biomes of a border of
     * {@value #TREE_REACH} columns around the chunk are computed first, so a
     * canopy whose trunk is in the next chunk still hangs over this one — and
     * hangs over it identically whichever chunk is generated first.
     */
    public void generateChunk(int cx, int cy, int[][] out) {
        int c0 = cx * CHUNK, r0 = cy * CHUNK;
        int span = CHUNK + 2 * TREE_REACH;
        int[] heights = new int[span * span];
        int[] biomes = new int[span * span];
        for (int ly = 0; ly < span; ly++) {
            int row = r0 - TREE_REACH + ly;
            for (int lx = 0; lx < span; lx++) {
                long packed = shapeAt(c0 - TREE_REACH + lx, row);
                int i = ly * span + lx;
                heights[i] = heightOf(packed);
                biomes[i] = biomeOf(packed);
            }
        }
        List<Plot> plots = plotsNear(c0, r0);

        int[] cells = new int[topLayer];
        int[] bands = new int[topLayer / CAVE_BAND + 2];
        for (int ly = 0; ly < CHUNK; ly++) {
            for (int lx = 0; lx < CHUNK; lx++) {
                int col = c0 + lx, row = r0 + ly;
                int i = (ly + TREE_REACH) * span + (lx + TREE_REACH);
                out[ly * CHUNK + lx] = column(col, row, heights[i], surface[biomes[i]],
                        heights, biomes, span, lx, ly, plots, cells, bands);
            }
        }
    }

    /**
     * One column, bedrock to canopy, as run-encoded pairs.
     *
     * <p>Written into a scratch array of layers and compressed at the end
     * rather than assembled as runs, because half the passes below overwrite
     * what an earlier one put down — a cave eats the strata, a lava lake fills
     * the cave, a building levels the hill it stands on. Runs are what the
     * world is <em>stored</em> as, not what it is easiest to build in.
     */
    private int[] column(int col, int row, int height, Resolved b,
                         int[] heights, int[] biomes, int span, int lx, int ly,
                         List<Plot> plots, int[] cells, int[] bands) {
        java.util.Arrays.fill(cells, 0);
        strata(col, row, height, b, cells);
        caves(col, row, height, b, cells, bands);
        sea(height, b, cells);
        cover(col, row, height, b, cells);
        canopy(col, row, heights, biomes, span, lx, ly, cells);
        for (Plot plot : plots) plot.stamp(col, row, cells, height);
        return compress(cells);
    }

    /** Bedrock, rock, soil and cap — the column before anything happens to it. */
    private void strata(int col, int row, int height, Resolved b, int[] cells) {
        cells[0] = bedrock;
        int soil = b.b.soilDepth;
        boolean wet = height <= seaLevel + 2;
        int cap = capBlock(b, height, col, row);
        double variety = 0;
        for (int y = 1; y <= height && y < topLayer; y++) {
            int depth = height - y;
            int id;
            if (depth == 0) {
                id = cap;
            } else if (depth <= soil) {
                id = wet ? b.beach : b.soil;
            } else if (y <= 2) {
                id = bedrock;
            } else if (y < DEEPSLATE_TOP) {
                id = deepslate;
            } else {
                if ((y & 3) == 0) {
                    variety = strataNoise.noise(col * 0.055 + y * 0.012,
                            row * 0.055 - y * 0.011);
                }
                // The biome's own rock is a band under its soil rather than
                // the whole column: a desert is sandstone where you dig into
                // the dune, and ordinary stone a hundred blocks down, because
                // what a biome is is a surface and not a core sample.
                id = depth < BIOME_ROCK_DEPTH && variety > 0.34 ? b.stone : defaultStone;
                int ore = ore(col, row, y, b);
                if (ore > 0) id = ore;
            }
            cells[y] = id;
        }
    }

    /** An ore in this cell, or 0. Richer with depth and with the biome's dial. */
    private int ore(int col, int row, int y, Resolved b) {
        double richness = oreDial * (b.b.oreRichness / 50.0);
        if (richness <= 0) return 0;
        long hs = hash(col * 31L + y * 7919L, row * 17L - y * 104729L, 41);
        double roll = (hs & 0xFFFF) / 65536.0;
        if (roll >= 0.010 * richness) return 0;
        int pick = (int) ((hs >>> 17) & 3);
        int[] table = y > 116 ? shallowOres : y > 58 ? midOres : deepOres;
        return table[pick];
    }

    /**
     * Carve the tunnels and caverns, then hand each hollow to whichever
     * underground biome owns its depth: the floor, the ceiling, what pools in
     * the bottom of it, and what glows in it.
     */
    private void caves(int col, int row, int height, Resolved b, int[] cells, int[] bands) {
        double density = caveDial * (b.b.caveDensity / 50.0);
        if (density <= 0 || height < 8) return;
        int ceiling = Math.min(height - 2, topLayer - 1);
        // A submerged column keeps a sealed floor, so the sea does not open
        // onto an air pocket nobody can see into.
        int seal = height < seaLevel ? 7 : 3;
        ceiling = Math.min(ceiling, height - seal);
        if (ceiling < 4) return;

        // Saturated the same way the surface climate is, and for the same
        // reason: a water cave that wants humidity 96 is a cave nobody ever
        // finds if the field only ever reaches 70.
        double ct = clamp01(0.5 + 2.1 * caveTemp.fbm(col * 0.0022 * climateScale,
                row * 0.0022 * climateScale, 2, 0.5, 2.0)) * 100;
        double ch = clamp01(0.5 + 2.1 * caveHumid.fbm(col * 0.0022 * climateScale + 313,
                row * 0.0022 * climateScale - 577, 2, 0.5, 2.0)) * 100;
        int bandCount = ceiling / CAVE_BAND + 1;
        for (int band = 0; band < bandCount && band < bands.length; band++) {
            bands[band] = pickUnderground(col, row, ct, ch,
                    band * CAVE_BAND + CAVE_BAND / 2);
        }

        boolean carvedAny = false;
        for (int y = 3; y <= ceiling; y++) {
            // Caves fade out as they approach the surface, so the ground is not
            // a lid over a honeycomb.
            double near = y > ceiling - 6 ? 0.25 : 1.0;
            if (!carved(col, row, y, density * near)) continue;
            cells[y] = 0;
            carvedAny = true;
        }
        if (!carvedAny) return;

        // Dress every hollow: the pass above only knows where the rock is not.
        for (int y = 3; y <= ceiling; y++) {
            if (cells[y] != 0) continue;
            Resolved cave = under[bands[Math.min(bands.length - 1, y / CAVE_BAND)]];
            int pool = cave.b.targetLevel + cave.b.liquidLevelOffset;
            if (cave.liquid > 0 && y <= pool) {
                cells[y] = cave.liquid;
                continue;
            }
            boolean floor = cells[y - 1] != 0;
            boolean roof = y + 1 < topLayer && cells[y + 1] != 0;
            if (floor && y - 1 > 2) cells[y - 1] = cave.cap;
            if (roof && cave.soil > 0) cells[y + 1] = cave.soil;
            if (!floor) continue;
            long hs = hash(col, row * 131L + y * 977L, 53);
            double roll = (hs & 0xFFFF) / 65536.0;
            double lightRate = cave.b.lightDensity / 100.0 * 0.35 * decorDial;
            if (cave.light > 0 && roll < lightRate) {
                cells[y] = cave.light;
                continue;
            }
            double decorRate = cave.b.decorDensity / 100.0 * 0.30 * decorDial;
            if (cave.decor.length > 0 && roll < lightRate + decorRate) {
                cells[y] = cave.decor[(int) ((hs >>> 21) % cave.decor.length)];
                continue;
            }
            // A buried temple grows pillars between its floor and its ceiling,
            // which is what tells you the hollow you are in was built.
            if (cave.wall > 0 && cave.b.structureDensity > 0
                    && (col % 7 == 0) && (row % 7 == 0) && roof) {
                cells[y] = cave.wall;
            }
        }
    }

    /** Whether the rock at this cell is hollow: tunnels first, then caverns. */
    private boolean carved(int col, int row, int y, double density) {
        if (density <= 0) return false;
        double f = 0.021 * shapeScale;
        double yf = y * 0.034;
        double n1 = caveA.noise(col * f, yf, row * f);
        double n2 = caveB.noise(col * f, yf, row * f);
        if (n1 * n1 + n2 * n2 < 0.0016 + 0.0055 * density) return true;
        double blob = caveC.noise(col * 0.036 * shapeScale, y * 0.05,
                row * 0.036 * shapeScale);
        return blob > 0.62 - 0.14 * density;
    }

    /**
     * The underground biome that owns a depth in this column's cave climate.
     *
     * <p>Depth counts for a great deal more than climate does — that is what
     * puts lava at the bottom of the world and ice caves up near the rock — and
     * each biome gets the same per-biome presence field the surface ones do, so
     * two caves that want the same conditions divide the world between them
     * instead of one of them never being dug into.
     */
    private int pickUnderground(int col, int row, double temp, double humid, int depth) {
        double pf = 0.0009 * climateScale;
        int best = 0;
        double bestScore = -1;
        for (int i = 0; i < under.length; i++) {
            Biome b = under[i].b;
            double dt = temp - b.temperature;
            double dh = humid - b.humidity;
            double dl = (depth - b.targetLevel) / Math.max(8.0, b.relief);
            double d2 = dt * dt + dh * dh + dl * dl * 1500;
            double fit = 1.0 / (1.0 + d2 / 900.0);
            double presence = 0.06 + 2.5 * clamp01(0.5 + 1.9 * presenceNoise.noise(
                    col * pf - i * 631.0, row * pf + i * 457.0));
            double w = under[i].weight * fit * fit * presence;
            if (w > bestScore) {
                bestScore = w;
                best = i;
            }
        }
        return best;
    }

    /** Everything from the top of the ground up to the water line floods. */
    private void sea(int height, Resolved b, int[] cells) {
        if (height >= seaLevel) return;
        int fill = b.liquid > 0 ? b.liquid : water;
        if (fill <= 0) return;
        for (int y = height + 1; y <= seaLevel && y < topLayer; y++) {
            cells[y] = fill;
        }
        // A frozen biome's sea has a lid on it rather than a surface you sink
        // straight through.
        if (b.b.snowy && ice > 0 && seaLevel < topLayer) cells[seaLevel] = ice;
    }

    /** Grass, flowers and whatever else this biome scatters on its own ground. */
    private void cover(int col, int row, int height, Resolved b, int[] cells) {
        int y = height + 1;
        if (y >= topLayer || cells[y] != 0) return;
        if (height <= seaLevel) return;
        long hs = hash(col, row, 7);
        double roll = (hs & 0xFFFF) / 65536.0;
        double grass = b.b.grassDensity / 100.0 * 0.55 * decorDial;
        double decor = b.b.decorDensity / 100.0 * 0.30 * decorDial;
        if (roll < decor && b.decor.length > 0) {
            cells[y] = b.decor[(int) ((hs >>> 19) % b.decor.length)];
            return;
        }
        if (roll < decor + grass) {
            cells[y] = grassBlock(b, hs);
            return;
        }
        double lightRate = b.b.lightDensity / 100.0 * 0.10 * decorDial;
        if (b.light > 0 && roll < decor + grass + lightRate) cells[y] = b.light;
    }

    /** This biome's ground cover — its own tall grass, or the engine's. */
    private int grassBlock(Resolved b, long hs) {
        if (b.decor.length > 0 && (hs & 8) != 0) {
            return b.decor[(int) ((hs >>> 27) % b.decor.length)];
        }
        return tallGrassId();
    }

    private int tallGrassCache = -1;

    private int tallGrassId() {
        if (tallGrassCache < 0) {
            Block g = BlockRegistry.standard().get("tall_grass");
            tallGrassCache = g != null ? g.id() : 0;
        }
        return tallGrassCache;
    }

    // --- trees -----------------------------------------------------------------

    /**
     * Every trunk within reach of this column, and the part of its canopy that
     * hangs over it.
     *
     * <p>A tree is placed by the column it grows in, but it is <em>built</em>
     * by every column it covers: each one asks which of its neighbours has a
     * trunk and adds the leaves that belong over itself. That is what makes a
     * tree that straddles a chunk boundary come out whole — neither chunk owns
     * it, and both compute the same answer.
     */
    private void canopy(int col, int row, int[] heights, int[] biomes, int span,
                        int lx, int ly, int[] cells) {
        for (int dy = -TREE_REACH; dy <= TREE_REACH; dy++) {
            for (int dx = -TREE_REACH; dx <= TREE_REACH; dx++) {
                int i = (ly + TREE_REACH + dy) * span + (lx + TREE_REACH + dx);
                Resolved b = surface[biomes[i]];
                int h = heights[i];
                int trunk = treeHeight(col + dx, row + dy, h, b);
                if (trunk <= 0) continue;
                int base = h + 1;
                int top = base + trunk - 1;
                if (dx == 0 && dy == 0) {
                    for (int y = base; y <= top && y < topLayer; y++) cells[y] = b.log;
                }
                if (b.leaf <= 0) continue;
                int radius = trunk >= 9 ? 3 : 2;
                int d2 = dx * dx + dy * dy;
                if (d2 > radius * radius) continue;
                int from = Math.max(base, top - (radius + 1));
                int to = Math.min(topLayer - 1, top + 1);
                for (int y = from; y <= to; y++) {
                    // Pinched at the crown and at the shoulders, so it reads as
                    // a tree rather than as a cube of leaves on a stick.
                    int below = top - y;
                    int reach = below >= 2 ? radius : below >= 0 ? radius - 1 : 1;
                    if (reach < 1) reach = 1;
                    if (d2 > reach * reach) continue;
                    if (cells[y] == 0) cells[y] = b.leaf;
                }
            }
        }
    }

    /** How tall the trunk at a column is, or 0 for no tree there. */
    private int treeHeight(int col, int row, int height, Resolved b) {
        if (b.log <= 0 || b.b.treeDensity <= 0) return 0;
        if (height <= seaLevel + 1) return 0;
        long hs = hash(col, row, 11);
        double roll = (hs & 0xFFFF) / 65536.0;
        // Nine per cent at full density, not thirty. A canopy covers a dozen
        // columns, so a trunk in one column in six is three canopies deep over
        // every cell — a forest that is one unbroken green slab you cannot see
        // the ground of, which is what the first render of a pine forest was.
        if (roll >= b.b.treeDensity / 100.0 * 0.09 * decorDial) return 0;
        int spread = Math.max(1, b.b.treeMaxHeight - b.b.treeMinHeight + 1);
        return b.b.treeMinHeight + (int) ((hs >>> 23) % spread);
    }

    // --- buildings -------------------------------------------------------------

    /** One building the world has decided to put somewhere. */
    private final class Plot {
        final int col, row, size, base, wallTop;
        final int wall, accent, light;

        Plot(int col, int row, int size, int base, Resolved b) {
            this.col = col;
            this.row = row;
            this.size = size;
            this.base = base;
            this.wallTop = base + 3 + b.b.structureDensity / 25;
            this.wall = b.wall;
            this.accent = b.accent > 0 ? b.accent : b.wall;
            this.light = b.light;
        }

        boolean covers(int c, int r) {
            return c >= col && r >= row && c < col + size && r < row + size;
        }

        /**
         * Put this building into one of its columns: a floor, a wall or a clear
         * interior, a roof over both, and whatever ground it needed levelling
         * or underpinning to stand on.
         */
        void stamp(int c, int r, int[] cells, int ground) {
            if (!covers(c, r)) return;
            int lx = c - col, ly = r - row;
            // The plot is levelled: a hill inside it is cut away and a dip
            // under it is filled, so the building stands square. The fill runs
            // from this column's own ground up to the plot's floor and no
            // further — filling every empty cell instead would pour the roof
            // block down every cave under the village.
            for (int y = base + 1; y < topLayer; y++) cells[y] = 0;
            for (int y = Math.max(1, ground + 1); y <= base; y++) {
                if (cells[y] == 0) cells[y] = accent;
            }
            cells[base] = accent;
            boolean edge = lx == 0 || ly == 0 || lx == size - 1 || ly == size - 1;
            if (edge && wall > 0) {
                boolean door = ly == 0 && lx == size / 2;
                for (int y = base + 1; y <= wallTop && y < topLayer; y++) {
                    if (door && y <= base + 2) continue;
                    cells[y] = wall;
                }
            } else if (light > 0 && lx == size / 2 && ly == size / 2) {
                cells[base + 1] = light;
            }
            int roof = wallTop + 1;
            if (roof < topLayer && accent > 0) cells[roof] = accent;
        }
    }

    /**
     * The buildings whose footprints could reach into this chunk. Each
     * candidate plot is a hash of its own region, so the same building is found
     * by every chunk it touches.
     */
    private List<Plot> plotsNear(int c0, int r0) {
        List<Plot> out = new ArrayList<>(4);
        int reg = STRUCTURE_REGION;
        int g0c = Math.floorDiv(c0 - reg, reg), g1c = Math.floorDiv(c0 + CHUNK + reg, reg);
        int g0r = Math.floorDiv(r0 - reg, reg), g1r = Math.floorDiv(r0 + CHUNK + reg, reg);
        for (int gr = g0r; gr <= g1r; gr++) {
            for (int gc = g0c; gc <= g1c; gc++) {
                Plot plot = plotIn(gc, gr);
                if (plot != null) out.add(plot);
            }
        }
        return out;
    }

    /** The building in one region of the world, or {@code null} for open country. */
    private Plot plotIn(int gc, int gr) {
        long hs = hash(gc, gr, 23);
        int reg = STRUCTURE_REGION;
        int ocol = gc * reg + (int) ((hs >>> 8) % (reg / 2));
        int orow = gr * reg + (int) ((hs >>> 24) % (reg / 2));
        long packed = shapeAt(ocol, orow);
        Resolved b = surface[biomeOf(packed)];
        if (b.b.structureDensity <= 0 || b.wall <= 0) return null;
        double roll = ((hs >>> 40) & 0xFFFF) / 65536.0;
        if (roll >= b.b.structureDensity / 100.0) return null;
        int base = heightOf(packed);
        if (base <= seaLevel + 1) return null;
        int size = 6 + b.b.structureDensity / 12 + (int) ((hs >>> 4) & 3);
        size = Math.min(size, reg / 2);
        return new Plot(ocol, orow, size, base, b);
    }

    // --- run encoding ----------------------------------------------------------

    /**
     * A scratch column of layers, compressed to {@code (endExclusive, id)}
     * pairs with the trailing sky trimmed off.
     */
    private int[] compress(int[] cells) {
        int top = cells.length;
        while (top > 0 && cells[top - 1] == 0) top--;
        if (top == 0) return WorldTerrain.EMPTY_COLUMN;
        int runs = 1;
        for (int i = 1; i < top; i++) {
            if (cells[i] != cells[i - 1]) runs++;
        }
        int[] out = new int[runs * 2];
        int at = 0;
        for (int i = 1; i <= top; i++) {
            if (i == top || cells[i] != cells[i - 1]) {
                out[at++] = i;
                out[at++] = cells[i - 1];
            }
        }
        return out;
    }

    // --- helpers ---------------------------------------------------------------

    /** A well-mixed hash of a cell and a salt — the generator's dice. */
    private long hash(long a, long b, long salt) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= a * 0xFF51AFD7ED558CCDL;
        h ^= b * 0xC4CEB9FE1A85EC53L;
        h ^= salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 29;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
