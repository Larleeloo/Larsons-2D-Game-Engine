package com.larsons.engine.demo;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.gen.Biome;
import com.larsons.engine.world.gen.TerrainSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings-menu rows for a level's procedural world: the world itself, and
 * then the biomes it is built out of.
 *
 * <p><b>One biome at a time.</b> Eighteen biomes with thirty editable
 * properties each is five hundred rows, which is not a settings menu, it is a
 * spreadsheet nobody scrolls to the bottom of. So there is a biome
 * <em>picker</em> and then the properties of whichever biome it is pointing at:
 * every row below the picker reads the current selection when it is drawn, so
 * moving the picker changes what the rows are editing without rebuilding
 * anything. The two rows that <em>do</em> rebuild are the ones that change how
 * many biomes there are — the {@code +} buttons and the delete — because the
 * picker itself is a list of them.
 *
 * <p><b>Blocks are picked, not typed.</b> Every block-valued property is a
 * cycler over the level's own registry, custom blocks included, so a biome can
 * never name a block that is not there and a creator who adds a block through
 * the editor's <em>+ New Block</em> form can build a biome out of it
 * immediately.
 */
final class TerrainForms {

    /** How the "no block here" entry of a block cycler reads. */
    private static final String NONE = "(none)";

    private TerrainForms() {}

    /**
     * Add the whole terrain section to {@code form}.
     *
     * @param rebuild what to call when the list of rows itself has to change —
     *                adding or deleting a biome, or a decoration slot. Never
     *                {@code null}; pass a no-op where the form is built once.
     */
    static void addTerrainOptions(ConfigForm form, GameProfile p, Runnable rebuild) {
        if (p.terrain == null) p.terrain = new TerrainSettings();
        TerrainSettings t = p.terrain;
        Runnable refresh = rebuild != null ? rebuild : () -> {};

        form.addNote("Infinite procedural terrain — the world beyond this "
                + "level's own bounds, generated as you walk into it. Turn it on "
                + "and the level is moved into the middle of a world "
                + t.worldSize + " blocks across; turn it off again and "
                + "generation stops, keeping the ground you have already "
                + "explored.");
        form.addToggle("Infinite terrain", () -> t.enabled, v -> t.enabled = v);

        // The seed is a text field rather than a stepper because it is a
        // number people copy to each other, not one they nudge.
        form.addText("World seed", () -> Long.toString(t.seed), v -> {
            try {
                t.seed = v == null || v.isBlank() ? 0 : Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                // Anything that is not a number is a name for a world, which is
                // a perfectly good way to choose a seed.
                t.seed = v.hashCode();
            }
        }, 20).enabledWhen(() -> t.enabled);
        form.addAction("Roll a new seed", () -> t.seed = System.nanoTime() | 1L);
        form.addNote("Two levels with the same seed and the same biomes are the "
                + "same world, block for block. 0 means \"choose one when the "
                + "world is first built\".");

        form.addInt("World size (blocks per side)", () -> t.worldSize,
                        v -> t.worldSize = v, TerrainSettings.MIN_WORLD_SIZE,
                        TerrainSettings.MAX_WORLD_SIZE, 1024)
                .enabledWhen(() -> t.enabled);
        form.addSlider("Render distance (blocks)", () -> t.renderDistance,
                        v -> t.renderDistance = v, 2, TerrainSettings.MAX_RENDER_DISTANCE)
                .enabledWhen(() -> t.enabled);
        form.addInt("Distant generation (blocks)", () -> t.distantDistance,
                        v -> t.distantDistance = v, 0,
                        TerrainSettings.MAX_DISTANT_DISTANCE, 64)
                .enabledWhen(() -> t.enabled);
        form.addNote("Render distance is what is drawn block by block. Distant "
                + "generation is the horizon behind it, drawn as landforms — "
                + "cheap enough to reach "
                + TerrainSettings.MAX_DISTANT_DISTANCE + " blocks. The horizon "
                + "also needs \"Distant terrain\" on in Options, which is the "
                + "player's own say over what their machine draws.");

        form.addInt("Sea level (layer)", () -> t.seaLevel, v -> t.seaLevel = v,
                1, TerrainSettings.MAX_LEVEL - 2, 1).enabledWhen(() -> t.enabled);
        form.addInt("Ground level (layer)", () -> t.groundLevel, v -> t.groundLevel = v,
                2, TerrainSettings.MAX_LEVEL - 1, 1).enabledWhen(() -> t.enabled);
        form.addNote("Land starts at the ground level and climbs; everything at "
                + "or below sea level floods. The height axis runs 0 to "
                + TerrainSettings.MAX_LEVEL + ", so a biome can aim anywhere in "
                + "that range.");

        form.addInt("Landform scale (%)", () -> t.terrainScale, v -> t.terrainScale = v,
                10, 400, 10).enabledWhen(() -> t.enabled);
        form.addInt("Biome scale (%)", () -> t.biomeScale, v -> t.biomeScale = v,
                10, 400, 10).enabledWhen(() -> t.enabled);
        form.addSlider("Cave density", () -> t.caveDensity, v -> t.caveDensity = v, 0, 100)
                .enabledWhen(() -> t.enabled);
        form.addSlider("Ore richness", () -> t.oreRichness, v -> t.oreRichness = v, 0, 100)
                .enabledWhen(() -> t.enabled);
        form.addInt("Decoration density (%)", () -> t.decorDensity,
                v -> t.decorDensity = v, 0, 400, 10).enabledWhen(() -> t.enabled);

        form.addInt("Chunks kept loaded", () -> t.loadedChunkBudget,
                v -> t.loadedChunkBudget = v, 64, 8192, 64).enabledWhen(() -> t.enabled);
        form.addInt("Chunks built ahead", () -> t.prefetchRadius,
                v -> t.prefetchRadius = v, 0, 32, 1).enabledWhen(() -> t.enabled);
        form.addInt("Generator threads", () -> t.workerThreads,
                v -> t.workerThreads = v, 1, 8, 1).enabledWhen(() -> t.enabled);
        form.addToggle("Save every explored chunk", () -> t.saveExplored,
                v -> t.saveExplored = v).enabledWhen(() -> t.enabled);
        form.addNote("Chunks you have changed are always saved. Explored ground "
                + "that you have not changed is not — it is a function of the "
                + "seed and grows back identical, so saving it would store a "
                + "megabyte to say what the seed already says. Turn this on to "
                + "write it down anyway, and the world stops dropping chunks "
                + "behind you.");

        addBiomeOptions(form, t, refresh);
    }

    /** The biome picker, the {@code +} buttons, and the selected biome's rows. */
    private static void addBiomeOptions(ConfigForm form, TerrainSettings t, Runnable rebuild) {
        form.addNote("Biomes — the recipes the world is built from. Pick one to "
                + "edit it, or add your own with a \"+\" button. Everything "
                + "below the picker edits whichever biome it is pointing at.");

        if (t.biomes.isEmpty()) t.addBiome(Biome.Realm.SURFACE);
        Biome[] all = t.biomes.toArray(new Biome[0]);
        // The selection is held here rather than on the settings: which biome a
        // creator happens to be looking at is not part of the level.
        Biome[] chosen = {all[0]};

        form.addEnum("Biome", all, () -> chosen[0], b -> {
            if (b != null) chosen[0] = b;
        });
        form.addAction("+ New above-ground biome", () -> {
            chosen[0] = t.addBiome(Biome.Realm.SURFACE);
            rebuild.run();
        });
        form.addAction("+ New below-ground biome", () -> {
            chosen[0] = t.addBiome(Biome.Realm.UNDERGROUND);
            rebuild.run();
        });
        form.addAction("Delete this biome", () -> {
            if (t.biomes.size() <= 1) return;
            t.biomes.remove(chosen[0]);
            chosen[0] = t.biomes.get(0);
            rebuild.run();
        });
        form.addAction("Restore the standard biomes", () -> {
            t.biomes = com.larsons.engine.world.gen.Biomes.standard();
            chosen[0] = t.biomes.get(0);
            rebuild.run();
        });

        String[] blocks = blockChoices();
        java.util.function.Supplier<Biome> b = () -> chosen[0];

        form.addText("· Name", () -> b.get().name, v -> b.get().name = v, 28);
        form.addToggle("· Generate this biome", () -> b.get().enabled,
                v -> b.get().enabled = v);
        form.addEnum("· Where it generates", Biome.Realm.values(),
                () -> b.get().realm, v -> b.get().realm = v);
        form.addSlider("· Likelihood (0-100)", () -> b.get().weight,
                v -> b.get().weight = v, 0, 100);
        form.addSlider("· Target generation level", () -> b.get().targetLevel,
                v -> b.get().targetLevel = v, 0, TerrainSettings.MAX_LEVEL);
        form.addSlider("· Height variation", () -> b.get().relief,
                v -> b.get().relief = v, 0, 200);
        form.addNote("Above ground the target level is the height its land aims "
                + "for and the variation is how far it wanders. Below ground "
                + "they are the depth its caverns centre on and how deep a band "
                + "they fill — which is what keeps lava at the bottom of the "
                + "world and ice caves up near the rock.");
        form.addSlider("· Temperature (0-100)", () -> b.get().temperature,
                v -> b.get().temperature = v, 0, 100);
        form.addSlider("· Humidity (0-100)", () -> b.get().humidity,
                v -> b.get().humidity = v, 0, 100);
        form.addNote("Climate is where a biome belongs, not how often it wins: "
                + "the world's own temperature and rainfall decide which biomes "
                + "fit a region, and the likelihood decides between the ones "
                + "that do.");

        blockRow(form, "· Surface block", blocks, () -> b.get().surfaceBlock,
                v -> b.get().surfaceBlock = v);
        blockRow(form, "· Under the surface", blocks, () -> b.get().subsurfaceBlock,
                v -> b.get().subsurfaceBlock = v);
        form.addInt("· Soil depth (layers)", () -> b.get().soilDepth,
                v -> b.get().soilDepth = v, 0, 32, 1);
        blockRow(form, "· Stone", blocks, () -> b.get().stoneBlock,
                v -> b.get().stoneBlock = v);
        blockRow(form, "· Shoreline block", blocks, () -> b.get().beachBlock,
                v -> b.get().beachBlock = v);
        blockRow(form, "· Liquid", blocks, () -> b.get().liquidBlock,
                v -> b.get().liquidBlock = v);
        form.addInt("· Liquid level offset", () -> b.get().liquidLevelOffset,
                v -> b.get().liquidLevelOffset = v, -256, 256, 2);
        form.addToggle("· Snowy (snow cap, frozen water)", () -> b.get().snowy,
                v -> b.get().snowy = v);

        form.addSlider("· Tree density", () -> b.get().treeDensity,
                v -> b.get().treeDensity = v, 0, 100);
        blockRow(form, "· Trunk block", blocks, () -> b.get().logBlock,
                v -> b.get().logBlock = v);
        blockRow(form, "· Canopy block", blocks, () -> b.get().leafBlock,
                v -> b.get().leafBlock = v);
        form.addInt("· Shortest tree", () -> b.get().treeMinHeight,
                v -> b.get().treeMinHeight = v, 1, 40, 1);
        form.addInt("· Tallest tree", () -> b.get().treeMaxHeight,
                v -> b.get().treeMaxHeight = v, 1, 40, 1);

        form.addSlider("· Ground cover density", () -> b.get().grassDensity,
                v -> b.get().grassDensity = v, 0, 100);
        form.addSlider("· Decoration density", () -> b.get().decorDensity,
                v -> b.get().decorDensity = v, 0, 100);
        List<String> decor = b.get().decorBlocks;
        for (int i = 0; i < decor.size(); i++) {
            int slot = i;
            blockRow(form, "· Decoration " + (i + 1), blocks,
                    () -> slot < b.get().decorBlocks.size()
                            ? b.get().decorBlocks.get(slot) : "",
                    v -> {
                        List<String> list = b.get().decorBlocks;
                        if (slot < list.size()) list.set(slot, v);
                    });
        }
        form.addAction("+ Add a decoration", () -> {
            b.get().decorBlocks.add("tall_grass");
            rebuild.run();
        });
        form.addAction("Remove the last decoration", () -> {
            List<String> list = b.get().decorBlocks;
            if (!list.isEmpty()) list.remove(list.size() - 1);
            rebuild.run();
        });
        form.addNote("Decorations are the one-block details scattered across "
                + "the biome — flowers, bushes, coral, crystals. Underground "
                + "they land on cave floors instead.");

        form.addSlider("· Cave density", () -> b.get().caveDensity,
                v -> b.get().caveDensity = v, 0, 100);
        form.addSlider("· Ore richness", () -> b.get().oreRichness,
                v -> b.get().oreRichness = v, 0, 100);
        blockRow(form, "· Glowing block", blocks, () -> b.get().lightBlock,
                v -> b.get().lightBlock = v);
        form.addSlider("· Glowing block density", () -> b.get().lightDensity,
                v -> b.get().lightDensity = v, 0, 100);

        form.addSlider("· Building density", () -> b.get().structureDensity,
                v -> b.get().structureDensity = v, 0, 100);
        blockRow(form, "· Building walls", blocks, () -> b.get().structureBlock,
                v -> b.get().structureBlock = v);
        blockRow(form, "· Building floor & roof", blocks,
                () -> b.get().structureAccentBlock,
                v -> b.get().structureAccentBlock = v);
        form.addNote("Buildings are what makes a village a village and an "
                + "ancient city a ruin: a biome with a wall block and some "
                + "density scatters plots across itself, levels the ground "
                + "under each one and builds on it. Denser also means bigger.");
    }

    /** A cycler over every block in the registry, plus "no block at all". */
    private static void blockRow(ConfigForm form, String label, String[] choices,
                                 java.util.function.Supplier<String> get,
                                 java.util.function.Consumer<String> set) {
        form.addEnum(label, choices,
                () -> {
                    String key = get.get();
                    return key == null || key.isBlank() ? NONE : key;
                },
                v -> set.accept(NONE.equals(v) ? "" : v));
    }

    /** Every registered block key, in registration order, behind a "(none)". */
    private static String[] blockChoices() {
        List<String> keys = new ArrayList<>();
        keys.add(NONE);
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // simulation artifacts, never painted
            keys.add(b.key());
        }
        return keys.toArray(new String[0]);
    }
}
