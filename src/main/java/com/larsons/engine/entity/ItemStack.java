package com.larsons.engine.entity;

/**
 * A quantity of one item in an inventory slot. Deliberately tiny — the
 * {@link ItemDef} (via its key) carries all behaviour.
 */
public final class ItemStack {
    public final String key;
    public int count;

    public ItemStack(String key, int count) {
        this.key = key;
        this.count = count;
    }
}
