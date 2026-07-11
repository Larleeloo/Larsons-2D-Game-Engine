package com.larsons.engine.level;

import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural level generation for the creative editor's <em>Generate</em>
 * button: Minecraft-style Perlin terrain fused with a Metroidvania room
 * network, so the result plays like one big interconnected exploration map.
 *
 * <p>The pipeline, top to bottom:
 * <ol>
 *   <li><b>Surface</b> — fractal Perlin heightmap; grass over dirt over
 *       stone, deepslate in the depths, sand and pooled water below sea
 *       level (real {@code water} sources, so the liquid sim owns them).</li>
 *   <li><b>Caves</b> — two noise fields: ridged "worm" tunnels plus blobby
 *       caverns, both widening with depth.</li>
 *   <li><b>Rooms &amp; corridors</b> — the underground is divided into
 *       sectors, each carved into a room; rooms are linked to their
 *       neighbours (union-find guarantees one connected component, extra
 *       links add loops) and vertical runs get platform ladders so every
 *       room is reachable with the standard jump.</li>
 *   <li><b>Shafts</b> — a few platformed shafts connect the surface to the
 *       room network.</li>
 *   <li><b>Dressing</b> — ore veins by depth, a lava ocean at the bottom,
 *       torches in rooms, trees/rocks/bushes on the surface, glowing
 *       mushrooms and crystals in the caves, treasure items in room centres,
 *       mobs by depth, and multiplayer spawn points spread across the
 *       surface.</li>
 * </ol>
 *
 * <p>Everything derives from the seed: the same (seed, size) always builds
 * the identical level.
 */
public final class LevelGenerator {

    private LevelGenerator() {}

    public static Level generate(String name, int width, int height, int tileSize, long seed) {
        width = Math.max(48, width);
        height = Math.max(36, height);
        if ((long) width * height > Level.DENSE_TILE_LIMIT) {
            return generateChunked(name, width, height, tileSize, seed);
        }
        Level lvl = Level.empty(name, width, height, tileSize);
        new Generation(lvl, seed).run();
        return lvl;
    }

    /**
     * "Maze mode": the automatic generator for top-down / isometric levels —
     * a perfect maze (recursive backtracker) with solid walls, walkable path
     * floors, torches at junctions, treasure chests in dead ends, mobs in the
     * far reaches, and the exit chest (with the gold key) at the cell farthest
     * from the spawn. Same seed, same maze.
     */
    public static Level generateMaze(String name, int width, int height, int tileSize,
                                     long seed, com.larsons.engine.graphics.Perspective perspective) {
        // Mazes carve every cell, so they stay dense (no giant chunked mazes).
        width = Math.max(17, Math.min(511, width)) | 1;   // odd sizes keep walls 1 thick
        height = Math.max(17, Math.min(511, height)) | 1;
        Level lvl = Level.empty(name, width, height, tileSize);
        lvl.perspective = perspective;
        new MazeGeneration(lvl, seed).run();
        return lvl;
    }

    /**
     * Generate a <em>giant</em> level (beyond {@link Level#DENSE_TILE_LIMIT},
     * up to 65536&times;65536) lazily: nothing is filled up front — a
     * deterministic {@link ChunkGenerator} builds each chunk the first time
     * the camera or simulation looks at it, so generation cost tracks what's
     * visible instead of the level bounds. Terrain, caves, ores, sea water and
     * the lava floor come from the same noise recipes as eager generation;
     * the room network and entity dressing (global passes) are skipped.
     */
    public static Level generateChunked(String name, int width, int height,
                                        int tileSize, long seed) {
        TerrainChunkGenerator gen = new TerrainChunkGenerator(seed, width, height);
        Level lvl = Level.emptyChunked(name, width, height, tileSize, gen);
        // Spawn on the dry surface near the left edge, like eager generation.
        int spawnCol = 4;
        for (int c = 4; c < Math.min(width - 4, 4 + 400); c++) {
            if (gen.surfaceAt(c) <= gen.seaLevel) {
                spawnCol = c;
                break;
            }
        }
        lvl.spawnX = spawnCol * (double) tileSize;
        lvl.spawnY = (gen.surfaceAt(spawnCol) - 3) * (double) tileSize;
        // A few multiplayer spawns and ambient mobs near the spawn area.
        String[] passive = {"sheep", "rabbit", "deer", "fox", "pig"};
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 1; i <= 4; i++) {
            int c = Math.min(width - 4, spawnCol + i * 24 + rng.nextInt(12));
            if (gen.surfaceAt(c) > gen.seaLevel) continue;
            lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn",
                    c * (double) tileSize, (gen.surfaceAt(c) - 2) * (double) tileSize));
            lvl.entities.add(new Level.EntitySpawn("mob",
                    passive[rng.nextInt(passive.length)],
                    (c + 4) * (double) tileSize, (gen.surfaceAt(c + 4) - 2) * (double) tileSize));
        }
        return lvl;
    }

    /** The deterministic chunk generator giant saves rebuild from their seed. */
    public static ChunkGenerator chunkGenerator(long seed, int width, int height) {
        return new TerrainChunkGenerator(seed, width, height);
    }

    /**
     * Per-chunk terrain generation: every tile is a pure function of
     * (seed, col, row), so any chunk can be built — or rebuilt after eviction —
     * in isolation. Reuses the eager pipeline's noise recipes for the surface,
     * strata, caves and sea; ore veins become per-tile hash rolls of matching
     * density (random-walk veins don't decompose into independent chunks).
     */
    static final class TerrainChunkGenerator implements ChunkGenerator {
        private final long seed;
        private final PerlinNoise noise;
        private final int w, h;
        final int seaLevel;

        // Resolved once; the standard registry is immutable by convention.
        private final int grass, dirt, stone, deepslate, granite, sand, sandstone,
                water, lava, basalt;

        TerrainChunkGenerator(long seed, int width, int height) {
            this.seed = seed;
            this.noise = new PerlinNoise(seed);
            this.w = width;
            this.h = height;
            this.seaLevel = (int) (h * 0.36);
            BlockRegistry blocks = BlockRegistry.standard();
            grass = idOf(blocks, "grass");
            dirt = idOf(blocks, "dirt");
            stone = idOf(blocks, "stone");
            deepslate = idOf(blocks, "deepslate");
            granite = idOf(blocks, "granite");
            sand = idOf(blocks, "sand");
            sandstone = idOf(blocks, "sandstone");
            water = idOf(blocks, "water");
            lava = idOf(blocks, "lava");
            basalt = idOf(blocks, "basalt");
        }

        private static int idOf(BlockRegistry blocks, String key) {
            Block b = blocks.get(key);
            return b != null ? b.id() : 0;
        }

        @Override
        public long seed() {
            return seed;
        }

        /** Surface row for a column — the eager pipeline's heightmap formula. */
        int surfaceAt(int c) {
            double hills = noise.fbm(c * 0.012, 0.7, 4, 0.5, 2.1);
            double rough = noise.fbm(c * 0.07, 13.4, 3, 0.5, 2.0);
            int s = (int) (h * 0.32 + hills * hillAmplitude(h) + rough * roughAmplitude(h));
            return Math.max(4, Math.min(h - 14, s));
        }

        @Override
        public void generate(int cx, int cy, int[] out) {
            int c0 = cx * ChunkedTiles.CHUNK, r0 = cy * ChunkedTiles.CHUNK;
            for (int lc = 0; lc < ChunkedTiles.CHUNK; lc++) {
                int c = c0 + lc;
                if (c >= w) break;
                int surface = surfaceAt(c);
                for (int lr = 0; lr < ChunkedTiles.CHUNK; lr++) {
                    int r = r0 + lr;
                    if (r >= h) break;
                    out[lr * ChunkedTiles.CHUNK + lc] = tileFor(c, r, surface);
                }
            }
        }

        private int tileFor(int c, int r, int surface) {
            // Side borders and floor: a basalt frame contains liquids.
            if (r == h - 1) return basalt;
            if ((c == 0 || c == w - 1) && r >= Math.min(surface, seaLevel)) return basalt;
            if (r < surface) {
                // Open sky, except valleys below sea level fill with water.
                return r >= seaLevel ? water : 0;
            }

            boolean submerged = surface > seaLevel;
            boolean beach = !submerged && surface >= seaLevel - 2;
            int depth = r - surface;
            int block;
            if (depth == 0) {
                block = submerged || beach ? sand : grass;
            } else if (depth <= 3 + (int) (noise.noise(c * 0.15, 4.2) * 2)) {
                block = submerged || beach ? sandstone : dirt;
            } else if (r > h * 0.72) {
                block = noise.fbm(c * 0.05, r * 0.05, 2, 0.5, 2.0) > 0.25
                        ? granite : deepslate;
            } else {
                block = noise.fbm(c * 0.05, r * 0.05, 2, 0.5, 2.0) > 0.38
                        ? granite : stone;
            }

            // Caves: same worm + cavern noise as the eager pipeline.
            boolean carved = false;
            if (c > 0 && c < w - 1 && r >= surface + 3 && r < h - 2) {
                double depthT = (r - surface) / (double) h;
                double worm = noise.fbm(c * 0.035, r * 0.05, 2, 0.5, 2.0);
                double cavern = noise.fbm(c * 0.05 + 200, r * 0.07 + 200, 3, 0.5, 2.0);
                carved = Math.abs(worm) < 0.045 + depthT * 0.03
                        || cavern > 0.42 - depthT * 0.06;
            }
            if (carved) {
                // Carved caves flood with lava near the bottom.
                return r >= h - 6 ? lava : 0;
            }

            // Ore veins as per-tile hash rolls in stone-family rock.
            if (block == stone || block == deepslate || block == granite) {
                long hash = mix(c, r);
                if ((hash & 0xFFFF) < (int) (0.022 * 0x10000)) {
                    double depthT = (r - surface) / Math.max(1.0, h - (double) surface);
                    block = idOf(BlockRegistry.standard(),
                            oreForDepth(depthT, ((hash >>> 16) & 0xFFFF) / 65536.0));
                }
            }
            return block;
        }

        /** Depth-banded ore pick — the eager pipeline's table with an explicit roll. */
        private static String oreForDepth(double t, double roll) {
            if (t < 0.3) return roll < 0.55 ? "coal_ore" : "copper_ore";
            if (t < 0.55) {
                if (roll < 0.35) return "iron_ore";
                if (roll < 0.6) return "coal_ore";
                if (roll < 0.8) return "silver_ore";
                return "emerald_ore";
            }
            if (roll < 0.3) return "gold_ore";
            if (roll < 0.55) return "amethyst_ore";
            if (roll < 0.75) return "ruby_ore";
            return "diamond_ore";
        }

        /** SplitMix64-style position hash, folded with the seed. */
        private long mix(int c, int r) {
            long z = seed ^ (c * 0x9E3779B97F4A7C15L) ^ ((long) r << 32 | (r & 0xFFFFFFFFL));
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    /**
     * Hill amplitude in tiles. Proportional scaling made tall maps spike into
     * unclimbable vertical mountains (the same noise slope stretched over
     * hundreds of rows), so the amplitude is capped in absolute tiles: terrain
     * stays smoothly rolling no matter the map size.
     */
    static double hillAmplitude(int heightTiles) {
        return Math.min(heightTiles * 0.20, 22);
    }

    /** Fine-detail roughness, similarly capped so big maps stay walkable. */
    static double roughAmplitude(int heightTiles) {
        return Math.min(heightTiles * 0.045, 2.5);
    }

    /**
     * The maze pipeline: a recursive-backtracker maze over a half-resolution
     * cell grid (odd tile coordinates are rooms, even ones walls), then
     * dressing — path floors, junction torches, dead-end loot, mobs by
     * distance from the entrance.
     */
    private static final class MazeGeneration {
        final Level lvl;
        final Random rng;
        final int w, h, ts;
        final int cols, rows;          // maze cells (each = 1 room tile + wall)
        final int[][] dist;            // BFS distance from the entrance cell
        final List<int[]> deadEnds = new ArrayList<>(); // {cx, cy}

        MazeGeneration(Level lvl, long seed) {
            this.lvl = lvl;
            this.rng = new Random(seed);
            this.w = lvl.width;
            this.h = lvl.height;
            this.ts = lvl.tileSize;
            this.cols = (w - 1) / 2;
            this.rows = (h - 1) / 2;
            this.dist = new int[cols][rows];
        }

        int id(String key) {
            Block b = lvl.blocks.get(key);
            return b != null ? b.id() : 0;
        }

        int tileX(int cx) { return cx * 2 + 1; }
        int tileY(int cy) { return cy * 2 + 1; }

        void run() {
            int wall = id("stone_wall");
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) lvl.tiles[r][c] = wall;
            }
            carve();
            measureDistances();
            dress();
            lvl.background = new Color(14 + rng.nextInt(10), 16 + rng.nextInt(10),
                    24 + rng.nextInt(12));
            lvl.spawnX = (tileX(0) + 0.5) * ts - ts / 2.0;
            lvl.spawnY = (tileY(0) + 0.5) * ts - ts / 2.0;
        }

        /** Recursive backtracker: carve rooms + the wall between visited pairs. */
        void carve() {
            boolean[][] visited = new boolean[cols][rows];
            java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
            visited[0][0] = true;
            lvl.tiles[tileY(0)][tileX(0)] = 0;
            stack.push(new int[]{0, 0});
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!stack.isEmpty()) {
                int[] cur = stack.peek();
                List<int[]> open = new ArrayList<>(4);
                for (int[] d : dirs) {
                    int nx = cur[0] + d[0], ny = cur[1] + d[1];
                    if (nx >= 0 && nx < cols && ny >= 0 && ny < rows && !visited[nx][ny]) {
                        open.add(d);
                    }
                }
                if (open.isEmpty()) {
                    stack.pop();
                    continue;
                }
                int[] d = open.get(rng.nextInt(open.size()));
                int nx = cur[0] + d[0], ny = cur[1] + d[1];
                visited[nx][ny] = true;
                lvl.tiles[tileY(ny)][tileX(nx)] = 0;
                lvl.tiles[tileY(cur[1]) + d[1]][tileX(cur[0]) + d[0]] = 0;
                stack.push(new int[]{nx, ny});
                // Occasional extra opening turns the perfect maze into loops.
                if (rng.nextDouble() < 0.04) {
                    int[] d2 = dirs[rng.nextInt(4)];
                    int lx = cur[0] + d2[0], ly = cur[1] + d2[1];
                    if (lx >= 0 && lx < cols && ly >= 0 && ly < rows && visited[lx][ly]) {
                        lvl.tiles[tileY(cur[1]) + d2[1]][tileX(cur[0]) + d2[0]] = 0;
                    }
                }
            }
        }

        /** BFS from the entrance; also collects the dead ends for loot. */
        void measureDistances() {
            for (int[] col : dist) java.util.Arrays.fill(col, -1);
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            dist[0][0] = 0;
            queue.add(new int[]{0, 0});
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                int[] cur = queue.poll();
                int exits = 0;
                for (int[] d : dirs) {
                    int nx = cur[0] + d[0], ny = cur[1] + d[1];
                    if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
                    // Connected when the wall tile between the cells is open.
                    if (lvl.tiles[tileY(cur[1]) + d[1]][tileX(cur[0]) + d[0]] != 0) continue;
                    exits++;
                    if (dist[nx][ny] < 0) {
                        dist[nx][ny] = dist[cur[0]][cur[1]] + 1;
                        queue.add(new int[]{nx, ny});
                    }
                }
                if (exits == 1 && (cur[0] != 0 || cur[1] != 0)) deadEnds.add(cur);
            }
        }

        void dress() {
            int path = id("stone_path"), torch = id("torch"), chest = id("chest");
            // Path floors along the corridors (walkable markings, not walls).
            for (int r = 1; r < h - 1; r++) {
                for (int c = 1; c < w - 1; c++) {
                    if (lvl.tiles[r][c] == 0 && rng.nextDouble() < 0.5) {
                        lvl.tiles[r][c] = path;
                    }
                }
            }
            // Torches at junction cells so the maze is navigable when lit.
            for (int cx = 0; cx < cols; cx++) {
                for (int cy = 0; cy < rows; cy++) {
                    if (dist[cx][cy] >= 0 && rng.nextDouble() < 0.10) {
                        lvl.tiles[tileY(cy)][tileX(cx)] = torch;
                    }
                }
            }
            // The farthest dead end holds the exit chest with the gold key.
            int[] far = {0, 0};
            for (int[] de : deadEnds) {
                if (dist[de[0]][de[1]] > dist[far[0]][far[1]]) far = de;
            }
            placeChest(far, new String[]{"gold_key", "golden_apple", "diamond"});
            // Other dead ends: chests, mobs by depth, the odd trap.
            String[] loot = {"bread", "apple", "arrow", "health_potion", "rope",
                    "torch", "iron_sword", "feather_charm"};
            String[] mobsNear = {"slime", "spider", "goblin"};
            String[] mobsFar = {"zombie", "skeleton", "mage"};
            int maxDist = Math.max(1, dist[far[0]][far[1]]);
            for (int[] de : deadEnds) {
                if (de == far) continue;
                double t = dist[de[0]][de[1]] / (double) maxDist;
                double roll = rng.nextDouble();
                if (roll < 0.30) {
                    placeChest(de, new String[]{loot[rng.nextInt(loot.length)],
                            loot[rng.nextInt(loot.length)]});
                } else if (roll < 0.55) {
                    String[] pool = t < 0.5 ? mobsNear : mobsFar;
                    lvl.entities.add(new Level.EntitySpawn("mob",
                            pool[rng.nextInt(pool.length)],
                            (tileX(de[0]) + 0.5) * ts, (tileY(de[1]) + 0.5) * ts));
                }
            }
            // Multiplayer spawns near the three other corners.
            int[][] corners = {{cols - 1, 0}, {0, rows - 1}, {cols - 1, rows - 1}};
            for (int[] corner : corners) {
                lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn",
                        (tileX(corner[0]) + 0.5) * ts, (tileY(corner[1]) + 0.5) * ts));
            }
            if (chest == 0) return; // chest block missing: loot cells stay open
        }

        /** A chest block in the cell, its container pre-filled with the loot. */
        void placeChest(int[] cell, String[] items) {
            int tx = tileX(cell[0]), ty = tileY(cell[1]);
            lvl.tiles[ty][tx] = id("chest");
            List<com.larsons.engine.entity.ItemStack> box = lvl.openContainer(tx, ty);
            for (String item : items) {
                box.add(new com.larsons.engine.entity.ItemStack(item, 1));
            }
        }
    }

    /** One generation run; fields keep the pipeline stages readable. */
    private static final class Generation {
        final Level lvl;
        final BlockRegistry blocks;
        final Random rng;
        final PerlinNoise noise;
        final int w, h, ts;
        final int seaLevel;
        final int[] surface;
        final List<int[]> roomCenters = new ArrayList<>(); // {col, row} floors

        Generation(Level lvl, long seed) {
            this.lvl = lvl;
            this.blocks = lvl.blocks;
            this.rng = new Random(seed);
            this.noise = new PerlinNoise(seed);
            this.w = lvl.width;
            this.h = lvl.height;
            this.ts = lvl.tileSize;
            this.seaLevel = (int) (h * 0.36);
            this.surface = new int[w];
        }

        void run() {
            shapeSurface();
            fillTerrain();
            carveCaves();
            carveRooms();
            digEntryShafts();
            placeOres();
            floodBottomWithLava();
            buildBorder();
            lightRooms();
            dressSurface();
            dressCaves();
            placeTreasure();
            placeMobs();
            placeSpawns();
            lvl.background = new Color(18 + rng.nextInt(14), 22 + rng.nextInt(14),
                    36 + rng.nextInt(20));
        }

        int id(String key) {
            Block b = blocks.get(key);
            return b != null ? b.id() : 0;
        }

        boolean inBounds(int c, int r) {
            return c >= 0 && c < w && r >= 0 && r < h;
        }

        // --- terrain ---------------------------------------------------------

        void shapeSurface() {
            for (int c = 0; c < w; c++) {
                double hills = noise.fbm(c * 0.012, 0.7, 4, 0.5, 2.1);
                double rough = noise.fbm(c * 0.07, 13.4, 3, 0.5, 2.0);
                int s = (int) (h * 0.32 + hills * hillAmplitude(h) + rough * roughAmplitude(h));
                surface[c] = Math.max(4, Math.min(h - 14, s));
            }
        }

        void fillTerrain() {
            int grass = id("grass"), dirt = id("dirt"), stone = id("stone");
            int deepslate = id("deepslate"), granite = id("granite");
            int sand = id("sand"), sandstone = id("sandstone"), water = id("water");
            for (int c = 0; c < w; c++) {
                boolean submerged = surface[c] > seaLevel;
                boolean beach = !submerged && surface[c] >= seaLevel - 2;
                for (int r = surface[c]; r < h; r++) {
                    int depth = r - surface[c];
                    int block;
                    if (depth == 0) {
                        block = submerged || beach ? sand : grass;
                    } else if (depth <= 3 + (int) (noise.noise(c * 0.15, 4.2) * 2)) {
                        block = submerged || beach ? sandstone : dirt;
                    } else if (r > h * 0.72) {
                        block = noise.fbm(c * 0.05, r * 0.05, 2, 0.5, 2.0) > 0.25
                                ? granite : deepslate;
                    } else {
                        block = noise.fbm(c * 0.05, r * 0.05, 2, 0.5, 2.0) > 0.38
                                ? granite : stone;
                    }
                    lvl.tiles[r][c] = block;
                }
                // Valleys below sea level fill with real water sources.
                for (int r = seaLevel; r < surface[c]; r++) {
                    lvl.tiles[r][c] = water;
                }
            }
        }

        void carveCaves() {
            for (int c = 1; c < w - 1; c++) {
                for (int r = surface[c] + 3; r < h - 2; r++) {
                    double depthT = (r - surface[c]) / (double) h; // widen with depth
                    double worm = noise.fbm(c * 0.035, r * 0.05, 2, 0.5, 2.0);
                    double cavern = noise.fbm(c * 0.05 + 200, r * 0.07 + 200, 3, 0.5, 2.0);
                    if (Math.abs(worm) < 0.045 + depthT * 0.03
                            || cavern > 0.42 - depthT * 0.06) {
                        lvl.tiles[r][c] = 0;
                    }
                }
            }
        }

        // --- metroidvania room network ----------------------------------------

        void carveRooms() {
            final int sectorW = 26, sectorH = 18;
            int maxSurface = 0;
            for (int s : surface) maxSurface = Math.max(maxSurface, s);
            int top = maxSurface + 4;
            int cols = Math.max(1, (w - 4) / sectorW);
            int rows = Math.max(0, (h - 4 - top) / sectorH);
            if (rows == 0 || cols < 2) return;

            int[][] roomAt = new int[cols][rows]; // index into rects, -1 = none
            List<int[]> rects = new ArrayList<>();  // {c0, r0, rw, rh}
            for (int sc = 0; sc < cols; sc++) {
                for (int sr = 0; sr < rows; sr++) {
                    roomAt[sc][sr] = -1;
                    if (rng.nextDouble() < 0.12) continue; // some sectors stay wild
                    int rw = 8 + rng.nextInt(Math.max(1, sectorW - 12));
                    int rh = 5 + rng.nextInt(Math.max(1, sectorH - 9));
                    int c0 = 2 + sc * sectorW + rng.nextInt(Math.max(1, sectorW - rw - 2));
                    int r0 = top + sr * sectorH + rng.nextInt(Math.max(1, sectorH - rh - 2));
                    if (c0 + rw >= w - 2 || r0 + rh >= h - 6) continue;
                    carveRect(c0, r0, rw, rh);
                    platformTallRoom(c0, r0, rw, rh);
                    roomAt[sc][sr] = rects.size();
                    rects.add(new int[]{c0, r0, rw, rh});
                    roomCenters.add(new int[]{c0 + rw / 2, r0 + rh - 1});
                }
            }
            connectRooms(roomAt, rects, cols, rows);
        }

        void carveRect(int c0, int r0, int rw, int rh) {
            for (int r = r0; r < r0 + rh; r++) {
                for (int c = c0; c < c0 + rw; c++) {
                    if (inBounds(c, r)) lvl.tiles[r][c] = 0;
                }
            }
        }

        /** Rooms taller than a jump get staggered platforms so they're climbable. */
        void platformTallRoom(int c0, int r0, int rw, int rh) {
            int platform = id("platform");
            boolean left = rng.nextBoolean();
            for (int r = r0 + rh - 4; r > r0 + 1; r -= 3) {
                int len = Math.max(3, rw / 3);
                int start = left ? c0 + 1 : c0 + rw - 1 - len;
                for (int c = start; c < start + len; c++) {
                    if (inBounds(c, r)) lvl.tiles[r][c] = platform;
                }
                left = !left;
            }
        }

        /** Union-find-connected corridor graph over the sector grid, plus loops. */
        void connectRooms(int[][] roomAt, List<int[]> rects, int cols, int rows) {
            int[] parent = new int[rects.size()];
            for (int i = 0; i < parent.length; i++) parent[i] = i;

            List<int[]> edges = new ArrayList<>(); // {roomA, roomB}
            for (int sc = 0; sc < cols; sc++) {
                for (int sr = 0; sr < rows; sr++) {
                    int a = roomAt[sc][sr];
                    if (a < 0) continue;
                    if (sc + 1 < cols && roomAt[sc + 1][sr] >= 0) {
                        edges.add(new int[]{a, roomAt[sc + 1][sr]});
                    }
                    if (sr + 1 < rows && roomAt[sc][sr + 1] >= 0) {
                        edges.add(new int[]{a, roomAt[sc][sr + 1]});
                    }
                }
            }
            java.util.Collections.shuffle(edges, rng);
            for (int[] e : edges) {
                boolean joins = find(parent, e[0]) != find(parent, e[1]);
                if (joins || rng.nextDouble() < 0.30) { // tree + extra loops
                    union(parent, e[0], e[1]);
                    carveCorridor(rects.get(e[0]), rects.get(e[1]));
                }
            }
        }

        static int find(int[] parent, int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        static void union(int[] parent, int a, int b) {
            parent[find(parent, a)] = find(parent, b);
        }

        /** An L-shaped corridor: 3 tiles of clearance, platforms on vertical runs. */
        void carveCorridor(int[] a, int[] b) {
            int ac = a[0] + a[2] / 2, ar = a[1] + a[3] / 2;
            int bc = b[0] + b[2] / 2, br = b[1] + b[3] / 2;
            for (int c = Math.min(ac, bc); c <= Math.max(ac, bc); c++) {
                carveRect(c, ar - 1, 1, 3);
            }
            digVerticalRun(bc, Math.min(ar, br), Math.max(ar, br));
        }

        /** A 3-wide vertical run with alternating platforms every 3 rows. */
        void digVerticalRun(int col, int rTop, int rBottom) {
            int platform = id("platform");
            for (int r = rTop; r <= rBottom; r++) {
                carveRect(col - 1, r, 3, 1);
            }
            boolean left = true;
            for (int r = rBottom - 3; r > rTop + 1; r -= 3) {
                int c = left ? col - 1 : col + 1;
                if (inBounds(c, r)) lvl.tiles[r][c] = platform;
                left = !left;
            }
        }

        /** Platformed shafts from the surface down into the network. */
        void digEntryShafts() {
            if (roomCenters.isEmpty()) return;
            int shafts = Math.max(1, w / 160);
            for (int i = 0; i < shafts; i++) {
                int[] room = roomCenters.get(rng.nextInt(roomCenters.size()));
                int col = Math.max(3, Math.min(w - 4, room[0]));
                if (surface[col] > seaLevel) continue; // don't drain the sea in
                digVerticalRun(col, surface[col], room[1]);
            }
        }

        // --- dressing ---------------------------------------------------------

        void placeOres() {
            int stone = id("stone"), deepslate = id("deepslate"), granite = id("granite");
            int veins = w * h / 250;
            for (int i = 0; i < veins; i++) {
                int c = 2 + rng.nextInt(Math.max(1, w - 4));
                int r = surface[c] + 4 + rng.nextInt(Math.max(1, h - surface[c] - 8));
                if (!inBounds(c, r)) continue;
                double depthT = (r - surface[c]) / Math.max(1.0, h - (double) surface[c]);
                int ore = id(oreForDepth(depthT));
                int len = 4 + rng.nextInt(6);
                for (int j = 0; j < len; j++) {
                    int cur = lvl.tileAt(c, r);
                    if (cur == stone || cur == deepslate || cur == granite) {
                        lvl.tiles[r][c] = ore;
                    }
                    c += rng.nextInt(3) - 1;
                    r += rng.nextInt(3) - 1;
                    if (!inBounds(c, r)) break;
                }
            }
        }

        String oreForDepth(double t) {
            double roll = rng.nextDouble();
            if (t < 0.3) return roll < 0.55 ? "coal_ore" : "copper_ore";
            if (t < 0.55) {
                if (roll < 0.35) return "iron_ore";
                if (roll < 0.6) return "coal_ore";
                if (roll < 0.8) return "silver_ore";
                return "emerald_ore";
            }
            if (roll < 0.3) return "gold_ore";
            if (roll < 0.55) return "amethyst_ore";
            if (roll < 0.75) return "ruby_ore";
            return "diamond_ore";
        }

        void floodBottomWithLava() {
            int lava = id("lava");
            for (int r = h - 6; r < h - 1; r++) {
                for (int c = 1; c < w - 1; c++) {
                    if (lvl.tiles[r][c] == 0) lvl.tiles[r][c] = lava;
                }
            }
        }

        /** A basalt frame contains liquids (the top stays open sky). */
        void buildBorder() {
            int basalt = id("basalt");
            for (int c = 0; c < w; c++) lvl.tiles[h - 1][c] = basalt;
            for (int r = Math.min(surface[0], seaLevel); r < h; r++) lvl.tiles[r][0] = basalt;
            for (int r = Math.min(surface[w - 1], seaLevel); r < h; r++) {
                lvl.tiles[r][w - 1] = basalt;
            }
        }

        void lightRooms() {
            int torch = id("torch");
            for (int[] room : roomCenters) {
                int c = room[0], r = room[1];
                if (lvl.tileAt(c, r) == 0 && lvl.solidAt(c, r + 1)) {
                    lvl.tiles[r][c] = torch;
                }
            }
        }

        void dressSurface() {
            String[] trees = {"oak_tree", "pine_tree", "autumn_tree", "dead_tree"};
            int tallGrass = id("tall_grass");
            int[] flowers = {id("flower_red"), id("flower_yellow"), id("flower_blue")};
            int grass = id("grass");
            for (int c = 2; c < w - 2; c++) {
                int r = surface[c];
                if (r > seaLevel || lvl.tileAt(c, r) != grass || lvl.tileAt(c, r - 1) != 0) {
                    continue;
                }
                double x = (c + 0.5) * ts, y = r * (double) ts;
                double roll = rng.nextDouble();
                if (roll < 0.09) {
                    lvl.entities.add(new Level.EntitySpawn("decor_bg",
                            trees[rng.nextInt(trees.length)], x, y));
                } else if (roll < 0.14) {
                    lvl.entities.add(new Level.EntitySpawn("decor_bg",
                            rng.nextBoolean() ? "bush" : "berry_bush", x, y));
                } else if (roll < 0.19) {
                    lvl.entities.add(new Level.EntitySpawn(
                            rng.nextInt(3) == 0 ? "decor_fg" : "decor_bg",
                            rng.nextBoolean() ? "boulder" : "stones", x, y));
                } else if (roll < 0.27) {
                    lvl.tiles[r - 1][c] = flowers[rng.nextInt(flowers.length)];
                } else if (roll < 0.36) {
                    lvl.tiles[r - 1][c] = tallGrass;
                }
                // Face-attached surface details generate with the terrain:
                // tufts and wildflowers on grass tops (they hide automatically
                // if the player builds over them — OPEN_FACE visibility).
                double detail = rng.nextDouble();
                if (detail < 0.22) {
                    lvl.surfaceDecor.add(new com.larsons.engine.world.SurfaceDecor.Placement(
                            c, r, com.larsons.engine.world.SurfaceDecor.Face.UP,
                            detail < 0.16 ? "grass_tuft" : "wildflowers", false,
                            com.larsons.engine.world.SurfaceDecor.Visibility.OPEN_FACE));
                }
            }
        }

        void dressCaves() {
            int glow = id("glow_mushroom"), shroom = id("mushroom");
            for (int c = 2; c < w - 2; c++) {
                for (int r = surface[c] + 6; r < h - 7; r++) {
                    if (lvl.tiles[r][c] != 0 || !lvl.solidAt(c, r + 1)) continue;
                    double roll = rng.nextDouble();
                    if (roll < 0.02) {
                        lvl.tiles[r][c] = glow;
                    } else if (roll < 0.035) {
                        lvl.tiles[r][c] = shroom;
                    } else if (roll < 0.05) {
                        lvl.entities.add(new Level.EntitySpawn("decor_bg",
                                rng.nextBoolean() ? "stalagmite" : "glow_crystal",
                                (c + 0.5) * ts, (r + 1) * (double) ts));
                    }
                    // Cave-ceiling details on the block above an air pocket.
                    if (r > 0 && lvl.solidAt(c, r - 1) && rng.nextDouble() < 0.06) {
                        String key = switch (rng.nextInt(3)) {
                            case 0 -> "hanging_moss";
                            case 1 -> "dripstone";
                            default -> "roots";
                        };
                        lvl.surfaceDecor.add(new com.larsons.engine.world.SurfaceDecor.Placement(
                                c, r - 1, com.larsons.engine.world.SurfaceDecor.Face.DOWN,
                                key, false,
                                com.larsons.engine.world.SurfaceDecor.Visibility.OPEN_FACE));
                    }
                }
            }
        }

        void placeTreasure() {
            String[] shallow = {"bread", "apple", "rope", "arrow", "rock"};
            String[] mid = {"health_potion", "wooden_bow", "iron_sword", "cheese", "arrow"};
            String[] deep = {"gold_key", "diamond", "steel_sword", "scroll", "golden_apple",
                    "feather_charm", "sky_totem"};
            for (int[] room : roomCenters) {
                if (rng.nextDouble() > 0.35) continue;
                double t = room[1] / (double) h;
                String[] pool = t < 0.55 ? shallow : t < 0.8 ? mid : deep;
                int count = 1 + rng.nextInt(2);
                for (int i = 0; i < count; i++) {
                    lvl.entities.add(new Level.EntitySpawn("item",
                            pool[rng.nextInt(pool.length)],
                            (room[0] + i) * (double) ts, (room[1] - 1) * (double) ts));
                }
            }
        }

        void placeMobs() {
            String[] shallow = {"slime", "goblin", "spider"};
            String[] deepMobs = {"zombie", "skeleton", "bat", "mage"};
            for (int[] room : roomCenters) {
                if (rng.nextDouble() > 0.55) continue;
                String[] pool = room[1] < h * 0.65 ? shallow : deepMobs;
                lvl.entities.add(new Level.EntitySpawn("mob",
                        pool[rng.nextInt(pool.length)],
                        (room[0] + 2) * (double) ts, (room[1] - 2) * (double) ts));
            }
            String[] passive = {"sheep", "rabbit", "deer", "fox", "pig"};
            for (int c = 6; c < w - 6; c += 20 + rng.nextInt(16)) {
                if (surface[c] > seaLevel) continue;
                lvl.entities.add(new Level.EntitySpawn("mob",
                        passive[rng.nextInt(passive.length)],
                        c * (double) ts, (surface[c] - 2) * (double) ts));
            }
        }

        void placeSpawns() {
            int spawnCol = dryColumnNear((int) (w * 0.08));
            lvl.spawnX = spawnCol * (double) ts;
            lvl.spawnY = (surface[spawnCol] - 3) * (double) ts;
            double[] spots = {0.15, 0.4, 0.6, 0.85};
            for (double s : spots) {
                int c = dryColumnNear((int) (w * s));
                lvl.entities.add(new Level.EntitySpawn("mp_spawn", "mp_spawn",
                        c * (double) ts, (surface[c] - 2) * (double) ts));
            }
        }

        /** The dry (above-sea) surface column closest to {@code want}. */
        int dryColumnNear(int want) {
            want = Math.max(2, Math.min(w - 3, want));
            for (int d = 0; d < w; d++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    int c = want + d * sign;
                    if (c >= 2 && c <= w - 3 && surface[c] <= seaLevel) return c;
                }
            }
            return want;
        }
    }
}
