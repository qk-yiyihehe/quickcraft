package com.yiyihehe.quickcraft;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import java.util.List;

/** 26.1.2 的 QuickSort Bundle API 访问：使用旧的 itemCopyStream 遍历方法。 */
final class QuickSortBundleAccess {
    private QuickSortBundleAccess() {
    }

    static List<ItemStack> itemCopies(BundleContents contents) {
        return contents.itemCopyStream().toList();
    }
}
