package com.yiyihehe.quickcraft;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 1.21 的容器物品写入 API：通过方块实体生成旧式物品 NBT。 */
final class QuickCreativePackingContainerAccess {
    private QuickCreativePackingContainerAccess() {
    }

    static ItemStack fill(
            MinecraftClient client,
            ItemStack contents,
            Item containerItem,
            LootableContainerBlockEntity container
    ) {
        for (int slot = 0; slot < container.size(); slot++) {
            container.setStack(slot, contents.copy());
        }

        ItemStack result = containerItem.getDefaultStack();
        container.setStackNbt(result, client.world.getRegistryManager());
        return result;
    }
}
