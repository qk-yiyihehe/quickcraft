package com.yiyihehe.quickcraft.litematica;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;

/** 26.1.2–26.2 工具类型判定适配。 */
final class QuickLitematicaEasyPlaceAccess {
    private QuickLitematicaEasyPlaceAccess() {
    }

    static boolean isAxe(ItemStack stack) {
        return stack.getItem() instanceof AxeItem;
    }

    static boolean isShovel(ItemStack stack) {
        return stack.getItem() instanceof ShovelItem;
    }

    static boolean isHoe(ItemStack stack) {
        return stack.getItem() instanceof HoeItem;
    }
}
