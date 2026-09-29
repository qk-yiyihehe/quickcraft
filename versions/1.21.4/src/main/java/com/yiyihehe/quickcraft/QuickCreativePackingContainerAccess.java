package com.yiyihehe.quickcraft;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 1.21.4+ 的容器物品写入 API：使用 Container 数据组件保存槽位内容。 */
final class QuickCreativePackingContainerAccess {
    private QuickCreativePackingContainerAccess() {
    }

    static ItemStack fill(
            MinecraftClient client,
            ItemStack contents,
            Item containerItem,
            LootableContainerBlockEntity container
    ) {
        List<ItemStack> stacks = new ArrayList<>(container.size());
        for (int slot = 0; slot < container.size(); slot++) {
            stacks.add(contents.copy());
        }

        ItemStack result = containerItem.getDefaultStack();
        result.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
        return result;
    }
}
