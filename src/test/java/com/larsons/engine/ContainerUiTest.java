package com.larsons.engine;

import com.larsons.engine.entity.Inventory;
import com.larsons.engine.entity.ItemRegistry;
import com.larsons.engine.entity.ItemStack;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.level.Level;
import com.larsons.engine.ui.ContainerPanel;
import org.junit.jupiter.api.Test;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chest/barrel UI rework: the container panel opens beside the player's
 * inventory panel (side by side, no overlap), and open/close play as
 * animation states rather than the panel popping in and out.
 */
class ContainerUiTest {

    private static final int VW = 1280, VH = 720;

    private static Level chestLevel() {
        Level lvl = Level.empty("chest-ui", 8, 8, 32);
        lvl.setTile(3, 3, lvl.blocks.get("chest").id());
        return lvl;
    }

    private static ContainerPanel openPanel(Level lvl) {
        ContainerPanel panel = new ContainerPanel(lvl, 3, 3, "Chest", ItemRegistry.standard());
        panel.tick(1.0); // play the opening animation to its end
        return panel;
    }

    // --- input simulation ------------------------------------------------------------

    private static final Component SOURCE = new Canvas(); // events need a non-null source

    private static InputManager click(int x, int y) {
        InputManager input = new InputManager();
        input.mousePressed(new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
        input.newFrame();
        return input;
    }

    private static InputManager press(int keyCode) {
        InputManager input = new InputManager();
        input.keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED));
        input.newFrame();
        return input;
    }

    /** Screen centre of a container slot, derived from the panel's public bounds. */
    private static int[] slotCentre(ContainerPanel panel, int slot) {
        int[] b = panel.bounds(VW, VH);
        int gx = b[0] + 20, gy = b[1] + 56; // grid origin inside the background
        return new int[]{gx + (slot % 6) * 52 + 23, gy + (slot / 6) * 52 + 23};
    }

    // --- side-by-side layout ---------------------------------------------------------

    @Test
    void containerPanelSitsBesideTheInventoryPanelWithoutOverlap() {
        ContainerPanel panel = openPanel(chestLevel());

        int invLeft = ContainerPanel.pairedInventoryLeft(VW);
        int invRight = invLeft + ContainerPanel.INVENTORY_PANEL_W;
        int[] b = panel.bounds(VW, VH);

        assertTrue(b[0] >= invRight, "container panel must start right of the inventory panel");
        assertEquals(invRight + ContainerPanel.PANEL_GAP, b[0],
                "panels sit a fixed gap apart");
        assertTrue(invLeft >= 0 && b[0] + b[2] <= VW,
                "the pair fits inside a default-size viewport");
    }

    @Test
    void pairedLayoutStaysOnScreenInSmallWindows() {
        assertEquals(8, ContainerPanel.pairedInventoryLeft(640),
                "small windows clamp the pair to the left edge instead of centring off-screen");
    }

    // --- open/close animation states ---------------------------------------------------

    @Test
    void panelPlaysOpeningThenOpenThenClosingThenClosed() {
        ContainerPanel panel = new ContainerPanel(chestLevel(), 3, 3, "Chest",
                ItemRegistry.standard());

        assertEquals(ContainerPanel.State.OPENING, panel.state());
        assertTrue(panel.openness() < 0.01, "a fresh panel starts closed");
        assertFalse(panel.interactive(), "no input while the lid is swinging open");

        panel.tick(0.1);
        double mid = panel.openness();
        assertTrue(mid > 0 && mid < 1, "openness ramps during the opening state");

        panel.tick(0.2);
        assertEquals(ContainerPanel.State.OPEN, panel.state());
        assertEquals(1.0, panel.openness(), 1e-9);
        assertTrue(panel.interactive());

        panel.beginClose();
        assertEquals(ContainerPanel.State.CLOSING, panel.state());
        assertFalse(panel.interactive());
        panel.tick(0.08);
        assertTrue(panel.openness() < 1, "openness ramps back down while closing");
        assertFalse(panel.closed());

        panel.tick(0.2);
        assertTrue(panel.closed(), "the finished close animation reports closed");
        assertEquals(0.0, panel.openness(), 1e-9);
    }

    @Test
    void inputIsIgnoredUntilTheOpeningAnimationFinishes() {
        Level lvl = chestLevel();
        ContainerPanel panel = new ContainerPanel(lvl, 3, 3, "Chest", ItemRegistry.standard());
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("apple", 5);

        assertFalse(panel.update(press(KeyEvent.VK_Q), inv, -1, VW, VH),
                "Q must not deposit while the panel is still opening");
        assertTrue(lvl.openContainer(3, 3).isEmpty());

        panel.tick(1.0);
        assertTrue(panel.update(press(KeyEvent.VK_Q), inv, -1, VW, VH),
                "once open, Q stashes the selected stack");
        assertEquals("apple", lvl.openContainer(3, 3).get(0).key);
    }

    // --- moving stacks between the two panels ------------------------------------------

    @Test
    void clickingAnEmptySlotDepositsTheCursorHeldStackFirst() {
        Level lvl = chestLevel();
        ContainerPanel panel = openPanel(lvl);
        Inventory inv = new Inventory(ItemRegistry.standard());
        inv.add("apple", 5);  // hotbar slot 0, selected
        inv.add("stone", 7);  // hotbar slot 1
        inv.move(1, 10);      // carried on the inventory cursor from slot 10

        int[] c = slotCentre(panel, 0);
        assertTrue(panel.update(click(c[0], c[1]), inv, 10, VW, VH));

        List<ItemStack> box = lvl.openContainer(3, 3);
        assertEquals(1, box.size());
        assertEquals("stone", box.get(0).key, "the held stack deposits, not the hotbar one");
        assertEquals(7, box.get(0).count);
        assertNull(inv.slot(10), "the held stack left the inventory");
        assertEquals("apple", inv.slot(0).key, "the selected hotbar stack stays put");
    }

    @Test
    void clickingAStoredStackTakesItIntoTheInventory() {
        Level lvl = chestLevel();
        lvl.openContainer(3, 3).add(new ItemStack("bread", 3));
        ContainerPanel panel = openPanel(lvl);
        Inventory inv = new Inventory(ItemRegistry.standard());

        int[] c = slotCentre(panel, 0);
        assertTrue(panel.update(click(c[0], c[1]), inv, -1, VW, VH));
        assertTrue(lvl.openContainer(3, 3).isEmpty());
        assertEquals("bread", inv.slot(0).key);
        assertEquals(3, inv.slot(0).count);
    }

    @Test
    void panelContainsItsBackgroundButNotTheInventorySide() {
        ContainerPanel panel = openPanel(chestLevel());
        int[] b = panel.bounds(VW, VH);
        assertTrue(panel.contains(b[0] + 5, b[1] + 5, VW, VH));
        assertFalse(panel.contains(ContainerPanel.pairedInventoryLeft(VW) + 5,
                b[1] + 5, VW, VH), "the paired inventory panel is not this panel");
    }
}
