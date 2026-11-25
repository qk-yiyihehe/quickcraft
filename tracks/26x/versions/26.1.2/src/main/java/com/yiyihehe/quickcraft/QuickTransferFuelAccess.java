package com.yiyihehe.quickcraft;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

final class QuickTransferFuelAccess {
    private QuickTransferFuelAccess() {}

    static boolean isFurnaceFuel(Minecraft client, ItemStack stack) {
        return client.level != null && client.level.fuelValues().isFuel(stack);
    }
}
