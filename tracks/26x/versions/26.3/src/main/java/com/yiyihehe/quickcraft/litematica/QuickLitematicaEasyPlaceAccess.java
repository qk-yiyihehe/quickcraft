package com.yiyihehe.quickcraft.litematica;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/** 26.3 工具类型判定适配（基于 ItemTags）。 */
final class QuickLitematicaEasyPlaceAccess {
    private QuickLitematicaEasyPlaceAccess() {
    }

    static boolean isAxe(ItemStack stack) {
        return stack.is(ItemTags.AXES);
    }

    static boolean isShovel(ItemStack stack) {
        return stack.is(ItemTags.SHOVELS);
    }

    static boolean isHoe(ItemStack stack) {
        return stack.is(ItemTags.HOES);
    }
}
