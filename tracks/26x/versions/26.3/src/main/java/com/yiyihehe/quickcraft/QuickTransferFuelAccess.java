package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

final class QuickTransferFuelAccess {
    private QuickTransferFuelAccess() {
    }

    static boolean isFurnaceFuel(Minecraft client, ItemStack stack) {
        return stack.has(DataComponents.COOKING_FUEL);
    }
}
