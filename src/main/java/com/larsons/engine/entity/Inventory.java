package com.larsons.engine.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player inventory, ported from the Side-Scroller engine's Minecraft-style
 * inventory: a fixed grid of slots whose first row is the quick-access
 * hotbar. Stacks merge up to the item's {@code maxStack}; the hotbar
 * selection is what mouse actions use (place block / swing weapon / eat).
 *
 * <p>The original was 8×4 + a separate 5-slot hotbar; here the hotbar is the
 * first {@value #HOTBAR} slots of the same grid, which removes a whole class
 * of "move between bars" edge cases while looking identical in the UI.
 */
public final class Inventory {

    public static final int COLS = 8;
    public static final int ROWS = 4;
    public static final int SIZE = COLS * ROWS;
    public static final int HOTBAR = 5;

    private final ItemStack[] slots = new ItemStack[SIZE];
    private final ItemRegistry registry;
    private int selected; // hotbar index [0, HOTBAR)

    public Inventory(ItemRegistry registry) {
        this.registry = registry;
    }

    public ItemStack slot(int i) {
        return i >= 0 && i < SIZE ? slots[i] : null;
    }

    public int selectedIndex() {
        return selected;
    }

    public void select(int hotbarIndex) {
        if (hotbarIndex >= 0 && hotbarIndex < HOTBAR) selected = hotbarIndex;
    }

    /** Scroll the hotbar selection (mouse wheel), wrapping. */
    public void scrollSelect(int delta) {
        selected = Math.floorMod(selected + delta, HOTBAR);
    }

    /** The stack under the hotbar selection, or {@code null}. */
    public ItemStack selectedStack() {
        return slots[selected];
    }

    public ItemDef selectedDef() {
        ItemStack s = selectedStack();
        return s == null ? null : registry.get(s.key);
    }

    /**
     * Add items, merging into existing stacks first, then filling empty slots
     * (hotbar first so pickups are immediately usable). Returns the count that
     * did <em>not</em> fit.
     */
    public int add(String key, int count) {
        ItemDef def = registry.get(key);
        if (def == null || count <= 0) return count;
        int left = count;
        for (int i = 0; i < SIZE && left > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && s.key.equals(def.key()) && s.count < def.maxStack()) {
                int take = Math.min(left, def.maxStack() - s.count);
                s.count += take;
                left -= take;
            }
        }
        for (int i = 0; i < SIZE && left > 0; i++) {
            if (slots[i] == null) {
                int take = Math.min(left, def.maxStack());
                slots[i] = new ItemStack(def.key(), take);
                left -= take;
            }
        }
        return left;
    }

    /**
     * Move the stack in {@code from} onto {@code to}: merge when both hold the
     * same item (up to {@code maxStack}, leftovers stay behind), swap
     * otherwise. This is the one primitive the inventory UI needs — pick a
     * stack up, put it down — and, online, the {@code invmove} request the
     * server applies to its authoritative copy. Returns whether anything moved.
     */
    public boolean move(int from, int to) {
        if (from < 0 || from >= SIZE || to < 0 || to >= SIZE || from == to) return false;
        ItemStack src = slots[from];
        if (src == null) return false;
        ItemStack dst = slots[to];
        ItemDef def = registry.get(src.key);
        if (dst != null && dst.key.equals(src.key) && def != null) {
            int take = Math.min(src.count, def.maxStack() - dst.count);
            if (take <= 0) return false;
            dst.count += take;
            src.count -= take;
            if (src.count <= 0) slots[from] = null;
        } else {
            slots[from] = dst;
            slots[to] = src;
        }
        return true;
    }

    /**
     * Take up to {@code count} items out of one slot (dropping into the
     * world). Returns how many were actually removed.
     */
    public int removeAt(int slot, int count) {
        if (slot < 0 || slot >= SIZE || count <= 0) return 0;
        ItemStack s = slots[slot];
        if (s == null) return 0;
        int take = Math.min(count, s.count);
        s.count -= take;
        if (s.count <= 0) slots[slot] = null;
        return take;
    }

    /** Remove up to {@code count} of an item; returns how many were removed. */
    public int remove(String key, int count) {
        int removed = 0;
        for (int i = SIZE - 1; i >= 0 && removed < count; i--) {
            ItemStack s = slots[i];
            if (s != null && s.key.equals(key)) {
                int take = Math.min(count - removed, s.count);
                s.count -= take;
                removed += take;
                if (s.count <= 0) slots[i] = null;
            }
        }
        return removed;
    }

    /** Consume one item from the selected stack (eat food, place block…). */
    public boolean consumeSelected() {
        ItemStack s = slots[selected];
        if (s == null) return false;
        if (--s.count <= 0) slots[selected] = null;
        return true;
    }

    public int totalOf(String key) {
        int n = 0;
        for (ItemStack s : slots) {
            if (s != null && s.key.equals(key)) n += s.count;
        }
        return n;
    }

    public void clear() {
        for (int i = 0; i < SIZE; i++) slots[i] = null;
    }

    // --- serialization (save files + the multiplayer "inv" message) ------------

    public List<Object> toList() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack s = slots[i];
            if (s == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("i", i);
            m.put("k", s.key);
            m.put("n", s.count);
            list.add(m);
        }
        return list;
    }

    /** Replace contents from {@link #toList()} data (unknown items skipped). */
    public void fromList(List<Object> list) {
        clear();
        if (list == null) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            int i = m.get("i") instanceof Number n ? n.intValue() : -1;
            String key = m.get("k") instanceof String s ? s : null;
            int count = m.get("n") instanceof Number n ? n.intValue() : 0;
            if (i >= 0 && i < SIZE && registry.get(key) != null && count > 0) {
                slots[i] = new ItemStack(registry.get(key).key(), count);
            }
        }
    }
}
