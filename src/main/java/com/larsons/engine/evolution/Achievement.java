package com.larsons.engine.evolution;

/**
 * A one-off discovery badge, in the spirit of the storefront achievement lists
 * the design document points at: something the player's dishes either have
 * produced or have not.
 *
 * <p>Every one of these is unlocked by the simulation surprising you, not by
 * following instructions — nothing here can be bought, and nothing is awarded
 * for time spent. The hint is deliberately a nudge rather than a recipe.
 */
public enum Achievement {

    FIRST_LIFE("First Light", "Catalog your very first organism.",
            "Anything at all that divides once will do it."),
    TEN_SPECIES("Busy Slide", "Catalog ten distinct strands.",
            "Keep the food coming and let them copy."),
    FIFTY_SPECIES("Field Guide", "Catalog fifty distinct strands.",
            "Sloppy copying explores faster than careful copying."),
    HUNDRED_SPECIES("Taxonomist", "Catalog a hundred distinct strands.",
            "Mutagen makes short work of this."),

    PREDATOR("Something Bit Back", "Catalog a strand that evolved predation.",
            "Two reds in a row."),
    VENOMOUS("Slow Poison", "Catalog a strand that evolved venom.",
            "Red, then blue."),
    ALTRUIST("Share and Share Alike", "Catalog a strand that evolved sharing.",
            "Two blues in a row."),
    KIN_GUARD("Family Business", "Catalog a strand that defends its own kin.",
            "Blue, then red."),
    BROADCASTER("Word Gets Around", "Catalog a strand that broadcasts food locations.",
            "Blue, then green."),
    GOURMAND("Strong Stomach", "Catalog a strand that digests complex energy.",
            "Two greens in a row — then buy it something worth eating."),
    ARSONIST("Running Hot", "Catalog an exothermic strand.",
            "Red, then green."),
    CRYOPHILE("Running Cold", "Catalog an endothermic strand.",
            "Green, then blue."),
    GLOWWORM("Bioluminescence", "Catalog a strand with light emission of 6 or more.",
            "Greens in slot 3 — and a red right after doubles it."),
    TOOLMAKER("Opposable Everything", "Catalog a strand that can use tools.",
            "A long strand that reads GRG late on, and can recognise patterns."),
    COLONIST("Better Together", "Catalog a multicellular strand.",
            "Three blues in a row, in a strand of fourteen or more."),
    SYMBIOSIS("Two Become One", "Watch different strands bind into one colony.",
            "Two different multicellular strands have to meet."),

    LONG_STRAND("Verbose", "Catalog a strand twenty-four nucleotides long.",
            "Insertions lengthen DNA; they are likelier than deletions."),
    MAXIMAL("As Long As It Gets", "Catalog a strand at the maximum length of 48.",
            "Patience, and a lot of mutagen."),
    POLYMORPH("Every Body", "Catalog all eight body shapes.",
            "Shape follows what a strand commits to — and needs six nucleotides to show."),
    FULL_SPECTRUM("Balanced Diet", "Catalog a strand that is at least 30% of each colour.",
            "Hostility, wild cards and altruism, all at once."),
    DEEP_LINEAGE("Fiftieth Cousin", "Catalog a strand fifty generations from the one you seeded.",
            "Let a single dish run undisturbed for a long time."),

    WEALTHY("Grant Funded", "Bank 500 credits.",
            "Complex discoveries pay far better than plain ones."),
    TEEMING("Standing Room Only", "Have 200 organisms alive in one dish at once.",
            "Feed heavily, and keep the temperature comfortable."),
    LABORATORY("Six Slides", "Run six Petri dishes at once.",
            "Dishes are the most expensive thing in the shop for a reason.");

    private final String title;
    private final String description;
    private final String hint;

    Achievement(String title, String description, String hint) {
        this.title = title;
        this.description = description;
        this.hint = hint;
    }

    public String title() { return title; }

    public String description() { return description; }

    public String hint() { return hint; }

    /** The achievement a newly unlocked ability corresponds to, if any. */
    public static Achievement forAbility(Ability a) {
        return switch (a) {
            case PREDATION -> PREDATOR;
            case VENOM -> VENOMOUS;
            case SHARING -> ALTRUIST;
            case KIN_DEFENSE -> KIN_GUARD;
            case BROADCAST -> BROADCASTER;
            case COMPLEX_DIGESTION -> GOURMAND;
            case EXOTHERMY -> ARSONIST;
            case ENDOTHERMY -> CRYOPHILE;
            case TOOL_USE -> TOOLMAKER;
            case MULTICELLULAR -> COLONIST;
        };
    }
}
