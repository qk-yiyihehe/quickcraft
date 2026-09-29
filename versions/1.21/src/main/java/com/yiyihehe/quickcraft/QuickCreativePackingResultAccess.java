package com.yiyihehe.quickcraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

/** 1.21 的创造物品提交 API：沿用原版交互管理器的直接提交路径。 */
final class QuickCreativePackingResultAccess {
    private QuickCreativePackingResultAccess() {
    }

    static void submit(MinecraftClient client, ClientPlayerEntity player, ItemStack result) {
        client.interactionManager.clickCreativeStack(result, 36 + player.getInventory().selectedSlot);
    }
}
