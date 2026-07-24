package com.larsons.engine.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The genetics rule book: how a strand of coloured nucleotides decodes into an
 * organism, and how it copies itself imperfectly.
 *
 * <p>These are the rules the whole game rests on, including the design
 * document's worked example — "a green nucleotide in slot 3 of a DNA sequence
 * means light emission of 3. If it's followed by red, then it's doubled."
 */
@Timeout(60)
class GenomeTest {

    // --- the wild-card wheel ---------------------------------------------------------

    @Test
    void greenInSlotThreeEncodesLightEmissionOfThree() {
        // BBGB: the only green sits in slot 3, and is followed by blue, which
        // halves it — so isolate the rule with a strand where slot 4 is green
        // too? No: use a following green, which adds one. Simplest is a strand
        // where the green is last, taking no modifier at all.
        Phenotype p = Phenotype.of(Genome.of("BBG"));
        assertEquals(3.0, p.trait(Trait.LIGHT_EMISSION), 1e-9,
                "a green in slot 3 with nothing after it is light emission of exactly 3");
    }

    @Test
    void redAfterAGreenDoublesIt() {
        Phenotype p = Phenotype.of(Genome.of("BBGR"));
        assertEquals(6.0, p.trait(Trait.LIGHT_EMISSION), 1e-9,
                "green in slot 3 followed by red is doubled to 6");
    }

    @Test
    void blueAfterAGreenHalvesItAndRefinesDigestion() {
        Phenotype p = Phenotype.of(Genome.of("BBGB"));
        assertEquals(1.5, p.trait(Trait.LIGHT_EMISSION), 1e-9, "blue tempers the green");
        assertTrue(p.trait(Trait.EFFICIENCY) > 0, "and refines digestion instead");
    }

    @Test
    void greenAfterAGreenChainsOn() {
        Phenotype p = Phenotype.of(Genome.of("BBGG"));
        assertEquals(4.0, p.trait(Trait.LIGHT_EMISSION), 1e-9, "slot 3 green + 1 for chaining");
        assertEquals(4.0, p.trait(Trait.HEAT_TOLERANCE), 1e-9, "slot 4 green is heat tolerance");
    }

    @Test
    void theWildCardWheelRepeatsEveryEightSlots() {
        assertSame(Trait.SPEED, Trait.forSlot(1));
        assertSame(Trait.LIGHT_EMISSION, Trait.forSlot(3));
        assertSame(Trait.PATTERN_RECOGNITION, Trait.forSlot(8));
        assertSame(Trait.SPEED, Trait.forSlot(9), "the wheel wraps");
        assertSame(Trait.LIGHT_EMISSION, Trait.forSlot(11));

        // A later slot expresses the same trait at a bigger magnitude, which is
        // what makes a longer strand worth having.
        double early = Phenotype.of(Genome.of("BBG")).trait(Trait.LIGHT_EMISSION);
        double late = Phenotype.of(Genome.of("BBBBBBBBBBG")).trait(Trait.LIGHT_EMISSION);
        assertEquals(3.0, early, 1e-9);
        assertEquals(11.0, late, 1e-9);
    }

    // --- red, blue and the pair rules ---------------------------------------------------

    @Test
    void redEncodesHostilityAndBlueEncodesAltruism() {
        Phenotype red = Phenotype.of(Genome.of("RRRR"));
        Phenotype blue = Phenotype.of(Genome.of("BBBB"));
        assertEquals(1.0, red.hostility(), 1e-9);
        assertEquals(0.0, red.altruism(), 1e-9);
        assertEquals(1.0, blue.altruism(), 1e-9);
        assertTrue(red.attack() > blue.attack(), "hostility is what makes a strand dangerous");
    }

    @Test
    void everyOrderedPairMeansSomething() {
        assertTrue(Phenotype.of(Genome.of("RR")).has(Ability.PREDATION));
        assertTrue(Phenotype.of(Genome.of("RG")).has(Ability.EXOTHERMY));
        assertTrue(Phenotype.of(Genome.of("RB")).has(Ability.VENOM));
        assertTrue(Phenotype.of(Genome.of("GG")).has(Ability.COMPLEX_DIGESTION));
        assertTrue(Phenotype.of(Genome.of("GB")).has(Ability.ENDOTHERMY));
        assertTrue(Phenotype.of(Genome.of("BR")).has(Ability.KIN_DEFENSE));
        assertTrue(Phenotype.of(Genome.of("BG")).has(Ability.BROADCAST));
        assertTrue(Phenotype.of(Genome.of("BB")).has(Ability.SHARING));
        // GR is the magnitude modifier, and unlocks nothing on its own.
        assertTrue(Phenotype.of(Genome.of("GR")).abilities().isEmpty());
    }

    @Test
    void toolUseAndMulticellularityNeedLongStrands() {
        // GRG early on is not enough, however well-placed.
        assertFalse(Phenotype.of(Genome.of("GRGBBB")).has(Ability.TOOL_USE));
        // Late GRG in a strand that also recognises patterns unlocks it: the
        // green in slot 8 is the pattern recognition, and GRG starts in slot 9.
        Genome late = Genome.of("BBBBBBBGGRG");
        Phenotype toolUser = Phenotype.of(late);
        assertEquals(9, late.indexOfMotif("GRG"), "the motif has to sit late in the strand");
        assertTrue(toolUser.trait(Trait.PATTERN_RECOGNITION) >= 4,
                "slot 8 is pattern recognition, which the rule requires");
        assertTrue(toolUser.has(Ability.TOOL_USE));

        assertFalse(Phenotype.of(Genome.of("BBBRRR")).has(Ability.MULTICELLULAR),
                "BBB alone is not enough in a short strand");
        assertTrue(Phenotype.of(Genome.of("BBBRRRRRRRRRRRR")).has(Ability.MULTICELLULAR),
                "BBB in a strand of 14+ binds into colonies");
    }

    // --- colour, shape and identity -------------------------------------------------------

    @Test
    void colourIsTheBalanceOfTheStrand() {
        assertEquals(235, Genome.of("RRRR").color().getRed());
        assertEquals(0, Genome.of("RRRR").color().getBlue());
        assertEquals(235, Genome.of("BBBB").color().getBlue());
        assertEquals(0, Genome.of("GGGG").color().getRed());
        // An even mix reads as white: equal parts of everything.
        var even = Genome.of("RGB").color();
        assertEquals(even.getRed(), even.getGreen());
        assertEquals(even.getGreen(), even.getBlue());
    }

    @Test
    void everythingStartsAsASquareAndDifferentiatesLater() {
        for (Nucleotide n : Nucleotide.values()) {
            Genome starter = Genome.starter(n);
            assertEquals(4, starter.length(), "starters are four nucleotides long");
            assertSame(BodyShape.SQUARE, Phenotype.of(starter).shape(),
                    "the first organism is always the plain square");
        }
        // A long hostile strand grows the body that goes with hunting.
        assertSame(BodyShape.TRIANGLE, Phenotype.of(Genome.of("RRRRRRGB")).shape());
        // And a long altruistic one grows the body that goes with cooperating.
        assertSame(BodyShape.HEXAGON, Phenotype.of(Genome.of("BBBBBBBBBBBBBB")).shape());
    }

    @Test
    void aStrandIsItsOwnIdentity() {
        assertEquals(Genome.of("RGB"), Genome.of("RGB"));
        assertNotEquals(Genome.of("RGB"), Genome.of("RBG"), "order matters");
        assertEquals(0, Genome.of("RGB").distanceTo(Genome.of("RGB")));
        assertEquals(1, Genome.of("RGB").distanceTo(Genome.of("RGG")));
        assertEquals(2, Genome.of("RGB").distanceTo(Genome.of("RGBRG")));
        assertTrue(Genome.isValid("RGBRGB"));
        assertFalse(Genome.isValid("RGBX"));
        assertFalse(Genome.isValid(""));
    }

    @Test
    void decodingTheSameStrandTwiceIsTheSameOrganism() {
        assertSame(Phenotype.of(Genome.of("RGBRGB")), Phenotype.of(Genome.of("RGBRGB")),
                "decoded strands are cached, so the simulation never re-decodes");
    }

    // --- replication ------------------------------------------------------------------------

    @Test
    void replicationCopiesImperfectlyAndCanLengthenTheStrand() {
        Random rng = new Random(1234);
        Genome parent = Genome.of("RGBRGB");
        int changed = 0;
        int lengthened = 0;
        for (int i = 0; i < 400; i++) {
            Genome child = parent.replicate(rng, 0.3);
            if (!child.equals(parent)) changed++;
            if (child.length() > parent.length()) lengthened++;
            assertTrue(child.length() >= Genome.MIN_LENGTH);
            assertTrue(child.length() <= Genome.MAX_LENGTH);
        }
        assertTrue(changed > 100, "a variable strand miscopies often (was " + changed + "/400)");
        assertTrue(lengthened > 20, "insertions lengthen DNA (was " + lengthened + "/400)");
    }

    @Test
    void aFaithfulStrandMostlyBreedsTrue() {
        Random rng = new Random(99);
        Genome parent = Genome.of("RBRBRBRBRBRBRBRBRBRB"); // no greens in the tail
        Phenotype p = Phenotype.of(parent);
        assertEquals(0.10, p.variability(), 1e-9, "a green-free tail copies as faithfully as possible");

        int identical = 0;
        for (int i = 0; i < 400; i++) {
            if (parent.replicate(rng, p.variability()).equals(parent)) identical++;
        }
        // Long strands have to be able to hold onto what they evolved, or
        // nothing complex could ever persist.
        assertTrue(identical > 200, "most copies of a faithful strand are exact (was " + identical + ")");
    }

    @Test
    void offspringVariabilityIsItselfAnEvolvedTrait() {
        double faithful = Phenotype.of(Genome.of("RRRRRRRRR")).variability();
        double sloppy = Phenotype.of(Genome.of("RRRRRRGGG")).variability();
        assertTrue(sloppy > faithful,
                "greens in the tail of the strand make replication sloppier");
    }

    @Test
    void strandsNeverGrowPastTheCap() {
        Random rng = new Random(7);
        Genome g = Genome.random(rng, Genome.MAX_LENGTH);
        for (int i = 0; i < 200; i++) {
            g = g.replicate(rng, 1.0);
            assertTrue(g.length() <= Genome.MAX_LENGTH);
        }
    }

    @Test
    void complexityRewardsLongerAndMoreCapableStrands() {
        int plain = Phenotype.of(Genome.of("RRRR")).complexity();
        int rich = Phenotype.of(Genome.of("RRGBBBGRBRGGRGGGRGB")).complexity();
        assertTrue(rich > plain * 2, "a long, capable strand is worth far more as a discovery");
    }
}
