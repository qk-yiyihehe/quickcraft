package com.yiyihehe.quickcraft.crafting;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class QuickCraftWorkbenchShulkerContents {
    // 玩家槽位各保留一份，额外一份用于光标，避免长期喷射累积历史盒内容。
    private static final Contents[] CACHE = new Contents[PlayerInventory.MAIN_SIZE + 1];

    private QuickCraftWorkbenchShulkerContents() {
    }

    static void clear() {
        Arrays.fill(CACHE, null);
    }

    static Contents get(ItemStack shulker) {
        return get(PlayerInventory.MAIN_SIZE, shulker);
    }

    static Contents get(int inventoryIndex, ItemStack shulker) {
        ContainerComponent container = shulker.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT);
        Contents cached = CACHE[inventoryIndex];
        if (cached != null && (cached.container == container || cached.container.equals(container))) {
            // 全量回包可能重建内容相同的组件；沿用统计，并记住新引用供后续快速检查。
            cached.container = container;
            return cached;
        }

        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stored : container.iterateNonEmpty()) {
            stacks.add(stored);
        }
        Contents contents = new Contents(container, stacks);
        CACHE[inventoryIndex] = contents;
        return contents;
    }

    static final class Contents {
        private ContainerComponent container;
        private final List<ItemStack> stacks;
        private final ItemStack material;
        private final int count;
        private final boolean homogeneous;
        private final int matchingCapacity;

        private Contents(ContainerComponent container, List<ItemStack> stacks) {
            this.container = container;
            this.stacks = stacks;
            material = stacks.isEmpty() ? ItemStack.EMPTY : stacks.getFirst();
            int total = 0;
            int capacity = 0;
            boolean sameMaterial = true;
            for (ItemStack stored : stacks) {
                total += stored.getCount();
                if (ItemStack.areItemsAndComponentsEqual(material, stored)) {
                    capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
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
            return count == 0 || homogeneous && ItemStack.areItemsAndComponentsEqual(material, output);
        }

        int capacity(ItemStack output) {
            int capacity = 0;
            if (ItemStack.areItemsAndComponentsEqual(material, output)) {
                capacity = matchingCapacity;
            } else if (!homogeneous) {
                for (ItemStack stored : stacks) {
                    if (ItemStack.areItemsAndComponentsEqual(stored, output)) {
                        capacity += Math.max(0, stored.getMaxCount() - stored.getCount());
                    }
                }
            }
            return QuickCraftWorkbenchShulkerOutput.calculateCapacity(output.getMaxCount(), stacks.size(), capacity);
        }
    }
}
