package com.yiyihehe.quickcraft.sort;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class QuickCrossBoxSortEmptyMerges {
    private QuickCrossBoxSortEmptyMerges() {}

    public static List<Merge> plan(Inventory inventory) {
        return plan(inventory, 36, QuickCrossBoxSortProtection::isLocked);
    }

    public static List<Merge> plan(net.minecraft.world.Container inventory) {
        return plan(inventory, inventory.getContainerSize(), slot -> false);
    }

    private static List<Merge> plan(
            net.minecraft.world.Container inventory,
            int size,
            java.util.function.IntPredicate locked
    ) {
        List<Merge> merges = new ArrayList<>();
        ItemStack[] simulated = new ItemStack[size];

        for (int i = 0; i < simulated.length; ++i) {
            simulated[i] = inventory.getItem(i).copy();
        }

        for (int target = 0; target < simulated.length; ++target) {
            ItemStack targetStack = simulated[target];
            if (!locked.test(target) && targetStack.has(net.minecraft.core.component.DataComponents.CONTAINER)
                    && QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(targetStack)) {
                int max = targetStack.getMaxStackSize();
                if (max > targetStack.getCount()) {
                    for (int source = target + 1; source < simulated.length; ++source) {
                        ItemStack sourceStack = simulated[source];
                        if (!locked.test(source)
                                && sourceStack.has(net.minecraft.core.component.DataComponents.CONTAINER)
                                && QuickCrossBoxSortInventory.isOrdinaryEmptyShulker(sourceStack)
                                && ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
                            int amount = Math.min(sourceStack.getCount(), max - targetStack.getCount());
                            merges.add(new Merge(source, target, new QuickCrossBoxSortStackKey(sourceStack), amount));
                            targetStack.grow(amount);
                            sourceStack.shrink(amount);
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
