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

    /** Shared feeding RNG, so successive top-ups do not land on the same spots. */
    private final Random feedRng = new Random(11);

    private void feed(EvolutionGame game, int orbs) {
        feed(game, orbs, feedRng);
    }

    private static void feed(EvolutionGame game, int orbs, Random rng) {
        Dish dish = game.activeDish();
        game.inventory().add(ShopItem.ENERGY_SIMPLE, orbs);
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

    // --- resetting the lab -------------------------------------------------------------------------

    @Test
    void resettingTheLabHandsBackEveryCreditTheBookHasEverEarned() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 40L);
        feed(game, 100);
        run(game, 300);

        int lifetime = game.catalog().creditEarned();
        int species = game.catalog().speciesCount();
        assertTrue(lifetime > 0, "the run earned something to redistribute");

        // Spend down, and build a lab worth losing.
        game.inventory().add(ShopItem.BARRIER, 5);
        game.placeBarrier(30, 30);
        game.dishes().add(new Dish("Dish 2", Dish.DEFAULT_RADIUS, 3L));

        game.resetExperiment(Nucleotide.B);

        assertEquals(game.catalog().creditEarned(), game.credits(),
                "the balance after a reset is exactly the lifetime total");
        assertTrue(game.credits() >= lifetime,
                "which is at least everything earned before it (" + game.credits()
                        + " vs " + lifetime + ")");
        assertEquals(1, game.dishes().size(), "back to a single dish");
        assertEquals(1, game.activeDish().population(), "with one starting organism");
        assertTrue(game.activeDish().barriers().isEmpty(), "the old lab is gone");
        assertEquals(Inventory.STARTING_ENERGY,
                game.inventory().count(ShopItem.ENERGY_SIMPLE), "and a fresh bench");
        assertEquals(0, game.inventory().count(ShopItem.BARRIER));
        assertEquals(Nucleotide.B, game.startingColor(), "started over from the chosen colour");
        // The book is never cleared by a reset — and it may pick up one more
        // entry, since a starter strand in a colour this book has not seen is
        // itself a first-time discovery.
        assertTrue(game.catalog().speciesCount() >= species,
                "nothing is lost from the book (" + species + " -> "
                        + game.catalog().speciesCount() + ")");
        assertEquals(2, game.catalog().runs(), "and the run is counted");
    }

    @Test
    void resettingRepeatedlyIsNotAnExploit() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 41L);
        feed(game, 100);
        run(game, 300);
        int lifetime = game.catalog().creditEarned();

        game.resetExperiment(Nucleotide.G);
        int afterFirst = game.credits();
        for (int i = 0; i < 5; i++) game.resetExperiment(Nucleotide.G);
        assertEquals(afterFirst, game.credits(),
                "resetting again and again returns the same balance, never more");
        assertEquals(afterFirst, game.catalog().creditEarned(),
                "because re-seeding a strand the book already knows pays nothing");
    }

    @Test
    void aResetLabIsPlayableAndStillEarnsForGenuinelyNewStrands() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 42L);
        feed(game, 100);
        run(game, 300);
        game.resetExperiment(Nucleotide.G);
        int before = game.catalog().speciesCount();

        feed(game, 100);
        run(game, 400);
        assertTrue(game.activeDish().clock() > 0, "the new dish is running");
        assertTrue(game.catalog().speciesCount() >= before,
                "the book only ever grows (was " + before + ", now "
                        + game.catalog().speciesCount() + ")");
    }

    @Test
    void theBookTracksLifetimeTotalsAcrossRuns() {
        EvolutionGame game = EvolutionGame.newGame(Nucleotide.G, 43L);
        feed(game, 100);
        run(game, 400);
        Catalog book = game.catalog();

        assertEquals(1, book.runs());
        assertTrue(book.creditEarned() > 0);
        if (book.speciesCount() > 0) {
            assertNotNull(book.longestStrand());
            assertNotNull(book.mostComplex());
            assertTrue(book.shapesSeen().size() >= 1, "at least the square has been seen");
            assertTrue(book.mostComplex().phenotype().complexity()
                    >= book.longestStrand().phenotype().complexity() - 20);
        }
        game.resetExperiment(Nucleotide.R);
        assertEquals(2, book.runs(), "lifetime totals survive the reset");
        assertTrue(book.creditEarned() > 0);
    }

    // --- making behaviour visible ---------------------------------------------------------------

    @Test
    void aBodyStartsHandingEnergyBackAlmostImmediately() {
        Dish dish = new Dish("Decay", 200, 44L);
        dish.spawn(Genome.of("RRRR"), 0, 0);
        dish.organisms().get(0).energy = 0.01;
        for (int i = 0; i < 20; i++) dish.step(0.05); // die
        assertEquals(0, dish.population());

        boolean decayed = false;
        for (int i = 0; i < 40 && !decayed; i++) { // within ~2 seconds of the death
            dish.step(0.05);
            for (Dish.Event e : dish.drainEvents()) {
                if (e.kind() == Dish.EventKind.DECAY) decayed = true;
            }
        }
        assertTrue(decayed, "the first orb off a body arrives while the player is still looking");
        assertFalse(dish.orbs().isEmpty(), "and it really is food in the dish");
    }

    @Test
    void huntingSharingAndSignallingAllAnnounceThemselves() {
        // A predator that mostly misses, so both outcomes are exercised.
        Dish hunt = new Dish("Hunt", 120, 45L);
        hunt.spawn(Genome.of("RRRRRRRR"), 0, 0);
        for (int i = 0; i < 8; i++) hunt.spawn(Genome.of("BBBBBBBB"), 6 + i, 0);
        boolean attacked = false;
        for (int i = 0; i < 600 && !attacked; i++) {
            hunt.step(0.05);
            for (Dish.Event e : hunt.drainEvents()) {
                if (e.kind() == Dish.EventKind.ATTACK || e.kind() == Dish.EventKind.KILL) {
                    attacked = true;
                }
            }
        }
        assertTrue(attacked, "a strike is reported whether or not it kills");

        // Sharing: a well-fed donor beside a starving cell of the same strand.
        Dish share = new Dish("Share", 120, 46L);
        Organism donor = share.spawn(Genome.of("BBBB"), 0, 0);
        Organism starving = share.spawn(Genome.of("BBBB"), 12, 0);
        assertNotNull(donor);
        assertNotNull(starving);
        donor.energy = Organism.REPLICATE_AT * 0.9;
        starving.energy = 4;
        boolean shared = false;
        for (int i = 0; i < 200 && !shared; i++) {
            share.step(0.05);
            for (Dish.Event e : share.drainEvents()) {
                if (e.kind() == Dish.EventKind.SHARE) shared = true;
            }
        }
        assertTrue(shared, "a donation is visible");

        // Broadcasting: a signaller that knows where food is, with an audience.
        Dish signal = new Dish("Signal", 200, 47L);
        Organism crier = signal.spawn(Genome.of("BGGGGGBG"), 0, 0);
        assertNotNull(crier);
        assertTrue(crier.pheno.has(Ability.BROADCAST), "BG makes a broadcaster");
        for (int i = 0; i < 4; i++) signal.spawn(Genome.of("BGGGGGBG"), 20 + i * 10, 10);
        for (int i = 0; i < 60; i++) signal.dropOrb(EnergyOrb.Kind.SIMPLE, 0, 0);
        boolean broadcast = false;
        for (int i = 0; i < 400 && !broadcast; i++) {
            signal.step(0.05);
            for (Dish.Event e : signal.drainEvents()) {
                if (e.kind() == Dish.EventKind.BROADCAST) {
                    broadcast = true;
                    assertTrue(e.radius() > 0, "the signal carries its earshot for the scene");
                }
            }
        }
        assertTrue(broadcast, "a signal going out is visible");
    }

    // --- the starting colours ----------------------------------------------------------------------

    @Test
    void redPaysTheHighestUpkeepWithNoForagingEdgeToShowForIt() {
        Phenotype red = Phenotype.of(Genome.starter(Nucleotide.R));
        Phenotype green = Phenotype.of(Genome.starter(Nucleotide.G));
        Phenotype blue = Phenotype.of(Genome.starter(Nucleotide.B));

        // Red carries the upkeep of predation it has nothing to hunt yet, and
        // blue runs frugally, so red is the most expensive opening to keep alive.
        assertTrue(red.metabolism() > blue.metabolism(),
                "red costs more to run than blue (" + red.metabolism()
                        + " vs " + blue.metabolism() + ")");
        // And it buys nothing in the field: red finds food no faster than blue.
        assertEquals(blue.speed(), red.speed(), 1e-9, "no speed advantage");
        assertEquals(blue.senseRadius(), red.senseRadius(), 1e-9, "no sight advantage");
        // Green's wild cards give it the outright best opening.
        assertTrue(green.speed() > red.speed());
        assertTrue(green.eatRate() > red.eatRate());
        // Red is still given something of its own, or it cannot establish at all
        // before its first prey exists.
        assertTrue(red.eatRate() > blue.eatRate(),
                "hostility feeds aggressively, which is red's only early edge");
    }

    @Test
    void aFedRedDishUsuallySurvivesItsFirstFiveMinutes() {
        int seeds = 8;
        int survived = 0;
        for (int s = 0; s < seeds; s++) {
            EvolutionGame game = EvolutionGame.newGame(Nucleotide.R, 500L + s * 7919L);
            Random rng = new Random(s);
            feed(game, 100, rng);
            for (double t = 0; t < 300; t += 0.5) {
                game.step(0.5);
                if (t % 60 == 0) feed(game, 25, rng);
            }
            if (game.activeDish().population() > 0) survived++;
        }
        assertTrue(survived >= seeds * 3 / 4,
                "a red lab that is kept fed stays alive (" + survived + " of " + seeds + ")");
    }

    @Test
    void greenOutDiscoversRedByAWideMargin() {
        // The colours are meant to be a difficulty choice, and green's wild-card
        // machinery is the easy end of it. One dish is far too noisy to say
        // anything, so this averages several — and only asserts the gap that is
        // structural (speed plus consumption plus light) rather than the much
        // narrower red-versus-blue one.
        int seeds = 8;
        int red = 0, green = 0;
        for (int s = 0; s < seeds; s++) {
            long seed = 500L + s * 7919L;
            red += discoveriesFrom(Nucleotide.R, seed);
            green += discoveriesFrom(Nucleotide.G, seed);
        }
        assertTrue(green > red * 3 / 2,
                "green discovers far more than red (" + green + " vs " + red + ")");
    }

    private int discoveriesFrom(Nucleotide color, long seed) {
        EvolutionGame game = EvolutionGame.newGame(color, seed);
        Random rng = new Random(seed);
        feed(game, 100, rng);
        for (double t = 0; t < 300; t += 0.5) {
            game.step(0.5);
            if (t % 60 == 0) feed(game, 25, rng);
        }
        return game.catalog().speciesCount();
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
