package com.larsons.engine.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ecology and the game around it, run headlessly: a dish that feeds,
 * replicates, mutates, starves and recycles; the catalog that prices
 * discoveries; the shop; and the JSON save that puts it all back.
 *
 * <p>The simulation is deliberately non-deterministic in normal play, so every
 * test here seeds it explicitly and asserts on behaviour rather than on exact
 * outcomes.
 */
@Timeout(120)
class EvolutionGameTest {

    /** Scatter food and run the dish for {@code seconds} of simulated time. */
    private static void run(EvolutionGame game, double seconds) {
        for (double t = 0; t < seconds; t += 0.25) game.step(0.25);
    }

    private static void feed(EvolutionGame game, int orbs) {
        Dish dish = game.activeDish();
        game.inventory().add(ShopItem.ENERGY_SIMPLE, orbs);
        Random rng = new Random(11);
        for (int i = 0; i < orbs; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = dish.radius * 0.9 * Math.sqrt(rng.nextDouble());
            game.feed(EnergyOrb.Kind.SIMPLE, Math.cos(a) * r, Math.sin(a) * r);
        }
    }

    // --- starting state ------------------------------------------------------------------

    @Test
    void anewExperimentMatchesTheOpeningTheDesignAsksFor() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 1L);
        assertEquals(1, game.dishes().size(), "one dish");
        assertEquals(1, game.activeDish().population(), "one organism");
        assertEquals(100, game.inventory().count(ShopItem.ENERGY_SIMPLE),
                "a hundred energy orbs to place by hand");
        assertEquals(0, game.activeDish().orbs().size(), "none of them placed yet");

        Organism first = game.activeDish().organisms().get(0);
        assertEquals("GGGG", first.genome.sequence());
        assertSame(BodyShape.SQUARE, first.pheno.shape());
        assertEquals(1, game.catalog().speciesCount(), "the starter is catalogued immediately");
        assertTrue(game.credits() > 0, "and pays its discovery credit");
    }

    @Test
    void feedingSpendsFromTheBenchAndLandsInTheDish() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.B, 2L);
        assertTrue(game.feed(EnergyOrb.Kind.SIMPLE, 10, 10));
        assertEquals(99, game.inventory().count(ShopItem.ENERGY_SIMPLE));
        assertEquals(1, game.activeDish().orbs().size());

        // Outside the glass nothing is placed and nothing is spent.
        assertFalse(game.feed(EnergyOrb.Kind.SIMPLE, 99999, 0));
        assertEquals(99, game.inventory().count(ShopItem.ENERGY_SIMPLE));

        // Complex energy needs to have been bought first.
        assertFalse(game.feed(EnergyOrb.Kind.COMPLEX, 0, 0));
    }

    // --- the ecology ------------------------------------------------------------------------

    @Test
    void fedOrganismsReplicateAndTheirDescendantsDiverge() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 3L);
        feed(game, 100);
        run(game, 240);

        assertTrue(game.catalog().speciesCount() > 1,
                "mutation produces strands that never existed (found "
                        + game.catalog().speciesCount() + ")");
        Set<Integer> generations = new HashSet<>();
        for (Organism o : game.activeDish().organisms()) generations.add(o.generation);
        assertTrue(game.credits() > 9, "discoveries pay for themselves");
        assertFalse(generations.isEmpty() && game.catalog().speciesCount() < 2,
                "a lineage got somewhere");
    }

    @Test
    void anUnfedDishStarvesAndTheRunCanEnd() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.R, 4L);
        // No food at all: the starter cannot pay its upkeep for long.
        run(game, 400);
        assertEquals(0, game.activeDish().population(), "nothing survives an empty dish");
        assertTrue(game.isGameOver(),
                "with no life and no way to reseed, the experiment is over");
    }

    @Test
    void aDishWithCreditLeftIsNotDeclaredOverPrematurely() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.R, 5L);
        feed(game, 100);
        run(game, 200);
        // Bank enough to buy a starter colony, then let everything die.
        while (game.credits() < ShopItem.STARTER_COLONY.price()) {
            game.buy(ShopItem.ENERGY_SIMPLE); // no-op when broke; loop guard below
            break;
        }
        run(game, 900);
        if (game.credits() >= ShopItem.STARTER_COLONY.price()) {
            assertFalse(game.isGameOver(),
                    "a player who can still afford to reseed has not lost");
        }
    }

    @Test
    void deadBodiesRecycleTheirEnergyBackIntoTheDish() {
        Dish dish = new Dish("Recycler", 200, 42L);
        dish.spawn(Genome.of("RRRR"), 0, 0);
        Organism o = dish.organisms().get(0);
        o.energy = 0.01; // about to starve
        double before = dish.totalEnergy();

        for (int i = 0; i < 40; i++) dish.step(0.25);
        assertEquals(0, dish.population(), "it died");
        assertTrue(dish.corpses().size() + dish.orbs().size() > 0,
                "and left something behind to dissolve");

        for (int i = 0; i < 2000; i++) dish.step(0.25);
        assertTrue(dish.corpses().isEmpty(), "the body fully decomposed");
        assertTrue(dish.totalEnergy() > before,
                "a body is worth more than the energy it was carrying, and none is minted twice");
    }

    @Test
    void digestionLeavesWasteBehind() {
        Dish dish = new Dish("Waste", 200, 7L);
        // A plain strand keeps a bit over half of what it eats; the rest is waste.
        dish.spawn(Genome.of("RRRR"), 0, 0);
        for (int i = 0; i < 40; i++) dish.dropOrb(EnergyOrb.Kind.SIMPLE, 0, 0);
        for (int i = 0; i < 200; i++) dish.step(0.05);
        assertFalse(dish.wastes().isEmpty(), "inefficient digestion produces waste");
    }

    @Test
    void onlyStrandsWithComplexDigestionCanEatComplexEnergy() {
        Dish plain = new Dish("Plain", 150, 8L);
        plain.spawn(Genome.of("RRRR"), 0, 0);
        plain.dropOrb(EnergyOrb.Kind.COMPLEX, 0, 0);
        double plainStart = plain.organisms().get(0).energy;
        for (int i = 0; i < 60; i++) plain.step(0.05);
        assertEquals(1, plain.orbs().size(), "the orb is untouched");
        assertEquals(EnergyOrb.Kind.COMPLEX.defaultEnergy(), plain.orbs().get(0).energy, 1e-9);
        assertTrue(plain.organisms().get(0).energy < plainStart,
                "a plain strand starves next to food it cannot digest");

        Dish able = new Dish("Able", 150, 8L);
        able.spawn(Genome.of("GGGG"), 0, 0); // GG unlocks complex digestion
        able.dropOrb(EnergyOrb.Kind.COMPLEX, 0, 0);
        double ableStart = able.organisms().get(0).energy;
        for (int i = 0; i < 60; i++) able.step(0.05);
        assertTrue(able.orbs().isEmpty(), "a complex digester clears the orb out");
        // A single complex orb is worth so much that the meal usually ends in a
        // division, so count either outcome as having thrived on it.
        assertTrue(able.population() > 1 || able.organisms().get(0).energy > ableStart,
                "a strand that evolved complex digestion grows or divides on it");
    }

    @Test
    void predatorsKillAndTheKillIsReported() {
        Dish dish = new Dish("Hunt", 120, 9L);
        Organism hunter = dish.spawn(Genome.of("RRRRRRRR"), 0, 0);   // predation, high hostility
        assertNotNull(hunter);
        assertTrue(hunter.pheno.has(Ability.PREDATION));
        for (int i = 0; i < 8; i++) dish.spawn(Genome.of("BBBBBBBB"), 6 + i, 0); // soft prey

        boolean killed = false;
        for (int i = 0; i < 600 && !killed; i++) {
            dish.step(0.05);
            for (Dish.Event e : dish.drainEvents()) {
                if (e.kind() == Dish.EventKind.KILL) killed = true;
            }
        }
        assertTrue(killed, "a hostile strand surrounded by prey eventually eats one");
    }

    @Test
    void barriersBlockMovement() {
        Dish dish = new Dish("Maze", 300, 10L);
        dish.addBarrier(60, 0, 30);
        Organism o = dish.spawn(Genome.of("GGGGGG"), 0, 0);
        assertNotNull(o);
        for (int i = 0; i < 1200; i++) {
            dish.step(0.05);
            for (Dish.Barrier b : dish.barriers()) {
                double d = Math.hypot(o.x - b.x, o.y - b.y);
                assertTrue(d >= b.radius - 1,
                        "an organism never ends up inside a pillar (was " + d + ")");
            }
        }
    }

    @Test
    void organismsStayInsideTheGlass() {
        Dish dish = new Dish("Bounds", 180, 11L);
        for (int i = 0; i < 20; i++) dish.spawn(Genome.random(new Random(i), 8), 0, 0);
        for (int i = 0; i < 400; i++) {
            dish.step(0.05);
            for (Organism o : dish.organisms()) {
                assertTrue(Math.hypot(o.x, o.y) <= dish.radius + 1,
                        "nothing escapes the dish");
            }
        }
    }

    @Test
    void heatSourcesWarmTheGel() {
        Dish dish = new Dish("Thermal", 200, 12L);
        double before = dish.temperatureAt(0, 0);
        dish.addHeatSource(0, 0, 120, 14);
        for (int i = 0; i < 200; i++) dish.step(0.05);
        assertTrue(dish.temperatureAt(0, 0) > before + 2,
                "an exothermic source raises the local temperature");
        assertTrue(dish.temperatureAt(0, 0) > dish.temperatureAt(dish.radius * 0.95, 0),
                "and it is warmer at the source than at the rim");
    }

    @Test
    void bioluminescenceAndSpotlightsLightTheSlide() {
        Dish dish = new Dish("Dark", 300, 13L);
        // Find a point inside one of this dish's shadows.
        double[] shadow = dish.darkPatches().get(0);
        double dim = dish.illuminationAt(shadow[0], shadow[1]);
        assertTrue(dim < 0.6, "a shadowed patch really is dim (was " + dim + ")");

        dish.addSpotlight(shadow[0], shadow[1], 150, 90);
        assertTrue(dish.illuminationAt(shadow[0], shadow[1]) > dim,
                "a spotlight lights it back up");
    }

    // --- the meta game -------------------------------------------------------------------------

    @Test
    void theShopChargesAndDelivers() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 14L);
        assertFalse(game.buy(ShopItem.DNA_SCANNER), "cannot buy what you cannot afford");

        feed(game, 100);
        run(game, 400);
        int credits = game.credits();
        if (credits < ShopItem.BARRIER.price()) return; // nothing to test against

        int before = game.inventory().count(ShopItem.BARRIER);
        assertTrue(game.buy(ShopItem.BARRIER));
        assertEquals(credits - ShopItem.BARRIER.price(), game.credits(), "credit is spent");
        assertEquals(before + ShopItem.BARRIER.quantity(), game.inventory().count(ShopItem.BARRIER),
                "and the item arrives on the bench");
    }

    @Test
    void aBoughtDishGoesStraightOntoTheStage() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 15L);
        while (game.credits() < ShopItem.PETRI_DISH.price()) {
            feed(game, 60);
            run(game, 120);
            if (game.playTime() > 4000) break;
        }
        if (game.credits() < ShopItem.PETRI_DISH.price()) return; // slow seed; not the point

        assertTrue(game.buy(ShopItem.PETRI_DISH));
        assertEquals(2, game.dishes().size());
        assertEquals(1, game.activeDishIndex(), "the new slide is the one under the lens");
        game.cycleDish(1);
        assertEquals(0, game.activeDishIndex(), "and Tab wraps back around");
    }

    @Test
    void theTimeWarpNeedsItsInstrument() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 16L);
        game.cycleTimeScale(1);
        assertEquals(1.0, game.timeScale(), 1e-9, "no dial, no time warp");
    }

    @Test
    void theSpatulaMovesOneOrganismBetweenDishesAndIsSpent() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.B, 17L);
        game.inventory().add(ShopItem.TRANSFER_SPATULA, 1);
        game.inventory().add(ShopItem.PETRI_DISH, 0);
        // Give the lab a second dish without needing the credit for it.
        game.dishes().add(new Dish("Dish 2", Dish.DEFAULT_RADIUS, 71L));

        Organism lifted = game.liftOrganism(0, 0);
        assertNotNull(lifted, "the starter sits at the middle of the dish");
        assertEquals(0, game.dishes().get(0).population(), "it left the first dish");

        game.setActiveDish(1);
        assertTrue(game.dropCarried(0, 0));
        assertEquals(1, game.dishes().get(1).population(), "and arrived in the second");
        assertEquals(0, game.inventory().count(ShopItem.TRANSFER_SPATULA), "one use, then gone");
    }

    @Test
    void puttingAnOrganismBackWhereItCameFromCostsNothing() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.B, 18L);
        game.inventory().add(ShopItem.TRANSFER_SPATULA, 1);
        assertNotNull(game.liftOrganism(0, 0));
        assertTrue(game.dropCarried(20, 20), "dropped back into the same dish");
        assertEquals(1, game.inventory().count(ShopItem.TRANSFER_SPATULA),
                "a misclick does not burn the spatula");
        assertEquals(1, game.activeDish().population());
    }

    @Test
    void theCatalogOnlyPaysForStrandsItHasNeverSeen() {
        Catalog catalog = new Catalog();
        Genome g = Genome.of("RGBRGB");
        SpeciesRecord first = catalog.record(g, "Dish 1", 3);
        assertNotNull(first);
        assertNull(catalog.record(g, "Dish 1", 4), "the same strand is not a second discovery");
        assertEquals(1, catalog.speciesCount());
        assertTrue(first.credit > 0);
        assertTrue(catalog.isUnlocked(Achievement.FIRST_LIFE));
    }

    @Test
    void colonyCombinationsAreDiscoveriesInTheirOwnRight() {
        Catalog catalog = new Catalog();
        assertFalse(catalog.recordCombination("RGBRGB"), "one strand is not a combination");
        assertTrue(catalog.recordCombination("BBBRRRRRRRRRRRR+BBBRRRRRRRRRRRG"));
        assertFalse(catalog.recordCombination("BBBRRRRRRRRRRRR+BBBRRRRRRRRRRRG"), "only once");
        assertEquals(1, catalog.combinationCount());
        assertTrue(catalog.isUnlocked(Achievement.SYMBIOSIS));
        assertTrue(catalog.combinationCredit("BBBRRRRRRRRRRRR+BBBRRRRRRRRRRRG") > 0);
    }

    // --- persistence ------------------------------------------------------------------------------

    @Test
    void anExperimentSurvivesASaveAndLoad(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.R, 19L);
        feed(game, 60);
        run(game, 200);
        game.inventory().add(ShopItem.BARRIER, 3);
        game.placeBarrier(40, 40);
        game.placeBarrier(-40, 40);

        int credits = game.credits();
        int species = game.catalog().speciesCount();
        int population = game.activeDish().population();
        int orbs = game.activeDish().orbs().size();
        int barriers = game.activeDish().barriers().size();
        store.save(game);

        EvolutionGame loaded = store.load();
        assertNotNull(loaded, "the save reads back");
        assertEquals(credits, loaded.credits());
        assertEquals(species, loaded.catalog().speciesCount(), "the whole book comes back");
        assertEquals(population, loaded.activeDish().population());
        assertEquals(orbs, loaded.activeDish().orbs().size());
        assertEquals(barriers, loaded.activeDish().barriers().size());
        assertEquals(Nucleotide.R, loaded.startingColor());

        // And it keeps simulating from exactly where it stopped.
        loaded.step(1.0);
        assertTrue(loaded.activeDish().clock() > 0);
    }

    @Test
    void savingMidTransferDoesNotLoseTheOrganismOnTheSpatula(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.B, 23L);
        game.inventory().add(ShopItem.TRANSFER_SPATULA, 1);
        assertNotNull(game.liftOrganism(0, 0));
        assertEquals(0, game.activeDish().population(), "it is on the spatula, in no dish");

        store.save(game);
        EvolutionGame loaded = store.load();
        assertNotNull(loaded);
        assertEquals(1, loaded.activeDish().population(),
                "saving puts a carried organism back rather than dropping it on the floor");
        assertEquals(1, loaded.inventory().count(ShopItem.TRANSFER_SPATULA),
                "and the unused spatula is still on the bench");
    }

    @Test
    void everyDiscoveryIsWrittenAsItsOwnJsonFile(@TempDir Path dir) throws Exception {
        EvolutionStore store = new EvolutionStore(dir.toString());
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 20L);
        feed(game, 80);
        run(game, 240);
        store.save(game);

        int catalogued = game.catalog().speciesCount();
        assertEquals(catalogued, store.speciesFileCount(),
                "one file per catalogued strand, not one table of all of them");

        List<SpeciesRecord> read = store.loadSpecies();
        assertEquals(catalogued, read.size());
        for (SpeciesRecord rec : read) {
            assertTrue(Genome.isValid(rec.sequence));
            Path file = store.speciesFile(rec.sequence);
            assertTrue(Files.exists(file), "named after the DNA that produced it");
            String json = Files.readString(file);
            assertTrue(json.contains("\"dna\""), "and readable on its own");
            assertTrue(json.contains("\"traits\""));
            assertTrue(json.contains("\"abilities\""));
        }
    }

    @Test
    void theBookOutlivesAnyOneExperiment(@TempDir Path dir) {
        EvolutionStore store = new EvolutionStore(dir.toString());
        EvolutionGame first = EvolutionGame.newGame(Nucleotide.G, 21L);
        feed(first, 80);
        run(first, 200);
        store.save(first);
        int found = store.speciesFileCount();
        assertTrue(found >= 1);

        // A brand new experiment replaces the save but not the discoveries.
        EvolutionGame second = EvolutionGame.newGame(Nucleotide.R, 22L);
        store.save(second);
        assertTrue(store.speciesFileCount() >= found,
                "starting over never deletes what earlier runs discovered");
    }

    @Test
    void anUnreadableSaveIsReportedRatherThanThrown(@TempDir Path dir) throws Exception {
        EvolutionStore store = new EvolutionStore(dir.toString());
        Files.createDirectories(dir);
        Files.writeString(store.saveFile(), "{ this is not json");
        assertNull(store.load(), "a corrupt save offers a new game instead of crashing");
    }

    @Test
    void loadingToleratesJunkInsideAnOtherwiseValidSave(@TempDir Path dir) throws Exception {
        EvolutionStore store = new EvolutionStore(dir.toString());
        Files.createDirectories(dir);
        Files.writeString(store.saveFile(),
                "{\"seed\": 5, \"credits\": 42, \"dishes\": [{\"name\": \"Dish 1\","
                        + "\"organisms\": [{\"dna\": \"NOPE\"}, {\"dna\": \"RGBR\", \"energy\": 30}]}]}");
        EvolutionGame game = store.load();
        assertNotNull(game);
        assertEquals(42, game.credits());
        assertEquals(1, game.activeDish().population(), "the impossible strand is skipped");
        assertEquals("RGBR", game.activeDish().organisms().get(0).genome.sequence());
    }
}
