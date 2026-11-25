package com.yiyihehe.quickcraft.sort;

import java.util.Objects;
import net.minecraft.item.ItemStack;

public final class QuickCrossBoxSortStackKey {
    private final ItemStack template;
    private final int hash;

    public QuickCrossBoxSortStackKey(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("An empty stack has no identity");
        } else {
            this.template = stack.copyWithCount(1);
            this.hash = 31 * this.template.getItem().hashCode() + this.template.getComponents().hashCode();
        }
    }

    public ItemStack template() {
        return this.template.copy();
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(this.template, stack);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof QuickCrossBoxSortStackKey key && ItemStack.areItemsAndComponentsEqual(
                template,
                key.template
        );
    }

    public int hashCode() {
        return this.hash;
    }

    public String toString() {
        return Objects.toString(this.template);
    }
}
