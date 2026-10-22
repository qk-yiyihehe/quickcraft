package com.yiyihehe.quickcraft.crafting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class QuickCraftWorkbenchShulkerContents {
    // 玩家槽位各保留一份，额外一份用于光标，避免长期喷射累积历史盒内容。
    private static final Contents[] CACHE = new Contents[Inventory.INVENTORY_SIZE + 1];

    private QuickCraftWorkbenchShulkerContents() {
    }

    static void clear() {
        Arrays.fill(CACHE, null);
    }

    static Contents get(ItemStack shulker) {
        return get(Inventory.INVENTORY_SIZE, shulker);
    }

    static Contents get(int inventoryIndex, ItemStack shulker) {
        ItemContainerContents container = shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        Contents cached = CACHE[inventoryIndex];
        if (cached != null && (cached.container == container || cached.container.equals(container))) {
            // 全量回包可能重建内容相同的组件；沿用统计，并记住新引用供后续快速检查。
            cached.container = container;
            return cached;
        }

        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stored : container.nonEmptyItemCopyStream().toList()) {
            stacks.add(stored);
        }
        Contents contents = new Contents(container, stacks);
        CACHE[inventoryIndex] = contents;
        return contents;
    }

    static final class Contents {
        private ItemContainerContents container;
        private final List<ItemStack> stacks;
        private final ItemStack material;
        private final int count;
        private final boolean homogeneous;
        private final int matchingCapacity;

        private Contents(ItemContainerContents container, List<ItemStack> stacks) {
            this.container = container;
            this.stacks = stacks;
            material = stacks.isEmpty() ? ItemStack.EMPTY : stacks.getFirst();
            int total = 0;
            int capacity = 0;
            boolean sameMaterial = true;
            for (ItemStack stored : stacks) {
                total += stored.getCount();
                if (ItemStack.isSameItemSameComponents(material, stored)) {
                    capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
                } else {
                    sameMaterial = false;
                }
            }
            count = total;
            homogeneous = sameMaterial;
            matchingCapacity = capacity;
        }

        ItemStack material() {
            return material;
        }

        int count() {
            return count;
        }

        boolean homogeneous() {
            return homogeneous;
        }

        boolean containsOnly(ItemStack output) {
            return count == 0 || homogeneous && ItemStack.isSameItemSameComponents(material, output);
        }

        int capacity(ItemStack output) {
            int capacity = 0;
            if (ItemStack.isSameItemSameComponents(material, output)) {
                capacity = matchingCapacity;
            } else if (!homogeneous) {
                for (ItemStack stored : stacks) {
                    if (ItemStack.isSameItemSameComponents(stored, output)) {
                        capacity += Math.max(0, stored.getMaxStackSize() - stored.getCount());
                    }
                }
            }
            return QuickCraftWorkbenchShulkerOutput.calculateCapacity(output.getMaxStackSize(), stacks.size(), capacity);
        }
    }
}
