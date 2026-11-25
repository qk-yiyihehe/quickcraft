package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

public final class QuickCrossBoxSortEmptyMerges {
    private QuickCrossBoxSortEmptyMerges() {}

    public static List<Merge> plan(PlayerInventory inventory) {
        return plan(inventory, 36, QuickCrossBoxSortProtection::isLocked);
    }

    public static List<Merge> plan(net.minecraft.inventory.Inventory inventory) {
        return plan(inventory, inventory.size(), slot -> false);
    }

    private static List<Merge> plan(
            net.minecraft.inventory.Inventory inventory,
            int size,
            java.util.function.IntPredicate locked
    ) {
        List<Merge> merges = new ArrayList<>();
        ItemStack[] simulated = new ItemStack[size];

        for (int i = 0; i < simulated.length; ++i) {
            simulated[i] = inventory.getStack(i).copy();
        }

        for (int target = 0; target < simulated.length; ++target) {
            ItemStack targetStack = simulated[target];
            if (!locked.test(target) && targetStack.contains(net.minecraft.component.DataComponentTypes.CONTAINER)
                    && QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(targetStack)) {
                int max = targetStack.getMaxCount();
                if (max > targetStack.getCount()) {
                    for (int source = target + 1; source < simulated.length; ++source) {
                        ItemStack sourceStack = simulated[source];
                        if (!locked.test(source)
                                && sourceStack.contains(net.minecraft.component.DataComponentTypes.CONTAINER)
                                && QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(sourceStack)
                                && ItemStack.areItemsAndComponentsEqual(targetStack, sourceStack)) {
                            int amount = Math.min(sourceStack.getCount(), max - targetStack.getCount());
                            merges.add(new Merge(source, target, new QuickCrossBoxSortStackKey(sourceStack), amount));
                            targetStack.increment(amount);
                            sourceStack.decrement(amount);
                            if (sourceStack.isEmpty()) {
                                simulated[source] = ItemStack.EMPTY;
                            }

                            if (targetStack.getCount() >= max) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        return List.copyOf(merges);
    }

    public record Merge(int sourceInventorySlot, int targetInventorySlot, QuickCrossBoxSortStackKey key, int amount) {
    }
}
