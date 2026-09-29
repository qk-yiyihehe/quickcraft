package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/** 1.21.3 的创造物品提交 API：提交前刷新客户端快捷栏并广播界面内容。 */
final class QuickCreativePackingResultAccess {
    private QuickCreativePackingResultAccess() {
    }

    static void submit(MinecraftClient client, ClientPlayerEntity player, ItemStack result) {
        int selectedSlot = player.getInventory().selectedSlot;
        player.getInventory().setStack(selectedSlot, result.copy());
        client.interactionManager.clickCreativeStack(result, 36 + selectedSlot);
        player.playerScreenHandler.sendContentUpdates();
    }
}
