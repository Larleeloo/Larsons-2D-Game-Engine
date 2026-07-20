package com.larsons.engine;

import com.larsons.engine.demo.PaletteInfo;
import com.larsons.engine.entity.ItemDef;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.MobDef;
import com.larsons.engine.entity.MobRegistry;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;
import com.larsons.engine.world.Decor;
import com.larsons.engine.world.DecorRegistry;
import com.larsons.engine.world.SurfaceDecor;
import com.larsons.engine.world.SurfaceDecorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creative palette's item descriptions: every registered block, mob,
 * item, decoration, and surface detail describes what it does, composed from
 * its definition data (so custom creations get described the same way).
 */
@Timeout(30)
class PaletteInfoTest {

    @Test
    void everyRegisteredDefinitionHasADescription() {
        for (Block b : BlockRegistry.standard().all()) {
            if (b.isFlow()) continue; // hidden from the palette
            assertFalse(PaletteInfo.describe(b).isBlank(),
                    "block " + b.key() + " should describe itself");
        }
        for (MobDef d : MobRegistry.standard().all()) {
            assertFalse(PaletteInfo.describe(d).isBlank(),
                    "mob " + d.key() + " should describe itself");
        }
        for (ItemDef d : ItemRegistry.standard().all()) {
            assertFalse(PaletteInfo.describe(d).isBlank(),
                    "item " + d.key() + " should describe itself");
        }
        for (Decor d : DecorRegistry.standard().all()) {
            assertFalse(PaletteInfo.describe(d).isBlank(),
                    "decor " + d.key() + " should describe itself");
        }
        for (SurfaceDecor d : SurfaceDecorRegistry.standard().all()) {
            assertFalse(PaletteInfo.describe(d).isBlank(),
                    "surface decor " + d.key() + " should describe itself");
        }
    }

    @Test
    void blockDescriptionsExplainBehaviour() {
        BlockRegistry blocks = BlockRegistry.standard();
        String stone = PaletteInfo.describe(blocks.get("stone"));
        assertTrue(stone.contains("Solid"), "stone is solid terrain: " + stone);

        String water = PaletteInfo.describe(blocks.get("water"));
        assertTrue(water.contains("Liquid"), "water flows: " + water);

        Block torch = blocks.get("torch");
        if (torch != null) {
            String s = PaletteInfo.describe(torch);
            assertTrue(s.contains("light"), "torches mention their light: " + s);
        }

        Block spikes = blocks.get("spikes");
        if (spikes != null) {
            String s = PaletteInfo.describe(spikes);
            assertTrue(s.contains("Hazard"), "spikes mention contact damage: " + s);
        }
    }

    @Test
    void itemDescriptionsExplainUse() {
        ItemRegistry items = ItemRegistry.standard();
        ItemDef sword = items.get("iron_sword");
        if (sword != null) {
            String s = PaletteInfo.describe(sword);
            assertTrue(s.contains("Melee") && s.contains(String.valueOf((long) sword.damage())),
                    "weapons state their damage: " + s);
        }
        ItemDef apple = items.get("apple");
        if (apple != null) {
            String s = PaletteInfo.describe(apple);
            assertTrue(s.contains("eat"), "food explains eating: " + s);
        }
        // The movement relics do more than their category says — the
        // description covers the real passive.
        ItemDef wings = items.get("wings_of_icarus");
        if (wings != null) {
            String s = PaletteInfo.describe(wings);
            assertTrue(s.contains("mid-air"), "relics explain their passive: " + s);
        }
    }

    @Test
    void mobDescriptionsExplainTemperamentAndStats() {
        MobDef zombie = MobRegistry.standard().get("zombie");
        assertNotNull(zombie);
        String s = PaletteInfo.describe(zombie);
        assertTrue(s.contains("Hostile"), "zombies read as hostile: " + s);
        assertTrue(s.contains("HP"), "descriptions carry the mob's HP: " + s);
    }
}
