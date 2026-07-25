package com.larsons.engine.evolution;

import com.larsons.engine.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a save writes the parts of the game there are thousands of: the cells,
 * the food, the bodies, the waste and the lists of strands the books keep.
 *
 * <p>Two things have to hold at once. The file has to be <em>small</em> — a dish
 * at its caps carries {@value Dish#MAX_ORGANISMS} cells and
 * {@value Dish#MAX_ORBS} orbs, and a JSON object per item turns a lab into
 * megabytes of field names — and it has to come back <em>whole</em>, including
 * saves written by builds from before the packed format existed.
 */
@Timeout(60)
class PackedSaveTest {

    // --- rows come back as what went in ---------------------------------------------

    @Test
    void aCellSurvivesTheRoundTripThroughAPackedSave() {
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, 4L);
        Organism o = dish.spawn(Genome.of("RGBRGB"), 40, -80);
        assertNotNull(o);
        o.vx = -12.5;
        o.vy = 3.25;
        o.energy = 61.5;
        o.age = 33.75;
        o.generation = 17;
        o.colonyId = 4;
        o.venom = 2.5;
        o.memory.add(new double[]{120, -60});
        o.memory.add(new double[]{-15, 90});

        Organism back = reload(dish).organisms().get(0);
        assertEquals(o.id, back.id, "the id survives, so a carried tool still finds its cell");
        assertEquals("RGBRGB", back.genome.sequence());
        assertEquals(o.x, back.x, 0.01);
        assertEquals(o.y, back.y, 0.01);
        assertEquals(o.vx, back.vx, 0.01);
        assertEquals(o.vy, back.vy, 0.01);
        assertEquals(o.energy, back.energy, 0.01);
        assertEquals(o.age, back.age, 0.01);
        assertEquals(17, back.generation);
        assertEquals(4, back.colonyId);
        assertEquals(2.5, back.venom, 0.01);
        assertEquals(2, back.memory.size(), "and it still remembers where the food was");
        assertEquals(120, back.memory.get(0)[0], 1.0);
        assertEquals(-60, back.memory.get(0)[1], 1.0);
    }

    @Test
    void everythingElseInTheDishSurvivesToo() {
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, 5L);
        dish.dropOrb(EnergyOrb.Kind.SIMPLE, 10, 10);
        dish.dropOrb(EnergyOrb.Kind.COMPLEX, -20, 30);
        dish.orbs().get(0).energy = 7.5; // half eaten
        dish.corpses().add(new Dish.Corpse(5, -5, 22.5, 1.4, new java.awt.Color(200, 120, 40)));
        dish.wastes().add(new Dish.Waste(-40, 60, 9.25));
        dish.wastes().get(0).age = 12.5;

        Dish back = reload(dish);
        assertEquals(2, back.orbs().size());
        assertEquals(EnergyOrb.Kind.SIMPLE, back.orbs().get(0).kind);
        assertEquals(7.5, back.orbs().get(0).energy, 0.01, "a half-eaten orb keeps what is left");
        assertEquals(EnergyOrb.Kind.COMPLEX, back.orbs().get(1).kind);
        assertEquals(EnergyOrb.Kind.COMPLEX.defaultEnergy(), back.orbs().get(1).energy, 0.01,
                "and an untouched one comes back full even though the row leaves it out");

        assertEquals(1, back.corpses().size());
        assertEquals(22.5, back.corpses().get(0).energy, 0.01);
        assertEquals(1.4, back.corpses().get(0).rate, 0.01);
        assertEquals(new java.awt.Color(200, 120, 40).getRGB(), back.corpses().get(0).color.getRGB(),
                "the strand's colour is what the body is drawn in");
        assertEquals(1, back.wastes().size());
        assertEquals(9.25, back.wastes().get(0).amount, 0.01);
        assertEquals(12.5, back.wastes().get(0).age, 0.01);
    }

    @Test
    void aFullDishOfClonesComesBackCellForCell() {
        Random rng = new Random(9);
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, 6L);
        for (int i = 0; i < Dish.MAX_ORGANISMS; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rng.nextDouble()) * 300;
            Organism o = dish.spawn(Genome.random(rng, 4 + rng.nextInt(6)),
                    Math.cos(a) * r, Math.sin(a) * r);
            if (o == null) break;
            o.energy = rng.nextDouble() * 70;
        }
        Dish back = reload(dish);
        assertEquals(dish.population(), back.population());
        for (int i = 0; i < dish.population(); i++) {
            assertEquals(dish.organisms().get(i).genome.sequence(),
                    back.organisms().get(i).genome.sequence(),
                    "cell " + i + " kept its DNA");
            assertEquals(dish.organisms().get(i).x, back.organisms().get(i).x, 0.01);
            assertEquals(dish.organisms().get(i).energy, back.organisms().get(i).energy, 0.01);
        }
    }

    // --- and the file is much smaller for it ----------------------------------------

    @Test
    void aStrandIsWrittenOnceHoweverManyCellsWearIt() {
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, 7L);
        for (int i = 0; i < 60; i++) {
            dish.spawn(Genome.of(i % 2 == 0 ? "RRGGBB" : "GGGRRR"), i - 30, i - 30);
        }
        Object block = dish.toMap().get("organisms");
        List<String> strands = Packed.words(block, "strands");
        assertEquals(List.of("RRGGBB", "GGGRRR"), strands,
                "a bloom of two strands is a dictionary of two, not sixty");
        assertEquals(60, Packed.rows(block).size());

        String json = Json.stringify(dish.toMap());
        assertEquals(1, count(json, "RRGGBB"), "the sequence itself appears exactly once");
    }

    @Test
    void aPackedDishIsFarSmallerThanTheObjectFormItReplaces() {
        Random rng = new Random(3);
        Dish dish = new Dish("Dish 1", Dish.DEFAULT_RADIUS, 8L);
        Genome[] strands = new Genome[8];
        for (int i = 0; i < strands.length; i++) strands[i] = Genome.random(rng, 6 + rng.nextInt(10));
        for (int i = 0; i < Dish.MAX_ORGANISMS; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rng.nextDouble()) * 320;
            Organism o = dish.spawn(strands[rng.nextInt(strands.length)],
                    Math.cos(a) * r, Math.sin(a) * r);
            if (o == null) break;
            o.vx = rng.nextGaussian() * 20;
            o.vy = rng.nextGaussian() * 20;
            o.energy = rng.nextDouble() * 70;
            o.age = rng.nextDouble() * 120;
        }
        for (int i = 0; i < 800; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rng.nextDouble()) * 320;
            dish.dropOrb(EnergyOrb.Kind.SIMPLE, Math.cos(a) * r, Math.sin(a) * r);
        }

        int packed = Json.stringify(dish.toMap()).length();
        int objects = Json.stringify(objectForm(dish)).length();
        assertTrue(packed * 3 < objects,
                "a full dish should pack to well under a third of the object form (was "
                        + packed + " vs " + objects + " bytes)");
    }

    /** The dish's dense lists in the object-per-item form the packing replaced. */
    private static Map<String, Object> objectForm(Dish dish) {
        List<Object> cells = new ArrayList<>();
        for (Organism o : dish.organisms()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", o.id);
            c.put("dna", o.genome.sequence());
            c.put("x", o.x);
            c.put("y", o.y);
            c.put("vx", o.vx);
            c.put("vy", o.vy);
            c.put("energy", o.energy);
            c.put("age", o.age);
            c.put("generation", o.generation);
            c.put("colony", o.colonyId);
            cells.add(c);
        }
        List<Object> orbs = new ArrayList<>();
        for (EnergyOrb orb : dish.orbs()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", orb.kind.name());
            e.put("x", orb.x);
            e.put("y", orb.y);
            e.put("energy", orb.energy);
            orbs.add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organisms", cells);
        m.put("orbs", orbs);
        return m;
    }

    @Test
    void numbersAreWrittenNoLongerThanTheyNeedToBe() {
        assertEquals("0", Packed.text(0, 2));
        assertEquals("0", Packed.text(-0.0, 2), "a negative zero is just zero");
        assertEquals("0", Packed.text(0.0004, 2), "and so is anything below the last decimal");
        assertEquals("24", Packed.text(24.0, 2), "a whole number carries no decimal point");
        assertEquals("-12.35", Packed.text(-12.3456789, 2));
        assertEquals("12.3", Packed.text(12.2999, 2), "trailing zeros are trimmed");
        assertEquals("13", Packed.text(12.999, 2));
        assertEquals("0", Packed.text(Double.NaN, 2), "and nothing ever writes a NaN into a save");
        assertEquals("0", Packed.text(Double.POSITIVE_INFINITY, 2));
        assertFalse(Packed.text(1e-9, 6).contains("E"), "never scientific notation");
    }

    @Test
    void aRowStopsAtItsLastMeaningfulField() {
        String plain = new Packed.Row().add(7).num(1.5, 2).add(-1, -1).num(0, 2, 0).toString();
        assertEquals("7 1.5", plain, "a cell in no colony with no venom simply stops early");

        String venomous = new Packed.Row().add(7).num(1.5, 2).add(-1, -1).num(3, 2, 0).toString();
        assertEquals("7 1.5 -1 3", venomous,
                "but a field that matters brings the defaults before it back");
    }

    // --- nothing a save can contain is fatal ----------------------------------------

    @Test
    void anUnreadableRowCostsThatOneCellAndNothingElse() {
        List<String> strands = List.of("RGRG");
        assertNull(Organism.fromRow("", strands), "an empty row is not a cell");
        assertNull(Organism.fromRow("3", strands), "nor is one with no strand");
        assertNull(Organism.fromRow("3 9 0 0", strands), "nor one pointing past the dictionary");
        assertNull(Organism.fromRow("3 x y z", strands), "nor one that is not numbers");

        Organism o = Organism.fromRow("3 0 nonsense 12", strands);
        assertNotNull(o, "a cell with one bad field is still a cell");
        assertEquals(0, o.x, 1e-9, "the unreadable field falls back to its default");
        assertEquals(12, o.y, 1e-9);
    }

    @Test
    void aHandEditedSaveWithJunkRowsStillOpens() {
        Map<String, Object> dish = new LinkedHashMap<>();
        dish.put("name", "Dish 1");
        dish.put("organisms", Packed.block(Organism.ROW_FORMAT, "strands", List.of("RGRG", "NOPE"),
                List.of("1 0 10 20 0 0 30 1 2", "2 1 0 0", "", "garbage")));
        Dish back = Dish.fromMap(dish, 12L);
        assertEquals(1, back.organisms().size(), "only the one readable cell is restored");
        assertEquals("RGRG", back.organisms().get(0).genome.sequence());
        assertEquals(30, back.organisms().get(0).energy, 0.01);
    }

    // --- saves from before the packed format ----------------------------------------

    @Test
    void aDishSavedAsObjectsStillLoads() {
        // Exactly the shape builds before the packed format wrote.
        String json = """
                {
                  "name": "Dish 1",
                  "radius": 360.0,
                  "organisms": [
                    {"id": 3, "dna": "RGBR", "x": 12.0, "y": -4.0, "energy": 41.0,
                     "generation": 2, "colony": 1, "venom": 1.5,
                     "memory": [[100.0, 50.0]]}
                  ],
                  "orbs": [{"kind": "COMPLEX", "x": 3.0, "y": 4.0, "energy": 12.0}],
                  "corpses": [{"x": 1.0, "y": 2.0, "energy": 8.0, "rate": 1.0, "rgb": -100}],
                  "waste": [{"x": 5.0, "y": 6.0, "amount": 3.0, "age": 2.0}]
                }
                """;
        Dish dish = Dish.fromMap(Json.asObject(Json.parse(json)), 13L);
        assertEquals(1, dish.population());
        Organism o = dish.organisms().get(0);
        assertEquals("RGBR", o.genome.sequence());
        assertEquals(41, o.energy, 1e-9);
        assertEquals(1, o.colonyId);
        assertEquals(1.5, o.venom, 1e-9);
        assertEquals(1, o.memory.size());
        assertEquals(1, dish.orbs().size());
        assertEquals(EnergyOrb.Kind.COMPLEX, dish.orbs().get(0).kind);
        assertEquals(12, dish.orbs().get(0).energy, 1e-9);
        assertEquals(1, dish.corpses().size());
        assertEquals(8, dish.corpses().get(0).energy, 1e-9);
        assertEquals(1, dish.wastes().size());
        assertEquals(3, dish.wastes().get(0).amount, 1e-9);

        // And re-saving it writes the packed form from then on.
        assertTrue(Packed.isBlock(dish.toMap().get("organisms")));
    }

    @Test
    void theBooksReadBothFormsOfTheirStrandLists() {
        Catalog catalog = new Catalog();
        catalog.record(Genome.of("RGRG"), "Dish 1", 0);
        catalog.record(Genome.of("GGBB"), "Dish 1", 3);
        catalog.recordCombination("RGRG+GGBB");

        Map<String, Object> saved = catalog.toMap();
        assertEquals("RGRG GGBB", saved.get("species"),
                "a long game's thousands of strands are one line, not one line each");

        Catalog back = Catalog.fromMap(
                Json.asObject(Json.parse(Json.stringify(saved))), new History());
        assertEquals(catalog.sequences(), back.sequences());
        assertEquals(catalog.combinations(), back.combinations());

        // The array-of-strings and array-of-objects an older save wrote.
        String legacy = """
                {"species": ["RGRG", "GGBB"],
                 "combinations": [{"signature": "RGRG+GGBB", "at": 1700000000000}],
                 "creditEarned": 12}
                """;
        Catalog old = Catalog.fromMap(Json.asObject(Json.parse(legacy)), new History());
        assertEquals(catalog.sequences(), old.sequences());
        assertEquals(catalog.combinations(), old.combinations());
        assertEquals(12, old.creditEarned());
    }

    @Test
    void theHistoryReadsBothFormsOfItsCombinations() {
        History history = new History();
        history.recordCombination("RGRG+GGBB");
        History back = History.fromMap(Json.asObject(Json.parse(Json.stringify(history.toMap()))));
        assertEquals(history.combinations(), back.combinations());

        History old = History.fromMap(Json.asObject(Json.parse(
                "{\"combinations\": [{\"signature\": \"RGRG+GGBB\", \"at\": 1700000000000}]}")));
        assertEquals(history.combinations(), old.combinations());
    }

    @Test
    void aTimestampIsReadAsWhatItIs() {
        // Rows write whole seconds; a value from a build that wrote milliseconds
        // has to land on the same day rather than in 1970.
        assertEquals(1_700_000_000_000L, Packed.epochMillis(1_700_000_000L), "seconds");
        assertEquals(1_700_000_000_000L, Packed.epochMillis(1_700_000_000_000L), "milliseconds");
        assertEquals(0, Packed.epochMillis(0), "and nothing at all is still nothing");
    }

    // --- the permanent record --------------------------------------------------------

    @Test
    void aDiscoveryKeepsOnlyWhatTheStrandDoesNotAlreadySay() {
        SpeciesRecord rec = SpeciesRecord.of(Genome.of("RGBRGB"), "Dish 3", 12);
        String row = rec.toRow(2);
        assertEquals("RGBRGB " + (rec.discoveredAt / 1000) + " 2 12", row,
                "the name and the credit are what the rules say, so they are not written");

        SpeciesRecord back = SpeciesRecord.fromRow(row, List.of("Dish 1", "Dish 2", "Dish 3"));
        assertNotNull(back);
        assertEquals("RGBRGB", back.sequence);
        assertEquals(rec.name, back.name, "the name comes back out of the strand");
        assertEquals(rec.credit, back.credit, "and so does what it paid");
        assertEquals("Dish 3", back.dish, "the dish name comes out of the dictionary");
        assertEquals(12, back.generation);
        assertEquals(rec.discoveredAt / 1000, back.discoveredAt / 1000, "first seen, to the second");
        // The whole decoded half is recomputed, which is why it need not be stored.
        assertEquals(rec.phenotype().shape(), back.phenotype().shape());
        assertEquals(rec.phenotype().abilities(), back.phenotype().abilities());
        assertEquals(rec.phenotype().complexity(), back.phenotype().complexity());
    }

    @Test
    void aRecordThatDisagreesWithTheRulesKeepsWhatItSaid() {
        // Nothing in the game renames a species or reprices a discovery, but a
        // hand-edited file from an older build might have, and migrating it
        // should not quietly overwrite what it said.
        SpeciesRecord odd = new SpeciesRecord("RGRG", "Nessie horribilis", 1_700_000_000_000L,
                "Petri dish of doom", 3, 999);
        SpeciesRecord back = SpeciesRecord.fromRow(odd.toRow(0), List.of("Petri dish of doom"));
        assertNotNull(back);
        assertEquals("Nessie horribilis", back.name, "the odd name survives the round trip");
        assertEquals(999, back.credit);
        assertEquals("Petri dish of doom", back.dish, "spaces and all");
        assertEquals(1_700_000_000_000L, back.discoveredAt);
    }

    @Test
    void theWholeCollectionRoundTripsThroughOneFile() {
        History history = new History();
        history.record(SpeciesRecord.of(Genome.of("GGGG"), "Dish 1", 0));
        history.record(SpeciesRecord.of(Genome.of("RGBRGB"), "Dish 2", 7));
        history.record(SpeciesRecord.of(Genome.of("BBRRGG"), "Dish 2", 21));
        history.recordCombination("GGGG+RGBRGB");
        history.unlock(Achievement.PREDATOR);
        history.countGame();

        History back = History.fromMap(Json.asObject(Json.parse(Json.stringify(history.toMap()))));
        assertEquals(history.speciesCount(), back.speciesCount());
        for (SpeciesRecord rec : history.allSpecies()) {
            SpeciesRecord got = back.species(rec.sequence);
            assertNotNull(got, rec.sequence + " is still on the record");
            assertEquals(rec.dish, got.dish);
            assertEquals(rec.generation, got.generation);
            assertEquals(rec.name, got.name);
        }
        assertEquals(history.combinations(), back.combinations());
        assertEquals(history.unlockedAchievements(), back.unlockedAchievements());
        assertEquals(history.gamesPlayed(), back.gamesPlayed());
        assertEquals(history.lifetimeCredit(), back.lifetimeCredit());
    }

    @Test
    void aHistoryFromAnOlderBuildStillOpens() {
        // The index a build wrote when discoveries lived in their own files:
        // achievements as an array, combinations as objects, no species at all.
        String legacy = """
                {"version": 1,
                 "combinations": [{"signature": "RGRG+GGBB", "at": 1700000000000}],
                 "achievements": ["FIRST_LIFE", "PREDATOR", "NO_SUCH_ACHIEVEMENT"],
                 "gamesPlayed": 3,
                 "lifetimeCredit": 812}
                """;
        History old = History.fromMap(Json.asObject(Json.parse(legacy)));
        assertEquals(0, old.speciesCount(), "its discoveries are in the folder beside it");
        assertEquals(Set.of("RGRG+GGBB"), old.combinations());
        assertTrue(old.isUnlocked(Achievement.FIRST_LIFE));
        assertTrue(old.isUnlocked(Achievement.PREDATOR));
        assertEquals(2, old.unlockedAchievements().size(), "an achievement that no longer exists is skipped");
        assertEquals(3, old.gamesPlayed());
        assertEquals(812, old.lifetimeCredit());
    }

    @Test
    void anUnreadableDiscoveryRowCostsThatOneDiscovery() {
        List<String> dishes = List.of("Dish 1");
        assertNull(SpeciesRecord.fromRow("", dishes), "an empty row is not a discovery");
        assertNull(SpeciesRecord.fromRow("NOPE 1700000000", dishes), "nor is an impossible strand");

        SpeciesRecord rec = SpeciesRecord.fromRow("RGRG", dishes);
        assertNotNull(rec, "a strand on its own is enough to be a discovery");
        assertEquals(SpeciesRecord.nameFor(Genome.of("RGRG")), rec.name);
        assertEquals(0, rec.generation);
    }

    // --- helpers ---------------------------------------------------------------------

    /** Write a dish out as the game would, then read it back off the "disk". */
    private static Dish reload(Dish dish) {
        String json = Json.stringify(dish.toMap());
        return Dish.fromMap(Json.asObject(Json.parse(json)), dish.seed());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
