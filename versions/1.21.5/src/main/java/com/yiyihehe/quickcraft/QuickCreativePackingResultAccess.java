package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/** 1.21.5+ 的创造物品提交 API：使用库存公开的选中槽位访问器。 */
final class QuickCreativePackingResultAccess {
    private QuickCreativePackingResultAccess() {
    }

    static void submit(MinecraftClient client, ClientPlayerEntity player, ItemStack result) {
        int selectedSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setStack(selectedSlot, result.copy());
        client.interactionManager.clickCreativeStack(result, 36 + selectedSlot);
        player.playerScreenHandler.sendContentUpdates();
    }
}
