package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import java.util.List;

final class QuickLitematicaMaterialInventory {
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
