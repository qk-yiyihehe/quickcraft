package com.yiyihehe.quickcraft;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import java.util.List;

/** 26.1.2 的 Bundle API 访问：使用带初始内容的构造器和旧遍历方法。 */
final class QuickCreativePackingBundleAccess {
    private QuickCreativePackingBundleAccess() {
    }

    static BundleContents.Mutable newBuilder() {
        return new BundleContents.Mutable(BundleContents.EMPTY);
    }

    static List<ItemStack> itemCopies(BundleContents contents) {
        return contents.itemCopyStream().toList();
    }
}
