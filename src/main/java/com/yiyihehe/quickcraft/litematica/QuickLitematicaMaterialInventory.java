package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentMap;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class QuickLitematicaMaterialInventory {
    private record StackKey(Item item, ComponentMap components) {
        static StackKey of(ItemStack stack) { return new StackKey(stack.getItem(), stack.getComponents()); }
    }

    private QuickLitematicaMaterialInventory() { }

    static int countAvailable(List<ItemStack> targets) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) { return 0; }
        int count = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            count += countStack(inventory.getStack(slot), targets, 0);
        }
        return count;
    }

    static int[] countAvailableFor(List<ItemStack> targets) {
        int[] result = new int[targets.size()];
        if (targets.isEmpty()) { return result; }
        var player = MinecraftClient.getInstance().player;
        if (player == null) { return result; }
        Map<StackKey, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            countStack(inventory.getStack(slot), counts, 0, 1);
        }
        for (int i = 0; i < targets.size(); i++) {
            result[i] = counts.getOrDefault(StackKey.of(targets.get(i)), 0);
        }
        return result;
    }

    private static void countStack(ItemStack stack, Map<StackKey, Integer> counts, int depth, int multiplier) {
        if (stack.isEmpty()) { return; }
        int amount = multiplier * stack.getCount();
        counts.merge(StackKey.of(stack), amount, Integer::sum);
        if (depth < 4 && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            var stored = InventoryUtils.getStoredItems(stack);
            if (stored != null) {
                for (ItemStack nested : stored) { countStack(nested, counts, depth + 1, amount); }
            }
        }
    }

    private static int countStack(ItemStack stack, List<ItemStack> targets, int depth) {
        if (stack.isEmpty()) { return 0; }
        int count = targets.stream().anyMatch(target -> ItemStack.areItemsAndComponentsEqual(target, stack))
                ? stack.getCount() : 0;
        // 与容器材料和末影箱统计保持相同上限，兼容服务器提供的嵌套潜影盒。
        if (depth < 4 && stack.getItem() instanceof BlockItem item && item.getBlock() instanceof ShulkerBoxBlock) {
            var stored = InventoryUtils.getStoredItems(stack);
            if (stored != null) {
                for (ItemStack nested : stored) { count += stack.getCount() * countStack(nested, targets, depth + 1); }
            }
        }
        return count;
    }
}
