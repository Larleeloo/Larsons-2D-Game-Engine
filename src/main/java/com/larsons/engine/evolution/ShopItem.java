package com.larsons.engine.evolution;

/**
 * Everything the lab shop sells, bought with the credit earned by discovering
 * new organisms.
 *
 * <p>Items come in two shapes. <b>Consumables</b> go into the
 * {@link Inventory} as a count and are then placed in a dish by hand (energy,
 * barriers, heat, spotlights, mutagen, tool kits, starter colonies, spatula
 * transfers). <b>Instruments</b> are bought once and stay bought — the
 * thermometer, the DNA scanner and the time warp are permanent additions to the
 * microscope rig.
 */
public enum ShopItem {

    // --- consumables ------------------------------------------------------------
    ENERGY_SIMPLE("Simple energy ×25", 20, 25, Category.FOOD,
            "Twenty-five plain energy orbs to drop wherever you like."),
    ENERGY_COMPLEX("Complex energy ×10", 55, 10, Category.FOOD,
            "Rich, long-chain energy — only strands that evolved complex digestion can eat it."),
    STARTER_COLONY("Starter colony", 80, 1, Category.LIFE,
            "Three randomly-written organisms, seeded wherever you place them."),
    PETRI_DISH("New Petri dish", 400, 1, Category.LIFE,
            "A whole new slide to run a separate experiment on."),
    TRANSFER_SPATULA("Transfer spatula", 50, 1, Category.LIFE,
            "Lifts one organism from one dish into another. One use, then it is gone."),
    BARRIER("Barrier pillars ×3", 30, 3, Category.ENVIRONMENT,
            "Inert pillars that block movement and line of sight."),
    HEAT_SOURCE("Exothermic source", 60, 1, Category.ENVIRONMENT,
            "Warms the gel around it. Cooks anything without the heat tolerance for it."),
    COLD_SOURCE("Endothermic sink", 60, 1, Category.ENVIRONMENT,
            "Chills the gel around it, for the opposite pressure."),
    SPOTLIGHT("Spotlight ×2", 45, 2, Category.ENVIRONMENT,
            "Lights a dark patch of slide for 90 seconds so the cells there can forage."),
    MUTAGEN("Mutagen vial ×2", 90, 2, Category.ENVIRONMENT,
            "A cloud that triples copying errors inside it. Chaos, deliberately applied."),
    TOOL_KIT("Cell tool kit", 120, 1, Category.LIFE,
            "Drops a tool only strands that evolved tool use can pick up, share and wear out."),

    // --- permanent instruments -----------------------------------------------------
    THERMOMETER("Thermometer", 250, 0, Category.INSTRUMENT,
            "Overlays the dish's temperature field so you can see the thermal landscape."),
    DNA_SCANNER("DNA catalog scanner", 450, 0, Category.INSTRUMENT,
            "Reads any organism's exact DNA and decodes it — no more guessing."),
    TIME_WARP("Time warp dial", 350, 0, Category.INSTRUMENT,
            "Speeds the whole experiment up to 8× or slows it to a crawl.");

    /** Rough grouping, used to lay the shop out. */
    public enum Category { FOOD, LIFE, ENVIRONMENT, INSTRUMENT }

    private final String displayName;
    private final int price;
    private final int quantity;
    private final Category category;
    private final String description;

    ShopItem(String displayName, int price, int quantity, Category category, String description) {
        this.displayName = displayName;
        this.price = price;
        this.quantity = quantity;
        this.category = category;
        this.description = description;
    }

    public String displayName() { return displayName; }

    public int price() { return price; }

    /** How many uses a purchase adds; {@code 0} for permanent instruments. */
    public int quantity() { return quantity; }

    public Category category() { return category; }

    public String description() { return description; }

    /** Instruments are owned once; everything else stacks in the inventory. */
    public boolean permanent() { return category == Category.INSTRUMENT; }
}
