package com.larsons.engine.autobattler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The item registry: five components (PvE creep drops) and the full 15-entry
 * combination table — every pair of components makes a named combined item
 * whose stats are roughly the sum of its parts doubled. Two components on the
 * same unit combine automatically, exactly like TFT.
 *
 * <p>On top of the stat items sit the <em>elemental relics</em>, the item side
 * of the {@link Element} system: infusion charms add an attack element, the
 * Radiation Core converts a unit's attacks to pure Radiation, and the Prism
 * Ward grants resistance to everything. Relics also drop from creep rounds
 * (a minority of drops) and never combine.
 */
public final class AutoItems {

    private static final Map<String, AutoItem> ITEMS = new LinkedHashMap<>();
    /** "a+b" (sorted component keys) -> combined item key. */
    private static final Map<String, String> COMBOS = new LinkedHashMap<>();
    private static final List<String> RELICS = new ArrayList<>();

    public static final String SWORD = "sword";
    public static final String BOW = "bow";
    public static final String ROD = "rod";
    public static final String VEST = "vest";
    public static final String BELT = "belt";

    static {
        component(SWORD, "Iron Sword", 15, 0, 0, 0, 0, new Color(210, 210, 220));
        component(BOW, "Ash Bow", 0, 0.15, 0, 0, 0, new Color(170, 210, 120));
        component(ROD, "Runed Rod", 0, 0, 20, 0, 0, new Color(120, 160, 235));
        component(VEST, "Chain Vest", 0, 0, 0, 20, 0, new Color(200, 175, 110));
        component(BELT, "Giant's Belt", 0, 0, 0, 0, 150, new Color(200, 120, 120));

        combo("deathblade", "Deathblade", SWORD, SWORD, 40, 0, 0, 0, 0);
        combo("swiftblade", "Swiftblade", SWORD, BOW, 20, 0.25, 0, 0, 0);
        combo("spellblade", "Spellblade", SWORD, ROD, 20, 0, 25, 0, 0);
        combo("duelist_plate", "Duelist Plate", SWORD, VEST, 20, 0, 0, 25, 0);
        combo("berserker_axe", "Berserker Axe", SWORD, BELT, 20, 0, 0, 0, 200);
        combo("rapid_quiver", "Rapid Quiver", BOW, BOW, 0, 0.40, 0, 0, 0);
        combo("storm_bow", "Storm Bow", BOW, ROD, 0, 0.20, 25, 0, 0);
        combo("ranger_mail", "Ranger Mail", BOW, VEST, 0, 0.20, 0, 25, 0);
        combo("hunter_harness", "Hunter Harness", BOW, BELT, 0, 0.20, 0, 0, 200);
        combo("archmage_rod", "Archmage Rod", ROD, ROD, 0, 0, 50, 0, 0);
        combo("warded_plate", "Warded Plate", ROD, VEST, 0, 0, 25, 25, 0);
        combo("vital_staff", "Vital Staff", ROD, BELT, 0, 0, 25, 0, 200);
        combo("bulwark", "Bulwark", VEST, VEST, 0, 0, 0, 55, 0);
        combo("colossus_guard", "Colossus Guard", VEST, BELT, 0, 0, 0, 25, 250);
        combo("giants_heart", "Giant's Heart", BELT, BELT, 0, 0, 0, 0, 450);

        // Elemental relics: infusions per element, one converter, one ward.
        relic("ember_charm", "Ember Charm", Element.FIRE, AutoItem.ElementEffect.INFUSE);
        relic("cryo_shard", "Cryo Shard", Element.CRYO, AutoItem.ElementEffect.INFUSE);
        relic("volt_coil", "Volt Coil", Element.ELECTRIC, AutoItem.ElementEffect.INFUSE);
        relic("acid_vial", "Acid Vial", Element.CORROSIVE, AutoItem.ElementEffect.INFUSE);
        relic("blast_powder", "Blast Powder", Element.EXPLOSIVE, AutoItem.ElementEffect.INFUSE);
        relic("rad_core", "Radiation Core", Element.RADIATION, AutoItem.ElementEffect.CONVERT);
        relic("prism_ward", "Prism Ward", null, AutoItem.ElementEffect.WARD);
    }

    private AutoItems() {}

    private static void component(String key, String name, double ad, double as,
                                  double sp, double armor, double hp, Color color) {
        ITEMS.put(key, new AutoItem(key, name, AutoItem.Kind.COMPONENT, null, null,
                ad, as, sp, armor, hp, null, null, color));
    }

    private static void combo(String key, String name, String a, String b,
                              double ad, double as, double sp, double armor, double hp) {
        Color mix = blend(ITEMS.get(a).color, ITEMS.get(b).color);
        ITEMS.put(key, new AutoItem(key, name, AutoItem.Kind.COMBINED, a, b,
                ad, as, sp, armor, hp, null, null, mix));
        COMBOS.put(comboKey(a, b), key);
    }

    private static void relic(String key, String name, Element element,
                              AutoItem.ElementEffect effect) {
        Color color = element != null ? element.color : new Color(225, 225, 235);
        ITEMS.put(key, new AutoItem(key, name, AutoItem.Kind.RELIC, null, null,
                0, 0, 0, 0, 0, element, effect, color));
        RELICS.add(key);
    }

    private static Color blend(Color a, Color b) {
        return new Color((a.getRed() + b.getRed()) / 2,
                (a.getGreen() + b.getGreen()) / 2,
                (a.getBlue() + b.getBlue()) / 2);
    }

    private static String comboKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "+" + b : b + "+" + a;
    }

    public static AutoItem get(String key) {
        return ITEMS.get(key);
    }

    public static List<AutoItem> all() {
        return new ArrayList<>(ITEMS.values());
    }

    /** The five components PvE rounds drop. */
    public static List<String> componentKeys() {
        return List.of(SWORD, BOW, ROD, VEST, BELT);
    }

    /** The elemental relics PvE rounds occasionally drop instead. */
    public static List<String> relicKeys() {
        return List.copyOf(RELICS);
    }

    /** The combined item two components make, or {@code null} if either isn't a component. */
    public static String combine(String a, String b) {
        AutoItem ia = ITEMS.get(a);
        AutoItem ib = ITEMS.get(b);
        if (ia == null || ib == null || !ia.isComponent() || !ib.isComponent()) return null;
        return COMBOS.get(comboKey(a, b));
    }
}
